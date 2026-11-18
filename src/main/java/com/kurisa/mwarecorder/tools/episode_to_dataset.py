#!/usr/bin/env python3
"""
Mwarecorder episode -> training dataset converter.

Turns one episode (or a directory of episodes) into flat numpy arrays that are easy to feed to
a world model or an imitation-learning pipeline.

Usage
-----
    # Convert a single episode
    python episode_to_dataset.py path/to/episode_20260101_120000

    # Convert every episode below a directory
    python episode_to_dataset.py path/to/mwarecorder_data

    # Keep more nearby-entity slots
    python episode_to_dataset.py path/to/episode --nearby-k 32

    # Overwrite an existing dataset folder
    python episode_to_dataset.py path/to/episode --force

    # Build the samples from the state stream instead of the frame index
    python episode_to_dataset.py path/to/episode --sample-source states

    # Also write one dataset per dimension (overworld / the_nether / the_end)
    python episode_to_dataset.py path/to/episode --split-by-dimension

Output (inside <episode>/dataset/)
----------------------------------
    observations.npy      [N, D_obs] float32
    actions.npy           [N, D_act] float32
    rewards.npy           [N]        float32   (zeros, placeholder for later shaping)
    dones.npy             [N]        bool      (last sample marked done)
    sample_ticks.npy      [N]        int32
    world_times.npy       [N]        int64     (game time of the current dimension)
    dimensions.npy        [N]        int32     (id into dimension_labels.json)
    dimension_labels.json {name: id}
    sample_dimensions.txt one dimension id string per sample
    dimension_segments.json contiguous runs of one dimension
    frame_paths.txt       N lines, file names relative to <episode>/frames
    events.json           {"sample_tick": [events]}
    vocab.json            item / block / entity / biome / dimension vocabularies
    obs_schema.json       meaning of every observation column
    act_schema.json       meaning of every action column
    meta.json             statistics and capture configuration
"""

from __future__ import annotations

import argparse
import json
import math
import shutil
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

try:
    import numpy as np
except ImportError:  # pragma: no cover - dependency check
    print("numpy is required: pip install numpy", file=sys.stderr)
    sys.exit(1)


# ============================================================
# Loading helpers
# ============================================================


def load_jsonl(path: Path) -> List[Dict]:
    """Reads a JSON Lines file, skipping blank lines."""
    if not path.exists():
        return []

    records: List[Dict] = []
    with open(path, "r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError:
                # A crash can leave a partially written last line; ignore it.
                continue
    return records


def load_json(path: Path) -> Optional[Dict]:
    if not path.exists():
        return None
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def find_episodes(root: Path) -> List[Path]:
    """Returns the episode directories below ``root``."""
    if (root / "state.jsonl").exists() or (root / "frames.jsonl").exists():
        return [root]

    episodes = []
    for candidate in sorted(root.iterdir()):
        if candidate.is_dir() and (
            (candidate / "state.jsonl").exists() or (candidate / "frames.jsonl").exists()
        ):
            episodes.append(candidate)
    return episodes


# ============================================================
# Vocabulary
# ============================================================


def build_vocab(states: List[Dict]) -> Dict[str, Dict[str, int]]:
    """
    Collects every type name that appears in the state stream.

    Index 0 is reserved for "unknown / empty", so vocabularies start at 1.
    """
    vocab: Dict[str, Dict[str, int]] = {
        "item": {},
        "block": {},
        "entity": {},
        "biome": {},
        "dimension": {},
    }

    def add(kind: str, name: Optional[str]) -> None:
        if not name:
            return
        table = vocab[kind]
        if name not in table:
            table[name] = len(table) + 1

    for state in states:
        player = state.get("player") or {}

        for key in ("held_item", "offhand_item"):
            item = player.get(key)
            if item:
                add("item", item.get("type"))

        look = player.get("looking_at")
        if look:
            if look.get("kind") == "block":
                add("block", (look.get("block") or {}).get("type"))
            elif look.get("kind") == "entity":
                add("entity", (look.get("entity") or {}).get("type"))

        for entity in player.get("nearby_entities") or []:
            add("entity", entity.get("type"))
            if entity.get("type") == "minecraft:item":
                add("item", entity.get("item_type"))

        add("dimension", state_dimension(state))

        environment = player.get("environment") or {}
        add("biome", environment.get("biome"))

    return vocab


def state_dimension(state: Dict) -> str:
    """Dimension of a state record; supports both top-level and player-level storage."""
    top_level = state.get("dimension")
    if isinstance(top_level, str) and top_level:
        return top_level

    player = state.get("player") or {}
    value = player.get("dimension")
    return value if isinstance(value, str) and value else "unknown"


# ============================================================
# Feature extraction
# ============================================================

LOOK_KIND = {"none": 0, "block": 1, "entity": 2}

# rel_pos(3) + distance(1) + type(1) + health(1) + is_alive(1) + item_type(1) + item_count(1)
ENTITY_FEAT_DIM = 9


def _number(value: Any, default: float = 0.0) -> float:
    """Converts a JSON value to float, tolerating nulls and strings."""
    if value is None:
        return default
    if isinstance(value, bool):
        return 1.0 if value else 0.0
    try:
        result = float(value)
    except (TypeError, ValueError):
        return default
    if math.isnan(result) or math.isinf(result):
        return default
    return result


def _dict(value: Any) -> Dict:
    return value if isinstance(value, dict) else {}


def extract_observation(
    state: Dict,
    vocab: Dict[str, Dict[str, int]],
    nearby_k: int,
) -> Tuple[np.ndarray, Dict[str, Tuple[int, int]]]:
    """
    Flattens one state record into a float32 vector.

    Returns the vector and a mapping ``{field: (start, end)}`` describing the columns.
    """
    player = _dict(state.get("player"))
    features: List[float] = []
    slices: Dict[str, Tuple[int, int]] = {}

    def push(name: str, values) -> None:
        start = len(features)
        features.extend(values)
        slices[name] = (start, len(features))

    # -------- Pose --------
    position = _dict(player.get("position"))
    push("position", [
        _number(position.get("x")),
        _number(position.get("y")),
        _number(position.get("z")),
    ])

    rotation = _dict(player.get("rotation"))
    push("rotation", [
        _number(rotation.get("yaw")),
        _number(rotation.get("pitch")),
    ])

    look_direction = _dict(player.get("look_direction"))
    push("look_direction", [
        _number(look_direction.get("x")),
        _number(look_direction.get("y")),
        _number(look_direction.get("z")),
    ])

    velocity = _dict(player.get("velocity"))
    push("velocity", [
        _number(velocity.get("x")),
        _number(velocity.get("y")),
        _number(velocity.get("z")),
    ])

    # -------- Vitals --------
    push("health", [
        _number(player.get("health"), 20.0),
        _number(player.get("max_health"), 20.0),
        _number(player.get("absorption")),
        _number(player.get("food"), 20.0),
        _number(player.get("saturation")),
        _number(player.get("air"), 300.0),
    ])

    push("progress", [
        _number(player.get("xp_level")),
        _number(player.get("xp_progress")),
    ])

    # -------- Movement flags --------
    push("flags", [
        float(bool(player.get("sprinting", False))),
        float(bool(player.get("sneaking", False))),
        float(bool(player.get("on_ground", False))),
        float(bool(player.get("swimming", False))),
        float(bool(player.get("in_water", False))),
        float(bool(player.get("on_fire", False))),
        float(bool(player.get("flying", False))),
    ])

    push("selected_slot", [float(player.get("selected_slot", 0))])

    # -------- Held items --------
    held = player.get("held_item")
    push("held_item", [
        float(vocab["item"].get(held["type"], 0)) if held else 0.0,
        _number((held or {}).get("count")),
    ])

    offhand = player.get("offhand_item")
    push("offhand_item", [
        float(vocab["item"].get(offhand["type"], 0)) if offhand else 0.0,
        _number((offhand or {}).get("count")),
    ])

    # -------- Looking at --------
    look = player.get("looking_at")
    block_type = 0.0
    entity_type = 0.0
    entity_health = 0.0

    if look:
        kind = look.get("kind")
        if kind == "block":
            block_type = float(vocab["block"].get(_dict(look.get("block")).get("type"), 0))
        elif kind == "entity":
            entity = _dict(look.get("entity"))
            entity_type = float(vocab["entity"].get(entity.get("type"), 0))
            entity_health = _number(entity.get("health"))

    push("looking_at", [
        float(LOOK_KIND.get((look or {}).get("kind"), 0)),
        _number((look or {}).get("distance")),
        block_type,
        entity_type,
        entity_health,
    ])

    # -------- Nearby entities --------
    entities = (player.get("nearby_entities") or [])[:nearby_k]

    for index in range(nearby_k):
        if index < len(entities):
            entity = _dict(entities[index])
            relative = _dict(entity.get("relative_position"))

            item_type = 0.0
            item_count = 0.0
            if entity.get("type") == "minecraft:item":
                item_type = float(vocab["item"].get(entity.get("item_type"), 0))
                item_count = _number(entity.get("item_count"))

            row = [
                _number(relative.get("x")),
                _number(relative.get("y")),
                _number(relative.get("z")),
                _number(entity.get("distance")),
                float(vocab["entity"].get(entity.get("type"), 0)),
                _number(entity.get("health")),
                float(bool(entity.get("is_alive", True))),
                item_type,
                item_count,
            ]
        else:
            row = [0.0] * ENTITY_FEAT_DIM

        push(f"nearby_entity[{index}]", row)

    # -------- Dimension --------
    push("dimension", [
        float(vocab["dimension"].get(state_dimension(state), 0)),
        _number(player.get("dimension_scale"), 1.0),
    ])

    # -------- Environment (optional in older episodes) --------
    environment = _dict(player.get("environment"))
    push("environment", [
        float(vocab["biome"].get(environment.get("biome"), 0)),
        _number(environment.get("block_light")),
        _number(environment.get("sky_light")),
        _number(environment.get("rain_level")),
        float(bool(environment.get("raining", False))),
        float(bool(environment.get("thundering", False))),
        _number(environment.get("day_time")),
    ])

    return np.asarray(features, dtype=np.float32), slices


def extract_action(action: Optional[Dict]) -> Tuple[np.ndarray, Dict[str, Tuple[int, int]]]:
    """Flattens one action record into a float32 vector."""
    keys = _dict((action or {}).get("keys"))
    mouse = _dict((action or {}).get("mouse"))

    features: List[float] = []
    slices: Dict[str, Tuple[int, int]] = {}

    def push(name: str, values) -> None:
        start = len(features)
        features.extend(values)
        slices[name] = (start, len(features))

    push("keys", [
        float(bool(keys.get("forward", False))),
        float(bool(keys.get("back", False))),
        float(bool(keys.get("left", False))),
        float(bool(keys.get("right", False))),
        float(bool(keys.get("jump", False))),
        float(bool(keys.get("sneak", False))),
        float(bool(keys.get("sprint", False))),
    ])

    push("mouse_delta", [
        _number(mouse.get("dx")),
        _number(mouse.get("dy")),
    ])

    push("mouse_buttons", [
        float(bool(mouse.get("left", False))),
        float(bool(mouse.get("middle", False))),
        float(bool(mouse.get("right", False))),
    ])

    return np.asarray(features, dtype=np.float32), slices


# ============================================================
# Sample building
# ============================================================


def build_samples(
    frames: List[Dict],
    states: List[Dict],
    actions: List[Dict],
    events: List[Dict],
    sample_source: str,
) -> Tuple[List[Dict], bool]:
    """
    Pairs states, actions, frames and events into samples.

    Returns ``(samples, actions_available)``. ``actions_available`` is false when the episode
    was recorded with the action stream disabled; the action vector is then all zeros.
    """
    states_by_tick = {
        state["sample_tick"]: state
        for state in states
        if isinstance(state.get("sample_tick"), int)
    }
    actions_by_tick = {
        action["sample_tick"]: action
        for action in actions
        if isinstance(action.get("sample_tick"), int)
    }

    events_by_tick: Dict[int, List[Dict]] = {}
    for event in events:
        tick = event.get("sample_tick")
        if isinstance(tick, int):
            events_by_tick.setdefault(tick, []).append(event)

    samples: List[Dict] = []

    if sample_source == "frames" or (sample_source == "auto" and frames):
        if not frames:
            raise RuntimeError("frames.jsonl is empty; use --sample-source states")

        for frame in frames:
            tick = frame.get("sample_tick")
            if not isinstance(tick, int):
                continue
            state = states_by_tick.get(tick)
            if state is None:
                continue

            samples.append({
                "frame_id": frame.get("frame_id"),
                "file": frame.get("file"),
                "sample_tick": tick,
                "world_time": frame.get("world_time", -1),
                "dimension": state_dimension(state),
                "state": state,
                "action": actions_by_tick.get(tick),
                "events": events_by_tick.get(tick, []),
            })

        samples.sort(key=lambda sample: sample["frame_id"] or 0)

    else:
        if not states:
            raise RuntimeError("state.jsonl is empty and no frames are available")

        for state in sorted(states, key=lambda item: item.get("sample_tick", 0)):
            tick = state.get("sample_tick")
            if not isinstance(tick, int):
                continue

            samples.append({
                "frame_id": None,
                "file": None,
                "sample_tick": tick,
                "world_time": state.get("world_time", -1),
                "dimension": state_dimension(state),
                "state": state,
                "action": actions_by_tick.get(tick),
                "events": events_by_tick.get(tick, []),
            })

    return samples, bool(actions_by_tick)


# ============================================================
# Dataset writing
# ============================================================


def write_dataset(
    out_dir: Path,
    samples: List[Dict],
    vocab: Dict[str, Dict[str, int]],
    nearby_k: int,
    metadata: Dict,
) -> Dict[str, Any]:
    """Writes one dataset folder and returns its meta dictionary."""
    observations: List[np.ndarray] = []
    actions: List[np.ndarray] = []
    dimension_ids: List[int] = []
    sample_ticks: List[int] = []
    world_times: List[int] = []
    frame_paths: List[str] = []

    obs_slices: Optional[Dict[str, Tuple[int, int]]] = None
    act_slices: Optional[Dict[str, Tuple[int, int]]] = None

    for sample in samples:
        observation, observation_slices = extract_observation(
            sample["state"], vocab, nearby_k)
        action, action_slices = extract_action(sample["action"])

        if obs_slices is None:
            obs_slices = observation_slices
            act_slices = action_slices

        observations.append(observation)
        actions.append(action)
        sample_ticks.append(sample["sample_tick"])
        world_times.append(sample["world_time"])
        dimension_ids.append(vocab["dimension"].get(sample["dimension"], 0))
        frame_paths.append(sample["file"] or "")

    observations_array = np.stack(observations, axis=0)
    actions_array = np.stack(actions, axis=0)
    rewards = np.zeros(len(samples), dtype=np.float32)
    dones = np.zeros(len(samples), dtype=bool)

    if len(dones) > 0:
        dones[-1] = True

    out_dir.mkdir(parents=True, exist_ok=True)

    np.save(out_dir / "observations.npy", observations_array)
    np.save(out_dir / "actions.npy", actions_array)
    np.save(out_dir / "rewards.npy", rewards)
    np.save(out_dir / "dones.npy", dones)
    np.save(out_dir / "sample_ticks.npy", np.asarray(sample_ticks, dtype=np.int32))
    np.save(out_dir / "world_times.npy", np.asarray(world_times, dtype=np.int64))
    np.save(out_dir / "dimensions.npy", np.asarray(dimension_ids, dtype=np.int32))

    with open(out_dir / "frame_paths.txt", "w", encoding="utf-8") as handle:
        for path in frame_paths:
            handle.write(path + "\n")

    with open(out_dir / "sample_dimensions.txt", "w", encoding="utf-8") as handle:
        for sample in samples:
            handle.write(sample["dimension"] + "\n")

    dimension_labels = {name: index for name, index in vocab["dimension"].items()}
    with open(out_dir / "dimension_labels.json", "w", encoding="utf-8") as handle:
        json.dump(dimension_labels, handle, indent=2, ensure_ascii=False)

    with open(out_dir / "dimension_segments.json", "w", encoding="utf-8") as handle:
        json.dump(build_dimension_segments(samples), handle, indent=2, ensure_ascii=False)

    events_by_tick: Dict[int, List[Dict]] = {}
    for sample in samples:
        if sample["events"]:
            events_by_tick[sample["sample_tick"]] = sample["events"]

    with open(out_dir / "events.json", "w", encoding="utf-8") as handle:
        json.dump(
            {str(tick): events for tick, events in events_by_tick.items()},
            handle, indent=2, ensure_ascii=False)

    with open(out_dir / "vocab.json", "w", encoding="utf-8") as handle:
        json.dump(vocab, handle, indent=2, ensure_ascii=False)

    with open(out_dir / "obs_schema.json", "w", encoding="utf-8") as handle:
        json.dump({
            "dim_total": int(observations_array.shape[1]),
            "nearby_k": nearby_k,
            "fields": {key: list(value) for key, value in (obs_slices or {}).items()},
        }, handle, indent=2, ensure_ascii=False)

    with open(out_dir / "act_schema.json", "w", encoding="utf-8") as handle:
        json.dump({
            "dim_total": int(actions_array.shape[1]),
            "fields": {key: list(value) for key, value in (act_slices or {}).items()},
        }, handle, indent=2, ensure_ascii=False)

    meta = {
        "episode_id": metadata.get("episode_id"),
        "source_episode": metadata.get("episode_directory"),
        "num_samples": int(observations_array.shape[0]),
        "obs_dim": int(observations_array.shape[1]),
        "act_dim": int(actions_array.shape[1]),
        "nearby_k": nearby_k,
        "sample_ticks": [int(min(sample_ticks)), int(max(sample_ticks))] if sample_ticks else [],
        "world_time_range": [int(min(world_times)), int(max(world_times))] if world_times else [],
        "dimensions": [name for name, _ in sorted(
            vocab["dimension"].items(), key=lambda item: item[1])],
        "vocab_sizes": {kind: len(table) for kind, table in vocab.items()},
        "event_types": sorted({
            event.get("type")
            for sample in samples
            for event in sample["events"]
            if event.get("type")
        }),
        "capture_config": metadata.get("capture_config", {}),
        "start_dimension": metadata.get("start_dimension"),
    }

    with open(out_dir / "meta.json", "w", encoding="utf-8") as handle:
        json.dump(meta, handle, indent=2, ensure_ascii=False)

    return meta


def build_dimension_segments(samples: List[Dict]) -> List[Dict]:
    """Groups consecutive samples that share a dimension."""
    segments: List[Dict] = []
    current: Optional[Dict] = None

    for index, sample in enumerate(samples):
        dimension = sample["dimension"]
        if current is None or current["dimension"] != dimension:
            current = {
                "dimension": dimension,
                "start_index": index,
                "end_index": index,
                "start_tick": sample["sample_tick"],
                "end_tick": sample["sample_tick"],
            }
            segments.append(current)
        else:
            current["end_index"] = index
            current["end_tick"] = sample["sample_tick"]

    return segments


def safe_name(name: str) -> str:
    """Turns a dimension id into a directory name."""
    return "".join(
        character if character.isalnum() or character in "-_." else "_"
        for character in name
    ) or "unknown"


def convert_episode(
    episode_dir: Path,
    nearby_k: int = 16,
    force: bool = False,
    sample_source: str = "auto",
    split_by_dimension: bool = False,
) -> Dict[str, Any]:
    """Converts one episode directory and returns its meta information."""
    out_dir = episode_dir / "dataset"

    if out_dir.exists() and not force:
        raise FileExistsError(f"{out_dir} already exists; use --force to overwrite")

    states = load_jsonl(episode_dir / "state.jsonl")
    actions = load_jsonl(episode_dir / "action.jsonl")
    events = load_jsonl(episode_dir / "event.jsonl")
    frames = load_jsonl(episode_dir / "frames.jsonl")
    metadata = load_json(episode_dir / "metadata.json") or {}

    samples, actions_available = build_samples(
        frames, states, actions, events, sample_source)

    if not samples:
        raise RuntimeError("no samples could be built (state/action did not match frames)")

    vocab = build_vocab(states)

    if out_dir.exists() and force:
        shutil.rmtree(out_dir)

    metadata.setdefault("episode_directory", str(episode_dir))

    meta = write_dataset(out_dir, samples, vocab, nearby_k, metadata)

    meta["actions_available"] = actions_available
    meta["frames_available"] = bool(frames)

    if split_by_dimension:
        split_root = out_dir / "by_dimension"
        for segment in build_dimension_segments(samples):
            dimension = segment["dimension"]
            subset = samples[segment["start_index"]: segment["end_index"] + 1]
            if not subset:
                continue
            write_dataset(
                split_root / safe_name(dimension),
                subset, vocab, nearby_k, metadata)

        meta["split_by_dimension"] = True

    with open(out_dir / "meta.json", "w", encoding="utf-8") as handle:
        json.dump(meta, handle, indent=2, ensure_ascii=False)

    return meta


# ============================================================
# CLI
# ============================================================


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Convert Mwarecorder episodes into numpy datasets")
    parser.add_argument("path", type=Path,
                        help="an episode directory or a directory of episodes")
    parser.add_argument("--nearby-k", type=int, default=16,
                        help="number of nearby-entity slots (default: 16)")
    parser.add_argument("--force", action="store_true",
                        help="overwrite an existing dataset directory")
    parser.add_argument("--sample-source", choices=["auto", "frames", "states"],
                        default="auto",
                        help="align samples to frames or to states (default: auto)")
    parser.add_argument("--split-by-dimension", action="store_true",
                        help="also write one dataset per dimension segment")

    args = parser.parse_args()

    if not args.path.exists():
        print(f"path does not exist: {args.path}", file=sys.stderr)
        sys.exit(2)

    episodes = find_episodes(args.path)
    if not episodes:
        print(f"no episodes found below: {args.path}", file=sys.stderr)
        sys.exit(2)

    print(f"found {len(episodes)} episode(s)\n")

    succeeded = 0
    failed = 0

    for episode in episodes:
        try:
            meta = convert_episode(
                episode,
                nearby_k=args.nearby_k,
                force=args.force,
                sample_source=args.sample_source,
                split_by_dimension=args.split_by_dimension,
            )

            print(f"[ok] {episode.name}")
            print(f"     samples      : {meta['num_samples']}")
            print(f"     obs_dim      : {meta['obs_dim']}")
            print(f"     act_dim      : {meta['act_dim']}")
            print(f"     dimensions   : {', '.join(meta['dimensions'])}")
            print(f"     vocab sizes  : {meta['vocab_sizes']}")
            print(f"     events       : {len(meta['event_types'])} type(s)")
            print(f"     actions      : {'yes' if meta['actions_available'] else 'no'}")
            print()

            succeeded += 1
        except Exception as error:  # noqa: BLE001 - report and continue with other episodes
            print(f"[fail] {episode.name}: {error}", file=sys.stderr)
            failed += 1

    print(f"done: {succeeded} converted, {failed} failed")
    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    main()

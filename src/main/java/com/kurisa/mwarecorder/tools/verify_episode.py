#!/usr/bin/env python3
"""
Mwarecorder episode verifier.

Checks that one episode (or a directory of episodes) is complete, internally consistent and
safe to convert into a training dataset.

Verified properties
-------------------
* required files and metadata fields exist;
* state / action / frame tick counters are continuous and aligned;
* every referenced JPEG exists, is non-empty and is not orphaned;
* world time never runs backwards inside one dimension;
* player teleports are reported (dimension changes are expected and excluded);
* events reference recorded ticks and pause / resume markers are paired;
* dimension changes in the state stream match ``dimension_change`` events;
* no NaN / infinity values and all key observation fields are present;
* summary.json counters match the actual files.

Usage
-----
    # One episode, detailed report
    python verify_episode.py path/to/episode_20260101_120000

    # Every episode below a directory
    python verify_episode.py path/to/mwarecorder_data

    # Compact output
    python verify_episode.py path/to/mwarecorder_data --quiet

    # Machine readable report
    python verify_episode.py path/to/mwarecorder_data --json report.json

    # CI: fail when any error is found
    python verify_episode.py path/to/mwarecorder_data --strict
"""

from __future__ import annotations

import argparse
import json
import math
import sys
import traceback
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

# ============================================================
# Limits
# ============================================================

FRAME_MIN_BYTES = 200
FRAME_MAX_BYTES = 8 * 1024 * 1024
PLAYER_TELEPORT_WARN = 32.0
MIN_FIELD_COVERAGE = 0.99
WORLD_TIME_GRACE_TICKS = 40


# ============================================================
# Colours
# ============================================================


class Color:
    """ANSI escapes, disabled when the terminal does not support them."""

    RESET = "\033[0m"
    BOLD = "\033[1m"
    DIM = "\033[2m"
    RED = "\033[31m"
    GREEN = "\033[32m"
    YELLOW = "\033[33m"
    CYAN = "\033[36m"


def supports_color() -> bool:
    if not sys.stdout.isatty():
        return False
    if sys.platform == "win32":
        return bool(sys.stdout.isatty())
    return True


USE_COLOR = supports_color()


def paint(text: str, *styles: str) -> str:
    if not USE_COLOR or not styles:
        return text
    return "".join(styles) + text + Color.RESET


# ============================================================
# Report model
# ============================================================


@dataclass
class Check:
    """One finding produced by a check."""

    severity: str  # "error" | "warning" | "info"
    code: str
    message: str
    details: Optional[Dict[str, Any]] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "severity": self.severity,
            "code": self.code,
            "message": self.message,
            "details": self.details,
        }


@dataclass
class EpisodeReport:
    """All findings for one episode."""

    episode_path: Path
    checks: List[Check] = field(default_factory=list)
    stats: Dict[str, Any] = field(default_factory=dict)

    def error(self, code: str, message: str, **details: Any) -> None:
        self.checks.append(Check("error", code, message, details or None))

    def warn(self, code: str, message: str, **details: Any) -> None:
        self.checks.append(Check("warning", code, message, details or None))

    def info(self, code: str, message: str, **details: Any) -> None:
        self.checks.append(Check("info", code, message, details or None))

    @property
    def errors(self) -> List[Check]:
        return [check for check in self.checks if check.severity == "error"]

    @property
    def warnings(self) -> List[Check]:
        return [check for check in self.checks if check.severity == "warning"]

    @property
    def infos(self) -> List[Check]:
        return [check for check in self.checks if check.severity == "info"]

    def to_dict(self) -> Dict[str, Any]:
        return {
            "episode": str(self.episode_path),
            "checks": [check.to_dict() for check in self.checks],
            "stats": self.stats,
        }


# ============================================================
# Loading helpers
# ============================================================


def load_jsonl(path: Path, report: EpisodeReport) -> List[Dict]:
    """Reads JSON Lines records, reporting malformed lines."""
    if not path.exists():
        return []

    records: List[Dict] = []
    malformed = 0

    with open(path, "r", encoding="utf-8") as handle:
        for number, line in enumerate(handle, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError as error:
                malformed += 1
                if malformed <= 3:
                    report.warn(
                        "jsonl_malformed",
                        f"{path.name} line {number} is not valid JSON: {error}")
    return records


def load_json(path: Path, report: EpisodeReport) -> Optional[Dict]:
    if not path.exists():
        return None
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return json.load(handle)
    except (json.JSONDecodeError, OSError) as error:
        report.error("json_parse", f"{path.name} cannot be parsed: {error}")
        return None


def state_dimension(state: Dict) -> str:
    """Dimension of a state record (top-level first, then player level)."""
    value = state.get("dimension")
    if isinstance(value, str) and value:
        return value

    player = state.get("player")
    if isinstance(player, dict):
        value = player.get("dimension")
        if isinstance(value, str) and value:
            return value

    return "unknown"


# ============================================================
# Structure
# ============================================================


def check_structure(episode: Path, report: EpisodeReport) -> Dict[str, Optional[Path]]:
    """Checks that the expected files exist and reports their sizes."""
    files: Dict[str, Optional[Path]] = {
        "metadata": episode / "metadata.json",
        "summary": episode / "summary.json",
        "state": episode / "state.jsonl",
        "action": episode / "action.jsonl",
        "event": episode / "event.jsonl",
        "frames": episode / "frames.jsonl",
    }

    present: Dict[str, Optional[Path]] = {}

    for name, path in files.items():
        if path.exists():
            present[name] = path
        else:
            present[name] = None
            if name == "frames":
                report.info("frames_missing", "frames.jsonl is missing (frame capture disabled)")
            elif name == "summary":
                report.warn("summary_missing", "summary.json is missing (episode did not stop cleanly)")
            else:
                report.error("file_missing", f"required file is missing: {path.name}")

    frames_dir = episode / "frames"
    present["frames_dir"] = frames_dir if frames_dir.is_dir() else None

    if present["frames"] is not None and present["frames_dir"] is None:
        report.error("frames_dir_missing", "frames.jsonl exists but the frames/ directory does not")

    return present


# ============================================================
# Metadata and summary
# ============================================================


def check_metadata(path: Optional[Path], report: EpisodeReport) -> Optional[Dict]:
    if path is None:
        return None

    metadata = load_json(path, report)
    if metadata is None:
        return None

    for key in ("episode_id", "start_time", "minecraft_version", "mod", "format_version"):
        if key not in metadata:
            report.warn("metadata_field_missing", f"metadata.json lacks the field: {key}")

    if metadata.get("mod") != "mwarecorder":
        report.warn("metadata_mod", f"unexpected mod id: {metadata.get('mod')}")

    if "start_dimension" not in metadata:
        report.info("metadata_start_dimension", "metadata.json has no start_dimension (older format)")

    capture = metadata.get("capture_config") or {}
    report.info(
        "metadata_capture",
        "capture: "
        f"frames={capture.get('frame_capture_enabled')}, "
        f"every_n={capture.get('every_n_frames')}, "
        f"quality={capture.get('jpeg_quality')}, "
        f"target={capture.get('target_width')}x{capture.get('target_height')}, "
        f"area={capture.get('event_area_size')}")

    report.info(
        "metadata_world",
        f"world={metadata.get('world_type')}, seed={metadata.get('world_seed')}, "
        f"mc={metadata.get('minecraft_version')}")

    report.stats["metadata"] = {
        "episode_id": metadata.get("episode_id"),
        "start_time": metadata.get("start_time"),
        "world_type": metadata.get("world_type"),
        "world_seed": metadata.get("world_seed"),
        "start_dimension": metadata.get("start_dimension"),
        "mod_count": len(metadata.get("mods") or []),
        "capture_config": capture,
    }

    return metadata


def check_summary(path: Optional[Path], report: EpisodeReport) -> Optional[Dict]:
    if path is None:
        return None

    summary = load_json(path, report)
    if summary is None:
        return None

    report.stats["summary"] = summary
    return summary


# ============================================================
# Tick alignment
# ============================================================


def ticks_of(records: List[Dict]) -> List[int]:
    return [
        record["sample_tick"]
        for record in records
        if isinstance(record.get("sample_tick"), int)
    ]


def check_tick_sequences(
    states: List[Dict],
    actions: List[Dict],
    frames: List[Dict],
    report: EpisodeReport,
) -> Tuple[Set[int], Set[int], Set[int]]:
    """Checks that the three streams agree on ``sample_tick``."""
    state_ticks_list = ticks_of(states)
    action_ticks_list = ticks_of(actions)
    frame_ticks_list = ticks_of(frames)

    state_ticks = set(state_ticks_list)
    action_ticks = set(action_ticks_list)
    frame_ticks = set(frame_ticks_list)

    report.stats["tick_counts"] = {
        "state": len(state_ticks),
        "action": len(action_ticks),
        "frames": len(frame_ticks),
    }

    # -------- state continuity --------
    if state_ticks_list:
        ordered = sorted(state_ticks_list)
        missing = sorted(set(range(ordered[0], ordered[-1] + 1)) - state_ticks)

        if missing:
            report.warn(
                "state_tick_gaps",
                f"state.jsonl has {len(missing)} missing tick(s) between {ordered[0]} and {ordered[-1]}",
                first_missing=missing[:10])
        else:
            report.info(
                "state_tick_continuous",
                f"state sample_tick is continuous ({ordered[0]}..{ordered[-1]})")

        if ordered[0] != 1:
            report.warn("state_tick_start", f"state sample_tick starts at {ordered[0]} (expected 1)")
    else:
        report.error("state_empty", "state.jsonl contains no records")

    # -------- action alignment --------
    if action_ticks:
        only_action = sorted(action_ticks - state_ticks)
        if only_action:
            report.error(
                "action_orphan_ticks",
                f"{len(only_action)} action tick(s) have no matching state",
                sample=only_action[:10])

        missing_actions = len(state_ticks - action_ticks)
        if missing_actions:
            report.warn(
                "action_missing_ticks",
                f"{missing_actions} state tick(s) have no action record")
        else:
            report.info("action_aligned", "action and state ticks match")
    else:
        report.info("action_empty", "action.jsonl is empty (input capture disabled)")

    # -------- frame alignment --------
    if frame_ticks:
        only_frame = sorted(frame_ticks - state_ticks)
        if only_frame:
            report.error(
                "frame_orphan_ticks",
                f"{len(only_frame)} frame tick(s) have no matching state",
                sample=only_frame[:10])
        else:
            report.info("frame_aligned", "every frame tick has a matching state")

        coverage = len(frame_ticks) / max(1, len(state_ticks))
        report.info(
            "frame_coverage",
            f"frames cover {coverage * 100:.1f}% of the recorded ticks "
            f"({len(frame_ticks)}/{len(state_ticks)})")

    return state_ticks, action_ticks, frame_ticks


# ============================================================
# Frame files
# ============================================================


def check_frames(
    frames: List[Dict],
    frames_dir: Optional[Path],
    report: EpisodeReport,
) -> None:
    if not frames:
        report.info("no_frames", "no frame records found")
        return

    ids = [frame["frame_id"] for frame in frames if isinstance(frame.get("frame_id"), int)]
    if ids:
        ordered = sorted(ids)
        missing = sorted(set(range(ordered[0], ordered[-1] + 1)) - set(ids))
        if missing:
            report.error(
                "frame_id_gaps",
                f"frame_id is not continuous, {len(missing)} missing",
                first_missing=missing[:10])
        else:
            report.info("frame_id_continuous", f"frame_id is continuous ({ordered[0]}..{ordered[-1]})")

        if ordered[0] != 1:
            report.warn("frame_id_start", f"frame_id starts at {ordered[0]} (expected 1)")

    if frames_dir is None:
        return

    referenced: Set[str] = set()
    missing_files: List[str] = []
    too_small: List[Tuple[str, int]] = []
    too_big: List[Tuple[str, int]] = []
    total_bytes = 0

    for frame in frames:
        filename = frame.get("file")
        if not filename:
            report.error(
                "frame_file_field_missing",
                f"frame record has no file field (frame_id={frame.get('frame_id')})")
            continue

        referenced.add(filename)
        path = frames_dir / filename

        if not path.exists():
            missing_files.append(filename)
            continue

        size = path.stat().st_size
        total_bytes += size

        if size < FRAME_MIN_BYTES:
            too_small.append((filename, size))
        elif size > FRAME_MAX_BYTES:
            too_big.append((filename, size))

    if missing_files:
        report.error(
            "frame_files_missing",
            f"{len(missing_files)} frame file(s) are missing",
            sample=missing_files[:10])

    if too_small:
        report.warn(
            "frame_files_small",
            f"{len(too_small)} frame file(s) are smaller than {FRAME_MIN_BYTES} bytes",
            sample=too_small[:5])

    if too_big:
        report.warn(
            "frame_files_big",
            f"{len(too_big)} frame file(s) are larger than {FRAME_MAX_BYTES} bytes",
            sample=too_big[:5])

    actual = {path.name for path in frames_dir.glob("*.jpg")}
    orphans = actual - referenced
    if orphans:
        report.warn(
            "frame_orphans",
            f"frames/ contains {len(orphans)} file(s) that are not indexed",
            sample=sorted(orphans)[:10])

    if not missing_files and not too_small and not too_big and not orphans:
        report.info("frame_files_ok", f"all {len(frames)} frame file(s) exist and look valid")

    if total_bytes > 0:
        report.info(
            "frame_size",
            f"frames: {total_bytes / 1024 / 1024:.1f} MB total, "
            f"{total_bytes / len(frames) / 1024:.1f} KB average")

    report.stats["frame_total_bytes"] = total_bytes


# ============================================================
# Time and movement
# ============================================================


def check_world_time(states: List[Dict], report: EpisodeReport) -> None:
    """
    ``world_time`` must not run backwards inside one dimension.

    Each dimension has its own game time, so the comparison restarts after a dimension change.
    A short grace window after the transition is also allowed: the client receives the new
    level's time a few ticks late, which can look like a small regression right after arrival.
    """
    if len(states) < 2:
        return

    ordered = sorted(states, key=lambda record: record.get("sample_tick", 0))

    regressions: List[Dict] = []
    tolerated = 0
    previous_time: Optional[int] = None
    previous_dimension: Optional[str] = None
    transition_index = -1_000_000

    for index, state in enumerate(ordered):
        world_time = state.get("world_time")
        dimension = state_dimension(state)

        if dimension != previous_dimension:
            transition_index = index

        if (
            isinstance(world_time, int)
            and isinstance(previous_time, int)
            and dimension == previous_dimension
            and world_time < previous_time
        ):
            entry = {
                "sample_tick": state.get("sample_tick"),
                "dimension": dimension,
                "previous": previous_time,
                "current": world_time,
            }
            if index - transition_index <= WORLD_TIME_GRACE_TICKS:
                tolerated += 1
            else:
                regressions.append(entry)

        previous_time = world_time if isinstance(world_time, int) else previous_time
        previous_dimension = dimension

    if tolerated:
        report.info(
            "world_time_resync",
            f"{tolerated} small world_time adjustment(s) tolerated right after a dimension change")

    if regressions:
        report.error(
            "world_time_regression",
            f"world_time runs backwards {len(regressions)} time(s) inside one dimension",
            sample=regressions[:5])
    else:
        report.info("world_time_monotonic", "world_time never runs backwards inside a dimension")


def check_movement(states: List[Dict], report: EpisodeReport) -> None:
    """Reports large position jumps, ignoring jumps that happen at a dimension change."""
    if len(states) < 2:
        return

    ordered = sorted(states, key=lambda record: record.get("sample_tick", 0))

    jumps: List[Dict] = []
    previous_position: Optional[Dict] = None
    previous_dimension: Optional[str] = None

    for state in ordered:
        player = state.get("player") or {}
        position = player.get("position")
        dimension = state_dimension(state)

        if not isinstance(position, dict):
            previous_position = None
            previous_dimension = dimension
            continue

        if previous_position is not None and dimension == previous_dimension:
            delta_x = position.get("x", 0.0) - previous_position.get("x", 0.0)
            delta_y = position.get("y", 0.0) - previous_position.get("y", 0.0)
            delta_z = position.get("z", 0.0) - previous_position.get("z", 0.0)
            distance = math.sqrt(delta_x ** 2 + delta_y ** 2 + delta_z ** 2)

            if distance > PLAYER_TELEPORT_WARN:
                jumps.append({
                    "sample_tick": state.get("sample_tick"),
                    "distance": round(distance, 2),
                })

        previous_position = position
        previous_dimension = dimension

    if jumps:
        report.warn(
            "player_teleport",
            f"{len(jumps)} large position jump(s) detected "
            "(respawn, teleport or portal; dimension changes are excluded)",
            sample=jumps[:5])
    else:
        report.info("player_movement_smooth", "no large position jumps detected")


# ============================================================
# Dimensions
# ============================================================


def check_dimensions(
    states: List[Dict],
    events: List[Dict],
    frames: List[Dict],
    metadata: Optional[Dict],
    report: EpisodeReport,
) -> None:
    """Cross-checks the dimension information of every stream."""
    if not states:
        return

    ordered = sorted(states, key=lambda record: record.get("sample_tick", 0))

    dimensions: List[str] = []
    transitions: List[Dict] = []
    previous: Optional[str] = None

    for state in ordered:
        dimension = state_dimension(state)
        if dimension not in dimensions:
            dimensions.append(dimension)

        if previous is not None and dimension != previous:
            transitions.append({
                "sample_tick": state.get("sample_tick"),
                "from": previous,
                "to": dimension,
            })

        previous = dimension

    report.stats["dimensions"] = dimensions
    report.stats["dimension_transitions"] = transitions

    if len(dimensions) == 1:
        report.info("dimension_single", f"episode stays in {dimensions[0]}")
    else:
        report.info(
            "dimension_multi",
            f"episode spans {len(dimensions)} dimensions: {', '.join(dimensions)}")

    # -------- every state should carry a dimension --------
    unknown = sum(1 for state in ordered if state_dimension(state) == "unknown")
    if unknown:
        report.warn(
            "dimension_missing",
            f"{unknown}/{len(ordered)} state record(s) have no dimension field")

    # -------- dimension_change events must match state transitions --------
    change_events = [
        event for event in events if event.get("type") == "dimension_change"
    ]

    event_pairs = {
        (event.get("from"), event.get("to")) for event in change_events
    }
    state_pairs = {(transition["from"], transition["to"]) for transition in transitions}

    missing_events = state_pairs - event_pairs
    if missing_events:
        report.warn(
            "dimension_event_missing",
            f"{len(missing_events)} dimension transition(s) have no dimension_change event",
            missing=sorted(missing_events))

    extra_events = event_pairs - state_pairs
    if extra_events:
        report.warn(
            "dimension_event_extra",
            f"{len(extra_events)} dimension_change event(s) do not match a state transition",
            extra=sorted(extra_events))

    if transitions and not missing_events and not extra_events:
        report.info(
            "dimension_events_consistent",
            f"{len(change_events)} dimension_change event(s) match the state stream")

    for event in change_events:
        if not event.get("from_position") or not event.get("to_position"):
            report.warn(
                "dimension_event_incomplete",
                "dimension_change event lacks from_position / to_position")

    # -------- frame dimension must agree with the state --------
    state_by_tick = {
        state["sample_tick"]: state_dimension(state)
        for state in ordered
        if isinstance(state.get("sample_tick"), int)
    }

    mismatched = 0
    for frame in frames:
        frame_dimension = frame.get("dimension")
        state_dimension_value = state_by_tick.get(frame.get("sample_tick"))
        if frame_dimension and state_dimension_value and frame_dimension != state_dimension_value:
            mismatched += 1

    if mismatched:
        report.warn(
            "frame_dimension_mismatch",
            f"{mismatched} frame record(s) disagree with the state dimension")

    # -------- metadata start dimension --------
    if metadata:
        start = metadata.get("start_dimension")
        if isinstance(start, str) and start and start != "unknown" and dimensions:
            if start != dimensions[0]:
                report.warn(
                    "start_dimension_mismatch",
                    f"metadata start_dimension={start} but the first state is {dimensions[0]}")


# ============================================================
# Events
# ============================================================


def check_events(events: List[Dict], state_ticks: Set[int], report: EpisodeReport) -> None:
    if not events:
        report.info("no_events", "no events recorded")
        return

    orphan_events = [
        {"type": event.get("type"), "sample_tick": event.get("sample_tick")}
        for event in events
        if isinstance(event.get("sample_tick"), int)
        and event["sample_tick"] not in state_ticks
    ]

    if orphan_events:
        report.error(
            "event_orphan_ticks",
            f"{len(orphan_events)} event(s) reference ticks that are not in the state stream",
            sample=orphan_events[:10])
    else:
        report.info("event_ticks_valid", f"all {len(events)} event(s) map to a recorded tick")

    # -------- pause / resume pairing --------
    open_pauses: List[int] = []
    unpaired: List[Dict] = []

    for event in sorted(events, key=lambda item: item.get("sample_tick", 0)):
        event_type = event.get("type")
        if event_type == "session_pause":
            open_pauses.append(event.get("sample_tick"))
        elif event_type == "session_resume":
            if open_pauses:
                open_pauses.pop()
            else:
                unpaired.append({"type": "resume_without_pause", "tick": event.get("sample_tick")})

    if unpaired:
        report.warn(
            "pause_unpaired",
            f"{len(unpaired)} session_resume event(s) have no matching session_pause",
            sample=unpaired[:5])
    else:
        report.info("pause_paired", "session_pause / session_resume markers are paired")

    # -------- type counts --------
    counts: Dict[str, int] = {}
    for event in events:
        event_type = event.get("type", "unknown")
        counts[event_type] = counts.get(event_type, 0) + 1

    report.stats["event_type_counts"] = counts
    report.info(
        "event_types",
        "event types: " + ", ".join(f"{name}={count}" for name, count in sorted(counts.items())))


# ============================================================
# Data quality
# ============================================================


def check_data_quality(states: List[Dict], report: EpisodeReport) -> None:
    if not states:
        return

    bad_numbers: List[Dict] = []

    def scan(value: Any, path: str, tick: Any, depth: int = 0) -> None:
        if depth > 6:
            return
        if isinstance(value, float):
            if math.isnan(value) or math.isinf(value):
                bad_numbers.append({"sample_tick": tick, "path": path, "value": str(value)})
        elif isinstance(value, dict):
            for key, child in value.items():
                scan(child, f"{path}.{key}", tick, depth + 1)
        elif isinstance(value, list):
            for index, child in enumerate(value[:8]):
                scan(child, f"{path}[{index}]", tick, depth + 1)

    for state in states:
        scan(state, "", state.get("sample_tick", -1))

    if bad_numbers:
        report.error(
            "nan_or_inf",
            f"{len(bad_numbers)} NaN / infinity value(s) found",
            sample=bad_numbers[:5])
    else:
        report.info("numbers_valid", "no NaN / infinity values")

    # -------- field coverage --------
    coverage: Dict[str, int] = {}
    for state in states:
        for key in (state.get("player") or {}):
            coverage[key] = coverage.get(key, 0) + 1

    total = len(states)
    report.info(
        "field_coverage",
        "player field coverage: "
        + ", ".join(
            f"{key}={count}/{total}" for key, count in sorted(coverage.items())))

    for required in ("position", "rotation", "velocity", "health", "food"):
        count = coverage.get(required, 0)
        if count < total * MIN_FIELD_COVERAGE:
            report.warn(
                "field_missing",
                f"player.{required} is missing in {(1 - count / total) * 100:.1f}% of the ticks")


# ============================================================
# Summary consistency
# ============================================================


def check_summary_vs_actual(
    summary: Optional[Dict],
    states: List[Dict],
    actions: List[Dict],
    events: List[Dict],
    frames: List[Dict],
    report: EpisodeReport,
) -> None:
    if summary is None:
        return

    pairs = [
        ("total_state_records", len(states)),
        ("total_action_records", len(actions)),
        ("total_event_records", len(events)),
        ("total_frames", len(frames)),
    ]

    mismatches = 0
    for key, actual in pairs:
        declared = summary.get(key)
        if declared is None:
            report.warn("summary_field_missing", f"summary.json lacks the field {key}")
            continue
        if declared != actual:
            report.error("summary_mismatch", f"{key}: summary says {declared}, actual is {actual}")
            mismatches += 1

    if mismatches == 0:
        report.info("summary_consistent", "summary.json matches the actual record counts")

    stop_reason = summary.get("stop_reason")
    if stop_reason is not None:
        report.info("summary_stop_reason", f"episode stopped because: {stop_reason}")


# ============================================================
# Orchestration
# ============================================================


def verify_episode(episode: Path) -> EpisodeReport:
    """Runs every check for one episode directory."""
    report = EpisodeReport(episode_path=episode)

    try:
        files = check_structure(episode, report)
        metadata = check_metadata(files["metadata"], report)
        summary = check_summary(files["summary"], report)

        empty = Path("/dev/null")
        states = load_jsonl(files["state"] or empty, report)
        actions = load_jsonl(files["action"] or empty, report)
        events = load_jsonl(files["event"] or empty, report)
        frames = load_jsonl(files["frames"] or empty, report)

        state_ticks, _, _ = check_tick_sequences(states, actions, frames, report)
        check_frames(frames, files["frames_dir"], report)
        check_world_time(states, report)
        check_movement(states, report)
        check_dimensions(states, events, frames, metadata, report)
        check_events(events, state_ticks, report)
        check_data_quality(states, report)
        check_summary_vs_actual(summary, states, actions, events, frames, report)

        report.stats["file_sizes"] = {
            name: (path.stat().st_size if path and path.exists() else None)
            for name, path in files.items()
            if isinstance(path, Path)
        }

    except Exception as error:  # noqa: BLE001 - the verifier must never crash
        report.error("verifier_crash", f"verifier crashed: {error}")
        report.stats["traceback"] = traceback.format_exc()

    return report


# ============================================================
# Reporting
# ============================================================


def print_report(report: EpisodeReport) -> None:
    print()
    print(paint("=" * 72, Color.DIM))
    print(paint("Episode: ", Color.BOLD) + paint(str(report.episode_path.name), Color.CYAN))
    print(paint("=" * 72, Color.DIM))

    for check in report.errors:
        print(paint("  [ERROR]", Color.RED + Color.BOLD) + "  " + check.message)
        if check.details:
            print(paint("           " + json.dumps(check.details, ensure_ascii=False), Color.DIM))

    for check in report.warnings:
        print(paint("  [WARN ]", Color.YELLOW + Color.BOLD) + "  " + check.message)
        if check.details:
            print(paint("           " + json.dumps(check.details, ensure_ascii=False), Color.DIM))

    if not report.errors and not report.warnings:
        for check in report.infos:
            print(paint("  [ OK  ] ", Color.GREEN) + check.message)

    counts = report.stats.get("tick_counts")
    if counts:
        print()
        print(
            paint("  Summary: ", Color.BOLD)
            + f"state={counts['state']} ticks, "
              f"action={counts['action']} ticks, "
              f"frames={counts['frames']} ticks")

    dimensions = report.stats.get("dimensions") or []
    if dimensions:
        print(paint("  Dimensions: ", Color.BOLD) + ", ".join(dimensions))

    print()
    errors = len(report.errors)
    warnings = len(report.warnings)

    if errors == 0 and warnings == 0:
        print(paint("  Status: PASS", Color.GREEN + Color.BOLD))
    elif errors == 0:
        print(paint(f"  Status: PASS with {warnings} warning(s)", Color.YELLOW + Color.BOLD))
    else:
        print(paint(f"  Status: {errors} error(s), {warnings} warning(s)", Color.RED + Color.BOLD))


def print_final_summary(reports: List[EpisodeReport]) -> None:
    print()
    print(paint("=" * 72, Color.DIM))
    print(paint("  Overview", Color.BOLD))
    print(paint("=" * 72, Color.DIM))

    total_errors = sum(len(report.errors) for report in reports)
    total_warnings = sum(len(report.warnings) for report in reports)
    passed = sum(1 for report in reports if not report.errors and not report.warnings)
    warn_only = sum(1 for report in reports if not report.errors and report.warnings)
    failed = sum(1 for report in reports if report.errors)

    print(f"  Episodes: {len(reports)}")
    print(f"  Passed: {paint(str(passed), Color.GREEN)}")
    print(f"  Warnings only: {paint(str(warn_only), Color.YELLOW)}")
    print(f"  Failed: {paint(str(failed), Color.RED)}")
    print(f"  Total errors: {paint(str(total_errors), Color.RED)}")
    print(f"  Total warnings: {paint(str(total_warnings), Color.YELLOW)}")

    if failed:
        print()
        print(paint("  Episodes with errors:", Color.RED + Color.BOLD))
        for report in reports:
            if report.errors:
                print(f"    - {report.episode_path.name} "
                      f"({len(report.errors)} errors, {len(report.warnings)} warnings)")


# ============================================================
# CLI
# ============================================================


def find_episodes(root: Path) -> List[Path]:
    if (root / "state.jsonl").exists():
        return [root]

    episodes = []
    for candidate in sorted(root.iterdir()):
        if candidate.is_dir() and (candidate / "state.jsonl").exists():
            episodes.append(candidate)
    return episodes


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Verify Mwarecorder episodes",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__)
    parser.add_argument("path", type=Path, help="episode directory or data root directory")
    parser.add_argument("--json", type=Path, default=None, help="write a JSON report to this file")
    parser.add_argument("--strict", action="store_true", help="exit non-zero when an error is found")
    parser.add_argument("--quiet", action="store_true", help="print only the overview")

    args = parser.parse_args()

    if not args.path.exists():
        print(paint(f"path does not exist: {args.path}", Color.RED), file=sys.stderr)
        sys.exit(2)

    episodes = find_episodes(args.path)
    if not episodes:
        print(paint(f"no episodes found below: {args.path}", Color.RED), file=sys.stderr)
        sys.exit(2)

    print(paint(f"found {len(episodes)} episode(s)", Color.CYAN))

    reports: List[EpisodeReport] = []
    for episode in episodes:
        report = verify_episode(episode)
        reports.append(report)

        if args.quiet:
            marker = (
                paint("PASS", Color.GREEN) if not report.errors and not report.warnings
                else paint("WARN", Color.YELLOW) if not report.errors
                else paint("FAIL", Color.RED)
            )
            print(f"  {marker} {episode.name}  "
                  f"({len(report.errors)} errors, {len(report.warnings)} warnings)")
        else:
            print_report(report)

    print_final_summary(reports)

    if args.json:
        payload = {
            "root": str(args.path),
            "episode_count": len(reports),
            "reports": [report.to_dict() for report in reports],
            "totals": {
                "errors": sum(len(report.errors) for report in reports),
                "warnings": sum(len(report.warnings) for report in reports),
            },
        }
        args.json.parent.mkdir(parents=True, exist_ok=True)
        with open(args.json, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, indent=2, ensure_ascii=False)
        print()
        print(paint(f"JSON report written to: {args.json}", Color.DIM))

    if args.strict and any(report.errors for report in reports):
        sys.exit(1)


if __name__ == "__main__":
    main()

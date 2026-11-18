package com.kurisa.mwarecorder.recorder;

/**
 * Marker for screens that must suspend recording.
 *
 * <p>The configuration screen implements this so that time spent changing settings does not
 * end up in the dataset as idle game play. The check lives in {@link EpisodeManager} and only
 * depends on this interface, which keeps the recorder independent of the UI package.</p>
 */
public interface PausesRecording {
}

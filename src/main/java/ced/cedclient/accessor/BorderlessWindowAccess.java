package ced.cedclient.accessor;

/** Implemented on Window by WindowBorderlessMixin. */
public interface BorderlessWindowAccess {
    /** Re-runs the window's fullscreen / windowed setup (so a Borderless Fullscreen toggle applies right away). */
    void cedclient$reapplyMode();
}

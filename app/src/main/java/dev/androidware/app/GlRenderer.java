package dev.androidware.app;

import android.view.Surface;

import java.io.File;

/**
 * Host GLES renderer (engine/emugl -> libawgl.so): decodes the guest's OpenGL ES command stream
 * (Android emulator "opengles" pipe protocol, forwarded by the engine's goldfish-pipe device to a
 * unix socket) and executes it on this device's GPU, drawing the guest screen onto a Surface.
 * Runs inside the app process.
 */
public final class GlRenderer {
    private static boolean loaded, running;

    private GlRenderer() {}

    public static synchronized boolean isRunning() { return running; }

    /** Starts the render server listening on socketPath. Returns null on success or an error text. */
    public static synchronized String start(File socketPath, int width, int height) {
        try {
            if (!loaded) { System.loadLibrary("awgl"); loaded = true; }
        } catch (UnsatisfiedLinkError e) { return "cannot load libawgl: " + e.getMessage(); }
        if (running) stop();
        //noinspection ResultOfMethodCallIgnored
        socketPath.delete();
        int r = nativeStart(socketPath.getAbsolutePath(), width, height);
        if (r != 0) return "renderer init failed (" + r + "); see logcat tag awgl";
        running = true;
        return null;
    }

    public static synchronized void setSurface(Surface s, int w, int h) { if (running) nativeSetSurface(s, w, h); }
    public static synchronized void stop() { if (running) { nativeStop(); running = false; } }
    public static synchronized String glStrings() { return running ? nativeGlStrings() : ""; }

    private static native int nativeStart(String socketPath, int width, int height);
    private static native int nativeSetSurface(Surface surface, int width, int height);
    private static native void nativeStop();
    private static native String nativeGlStrings();
    private static native long nativeFrameCount();

    /** Frames the guest has posted to the screen so far (for the fps readout); 0 when not running. */
    public static synchronized long frameCount() { return running ? nativeFrameCount() : 0; }
}

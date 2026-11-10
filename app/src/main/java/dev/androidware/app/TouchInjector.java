package dev.androidware.app;

import android.view.MotionEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Multi-touch input for the Android guest. The RFB (VNC) protocol has a single pointer, so touches
 * are sent over QMP instead: "input-send-event" with InputMultiTouchEvent ("mtt"), one slot per
 * finger, which the engine's goldfish-events device turns into Linux multi-touch protocol B.
 * One QMP command carries all changes of a MotionEvent and ends in one SYN_REPORT.
 *
 * QEMU's QMP socket serves one client at a time, so {@link #close()} must be called before
 * anything else (EngineService.stop) connects to it.
 */
public final class TouchInjector {
    private static final int SLOTS = 10;       // GF_EV_SLOTS in the engine
    private static final int ABS_MAX = 0x7fff;

    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static QmpClient qmp;              // io thread only
    private static File socket;                // io thread only
    private static long retryAfterMs;          // io thread only
    private static final boolean[] down = new boolean[SLOTS];   // UI thread only
    private static int nextTrackingId = 1;                      // UI thread only

    private TouchInjector() {}

    /**
     * Sends the touch changes of one MotionEvent. gx/gy are guest pixel coordinates per pointer
     * index (already clamped to the guest screen), w/h the guest screen size.
     */
    public static void send(File qmpSocket, MotionEvent e, float[] gx, float[] gy, int w, int h) {
        JSONArray ev = new JSONArray();
        try {
            int action = e.getActionMasked(), idx = e.getActionIndex();
            switch (action) {
                case MotionEvent.ACTION_DOWN: case MotionEvent.ACTION_POINTER_DOWN: {
                    int slot = e.getPointerId(idx);
                    if (slot < 0 || slot >= SLOTS) return;
                    put(ev, "begin", slot, nextTrackingId, "x", 0);
                    down[slot] = true;
                    position(ev, slot, gx[idx], gy[idx], w, h);
                    nextTrackingId = nextTrackingId % 0xfff0 + 1;
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    // Moves are coalesced: only the newest position of each finger is kept until the
                    // sender is ready, and at most ~60 batches per second go out. (Sending every
                    // sample kept QEMU's monitor thread at 20-30% of a core and stalled the guest.)
                    boolean first;
                    synchronized (pendingMove) {
                        first = !movePending;
                        for (int i = 0; i < e.getPointerCount(); i++) {
                            int slot = e.getPointerId(i);
                            if (slot < 0 || slot >= SLOTS || !down[slot]) continue;
                            pendingMove[slot * 2] = toAbs(gx[i], w); pendingMove[slot * 2 + 1] = toAbs(gy[i], h);
                            moveDirty[slot] = true;
                        }
                        movePending = true;
                    }
                    if (first) io.execute(() -> {
                        long wait = lastMoveMs + 16 - System.currentTimeMillis();
                        if (wait > 0) try { Thread.sleep(wait); } catch (InterruptedException ignored) {}
                        JSONArray mv = new JSONArray();
                        synchronized (pendingMove) {
                            movePending = false;
                            try {
                                for (int sl = 0; sl < SLOTS; sl++) if (moveDirty[sl]) {
                                    moveDirty[sl] = false;
                                    put(mv, "data", sl, 0, "x", pendingMove[sl * 2]);
                                    put(mv, "data", sl, 0, "y", pendingMove[sl * 2 + 1]);
                                }
                            } catch (JSONException ignored) {}
                        }
                        lastMoveMs = System.currentTimeMillis();
                        if (mv.length() > 0) transmit(qmpSocket, mv);
                    });
                    return;
                }
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_POINTER_UP: {
                    int slot = e.getPointerId(idx);
                    if (slot < 0 || slot >= SLOTS || !down[slot]) return;
                    put(ev, "end", slot, 0, "x", 0);
                    down[slot] = false;
                    break;
                }
                case MotionEvent.ACTION_CANCEL:
                    for (int s = 0; s < SLOTS; s++) if (down[s]) { put(ev, "end", s, 0, "x", 0); down[s] = false; }
                    break;
                default: return;
            }
        } catch (JSONException ex) { return; }
        if (ev.length() == 0) return;
        // a finger going up must not be followed by a stale queued position for it
        synchronized (pendingMove) { for (int sl = 0; sl < SLOTS; sl++) if (!down[sl]) moveDirty[sl] = false; }
        io.execute(() -> transmit(qmpSocket, ev));
    }

    private static final int[] pendingMove = new int[SLOTS * 2];   // guarded by pendingMove
    private static final boolean[] moveDirty = new boolean[SLOTS]; // guarded by pendingMove
    private static boolean movePending;                            // guarded by pendingMove
    private static long lastMoveMs;                                // io thread only

    /** io thread: sends one input-send-event command, (re)connecting to QMP if needed. */
    private static void transmit(File qmpSocket, JSONArray ev) {
        try {
            if (qmp == null || !qmpSocket.equals(socket)) {
                if (System.currentTimeMillis() < retryAfterMs) return;
                closeNow();
                QmpClient c = new QmpClient(qmpSocket);
                c.connect(2000);
                qmp = c; socket = qmpSocket;
            }
            qmp.execute("input-send-event", new JSONObject().put("events", ev));
        } catch (IOException | JSONException ex) {
            closeNow();
            retryAfterMs = System.currentTimeMillis() + 1000;
        }
    }

    private static void position(JSONArray ev, int slot, float gx, float gy, int w, int h) throws JSONException {
        put(ev, "data", slot, 0, "x", toAbs(gx, w));
        put(ev, "data", slot, 0, "y", toAbs(gy, h));
    }

    private static int toAbs(float g, int size) { return clamp(Math.round(g * ABS_MAX / Math.max(1, size - 1))); }

    private static int clamp(int v) { return Math.max(0, Math.min(ABS_MAX, v)); }

    private static void put(JSONArray ev, String type, int slot, int trackingId, String axis, int value) throws JSONException {
        ev.put(new JSONObject().put("type", "mtt").put("data", new JSONObject()
                .put("type", type).put("slot", slot).put("tracking-id", trackingId).put("axis", axis).put("value", value)));
    }

    private static void closeNow() { if (qmp != null) { qmp.close(); qmp = null; } socket = null; }

    /** Releases the QMP socket (blocks briefly until queued touches are flushed). */
    public static void close() {
        java.util.Arrays.fill(down, false);
        try { io.submit(TouchInjector::closeNow).get(3, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
    }
}

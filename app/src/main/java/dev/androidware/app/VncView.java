package dev.androidware.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal RFB 3.8 client (Raw + CopyRect encodings, 32bpp) that connects to QEMU's VNC server over a
 * unix socket in the app's data directory. It renders the guest framebuffer and converts touch
 * events into RFB pointer events (single pointer). Multi-touch goes through QMP instead.
 *
 * This is deliberately simple: zero native code, zero third-party deps, good enough to see the guest
 * and tap on it. Performance work (shared memory / virtio-gpu scanout to a SurfaceView) is later.
 */
public class VncView extends View {
    public interface StatusListener { void onVncStatus(String s); }

    private Bitmap fb;
    private int fbW, fbH;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF dst = new RectF();
    private Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private DataOutputStream out;
    private StatusListener status;
    private int lastButtons = 0;
    private volatile boolean overlay;   // host-GPU mode: draw nothing, only map touches

    /** In overlay mode the guest screen is drawn by the GLES renderer on a SurfaceView below this view. */
    public void setOverlay(boolean on) { overlay = on; setBackgroundColor(on ? Color.TRANSPARENT : Color.BLACK); postInvalidate(); }
    /** Guest framebuffer size as reported by VNC (0 if not connected yet). */
    public int guestWidth() { return fbW; }
    public int guestHeight() { return fbH; }

    public VncView(Context c) { super(c); init(); }
    public VncView(Context c, AttributeSet a) { super(c, a); init(); }
    private void init() { setFocusable(true); setFocusableInTouchMode(true); setBackgroundColor(Color.BLACK); }

    public void setStatusListener(StatusListener l) { status = l; }
    private void say(String s) { if (status != null) post(() -> status.onVncStatus(s)); }

    public void connect(File socketPath) {
        disconnect();
        running.set(true);
        thread = new Thread(() -> run(socketPath), "vnc-client");
        thread.setDaemon(true);
        thread.start();
    }

    public void disconnect() {
        running.set(false);
        Thread t = thread;
        if (t != null) { t.interrupt(); thread = null; }
        synchronized (this) { out = null; }
    }

    private void run(File socketPath) {
        // QEMU creates the socket shortly after start; retry for a while.
        LocalSocket sock = null;
        for (int i = 0; i < 100 && running.get(); i++) {
            try {
                sock = new LocalSocket();
                sock.connect(new LocalSocketAddress(socketPath.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM));
                break;
            } catch (IOException e) {
                try { sock.close(); } catch (IOException ignored) {}
                sock = null;
                try { Thread.sleep(200); } catch (InterruptedException ie) { return; }
            }
        }
        if (sock == null) { say("vnc: could not connect to " + socketPath); return; }
        try {
            DataInputStream in = new DataInputStream(sock.getInputStream());
            DataOutputStream o = new DataOutputStream(sock.getOutputStream());
            byte[] ver = new byte[12]; in.readFully(ver);
            say("vnc: server " + new String(ver, 0, 11));
            o.write("RFB 003.008\n".getBytes()); o.flush();
            int nsec = in.readUnsignedByte();
            if (nsec == 0) { throw new IOException("vnc: server refused: " + readString(in)); }
            byte[] secs = new byte[nsec]; in.readFully(secs);
            boolean none = false; for (byte b : secs) if (b == 1) none = true;
            if (!none) throw new IOException("vnc: server requires authentication; not supported");
            o.writeByte(1); o.flush();
            int result = in.readInt();
            if (result != 0) throw new IOException("vnc: security result " + result + ": " + readString(in));
            o.writeByte(1); o.flush(); // ClientInit: shared
            fbW = in.readUnsignedShort(); fbH = in.readUnsignedShort();
            byte[] pf = new byte[16]; in.readFully(pf);
            String name = readString(in);
            say("vnc: " + name + " " + fbW + "x" + fbH);
            fb = Bitmap.createBitmap(fbW, fbH, Bitmap.Config.ARGB_8888);
            // SetPixelFormat: 32bpp, depth 24, little-endian, true colour, RGB 8:8:8 at shifts 16/8/0
            o.writeByte(0); o.write(new byte[3]);
            o.writeByte(32); o.writeByte(24); o.writeByte(0); o.writeByte(1);
            o.writeShort(255); o.writeShort(255); o.writeShort(255);
            o.writeByte(16); o.writeByte(8); o.writeByte(0); o.write(new byte[3]);
            // SetEncodings: Raw(0), CopyRect(1), DesktopSize(-223)
            o.writeByte(2); o.writeByte(0); o.writeShort(3); o.writeInt(0); o.writeInt(1); o.writeInt(-223);
            o.flush();
            synchronized (this) { out = o; }
            requestUpdate(false);
            int[] row = new int[fbW];
            byte[] rowBytes = new byte[fbW * 4];
            while (running.get()) {
                int type = in.readUnsignedByte();
                switch (type) {
                    case 0: { // FramebufferUpdate
                        in.readByte();
                        int n = in.readUnsignedShort();
                        for (int i = 0; i < n; i++) {
                            int x = in.readUnsignedShort(), y = in.readUnsignedShort();
                            int w = in.readUnsignedShort(), h = in.readUnsignedShort();
                            int enc = in.readInt();
                            if (enc == 0) {
                                if (rowBytes.length < w * 4) { rowBytes = new byte[w * 4]; row = new int[w]; }
                                for (int yy = 0; yy < h; yy++) {
                                    in.readFully(rowBytes, 0, w * 4);
                                    ByteBuffer.wrap(rowBytes, 0, w * 4).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(row, 0, w);
                                    for (int k = 0; k < w; k++) row[k] |= 0xFF000000;
                                    if (y + yy < fbH && x + w <= fbW) fb.setPixels(row, 0, w, x, y + yy, w, 1);
                                }
                            } else if (enc == 1) {
                                int sx = in.readUnsignedShort(), sy = in.readUnsignedShort();
                                int[] block = new int[w * h];
                                fb.getPixels(block, 0, w, sx, sy, w, h);
                                fb.setPixels(block, 0, w, x, y, w, h);
                            } else if (enc == -223) {
                                fbW = w; fbH = h;
                                fb = Bitmap.createBitmap(fbW, fbH, Bitmap.Config.ARGB_8888);
                                row = new int[fbW]; rowBytes = new byte[fbW * 4];
                                say("vnc: resize " + w + "x" + h);
                            } else {
                                throw new IOException("vnc: unsupported encoding " + enc);
                            }
                        }
                        postInvalidate();
                        requestUpdate(true);
                        break;
                    }
                    case 1: { // SetColourMapEntries
                        in.readByte(); in.readShort(); int n = in.readUnsignedShort(); in.skipBytes(n * 6); break;
                    }
                    case 2: break; // Bell
                    case 3: { in.skipBytes(3); int len = in.readInt(); in.skipBytes(len); break; } // ServerCutText
                    default: throw new IOException("vnc: unknown server message " + type);
                }
            }
        } catch (IOException e) {
            if (running.get()) say("vnc: disconnected" + (e.getMessage() != null ? " (" + e.getMessage() + ")" : ""));
        } finally {
            try { sock.close(); } catch (IOException ignored) {}
            synchronized (this) { out = null; }
        }
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = in.readInt(); byte[] b = new byte[Math.max(0, Math.min(len, 1 << 16))]; in.readFully(b); return new String(b);
    }

    private void requestUpdate(boolean incremental) {
        DataOutputStream o; synchronized (this) { o = out; }
        if (o == null) return;
        try {
            synchronized (o) { o.writeByte(3); o.writeByte(incremental ? 1 : 0); o.writeShort(0); o.writeShort(0); o.writeShort(fbW); o.writeShort(fbH); o.flush(); }
        } catch (IOException ignored) {}
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Bitmap b = fb;
        if (b == null) return;
        float sx = (float) getWidth() / b.getWidth(), sy = (float) getHeight() / b.getHeight();
        float s = Math.min(sx, sy);
        float dw = b.getWidth() * s, dh = b.getHeight() * s;
        dst.set((getWidth() - dw) / 2, (getHeight() - dh) / 2, (getWidth() + dw) / 2, (getHeight() + dh) / 2);
        if (!overlay) canvas.drawBitmap(b, null, dst, paint);
    }

    /** Maps a view coordinate into guest framebuffer pixels; null if outside the image. */
    private int[] toGuest(float vx, float vy) {
        if (fb == null || dst.width() <= 0) return null;
        float gx = (vx - dst.left) / dst.width() * fbW, gy = (vy - dst.top) / dst.height() * fbH;
        if (gx < 0 || gy < 0 || gx >= fbW || gy >= fbH) return null;
        return new int[]{(int) gx, (int) gy};
    }

    /** Android guest: touches go over QMP as multi-touch (see TouchInjector). Null = RFB single pointer. */
    private volatile java.io.File multiTouchSocket;
    public void setMultiTouchSocket(java.io.File qmpSocket) { multiTouchSocket = qmpSocket; }

    @Override public boolean onTouchEvent(MotionEvent e) {
        DataOutputStream o; synchronized (this) { o = out; }
        if (o == null) return true;
        java.io.File mt = multiTouchSocket;
        if (mt != null && fb != null && dst.width() > 0) {
            int n = e.getPointerCount();
            float[] gx = new float[n], gy = new float[n];
            for (int i = 0; i < n; i++) {
                gx[i] = Math.max(0, Math.min(fbW - 1, (e.getX(i) - dst.left) / dst.width() * fbW));
                gy[i] = Math.max(0, Math.min(fbH - 1, (e.getY(i) - dst.top) / dst.height() * fbH));
            }
            TouchInjector.send(mt, e, gx, gy, fbW, fbH);
            return true;
        }
        int[] g = toGuest(e.getX(), e.getY());
        int buttons;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: case MotionEvent.ACTION_MOVE: buttons = 1; break;
            default: buttons = 0;
        }
        if (g == null) { if (buttons == 0 && lastButtons != 0) g = new int[]{0, 0}; else return true; }
        lastButtons = buttons;
        try { synchronized (o) { o.writeByte(5); o.writeByte(buttons); o.writeShort(g[0]); o.writeShort(g[1]); o.flush(); } } catch (IOException ignored) {}
        return true;
    }

    /** Sends an X11 keysym. The activity maps Android BACK to Escape so old games see a back/cancel key. */
    public void sendKey(int keysym, boolean down) {
        DataOutputStream o; synchronized (this) { o = out; }
        if (o == null) return;
        try { synchronized (o) { o.writeByte(4); o.writeByte(down ? 1 : 0); o.writeShort(0); o.writeInt(keysym); o.flush(); } } catch (IOException ignored) {}
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) { return keyToVnc(keyCode, true) || super.onKeyDown(keyCode, event); }
    @Override public boolean onKeyUp(int keyCode, KeyEvent event) { return keyToVnc(keyCode, false) || super.onKeyUp(keyCode, event); }

    private boolean keyToVnc(int keyCode, boolean down) {
        int ks;
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER: ks = 0xff0d; break;
            case KeyEvent.KEYCODE_DEL: ks = 0xff08; break;
            case KeyEvent.KEYCODE_SPACE: ks = 0x20; break;
            case KeyEvent.KEYCODE_DPAD_UP: ks = 0xff52; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: ks = 0xff54; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: ks = 0xff51; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: ks = 0xff53; break;
            default:
                if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) ks = 'a' + (keyCode - KeyEvent.KEYCODE_A);
                else if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) ks = '0' + (keyCode - KeyEvent.KEYCODE_0);
                else return false;
        }
        sendKey(ks, down);
        return true;
    }
}

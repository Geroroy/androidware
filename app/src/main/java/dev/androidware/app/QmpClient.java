package dev.androidware.app;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal QMP (QEMU Machine Protocol) client over a filesystem unix socket.
 * Used for graceful shutdown and, later, multi-touch injection via "input-send-event" with
 * InputMultiTouchEvent ("mtt", QEMU >= 8.1) which the VNC protocol cannot express.
 */
public final class QmpClient {
    private final File socketPath;
    private LocalSocket sock;
    private BufferedReader in;
    private OutputStream out;

    public QmpClient(File socketPath) { this.socketPath = socketPath; }

    public void connect(int timeoutMs) throws IOException {
        sock = new LocalSocket();
        sock.connect(new LocalSocketAddress(socketPath.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM));
        sock.setSoTimeout(timeoutMs);
        in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
        out = sock.getOutputStream();
        String greeting = in.readLine(); // {"QMP": {...}}
        if (greeting == null || !greeting.contains("QMP")) throw new IOException("bad QMP greeting: " + greeting);
        execute("qmp_capabilities", null);
    }

    public JSONObject execute(String cmd, JSONObject args) throws IOException {
        try {
            JSONObject o = new JSONObject().put("execute", cmd);
            if (args != null) o.put("arguments", args);
            out.write((o.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            while (true) {
                String line = in.readLine();
                if (line == null) throw new IOException("QMP closed");
                JSONObject r = new JSONObject(line);
                if (r.has("event")) continue; // async events are ignored here
                if (r.has("error")) throw new IOException("QMP error: " + r.getJSONObject("error"));
                return r;
            }
        } catch (JSONException e) { throw new IOException(e); }
    }

    /** Multi-touch: one slot, absolute coordinates in [0, 0x7fff]. type: begin|update|end. */
    public void sendTouch(String type, int slot, int trackingId, int absX, int absY) throws IOException {
        try {
            JSONArray events = new JSONArray();
            events.put(mtt(type, slot, trackingId, "x", absX));
            events.put(mtt(type, slot, trackingId, "y", absY));
            execute("input-send-event", new JSONObject().put("events", events));
        } catch (JSONException e) { throw new IOException(e); }
    }

    private static JSONObject mtt(String type, int slot, int trackingId, String axis, int value) throws JSONException {
        return new JSONObject().put("type", "mtt").put("data", new JSONObject()
                .put("type", type).put("slot", slot).put("tracking-id", trackingId).put("axis", axis).put("value", value));
    }

    public void close() { try { if (sock != null) sock.close(); } catch (IOException ignored) {} }
}

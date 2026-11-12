package dev.androidware.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Plays the guest's audio: the engine's goldfish-audio device writes raw PCM (44.1 kHz, stereo,
 * signed 16-bit little endian) to a unix socket; this reads it and feeds an AudioTrack.
 */
public final class AudioPlayer {
    private volatile Thread thread;
    private volatile boolean running;
    // Diagnostics for the "perf:" log line: where does the sound stop (guest, engine, socket, AudioTrack)?
    private volatile long bytesReceived, connects;
    private volatile int peak;
    private volatile String state = "idle";

    /** "audio=<kB received since the last call>kB peak=<max sample> <state>", and resets the interval counters. */
    public String statsAndReset(long[] prevBytes) {
        long b = bytesReceived, d = b - prevBytes[0];
        prevBytes[0] = b;
        int p = peak; peak = 0;
        return "audio=" + (d >> 10) + "kB peak=" + p + " " + state + (connects > 1 ? " reconnects=" + (connects - 1) : "");
    }

    public void start(File socketPath) {
        stop();
        running = true;
        Thread t = new Thread(() -> { while (alive()) { run(socketPath); pause(500); } }, "guest-audio");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    /** False once stop() or a newer start() has replaced the calling player thread. */
    private boolean alive() { return running && thread == Thread.currentThread(); }

    private void pause(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    public void stop() {
        running = false;
        Thread t = thread;
        if (t != null) t.interrupt();
        thread = null;
        state = "stopped";
    }

    /** One connection to the engine's audio socket; returns when it ends (the caller reconnects while running). */
    private void run(File socketPath) {
        LocalSocket sock = null;
        state = "connecting";
        for (int i = 0; i < 100 && alive(); i++) {
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
        if (sock == null) { state = "no-socket"; return; }
        connects++;
        AudioTrack track;
        try {
            int min = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(44100)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(min, 16384))
                    .setTransferMode(AudioTrack.MODE_STREAM).build();
        } catch (RuntimeException e) {
            state = "track-error:" + e.getClass().getSimpleName();
            try { sock.close(); } catch (IOException ignored) {}
            return;
        }
        try (InputStream in = sock.getInputStream()) {
            track.play();
            state = "playing";
            byte[] buf = new byte[8192];
            int have = 0;
            while (alive()) {
                int n = in.read(buf, have, buf.length - have);
                if (n < 0) { state = "socket-closed"; break; }
                bytesReceived += n;
                have += n;
                int whole = have & ~3;                 // whole stereo frames only
                if (whole > 0) {
                    int p = peak;
                    for (int i = 0; i + 1 < whole; i += 64) {      // sparse amplitude probe (is it silence?)
                        int v = Math.abs((short) ((buf[i] & 0xff) | (buf[i + 1] << 8)));
                        if (v > p) p = v;
                    }
                    peak = p;
                    int w = track.write(buf, 0, whole);
                    if (w < 0) { state = "track-write-error:" + w; break; }
                }
                System.arraycopy(buf, whole, buf, 0, have - whole);
                have -= whole;
            }
        } catch (IOException e) {
            state = "io-error";
        } finally {
            try { track.stop(); } catch (IllegalStateException ignored) {}
            track.release();
            try { sock.close(); } catch (IOException ignored) {}
        }
    }
}

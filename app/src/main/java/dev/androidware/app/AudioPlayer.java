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

    public void start(File socketPath) {
        stop();
        running = true;
        Thread t = new Thread(() -> run(socketPath), "guest-audio");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    public void stop() {
        running = false;
        Thread t = thread;
        if (t != null) t.interrupt();
        thread = null;
    }

    private void run(File socketPath) {
        LocalSocket sock = null;
        for (int i = 0; i < 100 && running; i++) {
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
        if (sock == null) return;
        int min = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(44100)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(min, 16384))
                .setTransferMode(AudioTrack.MODE_STREAM).build();
        try (InputStream in = sock.getInputStream()) {
            track.play();
            byte[] buf = new byte[8192];
            int have = 0;
            while (running) {
                int n = in.read(buf, have, buf.length - have);
                if (n < 0) break;
                have += n;
                int whole = have & ~3;                 // whole stereo frames only
                if (whole > 0) track.write(buf, 0, whole);
                System.arraycopy(buf, whole, buf, 0, have - whole);
                have -= whole;
            }
        } catch (IOException ignored) {
        } finally {
            try { track.stop(); } catch (IllegalStateException ignored) {}
            track.release();
            try { sock.close(); } catch (IOException ignored) {}
        }
    }
}

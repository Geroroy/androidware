package dev.androidware.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Foreground service owning the QEMU child process. Lifetime rules:
 *  - start(): spawns qemu, tees stdout/stderr into files/run/qemu.log and to listeners.
 *  - stop(): tries QMP "quit" (graceful), then SIGTERM/destroy, always removes stale sockets.
 *  - onDestroy()/onTaskRemoved(): the guest never outlives the service.
 */
public class EngineService extends Service {
    private static final String TAG = "EngineService";
    private static final String CHANNEL = "guest";
    private static final int NOTIF_ID = 1;

    public interface LogListener { void onLine(String line); void onExit(int code); }

    public class LocalBinder extends Binder { public EngineService get() { return EngineService.this; } }

    private final LocalBinder binder = new LocalBinder();
    private final List<LogListener> listeners = new CopyOnWriteArrayList<>();
    private volatile Process proc;
    private volatile Thread pump;
    private volatile int lastExit = Integer.MIN_VALUE;

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notif_running))
                .setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIF_ID, n);
        return START_NOT_STICKY;
    }

    public void addListener(LogListener l) { listeners.add(l); }
    public void removeListener(LogListener l) { listeners.remove(l); }
    public boolean isRunning() { Process p = proc; return p != null && p.isAlive(); }
    public int lastExitCode() { return lastExit; }

    public synchronized void start(GuestProfile profile) throws IOException {
        if (isRunning()) throw new IOException("guest already running");
        Context ctx = getApplicationContext();
        QemuAssets.ensureExtracted(ctx);
        cleanupSockets(ctx);
        List<String> cmd = QemuLauncher.build(ctx, profile);
        emit("$ " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(QemuLauncher.runtimeDir(ctx));
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("TMPDIR", ctx.getCacheDir().getAbsolutePath());
        env.put("HOME", ctx.getFilesDir().getAbsolutePath());
        env.put("LD_LIBRARY_PATH", ctx.getApplicationInfo().nativeLibraryDir);
        if (GuestProfile.KIND_ANDROID_RANCHU.equals(profile.kind)) {
            boolean fast = AndroidGuest.fastSwitch(ctx);
            if (!fast) env.put("AW_TLB_CTX", "0");   // engine patch 0025: keep-TLB-per-address-space off
            emit("engine: fast process switching " + (fast ? "on" : "off"));
        }
        final Process p = pb.start();
        proc = p;
        final File logFile = new File(QemuLauncher.runtimeDir(ctx), "qemu.log");
        pump = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                 PrintStream out = new PrintStream(new FileOutputStream(logFile, false))) {
                String line;
                while ((line = r.readLine()) != null) { out.println(line); emit(line); }
            } catch (IOException e) {
                emit("[log pump] " + e);
            }
            int code;
            try { code = p.waitFor(); } catch (InterruptedException e) { code = -1; }
            lastExit = code;
            emit("[qemu exited with code " + code + "]");
            for (LogListener l : listeners) l.onExit(code);
            cleanupSockets(ctx);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }, "qemu-log-pump");
        pump.setDaemon(true);
        pump.start();
    }

    public synchronized void stop() {
        Process p = proc;
        if (p == null) return;
        TouchInjector.close();   // the QMP socket serves one client at a time
        try {
            QmpClient qmp = new QmpClient(QemuLauncher.qmpSocket(getApplicationContext()));
            qmp.connect(1500);
            qmp.execute("quit", null);
            qmp.close();
            if (p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) { proc = null; return; }
        } catch (Exception e) {
            Log.w(TAG, "graceful quit failed: " + e);
        }
        p.destroy();
        try { if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly(); } catch (InterruptedException ignored) {}
        proc = null;
    }

    private void cleanupSockets(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        QemuLauncher.vncSocket(ctx).delete();
        //noinspection ResultOfMethodCallIgnored
        QemuLauncher.qmpSocket(ctx).delete();
        //noinspection ResultOfMethodCallIgnored
        QemuLauncher.audioSocket(ctx).delete();
    }

    private void emit(String line) { for (LogListener l : listeners) l.onLine(line); }

    @Override public void onTaskRemoved(Intent rootIntent) { stop(); stopSelf(); }
    @Override public void onDestroy() { stop(); super.onDestroy(); }
}

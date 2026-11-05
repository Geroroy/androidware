package dev.androidware.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * M0/M1 diagnostic UI. Deliberately plain: the point is to prove the engine runs on the device,
 * not to look good. Every action is logged to the on-screen console and to files/run/app.log.
 */
public class MainActivity extends Activity implements EngineService.LogListener, VncView.StatusListener {
    private static final int REQ_KERNEL = 1, REQ_INITRD = 2, REQ_DISK = 3, REQ_APK = 4, REQ_EXPORT = 5;

    private TextView logView;
    private ScrollView logScroll;
    private VncView vnc;
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private EngineService service;
    private GuestProfile profile;
    private File appLog;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            service = ((EngineService.LocalBinder) b).get();
            service.addListener(MainActivity.this);
            log("service bound; running=" + service.isRunning());
            if (service.isRunning()) vnc.connect(QemuLauncher.vncSocket(MainActivity.this));
        }
        @Override public void onServiceDisconnected(ComponentName n) { service = null; }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        logView = findViewById(R.id.log);
        logScroll = findViewById(R.id.log_scroll);
        vnc = findViewById(R.id.vnc);
        vnc.setStatusListener(this);
        appLog = new File(QemuLauncher.runtimeDir(this), "app.log");
        profile = GuestProfile.load(getFilesDir(), "default");

        ((Button) findViewById(R.id.btn_host)).setOnClickListener(v -> log(HostInfo.collect(this)));
        ((Button) findViewById(R.id.btn_selftest)).setOnClickListener(v -> io.execute(() -> log(SelfTest.run(this))));
        ((Button) findViewById(R.id.btn_kernel)).setOnClickListener(v -> pick(REQ_KERNEL));
        ((Button) findViewById(R.id.btn_initrd)).setOnClickListener(v -> pick(REQ_INITRD));
        ((Button) findViewById(R.id.btn_disk)).setOnClickListener(v -> pick(REQ_DISK));
        ((Button) findViewById(R.id.btn_apk)).setOnClickListener(v -> pick(REQ_APK));
        ((Button) findViewById(R.id.btn_start)).setOnClickListener(v -> startGuest());
        ((Button) findViewById(R.id.btn_stop)).setOnClickListener(v -> stopGuest());
        ((Button) findViewById(R.id.btn_export)).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain")
                    .putExtra(Intent.EXTRA_TITLE, "androidware-log-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt");
            startActivityForResult(i, REQ_EXPORT);
        });

        log("androidware M0 build; profile=" + profile.name + " kernel=" + profile.kernel + " initrd=" + profile.initrd + " disk=" + profile.disk);
        Intent svc = new Intent(this, EngineService.class);
        bindService(svc, conn, Context.BIND_AUTO_CREATE);
    }

    private void pick(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        startActivityForResult(i, req);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_EXPORT) { exportLog(uri); return; }
        String name = ImportManager.displayName(this, uri);
        long size = ImportManager.size(this, uri);
        log("importing " + name + " (" + size + " bytes)...");
        io.execute(() -> {
            try {
                File dir = req == REQ_APK ? ImportManager.gamesDir(this) : ImportManager.guestDir(this);
                File f = ImportManager.copyToDir(this, uri, dir, name, (c, t) -> log("  " + (c >> 20) + "/" + (t >> 20) + " MiB"));
                switch (req) {
                    case REQ_KERNEL: profile.kernel = f.getAbsolutePath(); break;
                    case REQ_INITRD: profile.initrd = f.getAbsolutePath(); break;
                    case REQ_DISK: profile.disk = f.getAbsolutePath(); break;
                    case REQ_APK: log(ApkInspector.inspect(this, f)); break;
                }
                profile.save(getFilesDir());
                log("imported -> " + f);
            } catch (IOException e) { log("import failed: " + e); }
        });
    }

    private void startGuest() {
        if (service == null) { toast("service not bound yet"); return; }
        if (profile.kernel == null && profile.disk == null) { toast("import a kernel or disk image first"); return; }
        io.execute(() -> {
            try {
                startForegroundService(new Intent(this, EngineService.class));
                service.start(profile);
                ui.post(() -> vnc.connect(QemuLauncher.vncSocket(this)));
            } catch (IOException e) { log("start failed: " + e); }
        });
    }

    private void stopGuest() {
        vnc.disconnect();
        if (service != null) io.execute(() -> { service.stop(); log("stop requested"); });
    }

    private void exportLog(Uri uri) {
        io.execute(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                StringBuilder sb = new StringBuilder();
                sb.append("== host ==\n").append(HostInfo.collect(this)).append("\n== app log ==\n");
                synchronized (lines) { for (String l : lines) sb.append(l).append('\n'); }
                File qemuLog = new File(QemuLauncher.runtimeDir(this), "qemu.log");
                File serial = QemuLauncher.serialLog(this);
                for (File f : new File[]{qemuLog, serial}) {
                    sb.append("\n== ").append(f.getName()).append(" ==\n");
                    if (f.exists()) sb.append(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                }
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                log("log exported");
            } catch (IOException e) { log("export failed: " + e); }
        });
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && service != null && service.isRunning()) { vnc.sendKey(0xff1b, true); return true; }
        return super.onKeyDown(keyCode, event);
    }
    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && service != null && service.isRunning()) { vnc.sendKey(0xff1b, false); toast("BACK sent to guest as Escape; use Stop guest to exit"); return true; }
        return super.onKeyUp(keyCode, event);
    }

    @Override public void onLine(String line) { log(line); }
    @Override public void onExit(int code) { ui.post(() -> vnc.disconnect()); }
    @Override public void onVncStatus(String s) { log(s); }

    private void toast(String s) { ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    private void log(String s) {
        String stamped = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + s;
        synchronized (lines) { lines.addLast(stamped); while (lines.size() > 2000) lines.removeFirst(); }
        try (FileOutputStream out = new FileOutputStream(appLog, true)) { out.write((stamped + "\n").getBytes(StandardCharsets.UTF_8)); } catch (IOException ignored) {}
        ui.post(() -> {
            StringBuilder sb = new StringBuilder();
            synchronized (lines) { int skip = Math.max(0, lines.size() - 400); int i = 0; for (String l : lines) { if (i++ < skip) continue; sb.append(l).append('\n'); } }
            logView.setText(sb);
            logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    @Override protected void onDestroy() {
        vnc.disconnect();
        if (service != null) service.removeListener(this);
        unbindService(conn);
        io.shutdown();
        super.onDestroy();
    }
}

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
    private static final int REQ_KERNEL = 1, REQ_INITRD = 2, REQ_DISK = 3, REQ_APK = 4, REQ_EXPORT = 5, REQ_ANDROID_ZIP = 6;

    private TextView logView;
    private ScrollView logScroll;
    private VncView vnc;
    private static final AudioPlayer audio = new AudioPlayer();   // static: keeps playing across activity recreation
    // static: the on-screen log survives activity recreation (rotation)
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
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
        ((Button) findViewById(R.id.btn_boottest)).setOnClickListener(v -> bootTest());
        ((Button) findViewById(R.id.btn_kernel)).setOnClickListener(v -> pick(REQ_KERNEL));
        ((Button) findViewById(R.id.btn_initrd)).setOnClickListener(v -> pick(REQ_INITRD));
        ((Button) findViewById(R.id.btn_disk)).setOnClickListener(v -> pick(REQ_DISK));
        ((Button) findViewById(R.id.btn_apk)).setOnClickListener(v -> pick(REQ_APK));
        ((Button) findViewById(R.id.btn_start)).setOnClickListener(v -> startGuest());
        ((Button) findViewById(R.id.btn_stop)).setOnClickListener(v -> stopGuest());
        ((Button) findViewById(R.id.btn_android_get)).setOnClickListener(v -> androidGuestMenu());
        ((Button) findViewById(R.id.btn_android_start)).setOnClickListener(v -> startAndroid());
        ((Button) findViewById(R.id.btn_fullscreen)).setOnClickListener(v -> setFullscreen(true));
        // Immersive mode ends when the user swipes in from a screen edge: use that to leave fullscreen.
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(vis -> {
            if (fullscreen && (vis & android.view.View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) setFullscreen(false);
        });
        ((Button) findViewById(R.id.btn_export)).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain")
                    .putExtra(Intent.EXTRA_TITLE, "androidware-log-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt");
            startActivityForResult(i, REQ_EXPORT);
        });

        log("androidware 0.0.8-m1; profile=" + profile.name + " kernel=" + profile.kernel + " initrd=" + profile.initrd + " disk=" + profile.disk);
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
        if (req == REQ_ANDROID_ZIP) {
            log("installing Android guest from " + ImportManager.displayName(this, uri) + " ...");
            io.execute(() -> {
                try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("cannot open " + uri);
                    AndroidGuest.installFromZip(this, in, this::progress);
                    log("Android guest installed in " + AndroidGuest.dir(this));
                } catch (IOException e) { log("Android guest install failed: " + e); }
            });
            return;
        }
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
                    case REQ_APK: {
                        log(ApkInspector.inspect(this, f));
                        String pkg = AndroidGuest.registerGame(this, f);
                        if (pkg != null) log("game " + pkg + " registered: it is installed and started in the Android guest on the next \"Start Android\"");
                        else log("this APK could not be parsed; it will not be installed in the guest");
                        break;
                    }
                }
                profile.save(getFilesDir());
                log("imported -> " + f);
            } catch (IOException e) { log("import failed: " + e); }
        });
    }

    /** Boots the bundled test guest (arm64 Linux kernel + initramfs running a 64-bit and a 32-bit bionic bench). */
    private void bootTest() {
        if (service != null && service.isRunning()) { toast("guest already running"); return; }
        io.execute(() -> {
            try {
                File dir = ImportManager.guestDir(this);
                File k = new File(dir, "test-vmlinuz-arm64"), i = new File(dir, "test-initrd.img");
                copyAsset("testguest/vmlinuz-arm64", k);
                copyAsset("testguest/initrd-test.img", i);
                GuestProfile t = new GuestProfile();
                t.name = "boottest"; t.kernel = k.getAbsolutePath(); t.initrd = i.getAbsolutePath();
                log("== boot test: starting AArch64 guest; it powers off by itself when done ==");
                bootStartMs = System.currentTimeMillis();
                startForegroundService(new Intent(this, EngineService.class));
                service.start(t);
                ui.post(() -> vnc.connect(QemuLauncher.vncSocket(this)));
            } catch (Exception e) { log("boot test failed: " + e); }
        });
    }

    private volatile long bootStartMs;
    private boolean fullscreen;

    /** Fullscreen: only the guest display, system bars hidden. Swipe in from a screen edge to leave. */
    @SuppressWarnings("deprecation")
    private void setFullscreen(boolean on) {
        fullscreen = on;
        findViewById(R.id.panel).setVisibility(on ? android.view.View.GONE : android.view.View.VISIBLE);
        getWindow().getDecorView().setSystemUiVisibility(on
                ? android.view.View.SYSTEM_UI_FLAG_FULLSCREEN | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                  | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                : android.view.View.SYSTEM_UI_FLAG_VISIBLE);
        if (on) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            toast("Fullscreen: swipe in from the top or bottom edge to leave");
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void progress(String what, long done, long total) {
        log("  " + what + ": " + (done >> 20) + "/" + (total > 0 ? (total >> 20) : "?") + " MiB");
    }

    /** Get / import / reset the Android 7.0 (API 24, armeabi-v7a) emulator image. */
    private void androidGuestMenu() {
        String[] items = {
                "Download from dl.google.com (" + (AndroidGuest.ZIP_SIZE >> 20) + " MiB)",
                "Import armeabi-v7a-24_r07.zip",
                "Factory reset guest data",
                "Resolution… (now " + AndroidGuest.DISPLAY_LABELS[AndroidGuest.displayMode(this)] + ")"};
        new android.app.AlertDialog.Builder(this)
                .setTitle("Android 7.0 guest (" + (AndroidGuest.isInstalled(this) ? "installed" : "not installed") + ")")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        new android.app.AlertDialog.Builder(this)
                                .setTitle("Download Android system image")
                                .setMessage("Downloads Google's official Android 7.0 ARM emulator system image:\n"
                                        + AndroidGuest.ZIP_URL + "\n\nIts use is governed by the Android Software "
                                        + "Development Kit License Agreement (developer.android.com/studio/terms). Continue?")
                                .setPositiveButton("Download", (d2, w2) -> io.execute(() -> {
                                    try {
                                        log("downloading " + AndroidGuest.ZIP_URL);
                                        AndroidGuest.download(this, this::progress);
                                        log("Android guest installed in " + AndroidGuest.dir(this));
                                    } catch (IOException e) { log("download failed: " + e); }
                                }))
                                .setNegativeButton(android.R.string.cancel, null).show();
                    } else if (which == 1) {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip");
                        startActivityForResult(i, REQ_ANDROID_ZIP);
                    } else if (which == 3) {
                        new android.app.AlertDialog.Builder(this).setTitle("Guest resolution (applies on next Start Android)")
                                .setSingleChoiceItems(AndroidGuest.DISPLAY_LABELS, AndroidGuest.displayMode(this), (d3, w3) -> {
                                    try { AndroidGuest.setDisplayMode(this, w3); log("guest resolution: " + AndroidGuest.DISPLAY_LABELS[w3] + " (restart the guest to apply)"); }
                                    catch (IOException e) { log("cannot save resolution: " + e); }
                                    d3.dismiss();
                                }).show();
                    } else {
                        if (service != null && service.isRunning()) { toast("stop the guest first"); return; }
                        io.execute(() -> {
                            try { AndroidGuest.resetData(this, this::progress); log("guest data reset"); }
                            catch (IOException e) { log("reset failed: " + e); }
                        });
                    }
                }).show();
    }

    private void startAndroid() {
        if (service == null) { toast("service not bound yet"); return; }
        if (service.isRunning()) { toast("guest already running"); return; }
        if (!AndroidGuest.isInstalled(this)) { toast("get the Android 7 guest first"); return; }
        io.execute(() -> {
            try {
                log("== Android 7.0 guest: booting (expect a few minutes on TCG) ==");
                bootStartMs = System.currentTimeMillis();
                startForegroundService(new Intent(this, EngineService.class));
                log("guest games: " + AndroidGuest.prepareBoot(this));
                service.start(AndroidGuest.profile(this));
                audio.start(QemuLauncher.audioSocket(this));
                ui.post(() -> vnc.connect(QemuLauncher.vncSocket(this)));
            } catch (IOException e) { log("start failed: " + e); }
        });
    }

    private void copyAsset(String asset, File dst) throws IOException {
        try (java.io.InputStream in = getAssets().open(asset); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
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
        audio.stop();
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
    @Override public void onExit(int code) {
        ui.post(() -> vnc.disconnect());
        audio.stop();
        if (bootStartMs != 0) { log("guest ran for " + (System.currentTimeMillis() - bootStartMs) + " ms wall"); bootStartMs = 0; }
        // Show what the guest printed on its serial console (last 40 lines).
        try {
            File serial = QemuLauncher.serialLog(this);
            if (serial.exists()) {
                java.util.List<String> all = java.nio.file.Files.readAllLines(serial.toPath(), java.nio.charset.StandardCharsets.ISO_8859_1);
                StringBuilder sb = new StringBuilder("-- guest serial console (tail) --\n");
                for (String l : all.subList(Math.max(0, all.size() - 40), all.size())) sb.append(l).append('\n');
                log(sb.toString());
            }
        } catch (IOException e) { log("cannot read serial log: " + e); }
    }
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

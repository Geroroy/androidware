package dev.androidware.app;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Android guest (M1): the official Android 7.0 (API 24) ARM emulator system image
 * "system-images;android-24;default;armeabi-v7a" from Google's SDK repository.
 *
 * It is a 32-bit ARMv7 userspace (abilist armeabi-v7a,armeabi) with a Linux 3.10 "ranchu" kernel.
 * It boots on QEMU 'virt' with goldfish=on (engine patch 0024: goldfish framebuffer, input and
 * battery devices). Nothing from the image is bundled in the APK: the user downloads it from
 * dl.google.com (or imports the same zip) and the files are extracted into app storage.
 * Use of the image is governed by the Android Software Development Kit License Agreement.
 */
public final class AndroidGuest {
    public static final String ZIP_URL =
            "https://dl.google.com/android/repository/sys-img/android/armeabi-v7a-24_r07.zip";
    public static final String ZIP_SHA1 = "3454546b4eed2d6c3dd06d47757d6da9f4176033";
    public static final long ZIP_SIZE = 283677512L;

    private static final String[] NEEDED = {"kernel-ranchu", "ramdisk.img", "system.img", "userdata.img"};

    public interface Progress { void onProgress(String what, long done, long total); }

    private AndroidGuest() {}

    public static File dir(Context ctx) { File d = new File(ctx.getFilesDir(), "android24"); d.mkdirs(); return d; }
    /** Pristine copy of userdata.img kept so "factory reset" does not need the zip again. */
    private static File pristineUserdata(Context ctx) { return new File(dir(ctx), "userdata.pristine.img"); }

    public static boolean isInstalled(Context ctx) {
        for (String n : NEEDED) if (!new File(dir(ctx), n).isFile()) return false;
        return new File(dir(ctx), "cache.img").isFile();
    }

    /** Imported game APKs: files/games/<package>.apk. The file named in files/games/launch.txt is started after boot. */
    public static File launchFile(Context ctx) { return new File(ImportManager.gamesDir(ctx), "launch.txt"); }

    /**
     * Registers an imported APK as a guest game: renames it to <package>.apk and marks it to be
     * started after the next boot. Returns the package name, or null if the APK cannot be parsed.
     */
    public static String registerGame(Context ctx, File apk) throws IOException {
        android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (pi == null || pi.packageName == null || !pi.packageName.matches("[A-Za-z0-9._]+")) return null;
        File dst = new File(ImportManager.gamesDir(ctx), pi.packageName + ".apk");
        if (!apk.equals(dst)) {
            dst.delete();
            if (!apk.renameTo(dst)) throw new IOException("rename failed: " + apk);
        }
        try (OutputStream o = new FileOutputStream(launchFile(ctx))) { o.write(pi.packageName.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        return pi.packageName;
    }

    /**
     * Prepares the per-boot media: overlay ramdisk (auto-install service), SD card image (created
     * once, kept across factory resets) and the transfer disk with every imported game APK.
     * Returns a description of what will be installed/started.
     */
    public static String prepareBoot(Context ctx) throws IOException {
        File d = dir(ctx);
        GuestMedia.buildRamdisk(new File(d, "ramdisk.img"), new File(d, "ramdisk-aw.img"), hostGpu(ctx), displaySize(ctx)[2]);
        File sd = new File(d, "sdcard.img");
        if (!sd.isFile()) {
            try (InputStream in = ctx.getAssets().open("androidguest/sdcard.img.gz")) { GuestMedia.extractSparseGz(in, sd); }
        }
        String launch = null;
        File lf = launchFile(ctx);
        if (lf.isFile()) launch = new String(java.nio.file.Files.readAllBytes(lf.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
        java.util.List<GuestMedia.Apk> apks = new java.util.ArrayList<>();
        StringBuilder sb = new StringBuilder();
        File[] files = ImportManager.gamesDir(ctx).listFiles();
        if (files != null) for (File f : files) {
            if (!f.getName().endsWith(".apk")) continue;
            String pkg = f.getName().substring(0, f.getName().length() - 4);
            if (!pkg.matches("[A-Za-z0-9._]+")) continue;
            boolean l = pkg.equals(launch);
            apks.add(new GuestMedia.Apk(f, pkg, l));
            sb.append(pkg).append(l ? " (auto-start)" : "").append(' ');
            int gl = requiredGles(ctx, f);
            if (gl >= 0x20000 && !hostGpu(ctx)) {
                sb.append("\n  WARNING: ").append(pkg).append(" requires OpenGL ES ").append(gl >> 16).append('.').append(gl & 0xffff)
                  .append(" but Graphics is Software (GLES 1.x only): it will stop at startup."
                        + " Switch \"Android 7 guest…\" -> Graphics to Host GPU.\n");
            }
        }
        GuestMedia.writeTransferDisk(new File(d, "transfer.img"), apks);
        // Game data (OBB): one FAT32 image, rebuilt only when the imported files change.
        java.util.List<GuestMedia.DataFile> data = new java.util.ArrayList<>();
        StringBuilder manifest = new StringBuilder();
        File[] pkgs = gameDataDir(ctx).listFiles();
        if (pkgs != null) for (File pd : pkgs) {
            File[] fs = pd.isDirectory() ? pd.listFiles() : null;
            if (fs != null) for (File f : fs) {
                if (!f.isFile() || f.getName().endsWith(".part")) continue;
                data.add(new GuestMedia.DataFile(pd.getName(), f.getName(), f));
                manifest.append(pd.getName()).append('/').append(f.getName()).append(' ').append(f.length()).append(' ').append(f.lastModified()).append('\n');
            }
        }
        File img = new File(d, "obb.img"), mf = new File(d, "obb.manifest");
        if (data.isEmpty()) { img.delete(); mf.delete(); }
        else {
            String old = mf.isFile() ? new String(java.nio.file.Files.readAllBytes(mf.toPath()), java.nio.charset.StandardCharsets.UTF_8) : "";
            if (!img.isFile() || !old.equals(manifest.toString())) {
                mf.delete();
                GuestMedia.writeFatImage(img, data);
                try (OutputStream o = new FileOutputStream(mf)) { o.write(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            }
            sb.append("\n  game data: ").append(manifest.toString().trim().replace("\n", "; "));
        }
        return apks.isEmpty() ? "no game APK imported" : sb.toString().trim();
    }

    /** Imported game data: files/gamedata/<package>/<file>, shown to the guest as /sdcard/Android/obb/<package>/<file>. */
    public static File gameDataDir(Context ctx) { File d = new File(ctx.getFilesDir(), "gamedata"); d.mkdirs(); return d; }

    private static final java.util.regex.Pattern OBB_NAME = java.util.regex.Pattern.compile("(main|patch)\\.\\d+\\.([A-Za-z0-9._]+)\\.obb");

    /**
     * Imports game data from a .obb file or a zip that contains .obb files (any folder layout).
     * The package is taken from the standard OBB file name "main.<version>.<package>.obb".
     * Returns a description of what was imported.
     */
    public static String importGameData(Context ctx, InputStream in, String displayName, Progress p) throws IOException {
        StringBuilder sb = new StringBuilder();
        if (displayName.toLowerCase(java.util.Locale.US).endsWith(".obb")) {
            sb.append(saveObb(ctx, in, displayName, -1, p));
        } else {
            try (ZipInputStream z = new ZipInputStream(in)) {
                ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    String base = e.getName().substring(e.getName().lastIndexOf('/') + 1);
                    if (e.isDirectory() || !base.toLowerCase(java.util.Locale.US).endsWith(".obb")) continue;
                    sb.append(saveObb(ctx, z, base, e.getSize(), p)).append(' ');
                }
            }
        }
        if (sb.length() == 0) throw new IOException("no .obb file found in " + displayName);
        return sb.toString().trim();
    }

    private static String saveObb(Context ctx, InputStream in, String name, long size, Progress p) throws IOException {
        java.util.regex.Matcher m = OBB_NAME.matcher(name);
        if (!m.matches()) throw new IOException("not a standard OBB file name (main.<version>.<package>.obb): " + name);
        File dir = new File(gameDataDir(ctx), m.group(2));
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File tmp = new File(dir, name + ".part"), dst = new File(dir, name);
        try (OutputStream out = new FileOutputStream(tmp)) { copy(in, out, name, size, p); }
        if (size >= 0 && tmp.length() != size) { tmp.delete(); throw new IOException("truncated: " + name); }
        if (dst.exists() && !dst.delete()) throw new IOException("cannot replace " + dst);
        if (!tmp.renameTo(dst)) throw new IOException("rename failed: " + tmp);
        return m.group(2) + "/" + name + " (" + dst.length() + " bytes)";
    }

    /** OpenGL ES version the APK's manifest requires (0x00020000 = 2.0), or 0 if it does not say. */
    static int requiredGles(Context ctx, File apk) {
        android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(),
                android.content.pm.PackageManager.GET_CONFIGURATIONS);
        int v = 0;
        if (pi != null && pi.reqFeatures != null) {
            for (android.content.pm.FeatureInfo f : pi.reqFeatures) if (f.name == null) v = Math.max(v, f.reqGlEsVersion);
        }
        return v;
    }

    /** Downloads the official zip from dl.google.com, verifying size and SHA-1, then installs it. */
    public static void download(Context ctx, Progress p) throws IOException {
        File zip = new File(ctx.getCacheDir(), "armeabi-v7a-24_r07.zip");
        File part = new File(ctx.getCacheDir(), "armeabi-v7a-24_r07.zip.part");
        HttpURLConnection c = (HttpURLConnection) new URL(ZIP_URL).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                copy(in, out, "download", ZIP_SIZE, p);
            }
        } finally {
            c.disconnect();
        }
        if (part.length() != ZIP_SIZE) throw new IOException("size mismatch: " + part.length());
        String sha1 = sha1(part);
        if (!ZIP_SHA1.equals(sha1)) { part.delete(); throw new IOException("SHA-1 mismatch: " + sha1); }
        if (!part.renameTo(zip)) throw new IOException("rename failed");
        try (InputStream in = new java.io.FileInputStream(zip)) {
            installFromZip(ctx, in, p);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            zip.delete();
        }
    }

    /** Extracts kernel, ramdisk, system and userdata from the image zip (streamed, no temp copy). */
    public static void installFromZip(Context ctx, InputStream zipStream, Progress p) throws IOException {
        File d = dir(ctx);
        int found = 0;
        try (ZipInputStream z = new ZipInputStream(zipStream)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String base = e.getName().substring(e.getName().lastIndexOf('/') + 1);
                boolean wanted = false;
                for (String n : NEEDED) if (n.equals(base)) wanted = true;
                if (!wanted || e.isDirectory()) continue;
                File tmp = new File(d, base + ".part");
                try (OutputStream out = new FileOutputStream(tmp)) { copy(z, out, base, e.getSize(), p); }
                File dst = new File(d, base);
                if (dst.exists() && !dst.delete()) throw new IOException("cannot replace " + dst);
                if (!tmp.renameTo(dst)) throw new IOException("rename failed: " + tmp);
                found++;
            }
        }
        if (found < NEEDED.length) throw new IOException("zip is missing files (found " + found + " of " + NEEDED.length + "); expected " + ZIP_URL);
        File ud = new File(d, "userdata.img");
        File pristine = pristineUserdata(ctx);
        pristine.delete();
        if (!ud.renameTo(pristine)) throw new IOException("rename failed: " + ud);
        resetData(ctx, p);
    }

    /** Factory reset: fresh /data from the pristine image and an empty /cache. */
    public static void resetData(Context ctx, Progress p) throws IOException {
        File d = dir(ctx);
        try (InputStream in = new java.io.FileInputStream(pristineUserdata(ctx));
             OutputStream out = new FileOutputStream(new File(d, "userdata.img"))) {
            copy(in, out, "userdata", pristineUserdata(ctx).length(), p);
        }
        try (InputStream in = new GZIPInputStream(ctx.getAssets().open("androidguest/cache.img.gz"));
             OutputStream out = new FileOutputStream(new File(d, "cache.img"))) {
            copy(in, out, "cache", 64L << 20, p);
        }
    }

    /** Guest display presets: label, width, height, dpi. The guest renders in software, so larger is slower. */
    public static final String[] DISPLAY_LABELS = {"320 x 480 (fastest)", "480 x 800 (default)", "540 x 960", "720 x 1280 (slowest)"};
    private static final int[][] DISPLAY_MODES = {{320, 480, 160}, {480, 800, 240}, {540, 960, 240}, {720, 1280, 320}};
    private static File displayFile(Context ctx) { return new File(dir(ctx), "display.txt"); }

    public static int displayMode(Context ctx) {
        try {
            int i = Integer.parseInt(new String(java.nio.file.Files.readAllBytes(displayFile(ctx).toPath()), java.nio.charset.StandardCharsets.UTF_8).trim());
            if (i >= 0 && i < DISPLAY_MODES.length) return i;
        } catch (IOException | NumberFormatException ignored) {}
        return 1;
    }

    public static void setDisplayMode(Context ctx, int i) throws IOException {
        try (OutputStream o = new FileOutputStream(displayFile(ctx))) { o.write(String.valueOf(i).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    }

    /** Graphics mode: false = guest software GLES 1.x (libagl), true = host GPU through the GLES renderer (GLES 1.x + 2.0). */
    private static File gfxFile(Context ctx) { return new File(dir(ctx), "gfx.txt"); }
    public static boolean hostGpu(Context ctx) { return gfxFile(ctx).isFile(); }
    public static void setHostGpu(Context ctx, boolean on) throws IOException {
        if (on) { try (OutputStream o = new FileOutputStream(gfxFile(ctx))) { o.write('1'); } } else gfxFile(ctx).delete();
    }
    /**
     * Guest CPU count. Each guest CPU is one host thread (multi-threaded TCG). PC (4 host cores):
     * smp 2 booted in 75-85 s, smp 4 in 142-150 s (host oversubscribed). Default: 4 on hosts with
     * 8 or more cores, else 2.
     */
    public static final int[] CPU_CHOICES = {1, 2, 3, 4, 6};
    private static File cpusFile(Context ctx) { return new File(dir(ctx), "cpus.txt"); }
    public static int cpus(Context ctx) {
        try {
            int n = Integer.parseInt(new String(java.nio.file.Files.readAllBytes(cpusFile(ctx).toPath()), java.nio.charset.StandardCharsets.UTF_8).trim());
            if (n >= 1 && n <= 8) return n;
        } catch (IOException | NumberFormatException ignored) {}
        return Runtime.getRuntime().availableProcessors() >= 8 ? 4 : 2;
    }
    public static void setCpus(Context ctx, int n) throws IOException {
        try (OutputStream o = new FileOutputStream(cpusFile(ctx))) { o.write(String.valueOf(n).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    }

    public static int[] displaySize(Context ctx) { return DISPLAY_MODES[displayMode(ctx)]; }

    /** Profile that boots the image: ARMv7 CPU, goldfish devices, virtio-mmio disks. */
    public static GuestProfile profile(Context ctx) {
        File d = dir(ctx);
        GuestProfile g = new GuestProfile();
        g.name = "android24";
        g.kind = GuestProfile.KIND_ANDROID_RANCHU;
        g.machine = "virt,highmem=off,goldfish=on";
        g.cpu = "cortex-a15";
        g.smp = cpus(ctx);
        g.memMb = 1024;
        g.kernel = new File(d, "kernel-ranchu").getAbsolutePath();
        g.initrd = new File(d, new File(d, "ramdisk-aw.img").isFile() ? "ramdisk-aw.img" : "ramdisk.img").getAbsolutePath();
        g.androidSystem = new File(d, "system.img").getAbsolutePath();
        g.androidCache = new File(d, "cache.img").getAbsolutePath();
        g.androidData = new File(d, "userdata.img").getAbsolutePath();
        if (new File(d, "sdcard.img").isFile() && new File(d, "transfer.img").isFile()) {
            g.androidSdcard = new File(d, "sdcard.img").getAbsolutePath();
            g.androidTransfer = new File(d, "transfer.img").getAbsolutePath();
            if (new File(d, "obb.img").isFile()) g.androidObb = new File(d, "obb.img").getAbsolutePath();
        }
        // qemu.gles=0: guest software GLES (libGLES_android) until the virgl path exists.
        boolean gpu = hostGpu(ctx);
        g.glSocket = gpu ? QemuLauncher.glSocket(ctx).getAbsolutePath() : null;
        g.cmdline = "console=ttyAMA0 androidboot.console=ttyAMA0 androidboot.hardware=ranchu qemu=1 qemu.gles=" + (gpu ? 1 : 0)
                + " androidboot.selinux=permissive";
        int[] m = DISPLAY_MODES[displayMode(ctx)];
        g.displayWidth = m[0];
        g.displayHeight = m[1];
        g.displayDpi = m[2];
        return g;
    }

    private static void copy(InputStream in, OutputStream out, String what, long total, Progress p) throws IOException {
        byte[] buf = new byte[1 << 20];
        long done = 0, last = 0; int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            done += n;
            if (p != null && done - last >= (16L << 20)) { p.onProgress(what, done, total); last = done; }
        }
        if (p != null) p.onProgress(what, done, total);
    }

    private static String sha1(File f) throws IOException {
        try (InputStream in = new java.io.FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[1 << 20]; int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }
}

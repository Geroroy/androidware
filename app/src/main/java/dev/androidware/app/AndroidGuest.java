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

    /** Profile that boots the image: ARMv7 CPU, goldfish devices, virtio-mmio disks. */
    public static GuestProfile profile(Context ctx) {
        File d = dir(ctx);
        GuestProfile g = new GuestProfile();
        g.name = "android24";
        g.kind = GuestProfile.KIND_ANDROID_RANCHU;
        g.machine = "virt,highmem=off,goldfish=on";
        g.cpu = "cortex-a15";
        g.smp = 2;   // PC (4 host cores): smp 2 booted in 75-85 s, smp 4 in 142-150 s; re-measure on device
        g.memMb = 1024;
        g.kernel = new File(d, "kernel-ranchu").getAbsolutePath();
        g.initrd = new File(d, "ramdisk.img").getAbsolutePath();
        g.androidSystem = new File(d, "system.img").getAbsolutePath();
        g.androidCache = new File(d, "cache.img").getAbsolutePath();
        g.androidData = new File(d, "userdata.img").getAbsolutePath();
        // qemu.gles=0: guest software GLES (libGLES_android) until the virgl path exists.
        g.cmdline = "console=ttyAMA0 androidboot.console=ttyAMA0 androidboot.hardware=ranchu qemu=1 qemu.gles=0 "
                + "androidboot.selinux=permissive";
        g.displayWidth = 480;
        g.displayHeight = 800;
        g.displayDpi = 240;
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

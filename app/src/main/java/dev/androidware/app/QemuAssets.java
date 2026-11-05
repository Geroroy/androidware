package dev.androidware.app;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Extracts QEMU's data directory (keymaps, firmware-free) from APK assets/qemu-share into files/. */
public final class QemuAssets {
    private QemuAssets() {}

    public static void ensureExtracted(Context ctx) throws IOException {
        File root = QemuLauncher.dataDir(ctx);
        long ver;
        try { ver = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).getLongVersionCode(); }
        catch (android.content.pm.PackageManager.NameNotFoundException e) { ver = 0; }
        File stamp = new File(root, ".stamp-" + ver);
        if (stamp.exists()) return;
        String[] top = ctx.getAssets().list("qemu-share");
        if (top != null && top.length > 0) copyDir(ctx.getAssets(), "qemu-share", root);
        //noinspection ResultOfMethodCallIgnored
        stamp.createNewFile();
    }

    private static void copyDir(AssetManager am, String src, File dst) throws IOException {
        String[] children = am.list(src);
        if (children == null || children.length == 0) {
            // leaf file
            //noinspection ResultOfMethodCallIgnored
            dst.getParentFile().mkdirs();
            try (InputStream in = am.open(src); OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        dst.mkdirs();
        for (String c : children) copyDir(am, src + "/" + c, new File(dst, c));
    }
}

package dev.androidware.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Copies a user-selected document (Storage Access Framework) into app-private storage, streaming
 * in fixed-size chunks so multi-GB APK/OBB/disk images are never held in memory.
 */
public final class ImportManager {
    public interface Progress { void onProgress(long copied, long total); }

    private ImportManager() {}

    public static File guestDir(Context ctx) { File d = new File(ctx.getFilesDir(), "guest"); d.mkdirs(); return d; }
    public static File gamesDir(Context ctx) { File d = new File(ctx.getFilesDir(), "games"); d.mkdirs(); return d; }

    public static String displayName(Context ctx, Uri uri) {
        ContentResolver cr = ctx.getContentResolver();
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) { String n = c.getString(0); if (n != null) return n.replaceAll("[^A-Za-z0-9._-]", "_"); }
        } catch (Exception ignored) {}
        return "import-" + System.currentTimeMillis();
    }

    public static long size(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {}
        return -1;
    }

    public static File copyToDir(Context ctx, Uri uri, File dir, String fileName, Progress progress) throws IOException {
        File dst = new File(dir, fileName);
        File tmp = new File(dir, fileName + ".part");
        long total = size(ctx, uri);
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException("cannot open " + uri);
            byte[] buf = new byte[1 << 20];
            long copied = 0; int n; long lastReport = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                copied += n;
                if (progress != null && copied - lastReport > (8L << 20)) { progress.onProgress(copied, total); lastReport = copied; }
            }
            out.flush();
            if (progress != null) progress.onProgress(copied, total);
        }
        if (dst.exists() && !dst.delete()) throw new IOException("cannot replace " + dst);
        if (!tmp.renameTo(dst)) throw new IOException("rename failed: " + tmp);
        return dst;
    }
}

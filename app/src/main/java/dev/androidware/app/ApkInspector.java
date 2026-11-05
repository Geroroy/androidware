package dev.androidware.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * M2 groundwork: reports what an old game APK actually requires, instead of guessing from its name.
 * Uses PackageManager for the manifest (package, versions, min/target SDK) and reads the zip central
 * directory for native ABIs and asset layout. The APK is never fully loaded into memory.
 */
public final class ApkInspector {
    private ApkInspector() {}

    public static String inspect(Context ctx, File apk) {
        StringBuilder sb = new StringBuilder();
        sb.append("apk: ").append(apk.getName()).append(" size=").append(apk.length()).append('\n');
        PackageManager pm = ctx.getPackageManager();
        PackageInfo pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_PERMISSIONS | PackageManager.GET_CONFIGURATIONS);
        if (pi == null) {
            sb.append("manifest: PackageManager could not parse this APK (very old or malformed manifest)\n");
        } else {
            ApplicationInfo ai = pi.applicationInfo;
            sb.append("package: ").append(pi.packageName).append('\n');
            sb.append("version: ").append(pi.versionName).append(" (").append(pi.getLongVersionCode()).append(")\n");
            if (ai != null) {
                sb.append("minSdk: ").append(ai.minSdkVersion).append("  targetSdk: ").append(ai.targetSdkVersion).append('\n');
                sb.append("flags: largeHeap=").append((ai.flags & ApplicationInfo.FLAG_LARGE_HEAP) != 0)
                  .append(" debuggable=").append((ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0).append('\n');
            }
            if (pi.requestedPermissions != null) {
                sb.append("permissions: ").append(pi.requestedPermissions.length).append('\n');
                for (String p : pi.requestedPermissions) sb.append("  ").append(p).append('\n');
            }
            if (pi.reqFeatures != null) for (android.content.pm.FeatureInfo f : pi.reqFeatures) {
                if (f.name == null) sb.append("  uses-feature: OpenGL ES 0x").append(Integer.toHexString(f.reqGlEsVersion)).append('\n');
                else sb.append("  uses-feature: ").append(f.name).append(f.flags != 0 ? " (required)" : "").append('\n');
            }
        }
        TreeMap<String, TreeSet<String>> libsByAbi = new TreeMap<>();
        long assetBytes = 0; int assetCount = 0; boolean hasDex = false; int dexCount = 0;
        try (ZipFile zf = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (n.startsWith("lib/") && n.endsWith(".so")) {
                    String[] parts = n.split("/");
                    if (parts.length >= 3) libsByAbi.computeIfAbsent(parts[1], k -> new TreeSet<>()).add(parts[2] + " (" + e.getSize() + "B)");
                } else if (n.startsWith("assets/")) { assetBytes += Math.max(0, e.getSize()); assetCount++; }
                else if (n.matches("classes\\d*\\.dex")) { hasDex = true; dexCount++; }
            }
        } catch (IOException e) {
            sb.append("zip: error ").append(e.getMessage()).append('\n');
        }
        sb.append("dex files: ").append(dexCount).append(hasDex ? "" : " (none! native-only?)").append('\n');
        sb.append("assets: ").append(assetCount).append(" files, ").append(assetBytes / (1024 * 1024)).append(" MiB\n");
        if (libsByAbi.isEmpty()) sb.append("native libs: none (pure Dalvik/Java app)\n");
        for (String abi : libsByAbi.keySet()) {
            sb.append("native ABI ").append(abi).append(": ").append(libsByAbi.get(abi).size()).append(" libs\n");
            for (String l : libsByAbi.get(abi)) sb.append("  ").append(l).append('\n');
        }
        boolean has64 = libsByAbi.containsKey("arm64-v8a");
        boolean has32 = libsByAbi.containsKey("armeabi-v7a") || libsByAbi.containsKey("armeabi");
        if (has32 && !has64) sb.append("=> 32-bit ARM native code only: needs AArch32 execution (guest VM with TCG, or a binary translator)\n");
        if (!has32 && !has64 && !libsByAbi.isEmpty()) sb.append("=> non-ARM native libs only (x86?): cannot run on ARM guest without x86 emulation\n");
        return sb.toString();
    }
}

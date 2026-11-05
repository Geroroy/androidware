package dev.androidware.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects the facts about the host device that decide which execution strategy is possible.
 * Everything here is observed on the device at runtime; nothing is assumed from the model name.
 */
public final class HostInfo {

    private HostInfo() {}

    public static String collect(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
          .append(" (").append(Build.DEVICE).append(")\n");
        sb.append("android: ").append(Build.VERSION.RELEASE).append(" sdk=").append(Build.VERSION.SDK_INT)
          .append(" build=").append(Build.DISPLAY).append('\n');
        sb.append("soc: ").append(Build.VERSION.SDK_INT >= 31 ? Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL : "n/a")
          .append(" hardware=").append(Build.HARDWARE).append('\n');
        sb.append("kernel: ").append(System.getProperty("os.version")).append('\n');
        sb.append("supported_abis (ro.product.cpu.abilist): ").append(join(Build.SUPPORTED_ABIS)).append('\n');
        sb.append("supported_32bit_abis: ").append(join(Build.SUPPORTED_32_BIT_ABIS)).append('\n');
        sb.append("supported_64bit_abis: ").append(join(Build.SUPPORTED_64_BIT_ABIS)).append('\n');
        sb.append("=> native 32-bit ARM execution: ")
          .append(Build.SUPPORTED_32_BIT_ABIS.length == 0 ? "NOT AVAILABLE (CPU/OS is 64-bit only; instruction translation required)" : "available")
          .append('\n');
        sb.append("cpuinfo: ").append(readCpuInfoSummary()).append('\n');
        sb.append("page size (sysconf): ").append(pageSize()).append('\n');

        PackageManager pm = ctx.getPackageManager();
        sb.append("feature android.software.virtualization_framework: ")
          .append(pm.hasSystemFeature("android.software.virtualization_framework")).append('\n');
        sb.append("/dev/kvm: ").append(probeDevice("/dev/kvm")).append('\n');
        sb.append("/dev/binder: ").append(probeDevice("/dev/binder")).append('\n');
        sb.append("nativeLibraryDir: ").append(ctx.getApplicationInfo().nativeLibraryDir).append('\n');
        File qemu = new File(ctx.getApplicationInfo().nativeLibraryDir, QemuLauncher.QEMU_BINARY);
        sb.append("engine binary present: ").append(qemu.exists()).append(" executable=").append(qemu.canExecute()).append('\n');
        return sb.toString();
    }

    private static String join(String[] a) {
        if (a == null || a.length == 0) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i]); }
        return sb.toString();
    }

    private static String probeDevice(String path) {
        File f = new File(path);
        if (!f.exists()) return "absent";
        StringBuilder sb = new StringBuilder("exists");
        sb.append(" r=").append(f.canRead()).append(" w=").append(f.canWrite());
        try (InputStream in = new FileInputStream(f)) {
            sb.append(" open=ok");
        } catch (IOException | SecurityException e) {
            sb.append(" open=").append(e.getClass().getSimpleName()).append(':').append(e.getMessage());
        }
        return sb.toString();
    }

    private static String readCpuInfoSummary() {
        try (InputStream in = new FileInputStream("/proc/cpuinfo")) {
            byte[] buf = new byte[16384];
            int n = in.read(buf);
            String s = new String(buf, 0, Math.max(n, 0));
            List<String> keep = new ArrayList<>();
            for (String line : s.split("\n")) {
                if (line.startsWith("Features") || line.startsWith("CPU part") || line.startsWith("CPU implementer")) {
                    if (!keep.contains(line)) keep.add(line);
                }
            }
            return keep.isEmpty() ? "(unreadable)" : keep.toString();
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    private static String pageSize() {
        try {
            return String.valueOf(android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE));
        } catch (Throwable t) {
            return "?";
        }
    }
}

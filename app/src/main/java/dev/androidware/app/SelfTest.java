package dev.androidware.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * On-device engine self-test that needs no guest image:
 *  1. runs the 64-bit static bionic benchmark natively (host baseline),
 *  2. runs the 32-bit (armeabi-v7a) static bionic benchmark under qemu-arm (TCG user mode),
 *  3. prints qemu-system-aarch64 --version to prove the system emulator loads and runs.
 * Compare the numbers with experiments/pc-tcg-aarch32/RESULTS.md. This is the first real measurement of
 * TCG's AArch64 backend on the target CPU; everything before it is a PC-side estimate.
 */
public final class SelfTest {
    private SelfTest() {}

    public static String run(Context ctx) {
        StringBuilder sb = new StringBuilder("== engine self-test ==\n");
        File dir = new File(ctx.getFilesDir(), "selftest"); dir.mkdirs();
        File lib = new File(ctx.getApplicationInfo().nativeLibraryDir);
        try {
            File b64 = extract(ctx, "selftest/bench-arm64-bionic", new File(dir, "bench-arm64-bionic"));
            File b32 = extract(ctx, "selftest/bench-arm32-bionic", new File(dir, "bench-arm32-bionic"));
            // NOTE: b64 lives in the app data dir; execve() of it is blocked for targetSdk>=29 (W^X policy), so we
            // run it through the system linker instead: /system/bin/linker64 <static-exe> is also blocked for
            // static binaries. Therefore the 64-bit baseline is executed by qemu-aarch64 only if present, else skipped.
            File qsys = new File(lib, QemuLauncher.QEMU_BINARY);
            File qarm = new File(lib, QemuLauncher.QEMU_ARM_USER_BINARY);
            sb.append("qemu-system present=").append(qsys.exists()).append(" qemu-arm present=").append(qarm.exists()).append('\n');
            if (qsys.exists()) sb.append(exec(dir, 20, qsys.getAbsolutePath(), "--version"));
            if (qarm.exists()) {
                sb.append("-- 32-bit ARM bench under qemu-arm (TCG, user mode) --\n");
                sb.append(exec(dir, 600, qarm.getAbsolutePath(), b32.getAbsolutePath(), "1"));
            } else {
                sb.append("qemu-arm not bundled in this build; skipping 32-bit user-mode test\n");
            }
            // Direct-mode prototype (docs/direct-mode.md): a 32-bit ARM program under qemu-arm whose GL calls are
            // host thunks straight into this device's GLES driver, and the same program built natively.
            File glNat = new File(lib, "libglbench-arm64.so");
            if (qarm.exists()) {
                File g32 = extract(ctx, "selftest/glbench32", new File(dir, "glbench32"));
                sb.append("-- direct mode: 32-bit ARM GL workload under qemu-arm, GL calls -> host GPU driver --\n");
                sb.append(exec(dir, 300, qarm.getAbsolutePath(), g32.getAbsolutePath()));
            }
            if (glNat.exists()) {
                sb.append("-- same GL workload, native 64-bit (baseline) --\n");
                sb.append(exec(dir, 120, glNat.getAbsolutePath()));
            }
            File nat = new File(lib, "libbench-arm64.so");
            if (nat.exists()) {
                sb.append("-- same bench, native 64-bit on this CPU (baseline) --\n");
                sb.append(exec(dir, 120, nat.getAbsolutePath(), "1"));
            }
        } catch (Exception e) {
            sb.append("self-test error: ").append(e).append('\n');
        }
        return sb.toString();
    }

    private static File extract(Context ctx, String asset, File dst) throws IOException {
        try (InputStream in = ctx.getAssets().open(asset); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        //noinspection ResultOfMethodCallIgnored
        dst.setExecutable(true, false);
        return dst;
    }

    private static String exec(File cwd, int timeoutSec, String... cmd) {
        StringBuilder sb = new StringBuilder("$ ").append(String.join(" ", Arrays.asList(cmd))).append('\n');
        long t0 = System.nanoTime();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true);
            pb.environment().put("TMPDIR", cwd.getAbsolutePath());
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line; int lines = 0;
                while ((line = r.readLine()) != null && lines++ < 200) sb.append(line).append('\n');
            }
            boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); sb.append("[timeout]\n"); }
            else sb.append("[exit ").append(p.exitValue()).append(", ").append((System.nanoTime() - t0) / 1000000).append(" ms wall]\n");
        } catch (Exception e) { sb.append("[error ").append(e).append("]\n"); }
        return sb.toString();
    }
}

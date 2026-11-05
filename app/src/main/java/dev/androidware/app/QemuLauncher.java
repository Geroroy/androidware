package dev.androidware.app;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the qemu-system-aarch64 command line for a {@link GuestProfile}.
 *
 * The engine is shipped as an ELF *executable* named like a shared library so that the package
 * manager extracts it into nativeLibraryDir with exec permission (apps targeting SDK >= 29 may not
 * execve() files inside their writable data directory, see AOSP system/sepolicy
 * private/app_neverallows.te "Block calling execve() on files in an apps home directory").
 */
public final class QemuLauncher {
    public static final String QEMU_BINARY = "libqemu-system-aarch64.so";
    /** QEMU user-mode emulator for 32-bit ARM Linux/bionic processes (approach D engine, also the on-device TCG benchmark). */
    public static final String QEMU_ARM_USER_BINARY = "libqemu-arm.so";

    private QemuLauncher() {}

    public static File runtimeDir(Context ctx) { File d = new File(ctx.getFilesDir(), "run"); d.mkdirs(); return d; }
    public static File vncSocket(Context ctx) { return new File(runtimeDir(ctx), "vnc.sock"); }
    public static File qmpSocket(Context ctx) { return new File(runtimeDir(ctx), "qmp.sock"); }
    public static File serialLog(Context ctx) { return new File(runtimeDir(ctx), "serial.log"); }
    /** Data dir with QEMU's share/ files (keymaps etc.), extracted from assets on first run. */
    public static File dataDir(Context ctx) { File d = new File(ctx.getFilesDir(), "qemu-share"); d.mkdirs(); return d; }

    public static List<String> build(Context ctx, GuestProfile p) {
        List<String> a = new ArrayList<>();
        a.add(new File(ctx.getApplicationInfo().nativeLibraryDir, QEMU_BINARY).getAbsolutePath());
        a.add("-L"); a.add(dataDir(ctx).getAbsolutePath());
        a.add("-name"); a.add("androidware-" + p.name);
        a.add("-M"); a.add(p.machine);
        a.add("-cpu"); a.add(p.cpu);
        a.add("-accel"); a.add("tcg,thread=multi");
        a.add("-smp"); a.add(String.valueOf(p.smp));
        a.add("-m"); a.add(p.memMb + "M");
        a.add("-no-reboot");
        a.add("-nodefaults");
        a.add("-display"); a.add("none");
        a.add("-vnc"); a.add("unix:" + vncSocket(ctx).getAbsolutePath());
        a.add("-qmp"); a.add("unix:" + qmpSocket(ctx).getAbsolutePath() + ",server=on,wait=off");
        a.add("-serial"); a.add("file:" + serialLog(ctx).getAbsolutePath());
        a.add("-monitor"); a.add("none");
        if (p.kernel != null) { a.add("-kernel"); a.add(p.kernel); }
        if (p.initrd != null) { a.add("-initrd"); a.add(p.initrd); }
        if (p.kernel != null && p.cmdline != null && !p.cmdline.isEmpty()) { a.add("-append"); a.add(p.cmdline); }
        if (p.disk != null) {
            a.add("-drive"); a.add("if=none,id=hd0,file=" + p.disk + ",format=raw,cache=writeback");
            a.add("-device"); a.add("virtio-blk-pci,drive=hd0");
        }
        if (p.virtioGpu) {
            a.add("-device"); a.add("virtio-gpu-pci,xres=" + p.displayWidth + ",yres=" + p.displayHeight);
            a.add("-device"); a.add("virtio-keyboard-pci");
            a.add("-device"); a.add("virtio-multitouch-pci"); // QEMU >= 8.1, fed via QMP input-send-event
        }
        if (p.network) {
            // Only when the user explicitly enables it. Requires a QEMU built with slirp (not in M0 build).
            a.add("-netdev"); a.add("user,id=net0");
            a.add("-device"); a.add("virtio-net-pci,netdev=net0");
        } else {
            a.add("-nic"); a.add("none");
        }
        return a;
    }
}

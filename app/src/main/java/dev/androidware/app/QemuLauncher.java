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
    public static File glSocket(Context ctx) { return new File(runtimeDir(ctx), "gl.sock"); }
    public static File audioSocket(Context ctx) { return new File(runtimeDir(ctx), "audio.sock"); }
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
        if (GuestProfile.KIND_ANDROID_RANCHU.equals(p.kind)) {
            // Order matters: the ranchu kernel names virtio-mmio disks in reverse creation order,
            // and fstab.ranchu expects vda=system, vdb=cache, vdc=data, vdd=SD card. vde = APK transfer disk
            // read by /aw-install.sh (GuestMedia). vde needs vdd, so both or neither.
            if (p.androidSdcard != null && p.androidTransfer != null) {
                if (p.androidObb != null) {   // created first = last name: vdf (game data, see GuestMedia.writeFatImage)
                    a.add("-drive"); a.add("if=none,id=obb,file=" + p.androidObb + ",format=raw,cache=writeback");
                    a.add("-device"); a.add("virtio-blk-device,drive=obb");
                }
                a.add("-drive"); a.add("if=none,id=xfer,file=" + p.androidTransfer + ",format=raw,readonly=on");
                a.add("-device"); a.add("virtio-blk-device,drive=xfer");
                a.add("-drive"); a.add("if=none,id=sd,file=" + p.androidSdcard + ",format=raw,cache=writeback");
                a.add("-device"); a.add("virtio-blk-device,drive=sd");
            }
            a.add("-chardev"); a.add("socket,id=aud,path=" + audioSocket(ctx).getAbsolutePath() + ",server=on,wait=off");
            a.add("-global"); a.add("goldfish-audio.chardev=aud");
            if (p.glSocket != null) { a.add("-global"); a.add("goldfish-pipe.socket=" + p.glSocket); }
            a.add("-drive"); a.add("if=none,id=data,file=" + p.androidData + ",format=raw,cache=writeback");
            a.add("-device"); a.add("virtio-blk-device,drive=data");
            a.add("-drive"); a.add("if=none,id=cache,file=" + p.androidCache + ",format=raw,cache=writeback");
            a.add("-device"); a.add("virtio-blk-device,drive=cache");
            a.add("-drive"); a.add("if=none,id=system,file=" + p.androidSystem + ",format=raw,readonly=on");
            a.add("-device"); a.add("virtio-blk-device,drive=system");
            a.add("-global"); a.add("goldfish-fb.width=" + p.displayWidth);
            a.add("-global"); a.add("goldfish-fb.height=" + p.displayHeight);
            a.add("-global"); a.add("goldfish-fb.dpi=" + p.displayDpi);
        }
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

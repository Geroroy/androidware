package dev.androidware.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Host-side preparation of guest media. Pure Java (no Android classes) so it can be run and tested
 * on a PC (see experiments/android-guest/GuestMediaCli.java).
 *
 *  - {@link #buildRamdisk}: appends a second cpio archive to the image's ramdisk. The kernel unpacks
 *    concatenated archives in order, so files in the overlay replace the originals. The overlay adds
 *    /aw-install.sh and a service to init.ranchu.rc that runs it once the guest has booted.
 *  - {@link #writeTransferDisk}: a raw "transfer disk" (guest /dev/block/vde) carrying game APKs.
 *    Layout: block 0 = text lines "AWPK1 <first block> <size in bytes> <package> <launch 0|1>",
 *    padded with newlines; each APK starts on a 512-byte block boundary.
 *  - {@link #extractSparseGz}: unpacks a gzip'd disk image without writing its zero blocks.
 */
public final class GuestMedia {
    private GuestMedia() {}

    public static final class Apk {
        public final File file; public final String pkg; public final boolean launch;
        public Apk(File file, String pkg, boolean launch) { this.file = file; this.pkg = pkg; this.launch = launch; }
    }

    /** Runs in the guest as root after sys.boot_completed=1. Progress goes to the kernel log (serial console). */
    static final String INSTALL_SCRIPT = "#!/system/bin/sh\n"
            + "# androidware: install (once) and optionally start the APKs on the transfer disk\n"
            + "D=/dev/block/vde\n"
            + "say() { echo \"androidware: $*\" > /dev/kmsg; }\n"
            + "[ -b $D ] || { say 'no transfer disk'; exit 0; }\n"
            + "dd if=$D bs=512 count=1 2>/dev/null | while read magic off size pkg launch; do\n"
            + "  [ \"$magic\" = AWPK1 ] || continue\n"
            + "  mark=/data/local/tmp/aw-$pkg-$size.done\n"
            + "  if [ ! -f $mark ]; then\n"
            + "    f=/data/local/tmp/aw-$pkg.apk\n"
            + "    blocks=$((size / 512)); rem=$((size % 512))\n"
            + "    dd if=$D of=$f bs=512 skip=$off count=$blocks 2>/dev/null\n"
            + "    [ $rem -gt 0 ] && dd if=$D bs=1 skip=$(((off + blocks) * 512)) count=$rem 2>/dev/null >> $f\n"
            + "    chmod 644 $f\n"
            + "    say \"installing $pkg ($size bytes)\"\n"
            + "    r=$(pm install -r $f 2>&1)\n"
            + "    say \"install $pkg: $r\"\n"
            + "    case \"$r\" in *Success*) touch $mark;; esac\n"
            + "    rm -f $f\n"
            + "  fi\n"
            + "  if [ \"$launch\" = 1 ]; then\n"
            + "    say \"starting $pkg\"\n"
            + "    monkey -p $pkg -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1 || say \"start $pkg failed\"\n"
            + "  fi\n"
            + "done\n"
            + "say 'install script done'\n";

    /**
     * Runs in the guest as root: copies app crashes (Java "FATAL EXCEPTION" traces and native
     * tombstones from the crash buffer, plus linker/EGL warnings) to the kernel log, i.e. the serial
     * console, where the app shows them and "Export log" includes them. The guest has no adb.
     */
    static final String CRASHLOG_SCRIPT = "#!/system/bin/sh\n"
            + "logcat -b crash -b main -v brief AndroidRuntime:E DEBUG:I libEGL:W linker:W '*:S' | while read -r l; do\n"
            + "  echo \"awcrash: $l\" > /dev/kmsg\n"
            + "done\n";

    static final String RC_ADDITION = "\n# --- androidware ---\n"
            + "service aw-install /system/bin/sh /aw-install.sh\n"
            + "    user root\n"
            + "    group root\n"
            + "    seclabel u:r:su:s0\n"
            + "    disabled\n"
            + "    oneshot\n"
            + "\n"
            + "service aw-crashlog /system/bin/sh /aw-crashlog.sh\n"
            + "    user root\n"
            + "    group root\n"
            + "    seclabel u:r:su:s0\n"
            + "    disabled\n"
            + "\n"
            + "on property:sys.boot_completed=1\n"
            + "    start aw-crashlog\n"
            + "    start aw-install\n";

    /**
     * @param hostGles true when the guest will use the host GLES renderer (kernel arg qemu.gles=1):
     *        advertises OpenGL ES 2.0 to apps via ro.opengles.version (the emulator normally sets
     *        this through its qemud boot-properties service, which this engine does not have).
     */
    public static void buildRamdisk(File original, File out, boolean hostGles, int dpi) throws IOException {
        byte[] orig = Files.readAllBytes(original.toPath());
        byte[] rc = cpioFind(gunzip(orig), "init.ranchu.rc");
        if (rc == null) throw new IOException("init.ranchu.rc not found in " + original);
        ByteArrayOutputStream cpio = new ByteArrayOutputStream();
        cpioAdd(cpio, "init.ranchu.rc", 0100750, (new String(rc, StandardCharsets.UTF_8) + RC_ADDITION).getBytes(StandardCharsets.UTF_8));
        if (hostGles) {
            byte[] dp = cpioFind(gunzip(orig), "default.prop");
            if (dp == null) throw new IOException("default.prop not found in " + original);
            cpioAdd(cpio, "default.prop", 0100644, (new String(dp, StandardCharsets.UTF_8) + "\nro.opengles.version=131072\nro.sf.lcd_density=" + dpi + "\n").getBytes(StandardCharsets.UTF_8));
        }
        cpioAdd(cpio, "aw-install.sh", 0100750, INSTALL_SCRIPT.getBytes(StandardCharsets.UTF_8));
        cpioAdd(cpio, "aw-crashlog.sh", 0100750, CRASHLOG_SCRIPT.getBytes(StandardCharsets.UTF_8));
        cpioAdd(cpio, "TRAILER!!!", 0, new byte[0]);
        try (OutputStream o = new FileOutputStream(out)) {
            o.write(orig);
            GZIPOutputStream gz = new GZIPOutputStream(o);
            gz.write(cpio.toByteArray());
            gz.finish();
        }
    }

    public static void writeTransferDisk(File out, List<Apk> apks) throws IOException {
        StringBuilder head = new StringBuilder();
        long block = 1;
        for (Apk a : apks) {
            if (!a.pkg.matches("[A-Za-z0-9._]+")) throw new IOException("bad package name: " + a.pkg);
            head.append("AWPK1 ").append(block).append(' ').append(a.file.length()).append(' ').append(a.pkg)
                    .append(' ').append(a.launch ? 1 : 0).append('\n');
            block += (a.file.length() + 511) / 512;
        }
        byte[] h = head.toString().getBytes(StandardCharsets.US_ASCII);
        if (h.length > 511) throw new IOException("too many APKs for one transfer disk");
        byte[] b0 = new byte[512];
        java.util.Arrays.fill(b0, (byte) '\n');
        System.arraycopy(h, 0, b0, 0, h.length);
        try (OutputStream o = new FileOutputStream(out)) {
            o.write(b0);
            byte[] buf = new byte[1 << 20];
            for (Apk a : apks) {
                long n = 0;
                try (InputStream in = Files.newInputStream(a.file.toPath())) {
                    int r; while ((r = in.read(buf)) > 0) { o.write(buf, 0, r); n += r; }
                }
                int pad = (int) ((512 - n % 512) % 512);
                o.write(new byte[pad]);
            }
            o.write(new byte[512]); // QEMU wants a non-empty image even with no APKs
        }
    }

    public static void extractSparseGz(InputStream gzStream, File out) throws IOException {
        try (InputStream in = new GZIPInputStream(gzStream, 1 << 16); RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
            raf.setLength(0);
            byte[] buf = new byte[1 << 16];
            long pos = 0;
            for (;;) {
                int n = 0, r;
                while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
                if (n == 0) break;
                boolean zero = true;
                for (int i = 0; i < n; i++) if (buf[i] != 0) { zero = false; break; }
                if (!zero) { raf.seek(pos); raf.write(buf, 0, n); }
                pos += n;
            }
            raf.setLength(pos);
        }
    }

    // ---- cpio "newc" ----

    private static byte[] gunzip(byte[] b) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(b)); ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1 << 16]; int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }

    private static int hex(byte[] b, int off) { return (int) Long.parseLong(new String(b, off, 8, StandardCharsets.US_ASCII), 16); }

    private static byte[] cpioFind(byte[] cpio, String name) throws IOException {
        int p = 0;
        while (p + 110 <= cpio.length) {
            if (!new String(cpio, p, 6, StandardCharsets.US_ASCII).equals("070701")) throw new IOException("not a newc cpio archive");
            int fileSize = hex(cpio, p + 54), nameSize = hex(cpio, p + 94);
            String n = new String(cpio, p + 110, nameSize - 1, StandardCharsets.UTF_8);
            int data = (p + 110 + nameSize + 3) & ~3;
            if (n.equals("TRAILER!!!")) break;
            if (n.equals(name)) return java.util.Arrays.copyOfRange(cpio, data, data + fileSize);
            p = (data + fileSize + 3) & ~3;
        }
        return null;
    }

    private static int inode = 900000;

    private static void cpioAdd(ByteArrayOutputStream o, String name, int mode, byte[] data) throws IOException {
        byte[] nm = (name + "\0").getBytes(StandardCharsets.UTF_8);
        String h = String.format("070701%08X%08X%08X%08X%08X%08X%08X%08X%08X%08X%08X%08X%08X",
                inode++, mode, 0, 0, 1, 0, data.length, 0, 0, 0, 0, nm.length, 0);
        o.write(h.getBytes(StandardCharsets.US_ASCII));
        o.write(nm);
        while (o.size() % 4 != 0) o.write(0);
        o.write(data);
        while (o.size() % 4 != 0) o.write(0);
    }
}

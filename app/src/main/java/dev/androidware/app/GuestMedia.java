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
            + "# the guest has a hardware keyboard device (host keys), which hides the on-screen keyboard by default\n"
            + "settings put secure show_ime_with_hard_keyboard 1\n"
            + "O=/dev/block/vdf\n"
            + "if [ -b $O ]; then\n"
            + "  # game data (OBB) image built by the app: apps see it as /sdcard/Android/obb/<package>/\n"
            + "  mkdir -p /data/media/obb\n"
            + "  if mount -t vfat -o rw,uid=1023,gid=1023,fmask=0007,dmask=0007 $O /data/media/obb; then\n"
            + "    say \"game data mounted: $(ls /data/media/obb | tr '\\n' ' ')\"\n"
            + "  else say 'game data mount FAILED'; fi\n"
            + "fi\n"
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
            + "logcat -b crash -b main -v brief AndroidRuntime:E DEBUG:I libEGL:W EGL_emulation:E chromium:F linker:W '*:S' | while read -r l; do\n"
            + "  echo \"awcrash: $l\" > /dev/kmsg\n"
            + "done\n";

    /**
     * Runs in the guest as root: once a minute writes the busiest guest threads (5 s sample) to the
     * kernel log / serial console ("awperf:" lines, only in "Export log"), to tell whether a slow game
     * is limited by its own code (user time) or by the kernel/GL pipe (system time).
     */
    static final String PERF_SCRIPT = "#!/system/bin/sh\n"
            + "while true; do\n"
            + "  sleep 55\n"
            + "  top -n 1 -d 5 -m 10 -t | while read -r l; do [ -n \"$l\" ] && echo \"awperf: $l\" > /dev/kmsg; done\n"
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
            + "service aw-perf /system/bin/sh /aw-perf.sh\n"
            + "    user root\n"
            + "    group root\n"
            + "    seclabel u:r:su:s0\n"
            + "    disabled\n"
            + "\n"
            + "on property:sys.boot_completed=1\n"
            + "    start aw-crashlog\n"            + "    start aw-perf\n"
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
        cpioAdd(cpio, "aw-perf.sh", 0100750, PERF_SCRIPT.getBytes(StandardCharsets.UTF_8));
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

    /** A file to place in the game-data image: /<dir>/<name>. */
    public static final class DataFile {
        public final String dir, name; public final File src;
        public DataFile(String dir, String name, File src) { this.dir = dir; this.name = name; this.src = src; }
    }

    /**
     * Writes a FAT32 image (guest /dev/block/vdf) holding game data files in one directory level:
     * /<package>/<file>. The guest mounts it on /data/media/obb, which Android's storage daemon
     * exposes to apps as /sdcard/Android/obb. Long file names are stored as VFAT LFN entries.
     * Clusters are 4 KiB and every file is contiguous; the image file is sparse where unused.
     */
    public static void writeFatImage(File out, List<DataFile> files) throws IOException {
        final int SEC = 512, SPC = 8, CL = SEC * SPC, RSVD = 32;
        List<String> dirs = new java.util.ArrayList<>();
        for (DataFile f : files) {
            if (f.src.length() >= 0xFFFFFFFFL) throw new IOException("file too large for FAT32: " + f.name);
            if (!dirs.contains(f.dir)) dirs.add(f.dir);
        }
        // directory contents (32-byte entries), built after cluster numbers are known
        int rootEntries = 0;
        for (String d : dirs) rootEntries += 1 + (d.length() + 12) / 13;
        int[] dirEntries = new int[dirs.size()];
        for (int i = 0; i < dirs.size(); i++) {
            dirEntries[i] = 2;
            for (DataFile f : files) if (f.dir.equals(dirs.get(i))) dirEntries[i] += 1 + (f.name.length() + 12) / 13;
        }
        long next = 2;
        long rootCl = next; int rootN = Math.max(1, (rootEntries * 32 + CL - 1) / CL); next += rootN;
        long[] dirCl = new long[dirs.size()]; int[] dirN = new int[dirs.size()];
        for (int i = 0; i < dirs.size(); i++) { dirCl[i] = next; dirN[i] = (dirEntries[i] * 32 + CL - 1) / CL; next += dirN[i]; }
        long[] fileCl = new long[files.size()]; long[] fileN = new long[files.size()];
        for (int i = 0; i < files.size(); i++) {
            fileN[i] = (files.get(i).src.length() + CL - 1) / CL;
            fileCl[i] = fileN[i] == 0 ? 0 : next; next += fileN[i];
        }
        long clusters = Math.max(next - 2 + 4096, 66000);   // FAT32 needs >= 65525 clusters; 16 MiB spare
        if (clusters + 2 > Integer.MAX_VALUE / 4) throw new IOException("game data too large");
        long fatSecs = ((clusters + 2) * 4 + SEC - 1) / SEC;
        long dataStart = (RSVD + 2 * fatSecs) * SEC;
        long totalSecs = RSVD + 2 * fatSecs + clusters * SPC;

        java.nio.ByteBuffer fat = java.nio.ByteBuffer.allocate((int) (fatSecs * SEC)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        fat.putInt(0, 0x0FFFFFF8); fat.putInt(4, 0x0FFFFFFF);
        fatChain(fat, rootCl, rootN);
        for (int i = 0; i < dirs.size(); i++) fatChain(fat, dirCl[i], dirN[i]);
        for (int i = 0; i < files.size(); i++) fatChain(fat, fileCl[i], fileN[i]);

        java.nio.ByteBuffer bs = java.nio.ByteBuffer.allocate(SEC).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bs.put(new byte[]{(byte) 0xEB, 0x58, (byte) 0x90}).put("MSWIN4.1".getBytes(StandardCharsets.US_ASCII));
        bs.putShort((short) SEC).put((byte) SPC).putShort((short) RSVD).put((byte) 2).putShort((short) 0).putShort((short) 0)
          .put((byte) 0xF8).putShort((short) 0).putShort((short) 63).putShort((short) 255).putInt(0).putInt((int) totalSecs)
          .putInt((int) fatSecs).putShort((short) 0).putShort((short) 0).putInt((int) rootCl).putShort((short) 1).putShort((short) 6);
        bs.position(64);
        bs.put((byte) 0x80).put((byte) 0).put((byte) 0x29).putInt(0x41570001)
          .put("NO NAME    ".getBytes(StandardCharsets.US_ASCII)).put("FAT32   ".getBytes(StandardCharsets.US_ASCII));
        bs.put(510, (byte) 0x55).put(511, (byte) 0xAA);
        java.nio.ByteBuffer fi = java.nio.ByteBuffer.allocate(SEC).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        fi.putInt(0, 0x41615252).putInt(484, 0x61417272).putInt(488, -1).putInt(492, -1).putInt(508, 0xAA550000);

        int[] alias = {0};
        ByteArrayOutputStream root = new ByteArrayOutputStream();
        for (int i = 0; i < dirs.size(); i++) fatEntry(root, dirs.get(i), true, dirCl[i], 0, alias);
        try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
            raf.setLength(0);
            raf.setLength(totalSecs * SEC);
            raf.seek(0); raf.write(bs.array()); raf.write(fi.array());
            raf.seek(6L * SEC); raf.write(bs.array()); raf.write(fi.array());
            raf.seek((long) RSVD * SEC); raf.write(fat.array()); raf.write(fat.array());
            raf.seek(dataStart + (rootCl - 2) * CL); raf.write(root.toByteArray());
            for (int i = 0; i < dirs.size(); i++) {
                ByteArrayOutputStream d = new ByteArrayOutputStream();
                fatShort(d, ".          ", true, dirCl[i], 0);
                fatShort(d, "..         ", true, 0, 0);
                for (int j = 0; j < files.size(); j++) {
                    DataFile f = files.get(j);
                    if (f.dir.equals(dirs.get(i))) fatEntry(d, f.name, false, fileCl[j], f.src.length(), alias);
                }
                raf.seek(dataStart + (dirCl[i] - 2) * CL); raf.write(d.toByteArray());
            }
            byte[] buf = new byte[1 << 20];
            for (int j = 0; j < files.size(); j++) {
                if (fileN[j] == 0) continue;
                raf.seek(dataStart + (fileCl[j] - 2) * CL);
                long n = 0;
                try (InputStream in = Files.newInputStream(files.get(j).src.toPath())) {
                    int r; while ((r = in.read(buf)) > 0) { raf.write(buf, 0, r); n += r; }
                }
                if (n != files.get(j).src.length()) throw new IOException("file changed while copying: " + files.get(j).name);
            }
        }
    }

    private static void fatChain(java.nio.ByteBuffer fat, long first, long n) {
        for (long c = first; c < first + n; c++) fat.putInt((int) (c * 4), c == first + n - 1 ? 0x0FFFFFFF : (int) (c + 1));
    }

    private static void fatShort(ByteArrayOutputStream o, String name11, boolean dir, long cluster, long size) {
        java.nio.ByteBuffer e = java.nio.ByteBuffer.allocate(32).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        e.put(name11.getBytes(StandardCharsets.US_ASCII)).put((byte) (dir ? 0x10 : 0x20));
        e.putShort(20, (short) (cluster >>> 16)).putShort(22, (short) 0).putShort(24, (short) 0x5B6A)   // 2025-11-10
         .putShort(26, (short) cluster).putInt(28, (int) size);
        e.putShort(16, (short) 0x5B6A).putShort(18, (short) 0x5B6A);
        o.write(e.array(), 0, 32);
    }

    /** One directory entry with a VFAT long name; the 8.3 alias is a unique generated name. */
    private static void fatEntry(ByteArrayOutputStream o, String name, boolean dir, long cluster, long size, int[] alias) throws IOException {
        if (name.isEmpty() || name.length() > 255 || !name.matches("[A-Za-z0-9._-]+")) throw new IOException("bad file name: " + name);
        String shortName = String.format("AW%06d   ", ++alias[0]);
        int sum = 0;
        for (int i = 0; i < 11; i++) sum = (((sum & 1) << 7) + (sum >> 1) + shortName.charAt(i)) & 0xff;
        int parts = (name.length() + 12) / 13;
        for (int p = parts; p >= 1; p--) {
            java.nio.ByteBuffer e = java.nio.ByteBuffer.allocate(32).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            e.put(0, (byte) (p | (p == parts ? 0x40 : 0))).put(11, (byte) 0x0F).put(13, (byte) sum);
            int[] pos = {1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30};
            for (int k = 0; k < 13; k++) {
                int idx = (p - 1) * 13 + k;
                e.putShort(pos[k], idx < name.length() ? (short) name.charAt(idx) : idx == name.length() ? (short) 0 : (short) 0xFFFF);
            }
            o.write(e.array(), 0, 32);
        }
        fatShort(o, shortName, dir, cluster, size);
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

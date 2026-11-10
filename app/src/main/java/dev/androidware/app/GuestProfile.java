package dev.androidware.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Per-guest configuration persisted as JSON under files/profiles/. */
public final class GuestProfile {
    /** Generic Linux guest: -kernel/-initrd/-drive on virtio-pci. */
    public static final String KIND_LINUX = "linux";
    /** Android emulator image with a "ranchu" kernel: goldfish devices, virtio-mmio disks data/cache/system. */
    public static final String KIND_ANDROID_RANCHU = "android-ranchu";

    public String name = "default";
    public String kind = KIND_LINUX;
    public String machine = "virt";
    public String cpu = "cortex-a57";   // has AArch32 EL0 in QEMU's model -> 32-bit guest userspace works
    public int smp = 2;
    public int memMb = 1024;
    public String kernel;                // absolute paths inside app storage, or null
    public String initrd;
    public String disk;
    public String cmdline = "console=ttyAMA0 rdinit=/init loglevel=7";
    public boolean network = false;      // guest networking OFF by default
    public boolean virtioGpu = false;    // needs guest driver; off for the serial-console smoke test
    public int displayWidth = 1280;
    public int displayHeight = 720;
    public int displayDpi = 240;
    public String androidSystem, androidCache, androidData;   // KIND_ANDROID_RANCHU only
    public String androidSdcard, androidTransfer;             // optional: guest SD card (vdd), APK transfer disk (vde)

    public static File dir(File filesDir) { File d = new File(filesDir, "profiles"); d.mkdirs(); return d; }

    public static GuestProfile load(File filesDir, String name) {
        File f = new File(dir(filesDir), name + ".json");
        GuestProfile p = new GuestProfile();
        p.name = name;
        if (!f.exists()) return p;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0; while (off < b.length) { int n = in.read(b, off, b.length - off); if (n < 0) break; off += n; }
            JSONObject o = new JSONObject(new String(b, 0, off, StandardCharsets.UTF_8));
            p.machine = o.optString("machine", p.machine);
            p.cpu = o.optString("cpu", p.cpu);
            p.smp = o.optInt("smp", p.smp);
            p.memMb = o.optInt("memMb", p.memMb);
            p.kernel = o.optString("kernel", null);
            p.initrd = o.optString("initrd", null);
            p.disk = o.optString("disk", null);
            p.cmdline = o.optString("cmdline", p.cmdline);
            p.network = o.optBoolean("network", false);
            p.virtioGpu = o.optBoolean("virtioGpu", false);
            p.displayWidth = o.optInt("displayWidth", p.displayWidth);
            p.displayHeight = o.optInt("displayHeight", p.displayHeight);
            p.displayDpi = o.optInt("displayDpi", p.displayDpi);
            p.kind = o.optString("kind", p.kind);
            p.androidSystem = o.optString("androidSystem", null);
            p.androidCache = o.optString("androidCache", null);
            p.androidData = o.optString("androidData", null);
            p.androidSdcard = o.optString("androidSdcard", null);
            p.androidTransfer = o.optString("androidTransfer", null);
        } catch (IOException | JSONException e) {
            // fall through with defaults
        }
        return p;
    }

    public void save(File filesDir) throws IOException {
        JSONObject o = new JSONObject();
        try {
            o.put("machine", machine).put("cpu", cpu).put("smp", smp).put("memMb", memMb)
             .put("kernel", kernel).put("initrd", initrd).put("disk", disk).put("cmdline", cmdline)
             .put("network", network).put("virtioGpu", virtioGpu)
             .put("displayWidth", displayWidth).put("displayHeight", displayHeight).put("displayDpi", displayDpi)
             .put("kind", kind).put("androidSystem", androidSystem).put("androidCache", androidCache)
             .put("androidData", androidData).put("androidSdcard", androidSdcard).put("androidTransfer", androidTransfer);
        } catch (JSONException e) { throw new IOException(e); }
        File f = new File(dir(filesDir), name + ".json");
        String text;
        try { text = o.toString(2); } catch (JSONException e) { throw new IOException(e); }
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
    }
}

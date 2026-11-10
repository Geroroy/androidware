import dev.androidware.app.GuestMedia;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/** PC driver for the app's GuestMedia class:
 *  ramdisk <orig> <out> | transfer <out> [<apk> <package> <launch 0|1>]... | sparse <in.gz> <out> */
public class GuestMediaCli {
    public static void main(String[] a) throws Exception {
        switch (a[0]) {
            case "ramdisk": GuestMedia.buildRamdisk(new File(a[1]), new File(a[2])); break;
            case "transfer": {
                List<GuestMedia.Apk> l = new ArrayList<>();
                for (int i = 2; i + 2 < a.length; i += 3) l.add(new GuestMedia.Apk(new File(a[i]), a[i + 1], a[i + 2].equals("1")));
                GuestMedia.writeTransferDisk(new File(a[1]), l); break;
            }
            case "sparse": try (FileInputStream in = new FileInputStream(a[1])) { GuestMedia.extractSparseGz(in, new File(a[2])); } break;
            default: throw new IllegalArgumentException(a[0]);
        }
    }
}

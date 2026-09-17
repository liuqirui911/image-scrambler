import com.eta.scramble.PngMeta;
import com.eta.scramble.Scrambler;
import java.util.Arrays;

public class Test {
    static int failures = 0;

    public static void main(String[] args) {
        int[][] sizes = {{1, 1}, {1, 7}, {9, 1}, {16, 16}, {320, 240}, {37, 91}, {1024, 768}};
        String[] keys = {"", "123456", "混淆图", "emoji😀密钥"};
        for (int[] s : sizes) {
            for (String key : keys) {
                for (int passes = 1; passes <= Scrambler.MAX_PASSES; passes++) {
                    int[] a = randomImage(s[0], s[1], 12345);
                    int[] orig = a.clone();
                    Scrambler.scramble(a, s[0], s[1], key, passes);
                    boolean changed = !Arrays.equals(orig, a);
                    boolean alphaKept = alphaKept(orig, a);
                    Scrambler.unscramble(a, s[0], s[1], key, passes);
                    boolean ok = Arrays.equals(orig, a);
                    if (!ok || !changed || !alphaKept) {
                        failures++;
                        System.out.println("FAIL size=" + s[0] + "x" + s[1] + " key='" + key + "' passes=" + passes
                                + " restored=" + ok + " changed=" + changed + " alpha=" + alphaKept);
                    }
                }
            }
        }
        System.out.println("lossless tests done, failures=" + failures);

        int[] a = randomImage(64, 64, 7);
        int[] orig = a.clone();
        Scrambler.scramble(a, 64, 64, "right", 2);
        int[] b = a.clone();
        Scrambler.unscramble(b, 64, 64, "wrong", 2);
        int same = 0;
        for (int i = 0; i < orig.length; i++) if (orig[i] == b[i]) same++;
        System.out.println("wrong key: identical pixels=" + same + "/" + orig.length);
        int[] c = a.clone();
        Scrambler.unscramble(c, 64, 64, "right", 3);
        int same2 = 0;
        for (int i = 0; i < orig.length; i++) if (orig[i] == c[i]) same2++;
        System.out.println("wrong passes: identical pixels=" + same2 + "/" + orig.length);

        byte[] fakePng = fakePng(200);
        byte[] withTag = PngMeta.insertText(fakePng, PngMeta.buildTag(3));
        String tag = PngMeta.readText(withTag);
        System.out.println("tag=" + tag + " passes=" + PngMeta.parsePasses(tag, 1) + " delta=" + (withTag.length - fakePng.length));
        if (!"v=1;passes=3".equals(tag) || PngMeta.parsePasses(tag, 1) != 3) failures++;
        if (PngMeta.insertText(withTag, "v=1;passes=1").length != withTag.length) { failures++; System.out.println("idempotent fail"); }
        if (PngMeta.readText(fakePng) != null) { failures++; System.out.println("clean png should have no tag"); }

        int[] big = randomImage(2000, 1500, 99);
        long t0 = System.nanoTime();
        Scrambler.scramble(big, 2000, 1500, "性能测试", 2);
        long t1 = System.nanoTime();
        Scrambler.unscramble(big, 2000, 1500, "性能测试", 2);
        long t2 = System.nanoTime();
        System.out.printf("3MP scramble=%.0fms unscramble=%.0fms%n", (t1 - t0) / 1e6, (t2 - t1) / 1e6);

        System.out.println(failures == 0 ? "ALL OK" : ("FAILURES=" + failures));
    }

    static boolean alphaKept(int[] a, int[] b) {
        for (int i = 0; i < a.length; i++) if ((a[i] >>> 24) != (b[i] >>> 24)) return false;
        return true;
    }

    static int[] randomImage(int w, int h, long seed) {
        java.util.Random r = new java.util.Random(seed);
        int[] p = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                p[y * w + x] = (0xFF000000) | ((x * 255 / Math.max(1, w)) << 16) | ((y * 255 / Math.max(1, h)) << 8) | r.nextInt(256);
        return p;
    }

    static byte[] fakePng(int size) {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        bo.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 0, 8);
        byte[] ihdr = {0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 0, 0, 0, 0};
        bo.write(ihdr, 0, ihdr.length);
        byte[] idatHead = {0, 0, 0, 4, 'I', 'D', 'A', 'T'};
        bo.write(idatHead, 0, idatHead.length);
        for (int i = 0; i < size; i++) bo.write(i & 0xFF);
        bo.write(new byte[] {0, 0, 0, 0}, 0, 4);
        byte[] iend = {0, 0, 0, 0, 'I', 'E', 'N', 'D', 0, 0, 0, 0};
        bo.write(iend, 0, iend.length);
        return bo.toByteArray();
    }
}

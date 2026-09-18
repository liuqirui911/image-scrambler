import com.eta.scramble.LegacyCodecs;
import com.eta.scramble.LegacyScrambler;
import com.eta.scramble.PopularCodecs;
import com.eta.scramble.Scrambler;

/**
 * 真机基准：同一台设备上对比 v1.6 旧实现与 v1.7 并行实现。
 * 用 d8 打成 dex 后在设备上跑 dalvikvm，测的是 ART 的真实表现。
 * 用法（设备上）：dalvikvm -cp scramble-bench.jar DeviceBench <w> <h>
 */
public class DeviceBench {
    public static void main(String[] args) {
        int w = args.length > 0 ? Integer.parseInt(args[0]) : 3000;
        int h = args.length > 1 ? Integer.parseInt(args[1]) : 2000;
        int rounds = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        long heap = Runtime.getRuntime().maxMemory();
        System.out.println("DeviceBench " + w + "x" + h + " (" + (w * (double) h / 1e6) + " MP), ART 线程="
                + Runtime.getRuntime().availableProcessors() + ", 堆上限=" + (heap / 1048576) + "MB");

        int[] src = img(w, h);

        // 预热
        PopularCodecs.transform(1, img(200, 200), 200, 200, "0.666", 1, true);
        LegacyCodecs.transform(1, img(200, 200), 200, 200, "0.666", 1, true);

        System.out.println("format        旧实现(v1.6)   新实现(v1.7)    加速比");
        for (int f = 0; f < PopularCodecs.FORMAT_COUNT; f++) {
            long oldT = Long.MAX_VALUE, newT = Long.MAX_VALUE;
            for (int r = 0; r < rounds; r++) {
                int[] a = src.clone();
                long t0 = System.nanoTime();
                LegacyCodecs.Result ra = LegacyCodecs.transform(f, a, w, h, "0.666", 1, true);
                long t1 = System.nanoTime();
                int[] b = src.clone();
                long t2 = System.nanoTime();
                PopularCodecs.Result rb = PopularCodecs.transform(f, b, w, h, "0.666", 1, true);
                long t3 = System.nanoTime();
                oldT = Math.min(oldT, t1 - t0);
                newT = Math.min(newT, t3 - t2);
                if (ra.width != rb.width || ra.height != rb.height || !same(ra.pixels, rb.pixels)) {
                    System.out.println("  !! fmt" + f + " 结果与旧实现不一致");
                }
            }
            System.out.println(pad(name(f)) + pad(ms(oldT)) + pad(ms(newT)) + ratio(oldT, newT));
        }

        {
            long oldT = Long.MAX_VALUE, newT = Long.MAX_VALUE;
            for (int r = 0; r < rounds; r++) {
                int[] cf1 = new int[1], cf2 = new int[1];
                double[] cs1 = new double[1], cs2 = new double[1];
                int[] a = src.clone();
                long t0 = System.nanoTime();
                LegacyCodecs.autoRestore(a, w, h, "0.666", 1, cf1, cs1);
                long t1 = System.nanoTime();
                int[] b = src.clone();
                long t2 = System.nanoTime();
                PopularCodecs.Result rb = PopularCodecs.autoRestore(b, w, h, "0.666", 1, cf2, cs2);
                long t3 = System.nanoTime();
                oldT = Math.min(oldT, t1 - t0);
                newT = Math.min(newT, t3 - t2);
                if (cf1[0] != cf2[0] || !same(a, b)) System.out.println("  !! autoRestore 结果不一致");
                break; // 自动识别是最重的一步，跑一次就够
            }
            System.out.println(pad("自动识别") + pad(ms(oldT)) + pad(ms(newT)) + ratio(oldT, newT));
        }

        // 本机格式（ChaCha20）多轮
        for (int passes = 1; passes <= 4; passes++) {
            long oldT = Long.MAX_VALUE, newT = Long.MAX_VALUE;
            for (int r = 0; r < rounds; r++) {
                int[] a = src.clone();
                long t0 = System.nanoTime();
                LegacyScrambler.scramble(a, w, h, "混淆图", passes);
                long t1 = System.nanoTime();
                int[] b = src.clone();
                long t2 = System.nanoTime();
                Scrambler.scramble(b, w, h, "混淆图", passes);
                long t3 = System.nanoTime();
                oldT = Math.min(oldT, t1 - t0);
                newT = Math.min(newT, t3 - t2);
                if (!same(a, b)) System.out.println("  !! scrambler passes=" + passes + " 结果不一致");
            }
            System.out.println(pad("本机格式 " + passes + " 轮") + pad(ms(oldT)) + pad(ms(newT)) + ratio(oldT, newT));
        }
        System.out.println("DONE");
    }

    static String name(int f) {
        String[] n = {"小番茄 Gilbert", "PicEncrypt 行列", "PicEncrypt 行模式", "行像素混淆", "全像素混淆", "方块混淆", "本机密钥流"};
        return n[f];
    }

    static String pad(String s) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < 34) b.append(' ');
        return b.toString();
    }

    static String ms(long ns) {
        return String.format("%8.0f ms", ns / 1e6);
    }

    static String ratio(long a, long b) {
        return String.format("   %5.2fx", a / (double) b);
    }

    static boolean same(int[] a, int[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    static int[] img(int w, int h) {
        int[] p = new int[w * h];
        java.util.Random r = new java.util.Random(7);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                p[y * w + x] = 0xFF000000 | ((x * 255 / Math.max(1, w)) << 16)
                        | ((y * 255 / Math.max(1, h)) << 8) | r.nextInt(256);
            }
        }
        return p;
    }
}

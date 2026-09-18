import com.eta.scramble.PopularCodecs;
import com.eta.scramble.Scrambler;
import java.util.Arrays;

/**
 * 性能基准：逐格式计时 + 往返正确性校验（重构时必须全绿）。
 * 用法：java -cp build/core Perf <w> <h> [rounds]
 */
public class Perf {
    public static void main(String[] args) {
        int w = Integer.parseInt(args[0]);
        int h = Integer.parseInt(args[1]);
        int rounds = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        int[] src = img(w, h);
        double mp = w * (double) h / 1e6;
        System.out.printf("== %dx%d (%.1f MP), threads=%d ==%n", w, h, mp, Runtime.getRuntime().availableProcessors());

        // 预热
        PopularCodecs.transform(1, img(200, 200), 200, 200, "0.666", 1, true);
        Scrambler.scramble(img(200, 200), 200, 200, "0.666", 1);

        for (int f = 0; f < PopularCodecs.FORMAT_COUNT; f++) {
            long enc = Long.MAX_VALUE, dec = Long.MAX_VALUE;
            boolean ok = true;
            int ow = 0, oh = 0;
            for (int r = 0; r < rounds; r++) {
                int[] work = src.clone();
                long t0 = System.nanoTime();
                PopularCodecs.Result e = PopularCodecs.transform(f, work, w, h, "0.666", 1, true);
                long t1 = System.nanoTime();
                PopularCodecs.Result d = PopularCodecs.transform(f, e.pixels, e.width, e.height, "0.666", 1, false);
                if (d.width != w || d.height != h) d = new PopularCodecs.Result(PopularCodecs.cropRegion(d.pixels, d.width, d.height, w, h), w, h);
                long t2 = System.nanoTime();
                enc = Math.min(enc, t1 - t0);
                dec = Math.min(dec, t2 - t1);
                ow = e.width; oh = e.height;
                if (!Arrays.equals(src, d.pixels)) ok = false;
            }
            System.out.printf("fmt%d %-14s enc=%6.0fms dec=%6.0fms out=%dx%d roundtrip=%s%n",
                    f, shortName(f), enc / 1e6, dec / 1e6, ow, oh, ok ? "ok" : "MISMATCH");
        }

        long best = Long.MAX_VALUE;
        int pick = -1;
        for (int r = 0; r < Math.max(1, rounds - 1); r++) {
            int[] cf = new int[1];
            double[] cs = new double[1];
            long t0 = System.nanoTime();
            PopularCodecs.Result res = PopularCodecs.autoRestore(src.clone(), w, h, "0.666", 1, cf, cs);
            long t1 = System.nanoTime();
            if (t1 - t0 < best) { best = t1 - t0; pick = cf[0]; }
            System.out.printf("autoRestore=%.0fms picked=fmt%d score=%.2f dims=%dx%d%n",
                    (t1 - t0) / 1e6, cf[0], cs[0], res.width, res.height);
        }
        System.out.printf("autoRestore best=%.0fms picked=fmt%d%n", best / 1e6, pick);
    }

    static String shortName(int f) {
        String[] n = {"tomato", "pic-rowcol", "pic-row", "row-pixel", "per-pixel", "block", "eta-chacha"};
        return n[f];
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

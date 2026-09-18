import com.eta.scramble.LegacyCodecs;
import com.eta.scramble.LegacyScrambler;
import com.eta.scramble.PopularCodecs;
import com.eta.scramble.Scrambler;
import java.util.Arrays;

/**
 * 并行重构的等价性对拍：新版实现必须与 v1.6 的冻结副本逐位相同。
 *
 * 覆盖 7 种格式 × 混淆/还原两个方向 × times 1-4 × 多种密钥与尺寸（含奇数尺寸、
 * 单像素、方块混淆需要补边的尺寸），另外校验自动识别的选中格式与评分。
 * 还原方向统一用混淆结果作为输入（方块混淆会补齐尺寸，这与 App 实际流程一致）。
 * 用法：java -cp build/core ParityTest
 */
public class ParityTest {
    static int checked = 0;
    static int diff = 0;

    public static void main(String[] args) {
        // 尺寸刻意跨过并行阈值（26 万像素）两侧，并含奇数边、单像素、方块补齐边
        int[][] sizes = {{1, 1}, {5, 3}, {9, 1}, {1, 9}, {37, 91}, {64, 64}, {31, 33},
                         {320, 240}, {129, 777}, {255, 1024}, {256, 1024}, {257, 1024},
                         {513, 512}, {700, 500}, {1024, 768}};
        String[] allKeys = {"0.666", "123456", "混淆图", "", "emoji😀"};
        int[] allTimes = {1, 2, 3, 4};

        for (int[] s : sizes) {
            int w = s[0], h = s[1];
            boolean big = (long) w * h > 300000;
            String[] keys = big ? new String[] {"0.666", "混淆图"} : allKeys;
            int[] timesList = big ? new int[] {1, 2} : allTimes;

            for (String key : keys) {
                for (int times : timesList) {
                    long seed = (long) w * 31 + h * 7 + times;
                    for (int f = 0; f < PopularCodecs.FORMAT_COUNT; f++) {
                        int[] img = img(w, h, seed);
                        String tag = "fmt=" + f + " " + w + "x" + h + " key='" + key + "' times=" + times;

                        // 混淆方向
                        PopularCodecs.Result ne = null;
                        LegacyCodecs.Result le = null;
                        Throwable nt = null, lt = null;
                        try { ne = PopularCodecs.transform(f, img, w, h, key, times, true); } catch (Throwable t) { nt = t; }
                        try { le = LegacyCodecs.transform(f, img, w, h, key, times, true); } catch (Throwable t) { lt = t; }
                        checked++;
                        if ((nt == null) != (lt == null)) {
                            fail(tag + " 混淆：一边抛异常 " + (nt == null ? lt.getClass().getSimpleName() : nt.getClass().getSimpleName()));
                            continue;
                        }
                        if (nt != null) continue; // 两边同样抛异常，视为行为一致
                        if (ne.width != le.width || ne.height != le.height || !Arrays.equals(ne.pixels, le.pixels)) {
                            fail(tag + " 混淆结果不同（尺寸 " + ne.width + "x" + ne.height + " vs " + le.width + "x" + le.height + "）");
                            continue;
                        }

                        // 还原方向：拿混淆结果当输入
                        PopularCodecs.Result nd = null;
                        LegacyCodecs.Result ld = null;
                        Throwable nt2 = null, lt2 = null;
                        try { nd = PopularCodecs.transform(f, ne.pixels, ne.width, ne.height, key, times, false); } catch (Throwable t) { nt2 = t; }
                        try { ld = LegacyCodecs.transform(f, le.pixels, le.width, le.height, key, times, false); } catch (Throwable t) { lt2 = t; }
                        checked++;
                        if ((nt2 == null) != (lt2 == null)) {
                            fail(tag + " 还原：一边抛异常 " + (nt2 == null ? lt2.getClass().getSimpleName() : nt2.getClass().getSimpleName()));
                            continue;
                        }
                        if (nt2 != null) continue;
                        if (nd.width != ld.width || nd.height != ld.height || !Arrays.equals(nd.pixels, ld.pixels)) {
                            fail(tag + " 还原结果不同（尺寸 " + nd.width + "x" + nd.height + " vs " + ld.width + "x" + ld.height + "）");
                            continue;
                        }

                        // 往返无损性（times=2 起累积误差不属于本测试范围，只看单次）
                        if (times == 1 && nd.width == w && nd.height == h && !Arrays.equals(nd.pixels, img)) {
                            long bad = 0;
                            for (int i = 0; i < img.length; i++) if (img[i] != nd.pixels[i]) bad++;
                            fail(tag + " 往返不无损，差异像素 " + bad + "/" + img.length);
                        }
                    }

                    // 自动识别：像素、选中格式、评分都要一致
                    int[] cf1 = new int[1], cf2 = new int[1];
                    double[] cs1 = new double[1], cs2 = new double[1];
                    int[] img = img(w, h, seed + 500);
                    PopularCodecs.Result a = PopularCodecs.autoRestore(img.clone(), w, h, key, times, cf1, cs1);
                    LegacyCodecs.Result b = LegacyCodecs.autoRestore(img.clone(), w, h, key, times, cf2, cs2);
                    checked++;
                    if (cf1[0] != cf2[0] || Math.abs(cs1[0] - cs2[0]) > 1e-9 || !Arrays.equals(a.pixels, b.pixels)) {
                        fail("autoRestore " + w + "x" + h + " key='" + key + "' times=" + times
                                + " 格式 " + cf1[0] + "/" + cf2[0] + " 评分 " + cs1[0] + "/" + cs2[0]);
                    }
                }
            }
        }

        // 并行确定性：同一输入重复跑必须逐位一致（线程调度不同也不允许出现差异）
        for (int[] s : new int[][] {{257, 1024}, {256, 1024}, {513, 512}, {1024, 768}, {2000, 130}}) {
            for (int f = 0; f < PopularCodecs.FORMAT_COUNT; f++) {
                for (int times = 1; times <= 2; times++) {
                    int[] im = img(s[0], s[1], 7777);
                    PopularCodecs.Result a = PopularCodecs.transform(f, im, s[0], s[1], "0.666", times, true);
                    PopularCodecs.Result b = PopularCodecs.transform(f, im, s[0], s[1], "0.666", times, true);
                    checked++;
                    if (a.width != b.width || a.height != b.height || !Arrays.equals(a.pixels, b.pixels)) {
                        fail("并行结果不确定 fmt=" + f + " " + s[0] + "x" + s[1] + " times=" + times);
                    }
                    int[] x = im.clone(), y = im.clone();
                    Scrambler.scramble(x, s[0], s[1], "混淆图", 2);
                    Scrambler.scramble(y, s[0], s[1], "混淆图", 2);
                    checked++;
                    if (!Arrays.equals(x, y)) fail("并行结果不确定 scramble " + s[0] + "x" + s[1] + " times=" + times);
                }
            }
        }

        // 本机密钥流混淆：直接对拍 Scrambler（含 1-4 轮）
        for (int[] s : sizes) {
            int w = s[0], h = s[1];
            String[] keys = (long) w * h > 300000 ? new String[] {"0.666", "混淆图"} : allKeys;
            for (String key : keys) {
                for (int passes = 1; passes <= Scrambler.MAX_PASSES; passes++) {
                    int[] base = img(w, h, 4242);
                    int[] x = base.clone(), y = base.clone();
                    LegacyScrambler.scramble(x, w, h, key, passes);
                    Scrambler.scramble(y, w, h, key, passes);
                    checked++;
                    if (!Arrays.equals(x, y)) fail("scramble " + w + "x" + h + " key='" + key + "' passes=" + passes);
                    LegacyScrambler.unscramble(x, w, h, key, passes);
                    Scrambler.unscramble(y, w, h, key, passes);
                    checked++;
                    if (!Arrays.equals(x, y)) fail("unscramble " + w + "x" + h + " key='" + key + "' passes=" + passes);
                    if (!Arrays.equals(y, base)) fail("scrambler 往返不无损 " + w + "x" + h + " passes=" + passes);
                }
            }
        }

        System.out.println("parity 用例=" + checked + "  不一致=" + diff);
        System.out.println(diff == 0 ? "PARITY OK" : "PARITY FAILED");
        if (diff != 0) System.exit(1);
    }

    static void fail(String msg) {
        diff++;
        if (diff <= 20) System.out.println("DIFF: " + msg);
    }

    static int[] img(int w, int h, long seed) {
        java.util.Random r = new java.util.Random(seed);
        int[] p = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                p[y * w + x] = 0xFF000000 | ((x * 255 / Math.max(1, w)) << 16)
                        | ((y * 255 / Math.max(1, h)) << 8) | r.nextInt(256);
            }
        }
        return p;
    }
}

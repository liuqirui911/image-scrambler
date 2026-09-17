import com.uiloalxise.ImageData;
import com.eta.scramble.PopularCodecs;
import java.util.Arrays;

/** 判定本应用实现与参考库的对应关系（含方向），并验证自洽往返。 */
public class CompatTest2 {

    static int hardFail = 0;

    public static void main(String[] args) {
        int[][] sizes = {{64, 48}, {33, 17}, {100, 100}, {32, 64}};
        String[] keys = {"0.666", "abc123"};

        for (int[] s : sizes) {
            int w = s[0], h = s[1];
            int[] src = image(w, h);

            // 小番茄
            pair("tomato " + w + "x" + h,
                    refEnc(src, w, h, "tomato", null),
                    refDec(src, w, h, "tomato", null),
                    PopularCodecs.transform(PopularCodecs.FORMAT_TOMATO, src.clone(), w, h, "", 1, true),
                    PopularCodecs.transform(PopularCodecs.FORMAT_TOMATO, src.clone(), w, h, "", 1, false),
                    w, h);

            for (String k : new String[]{"0.666", "0.3"}) {
                pair("picRowCol " + w + "x" + h + " k=" + k,
                        refEnc(src, w, h, "picRowCol", k), refDec(src, w, h, "picRowCol", k),
                        PopularCodecs.transform(PopularCodecs.FORMAT_PIC_ROWCOL, src.clone(), w, h, k, 1, true),
                        PopularCodecs.transform(PopularCodecs.FORMAT_PIC_ROWCOL, src.clone(), w, h, k, 1, false), w, h);

                pair("picRow " + w + "x" + h + " k=" + k,
                        refEnc(src, w, h, "picRow", k), refDec(src, w, h, "picRow", k),
                        PopularCodecs.transform(PopularCodecs.FORMAT_PIC_ROW, src.clone(), w, h, k, 1, true),
                        PopularCodecs.transform(PopularCodecs.FORMAT_PIC_ROW, src.clone(), w, h, k, 1, false), w, h);

                pair("rowPixel " + w + "x" + h + " k=" + k,
                        refEnc(src, w, h, "rowPixel", k), refDec(src, w, h, "rowPixel", k),
                        PopularCodecs.transform(PopularCodecs.FORMAT_ROW_PIXEL, src.clone(), w, h, k, 1, true),
                        PopularCodecs.transform(PopularCodecs.FORMAT_ROW_PIXEL, src.clone(), w, h, k, 1, false), w, h);

                pair("perPixel " + w + "x" + h + " k=" + k,
                        refEnc(src, w, h, "perPixel", k), refDec(src, w, h, "perPixel", k),
                        PopularCodecs.transform(PopularCodecs.FORMAT_PER_PIXEL, src.clone(), w, h, k, 1, true),
                        PopularCodecs.transform(PopularCodecs.FORMAT_PER_PIXEL, src.clone(), w, h, k, 1, false), w, h);

                // 方块混淆：参考实现会补齐尺寸
                ImageData re = new com.uiloalxise.utils.BlockObfuscation(src.clone(), w, h, k).encrypt();
                ImageData rd = new com.uiloalxise.utils.BlockObfuscation(re.getPixels().clone(), re.getWidth(), re.getHeight(), k).decrypt();
                PopularCodecs.Result me = PopularCodecs.transform(PopularCodecs.FORMAT_BLOCK, src.clone(), w, h, k, 1, true);
                PopularCodecs.Result md = PopularCodecs.transform(PopularCodecs.FORMAT_BLOCK, re.getPixels().clone(), re.getWidth(), re.getHeight(), k, 1, false);
                pair("block " + w + "x" + h + " k=" + k, re, rd, me, md, -1, -1);
            }

            // 自洽往返（叠加 1/2/3 次）
            for (int times = 1; times <= 3; times++) {
                for (int f = 0; f <= 5; f++) {
                    String key = (f == 3 || f == 4 || f == 5) ? "abc123" : "0.666";
                    int[] px = src.clone();
                    PopularCodecs.Result e = PopularCodecs.transform(f, px, w, h, key, times, true);
                    PopularCodecs.Result d = PopularCodecs.transform(f, e.pixels.clone(), e.width, e.height, key, times, false);
                    boolean ok;
                    if (f == 5) {
                        // 方块混淆会把尺寸补齐到 32 的倍数，只比较左上角原图区域
                        ok = d.width >= w && d.height >= h && PopularCodecs.cropRegion(d.pixels, d.width, d.height, w, h).length == src.length
                                && Arrays.equals(PopularCodecs.cropRegion(d.pixels, d.width, d.height, w, h), src);
                    } else {
                        ok = d.width == w && d.height == h && d.pixels.length == src.length
                                && Arrays.equals(Arrays.copyOf(d.pixels, src.length), src);
                    }
                    if (!ok) {
                        hardFail++;
                        System.out.println("ROUNDTRIP FAIL " + PopularCodecs.name(f) + " " + w + "x" + h + " times=" + times);
                    }
                }
            }
        }
        System.out.println(hardFail == 0 ? "=== 往返自洽全部通过" : ("=== 自洽失败：" + hardFail));
    }

    static ImageData refEnc(int[] src, int w, int h, String kind, String key) {
        switch (kind) {
            case "tomato": return new com.uiloalxise.utils.TomatoObfuscation(src.clone(), w, h).encrypt();
            case "picRowCol": return new com.uiloalxise.utils.PicEncryptRowColumnObfuscation(src.clone(), w, h, Double.parseDouble(key)).encrypt();
            case "picRow": return new com.uiloalxise.utils.PicEncryptRowObfuscation(src.clone(), w, h, Double.parseDouble(key)).encrypt();
            case "rowPixel": return new com.uiloalxise.utils.RowPixelObfuscation(src.clone(), w, h, key).encrypt();
            default: return new com.uiloalxise.utils.PerPixelObfuscation(src.clone(), w, h, key).encrypt();
        }
    }

    static ImageData refDec(int[] src, int w, int h, String kind, String key) {
        switch (kind) {
            case "tomato": return new com.uiloalxise.utils.TomatoObfuscation(src.clone(), w, h).decrypt();
            case "picRowCol": return new com.uiloalxise.utils.PicEncryptRowColumnObfuscation(src.clone(), w, h, Double.parseDouble(key)).decrypt();
            case "picRow": return new com.uiloalxise.utils.PicEncryptRowObfuscation(src.clone(), w, h, Double.parseDouble(key)).decrypt();
            case "rowPixel": return new com.uiloalxise.utils.RowPixelObfuscation(src.clone(), w, h, key).decrypt();
            default: return new com.uiloalxise.utils.PerPixelObfuscation(src.clone(), w, h, key).decrypt();
        }
    }

    static void pair(String label, ImageData re, ImageData rd, PopularCodecs.Result me, PopularCodecs.Result md, int w, int h) {
        String a = cmp(re.getPixels(), re.getWidth(), re.getHeight(), me);
        String b = cmp(re.getPixels(), re.getWidth(), re.getHeight(), md);
        String c = cmp(rd.getPixels(), rd.getWidth(), rd.getHeight(), me);
        String d = cmp(rd.getPixels(), rd.getWidth(), rd.getHeight(), md);
        System.out.printf("%-34s mineEnc↔refEnc:%s  mineDec↔refDec:%s  mineEnc↔refDec:%s  mineDec↔refEnc:%s%n",
                label, a, d, c, b);
    }

    static String cmp(int[] ref, int refW, int refH, PopularCodecs.Result mine) {
        if (refW != mine.width || refH != mine.height || ref.length != mine.pixels.length) return "尺寸不同";
        for (int i = 0; i < ref.length; i++) {
            if (ref[i] != mine.pixels[i]) return "不同";
        }
        return "相同";
    }

    static int[] image(int w, int h) {
        java.util.Random r = new java.util.Random(20260916L);
        int[] p = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                p[y * w + x] = 0xFF000000 | ((x * 255 / Math.max(1, w - 1)) << 16)
                        | ((y * 255 / Math.max(1, h - 1)) << 8) | r.nextInt(64);
            }
        }
        return p;
    }
}

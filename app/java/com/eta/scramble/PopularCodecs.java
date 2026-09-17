package com.eta.scramble;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * 网络流行混淆图格式的兼容实现。
 *
 * 这些格式都不是本应用自创的，每种都严格按原版实现的像素映射复刻，
 * 目标是双向兼容：别人用这些工具做出来的混淆图，本应用能还原；
 * 本应用做出来的，对方工具也能还原。
 *
 *  0 小番茄混淆        Gilbert 空间填充曲线 + 循环位移（offset = round(0.6180339887 × 像素数)），不需要密钥
 *  1 PicEncrypt 行列混淆  Logistic 混沌映射排序，逐行 + 逐列置换，double 密钥（默认 0.666）
 *  2 PicEncrypt 行模式混淆 Logistic 混沌映射排序，同一置换作用于每一行，double 密钥
 *  3 行像素混淆        MD5(key+i) 派生 Fisher-Yates 置换，逐行像素重排，字符串密钥
 *  4 全像素混淆        同上，x/y 两个置换共同决定每一个像素的去向，字符串密钥
 *  5 方块混淆          同上，按 32×32 个方块打乱，尺寸会向上补齐到 32 的倍数，字符串密钥
 *  6 本机密钥流混淆     ChaCha20 密钥流 + 行列置换（见 Scrambler），支持任意密钥与轮数
 */
public final class PopularCodecs {

    public static final int FORMAT_TOMATO = 0;
    public static final int FORMAT_PIC_ROWCOL = 1;
    public static final int FORMAT_PIC_ROW = 2;
    public static final int FORMAT_ROW_PIXEL = 3;
    public static final int FORMAT_PER_PIXEL = 4;
    public static final int FORMAT_BLOCK = 5;
    public static final int FORMAT_ETA = 6;
    public static final int FORMAT_COUNT = 7;

    /** 网络工具的默认密钥：PicEncrypt 系与其衍生的像素/方块混淆都用它。 */
    public static final String DEFAULT_POPULAR_KEY = "0.666";
    private static final int BLOCK_COUNT = 32;

    private static final String[] NAMES = {
            "小番茄混淆（Gilbert 曲线）",
            "PicEncrypt 行列混淆",
            "PicEncrypt 行模式混淆",
            "行像素混淆",
            "全像素混淆",
            "方块混淆",
            "本机密钥流混淆（自定义密钥）"
    };

    private PopularCodecs() {}

    public static String name(int format) {
        if (format < 0 || format >= NAMES.length) return NAMES[0];
        return NAMES[format];
    }

    /** 该格式是否需要密钥（小番茄不需要）。 */
    public static boolean needsKey(int format) {
        return format != FORMAT_TOMATO;
    }

    /** 该格式的密钥是否按小数解析（PicEncrypt 系）。 */
    public static boolean usesNumericKey(int format) {
        return format == FORMAT_PIC_ROWCOL || format == FORMAT_PIC_ROW;
    }

    /** 处理结果：方块混淆会改变尺寸，所以尺寸也要一起返回。 */
    public static final class Result {
        public final int[] pixels;
        public final int width;
        public final int height;

        public Result(int[] pixels, int width, int height) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * 按指定格式处理像素。
     *
     * @param times   叠加次数（1-4），等于把同一次变换重复应用若干遍
     * @param encrypt true 混淆，false 还原
     */
    public static Result transform(int format, int[] pixels, int width, int height, String key, int times, boolean encrypt) {
        int loops = times < 1 ? 1 : (times > 4 ? 4 : times);
        if (format == FORMAT_ETA) {
            // 本机格式：轮数直接交给 Scrambler，保持与旧版本生成的图片兼容
            int[] copy = pixels.clone();
            if (encrypt) Scrambler.scramble(copy, width, height, key, loops);
            else Scrambler.unscramble(copy, width, height, key, loops);
            return new Result(copy, width, height);
        }
        Result current = new Result(pixels, width, height);
        for (int i = 0; i < loops; i++) {
            current = once(format, current.pixels, current.width, current.height, key, encrypt);
        }
        return current;
    }

    private static Result once(int format, int[] src, int w, int h, String key, boolean encrypt) {
        // 各算法的辅助方法以“是否做逆变换”为参数，这里统一换算一次，避免方向写反。
        final boolean inverse = !encrypt;
        switch (format) {
            case FORMAT_TOMATO:
                return new Result(tomato(src, w, h, inverse), w, h);
            case FORMAT_PIC_ROWCOL:
                return new Result(picRowColumn(src, w, h, parseKey(key), inverse), w, h);
            case FORMAT_PIC_ROW:
                return new Result(picRow(src, w, h, parseKey(key), inverse), w, h);
            case FORMAT_ROW_PIXEL:
                return new Result(rowPixel(src, w, h, stringKey(key), inverse), w, h);
            case FORMAT_PER_PIXEL:
                return new Result(perPixel(src, w, h, stringKey(key), inverse), w, h);
            case FORMAT_BLOCK:
                return block(src, w, h, stringKey(key), inverse);
            case FORMAT_ETA:
                return new Result(src.clone(), w, h);
            default:
                return new Result(src.clone(), w, h);
        }
    }

    private static double parseKey(String key) {
        if (key == null) return 0.666;
        String trimmed = key.trim();
        if (trimmed.isEmpty()) return 0.666;
        try {
            double v = Double.parseDouble(trimmed);
            if (v <= 0 || v >= 1) return 0.666;
            return v;
        } catch (NumberFormatException e) {
            return 0.666;
        }
    }

    private static String stringKey(String key) {
        return (key == null || key.isEmpty()) ? DEFAULT_POPULAR_KEY : key;
    }

    // ------------------------------------------------------------ 0 小番茄（Gilbert 曲线）

    private static int[] tomato(int[] src, int w, int h, boolean decrypt) {
        int n = w * h;
        int[] order = gilbertOrder(w, h);
        int offset = (int) Math.round((Math.sqrt(5) - 1) / 2 * n);
        offset = ((offset % n) + n) % n;
        int[] dst = new int[n];
        for (int i = 0; i < n; i++) {
            int from = order[i];
            int to = order[(i + offset) % n];
            if (decrypt) dst[from] = src[to];
            else dst[to] = src[from];
        }
        return dst;
    }

    /** 生成 Gilbert / Hilbert 空间填充曲线经过的像素下标序列。 */
    static int[] gilbertOrder(int width, int height) {
        int[] positions = new int[width * height];
        int[] cursor = new int[1];
        if (width >= height) {
            generate2d(positions, cursor, 0, 0, width, 0, 0, height, width);
        } else {
            generate2d(positions, cursor, 0, 0, 0, height, width, 0, width);
        }
        return positions;
    }

    private static void generate2d(int[] positions, int[] cursor, int x, int y,
                                   int ax, int ay, int bx, int by, int width) {
        int w = Math.abs(ax + ay);
        int h = Math.abs(bx + by);
        int dax = (int) Math.signum(ax);
        int day = (int) Math.signum(ay);
        int dbx = (int) Math.signum(bx);
        int dby = (int) Math.signum(by);

        if (h == 1) {
            for (int i = 0; i < w; i++) {
                positions[cursor[0]++] = x + y * width;
                x += dax;
                y += day;
            }
            return;
        }
        if (w == 1) {
            for (int i = 0; i < h; i++) {
                positions[cursor[0]++] = x + y * width;
                x += dbx;
                y += dby;
            }
            return;
        }

        int ax2 = Math.floorDiv(ax, 2);
        int ay2 = Math.floorDiv(ay, 2);
        int bx2 = Math.floorDiv(bx, 2);
        int by2 = Math.floorDiv(by, 2);
        int w2 = Math.abs(ax2 + ay2);
        int h2 = Math.abs(bx2 + by2);

        if (2 * w > 3 * h) {
            if ((w2 & 1) == 1 && w > 2) {
                ax2 += dax;
                ay2 += day;
            }
            generate2d(positions, cursor, x, y, ax2, ay2, bx, by, width);
            generate2d(positions, cursor, x + ax2, y + ay2, ax - ax2, ay - ay2, bx, by, width);
        } else {
            if ((h2 & 1) == 1 && h > 2) {
                bx2 += dbx;
                by2 += dby;
            }
            generate2d(positions, cursor, x, y, bx2, by2, ax2, ay2, width);
            generate2d(positions, cursor, x + bx2, y + by2, ax, ay, bx - bx2, by - by2, width);
            generate2d(positions, cursor, x + (ax - dax) + (bx2 - dbx), y + (ay - day) + (by2 - dby),
                    -bx2, -by2, -(ax - ax2), -(ay - ay2), width);
        }
    }

    // ------------------------------------------------------------ 1/2 PicEncrypt（Logistic 混沌排序）

    private static int[] picRow(int[] src, int w, int h, double key, boolean decrypt) {
        int[] positions = logisticPositions(key, w);
        int[] dst = new int[src.length];
        for (int j = 0; j < h; j++) {
            int off = j * w;
            for (int i = 0; i < w; i++) {
                if (decrypt) dst[positions[i] + off] = src[i + off];
                else dst[i + off] = src[positions[i] + off];
            }
        }
        return dst;
    }

    private static int[] picRowColumn(int[] src, int w, int h, double key, boolean decrypt) {
        int[] dst = new int[src.length];
        if (!decrypt) {
            int[] rows = new int[src.length];
            double x = key;
            for (int j = 0, off = 0; j < h; j++, off += w) {
                int[] positions = logisticPositions(x, w);
                x = logisticLast(x, w);
                for (int i = 0; i < w; i++) rows[i + off] = src[positions[i] + off];
            }
            x = key;
            for (int i = 0; i < w; i++) {
                int[] positions = logisticPositions(x, h);
                x = logisticLast(x, h);
                for (int j = 0; j < h; j++) dst[i + j * w] = rows[i + positions[j] * w];
            }
        } else {
            int[] cols = new int[src.length];
            double x = key;
            for (int i = 0; i < w; i++) {
                int[] positions = logisticPositions(x, h);
                x = logisticLast(x, h);
                for (int j = 0; j < h; j++) cols[i + positions[j] * w] = src[i + j * w];
            }
            x = key;
            for (int j = 0, off = 0; j < h; j++, off += w) {
                int[] positions = logisticPositions(x, w);
                x = logisticLast(x, w);
                for (int i = 0; i < w; i++) dst[positions[i] + off] = cols[i + off];
            }
        }
        return dst;
    }

    /** 与参考实现一致：x0 = x1，随后 x = 3.9999999 * x * (1 - x)，返回第 n 个状态。 */
    private static double logisticLast(double x1, int n) {
        double x = x1;
        for (int i = 1; i < n; i++) {
            x = 3.9999999 * x * (1 - x);
        }
        return x;
    }

    /** Logistic 序列的升序位置表：positions[i] 表示排序后第 i 位来自原序列的哪个下标。 */
    private static int[] logisticPositions(double x1, int n) {
        double[] values = new double[n];
        int[] index = new int[n];
        double x = x1;
        values[0] = x;
        index[0] = 0;
        for (int i = 1; i < n; i++) {
            x = 3.9999999 * x * (1 - x);
            values[i] = x;
            index[i] = i;
        }
        mergeSortByValue(values, index, 0, n - 1, new int[n], new int[n]);
        return index;
    }

    /** 与参考实现的 Arrays.sort(comparator: a>b ? 1 : -1) 等价的稳定归并排序。 */
    private static void mergeSortByValue(double[] values, int[] index, int lo, int hi, int[] tmpIdx, int[] tmpVal) {
        if (lo >= hi) return;
        int mid = (lo + hi) >>> 1;
        mergeSortByValue(values, index, lo, mid, tmpIdx, tmpVal);
        mergeSortByValue(values, index, mid + 1, hi, tmpIdx, tmpVal);
        int i = lo, j = mid + 1, k = lo;
        while (i <= mid && j <= hi) {
            boolean takeLeft = !(values[index[i]] > values[index[j]]);
            if (takeLeft) {
                tmpIdx[k] = index[i];
                tmpVal[k] = 0;
                i++;
            } else {
                tmpIdx[k] = index[j];
                tmpVal[k] = 0;
                j++;
            }
            k++;
        }
        while (i <= mid) {
            tmpIdx[k++] = index[i++];
        }
        while (j <= hi) {
            tmpIdx[k++] = index[j++];
        }
        for (int m = lo; m <= hi; m++) index[m] = tmpIdx[m];
    }

    // ------------------------------------------------------------ 3/4/5 像素与方块混淆（MD5 置换）

    private static int[] md5Shuffle(String key, int length) {
        int[] arr = new int[length];
        for (int i = 0; i < length; i++) arr[i] = i;
        for (int i = length - 1; i > 0; i--) {
            byte[] md5 = md5((key + i).getBytes(StandardCharsets.UTF_8));
            String hex = new BigInteger(1, md5).toString(16);
            while (hex.length() < 32) {
                hex = "0" + hex;
            }
            int rand = Integer.parseInt(hex.substring(0, 7), 16) % (i + 1);
            int tmp = arr[rand];
            arr[rand] = arr[i];
            arr[i] = tmp;
        }
        return arr;
    }

    private static byte[] md5(byte[] data) {
        try {
            return MessageDigest.getInstance("MD5").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    private static int[] rowPixel(int[] src, int w, int h, String key, boolean decrypt) {
        int[] xArray = md5Shuffle(key, w);
        int[] dst = new int[src.length];
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                int m = xArray[(xArray[j % w] + i) % w];
                if (decrypt) dst[m + j * w] = src[i + j * w];
                else dst[i + j * w] = src[m + j * w];
            }
        }
        return dst;
    }

    private static int[] perPixel(int[] src, int w, int h, String key, boolean decrypt) {
        int[] xArray = md5Shuffle(key, w);
        int[] yArray = md5Shuffle(key, h);
        int[] dst = new int[src.length];
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                int m = xArray[(xArray[j % w] + i) % w];
                int n = yArray[(yArray[m % h] + j) % h];
                if (decrypt) dst[m + n * w] = src[i + j * w];
                else dst[i + j * w] = src[m + n * w];
            }
        }
        return dst;
    }

    private static Result block(int[] src, int w, int h, String key, boolean decrypt) {
        int[] xArray = md5Shuffle(key, BLOCK_COUNT);
        int[] yArray = md5Shuffle(key, BLOCK_COUNT);
        int newW = (w % BLOCK_COUNT > 0) ? w + BLOCK_COUNT - w % BLOCK_COUNT : w;
        int newH = (h % BLOCK_COUNT > 0) ? h + BLOCK_COUNT - h % BLOCK_COUNT : h;
        int blockW = newW / BLOCK_COUNT;
        int blockH = newH / BLOCK_COUNT;
        int[] dst = new int[newW * newH];
        for (int i = 0; i < newW; i++) {
            for (int j = 0; j < newH; j++) {
                int n = j;
                int m = (xArray[(n / blockH) % BLOCK_COUNT] * blockW + i) % newW;
                m = xArray[m / blockW] * blockW + m % blockW;
                n = (yArray[m / blockW % BLOCK_COUNT] * blockH + n) % newH;
                n = yArray[n / blockH] * blockH + n % blockH;
                if (decrypt) {
                    dst[m + n * newW] = src[i + j * newW];
                } else {
                    dst[i + j * newW] = src[m % w + n % h * w];
                }
            }
        }
        Result r = new Result(dst, newW, newH);
        if (decrypt) {
            // 还原时结果的可见尺寸仍是补齐前的原图尺寸（取左上角区域即可）
            return new Result(crop(dst, newW, newH, w, h), w, h);
        }
        return r;
    }

    /** 从大图中取出左上角 w x h 区域（方块混淆还原时用）。 */
    public static int[] cropRegion(int[] src, int srcW, int srcH, int w, int h) {
        return crop(src, srcW, srcH, w, h);
    }

    private static int[] crop(int[] src, int srcW, int srcH, int w, int h) {
        if (srcW == w && srcH == h) return src;
        int[] dst = new int[w * h];
        for (int j = 0; j < h && j < srcH; j++) {
            System.arraycopy(src, j * srcW, dst, j * w, Math.min(w, srcW));
        }
        return dst;
    }

    // ------------------------------------------------------------ 自动识别

    /**
     * 自动识别格式：把候选格式各还原一遍，取"最像照片"的结果（相邻像素差最小）。
     * 只在还原模式下使用，默认参数为网络工具的默认密钥。
     */
    /** 相邻像素差超过这个值就认为还不是照片，需要用别的次数再试一遍。 */
    private static final double NOISE_THRESHOLD = 25.0;

    public static Result autoRestore(int[] pixels, int w, int h, String key, int times, int[] chosenFormat, double[] chosenScore) {
        Result best = bestOf(pixels, w, h, key, times, chosenFormat, chosenScore);
        double score = (chosenScore != null && chosenScore.length > 0) ? chosenScore[0] : Double.MAX_VALUE;
        if (times == 1 && score > NOISE_THRESHOLD) {
            int[] altFormat = new int[1];
            double[] altScore = new double[1];
            Result alt = bestOf(pixels, w, h, key, 2, altFormat, altScore);
            if (altScore[0] < score) {
                if (chosenFormat != null && chosenFormat.length > 0) chosenFormat[0] = altFormat[0];
                if (chosenScore != null && chosenScore.length > 0) chosenScore[0] = altScore[0];
                return alt;
            }
        }
        return best;
    }

    private static Result bestOf(int[] pixels, int w, int h, String key, int times, int[] chosenFormat, double[] chosenScore) {
        Result best = null;
        double bestScore = Double.MAX_VALUE;
        int bestFormat = -1;
        for (int format = 0; format < FORMAT_COUNT - 1; format++) {
            Result candidate;
            try {
                candidate = transform(format, pixels.clone(), w, h, key, times, false);
            } catch (Throwable t) {
                continue;
            }
            double score = smoothness(candidate.pixels, candidate.width, candidate.height);
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
                bestFormat = format;
            }
        }
        if (chosenFormat != null && chosenFormat.length > 0) chosenFormat[0] = bestFormat;
        if (chosenScore != null && chosenScore.length > 0) chosenScore[0] = bestScore;
        if (best == null) best = new Result(pixels.clone(), w, h);
        return best;
    }

    /** 越小越像自然图像：相邻像素灰度差均值（抽样计算，保证性能）。 */
    public static double smoothness(int[] pixels, int w, int h) {
        if (w < 2 || h < 2) return Double.MAX_VALUE;
        long sum = 0;
        int count = 0;
        int step = Math.max(1, (w * h) / 200000);
        for (int j = 0; j < h; j += 1) {
            int row = j * w;
            for (int i = 0; i < w; i += step) {
                int idx = row + i;
                if (i + 1 < w) {
                    sum += diff(pixels[idx], pixels[idx + 1]);
                    count++;
                }
                if (j + 1 < h) {
                    sum += diff(pixels[idx], pixels[idx + w]);
                    count++;
                }
            }
        }
        return count == 0 ? Double.MAX_VALUE : (double) sum / count;
    }

    private static int diff(int a, int b) {
        int ga = ((a >>> 16) & 0xFF) * 77 + ((a >>> 8) & 0xFF) * 150 + (a & 0xFF) * 29;
        int gb = ((b >>> 16) & 0xFF) * 77 + ((b >>> 8) & 0xFF) * 150 + (b & 0xFF) * 29;
        return Math.abs(ga - gb) >> 8;
    }

    /** 供自测使用：把格式名数组暴露出去。 */
    public static String[] allNames() {
        return Arrays.copyOf(NAMES, NAMES.length);
    }
}

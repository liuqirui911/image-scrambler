package com.eta.scramble;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
 *
 * 并行化说明：每种变换都是“每个输出像素只写一次”的置换或散列映射，输出互不重叠，
 * 因此可以按行/列/像素区间切片交给多个线程（见 Par），结果与串行逐位一致。
 * 浮点部分（Logistic 链）并行时用“跳过 j×(w-1) 步”的方式让各段独立推进，
 * 与原来“上一行末尾续算”是同一串确定性 double 迭代，逐位相同。
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
     * 按指定格式处理像素。不会修改传入的数组：各格式都是读 src、写新数组。
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
        final int n = w * h;
        final int[] order = gilbertOrder(w, h);
        int offset = (int) Math.round((Math.sqrt(5) - 1) / 2 * n);
        offset = ((offset % n) + n) % n;
        final int shift = offset;
        final int[] dst = new int[n];
        final boolean dec = decrypt;
        // 每个 i 恰好写出 order[(i+shift)%n] 一次，互不重叠，可任意切片并行。
        Par.each(n, n, new Par.Range() {
            @Override public void run(int from, int to) {
                int k = (int) ((from + (long) shift) % n);
                for (int i = from; i < to; i++) {
                    int a = order[i];
                    int b = order[k];
                    if (dec) dst[a] = src[b];
                    else dst[b] = src[a];
                    if (++k == n) k = 0;
                }
            }
        });
        return dst;
    }

    /** 并行生成的门槛：低于这么多格子就老老实实串行递归。 */
    private static final int PAR_MIN_CELLS = 1 << 18;

    /**
     * 生成 Gilbert / Hilbert 空间填充曲线经过的像素下标序列。
     *
     * 关键性质：一个子矩形遍历产生的下标个数**恒等于它的格子数** |ax+ay|·|bx+by|，
     * 与它内部的遍历顺序无关。所以只要按原始先后把子块的格子数累加，就能事先算出
     * 每个子块该从哪个下标开始写——各子块写的区间互不重叠，且顺序与串行完全一致，
     * 于是可以切开交给多个线程同时生成。
     */
    /** 供自测使用：曲线下标序列（并行生成）。 */
    public static int[] gilbertOrder(int width, int height) {
        final int[] positions = new int[width * height];
        final int widthArg = width;
        int threads = positions.length >= PAR_MIN_CELLS ? Par.threads() : 1;
        if (threads <= 1) {
            if (width >= height) {
                generate2d(positions, 0, 0, 0, width, 0, 0, height, width);
            } else {
                generate2d(positions, 0, 0, 0, 0, height, width, 0, width);
            }
            return positions;
        }
        final List<Sub> parts = new ArrayList<Sub>();
        if (width >= height) {
            splitInto(parts, threads, 0, 0, 0, width, 0, 0, height);
        } else {
            splitInto(parts, threads, 0, 0, 0, 0, height, width, 0);
        }
        Par.forEachIndex(parts.size(), new Par.Indexed() {
            @Override public void run(int index) {
                Sub s = parts.get(index);
                generate2d(positions, s.start, s.x, s.y, s.ax, s.ay, s.bx, s.by, widthArg);
            }
        });
        return positions;
    }

    /** 一段子矩形：递归遍历参数 + 它在 positions 中的起始下标。 */
    private static final class Sub {
        final int start, x, y, ax, ay, bx, by;

        Sub(int start, int x, int y, int ax, int ay, int bx, int by) {
            this.start = start;
            this.x = x;
            this.y = y;
            this.ax = ax;
            this.ay = ay;
            this.bx = bx;
            this.by = by;
        }

        /** 遍历这块会写出的下标个数，恒等于格子数。 */
        int size() {
            return Math.abs(ax + ay) * Math.abs(bx + by);
        }

        /** 已经是一行/一列，不能再切。 */
        boolean leaf() {
            return Math.abs(ax + ay) == 1 || Math.abs(bx + by) == 1;
        }
    }

    /**
     * 先把曲线切成 threads 块（只遍历树的形状，不写下标）：每次挑最大的一块继续切，
     * 这样各块大小自然接近，负载比较均衡。切不动（叶子或太小）就停。
     */
    private static void splitInto(List<Sub> parts, int want, int start, int x, int y,
                                  int ax, int ay, int bx, int by) {
        parts.add(new Sub(start, x, y, ax, ay, bx, by));
        while (parts.size() < want) {
            int idx = -1;
            int biggest = 0;
            for (int i = 0; i < parts.size(); i++) {
                Sub s = parts.get(i);
                if (s.leaf() || s.size() < PAR_MIN_CELLS * 2) continue;
                if (s.size() > biggest) {
                    biggest = s.size();
                    idx = i;
                }
            }
            if (idx < 0) break;
            Sub s = parts.remove(idx);
            Sub[] subs = new Sub[3];
            int n = split(s.start, s.x, s.y, s.ax, s.ay, s.bx, s.by, subs);
            for (int i = 0; i < n; i++) parts.add(idx + i, subs[i]);
        }
    }

    /**
     * 按原算法的分裂规则把一个矩形拆成 2 或 3 个子矩形，顺序与串行遍历一致。
     * 子块起始下标 = 父块起点 + 前面各子块的格子数之和。
     */
    private static int split(int start, int x, int y, int ax, int ay, int bx, int by, Sub[] subs) {
        int w = Math.abs(ax + ay);
        int h = Math.abs(bx + by);
        int dax = Integer.signum(ax);
        int day = Integer.signum(ay);
        int dbx = Integer.signum(bx);
        int dby = Integer.signum(by);
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
            subs[0] = new Sub(start, x, y, ax2, ay2, bx, by);
            subs[1] = new Sub(subs[0].start + subs[0].size(), x + ax2, y + ay2, ax - ax2, ay - ay2, bx, by);
            return 2;
        }
        if ((h2 & 1) == 1 && h > 2) {
            bx2 += dbx;
            by2 += dby;
        }
        subs[0] = new Sub(start, x, y, bx2, by2, ax2, ay2);
        subs[1] = new Sub(subs[0].start + subs[0].size(), x + bx2, y + by2, ax, ay, bx - bx2, by - by2);
        subs[2] = new Sub(subs[1].start + subs[1].size(),
                x + (ax - dax) + (bx2 - dbx), y + (ay - day) + (by2 - dby),
                -bx2, -by2, -(ax - ax2), -(ay - ay2));
        return 3;
    }

    private static void generate2d(int[] positions, int start, int x, int y,
                                   int ax, int ay, int bx, int by, int width) {
        int w = Math.abs(ax + ay);
        int h = Math.abs(bx + by);
        if (h == 1) {
            emitLine(positions, start, x, y, width, Integer.signum(ax), Integer.signum(ay), w);
            return;
        }
        if (w == 1) {
            emitLine(positions, start, x, y, width, Integer.signum(bx), Integer.signum(by), h);
            return;
        }
        Sub[] subs = new Sub[3];
        int n = split(start, x, y, ax, ay, bx, by, subs);
        for (int i = 0; i < n; i++) {
            Sub s = subs[i];
            generate2d(positions, s.start, s.x, s.y, s.ax, s.ay, s.bx, s.by, width);
        }
    }

    /**
     * 从 start 开始连续写 count 个下标：沿固定方向前进，步长是常数，
     * 省掉每像素的乘法（x+i·dx + (y+i·dy)·width == x+y·width + i·(dx+dy·width)）。
     */
    private static void emitLine(int[] positions, int start, int x, int y, int width, int dx, int dy, int count) {
        int idx = x + y * width;
        int step = dx + dy * width;
        int cur = start;
        for (int i = 0; i < count; i++) {
            positions[cur++] = idx;
            idx += step;
        }
    }

    // ------------------------------------------------------------ 1/2 PicEncrypt（Logistic 混沌排序）

    private static int[] picRow(int[] src, int w, int h, double key, boolean decrypt) {
        // 整幅图共用同一张行内置换表，每一行互不重叠，按行并行。
        final int[] positions = logisticPositions(key, w);
        final int[] dst = new int[src.length];
        final int width = w;
        final boolean dec = decrypt;
        Par.each(h, (long) w * h, new Par.Range() {
            @Override public void run(int j0, int j1) {
                for (int j = j0; j < j1; j++) {
                    int off = j * width;
                    for (int i = 0; i < width; i++) {
                        if (dec) dst[positions[i] + off] = src[i + off];
                        else dst[i + off] = src[positions[i] + off];
                    }
                }
            }
        });
        return dst;
    }

    private static int[] picRowColumn(int[] src, int w, int h, double key, boolean decrypt) {
        int[] dst = new int[src.length];
        if (!decrypt) {
            int[] rows = new int[src.length];
            logisticRows(key, w, h, src, rows, false);
            logisticColumns(key, h, w, rows, dst, false);
        } else {
            int[] cols = new int[src.length];
            logisticColumns(key, h, w, src, cols, true);
            logisticRows(key, w, h, cols, dst, true);
        }
        return dst;
    }

    /**
     * 逐行 Logistic 置换：第 j 行用序列起点 x_j = f^((w-1)·j)(key)。
     * 与旧实现“上一行算完接着续算”完全等价（同一串确定性 double 迭代，逐位相同），
     * 于是各线程可以各自跳到自己那一段的起点独立推进。
     */
    private static void logisticRows(final double key, final int w, final int h,
                                     final int[] in, final int[] out, final boolean inverse) {
        Par.each(h, (long) w * h, new Par.Range() {
            @Override public void run(int j0, int j1) {
                double[] values = new double[w];
                int[] index = new int[w];
                int[] tmp = new int[w];
                double x = logisticSkip(key, (long) j0 * (w - 1));
                for (int j = j0; j < j1; j++) {
                    x = logisticInto(values, index, tmp, w, x);
                    int off = j * w;
                    if (inverse) {
                        for (int i = 0; i < w; i++) out[index[i] + off] = in[i + off];
                    } else {
                        for (int i = 0; i < w; i++) out[i + off] = in[index[i] + off];
                    }
                }
            }
        });
    }

    /** 逐列 Logistic 置换：第 i 列用序列起点 x_i = f^((h-1)·i)(key)，列与列之间互不重叠。 */
    private static void logisticColumns(final double key, final int h, final int w,
                                        final int[] in, final int[] out, final boolean inverse) {
        Par.each(w, (long) w * h, new Par.Range() {
            @Override public void run(int i0, int i1) {
                double[] values = new double[h];
                int[] index = new int[h];
                int[] tmp = new int[h];
                double x = logisticSkip(key, (long) i0 * (h - 1));
                for (int i = i0; i < i1; i++) {
                    x = logisticInto(values, index, tmp, h, x);
                    if (inverse) {
                        for (int j = 0; j < h; j++) out[i + index[j] * w] = in[i + j * w];
                    } else {
                        for (int j = 0; j < h; j++) out[i + j * w] = in[i + index[j] * w];
                    }
                }
            }
        });
    }

    /** 从 x1 起把 Logistic 映射迭代 steps 次，用于并行时直接跳到某一行/列的起点。 */
    private static double logisticSkip(double x1, long steps) {
        double x = x1;
        for (long i = 0; i < steps; i++) {
            x = 3.9999999 * x * (1 - x);
        }
        return x;
    }

    /** Logistic 序列的升序位置表：positions[i] 表示排序后第 i 位来自原序列的哪个下标。 */
    private static int[] logisticPositions(double x1, int n) {
        double[] values = new double[n];
        int[] index = new int[n];
        int[] tmp = new int[n];
        logisticInto(values, index, tmp, n, x1);
        return index;
    }

    /**
     * 生成 Logistic 序列并原地排序出位置表，结果写进调用方提供的缓冲（避免逐行重复分配）。
     * 返回序列最后一个状态，可直接作为下一行/列的起点，省掉一次重复迭代。
     */
    private static double logisticInto(double[] values, int[] index, int[] tmp, int n, double x1) {
        double x = x1;
        values[0] = x;
        index[0] = 0;
        for (int i = 1; i < n; i++) {
            x = 3.9999999 * x * (1 - x);
            values[i] = x;
            index[i] = i;
        }
        double last = x;
        mergeSortByValue(values, index, 0, n - 1, tmp);
        return last;
    }

    /** 与参考实现的 Arrays.sort(comparator: a>b ? 1 : -1) 等价的稳定归并排序。 */
    private static void mergeSortByValue(double[] values, int[] index, int lo, int hi, int[] tmpIdx) {
        if (lo >= hi) return;
        int mid = (lo + hi) >>> 1;
        mergeSortByValue(values, index, lo, mid, tmpIdx);
        mergeSortByValue(values, index, mid + 1, hi, tmpIdx);
        int i = lo, j = mid + 1, k = lo;
        while (i <= mid && j <= hi) {
            boolean takeLeft = !(values[index[i]] > values[index[j]]);
            if (takeLeft) {
                tmpIdx[k] = index[i];
                i++;
            } else {
                tmpIdx[k] = index[j];
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

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static int[] md5Shuffle(String key, int length) {
        int[] arr = new int[length];
        for (int i = 0; i < length; i++) arr[i] = i;
        if (length < 2) return arr;
        MessageDigest md = md5Digest(); // 复用实例：一张置换表要算数千次哈希
        char[] hex = new char[32];
        for (int i = length - 1; i > 0; i--) {
            byte[] digest = md.digest((key + i).getBytes(StandardCharsets.UTF_8));
            for (int b = 0; b < 16; b++) {
                int v = digest[b] & 0xFF;
                hex[b * 2] = HEX[v >>> 4];
                hex[b * 2 + 1] = HEX[v & 0xF];
            }
            // 与原实现 BigInteger(1,md5).toString(16) 左补 0 到 32 位后取前 7 位十六进制完全等价
            int rand = Integer.parseInt(new String(hex, 0, 7), 16) % (i + 1);
            int tmp = arr[rand];
            arr[rand] = arr[i];
            arr[i] = tmp;
        }
        return arr;
    }

    private static MessageDigest md5Digest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (Exception e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    private static int[] rowPixel(int[] src, int w, int h, String key, boolean decrypt) {
        final int[] xArray = md5Shuffle(key, w);
        final int[] dst = new int[src.length];
        final int width = w;
        final boolean dec = decrypt;
        // 每行只读写自己那一行，按行并行；行内把 (xArray[j%w]+i)%w 换成自增取模，省掉逐像素除法。
        Par.each(h, (long) w * h, new Par.Range() {
            @Override public void run(int j0, int j1) {
                for (int j = j0; j < j1; j++) {
                    int off = j * width;
                    int base = xArray[j % width];
                    for (int i = 0; i < width; i++) {
                        int k = base + i;
                        if (k >= width) k -= width;
                        int m = xArray[k];
                        if (dec) dst[m + off] = src[i + off];
                        else dst[i + off] = src[m + off];
                    }
                }
            }
        });
        return dst;
    }

    private static int[] perPixel(int[] src, int w, int h, String key, boolean decrypt) {
        final int[] xArray = md5Shuffle(key, w);
        final int[] yArray = md5Shuffle(key, h);
        final int[] dst = new int[src.length];
        final int width = w;
        final int height = h;
        final boolean dec = decrypt;
        // (i,j) -> (m,n) 是一一映射（对固定 m，j -> n 也是双射），因此任意切片都不会写冲突。
        Par.each(h, (long) w * h, new Par.Range() {
            @Override public void run(int j0, int j1) {
                for (int j = j0; j < j1; j++) {
                    int base = xArray[j % width];
                    for (int i = 0; i < width; i++) {
                        int k = base + i;
                        if (k >= width) k -= width;
                        int m = xArray[k];
                        int n = yArray[(yArray[m % height] + j) % height];
                        if (dec) dst[m + n * width] = src[i + j * width];
                        else dst[i + j * width] = src[m + n * width];
                    }
                }
            }
        });
        return dst;
    }

    private static Result block(int[] src, int w, int h, String key, boolean decrypt) {
        final int[] xArray = md5Shuffle(key, BLOCK_COUNT);
        final int[] yArray = md5Shuffle(key, BLOCK_COUNT);
        final int newW = (w % BLOCK_COUNT > 0) ? w + BLOCK_COUNT - w % BLOCK_COUNT : w;
        final int newH = (h % BLOCK_COUNT > 0) ? h + BLOCK_COUNT - h % BLOCK_COUNT : h;
        final int blockW = newW / BLOCK_COUNT;
        final int blockH = newH / BLOCK_COUNT;
        final int[] dst = new int[newW * newH];
        final boolean dec = decrypt;
        // 逐列写出：固定 i 时只写第 i 列，切片之间无冲突。
        Par.each(newW, (long) newW * newH, new Par.Range() {
            @Override public void run(int i0, int i1) {
                for (int i = i0; i < i1; i++) {
                    for (int j = 0; j < newH; j++) {
                        int n = j;
                        int m = (xArray[(n / blockH) % BLOCK_COUNT] * blockW + i) % newW;
                        m = xArray[m / blockW] * blockW + m % blockW;
                        n = (yArray[m / blockW % BLOCK_COUNT] * blockH + n) % newH;
                        n = yArray[n / blockH] * blockH + n % blockH;
                        if (dec) {
                            dst[m + n * newW] = src[i + j * newW];
                        } else {
                            dst[i + j * newW] = src[m % w + n % h * w];
                        }
                    }
                }
            }
        });
        if (decrypt) {
            // 还原时结果的可见尺寸仍是补齐前的原图尺寸（取左上角区域即可）
            return new Result(crop(dst, newW, newH, w, h), w, h);
        }
        return new Result(dst, newW, newH);
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

    /** 相邻像素差超过这个值就认为还不是照片，需要用别的次数再试一遍。 */
    private static final double NOISE_THRESHOLD = 25.0;

    /**
     * 自动识别格式：把候选格式各还原一遍，取“最像照片”的结果（相邻像素差最小）。
     * 只在还原模式下使用，默认参数为网络工具的默认密钥。
     */
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
                // transform 只读入参、不写入，所以不必给每个候选格式都克隆一份原图
                candidate = transform(format, pixels, w, h, key, times, false);
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
        final int step = Math.max(1, (w * h) / 200000);
        final int width = w;
        final int height = h;
        final long[] acc = new long[1];
        final int[] cnt = new int[1];
        Par.each(h, (long) w * h, new Par.Range() {
            @Override public void run(int from, int to) {
                long sum = 0;
                int count = 0;
                for (int j = from; j < to; j++) {
                    int row = j * width;
                    for (int i = 0; i < width; i += step) {
                        int idx = row + i;
                        if (i + 1 < width) {
                            sum += diff(pixels[idx], pixels[idx + 1]);
                            count++;
                        }
                        if (j + 1 < height) {
                            sum += diff(pixels[idx], pixels[idx + width]);
                            count++;
                        }
                    }
                }
                synchronized (acc) {
                    acc[0] += sum;
                    cnt[0] += count;
                }
            }
        });
        return cnt[0] == 0 ? Double.MAX_VALUE : (double) acc[0] / cnt[0];
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

package com.eta.scramble;

import java.io.ByteArrayOutputStream;
import java.util.zip.Adler32;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * 自带的快速 PNG 编码器：行滤波恒为 0（None）+ 整条 zlib 流按行分段并行压缩。
 *
 * 为什么不直接用 Bitmap.compress(PNG)：混淆结果是噪声图，而系统走的是 libpng 的
 * 自适应滤波（每行把 5 种滤波都试一遍再挑最优）加 zlib 默认级别——对噪声来说滤波
 * 一点收益都没有，纯属白算；而且整条 zlib 流只能单线程压。
 *
 * 分段拼接的合法性：每段用 nowrap（裸 deflate）压缩，段尾用 Z_FULL_FLUSH 结束
 * （会写一个空存储块，相当于同步点），下一段接着写裸 deflate，最后一段用 Z_FINISH，
 * 外面套一个 zlib 头和全量 Adler-32。这是并行 gzip 的标准做法，inflate 能像读一条
 * 连续流一样读下来，因此输出是**标准 PNG**，任何解码器都能读。
 *
 * 只用于混淆方向（噪声输出，本来也压不动）；还原方向仍用系统编码器（照片类内容
 * 滤波收益大，不能用这里的"不滤波"策略）。
 */
public final class FastPng {

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final int BASE = 65521;
    private static final int MAX_LEVEL = 9;

    private FastPng() {}

    /** 噪声内容用 1 级足够（压不动，级别再高只是白算）。 */
    public static byte[] encode(int[] pixels, int w, int h) {
        return encode(pixels, w, h, 1);
    }

    public static byte[] encode(int[] pixels, int w, int h, int level) {
        if (pixels == null || w <= 0 || h <= 0 || pixels.length < w * h) {
            throw new IllegalArgumentException("bad image " + w + "x" + h);
        }
        final int lvl = level < 0 ? 1 : (level > MAX_LEVEL ? MAX_LEVEL : level);
        int threads = Par.threads();
        if (threads > h) threads = h;
        if (threads < 1) threads = 1;
        final int per = (h + threads - 1) / threads;
        final int count = (h + per - 1) / per;
        final byte[][] parts = new byte[count][];
        final int[] adlers = new int[count];
        final int stride = 1 + w * 4;
        final int width = w;
        final int height = h;

        Par.forEachIndex(count, new Par.Indexed() {
            @Override public void run(int index) {
                int a = index * per;
                int b = Math.min(height, a + per);
                boolean last = b >= height;
                Deflater def = new Deflater(lvl, true); // nowrap：裸 deflate，zlib 头由外层写
                Adler32 adler = new Adler32();
                ByteArrayOutputStream out = new ByteArrayOutputStream(stride * (b - a) / 2 + 4096);
                byte[] row = new byte[stride];
                byte[] buf = new byte[1 << 16];
                for (int y = a; y < b; y++) {
                    row[0] = 0; // 滤波类型 None
                    int o = 1;
                    int base = y * width;
                    for (int x = 0; x < width; x++) {
                        int p = pixels[base + x];
                        row[o++] = (byte) (p >>> 16);
                        row[o++] = (byte) (p >>> 8);
                        row[o++] = (byte) p;
                        row[o++] = (byte) (p >>> 24);
                    }
                    adler.update(row, 0, stride);
                    def.setInput(row);
                    while (!def.needsInput()) {
                        int n = def.deflate(buf, 0, buf.length);
                        if (n <= 0) break;
                        out.write(buf, 0, n);
                    }
                }
                if (last) {
                    // 段尾结束整条流：deflate(...,flush) 不接受 FINISH，改用 finish() + 常规 deflate
                    def.finish();
                    while (!def.finished()) {
                        int n = def.deflate(buf, 0, buf.length);
                        if (n <= 0) break;
                        out.write(buf, 0, n);
                    }
                } else {
                    while (true) {
                        int n = def.deflate(buf, 0, buf.length, Deflater.FULL_FLUSH);
                        if (n > 0) out.write(buf, 0, n);
                        else break; // 同步点已写完，下一段可以接着写裸 deflate
                    }
                }
                def.end();
                parts[index] = out.toByteArray();
                adlers[index] = (int) adler.getValue();
            }
        });

        long compressed = 0;
        for (int i = 0; i < count; i++) compressed += parts[i].length;
        // IDAT 的数据 = 2 字节 zlib 头 + deflate 数据 + 4 字节 Adler-32
        int idatLen = 2 + (int) compressed + 4;
        byte[] png = new byte[8 + 25 + (12 + idatLen) + 12];
        int p = 0;
        System.arraycopy(SIGNATURE, 0, png, p, 8);
        p += 8;

        int chunk = p;
        p = chunkHeader(png, p, 13, "IHDR");
        p = be32(png, p, width);
        p = be32(png, p, height);
        png[p++] = 8; // 位深：8
        png[p++] = 6; // 颜色类型：RGBA
        png[p++] = 0; // 压缩方法：deflate
        png[p++] = 0; // 滤波方法
        png[p++] = 0; // 非隔行
        p = chunkEnd(png, chunk, p);

        chunk = p;
        p = chunkHeader(png, p, idatLen, "IDAT");
        int header = zlibHeader(lvl);
        png[p++] = (byte) (header >>> 8);
        png[p++] = (byte) header;
        for (int i = 0; i < count; i++) {
            System.arraycopy(parts[i], 0, png, p, parts[i].length);
            p += parts[i].length;
        }
        long adler = adlers[0] & 0xFFFFFFFFL;
        for (int i = 1; i < count; i++) {
            long len = (long) (Math.min(height, (i + 1) * per) - i * per) * stride;
            adler = adlerCombine(adler, adlers[i] & 0xFFFFFFFFL, len);
        }
        p = be32(png, p, (int) adler);
        p = chunkEnd(png, chunk, p);

        chunk = p;
        p = chunkHeader(png, p, 0, "IEND");
        chunkEnd(png, chunk, p);
        return png;
    }

    private static int chunkHeader(byte[] out, int p, int len, String type) {
        p = be32(out, p, len);
        for (int i = 0; i < 4; i++) out[p++] = (byte) type.charAt(i);
        return p;
    }

    /** 从 chunk 起点（长度字段）到当前游标算 CRC32（覆盖类型 + 数据）并写入。 */
    private static int chunkEnd(byte[] out, int chunkStart, int p) {
        CRC32 crc = new CRC32();
        crc.update(out, chunkStart + 4, p - chunkStart - 4);
        return be32(out, p, (int) crc.getValue());
    }

    private static int be32(byte[] out, int p, int v) {
        out[p++] = (byte) (v >>> 24);
        out[p++] = (byte) (v >>> 16);
        out[p++] = (byte) (v >>> 8);
        out[p++] = (byte) v;
        return p;
    }

    /** zlib 头：0x78 + 级别提示（FLEVEL），并保证 (CMF<<8|FLG) % 31 == 0。 */
    private static int zlibHeader(int level) {
        int cmf = 0x78;
        int flevel = level <= 1 ? 0 : (level <= 5 ? 1 : (level == 6 ? 2 : 3));
        int flg = flevel << 6;
        int rem = ((cmf << 8) | flg) % 31;
        if (rem != 0) flg += 31 - rem;
        return (cmf << 8) | flg;
    }

    /** 与 zlib 的 adler32_combine 等价：合并两段校验值，len2 是第二段的字节数。 */
    static long adlerCombine(long a1, long a2, long len2) {
        long rem = len2 % BASE;
        long sum1 = a1 & 0xFFFF;
        long sum2 = (rem * sum1) % BASE;
        sum1 += (a2 & 0xFFFF) + BASE - 1;
        sum2 += ((a1 >>> 16) & 0xFFFF) + ((a2 >>> 16) & 0xFFFF) + BASE - rem;
        if (sum1 >= BASE) sum1 -= BASE;
        if (sum1 >= BASE) sum1 -= BASE;
        if (sum2 >= (BASE << 1)) sum2 -= (BASE << 1);
        if (sum2 >= BASE) sum2 -= BASE;
        return sum1 | (sum2 << 16);
    }
}

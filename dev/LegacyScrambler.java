package com.eta.scramble;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * v1.6 旧实现的冻结副本，仅用于 dev/ParityTest 做逐位等价对拍，不参与 APK 构建。
 *
 * 混淆核心算法（纯 Java，不依赖 Android API，可单独测试）。
 *
 * 每一轮（pass）依次执行四步可逆变换：
 *   1) 通道字节旋转：把 RGB 三个字节整体循环移位 8 或 16 位（alpha 不变）
 *   2) 流异或：用 ChaCha20 密钥流对 RGB 逐像素异或（alpha 不变）
 *   3) 行置换：以密钥派生的伪随机序列对整行做 Fisher-Yates 洗牌
 *   4) 列置换：对每一列做同样的洗牌
 * 还原时按相反顺序执行逆变换。整个过程完全由密钥决定，且严格无损。
 */
public final class LegacyScrambler {

    public static final int VERSION = 1;
    public static final int MIN_PASSES = 1;
    public static final int MAX_PASSES = 4;
    /** 密钥留空时使用的默认密钥，保证不填密钥的图片也能被任何人还原。 */
    public static final String DEFAULT_KEY = "混淆图";

    private LegacyScrambler() {}

    public static int clampPasses(int p) {
        if (p < MIN_PASSES) return MIN_PASSES;
        if (p > MAX_PASSES) return MAX_PASSES;
        return p;
    }

    public static void scramble(int[] px, int w, int h, String key, int passes) {
        apply(px, w, h, key, passes, true);
    }

    public static void unscramble(int[] px, int w, int h, String key, int passes) {
        apply(px, w, h, key, passes, false);
    }

    private static void apply(int[] px, int w, int h, String key, int passes, boolean forward) {
        if (px == null || w <= 0 || h <= 0 || px.length < w * h) return;
        String k = (key == null || key.trim().isEmpty()) ? DEFAULT_KEY : key;
        int n = clampPasses(passes);
        byte[] seed = sha256(("EtaScramble|v" + VERSION + "|" + k).getBytes(StandardCharsets.UTF_8));
        int[] buffer = new int[w * h];
        for (int step = 0; step < n; step++) {
            int pass = forward ? step : (n - 1 - step);
            byte[] sub = sha256(concat(seed, new byte[] {(byte) pass, 0x39, 0x5A, 0x11, (byte) VERSION}));
            if (forward) forwardPass(px, buffer, w, h, sub);
            else backwardPass(px, buffer, w, h, sub);
        }
    }

    private static void forwardPass(int[] px, int[] buffer, int w, int h, byte[] sub) {
        int total = w * h;
        int rot = ((stream(sub, "rot").nextByte() & 1) == 0) ? 8 : 16;

        // 1) 通道字节旋转
        for (int i = 0; i < total; i++) {
            int p = px[i];
            int rgb = p & 0x00FFFFFF;
            px[i] = (p & 0xFF000000) | (((rgb << rot) | (rgb >>> (24 - rot))) & 0x00FFFFFF);
        }

        // 2) 密钥流异或
        ChaCha xor = stream(sub, "xor");
        for (int i = 0; i < total; i++) {
            px[i] ^= xor.nextByte() | (xor.nextByte() << 8) | (xor.nextByte() << 16);
        }

        // 3) 行置换
        int[] rows = permutation(stream(sub, "row"), h);
        for (int y = 0; y < h; y++) System.arraycopy(px, rows[y] * w, buffer, y * w, w);
        System.arraycopy(buffer, 0, px, 0, total);

        // 4) 列置换
        int[] cols = permutation(stream(sub, "col"), w);
        for (int y = 0; y < h; y++) {
            int off = y * w;
            for (int x = 0; x < w; x++) buffer[x] = px[off + cols[x]];
            System.arraycopy(buffer, 0, px, off, w);
        }
    }

    private static void backwardPass(int[] px, int[] buffer, int w, int h, byte[] sub) {
        int total = w * h;
        int rot = ((stream(sub, "rot").nextByte() & 1) == 0) ? 8 : 16;

        // 4') 列逆置换
        int[] cols = permutation(stream(sub, "col"), w);
        for (int y = 0; y < h; y++) {
            int off = y * w;
            for (int x = 0; x < w; x++) buffer[cols[x]] = px[off + x];
            System.arraycopy(buffer, 0, px, off, w);
        }

        // 3') 行逆置换
        int[] rows = permutation(stream(sub, "row"), h);
        for (int y = 0; y < h; y++) System.arraycopy(px, y * w, buffer, rows[y] * w, w);
        System.arraycopy(buffer, 0, px, 0, total);

        // 2') 异或（同一密钥流）
        ChaCha xor = stream(sub, "xor");
        for (int i = 0; i < total; i++) {
            px[i] ^= xor.nextByte() | (xor.nextByte() << 8) | (xor.nextByte() << 16);
        }

        // 1') 通道旋转逆变换
        int rrot = 24 - rot;
        for (int i = 0; i < total; i++) {
            int p = px[i];
            int rgb = p & 0x00FFFFFF;
            px[i] = (p & 0xFF000000) | (((rgb << rrot) | (rgb >>> (24 - rrot))) & 0x00FFFFFF);
        }
    }

    private static int[] permutation(ChaCha c, int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = i;
        for (int i = n - 1; i > 0; i--) {
            int j = (int) ((c.nextInt() >>> 1) % (i + 1));
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
        return a;
    }

    private static ChaCha stream(byte[] sub, String tag) {
        byte[] key = sha256(concat(sub, tag.getBytes(StandardCharsets.UTF_8)));
        byte[] n = sha256(concat(sub, (tag + "|nonce").getBytes(StandardCharsets.UTF_8)));
        byte[] nonce = new byte[12];
        System.arraycopy(n, 0, nonce, 0, 12);
        return new ChaCha(key, nonce);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** ChaCha20 密钥流生成器（RFC 8439）。 */
    static final class ChaCha {
        private final int[] state = new int[16];
        private final byte[] block = new byte[64];
        private int index = 64;

        ChaCha(byte[] key, byte[] nonce) {
            state[0] = 0x61707865;
            state[1] = 0x3320646e;
            state[2] = 0x79622d32;
            state[3] = 0x6b206574;
            for (int i = 0; i < 8; i++) state[4 + i] = le32(key, i * 4);
            state[12] = 0;
            for (int i = 0; i < 3; i++) state[13 + i] = le32(nonce, i * 4);
        }

        int nextByte() {
            if (index >= 64) refill();
            return block[index++] & 0xFF;
        }

        int nextInt() {
            return nextByte() | (nextByte() << 8) | (nextByte() << 16) | (nextByte() << 24);
        }

        private void refill() {
            int[] x = state.clone();
            for (int i = 0; i < 10; i++) {
                qr(x, 0, 4, 8, 12);
                qr(x, 1, 5, 9, 13);
                qr(x, 2, 6, 10, 14);
                qr(x, 3, 7, 11, 15);
                qr(x, 0, 5, 10, 15);
                qr(x, 1, 6, 11, 12);
                qr(x, 2, 7, 8, 13);
                qr(x, 3, 4, 9, 14);
            }
            for (int i = 0; i < 16; i++) {
                int v = x[i] + state[i];
                int off = i * 4;
                block[off] = (byte) v;
                block[off + 1] = (byte) (v >>> 8);
                block[off + 2] = (byte) (v >>> 16);
                block[off + 3] = (byte) (v >>> 24);
            }
            state[12]++;
            if (state[12] == 0) state[13]++;
            index = 0;
        }

        private static void qr(int[] x, int a, int b, int c, int d) {
            x[a] += x[b]; x[d] ^= x[a]; x[d] = Integer.rotateLeft(x[d], 16);
            x[c] += x[d]; x[b] ^= x[c]; x[b] = Integer.rotateLeft(x[b], 12);
            x[a] += x[b]; x[d] ^= x[a]; x[d] = Integer.rotateLeft(x[d], 8);
            x[c] += x[d]; x[b] ^= x[c]; x[b] = Integer.rotateLeft(x[b], 7);
        }

        private static int le32(byte[] b, int off) {
            return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
        }
    }
}

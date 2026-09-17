package com.eta.scramble;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * 在 PNG 中写入 / 读取一个 tEXt 块，用来记录算法版本与轮数，
 * 这样还原时可以自动识别参数；即使标记被平台清除，只要密钥和轮数一致也能还原。
 */
public final class PngMeta {

    public static final String KEYWORD = "EtaScramble";
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private PngMeta() {}

    public static boolean isPng(byte[] data) {
        if (data == null || data.length < 8) return false;
        for (int i = 0; i < 8; i++) if (data[i] != SIGNATURE[i]) return false;
        return true;
    }

    /** v2 标记：记录格式、次数与原始尺寸，便于本应用自动还原（含方块混淆的补边裁剪）。 */
    public static String buildTag(int format, int times, int width, int height) {
        return "v=2;fmt=" + format + ";times=" + times + ";w=" + width + ";h=" + height;
    }

    /** 从标记文本里取整数参数，例如 fmt=5 / times=2 / w=720。 */
    public static int getInt(String tag, String key, int fallback) {
        if (tag == null || key == null) return fallback;
        int i = tag.indexOf(key + "=");
        if (i < 0) return fallback;
        int j = i + key.length() + 1;
        int end = j;
        while (end < tag.length() && (Character.isDigit(tag.charAt(end)) || tag.charAt(end) == '-')) end++;
        if (end == j) return fallback;
        try {
            return Integer.parseInt(tag.substring(j, end));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 参数文本，例如 v=1;passes=2 */
    public static String buildTag(int passes) {
        return "v=" + Scrambler.VERSION + ";passes=" + Scrambler.clampPasses(passes);
    }

    public static int parsePasses(String tag, int fallback) {
        if (tag == null) return fallback;
        int i = tag.indexOf("passes=");
        if (i < 0) return fallback;
        int j = i + 7;
        int end = j;
        while (end < tag.length() && Character.isDigit(tag.charAt(end))) end++;
        if (end == j) return fallback;
        try {
            return Scrambler.clampPasses(Integer.parseInt(tag.substring(j, end)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 插入 tEXt 块；不是 PNG 或已存在同名标记时原样返回。 */
    public static byte[] insertText(byte[] png, String text) {
        if (!isPng(png) || text == null) return png;
        if (readText(png) != null) return png;
        byte[] payload = (KEYWORD + "\u0000" + text).getBytes(StandardCharsets.ISO_8859_1);
        byte[] chunk = chunk("tEXt", payload);
        int insertAt = firstChunkEnd(png, "IDAT");
        if (insertAt < 0) insertAt = png.length;
        byte[] out = new byte[png.length + chunk.length];
        System.arraycopy(png, 0, out, 0, insertAt);
        System.arraycopy(chunk, 0, out, insertAt, chunk.length);
        System.arraycopy(png, insertAt, out, insertAt + chunk.length, png.length - insertAt);
        return out;
    }

    /** 读取标记文本，没有则返回 null。 */
    public static String readText(byte[] png) {
        if (!isPng(png)) return null;
        int pos = 8;
        while (pos + 12 <= png.length) {
            int len = be32(png, pos);
            if (len < 0 || pos + 12 + len > png.length) break;
            String type = new String(png, pos + 4, 4, StandardCharsets.US_ASCII);
            if ("tEXt".equals(type)) {
                int start = pos + 8;
                int end = start + len;
                int zero = -1;
                for (int i = start; i < end; i++) {
                    if (png[i] == 0) { zero = i; break; }
                }
                if (zero > start) {
                    String kw = new String(png, start, zero - start, StandardCharsets.US_ASCII);
                    if (KEYWORD.equals(kw)) {
                        return new String(png, zero + 1, end - zero - 1, StandardCharsets.US_ASCII);
                    }
                }
            }
            if ("IEND".equals(type)) break;
            pos += 12 + len;
        }
        return null;
    }

    private static int firstChunkEnd(byte[] png, String wanted) {
        int pos = 8;
        while (pos + 12 <= png.length) {
            int len = be32(png, pos);
            if (len < 0 || pos + 12 + len > png.length) return -1;
            String type = new String(png, pos + 4, 4, StandardCharsets.US_ASCII);
            if (wanted.equals(type)) return pos;
            if ("IEND".equals(type)) return -1;
            pos += 12 + len;
        }
        return -1;
    }

    private static byte[] chunk(String type, byte[] payload) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        writeBe32(bo, payload.length);
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        bo.write(t, 0, 4);
        bo.write(payload, 0, payload.length);
        CRC32 crc = new CRC32();
        crc.update(t, 0, 4);
        crc.update(payload, 0, payload.length);
        writeBe32(bo, (int) crc.getValue());
        return bo.toByteArray();
    }

    private static void writeBe32(ByteArrayOutputStream bo, int v) {
        bo.write((v >>> 24) & 0xFF);
        bo.write((v >>> 16) & 0xFF);
        bo.write((v >>> 8) & 0xFF);
        bo.write(v & 0xFF);
    }

    private static int be32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }
}

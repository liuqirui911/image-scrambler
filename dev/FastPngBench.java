import com.eta.scramble.FastPng;

/**
 * 自带 PNG 编码器的基准：逐级别计时、写出 PNG 与原始 RGBA 供交叉校验。
 * 纯 Java，可在设备 dalvikvm 或桌面 JVM 上跑。
 * 用法：java -cp ... FastPngBench <w> <h> <输出目录>
 */
public class FastPngBench {
    public static void main(String[] args) throws Exception {
        int w = args.length > 0 ? Integer.parseInt(args[0]) : 4000;
        int h = args.length > 1 ? Integer.parseInt(args[1]) : 2000;
        String dir = args.length > 2 ? args[2] : ".";
        int[] px = noise(w, h);
        System.out.println("FastPng " + w + "x" + h + " (" + String.format("%.1f", w * (double) h / 1e6)
                + " MP)，可用线程=" + Runtime.getRuntime().availableProcessors());
        // 预热
        FastPng.encode(noise(400, 300), 400, 300, 1);
        for (int lvl : new int[] {1, 6}) {
            long best = Long.MAX_VALUE;
            int size = 0;
            for (int r = 0; r < 3; r++) {
                long t0 = System.nanoTime();
                byte[] png = FastPng.encode(px, w, h, lvl);
                long t1 = System.nanoTime();
                if (t1 - t0 < best) { best = t1 - t0; size = png.length; }
                if (r == 0 && lvl == 1) {
                    write(dir + "/fastpng.png", png);
                    write(dir + "/fastpng.rgba", rgba(px, w, h));
                    // 再验一次：插入应用自己的 tEXt 标记后，仍必须是能被解码的合法 PNG
                    byte[] tagged = com.eta.scramble.PngMeta.insertText(png,
                            com.eta.scramble.PngMeta.buildTag(0, 1, w, h));
                    write(dir + "/fastpng_tagged.png", tagged);
                    System.out.println("  插入标记后 " + png.length + " → " + tagged.length + " 字节");
                }
            }
            System.out.printf("  level=%d  %6.0f ms  %6.2f MB%n", lvl, best / 1e6, size / 1048576.0);
        }
        System.out.println("DONE");
    }

    static byte[] rgba(int[] px, int w, int h) {
        byte[] out = new byte[w * h * 4];
        int o = 0;
        for (int i = 0; i < w * h; i++) {
            int p = px[i];
            out[o++] = (byte) (p >>> 16);
            out[o++] = (byte) (p >>> 8);
            out[o++] = (byte) p;
            out[o++] = (byte) (p >>> 24);
        }
        return out;
    }

    static void write(String path, byte[] data) throws Exception {
        java.io.FileOutputStream f = new java.io.FileOutputStream(path);
        try { f.write(data); } finally { f.close(); }
    }

    static int[] noise(int w, int h) {
        java.util.Random r = new java.util.Random(7);
        int[] p = new int[w * h];
        for (int i = 0; i < p.length; i++) p[i] = 0xFF000000 | r.nextInt(0x1000000);
        return p;
    }
}

import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;

/**
 * 真机测 PNG 编码：Android 的 Bitmap.compress(PNG, quality) 到底吃不吃 quality，
 * 以及 1080p 噪声图（= 混淆结果）编码要多久。用 dalvikvm 直接在设备上跑。
 */
public class PngBench {
    public static void main(String[] args) {
        int w = args.length > 0 ? Integer.parseInt(args[0]) : 1920;
        int h = args.length > 1 ? Integer.parseInt(args[1]) : 1080;
        int[] px = noise(w, h);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.setPixels(px, 0, w, 0, 0, w, h);
        int[] levels = {100, 90, 60, 30, 0};
        System.out.println("PNG 编码 " + w + "x" + h + "（噪声图，即混淆结果）");
        // 预热
        ByteArrayOutputStream warm = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, warm);
        for (int q : levels) {
            long best = Long.MAX_VALUE;
            int size = 0;
            for (int r = 0; r < 3; r++) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                long t0 = System.nanoTime();
                bmp.compress(Bitmap.CompressFormat.PNG, q, bo);
                long t1 = System.nanoTime();
                if (t1 - t0 < best) { best = t1 - t0; size = bo.size(); }
            }
            System.out.printf("  quality=%3d  %6.0f ms  %6.2f MB%n", q, best / 1e6, size / 1048576.0);
        }
        // 再看看 getPixels / setPixels / createBitmap 的量级
        long t0 = System.nanoTime();
        int[] back = new int[w * h];
        bmp.getPixels(back, 0, w, 0, 0, w, h);
        long t1 = System.nanoTime();
        Bitmap b2 = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b2.setPixels(back, 0, w, 0, 0, w, h);
        long t2 = System.nanoTime();
        System.out.printf("  getPixels=%.0f ms  createBitmap+setPixels=%.0f ms%n", (t1 - t0) / 1e6, (t2 - t1) / 1e6);
        System.out.println("DONE");
    }

    static int[] noise(int w, int h) {
        java.util.Random r = new java.util.Random(7);
        int[] p = new int[w * h];
        for (int i = 0; i < p.length; i++) p[i] = 0xFF000000 | r.nextInt(0x1000000);
        return p;
    }
}

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;

/**
 * 真机测平台原生路径：PNG 编码（Bitmap.compress）与 JPEG 解码（BitmapFactory）。
 * 裸 dalvikvm 里图形库没被加载，这里显式 loadLibrary("hwui") 把 JNI 注册上再跑。
 */
public class PngBench {
    public static void main(String[] args) throws Exception {
        String lib = args.length > 0 ? args[0] : "hwui";
        try {
            if (lib.indexOf(47) >= 0) System.load(lib); else System.loadLibrary(lib);
            System.out.println("loadLibrary(" + lib + ") ok");
        } catch (Throwable t) {
            System.out.println("loadLibrary(" + lib + ") 失败: " + t);
        }
        int w = 4000, h = 2000;
        int[] px = noise(w, h);
        Bitmap bmp;
        try {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(px, 0, w, 0, 0, w, h);
        } catch (Throwable t) {
            System.out.println("Bitmap 不可用: " + t);
            return;
        }
        System.out.println("噪声图 PNG 编码 " + w + "x" + h);
        for (int q : new int[] {100, 60, 0}) {
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
        // 照片类内容对照（渐变+噪声，比纯噪声可压缩）
        int[] photo = photoLike(w, h);
        Bitmap b2 = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b2.setPixels(photo, 0, w, 0, 0, w, h);
        for (int q : new int[] {100}) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            long t0 = System.nanoTime();
            b2.compress(Bitmap.CompressFormat.PNG, q, bo);
            long t1 = System.nanoTime();
            System.out.printf("  照片类 quality=%3d  %6.0f ms  %6.2f MB%n", q, (t1 - t0) / 1e6, bo.size() / 1048576.0);
        }
        // JPEG 解码（相机原图量级）
        if (args.length > 1) {
            String file = args[1];
            long t0 = System.nanoTime();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap dec = BitmapFactory.decodeFile(file, o);
            long t1 = System.nanoTime();
            System.out.printf("  解码 %s → %dx%d  %.0f ms%n", file, dec.getWidth(), dec.getHeight(), (t1 - t0) / 1e6);
        }
        System.out.println("DONE");
    }

    static int[] noise(int w, int h) {
        java.util.Random r = new java.util.Random(7);
        int[] p = new int[w * h];
        for (int i = 0; i < p.length; i++) p[i] = 0xFF000000 | r.nextInt(0x1000000);
        return p;
    }

    static int[] photoLike(int w, int h) {
        java.util.Random r = new java.util.Random(11);
        int[] p = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                p[y * w + x] = 0xFF000000 | ((x * 255 / w) << 16) | ((y * 255 / h) << 8) | r.nextInt(24);
            }
        }
        return p;
    }
}

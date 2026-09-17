import com.eta.scramble.PopularCodecs;
import java.nio.file.Files;
import java.nio.file.Paths;

/** 用本应用的实现处理同一张图的 RGBA 原始数据，与官网 JS 的输出逐字节比对。 */
public class JsCompat {
    public static void main(String[] args) throws Exception {
        byte[] in = Files.readAllBytes(Paths.get(args[0]));
        byte[] ref = Files.readAllBytes(Paths.get(args[1]));
        int w = Integer.parseInt(args[2]);
        int h = Integer.parseInt(args[3]);
        int n = w * h;

        int[] pixels = new int[n];
        for (int i = 0; i < n; i++) {
            pixels[i] = ((in[i * 4 + 3] & 0xFF) << 24) | ((in[i * 4] & 0xFF) << 16) | ((in[i * 4 + 1] & 0xFF) << 8) | (in[i * 4 + 2] & 0xFF);
        }
        PopularCodecs.Result mine = PopularCodecs.transform(PopularCodecs.FORMAT_TOMATO, pixels, w, h, "", 1, true);

        int diff = 0, first = -1;
        for (int i = 0; i < n; i++) {
            int a = mine.pixels[i];
            int refPixel = ((ref[i * 4 + 3] & 0xFF) << 24) | ((ref[i * 4] & 0xFF) << 16) | ((ref[i * 4 + 1] & 0xFF) << 8) | (ref[i * 4 + 2] & 0xFF);
            if (a != refPixel) {
                diff++;
                if (first < 0) first = i;
            }
        }
        System.out.printf("小番茄官网 JS vs 本应用实现：像素 %d，差异 %d%n", n, diff);
        if (diff > 0) System.out.printf("首个差异 @%d ref=%08x mine=%08x%n", first,
                ((ref[first * 4 + 3] & 0xFF) << 24) | ((ref[first * 4] & 0xFF) << 16) | ((ref[first * 4 + 1] & 0xFF) << 8) | (ref[first * 4 + 2] & 0xFF), mine.pixels[first]);
        System.out.println(diff == 0 ? "=== 与官网原版完全一致（可互相还原）" : "=== 不一致");
    }
}

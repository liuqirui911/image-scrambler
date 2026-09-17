import com.eta.scramble.PngMeta;
import com.eta.scramble.Scrambler;
import java.nio.file.Files;
import java.nio.file.Paths;

public class Verify {
    public static void main(String[] args) throws Exception {
        byte[] png = Files.readAllBytes(Paths.get(args[0]));
        byte[] outRgb = Files.readAllBytes(Paths.get(args[1]));
        byte[] inRgb = Files.readAllBytes(Paths.get(args[2]));
        int w = Integer.parseInt(args[3]);
        int h = Integer.parseInt(args[4]);
        String key = args[5];

        String tag = PngMeta.readText(png);
        int passes = PngMeta.parsePasses(tag, 2);
        System.out.println("PNG 标记 = " + tag + " → 轮数 " + passes);

        int n = w * h;
        if (outRgb.length < n * 3L) {
            System.out.println("raw 字节数不足: " + outRgb.length);
            return;
        }
        int[] px = new int[n];
        for (int i = 0; i < n; i++) {
            px[i] = 0xFF000000
                    | ((outRgb[i * 3] & 0xFF) << 16)
                    | ((outRgb[i * 3 + 1] & 0xFF) << 8)
                    | (outRgb[i * 3 + 2] & 0xFF);
        }

        // 混淆结果是否已经是噪声：相邻像素几乎不可能相同
        int sameNeighbor = 0;
        for (int i = 1; i < n; i++) if (px[i] == px[i - 1]) sameNeighbor++;
        System.out.println("相邻像素相同数 = " + sameNeighbor + " / " + n);

        Scrambler.unscramble(px, w, h, key, passes);

        int diff = 0;
        int shown = 0;
        for (int i = 0; i < n; i++) {
            int ref = 0xFF000000
                    | ((inRgb[i * 3] & 0xFF) << 16)
                    | ((inRgb[i * 3 + 1] & 0xFF) << 8)
                    | (inRgb[i * 3 + 2] & 0xFF);
            if (ref != px[i]) {
                diff++;
                if (shown < 3) {
                    System.out.printf("差异 @%d: 还原=%08x 原图=%08x%n", i, px[i], ref);
                    shown++;
                }
            }
        }
        System.out.println("像素数 = " + n + "，不一致 = " + diff);
        System.out.println(diff == 0 ? "ROUNDTRIP OK（App 输出可无损还原）" : "ROUNDTRIP FAIL");
    }
}

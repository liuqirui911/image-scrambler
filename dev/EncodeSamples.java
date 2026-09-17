import com.eta.scramble.PopularCodecs;
import java.nio.file.Files;
import java.nio.file.Paths;

/** 用参考算法生成混淆图，模拟"别人用网络工具做出来的图"。 */
public class EncodeSamples {
    public static void main(String[] args) throws Exception {
        byte[] in = Files.readAllBytes(Paths.get(args[0]));
        int w = Integer.parseInt(args[1]);
        int h = Integer.parseInt(args[2]);
        int fmt = Integer.parseInt(args[3]);
        String key = args[4];
        int n = w * h;
        int[] px = new int[n];
        for (int i = 0; i < n; i++) {
            px[i] = 0xFF000000 | ((in[i * 3] & 0xFF) << 16) | ((in[i * 3 + 1] & 0xFF) << 8) | (in[i * 3 + 2] & 0xFF);
        }
        PopularCodecs.Result r = PopularCodecs.transform(fmt, px, w, h, key, 1, true);
        byte[] out = new byte[r.pixels.length * 3];
        for (int i = 0; i < r.pixels.length; i++) {
            out[i * 3] = (byte) ((r.pixels[i] >> 16) & 0xFF);
            out[i * 3 + 1] = (byte) ((r.pixels[i] >> 8) & 0xFF);
            out[i * 3 + 2] = (byte) (r.pixels[i] & 0xFF);
        }
        Files.write(Paths.get(args[5]), out);
        System.out.println("wrote " + r.width + "x" + r.height + " " + PopularCodecs.name(fmt));
    }
}

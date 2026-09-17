# 测试与验证

本目录是算法与兼容性的自测集，**不参与 APK 构建**。运行：

```bash
bash dev/run_tests.sh
```

## 各文件做什么

| 文件 | 作用 | 依赖 |
|---|---|---|
| `Test.java` | 本机格式（ChaCha20 密钥流）：多尺寸 × 多密钥 × 1-4 轮的无损往返、错误密钥/错误轮数不可还原、PNG 标记读写、3MP 性能 | 无 |
| `CompatTest2.java` | 与参考实现逐格式对拍：尺寸 × 密钥 × 加解密两个方向必须像素相同；含 1-3 次叠加的往返自洽 | `ObfuscationUtils` 源码 |
| `tomato_ref.js` | 小番茄官网前端逻辑的复刻（曲线生成 + 循环位移，含 enc/dec 两个分支） | Node.js |
| `JsCompat.java` | 用本应用实现处理同一张图，与 `tomato_ref.js` 的输出逐字节比对 | 上一步的输出 |
| `EncodeSamples.java` | 用参考算法把一张 raw RGB 图混淆成样例，用于「别人做的混淆图」还原测试 | 无 |
| `Verify.java` | 端到端：读入 App 真实产出的 PNG，做逆变换并与原图逐像素比对 | 待验证的 PNG |

## 关于参考实现对拍

`CompatTest2` 需要先准备参考实现源码（仅用于测试，不随仓库分发）：

```bash
mkdir -p /tmp/refs && git clone --depth 1 \
  https://github.com/2195517546/ObfuscationUtils /tmp/refs/ObfuscationUtils
```

`JsCompat` 需要一张测试图：`run_tests.sh` 会在 `test/in.png` 缺失时用 `raw/icon_source.webp` 自动生成。

## 实测结论（2026-09-17）

- `Test`：全部尺寸/密钥/轮数无损往返通过；错误密钥与错误轮数还原后相同像素数为 0。
- `CompatTest2`：6 种格式在 4 种尺寸 × 多密钥下，加密、解密两个方向与参考实现**逐像素相同**。
- `JsCompat`：与小番茄官网逻辑对拍 **518400 像素 0 差异**。
- 端到端：官网逻辑生成的 JPEG 混淆图由本应用自动识别还原；PicEncrypt 行列混淆 PNG 由本应用还原后与原始图
  **逐字节完全一致（1555200 字节 0 差异）**；本应用产出的 PNG 由官网逻辑解混淆后同样逐字节一致。

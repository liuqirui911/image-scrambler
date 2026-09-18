<div align="center">

<img src="docs/icon.webp" width="128" alt="混淆图 图标">

# 混淆图 · Image Scrambler

**完全离线的 Android 图片混淆 / 还原应用：能还原网络上流行的混淆图，也能用同一套格式生成。**

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Min SDK](https://img.shields.io/badge/minSdk-26%20(Android%208.0)-green.svg)](#环境要求)
[![APK](https://img.shields.io/badge/APK-106%20KB-brightgreen.svg)](#体积预算)

</div>

> **English**: An offline Android app for obfuscating and restoring images with the formats popular in the
> Chinese community — Xiaofanqie (Gilbert space-filling curve), PicEncrypt (logistic-map sorting),
> row-pixel / per-pixel / block permutation — plus a ChaCha20-based format of its own.
> Pixel-level compatible in both directions with the original tools. ~100 KB APK, no network permission,
> multi-core accelerated (8 MP auto-restore in ~0.6 s on a modern phone).

---

## 它解决什么问题

网上的"混淆图"是把图片像素按固定规则打乱成噪声图、用来绕过平台审查的图，需要对应工具才能还原。
现成工具要么是网页版（要把图传到别人服务器）、要么是单一格式、要么夹广告。这个应用：

- **默认就是网络通用格式**，别人用网页版 / 其它 App 做的混淆图，丢进来能自动识别并还原；
- 反过来，本应用生成的混淆图，网页版工具也能还原（双向互通，见[兼容性验证](#兼容性怎么证明的)）；
- **完全离线**：清单里没有任何联网权限，图片只在设备内存中处理，不写临时文件；
- 同时保留一个**本应用独有的高强度格式**（ChaCha20 密钥流 + 行列洗牌，支持任意密钥与 1-4 轮）。

## 支持的格式

| # | 格式 | 算法 | 密钥 |
|---|------|------|------|
| 0 | 小番茄混淆 | Gilbert 空间填充曲线遍历 + 循环位移，`offset = round(0.6180339887 × 像素数)` | 不需要 |
| 1 | PicEncrypt 行列混淆 | Logistic 混沌映射排序（`x ← 3.9999999·x·(1−x)`），逐行 + 逐列置换 | 0-1 小数，默认 `0.666` |
| 2 | PicEncrypt 行模式混淆 | 同上，同一行置换作用于每一行 | 0-1 小数，默认 `0.666` |
| 3 | 行像素混淆 | `MD5(key+i)` 派生 Fisher-Yates 置换，逐行重排像素 | 任意串，默认 `0.666` |
| 4 | 全像素混淆 | 同上，x / y 两个置换共同决定每个像素的去向 | 任意串，默认 `0.666` |
| 5 | 方块混淆 | 同上，按 32×32 方块打乱，尺寸补齐到 32 的倍数 | 任意串，默认 `0.666` |
| 6 | 本机密钥流混淆 | ChaCha20 密钥流 + 行/列洗牌（`Scrambler.java`） | 任意串（含中文/emoji），1-4 轮 |

格式 0-5 的像素映射与各自原版实现逐像素对齐；格式 6 是本项目独有的加密强度更高的实现。

## 兼容性怎么证明的

不是"看起来能还原"，而是逐像素对拍过的（脚本都在 `dev/`，可复现）：

| 验证 | 结果 |
|---|---|
| 与参考实现 `ObfuscationUtils` 对拍 6 种格式（4 种尺寸 × 多密钥 × 加解密两个方向） | **全部逐像素相同** |
| 与小番茄官网前端逻辑对拍（`dev/tomato_ref.js`） | **518400 像素 0 差异** |
| 官网逻辑生成的 JPEG 混淆图 → 本应用自动识别并还原 | 识别为「小番茄混淆」，315 ms |
| PicEncrypt 行列混淆 PNG → 本应用还原 → 与原始图比对 | **1555200 字节 0 差异** |
| 本应用生成的 PNG → 官网逻辑解混淆 → 与原始图比对 | **逐字节完全一致** |

```bash
bash dev/run_tests.sh        # 一键复现（详见 dev/README.md）
```

## 下载

[**Releases**](https://github.com/liuqirui911/image-scrambler/releases/latest) 里直接下载 APK（约 100 KB，Android 8.0+）。
附件名用 ASCII（`ImageScrambler-v<版本>.apk`）——GitHub 会吞掉 Release 附件名里的中文。

## 使用

**混淆**：选图片 → 选格式与次数（默认小番茄、1 次）→ 开始混淆 → 保存到相册 / 分享。
结果必须**无损传递**（PNG / 原文件发送），聊天软件的二次压缩会破坏可还原性。

**还原**：切到「还原」直接选图即可。

- 图片带本应用写入的标记（PNG `tEXt`，`v=2;fmt=…;times=…;w=…;h=…`）→ 直接用标记里的格式与次数，并裁掉方块混淆补齐的边缘；
- 没有标记 → 依次尝试 6 种通用格式，用「相邻像素灰度差」挑出最像照片的结果；若结果仍像噪声，会自动改用 2 次再试一遍；
- 也可以关掉「自动识别格式」，手动指定格式、密钥与次数。

## 构建

### 环境要求

- Linux（Debian 系即可）+ JDK 17 以上
- `aapt2`、`zipalign`、`apksigner`、`imagemagick`：`apt-get install aapt zipalign apksigner android-sdk-build-tools imagemagick`
- `d8.jar`：来自 Android SDK build-tools（放到 `tools/lib/d8.jar`）
- `android.jar`：Android platform 包（放到 `android-32/android.jar`，本仓库不包含，见 `.gitignore`）

### 编译

```bash
bash build.sh          # 产物：build/混淆图-<版本>.apk
```

脚本流程：`aapt2 compile` 资源 → `aapt2 link`（含 `--auto-add-overlay`，因为 values-night 与 values 同名资源需要覆盖）
→ `javac` 编译 Java（`-source/-target 8`，`-bootclasspath android.jar`）→ `d8` 转 dex →
`zip` 打包 → `zipalign -f 4` → `apksigner` 只签 v2/v3。首次运行会自动生成 `keystore/eta.keystore`（已 gitignore）。

替换应用图标：把自己的图存成 `raw/icon_source.webp`，然后 `bash scripts/make_icons.sh`。

## 项目结构

```
app/
  AndroidManifest.xml
  java/com/eta/scramble/
    Scrambler.java          本机格式：ChaCha20 密钥流 + 行列洗牌（纯 Java，可独立测试）
    PopularCodecs.java      6 种网络通用格式 + 自动识别 + 平滑度评分
    Par.java                轻量并行调度：按行/列/像素区间切片（结果与串行逐位一致，见「性能」）
    PngMeta.java            PNG tEXt 标记读写（格式 / 次数 / 原始尺寸）
    SegmentedControl.java   MIUI 风格自绘分段控件（含无障碍支持）
    MainActivity.java       主界面：混淆 / 还原、流式解码、保存与分享
    AboutActivity.java      关于页
  res/                      布局、drawable、字符串、图标
build.sh                    手工构建脚本（aapt2 + javac + d8 + zipalign + apksigner）
scripts/make_icons.sh       图标流水线（自适应图标 + WebP，见下）
dev/                        算法与兼容性测试（不参与构建，见 dev/README.md）
raw/icon_source.webp        图标原图（作者插画）
docs/                       README 用图
```

## 设计说明

### 体积预算

APK 从最初的 535 KB 优化到 **106.5 KB**（v1.7 加入多核并行后增加约 4 KB），做法：

- **图标**：minSdk 26 起系统只会用 `mipmap-anydpi-v26` 的自适应图标，因此不再生成 5 档传统 PNG
  （省 266 KB）；自适应前景只留 xxxhdpi 一档（432px = 108dp）+ **WebP q92**（34 KB，同尺寸 PNG 需 240 KB）。
  前景插画缩到画布 96% 居中，给各家启动器的圆/方圆/水滴遮罩留余量。
- **签名**：minSdk 26 无需 v1，只签 v2/v3（再省 META-INF 约 4 KB）。
- 界面内引用前景图时配白色圆角底板 + `clipToOutline`，否则自适应前景是方形、界面里会丢圆角。

### 性能

所有格式都是**离散访存型置换**（每个输出像素独立算一个来源/去向，彼此不重叠），所以按行、列或像素区间
切成 8 份交给多核并行即可，不需要 GPU：并行只切分互不重叠的输出区间，结果与串行实现**逐位完全一致**
（`dev/ParityTest` 用 7 格式 × 加解密 × 1-4 次 × 11 种尺寸共 3212 例与 v1.6 冻结实现对拍）。

具体做了四件事：

1. **切片并行**：`Par.each` 把区间分给 `availableProcessors`（上限 8）个线程，小于 26 万像素的直接串行，
   避免线程开销超过计算本身。
2. **Logistic 链跳步**：PicEncrypt 逐行/逐列置换改成每段用 `f^((w-1)·j)(key)` 直接跳到自己那一段的起点
   独立推进（同一串确定性 double 迭代，逐位相同），并复用每段的排序缓冲——原来每行都要新分配 4 个数组，
   2000 行就是 160 MB 的垃圾。
3. **密钥流分段定位**：本机格式把「通道旋转 + 密钥流异或」合并成一次遍历；每段用自己的 ChaCha20 流，
   用字节偏移把块计数器直接跳到 `3·像素下标` 处，密钥流逐字节与顺序读取相同。
4. **热路径微优化**：去掉内层循环的 `%`（改自增回绕）、复用 `MessageDigest` 实例、Gilbert 曲线按固定步长写入、
   自动识别不再为每个候选格式克隆整图。

真机实测（ART，同一台设备，`dev/DeviceBench`）：

| 场景（8 MP） | v1.6 | v1.7 | 加速 |
|---|---|---|---|
| 自动识别（还原主路径） | 2452 ms | 593 ms | **4.1×** |
| PicEncrypt 行列混淆 | 2137 ms | 322 ms | **6.6×** |
| 行像素混淆 | 183 ms | 13 ms | **13.9×** |
| 本机密钥流混淆 | 310 ms | 81 ms | **3.8×** |
| 全像素混淆 | 149 ms | 46 ms | 3.2× |
| 方块混淆 | 98 ms | 35 ms | 2.8× |
| 小番茄混淆 | 108 ms | 102 ms | 1.06× |

6 MP 时自动识别 1781 → 398 ms。**唯一没提速的是小番茄**：它的耗时几乎全在 Gilbert 曲线的下标序列生成上，
那是一个深度优先递归遍历，写序必须严格顺序（属于光栅化前的串行准备工作），正在做的是把叶子循环的
寻址常数化，已经比 v1.6 快约 20%。

**为什么没上 GPU**：这些算法的代价在随机访存而不在算术吞吐，GPU 的 gather/scatter 反而更吃亏；
而且要么走 Vulkan（需要 NDK，多出几百 KB 的 `.so`，与「体积越小越好」直接冲突），要么走 GLES 片元着色器
（框架自带、加不了多少体积，但要把置换表编成纹理、受纹理尺寸上限约束，且索引/密钥流生成仍在 CPU）——
在 8 核 CPU 已经做到 0.3-0.6 s 的前提下，收益不足以抵消复杂度和体积。真要继续压，
下一步应该是把小番茄的曲线序生成也并行化，而不是换 GPU。

### 读取与报错

选图走两遍流式解码（先读尺寸算采样率，再按需解码），**不把整张原图读进内存**，因此没有文件体积上限；
PNG 标记与 EXIF 只读文件开头 256 KB。读取失败按真实原因提示（权限不足 / 云相册未下载 / 相机 RAW / 解码失败 / 内存不足），
并把底层异常写入日志（tag `混淆图`），便于排查。混淆像素上限 800 万，还原时按堆内存放宽到 1200 万；
由于缩放会破坏像素映射，还原时若被迫缩小会明确提示。

### 界面

纯自绘 MIUI 风格（无 Compose / MIUIX 依赖）：大标题、胶囊分段控件、18dp 圆角卡片、MIUI 开关、
下滑格式选择面板、深色模式、i18n 字符串资源。分段控件与面板都做了无障碍（状态描述、`ACTION_CLICK` 可操作）。

## 已知限制

- 混淆结果必须无损传输；经过会重新压缩图片的聊天软件后，小番茄系因保留邻域相关性仍可大致还原（有色差），
  其余格式会失效。
- 方块混淆会把尺寸补齐到 32 的倍数；用本应用输出（带标记）再还原时会自动裁掉多余边缘。
- 自动识别只在"默认参数"范围内尝试（通用格式 + 默认密钥 0.666 + 1 或 2 次）。对方改了密钥/次数时需要手动指定。
- 若对方用的是某种自研且不兼容以上格式的变体，本应用无法还原。

## 更新日志

见 [CHANGELOG.md](CHANGELOG.md)。

## 许可证与致谢

- 本项目以 [MIT 许可证](LICENSE) 发布，© 2026 liuqirui911。
- 算法实现参考了 [ObfuscationUtils](https://github.com/2195517546/ObfuscationUtils)（MIT, © 2025 uiloalxise）
  与小番茄混淆网页版的公开逻辑，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
- 应用图标所用插画由项目作者提供，不在 MIT 授权范围内。

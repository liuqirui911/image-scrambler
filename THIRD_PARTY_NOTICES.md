# 第三方声明 / Third-Party Notices

本项目的部分算法实现参考了下列公开实现，按 MIT 许可证要求在此保留版权声明。

## ObfuscationUtils

本项目 `PopularCodecs.java` 中的 PicEncrypt 行列 / 行模式混淆、行像素混淆、全像素混淆、方块混淆，
与参考实现 `ObfuscationUtils` 的像素映射逐像素对齐（用于兼容性验证）。

- 项目：https://github.com/2195517546/ObfuscationUtils
- 许可证：MIT License，Copyright (c) 2025 uiloalxise

```
MIT License

Copyright (c) 2025 uiloalxise

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## 小番茄混淆（Gilbert 空间填充曲线）

`PopularCodecs.java` 中的 `FORMAT_TOMATO` 实现（Gilbert 曲线遍历 + `offset = round(0.6180339887 × 像素数)` 循环位移）
按 xiaofanqiehunxiao.com 网页版前端的公开逻辑复刻，用于与该工具互通；`dev/tomato_ref.js` 保留了该前端逻辑的复刻版本，
仅用于兼容性测试。网页版未附带许可证说明，此处仅作来源标注。

## 其它

- 应用图标所用插画由本项目作者提供（`raw/icon_source.webp`），不在 MIT 授权范围内，替换成你自己的图片即可。
- `android.jar`、Android SDK build-tools 等构建依赖归各自版权方所有，不随本仓库分发。

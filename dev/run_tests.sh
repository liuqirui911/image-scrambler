#!/bin/bash
# 算法与兼容性自测（不参与 APK 构建）
set -e
cd "$(dirname "$0")/.."

mkdir -p build/core

echo "== 编译核心算法"
javac -nowarn -d build/core \
  app/java/com/eta/scramble/Par.java \
  app/java/com/eta/scramble/Scrambler.java \
  app/java/com/eta/scramble/PngMeta.java \
  app/java/com/eta/scramble/PopularCodecs.java

echo "== 本机格式自测（无损性 / 错误密钥 / 性能）"
javac -nowarn -cp build/core -d build/core dev/Test.java
java -Xmx1g -cp build/core Test

echo "== 并行实现与 v1.6 冻结副本逐位对拍（7 格式 × 加解密 × 1-4 次 × 多尺寸）"
javac -nowarn -cp build/core -d build/core \
  dev/LegacyScrambler.java dev/LegacyCodecs.java dev/ParityTest.java
java -Xmx1g -cp build/core ParityTest

# 与参考实现对拍（可选，需要先把参考仓库 clone 到 /tmp/refs）
if [ -d /tmp/refs/ObfuscationUtils/src/main/java ]; then
  echo "== 与参考实现 ObfuscationUtils 逐格式对拍"
  mkdir -p build/ref
  javac -nowarn -d build/ref $(find /tmp/refs/ObfuscationUtils/src/main/java -name '*.java')
  javac -nowarn -cp build/ref:build/core -d build/core \
    dev/CompatTest2.java dev/JsCompat.java dev/EncodeSamples.java dev/Verify.java
  java -cp build/ref:build/core CompatTest2
else
  echo "（跳过参考实现对拍：未找到 /tmp/refs/ObfuscationUtils）"
  echo "  git clone --depth 1 https://github.com/2195517546/ObfuscationUtils /tmp/refs/ObfuscationUtils"
fi

# 与小番茄官网逻辑对拍（需要 node 与 convert）
if command -v node >/dev/null 2>&1 && command -v convert >/dev/null 2>&1; then
  if [ ! -f test/in.png ]; then
    mkdir -p test
    convert raw/icon_source.webp -resize 720x720 -strip test/in.png
  fi
  W=$(identify -format '%w' test/in.png)
  H=$(identify -format '%h' test/in.png)
  echo "== 与小番茄官网 JS 对拍（${W}x${H}）"
  convert test/in.png -depth 8 rgba:- > /tmp/in.rgba
  node dev/tomato_ref.js /tmp/in.rgba /tmp/ref_tomato.rgba "$W" "$H"
  java -cp build/ref:build/core JsCompat /tmp/in.rgba /tmp/ref_tomato.rgba "$W" "$H"
else
  echo "（跳过官网逻辑对拍：需要 node 与 ImageMagick）"
fi

echo "== 全部测试结束"

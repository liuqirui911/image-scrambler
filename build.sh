#!/bin/bash
# 混淆图 —— 手工构建脚本（Debian 工具环境内使用 aapt2 + javac + d8 + zipalign + apksigner）
set -e
cd /workspace/scramble

APP=app
OUT=build
SDK=$PWD/android-32
ANDROID_JAR=$SDK/android.jar
D8JAR=$PWD/tools/lib/d8.jar
KS=$PWD/keystore/eta.keystore
KSPASS=etascramble

if [ ! -f "$D8JAR" ]; then echo "缺少 d8.jar：$D8JAR"; exit 1; fi
if [ ! -f "$ANDROID_JAR" ]; then echo "缺少 android.jar：$ANDROID_JAR"; exit 1; fi

rm -rf $OUT/gen $OUT/classes $OUT/dex $OUT/res.zip $OUT/*.apk
mkdir -p $OUT/gen $OUT/classes $OUT/dex

echo "==> aapt2 compile"
aapt2 compile --dir $APP/res -o $OUT/res.zip

echo "==> aapt2 link"
aapt2 link -o $OUT/app-unsigned.apk \
  -I $ANDROID_JAR \
  --manifest $APP/AndroidManifest.xml \
  -R $OUT/res.zip \
  --java $OUT/gen \
  --min-sdk-version 26 \
  --target-sdk-version 33 \
  --version-code 8 \
  --version-name 1.7 \
  --auto-add-overlay

echo "==> javac"
find $APP/java $OUT/gen -name '*.java' > $OUT/sources.txt
javac -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
  -d $OUT/classes @$OUT/sources.txt

echo "==> d8 (dex)"
find $OUT/classes -name '*.class' > $OUT/classes.txt
java -cp "$D8JAR" com.android.tools.r8.D8 --min-api 26 --lib "$ANDROID_JAR" \
  --output $OUT/dex @$OUT/classes.txt

echo "==> package"
cp $OUT/app-unsigned.apk $OUT/app.apk
rm -f $OUT/app-unsigned.apk
zip -q -j $OUT/app.apk $OUT/dex/classes.dex

echo "==> zipalign"
zipalign -f 4 $OUT/app.apk $OUT/app-aligned.apk
rm -f $OUT/app.apk

echo "==> sign"
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -storepass $KSPASS -keypass $KSPASS \
    -alias eta -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Eta Scramble,O=Eta,C=CN" -J-Duser.language=en >/dev/null
fi
apksigner sign --ks "$KS" --ks-pass pass:$KSPASS --key-pass pass:$KSPASS \
  --v1-signing-enabled false --v2-signing-enabled true \
  --out "$OUT/混淆图-1.7.apk" $OUT/app-aligned.apk
rm -f $OUT/app-aligned.apk

echo "==> verify"
apksigner verify --verbose "$OUT/混淆图-1.7.apk" | head -12
echo "==> badging"
aapt dump badging "$OUT/混淆图-1.7.apk" 2>/dev/null | head -8
echo "==> size"
ls -la "$OUT/混淆图-1.7.apk" | awk '{printf "APK: %s  (%.1f KB)\n", $NF, $5/1024}'

#!/bin/bash
# 应用图标流水线（精简版 v2）—— 用户认可的默认做法
#
# 目标：图标好看 + 体积小。要点：
#   1) minSdk 26 起系统只会用 mipmap-anydpi-v26 的自适应图标，传统多档 PNG 是死重量，直接不生成；
#   2) 自适应前景只留 xxxhdpi（108dp = 432px）一档，用 WebP q92（432px 仅约 38KB，PNG 要 240KB）；
#   3) 前景插画缩到画布 96% 居中，给各家的圆/方圆/水滴遮罩留余量；
#   4) 背景纯白（插画自带白底，无边缝）。
#
# 用法：把用户给的图（webp / jpg / png 都行）放到 raw/icon_source.webp，然后 bash scripts/make_icons.sh
set -e
cd /workspace/scramble

RES=app/res
SRC=${1:-raw/icon_source.webp}

mkdir -p $RES/mipmap-anydpi-v26 $RES/mipmap-xxxhdpi

# 清掉旧版多档 PNG（自适应图标上线后不再需要）
rm -rf $RES/mipmap-mdpi $RES/mipmap-hdpi $RES/mipmap-xhdpi $RES/mipmap-xxhdpi
rm -f $RES/mipmap-xxxhdpi/ic_launcher.png $RES/mipmap-xxxhdpi/ic_launcher_round.png
rm -f $RES/mipmap-xxxhdpi/ic_launcher_foreground.png

# 统一基准图：任何格式先转 1024 PNG，并去掉元数据
convert "$SRC" -strip -resize 1024x1024 raw/base.png
identify raw/base.png

# 自适应图标前景：432px 画布 + 96% 插画居中 + WebP q92
INNER=414          # 432 * 96%
convert raw/base.png -resize ${INNER}x${INNER} -strip /tmp/icon_fg.png
convert -size 432x432 xc:none /tmp/icon_canvas.png
convert /tmp/icon_canvas.png /tmp/icon_fg.png -gravity center -composite \
        -define webp:method=6 -quality 92 \
        $RES/mipmap-xxxhdpi/ic_launcher_foreground.webp
identify $RES/mipmap-xxxhdpi/ic_launcher_foreground.webp

# 自适应图标描述文件（背景色见 values/colors.xml 的 ic_launcher_background）
cat > $RES/mipmap-anydpi-v26/ic_launcher.xml <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
XML
cp $RES/mipmap-anydpi-v26/ic_launcher.xml $RES/mipmap-anydpi-v26/ic_launcher_round.xml

echo "--- 图标资源体积"
du -sh $RES
find $RES/mipmap-anydpi-v26 $RES/mipmap-xxxhdpi -type f | sort

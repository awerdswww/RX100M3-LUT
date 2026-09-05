#!/bin/bash
# LUT 引擎 → smali 注入包构建（bridge-work）
# 产物：build/smali-out/com/sonylut/bridge/*.smali（拷进 apktool 树即完成注入）
set -e
cd "$(dirname "$0")"

SDK=${ANDROID_SDK_ROOT:?need ANDROID_SDK_ROOT}
STUBS=${SONY_STUBS:?need SONY_STUBS}
ANDROID_JAR=$SDK/platforms/android-10/android.jar
DX=$SDK/build-tools/25.0.2/dx
APKTOOL=/d/pmca-tool/apktool/apktool.jar

rm -rf build
mkdir -p build/classes build/stub-classes build/dex build/apk

echo "== javac 编译期 stub（不进 dex）"
javac -encoding UTF-8 -source 1.6 -target 1.6 -nowarn \
  -bootclasspath "$ANDROID_JAR" -cp "$STUBS" \
  -d build/stub-classes \
  stub-src/com/sony/imaging/app/base/shooting/camera/CameraSetting.java

echo "== javac bridge（stub 作 classpath）"
javac -encoding UTF-8 -source 1.6 -target 1.6 -nowarn \
  -bootclasspath "$ANDROID_JAR" -cp "$STUBS;build/stub-classes" \
  -d build/classes \
  src/com/sonylut/bridge/*.java

echo "== dx（build/classes 根，只有 bridge）"
"$DX" --dex --output=build/dex/classes.dex build/classes

echo "== 打最小 apk 供 apktool 反解出 smali"
cd build/apk
cp ../dex/classes.dex .
zip -q -r mini.apk classes.dex
java -jar "$APKTOOL" d -f -o mini-out mini.apk >/dev/null

echo "== 收集产物"
mkdir -p ../smali-out
cp -r mini-out/smali/com ../smali-out/
ls -la ../smali-out/com/sonylut/bridge/ | head -10
echo "== DONE: build/smali-out/com/sonylut/bridge/"

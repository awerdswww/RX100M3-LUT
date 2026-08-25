#!/bin/bash
# CUSTOM LUT 手动构建脚本（无 gradle，PMCA 相机是 Android 2.3 / API 10）
#
# 需要的环境变量：
#   ANDROID_SDK_ROOT  Android SDK 路径（需含 platforms/android-10 与 build-tools/25.0.2）
#   SONY_STUBS        索尼 CameraEx 编译 stub JAR（默认 ./stubs/sony_cameraex_stubs.jar，
#                     需自行从相机提取，方法见 docs/BUILD.md）
set -e

SDK=${ANDROID_SDK_ROOT:?请先设置 ANDROID_SDK_ROOT，见 docs/BUILD.md}
STUBS=${SONY_STUBS:-./stubs/sony_cameraex_stubs_rx100m3.jar}
AAPT=$SDK/build-tools/25.0.2/aapt
DX=$SDK/build-tools/25.0.2/dx
ANDROID_JAR=$SDK/platforms/android-10/android.jar
KEYSTORE=debug.keystore

if [ ! -f "$STUBS" ]; then
  echo "错误: 找不到索尼编译 stub: $STUBS"
  echo "stub 需从你自己的相机提取（本仓库不提供），方法见 docs/BUILD.md"
  exit 1
fi

cd "$(dirname "$0")"
rm -rf gen classes out
mkdir -p gen classes out

# v1 签名 keystore（Android 2.3 只认 jarsigner 签名）；首次构建自动生成
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -alias debug -keyalg RSA -keysize 2048 -validity 10000 \
    -keystore "$KEYSTORE" -storepass android -keypass android \
    -dname "CN=Debug,O=CustomLut,C=CN"
fi

# 现代 JDK 默认禁用 SHA1，临时放开（仅本次 jarsigner 调用生效）
LEGACY_PROPS=$(mktemp /tmp/jarsigner-legacy.XXXXXX.properties)
echo "jdk.jar.disabledAlgorithms=" > "$LEGACY_PROPS"
trap 'rm -f "$LEGACY_PROPS"' EXIT

echo "== aapt: 生成 R.java"
"$AAPT" package -f -m -J gen -M AndroidManifest.xml -S res -I "$ANDROID_JAR"

echo "== javac"
find gen src -name '*.java' > sources.txt
javac -source 1.6 -target 1.6 -nowarn \
  -bootclasspath "$ANDROID_JAR" -cp "$STUBS" \
  -d classes @sources.txt

echo "== dx"
"$DX" --dex --output=out/classes.dex classes/

echo "== aapt: 打包 APK"
"$AAPT" package -f -M AndroidManifest.xml -S res -I "$ANDROID_JAR" \
  -F out/CustomLut-unsigned.apk

echo "== 注入 classes.dex"
cd out
zip -q CustomLut-unsigned.apk classes.dex
cd ..

echo "== jarsigner 签名"
cp out/CustomLut-unsigned.apk CustomLut.apk
jarsigner -J-Djava.security.properties="$LEGACY_PROPS" \
  -keystore "$KEYSTORE" -storepass android -keypass android \
  -sigalg SHA1withRSA -digestalg SHA1 CustomLut.apk debug

echo "== 完成: $(ls -la CustomLut.apk | awk '{print $5}') bytes -> CustomLut.apk"

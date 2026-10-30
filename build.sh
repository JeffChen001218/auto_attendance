#!/bin/sh
set -eu
cd "$(dirname "$0")"

ANDROID_SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-"$HOME/Library/Android/sdk"}}
BUILD_TOOLS_VERSION=${BUILD_TOOLS_VERSION:-35.0.0}
ANDROID_PLATFORM=${ANDROID_PLATFORM:-30}
AUTOLARK_KEYSTORE=${AUTOLARK_KEYSTORE:-"$HOME/.android/debug.keystore"}
BUILD_TOOLS="$ANDROID_SDK/build-tools/$BUILD_TOOLS_VERSION"
ANDROID_JAR="$ANDROID_SDK/platforms/android-$ANDROID_PLATFORM/android.jar"

[ -f "$ANDROID_JAR" ] || { echo "Missing Android platform: $ANDROID_JAR" >&2; exit 1; }
[ -x "$BUILD_TOOLS/aapt2" ] || { echo "Missing Android build tools: $BUILD_TOOLS" >&2; exit 1; }

if [ ! -f "$AUTOLARK_KEYSTORE" ]; then
    mkdir -p "$(dirname "$AUTOLARK_KEYSTORE")"
    keytool -genkeypair -noprompt -keystore "$AUTOLARK_KEYSTORE" \
        -alias androiddebugkey -storepass android -keypass android \
        -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 \
        -validity 10000
fi

mkdir -p build/classes build/dex
find build/classes build/dex -type f -delete
javac -source 8 -target 8 -Xlint:-options -classpath "$ANDROID_JAR" \
    -d build/classes src/com/codex/autolark/*.java
jar cf build/classes.jar -C build/classes .
"$BUILD_TOOLS/d8" --min-api 26 --lib "$ANDROID_JAR" \
    --output build/dex build/classes.jar
"$BUILD_TOOLS/aapt2" link -o build/unsigned.apk \
    --manifest AndroidManifest.xml -I "$ANDROID_JAR" \
    --min-sdk-version 26 --target-sdk-version 30
(cd build/dex && "$BUILD_TOOLS/aapt" add ../unsigned.apk classes.dex)
"$BUILD_TOOLS/zipalign" -f 4 build/unsigned.apk build/aligned.apk
"$BUILD_TOOLS/apksigner" sign \
    --ks "$AUTOLARK_KEYSTORE" \
    --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
    --out build/autolark.apk build/aligned.apk
"$BUILD_TOOLS/apksigner" verify build/autolark.apk

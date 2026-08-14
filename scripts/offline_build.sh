#!/usr/bin/env bash
# ============================================================================
# Оффлайн-сборка release APK без Gradle (для окружений без доступа к Maven).
# Обычная сборка: ./gradlew assembleRelease (см. README.md).
#
# Требуемые инструменты (пути передаются через переменные окружения):
#   JAVA_HOME      - JDK/JRE 17+
#   KOTLINC        - каталог kotlinc (bin/kotlinc)
#   BUILD_TOOLS    - Android build-tools (aapt2, zipalign, lib/d8.jar, lib/apksigner.jar)
#   PLATFORM_JAR   - android.jar (API 35)
#   LIBS_DIR       - каталог с извлечёнными AAR (ext/<name>/{classes.jar,res,AndroidManifest.xml})
#   JARS_DIR       - каталог с чистыми jar-зависимостями
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app"
OUT="$ROOT/out/offline-build"
APK_DIR="$APP/build/outputs/apk/release"
DIST="$ROOT/dist"

JAVA="$JAVA_HOME/bin/java"
AAPT2="$BUILD_TOOLS/aapt2"
ZIPALIGN="$BUILD_TOOLS/zipalign"
D8_JAR="$BUILD_TOOLS/lib/d8.jar"
APKSIGNER_JAR="$BUILD_TOOLS/lib/apksigner.jar"

MIN_SDK=24
TARGET_SDK=35
VERSION_CODE=2
VERSION_NAME="2.0.0"
APP_ID="ai.arena.webapp"

# Примечание: для сборки v2 нужен AAR androidx.webkit (ProxyController),
# извлечённый в LIBS_DIR, как и остальные библиотеки:
#   ext/webkit/{classes.jar,res,AndroidManifest.xml}

rm -rf "$OUT" && mkdir -p "$OUT"/{flat,gen,classes,dex,apk} "$APK_DIR" "$DIST"

echo "==> [1/8] Merged AndroidManifest"
python3 "$ROOT/scripts/merge_manifest.py" \
    "$APP/src/main/AndroidManifest.xml" "$OUT/AndroidManifest.xml" \
    "$APP_ID" "$MIN_SDK" "$TARGET_SDK"

echo "==> [2/8] aapt2 compile (app + libraries)"
"$AAPT2" compile --dir "$APP/src/main/res" -o "$OUT/flat/app.zip"
for d in "$LIBS_DIR"/*/; do
    name="$(basename "$d")"
    if [ -d "$d/res" ] && [ -n "$(find "$d/res" -type f 2>/dev/null | head -1)" ]; then
        "$AAPT2" compile --dir "$d/res" -o "$OUT/flat/lib-$name.zip"
    fi
done

echo "==> [3/8] aapt2 link"
LINK_ARGS=(
    -o "$OUT/apk/base.apk"
    -I "$PLATFORM_JAR"
    --manifest "$OUT/AndroidManifest.xml"
    --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK"
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"
    --auto-add-overlay
    --output-text-symbols "$OUT/R.txt"
)
for f in "$OUT"/flat/lib-*.zip; do LINK_ARGS+=("$f"); done
LINK_ARGS+=("$OUT/flat/app.zip")
"$AAPT2" link "${LINK_ARGS[@]}"

echo "==> [4/8] Generate R classes (Kotlin)"
R_PACKAGES="$APP_ID"
for d in "$LIBS_DIR"/*/; do
    pkg="$(grep -o 'package="[^"]*"' "$d/AndroidManifest.xml" | head -1 | cut -d'"' -f2)"
    R_PACKAGES="$R_PACKAGES,$pkg"
done
python3 "$ROOT/scripts/gen_r_kotlin.py" "$OUT/R.txt" "$OUT/gen" "$R_PACKAGES"

echo "==> [5/8] kotlinc"
CP="$PLATFORM_JAR"
for j in "$JARS_DIR"/*.jar; do CP="$CP:$j"; done
for d in "$LIBS_DIR"/*/; do [ -f "$d/classes.jar" ] && CP="$CP:$d/classes.jar"; done
JAVA_HOME="$JAVA_HOME" "$KOTLINC/bin/kotlinc" \
    -classpath "$CP" \
    -jvm-target 17 -no-reflect \
    -d "$OUT/classes" \
    $(find "$APP/src/main/java" -name '*.kt') \
    $(find "$OUT/gen" -name '*.kt') 2>&1 | grep -v "^warning:" || true

echo "==> [6/8] d8 (dex)"
cd "$OUT/classes" && zip -qr "$OUT/app-classes.jar" . && cd "$ROOT"
D8_INPUTS=("$OUT/app-classes.jar")
for d in "$LIBS_DIR"/*/; do [ -f "$d/classes.jar" ] && D8_INPUTS+=("$d/classes.jar"); done
for j in "$JARS_DIR"/*.jar; do D8_INPUTS+=("$j"); done
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 \
    --release --min-api "$MIN_SDK" \
    --lib "$PLATFORM_JAR" \
    --output "$OUT/dex" \
    "${D8_INPUTS[@]}"
test -f "$OUT/dex/classes.dex"

echo "==> [7/8] Package + zipalign"
cp "$OUT/apk/base.apk" "$OUT/apk/unsigned.apk"
cd "$OUT/dex" && zip -q "$OUT/apk/unsigned.apk" classes*.dex && cd "$ROOT"
"$ZIPALIGN" -f -p 4 "$OUT/apk/unsigned.apk" "$OUT/apk/aligned.apk"

echo "==> [8/8] apksigner"
"$JAVA" -jar "$APKSIGNER_JAR" sign \
    --ks "$APP/keystore/release.keystore" \
    --ks-pass pass:arenaai123 --key-pass pass:arenaai123 \
    --ks-key-alias arena \
    --out "$APK_DIR/app-release.apk" \
    "$OUT/apk/aligned.apk"
"$JAVA" -jar "$APKSIGNER_JAR" verify --print-certs "$APK_DIR/app-release.apk" | head -5

cp "$APK_DIR/app-release.apk" "$DIST/ArenaAI-release.apk"
echo ""
echo "APK: $APK_DIR/app-release.apk"
echo "APK (копия): $DIST/ArenaAI-release.apk"

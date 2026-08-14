#!/usr/bin/env bash
# ============================================================================
# Оффлайн-сборка release APK без Gradle и без доступа к Maven/Google.
# Полностью автономный пайплайн:
#   aapt2 (ресурсы) -> kotlinc (Kotlin) -> dx (DEX) -> jarsigner (подпись)
#
# Требуемые инструменты (пути через переменные окружения):
#   JAVA_HOME       - JDK/JRE 17+ (java, javac, jarsigner, keytool)
#   KOTLIN_COMPILER_JAR - kotlin-compiler.jar (K2JVMCompiler)
#   KOTLIN_STDLIB_JAR   - kotlin-stdlib.jar (без META-INF/versions и indy-классов)
#   AAPT2           - бинарник aapt2 (linux x86_64)
#   ANDROID_JAR     - jar с классами платформы (compile classpath)
#   FRAMEWORK_RES   - framework-res.apk (таблица ресурсов для aapt2 -I)
#   DX_CLASSES      - каталог с классами com/android/dx (собранный dx)
#
# Как получить инструменты в изолированной среде - см. README.md,
# раздел «Сборка без Android SDK».
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app"
OUT="$ROOT/out/offline-build"
APK_DIR="$APP/build/outputs/apk/release"
DIST="$ROOT/dist"

JAVA="$JAVA_HOME/bin/java"
JARSIGNER="$JAVA_HOME/bin/jarsigner"

MIN_SDK=26
TARGET_SDK=29
VERSION_CODE=5
VERSION_NAME="2.2.0"
APP_ID="ai.arena.webapp"

rm -rf "$OUT" && mkdir -p "$OUT"/{flat,gen,classes,dex,apk} "$APK_DIR" "$DIST"

echo "==> [1/7] Merged AndroidManifest"
python3 "$ROOT/scripts/merge_manifest.py" \
    "$APP/src/main/AndroidManifest.xml" "$OUT/AndroidManifest.xml" \
    "$APP_ID" "$MIN_SDK" "$TARGET_SDK"

echo "==> [2/7] aapt2 compile"
"$AAPT2" compile --dir "$APP/src/main/res" -o "$OUT/flat/app.zip"

echo "==> [3/7] aapt2 link (framework-res.apk как -I)"
"$AAPT2" link \
    -o "$OUT/apk/base.apk" \
    -I "$FRAMEWORK_RES" \
    --manifest "$OUT/AndroidManifest.xml" \
    --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    --auto-add-overlay \
    --output-text-symbols "$OUT/R.txt" \
    "$OUT/flat/app.zip"

echo "==> [4/7] Generate R classes (Kotlin)"
python3 "$ROOT/scripts/gen_r_kotlin.py" "$OUT/R.txt" "$OUT/gen" "$APP_ID"

echo "==> [5/7] kotlinc"
# Kotlin 2.0 по умолчанию генерирует лямбды и SAM-конверсии через
# invokedynamic (LambdaMetafactory). Dexer dx не умеет desugar
# invoke-custom, а ART такие инструкции не исполняет — приложение
# падало при запуске. Поэтому обе схемы переключаем на классы.
"$JAVA" -Xmx2g -cp "$KOTLIN_COMPILER_JAR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -classpath "$ANDROID_JAR" \
    -jvm-target 1.8 \
    -Xlambdas=class \
    -Xsam-conversions=class \
    -no-reflect \
    -d "$OUT/classes" \
    $(find "$APP/src/main/java" -name '*.kt') \
    $(find "$OUT/gen" -name '*.kt') 2>&1 | grep -v "^warning:" || true
test -n "$(find "$OUT/classes" -name '*.class' | head -1)"

echo "==> [6/7] dx (classes -> DEX)"
cd "$OUT/classes" && zip -qr "$OUT/app-classes.jar" . && cd "$ROOT"
"$JAVA" -cp "$DX_CLASSES" com.android.dx.command.Main --dex \
    --min-sdk-version "$MIN_SDK" \
    --output="$OUT/dex" \
    "$OUT/app-classes.jar" \
    "$KOTLIN_STDLIB_JAR"
test -f "$OUT/dex/classes.dex"

echo "==> [7/7] Package + sign (jarsigner, v1)"
cp "$OUT/apk/base.apk" "$OUT/apk/unsigned.apk"
cd "$OUT/dex" && zip -q "$OUT/apk/unsigned.apk" classes.dex && cd "$ROOT"
"$JARSIGNER" -keystore "$APP/keystore/release.keystore" \
    -storepass arenaai123 -keypass arenaai123 \
    -sigfile CERT -digestalg SHA-256 -sigalg SHA256withRSA \
    "$OUT/apk/unsigned.apk" arena
"$JARSIGNER" -verify "$OUT/apk/unsigned.apk" | head -3

cp "$OUT/apk/unsigned.apk" "$APK_DIR/app-release.apk"
cp "$APK_DIR/app-release.apk" "$DIST/ArenaAI-release.apk"
echo ""
echo "APK: $APK_DIR/app-release.apk"
echo "APK (копия): $DIST/ArenaAI-release.apk"

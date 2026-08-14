#!/usr/bin/env bash
# Оффлайн-тесты криптографии и SOCKS5-туннеля VPN.
# Использует те же инструменты, что и offline_build.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/out/vpn-tests"

JAVA="${JAVA_HOME:-/home/user/tools/package/jre}/bin/java"
KOTLIN_COMPILER_JAR="${KOTLIN_COMPILER_JAR:-/home/user/tools/kc/lib/kotlin-compiler.jar}"
KOTLIN_STDLIB_JAR="${KOTLIN_STDLIB_JAR:-/home/user/tools/kotlin-stdlib-final.jar}"

rm -rf "$OUT" && mkdir -p "$OUT"

echo "==> kotlinc (тесты + VPN-ядро без Android-зависимостей)"
"$JAVA" -Xmx1g -cp "$KOTLIN_COMPILER_JAR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 1.8 \
    -no-reflect \
    -d "$OUT" \
    "$ROOT/tools/tests/TestMain.kt" \
    "$ROOT/tools/tests/SocksTest.kt" \
    "$ROOT/app/src/main/java/ai/arena/webapp/vpn/Blake2s.kt" \
    "$ROOT/app/src/main/java/ai/arena/webapp/vpn/ChaCha20Poly1305.kt" \
    "$ROOT/app/src/main/java/ai/arena/webapp/vpn/Hkdf.kt" \
    "$ROOT/app/src/main/java/ai/arena/webapp/vpn/X25519.kt" \
    "$ROOT/app/src/main/java/ai/arena/webapp/vpn/WireGuardSession.kt" \
    "$ROOT/app/src/main/java/ai/arena/webapp/vpn/Socks5Client.kt" \
    "$ROOT/app/src/main/java/ai/arena/webapp/vpn/SocksTcpStack.kt" \
    "$ROOT/app/src/main/java/ai/arena/webapp/vpn/DnsResolver.kt" \
    2>&1 | grep -v "^warning:" || true

echo "==> run crypto"
"$JAVA" -cp "$OUT:$KOTLIN_STDLIB_JAR" ai.arena.test.TestMain
echo ""
echo "==> run socks"
"$JAVA" -cp "$OUT:$KOTLIN_STDLIB_JAR" ai.arena.test.SocksTest

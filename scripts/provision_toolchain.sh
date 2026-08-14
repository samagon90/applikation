#!/usr/bin/env bash
# ============================================================================
# Провижининг оффлайн-тулчейна (npm + GitHub, без Google/Maven).
# Используется в изолированных средах, где /home/user пересоздаётся.
# Подробности: scripts/offline_toolchain.md
# ============================================================================
set -euo pipefail
T="${TOOLS_DIR:-/home/user/tools}"
REPO="${REPO_DIR:-/home/user/applikation}"
mkdir -p "$T" && cd "$T"

echo "==> 1/8 JRE 17 (npm)"
rm -rf package && npm pack javajre-linux-64 --silent >/dev/null && tar xzf javajre-linux-64-*.tgz

echo "==> 2/8 kotlin-compiler (npm)"
rm -rf kc && mkdir kc && npm pack kotlin-compiler@2.0.21 --silent >/dev/null && tar xzf kotlin-compiler-2.0.21.tgz -C kc --strip-components=1

echo "==> 3/8 aapt2 (npm)"
rm -rf aapt && mkdir aapt && npm pack aaptjs3 --silent >/dev/null && tar xzf aaptjs3-*.tgz -C aapt --strip-components=1 && chmod +x aapt/bin/x64/linux/aapt2

echo "==> 4/8 dx (AOSP source, javac)"
rm -rf pd dx-classes && curl -sL --max-time 600 "https://codeload.github.com/aosp-mirror/platform_dalvik/tar.gz/refs/heads/main" -o pd.tgz
mkdir pd && tar xzf pd.tgz -C pd --strip-components=1
mkdir -p dx-classes
find pd/dx/src -name '*.java' > dx-sources.txt
package/jre/bin/javac -nowarn -d dx-classes @dx-sources.txt 2>&1 | grep -v "unchecked\|Recompile" || true

echo "==> 5/8 framework.jar (прошивочный дамп) + enjarify"
rm -rf sdk && mkdir -p sdk
sha=$(gh api "repos/theworkjoy/lge_mdh50lm_dump/contents/system/system/framework/framework.jar?ref=mdh50lm-user-10-QKQ1.200216.002-220211534ef56-release-keys" --jq '.sha')
gh api "repos/theworkjoy/lge_mdh50lm_dump/git/blobs/$sha" --jq '.content' | base64 -d > sdk/framework-29.jar
rm -rf enjarify && curl -sL --max-time 300 "https://codeload.github.com/google/enjarify/tar.gz/refs/heads/master" -o enj.tgz
mkdir enjarify && tar xzf enj.tgz -C enjarify --strip-components=1
cp sdk/framework-29.jar sdk/framework-29.apk
(cd enjarify && python3 -O -m enjarify.main -f "$T/sdk/framework-29.apk" -o "$T/sdk/framework-classes.jar" 2>&1 | tail -1)

echo "==> 6/8 ASM (OpenJDK source) + FixInner"
rm -rf jdk17u asm-full.jar asmout asmsrc fix
git clone -q --depth 1 --filter=blob:none --sparse https://github.com/openjdk/jdk17u jdk17u
(cd jdk17u && git sparse-checkout set src/java.base/share/classes/jdk/internal/org/objectweb/asm >/dev/null 2>&1)
mkdir -p asmsrc asmout
python3 - <<'PYEOF'
import os
SRC = '/home/user/tools/jdk17u/src/java.base/share/classes/jdk/internal/org/objectweb'
DST = '/home/user/tools/asmsrc'
for root, dirs, files in os.walk(SRC):
    rel = os.path.relpath(root, SRC)
    for f in files:
        if not f.endswith('.java'):
            continue
        out_path = os.path.join(DST, os.path.join(rel, f) if rel != '.' else f)
        os.makedirs(os.path.dirname(out_path), exist_ok=True)
        text = open(os.path.join(root, f)).read().replace('jdk.internal.org.objectweb', 'org.objectweb')
        open(out_path, 'w').write(text)
PYEOF
find asmsrc -name '*.java' > asm-sources.txt
package/jre/bin/javac -nowarn -d asmout @asm-sources.txt 2>&1 | grep -E "error" | head -3 || true
(cd asmout && zip -qr ../asm-full.jar .)
mkdir -p fix && cp "$REPO/scripts/fix_inner_classes/FixInner.java" fix/
package/jre/bin/javac -encoding UTF-8 -cp asm-full.jar -d fix fix/FixInner.java
package/jre/bin/java -Xmx2g -cp fix:asm-full.jar FixInner sdk/framework-classes.jar sdk/framework-fixed.jar

echo "==> 7/8 framework-res.apk (для aapt2 -I)"
sha=$(gh api "repos/DroidDumps/lge_mh2lm_dump/contents/system/system/framework/framework-res.apk?ref=mh2lm-user-9-PKQ1.190522.001-193302209c0a6-release-keys" --jq '.sha')
gh api "repos/DroidDumps/lge_mh2lm_dump/git/blobs/$sha" --jq '.content' | base64 -d > sdk/framework-res.apk

echo "==> 8/8 kotlin-stdlib-clean (без indy и module-info)"
rm -rf slib && mkdir slib && (cd slib && unzip -q ../kc/lib/kotlin-stdlib.jar)
rm -rf slib/META-INF/versions slib/kotlin/streams slib/kotlin/uuid
rm -f slib/kotlin/comparisons/ComparisonsKt__ComparisonsKt.class
find slib -name 'module-info.class' -delete
(cd slib && zip -qr ../kotlin-stdlib-final.jar .)

echo "==> DONE"
package/jre/bin/java -version 2>&1 | head -1
aapt/bin/x64/linux/aapt2 version 2>&1 | head -1

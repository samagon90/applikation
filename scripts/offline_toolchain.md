# Инструменты для оффлайн-сборки (без Android SDK)

`scripts/offline_build.sh` собирает APK без Gradle и без доступа к
Google/Maven. Ниже — проверенный рецепт получения всех инструментов
в среде, где доступны только **github.com, registry.npmjs.org
и pypi.org** (например, изолированная песочница). Если у вас обычная
машина с интернетом — просто скачайте то же самое из первоисточников.

## 1. JRE/JDK 17 (java, javac, jarsigner, keytool)

npm-пакет `javajre-linux-64` содержит полный JRE 17:

```bash
npm pack javajre-linux-64
tar xzf javajre-linux-64-*.tgz
# java лежит в package/jre/bin/
```

## 2. Компилятор Kotlin (kotlin-compiler.jar + kotlin-stdlib.jar)

npm-пакет `kotlin-compiler` (та же сборка, что и у JetBrains):

```bash
npm pack kotlin-compiler@2.0.21
tar xzf kotlin-compiler-2.0.21.tgz
# lib/kotlin-compiler.jar  — компилятор
# lib/kotlin-stdlib.jar    — стандартная библиотека
```

### Подготовка stdlib для старого dexer (dx)

В stdlib есть `module-info.class` (multi-release jar) и несколько
классов с invokedynamic. Для dx их нужно убрать:

```bash
mkdir slib && cd slib && unzip -q kotlin-stdlib.jar
rm -rf META-INF/versions kotlin/streams kotlin/uuid
find . -name module-info.class -delete
zip -qr ../kotlin-stdlib-clean.jar .
```

## 3. aapt2 (linux x86_64)

npm-пакет `aaptjs3` содержит бинарники aapt2 для всех платформ:

```bash
npm pack aaptjs3
tar xzf aaptjs3-*.tgz
# package/bin/x64/linux/aapt2
```

## 4. dx (dexer) — сборка из исходников AOSP

Релизные бинарники недоступны, но исходники `dx` лежат в git-зеркале
AOSP. Собирается обычным javac (без зависимостей):

```bash
curl -L https://codeload.github.com/aosp-mirror/platform_dalvik/tar.gz/refs/heads/main -o pd.tgz
tar xzf pd.tgz
mkdir dx_out
javac -nowarn -d dx_out $(find platform_dalvik-main/dx/src -name '*.java')
# использовать: java -cp dx_out com.android.dx.command.Main ...
```

## 5. android.jar (классы платформы) + framework-res.apk

Здесь два независимых артефакта:

1. **framework.jar** (dex-файлы) — из дампа прошивки, закоммиченного
   в git (например, `theworkjoy/lge_mdh50lm_dump`,
   `system/system/framework/framework.jar`). Качается через Git blobs API.
2. **framework-res.apk** (таблица ресурсов для `aapt2 -I`) — из дампа
   `DroidDumps/lge_mh2lm_dump` (`system/system/framework/framework-res.apk`).

Затем framework.jar конвертируется в class-файлы:

```bash
# enjarify (конвертер DEX -> JVM class, чистый Python) + rename .apk:
cp framework.jar framework.apk
python3 -O -m enjarify.main -f framework.apk -o framework-classes.jar
```

### Починка InnerClasses

enjarify не пишет атрибуты `InnerClasses`, поэтому компиляторы не видят
вложенные классы (`WebChromeClient.CustomViewCallback` и т.п.). Это
чинится утилитой `scripts/fix_inner_classes/FixInner.java`:

```bash
# ASM берётся из исходников OpenJDK (git, без Maven):
git clone --depth 1 --filter=blob:none --sparse https://github.com/openjdk/jdk17u
cd jdk17u && git sparse-checkout set src/java.base/share/classes/jdk/internal/org/objectweb/asm
# переименовать пакет jdk.internal.org.objectweb -> org.objectweb, затем:
javac -d asm_out $(find asm_src -name '*.java')

javac -cp asm_out -d . FixInner.java
java -cp .:asm_out FixInner framework-classes.jar framework-fixed.jar
```

`framework-fixed.jar` — это `ANDROID_JAR` для сборки.

## 6. Подпись

Подписывает `jarsigner` из JRE (схема v1 — достаточно для установки
из неизвестных источников). Keystore уже лежит в репозитории:
`app/keystore/release.keystore`.

---

## Итоговые переменные для offline_build.sh

| Переменная          | Что это                                   |
|---------------------|-------------------------------------------|
| `JAVA_HOME`         | JRE 17 из п.1                              |
| `KOTLIN_COMPILER_JAR` | `lib/kotlin-compiler.jar` из п.2         |
| `KOTLIN_STDLIB_JAR` | `kotlin-stdlib-clean.jar` из п.2           |
| `AAPT2`             | бинарник из п.3                            |
| `ANDROID_JAR`       | `framework-fixed.jar` из п.5               |
| `FRAMEWORK_RES`     | `framework-res.apk` из п.5                 |
| `DX_CLASSES`        | каталог с классами dx из п.4               |

Проверенный результат: `dist/ArenaAI-release.apk` (v2.0.0).

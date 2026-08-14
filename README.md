# Arena AI — Android-приложение

Нативное Android-приложение (Kotlin), которое оборачивает сайт
**https://arena.ai** в WebView, с интерфейсом в стиле iOS и
встроенным **AI-помощником**: он подсказывает, как составить промпт,
какая модель лучше подойдёт под задачу, и проводит по сайту.

| Параметр        | Значение               |
|-----------------|------------------------|
| applicationId   | `ai.arena.webapp`      |
| minSdk          | 24 (Android 7.0)       |
| targetSdk       | 35 (Gradle) / 29 (оффлайн-сборка) |
| Язык            | Kotlin                 |
| Зависимости     | **нет** (только платформенные API) |
| Версия          | 3.0.0 (versionCode 7)  |

Готовый установочный файл v3.0.0: **`dist/ArenaAI-release.apk`**
(собран без Android SDK — см. «Сборка без Android SDK»).
APK прошлой версии: `dist/ArenaAI-v1.0.0.apk`.

---

## Возможности

### 🤖 AI-помощник

Встроенный помощник (кнопка ✨ в нижней панели) — путеводитель по
arena.ai:

- **Конструктор промптов**: описываете задачу своими словами —
  помощник составляет готовый запрос (роль, требования, формат ответа)
  и подсказывает подходящий режим сайта.
- **Советник по моделям**: по типу задачи (код, веб-разработка,
  картинки, видео, перевод, математика, свежие факты, агентные задачи)
  подсказывает, какую категорию лидерборда смотреть и что выбрать.
- **Путеводитель по сайту**: Battle Mode, Direct, лидерборд и его
  категории, история и поиск, WebDev-шаблоны, советы по промптам.
- Быстрые подсказки-чипы («Составь промпт», «Какая модель лучше?»…).
- Работает **мгновенно и офлайн** (локальная база знаний); свободные
  вопросы дополнительно отправляются в бесплатный LLM
  (pollinations.ai, без ключа), а при недоступности сети помощник
  отвечает локально.

### 🎨 Интерфейс в стиле iOS

- Фиксированные панели (верхняя навигация + нижний toolbar) —
  контент сайта ничего не перекрывает.
- Прогресс-бар в стиле iOS Safari, карточки, системная палитра.
- Шторка помощника — настоящий iOS bottom sheet: граббер, пружинное
  появление, свайп вниз для закрытия, чат с пузырями и полем ввода.
- Тёмная/светлая темы по системе, локализация en/ru.

### 🧩 Браузер

WebView с полным набором: JS, DOM storage, cookies, загрузка и
скачивание файлов, полноэкранное видео, камера/микрофон, сохранение
состояния при повороте, только HTTPS. Никаких сторонних библиотек.

## Сборка

### Обычная (Gradle + Android SDK)

Требования: JDK 17, Android SDK platform 35, интернет для Gradle.

```bash
./gradlew assembleRelease
# результат: app/build/outputs/apk/release/app-release.apk
```

### Без Android SDK (полностью оффлайн)

Готовый пайплайн `scripts/offline_build.sh` повторяет сборку вручную:
`aapt2 → kotlinc → dx → jarsigner`. Все инструменты берутся из
доступных источников (npm, исходники на GitHub) — полная инструкция
по их получению: [`scripts/offline_toolchain.md`](scripts/offline_toolchain.md).

```bash
export JAVA_HOME=/path/to/jre17
export KOTLIN_COMPILER_JAR=/path/to/kotlin-compiler.jar
export KOTLIN_STDLIB_JAR=/path/to/kotlin-stdlib-clean.jar
export AAPT2=/path/to/aapt2
export ANDROID_JAR=/path/to/framework-fixed.jar
export FRAMEWORK_RES=/path/to/framework-res.apk
export DX_CLASSES=/path/to/dx-classes
bash scripts/offline_build.sh
# результат: dist/ArenaAI-release.apk (подписан keystore из репозитория)
```

> Примечание: оффлайн-вариант собирается против Android 9 (API 28/29)
> framework, поэтому APK имеет `targetSdk 29`; Gradle-сборка использует
> полноценный SDK и `targetSdk 35`.

## Подпись

Release подписывается сгенерированным keystore
`app/keystore/release.keystore`:

- alias: `arena`
- store/key password: `arenaai123`
- сертификат: `CN=Arena AI, OU=Mobile, O=Arena, C=SG`, RSA-2048

> **Для публикации** замените keystore своим и обновите `signingConfigs`
> в `app/build.gradle.kts` (пароли вынесите в переменные окружения).

## Установка на устройство

```bash
adb install dist/ArenaAI-release.apk
```

Либо передайте APK на телефон (мессенджер, USB, облако), откройте файл
и разрешите установку из неизвестных источников. Android 8.0+.

## Структура проекта

```
app/src/main/java/ai/arena/webapp/
  MainActivity.kt            — активность: WebView, панели, чат помощника
  ArenaWebViewClient.kt      — навигация внутри arena.ai, внешние ссылки — системе
  ArenaWebChromeClient.kt    — upload, видео, permission-запросы
  assistant/
    AssistantEngine.kt       — локальная база знаний: промпты, модели, путеводитель
    AiBackend.kt             — бесплатный LLM для свободных вопросов (с фолбэком)
app/src/main/res/            — layout, drawables, строки (en/ru), темы
scripts/
  offline_build.sh           — оффлайн-сборка APK без Gradle/Android SDK
  provision_toolchain.sh     — провижининг тулчейна (npm + GitHub)
  offline_toolchain.md       — как добыть инструменты для оффлайн-сборки
  fix_inner_classes/         — починка InnerClasses в jar из DEX (для android.jar)
dist/
  ArenaAI-release.apk        — готовый подписанный APK v3.0.0
  ArenaAI-v1.0.0.apk         — APK прошлой версии
```

## Ограничения

- Это WebView-обёртка: контент полностью зависит от сайта arena.ai.
- Push-уведомления (FCM) не реализованы.
- Помощник на свободные вопросы использует бесплатный LLM-сервис
  (pollinations.ai) — если он недоступен, отвечает локальная база
  знаний (промпты, модели, путеводитель работают всегда).
- Google Play может отклонять обёртки над чужими сайтами —
  распространяйте APK напрямую.


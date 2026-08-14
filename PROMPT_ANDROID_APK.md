# Промт для ИИ-агента: Android .apk приложение для сайта arena.ai

Скопируй текст ниже и отправь его ИИ-агенту (Cursor, Copilot Workspace, Claude Code, Codex и т.п.).

---

## ПРОМТ

Ты — опытный Android-разработчик. Твоя задача — создать с нуля полноценное нативное Android-приложение (Kotlin), которое оборачивает сайт **https://arena.ai** в WebView, и собрать из него готовый установочный файл **.apk**.

### 1. Общие требования

- Язык: **Kotlin**, система сборки: **Gradle (Kotlin DSL)**.
- Минимальная версия Android: **API 24 (Android 7.0)**, целевая: **API 34+**.
- Название приложения: **Arena AI**, applicationId: `ai.arena.webapp`.
- Проект должен собираться командой `./gradlew assembleRelease` без ручных правок.
- Итог работы: файл `app/build/outputs/apk/release/app-release.apk`, подписанный debug- или сгенерированным keystore, готовый к установке на устройство.

### 2. Функциональность WebView

- При запуске приложение открывает `https://arena.ai` на весь экран (edge-to-edge, с корректными отступами под системные панели).
- Включить: `javaScriptEnabled`, `domStorageEnabled`, `databaseEnabled`, поддержку cookies (включая сторонние, `CookieManager`), кэширование.
- User-Agent оставить стандартный мобильный (не desktop), чтобы сайт отдавал мобильную версию.
- Все переходы внутри доменов `arena.ai` и `*.arena.ai` открывать внутри WebView; внешние ссылки (другие домены, `mailto:`, `tel:`, `intent:`) — через внешний браузер/системный intent.
- Кнопка «Назад» устройства: если у WebView есть история — `goBack()`, иначе стандартное поведение (выход).
- Поддержка **загрузки файлов** (upload): обработать `onShowFileChooser` с выбором файла/фото.
- Поддержка **скачивания файлов** (download): через `DownloadListener` + `DownloadManager` с уведомлением.
- Поддержка полноэкранного видео (`onShowCustomView` / `onHideCustomView`).
- Разрешить доступ к микрофону/камере из WebView (`onPermissionRequest`), с запросом runtime-разрешений у пользователя.
- Pull-to-refresh через `SwipeRefreshLayout`.
- Обработка отсутствия интернета: показать понятный экран-заглушку с кнопкой «Повторить» вместо стандартной ошибки WebView.
- Сохранять состояние WebView при повороте экрана (не перезагружать страницу).

### 3. Манифест и разрешения

- Разрешения: `INTERNET`, `ACCESS_NETWORK_STATE`, `CAMERA`, `RECORD_AUDIO`, `POST_NOTIFICATIONS` (для загрузок на Android 13+).
- `android:usesCleartextTraffic="false"` — только HTTPS.
- Поддержка тёмной темы (следовать системной теме; `WebSettingsCompat.setForceDark` / `setAlgorithmicDarkeningAllowed` там, где применимо).

### 4. Оформление

- Splash screen через официальную библиотеку `androidx.core:core-splashscreen`.
- Иконка приложения: адаптивная (foreground + background), простой минималистичный логотип с буквой «A» или текстом «Arena» — сгенерируй векторный drawable, без бинарных PNG, если возможно.
- Тема без ActionBar (полноэкранный контент).

### 5. Качество кода

- Архитектура простая: `MainActivity` + вспомогательные классы (`ArenaWebViewClient`, `ArenaWebChromeClient`), без лишних библиотек.
- Все строки — в `strings.xml` (локализации: `ru`, `en` по умолчанию).
- Добавь `README.md` с инструкцией: как собрать debug/release apk, как подписать своим keystore, минимальные требования (JDK 17, Android SDK 34).
- Добавь `.gitignore` для Android-проекта.

### 6. Порядок работы

1. Сгенерируй полную структуру проекта (settings.gradle.kts, build.gradle.kts, gradle wrapper, манифест, код, ресурсы).
2. Собери проект и исправь все ошибки компиляции до успешной сборки.
3. Прогони `./gradlew lint` и устрани критичные предупреждения.
4. Выполни `./gradlew assembleRelease` и укажи точный путь к готовому .apk.
5. В конце выведи краткий отчёт: что реализовано, как установить apk на телефон (adb install / передача файла), какие ограничения есть (например, что это WebView-обёртка и push-уведомления не реализованы).

Не задавай уточняющих вопросов — при неоднозначности выбирай разумное значение по умолчанию и фиксируй его в README.

---

## Дополнительно (по желанию)

Если агенту доступен интернет и нужна альтернатива WebView, можно добавить в промт один из пунктов:

- **TWA (Trusted Web Activity):** «Вместо WebView используй Trusted Web Activity на базе `androidx.browser`, если arena.ai отдаёт корректный Digital Asset Links; иначе откатись на WebView».
- **Push-уведомления:** «Добавь Firebase Cloud Messaging (заглушку конфигурации `google-services.json` опиши в README)».
- **AAB для Google Play:** «Дополнительно собери `./gradlew bundleRelease` и приложи инструкцию по публикации в Google Play».

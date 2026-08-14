# Arena AI — Android WebView-приложение

Нативное Android-приложение (Kotlin), которое оборачивает сайт
**https://arena.ai** в WebView.

| Параметр        | Значение          |
|-----------------|-------------------|
| applicationId   | `ai.arena.webapp` |
| minSdk          | 24 (Android 7.0)  |
| targetSdk       | 35 (Android 15)   |
| Язык            | Kotlin            |
| Сборка          | Gradle (Kotlin DSL) |

Готовый установочный файл: **`dist/ArenaAI-release.apk`**
(дубль: `app/build/outputs/apk/release/app-release.apk` после сборки).

## Возможности

- Полноэкранный WebView (edge-to-edge) с загрузкой `https://arena.ai`
- JavaScript, DOM storage, БД, cookies (включая сторонние), кэширование
- Стандартный мобильный User-Agent (сайт отдаёт мобильную версию)
- Ссылки на `arena.ai` / `*.arena.ai` — внутри приложения; остальные
  (`mailto:`, `tel:`, `intent:`, чужие домены) — во внешних приложениях
- Кнопка «Назад» — навигация по истории WebView
- Загрузка файлов на сайт (`onShowFileChooser`)
- Скачивание файлов через `DownloadManager` с уведомлением
- Полноэкранное видео (`onShowCustomView`)
- Доступ к камере/микрофону из WebView с runtime-запросом разрешений
- Pull-to-refresh (`SwipeRefreshLayout`)
- Экран «Нет подключения» с кнопкой «Повторить»
- Состояние WebView сохраняется при повороте экрана
- Splash screen (`androidx.core:core-splashscreen`), адаптивная иконка
- Тёмная тема (алгоритмическое затемнение на Android 13+, Force Dark на 10–12)
- Только HTTPS (`usesCleartextTraffic="false"`)
- Локализации: en (по умолчанию), ru

## Требования для сборки

- JDK 17
- Android SDK: platform 35, build-tools (любые свежие)
- Интернет для загрузки зависимостей (Google Maven / Maven Central)

## Сборка

```bash
# debug
./gradlew assembleDebug

# release (подписывается keystore из app/keystore/release.keystore)
./gradlew assembleRelease
# результат: app/build/outputs/apk/release/app-release.apk
```

### Оффлайн-сборка (без доступа к Maven)

В окружениях без доступа к `dl.google.com` / `maven.google.com`
(например, в изолированных песочницах) используется скрипт
`scripts/offline_build.sh`, который повторяет пайплайн Gradle вручную:
`aapt2 compile/link → генерация R → kotlinc → d8 → zipalign → apksigner`.
Пути к инструментам передаются переменными окружения (см. шапку скрипта).
Именно этим способом собран приложенный `dist/ArenaAI-release.apk`.

## Подпись

Release подписывается сгенерированным keystore
`app/keystore/release.keystore`:

- alias: `arena`
- store/key password: `arenaai123`
- сертификат: `CN=Arena AI, OU=Mobile, O=Arena, C=SG`, RSA-2048, 10000 дней

> **Для публикации** замените keystore своим и обновите
> `signingConfigs` в `app/build.gradle.kts` (пароли лучше вынести в
> `~/.gradle/gradle.properties` или переменные окружения). Ключ в репозитории
> оставлен намеренно, чтобы сборка воспроизводилась «из коробки».

Создание собственного keystore:

```bash
keytool -genkeypair -keystore my.keystore -alias mykey \
  -keyalg RSA -keysize 2048 -validity 10000
```

## Установка на устройство

```bash
# через adb
adb install dist/ArenaAI-release.apk
```

Или передайте `dist/ArenaAI-release.apk` на телефон (мессенджер, USB,
облако), откройте файл и разрешите установку из неизвестных источников.
Приложение ставится на Android 7.0+.

## Структура проекта

```
app/src/main/java/ai/arena/webapp/
  MainActivity.kt          — активность, WebView, загрузки, разрешения, офлайн-экран
  ArenaWebViewClient.kt    — маршрутизация ссылок, ошибки главного фрейма
  ArenaWebChromeClient.kt  — upload, полноэкранное видео, permission-запросы
app/src/main/res/          — layout, строки (en/ru), темы, иконки
scripts/offline_build.sh   — оффлайн-сборка APK без Gradle
dist/ArenaAI-release.apk   — готовый подписанный APK
```

## Принятые решения по умолчанию

- `versionCode 1`, `versionName 1.0.0`
- Без AppCompat/Material: приложению с одним WebView достаточно
  platform-API + core/splashscreen/swiperefreshlayout — APK меньше
- Debug-сборка получает суффикс `.debug`, чтобы ставиться рядом с release
- `minifyEnabled false` (в APK почти нет «лишнего» кода)

## Ограничения

- Это WebView-обёртка: контент полностью зависит от сайта arena.ai
- Push-уведомления (FCM) не реализованы
- Уведомление о скачивании показывает системный `DownloadManager`;
  на Android 13+ система сама запросит разрешение на уведомления
- Оффлайн-режим — только экран-заглушка, контент не кэшируется для
  просмотра без сети

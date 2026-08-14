#!/usr/bin/env python3
"""Мини-аналог manifest merger: подставляет package и uses-sdk.
Приложение не использует сторонних библиотек, поэтому мержить нечего."""
import sys

src, dst, app_id, min_sdk, target_sdk = sys.argv[1:6]

manifest = open(src, encoding="utf-8").read()

manifest = manifest.replace(
    '<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
    f'<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
    f'    package="{app_id}">\n\n'
    f'    <uses-sdk android:minSdkVersion="{min_sdk}" '
    f'android:targetSdkVersion="{target_sdk}" />',
)

open(dst, "w", encoding="utf-8").write(manifest)
print(f"  merged manifest -> {dst}")

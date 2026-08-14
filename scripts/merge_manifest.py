#!/usr/bin/env python3
"""Мини-аналог manifest merger: подставляет uses-sdk, applicationId и
атрибуты из библиотек AndroidX (appComponentFactory из androidx.core)."""
import re
import sys

src, dst, app_id, min_sdk, target_sdk = sys.argv[1:6]

manifest = open(src, encoding="utf-8").read()

# package + uses-sdk
manifest = manifest.replace(
    '<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
    f'<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
    f'    package="{app_id}">\n\n'
    f'    <uses-sdk android:minSdkVersion="{min_sdk}" '
    f'android:targetSdkVersion="{target_sdk}" />\n'
    f'    <permission\n'
    f'        android:name="{app_id}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"\n'
    f'        android:protectionLevel="signature" />\n'
    f'    <uses-permission '
    f'android:name="{app_id}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" />',
)

# androidx.core: appComponentFactory
manifest = re.sub(
    r"<application\b",
    '<application\n        '
    'android:appComponentFactory="androidx.core.app.CoreComponentFactory"',
    manifest,
    count=1,
)

open(dst, "w", encoding="utf-8").write(manifest)
print(f"  merged manifest -> {dst}")

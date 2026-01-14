#!/bin/bash
# Build and deploy Meeting Bingo to connected Android device

set -e

echo "Building debug APK..."
./gradlew assembleDebug

echo ""
echo "Installing on device..."
adb install -r app/build/outputs/apk/debug/app-debug.apk

echo ""
echo "Launching app..."
adb shell am start -n com.example.meetingbingo/.MainActivity

echo ""
echo "Done! App should be running on device."

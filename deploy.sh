#!/bin/bash
# Build and deploy Meeting Bingo to connected Android device

set -e

echo "Building debug APK..."
./gradlew assembleDebug

echo ""
echo "Installing on device..."
adb install -r app/build/outputs/apk/debug/app-debug.apk

echo ""
echo "Granting overlay permission..."
adb shell appops set com.example.meetingbingo SYSTEM_ALERT_WINDOW allow

echo ""
echo "Enabling accessibility service..."
adb shell settings put secure enabled_accessibility_services com.example.meetingbingo/com.example.meetingbingo.service.BingoAccessibilityService
adb shell settings put secure accessibility_enabled 1

echo ""
echo "Launching app..."
adb shell am start -n com.example.meetingbingo/.MainActivity

echo ""
echo "Done! App should be running on device with overlay and accessibility permissions."

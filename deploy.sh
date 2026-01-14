#!/bin/bash
# Build and deploy Meeting Bingo to all connected Android devices

set -e

echo "Building debug APK..."
./gradlew assembleDebug

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

# Get list of connected devices
DEVICES=$(adb devices | grep -v "List of devices" | grep "device$" | cut -f1)

if [ -z "$DEVICES" ]; then
    echo "No devices connected!"
    exit 1
fi

# Deploy to each device
for DEVICE in $DEVICES; do
    echo ""
    echo "===== Deploying to device: $DEVICE ====="

    echo "Installing on device..."
    adb -s "$DEVICE" install -r "$APK_PATH"

    echo "Granting overlay permission..."
    adb -s "$DEVICE" shell appops set com.example.meetingbingo SYSTEM_ALERT_WINDOW allow

    echo "Enabling accessibility service..."
    adb -s "$DEVICE" shell settings put secure enabled_accessibility_services com.example.meetingbingo/com.example.meetingbingo.service.BingoAccessibilityService
    adb -s "$DEVICE" shell settings put secure accessibility_enabled 1

    echo "Launching app..."
    adb -s "$DEVICE" shell am start -n com.example.meetingbingo/.MainActivity

    echo "Done with device: $DEVICE"
done

echo ""
echo "===== Deployment complete to $(echo "$DEVICES" | wc -w) device(s) ====="

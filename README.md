# HomeRoom

HomeRoom is a private, self-hosted shared chat space: rooms per project, saved place in every chat, pictures and files, and shared rules that everyone in the space can review. The server runs on infrastructure you control. The Android app is a thin native shell over the same interface the server serves, so interface improvements reach the app without reinstalling it.

## How it works

- You run a HomeRoom server, or someone who runs one invites you.
- The server hands out one-time setup links. Each link connects exactly one device.
- The app keeps only the server address and your session on the device. No accounts, no tracking. The only third-party contact is GitHub's public API, used to check for app updates; nothing about you or your server is sent to it.

## Install

1. Download the newest APK from the Releases page.
2. Open it on your phone and allow the install.
3. Paste your one-time setup link and tap Connect.

The app checks for updates by itself and offers them when one is ready.

## Build from source

Requires a JDK and the Android SDK (platform android-34, build-tools 34.0.0). Set ANDROID_SDK and JAVA_HOME if they live in unusual places, then:

    ./build.sh

The signing keystore is read from HOMEROOM_KEYSTORE and HOMEROOM_KEYSTORE_PASS_FILE. The signed APK lands in out/.

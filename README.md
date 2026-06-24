# MATH Client

An experimental companion launcher for **Minecraft Bedrock on Android**.

The first goal is deliberately small: build a real APK that opens, shows the MATH Client interface, and launches the installed Minecraft app.

## Current milestone: 0.1.0-alpha

- Native Android application
- Dark mathematical interface
- Minecraft launch button
- Automatic APK build with GitHub Actions
- Placeholder sections for profiles and resource packs

## Planned direction

1. Resource-pack profiles
2. Pack import and management
3. Client settings
4. News and update feed
5. Optional online services

## Scope

MATH Client is a companion application. It does not inject code into Minecraft, modify game memory, or bypass protections.

## Building

Every push to `main` starts the **Build Android APK** workflow.

After a successful run:

1. Open the repository's **Actions** tab.
2. Open the latest successful run.
3. Download the `math-client-debug` artifact.
4. Extract the ZIP and install `app-debug.apk` on Android.

The project currently uses Android Gradle Plugin 9.2.1, Gradle 9.4.1, JDK 17, and Android API 36.

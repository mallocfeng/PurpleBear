# Contributing to PurpleBear

Thanks for helping improve PurpleBear. This project is an Android VPN proxy application built around Android `VpnService` and the Xray runtime.

## Before You Start

- Check existing issues and pull requests to avoid duplicate work.
- Keep changes focused. Separate unrelated fixes into separate pull requests.
- Do not include private subscription links, node credentials, tokens, keystores, or device logs that expose personal data.
- For behavior changes, describe how users will notice the change.

## Development Setup

Requirements:

- Android Studio or command line Android SDK
- JDK 17
- Android SDK matching the project configuration

Build the release APK:

```bash
./gradlew :app:assembleRelease
```

Build a debug APK:

```bash
./gradlew :app:assembleDebug
```

Run Kotlin compilation checks:

```bash
./gradlew :app:compileDebugKotlin
```

## Pull Request Guidelines

Please include:

- A short summary of the change.
- Screenshots or screen recordings for UI changes when possible.
- Testing notes, including device/emulator version if relevant.
- Any limitations, follow-up work, or compatibility risks.

For Android UI changes:

- Check both light and dark themes.
- Check Chinese and English language modes.
- Avoid text overflow in narrow controls.

For VPN, routing, and Xray changes:

- Keep LAN/private address behavior explicit.
- Avoid changing proxy/routing behavior unless the pull request is specifically about that behavior.
- Include clear notes about reconnect requirements and compatibility impact.

## Commit Style

Use concise conventional commit style when possible:

- `feat: add feature`
- `fix: correct behavior`
- `docs: update documentation`
- `chore: update tooling`

## Reporting Issues

Use the issue templates when possible. Include:

- App version
- Android version and device model
- Steps to reproduce
- Expected behavior
- Actual behavior
- Relevant logs with private data removed

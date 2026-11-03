# Safety & privacy

- No internet/storage permissions; no analytics, account access, or data upload.
- The scan list and BLE input MAC addresses are used transiently in memory only.
- The Xiaomi 8 Pro proprietary commands are not sent and pairing is not altered intentionally.
- Do not install if APK is obtained from an untrusted third party. Use Android Studio or provided GitHub Actions to build from source.
- Gradle bootstrap source is included under `build-tools`; it verifies the Gradle distribution SHA-256 before unpacking.
- This is not a medical device. If the input times out, last known HR is not continually re-notified.
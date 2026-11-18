# PhotoAlbumScan Beta

An Android app for turning photographs of album pages into individual cropped pictures. Capture or import a page, review detected outlines, adjust corners and rotation, then export the results. Processing runs on the device. Android 10 (API 29) or newer is required.

This is an early public beta. Automatic detection can miss pictures, merge neighboring pictures, include background, or cut away picture content. Check detected counts and review warnings, sample the outputs, and inspect important results against their originals before relying on an export. Keep your originals and an independent backup.

## Install

**[Download PhotoAlbumScan 0.4.0-beta1 APK](https://github.com/domleblanc94/photoalbumscan-beta/releases/download/v0.4.0-beta1/PhotoAlbumScan-0.4.0-beta1-arm64.apk)** · [Release page and checksums](https://github.com/domleblanc94/photoalbumscan-beta/releases/tag/v0.4.0-beta1)

This APK requires **Android 10 (API 29) or newer and an ARM64 device**.

1. Download the APK using the link above, then open it from your downloads.
2. If Android asks, allow your browser or file manager to install apps from this source, then choose **Install**. You can turn that permission off again afterward.
3. Open **PhotoAlbumScan Beta**, try a few scans, and check detected counts, warnings and sample results.

Install a signed APK only from a release you trust. The release APK is signed; GitHub Actions build artifacts are unsigned and cannot be installed directly. Contributors can build a debug APK locally.

[Report a bug or suggest an improvement](https://github.com/domleblanc94/photoalbumscan-beta/issues). Start with a written description or a synthetic reproduction. **Do not attach personal photos, review ZIPs, albums or full logs to public issues.**

The public beta uses application ID `io.github.domleblanc94.photoalbumscan`, version `0.4.0-beta1` (code 1), and label **PhotoAlbumScan Beta**. It installs separately from the earlier private trial. It does not automatically migrate private albums or replace the private app. Do not uninstall your existing app to try this beta. Future public updates must use the same application ID and release signing certificate to preserve the normal upgrade path.

## Build

Use JDK 17 and Android SDK Platform 35. Set `ANDROID_HOME` to your SDK or create a local, untracked `local.properties` with `sdk.dir`. The Gradle wrapper downloads and verifies Gradle 8.9; dependencies come from Google Maven and Maven Central. Android Studio can also open this directory.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintRelease
./gradlew -PphotoscanAbi=arm64-v8a :app:assembleRelease :app:bundleRelease
```

On Windows, use `gradlew.bat`. Release outputs in `app/build/outputs` are unsigned. No release key, password or signing configuration is included. To limit APK size for a compatible ARM64 phone, add `-PphotoscanAbi=arm64-v8a`. Omitting this option uses the ABIs supplied by the dependencies. Debug builds use Android's local debug signing identity and are for development.

The committed assets contain the selected L1/R3/c99 models. Every normal build verifies their exact SHA-256 values before building; there are no downloads from private model folders and no legacy-model default. See [MODEL_CARD.md](MODEL_CARD.md) for the frozen detector configuration and limits.

Before preparing a source archive, export a clean source-only directory without `.git` or build outputs and run `python tools/privacy_preflight.py .` from that directory (Python 3). This checks the source allowlist and model hashes; it does not establish training-data rights or absence of model memorization. Add `--source-zip ../source.zip` to verify an archive matches this tree exactly.

Run synthetic device tests on an emulator or disposable test device with `./gradlew :app:connectedDebugAndroidTest`. The supplied tests generate their own images and temporary data. They do not require private photos. Unit tests and lint do not establish real-phone detection quality or speed.

## Beta behavior

The crop detector keeps the selected L1/R3/c99 cascade, R8 geometry fallback, shared ROI context and 0.50 padding. Experimental crop refinement is available through Developer tools on API 33+ and defaults **off**. Earlier small, reused evaluations found both improved matches and harmful crop changes, with slower processing when this option was enabled. They do not establish general accuracy or physical-phone performance. Include unflagged crops when sampling outputs.

Face-based automatic rotation from the private trial has been removed along with Google ML Kit. Manual 90-degree rotation, camera orientation handling and EXIF-aware image decoding remain available. No replacement face model is included.

## Privacy and feedback

See [PRIVACY.md](PRIVACY.md) before sharing an export. Review ZIPs include full originals and may contain location metadata, personal photos, album names and notes. **Never attach private review ZIPs, albums, photos or full logs to public GitHub issues.** Start with a written description or a synthetic reproduction.

Bug reports and contributions are welcome; read [CONTRIBUTING.md](CONTRIBUTING.md). Report security issues using [SECURITY.md](SECURITY.md).

## License

Project source and the selected owned model weights are offered under Apache-2.0, subject to the third-party notices and licenses. Dependencies retain their own licenses. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). No training photos, labels, private evaluation fixtures, model optimizer state or repository history are included. This is an inference application source release; it does not reproduce the private training dataset or training process.
# Contributing

Open an issue describing the problem or proposed change before undertaking a large redesign. Include the beta version, Android version, a short sequence of steps, expected behavior and actual behavior. Use synthetic drawings or invented data for reproduction.

Do not attach private photos, review ZIPs, albums, full logs, credentials or signing files. Do not add training photos or copied third-party model weights. Contributions should be yours to share under the project's Apache-2.0 license; preserve applicable third-party notices.

Use JDK 17 and Android SDK 35. Before submitting changes, run:

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

For Android UI, image-decoding or storage changes, run the relevant synthetic instrumentation tests on a disposable emulator. Describe what you checked and any gaps in the pull request. Keep changes focused and explain user-visible behavior. Do not claim detector quality or speed improvements from unit tests alone.

Model changes need a clear model card, provenance and permission to redistribute the weights. Do not upload a private dataset to demonstrate an improvement. The checked-in model hashes intentionally pin this beta's detector.
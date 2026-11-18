# Privacy

PhotoAlbumScan processes camera captures and imported images on the device. This beta requests camera access; camera hardware is optional because images can be imported. The merged application manifest removes `INTERNET` and `ACCESS_NETWORK_STATE` permissions, including permissions contributed by dependencies. No analytics, account, advertising or cloud-upload integration is configured in this source release. ONNX Runtime contains upstream telemetry-related code; this app removes its automatic initialization provider and disables environment telemetry before creating model sessions. The dependency code and its licence notices remain included, and the app has no network permission.

Albums, original files, crop settings and notes are stored in app-private storage. Android backup is disabled. Exporting or sharing a file intentionally sends it to the destination or app you choose; that destination has its own privacy behavior. Removing the app can delete its private data. Keep independent backups of material you need.

## Exports can contain sensitive data

A review or collection ZIP is created only after you choose to export it. It can include full-resolution original files copied byte for byte, personal photos, album names and dates, notes, capture information, crop geometry and device/build diagnostics. Originals may retain EXIF location, camera/device identifiers or other metadata. Ordinary photo exports also contain image content you should review before sharing.

**Never post private review ZIPs, original scans, albums or full logs to public GitHub issues or pull requests.** A filename change does not anonymize an image or its metadata. Use a synthetic image and a written description whenever possible. If a maintainer needs more evidence, first agree on a suitable private route and inspect every file yourself; no private upload destination is configured by this project.

Developer tools can create a local training collection and diagnostic exports. Creating these does not automatically upload data or grant permission to use it for training. This public repository contains no user photos, private training data or private evaluation fixtures.

Security-sensitive reports should follow [SECURITY.md](SECURITY.md). Public bug templates deliberately request no photo attachments.
# 0.4.0-beta1

First public beta source snapshot with a separate application ID and label. The selected L1/R3/c99 models, R8 geometry fallback and shared 0.50 ROI context are pinned and verified at build time. Experimental crop refinement is available on API 33+ and defaults off.

The public beta removes ML Kit and face-based automatic rotation. Manual rotation, camera orientation and EXIF-aware decoding remain. Review export dialogs now warn about original location/device metadata and public GitHub disclosure.

This is a beta for careful review, with known detection errors and optional-refinement quality/speed tradeoffs. No new physical-phone performance validation is claimed. No private photos, training corpus, original repository history, signing keys or private evaluation fixtures are included.

The new public app installs alongside the private trial and does not automatically migrate albums. Future signed public APKs require the permanent public signing identity; unsigned build outputs are for developer verification and cannot be installed directly.
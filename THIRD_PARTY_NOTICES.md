# Third-party notices

The project's Apache-2.0 licence covers its own source code. Third-party components retain their respective licences; it does not relicense their code or assets.

The Android application uses AndroidX and CameraX, Kotlin and coroutines, JetBrains annotations, JSpecify, Guava's ListenableFuture and AutoValue annotations under Apache-2.0. CameraX also includes libyuv under a BSD licence. Exact resolved runtime coordinates are listed in `runtime-dependencies.csv`.

The bundled LLVM C++ runtime uses Apache-2.0 with LLVM exceptions and retained legacy notices.

OpenCV 4.13.0 is distributed under Apache-2.0 with additional native third-party notices. Its copyright file and retained third-party texts accompany this distribution. The application's float contour hull adapts OpenCV's `modules/imgproc/src/convhull.cpp`; preserve the full Intel notice in the Java source and application notice asset.

ONNX Runtime Android 1.31.1 is distributed under MIT with additional third-party notices. The MIT and broad upstream notice texts supplied here were obtained from Microsoft's same-version ONNX Runtime distribution. They are retained conservatively and do not claim that every optional component is compiled into the Android binary. Its Java archive also supplies an Apache-2.0 notice for 1DS.

The architecture reference uses torchvision under BSD-3-Clause and timm under Apache-2.0. These architecture libraries were used with random initialization for the project's crop-model training; external pretrained crop weights are not included.

Public training textures included the Library of Congress FSA/OWI Color Photographs collection and Flickr images recorded as CC0. Credit: Library of Congress, Prints & Photographs Division, FSA-OWI Collection. These training photographs are not distributed. No licence to underlying photographs or guarantee concerning learned-weight copyright status is implied by this notice.

Full licence and notice texts are supplied alongside this file and are accessible in the application's third-party notices screen. Preserve them with source and binary redistribution. The original proprietary ML Kit orientation component is omitted from this beta.

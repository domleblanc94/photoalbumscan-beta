# Model card: public beta detector

## Purpose and contents

The bundled models propose and refine quadrilateral crops for photographed album pages. They are for assisted photo extraction, with human review. They do not recognize identities or perform face-based rotation. This release contains inference weights, not training images, labels, optimizer state or a reproducible training pipeline.

| Asset | Role | SHA-256 |
| --- | --- | --- |
| `print-detector.onnx` | L1 page locator | `600f58506ffa4bdd32ec61bc697a36b4f68b7357e7a60170ebc87f222b67c1ea` |
| `picture-own-roi.onnx` | R3 primary picture refiner | `7af326cd0368735fdd09a71cbb295c9f896b4a4b4f64a00cea887b2dd7f23fa9` |
| `picture-own-fallback-roi.onnx` | c99 fallback picture refiner | `c99d1dec299847f91767c05321af2c41c04f68a1928edfb1b2a05afc5fff1016` |

These selected project-owned exports are distributed under the root Apache-2.0 license. They must not be confused with earlier pretrained research assets, which are excluded from this release. Model metadata and this document intentionally contain no private image names, source identifiers or workstation paths.

## Frozen runtime policy

The locator is supplementary. The primary and helper use `shared_roi_v1` with 0.50 padding. Primary recovery uses `constrained_line`, `source_edge_16` retry and `legacy` first hull. Fallback policy is `mixed_trigger025_accept010`, verifier is `empty005_iou080_v2`, local arbitration is `selected_underhalf_two75_split_v2`, and R8 geometry fallback is `l1-failed-helper-envelope-v1`. Gradle checks the exact asset hashes before each build.

Optional experimental crop refinement is available only on Android API 33+ and starts disabled. Enabling it affects future detections and re-runs; it does not undo or replace existing saved crop edits.

## Provenance and limitations

The project developed these models using privately held personal and album photographs. Publishing the weights preserves their training influence even though originals are excluded. Metadata scans do not prove absence of memorization. The corpus and its annotations are not part of this publication. Training also used synthetic composites from a historical texture pool recorded as 730 Library of Congress public-domain sources and 301 Flickr sources recorded as CC0. R3 additionally used 34 public-domain Library of Congress negative examples. The c99 helper also used 14 archival album pages from the Metropolitan Museum of Art and Rijksmuseum, historically recorded per item as public domain, Open Access or CC0. Those archival inputs and their private training manifests are excluded. The audit matched the recorded R3 source/derived hashes; the older Flickr license checks retain source URLs and evidence hashes but not the original HTML snapshots. These are bounded historical provenance records, not a fresh guarantee of every source right. No source photographs or item-level private manifests are published. Architecture definitions are available in `models/architecture.py`; default construction does not load pretrained weights. This source snapshot supplies no independent public benchmark or independently reproducible training evidence. Dataset counts and private cohort details are deliberately omitted; no universal training-data rights or absence-of-memorization claim follows from distributing weights.

Known failure modes include missed pictures, false background crops, merges, clipped picture content, perspective and glare errors, and misleading review warnings. Optional refinement improved some matches in small reused evaluations but also harmed some crops and ran slower. Those evaluations do not justify broad accuracy, fairness, comparative-product or current physical-phone performance claims. Review all outputs, including crops without warnings, and preserve originals.

The public beta removes the private trial's proprietary face-rotation component. The three detector models and their frozen geometry policies are unchanged. Manual photo rotation remains available.
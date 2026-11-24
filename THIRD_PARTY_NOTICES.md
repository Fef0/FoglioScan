# Third-party notices

## DocAligner (document corner detection model)

- Source: https://github.com/DocsaidLab/DocAligner — code licensed under Apache-2.0.
- Model: `lcnet100_h_e_bifpn_256_fp32.onnx` from release 1.1.1, converted to TFLite
  (`app/src/main/assets/models/docaligner_lcnet100.tflite`).
- Weights: the maintainer confirmed there are no restrictions on use, including commercial use,
  redistribution, conversion and quantization:
  https://github.com/DocsaidLab/DocAligner/issues/11
- Modification: BiFPN fusion `Einsum` ops were rewritten as equivalent `Slice/Mul/Sum` ops
  (`tools/models/rewrite_einsum.py`) so the model converts correctly to TFLite.

## SmartDoc 2015 Challenge 1 (test data only, not shipped)

Real-photo benchmark frames, CC BY 4.0. J.-C. Burie et al., "ICDAR2015 Competition on Smartphone
Document Capture and OCR (SmartDoc)", ICDAR 2015.
https://github.com/jchazalon/smartdoc15-ch1-dataset

## ML Kit Text Recognition v2

`com.google.mlkit:text-recognition` (on-device OCR, Latin model bundled in the APK). Use is subject
to the ML Kit Terms of Service: https://developers.google.com/ml-kit/terms

## LiteRT

Google LiteRT (`com.google.ai.edge.litert`), Apache-2.0.

## AndroidX / Jetpack Compose / CameraX / Kotlin

Apache-2.0.

## Material Symbols

Icons from https://github.com/google/material-design-icons, Apache-2.0.

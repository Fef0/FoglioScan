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

## UVDoc (curved-page dewarping model)

- Source: https://github.com/tanguymagne/UVDoc (commit 4c9b82b), code and weights
  (`model/best_model.pkl`) under the MIT License below. F. Verhoeven, T. Magne,
  O. Sorkine-Hornung, "UVDoc: Neural Grid-based Document Unwarping", SIGGRAPH Asia 2023,
  https://doi.org/10.1145/3610548.3618174
- Modifications: exported to ONNX with only the 2D grid branch, reflect padding written as index
  lookups (`tools/models/export_uvdoc.py`), converted to TFLite with float16 weights
  (`app/src/main/assets/models/uvdoc_fp16.tflite`).

```
MIT License

Copyright (c) 2023 Tanguy MAGNE

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

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

# TODO

## 1. On-device OCR

Done in 0.4.0: ML Kit Text Recognition v2 (bundled Latin model, offline) runs in the background on
each enhanced page; saved PDFs get an invisible, rotated, width-fitted text layer (Helvetica /
WinAnsi, so copy & paste works in every viewer); review has "Recognized text" with copy / share.

- [ ] Evaluate PaddleOCR PP-OCRv5 mobile on LiteRT against ML Kit on dense pages.
- [ ] Non-Latin scripts (ML Kit Chinese / Devanagari / Japanese / Korean models, downloaded on demand)
      — needs an embedded font subset + `/ToUnicode` CMap instead of WinAnsi Helvetica.
- [ ] Store recognized text alongside the PDF (XMP metadata or a sidecar) for in-app search.
- [ ] In-app search across saved documents.
- [ ] Use OCR line orientation to auto-rotate pages scanned upside down / sideways.

## 2. Best-in-class ML upgrades

The pipeline is built behind small interfaces (`DocumentLocator`, `PageProcessor`) so each stage
can be swapped for a learned model without touching the UI.

- [ ] **Curved-page dewarping — UVDoc** (MIT, SIGGRAPH Asia 2023): predicts a 45×31 sampling grid
      from a 712×488 crop; apply it with bilinear sampling in Kotlin. Use it automatically when the
      grid deviates from a pure homography (page is curved: books, folded sheets).
      *Status*: not converted yet — exporting it requires loading the third-party PyTorch
      checkpoint (`model/best_model.pkl`, use `torch.load(..., weights_only=True)`) and then the
      same ONNX → TFLite path as DocAligner (see README).
- [ ] **Learned restoration — DocRes** (MIT, CVPR 2024): deshadowing / appearance enhancement /
      deblurring in one model. Heavy (transformer); evaluate running at reduced resolution and
      applying its output as a shading map to the full-resolution page. Offer as "Max quality".
- [ ] **Higher-accuracy detector** for the capture step. The network's error is relative
      (≈0.3% of the page diagonal → up to ~15 px on a 4000 px photo). Candidates: DocAligner FastViT
      variants (same conversion recipe; FastViT-T8 returned no corners on the synthetic test image),
      or a 512-px-input model. Must beat the current one on `SmartDocBenchmark`, not just synthetics.
      (Classic edge refinement and a test-time-zoom pass were tried and were worse on real photos.)
- [ ] Extend the real-photo benchmark beyond SmartDoc (whose frames are 1080p and possibly in
      DocAligner's training set): e.g. the Mendeley "Document Corner Localization" set (1,111 phone
      photos) and our own photos.
- [ ] **Deliver models with Play for On-device AI (AI packs)** instead of APK assets, so the base
      APK stays small and models can be updated independently. LiteRT already ships
      `AiPackModelProvider`.
- [ ] **NPU acceleration**: LiteRT `Accelerator.NPU` with Qualcomm/MediaTek providers; benchmark vs GPU.
- [ ] Quantize models (fp16 weights / int8) once a conversion path preserves accuracy; the
      `onnx2tf` fp16 output of this version uses fp16 activations that don't run on CPU.

## 3. Capture UX

- [x] Auto-capture when the outline is stable for ~1 s (AUTO toggle in the camera + setting).
- [ ] Tap-to-focus / exposure lock on the viewfinder.
- [ ] Glare detection hint ("Tilt to avoid reflections").
- [x] Batch mode (keep shooting without visiting review between pages).
- [ ] Book mode: split a two-page spread into two pages.

## 4. Documents

- [ ] Reorder pages by drag & drop in review (`ScanSessionViewModel.move` already exists).
- [ ] Re-open a saved PDF to append pages.
- [ ] In-app PDF viewer (`androidx.pdf`) instead of relying on an external app.
- [ ] Page size setting (Auto / A4 / Letter / Original).
- [x] 1-bit Flate encoding for B&W pages (compression levels).
- [ ] CCITT G4 / JBIG2 for B&W pages (another 2–5× smaller than Flate).
- [ ] PDF/A output for archival.

## 5. Engineering

- [x] JVM unit tests for the PDF writer / text layer, geometry, enhancer, auto-capture.
- [x] Baseline Profiles for faster startup.
- [ ] CI (build + instrumented tests on a Gradle Managed Device).
- [ ] Real signing config for release builds (test APKs use the debug key).

# FoglioScan

An Android document scanner: point the camera at a sheet of paper, see it highlighted live in the
colour you choose, press the shutter, and get a page that looks like it came from a professional
flatbed scanner. Save it as a PDF or keep adding pages.

## How it works

```
CameraX analysis frame ──► DocAligner (LiteRT, GPU) ──► smoothed outline ──► live highlight
                                                                       (user-chosen colour)
Shutter ──► full-res photo (FoglioScan camera, optionally with the phone's HDR extension,
            or the phone's own camera app — Settings)
        ──► DocAligner on the photo (median corner error 0.31% on real photos)
        ──► curved page? UVDoc (LiteRT) predicts a 45×31 grid over the page; it is used when
            the grid bends and the photo's edges follow its curved outline rather than the
            straight one between the corners (books, curled or folded sheets)
        ──► flat page: true aspect from projective geometry (Zhang & He) + homography warp
            curved page: same aspect method on the grid's best-fit plane + arc length,
                         smooth (Catmull-Rom) grid warp
            both snapped to A4 / Letter
        ──► enhancement: illumination flattening (shadow-aware) · white balance · levels · unsharp mask
        ──► JPEG on disk (any number of pages: nothing is kept in memory)
        ──► OCR in the background (ML Kit Text Recognition v2, on-device);
            sideways / upside-down pages are turned upright from the text direction
Book mode ──► an open book is split at the spine (darkest gutter valley in the straightened
            spread) into two pages, each flattened
Save    ──► PDF/A-2u streamed page by page, at the chosen quality and page size (Auto / A4 /
            Letter / Original):
            Maximum ≈300 dpi (pass-through) · Balanced ≈200 dpi · Small ≈150 dpi · Smallest ≈100 dpi
            black & white pages → 1-bit CCITT Group 4 (crisp text, a few dozen KB per page)
            invisible text layer → searchable PDF, text selectable and copyable
Search  ──► every saved document's text, indexed from the PDFs' own text layer
```

| Layer | Tech |
|---|---|
| UI | Kotlin 2.4, Jetpack Compose (BOM 2026.09), Material 3 + dynamic colour, Navigation 3, edge-to-edge, predictive back |
| Camera | CameraX 1.6 + `camera-compose` viewfinder |
| ML | LiteRT 2.3 `CompiledModel` API, GPU accelerator with CPU fallback |
| Detection | DocAligner LCNet-100 heatmap model; classic Canny/Hough fallback |
| Dewarping | UVDoc (SIGGRAPH Asia 2023) grid model, float16 weights |
| OCR | ML Kit Text Recognition v2, Latin model bundled (offline, no Play services needed) |
| Imaging | Pure Kotlin, multithreaded (no OpenCV / native image libs) |
| Storage | MediaStore → `Documents/FoglioScan` (no storage permission), DataStore for settings |
| PDF | Custom streaming PDF/A-2u writer: JPEG pass-through (DCTDecode), 1-bit CCITT G4 images (own codec), invisible OCR text layer, XMP metadata, sRGB output intent |

Min SDK 29 (Android 10), target/compile SDK 37. Languages: English, Italian.

## Project layout

```
app/src/main/java/app/foglioscan/
  camera/     ScannerCamera – preview, capture, live analysis + temporal smoothing
  vision/     NeuralDocumentDetector (LiteRT), DocumentDetector (classic fallback), DocumentLocator, Quad
  imaging/    PageProcessor (pipeline), PerspectiveWarp (homography + aspect recovery),
              PageDewarper (UVDoc) + GridWarp + EdgeEvidence (curved pages), BookSplit, PageEnhancer
  ocr/        TextRecognizer (ML Kit), PageText
  pdf/        PdfWriter (PDF/A, page sizes), PdfExporter (compression levels), PdfTextLayer (searchable text),
              CcittG4 (Group 4 codec), PdfPageExtractor (reopen pages, read text back)
  session/    ScanSessionViewModel – multipage document, survives process death
  data/       DocumentRepository (MediaStore), SettingsRepository (DataStore), SearchIndex + TextSearch
  ui/         home, camera (highlight colour, auto-capture, batch), review (+ recognized text), crop, settings
tools/        test-photo generator, model conversion helpers
```

## Build & test

Release builds are signed with the key named in `~/.gradle/gradle.properties`
(`foglioscan.keystore`, `foglioscan.keystore.password`, `foglioscan.key.alias`). The key never
lives in the repository; without it, release builds fall back to the debug key.

```bash
./gradlew :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest   # end-to-end pipeline test on a device/emulator
```

Tests:

| Suite | What it checks |
|---|---|
| JVM unit tests (`./gradlew :app:testDebugUnitTest`) | PDF structure (xref offsets, stream lengths, encodings), text layer placement / rotation / encoding, 1-bit packing, paper-size snapping, quad geometry, true-aspect recovery under perspective, warp, curved-page grid (bending, size, smooth warp, edge evidence), enhancer (shadow removal, ink, colour), auto-capture timing |
| `OcrTest` | OCR accuracy on a clean page and on pipeline output from perspective photos; text extracted back from the saved PDF at every quality level; sideways / upside-down photos turned upright |
| `ZoomGestureTest` | Real pinch / double-tap / pan / swipe input on the review screen |
| `ReopenDocumentTest` | Reopening a saved PDF: pages reused byte for byte, replace vs copy, other apps' PDFs rasterized and read |
| `ReorderGestureTest` | Long-press-and-drag page reordering, accessibility move actions |
| `LargeDocumentTest` | 40 full-resolution pages: no failures, flat memory, every compression level, size estimate, save |
| `PipelineTest` | Detection accuracy, A4 aspect recovery, filters, PDF |
| `DocumentFeaturesTest` | Book spread split at the spine (vs ground truth) into two flat, readable pages; G4 pages rendered by pdfium identical to our decoder; page sizes; saved documents found by their text |
| `DewarpTest` | Curved pages (synthetic, exact ground truth) flattened better than the corner warp, A4 proportions; flat photos left to the corner warp |
| `SmartDocBenchmark` | Real-photo detection benchmark |

`PipelineTest` runs the real pipeline on generated photos (perspective, colour cast, lighting
falloff, hand shadow, sensor noise) and checks corner accuracy, A4 aspect recovery and PDF output.

### Real-photo benchmark (SmartDoc 2015)

```bash
python tools/fetch_smartdoc.py testdata/smartdoc     # 279 real phone frames + ground truth (CC BY 4.0)
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.foglioscan.SmartDocBenchmark
```

Results on the emulator (x86_64 CPU), 279 frames, 5 backgrounds × 6 document types:

| Detector | Found | IoU ≥ 0.9 | Mean IoU | Median corner error | Time |
|---|---|---|---|---|---|
| **Shutter path (neural)** | 100% | **100%** | 0.989 | 0.31% of diagonal | 27 ms |
| Live path (neural, 640 px) | 100% | 100% | 0.989 | 0.31% | 27 ms |
| Classic Canny/Hough | 47% | 22% | 0.324 | 12.3% | 52 ms |

Edge-snapping refinement and a test-time-zoom second pass were evaluated and *removed*: both made
real-photo accuracy worse. Caveat: DocAligner's training data isn't documented, so overlap with
SmartDoc can't be excluded.

## Rebuilding the detection model

```bash
python -m venv venv && venv/bin/pip install onnx onnxruntime onnx2tf tensorflow tf_keras onnx_graphsurgeon sng4onnx onnxsim ai-edge-litert pillow
curl -LO https://github.com/DocsaidLab/DocAligner/releases/download/1.1.1/lcnet100_h_e_bifpn_256_fp32.onnx
python tools/models/rewrite_einsum.py lcnet100_h_e_bifpn_256_fp32.onnx lcnet100_fixed.onnx
onnx2tf -i lcnet100_fixed.onnx -o out -ois img:1,3,256,256   # flatbuffer_direct backend
cp out/lcnet100_fixed_float32.tflite app/src/main/assets/models/docaligner_lcnet100.tflite
```

The Einsum rewrite is required: the stock conversion silently produces wrong heatmaps.

## Black & white pages (CCITT G4)

`CcittG4` is checked against libtiff in both directions: unit tests decode fixtures libtiff
encoded, and libtiff decodes ours (`tools/testdata/make_g4_fixtures.py --check`). On the device,
pdfium renders a G4 page identical to our decoder (100% of pixels).

## PDF/A

Saved PDFs declare PDF/A-2u: XMP metadata matching the document information, an sRGB output
intent (`app/src/main/resources/pdf/srgb.icc`, built by `tools/pdf/make_srgb_icc.py` and checked
against littleCMS), no unembedded fonts except the invisible text layer's (exempt in text
rendering mode 3). `PdfaSamplesTest` writes samples to `app/build/pdfa` for validation:

```bash
java -cp "verapdf/*" org.verapdf.apps.GreenfieldCliWrapper --flavour 2u app/build/pdfa/*.pdf
```

(veraPDF: `org.verapdf.apps:greenfield-apps` from Maven Central.)

## Curved pages (UVDoc)

```bash
git clone https://github.com/tanguymagne/UVDoc
python tools/models/export_uvdoc.py UVDoc uvdoc.onnx photo.jpg   # checks ONNX against PyTorch
onnx2tf -i uvdoc.onnx -o out -tb tf_converter
cp out/uvdoc_float16.tflite app/src/main/assets/models/uvdoc_fp16.tflite
python tools/testdata/make_curved_pages.py app/src/androidTest/assets   # synthetic test pages
```

The export rewrites the reflect-padded convolutions with index lookups (`onnx2tf` maps ONNX
`Pad(reflect)` to the wrong axes) and keeps only the 2D grid branch.

Synthetic curved pages (book, curl, wave, gentle bend; exact page position for every pixel), mean
error as a fraction of the page width, output outside the page penalized:

| Page | Corner warp | UVDoc |
|---|---|---|
| Book | 3.44% | **1.51%** |
| Curl | 4.90% | **2.29%** |
| Wave | 2.20% | **0.96%** |
| Gentle bend | 1.46% | **1.27%** |

On flat pages UVDoc is *less* precise than the corners (SmartDoc: median error 1.4% vs 0.4%), and
its grid bends as much on many flat pages as on curved ones, so bending alone can't decide. The
photo decides: the edge strength along UVDoc's curved outline over that along the straight
outline is 1.00 (median) on flat SmartDoc frames and 1.4–1.7 on curved pages; pages above 1.3
are flattened (≈2% of flat frames, mostly ones where the corners were wrong anyway).

See [TODO.md](TODO.md) for the roadmap (OCR, curved-page dewarping, learned restoration).

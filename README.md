# PDF Creator (Android)

Turn photos of documents into PDFs – one photo per page – and edit existing PDFs.

## Features
- **Camera** (in-app, CameraX): photos go to the app's private cache, never to the gallery. Take several shots in a row.
- **Gallery** import via the system Photo Picker (no storage permission).
- **Auto-crop**: document edges are detected (OpenCV) and perspective-corrected. Corners can be adjusted by hand.
- **Filters**: Auto, Original, Colour (enhanced), Gray, B&W. *Auto* picks Colour / Gray / B&W per page from its content.
- **Pages**: reorder by long-press + drag, rotate, delete, retake.
- **Page size** follows the cropped photo (long side = A4 long side).
- **Quality slider** (Small … Maximum) for photo pages.
- **OCR** (Tesseract, offline, German + English): adds an invisible text layer so the PDF is searchable. Switch in the save dialog, off by default.
- **Edit existing PDFs**: add pages (camera/gallery), delete, reorder, rotate.
  - *Scanned* pages can be converted to editable photo pages (crop, filters) and get OCR.
  - *Digital* pages (real text) can only be moved, rotated or deleted.
  - Save as a new file (default) or overwrite the original.
- **History**: PDFs are saved to `Documents/PDF Creator` and listed on the start screen (open, share, edit, delete).
- **Share** to any app via the Android share sheet.

## Build
GitHub Actions builds a debug APK on every push (*Actions → Build APK → Artifacts*).
Locally: `./gradlew assembleDebug` (JDK 17, Android SDK 35).

The APK is signed with the fixed key in `app/debug.keystore`, so new builds install as updates.

## Libraries
OpenCV (Apache 2.0), PdfBox-Android (Apache 2.0), Tesseract4Android (Apache 2.0) with `tessdata_fast` models, CameraX, Jetpack Compose, Reorderable.

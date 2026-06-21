# Screenshot and Optional Local OCR Design

## Goal

Add File Keeper's own screenshot workflow and OCR pipeline so users do not need to use `Win + Shift + S`, and can optionally install a local OCR package for better offline OCR. The first version supports Windows, captures a user-selected screen region, saves the screenshot as an image clipboard history record, and writes OCR text into the record note for preview and search.

## Confirmed Scope

- Screenshot mode: region selection with a full-screen overlay.
- Trigger: a dedicated screenshot global shortcut.
- OCR result behavior: one image history record, with OCR text stored in `ocr_text`, `note`, and `search_text`.
- Local enhanced OCR: optional package under the app install directory's `ocr/` subdirectory.
- Fallback: when the optional OCR package is missing, Windows falls back to Windows system OCR.
- Non-Windows behavior: preserve interfaces, but the first version does not implement real screenshot capture.
- Out of scope for the first version: full-screen screenshot, active-window screenshot, delayed screenshot, annotation/editing tools, PDF OCR, table/layout analysis, cloud OCR, network model download.

## Architecture

The feature has three boundaries:

1. Frontend interaction layer
   - Shows the screenshot selection overlay.
   - Tracks mouse drag coordinates.
   - Handles cancel with `Esc`.
   - Sends the selected region to a Tauri command.
   - Shows success or failure feedback.

2. Rust system layer
   - Captures the Windows screen using native APIs.
   - Crops the selected region.
   - Saves a PNG into the existing clipboard image cache.
   - Runs OCR through an `OcrProvider` abstraction.
   - Inserts the result as an image clipboard history item.
   - Emits `clipboard://changed` so the clipboard page refreshes.

3. OCR provider layer
   - Checks for an optional OCR package at `<install-dir>/ocr/file-keeper-ocr.exe`.
   - If present, calls the sidecar with the screenshot image path.
   - If missing on Windows, falls back to the existing Windows system OCR provider.
   - If OCR fails, returns an empty OCR result without blocking screenshot history creation.

## User Flow

```text
User presses screenshot shortcut
  -> File Keeper opens screenshot overlay
  -> user drags a region
  -> frontend sends region to Rust
  -> Rust captures and crops the screen
  -> PNG is saved to clipboard-cache/images/
  -> OCR provider tries optional sidecar first
  -> OCR text is saved as the image record note
  -> clipboard://changed refreshes the clipboard list
```

If the user presses `Esc`, clicks no valid area, or selects a region below the minimum size, the overlay closes and no history record is created.

## Screenshot Shortcut

Add a new setting:

```ts
screenshotShortcut: string
```

Default:

```text
CommandOrControl+Shift+X
```

The app already uses `tauri-plugin-global-shortcut` for the main app shortcut and clipboard quick panel shortcut. Screenshot shortcut registration should follow the same pattern, but keep its own registered state so shortcut cleanup and conflict handling remain separate.

If registration fails, show the same kind of conflict warning used by the existing shortcut settings.

## Screenshot Overlay

The overlay is a frontend component, not the Windows Snipping Tool.

Responsibilities:

- Cover the screen with a translucent layer.
- Let users drag a rectangular region.
- Show the current selection rectangle while dragging.
- Convert CSS/client coordinates into physical screen coordinates before invoking the backend.
- Cancel on `Esc`.
- Reject tiny selections.

The first version does not need handles, resizing, annotation, or post-capture editing.

## Backend Screenshot Capture

Add a Windows screenshot module under the platform layer. It should expose a focused function similar to:

```rust
capture_screen_region(region: ScreenRegion) -> Result<Vec<u8>, String>
```

The function returns PNG bytes for the selected region. The clipboard service then saves the bytes into the same image cache path used by image clipboard snapshots.

The backend command should return the created clipboard item id, so the frontend can select or highlight the new record if needed.

## Clipboard History Integration

Screenshots should reuse the existing image clipboard history shape:

- `kind = image`
- `image_path` points to the saved PNG
- `thumbnail_path` can point to the same PNG in the first version
- `cache_state = cached`
- `cache_bytes` is the PNG file size
- `ocr_text` stores OCR output when available
- `note` mirrors OCR text when available
- `search_text` includes OCR and note text

Deleting the screenshot history item should delete the cached screenshot file, following the cache cleanup behavior already used for backed-up images.

## OCR Provider Priority

Use this priority order:

```text
1. Optional local OCR sidecar under install-dir/ocr/
2. Windows system OCR fallback on Windows
3. Disabled/empty OCR result
```

The optional local OCR package layout is:

```text
File Keeper install directory/
  File Keeper.exe
  ocr/
    file-keeper-ocr.exe
    models/
      det.onnx
      rec.onnx
      cls.onnx
    onnxruntime.dll
```

The app should not download OCR models at runtime.

## OCR Sidecar Protocol

Rust calls the sidecar with an image path and reads JSON from stdout.

Input example:

```json
{
  "imagePath": "C:/Users/.../clipboard-cache/images/shot.png",
  "language": "zh_en"
}
```

Output example:

```json
{
  "text": "识别出来的完整文本",
  "engine": "rapidocr-onnx",
  "elapsedMs": 123,
  "blocks": [
    {
      "text": "某一行文字",
      "confidence": 0.96,
      "box": [[10, 20], [200, 20], [200, 50], [10, 50]]
    }
  ]
}
```

Only `text`, `engine`, and `elapsedMs` are required for the first version. `blocks` are included in the protocol so future search-hit highlighting can reuse the data without changing the contract.

## Optional OCR Package Behavior

The base File Keeper installer remains usable without local enhanced OCR.

When `<install-dir>/ocr/file-keeper-ocr.exe` exists:

- Use the RapidOCR + ONNX Runtime sidecar.
- Treat it as local enhanced OCR.
- Do not require Windows OCR language packs.

When it does not exist:

- On Windows, use Windows system OCR as a fallback.
- On non-Windows, return an empty OCR result.
- Do not show an error just because the optional OCR package is absent.

If the sidecar exists but fails or times out:

- Save the screenshot image record anyway.
- Leave OCR/note empty.
- Log the OCR failure for diagnostics.
- Do not block the screenshot workflow.

## Settings

Add or extend settings with:

```ts
screenshotShortcut: string
screenshotOcrEnabled: boolean
ocrProviderMode: 'auto' | 'local_sidecar' | 'windows_system' | 'disabled'
```

Recommended defaults:

```ts
screenshotShortcut: 'CommandOrControl+Shift+X'
screenshotOcrEnabled: true
ocrProviderMode: 'auto'
```

`auto` follows the provider priority order. The first version can expose the shortcut and OCR enable/disable switch in settings, while provider mode can be stored internally or shown as a simple status label if UI scope needs to stay small.

## Error Handling

- Screenshot capture failure: show a screenshot failure message and create no history record.
- OCR sidecar missing: fallback silently.
- OCR sidecar failure: screenshot still saves, OCR text stays empty.
- OCR timeout: screenshot still saves, OCR text stays empty.
- Windows OCR unavailable: screenshot still saves, OCR text stays empty.
- Shortcut conflict: show shortcut registration warning and keep the setting value for the user to change.
- Tiny selected region: cancel capture and create no history record.

## Testing Strategy

Automated frontend tests:

- Screenshot shortcut setting can be saved.
- Shortcut trigger opens screenshot selection mode.
- `Esc` exits screenshot selection mode.
- Dragging a valid region calls the screenshot command with expected coordinates.
- Tiny selections do not call the backend.

Automated Rust tests:

- OCR provider selection prefers local sidecar when the executable exists.
- OCR provider selection falls back to Windows OCR when sidecar is absent on Windows.
- OCR failure returns empty text without failing screenshot insertion.
- Screenshot image insertion writes `ocr_text`, `note`, and searchable text when OCR returns text.
- Deleting a screenshot image record removes the cached screenshot file.

Manual Windows tests:

- Press screenshot shortcut and select a region.
- Press `Esc` to cancel.
- Capture Chinese, English, and mixed Chinese-English text.
- Test with `ocr/` package present.
- Test with `ocr/` package absent.
- Confirm fallback does not block screenshot capture.
- Confirm screenshot appears in clipboard history as an image.
- Confirm OCR text appears as note and participates in search.
- Confirm deleting the history item deletes the screenshot cache file.

## Implementation Notes

- Keep screenshot capture, OCR provider selection, and clipboard insertion as separate units.
- Do not let frontend call the OCR sidecar directly.
- Do not let screenshot OCR depend on the system clipboard; screenshots are written directly into File Keeper history.
- Preserve existing clipboard image collection behavior for copied images and files.
- Keep Windows OCR as fallback only, not the primary local enhanced OCR path.

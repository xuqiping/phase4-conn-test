# File Keeper Optional OCR Sidecar

This optional package provides local enhanced OCR for File Keeper using RapidOCR and ONNX Runtime.

## Build

```powershell
./build-windows.ps1
```

## Install

Copy the built executable to:

```text
<File Keeper install directory>/ocr/file-keeper-ocr.exe
```

When this file exists, File Keeper uses the local sidecar first. When it is missing, Windows builds fall back to Windows system OCR.

## Input/Output Format

The sidecar reads JSON from stdin and writes OCR results to stdout:

**Input (stdin):**
```json
{
  "imagePath": "C:/path/to/image.png",
  "language": "zh_en"
}
```

**Output (stdout):**
```json
{
  "text": "识别的文本内容\nRecognized text",
  "engine": "rapidocr-onnx",
  "elapsedMs": 150,
  "blocks": [
    {
      "text": "识别的文本内容",
      "confidence": 0.95,
      "boxPoints": [[10.0, 20.0], [100.0, 20.0], [100.0, 40.0], [10.0, 40.0]]
    }
  ]
}
```

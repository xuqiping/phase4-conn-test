import json
import sys
import time

from rapidocr_onnxruntime import RapidOCR


def main() -> int:
    started = time.perf_counter()
    request = json.load(sys.stdin)
    image_path = request["imagePath"]
    engine = RapidOCR()
    result, _ = engine(image_path)
    blocks = []
    texts = []
    for item in result or []:
        box, text, confidence = item
     text = str(text).strip()
        if not text:
          continue
        texts.append(text)
        blocks.append({
            "text": text,
            "confidence": float(confidence),
            "boxPoints": [[float(point[0]), float(point[1])] for point in box]
        })
    output = {
        "text": "\n".join(texts),
        "engine": "rapidocr-onnx",
        "elapsedMs": int((time.perf_counter() - started) * 1000),
        "blocks": blocks
    }
    json.dump(output, sys.stdout, ensure_ascii=False)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

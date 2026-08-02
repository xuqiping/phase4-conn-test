# -*- coding: utf-8 -*-
"""
豆包风格对话网页 —— Coze 生图工作流的前端界面 + Flask 后端中转

启动：
    pip install flask requests
    python 2_对话网页.py
然后浏览器打开 http://127.0.0.1:5000

参数对应关系（与工作流「开始」节点一致）：
    文本输入          -> input1
    第 1/2/3 张图片   -> ckimg / ckimg2 / ckimg3

图片参数说明：
    Coze 工作流的 Image 类型参数不能直传文件，需先调
    POST /v1/files/upload 上传获得 file_id，
    再以 {"file_id": "..."} 作为参数值传给工作流。
"""

import io
import json
import os
import re
import time
import uuid
import urllib.parse

import requests
from flask import (Flask, jsonify, request, send_file,
                   send_from_directory)
from PIL import Image, ImageDraw, ImageFont

from coze_token import auth_headers

# ===================== 配置区 =====================
# 鉴权配置在 coze_token.py 中（OAuth JWT 应用：CLIENT_ID / KID / private_key.pem）

WORKFLOW_ID = "7629091689604775946"
API_BASE = "https://api.coze.cn"

# 参数名（与工作流开始节点定义一致）
TEXT_PARAM = "input1"
IMAGE_PARAMS = ["ckimg", "ckimg2", "ckimg3"]

POLL_INTERVAL = 3
POLL_TIMEOUT = 300

BASE_DIR = os.path.dirname(os.path.abspath(__file__))

# ---- 归档与水印配置 ----
# 归档根目录：图片按类别存到这里（downloads/<第一层>/<第二层用-拼接>/）
DOWNLOAD_DIR = os.path.join(BASE_DIR, "downloads")
# 水印文字（固定，请改成你自己的；会斜向平铺多次覆盖整图）
WATERMARK_TEXT = "绘雅图研阁为您精心定制您"
LOWRES_MAX = 512          # 低清图长边像素
LOWRES_QUALITY = 70       # 低清图 JPEG 质量
WATERMARK_ANGLE = 30      # 水印倾斜角度
WATERMARK_ALPHA = 130     # 水印不透明度（0~255，越大越明显）
# 中文字体（微软雅黑，Windows 自带；换机器可改路径，如 simhei.ttf）
FONT_PATH = r"C:\Windows\Fonts\msyh.ttc"

# ==================================================

app = Flask(__name__)

# 内存任务表：task_id -> {"execute_id": str}
TASKS = {}


def upload_file_to_coze(file_storage) -> str:
    """把用户上传的图片传给 Coze，返回 file_id。"""
    resp = requests.post(
        f"{API_BASE}/v1/files/upload",
        headers=auth_headers(),
        files={"file": (file_storage.filename, file_storage.stream, file_storage.mimetype)},
        timeout=60,
    )
    data = resp.json()
    if data.get("code") != 0:
        raise RuntimeError(f"图片上传 Coze 失败: {data.get('msg')} (code={data.get('code')})")
    return data["data"]["id"]


def start_workflow(parameters: dict) -> str:
    resp = requests.post(
        f"{API_BASE}/v1/workflow/run",
        headers=auth_headers(json_body=True),
        json={"workflow_id": WORKFLOW_ID, "parameters": parameters, "is_async": True},
        timeout=30,
    )
    data = resp.json()
    if data.get("code") != 0:
        raise RuntimeError(f"启动工作流失败: {data.get('msg')} (code={data.get('code')})")
    return data["execute_id"]


def get_workflow_history(execute_id: str) -> dict:
    resp = requests.get(
        f"{API_BASE}/v1/workflows/{WORKFLOW_ID}/run_histories/{execute_id}",
        headers=auth_headers(),
        timeout=30,
    )
    data = resp.json()
    if data.get("code") != 0:
        raise RuntimeError(f"查询结果失败: {data.get('msg')} (code={data.get('code')})")
    return data["data"][0]


def _find_first_http(text: str) -> str:
    """从字符串里找出第一个 http(s) 链接，去掉尾随标点。"""
    m = re.search(r"https?://\S+", text or "")
    if not m:
        return ""
    return m.group(0).rstrip(",，。.;:、)）\"'")


def _parse_category_tree(text: str):
    """把 Markdown 缩进列表解析成 (第1层列表, 第2层列表)。

    形如：
        - 二次元
            - 平涂
            - 赛璐珞
    """
    layer1, layer2 = [], []
    for line in (text or "").splitlines():
        stripped = line.strip()
        if not stripped.startswith("-"):
            continue
        item = stripped.lstrip("-").strip()
        if not item:
            continue
        # 原行有前置空白/Tab → 第 2 层，否则第 1 层
        (layer2 if line[:1] in (" ", "\t") else layer1).append(item)
    return layer1, layer2


def _sanitize(name: str) -> str:
    """清洗文件/目录名里的非法字符。"""
    return re.sub(r'[\\/:*?"<>|]', "_", name).strip().strip(".") or "_"


def parse_workflow_output(output: str) -> dict:
    """
    解析工作流输出，抽出图片短链和类别（两层）。

    输出是嵌套 JSON：
        {"node_status":"...","Output":"{\\"content_type\\":1,\\"data\\":\\"<短链>\\n---\\n类别\\n---\\n<类别树>\\",...}"}
    data 里用 \\n---\\n 分成三段：图片短链 / "类别" / 类别树。

    返回：
        {"short_link": ..., "layer1": "二次元", "layer2": "平涂-赛璐珞",
         "label": "二次元 / 平涂-赛璐珞"}
    """
    try:
        outer = json.loads(output)
        inner = json.loads(outer["Output"])
        data = inner.get("data", "")
    except (json.JSONDecodeError, KeyError, TypeError):
        data = output  # 退化：把整个 output 当纯文本处理

    short_link = ""
    layer1, layer2 = [], []

    parts = re.split(r"\n-{2,}\n", data)
    if parts:
        short_link = _find_first_http(parts[0])
    if len(parts) >= 3:
        layer1, layer2 = _parse_category_tree(parts[2])
    if not short_link:
        short_link = _find_first_http(data)  # 最后兜底

    l1 = "-".join(layer1)               # 第 1 层（通常只有 1 个）
    l2 = "-".join(layer2)               # 第 2 层多个用 - 拼接
    label = " / ".join(p for p in (l1, l2) if p)
    return {"short_link": short_link, "layer1": l1, "layer2": l2, "label": label}


def _make_watermark(size: tuple) -> Image.Image:
    """生成一张覆盖整图的斜向平铺水印透明图层（防盗图样式）。"""
    w, h = size
    font_size = max(20, w // 18)
    try:
        font = ImageFont.truetype(FONT_PATH, font_size)
    except Exception:
        font = ImageFont.load_default()

    # 量一次文字尺寸，决定平铺间距
    probe = ImageDraw.Draw(Image.new("RGBA", (10, 10)))
    bbox = probe.textbbox((0, 0), WATERMARK_TEXT, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    step_x = tw + max(40, w // 6)
    step_y = th + max(40, h // 6)

    # 建一张比原图大的图层，旋转后仍能覆盖整图
    diag = int((w ** 2 + h ** 2) ** 0.5) + step_x + step_y
    layer = Image.new("RGBA", (diag, diag), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    y = 0
    while y < diag:
        x = 0
        while x < diag:
            draw.text((x, y), WATERMARK_TEXT, font=font,
                      fill=(255, 255, 255, WATERMARK_ALPHA))
            x += step_x
        y += step_y

    layer = layer.rotate(WATERMARK_ANGLE, resample=Image.BICUBIC)
    left = (diag - w) // 2
    top = (diag - h) // 2
    return layer.crop((left, top, left + w, top + h))


def download_and_save(parsed: dict) -> dict:
    """下载原图，按类别归档（原图 + 水印低清图），返回给前端的展示 URL。"""
    short_link = parsed["short_link"]
    if not short_link:
        raise RuntimeError("工作流输出里没有图片链接")

    # 1. 下载原图（短链会 302 跳到真实图片，无需 Referer）
    resp = requests.get(short_link, headers={"User-Agent": "Mozilla/5.0"}, timeout=60)
    resp.raise_for_status()

    # 2. 算归档目录：downloads/<layer1>/<layer2>/，无类别则归到 _未分类
    parts = [p for p in (parsed["layer1"], parsed["layer2"]) if p]
    url_parts = [_sanitize(p) for p in parts] if parts else ["_未分类"]
    save_dir = os.path.join(DOWNLOAD_DIR, *url_parts)
    os.makedirs(save_dir, exist_ok=True)

    stamp = time.strftime("%Y%m%d_%H%M%S") + "_" + uuid.uuid4().hex[:4]

    # 3. 存原图：直接落原始字节，保持完全原样
    ctype = resp.headers.get("Content-Type", "").lower()
    ext = ".jpg" if ("jpeg" in ctype or "jpg" in ctype) else ".png"
    orig_name = f"{stamp}_原{ext}"
    with open(os.path.join(save_dir, orig_name), "wb") as f:
        f.write(resp.content)

    # 4. 生成低清水印图：长边缩到 512，斜向满铺水印，JPEG 质量 70
    img = Image.open(io.BytesIO(resp.content)).convert("RGBA")
    img.thumbnail((LOWRES_MAX, LOWRES_MAX))
    img = Image.alpha_composite(img, _make_watermark(img.size))
    wm_name = f"{stamp}_水印.jpg"
    img.convert("RGB").save(os.path.join(save_dir, wm_name), "JPEG", quality=LOWRES_QUALITY)

    # 5. 给前端的展示 URL（原图，编码中文路径段）
    url = "/img/" + "/".join(urllib.parse.quote(seg) for seg in (url_parts + [orig_name]))
    return {"url": url, "label": parsed["label"], "saved_to": save_dir}


@app.route("/")
def index():
    return send_file(os.path.join(BASE_DIR, "chat.html"))


@app.route("/img/<path:filename>")
def serve_img(filename):
    """供前端展示归档里的原图（支持子目录 + 中文名）。"""
    return send_from_directory(DOWNLOAD_DIR, filename)


@app.route("/api/chat", methods=["POST"])
def chat():
    """接收文本 + 最多 3 张图片，启动工作流，返回 task_id 供轮询。"""
    text = (request.form.get("text") or "").strip()
    files = [request.files.get(f"img{i}") for i in range(1, 4)]
    files = [f for f in files if f and f.filename]

    if not text and not files:
        return jsonify({"ok": False, "error": "请输入文本或上传图片"}), 400

    try:
        parameters = {TEXT_PARAM: text}
        for key, fs in zip(IMAGE_PARAMS, files):
            parameters[key] = {"file_id": upload_file_to_coze(fs)}

        execute_id = start_workflow(parameters)
    except Exception as e:
        return jsonify({"ok": False, "error": str(e)}), 500

    task_id = uuid.uuid4().hex
    TASKS[task_id] = {"execute_id": execute_id, "created": time.time()}
    return jsonify({"ok": True, "task_id": task_id})


@app.route("/api/result/<task_id>")
def result(task_id):
    """前端轮询任务结果。"""
    task = TASKS.get(task_id)
    if not task:
        return jsonify({"ok": False, "error": "任务不存在"}), 404

    try:
        history = get_workflow_history(task["execute_id"])
    except Exception as e:
        return jsonify({"ok": False, "error": str(e)}), 500

    status = history.get("execute_status")
    if status == "Success":
        TASKS.pop(task_id, None)
        parsed = parse_workflow_output(history.get("output", ""))
        try:
            saved = download_and_save(parsed)
            print(f"[归档] {saved['label'] or '(无类别)'} -> {saved['saved_to']}")
            text = f"类别：{saved['label']}" if saved["label"] else ""
            return jsonify({"ok": True, "status": "done",
                            "text": text, "images": [saved["url"]]})
        except Exception as e:
            # 下载/处理失败时退化：直接给短链，保证至少能展示原图
            fallback = parsed["short_link"]
            print(f"[归档失败] {e}")
            return jsonify({"ok": True, "status": "done",
                            "text": f"⚠️ 归档失败（{e}），仅展示原图",
                            "images": [fallback] if fallback else []})
    if status == "Fail":
        TASKS.pop(task_id, None)
        return jsonify({"ok": False, "status": "fail",
                        "error": history.get("error_message") or "工作流执行失败"}), 500
    return jsonify({"ok": True, "status": "running"})


if __name__ == "__main__":
    app.run(host="127.0.0.1", port=5000, debug=False)

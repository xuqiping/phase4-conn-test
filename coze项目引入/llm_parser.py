# -*- coding: utf-8 -*-
"""
批量生图文档解析模块 —— 用大模型把一份「多条 prompt」文档拆成结构化任务列表。

文档约定（用户已确认）：
    每段 prompt 的【下方独立一行】写出该 prompt 要用的参考图文件名（逗号分隔）。
    每个 prompt 最多 3 张参考图。

LLM 走 Anthropic 兼容接口（智谱 GLM 的 /api/anthropic 端点，即 Claude Messages API 格式），
用裸 requests 调用，无需额外 SDK。

配置读取（环境变量优先 → application.yaml 回退 → 内置默认值）：
    LLM_API_KEY   —— 必填。环境变量 LLM_API_KEY，或同目录 application.yaml 的 api_key
    LLM_BASE_URL  —— 默认 智谱 Anthropic 端点：https://open.bigmodel.cn/api/anthropic
                     （环境变量 LLM_BASE_URL，或 application.yaml 的 base_url）
    LLM_MODEL     —— 默认 glm-5（环境变量 LLM_MODEL，或 application.yaml 的 model）

依赖：requests、PyYAML（均已装）
"""

import json
import os
import re

import requests
import yaml

# ===================== 配置区 =====================
def _load_yaml_config():
    """读取与本文件同目录的 application.yaml 作为兜底配置。
    文件缺失或解析失败时安静返回空 dict，不报错（环境变量仍可独立生效）。"""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "application.yaml")
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f)
        return data or {}
    except FileNotFoundError:
        return {}
    except Exception:
        return {}


_YAML_CFG = _load_yaml_config()


def _cfg(env_key, yaml_key, default):
    """配置优先级：环境变量 > application.yaml > 默认值。"""
    env_val = os.environ.get(env_key)
    if env_val and env_val.strip():
        return env_val.strip()
    yaml_val = _YAML_CFG.get(yaml_key)
    if yaml_val is not None and str(yaml_val).strip():
        return str(yaml_val).strip()
    return default


LLM_BASE_URL = _cfg("LLM_BASE_URL", "base_url", "https://open.bigmodel.cn/api/anthropic").rstrip("/")
LLM_API_KEY = _cfg("LLM_API_KEY", "api_key", "")
LLM_MODEL = _cfg("LLM_MODEL", "model", "glm-5")
LLM_TIMEOUT = 60
LLM_MAX_TOKENS = 4096
MAX_REFS_PER_PROMPT = 3
# ==================================================

_SYSTEM_PROMPT = """你是一个「批量生图文档解析器」。

输入是一份包含【多条生图 prompt】的文档。文档的规则是：
- 每一段是一个独立的生图 prompt（即给画图模型的画面描述）。
- 在每段 prompt 的【正下方独立一行】，会写出该 prompt 要用的参考图文件名，多个用逗号分隔；
  这一行通常以「参考图」「参考」「图片」「refs」等字样开头，也可能直接就是文件名。
- 没有参考图的 prompt，下方就没有这一行（对应空数组）。

【标准格式示例】（用户通常用这种结构，请重点按它识别）
# 第一段
```
一只橘猫戴着墨镜，靠在复古跑车的引擎盖上，夕阳下的美国西部公路
```
参考图: cat.jpg, car.jpg

# 第二段
```
日式拉面店吧台特写，浓厚豚骨汤底，溏心蛋对半切开
```
参考图: ramen.jpg

说明：`# 第N段` 是分段标记（忽略它的具体文字）；三个反引号 ``` 里的是 prompt 正文；
紧跟其后的「参考图:」行是参考图文件名。没有 ``` 包裹、没有 `#` 标记的写法也要能识别。

你的任务：把文档拆成多个任务，每个任务包含：
- prompt：该段生图 prompt 的正文（去掉参考图那一行，去掉序号/项目符号，保留完整画面描述）
- refs：该 prompt 的参考图文件名列表（只保留文件名本身，如 cat.jpg；没有就给空数组 []）

【硬性要求】
1. 只输出一个 JSON 数组，不要输出任何解释、前后缀文字、markdown 标记。
2. 输出格式必须是：[{"prompt": "...", "refs": ["a.jpg", "b.jpg"]}, ...]
3. refs 里的文件名保持文档里写的原样（含扩展名），不要自己改。
4. 忽略空段落；prompt 正文为空整条丢弃。
"""


def _extract_json_array(content: str):
    """从 LLM 输出里稳健地抽出 JSON 数组（兼容被 ```json 包裹或带多余文字的情况）。"""
    content = content or ""
    # 去掉 ```json ... ``` / ``` ... ``` 代码块围栏
    fence = re.search(r"```(?:json)?\s*(.*?)```", content, re.DOTALL)
    if fence:
        content = fence.group(1)
    # 取第一个 [ 到最后一个 ]
    start, end = content.find("["), content.rfind("]")
    if start == -1 or end == -1 or end < start:
        raise ValueError("LLM 输出里找不到 JSON 数组")
    return json.loads(content[start:end + 1])


def parse_batch_document(text: str):
    """
    把批量生图文档拆成任务列表。

    返回：list[{"prompt": str, "refs": list[str]}]，refs 已去重、最多 MAX_REFS_PER_PROMPT 个。
    解析失败抛 RuntimeError（带可读提示）。
    """
    if not (text or "").strip():
        return []
    if not LLM_API_KEY:
        raise RuntimeError(
            "未配置 API Key。请在环境变量设置 LLM_API_KEY，"
            "或在同目录 application.yaml 里写 api_key 后再运行。例如：\n"
            '  Windows:  set LLM_API_KEY=你的key\n'
            "  application.yaml:  api_key: 你的key")

    # 调用 LLM：对临时性问题退避重试。
    # 智谱 anthropic 端点不稳定时会用 401（"令牌已过期或验证不正确"）表示临时限流，
    # 并非 key 问题；429/5xx/网络抖动同理。重试 2 次（2s/4s 退避）即可扛过去。
    import time as _time
    _RETRY_STATUS = {401, 429, 500, 502, 503, 504}
    resp, last_err = None, None
    for attempt in range(3):  # 首次 + 2 次重试
        try:
            resp = requests.post(
                f"{LLM_BASE_URL}/v1/messages",
                headers={
                    "x-api-key": LLM_API_KEY,
                    "anthropic-version": "2023-06-01",
                    "content-type": "application/json",
                },
                json={
                    "model": LLM_MODEL,
                    "system": _SYSTEM_PROMPT,
                    "messages": [
                        {"role": "user", "content": text},
                    ],
                    "max_tokens": LLM_MAX_TOKENS,
                    "temperature": 0.1,
                },
                timeout=LLM_TIMEOUT,
            )
            if resp.status_code == 200:
                break
            last_err = RuntimeError(
                f"调用 LLM 接口失败（HTTP {resp.status_code}）：{resp.text[:300]}")
            # 不可重试的错误码（400/403/404…）直接抛；可重试码若还有次数则继续
            if resp.status_code not in _RETRY_STATUS or attempt == 2:
                raise last_err
        except requests.RequestException as e:
            last_err = RuntimeError(f"调用 LLM 接口失败（网络）：{e}")
            if attempt == 2:
                raise last_err
        _time.sleep(2 * (2 ** attempt))  # attempt 0→2s, 1→4s
    if resp is None or resp.status_code != 200:
        raise last_err or RuntimeError("调用 LLM 接口失败：未知错误")

    try:
        data = resp.json()
        # Anthropic Messages 返回结构：content 是 block 列表，拼出全部 text block
        blocks = data.get("content") or []
        content = "".join(
            b.get("text", "") for b in blocks
            if isinstance(b, dict) and b.get("type") == "text"
        )
        if not content:
            raise ValueError("返回 content 为空")
    except (ValueError, TypeError) as e:
        raise RuntimeError(f"LLM 返回结构异常：{e}；原文片段：{str(resp.text)[:200]}")

    try:
        raw_jobs = _extract_json_array(content)
    except (ValueError, json.JSONDecodeError) as e:
        raise RuntimeError(f"LLM 输出无法解析为 JSON：{e}\n原始输出片段：{content[:200]}")

    # 二次校验与清洗
    result = []
    for j in raw_jobs:
        if not isinstance(j, dict):
            continue
        prompt = str(j.get("prompt", "")).strip()
        if not prompt:
            continue
        refs, seen = [], set()
        for r in (j.get("refs") or []):
            name = str(r).strip().strip("'\"")
            key = name.lower()
            if name and key not in seen:
                seen.add(key)
                refs.append(name)
        refs = refs[:MAX_REFS_PER_PROMPT]
        result.append({"prompt": prompt, "refs": refs})
    return result


if __name__ == "__main__":
    # 自测：直接运行本文件，粘贴文档到 stdin（Ctrl+Z 结束）即可看拆分结果
    import sys
    print(f"[配置] base_url={LLM_BASE_URL}  model={LLM_MODEL}  "
          f"key={'已配置' if LLM_API_KEY else '未配置'}", file=sys.stderr)
    doc = sys.stdin.read()
    print(json.dumps(parse_batch_document(doc), ensure_ascii=False, indent=2))

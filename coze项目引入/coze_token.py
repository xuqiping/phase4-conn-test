# -*- coding: utf-8 -*-
"""
Coze OAuth（JWT 服务端应用）鉴权模块 —— 1_生图.py 和 2_对话网页.py 共用

原理：
    本地用私钥签一个短期 JWT，调 Coze 换 access_token（最长 900 秒），
    之后所有 API 请求用 access_token 作为 Bearer，过期前自动续签。

准备工作（官方文档：https://docs.coze.cn/developer_guides_oauth_jwt）：
    1. 在 https://www.coze.cn/open/oauth/apps 创建 OAuth 应用，
       应用类型选「普通」（「渠道」是跨账号服务商场景，不用选）
    2. 进入应用详情页，点击「创建 Key」：
       - 平台自动生成 RS256 密钥对，私钥 private_key.pem 会自动下载到本地
         （私钥不上传平台，务必保管好），公钥指纹就是 JWT 的 kid
    3. 在应用里配置权限范围（勾选 运行工作流、上传文件 等）并完成授权
    4. 把 private_key.pem 放到本目录，填入下面的 CLIENT_ID 和 KID

依赖：pip install PyJWT cryptography requests
"""

import os
import threading
import time
import uuid

import jwt
import requests

# ===================== 配置区 =====================

# OAuth 应用的 Client ID（应用详情页可见，即「应用 ID」）
CLIENT_ID = "1143901573108"

# 公钥指纹（创建 Key 后在应用编辑页可见，即 JWT header 里的 kid）
KID = "pJvqMdS4szPFaBZrW2vws2ak8Ff7NqTET6vnw1Jfeew"

# 私钥文件路径（默认就在本脚本同目录）
PRIVATE_KEY_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "private_key.pem")

TOKEN_URL = "https://api.coze.cn/api/permission/oauth2/token"
TOKEN_TTL = 3600  # access_token 有效期（秒），Coze 上限 86399（约24小时）

# ==================================================

_cache = {"token": None, "expires_at": 0}
_private_key = None
# 并发取 token 的锁（批量并行任务多线程同时调用 auth_headers 时用）
_token_lock = threading.Lock()


def _load_private_key() -> str:
    global _private_key
    if _private_key is None:
        if not os.path.exists(PRIVATE_KEY_PATH):
            raise FileNotFoundError(
                f"找不到私钥文件: {PRIVATE_KEY_PATH}\n"
                "请在扣子平台 OAuth 应用里创建密钥对并下载 private_key.pem")
        with open(PRIVATE_KEY_PATH, "r", encoding="utf-8") as f:
            _private_key = f.read()
    return _private_key


def _build_jwt() -> str:
    now = int(time.time())
    payload = {
        "iss": CLIENT_ID,           # 签发者 = 应用 Client ID
        "aud": "api.coze.cn",       # 固定值
        "iat": now,
        "exp": now + TOKEN_TTL,     # 必须与换取时的 duration_seconds 一致
        "jti": uuid.uuid4().hex + uuid.uuid4().hex,  # 随机串防重放（官方建议>32字节）
    }
    return jwt.encode(payload, _load_private_key(),
                      algorithm="RS256", headers={"kid": KID})


def get_access_token() -> str:
    """获取 access_token，带缓存，临过期自动续签。多线程安全（双检锁）。"""
    # 预留 30 秒缓冲；快速路径无锁
    if _cache["token"] and time.time() < _cache["expires_at"] - 30:
        return _cache["token"]

    with _token_lock:
        # 二次检查：可能已有别的线程刚换好 token
        if _cache["token"] and time.time() < _cache["expires_at"] - 30:
            return _cache["token"]

        resp = requests.post(
            TOKEN_URL,
            headers={
                "Authorization": f"Bearer {_build_jwt()}",
                "Content-Type": "application/json",
            },
            json={
                "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
                "duration_seconds": TOKEN_TTL,
            },
            timeout=30,
        )
        data = resp.json()
        if "access_token" not in data:
            raise RuntimeError(
                f"换取 access_token 失败: {data}\n"
                "请检查 CLIENT_ID / KID / 私钥是否匹配，以及应用权限是否已勾选并发布")
        _cache["token"] = data["access_token"]
        _cache["expires_at"] = time.time() + data.get("expires_in", TOKEN_TTL)
        return _cache["token"]


def invalidate():
    """作废缓存的 access_token，强制下次 get_access_token() 重新换取。
    当 Coze 返回 4100（authentication is invalid）时调用——token 可能被平台侧
    提前失效，而本地 expires_at 还没到，不主动 invalidate 就会一直用废 token。"""
    with _token_lock:
        _cache["token"] = None
        _cache["expires_at"] = 0


def auth_headers(json_body: bool = False) -> dict:
    """返回带鉴权的请求头。json_body=True 时附带 Content-Type: application/json。"""
    h = {"Authorization": f"Bearer {get_access_token()}"}
    if json_body:
        h["Content-Type"] = "application/json"
    return h

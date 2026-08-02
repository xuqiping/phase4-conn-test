# -*- coding: utf-8 -*-
"""
通过 Coze（扣子）开放平台 API 调用已发布的工作流（生图工作流）

工作流地址：
    https://www.coze.cn/work_flow?space_id=7485998475315511311&workflow_id=7629091689604775946

API 文档：
    - 运行工作流：https://www.coze.cn/open/docs/developer_guides/workflow_run
    - 异步运行 + 查询结果（生图类耗时较长时推荐）：
      POST /v1/workflow/run  传 "is_async": true 返回 execute_id
      GET  /v1/workflows/{workflow_id}/run_histories/{execute_id} 查询执行结果

准备工作（缺一不可）：
    1. 工作流必须在扣子平台点击「发布」，只有已发布版本才能被 API 调用
    2. 在 https://www.coze.cn/open/oauth/pats 创建「个人访问令牌」(PAT)，
       注意 coze.cn 与 coze.com 的 token 不通用
    3. 把工作流「开始」节点定义的输入参数名填到下面的 WORKFLOW_PARAMS 里
"""

import json
import os
import time

import requests

from coze_token import auth_headers

# ===================== 配置区（按需修改） =====================
# 鉴权配置在 coze_token.py 中（OAuth JWT 应用：CLIENT_ID / KID / private_key.pem）

WORKFLOW_ID = "7629091689604775946"

# 工作流「开始」节点的输入参数（键名必须与工作流里定义的参数名一致！）
# 文本参数：input1；图片参数：ckimg / ckimg2 / ckimg3
# 注意：Image 类型参数需先调 POST /v1/files/upload 拿到 file_id，
#       再以 {"file_id": "..."} 传入（参考 2_对话网页.py 中的 upload_file_to_coze）
WORKFLOW_PARAMS = {
    "input1": "一只在月球上弹吉他的猫，赛博朋克风格",
}

# 生图结果保存路径（工作流输出为图片 URL 时自动下载）
SAVE_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "output_image.png")

API_BASE = "https://api.coze.cn"

# 异步轮询配置
POLL_INTERVAL = 3        # 秒
POLL_TIMEOUT = 300       # 最长等待秒数

# ============================================================


def run_workflow_async(workflow_id: str, parameters: dict) -> str:
    """异步运行工作流，返回 execute_id。"""
    resp = requests.post(
        f"{API_BASE}/v1/workflow/run",
        headers=auth_headers(json_body=True),
        json={
            "workflow_id": workflow_id,
            "parameters": parameters,
            "is_async": True,
        },
        timeout=30,
    )
    data = resp.json()
    if data.get("code") != 0:
        raise RuntimeError(f"启动工作流失败: code={data.get('code')} msg={data.get('msg')} "
                           f"detail={data.get('detail')}")
    return data["execute_id"]


def poll_workflow_result(workflow_id: str, execute_id: str) -> dict:
    """轮询工作流执行结果，直到成功/失败/超时。"""
    start = time.time()
    while time.time() - start < POLL_TIMEOUT:
        resp = requests.get(
            f"{API_BASE}/v1/workflows/{workflow_id}/run_histories/{execute_id}",
            headers=auth_headers(),
            timeout=30,
        )
        data = resp.json()
        if data.get("code") != 0:
            raise RuntimeError(f"查询执行结果失败: code={data.get('code')} msg={data.get('msg')}")

        history = data["data"][0]
        status = history.get("execute_status")  # Success / Fail / Running
        print(f"  状态: {status} ({int(time.time() - start)}s)")

        if status == "Success":
            return history
        if status == "Fail":
            raise RuntimeError(f"工作流执行失败: {history.get('error_message')}")
        time.sleep(POLL_INTERVAL)

    raise TimeoutError(f"等待工作流结果超时（>{POLL_TIMEOUT}s）")


def try_download_image(output: str, save_path: str) -> None:
    """如果输出里包含图片 URL，则下载保存。"""
    urls = []
    try:
        parsed = json.loads(output)
        # 输出通常是 {"output": "https://..."} 或嵌套结构，递归找 http 链接
        def find_urls(obj):
            if isinstance(obj, str) and obj.startswith("http"):
                urls.append(obj)
            elif isinstance(obj, dict):
                for v in obj.values():
                    find_urls(v)
            elif isinstance(obj, list):
                for v in obj:
                    find_urls(v)
        find_urls(parsed)
    except (json.JSONDecodeError, TypeError):
        if isinstance(output, str) and output.startswith("http"):
            urls.append(output)

    if not urls:
        print("输出中未找到图片 URL，不执行下载。")
        return

    url = urls[0]
    print(f"发现图片 URL: {url}")
    img = requests.get(url, timeout=60)
    img.raise_for_status()
    with open(save_path, "wb") as f:
        f.write(img.content)
    print(f"图片已保存: {save_path}")


def main():
    print(f"启动工作流 {WORKFLOW_ID}，参数: {WORKFLOW_PARAMS}")
    execute_id = run_workflow_async(WORKFLOW_ID, WORKFLOW_PARAMS)
    print(f"execute_id = {execute_id}，开始轮询结果...")

    result = poll_workflow_result(WORKFLOW_ID, execute_id)
    output = result.get("output", "")
    print(f"\n工作流输出:\n{output}")

    try_download_image(output, SAVE_PATH)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
项目资产库 Phase4 · 性能评测
对照 plan perf 目标：项目详情首屏(100资产)<1s；矩阵筛选<300ms；搜索<500ms
"""
import json, sys, time, statistics
import requests

B = "http://localhost:8080/api"
tok = requests.post(f"{B}/auth/login", json={"username": "admin", "password": "admin123"}, timeout=20).json()["data"]["accessToken"]
H = {"Authorization": f"Bearer {tok}", "Content-Type": "application/json"}

def pct(lat, p):
    if not lat: return -1
    lat = sorted(lat)
    k = (len(lat) - 1) * p
    f = int(k); c = min(f + 1, len(lat) - 1)
    return lat[f] + (lat[c] - lat[f]) * (k - f)

def bench(name, fn, n=30):
    lat = []
    for _ in range(n):
        t0 = time.perf_counter()
        ok = fn()
        lat.append((time.perf_counter() - t0) * 1000)
    print(f"{name:32}  n={n}  p50={pct(lat,.5):6.1f}ms  p95={pct(lat,.95):6.1f}ms  p99={pct(lat,.99):6.1f}ms  max={max(lat):6.1f}ms")
    return lat

# 建 perf 项目 + 种 120 资产（分布各类型/角色）
r = requests.post(f"{B}/assets/projects", headers=H, json={"name": "_phase4_perf_项目"}, timeout=20)
pid = r.json()["data"]["id"]
print(f"perf 项目 pid={pid}，种资产中…")
t0 = time.time()
types = ["PROMPT", "SCRIPT", "IMAGE", "VIDEO", "AUDIO"]
roles = ["人物", "道具", "场景", "风格", "通用"]
for i in range(120):
    mt = types[i % 5]
    rk = [roles[i % 5]]
    content = json.dumps({"body": f"perf 提示词正文 #{i} 古风女子红衣执扇庭院朱砂"}) if mt in ("PROMPT","SCRIPT") else "{}"
    requests.post(f"{B}/assets/projects/{pid}/assets", headers=H, json={
        "mediaType": mt, "name": f"perf资产{i:03d}", "roleKeys": rk, "content": content}, timeout=20)
print(f"种 120 资产耗时 {time.time()-t0:.1f}s")

print("\n=== 关键接口延迟（30 次） ===")
bench("GET 项目资产列表(size=24)", lambda: requests.get(f"{B}/assets/projects/{pid}/assets?page=1&size=24", headers=H, timeout=20).status_code == 200)
bench("GET 矩阵计数 count", lambda: requests.get(f"{B}/assets/projects/{pid}/assets/count", headers=H, timeout=20).status_code == 200)
bench("GET 矩阵筛选 PROMPT+人物", lambda: requests.get(f"{B}/assets/projects/{pid}/assets", headers=H, params={"mediaType":"PROMPT","roleKey":"人物","page":1,"size":24}, timeout=20).status_code == 200)
bench("GET 搜索 q='古风'", lambda: requests.get(f"{B}/assets/projects/{pid}/assets", headers=H, params={"q":"古风","page":1,"size":24}, timeout=20).status_code == 200)
bench("GET 项目详情", lambda: requests.get(f"{B}/assets/projects/{pid}", headers=H, timeout=20).status_code == 200)
bench("GET 项目列表", lambda: requests.get(f"{B}/assets/projects", headers=H, timeout=20).status_code == 200)

# 清理
requests.delete(f"{B}/assets/projects/{pid}", headers=H, timeout=20)
print("\n已清理 perf 项目")

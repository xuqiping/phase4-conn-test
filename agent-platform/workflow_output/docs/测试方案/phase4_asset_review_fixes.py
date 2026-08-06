#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Phase4 审查修复运行时验证（重启后端后跑）
1. JSON 校验：非 JSON content → 400（非 500/409）
2. PROMPT 建资产 {body} → 200 + 详情 content 可读
3. 一致性包清空：set → clear（空串）→ 键消失
4. REFERENCE 绑定：resolve 带 canvasId+nodeId → usages 出现 REFERENCE 行
5. SCRIPT synopsis：建剧本 {synopsis} → breakdown 跑通（需 LLM，跳过断言只验 body 键读取不报"正文不能为空"前的 400）
"""
import json, sys
import requests

B = "http://localhost:8080/api"
tok = requests.post(f"{B}/auth/login", json={"username":"admin","password":"admin123"}, timeout=20).json()["data"]["accessToken"]
H = {"Authorization": f"Bearer {tok}", "Content-Type":"application/json"}
R = []  # (name, ok, detail)
def chk(n,c,d=""):
    R.append((n,bool(c),d)); print(f"[{'PASS' if c else 'FAIL'}] {n}  {d}")

pid = requests.post(f"{B}/assets/projects", headers=H, json={"name":"_phase4_fix_验证"}, timeout=20).json()["data"]["id"]

# 1. 非 JSON content → 400
r = requests.post(f"{B}/assets/projects/{pid}/assets", headers=H, json={
    "mediaType":"PROMPT","name":"坏","content":"不是JSON的纯文本"}, timeout=20)
chk("FIX 非JSON content→400（非500/409）", r.status_code==400, f"status={r.status_code} body={r.text[:120]}")

# 2. PROMPT {body} 建资产
r = requests.post(f"{B}/assets/projects/{pid}/assets", headers=H, json={
    "mediaType":"PROMPT","name":"好提示词","roleKeys":["人物"],"content":json.dumps({"body":"红衣女子"})}, timeout=20)
aid = r.json().get("data",{}).get("id") if r.status_code==200 else None
chk("PROMPT {body} 建资产 200", r.status_code==200 and aid, f"status={r.status_code} aid={aid}")
d = requests.get(f"{B}/assets/assets/{aid}", headers=H, timeout=20).json().get("data",{})
chk("详情 content 含 body", "body" in (d.get("content") or ""), f"content={d.get('content')}")

# 3. 一致性包：set → clear
# 先建 人物 资产（一致性包仅人物/道具/场景额显，但 saveConsistencyPack 端点对所有类型可调）
r = requests.post(f"{B}/assets/projects/{pid}/assets", headers=H, json={
    "mediaType":"PROMPT","name":"人物卡","roleKeys":["人物"],"content":json.dumps({"body":"x"})}, timeout=20)
hid = r.json()["data"]["id"]
# set standardDescription
requests.put(f"{B}/assets/assets/{hid}/consistency-pack", headers=H,
             json={"standardDescription":"老板娘定稿描述"}, timeout=20)
cur = requests.get(f"{B}/assets/assets/{hid}", headers=H, timeout=20).json()["data"]["content"]
chk("一致性包 set 后 content 含 standardDescription", "standardDescription" in cur, f"content={cur}")
# clear（空串）
requests.put(f"{B}/assets/assets/{hid}/consistency-pack", headers=H,
             json={"standardDescription":""}, timeout=20)
cur2 = requests.get(f"{B}/assets/assets/{hid}", headers=H, timeout=20).json()["data"]["content"]
chk("一致性包 清空（空串）→ standardDescription 消失", "standardDescription" not in cur2, f"content={cur2}")

# 4. REFERENCE 绑定：建画布 → resolve 带 canvasId+nodeId → usages 出 REFERENCE
cid = requests.post(f"{B}/canvas", headers=H, timeout=20).json()["data"]["id"]
r = requests.post(f"{B}/assets/assets/{aid}/resolve", headers=H,
                  json={"version":None,"canvasId":cid,"nodeId":"node-ref-1"}, timeout=20)
chk("resolve 带 canvasId+nodeId 200", r.status_code==200, f"status={r.status_code}")
us = requests.get(f"{B}/assets/assets/{aid}/usages", headers=H, timeout=20).json().get("data",[])
has_ref = any(u.get("bindType")=="REFERENCE" for u in us)
chk("usages 含 REFERENCE 绑定（双向追溯补齐）", has_ref, f"usages={us}")

# 5. SCRIPT {synopsis} 建资产（breakdown 需 LLM，仅验 synopsis 读取不报"正文不能为空"——构造一个调 breakdown，若 LLM 未配会失败但不应是"正文不能为空"）
r = requests.post(f"{B}/assets/projects/{pid}/assets", headers=H, json={
    "mediaType":"SCRIPT","name":"分场剧本","roleKeys":["人物"],"content":json.dumps({"synopsis":"第一场：庭院"})}, timeout=20)
sid = r.json()["data"]["id"] if r.status_code==200 else None
chk("SCRIPT {synopsis} 建资产 200", r.status_code==200 and sid, f"status={r.status_code}")
if sid:
    rb = requests.post(f"{B}/assets/assets/{sid}/breakdown", headers=H, json={}, timeout=30)
    # 不报"正文不能为空"即证明 synopsis 被读到（LLM 失败/超时均可）
    body = rb.text
    not_empty_err = "正文不能为空" not in body
    chk("SCRIPT breakdown 读 synopsis（不报正文不能为空）", not_empty_err, f"status={rb.status_code} body={body[:150]}")

# 清理
requests.delete(f"{B}/assets/projects/{pid}", headers=H, timeout=20)
requests.delete(f"{B}/canvas/{cid}", headers=H, timeout=20)
print(f"\n通过 {sum(1 for _,c,_ in R if c)}/{len(R)}")
sys.exit(0 if all(c for _,c,_ in R) else 1)

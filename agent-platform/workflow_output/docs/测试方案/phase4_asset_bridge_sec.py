#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
项目资产库 Phase4 · 第二轮：画布打通 L5/L6 真链 + 数据层越权 IT
- 数据层 ACL：给 user2 临时授 asset:write，测其对非成员项目写=403（第二层兜底）
- 画布打通：建画布+文本节点(outputText) → canvas-import(PRODUCED) → 重复入库三态 → resolve(REFERENCE) → usages
"""
import json, sys, time
import requests, psycopg2

B = "http://localhost:8080/api"
PG = dict(host="localhost", port=5432, dbname="agent_platform", user="postgres", password="aa64221886")
results = []
def check(n, c, d=""):
    results.append((n, bool(c), d))
    print(f"[{'PASS' if c else 'FAIL'}] {n}  {d}")

def login(u, p):
    r = requests.post(f"{B}/auth/login", json={"username": u, "password": p}, timeout=20)
    return r.json().get("data", {}).get("accessToken") if r.status_code == 200 else None

def H(tok): return {"Authorization": f"Bearer {tok}", "Content-Type": "application/json"}

# ---- grant asset:write to 'user' role temporarily (data-layer ACL test) ----
conn = psycopg2.connect(**PG); cur = conn.cursor()
cur.execute("SELECT id FROM roles WHERE code='user'")
user_role_id = cur.fetchone()[0]
cur.execute("SELECT id FROM permissions WHERE code='asset:write'")
aw_perm_id = cur.fetchone()[0]
cur.execute("INSERT INTO role_permissions(role_id,permission_id) VALUES(%s,%s) ON CONFLICT DO NOTHING",
            (user_role_id, aw_perm_id))
conn.commit()
check("setup: 给 user 角色临时授 asset:write", True, f"role={user_role_id} perm={aw_perm_id}")

admin = H(login("admin", "admin123"))
u2 = login("aa64221886", "aa64221886")
check("setup: user2 登录", u2, "")

# ========== 数据层越权 IT ==========
# admin 建一个私有项目（不邀 user2）
r = requests.post(f"{B}/assets/projects", headers=admin,
                  json={"name": "_phase4_sec_私有项目"}, timeout=20)
pid = r.json().get("data", {}).get("id")
check("SEC-3 admin 建私有项目", pid, f"pid={pid}")

# user2 现在有 asset:write 平台权限，但非该项目成员 → 数据层 requireWrite 应 403
r = requests.post(f"{B}/assets/projects/{pid}/assets", headers=H(u2), json={
    "mediaType": "PROMPT", "name": "越权写入尝试", "content": "{\"body\":\"x\"}"}, timeout=20)
check("SEC-4 user2(asset:write,非成员) 写资产=403", r.status_code == 403,
      f"status={r.status_code} body={r.text[:150]}")

# user2 读该项目详情 → loadAccessible 应 403
r = requests.get(f"{B}/assets/projects/{pid}", headers=H(u2), timeout=20)
check("SEC-5 user2(非成员) 读项目=403", r.status_code == 403, f"status={r.status_code}")

# user2 尝试在他人项目里邀请成员（越权授权）→ 403
r = requests.post(f"{B}/assets/projects/{pid}/members", headers=H(u2),
                  json={"userId": 3, "role": "EDITOR"}, timeout=20)
check("SEC-6 user2 越权邀请成员=403", r.status_code == 403, f"status={r.status_code}")

# ========== 画布打通 L5/L6 真链 ==========
# admin 建画布
r = requests.post(f"{B}/canvas", headers=admin, timeout=20)
cid = r.json().get("data", {}).get("id")
check("BR-1 建画布", cid, f"cid={cid}")

# 存快照：一个 text 节点带 outputText
snap = {
    "nodes": [{"id": "node-text-1", "type": "text", "data": {"label": "扩写节点", "outputText": "古风女子，红衣执扇，立于庭院"}}],
    "edges": [], "viewport": {"x": 0, "y": 0, "zoom": 1}
}
r = requests.put(f"{B}/canvas/{cid}", headers=admin,
                 json={"name": "_phase4_bridge_画布", "snapshot": json.dumps(snap)}, timeout=20)
check("BR-2 存画布快照(text 节点带 outputText)", r.status_code == 200, f"status={r.status_code}")

# admin 建入库目标项目
r = requests.post(f"{B}/assets/projects", headers=admin, json={"name": "_phase4_bridge_项目"}, timeout=20)
bpid = r.json().get("data", {}).get("id")

# L5: canvas-import（无 mode 首次）→ created=true + PRODUCED 绑定
r = requests.post(f"{B}/assets/canvas-import", headers=admin, json={
    "canvasId": cid, "nodeId": "node-text-1", "projectId": bpid,
    "roleKeys": ["人物"], "name": "扩写产出 #1"}, timeout=20)
im = r.json().get("data", {}) if r.status_code == 200 else {}
aid = im.get("assetId") or im.get("id")
check("L5-1 canvas-import 首次入库 created", r.status_code == 200 and im.get("created") is True and aid,
      f"status={r.status_code} created={im.get('created')} aid={aid}")

# L5 重复入库（mode 空）→ duplicate 三态：created=false duplicateAssetId 回显
r = requests.post(f"{B}/assets/canvas-import", headers=admin, json={
    "canvasId": cid, "nodeId": "node-text-1", "projectId": bpid,
    "roleKeys": ["人物"], "name": "扩写产出 再入库"}, timeout=20)
im = r.json().get("data", {}) if r.status_code == 200 else {}
check("L5-2 重复入库(mode空)→duplicate", r.status_code == 200 and im.get("created") is False
      and im.get("duplicateAssetId"), f"created={im.get('created')} dup={im.get('duplicateAssetId')}")

# L5 NEW_VERSION → 在同资产建 v2
r = requests.post(f"{B}/assets/canvas-import", headers=admin, json={
    "canvasId": cid, "nodeId": "node-text-1", "projectId": bpid,
    "roleKeys": ["人物"], "name": "扩写产出 v2", "mode": "NEW_VERSION"}, timeout=20)
im = r.json().get("data", {}) if r.status_code == 200 else {}
check("L5-3 NEW_VERSION 入 v2", r.status_code == 200 and im.get("created") in (True, None) and im.get("assetId") == aid,
      f"status={r.status_code} assetId={im.get('assetId')} (期望={aid})")

# L6: resolve 当前版本 → 拿 content 快照
r = requests.post(f"{B}/assets/assets/{aid}/resolve", headers=admin, json={"version": None}, timeout=20)
rv = r.json().get("data", {}) if r.status_code == 200 else {}
check("L6-1 resolve 当前版本", r.status_code == 200 and rv.get("content"),
      f"status={r.status_code} version={rv.get('version')} contentLen={len(rv.get('content') or '')}")

# L6: resolve 锁定 v1（版本隔离）
r = requests.post(f"{B}/assets/assets/{aid}/resolve", headers=admin, json={"version": 1}, timeout=20)
rv1 = r.json().get("data", {}) if r.status_code == 200 else {}
check("L6-2 resolve 指定 v1", r.status_code == 200 and rv1.get("version") == 1,
      f"status={r.status_code} version={rv1.get('version')}")

# usages：列 PRODUCED 绑定
r = requests.get(f"{B}/assets/assets/{aid}/usages", headers=admin, timeout=20)
us = r.json().get("data", []) if r.status_code == 200 else []
has_produced = any((u.get("bindType") == "PRODUCED") for u in us)
check("L5/L6 usages 含 PRODUCED 绑定", r.status_code == 200 and has_produced,
      f"status={r.status_code} usages={len(us)} produced={'有' if has_produced else '无'}")

# ========== 收尾：revoke asset:write + 清测试数据 ==========
cur.execute("DELETE FROM role_permissions WHERE role_id=%s AND permission_id=%s",
            (user_role_id, aw_perm_id))
conn.commit()
check("teardown: 撤销 user 角色 asset:write", True, "")
cur.close(); conn.close()

# 删测试项目（级联软删）+ 画布
requests.delete(f"{B}/assets/projects/{pid}", headers=admin, timeout=20)
requests.delete(f"{B}/assets/projects/{bpid}", headers=admin, timeout=20)
requests.delete(f"{B}/canvas/{cid}", headers=admin, timeout=20)

print("\n========== 汇总 ==========")
ok = sum(1 for _, c, _ in results if c)
for n, c, d in results:
    print(f"[{'PASS' if c else 'FAIL'}] {n}  {d}")
print(f"\n通过 {ok}/{len(results)}")
sys.exit(0 if ok == len(results) else 1)

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
项目资产库 Phase4 E2E + 安全越权 IT（API 层）
覆盖关键路径 + L1-L10 联动 + 安全向量（viewer 越权写 / fileId 遍历 / 无权直访）
"""
import json, sys, time
import requests

B = "http://localhost:8080/api"
PASS = {"admin": "admin123"}
results = []  # (name, ok, detail)

def check(name, cond, detail=""):
    results.append((name, bool(cond), detail))
    print(f"[{'PASS' if cond else 'FAIL'}] {name}  {detail}")

def login(u, p):
    r = requests.post(f"{B}/auth/login", json={"username": u, "password": p}, timeout=20)
    if r.status_code != 200:
        return None, r
    d = r.json()
    return d.get("data", {}).get("accessToken"), d

def auth_h(tok):
    return {"Authorization": f"Bearer {tok}", "Content-Type": "application/json"}

# ---------- setup: admin + viewer tokens ----------
admin_tok, _ = login("admin", PASS["admin"])
check("admin login", admin_tok, f"token={'有' if admin_tok else '无'}")

# viewer = user 2 (aa64221886). 先尝试已知密码
viewer_tok = None
for pw in ["aa64221886", "admin123", "123456"]:
    t, _ = login("aa64221886", pw)
    if t:
        viewer_tok = t
        break
check("viewer(用户2) login", viewer_tok, f"pw尝试")

# viewer 无 asset:write：直访 /api/assets/projects 应 403
r = requests.get(f"{B}/assets/projects", headers=auth_h(viewer_tok), timeout=20) if viewer_tok else None
check("SEC-1 viewer 无 asset:write 直访 projects=403",
      r is not None and r.status_code == 403,
      f"status={r.status_code if r is not None else 'no viewer token'}")

H = auth_h(admin_tok)

# ---------- 1. 建项目 ----------
t0 = time.time()
r = requests.post(f"{B}/assets/projects", headers=H,
                  json={"name": "_phase4_e2e_项目", "description": "Phase4 验证"}, timeout=20)
proj = r.json().get("data") if r.status_code == 200 else None
pid = proj.get("id") if proj else None
check("1. 建项目 200", r.status_code == 200 and pid, f"status={r.status_code} pid={pid} 耗时={int((time.time()-t0)*1000)}ms")

# ---------- 2. 项目详情 / 默认五桶 ----------
r = requests.get(f"{B}/assets/projects/{pid}", headers=H, timeout=20)
d = r.json().get("data", {})
roles = d.get("narrativeRoles") or d.get("narrative_roles")
check("2. 项目详情 + 默认五桶", r.status_code == 200 and roles and len(roles) >= 5,
      f"status={r.status_code} roles={roles}")

# ---------- 3. 项目列表含新建 ----------
r = requests.get(f"{B}/assets/projects", headers=H, timeout=20)
lst = r.json().get("data", [])
# 可能是 {mine, shared} 结构
flat = []
if isinstance(lst, dict):
    for v in lst.values():
        if isinstance(v, list):
            flat += v
elif isinstance(lst, list):
    flat = lst
ids = [p.get("id") for p in flat]
check("3. 项目列表含新项目", pid in ids, f"count={len(flat)} 含本={pid in ids}")

# ---------- 4. 建文本资产（PROMPT，挂人物）----------
t0 = time.time()
r = requests.post(f"{B}/assets/projects/{pid}/assets", headers=H, json={
    "mediaType": "PROMPT", "name": "老板娘定稿提示词",
    "roleKeys": ["人物"], "content": "{\"prompt\":\"古风女子，红衣\"}",
    "tags": ["测试"]
}, timeout=20)
a = r.json().get("data") if r.status_code == 200 else None
aid = a.get("id") if a else None
check("4. 建 PROMPT 资产 200", r.status_code == 200 and aid, f"status={r.status_code} aid={aid} 耗时={int((time.time()-t0)*1000)}ms")

# ---------- 5. 双轴矩阵计数 ----------
r = requests.get(f"{B}/assets/projects/{pid}/assets/count", headers=H, timeout=20)
cnt = r.json().get("data")
check("5. 矩阵计数接口", r.status_code == 200 and cnt is not None, f"status={r.status_code} data={cnt}")

# 列表带 type/role 过滤
r = requests.get(f"{B}/assets/projects/{pid}/assets", headers=H,
                 params={"mediaType": "PROMPT", "roleKey": "人物"}, timeout=20)
items = r.json().get("data", {})
if isinstance(items, dict):
    items = items.get("records") or items.get("list") or items.get("items") or []
check("5b. 列表过滤 PROMPT+人物", r.status_code == 200 and len(items) >= 1,
      f"status={r.status_code} 命中={len(items) if isinstance(items,list) else '?'}")

# ---------- 6. 邀请成员（user2 = VIEWER）----------
r = requests.post(f"{B}/assets/projects/{pid}/members", headers=H,
                  json={"userId": 2, "role": "VIEWER"}, timeout=20)
check("6. 邀请 user2 为 VIEWER", r.status_code == 200, f"status={r.status_code} body={r.text[:200]}")

# viewer 现在 asset:write 仍无（gated admin），即便加了成员二层也过不了平台层
r = requests.get(f"{B}/assets/projects/{pid}", headers=auth_h(viewer_tok), timeout=20) if viewer_tok else None
check("6b. VIEWER(无asset:write) 访项目详情=403（双层授权平台层兜底）",
      r is not None and r.status_code == 403,
      f"status={r.status_code if r is not None else 'no tok'}")

# ---------- 7. 资产定稿 LOCK (L2) ----------
r = requests.post(f"{B}/assets/assets/{aid}/lock", headers=H, timeout=20)
d = r.json().get("data", {}) if r.status_code == 200 else {}
check("7. 定稿 LOCK (L2)", r.status_code == 200 and d.get("status") == "LOCKED",
      f"status={r.status_code} status={d.get('status')}")

# 解锁
r = requests.post(f"{B}/assets/assets/{aid}/unlock", headers=H, timeout=20)
d = r.json().get("data", {}) if r.status_code == 200 else {}
check("7b. 解锁回 DRAFT", r.status_code == 200 and d.get("status") == "DRAFT",
      f"status={r.status_code} status={d.get('status')}")

# ---------- 8. 归档 ARCHIVE (L3) ----------
r = requests.post(f"{B}/assets/assets/{aid}/archive", headers=H, timeout=20)
d = r.json().get("data", {}) if r.status_code == 200 else {}
check("8. 归档 ARCHIVE (L3)", r.status_code == 200 and d.get("status") == "ARCHIVED",
      f"status={r.status_code} status={d.get('status')}")
# 默认列表不含归档
r = requests.get(f"{B}/assets/projects/{pid}/assets", headers=H, timeout=20)
items = r.json().get("data", {})
if isinstance(items, dict):
    items = items.get("records") or items.get("list") or items.get("items") or []
archived_in_default = any((it.get("id") == aid) for it in (items or []))
check("8b. 默认列表不含归档项", not archived_in_default,
      f"归档项默认列表{'出现(BUG)' if archived_in_default else '隐藏(正确)'}")
# 取消归档
requests.post(f"{B}/assets/assets/{aid}/unarchive", headers=H, timeout=20)

# ---------- 9. 版本时间线 + 新版本（乐观锁） ----------
r = requests.get(f"{B}/assets/assets/{aid}/versions", headers=H, timeout=20)
vs = r.json().get("data", [])
check("9. 版本列表 >=1", r.status_code == 200 and len(vs) >= 1, f"versions={len(vs)}")

r = requests.post(f"{B}/assets/assets/{aid}/versions", headers=H,
                  json={"content": "{\"prompt\":\"古风女子，红衣，手持团扇\"}", "changeNote": "v2 加团扇"}, timeout=20)
nv = r.json().get("data", {}) if r.status_code == 200 else {}
check("9b. 新建版本 v2", r.status_code == 200, f"status={r.status_code} data={nv}")

# ---------- 10. 一致性包（人物类额显）----------
r = requests.put(f"{B}/assets/assets/{aid}/consistency-pack", headers=H, json={
    "standardDescription": "老板娘：红衣古风",
    "paramBaseline": "{\"style\":\"gufeng\"}"
}, timeout=20)
check("10. 一致性包保存", r.status_code == 200, f"status={r.status_code} body={r.text[:200]}")

# ---------- 11. 转让 owner (L1) ----------
r = requests.post(f"{B}/assets/projects/{pid}/transfer", headers=H, json={"toUserId": 2}, timeout=20)
check("11. 转让 owner 给 user2", r.status_code == 200, f"status={r.status_code} body={r.text[:200]}")
# 转让后 admin 应非 owner（降 editor 成员）
r = requests.get(f"{B}/assets/projects/{pid}", headers=H, timeout=20)
d = r.json().get("data", {})
check("11b. 转让后 ownerId=user2", str(d.get("ownerId")) == "2",
      f"ownerId={d.get('ownerId')}")

# ---------- 12. 删除项目（级联软删 L4）----------
# admin 现在是 editor 不能删；先转回
requests.post(f"{B}/assets/projects/{pid}/transfer", headers=auth_h(admin_tok) if False else H, json={"toUserId": 1}, timeout=20)
# 用 admin 平台 admin 旁路可删
r = requests.delete(f"{B}/assets/projects/{pid}", headers=H, timeout=20)
check("12. 删除项目 (L4 级联软删)", r.status_code == 200, f"status={r.status_code} body={r.text[:200]}")
# 再查 404
r = requests.get(f"{B}/assets/projects/{pid}", headers=H, timeout=20)
check("12b. 删后查项目=404/空", r.status_code in (404, 200) and (r.status_code == 404 or r.json().get("data") is None),
      f"status={r.status_code}")

# ---------- 安全：fileId 遍历（resolve 前过 Acl） ----------
# 用一个不存在的 asset id，应 404/403 而非泄露
r = requests.post(f"{B}/assets/assets/99999999/resolve", headers=H, json={"version": 1}, timeout=20)
check("SEC-2 resolve 不存在 asset=404/403（不泄露）", r.status_code in (403, 404),
      f"status={r.status_code}")

# ---------- summary ----------
print("\n========== 汇总 ==========")
ok = sum(1 for _, c, _ in results if c)
total = len(results)
for n, c, d in results:
    print(f"[{'PASS' if c else 'FAIL'}] {n}  {d}")
print(f"\n通过 {ok}/{total}")
sys.exit(0 if ok == total else 1)

/**
 * memory/anchors.ts —— 学习记忆：控件锚点库（升级v2 FR-110~113，ADR-004）
 * 锚点 = 某应用某界面上一个控件的「语义名 + 归一化坐标 + 已验证的执行方式」。
 * 安全：不存截图/界面文本；损坏文件作废重建（不信任恢复）；stdio MCP 天然串行，无并发写锁。
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync, renameSync, copyFileSync, rmSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { randomBytes } from "node:crypto";
import { configDir } from "../safety/config.js";

export interface Anchor {
  id: string;
  /** 窗口指纹：标题@客户区WxH */
  windowFingerprint: string;
  /** 指纹里的客户区尺寸（毫秒级解析用） */
  clientW: number;
  clientH: number;
  semanticName: string;
  /** 归一化坐标（客户区，0~1）——窗口缩放后仍可用 */
  relX: number;
  relY: number;
  /** 上次成功方式：memory|uia|postmessage|sendinput */
  method: string;
  /** 验证截图区域哈希（仅哈希串，绝不存图） */
  verifyHash: string;
  okCount: number;
  failStreak: number;
  lastOkAt: string;
}

interface AnchorFile {
  version: number;
  anchors: Anchor[];
}

const MAX_FILE_BYTES = 1024 * 1024; // 单 app 1MB，超限 LRU 淘汰（plan 运维容量预案）
const FP_SIZE_TOLERANCE = 0.1; // 窗口尺寸差 ≤10% 仍视为同一指纹

function memoryDir(): string {
  return join(configDir(), "memory");
}

function fileOf(app: string): string {
  // app 可能含路径非法字符，做最小清洗
  const safe = app.replace(/[^\w一-龥.-]/g, "_");
  return join(memoryDir(), `${safe}.json`);
}

/** 损坏 → 作废重建（安全策略：不信任恢复） */
function load(app: string): AnchorFile {
  const p = fileOf(app);
  if (!existsSync(p)) return { version: 1, anchors: [] };
  try {
    const raw = JSON.parse(readFileSync(p, "utf-8")) as AnchorFile;
    if (!Array.isArray(raw.anchors)) throw new Error("bad");
    return raw;
  } catch {
    rmSync(p, { force: true });
    return { version: 1, anchors: [] };
  }
}

/** 原子写：tmp + rename（断电不写坏原文件）；Windows 杀软占用 rename 偶发 EPERM → 退 copy+rm */
function store(app: string, data: AnchorFile): void {
  mkdirSync(memoryDir(), { recursive: true });
  const p = fileOf(app);
  const tmp = `${p}.${randomBytes(4).toString("hex")}.tmp`;
  writeFileSync(tmp, JSON.stringify(data), "utf-8");
  try {
    renameSync(tmp, p);
  } catch {
    copyFileSync(tmp, p);
    rmSync(tmp, { force: true });
  }
}

/** LRU 淘汰：超 1MB 时按 lastOkAt 最旧优先删，直到达标 */
function evictIfNeeded(data: AnchorFile): AnchorFile {
  let size = JSON.stringify(data).length;
  if (size <= MAX_FILE_BYTES) return data;
  const sorted = [...data.anchors].sort((a, b) => a.lastOkAt.localeCompare(b.lastOkAt));
  const dropped = new Set<string>();
  for (const a of sorted) {
    if (size <= MAX_FILE_BYTES) break;
    dropped.add(a.id);
    size -= JSON.stringify(a).length + 3;
  }
  return { version: data.version, anchors: data.anchors.filter((a) => !dropped.has(a.id)) };
}

/** 由窗口标题+客户区尺寸构造指纹 */
export function fingerprint(title: string, clientW: number, clientH: number): string {
  return `${title}@${clientW}x${clientH}`;
}

/** 同 app + 指纹尺寸容差 + 语义名模糊包含 → 命中（FR-111） */
export function hit(app: string, fp: string, semanticName: string): Anchor | null {
  const m = fp.match(/@(\d+)x(\d+)$/);
  const [w, h] = m ? [Number(m[1]), Number(m[2])] : [0, 0];
  const name = semanticName.trim().toLowerCase();
  const cands = load(app).anchors.filter((a) => {
    const dw = Math.abs(a.clientW - w) / Math.max(a.clientW, 1);
    const dh = Math.abs(a.clientH - h) / Math.max(a.clientH, 1);
    return dw <= FP_SIZE_TOLERANCE && dh <= FP_SIZE_TOLERANCE && a.semanticName.toLowerCase().includes(name);
  });
  // 多条命中取成功次数最多的（语义名漂移分裂时优选主力锚点）
  return cands.sort((a, b) => b.okCount - a.okCount)[0] ?? null;
}

/** upsert 锚点（键=指纹+语义名精确相同则覆盖坐标；成功操作后调用，FR-110） */
export function save(app: string, a: Omit<Anchor, "id" | "okCount" | "failStreak" | "lastOkAt"> & Partial<Anchor>): Anchor {
  const data = load(app);
  const existing = data.anchors.find(
    (x) => x.windowFingerprint === a.windowFingerprint && x.semanticName === a.semanticName
  );
  let saved: Anchor;
  if (existing) {
    Object.assign(existing, a, { okCount: (existing.okCount ?? 0) + 1, failStreak: 0, lastOkAt: new Date().toISOString() });
    saved = existing;
  } else {
    saved = {
      id: randomBytes(4).toString("hex"),
      okCount: 1,
      failStreak: 0,
      lastOkAt: new Date().toISOString(),
      ...a,
    } as Anchor;
    data.anchors.push(saved);
  }
  store(app, evictIfNeeded(data));
  return saved;
}

/** 命中但验证失败：failStreak+1，连续 2 次删除并返回 true（表示已作废，FR-112） */
export function fail(app: string, id: string): boolean {
  const data = load(app);
  const idx = data.anchors.findIndex((a) => a.id === id);
  if (idx < 0) return true;
  const a = data.anchors[idx];
  a.failStreak = (a.failStreak ?? 0) + 1;
  let gone = false;
  if (a.failStreak >= 2) {
    data.anchors.splice(idx, 1);
    gone = true;
  }
  store(app, data);
  return gone;
}

/** 列出某 app（或全部）锚点（FR-113 memory_list） */
export function list(app?: string): { app: string; anchors: Anchor[] }[] {
  const apps = app ? [app] : listApps();
  return apps.map((a) => ({ app: a, anchors: load(a).anchors }));
}

function listApps(): string[] {
  const dir = memoryDir();
  if (!existsSync(dir)) return [];
  return readdirSync(dir)
    .filter((f) => f.endsWith(".json"))
    .map((f) => f.slice(0, -5));
}

/** 删除指定锚点或清空某 app（FR-113 memory_forget）。返回删除条数 */
export function forget(app: string, id?: string, all = false): number {
  const data = load(app);
  if (all || !id) {
    store(app, { version: 1, anchors: [] });
    return data.anchors.length;
  }
  const before = data.anchors.length;
  const next = data.anchors.filter((a) => a.id !== id);
  store(app, { version: data.version, anchors: next });
  return before - next.length;
}

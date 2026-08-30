/**
 * memory.test.ts —— 锚点库单测（FR-110/111/112/113 / AC-110/111/112/113）
 * 每个 case 用独立临时 CU_SKILL_HOME，互不污染。
 */
import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { mkdtempSync, rmSync, writeFileSync, mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import * as anchors from "../src/memory/anchors.js";

let home: string;
beforeEach(() => {
  home = mkdtempSync(join(tmpdir(), "cu-mem-"));
  process.env.CU_SKILL_HOME = home;
});
afterEach(() => {
  delete process.env.CU_SKILL_HOME;
  rmSync(home, { recursive: true, force: true });
});

const base = {
  windowFingerprint: "Voice to Text@1046x639",
  clientW: 1046,
  clientH: 639,
  semanticName: "实时转写标签",
  relX: 0.851,
  relY: 0.167,
  method: "sendinput",
  verifyHash: "deadbeef",
};

describe("锚点沉淀与命中（FR-110/111）", () => {
  it("AC-110 成功后写入锚点且字段完整", () => {
    const a = anchors.save("v2t", base);
    const all = anchors.list("v2t")[0].anchors;
    expect(all).toHaveLength(1);
    expect(a.okCount).toBe(1);
    expect(a.failStreak).toBe(0);
    expect(a.id).toBeTruthy();
  });

  it("AC-111 相同键再次 save 是 upsert（okCount 累加而非分裂）", () => {
    anchors.save("v2t", base);
    const a2 = anchors.save("v2t", { ...base, relX: 0.9 });
    expect(anchors.list("v2t")[0].anchors).toHaveLength(1);
    expect(a2.okCount).toBe(2);
    expect(a2.relX).toBe(0.9); // 坐标已更新
  });

  it("AC-111 命中查询：同 app + 尺寸容差 + 语义名模糊包含", () => {
    anchors.save("v2t", base);
    // 尺寸差 <10% 仍命中
    const hit1 = anchors.hit("v2t", "Voice to Text@1100x670", "实时转写");
    expect(hit1?.id).toBeTruthy();
    // 语义名更短（模糊包含）
    expect(anchors.hit("v2t", base.windowFingerprint, "实时转写标签")).toBeTruthy();
  });

  it("未命中：尺寸超容差 / 语义名不含 / 不同 app", () => {
    anchors.save("v2t", base);
    expect(anchors.hit("v2t", "Voice to Text@1200x639", "实时转写")).toBeNull(); // 尺寸差 14.7%
    expect(anchors.hit("v2t", base.windowFingerprint, "扬声器")).toBeNull();
    expect(anchors.hit("别的app", base.windowFingerprint, "实时转写")).toBeNull();
  });

  it("多条命中取 okCount 最大者（语义名漂移分裂场景）", () => {
    anchors.save("v2t", { ...base, semanticName: "实时转写标签" });
    anchors.save("v2t", { ...base, semanticName: "实时转写tab" });
    anchors.save("v2t", { ...base, semanticName: "实时转写tab" }); // okCount=2
    const h = anchors.hit("v2t", base.windowFingerprint, "实时转写");
    expect(h?.semanticName).toBe("实时转写tab");
  });
});

describe("失效自愈（FR-112 / AC-112）", () => {
  it("失败 1 次保留，连续 2 次删除并返回 true", () => {
    const a = anchors.save("v2t", base);
    expect(anchors.fail("v2t", a.id)).toBe(false); // failStreak=1 仍在
    expect(anchors.list("v2t")[0].anchors).toHaveLength(1);
    expect(anchors.fail("v2t", a.id)).toBe(true); // 达 2 删除
    expect(anchors.list("v2t")[0].anchors).toHaveLength(0);
  });

  it("成功操作清零 failStreak（半失效恢复）", () => {
    const a = anchors.save("v2t", base);
    anchors.fail("v2t", a.id);
    const a2 = anchors.save("v2t", base);
    expect(a2.failStreak).toBe(0);
    expect(a2.okCount).toBe(2);
  });

  it("AC-112 错位锚点被覆盖更新：save 新坐标后 hit 返回新坐标", () => {
    anchors.save("v2t", base);
    anchors.save("v2t", { ...base, relX: 0.5, relY: 0.5 });
    const h = anchors.hit("v2t", base.windowFingerprint, "实时转写");
    expect(h?.relX).toBe(0.5);
  });
});

describe("运维工具（FR-113 / AC-113）", () => {
  it("memory_forget 指定 id 删除一条", () => {
    const a = anchors.save("v2t", base);
    expect(anchors.forget("v2t", a.id)).toBe(1);
    expect(anchors.hit("v2t", base.windowFingerprint, "实时转写")).toBeNull();
  });

  it("memory_forget all 清空（批量边界）", () => {
    anchors.save("v2t", base);
    anchors.save("v2t", { ...base, semanticName: "b", windowFingerprint: "x@1x1", clientW: 1, clientH: 1 });
    expect(anchors.forget("v2t", undefined, true)).toBe(2);
    expect(anchors.list("v2t")[0].anchors).toHaveLength(0);
  });

  it("list() 无参列出全部 app", () => {
    anchors.save("v2t", base);
    anchors.save("notepad", { ...base, semanticName: "编辑区" });
    expect(anchors.list().map((x) => x.app).sort()).toEqual(["notepad", "v2t"]);
  });
});

describe("健壮性", () => {
  it("损坏 JSON 作废重建（不信任恢复）", () => {
    anchors.save("v2t", base);
    const { writeFileSync: wf } = { writeFileSync };
    wf(join(home, "memory", "v2t.json"), "{broken", "utf-8");
    expect(anchors.list("v2t")[0].anchors).toHaveLength(0);
    // 可继续写入
    anchors.save("v2t", base);
    expect(anchors.list("v2t")[0].anchors).toHaveLength(1);
  });

  it("LRU 淘汰：超 1MB 删最旧（容量预案）", () => {
    // 直接构造超限文件（避免逐条 save 的 O(n²) 磁盘写）：2000 条 × ~600B ≈ 1.2MB
    const bigHash = "h".repeat(500);
    const arr = Array.from({ length: 2000 }, (_, i) => ({
      id: `id${i}`,
      windowFingerprint: base.windowFingerprint,
      clientW: base.clientW,
      clientH: base.clientH,
      semanticName: `控件${i}`,
      relX: 0.5,
      relY: 0.5,
      method: "sendinput",
      verifyHash: bigHash,
      okCount: 1,
      failStreak: 0,
      lastOkAt: new Date(2026, 0, 1, 0, 0, i).toISOString(), // i 小者最旧
    }));
    mkdirSync(join(home, "memory"), { recursive: true });
    writeFileSync(join(home, "memory", "v2t.json"), JSON.stringify({ version: 1, anchors: arr }), "utf-8");
    // save 一条触发淘汰路径
    anchors.save("v2t", { ...base, semanticName: "新控件" });
    const json = JSON.stringify(anchors.list("v2t")[0]);
    expect(json.length).toBeLessThanOrEqual(1024 * 1024 + 4096);
    expect(json).not.toContain("控件0\""); // 最旧的已删
    expect(json).toContain("控件1999\""); // 最新的保留
  });
});

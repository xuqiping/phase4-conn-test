// Step 8 驾驶舱静态版测试：HUD 四指标 / 进度读状态机 / 虚拟列表窗口化（PERF-03）。
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Dashboard from "./dashboard/Dashboard";
import { resetProjectStore, useProjectStore } from "../stores/project";
import type { StateDto } from "../lib/ipc";

vi.mock("../lib/ipc", () => ({
  onState: async () => () => {},
  ipc: {},
  errMessage: String,
}));

function fakeSnapshot(donePhases: number): StateDto {
  const keys = ["idea", "spec", "plan", "build", "accept", "deploy"];
  return {
    project_id: 1,
    phase: keys[donePhases],
    workflow_version: "1.20",
    phases: keys.map((k, i) => ({
      key: k,
      label: k,
      status: i < donePhases ? "done" : i === donePhases ? "active" : "todo",
    })),
    pending_gates: [],
    allowed_next: [],
    warning: null,
  };
}

describe("驾驶舱静态版（Step 8）", () => {
  beforeEach(() => resetProjectStore());
  afterEach(cleanup);

  it("AC-043：HUD 四指标就位；进度来自状态机快照", async () => {
    useProjectStore.setState({ snapshot: fakeSnapshot(2) }); // 过 2/6 阶段
    render(<Dashboard />);
    for (const label of ["进度", "缺陷", "覆盖率", "消耗"]) {
      expect(screen.getByTestId(`hud-${label}`)).toBeTruthy();
    }
    expect(screen.getByTestId("hud-进度").textContent).toContain("33"); // 2/6≈33%
    // 未接通指标显示占位而非假数字
    expect(screen.getByTestId("hud-消耗").textContent).toContain("待 P02");
  });

  it("无项目时进度为 0，页面不崩", () => {
    render(<Dashboard />);
    expect(screen.getByTestId("hud-进度").textContent).toContain("0");
  });

  it("PERF-03：1000 条 mock 只渲染可视窗口", async () => {
    render(<Dashboard />);
    const list = screen.getByTestId("task-list");
    await waitFor(() => expect(list.textContent).toContain("示例任务 1"));
    // 渲染行数 << 1000（可视窗口 + 缓冲 ≈ 20 行）
    const rendered = list.querySelectorAll("[class*='border-b']").length;
    expect(rendered).toBeLessThan(50);
    expect(rendered).toBeGreaterThan(5);
  });
});

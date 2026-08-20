// P05 S7 驾驶舱真实数据测试：HUD 读 tasks/rounds，进度读状态机快照。
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Dashboard from "./dashboard/Dashboard";
import { resetProjectStore, useProjectStore } from "../stores/project";
import type { StateDto, TaskDto, RoundDto } from "../lib/ipc";

const mockTasks: TaskDto[] = [
  { id: 1, round_id: 1, chunk_no: 1, title: "任务 A", status: "done", instructions: "", cost_cents: 15 },
  { id: 2, round_id: 1, chunk_no: 2, title: "任务 B", status: "failed", instructions: "", cost_cents: 0 },
  { id: 3, round_id: 1, chunk_no: 3, title: "任务 C", status: "pending", instructions: "", cost_cents: 0 },
];

const mockRounds: RoundDto[] = [
  { id: 1, seq: 1, title: "第一轮", status: "open", total_tasks: 3, done_tasks: 1 },
];

vi.mock("../lib/ipc", () => ({
  onState: async () => () => {},
  ipc: {
    listTasks: vi.fn(async () => mockTasks),
    listRounds: vi.fn(async () => mockRounds),
  },
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

describe("驾驶舱真实数据版（P05 S7）", () => {
  beforeEach(() => resetProjectStore());
  afterEach(cleanup);

  it("AC-043：HUD 四指标就位；进度来自状态机快照，缺陷/覆盖率/消耗来自 tasks", async () => {
    useProjectStore.setState({ snapshot: fakeSnapshot(2), currentId: 1 }); // 过 2/6 阶段
    render(<Dashboard />);
    for (const label of ["进度", "缺陷", "覆盖率", "消耗"]) {
      expect(screen.getByTestId(`hud-${label}`)).toBeTruthy();
    }
    expect(screen.getByTestId("hud-进度").textContent).toContain("33"); // 2/6≈33%
    await waitFor(() => {
      const text = screen.getByTestId("hud-消耗").textContent;
      expect(text).toContain("15"); // sum cost_cents
      expect(text).toContain("¢");
    });
    await waitFor(() => {
      expect(screen.getByTestId("hud-覆盖率").textContent).toContain("33"); // 1/3
    });
    await waitFor(() => {
      expect(screen.getByTestId("hud-缺陷").textContent).toContain("1");
    });
  });

  it("无项目时进度为 0，页面不崩", () => {
    render(<Dashboard />);
    expect(screen.getByTestId("hud-进度").textContent).toContain("0");
  });

  it("PERF-03：任务列表渲染真实任务", async () => {
    useProjectStore.setState({ currentId: 1 });
    render(<Dashboard />);
    const list = screen.getByTestId("task-list");
    await waitFor(() => expect(list.textContent).toContain("任务 A"));
    await waitFor(() => expect(list.textContent).toContain("任务 B"));
  });
});

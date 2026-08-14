// applySnapshot 归位策略（交叉审查做偏-1）：阶段真变才归位，不拽走用户的视图。
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { applySnapshot, useProjectStore } from "./project";
import { useUiStore } from "./ui";

const stateOf = (phase: string) => ({
  project_id: 1,
  phase,
  workflow_version: "1.20",
  phases: [],
  pending_gates: [],
  allowed_next: [],
  warning: null,
});

beforeEach(() => {
  useProjectStore.setState({
    projects: [], currentId: null, snapshot: null, error: null, wizardOpen: false,
  });
  useUiStore.setState({ density: "comfort", view: "dashboard", rightTab: "logs" });
});
afterEach(() => {});

describe("applySnapshot 归位策略（Step 0）", () => {
  it("首次快照（prev 为空）→ 视图归位到内核阶段", () => {
    applySnapshot(stateOf("spec"));
    expect(useUiStore.getState().view).toBe("spec");
  });

  it("阶段真变 → 视图归位到新阶段（联动点 1）", () => {
    applySnapshot(stateOf("spec"));
    useUiStore.setState({ view: "dashboard" });
    applySnapshot(stateOf("plan"));
    expect(useUiStore.getState().view).toBe("plan");
  });

  it("阶段未变（用户在看驾驶舱，内核推门禁更新）→ 不拽回阶段视图", () => {
    applySnapshot(stateOf("spec"));
    useUiStore.setState({ view: "dashboard" }); // 用户手动切驾驶舱
    applySnapshot(stateOf("spec"));
    expect(useUiStore.getState().view).toBe("dashboard");
  });

  it("快照带 warning → 进错误 toast 通道", () => {
    applySnapshot({ ...stateOf("spec"), warning: "工作流文件损坏，已用内置默认" });
    expect(useProjectStore.getState().error).toContain("内置默认");
  });
});

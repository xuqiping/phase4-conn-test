// P01 骨架渲染与联动测试。
// Step 7 起前端接 IPC：用 vi.mock 替换 lib/ipc 为内存假内核（语义对齐 default.yaml）。
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App";
import { resetProjectStore } from "./stores/project";
import { useUiStore } from "./stores/ui";

// ---------- 内存假内核（对齐 default.yaml：六阶段/四门禁/回退边） ----------

const PHASES = ["idea", "spec", "plan", "build", "accept", "deploy"];
const LABELS: Record<string, string> = {
  idea: "想法", spec: "需求", plan: "计划", build: "建造", accept: "验收", deploy: "部署",
};
const EDGES: Record<string, string[]> = {
  idea: ["spec"], spec: ["plan", "idea"], plan: ["build", "spec"],
  build: ["accept"], accept: ["deploy", "build"], deploy: [],
};
const EDGE_GATE: Record<string, string> = {
  "spec>plan": "requirement_confirm", "plan>build": "kickoff",
  "build>accept": "security", "accept>deploy": "release",
};
const GATE_LABEL: Record<string, string> = {
  requirement_confirm: "需求确认", kickoff: "开工确认", security: "安全检查", release: "上线确认",
};

interface FakeProject { id: number; name: string; scale: string; phase: string; gates: Set<string> }
const fake = { projects: [] as FakeProject[], nextId: 1 };

function stateDto(p: FakeProject) {
  const order = PHASES.indexOf(p.phase);
  return {
    project_id: p.id,
    phase: p.phase,
    workflow_version: "1.20",
    phases: PHASES.map((k, i) => ({
      key: k, label: LABELS[k],
      status: (i < order ? "done" : i === order ? "active" : "todo") as "done" | "active" | "todo",
    })),
    pending_gates: EDGES[p.phase]
      .map((to) => EDGE_GATE[`${p.phase}>${to}`])
      .filter(Boolean)
      .map((g) => ({ key: g, label: GATE_LABEL[g], checklist: ["检查项"], passed: p.gates.has(g) })),
    allowed_next: EDGES[p.phase],
    warning: null,
  };
}

vi.mock("./lib/ipc", async (importOriginal) => ({
  // errMessage 用真实实现（Step0 大白话翻译是测试对象，不能 mock 掉）
  ...(await importOriginal<typeof import("./lib/ipc")>()),
  ipc: {
    listProjects: async () =>
      fake.projects.map((p) => ({
        id: p.id, name: p.name, path: `/fake/${p.name}`, scale: p.scale, current_phase: p.phase,
      })),
    createProject: async (name: string, _parentDir: string | null, scale: string) => {
      const p: FakeProject = { id: fake.nextId++, name, scale, phase: "idea", gates: new Set() };
      fake.projects.push(p);
      return { id: p.id, name: p.name, path: `/fake/${name}`, scale, current_phase: "idea" };
    },
    getState: async (id: number) => stateDto(fake.projects.find((p) => p.id === id)!),
    transition: async (id: number, to: string) => {
      const p = fake.projects.find((x) => x.id === id)!;
      if (!EDGES[p.phase].includes(to)) {
        throw { code: "TRANSITION", message: `不允许从「${LABELS[p.phase]}」直接到「${LABELS[to]}」（越阶段或不存在的转移）` };
      }
      const gate = EDGE_GATE[`${p.phase}>${to}`];
      if (gate && !p.gates.has(gate)) {
        throw { code: "TRANSITION", message: `门禁「${GATE_LABEL[gate]}」未通过：检查项` };
      }
      p.phase = to;
      return stateDto(p);
    },
    passGate: async (id: number, gate: string) => {
      const p = fake.projects.find((x) => x.id === id)!;
      p.gates.add(gate);
      return stateDto(p);
    },
  },
  onState: async () => () => {},
}));

// ---------- 用例 ----------

async function renderAndSettle() {
  render(<App />);
  await waitFor(() => expect(screen.queryByText("创建中…")).toBeNull());
}

describe("App 骨架", () => {
  beforeEach(() => {
    fake.projects = [];
    fake.nextId = 1;
    resetProjectStore();
    useUiStore.setState({ density: "comfort", view: "dashboard", rightTab: "logs" });
  });
  afterEach(cleanup);

  it("AC-020 应用可渲染", async () => {
    await renderAndSettle();
    expect(screen.getByText("DevPilot")).toBeTruthy();
  });

  it("三栏骨架：顶栏/左导航/中栏/右栏五 Tab 就位（Step 3）", async () => {
    await renderAndSettle();
    for (const id of ["topbar", "sidebar", "center", "rightbar"]) {
      expect(screen.getByTestId(id)).toBeTruthy();
    }
    for (const label of ["规格", "变更", "日志", "预览", "文件"]) {
      expect(screen.getByRole("tab", { name: label })).toBeTruthy();
    }
  });

  it("密度切换：舒适 ⇄ 紧凑（Step 3）", async () => {
    const { container } = render(<App />);
    await waitFor(() => screen.getByTestId("topbar"));
    const root = container.firstElementChild as HTMLElement;
    expect(root.getAttribute("data-density")).toBe("comfort");
    fireEvent.click(screen.getByRole("button", { name: "切换界面密度" }));
    expect(root.getAttribute("data-density")).toBe("compact");
  });

  it("LI1 视图浏览：点导航「需求」→ 中栏切需求视图（Step 5）", async () => {
    await renderAndSettle();
    fireEvent.click(within(screen.getByTestId("sidebar")).getByRole("button", { name: /需求/ }));
    expect(screen.getByTestId("view-spec")).toBeTruthy();
  });

  // ---- Step 7：前后端接通 ----

  it("无项目时自动弹出创建向导", async () => {
    await renderAndSettle();
    expect(screen.getByTestId("create-project-wizard")).toBeTruthy();
  });

  it("创建 L2 项目 → 管道条停「想法」+ 中栏归位想法视图（Step 7 验证）", async () => {
    await renderAndSettle();
    fireEvent.change(screen.getByPlaceholderText("例如：记账小助手"), {
      target: { value: "演示项目" },
    });
    fireEvent.click(screen.getByRole("button", { name: "创建项目" }));
    await waitFor(() =>
      expect(screen.queryByTestId("create-project-wizard")).toBeNull(),
    );
    // 管道条停想法
    const pipeline = screen.getByTestId("pipeline");
    expect(
      within(pipeline).getByRole("button", { name: /想法/ }).getAttribute("aria-current"),
    ).toBe("step");
    // 中栏归位（联动点 1）
    expect(screen.getByTestId("view-idea")).toBeTruthy();
    // 顶栏项目切换显示项目名
    expect(screen.getByRole("button", { name: /演示项目/ })).toBeTruthy();
  });

  it("门禁拦截大白话提示，确认后可推进（AC-032 UI 层）", async () => {
    await renderAndSettle();
    fireEvent.change(screen.getByPlaceholderText("例如：记账小助手"), { target: { value: "演示" } });
    fireEvent.click(screen.getByRole("button", { name: "创建项目" }));
    await waitFor(() => screen.getByTestId("phase-bar"));

    // idea → spec（无门禁）
    fireEvent.click(screen.getByRole("button", { name: /推进到「需求」/ }));
    await waitFor(() => screen.getByTestId("view-spec"));

    // spec → plan 被「需求确认」拦：大白话 toast
    fireEvent.click(screen.getByRole("button", { name: /推进到「计划」/ }));
    await waitFor(() => expect(screen.getByRole("alert").textContent).toContain("需求确认"));
    expect(screen.getByTestId("view-spec")).toBeTruthy(); // 仍在需求

    // 确认门禁后再推进 → 成功
    fireEvent.click(screen.getByRole("button", { name: /确认「需求确认」/ }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /推进到「计划」/ })).toBeTruthy(),
    );
    fireEvent.click(screen.getByRole("button", { name: "知道了" }));
    fireEvent.click(screen.getByRole("button", { name: /推进到「计划」/ }));
    await waitFor(() => screen.getByTestId("view-plan"));
  });

  it("AC-052 UI 层：重开后从快照恢复阶段（管道条停「计划」）", async () => {
    // 预置：假内核里已有一个推进到 plan 的项目（模拟上次会话）
    fake.projects.push({ id: 1, name: "旧项目", scale: "L2", phase: "plan", gates: new Set(["requirement_confirm"]) });
    fake.nextId = 2;
    await renderAndSettle();
    const pipeline = screen.getByTestId("pipeline");
    await waitFor(() =>
      expect(
        within(pipeline).getByRole("button", { name: /计划/ }).getAttribute("aria-current"),
      ).toBe("step"),
    );
    expect(screen.getByTestId("view-plan")).toBeTruthy();
  });

  it("LI1 反向：手动切 Tab 后再切视图 → Tab 归位该视图默认", async () => {
    await renderAndSettle();
    fireEvent.click(screen.getByRole("tab", { name: "文件" }));
    expect(screen.getByRole("tab", { name: "文件" }).getAttribute("aria-selected")).toBe("true");
    fireEvent.click(within(screen.getByTestId("sidebar")).getByRole("button", { name: /验收/ }));
    expect(screen.getByTestId("view-accept")).toBeTruthy();
    expect(screen.getByRole("tab", { name: "预览" }).getAttribute("aria-selected")).toBe("true");
  });
});

// P06 S9 Accept 整合视图测试：发布按钮门禁（全 pass/na 才可用，AC-036/FR-033）。
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Accept from "./Accept";
import { resetProjectStore, useProjectStore } from "../stores/project";
import type { AcceptanceItemDto, ProjectDto, SecurityScanDto, StateDto } from "../lib/ipc";

const mockItems = vi.fn(async (_id?: number): Promise<AcceptanceItemDto[]> => []);
const runSecurityScan = vi.fn(async (_id?: number): Promise<SecurityScanDto> => ({
  status: "pass",
  findings: [],
  gate_passed: true,
  warning: null,
}));
const runSmokeCheck = vi.fn(async (_id?: number) => ({
  passed: 0,
  failed: 0,
  skipped: 0,
  warning: null,
}));
const requestRelease = vi.fn(async () => undefined);

vi.mock("../lib/ipc", () => ({
  onState: async () => () => {},
  ipc: {
    getAcceptanceChecklist: (...a: unknown[]) => mockItems(...(a as [number])),
    updateAcceptanceItem: vi.fn(),
    regenerateAcceptanceChecklist: vi.fn(),
    runSecurityScan: (id: number) => runSecurityScan(id),
    runSmokeCheck: (...a: unknown[]) => runSmokeCheck(...(a as [number])),
  },
  errMessage: String,
}));

function item(id: number, tc: string, status: string): AcceptanceItemDto {
  return {
    id,
    project_id: 1,
    source_file: "t.md",
    tc_id: tc,
    title: "用例",
    steps: "s",
    expected: "e",
    method: "manual",
    status: status as AcceptanceItemDto["status"],
    sort_order: id,
  };
}

const proj: ProjectDto = { id: 1, name: "p", path: "/tmp/p", scale: "L2", current_phase: "accept" };

function fakeSnapshot(): StateDto {
  return {
    project_id: 1,
    phase: "accept",
    workflow_version: "1.20",
    phases: [],
    pending_gates: [],
    allowed_next: [],
    warning: null,
  };
}

describe("Accept 整合视图（P06 S9）", () => {
  beforeEach(() => {
    resetProjectStore();
    mockItems.mockClear();
    useProjectStore.setState({
      projects: [proj],
      currentId: 1,
      snapshot: fakeSnapshot(),
      requestRelease,
    });
  });
  afterEach(cleanup);

  it("存在未通过项时发布禁用；全部 pass/na 后启用", async () => {
    mockItems.mockResolvedValueOnce([item(1, "TC-01", "pass"), item(2, "TC-02", "fail")]);
    render(<Accept />);
    await waitFor(() =>
      expect((screen.getByTestId("btn-release") as HTMLButtonElement).disabled).toBe(true),
    );

    mockItems.mockResolvedValue([item(1, "TC-01", "pass"), item(2, "TC-02", "na")]);
    useProjectStore.setState({ snapshot: fakeSnapshot() });
    cleanup();
    render(<Accept />);
    await waitFor(() =>
      expect((screen.getByTestId("btn-release") as HTMLButtonElement).disabled).toBe(false),
    );
  });

  it("安全面板与预览窗格就位（AC-044/AC-057）", async () => {
    mockItems.mockResolvedValue([]);
    render(<Accept />);
    expect(screen.getByTestId("security-panel")).toBeTruthy();
    expect(screen.getByTestId("preview-pane")).toBeTruthy();
    expect(screen.getByTestId("acceptance-checklist")).toBeTruthy();
  });
});

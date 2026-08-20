// P06 S4/S5 Accept 视图安全面板测试：扫描触发、findings 展示、脱敏 message。
import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Accept from "./Accept";
import { resetProjectStore, useProjectStore } from "../stores/project";
import type { ProjectDto, SecurityScanDto } from "../lib/ipc";

const mockScan: SecurityScanDto = {
  status: "fail",
  gate_passed: false,
  warning: null,
  findings: [
    {
      severity: "high",
      category: "secret",
      message: "疑似 OpenAI/Anthropic API Key 硬编码 → sk-abcde...3456",
      file: "config.ts",
      line: 3,
      snippet: "const KEY = 'sk-abcdefg...';",
      suggestion: "将密钥移入环境变量",
    },
  ],
};

const runSecurityScan = vi.fn(async (_id: number) => mockScan);

vi.mock("../lib/ipc", () => ({
  onState: async () => () => {},
  ipc: { runSecurityScan: (id: number) => runSecurityScan(id) },
  errMessage: String,
}));

const proj: ProjectDto = { id: 1, name: "p", path: "/tmp/p", scale: "L2", current_phase: "build" };

describe("Accept 安全面板（P06 S4/S5）", () => {
  beforeEach(() => {
    resetProjectStore();
    runSecurityScan.mockClear();
    useProjectStore.setState({ projects: [proj], currentId: 1 });
  });
  afterEach(cleanup);

  it("AC-044：点扫描 → 展示结果与 findings，L2 提示强制卡点", async () => {
    render(<Accept />);
    expect(screen.getByTestId("view-accept").textContent).toContain("强制卡点");
    fireEvent.click(screen.getByTestId("btn-security-scan"));
    await waitFor(() => expect(screen.getByTestId("scan-result")).toBeTruthy());
    expect(screen.getAllByTestId("finding-row").length).toBe(1);
    expect(screen.getByTestId("scan-result").textContent).toContain("未通过");
  });

  it("AC-044：扫描报错走大白话展示，不甩原始堆栈", async () => {
    runSecurityScan.mockRejectedValueOnce(new Error("boom"));
    render(<Accept />);
    fireEvent.click(screen.getByTestId("btn-security-scan"));
    await waitFor(() => expect(screen.getByTestId("scan-error")).toBeTruthy());
    expect(screen.getByTestId("scan-error").textContent).toContain("boom");
  });
});

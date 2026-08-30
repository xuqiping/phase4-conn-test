// P07 S6（FR-026/AC-029 展示侧）：error 态高亮一键重启；卸载需确认；日志抽屉。
import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import McpPanel from "./McpPanel";
import type { McpServerDto } from "../../lib/ipc";

const row = (over: Partial<McpServerDto>): McpServerDto => ({
  id: 1, name: "filesystem", description: "文件读写", command: "npx",
  status: "running", enabled: true, restart_count: 0, last_error: "",
  ...over,
});

const listMcpServers = vi.fn(async () => [] as McpServerDto[]);
const mcpRestart = vi.fn(async (_id: number) => "已重启");
const mcpStop = vi.fn(async (_id: number) => "已停止");
const mcpUninstall = vi.fn(async (_id: number) => "已卸载");
const mcpLogs = vi.fn(async (_id: number) => [] as string[]);
vi.mock("../../lib/ipc", () => ({
  ipc: {
    listMcpServers: () => listMcpServers(),
    mcpRestart: (id: number) => mcpRestart(id),
    mcpStop: (id: number) => mcpStop(id),
    mcpUninstall: (id: number) => mcpUninstall(id),
    mcpLogs: (id: number) => mcpLogs(id),
    mcpStart: vi.fn(),
  },
}));

afterEach(cleanup);

describe("McpPanel（AC-029 展示侧）", () => {
  it("error 态显示红色徽章 + 一键重启按钮；running 态只有停止/重启", async () => {
    listMcpServers.mockResolvedValue([
      row({ id: 1, name: "bad", status: "error", last_error: "进程异常退出（退出码 Some(1)）" }),
      row({ id: 2, name: "good", status: "running" }),
    ]);
    render(<McpPanel />);
    await waitFor(() => expect(screen.getByTestId("mcp-row-bad")).toBeTruthy());
    expect(screen.getByTestId("mcp-status-bad").textContent).toContain("出错");
    expect(screen.getByTestId("mcp-status-bad").textContent).not.toContain("运行中");
    expect(screen.getByTestId("mcp-restart-bad")).toBeTruthy();
    expect(screen.queryByTestId("mcp-restart-good")).toBeNull();
    // 错误原因直接可见（大白话）
    expect(screen.getByText(/异常退出/)).toBeTruthy();
  });

  it("一键重启调用 IPC 并刷新列表", async () => {
    listMcpServers.mockResolvedValue([row({ status: "error" })]);
    render(<McpPanel />);
    await waitFor(() => expect(screen.getByTestId("mcp-restart-filesystem")).toBeTruthy());
    fireEvent.click(screen.getByTestId("mcp-restart-filesystem"));
    await waitFor(() => expect(mcpRestart).toHaveBeenCalledWith(1));
    // 重启后刷新（listMcpServers 至少被调 2 次）
    await waitFor(() => expect(listMcpServers.mock.calls.length).toBeGreaterThanOrEqual(2));
  });

  it("卸载必须二次确认；取消则不调用", async () => {
    listMcpServers.mockResolvedValue([row({})]);
    const confirmSpy = vi.spyOn(window, "confirm");
    render(<McpPanel />);
    await waitFor(() => expect(screen.getByTestId("mcp-uninstall-filesystem")).toBeTruthy());
    confirmSpy.mockReturnValue(false);
    fireEvent.click(screen.getByTestId("mcp-uninstall-filesystem"));
    expect(mcpUninstall).not.toHaveBeenCalled();
    confirmSpy.mockReturnValue(true);
    fireEvent.click(screen.getByTestId("mcp-uninstall-filesystem"));
    await waitFor(() => expect(mcpUninstall).toHaveBeenCalledWith(1));
  });

  it("日志按钮打开抽屉并展示日志内容", async () => {
    listMcpServers.mockResolvedValue([row({})]);
    mcpLogs.mockResolvedValue(["line1", "line2"]);
    render(<McpPanel />);
    await waitFor(() => expect(screen.getByText("日志")).toBeTruthy());
    fireEvent.click(screen.getByText("日志"));
    await waitFor(() => expect(screen.getByTestId("mcp-log-drawer")).toBeTruthy());
    expect(screen.getByText(/line1/)).toBeTruthy();
  });
});

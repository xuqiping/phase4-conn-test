// P07 S6（FR-010/AC-012 展示侧）：安装失败显示大白话原因；required env 弹表单。
import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import McpMarket from "./McpMarket";
import type { MarketEntryDto } from "../../lib/ipc";

const entries: MarketEntryDto[] = [
  { name: "fetch", description: "抓网页", runtime: "npx", command: "npx", args: [], env: [] },
  {
    name: "github", description: "GitHub", runtime: "npx", command: "npx", args: [],
    env: [{ key: "TOKEN", description: "令牌", required: true }],
  },
];

const listMcpMarket = vi.fn(async () => entries);
const installMcpServer = vi.fn(
  async (_n: string, _e: Record<string, string>) =>
    ({ id: 1, outcome: "installed_and_running", message: "「fetch」已安装并启动，立即可用" }),
);
const addMcpManual = vi.fn(async (_j: string) => ({ id: 2, outcome: "ok", message: "已添加" }));
vi.mock("../../lib/ipc", () => ({
  ipc: {
    listMcpMarket: () => listMcpMarket(),
    installMcpServer: (n: string, e: Record<string, string>) => installMcpServer(n, e),
    addMcpManual: (j: string) => addMcpManual(j),
  },
}));

afterEach(cleanup);

describe("McpMarket（AC-012 展示侧）", () => {
  it("安装失败显示大白话原因（缺运行时指引）", async () => {
    installMcpServer.mockRejectedValueOnce(
      new Error("没找到 Node.js（npx 命令不可用）。去 https://nodejs.org 下载 LTS 版安装，装完重开 DevPilot 再试。"),
    );
    render(<McpMarket />);
    await waitFor(() => expect(screen.getByTestId("market-card-fetch")).toBeTruthy());
    fireEvent.click(screen.getByTestId("market-card-fetch").querySelector("button")!);
    await waitFor(() =>
      expect(screen.getByTestId("market-msg-fetch").textContent).toContain("nodejs.org"),
    );
  });

  it("required env 的 server 先弹表单，填完才安装", async () => {
    render(<McpMarket />);
    await waitFor(() => expect(screen.getByTestId("market-card-github")).toBeTruthy());
    fireEvent.click(screen.getByTestId("market-card-github").querySelector("button")!);
    await waitFor(() => expect(screen.getByTestId("env-dialog")).toBeTruthy());
    const input = screen
      .getByTestId("env-dialog")
      .querySelector("input") as HTMLInputElement;
    fireEvent.change(input, { target: { value: "tok-1" } });
    fireEvent.click(screen.getByText("继续安装"));
    await waitFor(() => expect(installMcpServer).toHaveBeenCalledWith("github", { TOKEN: "tok-1" }));
  });

  it("搜索框过滤目录", async () => {
    render(<McpMarket />);
    await waitFor(() => expect(screen.getByTestId("market-card-fetch")).toBeTruthy());
    fireEvent.change(screen.getByPlaceholderText("搜索 server…"), { target: { value: "github" } });
    expect(screen.queryByTestId("market-card-fetch")).toBeNull();
    expect(screen.getByTestId("market-card-github")).toBeTruthy();
  });
});

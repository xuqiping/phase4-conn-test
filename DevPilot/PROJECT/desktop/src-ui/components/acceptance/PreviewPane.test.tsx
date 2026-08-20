// P06 S6 预览窗格测试：URL 白名单校验 + 尺寸切换（AC-057）。
import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import PreviewPane, { isAllowedPreviewUrl } from "./PreviewPane";

const createFixTask = vi.fn(async (_req: unknown) => 7);
vi.mock("../../lib/ipc", () => ({
  ipc: { createFixTask: (req: unknown) => createFixTask(req) },
  errMessage: String,
}));

describe("预览 URL 白名单（plan 安全清单）", () => {
  it("只放行 localhost / 127.0.0.1 的 http(s)", () => {
    expect(isAllowedPreviewUrl("http://localhost:5173")).toBe(true);
    expect(isAllowedPreviewUrl("https://127.0.0.1:3000")).toBe(true);
    expect(isAllowedPreviewUrl("http://example.com")).toBe(false);
    expect(isAllowedPreviewUrl("file:///C:/x")).toBe(false);
    expect(isAllowedPreviewUrl("not a url")).toBe(false);
  });
});

describe("PreviewPane（AC-057）", () => {
  afterEach(cleanup);

  it("默认渲染本机 iframe；切手机尺寸容器收窄", () => {
    render(<PreviewPane />);
    const frame = screen.getByTestId("preview-frame") as HTMLIFrameElement;
    expect(frame.getAttribute("src")).toBe("http://localhost:5173");
    expect(frame.style.width).toBe("100%");
    fireEvent.click(screen.getByTestId("preview-device-mobile"));
    expect((screen.getByTestId("preview-frame") as HTMLIFrameElement).style.width).toBe("390px");
  });

  it("输入外网地址回车被拒绝并提示", () => {
    render(<PreviewPane />);
    const input = screen.getByTestId("preview-url") as HTMLInputElement;
    fireEvent.change(input, { target: { value: "http://evil.com" } });
    fireEvent.keyDown(input, { key: "Enter" });
    expect(screen.getByTestId("preview-frame").getAttribute("src")).toBe("http://localhost:5173");
    expect(screen.getByText(/只允许本机地址/)).toBeTruthy();
  });
});

describe("圈选修复（P06 S7 / AC-037）", () => {
  beforeEach(() => createFixTask.mockClear());
  afterEach(cleanup);

  it("圈选 → 填描述 → 创建 fix 任务；取消不创建", async () => {
    render(<PreviewPane projectId={1} acceptanceItemId={3} />);
    // 开圈选
    fireEvent.click(screen.getByTestId("preview-pick"));
    expect(screen.getByTestId("pick-overlay")).toBeTruthy();
    // 点遮罩圈选（getBoundingClientRect 在 jsdom 返回 0，坐标兜底为 0）
    fireEvent.click(screen.getByTestId("pick-overlay"));
    await waitFor(() => expect(screen.getByTestId("fix-task-dialog")).toBeTruthy());

    // 取消：不创建
    fireEvent.click(screen.getByTestId("fix-cancel"));
    expect(createFixTask).not.toHaveBeenCalled();
    expect(screen.queryByTestId("fix-task-dialog")).toBeNull();

    // 再圈一次并确认
    fireEvent.click(screen.getByTestId("preview-pick"));
    fireEvent.click(screen.getByTestId("pick-overlay"));
    fireEvent.change(screen.getByTestId("fix-description"), {
      target: { value: "按钮点了没反应" },
    });
    fireEvent.click(screen.getByTestId("fix-confirm"));
    await waitFor(() => expect(createFixTask).toHaveBeenCalledTimes(1));
    expect(createFixTask.mock.calls[0][0]).toMatchObject({
      projectId: 1,
      acceptanceItemId: 3,
      description: "按钮点了没反应",
    });
    await waitFor(() =>
      expect(screen.getByText(/已创建修复任务 #7/)).toBeTruthy(),
    );
  });

  it("描述为空时确认按钮禁用（反例）", async () => {
    render(<PreviewPane projectId={1} />);
    fireEvent.click(screen.getByTestId("preview-pick"));
    fireEvent.click(screen.getByTestId("pick-overlay"));
    await waitFor(() => expect(screen.getByTestId("fix-task-dialog")).toBeTruthy());
    expect((screen.getByTestId("fix-confirm") as HTMLButtonElement).disabled).toBe(true);
  });
});

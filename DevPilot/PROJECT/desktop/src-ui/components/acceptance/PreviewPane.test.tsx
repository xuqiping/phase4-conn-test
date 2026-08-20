// P06 S6 预览窗格测试：URL 白名单校验 + 尺寸切换（AC-057）。
import { cleanup, render, screen, fireEvent } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import PreviewPane, { isAllowedPreviewUrl } from "./PreviewPane";

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

// P01 骨架渲染测试。
// Step 1（AC-020 最小断言）：应用可挂载。
// Step 3：三栏骨架就位 + 右栏五 Tab + 密度切换生效。
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import App from "./App";

// vitest 未开 globals，testing-library 的自动清理不生效，需手动 cleanup
afterEach(cleanup);

describe("App 骨架", () => {
  // AC-020 的最低层断言：应用外壳可渲染（真机双端走人工验收 TC1/TC2）
  it("渲染顶栏品牌", () => {
    render(<App />);
    expect(screen.getByText("DevPilot")).toBeTruthy();
  });

  it("三栏骨架：顶栏/左导航/中栏/右栏五 Tab 就位（Step 3）", () => {
    render(<App />);
    for (const id of ["topbar", "sidebar", "center", "rightbar"]) {
      expect(screen.getByTestId(id)).toBeTruthy();
    }
    for (const label of ["规格", "变更", "日志", "预览", "文件"]) {
      expect(screen.getByRole("tab", { name: label })).toBeTruthy();
    }
  });

  it("密度切换：舒适 ⇄ 紧凑（Step 3）", () => {
    const { container } = render(<App />);
    const root = container.firstElementChild as HTMLElement;
    expect(root.getAttribute("data-density")).toBe("comfort");
    fireEvent.click(screen.getByRole("button", { name: "切换界面密度" }));
    expect(root.getAttribute("data-density")).toBe("compact");
  });
});

// P01 骨架渲染测试。
// Step 1（AC-020 最小断言）：应用可挂载。
// Step 3：三栏骨架就位 + 右栏五 Tab + 密度切换生效。
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
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

  // ---- Step 5：联动点 1 静态版（管道条/导航/中栏/右栏四处同步） ----

  it("LI1 正向：点管道条「建造」→ 中栏切建造视图 + 右栏归位日志 Tab", () => {
    render(<App />);
    const pipeline = screen.getByTestId("pipeline");
    fireEvent.click(
      within(pipeline).getByRole("button", { name: /建造/ }),
    );
    expect(screen.getByTestId("view-build")).toBeTruthy();
    expect(
      screen.getByRole("tab", { name: "日志" }).getAttribute("aria-selected"),
    ).toBe("true");
  });

  it("LI1 正向：点导航「需求」→ 中栏切需求视图 + 管道条高亮需求", () => {
    render(<App />);
    const sidebar = screen.getByTestId("sidebar");
    fireEvent.click(within(sidebar).getByRole("button", { name: /需求/ }));
    expect(screen.getByTestId("view-spec")).toBeTruthy();
    const pipeline = screen.getByTestId("pipeline");
    expect(
      within(pipeline)
        .getByRole("button", { name: /需求/ })
        .getAttribute("aria-current"),
    ).toBe("step");
  });

  it("LI1 反向：手动切 Tab 后再切视图 → Tab 归位该视图默认", () => {
    render(<App />);
    // 手动切到「文件」
    fireEvent.click(screen.getByRole("tab", { name: "文件" }));
    expect(
      screen.getByRole("tab", { name: "文件" }).getAttribute("aria-selected"),
    ).toBe("true");
    // 切视图到「验收」→ 右栏归位默认「预览」
    const sidebar = screen.getByTestId("sidebar");
    fireEvent.click(within(sidebar).getByRole("button", { name: /验收/ }));
    expect(screen.getByTestId("view-accept")).toBeTruthy();
    expect(
      screen.getByRole("tab", { name: "预览" }).getAttribute("aria-selected"),
    ).toBe("true");
  });
});

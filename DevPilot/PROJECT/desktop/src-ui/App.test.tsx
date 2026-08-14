import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import App from "./App";

// AC-020 的最低层断言：应用外壳可渲染（真机双端走人工验收 TC1/TC2）
describe("App 骨架", () => {
  it("渲染占位页标题", () => {
    render(<App />);
    expect(screen.getByText("DevPilot")).toBeTruthy();
  });
});

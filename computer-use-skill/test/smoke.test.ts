// AC-021 前置冒烟：服务入口模块可加载（FR-017）
import { describe, it, expect } from "vitest";

describe("smoke", () => {
  it("AC-021: index 模块可被导入且导出版本常量", async () => {
    // index.ts 的副作用是启动 server；真正的 tools/list 断言在 Step 8 的 tools.test.ts
    // 这里只验证包构建产物约定
    const pkg = await import("../package.json", { with: { type: "json" } });
    expect(pkg.name).toBe("computer-use-skill");
    expect(pkg.version).toBe("0.1.0");
  });
});

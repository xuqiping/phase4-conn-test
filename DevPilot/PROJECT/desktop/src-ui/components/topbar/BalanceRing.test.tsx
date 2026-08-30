// P02 Step8 验收（AC-045）：余额环渲染三态（正常/不足红色+去充值/对账告警）。
// 约定同 App.test.tsx：vi.mock ipc（vault/meter 命令），billing store 直接置态。
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

vi.mock("@tauri-apps/api/core", () => ({
  invoke: vi.fn(async (cmd: string) => {
    if (cmd === "meter_reconcile") return { ok: true, local_cents: 0, cloud_cents: 0, drift_cents: 0 };
    return undefined;
  }),
}));

vi.mock("../../lib/cloudApi", () => ({
  PaymentRequiredError: class extends Error {},
  cloudBase: () => "http://test",
  // billing.refresh 不真发请求：由测试直接置 balance
  cloud: vi.fn(async () => {
    throw new Error("本套不测网络层");
  }),
}));

import BalanceRing from "./BalanceRing";
import { resetBillingStore, useBillingStore } from "../../stores/billing";

beforeEach(() => resetBillingStore());
afterEach(cleanup);

describe("BalanceRing（P02 Step8）", () => {
  it("未加载：显示占位「— 元」", () => {
    render(<BalanceRing />);
    expect(screen.getByTestId("balance-ring").textContent).toContain("— 元");
    expect(screen.queryByText("去充值")).toBeNull();
  });

  it("余额充足：显示金额 + 体验金标注，无充值按钮", () => {
    useBillingStore.setState({
      balance: { balance_cents: 1500, gift_cents: 500, total_cents: 2000 },
    });
    render(<BalanceRing />);
    expect(screen.getByTestId("balance-ring").textContent).toContain("¥20.00");
    expect(screen.getByTestId("balance-ring").textContent).toContain("体验金 ¥5.00");
    expect(screen.queryByText("去充值")).toBeNull();
  });

  it("余额不足（402 置位）：环变红 + 「去充值」按钮跳门户", () => {
    useBillingStore.setState({
      balance: { balance_cents: 0, gift_cents: 0, total_cents: 0 },
      insufficient: true,
    });
    const open = vi.fn();
    vi.stubGlobal("window", { ...window, open });
    render(<BalanceRing />);
    const btn = screen.getByText("去充值");
    btn.click();
    expect(open).toHaveBeenCalledWith(expect.stringContaining("/portal/recharge"), "_blank", "noopener");
    vi.unstubAllGlobals();
  });

  it("对账不平：显示黄色告警条，可点击关闭", () => {
    useBillingStore.setState({
      balance: { balance_cents: 100, gift_cents: 0, total_cents: 100 },
      driftWarning: "对账不平：本地 0 分 vs 云端 100 分",
    });
    render(<BalanceRing />);
    const warn = screen.getByTestId("drift-warning");
    expect(warn.textContent).toContain("对账不平");
    fireEvent.click(warn);
    expect(screen.queryByTestId("drift-warning")).toBeNull();
  });
});

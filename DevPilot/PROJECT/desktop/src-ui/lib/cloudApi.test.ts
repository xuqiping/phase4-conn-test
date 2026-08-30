// P02 Step8 验收（AC-045 客户端半边）：cloudApi 402 拦截 / 401 静默刷新 / 网络错误大白话。
// jsdom 无 Tauri 运行时：vault 走 ipc mock（既有约定）。
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@tauri-apps/api/core", () => ({
  invoke: vi.fn(async (cmd: string, args?: Record<string, unknown>) => {
    if (cmd === "vault_load") return vaultRefreshToken;
    if (cmd === "vault_save") {
      vaultRefreshToken = String(args?.refreshToken);
      return undefined;
    }
    if (cmd === "vault_clear") {
      vaultRefreshToken = null;
      return undefined;
    }
    return undefined;
  }),
}));

let vaultRefreshToken: string | null = null;

import { cloud, setAccessToken } from "./cloudApi";

function jsonResponse(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

const fetchMock = vi.fn();
beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  fetchMock.mockReset();
  vaultRefreshToken = null;
  setAccessToken(null);
});
afterEach(() => vi.unstubAllGlobals());

describe("cloudApi（P02 Step8）", () => {
  it("200 正常取 data；自动带 Bearer", async () => {
    setAccessToken("acc-token");
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { code: 200, msg: "ok", data: { total_cents: 500 } }));
    const data = await cloud<{ total_cents: number }>("GET", "/balance");
    expect(data.total_cents).toBe(500);
    const headers = fetchMock.mock.calls[0][1].headers as Record<string, string>;
    expect(headers.authorization).toBe("Bearer acc-token");
  });

  it("402 → PaymentRequiredError 大白话（全局拦截入口）", async () => {
    setAccessToken("acc-token");
    fetchMock.mockResolvedValueOnce(jsonResponse(402, { code: 402, msg: "余额不足，请充值后再试", data: null }));
    await expect(cloud("GET", "/balance")).rejects.toMatchObject({
      name: "PaymentRequiredError",
      message: expect.stringContaining("余额不足"),
    });
  });

  it("401 → 用 vault refresh 静默换新再重试成功", async () => {
    setAccessToken("old-acc");
    vaultRefreshToken = "refresh-token-1";
    fetchMock
      .mockResolvedValueOnce(jsonResponse(401, { code: 401, msg: "请先登录", data: null }))
      .mockResolvedValueOnce(jsonResponse(200, { code: 200, msg: "ok", data: { token: "new-acc", refresh_token: "refresh-token-2" } }))
      .mockResolvedValueOnce(jsonResponse(200, { code: 200, msg: "ok", data: { total_cents: 100 } }));
    const data = await cloud<{ total_cents: number }>("GET", "/balance");
    expect(data.total_cents).toBe(100);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(vaultRefreshToken).toBe("refresh-token-2"); // 新 refresh 已回写 vault
  });

  it("401 且 vault 空 → 「请先登录」", async () => {
    setAccessToken(null);
    fetchMock.mockResolvedValueOnce(jsonResponse(401, { code: 401, msg: "请先登录", data: null }));
    await expect(cloud("GET", "/balance")).rejects.toThrow("请先登录");
  });

  it("refresh 也失效 → 清 vault + 「请重新登录」", async () => {
    setAccessToken("old-acc");
    vaultRefreshToken = "expired-refresh";
    fetchMock
      .mockResolvedValueOnce(jsonResponse(401, { code: 401, msg: "请先登录", data: null }))
      .mockResolvedValueOnce(jsonResponse(401, { code: 401, msg: "登录已过期", data: null }));
    await expect(cloud("GET", "/balance")).rejects.toThrow("请重新登录");
    expect(vaultRefreshToken).toBeNull(); // vault 已清
  });

  it("网络断 → 大白话（不甩英文堆栈）", async () => {
    fetchMock.mockRejectedValueOnce(new TypeError("Failed to fetch"));
    await expect(cloud("GET", "/balance")).rejects.toThrow("网络连接失败");
  });
});

// 云端 API 客户端：JWT 注入 + 401 静默刷新 + 402 全局拦截（联动点 4）。
// access token 只留内存（页面刷新走 vault 里的 refresh token 重新换），
// refresh token 经 Rust vault 存 OS 凭据管理器，绝不落明文文件。
import { invoke } from "@tauri-apps/api/core";

export class PaymentRequiredError extends Error {
  constructor(msg = "余额不足，请充值后再试") {
    super(msg);
    this.name = "PaymentRequiredError";
  }
}

/** 云端基地址（运营侧部署后改；先本地联调默认值） */
export function cloudBase(): string {
  return localStorage.getItem("devpilot_cloud_base") ?? "http://127.0.0.1:3000/api/v1";
}

// 模块级令牌（内存）：402/401 逻辑与 store 解耦，测试可直接 set
let accessToken: string | null = null;
export function setAccessToken(t: string | null) {
  accessToken = t;
}
export function getAccessToken() {
  return accessToken;
}

/** refresh token 持久化走 OS 凭据管理器（Rust vault） */
export const vault = {
  save: (refreshToken: string) => invoke<void>("vault_save", { refreshToken }),
  load: () => invoke<string | null>("vault_load"),
  clear: () => invoke<void>("vault_clear"),
};

interface RBody<T> {
  code: number;
  msg: string;
  data: T;
}

async function raw<T>(method: string, path: string, body?: unknown, auth?: string): Promise<T> {
  let resp: Response;
  try {
    resp = await fetch(`${cloudBase()}${path}`, {
      method,
      headers: {
        "content-type": "application/json",
        ...(auth ? { authorization: `Bearer ${auth}` } : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new Error("网络连接失败，请检查网络后重试");
  }
  const payload = (await resp.json().catch(() => null)) as RBody<T> | null;
  if (resp.status === 402) {
    throw new PaymentRequiredError(payload?.msg ?? "余额不足，请充值后再试");
  }
  if (resp.status === 401) {
    throw new Error("UNAUTHORIZED");
  }
  if (!resp.ok || !payload || payload.code !== 200) {
    throw new Error(payload?.msg ?? `请求失败（${resp.status}）`);
  }
  return payload.data;
}

/**
 * 云端请求：自动带 access；401 时用 vault 里的 refresh token 静默续期一次再重试
 * （refresh 也过期才向上抛「请重新登录」，不打断输入中的任务——联动点 4）。
 */
export async function cloud<T>(method: string, path: string, body?: unknown): Promise<T> {
  try {
    return await raw<T>(method, path, body, accessToken ?? undefined);
  } catch (e) {
    if (!(e instanceof Error) || e.message !== "UNAUTHORIZED") throw e;
    const refreshToken = await vault.load().catch(() => null);
    if (!refreshToken) throw new Error("请先登录");
    try {
      const tokens = await raw<{ token: string; refresh_token: string }>(
        "POST", "/auth/refresh", { refresh_token: refreshToken },
      );
      accessToken = tokens.token;
      await vault.save(tokens.refresh_token).catch(() => {});
      return await raw<T>(method, path, body, accessToken);
    } catch {
      await vault.clear().catch(() => {});
      throw new Error("登录已过期，请重新登录");
    }
  }
}

export interface ChatMessage {
  role: "user" | "assistant";
  content: string;
}

export interface ChatCompleteOptions {
  model?: string;
  messages: ChatMessage[];
  nonce?: string;
  taskId?: string;
}

export interface ChatCompleteResult {
  content: string;
  cost_cents: number;
  capped: boolean;
}

function randomNonce(length = 16): string {
  const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  let out = "";
  for (let i = 0; i < length; i++) {
    out += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  return out;
}

/** 非流式完成：走 /gateway/complete，自动带 access/401 刷新/402 拦截 */
export async function chatComplete(
  options: ChatCompleteOptions,
): Promise<ChatCompleteResult> {
  const model =
    options.model ?? localStorage.getItem("devpilot_chat_model") ?? "claude-sonnet-4";
  return cloud<ChatCompleteResult>("POST", "/gateway/complete", {
    model,
    messages: options.messages,
    nonce: options.nonce ?? randomNonce(),
    task_id: options.taskId,
  });
}

/** 订阅内核状态推送（事件名对齐 events.rs）；返回取消订阅函数 */
export async function onState(cb: (dto: import("./ipc").StateDto) => void): Promise<() => void> {
  const { listen } = await import("@tauri-apps/api/event");
  return listen<import("./ipc").StateDto>("kernel://state", (e) => cb(e.payload));
}

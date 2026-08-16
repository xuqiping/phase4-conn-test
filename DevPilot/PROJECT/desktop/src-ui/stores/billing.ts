// 计费 store：余额环数据源 + 用量镜像对账 + 余额不足引导（AC-045 客户端半边）。
import { create } from "zustand";
import { invoke } from "@tauri-apps/api/core";
import { cloud, PaymentRequiredError } from "../lib/cloudApi";

export interface Balance {
  balance_cents: number;
  gift_cents: number;
  total_cents: number;
}

interface LedgerRowVo {
  id: number;
  kind: number;
  model: string | null;
  amount_cents: number;
}

interface BillingState {
  balance: Balance | null;
  /** 对账不平告警（本地镜像 vs 云端账本） */
  driftWarning: string | null;
  /** 余额不足（402 拦截置位，环变红 + 引导充值；联动点 1 反向） */
  insufficient: boolean;
  refreshing: boolean;

  refresh: () => Promise<void>;
  /** 全局 402 入口：cloudApi 抛 PaymentRequiredError 时调用 */
  markInsufficient: () => void;
  dismissInsufficient: () => void;
  dismissDrift: () => void;
  /** 跳浏览器门户充值（充值到账后不自动续跑，需手动——plan 边界） */
  openRecharge: () => void;
}

export const RECHARGE_PORTAL_URL = "http://127.0.0.1:3000/portal/recharge";

export const useBillingStore = create<BillingState>((set, get) => ({
  balance: null,
  driftWarning: null,
  insufficient: false,
  refreshing: false,

  refresh: async () => {
    if (get().refreshing) return;
    set({ refreshing: true });
    try {
      const balance = await cloud<Balance>("GET", "/balance");
      set({ balance });
      // 用量镜像对账：拉最近账本行同步进本地镜像，再对总账
      try {
        const tx = await cloud<{ items: LedgerRowVo[] }>(
          "GET", "/balance/transactions?page=1&pageSize=100",
        );
        const rows = tx.items.map((i) => ({
          id: i.id,
          kind: i.kind,
          model: i.model,
          amount_cents: String(i.amount_cents),
        }));
        await invoke("meter_sync", { userId: 0, rows }); // TODO(P04)：userId 随登录态注入
        const report = await invoke<{ ok: boolean; local_cents: number; cloud_cents: number; drift_cents: number }>(
          "meter_reconcile", { userId: 0, cloudCents: balance.total_cents },
        );
        if (!report.ok) {
          set({
            driftWarning: `对账不平：本地 ${report.local_cents} 分 vs 云端 ${report.cloud_cents} 分（差 ${report.drift_cents} 分），已记录待同步`,
          });
        } else {
          set({ driftWarning: null });
        }
      } catch {
        // 镜像/对账失败不阻塞余额展示（降级路径）
      }
      if (balance.total_cents > 0) set({ insufficient: false });
    } catch (e) {
      if (e instanceof PaymentRequiredError) set({ insufficient: true });
      // 其余错误静默：余额环显示「—」，不打扰主流程
    } finally {
      set({ refreshing: false });
    }
  },

  markInsufficient: () => set({ insufficient: true }),
  dismissInsufficient: () => set({ insufficient: false }),
  dismissDrift: () => set({ driftWarning: null }),
  openRecharge: () => {
    window.open(RECHARGE_PORTAL_URL, "_blank", "noopener");
  },
}));

// 供测试重置用
export function resetBillingStore() {
  useBillingStore.setState({ balance: null, driftWarning: null, insufficient: false, refreshing: false });
}

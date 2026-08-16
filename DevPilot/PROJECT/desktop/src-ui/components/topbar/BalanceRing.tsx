// 顶栏余额环（AC-045 客户端半边）：余额=充值+体验金；不足变红 + 「去充值」。
// 环形进度以「本月额度感」示意（MVP 以 20 元为满环刻度，纯展示无业务含义）。
import { useEffect } from "react";
import { useBillingStore } from "../../stores/billing";

const FULL_CENTS = 2000; // 满环刻度（纯视觉）

export default function BalanceRing() {
  const balance = useBillingStore((s) => s.balance);
  const insufficient = useBillingStore((s) => s.insufficient);
  const driftWarning = useBillingStore((s) => s.driftWarning);
  const refresh = useBillingStore((s) => s.refresh);
  const openRecharge = useBillingStore((s) => s.openRecharge);
  const dismissDrift = useBillingStore((s) => s.dismissDrift);

  // 挂载即拉一次 + 每 60s 轮询（MVP；P04 任务驱动刷新）
  useEffect(() => {
    void refresh();
    const t = setInterval(() => void refresh(), 60_000);
    return () => clearInterval(t);
  }, [refresh]);

  const total = balance?.total_cents ?? null;
  const pct = total == null ? 0 : Math.min(100, (total / FULL_CENTS) * 100);
  const yuan = total == null ? null : (total / 100).toFixed(2);
  const low = insufficient || (total != null && total <= 0);

  return (
    <div className="flex items-center gap-2" data-testid="balance-ring">
      <button
        type="button"
        onClick={() => void refresh()}
        title={driftWarning ?? "点击刷新余额"}
        className={`flex items-center gap-2 rounded-[9px] border px-2.5 py-1.5 text-[13px] transition ${
          low
            ? "border-red-500/60 bg-red-500/10 text-red-400"
            : "border-border bg-card text-text-dim hover:border-border-strong"
        }`}
      >
        {/* 环形进度（conic-gradient） */}
        <span
          className="relative grid size-[22px] place-items-center rounded-full"
          style={{
            background: `conic-gradient(currentColor ${pct}%, var(--color-border, #333) 0)`,
          }}
          aria-hidden
        >
          <span className="size-[14px] rounded-full bg-card" />
        </span>
        <span className="font-mono">
          {yuan == null ? "— 元" : `¥${yuan}`}
          {balance && balance.gift_cents > 0 && (
            <span className="ml-1 text-[10px] text-text-faint">
              含体验金 ¥{(balance.gift_cents / 100).toFixed(2)}
            </span>
          )}
        </span>
      </button>
      {low && (
        <button
          type="button"
          onClick={openRecharge}
          className="rounded-[9px] bg-red-500/90 px-2.5 py-1.5 text-[13px] font-medium text-white transition hover:bg-red-500"
        >
          去充值
        </button>
      )}
      {driftWarning && (
        <button
          type="button"
          data-testid="drift-warning"
          onClick={dismissDrift}
          title="点击关闭"
          className="max-w-[220px] truncate rounded-[9px] border border-yellow-500/50 bg-yellow-500/10 px-2 py-1.5 text-[12px] text-yellow-400"
        >
          ⚠ {driftWarning}
        </button>
      )}
    </div>
  );
}

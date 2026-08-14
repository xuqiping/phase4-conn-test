// 阶段操作条（中栏底部）：当前阶段 + 门禁确认 + 推进/回退按钮。
// 门禁「确认通过」在 P04/P06 会换成真实的检查单交互，骨架期为一点即过。
import { viewDef, type ViewKey } from "../lib/viewRegistry";
import { useProjectStore } from "../stores/project";

export default function PhaseBar() {
  const snapshot = useProjectStore((s) => s.snapshot);
  const transition = useProjectStore((s) => s.transition);
  const passGate = useProjectStore((s) => s.passGate);
  if (!snapshot) return null;

  const order = snapshot.phases.map((p) => p.key);
  const cur = order.indexOf(snapshot.phase);

  return (
    <div
      data-testid="phase-bar"
      className="panel absolute right-[var(--space-pad)] bottom-[var(--space-pad)] left-[var(--space-pad)] flex items-center gap-3 rounded-[14px] px-4 py-2.5"
    >
      <span className="text-xs text-text-dim">
        当前阶段：
        <b className="text-text">{viewDef(snapshot.phase as ViewKey).label}</b>
      </span>

      {/* 门禁（未过的可一点确认） */}
      {snapshot.pending_gates.map((g) => (
        <span key={g.key} className="flex items-center gap-2 text-xs">
          {g.passed ? (
            <span className="text-success">✓ {g.label}</span>
          ) : (
            <button
              type="button"
              onClick={() => passGate(g.key)}
              title={g.checklist.join("；")}
              className="rounded-full border border-amber/40 px-3 py-1 text-amber transition hover:bg-amber/10"
            >
              确认「{g.label}」
            </button>
          )}
        </span>
      ))}

      {/* 推进 / 回退 */}
      <div className="ml-auto flex gap-2">
        {snapshot.allowed_next.map((to) => {
          const back = order.indexOf(to) < cur;
          return (
            <button
              key={to}
              type="button"
              onClick={() => transition(to)}
              className={`rounded-[9px] px-3 py-1.5 text-xs transition ${
                back
                  ? "border border-border text-text-dim hover:text-text"
                  : "bg-gradient-to-br from-brand to-brand2 font-semibold text-white"
              }`}
            >
              {back ? "回退到" : "推进到"}
              「{viewDef(to as ViewKey).label}」
            </button>
          );
        })}
      </div>
    </div>
  );
}

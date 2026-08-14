// HUD 指标瓦片：发光大数字 + 标签 + 来源提示。
export default function HudTile({
  label,
  value,
  unit,
  hint,
  tone = "brand",
}: {
  label: string;
  value: string;
  unit?: string;
  hint: string;
  tone?: "brand" | "success" | "amber" | "coral";
}) {
  const glow = {
    brand: "text-brand2 [text-shadow:0_0_18px_rgba(79,124,255,.55)]",
    success: "text-success [text-shadow:0_0_18px_rgba(52,211,153,.5)]",
    amber: "text-amber [text-shadow:0_0_18px_rgba(251,191,36,.5)]",
    coral: "text-coral [text-shadow:0_0_18px_rgba(251,113,133,.5)]",
  }[tone];

  return (
    <div data-testid={`hud-${label}`} className="panel rounded-[14px] p-4">
      <p className="text-xs text-text-dim">{label}</p>
      <p className={`mt-1 font-mono text-3xl font-bold ${glow}`}>
        {value}
        {unit && <span className="ml-1 text-sm">{unit}</span>}
      </p>
      <p className="mt-1 text-[11px] text-text-faint">{hint}</p>
    </div>
  );
}

// 中栏主视图区：Step 5 由视图注册表接管，当前为静态占位。
export default function Center() {
  return (
    <main
      data-testid="center"
      className="flex min-w-0 flex-1 items-center justify-center p-[var(--space-pad)]"
    >
      <div className="panel rounded-[14px] p-8 text-center">
        <p className="text-lg font-semibold">驾驶舱</p>
        <p className="mt-2 text-sm text-text-dim">
          中栏视图区 · 七视图将在 Step 5 注册，HUD 指标在 Step 8 落静态版
        </p>
      </div>
    </main>
  );
}

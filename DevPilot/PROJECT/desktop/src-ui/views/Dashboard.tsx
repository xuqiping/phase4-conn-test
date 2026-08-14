// 驾驶舱 视图占位：总览与四指标
export default function Dashboard() {
  return (
    <section
      data-testid="view-dashboard"
      className="panel flex flex-1 items-center justify-center rounded-[14px]"
    >
      <div className="text-center">
        <p className="text-lg font-semibold">驾驶舱</p>
        <p className="mt-2 text-sm text-text-dim">总览与四指标</p>
      </div>
    </section>
  );
}

// MCP 管理视图（P07 S6 FR-026）：管理面板 + 市场（非阶段视图，侧边栏直达）。
import McpMarket from "../components/settings/McpMarket";
import McpPanel from "../components/settings/McpPanel";

export default function Mcp() {
  return (
    <section
      data-testid="view-mcp"
      className="flex flex-1 flex-col gap-[var(--space-gap)] overflow-y-auto pr-1"
    >
      <div className="panel rounded-[14px] p-6 text-center">
        <p className="text-lg font-semibold">MCP 管理</p>
        <p className="mt-2 text-sm text-text-dim">
          安装外挂工具 server 给模型用：市场点选安装，这里启停 / 看日志 / 出错一键重启。
        </p>
      </div>
      <McpPanel />
      <McpMarket />
    </section>
  );
}

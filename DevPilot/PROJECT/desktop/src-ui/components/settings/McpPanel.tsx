// MCP 管理面板（P07 S6 FR-026/AC-029 展示侧）：状态徽章 + 启停/重启/卸载 + 日志抽屉。
import { useEffect, useState } from "react";
import { ipc, type McpServerDto } from "../../lib/ipc";

const STATUS_STYLE: Record<string, string> = {
  running: "bg-emerald-500/15 text-emerald-400",
  stopped: "bg-zinc-500/15 text-zinc-400",
  error: "bg-red-500/15 text-red-400",
  manual_required: "bg-amber-500/15 text-amber-400",
  installed: "bg-blue-500/15 text-blue-400",
};

const STATUS_LABEL: Record<string, string> = {
  running: "运行中",
  stopped: "已停止",
  error: "出错",
  manual_required: "需人工处理",
  installed: "已安装",
};

export default function McpPanel() {
  const [servers, setServers] = useState<McpServerDto[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [logServer, setLogServer] = useState<McpServerDto | null>(null);
  const [logs, setLogs] = useState<string[]>([]);

  const load = async () => {
    try {
      setServers(await ipc.listMcpServers());
      setError(null);
    } catch (e) {
      setError((e as Error)?.message ?? "读 server 列表失败");
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const act = async (id: number, op: (id: number) => Promise<string>) => {
    setBusyId(id);
    setError(null);
    try {
      await op(id);
      await load();
    } catch (e) {
      setError((e as Error)?.message ?? "操作失败");
    } finally {
      setBusyId(null);
    }
  };

  const uninstall = (s: McpServerDto) => {
    if (!window.confirm(`确定卸载「${s.name}」？配置记录会一并删除。`)) return;
    void act(s.id, ipc.mcpUninstall);
  };

  const openLogs = async (s: McpServerDto) => {
    setLogServer(s);
    try {
      setLogs(await ipc.mcpLogs(s.id));
    } catch {
      setLogs(["（读日志失败）"]);
    }
  };

  return (
    <div className="panel space-y-3 rounded-[9px] p-4" data-testid="mcp-panel">
      <h3 className="text-sm font-semibold">已安装的 MCP server</h3>
      {error && <div className="text-xs text-red-400">{error}</div>}
      {servers.length === 0 && (
        <div className="text-xs text-text-dim">还没有安装任何 server，去下面市场挑一个。</div>
      )}
      {servers.map((s) => (
        <div
          key={s.id}
          data-testid={`mcp-row-${s.name}`}
          className="flex items-center gap-3 rounded-[9px] border border-border px-3 py-2"
        >
          <span
            data-testid={`mcp-status-${s.name}`}
            className={`rounded-full px-2 py-0.5 text-[11px] ${
              STATUS_STYLE[s.status] ?? "bg-zinc-500/15 text-zinc-400"
            }`}
          >
            {STATUS_LABEL[s.status] ?? s.status}
          </span>
          <div className="min-w-0 flex-1">
            <div className="truncate text-xs font-medium">
              {s.name}
              <span className="ml-2 text-text-faint font-mono">{s.command}</span>
            </div>
            <div className="truncate text-[11px] text-text-dim">
              {s.status === "error" || s.status === "manual_required"
                ? s.last_error || "看日志了解详情"
                : s.description}
            </div>
          </div>
          <div className="flex shrink-0 items-center gap-1">
            {(s.status === "error" || s.status === "manual_required") && (
              <button
                type="button"
                data-testid={`mcp-restart-${s.name}`}
                disabled={busyId === s.id}
                onClick={() => void act(s.id, ipc.mcpRestart)}
                className="rounded-full bg-red-500/20 px-3 py-1 text-[11px] font-medium text-red-300 hover:bg-red-500/30"
              >
                一键重启
              </button>
            )}
            {s.status === "running" ? (
              <button
                type="button"
                disabled={busyId === s.id}
                onClick={() => void act(s.id, ipc.mcpStop)}
                className="rounded-[9px] border border-border px-2 py-1 text-[11px] text-text-dim hover:bg-card"
              >
                停止
              </button>
            ) : (
              s.status !== "manual_required" && (
                <button
                  type="button"
                  disabled={busyId === s.id}
                  onClick={() => void act(s.id, ipc.mcpStart)}
                  className="rounded-[9px] border border-border px-2 py-1 text-[11px] text-text-dim hover:bg-card"
                >
                  启动
                </button>
              )
            )}
            {s.status === "running" && (
              <button
                type="button"
                disabled={busyId === s.id}
                onClick={() => void act(s.id, ipc.mcpRestart)}
                className="rounded-[9px] border border-border px-2 py-1 text-[11px] text-text-dim hover:bg-card"
              >
                重启
              </button>
            )}
            <button
              type="button"
              onClick={() => void openLogs(s)}
              className="rounded-[9px] border border-border px-2 py-1 text-[11px] text-text-dim hover:bg-card"
            >
              日志
            </button>
            <button
              type="button"
              data-testid={`mcp-uninstall-${s.name}`}
              onClick={() => uninstall(s)}
              className="rounded-[9px] border border-border px-2 py-1 text-[11px] text-red-400 hover:bg-red-500/10"
            >
              卸载
            </button>
          </div>
        </div>
      ))}

      {logServer && (
        <div
          data-testid="mcp-log-drawer"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-8"
          onClick={() => setLogServer(null)}
        >
          <div
            className="max-h-full w-full max-w-2xl space-y-2 overflow-y-auto rounded-[14px] border border-border bg-card p-4"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between">
              <h4 className="text-sm font-semibold">{logServer.name} · 最近日志</h4>
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={() => void navigator.clipboard?.writeText(logs.join("\n"))}
                  className="rounded-[9px] border border-border px-2 py-1 text-[11px] text-text-dim"
                >
                  一键复制
                </button>
                <button
                  type="button"
                  onClick={() => setLogServer(null)}
                  className="rounded-[9px] border border-border px-2 py-1 text-[11px] text-text-dim"
                >
                  关闭
                </button>
              </div>
            </div>
            <pre className="max-h-96 overflow-y-auto rounded-[9px] bg-black/30 p-3 text-[11px] leading-relaxed text-text-dim">
              {logs.length === 0 ? "（暂无日志——server 没输出过 stderr）" : logs.join("\n")}
            </pre>
          </div>
        </div>
      )}
    </div>
  );
}

// MCP 市场卡片（P07 S6 FR-010/AC-012 展示侧）：搜索过滤 + 安装（required env 表单）+ 手动添加 JSON。
import { useEffect, useState } from "react";
import { ipc, type MarketEntryDto } from "../../lib/ipc";

export default function McpMarket() {
  const [entries, setEntries] = useState<MarketEntryDto[]>([]);
  const [filter, setFilter] = useState("");
  const [installing, setInstalling] = useState<string | null>(null);
  const [messages, setMessages] = useState<Record<string, string>>({});
  const [envPick, setEnvPick] = useState<MarketEntryDto | null>(null);
  const [envValues, setEnvValues] = useState<Record<string, string>>({});
  const [manualJson, setManualJson] = useState("");
  const [manualMsg, setManualMsg] = useState<string | null>(null);

  useEffect(() => {
    ipc.listMcpMarket().then(setEntries).catch(() => setEntries([]));
  }, []);

  const visible = entries.filter(
    (e) =>
      !filter.trim() ||
      e.name.includes(filter.trim()) ||
      e.description.includes(filter.trim()),
  );

  const install = async (e: MarketEntryDto, env: Record<string, string>) => {
    setInstalling(e.name);
    setEnvPick(null);
    try {
      const out = await ipc.installMcpServer(e.name, env);
      setMessages((m) => ({ ...m, [e.name]: out.message }));
    } catch (err) {
      // 失败原因大白话直接展示（后端已保证）
      setMessages((m) => ({ ...m, [e.name]: (err as Error).message }));
    } finally {
      setInstalling(null);
    }
  };

  const addManual = async () => {
    setManualMsg(null);
    try {
      const out = await ipc.addMcpManual(manualJson.trim());
      setManualMsg(out.message);
      setManualJson("");
    } catch (err) {
      setManualMsg((err as Error).message);
    }
  };

  return (
    <div className="panel space-y-3 rounded-[9px] p-4" data-testid="mcp-market">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold">MCP 市场</h3>
        <input
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          placeholder="搜索 server…"
          className="rounded-[9px] border border-border bg-card px-2 py-1 text-xs text-text"
        />
      </div>
      <div className="grid grid-cols-2 gap-2">
        {visible.map((e) => (
          <div
            key={e.name}
            data-testid={`market-card-${e.name}`}
            className="flex flex-col gap-2 rounded-[9px] border border-border p-3"
          >
            <div className="text-xs font-medium">
              {e.name}
              <span className="ml-2 rounded-full bg-card px-2 py-0.5 text-[10px] text-text-faint font-mono">
                {e.runtime}
              </span>
            </div>
            <div className="flex-1 text-[11px] text-text-dim">{e.description}</div>
            {messages[e.name] && (
              <div
                data-testid={`market-msg-${e.name}`}
                className={`text-[11px] ${
                  messages[e.name].includes("失败") || messages[e.name].includes("没找到")
                    ? "text-amber-400"
                    : "text-emerald-400"
                }`}
              >
                {messages[e.name]}
              </div>
            )}
            <button
              type="button"
              disabled={installing === e.name}
              onClick={() => {
                if (e.env.some((s) => s.required)) {
                  setEnvValues({});
                  setEnvPick(e);
                } else {
                  void install(e, {});
                }
              }}
              className="rounded-[9px] bg-brand px-3 py-1.5 text-[11px] font-medium text-white hover:bg-brand2 disabled:opacity-50"
            >
              {installing === e.name ? "安装中…" : "安装"}
            </button>
          </div>
        ))}
      </div>

      <details className="rounded-[9px] border border-border p-3">
        <summary className="cursor-pointer text-xs font-medium">手动添加（JSON 配置）</summary>
        <textarea
          value={manualJson}
          onChange={(e) => setManualJson(e.target.value)}
          rows={4}
          placeholder='{"name":"my-server","description":"说明","command":"npx","args":["-y","xxx"],"env":{}}'
          className="mt-2 w-full rounded-[9px] border border-border bg-card px-2 py-1.5 font-mono text-[11px] text-text"
        />
        {manualMsg && <div className="mt-1 text-[11px] text-text-dim">{manualMsg}</div>}
        <button
          type="button"
          disabled={!manualJson.trim()}
          onClick={() => void addManual()}
          className="mt-2 rounded-[9px] bg-brand px-3 py-1.5 text-[11px] font-medium text-white disabled:opacity-50"
        >
          添加
        </button>
      </details>

      {envPick && (
        <div
          data-testid="env-dialog"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-8"
        >
          <div className="w-full max-w-md space-y-3 rounded-[14px] border border-border bg-card p-4">
            <h4 className="text-sm font-semibold">安装「{envPick.name}」需要填</h4>
            {envPick.env.map((s) => (
              <label key={s.key} className="block space-y-1">
                <span className="text-[11px] text-text-dim">
                  {s.key}
                  {s.required ? "（必填）" : "（可选）"}：{s.description}
                </span>
                <input
                  value={envValues[s.key] ?? ""}
                  onChange={(e) =>
                    setEnvValues((v) => ({ ...v, [s.key]: e.target.value }))
                  }
                  className="w-full rounded-[9px] border border-border bg-card px-2 py-1 text-xs text-text"
                />
              </label>
            ))}
            <div className="flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setEnvPick(null)}
                className="rounded-[9px] border border-border px-3 py-1.5 text-[11px] text-text-dim"
              >
                取消
              </button>
              <button
                type="button"
                onClick={() => void install(envPick, envValues)}
                className="rounded-[9px] bg-brand px-3 py-1.5 text-[11px] font-medium text-white"
              >
                继续安装
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

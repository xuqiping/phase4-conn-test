// 需求视图：项目分析报告 → 需求确认卡片；全部确认才解锁「进入计划」（FR-031/AC-034）。
import { useEffect, useState } from "react";
import ClarifyDialog from "../components/clarify/ClarifyDialog";
import { generateSpecCards, type SpecCardDraft } from "../lib/generator";
import { errMessage, ipc, type SpecCardDto } from "../lib/ipc";
import { useProjectStore } from "../stores/project";

const REPORT_PATH = "workflow_output/docs/项目分析/项目分析报告.md";

export default function Spec() {
  const projectId = useProjectStore((s) => s.currentId);
  const transition = useProjectStore((s) => s.transition);
  const [reportMd, setReportMd] = useState<string | null>(null);
  const [cards, setCards] = useState<SpecCardDto[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [clarify, setClarify] = useState<string[] | null>(null);
  const [editing, setEditing] = useState<number | null>(null);
  const [editForm, setEditForm] = useState<{ title: string; detail: string; ac: string }>({
    title: "",
    detail: "",
    ac: "",
  });

  const load = async () => {
    if (projectId == null) return;
    try {
      const md = await ipc.readProjectFile(projectId, REPORT_PATH);
      setReportMd(md);
    } catch {
      setReportMd(null);
    }
    try {
      const rows = await ipc.listSpecCards(projectId);
      setCards(rows);
    } catch (e) {
      setError(errMessage(e));
    }
  };

  useEffect(() => {
    void load();
  }, [projectId]);

  const generate = async () => {
    if (projectId == null || reportMd == null) return;
    setLoading(true);
    setError(null);
    try {
      const agent = await ipc.loadAgentConfig(projectId);
      const res = await generateSpecCards(reportMd, agent);
      if (res.clarifyingQuestions.length > 0) {
        setClarify(res.clarifyingQuestions);
        setLoading(false);
        return;
      }
      const drafts: SpecCardDraft[] = res.content.cards;
      const saved = await ipc.saveSpecCards(projectId, drafts);
      setCards(saved);
    } catch (e) {
      setError(errMessage(e));
    } finally {
      setLoading(false);
    }
  };

  const handleClarify = async (answers: string[]) => {
    setClarify(null);
    // 把追问回答追加到报告末尾，再次生成
    if (!reportMd) return;
    const appendix = "\n\n【补充澄清】\n" + answers.map((a, i) => `${i + 1}. ${a}`).join("\n");
    setReportMd(reportMd + appendix);
    await generate();
  };

  const setStatus = async (id: number, status: SpecCardDto["status"]) => {
    try {
      await ipc.updateSpecCard(id, { status });
      setCards((prev) => prev.map((c) => (c.id === id ? { ...c, status } : c)));
    } catch (e) {
      setError(errMessage(e));
    }
  };

  const startEdit = (card: SpecCardDto) => {
    setEditing(card.id);
    setEditForm({
      title: card.title,
      detail: card.detail,
      ac: card.ac.join("\n"),
    });
  };

  const saveEdit = async () => {
    if (editing == null) return;
    try {
      const updated = await ipc.updateSpecCard(editing, {
        title: editForm.title,
        detail: editForm.detail,
        ac: editForm.ac
          .split("\n")
          .map((s) => s.trim())
          .filter(Boolean),
      });
      setCards((prev) => prev.map((c) => (c.id === editing ? updated : c)));
      setEditing(null);
    } catch (e) {
      setError(errMessage(e));
    }
  };

  const allResolved = cards.length > 0 && cards.every(
    (c) => c.status === "confirmed" || c.status === "skipped",
  );

  return (
    <section
      data-testid="view-spec"
      className="panel flex flex-1 flex-col gap-4 rounded-[14px] p-5"
    >
      <div className="flex items-center justify-between">
        <h2 className="text-base font-semibold text-text">需求确认</h2>
        {reportMd != null && (
          <button
            type="button"
            onClick={() => void generate()}
            disabled={loading}
            className="rounded-[9px] border border-border px-3 py-1.5 text-xs text-text-dim transition hover:bg-card-hover disabled:opacity-50"
          >
            {loading ? "生成中…" : "从报告生成卡片"}
          </button>
        )}
      </div>

      {error && (
        <div className="rounded-[9px] border border-coral/40 bg-coral/10 px-3 py-2 text-sm text-coral">
          {error}
        </div>
      )}

      {reportMd == null ? (
        <p className="text-sm text-text-dim">先在「想法」视图生成分析报告。</p>
      ) : (
        <div className="flex flex-1 gap-4 overflow-hidden">
          <div className="flex w-1/3 flex-col gap-2 overflow-auto rounded-[9px] border border-border bg-card p-3 text-sm">
            <div className="font-medium text-text">报告摘要</div>
            <div className="whitespace-pre-wrap text-text-dim">{reportMd.slice(0, 600)}…</div>
          </div>
          <div className="flex w-2/3 flex-col gap-3 overflow-auto">
            {cards.length === 0 && !loading && (
              <p className="text-sm text-text-dim">点击右上角生成需求卡。</p>
            )}
            {cards.map((card) => (
              <div
                key={card.id}
                className={`rounded-[9px] border p-3 text-sm transition ${
                  card.status === "confirmed"
                    ? "border-success/40 bg-success/5"
                    : card.status === "skipped"
                      ? "border-border bg-card opacity-60"
                      : "border-border bg-card"
                }`}
              >
                {editing === card.id ? (
                  <div className="flex flex-col gap-2">
                    <input
                      value={editForm.title}
                      onChange={(e) => setEditForm((p) => ({ ...p, title: e.target.value }))}
                      className="rounded-[9px] border border-border bg-card px-2 py-1 text-text"
                    />
                    <textarea
                      value={editForm.detail}
                      onChange={(e) => setEditForm((p) => ({ ...p, detail: e.target.value }))}
                      rows={2}
                      className="rounded-[9px] border border-border bg-card px-2 py-1 text-text"
                    />
                    <textarea
                      value={editForm.ac}
                      onChange={(e) => setEditForm((p) => ({ ...p, ac: e.target.value }))}
                      rows={2}
                      placeholder="验收标准，每行一条"
                      className="rounded-[9px] border border-border bg-card px-2 py-1 text-text"
                    />
                    <div className="flex gap-2">
                      <button
                        type="button"
                        onClick={saveEdit}
                        className="rounded-[9px] bg-brand px-2 py-1 text-xs text-white"
                      >
                        保存
                      </button>
                      <button
                        type="button"
                        onClick={() => setEditing(null)}
                        className="rounded-[9px] border border-border px-2 py-1 text-xs text-text-dim"
                      >
                        取消
                      </button>
                    </div>
                  </div>
                ) : (
                  <>
                    <div className="flex items-start justify-between">
                      <div className="font-medium text-text">{card.title}</div>
                      <span className="text-xs text-text-dim">
                        {card.status === "confirmed"
                          ? "已确认"
                          : card.status === "skipped"
                            ? "已跳过"
                            : "待确认"}
                      </span>
                    </div>
                    <p className="mt-1 text-text-dim">{card.detail}</p>
                    <ul className="mt-2 list-inside list-disc text-xs text-text-dim">
                      {card.ac.map((a, i) => (
                        <li key={i}>{a}</li>
                      ))}
                    </ul>
                    <div className="mt-2 flex gap-2">
                      {card.status !== "confirmed" && (
                        <button
                          type="button"
                          onClick={() => void setStatus(card.id, "confirmed")}
                          className="rounded-[9px] bg-success/10 px-2 py-1 text-xs text-success transition hover:bg-success/20"
                        >
                          确认
                        </button>
                      )}
                      {card.status !== "skipped" && (
                        <button
                          type="button"
                          onClick={() => void setStatus(card.id, "skipped")}
                          className="rounded-[9px] border border-border px-2 py-1 text-xs text-text-dim transition hover:bg-card-hover"
                        >
                          跳过
                        </button>
                      )}
                      <button
                        type="button"
                        onClick={() => startEdit(card)}
                        className="rounded-[9px] border border-border px-2 py-1 text-xs text-text-dim transition hover:bg-card-hover"
                      >
                        编辑
                      </button>
                    </div>
                  </>
                )}
              </div>
            ))}
            <div className="mt-2 flex items-center justify-between">
              <span className="text-xs text-text-dim">
                进度：{cards.filter((c) => c.status === "confirmed").length}/
                {cards.length} 已确认
                {cards.some((c) => c.status === "skipped") &&
                  `（${cards.filter((c) => c.status === "skipped").length} 跳过）`}
              </span>
              <button
                type="button"
                onClick={() => void transition("plan")}
                disabled={!allResolved}
                className="rounded-[9px] bg-gradient-to-br from-brand to-brand2 px-4 py-2 text-sm font-semibold text-white transition hover:opacity-90 disabled:opacity-50"
              >
                进入计划
              </button>
            </div>
          </div>
        </div>
      )}

      {clarify && (
        <ClarifyDialog
          questions={clarify}
          onAnswer={handleClarify}
          onCancel={() => setClarify(null)}
        />
      )}
    </section>
  );
}

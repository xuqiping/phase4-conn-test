// 计划视图：需求卡 → chunk 级施工计划；审批后才创建任务并解锁建造（FR-032/AC-035）。
import { useEffect, useState } from "react";
import ClarifyDialog from "../components/clarify/ClarifyDialog";
import { useClarifyRound } from "../hooks/useClarifyRound";
import { generatePlanChunks, type SpecCardDraft } from "../lib/generator";
import { errMessage, ipc, type PlanChunkDto, type SpecCardDto } from "../lib/ipc";
import { useProjectStore } from "../stores/project";

export default function Plan() {
  const projectId = useProjectStore((s) => s.currentId);
  const transition = useProjectStore((s) => s.transition);
  const [specCards, setSpecCards] = useState<SpecCardDto[]>([]);
  const [chunks, setChunks] = useState<PlanChunkDto[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<number | null>(null);
  const [editForm, setEditForm] = useState<{
    title: string;
    goal: string;
    estimated_tokens: string;
    dependencies: string;
  }>({ title: "", goal: "", estimated_tokens: "", dependencies: "" });
  const clarify = useClarifyRound();

  const load = async () => {
    if (projectId == null) return;
    try {
      const [cards, rows] = await Promise.all([
        ipc.listSpecCards(projectId),
        ipc.listPlanChunks(projectId),
      ]);
      setSpecCards(cards);
      setChunks(rows);
    } catch (e) {
      setError(errMessage(e));
    }
  };

  useEffect(() => {
    void load();
  }, [projectId]);

  const generate = async () => {
    if (projectId == null) return;
    const confirmed = specCards.filter(
      (c) => c.status === "confirmed" || c.status === "skipped",
    );
    if (confirmed.length === 0) {
      setError("至少确认 1 张需求卡才能生成计划");
      return;
    }
    setLoading(true);
    setError(null);
    clarify.reset();
    try {
      const agent = await ipc.loadAgentConfig(projectId);
      const drafts: SpecCardDraft[] = confirmed.map((c) => ({
        title: c.title,
        detail: c.detail,
        ac: c.ac,
      }));
      const res = await generatePlanChunks(drafts, agent);
      if (res.clarifyingQuestions.length > 0) {
        if (clarify.open(res.clarifyingQuestions)) {
          setError("信息仍不足，建议人工梳理后重试");
        }
        setLoading(false);
        return;
      }
      const saved = await ipc.savePlanChunks(
        projectId,
        res.content.chunks.map((c) => ({
          ...c,
          estimated_tokens: c.estimated_tokens ?? null,
        })),
      );
      setChunks(saved);
    } catch (e) {
      setError(errMessage(e));
    } finally {
      setLoading(false);
    }
  };

  const handleClarify = async (answers: string[]) => {
    clarify.close();
    // 把追问回答追加到对应需求卡详情（简化处理）
    const appended = answers.map((a, i) => `补充${i + 1}：${a}`).join("；");
    setSpecCards((prev) =>
      prev.map((c, idx) =>
        idx === 0 ? { ...c, detail: c.detail + "\n" + appended } : c
      ),
    );
    await generate();
  };

  const approved = chunks.length > 0 && chunks.every((c) => c.status === "approved");

  const startEdit = (chunk: PlanChunkDto) => {
    setEditing(chunk.id);
    setEditForm({
      title: chunk.title,
      goal: chunk.goal,
      estimated_tokens: chunk.estimated_tokens?.toString() ?? "",
      dependencies: chunk.dependencies.join("\n"),
    });
  };

  const saveEdit = async () => {
    if (editing == null) return;
    try {
      const tokens = editForm.estimated_tokens.trim()
        ? Number(editForm.estimated_tokens)
        : null;
      const updated = await ipc.updatePlanChunk(editing, {
        title: editForm.title,
        goal: editForm.goal,
        estimated_tokens: Number.isNaN(tokens) ? null : tokens,
        dependencies: editForm.dependencies
          .split("\n")
          .map((s) => s.trim())
          .filter(Boolean),
      });
      setChunks((prev) => prev.map((c) => (c.id === editing ? updated : c)));
      setEditing(null);
    } catch (e) {
      setError(errMessage(e));
    }
  };

  const approve = async () => {
    if (projectId == null) return;
    try {
      const rows = await ipc.approvePlan(projectId);
      setChunks(rows);
    } catch (e) {
      setError(errMessage(e));
    }
  };

  const revoke = async () => {
    if (projectId == null) return;
    try {
      const rows = await ipc.revokePlanApproval(projectId);
      setChunks(rows);
    } catch (e) {
      setError(errMessage(e));
    }
  };

  return (
    <section
      data-testid="view-plan"
      className="panel flex flex-1 flex-col gap-4 rounded-[14px] p-5"
    >
      <div className="flex items-center justify-between">
        <h2 className="text-base font-semibold text-text">施工计划</h2>
        <button
          type="button"
          onClick={() => void generate()}
          disabled={loading || approved}
          className="rounded-[9px] border border-border px-3 py-1.5 text-xs text-text-dim transition hover:bg-card-hover disabled:opacity-50"
        >
          {loading ? "生成中…" : "从需求卡生成计划"}
        </button>
      </div>

      {error && (
        <div className="rounded-[9px] border border-coral/40 bg-coral/10 px-3 py-2 text-sm text-coral">
          {error}
        </div>
      )}

      {chunks.length === 0 && !loading && (
        <p className="text-sm text-text-dim">点击右上角生成施工计划。</p>
      )}

      <div className="flex flex-1 flex-col gap-3 overflow-auto">
        {chunks.map((chunk, idx) => (
          <div
            key={chunk.id}
            className={`rounded-[9px] border p-3 text-sm transition ${
              chunk.status === "approved"
                ? "border-success/40 bg-success/5"
                : "border-border bg-card"
            }`}
          >
            {editing === chunk.id ? (
              <div className="flex flex-col gap-2">
                <input
                  value={editForm.title}
                  onChange={(e) => setEditForm((p) => ({ ...p, title: e.target.value }))}
                  className="rounded-[9px] border border-border bg-card px-2 py-1 text-text"
                />
                <textarea
                  value={editForm.goal}
                  onChange={(e) => setEditForm((p) => ({ ...p, goal: e.target.value }))}
                  rows={2}
                  className="rounded-[9px] border border-border bg-card px-2 py-1 text-text"
                />
                <input
                  value={editForm.estimated_tokens}
                  onChange={(e) => setEditForm((p) => ({ ...p, estimated_tokens: e.target.value }))}
                  placeholder="预估 tokens"
                  className="rounded-[9px] border border-border bg-card px-2 py-1 text-text"
                />
                <textarea
                  value={editForm.dependencies}
                  onChange={(e) => setEditForm((p) => ({ ...p, dependencies: e.target.value }))}
                  rows={2}
                  placeholder="依赖，每行一个"
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
                <div className="flex items-center justify-between">
                  <div className="font-medium text-text">
                    {idx + 1}. {chunk.title}
                  </div>
                  <span className="text-xs text-text-dim">
                    {chunk.status === "approved" ? "已审批" : "草稿"}
                  </span>
                </div>
                <p className="mt-1 text-text-dim">{chunk.goal}</p>
                {chunk.estimated_tokens != null && (
                  <div className="mt-1 text-xs text-text-dim">
                    预估：{chunk.estimated_tokens} tokens
                  </div>
                )}
                {chunk.dependencies.length > 0 && (
                  <div className="mt-1 text-xs text-text-dim">
                    依赖：{chunk.dependencies.join("、")}
                  </div>
                )}
                {!approved && (
                  <div className="mt-2 flex gap-2">
                    <button
                      type="button"
                      onClick={() => startEdit(chunk)}
                      className="rounded-[9px] border border-border px-2 py-1 text-xs text-text-dim transition hover:bg-card-hover"
                    >
                      编辑
                    </button>
                  </div>
                )}
              </>
            )}
          </div>
        ))}
      </div>

      <div className="flex items-center justify-between border-t border-border pt-3">
        <span className="text-xs text-text-dim">
          {approved
            ? "计划已审批，tasks 已创建"
            : `${chunks.filter((c) => c.status === "approved").length}/${chunks.length} 已审批`}
        </span>
        <div className="flex gap-2">
          {approved ? (
            <>
              <button
                type="button"
                onClick={() => void revoke()}
                className="rounded-[9px] border border-border px-3 py-2 text-xs text-text-dim transition hover:bg-card-hover"
              >
                撤销审批
              </button>
              <button
                type="button"
                onClick={() => void transition("build")}
                className="rounded-[9px] bg-gradient-to-br from-brand to-brand2 px-4 py-2 text-sm font-semibold text-white transition hover:opacity-90"
              >
                开工
              </button>
            </>
          ) : (
            <button
              type="button"
              onClick={() => void approve()}
              disabled={chunks.length === 0}
              className="rounded-[9px] bg-gradient-to-br from-brand to-brand2 px-4 py-2 text-sm font-semibold text-white transition hover:opacity-90 disabled:opacity-50"
            >
              审批计划
            </button>
          )}
        </div>
      </div>

      {clarify.questions && (
        <ClarifyDialog
          questions={clarify.questions}
          onAnswer={handleClarify}
          onCancel={() => clarify.close()}
        />
      )}
    </section>
  );
}

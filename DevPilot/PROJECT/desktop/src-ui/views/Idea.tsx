// 想法视图：访谈式问答 → 项目分析报告 + 三选一建议（FR-030/AC-033），歧义先追问（FR-044/AC-048）。
import { useState } from "react";
import ClarifyDialog from "../components/clarify/ClarifyDialog";
import { generateIdeaReport, type IdeaReport } from "../lib/generator";
import { errMessage, ipc } from "../lib/ipc";
import { useProjectStore } from "../stores/project";

const QUESTIONS: { key: string; label: string; placeholder: string }[] = [
  {
    key: "target_user",
    label: "做给谁用？",
    placeholder: "例如：65 岁以上独居、不会用智能手机的老人",
  },
  {
    key: "pain",
    label: "解决什么痛点？",
    placeholder: "例如：经常忘记按时吃药，家属无法实时知道",
  },
  {
    key: "competitors",
    label: "现有竞品或替代方案？",
    placeholder: "例如：手机闹钟、家属微信群提醒",
  },
  {
    key: "monetization",
    label: "怎么赚钱或持续运营？",
    placeholder: "例如：免费基础版 + 家属远程监控订阅",
  },
  {
    key: "tech_constraint",
    label: "技术/平台约束？",
    placeholder: "例如：必须是微信小程序，老人不用下载 App",
  },
];

const RECOMMEND_LABEL: Record<IdeaReport["recommendation"], string> = {
  worth_doing: "值得做",
  narrow_down: "建议先缩小范围",
  rethink: "建议再想想",
};

export default function Idea() {
  const projectId = useProjectStore((s) => s.currentId);
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [report, setReport] = useState<IdeaReport | null>(null);
  const [clarify, setClarify] = useState<string[] | null>(null);

  const update = (key: string, value: string) => {
    setAnswers((prev) => ({ ...prev, [key]: value }));
  };

  const generate = async () => {
    if (projectId == null) return;
    setLoading(true);
    setError(null);
    try {
      const agent = await ipc.loadAgentConfig(projectId);
      const res = await generateIdeaReport(answers, agent);
      if (res.clarifyingQuestions.length > 0) {
        setClarify(res.clarifyingQuestions);
        setLoading(false);
        return;
      }
      await ipc.writeProjectFile(
        projectId,
        "workflow_output/docs/项目分析/项目分析报告.md",
        res.content.report_md,
      );
      setReport(res.content);
    } catch (e) {
      setError(errMessage(e));
    } finally {
      setLoading(false);
    }
  };

  const handleClarify = (responses: string[]) => {
    setClarify(null);
    const combined: Record<string, string> = { ...answers };
    clarify?.forEach((q, i) => {
      combined[`clarify_${i}`] = `${q} → ${responses[i]}`;
    });
    setAnswers(combined);
    void generate();
  };

  return (
    <section
      data-testid="view-idea"
      className="panel flex flex-1 flex-col gap-4 rounded-[14px] p-5"
    >
      <h2 className="text-base font-semibold text-text">想法打磨</h2>

      {error && (
        <div className="rounded-[9px] border border-coral/40 bg-coral/10 px-3 py-2 text-sm text-coral">
          {error}
        </div>
      )}

      {report ? (
        <div className="flex flex-col gap-3 overflow-auto">
          <div className="flex items-center gap-2 text-sm">
            <span className="text-text-dim">建议：</span>
            <span className="rounded-full bg-brand/10 px-2 py-0.5 font-medium text-brand">
              {RECOMMEND_LABEL[report.recommendation] ?? report.recommendation}
            </span>
          </div>
          <div className="grid grid-cols-2 gap-3 text-xs">
            {Object.entries(report.summary).map(([k, v]) => (
              <div key={k} className="rounded-[9px] border border-border bg-card p-3">
                <div className="mb-1 text-text-dim">{k}</div>
                <div className="text-text">{v}</div>
              </div>
            ))}
          </div>
          <div className="prose prose-sm max-w-none whitespace-pre-wrap text-sm text-text">
            {report.report_md}
          </div>
          <button
            type="button"
            onClick={() => setReport(null)}
            className="self-start rounded-[9px] border border-border px-3 py-1.5 text-xs text-text-dim transition hover:bg-card-hover"
          >
            重新生成
          </button>
        </div>
      ) : (
        <div className="flex flex-col gap-4">
          {QUESTIONS.map((q) => (
            <label key={q.key} className="flex flex-col gap-1 text-sm">
              <span className="font-medium text-text">{q.label}</span>
              <textarea
                value={answers[q.key] ?? ""}
                onChange={(e) => update(q.key, e.target.value)}
                placeholder={q.placeholder}
                rows={2}
                disabled={loading}
                className="rounded-[9px] border border-border bg-card px-3 py-2 text-text outline-none transition focus:border-brand disabled:opacity-50"
              />
            </label>
          ))}
          <button
            type="button"
            onClick={() => void generate()}
            disabled={loading || QUESTIONS.some((q) => !(answers[q.key]?.trim()))}
            className="self-start rounded-[9px] bg-gradient-to-br from-brand to-brand2 px-4 py-2 text-sm font-semibold text-white transition hover:opacity-90 disabled:opacity-50"
          >
            {loading ? "生成中…" : "生成分析报告"}
          </button>
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

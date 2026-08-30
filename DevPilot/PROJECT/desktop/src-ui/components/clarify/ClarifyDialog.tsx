// 主动反问澄清弹窗：模型遇到歧义时先问用户，禁止自行假设（FR-044 / AC-048）。
import { useState } from "react";

interface Props {
  questions: string[];
  onAnswer: (answers: string[]) => void;
  onCancel: () => void;
}

export default function ClarifyDialog({ questions, onAnswer, onCancel }: Props) {
  const [answers, setAnswers] = useState<string[]>(() => questions.map(() => ""));

  const update = (i: number, value: string) => {
    setAnswers((prev) => {
      const next = [...prev];
      next[i] = value;
      return next;
    });
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div className="panel flex w-full max-w-xl flex-col rounded-[14px] p-6">
        <h3 className="text-base font-semibold text-text">需要先澄清几个问题</h3>
        <p className="mt-1 text-xs text-text-dim">
          你的描述有歧义，AI 不敢瞎猜。回答后重新生成。
        </p>
        <div className="mt-4 flex flex-col gap-3">
          {questions.map((q, i) => (
            <label key={i} className="flex flex-col gap-1 text-sm">
              <span className="text-text">{q}</span>
              <textarea
                value={answers[i]}
                onChange={(e) => update(i, e.target.value)}
                rows={2}
                className="rounded-[9px] border border-border bg-card px-3 py-2 text-text outline-none transition focus:border-brand"
              />
            </label>
          ))}
        </div>
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            className="rounded-[9px] border border-border px-4 py-2 text-sm text-text-dim transition hover:bg-card-hover"
          >
            取消
          </button>
          <button
            type="button"
            onClick={() => onAnswer(answers)}
            className="rounded-[9px] bg-gradient-to-br from-brand to-brand2 px-4 py-2 text-sm font-semibold text-white transition hover:opacity-90"
          >
            提交回答
          </button>
        </div>
      </div>
    </div>
  );
}

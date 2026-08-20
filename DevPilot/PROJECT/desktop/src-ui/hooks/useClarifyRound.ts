// 主动反问轮次控制（FR-044）：超过上限后提示人工梳理，防止无限追问。
import { useCallback, useState } from "react";

export const MAX_CLARIFY_ROUNDS = 3;

export interface ClarifyRoundState {
  /** 已完成的追问轮数 */
  round: number;
  /** 当前待回答的问题 */
  questions: string[] | null;
  /** 是否已达上限 */
  exhausted: boolean;
  /** 打开新一轮追问（若超限返回 true，调用方应自己设置错误提示） */
  open: (questions: string[]) => boolean;
  /** 关闭弹窗 */
  close: () => void;
  /** 重置计数（重新生成/撤销时） */
  reset: () => void;
}

export function useClarifyRound(maxRounds = MAX_CLARIFY_ROUNDS): ClarifyRoundState {
  const [round, setRound] = useState(0);
  const [questions, setQuestions] = useState<string[] | null>(null);
  const exhausted = round >= maxRounds;

  const open = useCallback(
    (qs: string[]) => {
      if (round >= maxRounds) {
        return true;
      }
      setQuestions(qs);
      return false;
    },
    [round, maxRounds],
  );

  const close = useCallback(() => {
    setQuestions(null);
    setRound((r) => r + 1);
  }, []);

  const reset = useCallback(() => {
    setRound(0);
    setQuestions(null);
  }, []);

  return { round, questions, exhausted, open, close, reset };
}

// 大白话文本组件（FR-036）：受全局 plainMode 控制，未翻译时调 cheap 模型。

import { useEffect, useState } from "react";
import { useUiStore } from "../../stores/ui";
import { GLOSSARY, translate } from "../../lib/translator";
import TermBubble from "./TermBubble";

interface Props {
  text: string;
  context?: string;
  className?: string;
}

function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$\u0026");
}

function renderWithTerms(text: string): React.ReactNode[] {
  const terms = Object.keys(GLOSSARY).sort((a, b) => b.length - a.length);
  if (terms.length === 0) return [text];
  const pattern = new RegExp(`\\b(${terms.map(escapeRegex).join("|")})\\b`, "gi");
  const out: React.ReactNode[] = [];
  let last = 0;
  let match: RegExpExecArray | null;
  // eslint-disable-next-line no-cond-assign
  while ((match = pattern.exec(text)) !== null) {
    if (match.index > last) {
      out.push(text.slice(last, match.index));
    }
    out.push(
      <TermBubble key={match.index} term={match[0]}>
        {match[0]}
      </TermBubble>,
    );
    last = match.index + match[0].length;
  }
  if (last < text.length) {
    out.push(text.slice(last));
  }
  return out;
}

export default function PlainText({ text, context, className = "" }: Props) {
  const plainMode = useUiStore((s) => s.plainMode);
  const [plain, setPlain] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!plainMode) {
      setPlain(null);
      setLoading(false);
      return;
    }
    if (plain != null || loading) return;
    setLoading(true);
    translate(text, context)
      .then((t) => {
        setPlain(t);
      })
      .finally(() => setLoading(false));
  }, [plainMode, text, context, plain, loading]);

  const display = plainMode ? plain ?? text : text;

  return (
    <span className={className}>
      {loading && plain == null && (
        <span className="mr-1 text-text-faint">[翻译中…]</span>
      )}
      {renderWithTerms(display)}
    </span>
  );
}

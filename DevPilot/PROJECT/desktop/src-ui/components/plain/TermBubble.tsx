// 术语气泡（FR-036）：悬停显示本地术语表解释。

import { explainTerm } from "../../lib/translator";

interface Props {
  term: string;
  children: React.ReactNode;
}

export default function TermBubble({ term, children }: Props) {
  const explanation = explainTerm(term);
  if (!explanation) {
    return <>{children}</>;
  }

  return (
    <span className="group relative inline cursor-help border-b border-dashed border-text-dim">
      {children}
      <span className="pointer-events-none absolute bottom-full left-1/2 z-50 mb-2 hidden w-56 -translate-x-1/2 rounded-md border border-border bg-card px-3 py-2 text-xs text-text shadow-lg group-hover:block">
        <span className="font-medium text-text">{term}</span>
        <span className="mt-1 block text-text-dim">{explanation}</span>
        <span className="absolute left-1/2 top-full -mt-1 -translate-x-1/2 border-4 border-transparent border-t-border" />
      </span>
    </span>
  );
}

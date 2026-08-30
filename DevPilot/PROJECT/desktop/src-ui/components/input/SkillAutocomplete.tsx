// '/' 技能候选弹层（P07 S5 FR-025/AC-028）：仅当输入以 '/' 开头且首词是某技能前缀时弹出。
// '/usr/bin' 这类路径不弹：前缀匹配不到任何技能名就静默。

import { useEffect, useMemo, useRef } from "react";
import type { SkillDto } from "../../lib/ipc";

export interface SkillAutocompleteProps {
  /** 输入框当前值（判断是否该弹） */
  value: string;
  skills: SkillDto[];
  /** 高亮项下标（键盘 ↑↓ 由父组件维护，保证输入框仍持有焦点） */
  activeIndex: number;
  onActiveIndexChange: (i: number) => void;
  onPick: (skill: SkillDto) => void;
}

/** 从输入里取斜杠首词的过滤前缀；不该弹时返回 null。 */
export function slashQuery(value: string): string | null {
  const m = /^\/([a-z0-9-]*)$/.exec(value.trim());
  if (!m) return null;
  return m[1];
}

/** 过滤候选（按前缀；命中空列表时上层不弹）。 */
export function filterSkills(skills: SkillDto[], query: string): SkillDto[] {
  return skills.filter((s) => s.name.startsWith(query));
}

export default function SkillAutocomplete(props: SkillAutocompleteProps) {
  const { value, skills, activeIndex, onActiveIndexChange, onPick } = props;
  const listRef = useRef<HTMLDivElement>(null);

  const query = slashQuery(value);
  const candidates = useMemo(
    () => (query == null ? [] : filterSkills(skills, query)),
    [query, skills],
  );

  // 候选变化时重置高亮
  useEffect(() => {
    onActiveIndexChange(0);
  }, [query, onActiveIndexChange]);

  // 不该弹：什么都不渲染（父组件也不用拦 Enter）
  if (query == null || candidates.length === 0) return null;

  return (
    <div
      data-testid="skill-autocomplete"
      className="absolute bottom-full left-0 z-10 mb-1 w-full rounded-[9px] border border-border bg-card shadow-lg"
      ref={listRef}
    >
      <div className="border-b border-border px-3 py-1.5 text-[11px] text-text-faint">
        ↑↓ 选择 · Enter 展开 · Esc 关闭
      </div>
      {candidates.map((s, i) => (
        <button
          key={s.name}
          type="button"
          data-testid={`skill-option-${s.name}`}
          onMouseEnter={() => onActiveIndexChange(i)}
          onClick={() => onPick(s)}
          className={`flex w-full items-center gap-2 px-3 py-1.5 text-left text-xs ${
            i === activeIndex ? "bg-brand/20 text-text" : "text-text-dim hover:bg-card"
          }`}
        >
          <span className="font-mono text-brand">/{s.name}</span>
          <span className="truncate">{s.description}</span>
        </button>
      ))}
    </div>
  );
}

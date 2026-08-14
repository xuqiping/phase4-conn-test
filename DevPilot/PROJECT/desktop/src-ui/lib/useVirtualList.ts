// 轻量虚拟列表 hook（PERF-03：只渲染可视窗口 + 上下缓冲）。
// 骨架期自研避免引依赖；行高固定场景够用，变高行后续换 @tanstack/virtual。
import { useMemo, useState, type UIEvent } from "react";

export interface VirtualWindow {
  start: number; // 首渲染行索引
  end: number; // 末渲染行索引（不含）
  offsetTop: number; // 顶部占位高度
  totalHeight: number;
  onScroll: (e: UIEvent<HTMLElement>) => void;
}

export function useVirtualList(
  count: number,
  rowHeight: number,
  viewportHeight: number,
  overscan = 5,
): VirtualWindow {
  const [scrollTop, setScrollTop] = useState(0);
  return useMemo(() => {
    const start = Math.max(0, Math.floor(scrollTop / rowHeight) - overscan);
    const visible = Math.ceil(viewportHeight / rowHeight) + overscan * 2;
    const end = Math.min(count, start + visible);
    return {
      start,
      end,
      offsetTop: start * rowHeight,
      totalHeight: count * rowHeight,
      onScroll: (e: UIEvent<HTMLElement>) =>
        setScrollTop(e.currentTarget.scrollTop),
    };
  }, [count, rowHeight, viewportHeight, overscan, scrollTop]);
}

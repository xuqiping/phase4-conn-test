// 视图元数据（纯数据，无组件引用）——stores 用它，避免与组件层产生循环依赖。
// 组件装配在 viewRegistry.ts。

export type ViewKey =
  | "dashboard"
  | "idea"
  | "spec"
  | "plan"
  | "build"
  | "accept"
  | "deploy"
  | "mcp";

export type RightTab = "spec" | "changes" | "logs" | "preview" | "files";

export interface ViewMeta {
  key: ViewKey;
  label: string;
  icon: string;
  /** 是否为管道条上的阶段（驾驶舱不是阶段） */
  isStage: boolean;
  /** 切到该视图时右栏默认落哪个 Tab（联动点 1） */
  defaultRightTab: RightTab;
}

export const VIEW_META: readonly ViewMeta[] = [
  { key: "dashboard", label: "驾驶舱", icon: "▣", isStage: false, defaultRightTab: "logs" },
  { key: "idea", label: "想法", icon: "💡", isStage: true, defaultRightTab: "spec" },
  { key: "spec", label: "需求", icon: "📋", isStage: true, defaultRightTab: "spec" },
  { key: "plan", label: "计划", icon: "🗺️", isStage: true, defaultRightTab: "spec" },
  { key: "build", label: "建造", icon: "🔨", isStage: true, defaultRightTab: "logs" },
  { key: "accept", label: "验收", icon: "✅", isStage: true, defaultRightTab: "preview" },
  { key: "deploy", label: "部署", icon: "🚀", isStage: true, defaultRightTab: "logs" },
  { key: "mcp", label: "MCP 管理", icon: "🔌", isStage: false, defaultRightTab: "logs" },
] as const;

/** 管道条阶段序列（按注册顺序） */
export const STAGE_META = VIEW_META.filter((v) => v.isStage);

export function viewMeta(key: ViewKey): ViewMeta {
  const meta = VIEW_META.find((v) => v.key === key);
  if (!meta) throw new Error(`未注册的视图: ${key}`);
  return meta;
}

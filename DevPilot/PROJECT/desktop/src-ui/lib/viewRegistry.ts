// 视图注册表：七视图的单一注册点（Step 5 联动点 1 的静态数据源）。
// 阶段推进后由内核状态机驱动 current（Step 7 接事件流后替换 mock）。
import type { ComponentType } from "react";
import Accept from "../views/Accept";
import Build from "../views/Build";
import Dashboard from "../views/Dashboard";
import Deploy from "../views/Deploy";
import Idea from "../views/Idea";
import Plan from "../views/Plan";
import Spec from "../views/Spec";

export type ViewKey =
  | "dashboard"
  | "idea"
  | "spec"
  | "plan"
  | "build"
  | "accept"
  | "deploy";

export type RightTab = "spec" | "changes" | "logs" | "preview" | "files";

export interface ViewDef {
  key: ViewKey;
  label: string;
  icon: string;
  /** 是否为管道条上的阶段（驾驶舱不是阶段） */
  isStage: boolean;
  /** 切到该视图时右栏默认落哪个 Tab（联动点 1） */
  defaultRightTab: RightTab;
  component: ComponentType;
}

export const VIEW_REGISTRY: readonly ViewDef[] = [
  { key: "dashboard", label: "驾驶舱", icon: "▣", isStage: false, defaultRightTab: "logs", component: Dashboard },
  { key: "idea", label: "想法", icon: "💡", isStage: true, defaultRightTab: "spec", component: Idea },
  { key: "spec", label: "需求", icon: "📋", isStage: true, defaultRightTab: "spec", component: Spec },
  { key: "plan", label: "计划", icon: "🗺️", isStage: true, defaultRightTab: "spec", component: Plan },
  { key: "build", label: "建造", icon: "🔨", isStage: true, defaultRightTab: "logs", component: Build },
  { key: "accept", label: "验收", icon: "✅", isStage: true, defaultRightTab: "preview", component: Accept },
  { key: "deploy", label: "部署", icon: "🚀", isStage: true, defaultRightTab: "logs", component: Deploy },
] as const;

/** 管道条阶段序列（按注册顺序） */
export const STAGES = VIEW_REGISTRY.filter((v) => v.isStage);

export function viewDef(key: ViewKey): ViewDef {
  const def = VIEW_REGISTRY.find((v) => v.key === key);
  if (!def) throw new Error(`未注册的视图: ${key}`);
  return def;
}

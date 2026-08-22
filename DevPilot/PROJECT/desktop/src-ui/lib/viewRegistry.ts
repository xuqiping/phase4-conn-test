// 视图注册表：元数据（viewMeta）+ 组件装配。阶段推进后由内核状态机驱动 current。
import type { ComponentType } from "react";
import Accept from "../views/Accept";
import Build from "../views/Build";
import Dashboard from "../views/dashboard/Dashboard";
import Deploy from "../views/Deploy";
import Idea from "../views/Idea";
import Mcp from "../views/Mcp";
import Plan from "../views/Plan";
import Spec from "../views/Spec";
import { VIEW_META, type ViewKey, type ViewMeta } from "./viewMeta";

export type { RightTab, ViewKey } from "./viewMeta";

export interface ViewDef extends ViewMeta {
  component: ComponentType;
}

const COMPONENTS: Record<ViewKey, ComponentType> = {
  dashboard: Dashboard,
  idea: Idea,
  spec: Spec,
  plan: Plan,
  build: Build,
  accept: Accept,
  deploy: Deploy,
  mcp: Mcp,
};

export const VIEW_REGISTRY: readonly ViewDef[] = VIEW_META.map((m) => ({
  ...m,
  component: COMPONENTS[m.key],
}));

/** 管道条阶段序列（按注册顺序） */
export const STAGES = VIEW_REGISTRY.filter((v) => v.isStage);

export function viewDef(key: ViewKey): ViewDef {
  const def = VIEW_REGISTRY.find((v) => v.key === key);
  if (!def) throw new Error(`未注册的视图: ${key}`);
  return def;
}

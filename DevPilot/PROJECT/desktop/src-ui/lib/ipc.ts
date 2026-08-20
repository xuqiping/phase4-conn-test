// IPC 封装：前端与 Rust 内核的全部通话都走这里（类型对齐 src-tauri/src/commands.rs DTO）。
// 测试环境（jsdom 无 Tauri 运行时）用 vi.mock 替换本模块即可。
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

export interface ProjectDto {
  id: number;
  name: string;
  path: string;
  scale: string;
  current_phase: string;
}

export interface PhaseDto {
  key: string;
  label: string;
  status: "done" | "active" | "todo";
}

export interface GateDto {
  key: string;
  label: string;
  checklist: string[];
  passed: boolean;
}

export interface StateDto {
  project_id: number;
  phase: string;
  workflow_version: string;
  phases: PhaseDto[];
  pending_gates: GateDto[];
  allowed_next: string[];
  warning: string | null;
}

export interface AgentConfigFields {
  positioning: string;
  target_users: string;
  tech_stack: string;
  commit_style: string;
  security_redlines: string;
  doc_requirements: string;
  testing_redlines: string;
  naming_style: string;
}

export interface SpecCardDto {
  id: number;
  project_id: number;
  title: string;
  detail: string;
  ac: string[];
  status: "pending" | "confirmed" | "skipped";
}

export interface PlanChunkDto {
  id: number;
  project_id: number;
  title: string;
  goal: string;
  estimated_tokens: number | null;
  dependencies: string[];
  status: "draft" | "approved" | "running" | "done";
}

/** 内核错误（CmdError 序列化形态） */
export interface CmdError {
  code: string;
  message: string;
}

export function errMessage(e: unknown): string {
  let raw: string;
  if (e && typeof e === "object" && "message" in e) {
    raw = String((e as CmdError).message);
  } else {
    raw = String(e);
  }
  // 前端只显示大白话（plan 安全清单）：底层 JS/网络原始错误在此翻译，
  // 不允许英文堆栈直接甩给用户（BUG-P01-01）。
  if (raw.includes("reading 'invoke'") || raw.includes("__TAURI__")) {
    return "内核通信未就绪，请重启应用再试";
  }
  if (raw.includes("Failed to fetch") || raw.includes("NetworkError")) {
    return "网络连接失败，请检查网络后重试";
  }
  return raw;
}

export const ipc = {
  listProjects: () => invoke<ProjectDto[]>("list_projects"),
  createProject: (name: string, parentDir: string | null, scale: string) =>
    invoke<ProjectDto>("create_project", {
      name,
      parentDir,
      scale,
    }),
  getState: (projectId: number) =>
    invoke<StateDto>("get_state", { projectId }),
  transition: (projectId: number, to: string) =>
    invoke<StateDto>("transition", { projectId, to }),
  passGate: (projectId: number, gate: string) =>
    invoke<StateDto>("pass_gate", { projectId, gate }),
  readProjectFile: (projectId: number, relPath: string) =>
    invoke<string>("read_project_file", { projectId, relPath }),
  writeProjectFile: (projectId: number, relPath: string, content: string) =>
    invoke<void>("write_project_file", { projectId, relPath, content }),
  loadAgentConfig: (projectId: number) =>
    invoke<AgentConfigFields>("load_agent_config", { projectId }),
  saveAgentConfig: (projectId: number, fields: AgentConfigFields) =>
    invoke<void>("save_agent_config", { projectId, fields }),
  saveSpecCards: (projectId: number, cards: Omit<SpecCardDto, "id" | "project_id" | "status">[]) =>
    invoke<SpecCardDto[]>("save_spec_cards", { projectId, cards }),
  listSpecCards: (projectId: number) =>
    invoke<SpecCardDto[]>("list_spec_cards", { projectId }),
  updateSpecCard: (id: number, patch: Partial<Omit<SpecCardDto, "id" | "project_id">>) =>
    invoke<SpecCardDto>("update_spec_card", { id, ...patch }),
  savePlanChunks: (
    projectId: number,
    chunks: Omit<PlanChunkDto, "id" | "project_id" | "status">[],
  ) => invoke<PlanChunkDto[]>("save_plan_chunks", { projectId, chunks }),
  listPlanChunks: (projectId: number) =>
    invoke<PlanChunkDto[]>("list_plan_chunks", { projectId }),
  updatePlanChunk: (
    id: number,
    patch: Partial<Omit<PlanChunkDto, "id" | "project_id" | "status">>,
  ) => invoke<PlanChunkDto>("update_plan_chunk", { id, ...patch }),
  approvePlan: (projectId: number) =>
    invoke<PlanChunkDto[]>("approve_plan", { projectId }),
  revokePlanApproval: (projectId: number) =>
    invoke<PlanChunkDto[]>("revoke_plan_approval", { projectId }),
};

/** 订阅内核状态推送（事件名对齐 events.rs）；返回取消订阅函数 */
export function onState(cb: (dto: StateDto) => void): Promise<() => void> {
  return listen<StateDto>("kernel://state", (e) => cb(e.payload));
}

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

/** 内核错误（CmdError 序列化形态） */
export interface CmdError {
  code: string;
  message: string;
}

export function errMessage(e: unknown): string {
  if (e && typeof e === "object" && "message" in e) {
    return String((e as CmdError).message);
  }
  return String(e);
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
};

/** 订阅内核状态推送（事件名对齐 events.rs）；返回取消订阅函数 */
export function onState(cb: (dto: StateDto) => void): Promise<() => void> {
  return listen<StateDto>("kernel://state", (e) => cb(e.payload));
}

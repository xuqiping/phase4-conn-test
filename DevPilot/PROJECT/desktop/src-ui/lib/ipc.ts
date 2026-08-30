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

export interface TaskEventDto {
  id?: number;
  task_id: number;
  event_type: "narrative" | "raw" | "error" | "checkpoint";
  message: string;
  created_at?: string;
}

export interface DiffSummaryDto {
  what_changed: string;
  why: string;
  impact: string;
  risk: string;
  files: string[];
  truncated: boolean;
  raw_diff: string;
}

export interface CheckpointDto {
  id: number;
  task_id: number;
  chunk_no: number;
  round_id: number;
  title: string;
  status: string;
  git_commit: string;
  summary_plain: string;
  created_at: string;
}

export interface TaskDto {
  id: number;
  round_id: number;
  chunk_no: number;
  title: string;
  status: string;
  instructions: string;
  cost_cents: number;
  started_at?: string;
  finished_at?: string;
}

export interface RoundDto {
  id: number;
  seq: number;
  title: string;
  status: string;
  total_tasks: number;
  done_tasks: number;
}

export interface AcceptanceItemDto {
  id: number;
  project_id: number;
  source_file: string;
  tc_id: string;
  title: string;
  steps: string;
  expected: string;
  method: "auto" | "manual";
  status: "pending" | "pass" | "fail" | "na";
  evidence_path?: string;
  fix_task_id?: number;
  sort_order: number;
}

export interface SecurityFindingDto {
  severity: "critical" | "high" | "medium" | "low" | "info";
  category: string;
  message: string;
  file: string;
  line: number;
  snippet?: string;
  suggestion: string;
}

export interface SecurityScanDto {
  status: "pass" | "fail" | "partial";
  findings: SecurityFindingDto[];
  gate_passed: boolean;
  warning: string | null;
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
  enterAcceptance: (projectId: number) =>
    invoke<StateDto>("enter_acceptance", { projectId }),
  requestRelease: (projectId: number) =>
    invoke<StateDto>("request_release", { projectId }),
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
  executeBuild: (
    projectId: number,
    accessToken: string,
    cloudBase?: string,
  ) =>
    invoke<StateDto>("execute_build", { projectId, accessToken, cloudBase }),
  summarizeDiff: (
    projectId: number,
    taskId: number,
    accessToken: string,
    cloudBase?: string,
  ) =>
    invoke<DiffSummaryDto>("summarize_diff", {
      projectId,
      taskId,
      accessToken,
      cloudBase,
    }),
  listCheckpoints: (projectId: number) =>
    invoke<CheckpointDto[]>("list_checkpoints", { projectId }),
  rollbackToCheckpoint: (checkpointId: number) =>
    invoke<StateDto>("rollback_to_checkpoint", { checkpointId }),
  listTasks: (projectId: number, roundId?: number) =>
    invoke<TaskDto[]>("list_tasks", { projectId, roundId }),
  listRounds: (projectId: number) => invoke<RoundDto[]>("list_rounds", { projectId }),
  listTaskEvents: (taskId: number) =>
    invoke<TaskEventDto[]>("list_task_events", { taskId }),
  continueTask: (
    projectId: number,
    instructions: string,
    accessToken: string,
    cloudBase?: string,
  ) =>
    invoke<StateDto>("continue_task", {
      projectId,
      instructions,
      accessToken,
      cloudBase,
    }),
  getAcceptanceChecklist: (projectId: number) =>
    invoke<AcceptanceItemDto[]>("get_acceptance_checklist", { projectId }),
  regenerateAcceptanceChecklist: (projectId: number) =>
    invoke<AcceptanceItemDto[]>("regenerate_acceptance_checklist", { projectId }),
  updateAcceptanceItem: (
    id: number,
    patch: { status?: AcceptanceItemDto["status"]; evidence_path?: string },
  ) =>
    invoke<AcceptanceItemDto>("update_acceptance_item", { id, ...patch }),
  runSecurityScan: (projectId: number) =>
    invoke<SecurityScanDto>("run_security_scan", { projectId }),
  createFixTask: (req: {
    projectId: number;
    acceptanceItemId?: number;
    selector: string;
    description: string;
  }) =>
    invoke<number>("create_fix_task", {
      req: {
        project_id: req.projectId,
        acceptance_item_id: req.acceptanceItemId ?? null,
        selector: req.selector,
        description: req.description,
      },
    }),
  runSmokeCheck: (projectId: number, baseUrl?: string) =>
    invoke<{ passed: number; failed: number; skipped: number; warning: string | null }>(
      "run_smoke_check",
      { projectId, baseUrl: baseUrl ?? null },
    ),
  runTask: (req: {
    projectId: number;
    taskId: number;
    title: string;
    instructions: string;
    files: { path: string; content: string }[];
  }) =>
    invoke<{
      success: boolean;
      phase: string;
      diff_summary: string;
      cost_cents: number;
    }>("run_task", { req: { ...req, project_id: req.projectId, task_id: req.taskId } }),
  // P07：技能斜杠调用
  listSkills: () => invoke<SkillDto[]>("list_skills"),
  invokeSkill: (name: string, taskId?: number) =>
    invoke<string>("invoke_skill", { name, taskId: taskId ?? null }),
  // P07：技能生成器 + 导入导出
  saveSkillFromContext: (req: {
    name: string;
    description: string;
    taskPrompt: string;
    roundsSummary: string;
    taskId?: number;
  }) =>
    invoke<string>("save_skill_from_context", {
      name: req.name,
      description: req.description,
      taskPrompt: req.taskPrompt,
      roundsSummary: req.roundsSummary,
      taskId: req.taskId ?? null,
    }),
  exportSkill: (skillId: number, destDir: string, taskId?: number) =>
    invoke<string>("export_skill", { skillId, destDir, taskId: taskId ?? null }),
  importSkills: (srcDir: string, taskId?: number) =>
    invoke<{ name: string; result: string }[]>("import_skills", {
      srcDir,
      taskId: taskId ?? null,
    }),
  // P07：MCP 市场
  listMcpMarket: () => invoke<MarketEntryDto[]>("list_mcp_market"),
  installMcpServer: (name: string, userEnv: Record<string, string>) =>
    invoke<McpInstallDto>("install_mcp_server", { name, userEnv }),
  addMcpManual: (configJson: string) =>
    invoke<McpInstallDto>("add_mcp_manual", { configJson }),
  // P07 S6：MCP 管理页
  listMcpServers: () => invoke<McpServerDto[]>("list_mcp_servers"),
  mcpStart: (id: number) => invoke<string>("mcp_start", { id }),
  mcpStop: (id: number) => invoke<string>("mcp_stop", { id }),
  mcpRestart: (id: number) => invoke<string>("mcp_restart", { id }),
  mcpUninstall: (id: number) => invoke<string>("mcp_uninstall", { id }),
  mcpLogs: (id: number) => invoke<string[]>("mcp_logs", { id }),
  // P07 S7：多模态输入
  saveAttachment: (projectId: number, bytes: Uint8Array) =>
    invoke<AttachmentDto>("save_attachment", { projectId, bytes: Array.from(bytes) }),
  deleteAttachment: (id: number) => invoke<void>("delete_attachment", { id }),
  voiceProbe: () => invoke<boolean>("voice_probe"),
  voiceTranscribe: (audio: Uint8Array) =>
    invoke<string>("voice_transcribe", { audio: Array.from(audio) }),
};

export interface AttachmentDto {
  id: number;
  path: string;
  source_kb: number;
}

export interface McpServerDto {
  id: number;
  name: string;
  description: string;
  command: string;
  status: string;
  enabled: boolean;
  restart_count: number;
  last_error: string;
}

export interface SkillDto {
  id: number;
  name: string;
  display_name: string;
  description: string;
  version: string;
  status: string;
}

export interface MarketEntryDto {
  name: string;
  description: string;
  runtime: string;
  command: string;
  args: string[];
  env: { key: string; description: string; required: boolean }[];
}

export interface McpInstallDto {
  id: number;
  outcome: string;
  message: string;
}

/** 订阅任务事件流（事件名对齐 events.rs）；返回取消订阅函数 */
export function onTaskEvent(
  cb: (dto: TaskEventDto) => void,
): Promise<() => void> {
  return listen<TaskEventDto>("kernel://task-event", (e) => cb(e.payload));
}

/** 订阅内核状态推送（事件名对齐 events.rs）；返回取消订阅函数 */
export function onState(cb: (dto: StateDto) => void): Promise<() => void> {
  return listen<StateDto>("kernel://state", (e) => cb(e.payload));
}

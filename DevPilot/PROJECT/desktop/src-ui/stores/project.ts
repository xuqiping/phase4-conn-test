// 项目与内核状态 store：前端不存业务真相，快照来自内核（plan 坑点表）。
// 每次快照更新后视图归位到内核阶段（联动点 1/4）。
import { create } from "zustand";
import { errMessage, ipc, onState, type ProjectDto, type StateDto } from "../lib/ipc";
import { type ViewKey } from "../lib/viewMeta";
import { useUiStore } from "./ui";

interface ProjectState {
  projects: ProjectDto[];
  currentId: number | null;
  snapshot: StateDto | null;
  /** 大白话错误（toast 展示） */
  error: string | null;
  wizardOpen: boolean;

  /** 启动时调用：拉项目列表 + 订阅内核事件（断连重连 = 重拉快照） */
  init: () => Promise<void>;
  openWizard: () => void;
  closeWizard: () => void;
  dismissError: () => void;
  create: (name: string, parentDir: string | null, scale: string) => Promise<boolean>;
  select: (id: number) => Promise<void>;
  transition: (to: string) => Promise<void>;
  passGate: (gate: string) => Promise<void>;
}

/** 应用内核快照（导出供测试：归位策略是联动点 1 的测试对象） */
export function applySnapshot(dto: StateDto) {
  const prev = useProjectStore.getState().snapshot;
  useProjectStore.setState({ snapshot: dto, currentId: dto.project_id });
  // 仅当阶段真变时才把视图归位到内核阶段——用户正看驾驶舱/存档点列表时
  // 不被内核推送事件拽走（交叉审查做偏-1）。首次快照（prev 为空）也归位。
  if (prev?.phase !== dto.phase) {
    useUiStore.getState().setView(dto.phase as ViewKey);
  }
  if (dto.warning) {
    useProjectStore.setState({ error: dto.warning });
  }
}

export const useProjectStore = create<ProjectState>((set, get) => ({
  projects: [],
  currentId: null,
  snapshot: null,
  error: null,
  wizardOpen: false,

  init: async () => {
    try {
      const projects = await ipc.listProjects();
      set({ projects, wizardOpen: projects.length === 0 });
      if (projects.length > 0) {
        await get().select(projects[projects.length - 1].id);
      }
    } catch (e) {
      set({ error: errMessage(e) });
    }
    await onState((dto) => {
      if (dto.project_id === get().currentId) applySnapshot(dto);
    });
  },

  openWizard: () => set({ wizardOpen: true }),
  closeWizard: () => set({ wizardOpen: false }),
  dismissError: () => set({ error: null }),

  create: async (name, parentDir, scale) => {
    try {
      const p = await ipc.createProject(name, parentDir, scale);
      set((s) => ({ projects: [...s.projects, p], wizardOpen: false }));
      await get().select(p.id);
      return true;
    } catch (e) {
      set({ error: errMessage(e) });
      return false;
    }
  },

  select: async (id) => {
    try {
      applySnapshot(await ipc.getState(id));
    } catch (e) {
      set({ error: errMessage(e) });
    }
  },

  transition: async (to) => {
    const id = get().currentId;
    if (id == null) return;
    try {
      applySnapshot(await ipc.transition(id, to));
      set({ error: null });
    } catch (e) {
      set({ error: errMessage(e) }); // 门禁拦截等大白话错误走 toast
    }
  },

  passGate: async (gate) => {
    const id = get().currentId;
    if (id == null) return;
    try {
      applySnapshot(await ipc.passGate(id, gate));
    } catch (e) {
      set({ error: errMessage(e) });
    }
  },
}));

// 供测试重置用
export function resetProjectStore() {
  useProjectStore.setState({
    projects: [],
    currentId: null,
    snapshot: null,
    error: null,
    wizardOpen: false,
  });
}

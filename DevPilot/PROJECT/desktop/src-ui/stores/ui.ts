// 全局 UI 偏好 + 当前视图（Step 5 起）。
// 注意：业务状态（真实阶段）单一真相源在 Rust 内核——这里只存「界面正在看哪个视图」，
// Step 7 接事件流后由内核事件驱动 setView。
import { create } from "zustand";
import { type RightTab, type ViewKey, viewMeta } from "../lib/viewMeta";

export type Density = "comfort" | "compact";

const PLAIN_MODE_KEY = "devpilot_plain_mode";

interface UiState {
  density: Density;
  toggleDensity: () => void;
  /** 大白话翻译层全局开关（FR-036） */
  plainMode: boolean;
  togglePlainMode: () => void;
  /** 当前视图（驾驶舱/六阶段） */
  view: ViewKey;
  /** 右栏当前 Tab；切视图时自动归位到该视图默认 Tab（联动点 1 正向+反向） */
  rightTab: RightTab;
  setView: (v: ViewKey) => void;
  setRightTab: (t: RightTab) => void;
}

export const useUiStore = create<UiState>((set) => ({
  density: "comfort",
  toggleDensity: () =>
    set((s) => ({ density: s.density === "comfort" ? "compact" : "comfort" })),
  plainMode: localStorage.getItem(PLAIN_MODE_KEY) === "1",
  togglePlainMode: () =>
    set((s) => {
      const next = !s.plainMode;
      localStorage.setItem(PLAIN_MODE_KEY, next ? "1" : "0");
      return { plainMode: next };
    }),
  view: "dashboard",
  rightTab: viewMeta("dashboard").defaultRightTab,
  setView: (view) => set({ view, rightTab: viewMeta(view).defaultRightTab }),
  setRightTab: (rightTab) => set({ rightTab }),
}));

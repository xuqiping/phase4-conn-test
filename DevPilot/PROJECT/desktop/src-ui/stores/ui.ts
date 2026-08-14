// 全局 UI 偏好（与业务状态无关；业务状态单一真相源在 Rust 内核，见 plan 坑点表）。
// P01 Step 3：仅密度模式。右栏 Tab / 当前视图在 Step 5 挂视图注册表时扩展。
import { create } from "zustand";

export type Density = "comfort" | "compact";

interface UiState {
  density: Density;
  toggleDensity: () => void;
}

export const useUiStore = create<UiState>((set) => ({
  density: "comfort",
  toggleDensity: () =>
    set((s) => ({ density: s.density === "comfort" ? "compact" : "comfort" })),
}));

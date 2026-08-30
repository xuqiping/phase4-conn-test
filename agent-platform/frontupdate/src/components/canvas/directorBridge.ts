import type { InjectionKey } from 'vue'

/**
 * 导演台节点 → 画布桥（Step 7）。
 * Vue Flow 自定义节点组件的 emit 不冒泡到父（已知行为），
 * 节点卡片「打开导演台」按钮走 inject 调本桥 → CanvasBoard emit 上抛 CanvasView 开 modal。
 * InjectionKey 带类型：拿错形状编译期即报。
 */
export interface DirectorBridge {
  openEditor(nodeId: string): void
}

export const DIRECTOR_BRIDGE_KEY: InjectionKey<DirectorBridge> = Symbol('director-bridge')

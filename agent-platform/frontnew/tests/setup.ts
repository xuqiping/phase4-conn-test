import { vi } from 'vitest'
import { h } from 'vue'

/**
 * 单测环境无 VueFlow 容器上下文：Handle/useNodeId 等会炸。
 * 统一 mock @vue-flow/core：Handle 渲染占位 span，Position 保留枚举值。
 * 每个含节点组件的 spec 文件都会经 setup 引用。
 */
vi.mock('@vue-flow/core', () => ({
  Handle: (props: Record<string, unknown>) => h('span', { class: 'mock-handle', 'data-type': props.type }),
  Position: { Top: 'top', Bottom: 'bottom', Left: 'left', Right: 'right' }
}))

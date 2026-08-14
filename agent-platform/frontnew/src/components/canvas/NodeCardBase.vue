<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { NIcon } from 'naive-ui'
import {
  CheckmarkCircle,
  AlertCircle,
  TimeOutline,
  HardwareChipOutline
} from '@vicons/ionicons5'
import type { CanvasNodeStatus, NodeKind } from '@/mocks/types'

/**
 * 节点卡片基座：头部/内容槽/底部状态条/连接桩（上入下出，对齐 frontend）。
 * props 命名与 frontend 现有 CanvasNodeBase 对齐（除 assetBadge 外），合回零改接口。
 * 零内联样式：类型色走 --node-kind（由 .node-card--{kind} 类映射 --kind-*），
 * 状态视觉全由状态类名 + CSS 变量派生。
 */
const props = defineProps<{
  kind: NodeKind
  kindLabel: string
  label?: string
  status?: CanvasNodeStatus
  selected?: boolean
  sceneNo?: string
  durationMs?: number
  tokens?: number
}>()

const STATUS_LABEL: Record<CanvasNodeStatus, string> = {
  idle: '待生成',
  running: '生成中',
  success: '完成',
  failed: '失败'
}

const statusLabel = computed(() => (props.status ? STATUS_LABEL[props.status] : ''))
const durationText = computed(() =>
  props.durationMs ? `${(props.durationMs / 1000).toFixed(1)}s` : ''
)
</script>

<template>
  <div
    class="node-card"
    :class="[
      `node-card--${kind}`,
      { 'node-card--selected': selected },
      status ? `node-card--${status}` : ''
    ]"
  >
    <Handle type="target" :position="Position.Top" class="node-card__handle" />

    <!-- running 流光描边层（T1/T3；其他主题 --running-border 为 none 自动隐藏） -->
    <div class="node-card__stream" aria-hidden="true" />

    <header class="node-card__header">
      <span class="node-card__icon"><n-icon :size="14"><slot name="icon" /></n-icon></span>
      <span class="node-card__kind">{{ kindLabel }}</span>
      <span v-if="label" class="node-card__label" :title="label">{{ label }}</span>
      <span v-else class="node-card__label node-card__label--empty">未命名</span>
      <span v-if="sceneNo" class="node-card__scene">{{ sceneNo }}</span>
      <span class="node-card__dot" :title="statusLabel" />
    </header>

    <div class="node-card__body">
      <slot />
    </div>

    <footer class="node-card__footer">
      <span class="node-card__status">
        <n-icon v-if="status === 'success'" :size="12"><CheckmarkCircle /></n-icon>
        <n-icon v-else-if="status === 'failed'" :size="12"><AlertCircle /></n-icon>
        {{ statusLabel }}
      </span>
      <span class="node-card__meta">
        <span v-if="durationText" class="node-card__meta-item">
          <n-icon :size="11"><TimeOutline /></n-icon>{{ durationText }}
        </span>
        <span v-if="tokens" class="node-card__meta-item">
          <n-icon :size="11"><HardwareChipOutline /></n-icon>{{ tokens }}
        </span>
      </span>
      <!-- T4 运行中：时间轴播放头扫描条 -->
      <span class="node-card__timeline" aria-hidden="true"><i /></span>
    </footer>

    <Handle type="source" :position="Position.Bottom" class="node-card__handle" />
  </div>
</template>

<style lang="scss" scoped>
.node-card {
  // 类型色注入：--node-kind 供辉光/描边/图标统一取色
  &--text { --node-kind: var(--kind-text); }
  &--image { --node-kind: var(--kind-image); }
  &--video { --node-kind: var(--kind-video); }
  &--audio { --node-kind: var(--kind-audio); }
  &--script { --node-kind: var(--kind-script); }
  &--storyboard { --node-kind: var(--kind-storyboard); }

  position: relative;
  width: 220px;
  border-radius: var(--r-lg);
  background: var(--node-bg, var(--sf-2));
  backdrop-filter: var(--node-blur);
  border: 1px solid var(--line-1);
  color: var(--tx-1);
  font-size: var(--fs-sm);
  transition: border-color var(--d-fast) var(--ease), box-shadow var(--d-fast) var(--ease),
    transform var(--d-fast) var(--ease);

  &:hover {
    border-color: var(--node-kind);
    transform: translateY(-1px);
  }

  // 选中：类型色辉光（T2 无辉光，退化为 2px 类型色描边）
  &--selected {
    border-color: var(--node-kind);
    box-shadow: var(--node-glow, var(--glow-accent));
  }
  :global([data-theme='calm-slate']) &--selected {
    border-width: 2px;
    box-shadow: none;
  }

  // 失败：红描边醒目
  &--failed {
    border-color: var(--err);
  }

  // ---------- 流光描边层（running 且主题提供 --running-border 时显示） ----------
  &__stream {
    display: none;
  }
  &--running &__stream {
    display: block;
    position: absolute;
    inset: -1px;
    border-radius: calc(var(--r-lg) + 1px);
    padding: 1px;
    background: var(--running-border);
    -webkit-mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
    -webkit-mask-composite: xor;
    mask-composite: exclude;
    pointer-events: none;
    overflow: hidden;

    &::before {
      content: '';
      position: absolute;
      inset: -100%;
      background: var(--running-border);
      animation: var(--running-anim, none);
    }
  }
  // 主题没给流光（T2/T4）时隐藏，各自由状态点/时间轴表达
  :global([data-theme='calm-slate']) &--running &__stream,
  :global([data-theme='cineon']) &--running &__stream {
    display: none;
  }

  // ---------- 头部 ----------
  &__header {
    display: flex;
    align-items: center;
    gap: var(--sp-2);
    padding: var(--sp-2) var(--sp-3);
    border-bottom: 1px solid var(--line-1);
    border-left: 3px solid var(--node-kind);
    border-radius: var(--r-lg) 0 0 0;
  }

  &__icon {
    color: var(--node-kind);
    display: grid;
    place-items: center;
    flex-shrink: 0;
  }

  &__kind {
    color: var(--node-kind);
    font-size: var(--fs-xs);
    font-weight: 600;
    flex-shrink: 0;
  }

  &__label {
    color: var(--tx-1);
    font-size: var(--fs-sm);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;

    &--empty {
      color: var(--tx-3);
    }
  }

  &__scene {
    margin-left: auto;
    font-family: var(--font-mono);
    font-size: 10px;
    color: var(--tx-3);
    border: 1px solid var(--line-1);
    border-radius: var(--r-sm);
    padding: 0 4px;
    flex-shrink: 0;
  }

  // 状态点
  &__dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    flex-shrink: 0;
    background: var(--tx-3);
  }
  &--running &__dot {
    background: var(--info);
    animation: breathe 1.2s ease-in-out infinite;
  }
  &--success &__dot {
    background: var(--ok);
  }
  &--failed &__dot {
    background: var(--err);
  }

  // ---------- 内容 ----------
  &__body {
    padding: var(--sp-3);
    min-height: 40px;
  }

  // ---------- 底部 ----------
  &__footer {
    position: relative;
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: var(--sp-1) var(--sp-3) var(--sp-2);
    border-top: 1px solid var(--line-1);
  }

  &__status {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-size: var(--fs-xs);
    color: var(--tx-2);
  }
  &--success &__status {
    color: var(--ok);
  }
  &--failed &__status {
    color: var(--err);
  }
  &--running &__status {
    color: var(--info);
  }

  &__meta {
    display: inline-flex;
    gap: var(--sp-2);
  }
  &__meta-item {
    display: inline-flex;
    align-items: center;
    gap: 2px;
    font-family: var(--font-mono);
    font-size: 10px;
    color: var(--tx-3);
  }

  // T4 时间轴：running 时播放头扫描；success 满格 ok 色
  &__timeline {
    display: none;
  }
  :global([data-theme='cineon']) &__timeline {
    display: block;
    position: absolute;
    left: var(--sp-3);
    right: var(--sp-3);
    bottom: 2px;
    height: 2px;
    background: var(--line-1);
    overflow: hidden;

    i {
      display: block;
      height: 100%;
      width: 0;
      background: var(--accent);
    }
  }
  :global([data-theme='cineon']) &--running &__timeline i {
    width: 40%;
    animation: scan 1.6s var(--ease) infinite;
  }
  :global([data-theme='cineon']) &--success &__timeline i {
    width: 100%;
    background: var(--ok);
  }
  :global([data-theme='cineon']) &--failed &__timeline i {
    width: 100%;
    background: var(--err);
  }

  // ---------- 连接桩 ----------
  &__handle {
    width: 10px;
    height: 10px;
    background: var(--sf-3);
    border: 2px solid var(--node-kind);
    transition: transform var(--d-fast) var(--ease);

    &:hover {
      transform: scale(1.4);
    }
  }
}
</style>

<template>
  <teleport to="body">
    <div class="annotate-overlay" @mouseup="onMouseUp" @mousemove="onMouseMove">
      <div class="annotate-overlay__bar">
        <span class="annotate-overlay__title">彩色标注 · 在图上拖框标记要修改的区域</span>
        <!-- 8 色板：选色给下一个新框；已选框时点色即改该框色（L4-3 改色即时生效） -->
        <div class="annotate-overlay__palette" role="radiogroup" aria-label="标注颜色">
          <button
            v-for="(hex, key) in ANNOTATE_COLORS"
            :key="key"
            type="button"
            class="annotate-overlay__swatch"
            :style="{ background: hex }"
            :aria-checked="activeColor === key"
            :title="ANNOTATE_COLOR_NAMES[key]"
            @click="onPickColor(key)"
          />
        </div>
        <n-button size="small" quaternary @click="emit('cancel')">取消</n-button>
        <n-button size="small" :disabled="!hasValidBoxes" @click="onConfirm('confirm-annotate')">
          生成标注图
        </n-button>
        <n-button size="small" type="primary" :disabled="!hasValidBoxes" @click="onConfirm('confirm-ai')">
          AI 修改
        </n-button>
      </div>
      <div class="annotate-overlay__body">
        <div ref="stageRef" class="annotate-overlay__stage" @mousedown="onStageMouseDown">
          <img v-if="previewUrl" :src="previewUrl" class="annotate-overlay__img" alt="标注底图" draggable="false" />
          <div
            v-for="(b, i) in boxes"
            :key="i"
            class="annotate-overlay__box"
            :class="{ 'annotate-overlay__box--selected': selectedIdx === i }"
            :style="boxStyle(b)"
            :title="`第${i + 1}框 · ${ANNOTATE_COLOR_NAMES[b.color]}`"
            @mousedown.stop="selectedIdx = i"
          >
            <span class="annotate-overlay__badge" :style="{ background: ANNOTATE_COLORS[b.color] }">{{ i + 1 }}</span>
          </div>
          <div v-if="!previewUrl" class="annotate-overlay__empty">该图节点尚无可预览图片</div>
        </div>
        <!-- 已选框编辑：改色/改指令/删框（L4-1 逐框指令、L4-3 改色、L4-2 删框） -->
        <div v-if="selectedBox" class="annotate-overlay__editor">
          <div class="annotate-overlay__editor-title">第 {{ (selectedIdx ?? 0) + 1 }} 框 · {{ ANNOTATE_COLOR_NAMES[selectedBox.color] }}</div>
          <n-input
            :value="selectedBox.text"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-count
            size="small"
            placeholder="该区域要怎么改？（一句中文指令，AI 修改时逐框拼入提示词）"
            @update:value="(v: string) => { if (selectedBox) selectedBox.text = v }"
          />
          <n-button size="tiny" quaternary type="error" @click="removeSelected">删除此框</n-button>
        </div>
      </div>
      <div class="annotate-overlay__hint">
        在图上拖拽画框（最多 {{ MAX_BOXES }} 个）→ 点框选色改指令 →「生成标注图」产标注新图节点；
        「AI 修改」以 [原图, 标注图] 为参考图 + 逐框指令提交重绘（无蒙版 API 的准局部重绘，真蒙版待供应商支持）。
      </div>
    </div>
  </teleport>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { NButton, NInput } from 'naive-ui'
import type { AnnotateBoxPayload, AnnotateColor } from '@/api/canvas'
import { ANNOTATE_COLOR_NAMES } from '@/api/canvas'

/** 确认载荷：归一化框（服务端合成用）+ 逐框指令（前端拼 AI prompt 用，不上传后端）。 */
export interface AnnotateConfirmPayload {
  boxes: AnnotateBoxPayload[]
  instructions: Array<{ index: number; color: AnnotateColor; text: string }>
}

/** 8 色板展示色（与后端 ANNOTATE_COLORS 同值；键为白名单，值仅本组件绘制用）。 */
const ANNOTATE_COLORS: Record<AnnotateColor, string> = {
  red: '#ff0000',
  orange: '#ff8c00',
  yellow: '#ffd700',
  green: '#00b43c',
  cyan: '#00bebe',
  blue: '#1e6eff',
  purple: '#963cff',
  magenta: '#ff00c8'
}

/** 框数上限（与后端 MAX_ANNOTATE_BOXES 同口径）。 */
const MAX_BOXES = 8

/** 拖拽中的草稿框（px 相对 stage；确认时归一化 0-1）。 */
interface BoxDraft {
  x: number
  y: number
  w: number
  h: number
  color: AnnotateColor
  text: string
}

defineProps<{
  /** 底图预览 URL（blob objectURL，会话级）。 */
  previewUrl?: string
}>()

const emit = defineEmits<{
  /** 出口①：仅合成标注图 → 新图节点。 */
  (e: 'confirm-annotate', payload: AnnotateConfirmPayload): void
  /** 出口②：AI 修改（参考图=[原图,标注图] + 逐框指令）→ 下游图节点。 */
  (e: 'confirm-ai', payload: AnnotateConfirmPayload): void
  (e: 'cancel'): void
}>()

const stageRef = ref<HTMLElement | null>(null)
const boxes = ref<BoxDraft[]>([])
const selectedIdx = ref<number | null>(null)
const activeColor = ref<AnnotateColor>('red')
let dragIdx: number | null = null
let dragStart = { x: 0, y: 0 }

const selectedBox = computed(() => (selectedIdx.value == null ? null : boxes.value[selectedIdx.value] ?? null))

/** 有效框（≥8px 才算画成；抖动微框确认时自动丢弃）。 */
const hasValidBoxes = computed(() => boxes.value.some(b => b.w >= 8 && b.h >= 8))

function onStageMouseDown(e: MouseEvent) {
  e.preventDefault()
  e.stopPropagation()
  const stage = stageRef.value
  if (!stage || boxes.value.length >= MAX_BOXES) return
  const r = stage.getBoundingClientRect()
  dragStart = { x: e.clientX - r.left, y: e.clientY - r.top }
  boxes.value.push({ x: dragStart.x, y: dragStart.y, w: 0, h: 0, color: activeColor.value, text: '' })
  dragIdx = boxes.value.length - 1
  selectedIdx.value = dragIdx
}

function onMouseMove(e: MouseEvent) {
  if (dragIdx == null || !stageRef.value) return
  const box = boxes.value[dragIdx]
  if (!box) return
  const r = stageRef.value.getBoundingClientRect()
  const cx = Math.max(0, Math.min(e.clientX - r.left, r.width))
  const cy = Math.max(0, Math.min(e.clientY - r.top, r.height))
  box.x = Math.min(dragStart.x, cx)
  box.y = Math.min(dragStart.y, cy)
  box.w = Math.abs(cx - dragStart.x)
  box.h = Math.abs(cy - dragStart.y)
}

function onMouseUp() {
  // 抖动微框（<8px）= 单击误触，当场删——数组里只留真框，确认时序号与预览徽标一致
  if (dragIdx != null) {
    const box = boxes.value[dragIdx]
    if (box && box.w < 8 && box.h < 8) {
      boxes.value.splice(dragIdx, 1)
      selectedIdx.value = null
    }
  }
  dragIdx = null
}

function onPickColor(key: AnnotateColor) {
  activeColor.value = key
  // 已选框：点色即改该框色（即时反映在预览框上）
  if (selectedBox.value) selectedBox.value.color = key
}

function removeSelected() {
  if (selectedIdx.value == null) return
  boxes.value.splice(selectedIdx.value, 1)
  selectedIdx.value = null
}

/** px → 归一化 0-1（按 stage 尺寸；服务端按源图自然像素换算画框）。 */
function onConfirm(evt: 'confirm-annotate' | 'confirm-ai') {
  const stage = stageRef.value
  if (!stage || !hasValidBoxes.value) return
  const r = stage.getBoundingClientRect()
  if (r.width <= 0 || r.height <= 0) return
  // onMouseUp 已剔除微框，数组即有效框；instructions.index 与服务端徽标序号（1..n 顺序）一致
  const payload: AnnotateConfirmPayload = {
    boxes: boxes.value.map(b => ({
      x: b.x / r.width,
      y: b.y / r.height,
      w: b.w / r.width,
      h: b.h / r.height,
      color: b.color
    })),
    instructions: boxes.value.map((b, i) => ({ index: i + 1, color: b.color, text: b.text.trim() }))
  }
  // 类型化 emit 不接受事件名联合，分流各调一次
  if (evt === 'confirm-annotate') emit('confirm-annotate', payload)
  else emit('confirm-ai', payload)
}

function boxStyle(b: BoxDraft) {
  return {
    left: `${b.x}px`,
    top: `${b.y}px`,
    width: `${b.w}px`,
    height: `${b.h}px`,
    borderColor: ANNOTATE_COLORS[b.color],
    background: `${ANNOTATE_COLORS[b.color]}4d` // 30% 半透明（0x4D=77，与后端 alpha 同值）
  }
}
</script>

<style lang="scss" scoped>
.annotate-overlay {
  position: fixed;
  inset: 0;
  z-index: 2000;
  background: rgba(0, 0, 0, 0.88);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-3);
  user-select: none;

  &__bar {
    display: flex;
    align-items: center;
    gap: var(--spacing-2);
    color: var(--color-text-primary);
    flex-wrap: wrap;
    justify-content: center;
  }

  &__title {
    font-size: var(--font-size-sm);
    margin-right: var(--spacing-2);
  }

  &__palette {
    display: flex;
    gap: var(--spacing-1);
  }

  &__swatch {
    width: 18px;
    height: 18px;
    border-radius: 50%;
    border: 2px solid transparent;
    cursor: pointer;
    padding: 0;

    &[aria-checked='true'] {
      border-color: #fff;
    }
  }

  &__body {
    display: flex;
    align-items: flex-start;
    gap: var(--spacing-3);
  }

  &__stage {
    position: relative;
    max-width: 80vw;
    max-height: 68vh;
    cursor: crosshair;
    line-height: 0;
  }

  &__img {
    max-width: 80vw;
    max-height: 68vh;
    display: block;
    -webkit-user-drag: none;
    user-select: none;
  }

  &__box {
    position: absolute;
    border: 2px solid;
    cursor: pointer;

    &--selected {
      box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.8);
    }
  }

  &__badge {
    position: absolute;
    top: 2px;
    left: 2px;
    min-width: 18px;
    height: 18px;
    border-radius: 50%;
    color: #fff;
    font-size: 12px;
    line-height: 18px;
    text-align: center;
    padding: 0 3px;
  }

  &__editor {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-2);
    width: 240px;
    padding: var(--spacing-2);
    background: var(--color-surface);
    border: 1px solid var(--color-border-light);
    border-radius: var(--radius-base);
    line-height: 1.4;
  }

  &__editor-title {
    font-size: var(--font-size-xs);
    color: var(--color-text-secondary);
  }

  &__empty {
    color: var(--color-text-tertiary);
    padding: var(--spacing-6);
    font-size: var(--font-size-sm);
  }

  &__hint {
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
    max-width: 720px;
    text-align: center;
  }
}
</style>

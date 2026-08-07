<template>
  <div class="mention-ta" ref="rootRef">
    <!--
      S13 节点 @引用输入框（设计 §十三）。
      原生 textarea（非 n-input）——需要 selectionStart 精准定位光标做 @唤起 + 占位符插入。
      占位符本体存 node/asset id（重命名不断链 L8）；label 仅服务人脑消歧。
      候选仅祖先节点（plan §S13：@沿既有连线，拓扑天然成立）。
      C4：mirror 镜像层垫在 textarea 下，把 @占位符染成蓝色 chip（断链=黄色）。
      用户看到的是「透明 textarea 文字」叠在「mirror 染色层」上——占位符处显蓝底。
    -->
    <div ref="mirrorRef" class="mention-ta__mirror" aria-hidden="true" @scroll="onMirrorScroll">
      <template v-for="(seg, i) in segments" :key="i">
        <span v-if="seg.type === 'text'" class="mention-ta__txt">{{ seg.value }}</span>
        <span v-else class="mention-ta__chip" :class="{ 'is-broken': seg.broken }">{{ seg.raw }}</span>
      </template>
    </div>
    <textarea
      ref="taRef"
      class="mention-ta__input"
      :value="modelValue"
      :placeholder="placeholder"
      :disabled="disabled"
      :rows="rows"
      @input="onInput"
      @keydown="onKeydown"
      @blur="onBlur"
      @scroll="onTaScroll"
    />
    <div v-if="open && filtered.length" class="mention-ta__popover">
      <div class="mention-ta__hint">@ 引用祖先节点（拓扑保证其先跑）</div>
      <button
        v-for="(c, i) in filtered"
        :key="c.kind + ':' + c.id"
        type="button"
        class="mention-ta__item"
        :class="{ 'is-active': i === activeIndex }"
        @mousedown.prevent="selectCandidate(c)"
        @mouseenter="activeIndex = i"
      >
        <span class="mention-ta__kind">{{ c.kind === 'node' ? '节点' : '资产' }}</span>
        <span class="mention-ta__label">{{ c.label }}</span>
      </button>
    </div>
    <div v-else-if="open && !filtered.length" class="mention-ta__popover">
      <div class="mention-ta__empty">无可引用祖先节点（无连线可达 / 画布成环）</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import type { MentionCandidate } from '@/types/canvas'

const props = withDefaults(defineProps<{
  /** 文本（含 `@{{node:id}}` 占位符，v-model 双向）。 */
  modelValue: string
  /** @选择器候选（祖先节点；设计 §十三硬约束）。 */
  candidates: MentionCandidate[]
  /** C4：已断链的占位符原文集合（上游被删/断连），mirror 里这些 chip 染黄色；父组件用 findBrokenMentions 算好传入。 */
  brokenMentions?: string[]
  placeholder?: string
  rows?: number
  disabled?: boolean
}>(), {
  brokenMentions: () => [],
  placeholder: '',
  rows: 4,
  disabled: false
})

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

const taRef = ref<HTMLTextAreaElement | null>(null)
const rootRef = ref<HTMLElement | null>(null)
/** C4 mirror 镜像层（垫在 textarea 下做占位符染色）。 */
const mirrorRef = ref<HTMLElement | null>(null)
/** @唤起开合。 */
const open = ref(false)
/** @ 符在文本中的下标（插入时切分点）。 */
const anchor = ref(-1)
/** 当前查询串（@ 后到光标间的非空白字符，过滤候选 label）。 */
const query = ref('')
/** 键盘上下移高亮项索引（Enter 选中）。 */
const activeIndex = ref(0)

const filtered = computed<MentionCandidate[]>(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return props.candidates
  return props.candidates.filter((c) => c.label.toLowerCase().includes(q))
})

/**
 * C4 mirror 切片：把 modelValue 切成「普通文本段 + 占位符段」。
 * 占位符段按 brokenMentions 标黄（断链），其余标蓝（正常引用）。
 * 用本地正则带 index 精确切分（parseMentions 不返 index，故此处不复用）。
 */
const MENTION_RE_LOCAL = /@\{\{(node|asset):([^}]+)\}\}/g
type Segment = { type: 'text'; value: string } | { type: 'mention'; raw: string; broken: boolean }
const segments = computed<Segment[]>(() => {
  const text = props.modelValue
  const brokenSet = new Set(props.brokenMentions)
  const out: Segment[] = []
  MENTION_RE_LOCAL.lastIndex = 0
  let m: RegExpExecArray | null
  let last = 0
  while ((m = MENTION_RE_LOCAL.exec(text)) !== null) {
    if (m.index > last) out.push({ type: 'text', value: text.slice(last, m.index) })
    out.push({ type: 'mention', raw: m[0], broken: brokenSet.has(m[0]) })
    last = m.index + m[0].length
  }
  if (last < text.length) out.push({ type: 'text', value: text.slice(last) })
  return out
})

/** textarea 滚动 → mirror 同步（长文本换行后两边视口对齐）。 */
function onTaScroll() {
  if (mirrorRef.value && taRef.value) mirrorRef.value.scrollTop = taRef.value.scrollTop
}
/** mirror 自身不接收滚轮（pointer-events:none），占位防抖。 */
function onMirrorScroll() {
  if (taRef.value && mirrorRef.value) taRef.value.scrollTop = mirrorRef.value.scrollTop
}

/**
 * 扫描文本定位 @ 唤起锚点：从光标前一个字符回退，跳过连续非空白字符直到 @；
 * 中途遇空白则不是 @ 唤起（防止把段落里普通的 @ 误判）。返回 @ 下标或 -1。
 */
function detectAnchor(text: string, caret: number): { at: number; q: string } | null {
  if (caret <= 0) return null
  let i = caret - 1
  while (i >= 0) {
    const ch = text[i]
    if (ch === '@') {
      // @ 前一字符须非字母数字（拦邮箱类 foo@bar；允许行首/空白/中文/标点后触发，
      // 修复「输入一段话后你好@ 不弹」——原要求空白过严，中文句末无空格触发不了）
      const prev = i > 0 ? text[i - 1] : ' '
      if (!/[A-Za-z0-9]/.test(prev)) return { at: i, q: text.slice(i + 1, caret) }
      return null
    }
    if (/\s/.test(ch)) return null // @ 后遇空白 → 关闭
    i--
  }
  return null
}

function onInput(e: Event) {
  const ta = e.target as HTMLTextAreaElement
  const value = ta.value
  emit('update:modelValue', value)
  const m = detectAnchor(value, ta.selectionStart)
  if (m) {
    open.value = true
    anchor.value = m.at
    query.value = m.q
    activeIndex.value = 0
  } else {
    open.value = false
  }
}

function onKeydown(e: KeyboardEvent) {
  if (!open.value) return
  if (e.key === 'Escape') {
    open.value = false
    e.preventDefault()
  } else if (e.key === 'ArrowDown') {
    activeIndex.value = Math.min(activeIndex.value + 1, filtered.value.length - 1)
    e.preventDefault()
  } else if (e.key === 'ArrowUp') {
    activeIndex.value = Math.max(activeIndex.value - 1, 0)
    e.preventDefault()
  } else if (e.key === 'Enter') {
    const pick = filtered.value[activeIndex.value]
    if (pick) {
      selectCandidate(pick)
      e.preventDefault()
    }
  }
}

function onBlur() {
  // 延迟关闭：让 @mousedown.prevent 的候选项点击先触发 selectCandidate
  setTimeout(() => { open.value = false }, 120)
}

/** 选定候选 → 在 @ 锚点处插入占位符 + 尾随空格，光标移到末尾，关闭弹层。 */
function selectCandidate(c: MentionCandidate) {
  const ta = taRef.value
  if (!ta) return
  const value = ta.value
  const caret = ta.selectionStart ?? value.length
  const at = anchor.value >= 0 ? anchor.value : caret
  const insert = `@{{${c.kind}:${c.id}}}`
  // 尾随空格：末尾或下一个非空白字符时补（便于继续输入），紧邻空白则不补（防双空格）
  const after = value[caret]
  const suffix = after !== undefined && /\s/.test(after) ? '' : ' '
  const next = value.slice(0, at) + insert + suffix + value.slice(caret)
  const pos = at + insert.length + suffix.length
  emit('update:modelValue', next)
  open.value = false
  anchor.value = -1
  query.value = ''
  // 光标置于占位符（+ 尾随空格）之后
  nextTick(() => {
    ta.focus()
    ta.setSelectionRange(pos, pos)
  })
}

defineExpose({ open, anchor, query, filtered, selectCandidate, detectAnchor })
</script>

<style lang="scss" scoped>
.mention-ta {
  position: relative;

  // C4 mirror 镜像层：垫在 textarea 下，字体/字号/行高/padding/border/wrap 与 textarea 像素级一致，
  // 文本透明仅作染色载体；textarea 文字叠在其上 → 占位符处显蓝底。
  &__mirror {
    position: absolute;
    top: 0;
    left: 0;
    width: 100%;
    height: 100%;
    box-sizing: border-box;
    padding: var(--spacing-1) var(--spacing-2);
    border: 1px solid transparent; // 与 textarea 同宽 border 占位，保证文本对齐
    border-radius: var(--radius-base);
    background: var(--color-bg);
    color: transparent; // 普通文本透明，仅 chip 染色
    font-size: var(--font-size-sm);
    line-height: 1.5;
    font-family: inherit;
    white-space: pre-wrap;
    word-break: break-word;
    overflow: hidden;
    pointer-events: none; // 滚轮/点击穿透到 textarea
    z-index: 0;
  }

  &__chip {
    background: rgba(var(--color-primary-rgb), 0.28);
    color: transparent;
    border-radius: var(--radius-small);
    padding: 0 2px;

    &.is-broken {
      background: rgba(250, 204, 21, 0.3); // 断链=黄，与 __warn 语义同源
    }
  }

  &__input {
    position: relative;
    z-index: 1; // 叠在 mirror 之上
    width: 100%;
    box-sizing: border-box;
    padding: var(--spacing-1) var(--spacing-2);
    background: transparent; // 透明，让 mirror 的 chip 染色透出
    border: 1px solid var(--color-border-light);
    border-radius: var(--radius-base);
    color: var(--color-text-primary);
    font-size: var(--font-size-sm);
    line-height: 1.5;
    resize: vertical;
    outline: none;
    font-family: inherit;
    caret-color: var(--color-primary);

    &:focus { border-color: var(--color-primary); }
    &:disabled { opacity: 0.6; cursor: not-allowed; }
  }

  &__popover {
    position: absolute;
    z-index: 20;
    left: 0;
    top: 100%;
    margin-top: 2px;
    min-width: 100%;
    max-width: 240px;
    background: var(--color-surface);
    border: 1px solid var(--color-border-light);
    border-radius: var(--radius-base);
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.35);
    overflow: hidden;
  }

  &__hint, &__empty {
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
    padding: var(--spacing-1) var(--spacing-2);
  }

  &__item {
    display: flex;
    align-items: center;
    gap: var(--spacing-1);
    width: 100%;
    padding: var(--spacing-1) var(--spacing-2);
    background: transparent;
    border: 0;
    color: var(--color-text-secondary);
    font-size: var(--font-size-sm);
    text-align: left;
    cursor: pointer;

    &.is-active {
      background: rgba(var(--color-primary-rgb), 0.14);
      color: var(--color-primary);
    }
  }

  &__kind {
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
    padding: 0 4px;
    border: 1px solid var(--color-border-light);
    border-radius: var(--radius-small);
    flex-shrink: 0;
  }
}
</style>

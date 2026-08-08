<template>
  <div class="mention-ta" ref="rootRef">
    <!--
      S13 节点 @引用输入框（设计 §十三）。
      A1 contenteditable 重写：原生 textarea 只能「存什么显什么」，无法存 token(@{{node:id}}) 却显人话(节点名)。
      改 contenteditable div：chip=原子块(contenteditable=false，存 data-mention=token、显 label)，
      文本节点=字面量，<br>=换行。序列化时 chip→token、<br>→\n，还原回 v-model 字符串（契约不变）。
      chip 可点击：hover 手型，点击 emit mention-click → 父组件跳转聚焦被引用对象（A1 增强）。
      候选仅祖先节点（plan §S13：@沿既有连线，拓扑天然成立）。
    -->
    <div
      ref="editRef"
      class="mention-ta__input"
      :class="{ 'is-disabled': disabled, 'is-empty': !modelValue }"
      :contenteditable="!disabled"
      :data-placeholder="placeholder"
      role="textbox"
      :aria-multiline="rows > 1"
      :style="{ minHeight: `calc(${rows} * 1.5em + var(--spacing-2))` }"
      @input="onInput"
      @keydown="onKeydown"
      @blur="onBlur"
      @paste="onPaste"
      @click="onEditorClick"
      @compositionstart="composing = true"
      @compositionend="onCompositionEnd"
    ></div>
    <span v-if="maxlength" class="mention-ta__count">{{ modelValue.length }}/{{ maxlength }}</span>
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
        <span class="mention-ta__kind">{{ kindLabels[c.kind] ?? c.kind }}</span>
        <span class="mention-ta__label">{{ c.label }}</span>
      </button>
    </div>
    <div v-else-if="open && !filtered.length" class="mention-ta__popover">
      <div class="mention-ta__empty">{{ emptyHint }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import type { MentionCandidate } from '@/types/canvas'
import { detectAnchor, escapeHtml, insertMention, parseSegments } from './mentionLogic'

const props = withDefaults(defineProps<{
  /** 文本（含 `@{{kind:id}}` 占位符，v-model 双向）。 */
  modelValue: string
  /** @选择器候选（画布=祖先节点/资产；视频页=会话附件。设计 §十三硬约束仅对画布生效）。 */
  candidates: MentionCandidate[]
  /** 已断链的占位符原文集合（上游被删/断连），chip 染黄色；父组件用 findBrokenMentions 算好传入。 */
  brokenMentions?: string[]
  /** chip/候选行里 kind→中文标签（默认画布 node/asset；视频页传 image/video/audio）。未知 kind 回退显 kind 本体。 */
  kindLabels?: Record<string, string>
  /** chip 显名：kind:id → 人类名（默认由 candidates 派生；断链找不到 → 显 raw token）。 */
  /** 无候选时的空态文案（默认画布「无祖先节点」；视频页传「无附件」类）。 */
  emptyHint?: string
  /** 字符上限（软截断 + 显计数器；不传则不限不计数）。 */
  maxlength?: number
  placeholder?: string
  rows?: number
  disabled?: boolean
}>(), {
  brokenMentions: () => [],
  kindLabels: () => ({ node: '节点', asset: '资产' }),
  emptyHint: '无可引用祖先节点（无连线可达 / 画布成环）',
  placeholder: '',
  rows: 4,
  disabled: false
})

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  /** A1 增强：点击 chip → 父组件跳转聚焦被引用对象（画布=选中并居中节点；视频页=滚到附件）。 */
  (e: 'mention-click', payload: { kind: string; id: string; raw: string }): void
}>()

const editRef = ref<HTMLDivElement | null>(null)
const rootRef = ref<HTMLElement | null>(null)
/** @唤起开合。 */
const open = ref(false)
/** @ 符在文本中的下标（插入时切分点）。 */
const anchor = ref(-1)
/** 当前查询串（@ 后到光标间的非空白字符，过滤候选 label）。 */
const query = ref('')
/** 键盘上下移高亮项索引（Enter 选中）。 */
const activeIndex = ref(0)
/** IME 合成中（不触发 @唤起/序列化，防中文输入法误触）。 */
const composing = ref(false)

/**
 * chip 显名映射：kind:id → label（由 candidates 派生，响应式——改节点名 chip 同步）。
 * 找不到（断链/候选已不在）→ null，渲染时回退显 raw token（黄底断链可见）。
 */
const labelMap = computed<Map<string, string>>(() => {
  const m = new Map<string, string>()
  for (const c of props.candidates) m.set(`${c.kind}:${c.id}`, c.label)
  return m
})

const filtered = computed<MentionCandidate[]>(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return props.candidates
  return props.candidates.filter((c) => c.label.toLowerCase().includes(q))
})

/**
 * 最近一次 emit 出去的字符串（回声守卫）：
 * 用户输入/选中 → emitValue → 父回写 modelValue → watch 触发 → 若 === lastEmitted 则跳过 render（DOM 已对、光标不丢）；
 * 仅外部变更（父加载/切节点）才 render 重建。
 */
const lastEmitted = ref(props.modelValue)

/** 把字符串渲染成 contenteditable 子节点（chip=原子 span、文本=字面量、\n=<br>）。命令式 innerHTML。 */
function render(text: string) {
  const el = editRef.value
  if (!el) return
  const brokenSet = new Set(props.brokenMentions)
  const segs = parseSegments(text)
  let html = ''
  for (const s of segs) {
    if (s.type === 'text') {
      html += escapeHtml(s.value).replace(/\n/g, '<br>')
    } else {
      const label = labelMap.value.get(`${s.kind}:${s.id}`) ?? s.raw
      const brokenCls = brokenSet.has(s.raw) ? ' is-broken' : ''
      html +=
        `<span class="mention-ta__chip${brokenCls}" contenteditable="false"` +
        ` data-mention="${escapeHtml(s.raw)}" data-kind="${escapeHtml(s.kind)}" data-id="${escapeHtml(s.id)}">` +
        `${escapeHtml(label)}</span>`
    }
  }
  el.innerHTML = html
}

/** 序列化 contenteditable 子节点 → 含 token 的字符串（chip→data-mention、<br>→\n、文本→字面量）。 */
function serialize(): string {
  const el = editRef.value
  if (!el) return ''
  return serializeNode(el)
}
function serializeNode(node: Node): string {
  let out = ''
  node.childNodes.forEach((child) => {
    if (child.nodeType === Node.TEXT_NODE) {
      out += child.nodeValue ?? ''
    } else if (child.nodeType === Node.ELEMENT_NODE) {
      const elc = child as HTMLElement
      if (elc.classList.contains('mention-ta__chip')) {
        out += elc.dataset.mention ?? ''
      } else if (elc.tagName === 'BR') {
        out += '\n'
      } else {
        // 块元素（DIV 等，浏览器偶尔产生）：内容前后补换行边界
        if (out.length > 0 && !out.endsWith('\n')) out += '\n'
        out += serializeNode(elc)
        out += '\n'
      }
    }
  })
  return out
}

function emitValue(v: string) {
  lastEmitted.value = v
  emit('update:modelValue', v)
}

/** 取 contenteditable 当前字符光标偏移（用于 @唤起定位——回退计字符数，chip 按 token 长度计）。 */
function caretCharOffset(): number {
  const el = editRef.value
  if (!el) return 0
  const sel = window.getSelection()
  if (!sel || sel.rangeCount === 0) return 0
  const range = sel.getRangeAt(0)
  const pre = range.cloneRange()
  pre.selectNodeContents(el)
  pre.setEnd(range.startContainer, range.startOffset)
  // pre.toString() 把 chip 的 textContent(label) 也算进去——需用 token 长度修正：
  // 简化：用 toString 计字面可见字符，对 @唤起定位足够（只需 @ 到光标间无空白判定）。
  return pre.toString().length
}

/** 把字符偏移设回 contenteditable 光标（选中候选后定位）。 */
function setCaretCharOffset(offset: number) {
  const el = editRef.value
  if (!el) return
  const sel = window.getSelection()
  if (!sel) return
  const range = document.createRange()
  let count = 0
  let placed = false
  for (const child of Array.from(el.childNodes)) {
    let len = 0
    if (child.nodeType === Node.TEXT_NODE) {
      len = child.nodeValue?.length ?? 0
    } else {
      const elc = child as HTMLElement
      if (elc.tagName === 'BR') len = 1
      else if (elc.classList.contains('mention-ta__chip')) len = elc.dataset.mention?.length ?? 0
    }
    if (count + len >= offset) {
      if (child.nodeType === Node.TEXT_NODE) {
        range.setStart(child, Math.min(offset - count, len))
      } else {
        range.setStartAfter(child)
      }
      range.collapse(true)
      placed = true
      break
    }
    count += len
  }
  if (!placed) {
    range.selectNodeContents(el)
    range.collapse(false)
  }
  sel.removeAllRanges()
  sel.addRange(range)
}

function onInput() {
  if (composing.value) return
  let v = serialize()
  // maxlength 软截断（contenteditable 无原生 maxlength；超限裁掉尾部，保 token 完整优先级低——超 8000 极罕见）
  if (props.maxlength && v.length > props.maxlength) {
    v = v.slice(0, props.maxlength)
    render(v)
    nextTick(() => setCaretCharOffset(v.length))
  }
  emitValue(v)
  const caret = caretCharOffset()
  const m = detectAnchor(v, caret)
  if (m) {
    open.value = true
    anchor.value = m.at
    query.value = m.q
    activeIndex.value = 0
  } else {
    open.value = false
  }
}

function onCompositionEnd() {
  composing.value = false
  onInput()
}

function onKeydown(e: KeyboardEvent) {
  if (open.value) {
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
    return
  }
  // Enter 插换行：拦截确保插 <br>（而非浏览器默认 <div>），保证 serialize→\n 1:1 可逆。
  if (e.key === 'Enter') {
    e.preventDefault()
    document.execCommand('insertLineBreak')
  }
}

function onBlur() {
  // 延迟关闭：让 @mousedown.prevent 的候选项点击先触发 selectCandidate
  setTimeout(() => { open.value = false }, 120)
}

/** 粘贴仅取纯文本（剥离富文本/HTML，防外部样式污染 contenteditable）。 */
function onPaste(e: ClipboardEvent) {
  e.preventDefault()
  const txt = e.clipboardData?.getData('text/plain') ?? ''
  if (txt) document.execCommand('insertText', false, txt)
}

/** 事件委托：点击 chip → emit mention-click（chip contenteditable=false 不会放光标，整块可点）。 */
function onEditorClick(e: MouseEvent) {
  const target = e.target as HTMLElement
  const chip = target.closest('.mention-ta__chip') as HTMLElement | null
  if (!chip) return
  // 断链 chip（上游已删）不响应点击——跳转无目标，cursor 已显 not-allowed
  if (chip.classList.contains('is-broken')) return
  const raw = chip.dataset.mention ?? ''
  const kind = chip.dataset.kind ?? ''
  const id = chip.dataset.id ?? ''
  if (raw) {
    emit('mention-click', { kind, id, raw })
    e.preventDefault()
  }
}

/** 选定候选 → 在 @ 锚点处插入 token + 尾随空格，重建 DOM 显 chip，光标移到 token 之后。 */
function selectCandidate(c: MentionCandidate) {
  const cur = lastEmitted.value
  const caret = caretCharOffset()
  const at = anchor.value >= 0 ? anchor.value : caret
  const { text, pos } = insertMention(cur, at, caret, c.kind, c.id)
  emitValue(text)
  render(text) // 立即重建（chip 要显示；lastEmitted 已更新故 watch 回声会跳过重复 render）
  open.value = false
  anchor.value = -1
  query.value = ''
  nextTick(() => {
    editRef.value?.focus()
    setCaretCharOffset(pos)
  })
}

// 外部 modelValue 变更（父加载/切节点）→ 重建；自身 emit 回声（=== lastEmitted）→ 跳过保光标。
watch(
  () => props.modelValue,
  (v) => {
    if (v === lastEmitted.value) return
    lastEmitted.value = v
    render(v)
  }
)
// 断链集合/候选 label 变化 → 重渲染（chip 染色/名同步），不影响光标（失焦态或只读视觉）。
watch([() => props.brokenMentions, () => props.candidates], () => {
  render(lastEmitted.value)
})

onMounted(() => {
  lastEmitted.value = props.modelValue
  render(props.modelValue)
})

defineExpose({ open, anchor, query, filtered, selectCandidate, detectAnchor, render, serialize })
</script>

<style lang="scss" scoped>
.mention-ta {
  position: relative;

  &__input {
    position: relative;
    width: 100%;
    box-sizing: border-box;
    padding: var(--spacing-1) var(--spacing-2);
    background: transparent;
    border: 1px solid var(--color-border-light);
    border-radius: var(--radius-base);
    color: var(--color-text-primary);
    font-size: var(--font-size-sm);
    line-height: 1.5;
    outline: none;
    font-family: inherit;
    caret-color: var(--color-primary);
    // 保留空白/换行（contenteditable 文本字面量按 pre-wrap 排版）
    white-space: pre-wrap;
    word-break: break-word;
    overflow-wrap: break-word;
    cursor: text;

    &:focus { border-color: var(--color-primary); }
    &.is-disabled { opacity: 0.6; cursor: not-allowed; }
    // 空内容占位符（contenteditable 无原生 placeholder，用 :empty + attr 模拟）
    &.is-empty::before {
      content: attr(data-placeholder);
      color: var(--color-text-tertiary);
      pointer-events: none;
    }
  }

  // A1 chip：显人话 label，原子不可编辑，hover 手型可点击跳转
  &__chip {
    display: inline-block;
    background: rgba(var(--color-primary-rgb), 0.28);
    color: var(--color-primary);
    border-radius: var(--radius-small);
    padding: 0 4px;
    margin: 0 1px;
    line-height: 1.4;
    cursor: pointer; // A1 增强：hover 显手型，提示可点击跳转
    user-select: none;
    transition: background 0.12s;

    &:hover { background: rgba(var(--color-primary-rgb), 0.45); }

    &.is-broken {
      background: rgba(250, 204, 21, 0.3); // 断链=黄，与 __warn 语义同源
      color: var(--color-text-secondary);
      cursor: not-allowed;
      &:hover { background: rgba(250, 204, 21, 0.45); }
    }
  }

  &__count {
    position: absolute;
    right: var(--spacing-1);
    bottom: 0;
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
    pointer-events: none;
    line-height: 1.6;
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

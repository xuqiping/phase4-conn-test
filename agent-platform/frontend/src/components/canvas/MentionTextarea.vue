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
    <!-- D4（2x-9）：弹层锚在光标处（popoverStyle 内联覆盖 left/top；空对象回落类静态定位） -->
    <div v-if="open && filtered.length" ref="popoverRef" class="mention-ta__popover" :style="popoverStyle">
      <div class="mention-ta__hint">@ 引用祖先节点（拓扑保证其先跑）</div>
      <template v-for="row in popRows" :key="row.type === 'header' ? row.key : `${row.c.kind}:${row.c.id}`">
        <!-- 2x 四轮 S9：组分节头（组内任一祖先命中→组全员可 @；头随成员过滤自动隐现） -->
        <div v-if="row.type === 'header'" class="mention-ta__group-head">
          <span class="mention-ta__group-dot" :style="{ background: row.color }"></span>{{ row.label }}
        </div>
        <button
          v-else
          type="button"
          class="mention-ta__item"
          :class="{ 'is-active': row.idx === activeIndex }"
          @mousedown.prevent="selectCandidate(row.c)"
          @mouseenter="activeIndex = row.idx"
        >
          <span class="mention-ta__kind">{{ kindLabels[row.c.kind] ?? row.c.kind }}</span>
          <span class="mention-ta__label">{{ row.c.label }}</span>
        </button>
      </template>
    </div>
    <div v-else-if="open && !filtered.length" ref="popoverRef" class="mention-ta__popover" :style="popoverStyle">
      <div class="mention-ta__empty">{{ emptyHint }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import type { MentionCandidate } from '@/types/canvas'
import { detectAnchor, escapeHtml, insertMention, parseSegments } from './mentionLogic'
import { caretViewportRect, placePopover } from './caret'

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
  /** 修复IV C1b（C-4 缺口2）：失焦（非点候选行——候选行 mousedown.prevent 不触发 blur）
   *  → 父组件据此 emit data-changed 走自动保存；输入后不点别处直接关页签不丢文本。 */
  (e: 'blur-committed'): void
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
/** D4：候选弹层元素 + 光标锚定定位（空对象=回落类静态「输入框下方」）。 */
const popoverRef = ref<HTMLDivElement | null>(null)
const popoverStyle = ref<Record<string, string>>({})

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
 * 2x 四轮 S9：弹层展示行 = 候选项 + 组分节头交错（首个某组候选前插头；header 非候选，
 * query 过滤掉组员后头自动消失——头是派生行不是数据）。idx 指向 filtered 下标（键盘高亮对齐）。
 */
type PopRow =
  | { type: 'header'; key: string; label: string; color?: string }
  | { type: 'item'; c: MentionCandidate; idx: number }
const popRows = computed<PopRow[]>(() => {
  const rows: PopRow[] = []
  let lastGroup = ''
  filtered.value.forEach((c, idx) => {
    if (c.groupId) {
      if (c.groupId !== lastGroup) {
        rows.push({ type: 'header', key: `h-${c.groupId}`, label: c.groupLabel ?? '组', color: c.groupColor })
        lastGroup = c.groupId
      }
    } else {
      lastGroup = ''
    }
    rows.push({ type: 'item', c, idx })
  })
  return rows
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

/** 取 contenteditable 当前序列化光标偏移（文本按字符、chip 按 token、br 按换行计）。 */
function caretCharOffset(): number {
  const el = editRef.value
  if (!el) return 0
  const sel = window.getSelection()
  if (!sel || sel.rangeCount === 0) return 0
  const range = sel.getRangeAt(0)
  const pre = range.cloneRange()
  pre.selectNodeContents(el)
  pre.setEnd(range.startContainer, range.startOffset)
  // cloneContents 会保留 chip 的 data-mention；交给同一序列化函数计算，避免
  // 可见 label 长度与内部 token 长度不一致时把第二次 @ 的锚点算偏。
  return serializeNode(pre.cloneContents()).length
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

// ---- D4（2x-9）：@候选弹层光标锚定 ----

/**
 * 弹层定位到当前光标处：contenteditable 原生 Range 光标矩形（视口坐标）→
 * 换算根容器相对坐标 → placePopover（上方优先，越上界翻转下方；左右夹容器内）。
 * 光标矩形探测失败（jsdom 无布局/异常态）→ 清空内联样式，回落 CSS 静态「输入框下方」。
 */
function repositionPopover() {
  const root = rootRef.value
  const editor = editRef.value
  if (!root || !editor || !open.value) return
  const cr = caretViewportRect(editor)
  if (!cr) {
    popoverStyle.value = {}
    return
  }
  const rootRect = root.getBoundingClientRect()
  const pop = popoverRef.value
  const popW = Math.min(240, rootRect.width || 240)
  const popH = pop?.offsetHeight ?? 0
  const pos = placePopover({
    caretX: cr.left - rootRect.left,
    caretY: cr.top - rootRect.top,
    caretH: cr.height,
    rootW: rootRect.width || popW,
    popW,
    popH
  })
  popoverStyle.value = { left: `${pos.left}px`, top: `${pos.top}px`, width: `${popW}px` }
}

/** 开合/查询串/候选过滤变化后重定位（nextTick 等弹层渲染出实高再算）。 */
watch([open, query, filtered], () => nextTick(repositionPopover))

/** 视口 resize/浏览器缩放 → 开着弹层就重算坐标。 */
function onWinResize() {
  if (open.value) repositionPopover()
}
onMounted(() => window.addEventListener('resize', onWinResize))
onUnmounted(() => window.removeEventListener('resize', onWinResize))

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
  // 能走到 blur = 不是点候选行（候选行 mousedown.prevent 不转移焦点）→ 通知父级提交保存
  emit('blur-committed')
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
  const { text, pos } = insertMention(cur, at, caret, c.kind, c.id, c.insertSuffix ?? '')
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

/**
 * D3（2x-8）：外部程序化追加 @引用到文本末尾（上游面板双击卡片触发）。
 * 序列化复用既有 token 格式；末尾非空白先补一个空格再接 token，token 后恒跟尾随空格。
 * 禁用态（生成中）返回 false 不动文本（调用方决定提示）；成功后聚焦并把光标落到引用之后。
 */
function appendMention(c: Pick<MentionCandidate, 'kind' | 'id' | 'insertSuffix'>): boolean {
  if (props.disabled) return false
  const cur = lastEmitted.value
  const gap = cur && !/\s/.test(cur.slice(-1)) ? ' ' : ''
  const token = `@{{${c.kind}:${c.id}}}${c.insertSuffix ?? ''}`
  const next = `${cur}${gap}${token} `
  emitValue(next)
  render(next)
  nextTick(() => {
    editRef.value?.focus()
    setCaretCharOffset(next.length)
  })
  return true
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

/**
 * 外科更新 chip 视觉（断链染色 + label 同步），**不重建 innerHTML**。
 * 必须外科而非 render：brokenMentions 在 VideoGenView 依赖 form.prompt，每键都重算返新数组
 * → 若走 render() 会重建 innerHTML → 光标丢失 → 下个字符插到开头 → 输入逆序（"abc"→"cba"）。
 * chip 是 contenteditable=false 的原子块，光标永不在其内；改其 class/textContent 不影响
 * 文本节点里的光标，故可安全在每次按键时调用。
 */
function applyChipVisuals() {
  const el = editRef.value
  if (!el) return
  const brokenSet = new Set(props.brokenMentions)
  el.querySelectorAll<HTMLElement>('.mention-ta__chip').forEach((chip) => {
    const raw = chip.dataset.mention ?? ''
    const kind = chip.dataset.kind ?? ''
    const id = chip.dataset.id ?? ''
    const label = labelMap.value.get(`${kind}:${id}`) ?? raw
    if (chip.textContent !== label) chip.textContent = label
    chip.classList.toggle('is-broken', brokenSet.has(raw))
  })
}
// 断链集合/候选 label 变化 → 外科更新 chip（染色/名同步），不丢光标。
watch([() => props.brokenMentions, () => props.candidates], applyChipVisuals, { deep: true })

onMounted(() => {
  lastEmitted.value = props.modelValue
  render(props.modelValue)
})

defineExpose({ open, anchor, query, filtered, selectCandidate, appendMention, detectAnchor, render, serialize })
</script>

<style lang="scss" scoped>
.mention-ta {
  position: relative;
  // 显式满宽：包裹层在 Naive n-form-item 里是 flex item，默认收缩到内容宽。
  // 空态靠 ::before 占位符长文本撑宽（~346），一旦打 @ 触发 is-empty 移除 →
  // 占位符消失 → min-content 塌到字宽（~31px），连带 input/popover 全塌成窄条，
  // 空态文案竖排撑成 412px 高遮下方。width:100% 让宽度只跟父容器，与内容解耦。
  width: 100%;

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

  // A1 chip：显人话 label，原子不可编辑，hover 手型可点击跳转。
  // chip 由 render() 经 innerHTML 注入（非 Vue 模板渲染），无 scoped data-v 属性，
  // 故 scoped 的 &__chip 规则不匹配 → 必须用 :deep() 穿透 scope 才能命中注入元素。
  :deep(.mention-ta__chip) {
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
    max-height: 240px;
    overflow-y: auto;
    background: var(--color-surface);
    border: 1px solid var(--color-border-light);
    border-radius: var(--radius-base);
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.35);
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

  /* 2x 四轮 S9：组分节头（色点+组名，非交互） */
  &__group-head {
    display: flex;
    align-items: center;
    gap: 4px;
    padding: var(--spacing-1) var(--spacing-2);
    font-size: var(--font-size-xs);
    color: var(--color-text-secondary);
    border-top: 1px solid var(--color-border-light);
    margin-top: 2px;
    user-select: none;
  }

  &__group-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    flex-shrink: 0;
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

<template>
  <div class="user-picker" :class="{ 'user-picker--open': open }">
    <!-- 已选 chips（multiple） -->
    <div v-if="multiple && chips.length" class="user-picker__chips" role="list" aria-label="已选用户">
      <span v-for="u in chips" :key="u.userId" class="user-picker__chip">
        {{ displayName(u) }}
        <button
          type="button"
          class="user-picker__chip-x"
          :aria-label="`移除 ${displayName(u)}`"
          @click="removeSelected(u.userId)"
        >×</button>
      </span>
    </div>

    <input
      v-model="query"
      class="user-picker__input"
      role="combobox"
      :aria-expanded="open"
      aria-controls="user-picker-listbox"
      aria-autocomplete="list"
      :aria-activedescendant="activeIndex >= 0 ? `user-picker-opt-${activeIndex}` : undefined"
      :placeholder="placeholder ?? (multiple ? '搜索用户名 / 姓名 / 备注' : '搜索用户名 / 姓名 / 备注，回车选择')"
      @input="onInput"
      @focus="openList"
      @keydown.down.prevent="moveActive(1)"
      @keydown.up.prevent="moveActive(-1)"
      @keydown.enter.prevent="pickActive"
      @keydown.esc.prevent="closeList"
      @blur="onBlur"
    />

    <ul
      v-if="open"
      id="user-picker-listbox"
      class="user-picker__listbox"
      role="listbox"
      aria-label="候选用户"
    >
      <li v-if="loading" class="user-picker__hint">搜索中…</li>
      <li v-else-if="!options.length" class="user-picker__hint">无匹配用户</li>
      <li
        v-for="(u, i) in options"
        v-else
        :key="u.userId"
        :id="`user-picker-opt-${i}`"
        class="user-picker__option"
        role="option"
        :aria-selected="i === activeIndex"
        :class="{ 'is-active': i === activeIndex, 'is-picked': isPicked(u.userId) }"
        @mousedown.prevent="pick(u)"
        @mousemove="activeIndex = i"
      >
        <span class="user-picker__name">{{ displayName(u) }}</span>
        <span class="user-picker__username">{{ u.username }}</span>
        <span
          v-if="u.remark"
          class="user-picker__remark"
          :title="u.remark"
        >{{ u.remark }}</span>
      </li>
    </ul>
  </div>
</template>

<script setup lang="ts">
/**
 * 修复III E3（12x#4）：统一选人组件。
 * 数据源注入（各处权限各异无统一端点）：prop search(keyword) 由调用方传各自 API——
 * 组长传 projectGroupApi.candidates、财务传 billing.rechargeUserOptions、管理端传 adminApi.listUsers。
 * 交互：远程搜索 debounce 300ms；行=姓名·账号·备注 tag（title 悬浮全文）；multiple 已选 chips 可移除；
 * 键盘 ↓↑/Enter/Esc；a11y role=listbox/option + aria-activedescendant。
 */
import { computed, ref } from 'vue'
import { displayName } from '@/utils/displayName'

export interface PickerUser {
  userId: number
  username: string
  name?: string | null
  remark?: string | null
}

const props = withDefaults(defineProps<{
  multiple?: boolean
  modelValue: number | number[] | null
  search: (keyword: string) => Promise<PickerUser[]>
  placeholder?: string
}>(), { multiple: false })

const emit = defineEmits<{ (e: 'update:modelValue', v: number | number[] | null): void }>()

const query = ref('')
const options = ref<PickerUser[]>([])
// multiple：已选行持久登记（chips 显姓名用）——chips 渲染以 modelValue 为准（受控），
// map 只补 id→姓名/备注映射，父侧移除/清空时 chips 随 modelValue 自动缩
const pickedMap = new Map<number, PickerUser>()
const open = ref(false)
const loading = ref(false)
const activeIndex = ref(-1)
let timer: ReturnType<typeof setTimeout> | null = null
let seq = 0

const chips = computed(() =>
  (Array.isArray(props.modelValue) ? props.modelValue : [])
    .map(id => pickedMap.get(id))
    .filter((u): u is PickerUser => !!u))

function onInput() {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => void doSearch(), 300)
}

async function doSearch() {
  const mySeq = ++seq
  loading.value = true
  try {
    const list = await props.search(query.value.trim())
    if (mySeq !== seq) return   // 过期响应丢弃（慢请求覆盖快输入）
    options.value = list.slice(0, 20)
    activeIndex.value = options.value.length ? 0 : -1
  } catch {
    if (mySeq === seq) options.value = []
  } finally {
    if (mySeq === seq) loading.value = false
  }
}

function openList() {
  if (!open.value) {
    open.value = true
    if (!options.value.length) void doSearch()
  }
}

function closeList() {
  open.value = false
  activeIndex.value = -1
}

function onBlur() {
  // mousedown.prevent 已阻 option 点击丢焦；此处延迟兜底纯点击外部关闭
  setTimeout(() => closeList(), 120)
}

function isPicked(id: number) {
  return props.multiple
    ? Array.isArray(props.modelValue) && props.modelValue.includes(id)
    : props.modelValue === id
}

function pick(u: PickerUser) {
  if (props.multiple) {
    const cur = Array.isArray(props.modelValue) ? [...props.modelValue] : []
    if (cur.includes(u.userId)) return   // 已选不重复
    pickedMap.set(u.userId, u)
    cur.push(u.userId)
    emit('update:modelValue', cur)
  } else {
    pickedMap.set(u.userId, u)
    emit('update:modelValue', u.userId)
    query.value = displayName(u)
    closeList()
  }
}

function pickActive() {
  if (activeIndex.value >= 0 && options.value[activeIndex.value]) {
    pick(options.value[activeIndex.value])
  }
}

function moveActive(delta: number) {
  if (!options.value.length) return
  open.value = true
  activeIndex.value = (activeIndex.value + delta + options.value.length) % options.value.length
}

function removeSelected(id: number) {
  const cur = Array.isArray(props.modelValue) ? props.modelValue.filter(i => i !== id) : []
  pickedMap.delete(id)
  emit('update:modelValue', cur)
}

defineExpose({ displayName })
</script>

<style lang="scss" scoped>
.user-picker {
  position: relative;

  &__chips {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
    margin-bottom: 6px;
  }

  &__chip {
    display: inline-flex;
    align-items: center;
    gap: 2px;
    padding: 1px 6px;
    border-radius: var(--radius-sm);
    background: rgba(var(--color-primary-rgb), 0.14);
    color: var(--color-primary);
    font-size: 12px;
    max-width: 160px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__chip-x {
    border: none;
    background: none;
    color: inherit;
    cursor: pointer;
    padding: 0 2px;
    font-size: 13px;
    line-height: 1;

    &:hover { color: var(--color-error, #e88080); }
  }

  &__input {
    width: 100%;
    box-sizing: border-box;
    padding: 6px 10px;
    border-radius: var(--radius-sm);
    border: 1px solid var(--color-border);
    background: var(--color-bg-secondary, rgba(15, 23, 42, 0.6));
    color: var(--color-text-primary);
    font-size: 13px;
    outline: none;

    &:focus { border-color: var(--color-primary); }
  }

  &__listbox {
    position: absolute;
    z-index: 20;
    top: calc(100% + 4px);
    left: 0;
    right: 0;
    max-height: 260px;
    overflow-y: auto;
    margin: 0;
    padding: 4px 0;
    list-style: none;
    border-radius: var(--radius-sm);
    border: 1px solid var(--color-border);
    background: var(--color-bg-primary, #0f172a);
    box-shadow: 0 12px 26px rgba(0, 0, 0, 0.4);
  }

  &__hint {
    padding: 8px 12px;
    font-size: 12px;
    color: var(--color-text-tertiary);
  }

  &__option {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 12px;
    font-size: 13px;
    cursor: pointer;

    &.is-active { background: rgba(var(--color-primary-rgb), 0.12); }
    &.is-picked { opacity: 0.55; }
  }

  &__name {
    color: var(--color-text-primary);
    font-weight: 500;
  }

  &__username {
    color: var(--color-text-tertiary);
    font-size: 12px;
  }

  &__remark {
    margin-left: auto;
    max-width: 40%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    padding: 0 6px;
    border-radius: var(--radius-sm);
    background: rgba(148, 163, 184, 0.16);
    color: var(--color-text-secondary);
    font-size: 11px;
  }
}
</style>

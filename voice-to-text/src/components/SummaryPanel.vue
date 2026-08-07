<script setup lang="ts">
// Step 11 (FR-109): 总结操作面板 —— 草稿态提示 + 重生成（全局/单段）+ 要点局部编辑
// + 多模态精修开关（默认关，开启二次确认「将上传课件图到 provider」）。
// 要点编辑列表挂在当前选中章节上（Study.vue 同款数据，避免双份渲染）。
import { computed, ref } from 'vue'
import { invoke } from '@tauri-apps/api/core'
import { useSessionStore } from '../stores/session'

const props = defineProps<{ selectedChapter: number }>()

const store = useSessionStore()
const regenerating = ref(false)
const regenSegment = ref<number | ''>('')
const editing = ref<{ segmentId: number; pointIndex: number; text: string } | null>(null)
const saving = ref(false)

const timeline = computed(() => store.timeline)
const chapters = computed(() => timeline.value?.chapters ?? [])
const editChapter = computed(
  () => chapters.value.find((c, i) => i === props.selectedChapter) ?? chapters.value[0] ?? null
)

/** 多模态精修开关：开启前二次确认（FR-107 安全检查：默认仅传文字）。 */
async function onVlmToggle(e: Event) {
  const input = e.target as HTMLInputElement
  if (!input.checked) {
    store.vlmOn = false
    return
  }
  const cfg = await invoke<{ base_url: string }>('get_summary_config').catch(() => null)
  const ok = window.confirm(
    `多模态精修会把课件帧图片上传到 ${cfg?.base_url ?? '你配置的云端服务'}（默认只传文字）。确认开启？`
  )
  if (ok) {
    store.vlmOn = true
  } else {
    input.checked = false
  }
}

async function regenerate(segmentId?: number) {
  regenerating.value = true
  try {
    await store.regenerate(segmentId)
  } catch {
    // errorMessage 已在 store 落好
  } finally {
    regenerating.value = false
  }
}

function startEdit(segmentId: number, pointIndex: number, text: string) {
  editing.value = { segmentId, pointIndex, text }
}

async function saveEdit() {
  if (!editing.value || saving.value) return
  saving.value = true
  try {
    await store.updatePoint(
      editing.value.segmentId,
      editing.value.pointIndex,
      editing.value.text
    )
    editing.value = null
  } catch (e) {
    store.errorMessage = `保存要点失败: ${e}`
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section class="panel" aria-label="总结操作">
    <div class="row status-row">
      <span v-if="timeline" class="draft-badge" role="status">
        草稿 v{{ timeline.version }} · {{ timeline.model }}（可编辑 / 可重生成）
      </span>
      <label class="vlm-switch">
        <input
          type="checkbox"
          :checked="store.vlmOn"
          aria-label="多模态精修（上传课件帧图，默认关）"
          @change="onVlmToggle"
        />
        多模态精修
      </label>
    </div>

    <div class="row ops-row">
      <button
        class="btn"
        :disabled="regenerating || !timeline"
        @click="regenerate()"
      >
        {{ regenerating ? '重生成中…' : '全量重生成' }}
      </button>
      <select
        v-model="regenSegment"
        class="seg-select"
        aria-label="选择要重生成的章节"
        :disabled="regenerating || !chapters.length"
      >
        <option value="">选择章节…</option>
        <option v-for="(ch, i) in chapters" :key="ch.segment_id" :value="ch.segment_id">
          {{ i + 1 }}. {{ ch.title }}
        </option>
      </select>
      <button
        class="btn"
        :disabled="regenerating || regenSegment === ''"
        @click="regenerate(Number(regenSegment))"
      >
        重生成该段
      </button>
    </div>

    <div v-if="editChapter" class="edit-area">
      <h3 class="edit-title">要点编辑 —— {{ editChapter.title }}</h3>
      <ul class="edit-list">
        <li v-for="(p, pi) in editChapter.points" :key="pi" class="edit-item">
          <template v-if="editing && editing.segmentId === editChapter.segment_id && editing.pointIndex === pi">
            <textarea
              v-model="editing.text"
              class="edit-textarea"
              rows="3"
              :aria-label="`编辑要点 ${pi + 1}`"
            />
            <span class="edit-ops">
              <button class="mini-btn" :disabled="saving" @click="saveEdit">保存</button>
              <button class="mini-btn ghost" @click="editing = null">取消</button>
            </span>
          </template>
          <template v-else>
            <span class="edit-text">[{{ p.ts_label }}] {{ p.text }}</span>
            <button
              class="mini-btn ghost"
              :aria-label="`编辑要点 ${pi + 1}`"
              @click="startEdit(editChapter.segment_id, pi, p.text)"
            >
              编辑
            </button>
          </template>
        </li>
      </ul>
    </div>
  </section>
</template>

<style scoped>
.panel {
  background: #161616;
  border: 1px solid #2a2a2a;
  border-radius: 8px;
  padding: 14px 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.row {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}
.status-row {
  justify-content: space-between;
}
.draft-badge {
  font-size: 12px;
  color: #fbbf24;
  background: #2a230f;
  border: 1px solid #4a3d17;
  border-radius: 999px;
  padding: 3px 12px;
}
.vlm-switch {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #bbb;
  cursor: pointer;
}
.vlm-switch input {
  accent-color: #2563eb;
}
.btn {
  padding: 7px 16px;
  font-size: 13px;
  background: #2563eb;
  color: #fff;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}
.btn:hover:not(:disabled) {
  background: #1d4ed8;
}
.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn:focus-visible,
.mini-btn:focus-visible,
.seg-select:focus-visible,
.edit-textarea:focus-visible {
  outline: 2px solid #60a5fa;
  outline-offset: 1px;
}
.seg-select {
  padding: 7px 10px;
  font-size: 13px;
  background: #222;
  color: #e0e0e0;
  border: 1px solid #333;
  border-radius: 6px;
  max-width: 240px;
}
.edit-area {
  border-top: 1px solid #2a2a2a;
  padding-top: 10px;
}
.edit-title {
  font-size: 13px;
  color: #f0f0f0;
  margin-bottom: 8px;
}
.edit-list {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.edit-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  font-size: 13px;
}
.edit-text {
  flex: 1;
  color: #ddd;
  line-height: 1.6;
}
.edit-textarea {
  flex: 1;
  background: #111;
  color: #e0e0e0;
  border: 1px solid #333;
  border-radius: 6px;
  padding: 8px 10px;
  font-size: 13px;
  font-family: inherit;
  resize: vertical;
}
.edit-ops {
  display: flex;
  gap: 6px;
}
.mini-btn {
  padding: 4px 12px;
  font-size: 12px;
  background: #2563eb;
  color: #fff;
  border: none;
  border-radius: 5px;
  cursor: pointer;
  flex-shrink: 0;
}
.mini-btn.ghost {
  background: #222;
  border: 1px solid #333;
}
.mini-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>

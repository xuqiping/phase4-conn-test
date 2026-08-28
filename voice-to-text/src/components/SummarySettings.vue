<script setup lang="ts">
// Step 8 (FR-107/109): 云端总结设置 —— Base URL / 模型名（非密，落 %APPDATA% 配置）+
// API Key（仅存 Windows 凭据管理器，永不回显）+ 连通性自检。
// 2026-08-19: 总结侧重点改为用户可维护预设表（FR-111 完善）。
import { onMounted, ref } from 'vue'
import { invoke } from '@tauri-apps/api/core'

interface FocusPreset {
  key: string
  label: string
  prompt: string
}

interface SummaryConfig {
  base_url: string
  model: string
  vlm_model: string | null
  max_segment_chars: number
  concurrency: number
  timeout_secs: number
  focus: string
  focus_presets: FocusPreset[]
}

const baseUrl = ref('')
const model = ref('')
const timeoutSecs = ref(600)
const vlmModel = ref('')
const focus = ref('')
const focusOptions = ref<FocusPreset[]>([])
const apiKey = ref('')
const keySaved = ref(false)
const message = ref('')
const messageKind = ref<'ok' | 'err'>('ok')
const testing = ref(false)

function flash(kind: 'ok' | 'err', text: string) {
  messageKind.value = kind
  message.value = text
}

onMounted(async () => {
  try {
    const cfg = await invoke<SummaryConfig>('get_summary_config')
    baseUrl.value = cfg.base_url
    model.value = cfg.model
    timeoutSecs.value = cfg.timeout_secs || 600
    vlmModel.value = cfg.vlm_model ?? ''
    focus.value = cfg.focus ?? ''
    focusOptions.value = cfg.focus_presets?.length ? cfg.focus_presets : defaultPresets()
    keySaved.value = await invoke<boolean>('has_summary_api_key')
  } catch (e) {
    flash('err', `读取设置失败: ${e}`)
  }
})

function defaultPresets(): FocusPreset[] {
  return [
    { key: 'exam', label: '考试复习', prompt: '优先提炼考点、定义公式、易错点、老师反复强调的内容。' },
    { key: 'concept', label: '概念理解', prompt: '优先解释概念的含义、为什么是这样、与相近概念的区别，表述通俗易懂。' },
    { key: 'practice', label: '实操步骤', prompt: '优先提炼操作步骤、流程顺序、注意事项和常见坑。' },
  ]
}

async function saveConfig() {
  try {
    await invoke('set_summary_config', {
      config: {
        base_url: baseUrl.value.trim(),
        model: model.value.trim(),
        vlm_model: vlmModel.value.trim() || null,
        max_segment_chars: 2000,
        concurrency: 2,
        timeout_secs: Math.max(30, Math.floor(timeoutSecs.value) || 600),
        focus: focus.value,
        focus_presets: focusOptions.value,
      },
    })
    flash('ok', '设置已保存')
  } catch (e) {
    flash('err', `保存设置失败: ${e}`)
  }
}

async function saveKey() {
  if (!apiKey.value.trim()) {
    flash('err', '请输入 API Key')
    return
  }
  try {
    await invoke('set_summary_api_key', { key: apiKey.value.trim() })
    apiKey.value = '' // 输入框立即清空，不留明文
    keySaved.value = true
    flash('ok', 'API Key 已存入系统凭据管理器')
  } catch (e) {
    flash('err', `保存 Key 失败: ${e}`)
  }
}

async function clearKey() {
  try {
    await invoke('clear_summary_api_key')
    keySaved.value = false
    flash('ok', 'API Key 已清除')
  } catch (e) {
    flash('err', `清除失败: ${e}`)
  }
}

async function testConnection() {
  testing.value = true
  try {
    // 输入框里有未保存的 Key → 先存再测（符合「填了就能测」的直觉）。
    if (apiKey.value.trim()) {
      await invoke('set_summary_api_key', { key: apiKey.value.trim() })
      apiKey.value = '' // 输入框立即清空，不留明文
      keySaved.value = true
    }
    const r = await invoke<string>('test_summary_connection')
    flash('ok', r)
  } catch (e) {
    flash('err', `连接失败: ${e}`)
  } finally {
    testing.value = false
  }
}

function addPreset() {
  const key = `custom_${Date.now()}`
  focusOptions.value.push({ key, label: '新建侧重', prompt: '' })
}

function removePreset(index: number) {
  const p = focusOptions.value[index]
  if (!p) return
  if (focus.value === p.key) {
    focus.value = ''
  }
  focusOptions.value.splice(index, 1)
}
</script>

<template>
  <section class="settings" aria-label="云端总结设置">
    <h2 class="title">总结设置（云端 LLM）</h2>

    <div class="grid">
      <label class="field">
        <span>Base URL</span>
        <input v-model="baseUrl" type="text" spellcheck="false" aria-label="Base URL" />
      </label>
      <label class="field">
        <span>模型</span>
        <input v-model="model" type="text" spellcheck="false" aria-label="模型名" />
      </label>
      <label class="field">
        <span>视觉模型（可选，多模态精修用）</span>
        <input v-model="vlmModel" type="text" spellcheck="false" aria-label="视觉模型名" />
      </label>
      <label class="field">
        <span>超时时长（秒，汇总定稿等长输入建议 ≥300）</span>
        <input
          v-model.number="timeoutSecs"
          type="number"
          min="30"
          step="30"
          aria-label="云端请求超时时长（秒）"
        />
      </label>
      <label class="field">
        <span>总结侧重点（下次生成/重生成生效）</span>
        <select v-model="focus" class="focus-select" aria-label="总结侧重点">
          <option value="">默认（均衡提炼）</option>
          <option v-for="p in focusOptions" :key="p.key" :value="p.key">
            {{ p.label }}
          </option>
        </select>
      </label>
      <label class="field">
        <span>API Key（仅存系统凭据管理器，不回显）</span>
        <input
          v-model="apiKey"
          type="password"
          :placeholder="keySaved ? '已保存（输入新 Key 可覆盖）' : '在此粘贴你的 API Key'"
          autocomplete="off"
          aria-label="API Key"
        />
      </label>
    </div>

    <div class="presets">
      <div class="presets-header">
        <h3 class="presets-title">侧重点预设维护</h3>
        <button class="btn small" @click="addPreset">新增预设</button>
      </div>
      <p class="presets-hint">名称供下拉框显示，提示词会注入到每段总结 system prompt 的「侧重点」条款。建议一句话。</p>
      <div class="preset-table">
        <div class="preset-row head">
          <span>名称</span>
          <span>提示词</span>
          <span></span>
        </div>
        <div v-for="(p, i) in focusOptions" :key="p.key" class="preset-row">
          <input v-model="p.label" type="text" placeholder="名称" />
          <input v-model="p.prompt" type="text" placeholder="提示词" />
          <button class="btn danger small" @click="removePreset(i)">删除</button>
        </div>
        <p v-if="!focusOptions.length" class="presets-empty">暂无预设，点「新增预设」添加。</p>
      </div>
    </div>

    <div class="actions">
      <button class="btn" @click="saveConfig">保存设置</button>
      <button class="btn" @click="saveKey">保存 Key</button>
      <button v-if="keySaved" class="btn danger" @click="clearKey">清除 Key</button>
      <button class="btn primary" :disabled="testing" @click="testConnection">
        {{ testing ? '测试中…' : '测试连接' }}
      </button>
    </div>

    <p v-if="message" class="msg" :class="messageKind" role="status">{{ message }}</p>
    <p class="hint">仅上传转写与课件文字（多模态精修默认关）；音视频永不离开本机。</p>
    <p class="hint">内容涉及专业术语/公式/定义时，要点自动带「术语：大白话解释」；不涉及则为默认格式。</p>
  </section>
</template>

<style scoped>
.settings {
  border-top: 1px solid #2a2a2a;
  padding-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.title {
  font-size: 13px;
  font-weight: 500;
  color: #ccc;
}
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 16px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: #888;
}
.field input {
  padding: 7px 10px;
  font-size: 13px;
  background: #222;
  color: #e0e0e0;
  border: 1px solid #333;
  border-radius: 6px;
  outline: none;
}
.field input:focus-visible {
  outline: 2px solid #2563eb;
  outline-offset: 1px;
}
.focus-select {
  padding: 7px 10px;
  font-size: 13px;
  background: #222;
  color: #e0e0e0;
  border: 1px solid #333;
  border-radius: 6px;
  outline: none;
  cursor: pointer;
}
.focus-select:focus-visible {
  outline: 2px solid #2563eb;
  outline-offset: 1px;
}
.presets {
  background: #161616;
  border: 1px solid #2a2a2a;
  border-radius: 8px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.presets-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.presets-title {
  font-size: 13px;
  font-weight: 500;
  color: #ccc;
  margin: 0;
}
.presets-hint {
  font-size: 12px;
  color: #666;
  margin: 0;
}
.presets-empty {
  font-size: 12px;
  color: #555;
  padding: 8px 0;
  margin: 0;
}
.preset-table {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.preset-row {
  display: grid;
  grid-template-columns: 140px 1fr auto;
  gap: 8px;
  align-items: center;
}
.preset-row.head {
  font-size: 12px;
  color: #888;
  padding: 0 4px;
}
.preset-row input {
  padding: 6px 8px;
  font-size: 13px;
  background: #222;
  color: #e0e0e0;
  border: 1px solid #333;
  border-radius: 6px;
  outline: none;
}
.preset-row input:focus-visible {
  outline: 2px solid #2563eb;
  outline-offset: 1px;
}
.actions {
  display: flex;
  gap: 10px;
}
.btn {
  padding: 7px 16px;
  font-size: 13px;
  background: #222;
  color: #e0e0e0;
  border: 1px solid #333;
  border-radius: 6px;
  cursor: pointer;
}
.btn:hover {
  background: #2a2a2a;
}
.btn.primary {
  background: #2563eb;
  border-color: #2563eb;
  color: #fff;
}
.btn.primary:hover:not(:disabled) {
  background: #1d4ed8;
}
.btn.primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn.danger {
  color: #f87171;
}
.btn.small {
  padding: 5px 12px;
  font-size: 12px;
}
.btn:focus-visible {
  outline: 2px solid #60a5fa;
  outline-offset: 1px;
}
.msg {
  font-size: 13px;
}
.msg.ok {
  color: #4ade80;
}
.msg.err {
  color: #f87171;
}
.hint {
  font-size: 12px;
  color: #555;
}
</style>

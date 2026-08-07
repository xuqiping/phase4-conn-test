<script setup lang="ts">
// Step 8 (FR-107/109): 云端总结设置 —— Base URL / 模型名（非密，落 %APPDATA% 配置）+
// API Key（仅存 Windows 凭据管理器，永不回显）+ 连通性自检。
import { onMounted, ref } from 'vue'
import { invoke } from '@tauri-apps/api/core'

interface SummaryConfig {
  base_url: string
  model: string
  vlm_model: string | null
  max_segment_chars: number
  concurrency: number
  timeout_secs: number
}

const baseUrl = ref('')
const model = ref('')
const vlmModel = ref('')
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
    vlmModel.value = cfg.vlm_model ?? ''
    keySaved.value = await invoke<boolean>('has_summary_api_key')
  } catch (e) {
    flash('err', `读取设置失败: ${e}`)
  }
})

async function saveConfig() {
  try {
    await invoke('set_summary_config', {
      config: {
        base_url: baseUrl.value.trim(),
        model: model.value.trim(),
        vlm_model: vlmModel.value.trim() || null,
        max_segment_chars: 2000,
        concurrency: 2,
        timeout_secs: 120,
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
    const r = await invoke<string>('test_summary_connection')
    flash('ok', r)
  } catch (e) {
    flash('err', `连接失败: ${e}`)
  } finally {
    testing.value = false
  }
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

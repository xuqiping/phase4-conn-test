<template>
  <div>
    <n-page-header @back="$router.push({ name: 'dashboard' })">
      <template #title>系统设置</template>
    </n-page-header>

    <n-card title="全局默认值" size="small" style="margin-top: 16px">
      <n-spin :show="loading">
        <n-form label-placement="left" :label-width="220">
          <n-form-item label="新用户默认设备上限">
            <n-input-number v-model:value="form.defaultDeviceLimit" :min="1" style="width: 200px" />
            <span class="hint">注册新用户时分配的设备绑定数量</span>
          </n-form-item>
          <n-form-item label="新用户默认离线缓存(分钟)">
            <n-input-number v-model:value="form.defaultOfflineCacheMinutes" :min="0" style="width: 200px" />
            <span class="hint">0 表示不允许离线使用</span>
          </n-form-item>
          <n-form-item label="匿名试用天数">
            <n-input-number v-model:value="form.anonymousTrialDays" :min="1" style="width: 200px" />
            <span class="hint">匿名设备全功能试用时长(天)</span>
          </n-form-item>
          <n-form-item label="免费模块更换间隔(天)">
            <n-input-number v-model:value="form.freeModuleChangeDays" :min="1" style="width: 200px" />
            <span class="hint">匿名用户两次更换免费模块的最短间隔</span>
          </n-form-item>
        </n-form>

        <n-space>
          <n-button type="primary" :loading="saving" @click="handleSave">保存设置</n-button>
          <n-button @click="load">重置</n-button>
        </n-space>
      </n-spin>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import {
  NPageHeader, NCard, NForm, NFormItem, NInputNumber, NButton, NSpace, NSpin, useMessage
} from 'naive-ui'
import * as settingsApi from '@/api/settings'
import type { SystemSettings } from '@/types'

const message = useMessage()
const loading = ref(false)
const saving = ref(false)

const form = reactive<SystemSettings>({
  defaultDeviceLimit: 1,
  defaultOfflineCacheMinutes: 0,
  anonymousTrialDays: 7,
  freeModuleChangeDays: 30
})

async function load() {
  loading.value = true
  try {
    const data = await settingsApi.getSettings()
    Object.assign(form, data)
  } catch (err) {
    message.error(err instanceof Error ? err.message : '加载设置失败')
  } finally {
    loading.value = false
  }
}

async function handleSave() {
  saving.value = true
  try {
    const data = await settingsApi.updateSettings({ ...form })
    Object.assign(form, data)
    message.success('设置已保存')
  } catch (err) {
    message.error(err instanceof Error ? err.message : '保存设置失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.hint {
  margin-left: 12px;
  font-size: 12px;
  color: var(--n-text-color-3, #999);
}
</style>

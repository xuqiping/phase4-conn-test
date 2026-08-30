<template>
  <div class="billing-settings">
    <n-form label-placement="left" label-width="160" class="billing-settings__form">
      <n-form-item label="低余额阈值">
        <n-input-number
          v-model:value="lowBalanceThreshold"
          :min="0"
          :max="1000000"
          class="billing-settings__input"
        />
        <span class="billing-settings__unit">积分</span>
      </n-form-item>
      <p class="billing-settings__hint">
        余额低于该阈值的用户禁止多任务并行（对话/视频生成），防止欠费用户并发刷量
      </p>
      <n-form-item label="低余额最大在途任务数">
        <n-input-number
          v-model:value="lowBalanceMaxInflight"
          :min="1"
          :max="100"
          class="billing-settings__input"
        />
        <span class="billing-settings__unit">个</span>
      </n-form-item>

      <n-divider class="billing-settings__divider">闲时时段（D8）</n-divider>
      <n-form-item label="启用闲时计价">
        <n-switch v-model:value="offPeakEnabled" />
      </n-form-item>
      <p class="billing-settings__hint">
        仅在窗口内按价表「闲时」列计价（未配闲时价的模型仍按忙时价）；时段固定 Asia/Shanghai；
        跨零点窗口直接写 22:00 → 08:00，系统自动拆分判断；到零点整请选 23:59
      </p>
      <n-form-item label="工作日窗口">
        <div class="billing-settings__windows">
          <div v-for="(row, i) in weekdayRows" :key="'wd' + i" class="billing-settings__window-row">
            <n-time-picker v-model:value="row.startTs" format="HH:mm" class="billing-settings__time" />
            <span class="billing-settings__sep">→</span>
            <n-time-picker v-model:value="row.endTs" format="HH:mm" class="billing-settings__time" />
            <n-button size="small" quaternary type="error" @click="weekdayRows.splice(i, 1)">删除</n-button>
          </div>
          <n-button
            v-if="weekdayRows.length < 4"
            size="small"
            dashed
            class="billing-settings__add"
            @click="weekdayRows.push({ startTs: null, endTs: null })"
          >+ 添加窗口</n-button>
        </div>
      </n-form-item>
      <n-form-item label="周末窗口">
        <div class="billing-settings__windows">
          <div v-for="(row, i) in weekendRows" :key="'we' + i" class="billing-settings__window-row">
            <n-time-picker v-model:value="row.startTs" format="HH:mm" class="billing-settings__time" />
            <span class="billing-settings__sep">→</span>
            <n-time-picker v-model:value="row.endTs" format="HH:mm" class="billing-settings__time" />
            <n-button size="small" quaternary type="error" @click="weekendRows.splice(i, 1)">删除</n-button>
          </div>
          <n-button
            v-if="weekendRows.length < 4"
            size="small"
            dashed
            class="billing-settings__add"
            @click="weekendRows.push({ startTs: null, endTs: null })"
          >+ 添加窗口</n-button>
        </div>
      </n-form-item>
      <p v-if="offPeakEnabled && !weekdayRows.length && !weekendRows.length" class="billing-settings__warn">
        已启用但未配任何窗口：所有时间都按忙时价计费
      </p>

      <n-button type="primary" :loading="saving" @click="handleSave">保存</n-button>
    </n-form>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  NButton, NDivider, NForm, NFormItem, NInputNumber, NSwitch, NTimePicker, useMessage
} from 'naive-ui'
import { systemApi } from '@/api/system'
import type { OffPeakWindow } from '@/api/system'

const message = useMessage()
const saving = ref(false)
const lowBalanceThreshold = ref(100)
const lowBalanceMaxInflight = ref(1)

// ---- D8（V160）：闲时时段 ----
const offPeakEnabled = ref(false)
interface WindowRow { startTs: number | null; endTs: number | null }
const weekdayRows = ref<WindowRow[]>([])
const weekendRows = ref<WindowRow[]>([])

/** "HH:mm" → 当日本地时间戳（time-picker 值；与浏览器时区无关的钟面时间）；24:00 显示层收敛为 23:59 */
function hmToTs(hm: string): number | null {
  const m = /^(\d{1,2}):(\d{2})$/.exec(hm || '')
  if (!m) return null
  let h = Number(m[1])
  let min = Number(m[2])
  if (h >= 24) { h = 23; min = 59 }
  const d = new Date()
  d.setHours(h, min, 0, 0)
  return d.getTime()
}

/** time-picker 时间戳 → "HH:mm"（按本地钟面读回，与写入同域往返一致） */
function tsToHm(ts: number): string {
  const d = new Date(ts)
  return [d.getHours(), d.getMinutes()].map(n => String(n).padStart(2, '0')).join(':')
}

function windowsToRows(windows?: OffPeakWindow[] | null): WindowRow[] {
  if (!windows) return []
  return windows.map(w => ({ startTs: hmToTs(w.start), endTs: hmToTs(w.end) }))
}

/** 行 → 窗口串；整行空跳过；半填返回 null（上层拦截） */
function rowsToWindows(rows: WindowRow[]): OffPeakWindow[] | null {
  const out: OffPeakWindow[] = []
  for (const r of rows) {
    if (r.startTs == null && r.endTs == null) continue
    if (r.startTs == null || r.endTs == null) return null
    out.push({ start: tsToHm(r.startTs), end: tsToHm(r.endTs) })
  }
  return out
}

onMounted(load)

async function load() {
  const res = await systemApi.getBillingSettings()
  lowBalanceThreshold.value = res.data.data.lowBalanceThreshold ?? 100
  lowBalanceMaxInflight.value = res.data.data.lowBalanceMaxInflight ?? 1
  offPeakEnabled.value = res.data.data.offPeak?.enabled ?? false
  weekdayRows.value = windowsToRows(res.data.data.offPeak?.weekday)
  weekendRows.value = windowsToRows(res.data.data.offPeak?.weekend)
}

async function handleSave() {
  const weekday = rowsToWindows(weekdayRows.value)
  const weekend = rowsToWindows(weekendRows.value)
  if (weekday === null || weekend === null) {
    message.error('闲时窗口起止时间须成对填写完整（HH:mm）')
    return
  }
  saving.value = true
  try {
    await systemApi.updateBillingSettings({
      lowBalanceThreshold: lowBalanceThreshold.value,
      lowBalanceMaxInflight: lowBalanceMaxInflight.value,
      offPeak: { enabled: offPeakEnabled.value, weekday, weekend }
    })
    message.success('计费设置已更新，立即生效')
  } finally {
    saving.value = false
  }
}
</script>

<style lang="scss" scoped>
.billing-settings__form {
  max-width: 560px;
}

.billing-settings__input {
  width: 160px;
}

.billing-settings__unit {
  margin-left: 8px;
  color: var(--color-text-secondary);
}

.billing-settings__hint {
  margin: -8px 0 16px 160px;
  font-size: 12px;
  color: var(--color-text-secondary);
}

.billing-settings__warn {
  margin: -8px 0 16px 160px;
  font-size: 12px;
  color: var(--color-warning, #f0a020);
}

.billing-settings__divider {
  margin: 8px 0 20px;

  :deep(.n-divider__title) {
    font-size: 13px;
  }
}

.billing-settings__windows {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.billing-settings__window-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.billing-settings__time {
  width: 110px;
}

.billing-settings__sep {
  color: var(--color-text-secondary);
}

.billing-settings__add {
  width: 240px;
}
</style>

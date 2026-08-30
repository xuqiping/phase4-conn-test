<!-- ============================================================
  波及通知 badge（计划12 F-4a）— 3s 轮询未处理数 + 下拉列表 + ACK
  · 走 memoryApi.countNotifications / listNotifications / ackNotification
  · 波及 = 他人撤回 turn 影响我的总结 / 项目删除影响（跨用户，非本人操作触发）
  ============================================================ -->
<template>
  <n-popover
    trigger="click"
    placement="bottom"
    :width="340"
    @update:show="onPopoverShow"
  >
    <template #trigger>
      <n-badge :value="count" :max="99" :show="count > 0" type="warning">
        <n-button quaternary circle size="small">
          <template #icon>
            <span class="memory-notif-badge__icon">🔔</span>
          </template>
        </n-button>
      </n-badge>
    </template>

    <div class="memory-notif-badge__panel">
      <div class="memory-notif-badge__head">
        <span>波及通知</span>
        <n-button v-if="list.length" size="tiny" quaternary @click="ackAll">全部已读</n-button>
      </div>
      <n-empty v-if="!list.length" size="small" description="暂无波及通知" />
      <div v-for="n in list" :key="n.id" class="memory-notif-badge__item">
        <div class="memory-notif-badge__msg">{{ n.message || typeLabel(n.type) }}</div>
        <div class="memory-notif-badge__meta">
          <n-tag size="tiny" :type="n.type === 'PROJECT_DELETED_AFFECTED' ? 'error' : 'warning'" :bordered="false">
            {{ typeLabel(n.type) }}
          </n-tag>
          <span>{{ n.createdAt }}</span>
          <n-button size="tiny" quaternary type="primary" @click="ack(n.id)">已读</n-button>
        </div>
      </div>
    </div>
  </n-popover>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { NBadge, NButton, NEmpty, NPopover, NTag, useMessage } from 'naive-ui'
import { memoryApi, type MemoryNotificationVO } from '@/api/memory'

const message = useMessage()

const count = ref(0)
const list = ref<MemoryNotificationVO[]>([])
let timer: ReturnType<typeof setInterval> | null = null

async function refreshCount() {
  try {
    const res = await memoryApi.countNotifications()
    count.value = res.data?.data ?? 0
  } catch { /* 静默：轮询失败不打扰 */ }
}

async function loadList() {
  try {
    const res = await memoryApi.listNotifications()
    list.value = res.data?.data ?? []
  } catch (e: any) {
    message.error(e?.message || '加载通知失败')
  }
}

function onPopoverShow(show: boolean) {
  if (show) loadList()
}

async function ack(id: number) {
  try {
    await memoryApi.ackNotification(id)
    list.value = list.value.filter(n => n.id !== id)
    count.value = Math.max(0, count.value - 1)
  } catch (e: any) {
    message.error(e?.message || '操作失败')
  }
}

async function ackAll() {
  for (const n of [...list.value]) {
    try { await memoryApi.ackNotification(n.id) } catch { /* continue */ }
  }
  list.value = []
  count.value = 0
  message.success('已全部标记已读')
}

function typeLabel(t: string): string {
  if (t === 'PROJECT_DELETED_AFFECTED') return '项目删除'
  if (t === 'SUMMARY_AFFECTED_BY_RECALL') return '撤回波及'
  if (t === 'LINK_REQUEST') return '授权申请'
  if (t === 'LINK_RESULT') return '授权结果'
  // 17x#3/#4（V138）：组邀请 / 公共池入组
  if (t === 'GROUP_INVITE') return '组邀请'
  if (t === 'GROUP_INVITE_RESULT') return '邀请结果'
  if (t === 'GROUP_JOIN_REQUEST') return '入组申请'
  if (t === 'GROUP_JOIN_RESULT') return '申请结果'
  if (t === 'USER_GRANT_REQUEST') return '召回申请'
  if (t === 'USER_GRANT_RESULT') return '召回结果'
  return t
}

onMounted(() => {
  refreshCount()
  // 3s 轮询 badge 计数（仅 count，轻量）
  timer = setInterval(refreshCount, 3000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<style lang="scss" scoped>
.memory-notif-badge {
  &__icon {
    font-size: 14px;
  }
  &__panel {
    max-height: 360px;
    overflow-y: auto;
  }
  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: 13px;
    font-weight: 600;
    padding-bottom: 8px;
    margin-bottom: 8px;
    border-bottom: 1px solid var(--divider-color, rgba(255, 255, 255, 0.09));
  }
  &__item {
    padding: 8px 0;
    border-bottom: 1px solid var(--divider-color, rgba(255, 255, 255, 0.06));
    &:last-child {
      border-bottom: none;
    }
  }
  &__msg {
    font-size: 12px;
    line-height: 1.5;
  }
  &__meta {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-top: 4px;
    font-size: 11px;
    opacity: 0.6;
    & > n-button {
      margin-left: auto;
    }
  }
}
</style>

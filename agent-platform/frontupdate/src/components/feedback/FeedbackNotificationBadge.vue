<!-- ============================================================
  反馈通知 badge（19x）— 3s 轮询未读数 + 下拉列表 + 已读/跳转
  · 克隆 MemoryNotificationBadge 模式（count 走部分索引，轻量）
  · 点击通知 → 跳反馈中心对应 tab（建议/提问）并标记已读
  ============================================================ -->
<template>
  <n-popover trigger="click" placement="bottom" :width="340" @update:show="onPopoverShow">
    <template #trigger>
      <n-badge :value="count" :max="99" :show="count > 0" type="info">
        <n-button quaternary circle size="small" title="反馈通知">
          <template #icon>
            <span class="feedback-notif-badge__icon">💬</span>
          </template>
        </n-button>
      </n-badge>
    </template>

    <div class="feedback-notif-badge__panel">
      <div class="feedback-notif-badge__head">
        <span>反馈通知</span>
        <n-button v-if="list.length" size="tiny" quaternary @click="readAll">全部已读</n-button>
      </div>
      <n-empty v-if="!list.length" size="small" description="暂无通知" />
      <div v-for="n in list" :key="n.id" class="feedback-notif-badge__item" @click="open(n)">
        <div class="feedback-notif-badge__msg">{{ n.message }}</div>
        <div class="feedback-notif-badge__meta">
          <n-tag size="tiny" :type="n.type === 'SUGGESTION_REVIEWED' ? 'success' : 'info'" :bordered="false">
            {{ n.type === 'SUGGESTION_REVIEWED' ? '建议审核' : '提问回答' }}
          </n-tag>
          <span>{{ fmt(n.createdAt) }}</span>
          <n-tag v-if="n.readAt" size="tiny" :bordered="false">已读</n-tag>
        </div>
      </div>
    </div>
  </n-popover>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { NBadge, NButton, NEmpty, NPopover, NTag, useMessage } from 'naive-ui'
import { useRouter } from 'vue-router'
import { feedbackApi, type FeedbackNotificationVO } from '@/api/feedback'

const message = useMessage()
const router = useRouter()

const count = ref(0)
const list = ref<FeedbackNotificationVO[]>([])
let timer: ReturnType<typeof setInterval> | null = null

function fmt(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString('zh-CN', { hour12: false }) : ''
}

async function refreshCount() {
  try {
    const res = await feedbackApi.unreadCount()
    count.value = res.data?.data?.count ?? 0
  } catch { /* 静默：轮询失败不打扰 */ }
}

async function loadList() {
  try {
    const res = await feedbackApi.notifications({ page: 1, size: 20 })
    list.value = res.data?.data?.records ?? []
  } catch (e: unknown) {
    message.error((e as Error)?.message || '加载通知失败')
  }
}

function onPopoverShow(show: boolean) {
  if (show) loadList()
}

/** 点击：标记已读 + 跳反馈中心对应 tab（建议/提问）。 */
async function open(n: FeedbackNotificationVO) {
  if (!n.readAt) {
    try {
      await feedbackApi.markNotificationRead(n.id)
      n.readAt = new Date().toISOString()
      count.value = Math.max(0, count.value - 1)
    } catch { /* 已读失败不阻塞跳转 */ }
  }
  router.push({
    path: '/feedback',
    query: { tab: n.type === 'SUGGESTION_REVIEWED' ? 'suggestions' : 'questions' }
  })
}

async function readAll() {
  try {
    await feedbackApi.markAllNotificationsRead()
    list.value = list.value.map(n => ({ ...n, readAt: n.readAt ?? new Date().toISOString() }))
    count.value = 0
    message.success('已全部标记已读')
  } catch {
    message.error('操作失败')
  }
}

onMounted(() => {
  refreshCount()
  // 3s 轮询未读数（仅 count，部分索引轻量——与记忆铃铛同模式已验证）
  timer = setInterval(refreshCount, 3000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})

// 测试探针（同 RechargeDialog 模式）：单测直调，绕开 popover 传送门
defineExpose({ count, list, loadList, open, readAll })
</script>

<style lang="scss" scoped>
.feedback-notif-badge {
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
    cursor: pointer;
    &:last-child {
      border-bottom: none;
    }
    &:hover {
      background: var(--color-bg-secondary, rgba(255, 255, 255, 0.04));
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
  }
}
</style>

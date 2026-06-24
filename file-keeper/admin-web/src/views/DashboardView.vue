<template>
  <div>
    <n-space justify="space-between" align="center" style="margin-bottom: 16px">
      <n-h2 style="margin: 0">Dashboard</n-h2>
      <n-button size="small" :loading="loading" @click="load">刷新</n-button>
    </n-space>

    <n-spin :show="loading">
      <n-grid :cols="1" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
        <!-- KPI 卡片 -->
        <n-gi span="1" style="display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px">
          <n-card size="small" hoverable class="kpi-card" :bordered="true" style="cursor: pointer" @click="goToPendingReview">
            <n-statistic label="待审核用户" :value="stats?.pendingReviewUsers ?? 0" />
            <n-tag v-if="(stats?.pendingReviewUsers ?? 0) > 0" type="warning" size="small" style="margin-top: 8px">点击处理</n-tag>
          </n-card>

          <n-card size="small" hoverable class="kpi-card">
            <n-statistic label="即将过期授权(7天内)" :value="stats?.expiringSoonEntitlements ?? 0" />
            <n-tag v-if="(stats?.expiringSoonEntitlements ?? 0) > 0" type="warning" size="small" style="margin-top: 8px">需关注</n-tag>
          </n-card>

          <n-card size="small" hoverable class="kpi-card">
            <n-statistic label="活跃设备" :value="stats?.activeDevices ?? 0" />
          </n-card>

          <n-card size="small" hoverable class="kpi-card">
            <n-statistic label="已过期授权" :value="stats?.expiredEntitlements ?? 0" />
            <n-tag v-if="(stats?.expiredEntitlements ?? 0) > 0" type="error" size="small" style="margin-top: 8px">需续期或撤销</n-tag>
          </n-card>
        </n-gi>

        <!-- 用户分布 -->
        <n-gi>
          <n-card title="用户分布" size="small">
            <n-grid :cols="2" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
              <n-gi span="2 m:1">
                <n-statistic label="总用户数" :value="stats?.totalUsers ?? 0" />
              </n-gi>
              <n-gi>
                <n-statistic label="正常(active)" :value="stats?.activeUsers ?? 0">
                  <template #suffix>
                    <span class="pct-text">{{ pct(stats?.activeUsers) }}</span>
                  </template>
                </n-statistic>
                <n-progress type="line" :percentage="pct(stats?.activeUsers)" :show-indicator="false" :height="4" style="margin-top: 8px" />
              </n-gi>
              <n-gi>
                <n-statistic label="待审核" :value="stats?.pendingReviewUsers ?? 0">
                  <template #suffix>
                    <span class="pct-text">{{ pct(stats?.pendingReviewUsers) }}</span>
                  </template>
                </n-statistic>
                <n-progress type="line" status="warning" :percentage="pct(stats?.pendingReviewUsers)" :show-indicator="false" :height="4" style="margin-top: 8px" />
              </n-gi>
              <n-gi>
                <n-statistic label="待验证" :value="stats?.pendingVerificationUsers ?? 0" />
              </n-gi>
              <n-gi>
                <n-statistic label="已禁用" :value="stats?.disabledUsers ?? 0">
                  <template #suffix>
                    <span class="pct-text">{{ pct(stats?.disabledUsers) }}</span>
                  </template>
                </n-statistic>
                <n-progress type="line" status="error" :percentage="pct(stats?.disabledUsers)" :show-indicator="false" :height="4" style="margin-top: 8px" />
              </n-gi>
            </n-grid>
          </n-card>
        </n-gi>
      </n-grid>
    </n-spin>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  NCard, NGrid, NGi, NStatistic, NH2, NSpace, NButton, NSpin, NTag, NProgress, useMessage
} from 'naive-ui'
import * as statsApi from '@/api/stats'
import type { DashboardStats } from '@/types'

const router = useRouter()
const message = useMessage()
const loading = ref(false)
const stats = ref<DashboardStats | null>(null)

async function load() {
  loading.value = true
  try {
    stats.value = await statsApi.getDashboardStats()
  } catch (err) {
    message.error(err instanceof Error ? err.message : '加载统计失败')
  } finally {
    loading.value = false
  }
}

function pct(value?: number): number {
  const total = stats.value?.totalUsers ?? 0
  if (!total) return 0
  return Math.round(((value ?? 0) / total) * 100)
}

function goToPendingReview() {
  router.push({ name: 'users', query: { status: 'pending_review' } })
}

onMounted(load)
</script>

<style scoped>
.kpi-card {
  transition: transform 0.15s ease;
}
.kpi-card:hover {
  transform: translateY(-2px);
}
.pct-text {
  font-size: 12px;
  color: var(--n-text-color-3, #999);
  margin-left: 4px;
}
</style>

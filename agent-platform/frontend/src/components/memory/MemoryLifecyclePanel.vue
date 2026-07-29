<!-- ============================================================
  生命周期折叠板（计划12 F-4b，总体设计 §3.7）— 已删除/已离开项目记忆拉取
  · 走 memoryApi.listDeletedProjects / restoreDeletedProject（restore：仅拉 turn 不拉 summary）
  · 走 memoryApi.listDepartedProjects / copyDepartedProjectTo（copy 非 move，原项目零改动）
  · 已删除板有未处理记忆（turnCount>0）时徽标高亮；restore 后后端置 resolved，顶部通知 badge 3s 轮询自消
  · 拉取动作 = 自建新项目 Q 重挂本人流水账；可空名走后端默认「「原项目名」记忆拉取」
  ============================================================ -->
<template>
  <div class="memory-lifecycle">
    <n-alert type="info" :bordered="false" size="small" class="memory-lifecycle__top">
      项目被删除或你已离开时，你在该项目的流水账仍保留（不删数据）。可在此拉取到自建新项目继续召回——拉取是「复制挂载」，原项目数据零改动。
    </n-alert>

    <n-space :size="8" align="center" class="memory-lifecycle__toolbar">
      <n-button size="small" :loading="loading" @click="load">刷新</n-button>
    </n-space>

    <n-collapse :default-expanded-names="deletedPending.length ? ['deleted'] : []" arrow-placement="left">
      <!-- 已删除项目：有未处理记忆时标题徽标高亮（§3.7 badge） -->
      <n-collapse-item name="deleted">
        <template #header>
          <n-badge :value="deletedPendingTurns" :max="99" :show="deletedPendingTurns > 0" type="error">
            <span>已删除项目（{{ deleted.length }}）</span>
          </n-badge>
        </template>
        <n-empty v-if="!deleted.length" size="small" description="无已删除项目波及的记忆" />
        <div v-for="p in deleted" :key="p.projectId" class="memory-lifecycle__row">
          <div class="memory-lifecycle__row-main">
            <span class="memory-lifecycle__name">{{ p.projectName }}</span>
            <n-tag size="tiny" :type="p.turnCount > 0 ? 'error' : 'default'" :bordered="false">
              {{ p.turnCount }} 条流水账
            </n-tag>
          </div>
          <n-button
            size="small"
            type="primary"
            quaternary
            :disabled="p.turnCount <= 0"
            :loading="pulling === `deleted-${p.projectId}`"
            @click="openPull('deleted', p)"
          >拉取到自建新项目</n-button>
        </div>
      </n-collapse-item>

      <!-- 已离开项目：copy 非 move，别的成员照常召回 -->
      <n-collapse-item name="departed">
        <template #header>
          <span>已离开项目（{{ departed.length }}）</span>
        </template>
        <n-empty v-if="!departed.length" size="small" description="无已离开项目" />
        <div v-for="p in departed" :key="p.projectId" class="memory-lifecycle__row">
          <div class="memory-lifecycle__row-main">
            <span class="memory-lifecycle__name">{{ p.projectName }}</span>
            <n-tag size="tiny" :bordered="false">{{ p.turnCount }} 条流水账</n-tag>
            <span v-if="p.departedAt" class="memory-lifecycle__departed-at">离开于 {{ formatTime(p.departedAt) }}</span>
          </div>
          <n-button
            size="small"
            type="primary"
            quaternary
            :disabled="p.turnCount <= 0"
            :loading="pulling === `departed-${p.projectId}`"
            @click="openPull('departed', p)"
          >拉取到自建新项目</n-button>
        </div>
      </n-collapse-item>
    </n-collapse>

    <!-- 拉取确认：可空名走后端默认命名 -->
    <n-modal v-model:show="pullEditing" preset="card" title="拉取到自建新项目" :style="{ maxWidth: '420px', width: '90vw' }">
      <n-space vertical :size="12">
        <div class="memory-lifecycle__pull-desc">
          将你在「{{ pullTarget?.projectName }}」的 {{ pullTarget?.turnCount }} 条流水账挂载到自建新项目。
          <template v-if="pullKind === 'deleted'">仅拉流水账，不拉项目总结。</template>
          <template v-else>原项目与其他成员不受任何影响（复制挂载，非移动）。</template>
        </div>
        <div>
          <div class="memory-lifecycle__field-label">新项目名（可留空）</div>
          <n-input
            v-model:value="pullName"
            :placeholder="`「${pullTarget?.projectName ?? '原项目'}」记忆拉取`"
            maxlength="100"
            show-count
            clearable
          />
        </div>
        <n-space justify="end">
          <n-button @click="pullEditing = false">取消</n-button>
          <n-button type="primary" :loading="pullSaving" @click="confirmPull">拉取</n-button>
        </n-space>
      </n-space>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NAlert, NBadge, NButton, NCollapse, NCollapseItem, NEmpty, NInput, NModal, NSpace, NTag, useMessage } from 'naive-ui'
import { memoryApi, type MemoryLifecycleProjectVO } from '@/api/memory'

const emit = defineEmits<{
  /** 已删除板未处理流水账总数（父级页签徽标联动）。 */
  (e: 'update:deletedPendingTurns', n: number): void
}>()

const message = useMessage()

const deleted = ref<MemoryLifecycleProjectVO[]>([])
const departed = ref<MemoryLifecycleProjectVO[]>([])
const loading = ref(false)
const pulling = ref<string | null>(null)

const pullEditing = ref(false)
const pullSaving = ref(false)
const pullKind = ref<'deleted' | 'departed'>('deleted')
const pullTarget = ref<MemoryLifecycleProjectVO | null>(null)
const pullName = ref('')

/** 有未处理记忆（turnCount>0）的已删除项目行。 */
const deletedPending = computed(() => deleted.value.filter(p => p.turnCount > 0))
const deletedPendingTurns = computed(() => deletedPending.value.reduce((s, p) => s + p.turnCount, 0))

async function load() {
  loading.value = true
  try {
    const [delRes, depRes] = await Promise.all([
      memoryApi.listDeletedProjects(),
      memoryApi.listDepartedProjects()
    ])
    deleted.value = delRes.data?.data ?? []
    departed.value = depRes.data?.data ?? []
    emit('update:deletedPendingTurns', deletedPendingTurns.value)
  } catch (e: any) {
    message.error(e?.response?.data?.msg || e?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function openPull(kind: 'deleted' | 'departed', p: MemoryLifecycleProjectVO) {
  pullKind.value = kind
  pullTarget.value = p
  pullName.value = ''
  pullEditing.value = true
}

async function confirmPull() {
  const p = pullTarget.value
  if (!p) return
  pullSaving.value = true
  const key = `${pullKind.value}-${p.projectId}`
  pulling.value = key
  try {
    const name = pullName.value.trim() || undefined
    const res = pullKind.value === 'deleted'
      ? await memoryApi.restoreDeletedProject(p.projectId, name)
      : await memoryApi.copyDepartedProjectTo(p.projectId, name)
    const vo = res.data?.data
    message.success(`已拉取 ${vo?.affectedTurns ?? 0} 条流水账到新项目「${vo?.newProjectName ?? ''}」`)
    pullEditing.value = false
    await load()
  } catch (e: any) {
    // 403=非已离开成员（copy-to 前置校验）；404=无待拉取/原项目不存在（防存在性探测）
    message.error(e?.response?.data?.msg || e?.message || '拉取失败')
  } finally {
    pullSaving.value = false
    pulling.value = null
  }
}

function formatTime(iso: string): string {
  try { return new Date(iso).toLocaleString('zh-CN') } catch { return iso }
}

onMounted(load)
</script>

<style lang="scss" scoped>
.memory-lifecycle {
  &__top {
    margin-bottom: 12px;
  }
  &__toolbar {
    margin-bottom: 12px;
  }
  &__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    flex-wrap: wrap;
    padding: 8px 0;
    border-bottom: 1px solid var(--divider-color, rgba(255, 255, 255, 0.06));
    &:last-child {
      border-bottom: none;
    }
  }
  &__row-main {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
    min-width: 0;
  }
  &__name {
    font-size: 13px;
    font-weight: 500;
    word-break: break-all;
  }
  &__departed-at {
    font-size: 11px;
    opacity: 0.55;
  }
  &__pull-desc {
    font-size: 13px;
    line-height: 1.6;
    opacity: 0.85;
  }
  &__field-label {
    margin-bottom: 6px;
    font-size: 13px;
    opacity: 0.85;
  }
}
</style>

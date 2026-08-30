<!-- ============================================================
  流水账页签（计划12 F-3a）— 列本人流水账 + raw 查看 / 批量删 + 挂载项目
  · 走 memoryApi.listTurns / listRawTurns / batchDeleteRawTurns（/memory/turns）
  · 仅本人（向量 7/13）；无导出；12h 撤回规则由后端 DELETE 时拦
  ============================================================ -->
<template>
  <div class="memory-turn-section">
    <n-space :size="8" align="center" class="memory-turn-section__toolbar">
      <n-radio-group v-model:value="mode" size="small">
        <n-radio-button value="all">全部</n-radio-button>
        <n-radio-button value="raw">仅 raw</n-radio-button>
      </n-radio-group>
      <n-input v-model:value="keyword" placeholder="按概要/原文筛选" clearable size="small" style="max-width: 220px" />
      <n-button size="small" :loading="loading" @click="load">刷新</n-button>
      <n-button
        v-if="checkedIds.length"
        size="small"
        type="error"
        ghost
        :loading="deleting"
        @click="batchDelete"
      >
        删所选 {{ checkedIds.length }}
      </n-button>
      <span class="memory-turn-section__hint">{{ filtered.length }} / {{ rows.length }} 条</span>
    </n-space>

    <n-empty v-if="!loading && !filtered.length" size="small" description="暂无流水账" />

    <n-card v-for="t in filtered" :key="t.id" size="small" :bordered="true" style="margin-bottom: 8px">
      <div class="memory-turn-section__row">
        <n-checkbox
          :checked="checkedIds.includes(t.id)"
          @update:checked="toggle(t.id, $event)"
        />
        <div class="memory-turn-section__body">
          <div class="memory-turn-section__head">
            <n-tag size="tiny" :type="t.direction === 'INPUT' ? 'info' : 'success'" :bordered="false">
              {{ t.direction === 'INPUT' ? '我说' : '回答' }}
            </n-tag>
            <n-tag v-if="!t.genDone" size="tiny" type="warning" :bordered="false">raw 未生成</n-tag>
            <span class="memory-turn-section__time">{{ t.createdAt }}</span>
          </div>
          <div v-if="t.l1Summary" class="memory-turn-section__l1">{{ t.l1Summary }}</div>
          <div v-if="t.l2Detail" class="memory-turn-section__l2">{{ t.l2Detail }}</div>
          <div v-if="t.rawContent" class="memory-turn-section__raw">{{ t.rawContent }}</div>
          <div class="memory-turn-section__tags">
            <n-tag v-for="(label, i) in t.tagLabels" :key="i" size="tiny" :bordered="false">{{ label }}</n-tag>
          </div>
          <div v-if="t.indexedProjects?.length" class="memory-turn-section__indexed">
            <span class="memory-turn-section__indexed-label">收录于：</span>
            <n-tag
              v-for="p in t.indexedProjects"
              :key="p.projectId"
              size="tiny"
              type="primary"
              :bordered="false"
            >{{ p.name }}</n-tag>
          </div>
        </div>
      </div>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NButton, NCard, NCheckbox, NEmpty, NInput, NRadioButton, NRadioGroup, NSpace, NTag, useDialog, useMessage } from 'naive-ui'
import { memoryApi, type MemoryTurnVO } from '@/api/memory'

const message = useMessage()
const dialog = useDialog()

const rows = ref<MemoryTurnVO[]>([])
const loading = ref(false)
const mode = ref<'all' | 'raw'>('all')
const keyword = ref('')
const checkedIds = ref<number[]>([])
const deleting = ref(false)

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (mode.value === 'raw') {
    return rows.value.filter(t => !t.genDone && (!kw || `${t.l1Summary || ''} ${t.rawContent || ''}`.toLowerCase().includes(kw)))
  }
  if (!kw) return rows.value
  return rows.value.filter(t => `${t.l1Summary || ''} ${t.l2Detail || ''} ${t.rawContent || ''}`.toLowerCase().includes(kw))
})

async function load() {
  loading.value = true
  try {
    const all = await memoryApi.listTurns()
    rows.value = all.data?.data ?? []
  } catch (e: any) {
    message.error(e?.message || '加载流水账失败')
  } finally {
    loading.value = false
  }
}

function toggle(id: number, on: boolean) {
  if (on) checkedIds.value = [...checkedIds.value, id]
  else checkedIds.value = checkedIds.value.filter(x => x !== id)
}

async function batchDelete() {
  if (!checkedIds.value.length) return
  dialog.warning({
    title: '确认删除？',
    content: `将删除所选 ${checkedIds.value.length} 条流水账（仅删本人 raw；已生成的总结不受影响）。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      deleting.value = true
      try {
        const res = await memoryApi.batchDeleteRawTurns(checkedIds.value)
        const n = res.data?.data ?? 0
        message.success(`已删除 ${n} 条`)
        checkedIds.value = []
        await load()
      } catch (e: any) {
        message.error(e?.message || '删除失败')
      } finally {
        deleting.value = false
      }
    }
  })
}

onMounted(load)
defineExpose({ refresh: load })
</script>

<style lang="scss" scoped>
.memory-turn-section {
  &__toolbar {
    margin-bottom: 12px;
    flex-wrap: wrap;
  }
  &__hint {
    font-size: 12px;
    opacity: 0.65;
  }
  &__row {
    display: flex;
    align-items: flex-start;
    gap: 10px;
  }
  &__body {
    flex: 1;
    min-width: 0;
  }
  &__head {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-bottom: 4px;
    flex-wrap: wrap;
  }
  &__time {
    font-size: 11px;
    opacity: 0.5;
    margin-left: auto;
  }
  &__l1 {
    font-size: 13px;
    line-height: 1.5;
  }
  &__l2 {
    font-size: 12px;
    opacity: 0.7;
    line-height: 1.5;
    margin-top: 2px;
  }
  &__raw {
    font-size: 12px;
    opacity: 0.55;
    line-height: 1.5;
    margin-top: 4px;
    white-space: pre-wrap;
    max-height: 6em;
    overflow: hidden;
  }
  &__tags {
    display: flex;
    gap: 4px;
    flex-wrap: wrap;
    margin-top: 6px;
  }
  &__indexed {
    display: flex;
    align-items: center;
    gap: 4px;
    flex-wrap: wrap;
    margin-top: 4px;
  }
  &__indexed-label {
    font-size: 11px;
    opacity: 0.55;
  }
}
</style>

<!-- ============================================================
  gen 开关矩阵（计划12 F-5）— 我所在项目 × owner/会员双开关 + 一键关
  · 走 memoryApi.getGenMatrix / putGenOwner / putGenMember
  · owner 开关仅 OWNER 可改（role≠OWNER 只读）；会员覆写本人可改
  · effective = owner AND member；任一关 → 仅写 raw 不调生成 LLM
  · 一键关 = 把所有本人会员覆写置 false（不碰 owner 开关）
  ============================================================ -->
<template>
  <div class="memory-gen-matrix">
    <n-alert type="info" :bordered="false" size="small" class="memory-gen-matrix__top">
      项目记忆生成开关：owner 项目级 与 本人会员覆写 皆开才生成 L0/L1/L2（任一关仅写 raw 流水账）。
      关掉 = 该项目不再为你提炼总结，原 raw 仍保留，<b>但 raw 不会自动总结</b>（自动总结只处理已生成的 turn）——
      想要总结请开启生成，或在「总结」面板点「立即总结」手动压。
    </n-alert>

    <n-space :size="8" align="center" class="memory-gen-matrix__toolbar">
      <n-button size="small" :loading="loading" @click="load">刷新</n-button>
      <n-button
        v-if="rows.some(r => r.memberEnabled)"
        size="small"
        type="warning"
        ghost
        :loading="bulkBusy"
        @click="turnAllOff"
      >
        一键关（我的全部会员覆写）
      </n-button>
      <span class="memory-gen-matrix__hint">{{ rows.length }} 个项目</span>
    </n-space>

    <n-empty v-if="!loading && !rows.length" size="small" description="暂无项目（加入项目后在此控制生成开关）" />

    <n-card v-for="r in rows" :key="r.projectId" size="small" :bordered="true" style="margin-bottom: 8px">
      <div class="memory-gen-matrix__row">
        <div class="memory-gen-matrix__name">
          {{ r.projectName }}
          <n-tag size="tiny" :bordered="false">{{ r.role }}</n-tag>
        </div>
        <div class="memory-gen-matrix__switches">
          <div class="memory-gen-matrix__switch">
            <span class="memory-gen-matrix__label">owner 开关</span>
            <n-switch
              :value="r.ownerEnabled"
              :disabled="r.role !== 'OWNER'"
              :loading="busyKey === 'owner:' + r.projectId"
              size="small"
              @update:value="(v) => toggleOwner(r, v)"
            />
            <span v-if="r.role !== 'OWNER'" class="memory-gen-matrix__lock">（仅 owner 可改）</span>
          </div>
          <div class="memory-gen-matrix__switch">
            <span class="memory-gen-matrix__label">我的覆写</span>
            <n-switch
              :value="r.memberEnabled"
              :loading="busyKey === 'member:' + r.projectId"
              size="small"
              @update:value="(v) => toggleMember(r, v)"
            />
          </div>
          <n-tag
            size="small"
            :type="r.effective ? 'success' : 'warning'"
            :bordered="false"
          >
            {{ r.effective ? '生效中' : '已关' }}
          </n-tag>
        </div>
      </div>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { NAlert, NButton, NCard, NEmpty, NSpace, NSwitch, NTag, useMessage } from 'naive-ui'
import { memoryApi, type MemoryGenMatrixItemVO } from '@/api/memory'

const message = useMessage()

const rows = ref<MemoryGenMatrixItemVO[]>([])
const loading = ref(false)
const busyKey = ref<string | null>(null)
const bulkBusy = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await memoryApi.getGenMatrix()
    rows.value = res.data?.data ?? []
  } catch (e: any) {
    message.error(e?.message || '加载矩阵失败')
  } finally {
    loading.value = false
  }
}

async function toggleOwner(r: MemoryGenMatrixItemVO, v: boolean) {
  if (r.role !== 'OWNER') return
  busyKey.value = 'owner:' + r.projectId
  try {
    await memoryApi.putGenOwner(r.projectId, v)
    r.ownerEnabled = v
    r.effective = r.ownerEnabled && r.memberEnabled
    message.success('已更新')
  } catch (e: any) {
    message.error(e?.message || '更新失败')
  } finally {
    busyKey.value = null
  }
}

async function toggleMember(r: MemoryGenMatrixItemVO, v: boolean) {
  busyKey.value = 'member:' + r.projectId
  try {
    await memoryApi.putGenMember(r.projectId, v)
    r.memberEnabled = v
    r.effective = r.ownerEnabled && r.memberEnabled
    message.success('已更新')
  } catch (e: any) {
    message.error(e?.message || '更新失败')
  } finally {
    busyKey.value = null
  }
}

async function turnAllOff() {
  bulkBusy.value = true
  let ok = 0
  try {
    for (const r of rows.value) {
      if (r.memberEnabled) {
        await memoryApi.putGenMember(r.projectId, false)
        r.memberEnabled = false
        r.effective = false
        ok++
      }
    }
    message.success(`已关闭 ${ok} 个项目的会员覆写`)
  } catch (e: any) {
    message.error(e?.message || '一键关失败')
    await load()
  } finally {
    bulkBusy.value = false
  }
}

onMounted(load)
defineExpose({ refresh: load })
</script>

<style lang="scss" scoped>
.memory-gen-matrix {
  &__top {
    margin-bottom: 12px;
  }
  &__toolbar {
    margin-bottom: 12px;
  }
  &__hint {
    font-size: 12px;
    opacity: 0.65;
  }
  &__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    flex-wrap: wrap;
  }
  &__name {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 13px;
    font-weight: 500;
  }
  &__switches {
    display: flex;
    align-items: center;
    gap: 16px;
    flex-wrap: wrap;
  }
  &__switch {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  &__label {
    font-size: 12px;
    opacity: 0.7;
  }
  &__lock {
    font-size: 11px;
    opacity: 0.5;
  }
}
</style>

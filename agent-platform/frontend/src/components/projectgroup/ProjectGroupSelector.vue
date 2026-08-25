<!-- ============================================================
  参与项目选择器（计划5 Step6 · 五入口共享）
  个人（默认）+ 我的组下拉；组选项带组池余额徽标与我的限额提示。
  value 语义：null=个人钱包计费；number=组池计费（非成员 403/组池尽 40201 文案由提交侧报错回显）。
  ============================================================ -->
<template>
  <n-select
    :value="modelValue ?? 0"
    :options="options"
    :disabled="disabled"
    :loading="loading"
    size="small"
    :consistent-menu-width="false"
    placeholder="参与项目"
    style="min-width: 150px"
    @update:value="onChange"
  />
</template>

<script setup lang="ts">
/**
 * 计划5 Step6：五入口（对话/知识库问答/生图/生视频/画布）共享的「参与项目」选择器。
 * 数据源 GET /project-groups/mine（我建的 OWNER + 我在的 MEMBER）；
 * 组件无持久化——各入口按自身口径存（localStorage / 画布快照顶层），只 v-model 读写。
 */
import { onMounted, ref } from 'vue'
import { NSelect, useMessage } from 'naive-ui'
import { projectGroupApi } from '@/api/projectGroup'

defineProps<{
  /** null=个人（默认）；number=组 id（组池计费） */
  modelValue: number | null
  disabled?: boolean
}>()

const emit = defineEmits<{ (e: 'update:modelValue', v: number | null): void }>()

const message = useMessage()
const loading = ref(false)

/** 余额展示：去掉尾零（后端 DECIMAL 序列化 1000 → 显示 1000）。 */
function fmt(n: number): string {
  return Number.isInteger(n) ? String(n) : String(n).replace(/\.?0+$/, '')
}

const options = ref<Array<{ label: string; value: number }>>([
  { label: '个人（默认）', value: 0 }
])

onMounted(async () => {
  loading.value = true
  try {
    const res = await projectGroupApi.mine()
    const groups = res.data.data
    options.value = [
      { label: '个人（默认）', value: 0 },
      ...groups.map(g => ({
        // 余额徽标进选项文案（下拉即见组池余量；我的限额余量对成员更关键；管理显可分配——17x V156）
        label: g.myRole === 'OWNER'
          ? `${g.name}（组长 · 余 ${fmt(g.balancePoints)} 分）`
          : g.myRole === 'MANAGER'
            ? `${g.name}（管理 · 可分配 ${g.myAllocatable === null ? '不限' : fmt(g.myAllocatable)}）`
            : `${g.name}（余 ${fmt(g.balancePoints)} 分${g.myQuota === null ? '' : ` · 限剩 ${fmt(Math.max(0, g.myQuota - g.myUsed))}`}）`,
        value: g.id
      }))
    ]
  } catch {
    // 静默降级为仅「个人」选项——选择器加载失败不阻塞入口（个人计费始终可用）
    message.error('加载我的项目组失败，当前按个人计费')
  } finally {
    loading.value = false
  }
})

function onChange(v: number | null) {
  // 0=个人占位值 → 对外统一 null（省略 gid 字段 = 后端个人钱包现状分支）
  emit('update:modelValue', v === 0 || v === null ? null : v)
}
</script>

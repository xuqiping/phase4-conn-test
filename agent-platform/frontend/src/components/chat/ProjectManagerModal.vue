<template>
  <n-modal :show="show" preset="card" title="项目管理" style="width: 560px" @update:show="v => emit('update:show', v)">
    <n-space vertical :size="16">
      <!-- 新建 -->
      <n-space align="center" :size="8">
        <n-input v-model:value="newName" placeholder="项目名称" style="width: 180px" />
        <n-input v-model:value="newDesc" placeholder="描述（可选）" style="width: 220px" />
        <n-button type="primary" :loading="creating" @click="createProject">新建</n-button>
      </n-space>

      <!-- 列表 -->
      <n-data-table
        :columns="columns"
        :data="projects"
        :loading="loading"
        size="small"
        :pagination="{ pageSize: 8 }"
      />
    </n-space>
  </n-modal>
</template>

<script setup lang="ts">
import { h, ref, watch } from 'vue'
import { NModal, NSpace, NInput, NButton, NDataTable, NTag, useMessage, useDialog } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { projectApi, type Project } from '@/api/project'

const props = defineProps<{ show: boolean }>()
const emit = defineEmits<{ 'update:show': [v: boolean]; changed: [] }>()

const message = useMessage()
const dialog = useDialog()

const projects = ref<Project[]>([])
const loading = ref(false)
const newName = ref('')
const newDesc = ref('')
const creating = ref(false)

const ROLE_LABEL: Record<string, string> = { OWNER: '拥有者', EDITOR: '编辑', VIEWER: '只读' }

const columns: DataTableColumns<Project> = [
  { title: '名称', key: 'name', width: 140, ellipsis: { tooltip: true } },
  { title: '描述', key: 'description', width: 160, ellipsis: { tooltip: true }, render: r => r.description || '-' },
  { title: '我的角色', key: 'myRole', width: 90, render: r => h(NTag, { size: 'small', bordered: false }, () => ROLE_LABEL[r.myRole || ''] || r.myRole || '-') },
  { title: '成员', key: 'memberCount', width: 60 },
  {
    title: '操作', key: 'actions', width: 90,
    render: r => r.myRole === 'OWNER'
      ? h(NButton, { size: 'small', quaternary: true, type: 'error', onClick: () => confirmDelete(r) }, () => '删除')
      : '-'
  }
]

async function load() {
  loading.value = true
  try {
    const res = await projectApi.list()
    projects.value = res.data.data || []
  } catch { message.error('加载项目失败') }
  finally { loading.value = false }
}

async function createProject() {
  if (!newName.value.trim()) { message.warning('请输入项目名称'); return }
  creating.value = true
  try {
    await projectApi.create({ name: newName.value.trim(), description: newDesc.value.trim() || undefined })
    newName.value = ''; newDesc.value = ''
    message.success('项目已创建')
    await load(); emit('changed')
  } catch { message.error('创建失败') }
  finally { creating.value = false }
}

function confirmDelete(p: Project) {
  dialog.warning({
    title: '删除项目', content: `删除项目「${p.name}」？项目内记忆的归属会移除（记忆本身保留）。`,
    positiveText: '删除', negativeText: '取消',
    onPositiveClick: async () => {
      try { await projectApi.delete(p.id); message.success('已删除'); await load(); emit('changed') }
      catch { message.error('删除失败') }
    }
  })
}

watch(() => props.show, v => { if (v) load() })
</script>

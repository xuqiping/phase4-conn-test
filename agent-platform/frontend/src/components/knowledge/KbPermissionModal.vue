<template>
  <n-modal
    :show="show"
    preset="card"
    title="知识库授权"
    style="max-width: 720px"
    @update:show="emit('update:show', $event)"
  >
    <div class="kb-perm">
      <!-- 授权表单 -->
      <div class="kb-perm__form">
        <n-select
          v-model:value="grant.subjectType"
          :options="subjectTypeOptions"
          style="width: 140px"
        />
        <n-select
          v-if="grant.subjectType === 'USER'"
          v-model:value="grant.subjectId"
          :options="userOptions"
          :loading="loadingUsers"
          filterable
          placeholder="选择用户"
          style="flex:1"
        />
        <n-select
          v-else-if="grant.subjectType === 'ROLE'"
          v-model:value="grant.subjectId"
          :options="roleOptions"
          :loading="loadingRoles"
          filterable
          placeholder="选择角色"
          style="flex:1"
        />
        <n-input-number
          v-else
          v-model:value="grant.subjectId"
          placeholder="输入部门 ID"
          style="flex:1"
        />
        <n-checkbox v-model:checked="grant.canRead">读</n-checkbox>
        <n-checkbox v-model:checked="grant.canWrite">写</n-checkbox>
        <n-checkbox v-model:checked="grant.canManage">管理</n-checkbox>
        <n-button type="primary" :loading="granting" :disabled="!grant.subjectId" @click="doGrant">授权</n-button>
      </div>

      <!-- 已授权列表 -->
      <n-data-table
        :columns="columns"
        :data="rows"
        :loading="loadingPerms"
        :pagination="false"
        size="small"
      />
      <n-empty v-if="!loadingPerms && rows.length === 0" description="暂无授权" />
    </div>

    <template #action>
      <n-space justify="end">
        <n-button @click="emit('update:show', false)">关闭</n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, h, ref, watch } from 'vue'
import {
  NButton, NCheckbox, NDataTable, NEmpty, NInputNumber, NModal, NSelect, NSpace, NTag, useMessage
} from 'naive-ui'
import type { DataTableColumns, SelectOption } from 'naive-ui'
import { knowledgeApi, type KnowledgePermission } from '@/api/knowledge'
import { adminApi, type UserVO, type Role } from '@/api/admin'

const props = defineProps<{
  show: boolean
  kbId: number
}>()

const emit = defineEmits<{
  (event: 'update:show', value: boolean): void
}>()

const message = useMessage()
const rows = ref<KnowledgePermission[]>([])
const loadingPerms = ref(false)
const granting = ref(false)

const users = ref<UserVO[]>([])
const roles = ref<Role[]>([])
const loadingUsers = ref(false)
const loadingRoles = ref(false)

const subjectTypeOptions: SelectOption[] = [
  { label: '用户', value: 'USER' },
  { label: '角色', value: 'ROLE' },
  { label: '部门', value: 'DEPARTMENT' }
]

const grant = ref({
  subjectType: 'USER',
  subjectId: null as number | null,
  canRead: true,
  canWrite: false,
  canManage: false
})

const userOptions = computed<SelectOption[]>(() =>
  users.value.map(u => ({ label: u.username, value: u.id }))
)
const roleOptions = computed<SelectOption[]>(() =>
  roles.value.map(r => ({ label: `${r.name} (${r.code})`, value: r.id }))
)

const subjectTypeTag: Record<string, string> = {
  USER: '用户', ROLE: '角色', DEPARTMENT: '部门', SERVICE_ACCOUNT: '服务账号'
}

const columns: DataTableColumns<KnowledgePermission> = [
  {
    title: '主体', key: 'subjectName',
    render: row => h(NSpace, { size: 4, align: 'center' }, () => [
      h(NTag, { size: 'small', bordered: false }, () => subjectTypeTag[row.subjectType] || row.subjectType),
      h('span', null, row.subjectName || `#${row.subjectId}`)
    ])
  },
  {
    title: '权限', key: 'perms',
    render: row => h(NSpace, { size: 4 }, () => [
      row.canRead && h(NTag, { size: 'small', type: 'info', bordered: false }, () => '读'),
      row.canWrite && h(NTag, { size: 'small', type: 'success', bordered: false }, () => '写'),
      row.canManage && h(NTag, { size: 'small', type: 'warning', bordered: false }, () => '管理')
    ].filter(Boolean))
  },
  {
    title: '操作', key: 'actions', width: 90,
    render: row => h(NButton, {
      size: 'small', quaternary: true, type: 'error',
      onClick: () => doRevoke(row)
    }, () => '撤销')
  }
]

watch(() => props.show, (show) => {
  if (show) {
    void loadPerms()
    void loadUsers()
    void loadRoles()
    grant.value = { subjectType: 'USER', subjectId: null, canRead: true, canWrite: false, canManage: false }
  }
}, { immediate: true })

async function loadPerms() {
  loadingPerms.value = true
  try {
    const res = await knowledgeApi.listPermissions('KB', props.kbId)
    rows.value = res.data.data || []
  } catch {
    message.error('加载授权列表失败')
  } finally {
    loadingPerms.value = false
  }
}

async function loadUsers() {
  loadingUsers.value = true
  try {
    const res = await adminApi.listUsers(1, 200)
    users.value = res.data.data.records || []
  } catch {
    /* 非管理员可能无权限，静默 */
  } finally {
    loadingUsers.value = false
  }
}

async function loadRoles() {
  loadingRoles.value = true
  try {
    const res = await adminApi.listAllRoles()
    roles.value = res.data.data || []
  } catch {
    /* 静默 */
  } finally {
    loadingRoles.value = false
  }
}

async function doGrant() {
  if (!grant.value.subjectId) return
  granting.value = true
  try {
    await knowledgeApi.grantPermission({
      targetType: 'KB',
      targetId: props.kbId,
      subjectType: grant.value.subjectType,
      subjectId: grant.value.subjectId,
      canRead: grant.value.canRead,
      canWrite: grant.value.canWrite,
      canManage: grant.value.canManage
    })
    message.success('授权成功')
    grant.value.subjectId = null
    await loadPerms()
  } catch {
    message.error('授权失败')
  } finally {
    granting.value = false
  }
}

async function doRevoke(row: KnowledgePermission) {
  try {
    await knowledgeApi.revokePermission(row.id)
    message.success('已撤销')
    await loadPerms()
  } catch {
    message.error('撤销失败')
  }
}
</script>

<style lang="scss" scoped>
.kb-perm {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-3);
}
.kb-perm__form {
  display: flex;
  gap: var(--spacing-2);
  align-items: center;
  flex-wrap: wrap;
}
</style>

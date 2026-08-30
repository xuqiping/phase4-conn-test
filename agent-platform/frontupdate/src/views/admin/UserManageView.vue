<template>
  <div class="user-manage">
    <!-- 雾中浮岛场景层（ART-DIR-0002R 方向二 admin 淡版，仅 ink 主题渲染） -->
    <ModuleScene scene="admin" lite />
    <PageHeader title="用户管理" />

    <!-- D1（12x-1）：搜索框（username/name/remark 三字段模糊）+ 状态筛选 -->
    <div class="user-manage__toolbar">
      <n-input
        v-model:value="keyword"
        clearable
        placeholder="搜索用户名 / 姓名 / 备注（如：A 班）"
        style="width: 260px"
        @keyup.enter="search"
        @clear="search"
      />
      <n-select
        v-model:value="statusFilter"
        :options="statusOptions"
        clearable
        placeholder="状态"
        style="width: 130px"
        @update:value="search"
      />
      <n-button type="primary" secondary @click="search">搜索</n-button>
    </div>

    <!-- 用户表格（remote=服务端分页：缺它会客户端再切一刀，第2页起永远空——16x id 1-100 老用户翻不到） -->
    <n-data-table
      remote
      :columns="columns"
      :data="users"
      :loading="loading"
      :pagination="pagination"
      :scroll-x="1200"
      @update:page="loadUsers"
      striped
    />

    <!-- 角色分配弹窗 -->
    <n-modal v-model:show="showRoleModal" preset="card" title="分配角色" style="max-width:440px">
      <p style="margin-bottom:12px;color:var(--color-text-secondary)">
        用户：<strong>{{ editingUser?.username }}</strong>
      </p>
      <n-checkbox-group v-model:value="selectedRoleIds">
        <n-space vertical>
          <n-checkbox v-for="role in allRoles" :key="role.id" :value="role.id" :label="`${role.name} (${role.code})`" />
        </n-space>
      </n-checkbox-group>
      <template #action>
        <n-button @click="showRoleModal = false">取消</n-button>
        <n-button type="primary" :loading="saving" @click="saveRoles">保存</n-button>
      </template>
    </n-modal>
    <!-- 状态变更弹窗（11x 加固：封号/禁用/锁定须填原因，即时踢下线） -->
    <n-modal v-model:show="showStatusModal" preset="card" title="变更账号状态" style="max-width:440px">
      <p style="margin-bottom:12px;color:var(--color-text-secondary)">
        用户：<strong>{{ statusTarget?.username }}</strong>
      </p>
      <n-alert type="warning" :bordered="false" style="margin-bottom:12px">
        变更后该用户将立即被踢下线（当前登录会话即刻失效），解封后须重新登录。
      </n-alert>
      <n-radio-group v-model:value="statusChoice" style="margin-bottom:12px">
        <n-space vertical>
          <n-radio value="DISABLED">禁用（临时停用，可恢复）</n-radio>
          <n-radio value="LOCKED">锁定（安全锁定，可恢复）</n-radio>
          <n-radio value="BANNED">封号（违规封禁，可恢复）</n-radio>
        </n-space>
      </n-radio-group>
      <n-input
        v-model:value="statusReason"
        type="textarea"
        placeholder="原因（必填，≤128 字符，管理端可见）"
        maxlength="128"
        show-count
        :autosize="{ minRows: 2, maxRows: 4 }"
      />
      <template #action>
        <n-button @click="showStatusModal = false">取消</n-button>
        <n-button type="error" :loading="saving" :disabled="!statusReason.trim()" @click="confirmStatusChange">确认变更</n-button>
      </template>
    </n-modal>
    <!-- D1：管理员改备注（Q6=A 本人+管理员可改；空=清除） -->
    <n-modal v-model:show="showRemarkModal" preset="card" title="修改备注" style="max-width:440px">
      <p style="margin-bottom:12px;color:var(--color-text-secondary)">
        用户：<strong>{{ remarkTarget?.name || remarkTarget?.username }}</strong>
      </p>
      <n-input
        v-model:value="remarkInput"
        maxlength="128"
        show-count
        clearable
        placeholder="如：A 班（留空=清除备注）"
      />
      <template #action>
        <n-button @click="showRemarkModal = false">取消</n-button>
        <n-button type="primary" :loading="saving" @click="saveRemark">保存</n-button>
      </template>
    </n-modal>
    <!-- 修复III E2（12x#3）：管理员改姓名（空=清除，≤64 字；全站 displayName 优先 name） -->
    <n-modal v-model:show="showNameModal" preset="card" title="修改姓名" style="max-width:440px">
      <p style="margin-bottom:12px;color:var(--color-text-secondary)">
        用户：<strong>{{ nameTarget?.username }}</strong>
      </p>
      <n-input
        v-model:value="nameInput"
        maxlength="64"
        show-count
        clearable
        placeholder="真实姓名（留空=清除，显示回用户名）"
      />
      <template #action>
        <n-button @click="showNameModal = false">取消</n-button>
        <n-button type="primary" :loading="saving" @click="saveName">保存</n-button>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, h } from 'vue'
import {
  NDataTable, NButton, NModal, NCheckboxGroup, NCheckbox, NSpace, NTag, NAlert,
  NRadioGroup, NRadio, NInput, NSelect, useMessage, useDialog
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { adminApi, type UserVO, type Role } from '@/api/admin'
import PageHeader from '@/components/PageHeader.vue'
import ModuleScene from '@/components/ModuleScene.vue'

const message = useMessage()
const loading = ref(false)
const saving = ref(false)
const users = ref<UserVO[]>([])
const allRoles = ref<Role[]>([])
const showRoleModal = ref(false)
const editingUser = ref<UserVO | null>(null)
const selectedRoleIds = ref<number[]>([])

// 11x 加固：状态变更弹窗（原因必填）
const showStatusModal = ref(false)
const statusTarget = ref<UserVO | null>(null)
const statusChoice = ref<'DISABLED' | 'LOCKED' | 'BANNED'>('DISABLED')
const statusReason = ref('')

const pagination = reactive({ page: 1, pageSize: 10, itemCount: 0 })

// D1：keyword（username/name/remark 三字段模糊）+ 状态筛选
const keyword = ref('')
const statusFilter = ref<string | null>(null)
const statusOptions = [
  { label: '正常', value: 'ACTIVE' },
  { label: '禁用', value: 'DISABLED' },
  { label: '锁定', value: 'LOCKED' },
  { label: '封号', value: 'BANNED' }
]

// D1：管理员改备注弹窗
const showRemarkModal = ref(false)
const remarkTarget = ref<UserVO | null>(null)
const remarkInput = ref('')

// 修复III E2（12x#3）：管理员改姓名弹窗（同备注交互：空=清除，≤64 字）
const showNameModal = ref(false)
const nameTarget = ref<UserVO | null>(null)
const nameInput = ref('')

const dialog = useDialog()

const statusMap: Record<string, { type: 'success' | 'warning' | 'error'; label: string }> = {
  ACTIVE: { type: 'success', label: '正常' },
  DISABLED: { type: 'warning', label: '禁用' },
  LOCKED: { type: 'error', label: '锁定' },
  BANNED: { type: 'error', label: '封号' }
}

const columns: DataTableColumns<UserVO> = [
  { title: 'ID', key: 'id', width: 60 },
  { title: '用户名', key: 'username', width: 120 },
  {
    title: '姓名', key: 'name', width: 120, ellipsis: { tooltip: true },
    render: (row) => row.name || '-'
  },
  { title: '部门', key: 'primaryDepartmentName', width: 140, ellipsis: { tooltip: true },
    render: (row) => row.primaryDepartmentName || '-' },
  { title: '备注', key: 'remark', width: 120, ellipsis: { tooltip: true },
    render: (row) => row.remark || '-' },
  { title: '邮箱', key: 'email', width: 180, ellipsis: { tooltip: true } },
  {
    title: '状态', key: 'status', width: 80,
    render: (row) => h(NTag, { size: 'small', type: statusMap[row.status]?.type || 'default', round: true }, () => statusMap[row.status]?.label || row.status)
  },
  {
    title: '原因', key: 'banReason', width: 140, ellipsis: { tooltip: true },
    render: (row) => row.status === 'ACTIVE' ? '-' : (row.banReason || '-')
  },
  {
    title: '角色', key: 'roles', width: 200,
    render: (row) => h(NSpace, { size: 4 }, () => row.roles.map(r => h(NTag, { size: 'small', bordered: false }, () => r)))
  },
  {
    title: '最后登录', key: 'lastLoginAt', width: 160,
    render: (row) => row.lastLoginAt ? new Date(row.lastLoginAt).toLocaleString('zh-CN') : '-'
  },
  {
    title: '操作', key: 'actions', width: 260, fixed: 'right',
    render: (row) => {
      // 修复III E1（12x#2）：暴破自动锁（LOCKED+lockedUntil）显「解锁」——提前解除自动锁，
      // 与封禁/禁用/手动锁的「启用」语义分离（解锁不动封禁语义）
      const autoLocked = row.status === 'LOCKED' && !!row.lockedUntil
      return h(NSpace, { size: 8 }, () => [
        h(NButton, { size: 'small', onClick: () => openRoleModal(row) }, () => '分配角色'),
        h(NButton, { size: 'small', onClick: () => openNameModal(row) }, () => '姓名'),
        h(NButton, { size: 'small', onClick: () => openRemarkModal(row) }, () => '备注'),
        ...(row.status === 'ACTIVE'
          ? [h(NButton, { size: 'small', type: 'warning', onClick: () => openStatusModal(row) }, () => '变更状态')]
          : []),
        ...(autoLocked
          ? [h(NButton, { size: 'small', type: 'success', onClick: () => confirmUnlock(row) }, () => '解锁')]
          : []),
        ...(row.status !== 'ACTIVE' && !autoLocked
          ? [h(NButton, { size: 'small', type: 'success', onClick: () => toggleStatus(row, 'ACTIVE') }, () => '启用')]
          : [])
      ])
    }
  }
]

async function loadUsers(page = 1) {
  loading.value = true
  try {
    const res = await adminApi.listUsers(page, pagination.pageSize, keyword.value.trim() || undefined, statusFilter.value || undefined)
    users.value = res.data.data.records
    pagination.itemCount = res.data.data.total
    pagination.page = page
  } catch {
    message.error('加载用户列表失败')
  } finally {
    loading.value = false
  }
}

/** D1：搜索（回筛选第 1 页；清空 keyword/状态=取消筛选） */
function search() {
  loadUsers(1)
}

function openRemarkModal(user: UserVO) {
  remarkTarget.value = user
  remarkInput.value = user.remark ?? ''
  showRemarkModal.value = true
}

async function saveRemark() {
  if (!remarkTarget.value) return
  saving.value = true
  try {
    const trimmed = remarkInput.value.trim()
    await adminApi.updateUserRemark(remarkTarget.value.id, trimmed === '' ? null : trimmed)
    message.success('备注已保存')
    showRemarkModal.value = false
    await loadUsers(pagination.page)
  } catch (e: any) {
    message.error(e?.response?.data?.msg || '备注保存失败')
  } finally {
    saving.value = false
  }
}

async function loadRoles() {
  try {
    const res = await adminApi.listAllRoles()
    allRoles.value = res.data.data
  } catch {
    message.error('加载角色列表失败')
  }
}

/** 修复III E1（12x#2）：解锁确认弹窗——显锁定到期时间，确认调 unlock 端点。 */
function confirmUnlock(user: UserVO) {
  const until = user.lockedUntil ? new Date(user.lockedUntil).toLocaleString('zh-CN') : ''
  dialog.warning({
    title: '提前解锁账号',
    content: `用户 ${user.username} 因连续登录失败被自动锁定${until ? `，将于 ${until} 自动解锁` : ''}。确认立即解锁？`,
    positiveText: '解锁',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await adminApi.unlockUser(user.id)
        message.success('已解锁，该用户可立即登录')
        await loadUsers(pagination.page)
      } catch (e: any) {
        message.error(e?.response?.data?.message || '解锁失败')
      }
    }
  })
}

/** 修复III E2（12x#3）：改姓名弹窗（同备注交互） */
function openNameModal(user: UserVO) {
  nameTarget.value = user
  nameInput.value = user.name ?? ''
  showNameModal.value = true
}

async function saveName() {
  if (!nameTarget.value) return
  saving.value = true
  try {
    const trimmed = nameInput.value.trim()
    await adminApi.updateUserName(nameTarget.value.id, trimmed === '' ? null : trimmed)
    message.success('姓名已保存')
    showNameModal.value = false
    await loadUsers(pagination.page)
  } catch (e: any) {
    message.error(e?.response?.data?.message || '姓名保存失败')
  } finally {
    saving.value = false
  }
}

function openRoleModal(user: UserVO) {
  editingUser.value = user
  // 把当前角色code转为id
  selectedRoleIds.value = allRoles.value
    .filter(r => user.roles.includes(r.code))
    .map(r => r.id)
  showRoleModal.value = true
}

async function saveRoles() {
  if (!editingUser.value) return
  saving.value = true
  try {
    await adminApi.assignRoles(editingUser.value.id, selectedRoleIds.value)
    message.success('角色分配成功')
    showRoleModal.value = false
    await loadUsers(pagination.page)
  } catch {
    message.error('角色分配失败')
  } finally {
    saving.value = false
  }
}

function openStatusModal(user: UserVO) {
  statusTarget.value = user
  statusChoice.value = 'DISABLED'
  statusReason.value = ''
  showStatusModal.value = true
}

async function confirmStatusChange() {
  if (!statusTarget.value) return
  saving.value = true
  try {
    await adminApi.updateUserStatus(statusTarget.value.id, statusChoice.value, statusReason.value.trim())
    message.success('状态已变更，该用户已被踢下线')
    showStatusModal.value = false
    await loadUsers(pagination.page)
  } catch (e: any) {
    message.error(e?.response?.data?.msg || '状态更新失败')
  } finally {
    saving.value = false
  }
}

async function toggleStatus(user: UserVO, status: string) {
  try {
    await adminApi.updateUserStatus(user.id, status)
    message.success('状态更新成功')
    await loadUsers(pagination.page)
  } catch (e: any) {
    message.error(e?.response?.data?.msg || '状态更新失败')
  }
}

onMounted(() => {
  loadUsers()
  loadRoles()
})
</script>

<style lang="scss" scoped>
.user-manage {
  padding: var(--spacing-6);

  &__toolbar {
    display: flex;
    gap: 8px;
    margin-bottom: var(--spacing-3);
  }
}

@media (max-width: 768px) {
  .user-manage {
    padding: var(--spacing-3);
  }
}
</style>

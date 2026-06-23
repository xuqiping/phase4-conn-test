<template>
  <div class="agent-detail">
    <!-- 面包屑 -->
    <div class="agent-detail__breadcrumb">
      <router-link to="/agents" class="agent-detail__breadcrumb-link">
        Agent大厅
      </router-link>
      <n-icon size="14" :component="ChevronForwardOutline" />
      <span class="agent-detail__breadcrumb-current">{{ agentDetail?.name || '加载中...' }}</span>
    </div>

    <!-- 加载状态 -->
    <div v-if="loading" class="agent-detail__loading">
      <n-spin size="large" />
    </div>

    <template v-else-if="agentDetail">
      <!-- Agent头部区域 -->
      <div class="agent-detail__hero">
        <div class="agent-detail__hero-gradient" />
        <div class="agent-detail__hero-content">
          <div class="agent-detail__hero-avatar">
            <img
              v-if="agentDetail.avatar"
              :src="agentDetail.avatar"
              :alt="agentDetail.name"
              class="agent-detail__hero-avatar-img"
            />
            <span v-else class="agent-detail__hero-avatar-placeholder">
              {{ agentDetail.name.charAt(0).toUpperCase() }}
            </span>
          </div>
          <div class="agent-detail__hero-info">
            <div class="agent-detail__hero-name-row">
              <h1 class="agent-detail__hero-name">{{ agentDetail.name }}</h1>
              <n-tag :type="statusTagType" size="small" round>{{ statusLabel }}</n-tag>
              <div style="flex:1" />
              <template v-if="canManage">
                <n-button size="small" @click="showPermissionModal = true">授权</n-button>
                <n-button size="small" @click="showEditModal = true">编辑</n-button>
                <n-button
                  v-if="agentDetail.status === 'DRAFT'"
                  size="small"
                  type="success"
                  @click="publishAgent"
                >发布</n-button>
                <n-button
                  v-if="agentDetail.status === 'PUBLISHED'"
                  size="small"
                  type="warning"
                  @click="offlineAgent"
                >下线</n-button>
                <n-button size="small" type="error" @click="confirmDelete">删除</n-button>
              </template>
              <n-button v-else-if="canCopy" size="small" @click="showEditModal = true">复制编辑</n-button>
            </div>
            <p class="agent-detail__hero-desc">{{ agentDetail.description || '暂无描述' }}</p>
            <div class="agent-detail__hero-meta">
              <span v-if="agentDetail.groupName" class="agent-detail__hero-tag">
                <n-icon size="12" :component="FolderOutline" />
                {{ agentDetail.groupName }}
              </span>
              <span class="agent-detail__hero-tag">
                <n-icon size="12" :component="FlashOutline" />
                {{ agentDetail.skills.length }} 个技能
              </span>
              <span
                class="agent-detail__hero-tag agent-detail__rag-toggle"
                title="开启后该 Agent 会话启用 RAG 证据 + 用户记忆（覆盖全局）"
              >
                记忆模式
                <n-switch
                  :value="agentRagEnabled"
                  size="small"
                  :disabled="!canManage"
                  @update:value="onAgentRagToggle"
                />
              </span>
            </div>
          </div>
        </div>
      </div>

      <!-- 左右分栏布局 -->
      <div class="agent-detail__body">
        <!-- 左侧：技能列表 -->
        <div class="agent-detail__skill-list">
          <div class="agent-detail__skill-list-header">
            <n-button
              v-if="canManageSkills"
              size="small"
              type="primary"
              @click="openCreateSkill"
            >
              新增能力
            </n-button>
            <h3>技能列表</h3>
          </div>
          <SkillList
            :skills="agentDetail.skills"
            :selected-skill-id="selectedSkillId"
            @select="onSkillSelect"
          />
        </div>

        <!-- 右侧：技能详情 -->
        <div class="agent-detail__skill-detail">
          <div v-if="skillLoading" class="agent-detail__skill-loading">
            <n-spin size="medium" />
          </div>
          <template v-else>
            <div v-if="canManageSkills && selectedSkill" class="agent-detail__skill-actions">
              <n-button size="small" @click="openEditSkill">编辑能力</n-button>
              <n-button size="small" type="error" @click="confirmDeleteSkill">删除能力</n-button>
            </div>
            <SkillDetail :skill="selectedSkill" />
          </template>
        </div>
      </div>
    </template>

    <!-- Agent不存在 -->
    <div v-else class="agent-detail__not-found">
      <n-empty description="Agent不存在或已被删除">
        <template #extra>
          <n-button @click="$router.push('/agents')">返回Agent大厅</n-button>
        </template>
      </n-empty>
    </div>

    <!-- 编辑弹窗 -->
    <AgentFormModal
      v-model:show="showEditModal"
      :groups="groups"
      :edit-data="editData"
      :save-mode="agentFormSaveMode"
      @updated="async () => { showEditModal = false; await loadAgent() }"
      @copied="onAgentCopied"
    />
    <SkillFormModal
      v-if="agentDetail"
      v-model:show="showSkillModal"
      :agent-id="agentDetail.id"
      :edit-data="editingSkill"
      @saved="onSkillSaved"
    />
    <AgentPermissionModal
      v-if="agentDetail"
      v-model:show="showPermissionModal"
      :agent-id="agentDetail.id"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NIcon, NSpin, NEmpty, NButton, NTag, NSwitch, useMessage, useDialog } from 'naive-ui'
import {
  ChevronForwardOutline,
  FolderOutline,
  FlashOutline
} from '@vicons/ionicons5'
import { agentApi, type AgentGroup } from '@/api/agent'
import type { AgentAccess, AgentDetail, SkillDetail as SkillDetailType } from '@/api/agent'
import { useAuthStore } from '@/stores/auth'
import SkillList from '@/components/SkillList.vue'
import SkillDetail from '@/components/SkillDetail.vue'
import AgentFormModal from '@/components/AgentFormModal.vue'
import SkillFormModal from '@/components/SkillFormModal.vue'
import AgentPermissionModal from '@/components/AgentPermissionModal.vue'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const message = useMessage()
const dialog = useDialog()

const agentDetail = ref<AgentDetail | null>(null)
const agentAccess = ref<AgentAccess | null>(null)
const groups = ref<AgentGroup[]>([])
const selectedSkill = ref<SkillDetailType | null>(null)
const selectedSkillId = ref<number | null>(null)
const loading = ref(true)
const skillLoading = ref(false)
const showEditModal = ref(false)
const showSkillModal = ref(false)
const showPermissionModal = ref(false)
const editingSkill = ref<SkillDetailType | null>(null)

const canManage = computed(() => agentAccess.value?.canManage === true)
const canCopy = computed(() => agentAccess.value?.canCopy === true)
const canManageSkills = computed(() => canManage.value && authStore.hasPermission('skill:manage'))
/** Agent 记忆模式：ragEnabled 存在 Agent.config JSONB，前端解析 config 取值（null=继承→显 off） */
const agentRagEnabled = computed(() => parseAgentConfig(agentDetail.value?.config).ragEnabled === true)
const agentFormSaveMode = computed(() => canManage.value ? 'update' : 'copy')

const statusMap: Record<string, { type: 'success' | 'warning' | 'default' | 'error'; label: string }> = {
  DRAFT: { type: 'default', label: '草稿' },
  PUBLISHED: { type: 'success', label: '已发布' },
  OFFLINE: { type: 'warning', label: '已下线' }
}
const statusTagType = computed(() => statusMap[agentDetail.value?.status || '']?.type || 'default')
const statusLabel = computed(() => statusMap[agentDetail.value?.status || '']?.label || agentDetail.value?.status)

const editData = computed(() => agentDetail.value ? {
  id: agentDetail.value.id,
  name: agentDetail.value.name,
  description: agentDetail.value.description,
  avatar: agentDetail.value.avatar,
  groupId: agentDetail.value.groupId
} : null)

async function loadGroups() {
  try {
    const res = await agentApi.getGroups()
    groups.value = res.data.data || []
  } catch { /* ignore */ }
}

function confirmDelete() {
  if (!agentDetail.value) return
  dialog.warning({
    title: '确认删除',
    content: `确定要删除Agent「${agentDetail.value.name}」吗？`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await agentApi.deleteAgent(agentDetail.value!.id)
        message.success('删除成功')
        router.push('/agents')
      } catch { message.error('删除失败') }
    }
  })
}

async function publishAgent() {
  if (!agentDetail.value) return
  try {
    await agentApi.updateAgentStatus(agentDetail.value.id, 'PUBLISHED')
    message.success('发布成功')
    await loadAgent()
  } catch { message.error('发布失败') }
}

/** 记忆模式开关：乐观更新本地 config + 调 rag-enabled 端点，失败回滚 */
async function onAgentRagToggle(val: boolean) {
  if (!agentDetail.value || !canManage.value) return
  const prevConfig = agentDetail.value.config
  const cfg = parseAgentConfig(prevConfig)
  cfg.ragEnabled = val
  agentDetail.value.config = JSON.stringify(cfg)
  try {
    await agentApi.setRagEnabled(agentDetail.value.id, val)
    message.success(val ? '已开启 Agent 记忆模式' : '已关闭 Agent 记忆模式')
  } catch {
    agentDetail.value.config = prevConfig
    message.error('设置失败')
  }
}

/** 解析 Agent.config JSONB（容错：null/非法→空对象） */
function parseAgentConfig(config: string | null | undefined): Record<string, unknown> {
  try {
    const o = JSON.parse(config || '{}')
    return o && typeof o === 'object' ? o as Record<string, unknown> : {}
  } catch {
    return {}
  }
}

async function offlineAgent() {
  if (!agentDetail.value) return
  try {
    await agentApi.updateAgentStatus(agentDetail.value.id, 'OFFLINE')
    message.success('已下线')
    await loadAgent()
  } catch { message.error('操作失败') }
}

async function onAgentCopied() {
  showEditModal.value = false
  message.success('已复制为你的 Agent')
  router.push('/agents')
}

async function loadAgent() {
  const agentId = Number(route.params.id)
  if (!agentId) { loading.value = false; return }
  try {
    const [detailRes, accessRes] = await Promise.all([
      agentApi.getAgentDetail(agentId),
      agentApi.getAgentAccess(agentId)
    ])
    agentDetail.value = detailRes.data.data
    agentAccess.value = accessRes.data.data
    if (agentDetail.value?.skills?.length > 0) {
      const nextSkillId = selectedSkillId.value &&
        agentDetail.value.skills.some(skill => skill.id === selectedSkillId.value)
        ? selectedSkillId.value
        : agentDetail.value.skills[0].id
      await onSkillSelect(nextSkillId)
    } else {
      selectedSkillId.value = null
      selectedSkill.value = null
    }
  } catch (e) {
    console.error('加载Agent详情失败:', e)
  } finally {
    loading.value = false
  }
}

function openCreateSkill() {
  editingSkill.value = null
  showSkillModal.value = true
}

function openEditSkill() {
  if (!selectedSkill.value) return
  editingSkill.value = selectedSkill.value
  showSkillModal.value = true
}

async function onSkillSaved(skill: SkillDetailType) {
  showSkillModal.value = false
  selectedSkillId.value = skill.id
  await loadAgent()
}

function confirmDeleteSkill() {
  if (!selectedSkill.value) return
  const skill = selectedSkill.value
  dialog.warning({
    title: '确认删除能力',
    content: `确定删除能力「${skill.name}」吗？`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await agentApi.deleteSkill(skill.id)
        message.success('能力删除成功')
        selectedSkillId.value = null
        selectedSkill.value = null
        await loadAgent()
      } catch {
        message.error('能力删除失败')
      }
    }
  })
}

async function onSkillSelect(skillId: number) {
  selectedSkillId.value = skillId
  skillLoading.value = true
  try {
    const res = await agentApi.getSkillDetail(skillId)
    selectedSkill.value = res.data.data
  } catch (e) {
    console.error('加载技能详情失败:', e)
    selectedSkill.value = null
  } finally {
    skillLoading.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadAgent(), loadGroups()])
})
</script>

<style lang="scss" scoped>
.agent-detail {
  padding: var(--spacing-6);
  min-height: 100%;
}

// 面包屑
.agent-detail__breadcrumb {
  display: flex;
  align-items: center;
  gap: var(--spacing-1);
  font-size: var(--font-size-sm);
  margin-bottom: var(--spacing-4);
}

.agent-detail__breadcrumb-link {
  color: var(--color-text-tertiary);
  text-decoration: none;
  transition: color var(--duration-instant) var(--ease-in-out);

  &:hover {
    color: var(--color-primary);
  }
}

.agent-detail__breadcrumb-current {
  color: var(--color-text-secondary);
}

// 加载状态
.agent-detail__loading {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 400px;
}

// Agent头部区域
.agent-detail__hero {
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  margin-bottom: var(--spacing-4);
}

.agent-detail__hero-gradient {
  height: 4px;
  background: linear-gradient(
    90deg,
    var(--color-gradient-start),
    var(--color-gradient-end)
  );
}

.agent-detail__hero-content {
  padding: var(--spacing-5);
  display: flex;
  gap: var(--spacing-5);
}

.agent-detail__hero-avatar {
  width: 64px;
  height: 64px;
  border-radius: var(--radius-lg);
  overflow: hidden;
  flex-shrink: 0;
}

.agent-detail__hero-avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.agent-detail__hero-avatar-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  background: linear-gradient(
    135deg,
    var(--color-gradient-start),
    var(--color-gradient-end)
  );
  color: #fff;
  font-size: var(--font-size-3xl);
  font-weight: var(--font-weight-bold);
}

.agent-detail__hero-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-2);
}

.agent-detail__hero-name-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
}

.agent-detail__hero-name {
  font-size: var(--font-size-2xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin: 0;
}

.agent-detail__hero-desc {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-base);
  margin: 0;
}

.agent-detail__hero-meta {
  display: flex;
  align-items: center;
  gap: var(--spacing-3);
  margin-top: var(--spacing-1);
}

.agent-detail__hero-tag {
  display: flex;
  align-items: center;
  gap: var(--spacing-1);
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  background: var(--color-elevated);
  padding: 2px 8px;
  border-radius: var(--radius-full);
}

// 左右分栏
.agent-detail__body {
  display: flex;
  gap: var(--spacing-4);
  min-height: 400px;
}

.agent-detail__skill-list {
  width: 300px;
  flex-shrink: 0;
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.agent-detail__skill-list-header {
  padding: var(--spacing-4);
  border-bottom: 1px solid var(--color-border-light);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-2);

  h3 {
    margin: 0;
    font-size: var(--font-size-base);
    font-weight: var(--font-weight-semibold);
    color: var(--color-text-primary);
  }
}

.agent-detail__skill-detail {
  flex: 1;
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  overflow: auto;
}

.agent-detail__skill-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--spacing-2);
  padding: var(--spacing-4) var(--spacing-4) 0;
}

.agent-detail__skill-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 300px;
}

// Agent不存在
.agent-detail__not-found {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 400px;
}

// 响应式
@media (max-width: 768px) {
  .agent-detail__body {
    flex-direction: column;
  }

  .agent-detail__skill-list {
    width: 100%;
  }
}
</style>

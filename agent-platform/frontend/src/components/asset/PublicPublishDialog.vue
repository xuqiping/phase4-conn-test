<template>
  <n-modal
    :show="show"
    preset="card"
    :title="`发布到公众池 · ${project?.name || '未选择项目'}`"
    :closable="!submitting"
    :mask-closable="!submitting"
    :close-on-esc="!submitting"
    style="max-width: 620px"
    @update:show="handleShowUpdate"
  >
    <div class="public-publish-dialog">
      <section v-if="isAdmin" class="public-publish-dialog__official" aria-label="官方发布说明">
        <div class="public-publish-dialog__official-heading">
          <n-icon :size="20" aria-hidden="true"><ribbon-outline /></n-icon>
          <strong>官方发布</strong>
          <n-tag size="small" :bordered="false" type="warning">固定开放使用</n-tag>
        </div>
        <p>管理员发布的官方内容将直接开放给所有用户，以只读方式使用，无需逐个审批。</p>
      </section>

      <section v-else aria-labelledby="public-access-mode-heading">
        <div id="public-access-mode-heading" class="public-publish-dialog__section-title">选择访问方式</div>
        <n-radio-group v-model:value="mode" class="public-publish-dialog__modes" name="public-access-mode">
          <label class="public-publish-dialog__mode" :class="{ 'public-publish-dialog__mode--selected': mode === 'OPEN' }">
            <n-radio value="OPEN" aria-label="开放使用" />
            <span>
              <strong>开放使用</strong>
              <small>所有人可直接只读使用，无需等待审批。</small>
            </span>
          </label>
          <label
            class="public-publish-dialog__mode"
            :class="{ 'public-publish-dialog__mode--selected': mode === 'APPROVAL_REQUIRED' }"
          >
            <n-radio value="APPROVAL_REQUIRED" aria-label="申请后使用" />
            <span>
              <strong>申请后使用</strong>
              <small>所有人可查看摘要，使用前需要申请，由项目所有者决定是否批准。</small>
            </span>
          </label>
        </n-radio-group>
      </section>

      <n-alert v-if="error" type="error" :show-icon="true" role="alert">{{ error }}</n-alert>
    </div>

    <template #action>
      <n-space justify="end">
        <n-button :disabled="submitting" @click="handleShowUpdate(false)">取消</n-button>
        <n-button type="primary" :loading="submitting" :disabled="!project || publishCompleted" @click="submit">
          {{ publishCompleted ? '已发布，等待列表刷新' : '发布到公众池' }}
        </n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NAlert, NButton, NIcon, NModal, NRadio, NRadioGroup, NSpace, NTag, useMessage } from 'naive-ui'
import { RibbonOutline } from '@vicons/ionicons5'
import { publicPoolApi } from '@/api/assets'
import type { AssetProjectVO, PublicAccessMode } from '@/types/asset'

const props = defineProps<{
  show: boolean
  project: AssetProjectVO | null
  isAdmin: boolean
}>()

const emit = defineEmits<{
  (e: 'update:show', value: boolean): void
  (e: 'published', projectId: number): void
}>()

const message = useMessage()
const mode = ref<PublicAccessMode>('OPEN')
const error = ref('')
const publishingProjectIds = ref<number[]>([])
const completedProjectIds = ref<number[]>([])
const observedPublishedProjectIds = ref<number[]>([])
const submitting = computed(() => Boolean(props.project && publishingProjectIds.value.includes(props.project.id)))
const publishCompleted = computed(() => Boolean(
  props.project && (props.project.publicPool || completedProjectIds.value.includes(props.project.id))
))
let contextVersion = 0

interface PublishContext {
  projectId: number
  version: number
}

watch(
  [() => props.show, () => props.project?.id, () => props.project?.publicPool],
  ([show, projectId, publicPool]) => {
    contextVersion += 1
    if (projectId && publicPool === true) {
      setObservedPublished(projectId, true)
    } else if (projectId && publicPool === false && observedPublishedProjectIds.value.includes(projectId)) {
      setCompleted(projectId, false)
      setObservedPublished(projectId, false)
    }
    mode.value = 'OPEN'
    error.value = ''
    if (!show) return
  },
  { immediate: true }
)

function isCurrentContext(context: PublishContext) {
  return props.show && props.project?.id === context.projectId && contextVersion === context.version
}

function handleShowUpdate(value: boolean) {
  if (!value && submitting.value) return
  emit('update:show', value)
}

function setPublishing(projectId: number, publishing: boolean) {
  if (publishing) {
    if (!publishingProjectIds.value.includes(projectId)) {
      publishingProjectIds.value = [...publishingProjectIds.value, projectId]
    }
    return
  }
  publishingProjectIds.value = publishingProjectIds.value.filter((id) => id !== projectId)
}

function setCompleted(projectId: number, completed: boolean) {
  if (completed) {
    if (!completedProjectIds.value.includes(projectId)) {
      completedProjectIds.value = [...completedProjectIds.value, projectId]
    }
    return
  }
  completedProjectIds.value = completedProjectIds.value.filter((id) => id !== projectId)
}

function setObservedPublished(projectId: number, observed: boolean) {
  if (observed) {
    if (!observedPublishedProjectIds.value.includes(projectId)) {
      observedPublishedProjectIds.value = [...observedPublishedProjectIds.value, projectId]
    }
    return
  }
  observedPublishedProjectIds.value = observedPublishedProjectIds.value.filter((id) => id !== projectId)
}

async function submit() {
  const currentProject = props.project
  if (
    !currentProject ||
    currentProject.publicPool ||
    publishingProjectIds.value.includes(currentProject.id) ||
    completedProjectIds.value.includes(currentProject.id)
  ) return
  const context: PublishContext = { projectId: currentProject.id, version: contextVersion }

  setPublishing(currentProject.id, true)
  error.value = ''
  const accessMode: PublicAccessMode = props.isAdmin ? 'OPEN' : mode.value
  let closeCurrentDialog = false
  try {
    await publicPoolApi.publish(currentProject.id, { accessMode })
    emit('published', currentProject.id)
    setCompleted(currentProject.id, true)
    if (isCurrentContext(context)) {
      message.success(props.isAdmin ? '官方项目已发布到公众池' : '项目已发布到公众池')
      closeCurrentDialog = true
    }
  } catch {
    if (isCurrentContext(context)) {
      error.value = '发布失败，请稍后重试'
      message.error('发布到公众池失败')
    }
  } finally {
    setPublishing(currentProject.id, false)
  }
  if (closeCurrentDialog && isCurrentContext(context)) emit('update:show', false)
}

defineExpose({ mode, submitting, publishCompleted, error, submit, handleShowUpdate })
</script>

<style scoped lang="scss">
.public-publish-dialog {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-4);
}

.public-publish-dialog__section-title {
  margin-bottom: var(--spacing-2);
  color: var(--color-text-secondary);
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.04em;
}

.public-publish-dialog__modes {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--spacing-3);
  width: 100%;
}

.public-publish-dialog__mode {
  display: flex;
  gap: var(--spacing-2);
  min-height: 110px;
  padding: var(--spacing-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-bg-secondary);
  cursor: pointer;
}

.public-publish-dialog__mode--selected {
  border-color: var(--color-primary);
  box-shadow: inset 3px 0 0 var(--color-primary);
}

.public-publish-dialog__mode > span {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-2);
}

.public-publish-dialog__mode strong {
  color: var(--color-text-primary);
}

.public-publish-dialog__mode small,
.public-publish-dialog__official p {
  margin: 0;
  color: var(--color-text-secondary);
  line-height: 1.65;
}

.public-publish-dialog__official {
  padding: var(--spacing-4);
  border: 1px solid rgba(240, 160, 32, 0.45);
  border-radius: var(--radius-md);
  background: linear-gradient(135deg, rgba(240, 160, 32, 0.12), rgba(240, 160, 32, 0.03));
}

.public-publish-dialog__official-heading {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  margin-bottom: var(--spacing-2);
  color: #f0a020;
}

@media (max-width: 640px) {
  .public-publish-dialog__modes {
    grid-template-columns: 1fr;
  }
}
</style>

<!--
  项目资产库·项目设置弹窗  plan 2x第三轮 C7（对齐后端 C6 PATCH /projects/{id}/settings）
  - 成员打分开关 n-switch：关闭后成员不可新增/修改评分，历史评分与均分保留
  - 内容模式 n-radio：SHARED 协作共享（默认）/ PERSONAL 成员仅能管理自己上传的内容
  - SHARED→PERSONAL 切换二次确认（n-popconfirm；后端直接生效，OWNER 不受影响、可随时切回）
  - 局部更新语义：仅提交变更字段（null=不改，与后端一致）
-->
<template>
  <n-modal
    :show="show"
    preset="card"
    title="项目设置"
    style="max-width:520px"
    @update:show="onVisibility"
  >
    <n-form label-placement="top">
      <n-form-item label="成员打分">
        <div class="project-settings__switch-row">
          <n-switch v-model:value="draftScoring" aria-label="成员打分开关" />
          <span class="project-settings__hint">
            开启后成员（EDITOR）可对资产打分并参与均分；关闭后成员不可新增或修改评分，历史评分与均分保留。拥有者始终可评分。
          </span>
        </div>
      </n-form-item>
      <n-form-item label="内容模式">
        <n-radio-group v-model:value="draftMode" name="content-mode">
          <div class="project-settings__mode-options">
            <n-radio value="SHARED">共享（协作）</n-radio>
            <n-radio value="PERSONAL">个人（仅管理自己上传的内容）</n-radio>
          </div>
        </n-radio-group>
        <span class="project-settings__hint">
          共享=所有编辑者可管理全部内容（现状默认）；个人=编辑者仅能编辑/删除/定稿/归档自己上传的内容，拥有者不受限制。切换即时生效且可逆，不删除任何数据。
        </span>
      </n-form-item>
    </n-form>
    <template #action>
      <n-button :disabled="saving" @click="close">取消</n-button>
      <!-- 切 PERSONAL 二次确认（plan C7；后端直接生效） -->
      <n-popconfirm v-if="needsPersonalConfirm" @positive-click="save">
        <template #trigger>
          <n-button type="primary" :loading="saving">保存</n-button>
        </template>
        切换为「个人」后，成员将只能管理自己上传的内容。确认切换？
      </n-popconfirm>
      <n-button v-else type="primary" :loading="saving" @click="save">保存</n-button>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NForm, NFormItem, NModal, NPopconfirm, NRadio, NRadioGroup, NSwitch, useMessage } from 'naive-ui'
import { projectApi } from '@/api/assets'
import type { AssetProjectVO, ProjectContentMode, ProjectSettingsRequest } from '@/types/asset'

const props = defineProps<{
  show: boolean
  /** 当前项目 VO（打开时取初始值；保存成功后父刷新传入新值） */
  project: AssetProjectVO | null
}>()

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  /** 保存成功（父重拉 project + 列表，L6 按钮显隐即时重算） */
  (e: 'saved'): void
}>()

const message = useMessage()

const draftScoring = ref(false)
const draftMode = ref<ProjectContentMode>('SHARED')
const originalScoring = ref(false)
const originalMode = ref<ProjectContentMode>('SHARED')
const saving = ref(false)

/** 打开时同步项目当前值（每次打开重建草稿，取消不污染；immediate 兜底初始即 show=true）。 */
watch(
  () => props.show,
  (show) => {
    if (show && props.project) {
      originalScoring.value = props.project.memberScoringEnabled === true
      originalMode.value = props.project.contentMode === 'PERSONAL' ? 'PERSONAL' : 'SHARED'
      draftScoring.value = originalScoring.value
      draftMode.value = originalMode.value
    }
  },
  { immediate: true }
)

/** SHARED→PERSONAL 需二次确认；PERSONAL→SHARED 回切恢复协作无需确认。 */
const needsPersonalConfirm = computed(
  () => draftMode.value === 'PERSONAL' && originalMode.value !== 'PERSONAL'
)

function onVisibility(v: boolean) {
  emit('update:show', v)
}

function close() {
  emit('update:show', false)
}

async function save() {
  if (!props.project || saving.value) return
  // 局部更新：仅提交变更字段（后端 null=不改）
  const payload: ProjectSettingsRequest = {}
  if (draftScoring.value !== originalScoring.value) payload.memberScoringEnabled = draftScoring.value
  if (draftMode.value !== originalMode.value) payload.contentMode = draftMode.value
  const hasChange = payload.memberScoringEnabled !== undefined || payload.contentMode !== undefined
  if (!hasChange) {
    message.info('设置未变更')
    close()
    return
  }
  saving.value = true
  try {
    await projectApi.updateSettings(props.project.id, payload)
    message.success('项目设置已更新')
    close()
    emit('saved')
  } catch {
    message.error('保存设置失败')
  } finally {
    saving.value = false
  }
}

defineExpose({ draftScoring, draftMode, needsPersonalConfirm, save })
</script>

<style lang="scss" scoped>
.project-settings {
  &__switch-row {
    display: flex;
    align-items: flex-start;
    gap: var(--spacing-3);
  }

  &__mode-options {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-2);
    margin-bottom: var(--spacing-2);
  }

  &__hint {
    display: block;
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
    line-height: 1.6;
  }
}
</style>

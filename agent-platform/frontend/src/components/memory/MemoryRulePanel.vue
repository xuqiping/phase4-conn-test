<!-- ============================================================
  收录规则面板（记忆二期 P1 · FR-001）— 项目收录规则编辑器
  · 项目列表复用 memoryApi.getGenMatrix（projectId/projectName/role）
  · owner/admin 可编辑（rule_text/正负例/enabled，PUT upsert）；成员只读（不见负例）
  · anchorReady=false = embed 失败规则未生效（enabled 强制 false），顶部警示
  ============================================================ -->
<template>
  <div class="memory-rule-panel">
    <n-alert type="info" :bordered="false" size="small" class="memory-rule-panel__top">
      收录规则：项目创建者配一条「什么对话该进项目记忆」的规则，路由自动把成员对话蒸馏成项目条目。
      规则只收摘要，对话原文不出个人域。
    </n-alert>

    <n-space :size="8" align="center" class="memory-rule-panel__toolbar">
      <n-select
        v-model:value="currentProjectId"
        :options="projectOptions"
        :loading="projectsLoading"
        size="small"
        placeholder="选择项目"
        class="memory-rule-panel__project-select"
        @update:value="onProjectChange"
      />
      <n-button size="small" :loading="projectsLoading" @click="loadProjects">刷新</n-button>
    </n-space>

    <n-empty
      v-if="!projectsLoading && !projectOptions.length"
      size="small"
      description="暂无项目（加入项目后可配置收录规则）"
    />

    <template v-else-if="currentProjectId">
      <n-spin :show="ruleLoading">
        <n-alert
          v-if="rule && !rule.anchorReady"
          type="warning"
          :bordered="false"
          size="small"
          class="memory-rule-panel__alert"
        >
          规则向量化失败（embed 故障），规则已强制停用——修复 embedding 后重新保存即可恢复。
        </n-alert>

        <n-alert
          v-if="!canEdit"
          type="default"
          :bordered="false"
          size="small"
          class="memory-rule-panel__alert"
        >
          仅项目 owner/admin 可配置收录规则；你可查看规则内容（负例不公开）。
        </n-alert>

        <n-form size="small" label-placement="top" :disabled="!canEdit">
          <n-form-item label="规则描述（什么样的对话该收进本项目，≤2000 字）">
            <n-input
              v-model:value="form.ruleText"
              type="textarea"
              :rows="3"
              maxlength="2000"
              show-count
              placeholder="例：涉及 SeedDance 视频生成的参数、踩坑、产出物讨论"
              :readonly="!canEdit"
            />
          </n-form-item>

          <n-form-item label="正例（该收录的对话样例，≤5 条）">
            <n-dynamic-input
              v-model:value="form.positiveExamples"
              :max="5"
              :disabled="!canEdit"
              :on-create="() => ''"
            >
              <template #default="{ index }">
                <n-input
                  v-model:value="form.positiveExamples[index]"
                  maxlength="500"
                  placeholder="例：SeedDance 2.0 的 4K 模式参数怎么配"
                  :readonly="!canEdit"
                />
              </template>
            </n-dynamic-input>
          </n-form-item>

          <n-form-item v-if="canEdit" label="负例（不该收录的对话样例，≤5 条；审核「弃」会自动反哺）">
            <n-dynamic-input
              v-model:value="form.negativeExamples"
              :max="5"
              :on-create="() => ''"
            >
              <template #default="{ index }">
                <n-input
                  v-model:value="form.negativeExamples[index]"
                  maxlength="500"
                  placeholder="例：闲聊今天吃什么"
                />
              </template>
            </n-dynamic-input>
          </n-form-item>

          <n-form-item label="启用">
            <n-switch v-model:value="form.enabled" :disabled="!canEdit" size="small" />
            <span class="memory-rule-panel__switch-hint">
              {{ form.enabled ? '路由生效中' : '已停用（不产生新条目）' }}
            </span>
          </n-form-item>
        </n-form>

        <n-space v-if="canEdit" :size="8" align="center">
          <n-button
            size="small"
            type="primary"
            :loading="saving"
            :disabled="!form.ruleText.trim()"
            @click="save"
          >
            保存规则
          </n-button>
          <span v-if="rule?.updatedAt" class="memory-rule-panel__meta">上次保存：{{ rule.updatedAt }}</span>
        </n-space>
      </n-spin>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  NAlert, NButton, NDynamicInput, NEmpty, NForm, NFormItem, NInput,
  NSelect, NSpace, NSpin, NSwitch, useMessage
} from 'naive-ui'
import {
  memoryApi,
  type MemoryGenMatrixItemVO,
  type MemoryProjectRuleVO
} from '@/api/memory'

const message = useMessage()

const projects = ref<MemoryGenMatrixItemVO[]>([])
const projectsLoading = ref(false)
const currentProjectId = ref<number | null>(null)
const rule = ref<MemoryProjectRuleVO | null>(null)
const ruleLoading = ref(false)
const saving = ref(false)

const form = ref({
  ruleText: '',
  positiveExamples: [] as string[],
  negativeExamples: [] as string[],
  enabled: true
})

const projectOptions = computed(() =>
  projects.value.map(p => ({ label: `${p.projectName}（${p.role}）`, value: p.projectId }))
)

/** 当前项目角色是否 owner/admin（可编辑规则）。 */
const canEdit = computed(() => {
  const p = projects.value.find(x => x.projectId === currentProjectId.value)
  return p?.role === 'OWNER' || p?.role === 'ADMIN'
})

async function loadProjects() {
  projectsLoading.value = true
  try {
    const res = await memoryApi.getGenMatrix()
    projects.value = res.data?.data ?? []
    if (!currentProjectId.value && projects.value.length) {
      // 默认选中第一个可编辑项目，其次第一个项目
      const first = projects.value.find(p => p.role === 'OWNER' || p.role === 'ADMIN') ?? projects.value[0]
      currentProjectId.value = first.projectId
      await loadRule()
    }
  } catch (e: any) {
    message.error(e?.message || '加载项目失败')
  } finally {
    projectsLoading.value = false
  }
}

async function loadRule() {
  if (currentProjectId.value == null) return
  ruleLoading.value = true
  try {
    const res = await memoryApi.getProjectRule(currentProjectId.value)
    rule.value = res.data?.data ?? null
    form.value = {
      ruleText: rule.value?.ruleText ?? '',
      positiveExamples: [...(rule.value?.positiveExamples ?? [])],
      negativeExamples: [...(rule.value?.negativeExamples ?? [])],
      enabled: rule.value?.enabled ?? true
    }
  } catch (e: any) {
    message.error(e?.message || '加载规则失败')
  } finally {
    ruleLoading.value = false
  }
}

async function onProjectChange() {
  await loadRule()
}

async function save() {
  if (currentProjectId.value == null || !canEdit.value) return
  saving.value = true
  try {
    const res = await memoryApi.putProjectRule(currentProjectId.value, {
      ruleText: form.value.ruleText.trim(),
      positiveExamples: form.value.positiveExamples.map(s => s.trim()).filter(Boolean),
      negativeExamples: form.value.negativeExamples.map(s => s.trim()).filter(Boolean),
      enabled: form.value.enabled
    })
    rule.value = res.data?.data ?? null
    form.value.enabled = rule.value?.enabled ?? form.value.enabled
    message.success(rule.value?.anchorReady ? '规则已保存' : '已保存，但向量化失败规则未生效')
  } catch (e: any) {
    message.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(loadProjects)
defineExpose({ refresh: loadProjects })
</script>

<style lang="scss" scoped>
.memory-rule-panel {
  &__top {
    margin-bottom: 12px;
  }
  &__toolbar {
    margin-bottom: 12px;
  }
  &__project-select {
    width: 240px;
  }
  &__alert {
    margin-bottom: 12px;
  }
  &__switch-hint {
    margin-left: 8px;
    font-size: 12px;
    opacity: 0.65;
  }
  &__meta {
    font-size: 11px;
    opacity: 0.55;
  }
}
</style>

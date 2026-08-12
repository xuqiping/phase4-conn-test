<template>
  <n-modal
    v-model:show="visible"
    preset="card"
    :title="isEdit ? '编辑能力' : '新增能力'"
    style="max-width: 880px"
  >
    <n-form ref="formRef" :model="form" :rules="rules" label-placement="top">
      <div class="skill-form__grid">
        <n-form-item label="能力名称" path="name">
          <n-input v-model:value="form.name" placeholder="例如：联调摘要生成" />
        </n-form-item>
        <n-form-item label="类型" path="type">
          <n-select v-model:value="form.type" :options="typeOptions" />
        </n-form-item>
      </div>

      <n-form-item label="描述" path="description">
        <n-input
          v-model:value="form.description"
          type="textarea"
          placeholder="说明这个能力适合处理什么任务"
          :rows="3"
        />
      </n-form-item>

      <div class="skill-form__grid">
        <n-form-item label="排序" path="sortOrder">
          <n-input-number v-model:value="form.sortOrder" :min="0" />
        </n-form-item>
      </div>

      <section class="skill-form__section">
        <div class="skill-form__section-header">
          <div>
            <span class="skill-form__section-title">公开入参</span>
            <p class="skill-form__section-desc">工作流引用该能力时，会按这里声明的参数展示固定入参名和说明。</p>
          </div>
          <n-button size="small" @click="addInputParam">新增入参</n-button>
        </div>

        <div v-for="(param, index) in inputParams" :key="index" class="skill-form__param-row">
          <n-form-item label="参数名">
            <n-input v-model:value="param.key" placeholder="summary" />
          </n-form-item>
          <n-form-item label="展示名">
            <n-input v-model:value="param.label" placeholder="摘要" />
          </n-form-item>
          <n-form-item label="类型">
            <n-select v-model:value="param.type" :options="inputTypeOptions" />
          </n-form-item>
          <n-form-item label="必填">
            <n-switch v-model:value="param.required" />
          </n-form-item>
          <n-form-item label="默认值">
            <n-input v-model:value="param.defaultValue" placeholder="可选" />
          </n-form-item>
          <n-form-item label="参数说明" class="skill-form__param-desc">
            <n-input v-model:value="param.description" placeholder="告诉引用方应该传什么内容" />
          </n-form-item>
          <n-button size="small" tertiary type="error" @click="removeInputParam(index)">删除</n-button>
        </div>

        <n-empty v-if="inputParams.length === 0" description="暂无公开入参">
          <template #extra>
            <n-button size="small" @click="addInputParam">新增第一个入参</n-button>
          </template>
        </n-empty>
      </section>

      <section class="skill-form__section">
        <div class="skill-form__section-header">
          <div>
            <span class="skill-form__section-title">执行步骤</span>
            <p class="skill-form__section-desc">按顺序执行。动作类型从下拉菜单选择，配置项会自动切换成对应表单。</p>
          </div>
          <n-button size="small" @click="addStep">新增步骤</n-button>
        </div>

        <div v-for="(step, index) in steps" :key="index" class="skill-form__step">
          <div class="skill-form__step-title">
            <span>步骤 {{ index + 1 }}</span>
            <n-button size="tiny" tertiary type="error" @click="removeStep(index)">删除</n-button>
          </div>

          <div class="skill-form__grid">
            <n-form-item label="步骤名称">
              <n-input v-model:value="step.name" placeholder="例如：生成联调摘要" />
            </n-form-item>
            <n-form-item label="动作">
              <n-select
                v-model:value="step.action"
                :options="stepActionOptions"
                @update:value="() => onStepActionChanged(step)"
              />
            </n-form-item>
          </div>

          <div v-if="step.action === 'LLM_CALL'" class="skill-form__step-config">
            <n-form-item label="系统提示词（可选）">
              <n-input
                v-model:value="step.configForm.systemPrompt"
                type="textarea"
                placeholder="定义模型角色、规则和边界"
                :rows="3"
              />
            </n-form-item>
            <n-form-item label="用户提示词（必填）">
              <n-input
                v-model:value="step.configForm.promptTemplate"
                type="textarea"
                placeholder="例如：请根据 {{input}} 生成联调摘要"
                :rows="5"
              />
            </n-form-item>
            <div class="skill-form__grid">
              <n-form-item label="模型（可选）">
                <n-input v-model:value="step.configForm.model" placeholder="留空使用管理员默认对话模型" />
              </n-form-item>
              <n-form-item label="输出变量名（可选）">
                <n-input v-model:value="step.configForm.outputKey" placeholder="summary" />
              </n-form-item>
            </div>
            <n-form-item label="温度（可选，0 更稳定，2 更发散）">
              <n-input-number
                v-model:value="step.configForm.temperature"
                :min="0"
                :max="2"
                :step="0.1"
              />
            </n-form-item>
          </div>

          <n-collapse>
            <n-collapse-item title="高级 JSON 预览" name="json">
              <n-input
                :value="buildStepConfig(step.configForm)"
                type="textarea"
                readonly
                :rows="5"
              />
            </n-collapse-item>
          </n-collapse>
        </div>

        <n-empty v-if="steps.length === 0" description="暂无步骤">
          <template #extra>
            <n-button size="small" @click="addStep">新增第一个步骤</n-button>
          </template>
        </n-empty>
      </section>
    </n-form>

    <template #action>
      <n-space justify="end">
        <n-button @click="visible = false">取消</n-button>
        <n-button type="primary" :loading="saving" @click="handleSubmit">
          保存能力
        </n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  NButton,
  NCollapse,
  NCollapseItem,
  NEmpty,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NModal,
  NSelect,
  NSpace,
  NSwitch,
  useMessage
} from 'naive-ui'
import type { FormInst, FormRules } from 'naive-ui'
import { agentApi, type SkillDetail, type SkillSaveRequest } from '@/api/agent'
import {
  buildSkillConfig,
  buildStepConfig,
  DEFAULT_LLM_STEP_CONFIG,
  parseLlmStepConfig,
  parseSkillInputParams,
  STEP_ACTION_OPTIONS,
  type SkillInputParamForm,
  type SkillStepForm
} from '@/utils/skillConfigForm'

const props = defineProps<{
  agentId: number
  editData?: SkillDetail | null
}>()

const emit = defineEmits<{
  (e: 'saved', skill: SkillDetail): void
}>()

const visible = defineModel<boolean>('show', { default: false })
const message = useMessage()
const formRef = ref<FormInst | null>(null)
const saving = ref(false)
const inputParams = ref<SkillInputParamForm[]>([])
const steps = ref<SkillStepForm[]>([])

const isEdit = computed(() => !!props.editData)

const typeOptions = [
  { label: '顺序执行', value: 'SEQUENCE' },
  { label: '路由决策', value: 'ROUTER' },
  { label: '并行处理', value: 'PARALLEL' }
]

const inputTypeOptions = [
  { label: '文本', value: 'text' },
  { label: '多行文本', value: 'textarea' },
  { label: '数字', value: 'number' },
  { label: '布尔值', value: 'boolean' },
  { label: 'JSON', value: 'json' }
]

const stepActionOptions = STEP_ACTION_OPTIONS.map(action => ({
  label: action.label,
  value: action.value
}))

const form = ref<SkillSaveRequest>({
  name: '',
  description: '',
  type: 'SEQUENCE',
  config: '{}',
  sortOrder: 0,
  steps: []
})

const rules: FormRules = {
  name: [{ required: true, message: '请输入能力名称', trigger: 'blur' }]
}

watch(visible, (show) => {
  if (!show) return
  if (props.editData) {
    form.value = {
      name: props.editData.name,
      description: props.editData.description || '',
      type: props.editData.type || 'SEQUENCE',
      config: props.editData.config || '{}',
      sortOrder: props.editData.sortOrder ?? 0,
      steps: []
    }
    inputParams.value = parseSkillInputParams(props.editData.config)
    steps.value = (props.editData.steps || []).map(step => ({
      stepOrder: step.stepOrder,
      name: step.name,
      action: step.action || 'LLM_CALL',
      config: step.config || '{}',
      configForm: parseLlmStepConfig(step.config)
    }))
  } else {
    form.value = {
      name: '',
      description: '',
      type: 'SEQUENCE',
      config: '{}',
      sortOrder: 0,
      steps: []
    }
    inputParams.value = []
    steps.value = []
  }
})

function addInputParam() {
  inputParams.value.push({
    key: '',
    label: '',
    description: '',
    type: 'text',
    required: false,
    defaultValue: ''
  })
}

function removeInputParam(index: number) {
  inputParams.value.splice(index, 1)
}

function addStep() {
  steps.value.push({
    stepOrder: steps.value.length + 1,
    name: '',
    action: 'LLM_CALL',
    config: '{}',
    configForm: { ...DEFAULT_LLM_STEP_CONFIG }
  })
}

function removeStep(index: number) {
  steps.value.splice(index, 1)
  steps.value.forEach((step, stepIndex) => {
    step.stepOrder = stepIndex + 1
  })
}

function onStepActionChanged(step: SkillStepForm) {
  if (step.action === 'LLM_CALL') {
    step.configForm = step.configForm || { ...DEFAULT_LLM_STEP_CONFIG }
  }
}

async function handleSubmit() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }

  const invalidParam = inputParams.value.find(param => !param.key.trim())
  if (invalidParam) {
    message.warning('公开入参的参数名不能为空')
    return
  }
  const invalidStep = steps.value.find(step => step.action === 'LLM_CALL' && !step.configForm.promptTemplate.trim())
  if (invalidStep) {
    message.warning('LLM 调用步骤的用户提示词不能为空')
    return
  }

  const payload: SkillSaveRequest = {
    ...form.value,
    description: form.value.description || undefined,
    config: buildSkillConfig(inputParams.value),
    steps: steps.value.map((step, index) => ({
      stepOrder: index + 1,
      name: step.name,
      action: step.action || 'LLM_CALL',
      config: buildStepConfig(step.configForm)
    }))
  }

  saving.value = true
  try {
    const res = isEdit.value && props.editData
      ? await agentApi.updateSkill(props.editData.id, payload)
      : await agentApi.createSkill(props.agentId, payload)
    message.success(isEdit.value ? '能力更新成功' : '能力创建成功')
    emit('saved', res.data.data)
    visible.value = false
  } catch {
    message.error(isEdit.value ? '能力更新失败' : '能力创建失败')
  } finally {
    saving.value = false
  }
}
</script>

<style lang="scss" scoped>
.skill-form__grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: var(--spacing-4);
}

.skill-form__section {
  margin-top: var(--spacing-4);
}

.skill-form__section-header,
.skill-form__step-title {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--spacing-3);
  margin-bottom: var(--spacing-3);
  color: var(--color-text-primary);
}

.skill-form__section-title,
.skill-form__step-title {
  font-weight: var(--font-weight-semibold);
}

.skill-form__section-desc {
  margin: 4px 0 0;
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
}

.skill-form__param-row {
  display: grid;
  grid-template-columns: minmax(100px, 1fr) minmax(100px, 1fr) 110px 72px minmax(100px, 1fr) 1.4fr auto;
  gap: var(--spacing-2);
  align-items: start;
  padding: var(--spacing-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-base);
  background: var(--color-elevated);
  margin-bottom: var(--spacing-2);
}

.skill-form__step {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-base);
  padding: var(--spacing-4);
  margin-bottom: var(--spacing-3);
  background: var(--color-elevated);
}

.skill-form__step-config {
  margin-top: var(--spacing-2);
}

@media (max-width: 960px) {
  .skill-form__param-row {
    grid-template-columns: 1fr 1fr;
  }

  .skill-form__param-desc {
    grid-column: 1 / -1;
  }
}

@media (max-width: 720px) {
  .skill-form__grid,
  .skill-form__param-row {
    grid-template-columns: 1fr;
  }
}
</style>

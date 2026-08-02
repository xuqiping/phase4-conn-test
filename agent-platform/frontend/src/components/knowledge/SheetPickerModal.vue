<template>
  <n-modal
    :show="show"
    preset="card"
    :title="`选择导入的 Sheet — ${fileName}`"
    style="max-width: 520px"
    :mask-closable="false"
    @update:show="onUpdateShow"
  >
    <n-space vertical :size="12">
      <n-checkbox
        :checked="allChecked"
        :indeterminate="indeterminate"
        @update:checked="toggleAll"
      >
        全选 / 反选
      </n-checkbox>
      <n-checkbox-group v-model:value="picked">
        <n-space vertical :size="6">
          <n-checkbox v-for="name in sheetNames" :key="name" :value="name" :label="name" />
        </n-space>
      </n-checkbox-group>
      <p class="sheet-picker__hint">已选 {{ picked.length }} / {{ sheetNames.length }}（空选 = 导全部 sheet）</p>
    </n-space>
    <template #footer>
      <n-space justify="end">
        <n-button @click="emit('cancel')">取消</n-button>
        <n-button type="primary" :loading="loading" @click="confirm">确认上传</n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NCheckbox, NCheckboxGroup, NModal, NSpace } from 'naive-ui'

const props = defineProps<{
  show: boolean
  fileName: string
  sheetNames: string[]
  loading?: boolean
}>()
const emit = defineEmits<{ confirm: [sheets: string[]]; cancel: []; 'update:show': [v: boolean] }>()

/** 进 modal 时默认全选（用户可取消不要的），保持「不改 = 全部」直觉。 */
const picked = ref<string[]>([])
watch(
  () => props.show,
  v => { if (v) picked.value = [...props.sheetNames] }
)

const allChecked = computed(() =>
  props.sheetNames.length > 0 && picked.value.length === props.sheetNames.length)
const indeterminate = computed(() =>
  picked.value.length > 0 && picked.value.length < props.sheetNames.length)

function toggleAll(checked: boolean) {
  picked.value = checked ? [...props.sheetNames] : []
}

function confirm() {
  emit('confirm', [...picked.value])
}

function onUpdateShow(v: boolean) {
  emit('update:show', v)
}
</script>

<style lang="scss" scoped>
.sheet-picker__hint {
  margin: 0;
  font-size: 12px;
  color: var(--color-text-tertiary);
}
</style>

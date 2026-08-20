<template>
  <div ref="boxRef" class="group-output-image" :title="failed ? '预览加载失败' : '点击查看大图'">
    <img v-if="url" :src="url" alt="图片产物" loading="lazy" @click="openFull" />
    <div v-else class="group-output-image__placeholder" :class="{ 'group-output-image__placeholder--failed': failed }">
      {{ failed ? '✕' : '…' }}
    </div>
    <!-- 大图弹窗 -->
    <NModal v-model:show="showFull" preset="card" title="图片产物" style="max-width: 80vw">
      <img v-if="url" :src="url" alt="图片产物" class="group-output-image__full" />
    </NModal>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { NModal } from 'naive-ui'
import { useLazyFilePreview } from '@/composables/useLazyFilePreview'

/**
 * 17x#1：组产出图片缩略图（懒加载 objectURL；组内共享由 ProjectGroupFileAccessGrantor 放行）。
 */
const props = defineProps<{ fileId: string }>()

const boxRef = ref<HTMLElement | null>(null)
const { url, failed } = useLazyFilePreview(boxRef, () => props.fileId, true)
const showFull = ref(false)

function openFull() {
  showFull.value = true
}
</script>

<style lang="scss" scoped>
.group-output-image {
  width: 56px;
  height: 42px;
  border-radius: 4px;
  border: 1px solid var(--color-border-light);
  overflow: hidden;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.03);
  cursor: pointer;
  vertical-align: middle;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.group-output-image__placeholder {
  font-size: 12px;
  color: var(--color-text-tertiary);

  &--failed {
    color: var(--color-error, #e88080);
    cursor: default;
  }
}

.group-output-image__full {
  max-width: 100%;
  max-height: 70vh;
  display: block;
  margin: 0 auto;
  border-radius: 6px;
}
</style>

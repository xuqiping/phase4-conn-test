<template>
  <div ref="root" class="picker-preview" :aria-label="`${name}预览`">
    <img v-if="mediaType === '图片' && url" :src="url" :alt="name" />
    <video v-else-if="mediaType === '视频' && url" :src="url" muted playsinline preload="metadata" />
    <span v-else>{{ failed ? '预览不可用' : mediaType }}</span>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useLazyFilePreview } from '@/composables/useLazyFilePreview'
import type { AssetMediaType } from '@/types/asset'

const props = defineProps<{ fileId?: string; mediaType: AssetMediaType; name: string }>()
const root = ref<HTMLElement | null>(null)
const enabled = computed(() => props.mediaType === '图片' || props.mediaType === '视频')
const { url, failed } = useLazyFilePreview(root, () => props.fileId, enabled)
</script>

<style scoped lang="scss">
.picker-preview {
  width: 72px;
  height: 48px;
  flex: 0 0 72px;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: var(--radius-small);
  background: #000;
  color: var(--color-text-tertiary);
  font-size: 11px;

  img, video { width: 100%; height: 100%; object-fit: cover; }
}
</style>

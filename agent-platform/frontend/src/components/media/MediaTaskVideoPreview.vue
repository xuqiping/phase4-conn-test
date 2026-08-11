<template>
  <div ref="root" class="task-video-preview" aria-label="历史任务视频预览">
    <video v-if="objectUrl" :src="objectUrl" muted controls playsinline preload="metadata" />
    <span v-else-if="failed">预览不可用</span>
    <span v-else>等待预览</span>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { fetchVideoBlob } from '@/api/media'

const props = defineProps<{ downloadPath: string }>()
const root = ref<HTMLElement | null>(null)
const objectUrl = ref<string | null>(null)
const failed = ref(false)
let observer: IntersectionObserver | null = null
let visible = false
let requestVersion = 0

function revoke() {
  requestVersion++
  if (objectUrl.value) URL.revokeObjectURL(objectUrl.value)
  objectUrl.value = null
}

async function load() {
  if (!visible || !props.downloadPath || objectUrl.value) return
  const version = ++requestVersion
  failed.value = false
  try {
    const url = await fetchVideoBlob(props.downloadPath)
    if (version !== requestVersion) return URL.revokeObjectURL(url)
    objectUrl.value = url
  } catch {
    if (version === requestVersion) failed.value = true
  }
}

onMounted(() => {
  if (!root.value || typeof IntersectionObserver === 'undefined') return
  observer = new IntersectionObserver(entries => {
    visible = !!entries[0]?.isIntersecting
    if (visible) void load()
  }, { rootMargin: '120px' })
  observer.observe(root.value)
})

watch(() => props.downloadPath, () => {
  revoke()
  failed.value = false
  void load()
})

onBeforeUnmount(() => {
  observer?.disconnect()
  revoke()
})
</script>

<style scoped lang="scss">
.task-video-preview {
  width: 132px;
  height: 74px;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: var(--radius-base);
  background: #000;
  color: var(--color-text-tertiary);
  font-size: var(--font-size-xs);

  video { width: 100%; height: 100%; object-fit: cover; }
}
</style>

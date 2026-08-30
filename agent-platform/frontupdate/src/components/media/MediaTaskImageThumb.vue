<template>
  <div
    ref="root"
    class="task-image-thumb"
    aria-label="历史任务首图缩略"
    @click.stop="emit('preview', objectUrl)"
  >
    <img v-if="objectUrl" :src="objectUrl" alt="首图缩略" />
    <span v-else-if="failed" class="task-image-thumb__ph">无预览</span>
    <NSpin v-else size="small" />
  </div>
</template>

<script setup lang="ts">
// 历史行首图缩略（问题1：不点开看不到图）。
// 与 MediaTaskVideoPreview 同构：进入可视区才带鉴权拉 blob（IntersectionObserver 懒加载），
// 卸载/换行 revoke objectURL 防内存泄漏；版本守卫防竞态回写。
// 点击缩略图 emit preview（调用方弹灯箱），stop 冒泡防触发行点击（行点击=加载任务详情）。
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { NSpin } from 'naive-ui'
import { fetchMediaBlob } from '@/api/media'

const props = defineProps<{ downloadPath: string }>()
const emit = defineEmits<{ preview: [url: string | null] }>()

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
    const url = await fetchMediaBlob(props.downloadPath)
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
.task-image-thumb {
  width: 56px;
  height: 56px;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: var(--radius-base, 6px);
  background: var(--bg-color-2, #1a1a1e);
  color: var(--text-color-3);
  font-size: 11px;
  cursor: zoom-in;

  img { width: 100%; height: 100%; object-fit: cover; }
  &__ph { white-space: nowrap; }
}
</style>

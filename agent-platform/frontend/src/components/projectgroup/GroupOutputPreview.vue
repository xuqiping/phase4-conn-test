<template>
  <span class="group-output-preview">
    <!-- 图片行：缩略图组 -->
    <template v-if="row.kind === 'IMAGE'">
      <template v-if="row.imageFileIds && row.imageFileIds.length">
        <GroupOutputImage v-for="fid in row.imageFileIds" :key="fid" :file-id="fid" class="group-output-preview__img" />
      </template>
      <span v-else class="group-output-preview__none">-</span>
    </template>
    <!-- 视频行：播放按钮 + 弹窗播放器 -->
    <template v-else-if="row.kind === 'VIDEO'">
      <NButton v-if="row.resultFileId" size="tiny" quaternary type="primary" @click="openVideo">▶ 播放</NButton>
      <span v-else class="group-output-preview__none">-</span>
    </template>
    <span v-else class="group-output-preview__none">-</span>

    <NModal v-model:show="showVideo" preset="card" title="视频产物" style="max-width: 80vw">
      <video v-if="videoUrl" :src="videoUrl" controls autoplay class="group-output-preview__video" />
      <NSpin v-else-if="videoLoading" size="small" />
      <span v-else class="group-output-preview__none">加载失败或无权限</span>
    </NModal>
  </span>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { NButton, NModal, NSpin } from 'naive-ui'
import GroupOutputImage from './GroupOutputImage.vue'
import { fetchFilePreview } from '@/api/file'
import type { ProjectGroupOutputVO } from '@/api/projectGroup'

/**
 * 17x#1：组产出预览单元格（产出表「预览」列）。
 * 图片→懒加载缩略图；视频→弹窗播放（打开时才拉 blob，防列表页整页视频流量）。
 */
const props = defineProps<{ row: ProjectGroupOutputVO }>()

const showVideo = ref(false)
const videoUrl = ref<string | null>(null)
const videoLoading = ref(false)

async function openVideo() {
  showVideo.value = true
  if (videoUrl.value || videoLoading.value || !props.row.resultFileId) return
  videoLoading.value = true
  try {
    videoUrl.value = await fetchFilePreview(props.row.resultFileId)
  } catch {
    videoUrl.value = null
  } finally {
    videoLoading.value = false
  }
}
</script>

<style lang="scss" scoped>
.group-output-preview__img {
  margin-right: 4px;
}

.group-output-preview__none {
  color: var(--color-text-tertiary);
  font-size: 12px;
}

.group-output-preview__video {
  width: 100%;
  max-height: 70vh;
  border-radius: 6px;
  background: #000;
}
</style>

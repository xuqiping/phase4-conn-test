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
    <!-- 17x-2026-08-25：对话行——查看生成结果弹窗（完整回复，滚动展示） -->
    <template v-else-if="row.kind === 'CHAT'">
      <NButton v-if="row.chatResult" size="tiny" quaternary type="primary" @click="showChat = true">查看结果</NButton>
      <span v-else class="group-output-preview__none">-</span>
    </template>
    <span v-else class="group-output-preview__none">-</span>

    <NModal v-model:show="showChat" preset="card" title="对话生成结果" style="max-width: 720px">
      <div class="group-output-preview__chat">{{ row.chatResult }}</div>
    </NModal>

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
const showChat = ref(false)
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

.group-output-preview__chat {
  max-height: 60vh;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.6;
}
</style>

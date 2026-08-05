<template>
  <n-drawer
    :show="show"
    :width="460"
    placement="right"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <n-drawer-content title="故事板（时间线排列）" closable>
      <div v-if="!ordered.length" class="sb-empty">
        画布上还没有可用的视频产出物。先生成/截取视频节点，再来这里排列成片。
      </div>

      <template v-else>
        <!-- 顺序预览：按当前顺序依次播放各段，ended 自动进下一段 -->
        <div class="sb-preview">
          <video
            v-if="currentPreviewUrl"
            ref="previewEl"
            :key="playIndex"
            :src="currentPreviewUrl"
            class="sb-preview__video"
            controls
            muted
            @ended="onSegmentEnded"
          />
          <div v-else class="sb-preview__placeholder">第 {{ playIndex + 1 }} 段无可预览流</div>
          <div class="sb-preview__hint">
            顺序预览：第 {{ playIndex + 1 }} / {{ ordered.length }} 段
            <span v-if="totalDurationSec">· 合计 ≈ {{ totalDurationSec }}s</span>
          </div>
        </div>

        <!-- 时间线列表：上下调序 -->
        <div class="sb-timeline">
          <div
            v-for="(seg, idx) in ordered"
            :key="seg.nodeId"
            class="sb-seg"
            :class="{ 'sb-seg--active': idx === playIndex }"
            @click="playIndex = idx"
          >
            <div class="sb-seg__idx">{{ idx + 1 }}</div>
            <div class="sb-seg__body">
              <div class="sb-seg__label" :title="seg.label">{{ seg.label }}</div>
              <div class="sb-seg__meta">
                <span v-if="seg.durationSec">{{ seg.durationSec }}s</span>
                <span v-else>时长未知</span>
              </div>
            </div>
            <div class="sb-seg__ops">
              <n-button quaternary size="tiny" :disabled="idx === 0" @click.stop="move(idx, -1)">
                ↑
              </n-button>
              <n-button quaternary size="tiny" :disabled="idx === ordered.length - 1" @click.stop="move(idx, 1)">
                ↓
              </n-button>
            </div>
          </div>
        </div>

        <div class="sb-foot">
          <n-button
            type="primary"
            block
            :loading="concating"
            :disabled="ordered.length < 2"
            @click="onConcat"
          >
            <template #icon><n-icon :component="FilmOutline" /></template>
            拼接成片（{{ ordered.length }} 段）
          </n-button>
          <div v-if="ordered.length < 2" class="sb-foot__hint">至少 2 段才能拼接</div>
        </div>
      </template>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NDrawer, NDrawerContent, NIcon } from 'naive-ui'
import { FilmOutline } from '@vicons/ionicons5'
import type { StoryboardSegment } from '@/types/canvas'

const props = defineProps<{
  show: boolean
  /** 画布上全部可参与故事板的视频段（已有 fileId）。父组件筛选后传入。 */
  segments: StoryboardSegment[]
  /** 拼接进行中（按钮 loading + 防重入）。 */
  concating?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  /** 按当前顺序拼接：传出有序 fileId 列表。 */
  (e: 'concat', fileIds: string[]): void
}>()

/** 内部排序（nodeId 顺序）；初始/segments 变化时同步新增段、保留已存在段旧序。 */
const order = ref<string[]>([])
const playIndex = ref(0)

watch(
  () => props.segments,
  (segs) => {
    const ids = segs.map(s => s.nodeId)
    // 保留旧序里仍存在的，追加新出现的
    const kept = order.value.filter(id => ids.includes(id))
    for (const id of ids) {
      if (!kept.includes(id)) kept.push(id)
    }
    order.value = kept
    if (playIndex.value >= kept.length) playIndex.value = 0
  },
  { immediate: true, deep: true }
)

const segMap = computed(() => {
  const m = new Map<string, StoryboardSegment>()
  for (const s of props.segments) m.set(s.nodeId, s)
  return m
})

const ordered = computed(() =>
  order.value
    .map(id => segMap.value.get(id))
    .filter((s): s is StoryboardSegment => !!s)
)

const currentPreviewUrl = computed(() => ordered.value[playIndex.value]?.previewUrl)

const totalDurationSec = computed(() => {
  const t = ordered.value.reduce((acc, s) => acc + (s.durationSec ?? 0), 0)
  return t > 0 ? t : null
})

function move(idx: number, delta: number) {
  const to = idx + delta
  if (to < 0 || to >= order.value.length) return
  const arr = [...order.value]
  ;[arr[idx], arr[to]] = [arr[to], arr[idx]]
  order.value = arr
}

/** 当前段播完：自动进下一段；末段结束停。 */
function onSegmentEnded() {
  if (playIndex.value < ordered.value.length - 1) {
    playIndex.value++
  }
}

function onConcat() {
  const fileIds = ordered.value.map(s => s.fileId)
  emit('concat', fileIds)
}

const previewEl = ref<HTMLVideoElement | null>(null)
</script>

<style lang="scss" scoped>
.sb-empty {
  color: var(--color-text-tertiary);
  font-size: var(--font-size-sm);
  padding: var(--spacing-4);
  text-align: center;
  line-height: 1.6;
}

.sb-preview {
  margin-bottom: var(--spacing-3);

  &__video {
    width: 100%;
    max-height: 240px;
    background: #000;
    border-radius: var(--radius-md);
    display: block;
  }

  &__placeholder {
    width: 100%;
    height: 160px;
    display: flex;
    align-items: center;
    justify-content: center;
    background: var(--color-bg);
    border-radius: var(--radius-md);
    color: var(--color-text-tertiary);
    font-size: var(--font-size-xs);
  }

  &__hint {
    margin-top: var(--spacing-1);
    font-size: var(--font-size-xs);
    color: var(--color-text-secondary);
  }
}

.sb-timeline {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-1);
}

.sb-seg {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  padding: var(--spacing-2);
  background: var(--color-bg);
  border: 1px solid transparent;
  border-radius: var(--radius-base);
  cursor: pointer;
  transition: all var(--duration-instant) var(--ease-in-out);

  &:hover {
    border-color: var(--color-border-light);
  }

  &--active {
    border-color: var(--color-primary);
    background: var(--color-primary-light);
  }

  &__idx {
    width: 24px;
    height: 24px;
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    background: var(--color-surface);
    border-radius: 50%;
    font-size: var(--font-size-xs);
    color: var(--color-text-secondary);
    font-weight: var(--font-weight-bold);
  }

  &__body {
    flex: 1;
    min-width: 0;
  }

  &__label {
    font-size: var(--font-size-sm);
    color: var(--color-text-primary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  &__meta {
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
    margin-top: 2px;
  }

  &__ops {
    display: flex;
    flex-direction: column;
    flex-shrink: 0;
  }
}

.sb-foot {
  margin-top: var(--spacing-3);

  &__hint {
    margin-top: var(--spacing-1);
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
    text-align: center;
  }
}
</style>

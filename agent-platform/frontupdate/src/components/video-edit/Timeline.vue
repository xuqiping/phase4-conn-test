<template>
  <div class="timeline" @pointermove="onPointerMove" @pointerup="onPointerUp">
    <!-- 工具栏 -->
    <div class="timeline__toolbar">
      <span class="timeline__total">总时长 ≈ {{ totalSeconds }}s</span>
      <n-button-group size="tiny">
        <n-button @click="zoom(-10)" :disabled="pxPerSecond <= 20">－</n-button>
        <n-button @click="zoom(10)" :disabled="pxPerSecond >= 200">＋</n-button>
      </n-button-group>
      <n-button size="tiny" @click="emit('addAudioTrack')" :disabled="audioTrackCount >= maxAudioTracks">
        + 音轨
      </n-button>
      <n-button size="tiny" @click="emit('addText')">+ 字幕</n-button>
    </div>

    <!-- 轨道区（横向滚动） -->
    <div class="timeline__scroll">
      <!-- 时间标尺 -->
      <div class="timeline__ruler-row">
        <div class="timeline__lane-label">时间</div>
        <div class="timeline__ruler" :style="{ width: totalWidth + 'px' }">
          <span v-for="t in rulerTicks" :key="t" class="timeline__tick" :style="{ left: t * pxPerSecond + 'px' }">
            {{ t }}s
          </span>
        </div>
      </div>

      <!-- 每条轨 -->
      <div v-for="track in tracks" :key="track.id" class="timeline__lane-row">
        <div class="timeline__lane-label">
          <span>{{ trackLabel(track) }}</span>
          <n-button
            v-if="track.type !== 'VIDEO'"
            size="tiny"
            quaternary
            type="error"
            class="timeline__lane-del"
            @click="emit('removeTrack', track.id)"
          >✕</n-button>
        </div>
        <div
          class="timeline__lane"
          :class="'timeline__lane--' + track.type.toLowerCase()"
          :style="{ width: totalWidth + 'px' }"
        >
          <!-- 视频/音频段 -->
          <template v-if="track.segments">
            <div
              v-for="(seg, i) in track.segments"
              :key="i"
              class="timeline__block"
              :class="['timeline__block--' + track.type.toLowerCase(), { 'timeline__block--active': isSelected(track.id, i, false) }]"
              :style="blockStyle(seg)"
              @pointerdown="(e) => onBlockDown(e, track, i, false, 'move')"
              @click.stop="selectBlock(track.id, i, false)"
            >
              <span class="timeline__handle timeline__handle--left"
                    @pointerdown.stop="(e) => onBlockDown(e, track, i, false, 'start')"
                    @click.stop></span>
              <span class="timeline__block-text">{{ infoOf(seg.fileId).name }}</span>
              <span class="timeline__block-dur">{{ durText(seg) }}</span>
              <span class="timeline__handle timeline__handle--right"
                    @pointerdown.stop="(e) => onBlockDown(e, track, i, false, 'end')"
                    @click.stop></span>
              <span class="timeline__block-del" @pointerdown.stop @click.stop="emit('removeBlock', { trackId: track.id, index: i, isText: false })">✕</span>
            </div>
          </template>
          <!-- 字幕段 -->
          <template v-if="track.texts">
            <div
              v-for="(tx, i) in track.texts"
              :key="i"
              class="timeline__block timeline__block--text"
              :class="{ 'timeline__block--active': isSelected(track.id, i, true) }"
              :style="blockStyle(tx)"
              @pointerdown="(e) => onBlockDown(e, track, i, true, 'move')"
              @click.stop="selectBlock(track.id, i, true)"
            >
              <span class="timeline__handle timeline__handle--left"
                    @pointerdown.stop="(e) => onBlockDown(e, track, i, true, 'start')"
                    @click.stop></span>
              <span class="timeline__block-text">{{ tx.content || '字幕' }}</span>
              <span class="timeline__handle timeline__handle--right"
                    @pointerdown.stop="(e) => onBlockDown(e, track, i, true, 'end')"
                    @click.stop></span>
              <span class="timeline__block-del" @pointerdown.stop @click.stop="emit('removeBlock', { trackId: track.id, index: i, isText: true })">✕</span>
            </div>
          </template>
          <span v-if="emptyTrack(track)" class="timeline__empty">{{ track.type === 'VIDEO' ? '从素材库加入视频' : '点击 + 添加' }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { NButton, NButtonGroup } from 'naive-ui'
import type { TrackSpec, SegmentSpec, TextSegmentSpec } from '@/api/mediaEdit'
import { useTimelineDrag } from './useTimelineDrag'

const props = defineProps<{
  tracks: TrackSpec[]
  clipInfo: Record<string, { name: string; duration: number | null }>
  maxAudioTracks: number
  selectedKey?: { trackId: string; index: number; isText: boolean } | null
}>()

const emit = defineEmits<{
  select: [payload: { trackId: string; index: number; isText: boolean } | null]
  removeBlock: [payload: { trackId: string; index: number; isText: boolean }]
  removeTrack: [trackId: string]
  addAudioTrack: []
  addText: []
}>()

const pxPerSecond = ref(50)
const drag = useTimelineDrag(pxPerSecond, 0.1)
const selected = ref<{ trackId: string; index: number; isText: boolean } | null>(null)

const audioTrackCount = computed(() => props.tracks.filter(t => t.type === 'AUDIO').length)

const totalSeconds = computed(() => {
  let end = 10
  for (const t of props.tracks) {
    const segs = (t.segments || t.texts || []) as { targetEnd?: number | null }[]
    for (const s of segs) {
      if (s.targetEnd != null) end = Math.max(end, s.targetEnd)
    }
  }
  return Math.ceil(end)
})
const totalWidth = computed(() => totalSeconds.value * pxPerSecond.value)

const rulerTicks = computed(() => {
  const n = totalSeconds.value
  const step = n > 60 ? 10 : n > 20 ? 5 : 1
  const ticks: number[] = []
  for (let i = 0; i <= n; i += step) ticks.push(i)
  return ticks
})

function zoom(d: number) {
  pxPerSecond.value = Math.min(200, Math.max(20, pxPerSecond.value + d))
}

function infoOf(fileId: string) {
  return props.clipInfo[fileId] || { name: '素材', duration: null as number | null }
}

function trackLabel(t: TrackSpec) {
  if (t.type === 'VIDEO') return '视频轨'
  if (t.type === 'TEXT') return '字幕轨'
  return t.name || '音频轨'
}

function emptyTrack(t: TrackSpec) {
  return (!t.segments || t.segments.length === 0) && (!t.texts || t.texts.length === 0)
}

function blockStyle(s: { targetStart: number; targetEnd: number }) {
  return {
    left: s.targetStart * pxPerSecond.value + 'px',
    width: Math.max(10, (s.targetEnd - s.targetStart) * pxPerSecond.value) + 'px'
  }
}

function durText(s: SegmentSpec) {
  return (s.targetEnd - s.targetStart).toFixed(1) + 's'
}

function isSelected(trackId: string, index: number, isText: boolean) {
  const s = selected.value
  return !!s && s.trackId === trackId && s.index === index && s.isText === isText
}

function selectBlock(trackId: string, index: number, isText: boolean) {
  selected.value = { trackId, index, isText }
  emit('select', selected.value)
}

/** pointerdown 在块/手柄上：记录快照（target/trim 原值），开启拖拽。 */
function onBlockDown(
  e: PointerEvent,
  track: TrackSpec,
  index: number,
  isText: boolean,
  kind: 'move' | 'start' | 'end'
) {
  const seg = (isText ? track.texts![index] : track.segments![index]) as SegmentSpec | TextSegmentSpec
  drag.begin(e, {
    trackId: track.id,
    index,
    kind,
    isText,
    origTargetStart: seg.targetStart,
    origTargetEnd: seg.targetEnd,
    origTrimStart: isText ? null : (seg as SegmentSpec).trimStart ?? null,
    origTrimEnd: isText ? null : (seg as SegmentSpec).trimEnd ?? null
  })
}

const MIN_DUR = 0.2

/** pointermove：按拖拽类型把位移（秒）应用到 segment（直接改 reactive tracks）。无变速约束：trim 与 target 同步。 */
function onPointerMove(e: PointerEvent) {
  const st = drag.state.value
  if (!st) return
  const d = drag.deltaSec(e)
  const track = props.tracks.find(t => t.id === st.trackId)
  if (!track) return
  const arr = st.isText ? track.texts : track.segments
  if (!arr) return
  const seg = arr[st.index] as SegmentSpec | TextSegmentSpec
  if (!seg) return

  if (st.kind === 'move') {
    let shift = d
    if (st.origTargetStart + shift < 0) shift = -st.origTargetStart
    seg.targetStart = round1(st.origTargetStart + shift)
    seg.targetEnd = round1(st.origTargetEnd + shift)
  } else if (st.kind === 'start') {
    let delta = d
    if (st.origTargetStart + delta < 0) delta = -st.origTargetStart
    if (st.origTargetEnd - (st.origTargetStart + delta) < MIN_DUR) {
      delta = st.origTargetEnd - st.origTargetStart - MIN_DUR
    }
    if (!st.isText && st.origTrimStart != null && st.origTrimStart + delta < 0) delta = -st.origTrimStart
    seg.targetStart = round1(st.origTargetStart + delta)
    if (!st.isText && st.origTrimStart != null) {
      ;(seg as SegmentSpec).trimStart = round1(Math.max(0, st.origTrimStart + delta))
    }
  } else {
    // end
    let delta = d
    if (st.origTargetEnd + delta - st.origTargetStart < MIN_DUR) {
      delta = st.origTargetStart + MIN_DUR - st.origTargetEnd
    }
    seg.targetEnd = round1(st.origTargetEnd + delta)
    if (!st.isText && st.origTrimEnd != null) {
      ;(seg as SegmentSpec).trimEnd = round1(Math.max(0, st.origTrimEnd + delta))
    }
  }
}

function onPointerUp() {
  drag.finish()
}

function round1(x: number): number {
  return Math.round(x * 10) / 10
}
</script>

<style lang="scss" scoped>
.timeline {
  user-select: none;
  &__toolbar {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 8px;
    flex-wrap: wrap;
  }
  &__total {
    font-size: 12px;
    opacity: 0.7;
  }
  &__scroll {
    overflow-x: auto;
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-radius: 6px;
  }
  &__ruler-row,
  &__lane-row {
    display: flex;
    align-items: stretch;
    min-height: 44px;
  }
  &__ruler-row {
    min-height: 24px;
    border-bottom: 1px solid rgba(255, 255, 255, 0.08);
    position: sticky;
    top: 0;
    background: var(--n-color, #1e1e1e);
    z-index: 2;
  }
  &__lane-label {
    width: 84px;
    flex-shrink: 0;
    padding: 6px 8px;
    font-size: 12px;
    border-right: 1px solid rgba(255, 255, 255, 0.08);
    display: flex;
    align-items: center;
    justify-content: space-between;
    position: sticky;
    left: 0;
    background: var(--n-color, #1e1e1e);
    z-index: 2;
  }
  &__lane-del {
    margin-left: 4px;
  }
  &__ruler {
    position: relative;
    flex-shrink: 0;
    height: 24px;
  }
  &__tick {
    position: absolute;
    top: 4px;
    font-size: 10px;
    opacity: 0.5;
    transform: translateX(-50%);
    white-space: nowrap;
  }
  &__lane {
    position: relative;
    flex-shrink: 0;
    min-height: 44px;
    background: rgba(255, 255, 255, 0.02);
    border-bottom: 1px solid rgba(255, 255, 255, 0.04);
  }
  &__empty {
    position: absolute;
    top: 50%;
    left: 12px;
    transform: translateY(-50%);
    font-size: 11px;
    opacity: 0.35;
  }
  &__block {
    position: absolute;
    top: 4px;
    bottom: 4px;
    min-width: 10px;
    border-radius: 4px;
    padding: 4px 10px;
    display: flex;
    flex-direction: column;
    justify-content: center;
    cursor: grab;
    color: #fff;
    font-size: 11px;
    overflow: hidden;
    touch-action: none;
    &:active {
      cursor: grabbing;
    }
  }
  &__block--video {
    background: rgba(64, 128, 255, 0.55);
    border: 1px solid rgba(64, 128, 255, 0.9);
  }
  &__block--audio {
    background: rgba(80, 200, 120, 0.5);
    border: 1px solid rgba(80, 200, 120, 0.9);
  }
  &__block--text {
    background: rgba(240, 170, 50, 0.5);
    border: 1px solid rgba(240, 170, 50, 0.9);
  }
  &__block--active {
    box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.8);
  }
  &__block-text {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    font-weight: 500;
  }
  &__block-dur {
    font-size: 10px;
    opacity: 0.8;
  }
  &__handle {
    position: absolute;
    top: 0;
    bottom: 0;
    width: 6px;
    cursor: ew-resize;
    background: rgba(255, 255, 255, 0.25);
    &--left {
      left: 0;
      border-radius: 4px 0 0 4px;
    }
    &--right {
      right: 0;
      border-radius: 0 4px 4px 0;
    }
  }
  &__block-del {
    position: absolute;
    top: 2px;
    right: 2px;
    width: 16px;
    height: 16px;
    line-height: 14px;
    text-align: center;
    border-radius: 50%;
    background: rgba(220, 60, 60, 0.9);
    color: #fff;
    font-size: 10px;
    cursor: pointer;
    opacity: 0;
  }
  &__block:hover &__block-del {
    opacity: 1;
  }
}
</style>

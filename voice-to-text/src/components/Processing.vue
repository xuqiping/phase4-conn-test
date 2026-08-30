<script setup lang="ts">
// Step 11 (FR-104~107): 处理区 —— 阶段化进度（抽帧 → OCR → 对齐 → 总结），可取消。
// 取消在「段间」生效：当前阶段在后端跑完才停，已完成阶段保留，可点「继续处理」接着跑。
import { onMounted } from 'vue'
import { useSessionStore, type StageStatus } from '../stores/session'

const store = useSessionStore()

// 停止录制进入 processing 态后自动开跑；重试/继续由按钮手动触发。
onMounted(() => store.runPipeline())

function icon(status: StageStatus): string {
  return status === 'done' ? '✓' : status === 'failed' ? '✗' : status === 'running' ? '…' : '○'
}
</script>

<template>
  <section class="processing" aria-label="录后处理进度">
    <h2 class="title">录后处理</h2>
    <ol class="stages">
      <li
        v-for="st in store.stages"
        :key="st.key"
        class="stage"
        :class="st.status"
        :aria-current="st.status === 'running' ? 'step' : undefined"
      >
        <span class="icon" aria-hidden="true">{{ icon(st.status) }}</span>
        <span class="label">{{ st.label }}</span>
        <span class="detail">{{ st.detail }}</span>
      </li>
    </ol>

    <div class="ops">
      <button
        v-if="store.pipelineRunning"
        class="btn"
        aria-label="取消录后处理"
        @click="store.cancelPipeline()"
      >
        取消
      </button>
      <template v-else>
        <button class="btn" @click="store.runPipeline()">
          {{ store.pipelineCancelled ? '继续处理' : '重试' }}
        </button>
        <button class="link" @click="store.reset()">放弃会话返回</button>
      </template>
    </div>

    <p v-if="store.pipelineCancelled" class="hint" role="status">
      已取消。已完成的阶段会保留，点「继续处理」从下一阶段接着跑。
    </p>
    <p v-if="store.errorMessage" class="error" role="alert">{{ store.errorMessage }}</p>
  </section>
</template>

<style scoped>
.processing {
  background: #161616;
  border: 1px solid #2a2a2a;
  border-radius: 8px;
  padding: 16px 20px;
}
.title {
  font-size: 14px;
  font-weight: 500;
  color: #f0f0f0;
  margin-bottom: 12px;
}
.stages {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.stage {
  display: flex;
  align-items: baseline;
  gap: 10px;
  font-size: 13px;
  color: #888;
}
.stage .icon {
  width: 16px;
  text-align: center;
  flex-shrink: 0;
}
.stage.running {
  color: #fbbf24;
}
.stage.done {
  color: #4ade80;
}
.stage.failed {
  color: #f87171;
}
.stage .detail {
  color: #666;
  font-size: 12px;
}
.stage.done .detail,
.stage.failed .detail {
  color: inherit;
  opacity: 0.75;
}
.ops {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 14px;
}
.btn {
  padding: 7px 18px;
  font-size: 13px;
  background: #2563eb;
  color: #fff;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}
.btn:hover {
  background: #1d4ed8;
}
.btn:focus-visible,
.link:focus-visible {
  outline: 2px solid #60a5fa;
  outline-offset: 1px;
}
.link {
  background: none;
  border: none;
  color: #60a5fa;
  cursor: pointer;
  font-size: 13px;
  text-decoration: underline;
}
.hint {
  margin-top: 10px;
  font-size: 12px;
  color: #fbbf24;
}
.error {
  margin-top: 10px;
  font-size: 13px;
  color: #f87171;
}
</style>

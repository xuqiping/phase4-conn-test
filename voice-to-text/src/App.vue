<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { invoke } from '@tauri-apps/api/core'
import Controls from './components/Controls.vue'
import Transcription from './components/Transcription.vue'
import Recorder from './components/Recorder.vue'
import SummarySettings from './components/SummarySettings.vue'
import Processing from './components/Processing.vue'
import Study from './components/Study.vue'
import SummaryPanel from './components/SummaryPanel.vue'
import RegionSelect from './components/RegionSelect.vue'
import { useSessionStore } from './stores/session'

// Step 10: 功能入口 feature flag —— 纯录音转文字（默认）与网课录屏总结并存。
const mode = ref<'transcribe' | 'course'>('transcribe')

// Step 12: 运维开关 —— 后端 feature_flags.json / 环境变量可关停「网课总结」入口，
// 出线上问题不必回滚发版。拉取失败按默认开处理，不阻塞主功能。
const courseEnabled = ref(true)
onMounted(async () => {
  try {
    const flags = await invoke<{ course_summary: boolean }>('get_feature_flags')
    courseEnabled.value = flags.course_summary
    if (!flags.course_summary && mode.value === 'course') {
      mode.value = 'transcribe'
    }
  } catch {
    /* 默认开 */
  }
})

// Step 11: 三区串联 —— 录制(Recorder) → 处理(Processing) → 学习(Study + SummaryPanel)。
const session = useSessionStore()
const selectedChapter = ref(0)
</script>

<template>
  <div class="app">
    <header class="header">
      <h1>{{ mode === 'transcribe' ? '实时语音转文字' : '网课录屏总结' }}</h1>
      <nav class="tabs" aria-label="功能切换">
        <button
          class="tab"
          :class="{ active: mode === 'transcribe' }"
          :aria-pressed="mode === 'transcribe'"
          @click="mode = 'transcribe'"
        >
          实时转写
        </button>
        <button
          v-if="courseEnabled"
          class="tab"
          :class="{ active: mode === 'course' }"
          :aria-pressed="mode === 'course'"
          @click="mode = 'course'"
        >
          网课总结
        </button>
      </nav>
    </header>
    <template v-if="mode === 'transcribe'">
      <main class="main">
        <Transcription />
      </main>
      <footer class="footer">
        <Controls />
      </footer>
    </template>
    <template v-else-if="courseEnabled">
      <main class="main main-course">
        <Recorder />
        <Processing v-if="session.phase === 'processing'" />
        <template v-if="session.phase === 'done'">
          <Study
            :chapter="selectedChapter"
            @update:chapter="selectedChapter = $event"
          />
          <SummaryPanel :selected-chapter="selectedChapter" />
        </template>
        <SummarySettings />
      </main>
    </template>
    <RegionSelect v-if="session.regionSelectMode" />
  </div>
</template>

<style>
* {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}
html,
body,
#app {
  height: 100%;
}
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft YaHei',
    sans-serif;
  background: #1a1a1a;
  color: #e0e0e0;
  -webkit-font-smoothing: antialiased;
}
.app {
  display: flex;
  flex-direction: column;
  height: 100vh;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 24px;
  border-bottom: 1px solid #2a2a2a;
}
.header h1 {
  font-size: 16px;
  font-weight: 500;
  color: #f0f0f0;
}
.tabs {
  display: flex;
  gap: 4px;
}
.tab {
  padding: 6px 14px;
  font-size: 13px;
  background: transparent;
  color: #888;
  border: 1px solid #333;
  border-radius: 6px;
  cursor: pointer;
}
.tab:hover {
  color: #ccc;
}
.tab.active {
  background: #2563eb;
  border-color: #2563eb;
  color: #fff;
}
.tab:focus-visible {
  outline: 2px solid #60a5fa;
  outline-offset: 1px;
}
.main {
  flex: 1;
  overflow: hidden;
  min-height: 0;
}
.main-course {
  padding: 14px 24px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.footer {
  padding: 14px 24px;
  border-top: 1px solid #2a2a2a;
  background: #161616;
}
</style>

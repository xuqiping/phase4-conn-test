<script setup lang="ts">
import Controls from './components/Controls.vue'
import Transcription from './components/Transcription.vue'
</script>

<template>
  <div class="app">
    <header class="header">
      <h1>实时语音转文字</h1>
    </header>
    <main class="main">
      <Transcription />
    </main>
    <footer class="footer">
      <Controls />
    </footer>
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
  padding: 14px 24px;
  border-bottom: 1px solid #2a2a2a;
}
.header h1 {
  font-size: 16px;
  font-weight: 500;
  color: #f0f0f0;
}
.main {
  flex: 1;
  overflow: hidden;
  min-height: 0;
}
.footer {
  padding: 14px 24px;
  border-top: 1px solid #2a2a2a;
  background: #161616;
}
</style>

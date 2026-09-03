<template>
  <main class="floating-ball-host">
    <button
      data-test="floating-ball-trigger"
      class="floating-ball"
      type="button"
      data-tauri-drag-region
      :aria-label="t('settings.floatingBallOpen')"
      @click.stop="activate"
      @keydown.enter.prevent="activate"
      @contextmenu.prevent.stop="openContextMenu"
    >
      <span aria-hidden="true">FK</span>
    </button>

  </main>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import type { UnlistenFn } from '@tauri-apps/api/event'
import { useI18n } from '../composables/useI18n'
import {
  listenFloatingBallMoved,
  reportFloatingBallPosition,
  restoreMainWindow,
  showFloatingBallMenu
} from '../api/floatingBall'

const { t } = useI18n()
let movedUnlisten: UnlistenFn | null = null

async function activate() {
  await restoreMainWindow()
}

async function openContextMenu() {
  await showFloatingBallMenu({
    open: t('settings.floatingBallOpen'),
    tray: t('settings.floatingBallTray'),
    exit: t('settings.floatingBallExit')
  })
}

onMounted(async () => {
  movedUnlisten = await listenFloatingBallMoved((position) => {
    void reportFloatingBallPosition(position)
  })
})

onBeforeUnmount(() => {
  movedUnlisten?.()
  movedUnlisten = null
})
</script>

<style scoped>
.floating-ball-host {
  width: 100vw;
  height: 100vh;
  overflow: visible;
  background: transparent;
}

.floating-ball {
  display: grid;
  width: 58px;
  height: 58px;
  place-items: center;
  border: 1px solid rgb(255 255 255 / 35%);
  border-radius: 9999px;
  background: linear-gradient(145deg, #45c58a, #25875e);
  box-shadow: 0 8px 24px rgb(0 0 0 / 35%);
  color: white;
  font: 700 15px/1 system-ui, sans-serif;
  cursor: grab;
  user-select: none;
}

.floating-ball:active { cursor: grabbing; }
.floating-ball:focus-visible { outline: 3px solid #d8fff0; outline-offset: 2px; }

</style>

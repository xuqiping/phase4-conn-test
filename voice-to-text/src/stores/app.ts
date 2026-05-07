import { defineStore } from 'pinia'
import { ref, onMounted } from 'vue'
import { listen } from '@tauri-apps/api/event'
import { invoke } from '@tauri-apps/api/core'

interface TranscriptionEvent {
  text: string
  is_final: boolean
  partial: string
}

export const useAppStore = defineStore('app', () => {
  const recording = ref(false)
  const transcript = ref('')
  const partial = ref('')
  const saveAudio = ref(false)
  const statusMessage = ref('')
  const errorMessage = ref('')
  const devices = ref<string[]>([])
  const selectedDevice = ref('')

  let stopListen: (() => void) | null = null

  async function loadDevices() {
    try {
      devices.value = await invoke<string[]>('list_audio_devices')
      if (devices.value.length > 0 && !selectedDevice.value) {
        selectedDevice.value = devices.value[0]
      }
    } catch (e) {
      console.error('Failed to load devices:', e)
    }
  }

  onMounted(() => {
    loadDevices()
  })

  async function start() {
    if (stopListen) stopListen()

    try {
      errorMessage.value = ''
      await invoke('start_recording', {
        saveAudio: saveAudio.value,
        deviceName: selectedDevice.value || null,
      })

      stopListen = await listen<TranscriptionEvent>('transcription', (e) => {
        const payload = e.payload
        if (payload.is_final && payload.text) {
          transcript.value += payload.text
        }
        partial.value = payload.partial
      })

      recording.value = true
      statusMessage.value = '正在录音'
    } catch (err: any) {
      errorMessage.value = err.toString()
      console.error(err)
    }
  }

  async function stop() {
    try {
      await invoke('stop_recording')
    } catch (e) {
      console.error(e)
    }
    if (stopListen) {
      stopListen()
      stopListen = null
    }
    recording.value = false
    statusMessage.value = ''
  }

  function reset() {
    transcript.value = ''
    partial.value = ''
  }

  return {
    recording,
    transcript,
    partial,
    saveAudio,
    statusMessage,
    errorMessage,
    devices,
    selectedDevice,
    loadDevices,
    start,
    stop,
    reset
  }
})

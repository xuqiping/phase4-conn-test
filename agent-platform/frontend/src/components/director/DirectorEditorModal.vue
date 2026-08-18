<template>
  <div class="director-modal-mask">
    <div v-if="webglFailed" class="director-webgl-fallback">
      <n-alert title="无法打开导演台" type="error" :closable="false">
        当前浏览器不支持 WebGL（或 WebGL 被禁用），3D 构图编辑器无法启动。画布其他功能不受影响，可换用
        Chrome / Edge 等现代浏览器后重试。
      </n-alert>
      <n-button class="director-fallback-close" @click="emit('close', null)">关闭</n-button>
    </div>
    <template v-else>
      <header class="director-modal-topbar">
        <div class="director-topbar-left">
          <span class="director-topbar-title">导演台</span>
          <span class="director-topbar-count">{{ scene.elements.length }} 元素 · {{ scene.cameras.length }} 机位</span>
        </div>
        <div class="director-topbar-actions">
          <n-button size="small" quaternary :disabled="!undoStack.canUndo" @click="undo">撤销</n-button>
          <n-button size="small" quaternary :disabled="!undoStack.canRedo" @click="redo">重做</n-button>
          <span class="director-topbar-hint">Ctrl+Z 撤销 · Ctrl+Shift+Z 重做</span>
          <n-button size="small" type="primary" @click="closeWithScene">完成并关闭</n-button>
        </div>
      </header>
      <div class="director-modal-body">
        <DirectorViewport ref="viewportRef" :scene-data="scene" @director-pose="onDirectorPose" />
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
/**
 * 导演台全屏编辑器壳（由 CanvasView defineAsyncComponent 懒加载，three chunk 不进主包）。
 *
 * 职责：WebGL 探测失败降级 / undo 栈与快捷键 / 场景暂存（close 回传 node.data）/ 卸载释放共享缓存。
 * 左右栏（元素工具栏/机位面板）Step 4/5 落进 body 布局。
 */
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { NAlert, NButton } from 'naive-ui';
import DirectorViewport from './DirectorViewport.vue';
import { disposeSceneAssets } from '../../director/buildScene';
import {
  UndoStack,
  cloneScene,
  type DirectorSceneData,
} from '../../director/sceneModel';

const props = defineProps<{
  initialScene: DirectorSceneData;
}>();

const emit = defineEmits<{
  (e: 'close', scene: DirectorSceneData | null): void;
}>();

const webglFailed = ref(false);
const viewportRef = ref<InstanceType<typeof DirectorViewport> | null>(null);
const scene = reactive(cloneScene(props.initialScene));
const undoStack = new UndoStack(props.initialScene);

function detectWebGL(): boolean {
  try {
    const canvas = document.createElement('canvas');
    return !!(canvas.getContext('webgl2') || canvas.getContext('webgl'));
  } catch {
    return false;
  }
}

function onDirectorPose(): void {
  // 位姿消费方（Step 5「从当前视角新增机位」）落地时在此暂存
}

function undo(): void {
  const prev = undoStack.undo();
  if (prev) Object.assign(scene, prev);
}

function redo(): void {
  const next = undoStack.redo();
  if (next) Object.assign(scene, next);
}

function onKeyDown(e: KeyboardEvent): void {
  if (!(e.ctrlKey || e.metaKey) || e.key.toLowerCase() !== 'z') return;
  e.preventDefault();
  if (e.shiftKey) redo();
  else undo();
}

function closeWithScene(): void {
  emit('close', cloneScene(scene));
}

onMounted(() => {
  webglFailed.value = !detectWebGL();
  window.addEventListener('keydown', onKeyDown);
});

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeyDown);
  disposeSceneAssets();
});
</script>

<style scoped lang="scss" src="../../../styles/director.scss" />

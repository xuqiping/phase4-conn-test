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
        <DirectorToolbar
          ref="toolbarRef"
          :scene="scene"
          :selected-id="selectedId"
          @add="onAdd"
          @select="onSelect"
          @update-element="onUpdateElement"
          @delete-element="onDeleteElement"
          @duplicate-element="onDuplicateElement"
        />
        <DirectorViewport
          ref="viewportRef"
          :scene-data="scene"
          :selected-element-id="selectedId"
          :transform-mode="transformMode"
          @pick="onPick"
          @transform-end="onTransformEnd"
        />
        <DirectorProperties
          :scene="scene"
          :selected-id="selectedId"
          :transform-mode="transformMode"
          @update-element="onUpdateElement"
          @set-transform-mode="transformMode = $event"
        />
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
import DirectorToolbar from './DirectorToolbar.vue';
import DirectorProperties from './DirectorProperties.vue';
import DirectorViewport from './DirectorViewport.vue';
import { disposeSceneAssets } from '../../director/buildScene';
import {
  NAME_MAX,
  UndoStack,
  cloneScene,
  createElement,
  genId,
  isValidHexColor,
  type DirectorElement,
  type DirectorSceneData,
  type ElementKind,
  type ElementTransform,
} from '../../director/sceneModel';

const props = defineProps<{
  initialScene: DirectorSceneData;
}>();

const emit = defineEmits<{
  (e: 'close', scene: DirectorSceneData | null): void;
}>();

const webglFailed = ref(false);
const viewportRef = ref<InstanceType<typeof DirectorViewport> | null>(null);
const toolbarRef = ref<InstanceType<typeof DirectorToolbar> | null>(null);
const scene = reactive(cloneScene(props.initialScene));
const undoStack = new UndoStack(props.initialScene);
const selectedId = ref<string | null>(null);
const transformMode = ref<'translate' | 'rotate' | 'scale'>('translate');

function detectWebGL(): boolean {
  try {
    const canvas = document.createElement('canvas');
    return !!(canvas.getContext('webgl2') || canvas.getContext('webgl'));
  } catch {
    return false;
  }
}

/** 交互结束统一推栈（gizmo 拖完 / 增删改 / 姿势体型切换等离散操作） */
function commitHistory(): void {
  undoStack.push(cloneScene(scene));
}

/** 文本类输入（名称/颜色 HEX）防逐键推栈：500ms 防抖合并 */
let textCommitTimer = 0;
function commitHistoryDebounced(): void {
  if (textCommitTimer) window.clearTimeout(textCommitTimer);
  textCommitTimer = window.setTimeout(() => commitHistory(), 500);
}

function undo(): void {
  const prev = undoStack.undo();
  if (prev) {
    Object.assign(scene, prev);
    if (selectedId.value && !scene.elements.some((el) => el.id === selectedId.value)) {
      selectedId.value = null;
    }
  }
}

function redo(): void {
  const next = undoStack.redo();
  if (next) {
    Object.assign(scene, next);
    if (selectedId.value && !scene.elements.some((el) => el.id === selectedId.value)) {
      selectedId.value = null;
    }
  }
}

function onSelect(id: string | null): void {
  selectedId.value = id;
  if (id) toolbarRef.value?.scrollToSelected();
}

function onPick(id: string | null): void {
  onSelect(id);
}

function onAdd(kind: ElementKind, overrides?: Partial<DirectorElement>): void {
  const el = createElement(kind, scene.elements.length, overrides);
  scene.elements.push(el);
  selectedId.value = el.id;
  commitHistory();
}

function onUpdateElement(id: string, patch: Partial<DirectorElement>): void {
  const el = scene.elements.find((e) => e.id === id);
  if (!el) return;
  if (patch.name !== undefined) patch.name = patch.name.trim().slice(0, NAME_MAX) || el.name;
  if (patch.color !== undefined && !isValidHexColor(patch.color)) delete patch.color;
  Object.assign(el, patch);
  commitHistoryDebounced();
}

function onDeleteElement(id: string): void {
  const i = scene.elements.findIndex((e) => e.id === id);
  if (i >= 0) scene.elements.splice(i, 1);
  if (selectedId.value === id) selectedId.value = null;
  commitHistory();
}

function onDuplicateElement(id: string): void {
  const src = scene.elements.find((e) => e.id === id);
  if (!src || scene.elements.length >= 200) return;
  const copy: DirectorElement = {
    ...structuredClone(src),
    id: genId('el'),
    name: `${src.name} 副本`.slice(0, NAME_MAX),
    transform: {
      ...structuredClone(src.transform),
      position: [src.transform.position[0] + 1, src.transform.position[1], src.transform.position[2] + 1],
    },
  };
  scene.elements.push(copy);
  selectedId.value = copy.id;
  commitHistory();
}

function onTransformEnd({ id, transform }: { id: string; transform: ElementTransform }): void {
  const el = scene.elements.find((e) => e.id === id);
  if (!el) return;
  el.transform = transform;
  commitHistory();
}

function onKeyDown(e: KeyboardEvent): void {
  const target = e.target as HTMLElement | null;
  const typing = target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable);
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') {
    e.preventDefault();
    if (e.shiftKey) redo();
    else undo();
    return;
  }
  if (typing || e.ctrlKey || e.metaKey || e.altKey) return;
  const key = e.key.toLowerCase();
  if (key === 'v') transformMode.value = 'translate';
  else if (key === 'r') transformMode.value = 'rotate';
  else if (key === 's') transformMode.value = 'scale';
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
  if (textCommitTimer) window.clearTimeout(textCommitTimer);
  disposeSceneAssets();
});
</script>

<style scoped lang="scss" src="../../styles/director.scss" />

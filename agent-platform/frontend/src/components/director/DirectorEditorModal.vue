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
          :active-camera-id="activeCameraId"
          @pick="onPick"
          @pick-camera="onPickCamera"
          @transform-end="onTransformEnd"
        />
        <div class="director-right-panel">
          <n-tabs type="segment" size="small" default-value="element">
            <n-tab-pane name="element" tab="元素">
              <DirectorProperties
                :scene="scene"
                :selected-id="selectedId"
                :transform-mode="transformMode"
                @update-element="onUpdateElement"
                @set-transform-mode="transformMode = $event"
              />
            </n-tab-pane>
            <n-tab-pane name="camera" tab="机位">
              <DirectorCameraPanel
                :scene="scene"
                :selected-camera-id="selectedCameraId"
                :active-camera-id="activeCameraId"
                @add-from-view="onAddCameraFromView"
                @update-camera="onUpdateCamera"
                @delete-camera="onDeleteCamera"
                @view-camera="onViewCamera"
                @select-camera="selectedCameraId = $event"
              />
            </n-tab-pane>
          </n-tabs>
        </div>
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
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { NAlert, NButton, NTabPane, NTabs } from 'naive-ui';
import DirectorToolbar from './DirectorToolbar.vue';
import DirectorProperties from './DirectorProperties.vue';
import DirectorCameraPanel from './DirectorCameraPanel.vue';
import DirectorViewport from './DirectorViewport.vue';
import { disposeSceneAssets } from '../../director/buildScene';
import {
  CAMERA_NAME_MAX,
  NAME_MAX,
  UndoStack,
  cloneScene,
  createCamera,
  createElement,
  genId,
  isValidHexColor,
  MAX_CAMERAS,
  type DirectorCameraData,
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
  if (id) selectedCameraId.value = null; // 元素/机位两选中域互斥
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

// ---------- 相机系统（Step 5） ----------

const selectedCameraId = ref<string | null>(null);
const activeCameraId = ref<string | null>(null);

function onAddCameraFromView(): void {
  if (scene.cameras.length >= MAX_CAMERAS) return;
  const pose = viewportRef.value?.getDirectorPose();
  const cam = createCamera(pose?.position ?? [6, 4, 8], pose?.target ?? [0, 1, 0], scene.cameras.length);
  scene.cameras.push(cam);
  selectedCameraId.value = cam.id;
  commitHistory();
}

function onUpdateCamera(id: string, patch: Partial<DirectorCameraData>): void {
  const cam = scene.cameras.find((c) => c.id === id);
  if (!cam) return;
  if (patch.name !== undefined) patch.name = patch.name.trim().slice(0, CAMERA_NAME_MAX) || cam.name;
  Object.assign(cam, patch);
  commitHistoryDebounced();
}

function onDeleteCamera(id: string): void {
  const i = scene.cameras.findIndex((c) => c.id === id);
  if (i >= 0) scene.cameras.splice(i, 1);
  if (selectedCameraId.value === id) selectedCameraId.value = null;
  // L4：删除当前所在机位视角 → 自动回导演视角
  if (activeCameraId.value === id) activeCameraId.value = null;
  commitHistory();
}

function onViewCamera(id: string | null): void {
  activeCameraId.value = id;
}

function onPickCamera(id: string | null): void {
  selectedCameraId.value = id;
  if (id === null) return;
  // 视锥点选 → 元素选中态清除（两个选中域互斥）
  if (selectedId.value) selectedId.value = null;
}

// undo/redo 或外部变化移除当前查看的机位 → 回导演视角（L4 兜底）
watch(
  () => scene.cameras.map((c) => c.id).join(','),
  (ids) => {
    if (activeCameraId.value && !ids.split(',').includes(activeCameraId.value)) {
      activeCameraId.value = null;
    }
    if (selectedCameraId.value && !ids.split(',').includes(selectedCameraId.value)) {
      selectedCameraId.value = null;
    }
  },
);

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

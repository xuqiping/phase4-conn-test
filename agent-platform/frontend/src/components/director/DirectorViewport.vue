<template>
  <div ref="containerRef" class="director-viewport">
    <!-- 机位视角遮幅框：框外压暗，框内=该机位画幅（WYSIWYG，截图同比例） -->
    <div
      v-if="activeCameraId && letterbox"
      class="director-letterbox"
      :style="{
        left: `${letterbox.x}px`,
        top: `${letterbox.y}px`,
        width: `${letterbox.width}px`,
        height: `${letterbox.height}px`,
      }"
    >
      <span class="director-letterbox-tag">{{ activeAspectLabel }}</span>
    </div>
    <div v-if="activeCameraId" class="director-viewmode-tip">机位视角（只读）· 点「导演视角」返回编辑</div>
  </div>
</template>

<script setup lang="ts">
/**
 * three 渲染视口：场景组装/导演相机/OrbitControls/按需渲染/尺寸跟随/全量 dispose。
 *
 * 渲染纪律（spec §6）：
 * - invalidate 按需渲染——静止 2s 停 rAF（防常驻空转耗电）
 * - pixelRatio 封顶 min(dpr, 2)
 * - 卸载 = disposeScene(traverse) + renderer.dispose + forceContextLoss + 移除 canvas
 * - 共享缓存 disposeSceneAssets 由 modal 卸载时统一调（多视口共用缓存）
 *
 * 数据同步：watch sceneData 按 id diff 增删改（同 kind/color/figure/crowd 复用既有 Group 只刷 transform），
 * 拖 gizmo 期间不改 sceneData（transform-end 才回写），避免拖动每帧全量 diff。
 */
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { TransformControls } from 'three/examples/jsm/controls/TransformControls.js';
import {
  applyTransform,
  buildElementObject,
  buildScene,
  disposeScene,
  rebuildElementObject,
} from '../../director/buildScene';
import {
  ASPECT_RATIOS,
  POS_CLAMP,
  SCALE_MAX,
  SCALE_MIN,
  letterboxRect,
  type DirectorCameraData,
  type DirectorSceneData,
  type ElementTransform,
  type Vec3,
} from '../../director/sceneModel';
import { buildCameraFrustum, disposeCameraFrustum } from './cameraFrustum';

const props = defineProps<{
  sceneData: DirectorSceneData;
  selectedElementId: string | null;
  transformMode: 'translate' | 'rotate' | 'scale';
  /** null=导演视角；有值=该机位视角渲染（只读+遮幅） */
  activeCameraId: string | null;
}>();

const emit = defineEmits<{
  (e: 'director-pose', pose: { position: Vec3; target: Vec3 }): void;
  (e: 'pick', elementId: string | null): void;
  (e: 'pick-camera', cameraId: string | null): void;
  (e: 'transform-end', payload: { id: string; transform: ElementTransform }): void;
}>();

const containerRef = ref<HTMLDivElement | null>(null);

// ---------- 机位视角状态 ----------

const letterbox = ref<{ x: number; y: number; width: number; height: number } | null>(null);
const activeAspectLabel = ref('');
/** cameraId → 渲染用 PerspectiveCamera（position/target/fov 随 sceneData 同步） */
const cameraObjs = new Map<string, THREE.PerspectiveCamera>();
/** 导演视角下的视锥可视化组 */
let frustumGroup: THREE.Group | null = null;

const activeCamera = (): DirectorCameraData | null =>
  props.sceneData.cameras.find((c) => c.id === props.activeCameraId) ?? null;

function syncRenderCamera(): void {
  const cam = activeCamera();
  if (!cam || !renderer) return;
  let obj = cameraObjs.get(cam.id);
  if (!obj) {
    obj = new THREE.PerspectiveCamera(cam.fov, 16 / 9, 0.1, 500);
    cameraObjs.set(cam.id, obj);
  }
  obj.position.set(cam.position[0], cam.position[1], cam.position[2]);
  obj.lookAt(cam.target[0], cam.target[1], cam.target[2]);
  obj.fov = cam.fov;
}

/** 机位视角遮幅：scissor 只渲框内 + 相机 aspect=画幅（真 WYSIWYG） */
function applyViewFrame(): void {
  const el = containerRef.value;
  if (!el || !renderer) return;
  const w = Math.max(el.clientWidth, 1);
  const h = Math.max(el.clientHeight, 1);
  const cam = activeCamera();
  if (cam && props.activeCameraId) {
    const rect = letterboxRect(w, h, cam.aspect);
    const [rw, rh] = ASPECT_RATIOS[cam.aspect];
    const obj = cameraObjs.get(cam.id);
    if (obj) {
      obj.aspect = rw / rh;
      obj.updateProjectionMatrix();
    }
    // three 视口原点在左下，letterbox 计算原点在左上 → y 翻转
    const yBottom = h - rect.y - rect.height;
    renderer.setScissorTest(true);
    renderer.setViewport(rect.x, yBottom, rect.width, rect.height);
    renderer.setScissor(rect.x, yBottom, rect.width, rect.height);
    letterbox.value = rect;
    activeAspectLabel.value = `${cam.aspect} · FOV ${cam.fov}`;
  } else {
    renderer.setScissorTest(false);
    renderer.setViewport(0, 0, w, h);
    letterbox.value = null;
  }
}

/** 机位数据变化 → 重建视锥 + 同步渲染相机（机位未变时只刷渲染相机，防逐帧重建视锥） */
let camerasSig = '';
function syncCameras(): void {
  const root = rootGroup;
  if (!scene || !root) return;
  const sig = JSON.stringify(props.sceneData.cameras);
  if (sig !== camerasSig) {
    camerasSig = sig;
    if (frustumGroup) {
      root.remove(frustumGroup);
      disposeCameraFrustum(frustumGroup);
      frustumGroup = null;
    }
    if (props.sceneData.cameras.length > 0) {
      frustumGroup = new THREE.Group();
      frustumGroup.name = 'camera-frustums';
      // 导演视角才显示视锥；机位视角隐藏（自己的视锥无意义）
      frustumGroup.visible = !props.activeCameraId;
      props.sceneData.cameras.forEach((cam) => frustumGroup!.add(buildCameraFrustum(cam)));
      root.add(frustumGroup);
    }
  } else if (frustumGroup) {
    frustumGroup.visible = !props.activeCameraId;
  }
  syncRenderCamera();
  applyViewFrame();
  invalidate();
}

let renderer: THREE.WebGLRenderer | null = null;
let scene: THREE.Scene | null = null;
let camera: THREE.PerspectiveCamera | null = null;
let controls: OrbitControls | null = null;
let transformControls: TransformControls | null = null;
let resizeObserver: ResizeObserver | null = null;

// 按需渲染循环状态
let needsRender = false;
let rafId = 0;
let lastActiveAt = 0;
const IDLE_STOP_MS = 2000;

function invalidate(): void {
  needsRender = true;
  if (!rafId && renderer) rafId = requestAnimationFrame(renderLoop);
}

/** 本帧渲染用相机：机位视角=该机位相机；导演视角=环视相机 */
function renderCamera(): THREE.PerspectiveCamera {
  const cam = activeCamera();
  if (cam && props.activeCameraId) {
    const obj = cameraObjs.get(cam.id);
    if (obj) return obj;
  }
  return camera!;
}

function renderLoop(t: number): void {
  rafId = requestAnimationFrame(renderLoop);
  controls?.update();
  if (needsRender && renderer && scene && camera) {
    if (props.activeCameraId) {
      // 遮幅外区域须先整帧清屏（scissor 只清框内，不清会留残影）
      renderer.setScissorTest(false);
      renderer.setViewport(0, 0, renderer.domElement.width, renderer.domElement.height);
      renderer.clear();
      applyViewFrame();
      renderer.render(scene, renderCamera());
    } else {
      renderer.setScissorTest(false);
      renderer.render(scene, camera);
    }
    needsRender = false;
    lastActiveAt = t;
  }
  if (t - lastActiveAt > IDLE_STOP_MS && !needsRender) {
    cancelAnimationFrame(rafId);
    rafId = 0;
  }
}

function onResize(): void {
  const el = containerRef.value;
  if (!el || !renderer || !camera) return;
  const w = Math.max(el.clientWidth, 1);
  const h = Math.max(el.clientHeight, 1);
  renderer.setSize(w, h, false);
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
  applyViewFrame();
  invalidate();
}

// ---------- 场景数据 diff 同步 ----------

let built = new Map<string, THREE.Group>();
let rootGroup: THREE.Group | null = null;

function resync(): void {
  const root = rootGroup;
  if (!scene || !root) return;
  const data = props.sceneData;
  const next = new Map<string, THREE.Group>();
  data.elements.forEach((el: DirectorSceneData['elements'][number]) => {
    const existing = built.get(el.id);
    if (existing) {
      // 复用：刷变换/可见性；颜色/姿势/阵列参数变更则重建
      next.set(el.id, rebuildIfChanged(existing, el));
    } else {
      const g = buildElementObject(el);
      root.add(g);
      next.set(el.id, g);
    }
  });
  // 删除的元素：移出场景树并释放
  built.forEach((g, id) => {
    if (!next.has(id)) {
      root.remove(g);
      disposeScene(g);
    }
  });
  built = next;
  // 元素对象被重建（改色/姿势/阵列）时 gizmo 换绑新 Group
  if (transformControls && props.selectedElementId) {
    const g = built.get(props.selectedElementId);
    if (g && transformControls.object !== g) transformControls.attach(g);
  }
  // 遮幅外清屏色随场景背景联动
  if (renderer) renderer.setClearColor(props.sceneData.ground.backgroundColor);
  syncCameras();
}

function rebuildIfChanged(existing: THREE.Group, el: DirectorSceneData['elements'][number]): THREE.Group {
  const root = rootGroup!;
  applyTransform(existing, el.transform);
  existing.visible = !el.hidden;
  const sig = elementSignature(el);
  if (existing.userData.sig !== sig) {
    const fresh = rebuildElementObject(existing, el);
    fresh.userData.sig = sig;
    fresh.userData.elementId = el.id;
    root.add(fresh);
    root.remove(existing);
    disposeScene(existing);
    return fresh;
  }
  return existing;
}

function elementSignature(el: DirectorSceneData['elements'][number]): string {
  return JSON.stringify([el.kind, el.color, el.figure ?? null, el.crowd ?? null]);
}

watch(
  () => props.sceneData,
  () => resync(),
  { deep: true },
);

// ---------- 生命周期 ----------

onMounted(() => {
  const el = containerRef.value;
  if (!el) return;
  renderer = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
  renderer.domElement.style.width = '100%';
  renderer.domElement.style.height = '100%';
  renderer.domElement.style.display = 'block';
  el.appendChild(renderer.domElement);

  scene = new THREE.Scene();
  const { root, elementMap } = buildScene(props.sceneData);
  rootGroup = root;
  built = elementMap;
  built.forEach((g, id) => {
    const el2 = props.sceneData.elements.find((e: DirectorSceneData['elements'][number]) => e.id === id);
    if (el2) g.userData.sig = elementSignature(el2);
  });
  scene.add(root);
  scene.background = new THREE.Color(props.sceneData.ground.backgroundColor);

  // 柔和天光 + 无阴影平行光（全图元无纹理，性能优先）
  scene.add(new THREE.HemisphereLight(0xdfe8f5, 0x3a4150, 1.0));
  const dir = new THREE.DirectionalLight(0xffffff, 1.2);
  dir.position.set(6, 10, 4);
  scene.add(dir);

  camera = new THREE.PerspectiveCamera(50, 16 / 9, 0.1, 500);
  camera.position.set(7, 5, 9);
  camera.lookAt(0, 1, 0);

  controls = new OrbitControls(camera, renderer.domElement);
  controls.target.set(0, 1, 0);
  controls.enableDamping = true;
  controls.addEventListener('change', () => {
    invalidate();
    emitDirectorPose();
  });

  // gizmo：拖拽期禁 orbit（两控制器抢事件是已知坑）；松手即回写 transform（undo 只在这一刻推栈）
  transformControls = new TransformControls(camera, renderer.domElement);
  transformControls.setMode(props.transformMode);
  // r169 起 TransformControls 不再是 Object3D，须加 helper 进场景
  scene.add(transformControls.getHelper());
  transformControls.addEventListener('change', () => invalidate());
  transformControls.addEventListener('dragging-changed', (e) => {
    const dragging = (e as unknown as { value: boolean }).value;
    if (controls) controls.enabled = !dragging;
    if (!dragging) emitTransformEnd();
    invalidate();
  });
  if (props.selectedElementId) {
    const g = built.get(props.selectedElementId);
    if (g) transformControls.attach(g);
  }
  window.addEventListener('keydown', onShiftKey);
  window.addEventListener('keyup', onShiftKey);
  renderer.domElement.addEventListener('pointerdown', onPointerDown);
  renderer.domElement.addEventListener('pointerup', onPointerUp);

  syncCameras();
  resizeObserver = new ResizeObserver(onResize);
  resizeObserver.observe(el);
  onResize();
  invalidate();
});

/** 缩放模式按住 Shift：只留中心手柄=等比缩放（spec §4.2 Shift 等比） */
function onShiftKey(e: KeyboardEvent): void {
  if (!transformControls || props.transformMode !== 'scale') return;
  const uniform = e.type === 'keydown' && e.shiftKey;
  transformControls.showX = !uniform;
  transformControls.showY = !uniform;
  transformControls.showZ = !uniform;
  invalidate();
}

// ---------- 视口点选（raycast → userData.elementId 反查） ----------

let downX = 0;
let downY = 0;

function onPointerDown(e: PointerEvent): void {
  downX = e.clientX;
  downY = e.clientY;
}

function onPointerUp(e: PointerEvent): void {
  if (!camera || !rootGroup || !renderer) return;
  // 机位视角只读，不处理点选
  if (props.activeCameraId) return;
  // 悬停/拖拽 gizmo 时不处理点选；位移超 5px 视为环视拖拽
  if (transformControls?.axis || transformControls?.dragging) return;
  if (Math.abs(e.clientX - downX) > 5 || Math.abs(e.clientY - downY) > 5) return;
  const rect = renderer.domElement.getBoundingClientRect();
  const ndc = new THREE.Vector2(
    ((e.clientX - rect.left) / rect.width) * 2 - 1,
    -((e.clientY - rect.top) / rect.height) * 2 + 1,
  );
  const raycaster = new THREE.Raycaster();
  raycaster.params.Line.threshold = 0.2;
  raycaster.setFromCamera(ndc, camera);
  // 先测机位视锥（机身盒+锥线），命中→pick-camera
  if (frustumGroup?.visible) {
    const camHits = raycaster.intersectObject(frustumGroup, true);
    for (const hit of camHits) {
      let node: THREE.Object3D | null = hit.object;
      while (node && !node.userData.cameraId) node = node.parent;
      if (node?.userData.cameraId) {
        emit('pick-camera', node.userData.cameraId as string);
        return;
      }
    }
  }
  const hits = raycaster.intersectObject(rootGroup, true);
  for (const hit of hits) {
    let node: THREE.Object3D | null = hit.object;
    while (node && !node.userData.elementId) node = node.parent;
    if (node?.userData.elementId) {
      emit('pick', node.userData.elementId as string);
      emit('pick-camera', null);
      return;
    }
  }
  emit('pick', null);
  emit('pick-camera', null);
}

/** gizmo 拖完回写：读 Group 世界变换 → clamp 到 schema 区间 → 上抛 */
function emitTransformEnd(): void {
  const id = props.selectedElementId;
  const g = id ? built.get(id) : null;
  if (!id || !g) return;
  const cl = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));
  emit('transform-end', {
    id,
    transform: {
      position: [cl(g.position.x, -POS_CLAMP, POS_CLAMP), cl(g.position.y, -POS_CLAMP, POS_CLAMP), cl(g.position.z, -POS_CLAMP, POS_CLAMP)],
      rotation: [g.rotation.x, g.rotation.y, g.rotation.z],
      scale: [cl(g.scale.x, SCALE_MIN, SCALE_MAX), cl(g.scale.y, SCALE_MIN, SCALE_MAX), cl(g.scale.z, SCALE_MIN, SCALE_MAX)],
    },
  });
}

// 选中 ↔ gizmo 双向：清单点选 → attach；清空 → detach
watch(
  () => props.selectedElementId,
  (id) => {
    if (!transformControls) return;
    const g = id ? built.get(id) : null;
    if (g) transformControls.attach(g);
    else transformControls.detach();
    invalidate();
  },
);

watch(
  () => props.transformMode,
  (mode) => {
    if (!transformControls) return;
    transformControls.setMode(mode);
    // 模式切走时恢复三轴显示（Shift 等比只作用于 scale 模式）
    transformControls.showX = true;
    transformControls.showY = true;
    transformControls.showZ = true;
    invalidate();
  },
);

// 导演视角 ↔ 机位视角：机位视角只读（禁 orbit/gizmo），回导演视角恢复选中
watch(
  () => props.activeCameraId,
  (id) => {
    if (!controls || !transformControls) return;
    if (id) {
      controls.enabled = false;
      transformControls.detach();
      transformControls.enabled = false;
    } else {
      controls.enabled = true;
      transformControls.enabled = true;
      const g = props.selectedElementId ? built.get(props.selectedElementId) : null;
      if (g) transformControls.attach(g);
    }
    if (frustumGroup) frustumGroup.visible = !id;
    syncRenderCamera();
    applyViewFrame();
    invalidate();
  },
);

let poseTimer = 0;
function emitDirectorPose(): void {
  // 节流：拖视角时 100ms 一报，供「从当前视角新增机位」取位姿
  if (poseTimer) return;
  poseTimer = window.setTimeout(() => {
    poseTimer = 0;
    if (!camera || !controls) return;
    emit('director-pose', {
      position: [camera.position.x, camera.position.y, camera.position.z],
      target: [controls.target.x, controls.target.y, controls.target.z],
    });
  }, 100);
}

onBeforeUnmount(() => {
  if (poseTimer) window.clearTimeout(poseTimer);
  if (rafId) cancelAnimationFrame(rafId);
  rafId = 0;
  window.removeEventListener('keydown', onShiftKey);
  window.removeEventListener('keyup', onShiftKey);
  renderer?.domElement.removeEventListener('pointerdown', onPointerDown);
  renderer?.domElement.removeEventListener('pointerup', onPointerUp);
  resizeObserver?.disconnect();
  resizeObserver = null;
  transformControls?.detach();
  transformControls?.dispose();
  transformControls = null;
  controls?.dispose();
  controls = null;
  if (rootGroup) disposeScene(rootGroup);
  rootGroup = null;
  built.clear();
  if (frustumGroup) {
    disposeCameraFrustum(frustumGroup);
    frustumGroup = null;
  }
  camerasSig = '';
  cameraObjs.clear();
  if (scene) {
    scene.clear();
    scene = null;
  }
  if (renderer) {
    renderer.dispose();
    renderer.forceContextLoss();
    renderer.domElement.remove();
    renderer = null;
  }
  camera = null;
});

// ---------- 对外 API ----------

/** 当前导演视角位姿（「从当前视角新增机位」用） */
function getDirectorPose(): { position: Vec3; target: Vec3 } | null {
  if (!camera || !controls) return null;
  return {
    position: [camera.position.x, camera.position.y, camera.position.z],
    target: [controls.target.x, controls.target.y, controls.target.z],
  };
}

defineExpose({
  invalidate,
  getDirectorPose,
});
</script>

<style scoped lang="scss">
.director-viewport {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
}

// 机位视角遮幅框（巨投影法：框外压暗，框内=画幅所见即截图所得）
.director-letterbox {
  position: absolute;
  pointer-events: none;
  border: 1px solid rgba(53, 208, 255, 0.8);
  box-shadow: 0 0 0 9999px rgba(0, 0, 0, 0.72);
}

.director-letterbox-tag {
  position: absolute;
  top: 6px;
  left: 8px;
  font-size: 11px;
  color: #35d0ff;
  background: rgba(0, 0, 0, 0.55);
  padding: 1px 6px;
  border-radius: 2px;
}

.director-viewmode-tip {
  position: absolute;
  bottom: 10px;
  left: 50%;
  transform: translateX(-50%);
  font-size: 12px;
  color: #c0c8d4;
  background: rgba(0, 0, 0, 0.55);
  padding: 3px 12px;
  border-radius: 10px;
  pointer-events: none;
}
</style>

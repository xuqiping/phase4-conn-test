/**
 * sceneModel → three 对象映射层（渲染期组装，含 three import——不进单测，happy-dom 无 WebGL）。
 *
 * 资源纪律（spec §6）：
 * - 盒体图元走共享单位 geometry 缓存 + per-mesh scale——同尺寸素模零额外 geometry
 * - 材质按元素色缓存（Map<color, MeshLambertMaterial>），modal 关闭统一 dispose
 * - disposeScene 清单化：traverse 释放非共享 geometry/material → 共享缓存单独 disposeSceneAssets
 */
import * as THREE from 'three';
import { BODY_PRESETS, POSE_PRESETS, type JointName } from './figurePresets';
import type { DirectorElement, DirectorSceneData, ElementKind, ElementTransform } from './sceneModel';

// ---------- 共享资源缓存 ----------

let sharedBox: THREE.BoxGeometry | null = null;
let sharedCylinder: THREE.CylinderGeometry | null = null;
const materialCache = new Map<string, THREE.MeshLambertMaterial>();

function boxGeo(): THREE.BoxGeometry {
  if (!sharedBox) sharedBox = new THREE.BoxGeometry(1, 1, 1);
  return sharedBox;
}

function cylGeo(): THREE.CylinderGeometry {
  if (!sharedCylinder) sharedCylinder = new THREE.CylinderGeometry(0.5, 0.5, 1, 16);
  return sharedCylinder;
}

function material(color: string): THREE.MeshLambertMaterial {
  let m = materialCache.get(color);
  if (!m) {
    m = new THREE.MeshLambertMaterial({ color });
    materialCache.set(color, m);
  }
  return m;
}

/** modal 关闭时释放共享缓存（geometry ×2 + 全部缓存材质） */
export function disposeSceneAssets(): void {
  sharedBox?.dispose();
  sharedBox = null;
  sharedCylinder?.dispose();
  sharedCylinder = null;
  materialCache.forEach((m) => m.dispose());
  materialCache.clear();
}

// ---------- 基础零件 ----------

interface BoxPart {
  w: number;
  h: number;
  d: number;
  /** 相对父级锚点（零件中心）位置 */
  x: number;
  y: number;
  z: number;
}

function addBox(parent: THREE.Object3D, part: BoxPart, mat: THREE.Material): THREE.Mesh {
  const mesh = new THREE.Mesh(boxGeo(), mat);
  mesh.scale.set(part.w, part.h, part.d);
  mesh.position.set(part.x, part.y, part.z);
  parent.add(mesh);
  return mesh;
}

// ---------- 素模（图元骨架 × 体型比例 × 姿势关节） ----------

/**
 * 素模骨架层级（局部 Y 向上，脚底=根原点）：
 * root
 *  ├ pelvis 盆（hipL/hipR 关节挂大腿）
 *  │   ├ torso 躯干（shoulderL/R 挂上臂、neck 挂头）
 *  │   │   ├ upperArmL → elbowL → lowerArmL
 *  │   │   └ upperArmR → elbowR → lowerArmR
 *  │   └ head
 *  ├ thighL → kneeL → shinL
 *  └ thighR → kneeR → shinR
 * 关节=空 Object3D 节点（旋转关节用），零件=共享盒 geometry 的 Mesh。
 */
export function buildFigure(color: string, bodyType: keyof typeof BODY_PRESETS, pose: string): THREE.Group {
  const p = BODY_PRESETS[bodyType] ?? BODY_PRESETS.adultMale;
  const posePreset = POSE_PRESETS[pose as keyof typeof POSE_PRESETS] ?? POSE_PRESETS.stand;
  const mat = material(color);

  const root = new THREE.Group();
  root.name = 'figure';

  // 盆
  const pelvis = new THREE.Group();
  pelvis.name = 'pelvis';
  pelvis.position.y = p.legLength + p.hipHeight / 2;
  root.add(pelvis);
  addBox(pelvis, { w: p.hipWidth, h: p.hipHeight, d: p.torsoThickness * 0.9, x: 0, y: 0, z: 0 }, mat);

  // 躯干（盆上方）
  const torso = new THREE.Group();
  torso.name = 'torso';
  torso.position.y = p.hipHeight / 2 + p.torsoHeight / 2;
  pelvis.add(torso);
  addBox(torso, { w: p.shoulderWidth, h: p.torsoHeight, d: p.torsoThickness, x: 0, y: 0, z: 0 }, mat);

  // 头（neck 关节）
  const neck = new THREE.Group();
  neck.name = 'neck';
  neck.position.y = p.torsoHeight / 2;
  torso.add(neck);
  addBox(neck, { w: p.headSize, h: p.headSize, d: p.headSize, x: 0, y: p.headSize / 2 + 0.02, z: 0 }, mat);

  // 手臂：上臂+前臂两段（shoulder 关节挂躯干顶部两侧）
  const armY = p.torsoHeight / 2 - p.armThickness / 2;
  ([['L', 1], ['R', -1]] as const).forEach(([side, dir]) => {
    const shoulder = new THREE.Group();
    shoulder.name = `shoulder${side}` as JointName;
    shoulder.position.set((dir * (p.shoulderWidth / 2 + p.armThickness / 2 + 0.01)), armY, 0);
    torso.add(shoulder);
    const upperLen = p.armLength * 0.55;
    addBox(shoulder, { w: p.armThickness, h: upperLen, d: p.armThickness, x: 0, y: -upperLen / 2, z: 0 }, mat);
    const elbow = new THREE.Group();
    elbow.name = `elbow${side}` as JointName;
    elbow.position.y = -upperLen;
    shoulder.add(elbow);
    const lowerLen = p.armLength * 0.45;
    addBox(elbow, { w: p.armThickness * 0.9, h: lowerLen, d: p.armThickness * 0.9, x: 0, y: -lowerLen / 2, z: 0 }, mat);
  });

  // 腿：大腿+小腿两段（hip 关节挂盆底两侧）
  ([['L', 1], ['R', -1]] as const).forEach(([side, dir]) => {
    const hip = new THREE.Group();
    hip.name = `hip${side}` as JointName;
    hip.position.set(dir * p.hipWidth * 0.26, -p.hipHeight / 2, 0);
    pelvis.add(hip);
    const thighLen = p.legLength * 0.5;
    addBox(hip, { w: p.legThickness, h: thighLen, d: p.legThickness, x: 0, y: -thighLen / 2, z: 0 }, mat);
    const knee = new THREE.Group();
    knee.name = `knee${side}` as JointName;
    knee.position.y = -thighLen;
    hip.add(knee);
    const shinLen = p.legLength * 0.5 - 0.02;
    addBox(knee, { w: p.legThickness * 0.85, h: shinLen, d: p.legThickness * 0.85, x: 0, y: -shinLen / 2, z: 0 }, mat);
  });

  applyPose(root, posePreset.joints, posePreset.rootOffsetY, posePreset.rootRotation);
  return root;
}

function applyPose(
  root: THREE.Group,
  joints: Partial<Record<JointName, [number, number, number]>>,
  rootOffsetY: number,
  rootRotation: [number, number, number],
): void {
  Object.entries(joints).forEach(([name, rot]) => {
    const node = root.getObjectByName(name);
    if (node && rot) node.rotation.set(rot[0], rot[1], rot[2]);
  });
  root.position.y += rootOffsetY;
  root.rotation.set(rootRotation[0], rootRotation[1], rootRotation[2]);
}

/** 素模可见高度（不含姿势折叠，用于机位默认目标/清单摘要） */
export function figureHeight(bodyType: keyof typeof BODY_PRESETS): number {
  return (BODY_PRESETS[bodyType] ?? BODY_PRESETS.adultMale).height;
}

// ---------- 基础几何 / 组合体 ----------

function buildPrimitiveGroup(kind: ElementKind, color: string): THREE.Group {
  const g = new THREE.Group();
  g.name = kind;
  const mat = material(color);
  switch (kind) {
    case 'box':
      addBox(g, { w: 1, h: 1, d: 1, x: 0, y: 0.5, z: 0 }, mat);
      break;
    case 'cylinder': {
      const mesh = new THREE.Mesh(cylGeo(), mat);
      mesh.scale.set(1, 1, 1); // 半径0.5 高1
      mesh.position.y = 0.5;
      g.add(mesh);
      break;
    }
    case 'plate':
      addBox(g, { w: 2.4, h: 0.08, d: 1.6, x: 0, y: 0.04, z: 0 }, mat);
      break;
    case 'table': {
      // 桌面 + 四腿（组合体）
      addBox(g, { w: 1.6, h: 0.07, d: 0.9, x: 0, y: 0.75, z: 0 }, mat);
      ([[-0.72, -0.38], [0.72, -0.38], [-0.72, 0.38], [0.72, 0.38]] as const).forEach(([lx, lz]) => {
        addBox(g, { w: 0.07, h: 0.75, d: 0.07, x: lx, y: 0.375, z: lz }, mat);
      });
      break;
    }
    case 'chair': {
      addBox(g, { w: 0.45, h: 0.05, d: 0.45, x: 0, y: 0.45, z: 0 }, mat);
      addBox(g, { w: 0.45, h: 0.55, d: 0.05, x: 0, y: 0.72, z: -0.2 }, mat);
      ([[-0.19, -0.19], [0.19, -0.19], [-0.19, 0.19], [0.19, 0.19]] as const).forEach(([lx, lz]) => {
        addBox(g, { w: 0.05, h: 0.45, d: 0.05, x: lx, y: 0.225, z: lz }, mat);
      });
      break;
    }
    default:
      addBox(g, { w: 1, h: 1, d: 1, x: 0, y: 0.5, z: 0 }, mat);
  }
  return g;
}

// ---------- 群众阵列（InstancedMesh：盒身+盒头两实例流） ----------

/** 阵列实例位姿（纯计算，渲染层拿来喂 InstancedMesh；也可用于测试） */
export function crowdMatrices(rows: number, cols: number, spacing: number): { x: number; z: number; rotY: number }[] {
  const out: { x: number; z: number; rotY: number }[] = [];
  const width = (cols - 1) * spacing;
  const depth = (rows - 1) * spacing;
  for (let r = 0; r < rows; r++) {
    for (let c = 0; c < cols; c++) {
      // 伪随机抖动（确定性：索引散列，不引 Math.random 保证同数据同渲染）
      const h = Math.sin(r * 127.1 + c * 311.7) * 43758.5453;
      const jitter = (h - Math.floor(h) - 0.5) * spacing * 0.3;
      out.push({
        x: c * spacing - width / 2 + jitter,
        z: r * spacing - depth / 2 + jitter,
        rotY: jitter * 2,
      });
    }
  }
  return out;
}

export function buildCrowd(color: string, rows: number, cols: number, spacing: number): THREE.Group {
  const g = new THREE.Group();
  g.name = 'crowd';
  const p = BODY_PRESETS.adultMale;
  const mat = material(color);
  const spots = crowdMatrices(rows, cols, spacing);

  const bodyMesh = new THREE.InstancedMesh(boxGeo(), mat, spots.length);
  const headMesh = new THREE.InstancedMesh(boxGeo(), mat, spots.length);
  const m = new THREE.Matrix4();
  const q = new THREE.Quaternion();
  const pos = new THREE.Vector3();
  const scale = new THREE.Vector3();
  spots.forEach((s, i) => {
    q.setFromAxisAngle(new THREE.Vector3(0, 1, 0), s.rotY);
    pos.set(s.x, p.legLength + (p.hipHeight + p.torsoHeight) / 2, s.z);
    scale.set(p.shoulderWidth * 0.8, p.hipHeight + p.torsoHeight, p.torsoThickness * 0.8);
    m.compose(pos, q, scale);
    bodyMesh.setMatrixAt(i, m);
    pos.set(s.x, p.legLength + p.hipHeight + p.torsoHeight + p.headSize / 2 + 0.02, s.z);
    scale.set(p.headSize, p.headSize, p.headSize);
    m.compose(pos, q, scale);
    headMesh.setMatrixAt(i, m);
  });
  bodyMesh.instanceMatrix.needsUpdate = true;
  headMesh.instanceMatrix.needsUpdate = true;
  g.add(bodyMesh, headMesh);
  return g;
}

// ---------- 元素统一入口 ----------

/** 按元素数据建 3D 对象（figure/几何/阵列），挂 userData.elementId 供视口点选反查 */
export function buildElementObject(el: DirectorElement): THREE.Group {
  let g: THREE.Group;
  if (el.kind === 'figure') {
    g = buildFigure(el.color, el.figure?.bodyType ?? 'adultMale', el.figure?.pose ?? 'stand');
  } else if (el.kind === 'crowd') {
    g = buildCrowd(el.color, el.crowd?.rows ?? 4, el.crowd?.cols ?? 4, el.crowd?.spacing ?? 1.5);
  } else {
    g = buildPrimitiveGroup(el.kind, el.color);
  }
  g.userData.elementId = el.id;
  applyTransform(g, el.transform);
  g.visible = !el.hidden;
  return g;
}

export function applyTransform(obj: THREE.Object3D, t: ElementTransform): void {
  obj.position.set(t.position[0], t.position[1], t.position[2]);
  obj.rotation.set(t.rotation[0], t.rotation[1], t.rotation[2]);
  obj.scale.set(t.scale[0], t.scale[1], t.scale[2]);
}

/** 元素参数（颜色/姿势/阵列）变更后重建对象（保留原 transform 与 visible） */
export function rebuildElementObject(old: THREE.Group, el: DirectorElement): THREE.Group {
  const fresh = buildElementObject(el);
  fresh.position.copy(old.position);
  fresh.rotation.copy(old.rotation);
  fresh.scale.copy(old.scale);
  fresh.visible = old.visible;
  return fresh;
}

// ---------- 整场景 ----------

export interface BuiltScene {
  root: THREE.Group;
  /** elementId → 顶层 Group（gizmo attach / 隐藏 / 点选反查） */
  elementMap: Map<string, THREE.Group>;
  grid: THREE.GridHelper | null;
}

export function buildScene(scene: DirectorSceneData): BuiltScene {
  const root = new THREE.Group();
  root.name = 'director-root';
  const elementMap = new Map<string, THREE.Group>();
  scene.elements.forEach((el) => {
    const g = buildElementObject(el);
    elementMap.set(el.id, g);
    root.add(g);
  });
  let grid: THREE.GridHelper | null = null;
  if (scene.ground.grid) {
    grid = new THREE.GridHelper(60, 60, 0x4a5568, 0x2d3748);
    grid.name = 'ground-grid';
    root.add(grid);
  }
  return { root, elementMap, grid };
}

/** 释放整棵场景树的非共享资源（InstancedMesh 的实例缓冲也在此释放） */
export function disposeScene(root: THREE.Object3D): void {
  root.traverse((obj) => {
    if (obj instanceof THREE.InstancedMesh) {
      obj.dispose();
    }
    const mesh = obj as THREE.Mesh;
    if (mesh.geometry && mesh.geometry !== sharedBox && mesh.geometry !== sharedCylinder) {
      mesh.geometry.dispose();
    }
  });
  root.clear();
}

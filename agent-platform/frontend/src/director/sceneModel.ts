/**
 * 导演台场景数据模型（纯逻辑层，零 three 依赖，可脱离 WebGL 单测）。
 *
 * 数据契约见 workflow_output/docs/specs/导演台3D构图设计.md §4.4：
 * - version:1 前向兼容（旧画布无 directorScene 视为空场景）
 * - 解析侧白名单 schema：未知字段丢弃 / 枚举回退默认 / 数值 clamp / 数组截断
 * - 序列化 256KB 上限自检（防御性，正常 200 元素满配约 30KB）
 */

export type Vec3 = [number, number, number];

export type ElementKind =
  | 'figure'
  | 'box'
  | 'cylinder'
  | 'plate'
  | 'table'
  | 'chair'
  | 'crowd';

/** 素模体型 8 枚举（同一图元骨架 × 比例参数） */
export type BodyType =
  | 'adultMale'
  | 'adultFemale'
  | 'elder'
  | 'child'
  | 'tall'
  | 'short'
  | 'heavy'
  | 'slim';

/** 姿势 6 枚举（渲染期套用关节组预设旋转，数据只存枚举） */
export type Pose = 'stand' | 'sit' | 'walk' | 'run' | 'crouch' | 'lie';

/** 画幅枚举 */
export type AspectKey = '16:9' | '9:16' | '1:1' | '4:3' | '2.39:1';

export interface ElementTransform {
  position: Vec3;
  rotation: Vec3;
  scale: Vec3;
}

export interface FigureOptions {
  bodyType: BodyType;
  pose: Pose;
}

export interface CrowdOptions {
  rows: number;
  cols: number;
  spacing: number;
}

export interface DirectorElement {
  id: string;
  kind: ElementKind;
  name: string;
  color: string;
  hidden: boolean;
  transform: ElementTransform;
  figure?: FigureOptions;
  crowd?: CrowdOptions;
}

export interface DirectorCameraData {
  id: string;
  name: string;
  position: Vec3;
  target: Vec3;
  fov: number;
  aspect: AspectKey;
}

export interface GroundOptions {
  grid: boolean;
  backgroundColor: string;
}

export interface DirectorSceneData {
  version: 1;
  elements: DirectorElement[];
  cameras: DirectorCameraData[];
  ground: GroundOptions;
}

// ---------- 上限与枚举常量（spec §4.2/§4.4） ----------

export const MAX_ELEMENTS = 200;
export const MAX_CAMERAS = 16;
export const MAX_CROWD_ROWS = 12;
export const MAX_CROWD_COLS = 12;
export const NAME_MAX = 30;
export const CAMERA_NAME_MAX = 20;
export const POS_CLAMP = 1000;
export const SCALE_MIN = 0.1;
export const SCALE_MAX = 20;
export const SPACING_MIN = 0.5;
export const SPACING_MAX = 10;
export const FOV_MIN = 15;
export const FOV_MAX = 90;
export const UNDO_LIMIT = 50;
export const SERIALIZE_LIMIT = 256 * 1024;
export const SCREENSHOT_LONG_EDGE = 2048;

export const ELEMENT_KINDS: readonly ElementKind[] = [
  'figure',
  'box',
  'cylinder',
  'plate',
  'table',
  'chair',
  'crowd',
] as const;

export const BODY_TYPES: readonly BodyType[] = [
  'adultMale',
  'adultFemale',
  'elder',
  'child',
  'tall',
  'short',
  'heavy',
  'slim',
] as const;

export const POSES: readonly Pose[] = [
  'stand',
  'sit',
  'walk',
  'run',
  'crouch',
  'lie',
] as const;

export const ASPECT_KEYS: readonly AspectKey[] = [
  '16:9',
  '9:16',
  '1:1',
  '4:3',
  '2.39:1',
] as const;

/** 画幅 → 宽高比数值（截图分辨率与遮幅计算的唯一真相源） */
export const ASPECT_RATIOS: Record<AspectKey, [number, number]> = {
  '16:9': [16, 9],
  '9:16': [9, 16],
  '1:1': [1, 1],
  '4:3': [4, 3],
  '2.39:1': [2.39, 1],
};

/** 12 色板（+HEX 自定义） */
export const COLOR_PALETTE: readonly string[] = [
  '#e5484d',
  '#f76b15',
  '#ffb224',
  '#46a758',
  '#12a594',
  '#0090ff',
  '#3e63dd',
  '#8e4ec6',
  '#d6409f',
  '#6e6e6e',
  '#a18072',
  '#f2f2f2',
] as const;

export const DEFAULT_COLOR = '#9aa4b2';
export const DEFAULT_BG_COLOR = '#1a1f28';
const HEX_RE = /^#[0-9a-fA-F]{6}$/;

// ---------- 基础清洗 ----------

function clampNum(v: unknown, min: number, max: number, fallback: number): number {
  const n = typeof v === 'number' ? v : Number(v);
  if (!Number.isFinite(n)) return fallback;
  return Math.min(max, Math.max(min, n));
}

function sanitizeName(raw: unknown, max: number, fallback: string): string {
  if (typeof raw !== 'string') return fallback;
  const trimmed = raw.trim().slice(0, max);
  return trimmed.length > 0 ? trimmed : fallback;
}

export function isValidHexColor(raw: unknown): raw is string {
  return typeof raw === 'string' && HEX_RE.test(raw);
}

function sanitizeColor(raw: unknown, fallback: string): string {
  return isValidHexColor(raw) ? raw.toLowerCase() : fallback;
}

function parseVec3(raw: unknown): Vec3 {
  if (!Array.isArray(raw) || raw.length < 3) return [0, 0, 0];
  const axis = (v: unknown) => clampNum(v, -POS_CLAMP, POS_CLAMP, 0);
  return [axis(raw[0]), axis(raw[1]), axis(raw[2])];
}

function parseScale(raw: unknown): Vec3 {
  if (!Array.isArray(raw) || raw.length < 3) return [1, 1, 1];
  const axis = (v: unknown) => clampNum(v, SCALE_MIN, SCALE_MAX, 1);
  return [axis(raw[0]), axis(raw[1]), axis(raw[2])];
}

let idCounter = 0;
/** 生成短随机 id（同画布内元素/机位命名空间分开传 prefix） */
export function genId(prefix: string): string {
  idCounter = (idCounter + 1) % 0xffff;
  const rand = Math.random().toString(36).slice(2, 8);
  return `${prefix}_${Date.now().toString(36)}${idCounter.toString(36)}${rand}`;
}

// ---------- 解析（白名单 schema） ----------

function pickEnum<T extends string>(raw: unknown, values: readonly T[], fallback: T): T {
  return typeof raw === 'string' && (values as readonly string[]).includes(raw)
    ? (raw as T)
    : fallback;
}

function parseElement(raw: unknown): DirectorElement | null {
  if (typeof raw !== 'object' || raw === null) return null;
  const r = raw as Record<string, unknown>;
  const kind = pickEnum(r.kind, ELEMENT_KINDS, 'box');
  const t = (r.transform ?? {}) as Record<string, unknown>;
  const el: DirectorElement = {
    id: typeof r.id === 'string' && r.id.length > 0 ? r.id : genId('el'),
    kind,
    name: sanitizeName(r.name, NAME_MAX, '元素'),
    color: sanitizeColor(r.color, DEFAULT_COLOR),
    hidden: r.hidden === true,
    transform: {
      position: parseVec3(t.position),
      rotation: parseVec3(t.rotation),
      scale: parseScale(t.scale),
    },
  };
  if (kind === 'figure') {
    const f = (r.figure ?? {}) as Record<string, unknown>;
    el.figure = {
      bodyType: pickEnum(f.bodyType, BODY_TYPES, 'adultMale'),
      pose: pickEnum(f.pose, POSES, 'stand'),
    };
  }
  if (kind === 'crowd') {
    const c = (r.crowd ?? {}) as Record<string, unknown>;
    el.crowd = {
      rows: Math.round(clampNum(c.rows, 1, MAX_CROWD_ROWS, 4)),
      cols: Math.round(clampNum(c.cols, 1, MAX_CROWD_COLS, 4)),
      spacing: clampNum(c.spacing, SPACING_MIN, SPACING_MAX, 1.5),
    };
  }
  return el;
}

function parseCamera(raw: unknown): DirectorCameraData | null {
  if (typeof raw !== 'object' || raw === null) return null;
  const r = raw as Record<string, unknown>;
  return {
    id: typeof r.id === 'string' && r.id.length > 0 ? r.id : genId('cam'),
    name: sanitizeName(r.name, CAMERA_NAME_MAX, '机位'),
    position: parseVec3(r.position),
    target: parseVec3(r.target),
    fov: Math.round(clampNum(r.fov, FOV_MIN, FOV_MAX, 45)),
    aspect: pickEnum(r.aspect, ASPECT_KEYS, '16:9'),
  };
}

/** 空场景默认值（旧节点 / WebGL 探测失败兜底） */
export function emptyScene(): DirectorSceneData {
  return {
    version: 1,
    elements: [],
    cameras: [],
    ground: { grid: true, backgroundColor: DEFAULT_BG_COLOR },
  };
}

/**
 * 白名单解析：损坏/手改数据不崩编辑器。
 * 非法根对象 → 空场景；数组超限截断；未知字段丢弃；枚举回退；数值 clamp。
 */
export function parseScene(raw: unknown): DirectorSceneData {
  if (typeof raw !== 'object' || raw === null) return emptyScene();
  const r = raw as Record<string, unknown>;
  const elements = Array.isArray(r.elements)
    ? r.elements
        .slice(0, MAX_ELEMENTS)
        .map(parseElement)
        .filter((e): e is DirectorElement => e !== null)
    : [];
  const cameras = Array.isArray(r.cameras)
    ? r.cameras
        .slice(0, MAX_CAMERAS)
        .map(parseCamera)
        .filter((c): c is DirectorCameraData => c !== null)
    : [];
  const groundRaw = (r.ground ?? {}) as Record<string, unknown>;
  return {
    version: 1,
    elements,
    cameras,
    ground: {
      grid: groundRaw.grid !== false,
      backgroundColor: sanitizeColor(groundRaw.backgroundColor, DEFAULT_BG_COLOR),
    },
  };
}

// ---------- 序列化 ----------

export class SceneTooLargeError extends Error {
  constructor(size: number) {
    super(`场景数据 ${Math.round(size / 1024)}KB 超过 ${SERIALIZE_LIMIT / 1024}KB 上限，已拒存`);
    this.name = 'SceneTooLargeError';
  }
}

/** 序列化 + 256KB 自检（超限抛 SceneTooLargeError，调用方提示用户） */
export function serializeScene(scene: DirectorSceneData): string {
  const json = JSON.stringify(scene);
  if (json.length > SERIALIZE_LIMIT) throw new SceneTooLargeError(json.length);
  return json;
}

/** 深拷贝（undo 快照 / 编辑器暂存用） */
export function cloneScene(scene: DirectorSceneData): DirectorSceneData {
  return structuredClone(scene);
}

// ---------- 工厂（新建元素/机位） ----------

const KIND_DEFAULT_NAMES: Record<ElementKind, string> = {
  figure: '人偶',
  box: '方块',
  cylinder: '圆柱',
  plate: '平板',
  table: '桌子',
  chair: '椅子',
  crowd: '群众阵列',
};

/**
 * 新建元素：落原点附近网格位（spawnIndex 决定偏移，避免全部叠在 0,0）。
 */
export function createElement(kind: ElementKind, spawnIndex: number, overrides?: Partial<DirectorElement>): DirectorElement {
  const col = spawnIndex % 5;
  const row = Math.floor(spawnIndex / 5) % 5;
  const el: DirectorElement = {
    id: genId('el'),
    kind,
    name: KIND_DEFAULT_NAMES[kind],
    color: DEFAULT_COLOR,
    hidden: false,
    transform: {
      position: [(col - 2) * 2, 0, (row - 2) * 2],
      rotation: [0, 0, 0],
      scale: [1, 1, 1],
    },
  };
  if (kind === 'figure') el.figure = { bodyType: 'adultMale', pose: 'stand' };
  if (kind === 'crowd') el.crowd = { rows: 4, cols: 4, spacing: 1.5 };
  return { ...el, ...overrides, transform: { ...el.transform, ...overrides?.transform } };
}

/** 新建机位（「从当前视角新增机位」：position/target 来自导演视角相机） */
export function createCamera(position: Vec3, target: Vec3, index: number, overrides?: Partial<DirectorCameraData>): DirectorCameraData {
  return {
    id: genId('cam'),
    name: `机位 ${index + 1}`,
    position: [...position] as Vec3,
    target: [...target] as Vec3,
    fov: 45,
    aspect: '16:9',
    ...overrides,
  };
}

// ---------- 截图分辨率与遮幅（纯函数，唯一真相源） ----------

/** 画幅 → 截图像素尺寸（长边封顶 SCREENSHOT_LONG_EDGE，偶数化避免半像素） */
export function resolutionForAspect(aspect: AspectKey, longEdge = SCREENSHOT_LONG_EDGE): [number, number] {
  const [rw, rh] = ASPECT_RATIOS[aspect];
  if (rw >= rh) {
    const w = longEdge;
    const h = Math.round((longEdge * rh) / rw / 2) * 2;
    return [w, h];
  }
  const h = longEdge;
  const w = Math.round((longEdge * rw) / rh / 2) * 2;
  return [w, h];
}

export interface LetterboxRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** 视口内目标画幅的取景框（框外压暗区域由 CSS 渲染，此处只算几何） */
export function letterboxRect(viewportW: number, viewportH: number, aspect: AspectKey): LetterboxRect {
  const [rw, rh] = ASPECT_RATIOS[aspect];
  const targetRatio = rw / rh;
  const viewRatio = viewportW / viewportH;
  let width = viewportW;
  let height = viewportH;
  if (viewRatio > targetRatio) {
    // 视口更宽：左右留黑边
    width = Math.round(viewportH * targetRatio);
  } else {
    // 视口更高：上下留黑边
    height = Math.round(viewportW / targetRatio);
  }
  return {
    x: Math.round((viewportW - width) / 2),
    y: Math.round((viewportH - height) / 2),
    width,
    height,
  };
}

// ---------- undo 栈（快照式，50 步，交互结束才推栈） ----------

/**
 * 快照式 undo/redo：
 * - push 仅在「交互结束」调用（dragging-changed false / 输入 blur），拖动过程不推
 * - 连续相同快照去重（防误推空操作）
 * - undoStack 超 UNDO_LIMIT 丢最旧
 */
export class UndoStack {
  private undoStack: DirectorSceneData[] = [];
  private redoStack: DirectorSceneData[] = [];
  private present: DirectorSceneData;

  constructor(initial: DirectorSceneData) {
    this.present = cloneScene(initial);
  }

  get canUndo(): boolean {
    return this.undoStack.length > 0;
  }

  get canRedo(): boolean {
    return this.redoStack.length > 0;
  }

  private static same(a: DirectorSceneData, b: DirectorSceneData): boolean {
    return JSON.stringify(a) === JSON.stringify(b);
  }

  /** 交互结束推快照；与当前态相同则跳过；推新快照清空 redo */
  push(next: DirectorSceneData): void {
    if (UndoStack.same(this.present, next)) return;
    this.undoStack.push(this.present);
    if (this.undoStack.length > UNDO_LIMIT) this.undoStack.shift();
    this.present = cloneScene(next);
    this.redoStack = [];
  }

  /** 替换当前态不产生历史（初始化/外部整体重置用） */
  reset(state: DirectorSceneData): void {
    this.present = cloneScene(state);
    this.undoStack = [];
    this.redoStack = [];
  }

  undo(): DirectorSceneData | null {
    if (!this.canUndo) return null;
    const prev = this.undoStack.pop()!;
    this.redoStack.push(this.present);
    this.present = prev;
    return cloneScene(prev);
  }

  redo(): DirectorSceneData | null {
    if (!this.canRedo) return null;
    const next = this.redoStack.pop()!;
    this.undoStack.push(this.present);
    this.present = next;
    return cloneScene(next);
  }
}

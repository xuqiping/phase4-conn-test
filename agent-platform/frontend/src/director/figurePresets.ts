/**
 * 素模预设（纯数据，零 three 依赖，可单测）。
 *
 * 人偶 = 图元骨架（头/躯干/盆/上臂/前臂/大腿/小腿）× 体型比例 × 姿势关节旋转。
 * 体型只改「零件尺寸」；姿势只改「关节角度」——两层正交，渲染期组合。
 * 单位：米。旋转：弧度。
 */
import type { BodyType, Pose } from './sceneModel';

/** 零件尺寸（米）：宽 × 高 × 厚 */
export interface BodyPreset {
  label: string;
  /** 总身高参考（米），用于清单展示与相机默认目标高度 */
  height: number;
  headSize: number;
  shoulderWidth: number;
  torsoHeight: number;
  torsoThickness: number;
  hipWidth: number;
  hipHeight: number;
  legLength: number;
  legThickness: number;
  armLength: number;
  armThickness: number;
}

export const BODY_PRESETS: Record<BodyType, BodyPreset> = {
  adultMale: {
    label: '成男', height: 1.75, headSize: 0.24, shoulderWidth: 0.46, torsoHeight: 0.55,
    torsoThickness: 0.24, hipWidth: 0.36, hipHeight: 0.2, legLength: 0.85, legThickness: 0.15,
    armLength: 0.72, armThickness: 0.11,
  },
  adultFemale: {
    label: '成女', height: 1.63, headSize: 0.22, shoulderWidth: 0.38, torsoHeight: 0.5,
    torsoThickness: 0.2, hipWidth: 0.36, hipHeight: 0.19, legLength: 0.81, legThickness: 0.13,
    armLength: 0.66, armThickness: 0.09,
  },
  elder: {
    label: '老者', height: 1.62, headSize: 0.22, shoulderWidth: 0.38, torsoHeight: 0.48,
    torsoThickness: 0.2, hipWidth: 0.33, hipHeight: 0.18, legLength: 0.79, legThickness: 0.12,
    armLength: 0.62, armThickness: 0.09,
  },
  child: {
    label: '儿童', height: 1.15, headSize: 0.22, shoulderWidth: 0.28, torsoHeight: 0.36,
    torsoThickness: 0.16, hipWidth: 0.24, hipHeight: 0.13, legLength: 0.53, legThickness: 0.1,
    armLength: 0.44, armThickness: 0.07,
  },
  tall: {
    label: '高个', height: 1.92, headSize: 0.24, shoulderWidth: 0.44, torsoHeight: 0.62,
    torsoThickness: 0.23, hipWidth: 0.35, hipHeight: 0.22, legLength: 0.98, legThickness: 0.14,
    armLength: 0.8, armThickness: 0.1,
  },
  short: {
    label: '矮个', height: 1.5, headSize: 0.22, shoulderWidth: 0.38, torsoHeight: 0.44,
    torsoThickness: 0.22, hipWidth: 0.34, hipHeight: 0.17, legLength: 0.7, legThickness: 0.14,
    armLength: 0.58, armThickness: 0.11,
  },
  heavy: {
    label: '胖', height: 1.72, headSize: 0.25, shoulderWidth: 0.52, torsoHeight: 0.52,
    torsoThickness: 0.36, hipWidth: 0.46, hipHeight: 0.19, legLength: 0.82, legThickness: 0.18,
    armLength: 0.7, armThickness: 0.14,
  },
  slim: {
    label: '瘦', height: 1.74, headSize: 0.22, shoulderWidth: 0.36, torsoHeight: 0.56,
    torsoThickness: 0.17, hipWidth: 0.28, hipHeight: 0.2, legLength: 0.87, legThickness: 0.11,
    armLength: 0.74, armThickness: 0.08,
  },
};

/** 关节名（素模骨架层级） */
export type JointName =
  | 'neck'
  | 'shoulderL'
  | 'shoulderR'
  | 'elbowL'
  | 'elbowR'
  | 'hipL'
  | 'hipR'
  | 'kneeL'
  | 'kneeR';

export interface PosePreset {
  label: string;
  /** 关节旋转（弧度，本地轴） */
  joints: Partial<Record<JointName, [number, number, number]>>;
  /** 根节点整体升降（米）：坐/蹲重心下移，躺落到地面 */
  rootOffsetY: number;
  /** 根节点整体旋转（弧度）：躺=绕 X 轴倒下 */
  rootRotation: [number, number, number];
}

const DEG = Math.PI / 180;

export const POSE_PRESETS: Record<Pose, PosePreset> = {
  stand: {
    label: '站',
    joints: {},
    rootOffsetY: 0,
    rootRotation: [0, 0, 0],
  },
  sit: {
    label: '坐',
    joints: {
      hipL: [-90 * DEG, 0, 0],
      hipR: [-90 * DEG, 0, 0],
      kneeL: [90 * DEG, 0, 0],
      kneeR: [90 * DEG, 0, 0],
      shoulderL: [0, 0, 10 * DEG],
      shoulderR: [0, 0, -10 * DEG],
      elbowL: [0, 0, 20 * DEG],
      elbowR: [0, 0, -20 * DEG],
    },
    // 大腿水平、小腿垂下：躯干底 = 椅面高 ≈ 大腿粗细，重心下沉腿长一半
    rootOffsetY: -0.4,
    rootRotation: [0, 0, 0],
  },
  walk: {
    label: '走',
    joints: {
      hipL: [25 * DEG, 0, 0],
      hipR: [-25 * DEG, 0, 0],
      kneeL: [0, 0, 0],
      kneeR: [30 * DEG, 0, 0],
      shoulderL: [20 * DEG, 0, 8 * DEG],
      shoulderR: [-20 * DEG, 0, -8 * DEG],
    },
    rootOffsetY: -0.02,
    rootRotation: [0, 0, 0],
  },
  run: {
    label: '跑',
    joints: {
      hipL: [45 * DEG, 0, 0],
      hipR: [-40 * DEG, 0, 0],
      kneeL: [20 * DEG, 0, 0],
      kneeR: [80 * DEG, 0, 0],
      shoulderL: [50 * DEG, 0, 12 * DEG],
      shoulderR: [-55 * DEG, 0, -12 * DEG],
      elbowL: [0, 0, 70 * DEG],
      elbowR: [0, 0, -70 * DEG],
      neck: [5 * DEG, 0, 0],
    },
    rootOffsetY: -0.08,
    rootRotation: [15 * DEG, 0, 0],
  },
  crouch: {
    label: '蹲',
    joints: {
      hipL: [-100 * DEG, 0, 15 * DEG],
      hipR: [-100 * DEG, 0, -15 * DEG],
      kneeL: [130 * DEG, 0, 0],
      kneeR: [130 * DEG, 0, 0],
      shoulderL: [30 * DEG, 0, 15 * DEG],
      shoulderR: [30 * DEG, 0, -15 * DEG],
      neck: [15 * DEG, 0, 0],
    },
    rootOffsetY: -0.45,
    rootRotation: [20 * DEG, 0, 0],
  },
  lie: {
    label: '躺',
    joints: {},
    // 整体绕 X 轴倒下 90°：脚朝 +Z，背贴地面
    rootOffsetY: -0.82,
    rootRotation: [-90 * DEG, 0, 0],
  },
};

/** 群众阵列素模用简化体型（降低实例开销：盒身+盒头两实例） */
export const CROWD_BODY: BodyPreset = BODY_PRESETS.adultMale;

/** 群众行列朝向抖动种子（渲染期给实例加微量随机偏转，避免死板直线） */
export const CROWD_JITTER = 0.15;

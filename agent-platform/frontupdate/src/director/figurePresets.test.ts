import { describe, expect, it } from 'vitest';
import { BODY_PRESETS, POSE_PRESETS } from './figurePresets';
import { BODY_TYPES, POSES } from './sceneModel';

describe('figurePresets 数据完整性', () => {
  it('8 体型全覆盖且有中文 label', () => {
    expect(Object.keys(BODY_PRESETS)).toHaveLength(8);
    BODY_TYPES.forEach((bt) => {
      const p = BODY_PRESETS[bt];
      expect(p, `体型 ${bt} 缺预设`).toBeDefined();
      expect(p.label.length).toBeGreaterThan(0);
    });
    const labels = BODY_TYPES.map((bt) => BODY_PRESETS[bt].label);
    expect(new Set(labels).size).toBe(8);
  });

  it('6 姿势全覆盖且有中文 label', () => {
    expect(Object.keys(POSE_PRESETS)).toHaveLength(6);
    POSES.forEach((pose) => {
      const p = POSE_PRESETS[pose];
      expect(p, `姿势 ${pose} 缺预设`).toBeDefined();
      expect(p.label.length).toBeGreaterThan(0);
    });
  });

  it('体型尺寸全为正数且身高在合理区间', () => {
    BODY_TYPES.forEach((bt) => {
      const p = BODY_PRESETS[bt];
      [
        p.headSize, p.shoulderWidth, p.torsoHeight, p.torsoThickness,
        p.hipWidth, p.hipHeight, p.legLength, p.legThickness,
        p.armLength, p.armThickness,
      ].forEach((v) => expect(v, `${bt} 尺寸须为正`).toBeGreaterThan(0));
      expect(p.height).toBeGreaterThan(0.8);
      expect(p.height).toBeLessThan(2.5);
      // 零件堆叠高度（头/躯干/盆/腿）与身高同量级（±15% 容差）
      const stacked = p.headSize + p.torsoHeight + p.hipHeight + p.legLength;
      expect(Math.abs(stacked - p.height) / p.height).toBeLessThan(0.15);
    });
  });

  it('关节旋转值均在 [-π, π]、根偏移有限', () => {
    POSES.forEach((pose) => {
      const p = POSE_PRESETS[pose];
      Object.entries(p.joints).forEach(([joint, rot]) => {
        (rot as number[]).forEach((v) => {
          expect(Math.abs(v), `${pose}.${joint} 旋转越界`).toBeLessThanOrEqual(Math.PI);
        });
      });
      expect(Math.abs(p.rootOffsetY)).toBeLessThan(1);
      p.rootRotation.forEach((v) => expect(Math.abs(v)).toBeLessThanOrEqual(Math.PI));
    });
  });

  it('坐姿：髋前抬+膝回弯（摆椅子语义成立）', () => {
    const sit = POSE_PRESETS.sit;
    expect(sit.joints.hipL![0]).toBeLessThan(-Math.PI / 3);
    expect(sit.joints.kneeL![0]).toBeGreaterThan(Math.PI / 3);
    expect(sit.rootOffsetY).toBeLessThan(0);
  });

  it('躺姿：根旋转 ±90° 量级', () => {
    expect(Math.abs(POSE_PRESETS.lie.rootRotation[0])).toBeGreaterThan(Math.PI / 3);
  });
});

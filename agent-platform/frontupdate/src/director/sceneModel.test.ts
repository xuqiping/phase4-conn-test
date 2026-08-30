import { describe, expect, it } from 'vitest';
import {
  BODY_TYPES,
  COLOR_PALETTE,
  DirectorSceneData,
  FOV_MAX,
  FOV_MIN,
  MAX_CAMERAS,
  MAX_ELEMENTS,
  POSES,
  SceneTooLargeError,
  UndoStack,
  cloneScene,
  createCamera,
  createElement,
  emptyScene,
  isValidHexColor,
  letterboxRect,
  parseScene,
  resolutionForAspect,
  serializeScene,
} from './sceneModel';

function fullScene(): DirectorSceneData {
  return {
    version: 1,
    elements: [
      {
        id: 'el_1',
        kind: 'figure',
        name: '主角',
        color: '#e5484d',
        hidden: false,
        transform: { position: [1, 0, -2], rotation: [0, 45, 0], scale: [1, 1.2, 1] },
        figure: { bodyType: 'adultFemale', pose: 'sit' },
      },
      {
        id: 'el_2',
        kind: 'crowd',
        name: '群众',
        color: '#0090ff',
        hidden: true,
        transform: { position: [0, 0, 5], rotation: [0, 0, 0], scale: [1, 1, 1] },
        crowd: { rows: 12, cols: 12, spacing: 2.5 },
      },
    ],
    cameras: [
      {
        id: 'cam_1',
        name: '主机位',
        position: [3, 2, 4],
        target: [0, 1, 0],
        fov: 35,
        aspect: '9:16',
      },
    ],
    ground: { grid: true, backgroundColor: '#1a1f28' },
  };
}

describe('sceneModel.parseScene', () => {
  it('round-trip：序列化→解析还原等价数据', () => {
    const scene = fullScene();
    const back = parseScene(JSON.parse(serializeScene(scene)));
    expect(back).toEqual(scene);
  });

  it('非法根输入 → 空场景不崩', () => {
    expect(parseScene(null)).toEqual(emptyScene());
    expect(parseScene('字符串')).toEqual(emptyScene());
    expect(parseScene(42)).toEqual(emptyScene());
  });

  it('未知字段丢弃、枚举回退默认', () => {
    const back = parseScene({
      version: 99,
      evil: '<script>',
      elements: [
        {
          id: 'e1',
          kind: 'spaceship',
          name: '外星',
          color: 'javascript:alert(1)',
          transform: { position: [1, 2, 3], rotation: [0, 0, 0], scale: [1, 1, 1] },
          figure: { bodyType: 'giant', pose: 'fly' },
          extra: true,
        },
      ],
      cameras: [{ id: 'c1', aspect: '21:9', fov: 500 }],
    });
    expect(back.version).toBe(1);
    expect(back.elements).toHaveLength(1);
    expect(back.elements[0].kind).toBe('box');
    expect(back.elements[0].color).toMatch(/^#[0-9a-f]{6}$/);
    expect(back.elements[0].figure).toBeUndefined();
    expect(back.cameras[0].aspect).toBe('16:9');
    expect(back.cameras[0].fov).toBeLessThanOrEqual(FOV_MAX);
  });

  it('数值越界 clamp：位置 ±1000 / scale 0.1..20 / fov 15..90 / 阵列 1..12', () => {
    const back = parseScene({
      elements: [
        {
          kind: 'box',
          transform: { position: [99999, -99999, 'x'], rotation: [0, 0, 0], scale: [99, 0.001, 1] },
        },
        {
          kind: 'crowd',
          crowd: { rows: 20, cols: 0, spacing: 100 },
        },
      ],
      cameras: [{ position: [0, 0, 0], target: [0, 0, 0], fov: 5 }],
    });
    const [p, s] = [back.elements[0].transform.position, back.elements[0].transform.scale];
    expect(p).toEqual([1000, -1000, 0]);
    expect(s).toEqual([20, 0.1, 1]);
    expect(back.elements[1].crowd).toEqual({ rows: 12, cols: 1, spacing: 10 });
    expect(back.cameras[0].fov).toBe(FOV_MIN);
  });

  it('数组超限截断：201 元素→200、17 机位→16', () => {
    const raw = {
      elements: Array.from({ length: MAX_ELEMENTS + 1 }, (_, i) => ({
        kind: 'box',
        name: `b${i}`,
      })),
      cameras: Array.from({ length: MAX_CAMERAS + 1 }, () => ({})),
    };
    const back = parseScene(raw);
    expect(back.elements).toHaveLength(MAX_ELEMENTS);
    expect(back.cameras).toHaveLength(MAX_CAMERAS);
  });

  it('名称截断 30 字 / 机位名 20 字 / 空 名回退默认', () => {
    const long = '甲'.repeat(50);
    const back = parseScene({ elements: [{ kind: 'box', name: long }], cameras: [{ name: long }] });
    expect(back.elements[0].name).toHaveLength(30);
    expect(back.cameras[0].name).toHaveLength(20);
    const back2 = parseScene({ elements: [{ kind: 'box', name: '   ' }] });
    expect(back2.elements[0].name).toBe('元素');
  });
});

describe('sceneModel 序列化与工厂', () => {
  it('serializeScene 超 256KB 抛 SceneTooLargeError', () => {
    const scene = emptyScene();
    scene.elements = Array.from({ length: MAX_ELEMENTS }, (_, i) => ({
      ...createElement('box', i),
      name: `${i}_`.repeat(40),
    }));
    const big = serializeScene(scene);
    expect(big.length).toBeLessThan(256 * 1024);
    const worse = emptyScene();
    worse.elements = Array.from({ length: MAX_ELEMENTS }, (_, i) => ({
      ...createElement('box', i),
      name: `${i}_`.repeat(400),
    }));
    expect(() => serializeScene(worse)).toThrow(SceneTooLargeError);
  });

  it('createElement：figure 带 figure 选项、crowd 带 crowd 选项、spawn 网格偏移', () => {
    const f = createElement('figure', 0);
    expect(f.figure).toEqual({ bodyType: 'adultMale', pose: 'stand' });
    expect(f.transform.position).toEqual([-4, 0, -4]);
    const c = createElement('crowd', 7);
    expect(c.crowd).toEqual({ rows: 4, cols: 4, spacing: 1.5 });
    expect(c.transform.position).toEqual([0, 0, -2]);
  });

  it('createCamera：拷贝位姿不共享引用', () => {
    const pos: [number, number, number] = [1, 2, 3];
    const cam = createCamera(pos, [0, 0, 0], 0);
    pos[0] = 99;
    expect(cam.position[0]).toBe(1);
    expect(cam.name).toBe('机位 1');
  });

  it('cloneScene 深拷贝：改副本不影响原件', () => {
    const scene = fullScene();
    const copy = cloneScene(scene);
    copy.elements[0].transform.position[0] = 777;
    copy.elements[1].crowd!.rows = 1;
    expect(scene.elements[0].transform.position[0]).toBe(1);
    expect(scene.elements[1].crowd!.rows).toBe(12);
  });

  it('色板 12 色 + HEX 校验', () => {
    expect(COLOR_PALETTE).toHaveLength(12);
    COLOR_PALETTE.forEach((c) => expect(isValidHexColor(c)).toBe(true));
    expect(isValidHexColor('#12345')).toBe(false);
    expect(isValidHexColor('#12345g')).toBe(false);
    expect(isValidHexColor('red')).toBe(false);
  });

  it('枚举完整性：8 体型 × 6 姿势', () => {
    expect(BODY_TYPES).toHaveLength(8);
    expect(POSES).toHaveLength(6);
  });
});

describe('sceneModel 截图分辨率与遮幅', () => {
  it('长边封顶 2048 且比例正确（偶数化）', () => {
    expect(resolutionForAspect('16:9')).toEqual([2048, 1152]);
    expect(resolutionForAspect('9:16')).toEqual([1152, 2048]);
    expect(resolutionForAspect('1:1')).toEqual([2048, 2048]);
    expect(resolutionForAspect('4:3')).toEqual([2048, 1536]);
    const [w, h] = resolutionForAspect('2.39:1');
    expect(Math.max(w, h)).toBe(2048);
    expect(Math.abs(w / h - 2.39)).toBeLessThan(0.01);
    expect(w % 2).toBe(0);
    expect(h % 2).toBe(0);
  });

  it('letterbox：宽视口左右留边、高视口上下留边、同比例全框', () => {
    const wide = letterboxRect(1000, 500, '1:1');
    expect(wide).toEqual({ x: 250, y: 0, width: 500, height: 500 });
    const tall = letterboxRect(500, 1000, '1:1');
    expect(tall).toEqual({ x: 0, y: 250, width: 500, height: 500 });
    const exact = letterboxRect(1600, 900, '16:9');
    expect(exact).toEqual({ x: 0, y: 0, width: 1600, height: 900 });
  });

  it('letterbox 框与截图分辨率同比例（WYSIWYG）', () => {
    (['16:9', '9:16', '1:1', '4:3', '2.39:1'] as const).forEach((aspect) => {
      const [rw, rh] = resolutionForAspect(aspect);
      const rect = letterboxRect(1234, 777, aspect);
      expect(rect.width / rect.height).toBeCloseTo(rw / rh, 2);
    });
  });
});

describe('UndoStack', () => {
  function withName(scene: DirectorSceneData, name: string): DirectorSceneData {
    const copy = cloneScene(scene);
    if (copy.elements[0]) copy.elements[0].name = name;
    return copy;
  }

  it('push/undo/redo 基本链路', () => {
    const s0 = fullScene();
    const stack = new UndoStack(s0);
    expect(stack.canUndo).toBe(false);
    stack.push(withName(s0, '第二版'));
    stack.push(withName(s0, '第三版'));
    expect(stack.canUndo).toBe(true);
    expect(stack.undo()!.elements[0].name).toBe('第二版');
    expect(stack.redo()!.elements[0].name).toBe('第三版');
    expect(stack.undo()!.elements[0].name).toBe('第二版');
    expect(stack.undo()!.elements[0].name).toBe('主角');
    expect(stack.undo()).toBeNull();
  });

  it('相同快照去重（不产生空历史）', () => {
    const s0 = fullScene();
    const stack = new UndoStack(s0);
    stack.push(cloneScene(s0));
    expect(stack.canUndo).toBe(false);
  });

  it('推新快照清空 redo（防分叉）', () => {
    const s0 = fullScene();
    const stack = new UndoStack(s0);
    stack.push(withName(s0, 'v2'));
    stack.undo();
    expect(stack.canRedo).toBe(true);
    stack.push(withName(s0, 'v3-分支'));
    expect(stack.canRedo).toBe(false);
    expect(stack.undo()!.elements[0].name).toBe('主角');
  });

  it('50 步上限丢最旧', () => {
    const s0 = fullScene();
    const stack = new UndoStack(s0);
    for (let i = 0; i < 60; i++) stack.push(withName(s0, `v${i}`));
    let count = 0;
    while (stack.undo()) count++;
    expect(count).toBe(50);
    expect(stack.undo()).toBeNull();
  });

  it('undo 返回的是副本（外部改不动栈内态）', () => {
    const s0 = fullScene();
    const stack = new UndoStack(s0);
    stack.push(withName(s0, 'v2'));
    const got = stack.undo()!;
    got.elements[0].name = '篡改';
    const again = stack.redo()!;
    expect(again.elements[0].name).not.toBe('篡改');
  });
});

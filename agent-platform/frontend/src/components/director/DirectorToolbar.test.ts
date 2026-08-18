import { flushPromises, mount } from '@vue/test-utils';
import { NButton } from 'naive-ui';
import { describe, expect, it } from 'vitest';
import DirectorToolbar from './DirectorToolbar.vue';
import { createElement, emptyScene, MAX_ELEMENTS, type DirectorSceneData } from '../../director/sceneModel';

function sceneWith(n: number): DirectorSceneData {
  const scene = emptyScene();
  scene.elements = Array.from({ length: n }, (_, i) => createElement('box', i, { name: `元素${i}` }));
  return scene;
}

function mountToolbar(scene: DirectorSceneData, selectedId: string | null = null) {
  return mount(DirectorToolbar, {
    props: { scene, selectedId },
  });
}

describe('DirectorToolbar', () => {
  it('清单渲染全部元素行（名称/类别）', () => {
    const scene = sceneWith(3);
    const wrapper = mountToolbar(scene);
    const rows = wrapper.findAll('.director-list-row');
    expect(rows).toHaveLength(3);
    expect(rows[0].text()).toContain('元素0');
    expect(rows[0].text()).toContain('方块');
  });

  it('空场景显示引导文案', () => {
    const wrapper = mountToolbar(emptyScene());
    expect(wrapper.find('.director-list-empty').exists()).toBe(true);
  });

  it('添加按钮 emit add（含体型 overrides）', async () => {
    const wrapper = mountToolbar(sceneWith(0));
    const btns = wrapper.findAllComponents(NButton);
    const adultFemale = btns.find((b) => b.text() === '成女');
    expect(adultFemale).toBeDefined();
    await adultFemale!.trigger('click');
    expect(wrapper.emitted('add')![0]).toEqual(['figure', { figure: { bodyType: 'adultFemale', pose: 'stand' } }]);
    const table = btns.find((b) => b.text() === '桌子');
    await table!.trigger('click');
    expect(wrapper.emitted('add')![1]).toEqual(['table']);
  });

  it('200 上限：添加按钮全禁用+提示可见，复制禁用', async () => {
    const wrapper = mountToolbar(sceneWith(MAX_ELEMENTS));
    expect(wrapper.find('.director-limit-tip').exists()).toBe(true);
    const disabled = wrapper.findAllComponents(NButton).filter((b) => b.props('disabled') === true);
    expect(disabled.length).toBeGreaterThan(6);
  });

  it('未达上限：无禁用提示', () => {
    const wrapper = mountToolbar(sceneWith(5));
    expect(wrapper.find('.director-limit-tip').exists()).toBe(false);
  });

  it('点行 emit select；眼睛/复制/删除按钮各自 emit', async () => {
    const scene = sceneWith(2);
    const wrapper = mountToolbar(scene, scene.elements[0].id);
    await wrapper.findAll('.director-list-row')[1].trigger('click');
    expect(wrapper.emitted('select')![0]).toEqual([scene.elements[1].id]);

    const row = wrapper.findAll('.director-list-row')[0];
    const actions = row.findAllComponents(NButton);
    expect(actions).toHaveLength(3);
    await actions[0].trigger('click'); // 显隐
    expect(wrapper.emitted('update-element')![0]).toEqual([scene.elements[0].id, { hidden: true }]);
    await actions[1].trigger('click'); // 复制
    expect(wrapper.emitted('duplicate-element')![0]).toEqual([scene.elements[0].id]);
    await actions[2].trigger('click'); // 删除
    expect(wrapper.emitted('delete-element')![0]).toEqual([scene.elements[0].id]);
  });

  it('阵列表单 emit add crowd 带行列间距', async () => {
    const wrapper = mountToolbar(sceneWith(0));
    const btns = wrapper.findAllComponents(NButton);
    await btns.find((b) => b.text() === '阵列')!.trigger('click');
    expect(wrapper.emitted('add')![0]).toEqual(['crowd', { crowd: { rows: 4, cols: 4, spacing: 1.5 } }]);
    await flushPromises();
  });

  it('选中行高亮 is-selected', () => {
    const scene = sceneWith(2);
    const wrapper = mountToolbar(scene, scene.elements[1].id);
    const rows = wrapper.findAll('.director-list-row');
    expect(rows[1].classes()).toContain('is-selected');
  });
});

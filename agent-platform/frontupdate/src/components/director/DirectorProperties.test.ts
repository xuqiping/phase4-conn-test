import { mount } from '@vue/test-utils';
import { NButton, NInput, NSelect, NSwitch } from 'naive-ui';
import { describe, expect, it } from 'vitest';
import DirectorProperties from './DirectorProperties.vue';
import {
  createElement,
  emptyScene,
  type DirectorSceneData,
} from '../../director/sceneModel';

function figureScene(): DirectorSceneData {
  const scene = emptyScene();
  scene.elements = [createElement('figure', 0, { name: '主角', color: '#e5484d' })];
  return scene;
}

function mountProps(scene: DirectorSceneData, selectedId: string | null) {
  return mount(DirectorProperties, {
    props: { scene, selectedId, transformMode: 'translate' as const },
  });
}

describe('DirectorProperties', () => {
  it('无选中：显示引导，不渲染属性字段', () => {
    const wrapper = mountProps(figureScene(), null);
    expect(wrapper.find('.director-list-empty').exists()).toBe(true);
    expect(wrapper.findAll('.director-prop-field')).toHaveLength(0);
  });

  it('选中 figure：显示名称/颜色/体型/姿势/显示开关', () => {
    const scene = figureScene();
    const wrapper = mountProps(scene, scene.elements[0].id);
    expect(wrapper.text()).toContain('名称');
    expect(wrapper.text()).toContain('体型');
    expect(wrapper.text()).toContain('姿势');
    expect(wrapper.text()).toContain('显示');
  });

  it('选中 crowd：显示行列/间距而非体型姿势', () => {
    const scene = emptyScene();
    scene.elements = [createElement('crowd', 0)];
    const wrapper = mountProps(scene, scene.elements[0].id);
    expect(wrapper.text()).toContain('行 × 列');
    expect(wrapper.text()).toContain('间距');
    expect(wrapper.text()).not.toContain('体型');
  });

  it('色板点击 emit 合法色值', async () => {
    const scene = figureScene();
    const wrapper = mountProps(scene, scene.elements[0].id);
    await wrapper.findAll('.director-swatch')[3].trigger('click');
    expect(wrapper.emitted('update-element')![0]).toEqual([scene.elements[0].id, { color: '#46a758' }]);
  });

  it('HEX 非法输入不回写（数据侧永远合法色）', async () => {
    const scene = figureScene();
    const wrapper = mountProps(scene, scene.elements[0].id);
    const input = wrapper.findComponent(NInput); // 名称输入在前，HEX 在后——取全部再筛
    const hexInput = wrapper.findAllComponents(NInput).find((w) => w.props('placeholder') === '#RRGGBB');
    expect(hexInput).toBeDefined();
    await hexInput!.vm.$emit('update:value', '#12345');
    expect(wrapper.find('.director-prop-error').exists()).toBe(true);
    expect(wrapper.emitted('update-element')).toBeUndefined();
    await hexInput!.vm.$emit('update:value', '#00ff88');
    const events = wrapper.emitted('update-element')!;
    expect(events[events.length - 1]).toEqual([scene.elements[0].id, { color: '#00ff88' }]);
    expect(wrapper.find('.director-prop-error').exists()).toBe(false);
    expect(input.exists()).toBe(true);
  });

  it('显示开关 emit hidden 取反', async () => {
    const scene = figureScene();
    const wrapper = mountProps(scene, scene.elements[0].id);
    const sw = wrapper.findComponent(NSwitch);
    await sw.vm.$emit('update:value', false);
    expect(wrapper.emitted('update-element')![0]).toEqual([scene.elements[0].id, { hidden: true }]);
  });

  it('姿势下拉 emit figure 全量 patch', async () => {
    const scene = figureScene();
    const wrapper = mountProps(scene, scene.elements[0].id);
    const selects = wrapper.findAllComponents(NSelect);
    expect(selects).toHaveLength(2);
    await selects[1].vm.$emit('update:value', 'sit');
    expect(wrapper.emitted('update-element')![0]).toEqual([
      scene.elements[0].id,
      { figure: { bodyType: 'adultMale', pose: 'sit' } },
    ]);
  });

  it('V/R/S 模式按钮 emit set-transform-mode', async () => {
    const scene = figureScene();
    const wrapper = mountProps(scene, scene.elements[0].id);
    const btns = wrapper.findAllComponents(NButton);
    await btns.find((b) => b.text() === '旋转 R')!.trigger('click');
    expect(wrapper.emitted('set-transform-mode')![0]).toEqual(['rotate']);
  });
});

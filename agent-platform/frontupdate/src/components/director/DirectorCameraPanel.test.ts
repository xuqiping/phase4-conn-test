import { mount } from '@vue/test-utils';
import { NButton, NInput, NSelect, NSlider } from 'naive-ui';
import { describe, expect, it } from 'vitest';
import DirectorCameraPanel from './DirectorCameraPanel.vue';
import { createCamera, emptyScene, MAX_CAMERAS, type DirectorSceneData } from '../../director/sceneModel';

function camsScene(n: number): DirectorSceneData {
  const scene = emptyScene();
  scene.cameras = Array.from({ length: n }, (_, i) =>
    createCamera([i, 2, 5], [0, 1, 0], i, { name: `机位${i + 1}` }),
  );
  return scene;
}

function mountPanel(scene: DirectorSceneData, selected: string | null = null, active: string | null = null) {
  return mount(DirectorCameraPanel, {
    props: { scene, selectedCameraId: selected, activeCameraId: active },
  });
}

describe('DirectorCameraPanel', () => {
  it('机位列表渲染与空态引导', () => {
    const empty = mountPanel(camsScene(0));
    expect(empty.find('.director-list-empty').exists()).toBe(true);
    const wrapper = mountPanel(camsScene(2));
    expect(wrapper.findAll('.director-camera-row')).toHaveLength(2);
    // naive-ui input 的值挂在内层 <input>.value 上，text() 拿不到
    const inputs = wrapper.findAll('input');
    expect(inputs.map((i) => (i.element as HTMLInputElement).value)).toEqual(['机位1', '机位2']);
  });

  it('16 上限：新增禁用+提示', () => {
    const wrapper = mountPanel(camsScene(MAX_CAMERAS));
    expect(wrapper.find('.director-limit-tip').exists()).toBe(true);
    const add = wrapper.findAllComponents(NButton).find((b) => b.text().includes('从当前视角'));
    expect(add?.props('disabled')).toBe(true);
  });

  it('「从当前视角新增机位」emit add-from-view；导演视角按钮仅机位视角显示', async () => {
    const scene = camsScene(1);
    const wrapper = mountPanel(scene);
    const add = wrapper.findAllComponents(NButton).find((b) => b.text().includes('从当前视角'))!;
    await add.trigger('click');
    expect(wrapper.emitted('add-from-view')).toHaveLength(1);
    expect(wrapper.text()).not.toContain('导演视角');
    const viewing = mountPanel(scene, null, scene.cameras[0].id);
    expect(viewing.text()).toContain('导演视角');
  });

  it('FOV 滑杆/画幅下拉 emit update-camera', async () => {
    const scene = camsScene(1);
    const wrapper = mountPanel(scene);
    await wrapper.findComponent(NSlider).vm.$emit('update:value', 66);
    expect(wrapper.emitted('update-camera')![0]).toEqual([scene.cameras[0].id, { fov: 66 }]);
    await wrapper.findComponent(NSelect).vm.$emit('update:value', '9:16');
    expect(wrapper.emitted('update-camera')![1]).toEqual([scene.cameras[0].id, { aspect: '9:16' }]);
  });

  it('改名 emit name；查看/删除各自 emit', async () => {
    const scene = camsScene(1);
    const wrapper = mountPanel(scene, scene.cameras[0].id);
    await wrapper.findComponent(NInput).vm.$emit('update:value', '主机位');
    expect(wrapper.emitted('update-camera')![0]).toEqual([scene.cameras[0].id, { name: '主机位' }]);
    const btns = wrapper.findAllComponents(NButton);
    await btns.find((b) => b.text() === '看')!.trigger('click');
    expect(wrapper.emitted('view-camera')![0]).toEqual([scene.cameras[0].id]);
    await btns.find((b) => b.text() === '删')!.trigger('click');
    expect(wrapper.emitted('delete-camera')![0]).toEqual([scene.cameras[0].id]);
  });

  it('「导演视角」按钮 emit view-camera null', async () => {
    const scene = camsScene(1);
    const wrapper = mountPanel(scene, null, scene.cameras[0].id);
    await wrapper.findAllComponents(NButton).find((b) => b.text() === '导演视角')!.trigger('click');
    expect(wrapper.emitted('view-camera')![0]).toEqual([null]);
  });

  it('选中/查看中行高亮', () => {
    const scene = camsScene(2);
    const wrapper = mountPanel(scene, scene.cameras[1].id, scene.cameras[0].id);
    const rows = wrapper.findAll('.director-camera-row');
    expect(rows[1].classes()).toContain('is-selected');
    expect(rows[0].classes()).toContain('is-viewing');
  });
});

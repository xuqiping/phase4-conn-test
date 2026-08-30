<template>
  <div class="director-camera-panel">
    <div class="director-camera-toolbar">
      <n-button size="tiny" type="primary" :disabled="atLimit" @click="emit('add-from-view')">
        从当前视角新增机位
      </n-button>
      <n-button v-if="activeCameraId" size="tiny" @click="emit('view-camera', null)">导演视角</n-button>
      <span class="director-camera-count">{{ scene.cameras.length }}/16</span>
    </div>
    <div v-if="atLimit" class="director-limit-tip">已达机位上限 16</div>

    <div
      v-for="cam in scene.cameras"
      :key="cam.id"
      class="director-camera-row"
      :class="{ 'is-selected': cam.id === selectedCameraId, 'is-viewing': cam.id === activeCameraId }"
      :data-camera-id="cam.id"
      @click="emit('select-camera', cam.id)"
    >
      <div class="director-camera-row-head">
        <n-input
          :value="cam.name"
          size="tiny"
          maxlength="20"
          :placeholder="`机位名≤${20}`"
          @update:value="(v: string) => patch(cam.id, { name: v })"
          @click.stop
        />
        <n-button text size="tiny" title="查看该机位" @click.stop="emit('view-camera', cam.id)">看</n-button>
        <n-button text size="tiny" title="删除机位" @click.stop="emit('delete-camera', cam.id)">删</n-button>
      </div>
      <div class="director-camera-row-body">
        <label>FOV {{ cam.fov }}°</label>
        <n-slider
          :value="cam.fov"
          :min="15"
          :max="90"
          :step="1"
          :format-tooltip="(v: number) => `${v}°`"
          @update:value="(v: number) => patch(cam.id, { fov: v })"
        />
        <n-select
          :value="cam.aspect"
          size="tiny"
          :options="aspectOptions"
          @update:value="(v: AspectKey) => patch(cam.id, { aspect: v })"
        />
      </div>
    </div>
    <div v-if="scene.cameras.length === 0" class="director-list-empty">
      先在导演视角环视到想要的构图，再「从当前视角新增机位」
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 机位面板：列表≤16/改名≤20/FOV 15..90/画幅 5 枚举/查看/删除。
 * 「从当前视角新增机位」emit 给 modal（modal 经 viewport.getDirectorPose 取位姿）。
 * FOV 拖动中不推 undo 栈（modal 侧防抖），松手由防抖统一落。
 */
import { computed } from 'vue';
import { NButton, NInput, NSelect, NSlider } from 'naive-ui';
import {
  ASPECT_KEYS,
  MAX_CAMERAS,
  type AspectKey,
  type DirectorCameraData,
  type DirectorSceneData,
} from '../../director/sceneModel';

const props = defineProps<{
  scene: DirectorSceneData;
  selectedCameraId: string | null;
  activeCameraId: string | null;
}>();

const emit = defineEmits<{
  (e: 'add-from-view'): void;
  (e: 'update-camera', id: string, patch: Partial<DirectorCameraData>): void;
  (e: 'delete-camera', id: string): void;
  (e: 'view-camera', id: string | null): void;
  (e: 'select-camera', id: string): void;
}>();

const atLimit = computed(() => props.scene.cameras.length >= MAX_CAMERAS);

const aspectOptions = ASPECT_KEYS.map((a) => ({ label: a, value: a }));

function patch(id: string, p: Partial<DirectorCameraData>): void {
  emit('update-camera', id, p);
}
</script>

<style scoped lang="scss" src="../../styles/director.panels.scss" />

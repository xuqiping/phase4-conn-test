<template>
  <aside class="director-properties">
    <template v-if="selected">
      <h4 class="director-panel-title">元素属性</h4>
      <div class="director-prop-field">
        <label>名称</label>
        <n-input
          :value="selected.name"
          size="small"
          maxlength="30"
          show-count
          placeholder="≤30 字"
          @update:value="(v: string) => patch({ name: v })"
        />
      </div>
      <div class="director-prop-field">
        <label>颜色</label>
        <div class="director-palette">
          <button
            v-for="c in COLOR_PALETTE"
            :key="c"
            type="button"
            class="director-swatch"
            :class="{ 'is-active': selected.color === c }"
            :style="{ background: c }"
            :title="c"
            @click="patch({ color: c })"
          />
        </div>
        <n-input
          :value="hexInput"
          size="small"
          placeholder="#RRGGBB"
          :status="hexError ? 'error' : undefined"
          @update:value="onHexInput"
        />
        <span v-if="hexError" class="director-prop-error">格式须为 #RRGGBB</span>
      </div>

      <template v-if="selected.kind === 'figure'">
        <div class="director-prop-field">
          <label>体型</label>
          <n-select
            :value="selected.figure?.bodyType"
            :options="bodyOptions"
            size="small"
            @update:value="setBodyType"
          />
        </div>
        <div class="director-prop-field">
          <label>姿势</label>
          <n-select
            :value="selected.figure?.pose"
            :options="poseOptions"
            size="small"
            @update:value="setPose"
          />
        </div>
      </template>

      <template v-if="selected.kind === 'crowd'">
        <div class="director-prop-field">
          <label>行 × 列</label>
          <div class="director-crowd-edit">
            <n-input-number
              :value="selected.crowd?.rows"
              size="small"
              :min="1"
              :max="12"
              @update:value="(v: number | null) => patchCrowd({ rows: v ?? 1 })"
            />
            <n-input-number
              :value="selected.crowd?.cols"
              size="small"
              :min="1"
              :max="12"
              @update:value="(v: number | null) => patchCrowd({ cols: v ?? 1 })"
            />
          </div>
        </div>
        <div class="director-prop-field">
          <label>间距</label>
          <n-input-number
            :value="selected.crowd?.spacing"
            size="small"
            :min="0.5"
            :max="10"
            :step="0.5"
            @update:value="(v: number | null) => patchCrowd({ spacing: v ?? 1.5 })"
          />
        </div>
      </template>

      <div class="director-prop-field">
        <label>显示</label>
        <n-switch :value="!selected.hidden" size="small" @update:value="(v: boolean) => patch({ hidden: !v })" />
        <span class="director-prop-hint">隐藏的元素不参与渲染与截图，数据保留</span>
      </div>
    </template>
    <template v-else>
      <h4 class="director-panel-title">元素属性</h4>
      <div class="director-list-empty">在视口或清单中点选元素后编辑属性</div>
    </template>

    <div class="director-gizmo-modes">
      <span class="director-add-label">操作模式</span>
      <div class="director-mode-row">
        <n-button size="tiny" :type="transformMode === 'translate' ? 'primary' : 'default'" @click="emit('set-transform-mode', 'translate')">移动 V</n-button>
        <n-button size="tiny" :type="transformMode === 'rotate' ? 'primary' : 'default'" @click="emit('set-transform-mode', 'rotate')">旋转 R</n-button>
        <n-button size="tiny" :type="transformMode === 'scale' ? 'primary' : 'default'" @click="emit('set-transform-mode', 'scale')">缩放 S</n-button>
      </div>
      <span class="director-prop-hint">缩放模式按住 Shift = 中心手柄等比</span>
    </div>
  </aside>
</template>

<script setup lang="ts">
/**
 * 右栏：选中元素属性（名称≤30/12色板+HEX校验/体型/姿势/阵列参数/显示开关）+ V/R/S 操作模式。
 * HEX 非法输入不回写模型，只标红提示（数据侧永远是合法色）。
 */
import { computed, ref, watch } from 'vue';
import { NButton, NInput, NInputNumber, NSelect, NSwitch } from 'naive-ui';
import { BODY_PRESETS, POSE_PRESETS } from '../../director/figurePresets';
import {
  BODY_TYPES,
  COLOR_PALETTE,
  POSES,
  isValidHexColor,
  type BodyType,
  type CrowdOptions,
  type DirectorElement,
  type DirectorSceneData,
  type Pose,
} from '../../director/sceneModel';

const props = defineProps<{
  scene: DirectorSceneData;
  selectedId: string | null;
  transformMode: 'translate' | 'rotate' | 'scale';
}>();

const emit = defineEmits<{
  (e: 'update-element', id: string, patch: Partial<DirectorElement>): void;
  (e: 'set-transform-mode', mode: 'translate' | 'rotate' | 'scale'): void;
}>();

const selected = computed(() => props.scene.elements.find((el) => el.id === props.selectedId) ?? null);

const hexInput = ref('');
const hexError = ref(false);

watch(
  selected,
  (el) => {
    hexInput.value = el?.color ?? '';
    hexError.value = false;
  },
  { immediate: true },
);

function patch(p: Partial<DirectorElement>): void {
  if (props.selectedId) emit('update-element', props.selectedId, p);
}

function patchCrowd(p: Partial<CrowdOptions>): void {
  const el = selected.value;
  if (!el?.crowd) return;
  patch({ crowd: { ...el.crowd, ...p } });
}

function setBodyType(v: BodyType): void {
  const el = selected.value;
  if (el?.kind === 'figure') {
    patch({ figure: { bodyType: v, pose: el.figure?.pose ?? 'stand' } });
  }
}

function setPose(v: Pose): void {
  const el = selected.value;
  if (el?.kind === 'figure') {
    patch({ figure: { bodyType: el.figure?.bodyType ?? 'adultMale', pose: v } });
  }
}

function onHexInput(v: string): void {
  hexInput.value = v;
  if (isValidHexColor(v)) {
    hexError.value = false;
    patch({ color: v.toLowerCase() });
  } else {
    hexError.value = v.length > 0;
  }
}

const bodyOptions = BODY_TYPES.map((bt) => ({ label: BODY_PRESETS[bt].label, value: bt }));
const poseOptions = POSES.map((p) => ({ label: POSE_PRESETS[p].label, value: p }));
</script>

<style scoped lang="scss" src="../../styles/director.panels.scss" />

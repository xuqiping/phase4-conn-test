<template>
  <aside class="director-toolbar">
    <section class="director-add">
      <h4 class="director-panel-title">添加元素</h4>
      <div class="director-add-group">
        <span class="director-add-label">人偶素模</span>
        <div class="director-add-grid">
          <n-button
            v-for="bt in BODY_TYPES"
            :key="bt"
            size="tiny"
            :disabled="atLimit"
            :title="`${BODY_PRESETS[bt].label}（身高 ${BODY_PRESETS[bt].height}m）`"
            @click="emit('add', 'figure', { figure: { bodyType: bt, pose: 'stand' } })"
          >
            {{ BODY_PRESETS[bt].label }}
          </n-button>
        </div>
      </div>
      <div class="director-add-group">
        <span class="director-add-label">基础几何</span>
        <div class="director-add-grid">
          <n-button size="tiny" :disabled="atLimit" @click="emit('add', 'box')">方块</n-button>
          <n-button size="tiny" :disabled="atLimit" @click="emit('add', 'cylinder')">圆柱</n-button>
          <n-button size="tiny" :disabled="atLimit" @click="emit('add', 'plate')">平板</n-button>
          <n-button size="tiny" :disabled="atLimit" @click="emit('add', 'table')">桌子</n-button>
          <n-button size="tiny" :disabled="atLimit" @click="emit('add', 'chair')">椅子</n-button>
        </div>
      </div>
      <div class="director-add-group">
        <span class="director-add-label">群众阵列</span>
        <div class="director-crowd-form">
          <n-input-number v-model:value="crowdRows" size="tiny" :min="1" :max="12" :disabled="atLimit">
            <template #prefix>行</template>
          </n-input-number>
          <n-input-number v-model:value="crowdCols" size="tiny" :min="1" :max="12" :disabled="atLimit">
            <template #prefix>列</template>
          </n-input-number>
          <n-input-number v-model:value="crowdSpacing" size="tiny" :min="0.5" :max="10" :step="0.5" :disabled="atLimit">
            <template #prefix>距</template>
          </n-input-number>
          <n-button size="tiny" :disabled="atLimit" @click="addCrowd">阵列</n-button>
        </div>
      </div>
      <div v-if="atLimit" class="director-limit-tip">已达元素上限 200，无法继续添加</div>
    </section>

    <section class="director-list">
      <h4 class="director-panel-title">元素清单（{{ scene.elements.length }}/200）</h4>
      <div
        v-for="el in scene.elements"
        :key="el.id"
        class="director-list-row"
        :class="{ 'is-selected': el.id === selectedId, 'is-hidden': el.hidden }"
        :data-element-id="el.id"
        :ref="(ins) => setRowRef(el.id, ins)"
        @click="emit('select', el.id)"
      >
        <span class="director-list-swatch" :style="{ background: el.color }" />
        <span class="director-list-name">{{ el.name }}</span>
        <span class="director-list-kind">{{ kindLabel(el.kind) }}</span>
        <span class="director-list-actions" @click.stop>
          <n-button text size="tiny" :title="el.hidden ? '显示' : '隐藏'" @click="emit('update-element', el.id, { hidden: !el.hidden })">
            {{ el.hidden ? '隐' : '显' }}
          </n-button>
          <n-button text size="tiny" title="复制" :disabled="atLimit" @click="emit('duplicate-element', el.id)">复</n-button>
          <n-button text size="tiny" title="删除" @click="emit('delete-element', el.id)">删</n-button>
        </span>
      </div>
      <div v-if="scene.elements.length === 0" class="director-list-empty">
        从上方添加人偶、几何或阵列开始构图
      </div>
    </section>
  </aside>
</template>

<script setup lang="ts">
/**
 * 左栏：添加面板（8 体型/5 几何/阵列参数表单）+ 元素清单（点选联动视口 gizmo）。
 * 纯展示与 emit，不持有场景状态（单一数据源在 modal）。
 */
import { computed, ref } from 'vue';
import { NButton, NInputNumber } from 'naive-ui';
import { BODY_PRESETS } from '../../director/figurePresets';
import {
  BODY_TYPES,
  MAX_ELEMENTS,
  type DirectorElement,
  type DirectorSceneData,
  type ElementKind,
} from '../../director/sceneModel';

const props = defineProps<{
  scene: DirectorSceneData;
  selectedId: string | null;
}>();

const emit = defineEmits<{
  (e: 'add', kind: ElementKind, overrides?: Partial<DirectorElement>): void;
  (e: 'select', id: string | null): void;
  (e: 'update-element', id: string, patch: Partial<DirectorElement>): void;
  (e: 'delete-element', id: string): void;
  (e: 'duplicate-element', id: string): void;
}>();

const atLimit = computed(() => props.scene.elements.length >= MAX_ELEMENTS);

const crowdRows = ref(4);
const crowdCols = ref(4);
const crowdSpacing = ref(1.5);

function addCrowd(): void {
  emit('add', 'crowd', {
    crowd: { rows: crowdRows.value, cols: crowdCols.value, spacing: crowdSpacing.value },
  });
}

const KIND_LABELS: Record<ElementKind, string> = {
  figure: '人偶',
  box: '方块',
  cylinder: '圆柱',
  plate: '平板',
  table: '桌',
  chair: '椅',
  crowd: '阵列',
};

function kindLabel(kind: ElementKind): string {
  return KIND_LABELS[kind] ?? kind;
}

// 选中行滚动到可见（视口点选 → 清单高亮滚动联动 L2 反向）
const rowRefs = new Map<string, HTMLElement>();
function setRowRef(id: string, ins: unknown): void {
  if (ins) rowRefs.set(id, ins as HTMLElement);
  else rowRefs.delete(id);
}

defineExpose({
  scrollToSelected(): void {
    if (props.selectedId) rowRefs.get(props.selectedId)?.scrollIntoView({ block: 'nearest' });
  },
});
</script>

<style scoped lang="scss" src="../../styles/director.panels.scss" />

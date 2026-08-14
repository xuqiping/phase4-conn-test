<script setup lang="ts">
import { computed, h } from 'vue'
import { NDropdown, NButton, NIcon } from 'naive-ui'
import { ColorPaletteOutline } from '@vicons/ionicons5'
import { useTheme } from '@/theme/useTheme'
import { THEMES, type ThemeMeta } from '@/theme/themes'
import type { ThemeKey } from '@/stores/theme'

const { current, setTheme } = useTheme()

// 每个主题选项渲染 3 个预览色点 + 名称 + 描述
function renderOption(meta: ThemeMeta) {
  return h('div', { class: 'theme-option' }, [
    h(
      'span',
      { class: 'theme-option__swatches' },
      meta.swatches.map((c) =>
        h('i', { class: 'theme-option__dot', style: { background: c } })
      )
    ),
    h('span', { class: 'theme-option__text' }, [
      h('b', null, meta.name),
      h('small', null, meta.desc)
    ])
  ])
}

const options = computed(() =>
  THEMES.map((t) => ({
    label: () => renderOption(t),
    key: t.key as string
  }))
)

function onSelect(key: string) {
  setTheme(key as ThemeKey)
}
</script>

<template>
  <n-dropdown
    trigger="click"
    :options="options"
    :value="current"
    class="theme-switcher"
    @select="onSelect"
  >
    <n-button quaternary aria-label="切换主题">
      <template #icon>
        <n-icon :size="18"><ColorPaletteOutline /></n-icon>
      </template>
      主题
    </n-button>
  </n-dropdown>
</template>

<style lang="scss">
/* 下拉选项内容（渲染在 body 下，须非 scoped） */
.theme-option {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-1) 0;
  min-width: 200px;

  &__swatches {
    display: inline-flex;
    gap: 3px;
  }

  &__dot {
    width: 12px;
    height: 12px;
    border-radius: 50%;
    border: 1px solid rgba(255, 255, 255, 0.15);
  }

  &__text {
    display: flex;
    flex-direction: column;
    line-height: 1.3;

    small {
      color: var(--tx-3);
      font-size: var(--fs-xs);
    }
  }
}
</style>

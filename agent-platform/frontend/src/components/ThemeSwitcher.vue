<template>
  <n-modal
    :show="show"
    preset="card"
    title="选择主题"
    :style="{ maxWidth: '400px', width: '90vw' }"
    :bordered="false"
    :mask-closable="true"
    @update:show="$emit('update:show', $event)"
  >
    <div class="theme-switcher">
      <div
        v-for="theme in themeList"
        :key="theme.name"
        class="theme-switcher__item"
        :class="{ 'theme-switcher__item--active': currentTheme === theme.name }"
        @click="selectTheme(theme.name)"
      >
        <!-- 选中指示器 -->
        <div class="theme-switcher__indicator">
          <div
            v-if="currentTheme === theme.name"
            class="theme-switcher__check"
          >
            <n-icon size="14" :component="CheckmarkOutline" color="#fff" />
          </div>
        </div>

        <!-- 色块预览 -->
        <div class="theme-switcher__preview">
          <div
            class="theme-switcher__swatch"
            :style="{ background: theme.colors.bg }"
          >
            <span
              class="theme-switcher__swatch-accent"
              :style="{ background: `linear-gradient(135deg, ${theme.colors.gradientStart}, ${theme.colors.gradientEnd})` }"
            ></span>
          </div>
        </div>

        <!-- 主题信息 -->
        <div class="theme-switcher__info">
          <div class="theme-switcher__name">{{ theme.label }}</div>
          <div class="theme-switcher__desc">{{ theme.description }}</div>
        </div>
      </div>
    </div>
  </n-modal>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { NModal, NIcon } from 'naive-ui'
import { CheckmarkOutline } from '@vicons/ionicons5'
import { useThemeStore, THEME_LIST, type ThemeName } from '@/stores/theme'

const props = defineProps<{
  show: boolean
}>()

const emit = defineEmits<{
  'update:show': [value: boolean]
}>()

const themeStore = useThemeStore()
const currentTheme = computed(() => themeStore.currentTheme)
const themeList = THEME_LIST

/** 选择主题 */
function selectTheme(name: ThemeName) {
  themeStore.setTheme(name)
  // 选择后自动关闭弹窗
  emit('update:show', false)
}
</script>

<style lang="scss" scoped>
.theme-switcher {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-3);
  padding: var(--spacing-2) 0;
}

.theme-switcher__item {
  display: flex;
  align-items: center;
  gap: var(--spacing-3);
  padding: var(--spacing-3) var(--spacing-4);
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-in-out);

  &:hover {
    border-color: var(--color-primary);
    background: var(--color-primary-light);
  }

  &--active {
    border-color: var(--color-primary);
    background: var(--color-primary-light);
  }
}

// 选中指示器
.theme-switcher__indicator {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.theme-switcher__check {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--color-primary);
  display: flex;
  align-items: center;
  justify-content: center;
}

// 色块预览
.theme-switcher__preview {
  flex-shrink: 0;
}

.theme-switcher__swatch {
  width: 48px;
  height: 32px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.theme-switcher__swatch-accent {
  width: 24px;
  height: 16px;
  border-radius: 3px;
}

// 主题信息
.theme-switcher__info {
  flex: 1;
  min-width: 0;
}

.theme-switcher__name {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  margin-bottom: 2px;
}

.theme-switcher__desc {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>

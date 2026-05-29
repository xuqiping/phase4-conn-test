<template>
  <div class="process-filter">
    <div class="filter-header">
      <span class="filter-label">{{ t('process.filter_categoryLabel') }}</span>
      <select
        v-model="selectedCategory"
      class="category-select"
        @change="handleCategoryChange"
      >
        <option value="All">{{ t('process.category_All') }} ({{ processStore.categoryCounts.All }})</option>
        <option value="Browser">{{ t('process.category_Browser') }} ({{ processStore.categoryCounts.Browser }})</option>
        <option value="Office">{{ t('process.category_Office') }} ({{ processStore.categoryCounts.Office }})</option>
        <option value="Explorer">{{ t('process.category_Explorer') }} ({{ processStore.categoryCounts.Explorer }})</option>
        <option value="Terminal">{{ t('process.category_Terminal') }} ({{ processStore.categoryCounts.Terminal }})</option>
        <option value="Archive">{{ t('process.category_Archive') }} ({{ processStore.categoryCounts.Archive }})</option>
        <option value="Document">{{ t('process.category_Document') }} ({{ processStore.categoryCounts.Document }})</option>
        <option value="Media">{{ t('process.category_Media') }} ({{ processStore.categoryCounts.Media }})</option>
        <option value="Image">{{ t('process.category_Image') }} ({{ processStore.categoryCounts.Image }})</option>
        <option value="Communication">{{ t('process.category_Communication') }} ({{ processStore.categoryCounts.Communication }})</option>
        <option value="Download">{{ t('process.category_Download') }} ({{ processStore.categoryCounts.Download }})</option>
        <option value="Game">{{ t('process.category_Game') }} ({{ processStore.categoryCounts.Game }})</option>
        <option value="System">{{ t('process.category_System') }} ({{ processStore.categoryCounts.System }})</option>
        <option value="Other">{{ t('process.category_Other') }} ({{ processStore.categoryCounts.Other }})</option>
      </select>
    </div>

    <div class="quick-filters">
      <button
        v-for="category in quickFilterCategories"
        :key="category"
        class="quick-filter-btn"
        :class="{ active: selectedCategory === category }"
      @click="selectQuickFilter(category)"
      >
        {{ t(`process.category_${category}`) }} ({{ processStore.categoryCounts[category] }})
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useProcessStore } from '../stores/processStore'
import { useI18n } from '../composables/useI18n'
import type { ProcessCategory } from '../types/process'

const processStore = useProcessStore()
const { t } = useI18n()

const selectedCategory = ref<ProcessCategory>('All')

const quickFilterCategories: ProcessCategory[] = [
  'All',
  'Browser',
  'Office',
  'Explorer',
  'Terminal'
]

function handleCategoryChange() {
  processStore.setCategory(selectedCategory.value)
}

function selectQuickFilter(category: ProcessCategory) {
  selectedCategory.value = category
  processStore.setCategory(category)
}

// Sync with store
watch(() => processStore.currentCategory, (newCategory) => {
  selectedCategory.value = newCategory
})
</script>

<style scoped>
.process-filter {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  padding: 0.75rem;
  background: var(--bg-secondary);
  border-radius: 0.5rem;
}

.filter-header {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.filter-label {
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--text-primary);
}

.category-select {
  flex: 1;
  max-width: 300px;
  padding: 0.5rem;
  border: 1px solid var(--border-color);
  background: var(--bg-primary);
  color: var(--text-primary);
  border-radius: 0.375rem;
  font-size: 0.875rem;
  cursor: pointer;
  transition: all 0.2s;
}

.category-select:hover {
  border-color: var(--border-hover);
}

.category-select:focus {
  outline: none;
  border-color: var(--primary-color);
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
}
.quick-filters {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.quick-filter-btn {
  padding: 0.375rem 0.75rem;
  border: 1px solid var(--border-color);
  background: var(--bg-primary);
  color: var(--text-secondary);
  border-radius: 0.375rem;
  font-size: 0.8125rem;
  cursor: pointer;
  transition: all 0.2s;
}

.quick-filter-btn:hover {
  background: var(--bg-hover);
  border-color: var(--border-hover);
  color: var(--text-primary);
}

.quick-filter-btn.active {
  background: var(--accent-subtle-bg);
  color: var(--accent-subtle-text);
  border-color: var(--accent-subtle-border);
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--accent-subtle-border) 65%, transparent);
}

.quick-filter-btn.active:hover {
  background: var(--accent-subtle-hover);
  border-color: var(--accent-subtle-border);
  color: var(--accent-subtle-text);
}
</style>

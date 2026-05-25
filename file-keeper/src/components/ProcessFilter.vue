<template>
  <div class="process-filter">
    <div class="filter-header">
      <span class="filter-label">Category:</span>
      <select
        v-model="selectedCategory"
      class="category-select"
        @change="handleCategoryChange"
      >
        <option value="All">All ({{ processStore.categoryCounts.All }})</option>
        <option value="Browser">Browser ({{ processStore.categoryCounts.Browser }})</option>
        <option value="Office">Office ({{ processStore.categoryCounts.Office }})</option>
        <option value="Explorer">Explorer ({{ processStore.categoryCounts.Explorer }})</option>
        <option value="Terminal">Terminal ({{ processStore.categoryCounts.Terminal }})</option>
        <option value="Archive">Archive ({{ processStore.categoryCounts.Archive }})</option>
        <option value="Document">Document ({{ processStore.categoryCounts.Document }})</option>
        <option value="Media">Media ({{ processStore.categoryCounts.Media }})</option>
        <option value="Image">Image ({{ processStore.categoryCounts.Image }})</option>
        <option value="Communication">Communication ({{ processStore.categoryCounts.Communication }})</option>
        <option value="Download">Download ({{ processStore.categoryCounts.Download }})</option>
        <option value="Game">Game ({{ processStore.categoryCounts.Game }})</option>
        <option value="System">System ({{ processStore.categoryCounts.System }})</option>
        <option value="Other">Other ({{ processStore.categoryCounts.Other }})</option>
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
        {{ category }} ({{ processStore.categoryCounts[category] }})
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useProcessStore } from '../stores/processStore'
import type { ProcessCategory } from '../types/process'

const processStore = useProcessStore()

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
  background: var(--primary-color);
  color: white;
  border-color: var(--primary-color);
}
</style>

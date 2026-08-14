<script setup lang="ts">
import { computed, ref } from 'vue'
import { NInput } from 'naive-ui'
import AgentCard from '@/components/agent/AgentCard.vue'
import { agents, ALL_TAGS } from '@/mocks/agents'

const keyword = ref('')
const activeTags = ref<string[]>([])

function toggleTag(t: string) {
  const i = activeTags.value.indexOf(t)
  if (i >= 0) activeTags.value.splice(i, 1)
  else activeTags.value.push(t)
}

// 内存过滤：名称/描述命中 + 标签「或」关系（L6）
const filtered = computed(() => {
  const kw = keyword.value.trim()
  return agents.filter((a) => {
    const hitKw = !kw || a.name.includes(kw) || a.desc.includes(kw)
    const hitTag = !activeTags.value.length || a.tags.some((t) => activeTags.value.includes(t))
    return hitKw && hitTag
  })
})
</script>

<template>
  <div class="agent-hall">
    <div class="agent-hall__bar">
      <n-input
        v-model:value="keyword"
        placeholder="搜索智能体…"
        clearable
        class="agent-hall__search"
      />
      <div class="agent-hall__tags">
        <button
          v-for="t in ALL_TAGS"
          :key="t"
          class="agent-hall__tag"
          :class="{ 'agent-hall__tag--on': activeTags.includes(t) }"
          @click="toggleTag(t)"
        >
          {{ t }}
        </button>
      </div>
    </div>

    <div v-if="filtered.length" class="agent-hall__grid">
      <AgentCard v-for="a in filtered" :key="a.id" :agent="a" />
    </div>
    <div v-else class="agent-hall__empty">
      <p>没有匹配的智能体</p>
      <p class="agent-hall__empty-sub">换个关键词或取消标签试试</p>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.agent-hall {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--sp-6);

  &__bar {
    display: flex;
    flex-direction: column;
    gap: var(--sp-3);
    margin-bottom: var(--sp-5);
  }

  &__search {
    max-width: 320px;
  }

  &__tags {
    display: flex;
    flex-wrap: wrap;
    gap: var(--sp-2);
  }

  &__tag {
    padding: 3px 12px;
    border-radius: 99px;
    border: 1px solid var(--line-1);
    background: transparent;
    color: var(--tx-2);
    font-size: var(--fs-xs);
    cursor: pointer;
    transition: all var(--d-fast) var(--ease);

    &:hover {
      border-color: var(--line-2);
      color: var(--tx-1);
    }

    &--on {
      background: color-mix(in srgb, var(--accent) 18%, transparent);
      border-color: var(--accent);
      color: var(--tx-1);
    }
  }

  &__grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
    gap: var(--sp-4);
  }

  &__empty {
    padding: var(--sp-8);
    text-align: center;
    color: var(--tx-2);
  }

  &__empty-sub {
    color: var(--tx-3);
    font-size: var(--fs-sm);
  }
}
</style>

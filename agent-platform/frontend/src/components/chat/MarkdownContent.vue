<template>
  <!-- 9x#13：助手正文 markdown 渲染（html:false → v-html 安全） -->
  <div class="markdown-content" v-html="html" />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { renderMarkdown } from '@/utils/markdown'

const props = defineProps<{ text: string }>()
const html = computed(() => renderMarkdown(props.text))
</script>

<style lang="scss" scoped>
/* v-html 内容不受 scoped 属性选择器覆盖，需 :deep 穿透 */
.markdown-content {
  font-size: 14px;
  color: var(--color-text-primary);
  line-height: 1.6;
  word-break: break-word;

  :deep(p) {
    margin: 0 0 8px;

    &:last-child {
      margin-bottom: 0;
    }
  }

  :deep(h1),
  :deep(h2),
  :deep(h3),
  :deep(h4),
  :deep(h5),
  :deep(h6) {
    margin: 14px 0 8px;
    line-height: 1.35;
    color: var(--color-text-primary);

    &:first-child {
      margin-top: 0;
    }
  }

  /* 大标题用平台标题字（霞鹜文楷），与页头/模块标题同源，去掉"默认 markdown 渲染"的割裂感 */
  :deep(h1),
  :deep(h2),
  :deep(h3) {
    font-family: var(--font-display);
    font-weight: 600;
  }

  :deep(h1) { font-size: 20px; }
  :deep(h2) { font-size: 18px; }
  :deep(h3) { font-size: 16px; }
  :deep(h4) { font-size: 15px; }

  :deep(ul),
  :deep(ol) {
    margin: 6px 0 10px;
    padding-left: 22px;
  }

  :deep(li) {
    margin: 3px 0;
  }

  :deep(blockquote) {
    margin: 8px 0;
    padding: 6px 12px;
    border-left: 3px solid var(--color-primary);
    background: color-mix(in srgb, var(--color-primary) 5%, transparent);
    color: var(--color-text-secondary);
  }

  :deep(code) {
    font-family: Consolas, Monaco, 'Courier New', monospace;
    font-size: 13px;
    background: color-mix(in srgb, var(--color-text-primary) 8%, transparent);
    border: 1px solid var(--color-border-light);
    border-radius: 4px;
    padding: 1px 5px;
  }

  /* 代码块走语义 surface 层（夜墨=墨锭凹陷 / 宣纸=深一度宣色），
     原 rgba(0,0,0,.35) 在宣纸下是黑底+墨字不可读 */
  :deep(pre) {
    margin: 8px 0;
    padding: 10px 12px;
    background: var(--color-surface);
    border: 1px solid var(--color-border-light);
    border-radius: 6px;
    overflow-x: auto;

    code {
      background: transparent;
      border: none;
      padding: 0;
      font-size: 13px;
      line-height: 1.5;
    }
  }

  :deep(a) {
    color: var(--color-primary);
    text-decoration: none;

    &:hover {
      text-decoration: underline;
      opacity: 0.85;
    }
  }

  :deep(table) {
    border-collapse: collapse;
    margin: 8px 0;
    max-width: 100%;
  }

  :deep(th),
  :deep(td) {
    border: 1px solid var(--color-border-light);
    padding: 5px 10px;
    font-size: 13px;
  }

  :deep(th) {
    background: color-mix(in srgb, var(--color-text-primary) 5%, transparent);
  }

  :deep(hr) {
    border: none;
    border-top: 1px solid var(--color-border-light);
    margin: 12px 0;
  }

  :deep(img) {
    max-width: 100%;
    border-radius: 4px;
  }
}
</style>

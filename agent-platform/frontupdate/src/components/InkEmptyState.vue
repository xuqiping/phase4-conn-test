<template>
  <!--
    高山流水 · 意境空状态（ART-DIR-0001 / STYLE-DNA-0001）
    用法：
      <InkEmptyState type="data" action-text="创建你的第一个 Agent" @action="..." />
    type: data=无数据(空山无人) / forbidden=无权限(云雾封径) / lost=404(水穷云起)
    现为纯 CSS 渐变山形占位；ART-ASSET-0003~0005 回填后以 image 属性或 --empty-img 换图
  -->
  <div class="ink-empty" :class="`ink-empty--${type}`" role="status">
    <div class="ink-empty__art" aria-hidden="true">
      <img :src="artImage" :alt="altText" class="ink-empty__img" />
    </div>
    <p class="ink-empty__poem u-display-font">{{ poem }}</p>
    <p v-if="description" class="ink-empty__desc">{{ description }}</p>
    <div v-if="$slots.default || actionText" class="ink-empty__action">
      <slot>
        <n-button type="primary" @click="$emit('action')">{{ actionText }}</n-button>
      </slot>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
// 空状态美术资产（ART-ASSET-0003~0005，已验收回填）
import imgData from '@/assets/art/empty/empty-data.webp'
import imgForbidden from '@/assets/art/empty/empty-forbidden.webp'
import imgLost from '@/assets/art/empty/empty-404.webp'

export type InkEmptyType = 'data' | 'forbidden' | 'lost'

const props = withDefaults(defineProps<{
  type?: InkEmptyType
  /** 覆盖默认诗句（默认随 type 走意境文案） */
  poem?: string
  /** 直白说明文案（给恢复路径，D-21.04） */
  description?: string
  /** 引导按钮文案；不传且无插槽则不渲染操作区 */
  actionText?: string
  /** 美术资产回填后传入图片地址；缺省用 CSS 渐变占位 */
  image?: string
  alt?: string
}>(), {
  type: 'data',
  poem: '',
  description: '',
  actionText: '',
  image: '',
  alt: ''
})

defineEmits<{ (e: 'action'): void }>()

const POEMS: Record<InkEmptyType, { poem: string; desc: string; alt: string; img: string }> = {
  data:      { poem: '空山无人，水流花开', desc: '这里还没有内容，从第一步开始吧', alt: '空山孤舟插画', img: imgData },
  forbidden: { poem: '云深不知处',         desc: '你暂时没有访问权限，可联系管理员开通', alt: '远山云雾插画', img: imgForbidden },
  lost:      { poem: '行到水穷处，坐看云起时', desc: '页面走丢了，回到首页继续探索', alt: '水穷云起插画', img: imgLost }
}

const poem = computed(() => props.poem || POEMS[props.type].poem)
const altText = computed(() => props.alt || POEMS[props.type].alt)
// 资产已回填：默认直接用对应场景插画；显式传 image 可覆盖
const artImage = computed(() => props.image || POEMS[props.type].img)

// description 默认取场景说明（允许显式传空串关闭）
const description = computed(() => props.description || POEMS[props.type].desc)
</script>

<style lang="scss" scoped>
.ink-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--spacing-12) var(--spacing-6);
  text-align: center;
}

.ink-empty__art {
  width: 200px;
  height: 200px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.ink-empty__img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
  border-radius: 12px; // 插画以柔和圆角小景呈现（现代新中式）
}

// 纯 CSS 渐变占位：极简山形 + 雾感（资产回填后由 image 替换）
.ink-empty__placeholder {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  opacity: 0.9;
  background:
    // 山形两重剪影
    radial-gradient(ellipse 70% 34% at 30% 78%, rgba(var(--color-primary-rgb), 0.16) 0%, transparent 70%),
    radial-gradient(ellipse 55% 26% at 68% 84%, rgba(var(--color-primary-rgb), 0.10) 0%, transparent 70%),
    // 顶部天光/月
    radial-gradient(circle 26px at 62% 26%, rgba(var(--color-primary-rgb), 0.20) 0%, transparent 100%);
  mask-image: radial-gradient(circle, #000 62%, transparent 71%);
}

.ink-empty--forbidden .ink-empty__placeholder {
  // 云雾封径：山形更重叠、月隐去
  background:
    radial-gradient(ellipse 75% 36% at 35% 76%, rgba(var(--color-primary-rgb), 0.12) 0%, transparent 70%),
    radial-gradient(ellipse 60% 30% at 65% 70%, rgba(var(--color-primary-rgb), 0.16) 0%, transparent 70%),
    radial-gradient(ellipse 80% 20% at 50% 60%, rgba(var(--color-primary-rgb), 0.10) 0%, transparent 75%);
}

.ink-empty--lost .ink-empty__placeholder {
  // 水穷云起：底部溪流光带 + 上升云雾
  background:
    radial-gradient(ellipse 40% 12% at 50% 88%, rgba(var(--color-primary-rgb), 0.22) 0%, transparent 70%),
    radial-gradient(ellipse 50% 30% at 50% 55%, rgba(var(--color-primary-rgb), 0.10) 0%, transparent 70%),
    radial-gradient(ellipse 30% 20% at 50% 34%, rgba(var(--color-primary-rgb), 0.14) 0%, transparent 70%);
}

.ink-empty__poem {
  margin: var(--spacing-4) 0 0;
  font-size: 20px;
  letter-spacing: 0.10em;
  color: var(--color-text-primary);
}

.ink-empty__desc {
  margin: var(--spacing-2) 0 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.ink-empty__action {
  margin-top: var(--spacing-5);
}
</style>

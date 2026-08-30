<template>
  <!--
    高山流水 · 雾中浮岛场景层（ART-DIR-0002R 方向二 / STYLE-DNA-0002）
    用法：<ModuleScene scene="knowledge" /> 作为页面根容器第一个子节点
    （根容器需 position: relative；本组件 absolute 铺满、压在内容下）

    - 一页一景 = 天际渐变 + 底部远山（种子异构） + 右缘竖排诗签
    - 仅 ink 双主题渲染（旧三主题 v-if 级不输出 DOM，DESIGN-TOKEN-0002 边界#8）
    - lite 模式（admin 高密度页）：全部 alpha 减半
    - reduced-motion：雾静止（本组件无动画，雾层在 MainLayout，已全局降级）
  -->
  <div v-if="isInkTheme" class="module-scene" :class="{ 'module-scene--lite': lite, 'module-scene--xuan': isXuanZhi }" aria-hidden="true">
    <div class="module-scene__sky" :style="skyStyle"></div>
    <div class="module-scene__ridges" :style="ridgeStyle"></div>
    <!-- ART-ASSET-0009 景窗图层：回填图存在时叠在 CSS 场景之上（CSS 层兼作加载兜底/未回填降级） -->
    <div v-if="sceneImg" class="module-scene__img" :style="{ backgroundImage: `url(${sceneImg})` }"></div>
    <!-- 融边纱罩：景窗图四缘向页面底色渐隐，消掉矩形边、压出雾感（验收实测必备，勿删） -->
    <div v-if="sceneImg" class="module-scene__scrim"></div>
    <div v-if="showPoem" class="module-scene__poem u-display-font">{{ scene.poem }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useThemeStore } from '@/stores/theme'
import { MODULE_SCENES, ridgeGradients, type SceneKey } from '@/config/scenes'

// ART-ASSET-0009：景窗图懒加载注册表（文件不存在则无条目，自动回退纯 CSS 场景）
// 命名：scene-{key}.avif（夜墨）/ scene-{key}-light.avif（宣纸，可选，缺省宣纸保持 CSS）
const sceneImgLoaders = import.meta.glob('../assets/art/scenes/scene-*.avif', { import: 'default' }) as Record<string, () => Promise<string>>

const props = withDefaults(defineProps<{
  scene: SceneKey
  /** admin 高密度页：alpha 减半 */
  lite?: boolean
  /** 右缘竖排诗签（默认开；极窄面板页可关） */
  poemRail?: boolean
}>(), {
  lite: false,
  poemRail: true
})

const themeStore = useThemeStore()
const isInkTheme = computed(() => themeStore.currentTheme === 'ye-mo' || themeStore.currentTheme === 'xuan-zhi')
const isXuanZhi = computed(() => themeStore.currentTheme === 'xuan-zhi')

const scene = computed(() => MODULE_SCENES[props.scene]).value

// lite（admin 高密度页）自动关闭诗签：通栏表格会把它拦腰截断，只留天际+远山（ART-QA-0002 复检调整）
const showPoem = computed(() => props.poemRail && !props.lite)

// 透明度规范（STYLE-DNA-0002 §基因1/6）：夜墨全浓度 / 宣纸浅调 / lite 减半
const alpha = computed(() => {
  const base = isXuanZhi.value
    ? { sky: 0.10, near: 0.06, far: 0.04 }   // 宣纸：山形以模块色浅染
    : { sky: 0.14, near: 0.08, far: 0.05 }   // 夜墨：全浓度
  if (!props.lite) return base
  return { sky: base.sky / 2, near: base.near / 2, far: base.far / 2 }
})

const skyStyle = computed(() => ({
  background: `linear-gradient(180deg, rgba(${scene.rgb}, ${alpha.value.sky}) 0%, transparent 45%)`
}))

const ridgeStyle = computed(() => ({
  // DESIGN-TOKEN-0002 §1：宣纸亮底下山形改用墨色 38,34,28（模块色浅染会发飘）
  background: ridgeGradients(
    scene.ridgeSeed,
    isXuanZhi.value ? '38,34,28' : scene.rgb,
    alpha.value.near,
    alpha.value.far
  ).join(', ')
}))

// 景窗图：按 主题×模块 取加载器，按需懒加载；宣纸缺 -light 图时回退 CSS 场景
const sceneImg = ref<string | null>(null)
watch(
  () => [props.scene, isXuanZhi.value] as const,
  async ([key, light]) => {
    sceneImg.value = null
    const name = light ? `scene-${key}-light` : `scene-${key}`
    const loader = sceneImgLoaders[`../assets/art/scenes/${name}.avif`]
    if (loader) sceneImg.value = await loader()
  },
  { immediate: true }
)
</script>

<style lang="scss" scoped>
.module-scene {
  position: absolute;
  inset: 0;
  // z-index:-1 沉底：页面根在 MainLayout 里已是 position:relative;z-index:1 的堆叠上下文，
  // 场景层永远画在内容文字之下（红线「装饰层压文字下」的结构性保证，不靠透明度自觉）
  z-index: -1;
  pointer-events: none;
  overflow: hidden;
}

.module-scene__sky,
.module-scene__ridges {
  position: absolute;
  inset: 0;
}

// 山形只在底缘 20% 区域（基因2「远」）：ridges 层裁剪到下部
.module-scene__ridges {
  top: auto;
  height: 32%; // 椭圆自带 transparent 收边，32% 容器保证视觉落在底部 20%
  bottom: 0;
}

// 景窗图层：铺满、cover、压至 0.35（ART-QA-0002 §4 验收实测：生成图偏浓，按「宁淡勿浓」代码层压淡），淡入
.module-scene__img {
  position: absolute;
  inset: 0;
  background-size: cover;
  background-position: center bottom;
  opacity: 0.35;
  animation: module-scene-fade 900ms ease-out;
}

.module-scene--lite .module-scene__img {
  opacity: 0.18;
}

// 宣纸版景窗图本身近白低对比（程序派生：亮度反转+宣纸双色调），浓度提到 0.6 才「隐约可见」
.module-scene--xuan .module-scene__img {
  opacity: 0.6;
}

.module-scene--xuan.module-scene--lite .module-scene__img {
  opacity: 0.3;
}

// 融边纱罩：上 18%、左右 10% 向底色渐隐；底部仅 6%（v6 起山形沉底，18% 会把山根整段吃掉）
.module-scene__scrim {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(180deg, var(--color-bg) 0%, transparent 18%, transparent 94%, var(--color-bg) 100%),
    linear-gradient(90deg, var(--color-bg) 0%, transparent 10%, transparent 90%, var(--color-bg) 100%);
}

@keyframes module-scene-fade {
  from { opacity: 0; }
}

@media (prefers-reduced-motion: reduce) {
  .module-scene__img { animation: none; }
}

// 右缘诗签：竖排文楷题跋，随场景色微染；起点避开页头操作区（PageHeader actions 在右上）
.module-scene__poem {
  position: absolute;
  top: 120px;
  right: var(--spacing-4);
  writing-mode: vertical-rl;
  font-size: 14px;
  letter-spacing: 0.3em;
  color: var(--color-text-secondary);
  opacity: 0.85;
  user-select: none;
}

.module-scene--lite .module-scene__poem {
  opacity: 0.65;
}

// 窄屏不题跋（空间让给内容）
@media (max-width: 768px) {
  .module-scene__poem { display: none; }
}
</style>

<!-- 非 scoped：题跋 gutter。诗签在 z-index:-1 场景层里，通栏表格/卡片会把它遮断——
     故 ink 主题下给含诗签的页面根预留右侧 56px 题跋区，诗签升格为版式的一部分。
     admin（lite 无诗签）与窄屏（诗签隐藏）不留 gutter。 -->
<style lang="scss">
[data-theme="ye-mo"],
[data-theme="xuan-zhi"] {
  @media (min-width: 769px) {
    .main-layout__content > *:has(> .module-scene:not(.module-scene--lite)) {
      padding-right: 56px;
    }
  }
}
</style>

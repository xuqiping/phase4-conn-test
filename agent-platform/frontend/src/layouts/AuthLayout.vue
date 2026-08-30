<template>
  <div class="auth-layout">
    <!-- 背景装饰层：渐变 + 粒子效果 -->
    <div class="auth-layout__bg">
      <div class="auth-layout__gradient"></div>
      <!-- 高山流水 · 登录大背景（ART-ASSET-0001，仅夜墨/宣纸主题显示） -->
      <img class="auth-layout__splash" :src="splashImg" alt="" aria-hidden="true" />
      <!-- CSS粒子 — 20个随机位置的小光点 -->
      <div class="auth-layout__particles">
        <span
          v-for="i in 20"
          :key="i"
          class="auth-layout__particle"
          :style="particleStyle(i)"
        ></span>
      </div>
    </div>

    <!-- 内容区域 — 居中卡片 -->
    <div class="auth-layout__content">
      <slot />
    </div>
  </div>
</template>

<script setup lang="ts">
// 高山流水 · 登录大背景资产（ART-ASSET-0001，已验收回填）
import splashImg from '@/assets/art/login/splash-ink.webp'

/**
 * 生成每个粒子的随机位置和动画参数
 * 使用固定的种子（索引）确保每次渲染一致
 */
function particleStyle(index: number) {
  // 使用简单的伪随机，基于索引生成不同的位置和动画参数
  const seed = index * 137.5
  const left = (seed * 7.3 % 100)
  const top = (seed * 3.7 % 100)
  const size = 2 + (seed % 3)
  const duration = 6 + (index % 4) * 2
  const delay = (index % 5) * -2

  return {
    left: `${left}%`,
    top: `${top}%`,
    width: `${size}px`,
    height: `${size}px`,
    animationDuration: `${duration}s`,
    animationDelay: `${delay}s`
  }
}
</script>

<style lang="scss" scoped>
.auth-layout {
  position: relative;
  width: 100vw;
  height: 100vh;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}

// 背景层
.auth-layout__bg {
  position: absolute;
  inset: 0;
  z-index: 0;
}

.auth-layout__gradient {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(ellipse at 20% 50%, rgba(var(--color-primary-rgb), 0.08) 0%, transparent 50%),
    radial-gradient(ellipse at 80% 20%, rgba(var(--color-primary-rgb), 0.06) 0%, transparent 40%),
    radial-gradient(ellipse at 50% 80%, rgba(var(--color-primary-rgb), 0.04) 0%, transparent 60%),
    linear-gradient(180deg, var(--color-bg) 0%, var(--color-surface) 100%);
}

// 粒子效果
.auth-layout__particles {
  position: absolute;
  inset: 0;
}

.auth-layout__particle {
  position: absolute;
  border-radius: 50%;
  background: var(--color-primary);
  opacity: 0.3;
  animation: pulse-glow var(--duration-slower) var(--ease-in-out) infinite;

  // 每个粒子有不同的浮动动画
  @for $i from 1 through 20 {
    &:nth-child(#{$i}) {
      animation:
        pulse-glow #{6 + ($i % 4) * 2}s var(--ease-in-out) infinite,
        float #{8 + ($i % 3) * 3}s var(--ease-in-out) infinite;
    }
  }
}

// 内容区
.auth-layout__content {
  position: relative;
  z-index: 1;
  animation: fade-in 0.6s var(--ease-out);
}
</style>

<!-- ============================================================
     高山流水 · 登录页意境层（仅 ye-mo / xuan-zhi 生效）
     远山剪影 + 云雾缓漂；泼墨大图资产(ART-ASSET-0001)回填后叠加
     非 scoped：需要按 documentElement 的 data-theme 判定
     ============================================================ -->
<style lang="scss">
// 大背景图默认隐藏（旧主题不用）
.auth-layout__splash {
  display: none;
}

[data-theme="ye-mo"],
[data-theme="xuan-zhi"] {
  .auth-layout__particles {
    display: none; // 粒子属于旧科技风，山水主题不用
  }

  // 大背景图：盖在渐变之上（右 40% 为卡片安全区，资产已按此构图）
  .auth-layout__splash {
    display: block;
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    object-fit: cover;
    z-index: 1;
  }

  .auth-layout__gradient {
    z-index: 2; // 渐变薄纱压在大图上，统一色调
    background:
      // 远山三重剪影（底部，由近及远透明度递减）
      radial-gradient(ellipse 90% 32% at 15% 108%, rgba(var(--color-primary-rgb), 0.10) 0%, transparent 70%),
      radial-gradient(ellipse 70% 26% at 70% 112%, rgba(var(--color-primary-rgb), 0.07) 0%, transparent 70%),
      radial-gradient(ellipse 55% 20% at 45% 118%, rgba(var(--color-primary-rgb), 0.05) 0%, transparent 70%),
      // 顶部天光
      radial-gradient(ellipse at 50% -10%, rgba(var(--color-primary-rgb), 0.10) 0%, transparent 55%),
      linear-gradient(180deg, var(--color-bg) 0%, transparent 100%);
  }

  // 云雾缓漂层（压在最上层柔和大图）
  .auth-layout__bg::after {
    content: '';
    position: absolute;
    inset: -10% -20%;
    z-index: 3;
    background:
      radial-gradient(ellipse 40% 18% at 25% 30%, rgba(var(--color-primary-rgb), 0.09) 0%, transparent 70%),
      radial-gradient(ellipse 35% 15% at 75% 55%, rgba(var(--color-primary-rgb), 0.07) 0%, transparent 70%);
    animation: ink-mist-drift 36s var(--ease-in-out, ease-in-out) infinite alternate;
  }

  // 登录卡片：宣纸/墨锭质感
  .auth-layout__content {
    animation: fade-in 0.6s cubic-bezier(0.22, 0.61, 0.36, 1);
  }
}

// 宣纸主题下远山用墨色而非主色光晕
[data-theme="xuan-zhi"] .auth-layout__gradient {
  background:
    radial-gradient(ellipse 90% 32% at 15% 108%, rgba(38, 34, 28, 0.08) 0%, transparent 70%),
    radial-gradient(ellipse 70% 26% at 70% 112%, rgba(38, 34, 28, 0.06) 0%, transparent 70%),
    radial-gradient(ellipse 55% 20% at 45% 118%, rgba(38, 34, 28, 0.04) 0%, transparent 70%),
    radial-gradient(ellipse at 50% -10%, rgba(61, 122, 148, 0.08) 0%, transparent 55%),
    linear-gradient(180deg, var(--color-bg) 0%, transparent 100%);
}

// 夜墨主题：大图偏亮，压暗保氛围并护住卡片文字对比度
[data-theme="ye-mo"] .auth-layout__splash {
  filter: brightness(0.52) saturate(0.85);
}

@keyframes ink-mist-drift {
  from { transform: translateX(-3%) translateY(0); }
  to   { transform: translateX(3%) translateY(-2%); }
}

@media (prefers-reduced-motion: reduce) {
  .auth-layout__bg::after { animation: none !important; }
}
</style>

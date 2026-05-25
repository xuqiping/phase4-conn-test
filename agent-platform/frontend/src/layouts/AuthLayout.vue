<template>
  <div class="auth-layout">
    <!-- 背景装饰层：渐变 + 粒子效果 -->
    <div class="auth-layout__bg">
      <div class="auth-layout__gradient"></div>
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
      <router-view />
    </div>
  </div>
</template>

<script setup lang="ts">
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

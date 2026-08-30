<template>
  <AuthLayout>
    <div class="privacy-card">
      <div class="privacy-card__glow"></div>
      <div class="privacy-card__body">
        <h2 class="privacy-card__title">隐私政策</h2>
        <p class="privacy-card__updated">更新日期：2026-08-15</p>

        <section class="privacy-card__section">
          <h3>一、我们收集哪些信息</h3>
          <ul>
            <li><b>账号信息</b>：用户名、邮箱、手机号（可选）、密码（仅哈希存储，任何人不可见）。</li>
            <li><b>第三方登录标识</b>：使用钉钉/微信扫码登录时，保存其开放平台标识（仅用于账号关联）。</li>
            <li><b>使用数据</b>：你创建的智能体、知识库文档、对话与工作流记录、媒体生成产物。</li>
            <li><b>日志数据</b>：登录时间、IP 地址、浏览器特征（用于安全风控与异常登录提醒）。</li>
          </ul>
        </section>

        <section class="privacy-card__section">
          <h3>二、信息用途</h3>
          <ul>
            <li>提供核心功能：账号认证、智能体运行、知识库检索、计费结算。</li>
            <li>安全防护：异常登录检测、暴力破解拦截、审计追溯。</li>
            <li>我们<b>不</b>将你的个人信息出售或提供给无关第三方。</li>
          </ul>
        </section>

        <section class="privacy-card__section">
          <h3>三、信息留存期限</h3>
          <ul>
            <li>账号与业务数据：账号存续期间保存。</li>
            <li>安全与审计日志：自产生之日起保留 <b>180 天</b>，到期自动清理。</li>
            <li>计费流水：按法定财务要求保留。</li>
          </ul>
        </section>

        <section class="privacy-card__section">
          <h3>四、你的删除权（如何注销账号）</h3>
          <ul>
            <li>路径：<b>设置 → 安全设置 → 注销账号</b>，输入当前密码确认后即完成注销。</li>
            <li>注销效果：用户名、邮箱、手机号、头像等身份信息将被<b>匿名化清除且不可恢复</b>，
              所有设备立即退出登录。</li>
            <li>保留口径：为满足法律与财务要求，计费流水与审计日志将做<b>脱敏保留</b>
              （不再关联到可识别的个人身份）；账号余额不予退还，请注销前自行处理。</li>
          </ul>
        </section>

        <section class="privacy-card__section">
          <h3>五、联系我们</h3>
          <p>对个人信息处理有疑问或需行使其他数据权利，请发送邮件至
            <b>privacy@agent-platform.local</b>，我们将在 15 个工作日内回复。</p>
        </section>

        <n-button type="primary" size="large" block class="privacy-card__btn" @click="goBack">
          返回
        </n-button>
      </div>
    </div>
  </AuthLayout>
</template>

<script setup lang="ts">
/**
 * 隐私政策独立页（安全体系 S5 · SEC-FR-103 J4）。
 * 注册弹窗内旧 5 句简版仍保留，本页为完整口径（收集范围/用途/留存/删除权/联系方式）。
 */
import { useRouter } from 'vue-router'
import { NButton } from 'naive-ui'
import AuthLayout from '@/layouts/AuthLayout.vue'

const router = useRouter()

function goBack() {
  if (window.history.length > 1) {
    router.back()
  } else {
    router.push('/login')
  }
}
</script>

<style lang="scss" scoped>
.privacy-card {
  position: relative;
  width: 100%;
  max-width: 680px;
  max-height: 82vh;
  overflow-y: auto;
  border-radius: 12px;
  background: var(--color-bg-container, #fff);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.12);
  padding: 32px 36px;
}
.privacy-card__glow {
  position: absolute;
  inset: 0;
  border-radius: 12px;
  pointer-events: none;
  background: radial-gradient(ellipse at top, rgba(64, 128, 255, 0.08), transparent 60%);
}
.privacy-card__body {
  position: relative;
}
.privacy-card__title {
  margin: 0 0 4px;
  font-size: 22px;
}
.privacy-card__updated {
  margin: 0 0 16px;
  font-size: 12px;
  color: var(--color-text-tertiary);
}
.privacy-card__section {
  margin-bottom: 18px;
  h3 {
    font-size: 15px;
    margin: 0 0 8px;
  }
  ul {
    margin: 0;
    padding-left: 20px;
    li {
      font-size: 13px;
      line-height: 1.8;
      color: var(--color-text-secondary);
    }
  }
  p {
    font-size: 13px;
    line-height: 1.8;
    color: var(--color-text-secondary);
  }
}
.privacy-card__btn {
  margin-top: 8px;
}
</style>

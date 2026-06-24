<template>
  <div class="push-target-guide text-sm space-y-3">
    <p class="text-xs text-gray-500 dark:text-gray-400">
      {{ t('workReport.pushGuideIntro') }}
    </p>

    <!-- 飞书基础 -->
    <details class="group border border-gray-200 dark:border-dark-border rounded-md overflow-hidden">
      <summary class="cursor-pointer p-2.5 bg-gray-50 dark:bg-dark-bg font-medium flex items-center justify-between select-none">
        <span>{{ t('workReport.platformFeishu') }}（{{ t('workReport.feishuBasic') }}）</span>
        <span class="text-xs text-gray-400 group-open:rotate-180 transition-transform">▼</span>
      </summary>
      <div class="p-3 space-y-2.5 bg-white dark:bg-dark-hover">
        <ol class="list-decimal list-inside space-y-1.5 text-xs leading-relaxed">
          <li>
            {{ t('workReport.feishuStep1') }}
            <a
              href="https://open.feishu.cn/app"
              class="text-blue-600 dark:text-blue-400 hover:underline"
              @click.prevent="openLink('https://open.feishu.cn/app')"
            >{{ t('workReport.openPlatform') }}</a>。
          </li>
          <li>{{ t('workReport.feishuStep2') }}</li>
          <li>{{ t('workReport.feishuStep3') }}</li>
          <li>{{ t('workReport.feishuStep4') }}</li>
          <li>
            {{ t('workReport.feishuStep5') }}
            <code class="px-1 py-0.5 rounded bg-gray-100 dark:bg-dark-bg text-xs">{{ credentialJsonExample }}</code>
          </li>
          <li>{{ t('workReport.feishuStep6') }}</li>
          <li>{{ t('workReport.feishuStep7') }}</li>
        </ol>
        <div class="text-[11px] text-gray-500 dark:text-gray-400 border-t border-gray-100 dark:border-dark-border pt-2">
          {{ t('workReport.feishuTip') }}
        </div>
      </div>
    </details>

    <!-- 飞书高级 -->
    <details class="group border border-gray-200 dark:border-dark-border rounded-md overflow-hidden">
      <summary class="cursor-pointer p-2.5 bg-gray-50 dark:bg-dark-bg font-medium flex items-center justify-between select-none">
        <span>{{ t('workReport.platformFeishu') }}（{{ t('workReport.feishuAdvanced') }}）</span>
        <span class="text-xs text-gray-400 group-open:rotate-180 transition-transform">▼</span>
      </summary>
      <div class="p-3 space-y-2.5 bg-white dark:bg-dark-hover">
        <ol class="list-decimal list-inside space-y-1.5 text-xs leading-relaxed">
          <li>{{ t('workReport.feishuAdvancedStep1') }}</li>
          <li>{{ t('workReport.feishuAdvancedStep2') }}</li>
          <li>{{ t('workReport.feishuAdvancedStep3') }}</li>
          <li>{{ t('workReport.feishuAdvancedStep4') }}</li>
        </ol>
        <div class="text-[11px] text-gray-500 dark:text-gray-400 border-t border-gray-100 dark:border-dark-border pt-2">
          {{ t('workReport.feishuAdvancedTip') }}
        </div>
      </div>
    </details>

    <!-- 钉钉群机器人 -->
    <details class="group border border-gray-200 dark:border-dark-border rounded-md overflow-hidden">
      <summary class="cursor-pointer p-2.5 bg-gray-50 dark:bg-dark-bg font-medium flex items-center justify-between select-none">
        <span>{{ t('workReport.platformDingtalk') }}（{{ t('workReport.dingtalkWebhook') }}）</span>
        <span class="text-xs text-gray-400 group-open:rotate-180 transition-transform">▼</span>
      </summary>
      <div class="p-3 space-y-2.5 bg-white dark:bg-dark-hover">
        <ol class="list-decimal list-inside space-y-1.5 text-xs leading-relaxed">
          <li>{{ t('workReport.dingtalkStep1') }}</li>
          <li>{{ t('workReport.dingtalkStep2') }}</li>
          <li>{{ t('workReport.dingtalkStep3') }}</li>
          <li>{{ t('workReport.dingtalkStep4') }}</li>
        </ol>
        <div class="text-[11px] text-gray-500 dark:text-gray-400 border-t border-gray-100 dark:border-dark-border pt-2">
          {{ t('workReport.dingtalkTip') }}
        </div>
      </div>
    </details>

    <!-- 钉钉企业自建应用（高级） -->
    <details class="group border border-gray-200 dark:border-dark-border rounded-md overflow-hidden">
      <summary class="cursor-pointer p-2.5 bg-gray-50 dark:bg-dark-bg font-medium flex items-center justify-between select-none">
        <span>{{ t('workReport.platformDingtalk') }}（{{ t('workReport.dingtalkEnterprise') }}）</span>
        <span class="text-xs text-gray-400 group-open:rotate-180 transition-transform">▼</span>
      </summary>
      <div class="p-3 space-y-2.5 bg-white dark:bg-dark-hover">
        <ol class="list-decimal list-inside space-y-1.5 text-xs leading-relaxed">
          <li>
            {{ t('workReport.dingtalkAdvancedStep1') }}
            <a
              href="https://open-dev.dingtalk.com"
              class="text-blue-600 dark:text-blue-400 hover:underline"
              @click.prevent="openLink('https://open-dev.dingtalk.com')"
            >{{ t('workReport.openPlatform') }}</a>。
          </li>
          <li>{{ t('workReport.dingtalkAdvancedStep2') }}</li>
          <li>{{ t('workReport.dingtalkAdvancedStep3') }}</li>
          <li>{{ t('workReport.dingtalkAdvancedStep4') }}</li>
        </ol>
        <div class="text-[11px] text-gray-500 dark:text-gray-400 border-t border-gray-100 dark:border-dark-border pt-2">
          {{ t('workReport.dingtalkAdvancedTip') }}
        </div>
      </div>
    </details>

    <!-- 引用链接 + 完整文档 -->
    <div class="text-[11px] text-gray-500 dark:text-gray-400 space-y-1">
      <p class="font-medium">{{ t('workReport.referenceLinks') }}</p>
      <div class="flex flex-wrap gap-x-3 gap-y-1">
        <a
          href="https://open.feishu.cn/document/client-docs/bot-v3/add-custom-bot"
          class="hover:underline"
          @click.prevent="openLink('https://open.feishu.cn/document/client-docs/bot-v3/add-custom-bot')"
        >飞书自定义机器人接入</a>
        <a
          href="https://open.dingtalk.com/document/robots/custom-robot-access"
          class="hover:underline"
          @click.prevent="openLink('https://open.dingtalk.com/document/robots/custom-robot-access')"
        >钉钉自定义机器人接入</a>
        <a
          href="https://open.dingtalk.com/document/development/asynchronous-sending-of-enterprise-session-messages"
          class="hover:underline"
          @click.prevent="openLink('https://open.dingtalk.com/document/development/asynchronous-sending-of-enterprise-session-messages')"
        >钉钉发送工作通知</a>
        <a
          href="https://open.feishu.cn/api-explorer/"
          class="hover:underline"
          @click.prevent="openLink('https://open.feishu.cn/api-explorer/')"
        >飞书 API 调试台</a>
      </div>
      <button
        type="button"
        class="text-blue-600 dark:text-blue-400 hover:underline"
        @click="openFullDoc"
      >
        {{ t('workReport.viewFullGuide') }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from '@/composables/useI18n'

const { t } = useI18n()

const credentialJsonExample = '{"appId":"cli_xxx","appSecret":"xxx"}'
const fullDocPath = 'C:\\\\AI Projects\\\\file-keeper\\\\其他功能分项\\\\工作日志、每日安排及推送\\\\推送应用配置指南.md'

async function openLink(url: string) {
  try {
    const { openUrl } = await import('@tauri-apps/plugin-opener')
    await openUrl(url)
  } catch {
    window.open(url, '_blank', 'noopener,noreferrer')
  }
}

async function openFullDoc() {
  try {
    const { openPath } = await import('@tauri-apps/plugin-opener')
    await openPath(fullDocPath)
  } catch {
    window.open(`file:///${fullDocPath.replace(/\\/g, '/')}`, '_blank', 'noopener,noreferrer')
  }
}
</script>

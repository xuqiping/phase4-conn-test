<!-- agent-platform/frontend/src/views/admin/security/RuleConfigView.vue
     规则配置（11x 加固 P4-C12）：总闸/分闸/告警开关 + 限流与规则阈值（读改 system_settings，热生效）
     权限：security:rule:manage -->
<template>
  <div class="rule-config-view">
    <!-- 雾中浮岛场景层（ART-DIR-0002R 方向二 admin 淡版，仅 ink 主题渲染） -->
    <ModuleScene scene="admin" lite />
    <PageHeader title="安全规则配置" />
    <n-card size="small">
      <n-alert type="warning" :bordered="false" style="margin-bottom: 12px">
        改动即时生效（Worker 每次求值读最新值）。关「自动处置总闸」= 所有规则只告警不封/锁。
      </n-alert>
      <n-spin :show="loading">
        <n-form label-placement="left" label-width="240">
          <n-divider title-placement="left">总开关</n-divider>
          <n-form-item v-for="item in switchKeys" :key="item.key" :label="item.label">
            <n-switch v-model:value="switchValues[item.key]" @update:value="(v: boolean) => save(item.key, String(v))" />
          </n-form-item>

          <n-divider title-placement="left">钉钉告警</n-divider>
          <n-form-item label="机器人 Webhook URL">
            <n-input v-model:value="textValues['security.alert.webhook.url']" placeholder="空=不推"
                     style="max-width: 480px" @blur="save('security.alert.webhook.url', textValues['security.alert.webhook.url'])" />
          </n-form-item>
          <n-form-item label="加签密钥（只写不读）">
            <n-input v-model:value="textValues['security.alert.webhook.secret']" type="password"
                     show-password-on="click" placeholder="留空=不修改"
                     style="max-width: 480px" @blur="saveSecret" />
          </n-form-item>

          <n-divider title-placement="left">限流阈值</n-divider>
          <n-form-item v-for="item in rateKeys" :key="item.key" :label="item.label">
            <n-input-number v-model:value="numberValues[item.key]" :min="1" :max="100000"
                            @update:value="(v: number | null) => v != null && save(item.key, String(v))" />
          </n-form-item>

          <n-divider title-placement="left">检测规则阈值</n-divider>
          <n-form-item v-for="item in ruleKeys" :key="item.key" :label="item.label">
            <n-input-number v-model:value="numberValues[item.key]" :min="1" :max="10000000"
                            @update:value="(v: number | null) => v != null && save(item.key, String(v))" />
          </n-form-item>
        </n-form>
      </n-spin>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useMessage } from 'naive-ui'
import { listSecurityRules, updateSecurityRule } from '@/api/security'
import PageHeader from '@/components/PageHeader.vue'
import ModuleScene from '@/components/ModuleScene.vue'

const message = useMessage()
const loading = ref(false)

const switchKeys = [
  { key: 'security.rate.enabled', label: '限流总闸' },
  { key: 'security.response.auto_enabled', label: '自动处置总闸' },
  { key: 'security.response.auto_ip_block', label: '自动封 IP 分闸' },
  { key: 'security.response.auto_account_lock', label: '自动锁号分闸' },
  { key: 'security.alert.enabled', label: '钉钉告警总闸' },
]
const rateKeys = [
  { key: 'security.rate.global_ip.max', label: '全局限流（次/分钟/IP）' },
  { key: 'security.rate.chat_send.max', label: '对话发送（次/分钟/用户）' },
  { key: 'security.rate.media_submit.max', label: '媒体提交（次/分钟/用户）' },
]
const ruleKeys = [
  { key: 'security.rule.idor.threshold', label: '越权探测（5分钟403次数）' },
  { key: 'security.rule.exfil.threshold', label: '数据外带（10分钟下载条数）' },
  { key: 'security.rule.points.threshold', label: '积分滥用（10分钟消耗分）' },
  { key: 'security.rule.media.thresholdFen', label: '媒体滥用（30分钟花费分）' },
  { key: 'security.rule.prompt.repeat', label: 'Prompt屡犯升级（1小时次数）' },
  { key: 'security.rule.token.ips', label: 'Token盗号（10分钟不同IP数）' },
]

const switchValues = reactive<Record<string, boolean>>({})
const numberValues = reactive<Record<string, number>>({})
const textValues = reactive<Record<string, string>>({})

async function reload() {
  loading.value = true
  try {
    const resp = await listSecurityRules()
    const data = resp.data.data
    switchKeys.forEach((i) => (switchValues[i.key] = data[i.key] !== 'false'))
    rateKeys.concat(ruleKeys).forEach((i) => (numberValues[i.key] = Number(data[i.key] ?? '0')))
    textValues['security.alert.webhook.url'] = data['security.alert.webhook.url'] ?? ''
    textValues['security.alert.webhook.secret'] = '' // 只写不读
  } catch (e: any) {
    message.error(e?.response?.data?.msg ?? '加载失败')
  } finally {
    loading.value = false
  }
}

async function save(key: string, value: string) {
  try {
    await updateSecurityRule(key, value)
    message.success('已保存并即时生效')
  } catch (e: any) {
    message.error(e?.response?.data?.msg ?? '保存失败')
  }
}

async function saveSecret() {
  const v = textValues['security.alert.webhook.secret']
  if (!v) return
  await save('security.alert.webhook.secret', v)
  textValues['security.alert.webhook.secret'] = ''
}

onMounted(reload)
</script>

<style scoped lang="scss">
.rule-config-view {
  padding: 12px;
}
</style>

<template>
  <div class="pg-view">
    <!-- 列表模式：我的组卡片 -->
    <div v-if="!selected" class="pg-view__list">
      <div class="pg-view__header">
        <h2 class="pg-view__title">项目组</h2>
        <div class="pg-view__header-actions">
          <NButton :tertiary="!poolMode" :type="poolMode ? 'primary' : 'default'" @click="togglePoolMode">
            {{ poolMode ? '返回我的组' : '公共池' }}
          </NButton>
          <NButton type="primary" :loading="creating" @click="showCreate = true">
            <template #icon><NIcon :component="AddOutline" /></template>
            新建项目组
          </NButton>
        </div>
      </div>

      <!-- 17x#3：我的待处理邀请 -->
      <div v-if="!poolMode && myInvites.length" class="pg-notice">
        <div class="pg-notice__title">📨 我的邀请（{{ myInvites.length }}）</div>
        <div v-for="inv in myInvites" :key="inv.id" class="pg-notice__row">
          <span>
            「{{ inv.inviterName || `#${inv.inviterUserId}` }}」邀请你加入
            <b>{{ inv.groupName || `项目组#${inv.groupId}` }}</b>
          </span>
          <NButton size="tiny" type="primary" @click="answerInvite(inv, true)">同意加入</NButton>
          <NButton size="tiny" quaternary @click="answerInvite(inv, false)">拒绝</NButton>
        </div>
      </div>

      <!-- 17x#4：我的入组申请（非 PENDING 也展示状态，PENDING 可取消） -->
      <div v-if="!poolMode && myJoinRequests.length" class="pg-notice">
        <div class="pg-notice__title">📋 我的入组申请</div>
        <div v-for="r in myJoinRequests" :key="r.id" class="pg-notice__row">
          <span>
            <b>{{ r.groupName || `项目组#${r.groupId}` }}</b>
            <NTag size="tiny" :bordered="false" style="margin-left: 6px"
              :type="r.status === 'APPROVED' ? 'success' : r.status === 'PENDING' ? 'warning' : 'default'">
              {{ JOIN_STATUS_LABEL[r.status] ?? r.status }}
            </NTag>
          </span>
          <NButton v-if="r.status === 'PENDING'" size="tiny" quaternary @click="cancelMyJoin(r)">取消申请</NButton>
        </div>
      </div>

      <!-- 17x#4：公共池浏览 -->
      <template v-if="poolMode">
        <NSpin :show="loadingPool">
          <NEmpty v-if="!poolItems.length" description="公共池暂无招募中的项目组" />
          <div v-else class="pg-view__grid">
            <div v-for="g in poolItems" :key="g.id" class="pg-card pg-card--pool">
              <div class="pg-card__head">
                <span class="pg-card__name" :title="g.name">{{ g.name }}</span>
                <NTag size="small" type="success" :bordered="false">招募中</NTag>
              </div>
              <div v-if="g.description" class="pg-card__desc" :title="g.description">{{ g.description }}</div>
              <div class="pg-card__meta">组长 {{ g.ownerUsername ?? '-' }} · {{ g.memberCount }} 成员</div>
              <div class="pg-card__foot">
                <NTag v-if="g.alreadyMember" size="small" type="primary" :bordered="false">已加入</NTag>
                <template v-else-if="g.myRequestStatus === 'PENDING'">
                  <NTag size="small" type="warning" :bordered="false">待审批</NTag>
                </template>
                <NButton v-else size="small" type="primary" @click="openApply(g)">申请加入</NButton>
              </div>
            </div>
          </div>
        </NSpin>
      </template>

      <NSpin v-else :show="loading">
        <NEmpty v-if="!groups.length" description="还没有项目组。组长可建组、划拨积分，成员消耗入组池。" />
        <div v-else class="pg-view__grid">
          <div v-for="g in groups" :key="g.id" class="pg-card" @click="openGroup(g)">
            <div class="pg-card__head">
              <span class="pg-card__name" :title="g.name">{{ g.name }}</span>
              <NTag size="small" :type="g.myRole === 'OWNER' ? 'primary' : 'default'" :bordered="false">
                {{ ROLE_LABEL[g.myRole] ?? '成员' }}
              </NTag>
            </div>
            <div class="pg-card__balance">组池 {{ fmt(g.balancePoints) }} 分</div>
            <div class="pg-card__meta">
              {{ g.memberCount }} 成员
              <template v-if="g.myRole === 'MEMBER'">
                · 我的限额 {{ g.myQuota === null ? '不限' : fmt(g.myQuota) }}
                · 已用 {{ fmt(g.myUsed) }}
              </template>
              <!-- 17x 未解决#1（V156）：管理卡片显自己的额度与剩余可分配 -->
              <template v-else-if="g.myRole === 'MANAGER'">
                · 我的额度 {{ g.myQuota === null ? '不限' : fmt(g.myQuota) }}
                · 可分配 {{ g.myAllocatable === null ? '不限' : fmt(g.myAllocatable) }}
              </template>
            </div>
          </div>
        </div>
      </NSpin>
    </div>

    <!-- 详情模式 -->
    <div v-else class="pg-view__detail">
      <div class="pg-view__detail-header">
        <NButton quaternary @click="backToList">
          <NIcon :component="ArrowBackOutline" /> 返回
        </NButton>
        <span class="pg-view__detail-name">{{ selected.name }}</span>
        <NTag size="small" :type="isOwner ? 'primary' : 'default'" :bordered="false">
          {{ ROLE_LABEL[selected.myRole] ?? '成员' }}
        </NTag>
        <!-- 组长资金操作（成员不可见） -->
        <template v-if="isOwner">
          <NButton size="small" tertiary type="primary" @click="openAllocate('allocate')">划拨</NButton>
          <NButton size="small" tertiary @click="openAllocate('reclaim')">回收</NButton>
          <span v-if="overview" class="pg-view__balance-chip">
            组池 {{ fmt(overview.group.balancePoints) }} 分
            <template v-if="Number(overview.group.inflightPoints) > 0">
              · 在途 {{ fmt(overview.group.inflightPoints) }}
            </template>
          </span>
        </template>
        <span v-else class="pg-view__balance-chip">
          组池 {{ fmt(selected.balancePoints) }} 分
          <!-- 17x 未解决#1（V156）：管理随时可见自己的剩余可分配额度 -->
          <template v-if="isManager">
            · 我的额度 {{ selected.myQuota === null ? '不限' : fmt(selected.myQuota) }}
            · 可分配 {{ selected.myAllocatable === null ? '不限' : fmt(selected.myAllocatable) }}
          </template>
          <!-- V161（修复III B）：我的组内账户——名下余额/欠款，欠款>0 冻结提示 + 自助划拨 -->
          · 名下 {{ fmt(selected.mySelf) }} 分
          <template v-if="Number(selected.myDebtPool) + Number(selected.myDebtLeader) > 0">
            · <span class="pg-debt-red">欠款 {{ fmt(Number(selected.myDebtPool) + Number(selected.myDebtLeader)) }} 分（消费冻结）</span>
          </template>
        </span>
        <NButton v-if="!isOwner" size="small" tertiary type="primary" @click="openSelfTransfer">划拨入组</NButton>
      </div>

      <NTabs type="line" :value="tab" @update:value="(v: string) => { tab = v; onTabChange() }">
        <!-- 成员/流水/审批：组长+管理可见（管理只读流水、无资金与设置权）；普通成员只有产出 tab -->
        <template v-if="canManage">
          <NTabPane name="members" tab="成员">
            <div class="pg-members">
              <div class="pg-members__toolbar">
                <NButton size="small" type="primary" @click="openAddMember">邀请成员</NButton>
                <span class="pg-members__hint">
                  17x#3：邀请制——对方在「项目组→我的邀请」同意后才入组。限额=成员累计消耗上限（空=不限）
                </span>
              </div>
              <NDataTable
                size="small"
                :columns="memberColumns"
                :data="overview?.group.members ?? []"
                :loading="loadingOverview"
                :row-key="(r: ProjectGroupMemberVO) => r.userId"
                :max-height="420"
              />
              <!-- 17x#3：邀请管理（PENDING 可取消） -->
              <div v-if="invites.length" class="pg-invites">
                <div class="pg-invites__title">待同意邀请（{{ invites.length }}）</div>
                <div v-for="inv in invites" :key="inv.id" class="pg-invites__row">
                  <span>{{ inv.inviteeName || `#${inv.inviteeUserId}` }}</span>
                  <span class="pg-invites__meta">
                    限额 {{ inv.quotaLimitPoints == null ? '不限' : fmt(inv.quotaLimitPoints) }} · {{ fmtTime(inv.createdAt) }}
                  </span>
                  <NButton size="tiny" quaternary type="error" @click="cancelInvite(inv)">取消邀请</NButton>
                </div>
              </div>
            </div>
          </NTabPane>
          <NTabPane name="ledger" tab="组池流水">
            <NDataTable
              remote
              size="small"
              :columns="ledgerColumns"
              :data="overview?.ledger.records ?? []"
              :loading="loadingOverview"
              :pagination="ledgerPagination"
              :max-height="420"
            />
          </NTabPane>
          <!-- 17x#4：入组审批（公共池申请） -->
          <NTabPane name="approvals" tab="入组审批">
            <div class="pg-approvals">
              <NEmpty v-if="!joinRequests.length" description="暂无入组申请（组推入公共池后，全平台用户可申请加入）" />
              <div v-for="r in joinRequests" :key="r.id" class="pg-approvals__row">
                <div class="pg-approvals__main">
                  <span class="pg-approvals__user">{{ r.username || `#${r.userId}` }}</span>
                  <span v-if="r.message" class="pg-approvals__msg" :title="r.message">{{ r.message }}</span>
                  <span class="pg-approvals__time">{{ fmtTime(r.createdAt) }}</span>
                </div>
                <template v-if="r.status === 'PENDING'">
                  <NButton size="tiny" type="primary" @click="decideJoin(r, true)">通过</NButton>
                  <NButton size="tiny" quaternary type="error" @click="decideJoin(r, false)">拒绝</NButton>
                </template>
                <NTag v-else size="small" :bordered="false"
                  :type="r.status === 'APPROVED' ? 'success' : 'default'">
                  {{ JOIN_STATUS_LABEL[r.status] ?? r.status }}
                </NTag>
              </div>
            </div>
          </NTabPane>
          <!-- 17x#2/#4：组设置（产出可见性 + 公共池招募）——管钱/管设置组长专属，管理不可见 -->
          <NTabPane v-if="isOwner" name="settings" tab="设置">
            <div class="pg-settings">
              <div class="pg-settings__section">
                <div class="pg-settings__label">成员产出可见性（谁能看到组内产出记录与图片/视频产物）</div>
                <NRadioGroup v-model:value="visForm.base">
                  <NRadio value="OWN">各自只见自己（默认）</NRadio>
                  <NRadio value="ALL">成员互见全组</NRadio>
                </NRadioGroup>
                <div class="pg-settings__overrides">
                  <span class="pg-settings__label">
                    {{ visForm.base === 'OWN' ? '例外：以下模块全员互见（如 AI 对话）' : '例外：以下模块仅本人+组长可见' }}
                  </span>
                  <NCheckbox
                    v-for="k in kindOptions" :key="k.value"
                    :checked="visForm.modules.includes(k.value)"
                    @update:checked="(v: boolean) => toggleVisModule(k.value, v)"
                  >{{ k.label }}</NCheckbox>
                </div>
                <NButton size="small" type="primary" :loading="savingVis" @click="saveVisibility">保存可见性</NButton>
              </div>
              <div class="pg-settings__section">
                <div class="pg-settings__label">公共池招募</div>
                <div class="pg-settings__hint">
                  推入后全平台用户可在「项目组→公共池」看到并申请加入；人够了可随时撤出（待审批申请自动失效）。
                </div>
                <NButton
                  v-if="!overview?.group.publicPool"
                  size="small" type="primary" tertiary :loading="togglingPool"
                  @click="togglePool(true)"
                >推入公共池</NButton>
                <NButton
                  v-else
                  size="small" type="warning" tertiary :loading="togglingPool"
                  @click="togglePool(false)"
                >撤出公共池</NButton>
                <NTag v-if="overview?.group.publicPool" size="small" type="success" :bordered="false" style="margin-left: 8px">
                  招募中
                </NTag>
              </div>
            </div>
          </NTabPane>
        </template>
        <NTabPane name="outputs" tab="产出">
          <div class="pg-outputs">
            <div class="pg-outputs__filters">
              <NSelect
                v-if="canManage"
                v-model:value="outputFilter.memberUserId"
                size="small"
                clearable
                placeholder="全部成员"
                :options="memberFilterOptions"
                class="pg-outputs__member"
                @update:value="onFilterChange"
              />
              <NSelect
                v-model:value="outputFilter.kind"
                size="small"
                clearable
                placeholder="全部类型"
                :options="kindOptions"
                class="pg-outputs__kind"
                @update:value="onFilterChange"
              />
              <NDatePicker
                v-model:value="outputFilter.range"
                type="daterange"
                size="small"
                clearable
                :actions="['clear']"
                close-on-select
                update-value-on-close
                class="pg-outputs__range"
                @update:value="onFilterChange"
              />
              <NButton size="small" quaternary :disabled="!hasFilters" @click="clearFilters">清空</NButton>
            </div>
            <span v-if="!canManage" class="pg-outputs__hint">可见范围由组长在「设置」中配置（默认仅自己；组长/管理/admin 恒全量）</span>
            <NDataTable
              remote
              size="small"
              :columns="outputColumns"
              :data="outputs?.records ?? []"
              :loading="loadingOutputs"
              :pagination="outputPagination"
              :scroll-x="900"
              :max-height="420"
            />
          </div>
        </NTabPane>
      </NTabs>
    </div>

    <!-- 建组弹窗 -->
    <NModal v-model:show="showCreate" preset="card" title="新建项目组" style="max-width: 400px">
      <NInput v-model:value="createName" placeholder="组名（≤64字）" maxlength="64" @keydown.enter="confirmCreate" />
      <NInput
        v-model:value="createDesc"
        placeholder="描述（可选）"
        style="margin-top: 8px"
        maxlength="200"
      />
      <template #footer>
        <div class="pg-view__modal-footer">
          <NButton size="small" quaternary @click="showCreate = false">取消</NButton>
          <NButton size="small" type="primary" :loading="creating" @click="confirmCreate">建组</NButton>
        </div>
      </template>
    </NModal>

    <!-- 17x#2（V139）：成员功能开关弹窗 —— 不限制开关 + 5 模块白名单；全不勾=全禁 -->
    <NModal v-model:show="showKinds" preset="card" :title="`功能开关 · ${kindsTargetName}`" style="max-width: 420px">
      <div class="pg-kinds">
        <div class="pg-kinds__row">
          <span>不限制（可用全部模块）</span>
          <NSwitch v-model:value="kindsForm.unlimited" />
        </div>
        <template v-if="!kindsForm.unlimited">
          <NCheckbox
            v-for="k in kindOptions" :key="k.value"
            :checked="kindsForm.kinds.includes(k.value)"
            @update:checked="(v: boolean) => toggleKind(k.value, v)"
          >{{ k.label }}</NCheckbox>
          <div v-if="!kindsForm.kinds.length" class="pg-kinds__warn">
            ⚠️ 全不勾 = 全禁：该成员将无法在本组消耗任何模型（组池扣费会被拦截）。
          </div>
        </template>
      </div>
      <template #footer>
        <div class="pg-view__modal-footer">
          <NButton size="small" quaternary @click="showKinds = false">取消</NButton>
          <NButton size="small" type="primary" :loading="savingKinds" @click="saveKinds">保存</NButton>
        </div>
      </template>
    </NModal>

    <!-- 17x#2（V139）：成员级可见性弹窗 —— 每模块三态：跟随组默认/仅自己/全员；优先级高于组设置 -->
    <NModal v-model:show="showVis" preset="card" :title="`可见性 · ${visTargetName}`" style="max-width: 460px">
      <div class="pg-vis">
        <div class="pg-vis__hint">
          对该成员的产出按模块单独设置，优先级高于组「设置」页；全部「跟随组默认」= 清除个人覆盖。
        </div>
        <div v-for="k in kindOptions" :key="k.value" class="pg-vis__row">
          <span class="pg-vis__kind">{{ k.label }}</span>
          <NRadioGroup
            size="small"
            :value="memberVisForm[k.value as GroupKind]"
            @update:value="(v: string) => { memberVisForm[k.value as GroupKind] = v as VisChoice }"
          >
            <NRadio value="FOLLOW">跟随组默认</NRadio>
            <NRadio value="OWN">仅自己</NRadio>
            <NRadio value="ALL">全员可见</NRadio>
          </NRadioGroup>
        </div>
      </div>
      <template #footer>
        <div class="pg-view__modal-footer">
          <NButton size="small" quaternary @click="showVis = false">取消</NButton>
          <NButton size="small" type="primary" :loading="savingMemberVis" @click="saveMemberVisibility">保存</NButton>
        </div>
      </template>
    </NModal>

    <!-- 划拨/回收弹窗 -->
    <NModal
      v-model:show="showAllocate"
      preset="card"
      :title="allocateMode === 'allocate' ? '划拨（个人→组池）' : '回收（组池→个人）'"
      style="max-width: 400px"
    >
      <NInputNumber
        v-model:value="allocatePoints"
        :min="0.01"
        :step="10"
        placeholder="积分"
        style="width: 100%"
      />
      <NInput v-model:value="allocateRemark" placeholder="备注（可选）" maxlength="100" style="margin-top: 8px" />
      <div v-if="allocateMode === 'reclaim'" class="pg-view__allocate-hint">
        回收按「组池余额−在途占用」封顶（在途任务结算后余额可能不足）。
      </div>
      <template #footer>
        <div class="pg-view__modal-footer">
          <NButton size="small" quaternary @click="showAllocate = false">取消</NButton>
          <NButton size="small" type="primary" :loading="allocating" @click="confirmAllocate">
            {{ allocateMode === 'allocate' ? '划拨' : '回收' }}
          </NButton>
        </div>
      </template>
    </NModal>

    <!-- V161（修复III B1）：个人划拨至组内名下弹窗——先还欠款（组长垫→组池垫）余款进名下 -->
    <NModal v-model:show="showSelfTransfer" preset="card" title="划拨至组内名下（个人→组内账户）" style="max-width: 440px">
      <NInputNumber
        v-model:value="selfTransferPoints"
        :min="0.01"
        :step="10"
        placeholder="积分"
        style="width: 100%"
      />
      <div class="pg-view__allocate-hint" style="margin-top: 8px">
        划入积分先还欠款（先退组长垫、后退组池垫，各回各家），余款进「名下余额」——组内消耗时组池扣完后由名下垫付，组长不可回收。
        <template v-if="selfTransferDebtTotal > 0">
          <br>当前欠款 {{ fmt(selfTransferDebtTotal) }} 分（组长垫 {{ fmt(selected?.myDebtLeader ?? 0) }} · 组池垫 {{ fmt(selected?.myDebtPool ?? 0) }}）——还清后解除消费冻结。
        </template>
        <template v-else>
          <br>当前无欠款，全部积分将进入名下余额。
        </template>
      </div>
      <template #footer>
        <div class="pg-view__modal-footer">
          <NButton size="small" quaternary @click="showSelfTransfer = false">取消</NButton>
          <NButton size="small" type="primary" :loading="selfTransferring" @click="confirmSelfTransfer">确认划拨</NButton>
        </div>
      </template>
    </NModal>

    <!-- 邀请成员弹窗（17x#3 + 修复III E3：UserPicker 多选批量邀请；对方同意后才入组） -->
    <NModal v-model:show="showAddMember" preset="card" title="邀请成员" style="max-width: 440px">
      <UserPicker
        v-model="addMemberIds"
        multiple
        placeholder="搜索用户名 / 姓名 / 备注（如：A 班），可多选批量邀请"
        :search="searchCandidatesForPicker"
      />
      <NInputNumber
        v-model:value="addMemberQuota"
        :min="0"
        placeholder="积分限额（空=不限，对每位被邀请人生效）"
        style="width: 100%; margin-top: 8px"
      />
      <div class="pg-view__allocate-hint">发出邀请后，对方在「项目组→我的邀请」同意才入组。</div>
      <template #footer>
        <div class="pg-view__modal-footer">
          <NButton size="small" quaternary @click="showAddMember = false">取消</NButton>
          <NButton size="small" type="primary" :disabled="!addMemberIds.length" @click="confirmAddMember">
            发邀请{{ addMemberIds.length > 1 ? `（${addMemberIds.length} 人）` : '' }}
          </NButton>
        </div>
      </template>
    </NModal>

    <!-- 17x#4：申请加入弹窗（可选留言） -->
    <NModal v-model:show="showApply" preset="card" :title="`申请加入「${applyTarget?.name ?? ''}」`" style="max-width: 400px">
      <NInput
        v-model:value="applyMessage"
        type="textarea"
        :rows="3"
        maxlength="200"
        placeholder="申请留言（可选，组长审批时可见）"
      />
      <template #footer>
        <div class="pg-view__modal-footer">
          <NButton size="small" quaternary @click="showApply = false">取消</NButton>
          <NButton size="small" type="primary" :loading="applying" @click="confirmApply">提交申请</NButton>
        </div>
      </template>
    </NModal>

    <!-- 修复III F2（17x#1）：产出一键入库三弹窗（复用生成页/对话页入库组件，不自建表单）。
         媒体入库同项目判重由后端 F1 兜底（duplicate 返回既有资产 id）；图片多图任务默认第 1 张。 -->
    <SaveImageToAssetDialog
      v-model:show="showImportImage"
      :task-id="importTaskId"
      :image-idx="0"
      :default-name="importName"
      @imported="onMediaImported"
    />
    <SaveVideoToAssetDialog
      v-model:show="showImportVideo"
      :task-id="importTaskId"
      :default-name="importName"
      @imported="onMediaImported"
    />
    <SaveChatToAssetDialog
      v-model:show="showImportChat"
      :content="chatContent"
      :default-name="importName"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import {
  NButton, NCheckbox, NDataTable, NDatePicker, NEmpty, NIcon, NInput, NInputNumber, NModal,
  NPopover, NRadio, NRadioGroup, NSelect, NSwitch,
  NSpin, NTabs, NTabPane, NTag, useDialog, useMessage,
  type DataTableColumns
} from 'naive-ui'
import { AddOutline, ArrowBackOutline } from '@vicons/ionicons5'
import {
  projectGroupApi,
  type ProjectGroupMemberVO,
  type ProjectGroupMineVO,
  type ProjectGroupOutputVO,
  type ProjectGroupLedgerRowVO,
  type ProjectGroupInviteVO,
  type ProjectGroupPoolItemVO,
  type ProjectGroupJoinRequestVO
} from '@/api/projectGroup'
import GroupOutputPreview from '@/components/projectgroup/GroupOutputPreview.vue'
import UserPicker from '@/components/common/UserPicker.vue'
import SaveImageToAssetDialog from '@/components/imagegen/SaveImageToAssetDialog.vue'
import SaveVideoToAssetDialog from '@/components/media/SaveVideoToAssetDialog.vue'
import SaveChatToAssetDialog from '@/components/chat/SaveChatToAssetDialog.vue'
import { assetBridgeApi } from '@/api/assets'
import { isImportable, parseImportedSet } from '@/utils/groupOutputImport'
import {
  kindsFormFromAllowed, allowedFromKindsForm,
  visFormFromOverrides, overridesFromVisForm,
  type KindsForm, type VisForm, type VisChoice, type GroupKind
} from '@/utils/groupPerms'
import { useAuthStore } from '@/stores/auth'
import { useProjectGroupStore } from '@/stores/projectGroup'

/**
 * 计划5 Step7：项目组推进页。
 * 我的组卡片 → 组详情：组长（成员管理/组池流水/划拨回收/全员产出）+ 成员（仅自己产出行，拍板边界）。
 * 权限：菜单/接口 gated project-group:manage（后端再 requireOwner/成员校验兜底）。
 * 17x 未解决#3：平台 admin 代管视角——admin 即使在组里只是 MEMBER 也给管理页签（后端本就 admin 放行）。
 */
const message = useMessage()
const dialog = useDialog()
const auth = useAuthStore()

const groups = ref<ProjectGroupMineVO[]>([])
const loading = ref(false)
const selected = ref<ProjectGroupMineVO | null>(null)
const tab = ref('members')

// ---- 17x#3/#4：我的邀请 / 我的入组申请 / 公共池 ----
const myInvites = ref<ProjectGroupInviteVO[]>([])
const myJoinRequests = ref<ProjectGroupJoinRequestVO[]>([])
const poolMode = ref(false)
const poolItems = ref<ProjectGroupPoolItemVO[]>([])
const loadingPool = ref(false)
const JOIN_STATUS_LABEL: Record<string, string> = {
  PENDING: '待审批', APPROVED: '已通过', REJECTED: '已拒绝', REVOKED: '已失效'
}

// ---- 17x#3：组邀请管理（组长） / 17x#4：入组审批（组长） ----
const invites = ref<ProjectGroupInviteVO[]>([])
const joinRequests = ref<ProjectGroupJoinRequestVO[]>([])

// ---- 17x#2：可见性设置表单 ----
const visForm = ref<{ base: 'OWN' | 'ALL'; modules: string[] }>({ base: 'OWN', modules: [] })
const savingVis = ref(false)
const togglingPool = ref(false)

// ---- 组长总览（overview = 详情+流水分页） ----
const overview = ref<Awaited<ReturnType<typeof projectGroupApi.overview>>['data']['data'] | null>(null)
const loadingOverview = ref(false)
const ledgerPage = ref(1)
const ledgerSize = ref(10)

// ---- 产出列表（组长全员/成员仅自己） ----
const outputs = ref<Awaited<ReturnType<typeof projectGroupApi.outputs>>['data']['data'] | null>(null)
const loadingOutputs = ref(false)
const outputPage = ref(1)
const outputSize = ref(10)
const outputFilter = ref<{
  memberUserId: number | null
  kind: string | null
  range: [number, number] | null
}>({ memberUserId: null, kind: null, range: null })

const isOwner = computed(() => selected.value?.myRole === 'OWNER')
/** 17x#2（V139）：MANAGER 管人不管钱——可见成员/流水（只读）/审批 tab，不见设置 tab 与划拨回收按钮。 */
const isManager = computed(() => selected.value?.myRole === 'MANAGER')
/** 17x 未解决#3：平台 admin 代管视角——组内身份即使是 MEMBER 也给管理页签（后端 admin 恒放行）。 */
const canManage = computed(() => isOwner.value || isManager.value || auth.isAdmin)
const ROLE_LABEL: Record<string, string> = { OWNER: '组长', MANAGER: '管理', MEMBER: '成员' }
const hasFilters = computed(() =>
  outputFilter.value.memberUserId != null || !!outputFilter.value.kind || !!outputFilter.value.range)

// ---- 修复III F2（17x#1）：产出一键入库（组长/管理；媒体复用生成页入库桥，CHAT 复用对话入库弹窗） ----
/** 本页媒体任务已入库集合（taskId 维度，跨项目；加载产出后一条 IN 查询回填，防逐行 N+1）。 */
const importedTaskIds = ref<Set<number>>(new Set())
const showImportImage = ref(false)
const showImportVideo = ref(false)
const showImportChat = ref(false)
const importTaskId = ref<number | null>(null)
const importName = ref('')
const chatContent = ref('')

const kindOptions = [
  { label: '对话', value: 'CHAT' },
  { label: '视频', value: 'VIDEO' },
  { label: '图片', value: 'IMAGE' },
  { label: '嵌入', value: 'EMBED' },
  { label: '重排', value: 'RERANK' }
]

/** 积分显示：去尾零（后端 DECIMAL 可能带 .00）。 */
function fmt(n: number | null | undefined): string {
  if (n == null) return '0'
  return Number.isInteger(n) ? String(n) : String(n).replace(/\.?0+$/, '')
}

function fmtTime(t: string | null): string {
  if (!t) return '-'
  return t.slice(0, 16).replace('T', ' ')
}

// ==================== 列表 ====================

async function loadGroups() {
  loading.value = true
  try {
    const res = await projectGroupApi.mine()
    groups.value = res.data.data
  } catch {
    message.error('加载我的项目组失败')
  } finally {
    loading.value = false
  }
}

function openGroup(g: ProjectGroupMineVO) {
  selected.value = g
  tab.value = (g.myRole === 'OWNER' || g.myRole === 'MANAGER' || auth.isAdmin) ? 'members' : 'outputs'
  invites.value = []
  joinRequests.value = []
  onTabChange()
}

function backToList() {
  selected.value = null
  overview.value = null
  outputs.value = null
  void loadGroups()
  void loadMyInvites()
  void loadMyJoinRequests()
}

// ==================== 17x#3：我的邀请 ====================

async function loadMyInvites() {
  try {
    const res = await projectGroupApi.myInvites()
    myInvites.value = res.data.data
  } catch {
    /* 拦截器已提示 */
  }
}

async function answerInvite(inv: ProjectGroupInviteVO, accept: boolean) {
  try {
    await (accept ? projectGroupApi.acceptInvite(inv.id) : projectGroupApi.declineInvite(inv.id))
    message.success(accept ? '已加入项目组' : '已拒绝邀请')
    await loadMyInvites()
    await loadGroups()
  } catch {
    /* 拦截器已提示 */
  }
}

// ==================== 17x#4：公共池 / 我的申请 ====================

async function loadMyJoinRequests() {
  try {
    const res = await projectGroupApi.myJoinRequests()
    myJoinRequests.value = res.data.data
  } catch {
    /* 拦截器已提示 */
  }
}

function togglePoolMode() {
  poolMode.value = !poolMode.value
  if (poolMode.value) void loadPool()
}

async function loadPool() {
  loadingPool.value = true
  try {
    const res = await projectGroupApi.pool()
    poolItems.value = res.data.data
  } catch {
    message.error('公共池加载失败')
  } finally {
    loadingPool.value = false
  }
}

const showApply = ref(false)
const applyTarget = ref<ProjectGroupPoolItemVO | null>(null)
const applyMessage = ref('')
const applying = ref(false)

function openApply(g: ProjectGroupPoolItemVO) {
  applyTarget.value = g
  applyMessage.value = ''
  showApply.value = true
}

async function confirmApply() {
  if (!applyTarget.value) return
  applying.value = true
  try {
    await projectGroupApi.applyJoin(applyTarget.value.id, applyMessage.value.trim() || undefined)
    message.success('申请已提交，待组长审批')
    showApply.value = false
    await loadPool()
    await loadMyJoinRequests()
  } catch {
    /* 拦截器已提示 */
  } finally {
    applying.value = false
  }
}

async function cancelMyJoin(r: ProjectGroupJoinRequestVO) {
  try {
    await projectGroupApi.cancelJoinRequest(r.id)
    message.success('申请已取消')
    await loadMyJoinRequests()
  } catch {
    /* 拦截器已提示 */
  }
}

// ==================== 17x#3：组邀请管理（组长） ====================

async function loadInvites() {
  const g = selected.value
  if (!g || !canManage.value) return
  try {
    const res = await projectGroupApi.listInvites(g.id)
    invites.value = res.data.data.filter(i => i.status === 'PENDING')
  } catch {
    /* 拦截器已提示 */
  }
}

async function cancelInvite(inv: ProjectGroupInviteVO) {
  try {
    await projectGroupApi.cancelInvite(inv.id)
    message.success('邀请已取消')
    await loadInvites()
  } catch {
    /* 拦截器已提示 */
  }
}

// ==================== 17x#4：入组审批（组长） ====================

async function loadJoinRequests() {
  const g = selected.value
  if (!g || !canManage.value) return
  try {
    const res = await projectGroupApi.listJoinRequests(g.id)
    joinRequests.value = res.data.data
  } catch {
    /* 拦截器已提示 */
  }
}

async function decideJoin(r: ProjectGroupJoinRequestVO, approve: boolean) {
  try {
    await projectGroupApi.decideJoinRequest(r.id, approve)
    message.success(approve ? '已通过，申请人已入组' : '已拒绝')
    await loadJoinRequests()
    if (approve) void loadOverview()
  } catch {
    /* 拦截器已提示 */
  }
}

// ==================== 17x#2/#4：组设置 ====================

function toggleVisModule(kind: string, checked: boolean) {
  const cur = visForm.value.modules
  visForm.value.modules = checked ? [...cur, kind] : cur.filter(k => k !== kind)
}

/** overview 详情 → 设置表单（base=组默认；modules=覆盖表中与 base 相反的模块集）。 */
function syncVisForm() {
  const g = overview.value?.group
  if (!g) return
  const base = g.memberOutputVisibility === 'ALL' ? 'ALL' : 'OWN'
  let overrides: Record<string, string> = {}
  try {
    overrides = g.moduleVisibilityOverrides ? JSON.parse(g.moduleVisibilityOverrides) : {}
  } catch {
    overrides = {}
  }
  const inverse = base === 'OWN' ? 'ALL' : 'OWN'
  visForm.value = {
    base,
    modules: Object.entries(overrides).filter(([, v]) => v === inverse).map(([k]) => k)
  }
}

async function saveVisibility() {
  const g = selected.value
  if (!g) return
  savingVis.value = true
  try {
    // 覆盖表只存「与 base 相反」的模块（稀疏语义；其余模块回落 base）
    const inverse = visForm.value.base === 'OWN' ? 'ALL' : 'OWN'
    const overrides: Record<string, 'OWN' | 'ALL'> = {}
    for (const k of visForm.value.modules) overrides[k] = inverse as 'OWN' | 'ALL'
    await projectGroupApi.updateVisibility(g.id, {
      memberOutputVisibility: visForm.value.base,
      moduleVisibilityOverrides: overrides
    })
    message.success('可见性设置已更新')
    await loadOverview()
  } catch {
    /* 拦截器已提示 */
  } finally {
    savingVis.value = false
  }
}

async function togglePool(publish: boolean) {
  const g = selected.value
  if (!g) return
  togglingPool.value = true
  try {
    await (publish ? projectGroupApi.publish(g.id) : projectGroupApi.unpublish(g.id))
    message.success(publish ? '已推入公共池，全平台可申请加入' : '已撤出公共池，待审批申请已失效')
    await loadOverview()
    if (!publish) await loadJoinRequests()
  } catch {
    /* 拦截器已提示 */
  } finally {
    togglingPool.value = false
  }
}


// ---- 建组 ----
const showCreate = ref(false)
const creating = ref(false)
const createName = ref('')
const createDesc = ref('')

async function confirmCreate() {
  const name = createName.value.trim()
  if (!name) return
  creating.value = true
  try {
    await projectGroupApi.create(name, createDesc.value.trim() || undefined)
    message.success('项目组已创建（你已是组长）')
    showCreate.value = false
    createName.value = ''
    createDesc.value = ''
    await loadGroups()
  } catch {
    /* 拦截器已提示 */
  } finally {
    creating.value = false
  }
}

// ==================== 总览加载 ====================

async function loadOverview() {
  const g = selected.value
  if (!g || !canManage.value) return
  loadingOverview.value = true
  try {
    const res = await projectGroupApi.overview(g.id, ledgerPage.value, ledgerSize.value)
    overview.value = res.data.data
    syncVisForm()
  } catch {
    message.error('组总览加载失败')
  } finally {
    loadingOverview.value = false
  }
}

async function loadOutputs() {
  const g = selected.value
  if (!g) return
  loadingOutputs.value = true
  try {
    const f = outputFilter.value
    const res = await projectGroupApi.outputs(g.id, {
      memberUserId: canManage.value ? (f.memberUserId ?? undefined) : undefined,
      kind: f.kind ?? undefined,
      // daterange：to=尾日 23:59:59.999（含整天）
      from: f.range ? new Date(f.range[0]).toISOString() : undefined,
      to: f.range ? new Date(f.range[1] + 86399999).toISOString() : undefined,
      page: outputPage.value,
      size: outputSize.value
    })
    outputs.value = res.data.data
    void refreshImported()
  } catch {
    message.error('产出列表加载失败')
  } finally {
    loadingOutputs.value = false
  }
}

/** 修复III F2：回填本页媒体行「已入库」态（一条 IN 查询；无权限/失败静默=不显 tag）。 */
async function refreshImported() {
  const ids = (outputs.value?.records ?? [])
    .map(r => r.taskId)
    .filter((t): t is number => t != null)
  if (!canManage.value || !ids.length) {
    importedTaskIds.value = new Set()
    return
  }
  try {
    const res = await assetBridgeApi.existsBySource(ids)
    importedTaskIds.value = parseImportedSet(res.data.data)
  } catch {
    importedTaskIds.value = new Set()
  }
}

/** 修复III F2：行「入库」按钮——IMAGE 走图弹窗（多图任务默认第 1 张，整组其余张去生成页逐张入库）、
 *  VIDEO 走视频弹窗、CHAT 走对话弹窗（预填该轮 assistant 回复）。 */
function openImport(r: ProjectGroupOutputVO) {
  importTaskId.value = r.taskId
  chatContent.value = r.chatResult ?? ''
  const raw = (r.mediaPrompt ?? r.chatResult ?? '').trim()
  importName.value = raw.slice(0, 30) || (r.kind === 'VIDEO' ? '视频产出' : r.kind === 'CHAT' ? '对话产出' : '图片产出')
  if (r.kind === 'CHAT') showImportChat.value = true
  else if (r.kind === 'VIDEO') showImportVideo.value = true
  else showImportImage.value = true
}

/** 媒体入库成功回调：本页行置「已入库」（弹窗自身已 toast 新建/判重两种话术，此处不重复）。 */
function onMediaImported() {
  if (importTaskId.value != null) importedTaskIds.value.add(importTaskId.value)
}

function onTabChange() {
  if (tab.value === 'outputs') {
    outputPage.value = 1
    void loadOutputs()
  } else if (tab.value === 'approvals') {
    void loadJoinRequests()
  } else if (canManage.value) {
    void loadOverview()
    if (tab.value === 'members') void loadInvites()
  }
}

function onFilterChange() {
  outputPage.value = 1
  void loadOutputs()
}

function clearFilters() {
  outputFilter.value = { memberUserId: null, kind: null, range: null }
  onFilterChange()
}

const memberFilterOptions = computed(() =>
  (overview.value?.group.members ?? []).map(m => ({
    label: m.displayName || m.username || `#${m.userId}`,
    value: m.userId
  })))

const ledgerPagination = computed(() => ({
  page: ledgerPage.value,
  pageSize: ledgerSize.value,
  itemCount: overview.value?.ledger.total ?? 0,
  pageSizes: [10, 20, 50],
  showSizePicker: true,
  onChange: (p: number) => { ledgerPage.value = p; void loadOverview() },
  onUpdatePageSize: (s: number) => { ledgerPage.value = 1; ledgerSize.value = s; void loadOverview() }
}))

const outputPagination = computed(() => ({
  page: outputPage.value,
  pageSize: outputSize.value,
  itemCount: outputs.value?.total ?? 0,
  pageSizes: [10, 20, 50],
  showSizePicker: true,
  onChange: (p: number) => { outputPage.value = p; void loadOutputs() },
  onUpdatePageSize: (s: number) => { outputPage.value = 1; outputSize.value = s; void loadOutputs() }
}))

// ==================== 表格列 ====================

const LEDGER_TYPE: Record<string, { label: string; type: 'default' | 'info' | 'success' | 'warning' | 'error' }> = {
  ALLOCATE: { label: '划入', type: 'success' },
  RECLAIM: { label: '回收', type: 'warning' },
  CONSUME: { label: '消耗', type: 'info' },
  REFUND: { label: '退款', type: 'default' },
  ADMIN_ADJUST: { label: '调整', type: 'default' },
  BACKSTOP: { label: '兜底', type: 'error' }
}

const ledgerColumns: DataTableColumns<ProjectGroupLedgerRowVO> = [
  { title: '时间', key: 'createdAt', width: 140, render: r => fmtTime(r.createdAt) },
  { title: '类型', key: 'type', width: 80, render: r => {
    const t = LEDGER_TYPE[r.type]
    return h(NTag, { size: 'small', type: t?.type ?? 'default', bordered: false }, () => t?.label ?? r.type)
  } },
  { title: '操作人', key: 'actorUsername', width: 110, render: r => r.actorUsername ?? (r.actorUserId != null ? `#${r.actorUserId}` : '-') },
  { title: '变动', key: 'deltaPoints', width: 100, render: r => {
    const v = Number(r.deltaPoints)
    const sign = r.type === 'CONSUME' ? '-' : (v > 0 ? '+' : '')
    return h('span', { class: v < 0 || r.type === 'CONSUME' ? 'pg-ledger__neg' : 'pg-ledger__pos' },
      `${sign}${fmt(r.deltaPoints)}`)
  } },
  { title: '余额', key: 'balanceAfter', width: 90, render: r => fmt(r.balanceAfter) },
  { title: '关联', key: 'ref', width: 90, render: r => r.refType ? `${r.refType}${r.refId ? '#' + r.refId : ''}` : '-' },
  { title: '备注', key: 'remark', ellipsis: { tooltip: true }, render: r => r.remark ?? '' }
]

const KIND_LABEL: Record<string, string> = { CHAT: '对话', VIDEO: '视频', IMAGE: '图片', EMBED: '嵌入', RERANK: '重排' }

const outputColumns: DataTableColumns<ProjectGroupOutputVO> = [
  { title: '时间', key: 'createdAt', width: 140, render: r => fmtTime(r.createdAt) },
  { title: '成员', key: 'username', width: 130, render: r => {
    // 17x#2：昵称/姓名优先，账号括注（无昵称回落账号）
    const uname = r.username ?? (r.userId != null ? `#${r.userId}` : '-')
    return r.displayName && r.displayName !== r.username ? `${r.displayName}（${uname}）` : uname
  } },
  { title: '类型', key: 'kind', width: 70, render: r => KIND_LABEL[r.kind] ?? r.kind },
  { title: '模型', key: 'model', width: 150, ellipsis: { tooltip: true }, render: r => r.model ?? '-' },
  {
    title: '内容', key: 'mediaPrompt', width: 260,
    // 17x 未解决#3：媒体行=prompt；CHAT 行=该次调用的用户提问（后端按轮配对）——不再显「对话消耗」占位
    render: r => {
      const text = r.mediaPrompt ?? '-'
      return h('div', { class: 'pg-output-cell' }, text)
    }
  },
  {
    title: '任务状态', key: 'mediaStatus', width: 100,
    render: r => r.taskId != null ? (r.mediaStatus ?? '-') : '-'
  },
  { title: '积分', key: 'pointsConsumed', width: 90, render: r => fmt(r.pointsConsumed) },
  // 17x#1：媒体产物预览（图片缩略图/视频弹窗播放；文件按组可见性设置放行）
  {
    title: '预览', key: 'preview', width: 130,
    render: r => h(GroupOutputPreview, { row: r })
  },
  // 修复III F2（17x#1）：一键入库——组长/管理可见；已入库显 tag；CHAT 预填该轮回复
  {
    title: '入库', key: 'import', width: 90, fixed: 'right',
    render: r => {
      if (!canManage.value) return '-'
      if (r.taskId != null && importedTaskIds.value.has(r.taskId)) {
        return h(NTag, { size: 'small', type: 'success', bordered: false }, () => '已入库')
      }
      // 与预览列同口径：媒体行按产物文件在否（SUCCEEDED 才有 fileId），CHAT 按有回复
      if (!isImportable(r)) return '-'
      return h(NButton, { size: 'small', quaternary: true, onClick: () => openImport(r) }, () => '入库')
    }
  }
]

const memberColumns = computed<DataTableColumns<ProjectGroupMemberVO>>(() => [
  { title: '用户', key: 'username', width: 170, render: r => {
    // 17x#2：昵称/姓名（账号）双显——无名回落账号；E3（12x#4）：备注灰 tag 悬浮全文
    const base = r.displayName || r.username || `#${r.userId}`
    const name = r.displayName && r.username && r.displayName !== r.username
      ? `${r.displayName}（${r.username}）` : base
    return h('span', null, [
      name,
      r.owner ? h(NTag, { size: 'tiny', type: 'primary', bordered: false, style: 'margin-left: 6px' }, () => '组长') : null,
      r.remark
        ? h(NPopover, { trigger: 'hover', placement: 'top' }, {
            trigger: () => h(NTag, { size: 'tiny', bordered: false, style: 'margin-left: 6px; max-width: 90px' },
              { default: () => r.remark }),
            default: () => h('span', null, r.remark ?? '')
          })
        : null
    ])
  } },
  // 17x#2（V139）：角色列——OWNER 视角可下拉任免（MEMBER↔MANAGER）；MANAGER 只读标签
  { title: '角色', key: 'role', width: 110, render: r => {
    if (r.owner) return h(NTag, { size: 'tiny', type: 'primary', bordered: false }, () => '组长')
    if (isOwner.value) {
      return h(NSelect, {
        size: 'tiny',
        style: 'width: 88px',
        value: r.role,
        options: [
          { label: '成员', value: 'MEMBER' },
          { label: '管理', value: 'MANAGER' }
        ],
        onUpdateValue: (v: string) => void changeRole(r, v as 'MEMBER' | 'MANAGER')
      })
    }
    return h(NTag, { size: 'tiny', bordered: false, type: r.role === 'MANAGER' ? 'warning' : 'default' },
      () => ROLE_LABEL[r.role] ?? r.role)
  } },
  { title: '限额', key: 'quotaLimitPoints', width: 110, render: r => r.quotaLimitPoints == null ? '不限' : fmt(r.quotaLimitPoints) },
  { title: '已用', key: 'usedPoints', width: 100, render: r => fmt(r.usedPoints) },
  // V161（修复III B）：名下余额/欠款列——欠款>0 红字，悬浮拆分垫付来源
  { title: '名下', key: 'selfPoints', width: 90, render: r => Number(r.selfPoints) > 0 ? fmt(r.selfPoints) : '-' },
  { title: '欠款', key: 'debtPoints', width: 110, render: r => {
    const total = Number(r.debtPoolPoints ?? 0) + Number(r.debtLeaderPoints ?? 0)
    if (!(total > 0)) return h('span', { class: 'pg-members__hint' }, '-')
    return h(NPopover, { trigger: 'hover', placement: 'top' }, {
      trigger: () => h('span', { class: 'pg-debt-red' }, fmt(total)),
      default: () => h('span', null,
        `组长垫 ${fmt(r.debtLeaderPoints)} · 组池垫 ${fmt(r.debtPoolPoints)}——组内消费冻结中（划拨或调限额抵清后恢复）`)
    })
  } },
  // 17x 未解决#1（V156 层级额度）：管理行显「可分配」=额度−子树已耗−下级预留——管理自己随时知道还能分多少
  { title: '可分配', key: 'allocatablePoints', width: 100, render: r => {
    if (r.role !== 'MANAGER') return '-'
    if (r.quotaLimitPoints == null) return '不限'
    return fmt(r.allocatablePoints ?? 0)
  } },
  { title: '加入时间', key: 'joinedAt', width: 140, render: r => fmtTime(r.joinedAt) },
  {
    title: '操作', key: 'actions', width: 320,
    render: r => {
      if (r.owner) return h('span', { class: 'pg-members__hint' }, '—')
      // V156：MANAGER 行——组长可调预算/重置已用（任免走角色列）；管理视角只读
      if (r.role !== 'MEMBER') {
        if (!isOwner.value) return h('span', { class: 'pg-members__hint' }, '管理行：仅组长可任免/调额')
        const btn = (label: string, onClick: () => void, type: 'primary' | 'default' = 'default') =>
          h(NButton, { size: 'tiny', quaternary: true, type, onClick }, () => label)
        return h('div', { style: 'display:flex;gap:4px;flex-wrap:wrap' }, [
          btn('调额度', () => openQuota(r), 'primary'),
          btn('重置已用', () => confirmResetUsed(r))
        ])
      }
      const btn = (label: string, onClick: () => void, type: 'primary' | 'default' | 'error' = 'default') =>
        h(NButton, { size: 'tiny', quaternary: true, type, onClick }, () => label)
      return h('div', { style: 'display:flex;gap:4px;flex-wrap:wrap' }, [
        btn('调限额', () => openQuota(r), 'primary'),
        btn('重置已用', () => confirmResetUsed(r)),
        btn('功能开关', () => openKinds(r)),
        btn('可见性', () => openVis(r)),
        btn('移除', () => confirmRemove(r), 'error')
      ])
    }
  }
])

// ==================== 成员管理 ====================

/** 我（管理视角）在成员表里的行——取自己的可分配额度（V156）。 */
const selfMemberRow = computed(() =>
  (overview.value?.group.members ?? []).find(m => m.userId === auth.userInfo?.id) ?? null)

function openQuota(m: ProjectGroupMemberVO) {
  // V156 层级额度：按「目标角色 × 我的角色」给预算上下文提示
  let content = '输入新限额（留空=改为不限）。调低不追溯已耗，仅约束后续消耗。'
  if (m.role === 'MANAGER') {
    content = `给管理定预算额度（留空=不限）。其下级消耗与已分配预留都会占用该额度；当前可分配 ${m.allocatablePoints == null ? '不限' : fmt(m.allocatablePoints)}，新额度不得低于已占用。`
  } else if (isManager.value) {
    const avail = selfMemberRow.value?.allocatablePoints
    content = `输入新限额。你当前剩余可分配 ${avail == null ? '不限' : fmt(avail)}（自己的额度 − 子树已耗 − 成员预留），分配不可超出；调低不追溯已耗。`
  }
  dialog.warning({
    title: `调整 ${m.displayName || m.username || '#' + m.userId} 的${m.role === 'MANAGER' ? '预算额度' : '限额'}`,
    content,
    positiveText: '保存',
    negativeText: '取消',
    onPositiveClick: async () => {
      const input = window.prompt(
        `新限额（当前 ${m.quotaLimitPoints == null ? '不限' : fmt(m.quotaLimitPoints)}，输入数字或留空=不限）`,
        m.quotaLimitPoints == null ? '' : String(m.quotaLimitPoints))
      if (input === null) return
      const val = input.trim() === '' ? null : Number(input)
      if (val != null && (!Number.isFinite(val) || val < 0)) {
        message.error('限额须为非负数字')
        return
      }
      try {
        await projectGroupApi.updateQuota(selected.value!.id, m.userId, val)
        message.success('限额已更新')
        void loadOverview()
      } catch { /* 拦截器已提示 */ }
    }
  })
}

function confirmResetUsed(m: ProjectGroupMemberVO) {
  dialog.warning({
    title: '重置成员已用',
    content: `把 ${m.displayName || m.username || '#' + m.userId} 的已用从 ${fmt(m.usedPoints)} 归零？会记一笔「调整」流水留痕，限额不变。`,
    positiveText: '重置',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await projectGroupApi.resetUsed(selected.value!.id, m.userId)
        message.success('已重置')
        void loadOverview()
      } catch { /* 拦截器已提示 */ }
    }
  })
}

function confirmRemove(m: ProjectGroupMemberVO) {
  dialog.warning({
    title: '移除成员',
    content: `把 ${m.displayName || m.username || '#' + m.userId} 移出项目组？历史流水留痕不受影响。`,
    positiveText: '移除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await projectGroupApi.removeMember(selected.value!.id, m.userId)
        message.success('已移除')
        void loadOverview()
      } catch { /* 拦截器已提示 */ }
    }
  })
}

// ==================== 17x#2（V139）：角色任免 / 功能开关 / 成员级可见性 ====================

async function changeRole(m: ProjectGroupMemberVO, role: 'MEMBER' | 'MANAGER') {
  if (m.role === role) return
  try {
    await projectGroupApi.updateMemberRole(selected.value!.id, m.userId, role)
    message.success(role === 'MANAGER' ? '已任命为管理（可管人/审批/看流水，不管钱）' : '已降为普通成员')
    void loadOverview()
  } catch { /* 拦截器已提示 */ }
}

// ---- 功能开关弹窗 ----
const showKinds = ref(false)
const kindsTarget = ref<ProjectGroupMemberVO | null>(null)
const kindsForm = ref<KindsForm>({ unlimited: true, kinds: [] })
const savingKinds = ref(false)
const kindsTargetName = computed(() =>
  kindsTarget.value ? (kindsTarget.value.displayName || kindsTarget.value.username || `#${kindsTarget.value.userId}`) : '')

function openKinds(m: ProjectGroupMemberVO) {
  kindsTarget.value = m
  kindsForm.value = kindsFormFromAllowed(m.allowedKinds)
  showKinds.value = true
}

function toggleKind(kind: string, checked: boolean) {
  const cur = kindsForm.value.kinds
  kindsForm.value.kinds = checked ? [...cur, kind] : cur.filter(k => k !== kind)
}

async function saveKinds() {
  const t = kindsTarget.value
  if (!t) return
  savingKinds.value = true
  try {
    await projectGroupApi.updateMemberKinds(selected.value!.id, t.userId, allowedFromKindsForm(kindsForm.value))
    message.success('成员可用模块已更新')
    showKinds.value = false
    void loadOverview()
  } catch { /* 拦截器已提示 */ } finally {
    savingKinds.value = false
  }
}

// ---- 成员级可见性弹窗 ----
const showVis = ref(false)
const visTarget = ref<ProjectGroupMemberVO | null>(null)
const memberVisForm = ref<VisForm>(visFormFromOverrides(null))
const savingMemberVis = ref(false)
const visTargetName = computed(() =>
  visTarget.value ? (visTarget.value.displayName || visTarget.value.username || `#${visTarget.value.userId}`) : '')

function openVis(m: ProjectGroupMemberVO) {
  visTarget.value = m
  memberVisForm.value = visFormFromOverrides(m.memberVisibilityOverrides)
  showVis.value = true
}

async function saveMemberVisibility() {
  const t = visTarget.value
  if (!t) return
  savingMemberVis.value = true
  try {
    await projectGroupApi.updateMemberVisibility(selected.value!.id, t.userId, overridesFromVisForm(memberVisForm.value))
    message.success('成员可见性覆盖已更新')
    showVis.value = false
    void loadOverview()
  } catch { /* 拦截器已提示 */ } finally {
    savingMemberVis.value = false
  }
}

// ---- 加成员 ----
const showAddMember = ref(false)
// 修复III E3（12x#4）：多选批量邀请（UserPicker），备注进候选行
const addMemberIds = ref<number[]>([])
const addMemberQuota = ref<number | null>(null)

function openAddMember() {
  addMemberIds.value = []
  addMemberQuota.value = null
  showAddMember.value = true
}

/** E3：UserPicker 数据源——组候选接口（排除组长/已有成员），按备注筛人 */
async function searchCandidatesForPicker(q: string): Promise<import('@/components/common/UserPicker.vue').PickerUser[]> {
  const res = await projectGroupApi.candidates(selected.value!.id, q)
  return res.data.data.map(c => ({ userId: c.userId, username: c.username, name: c.name, remark: c.remark }))
}

async function confirmAddMember() {
  if (!addMemberIds.value.length) return
  try {
    // 17x#3 邀请制 + E3 批量：逐个发邀请（串行防限流；对方同意后入组）
    let ok = 0
    for (const uid of addMemberIds.value) {
      await projectGroupApi.inviteMember(selected.value!.id, uid, addMemberQuota.value)
      ok++
    }
    message.success(`已向 ${ok} 人发送邀请，待对方同意后入组`)
    showAddMember.value = false
    void loadInvites()
  } catch { /* 拦截器已提示（中断处起后续未发，重开弹窗重选即可） */ }
}

// ==================== 划拨/回收 ====================

const showAllocate = ref(false)
const allocateMode = ref<'allocate' | 'reclaim'>('allocate')
const allocatePoints = ref<number | null>(null)
const allocateRemark = ref('')
const allocating = ref(false)

function openAllocate(mode: 'allocate' | 'reclaim') {
  allocateMode.value = mode
  allocatePoints.value = null
  allocateRemark.value = ''
  showAllocate.value = true
}

async function confirmAllocate() {
  const pts = allocatePoints.value
  if (!pts || pts <= 0) {
    message.warning('请输入正数积分')
    return
  }
  allocating.value = true
  try {
    await (allocateMode.value === 'allocate'
      ? projectGroupApi.allocate(selected.value!.id, pts, allocateRemark.value.trim() || undefined)
      : projectGroupApi.reclaim(selected.value!.id, pts, allocateRemark.value.trim() || undefined))
    message.success(allocateMode.value === 'allocate' ? '划拨成功' : '回收成功')
    showAllocate.value = false
    void loadOverview()
    // 划拨动了个人钱包 → 组卡片余额也刷新
    void loadGroups()
  } catch {
    /* 拦截器已提示（余额不足/在途上限等由后端文案） */
  } finally {
    allocating.value = false
  }
}

// 划拨/回收后刷新列表态卡片（不影响详情态）
watch(showAllocate, v => { if (!v) void loadGroups() })

// ==================== V161（修复III B1）：个人划拨至组内名下 ====================

const showSelfTransfer = ref(false)
const selfTransferPoints = ref<number | null>(null)
const selfTransferring = ref(false)
/** 幂等防连点：每次打开弹窗生成新 nonce，重试不吞、双击不双扣（后端 self-transfer-{uid}-{nonce}）。 */
const selfTransferNonce = ref('')

const selfTransferDebtTotal = computed(() =>
  Number(selected.value?.myDebtPool ?? 0) + Number(selected.value?.myDebtLeader ?? 0))

function openSelfTransfer() {
  selfTransferPoints.value = null
  selfTransferNonce.value = String(Date.now())
  showSelfTransfer.value = true
}

async function confirmSelfTransfer() {
  const pts = selfTransferPoints.value
  if (!pts || pts <= 0) {
    message.warning('请输入正数积分')
    return
  }
  if (!selected.value || !auth.userInfo?.id) return
  selfTransferring.value = true
  try {
    await projectGroupApi.selfTransfer(
      selected.value.id, auth.userInfo.id, pts, selfTransferNonce.value + '-' + pts)
    message.success('划拨成功')
    showSelfTransfer.value = false
    void loadGroups()   // 名下/欠款/组池余额刷新
  } catch {
    /* 拦截器已提示（余额不足/非成员等由后端文案） */
  } finally {
    selfTransferring.value = false
  }
}

onMounted(() => {
  void loadGroups()
  void loadMyInvites()
  void loadMyJoinRequests()
})

// 计划 E5（7x-3）：组池/成员积分事件驱动刷新——组卡片余额已由 store 秒级更新，
// 此处刷新组列表（限额/used 等）；防抖 1s 合并连发事件；页签不可见跳过
{
  const pgStore = useProjectGroupStore()
  let refreshTimer: ReturnType<typeof setTimeout> | null = null
  watch(() => pgStore.lastEvent, evt => {
    if (!evt || document.visibilityState !== 'visible') return
    if (refreshTimer) clearTimeout(refreshTimer)
    refreshTimer = setTimeout(() => { void loadGroups() }, 1000)
  })
}
</script>

<style lang="scss" scoped>
/* V161（修复III B）：欠款红字（成员表「欠款」列 + 详情头冻结提示共用） */
.pg-debt-red {
  color: var(--error-color, #e88080);
  font-weight: 600;
}

.pg-view {
  height: 100%;
  padding: var(--spacing-4);
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-3);
}

.pg-view__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.pg-view__title {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin: 0;
}

.pg-view__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: var(--spacing-3);
}

.pg-card {
  padding: var(--spacing-3) var(--spacing-4);
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-in-out);

  &:hover {
    border-color: var(--color-primary);
    transform: translateY(-2px);
    box-shadow: var(--shadow-md);
  }

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--spacing-2);
  }

  &__name {
    font-size: var(--font-size-base);
    font-weight: var(--font-weight-medium);
    color: var(--color-text-primary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  &__balance {
    margin-top: var(--spacing-2);
    font-size: var(--font-size-lg);
    color: var(--color-primary);
    font-weight: var(--font-weight-bold);
  }

  &__meta {
    margin-top: 2px;
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
  }
}

.pg-view__detail-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  flex-wrap: wrap;
}

.pg-view__detail-name {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.pg-view__balance-chip {
  font-size: var(--font-size-sm);
  color: var(--color-primary);
}

.pg-view__modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--spacing-2);
}

.pg-view__allocate-hint {
  margin-top: var(--spacing-2);
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  line-height: 1.5;
}

.pg-members__toolbar {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  margin-bottom: var(--spacing-2);
}

.pg-members__hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.pg-outputs__filters {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  margin-bottom: var(--spacing-2);
  flex-wrap: wrap;
}

.pg-outputs__member { width: 160px; }
.pg-outputs__kind { width: 120px; }
.pg-outputs__range { width: 240px; }
.pg-outputs__hint {
  display: block;
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  margin-bottom: var(--spacing-2);
}

.pg-ledger__pos { color: var(--color-success, #63e2b7); }
.pg-ledger__neg { color: var(--color-error, #e88080); }

/* 17x#3/#4：列表页通知条（我的邀请/我的申请） */
.pg-view__header-actions {
  display: flex;
  gap: var(--spacing-2);
}

.pg-notice {
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  padding: var(--spacing-2) var(--spacing-3);
}

.pg-notice__title {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  margin-bottom: var(--spacing-1);
}

.pg-notice__row {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  padding: 4px 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

/* 公共池卡片 */
.pg-card--pool {
  cursor: default;
}

.pg-card__desc {
  margin-top: var(--spacing-1);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.pg-card__foot {
  margin-top: var(--spacing-2);
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
}

/* 17x#3：邀请管理 */
.pg-invites {
  margin-top: var(--spacing-3);
  border-top: 1px dashed var(--color-border-light);
  padding-top: var(--spacing-2);
}

.pg-invites__title {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  margin-bottom: var(--spacing-1);
}

.pg-invites__row {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  padding: 4px 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.pg-invites__meta {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  margin-right: auto;
}

/* 17x#4：入组审批 */
.pg-approvals__row {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  padding: 8px 0;
  border-bottom: 1px dashed var(--color-border-light);
}

.pg-approvals__main {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  flex: 1;
  min-width: 0;
}

.pg-approvals__user {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  font-weight: var(--font-weight-medium);
}

.pg-approvals__msg {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 320px;
}

.pg-approvals__time {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  margin-left: auto;
}

/* 17x#2/#4：组设置 */
.pg-settings {
  max-width: 560px;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-4);
}

.pg-settings__section {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--spacing-2);
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  padding: var(--spacing-3);
}

.pg-settings__label {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  font-weight: var(--font-weight-medium);
}

.pg-settings__hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  line-height: 1.5;
}

.pg-settings__overrides {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-1);
}

/* 17x#2（V139）：功能开关 / 成员级可见性弹窗 */
.pg-kinds {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-2);
}

.pg-kinds__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.pg-kinds__warn {
  font-size: var(--font-size-xs);
  color: var(--color-warning, #f2c97d);
  line-height: 1.5;
}

.pg-vis {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-2);
}

.pg-vis__hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  line-height: 1.5;
}

.pg-vis__row {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
}

.pg-vis__kind {
  width: 48px;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

/* 17x-2026-08-25：产出「内容」列完整展示——限高+纵向滚动条，长文不截断 */
:deep(.pg-output-cell) {
  max-height: 96px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: var(--font-size-xs);
  line-height: 1.5;
}
</style>

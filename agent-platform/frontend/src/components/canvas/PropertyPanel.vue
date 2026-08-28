<template>
  <aside class="prop-panel" :style="{ width: `${panelWidth}px` }">
    <!-- 修复IV B4（C-6）：左缘拖拽条调宽 260-560，localStorage 持久化 -->
    <div
      class="prop-panel__gutter"
      role="separator"
      aria-orientation="vertical"
      aria-label="拖拽调整属性面板宽度"
      aria-valuemin="260"
      aria-valuemax="560"
      :aria-valuenow="panelWidth"
      @pointerdown="onGutterDown"
    ></div>
    <div class="prop-panel__title">属性</div>
    <div v-if="!node" class="prop-panel__empty">选中一个节点编辑其属性</div>
    <template v-else>
      <div class="prop-panel__field">
        <label>名称</label>
        <!-- L9 重命名查重：失焦时若与同画布其他节点撞名，自动追加序号（占位符存 id 不受影响） -->
        <n-input v-model:value="(node.data.label as string)" size="small" placeholder="节点名" @blur="onRenameBlur" />
      </div>

      <!-- 修复III C4（2x-4）：创建副本——同参数重新生成的起点，不带旧产物/旧任务引用 -->
      <div class="prop-panel__field">
        <n-button size="small" block @click="emit('clone-node', node)">创建副本</n-button>
      </div>

      <!-- D2（2x-8）：上游节点面板（BFS 分层；单击媒体 Lightbox 放大，双击卡片插入 @引用到本节点文本框）。
           修复IV B2（C-2）：depth>1 不再折叠，与直接上游同区直显（类型徽标配色区分），保留 ·N 层级号与 50 截断提示 -->
      <div v-if="upItems.length || upstreamTruncated" class="prop-panel__field">
        <label>上游（双击卡片插入 @引用）</label>
        <div
          v-for="u in upItems"
          :key="u.node.id"
          class="prop-panel__up-card"
          :class="{ 'prop-panel__up-card--far': u.depth > 1 }"
          title="双击把该节点插入提示词 @引用"
          @dblclick="onUpstreamDblClick(u)"
        >
          <button
            type="button"
            class="prop-panel__up-thumb"
            :class="{ 'is-clickable': !!upMediaSrc(u) }"
            :aria-label="upMediaSrc(u) ? `放大查看 ${String(u.node.data.label)}` : String(u.node.data.label)"
            @click="onUpstreamMediaClick(u)"
          >
            <img v-if="upThumbSrc(u)" :src="upThumbSrc(u)!" alt="" />
            <!-- 修复V A1（2x-1）：视频上游直出首帧——video preload=metadata 浏览器取首帧当封面
                 （coverPreviewUrl 仅导演台封面链路赋值，视频上游恒空——见 upThumbSrc 注释）。
                 P4 后续（用户反馈）：叠 ▶ 播放标——首帧易被当成静态图，缺「可点播」信号 -->
            <template v-else-if="u.node.type === 'video' && upMediaSrc(u)">
              <video
                class="prop-panel__up-video"
                :src="upMediaSrc(u)! + '#t=0.1'"
                preload="metadata"
                muted
                playsinline
              ></video>
              <span class="prop-panel__up-play" aria-hidden="true">▶</span>
            </template>
            <span v-else class="prop-panel__up-ph" :data-kind="u.node.type">{{ kindShort(u.node.type) }}</span>
          </button>
          <div class="prop-panel__up-meta">
            <div class="prop-panel__up-name">
              <span class="prop-panel__up-kind" :data-kind="u.node.type">{{ kindBadge(u.node.type) }}<template v-if="u.depth > 1">·{{ u.depth }}</template></span>
              <span class="prop-panel__up-label">{{ u.node.data.label }}</span>
            </div>
            <div class="prop-panel__up-prompt">{{ upPromptSnippet(u.node) || '（无文本）' }}</div>
          </div>
        </div>
        <div v-if="upstreamTruncated" class="prop-panel__hint">更上游超 50 节点，已截断</div>
      </div>
      <div v-else class="prop-panel__field">
        <label>上游</label>
        <div class="prop-panel__hint">无上游节点</div>
      </div>

      <!-- S12 资产库打通：入库 / 从库选择 / 已绑定徽标 + 检查更新（L5/L6，所有节点通用） -->
      <div class="prop-panel__field">
        <label>资产库</label>
        <div v-if="assetBound" class="prop-panel__asset-badge" :data-has-update="assetHasUpdate">
          来自资产 · {{ assetName }} v{{ assetVersion }}<template v-if="assetHasUpdate"> · 有新版</template>
        </div>
        <div class="prop-panel__row">
          <n-button size="tiny" tertiary @click="emit('save-to-asset', node)">存入资产库</n-button>
          <n-button size="tiny" tertiary @click="emit('pick-from-asset', node)">从库选择</n-button>
        </div>
        <div v-if="assetBound" class="prop-panel__row">
          <n-button size="tiny" tertiary @click="emit('check-update', node)">检查更新</n-button>
          <n-button
            size="tiny"
            tertiary
            type="primary"
            :disabled="!assetHasUpdate"
            @click="emit('update-asset', node)"
          >
            更新到最新版
          </n-button>
        </div>
      </div>

      <!-- 2x 四轮 S8：参考区（首帧/尾帧/图N/视频N 徽标缩略，悬浮放大、点击全屏/播放；随 prompt 增减 L7） -->
      <div v-if="references.length" class="prop-panel__field">
        <label>参考（提交时按此序号注入）</label>
        <div class="prop-panel__refs">
          <ReferencePreview
            v-for="r in references"
            :key="`${r.kind}:${r.label}:${r.fileId}`"
            :item="r"
            @open="onRefOpen"
          />
        </div>
      </div>

      <!-- 文本节点：提示词（S13 支持 @引用祖先节点产出） -->
      <template v-if="node.type === 'text'">
        <div class="prop-panel__field">
          <label>提示词</label>
          <MentionTextarea
            ref="primaryInputRef"
            :model-value="(node.data.prompt as string) || ''"
            :candidates="candidates"
            :broken-mentions="brokenMentions"
            :rows="4"
            placeholder="文本节点提示词；输入 @ 引用上游节点产出"
            @update:model-value="(v: string) => { if (node) node.data.prompt = v }"
            @blur-committed="emit('data-changed')"
            @mention-click="onMentionClick"
          />
          <div v-if="brokenMentions.length" class="prop-panel__warn">
            断链引用：{{ brokenMentions.join(' ') }}（上游被删/断连，运行前请重连或移除）
          </div>
        </div>
        <div class="prop-panel__field">
          <label>模型</label>
          <n-select
            :value="(node.data.model as string) || null"
            :options="chatModelOptions"
            size="small"
            clearable
            placeholder="默认（后端回落）"
            @update:value="(v: string | null) => { if (node) { node.data.model = v ?? undefined; emit('data-changed') } }"
          />
        </div>
        <n-button
          size="small"
          type="primary"
          block
          :loading="running"
          :disabled="!(node.data.prompt as string)?.trim()"
          @click="emit('run', node)"
        >
          <template #icon><n-icon :component="PlayOutline" /></template>
          生成文本
        </n-button>
        <div v-if="(node.data.outputText as string)" class="prop-panel__output">
          <label>生成结果</label>
          <div class="prop-panel__output-text">{{ node.data.outputText }}</div>
        </div>
      </template>

      <!-- 图片节点：上传 / AI 生图（Seedream lite+pro）/ 焦点编辑裁剪 -->
      <template v-else-if="node.type === 'image'">
        <n-upload
          :show-file-list="false"
          accept="image/*"
          @change="(opts) => onPickFile(opts)"
        >
          <n-button size="small" block :loading="running">
            <template #icon><n-icon :component="CloudUploadOutline" /></template>
            上传图片
          </n-button>
        </n-upload>
        <div class="prop-panel__field">
          <label>提示词</label>
          <MentionTextarea
            ref="primaryInputRef"
            :model-value="(node.data.prompt as string) || ''"
            :candidates="candidates"
            :broken-mentions="brokenMentions"
            :rows="3"
            :maxlength="PROMPT_MAX_LEN"
            placeholder="图片生成 prompt；输入 @ 引用上游图节点作参考图"
            @update:model-value="(v: string) => { if (node) node.data.prompt = v }"
            @blur-committed="emit('data-changed')"
            @mention-click="onMentionClick"
          />
          <!-- 修复VI（2x#4）：maxlength=平台后端 8000 硬拦；官方建议值仅提示（超长效果下降不报错） -->
          <div class="prop-panel__hint">官方建议 ≤300 汉字 / 600 英文词，超长效果下降</div>
          <div v-if="brokenMentions.length" class="prop-panel__warn">
            断链引用：{{ brokenMentions.join(' ') }}（上游被删/断连）
          </div>
        </div>
        <div class="prop-panel__field">
          <label>图片模型</label>
          <n-select
            :value="(node.data.model as string) || null"
            :options="imageModelOptions"
            size="small"
            clearable
            placeholder="选择生图模型（必选）"
            @update:value="onImageModelChange"
          />
        </div>

        <!-- 2x-3：按所选模型 capability 驱动的生成参数（与图片生成模块同源能力）；C4：+比例模式 -->
        <template v-if="imageCap">
          <div class="prop-panel__field">
            <label>尺寸</label>
            <n-select
              :value="(node.data.size as string) || null"
              :options="imgSizeOptions"
              size="small"
              clearable
              placeholder="模型默认"
              @update:value="onImageSizeChange"
            />
          </div>
          <div class="prop-panel__field">
            <label>比例（可选，与清晰度组合）</label>
            <n-select
              :value="(node.data.ratio as string) || null"
              :options="imgRatioOptions"
              size="small"
              clearable
              placeholder="不指定（按档位）"
              @update:value="(v: string | null) => { if (node) { node.data.ratio = v ?? undefined; emit('data-changed') } }"
            />
            <div v-if="imgRatioPreview" class="prop-panel__hint">{{ imgRatioPreview }}</div>
            <div v-else-if="imgRatioError" class="prop-panel__error">{{ imgRatioError }}</div>
          </div>
          <div v-if="(node.data.size as string) === '__custom__' && !(node.data.ratio as string)" class="prop-panel__field">
            <label>自定义宽x高</label>
            <n-input
              :value="(node.data.customSize as string) || ''"
              size="small"
              placeholder="如 2048x1152"
              @update:value="(v: string) => { if (node) { node.data.customSize = v; emit('data-changed') } }"
            />
          </div>
          <div class="prop-panel__row">
            <div class="prop-panel__field">
              <label>输出格式</label>
              <n-select
                :value="(node.data.outputFormat as string) || null"
                :options="toUpperOptions(imageCap.outputFormats)"
                size="small"
                @update:value="(v: string | null) => { if (node) { node.data.outputFormat = v ?? undefined; emit('data-changed') } }"
              />
            </div>
            <div class="prop-panel__field">
              <label>优化模式</label>
              <n-select
                :value="(node.data.optimizeMode as string) || null"
                :options="(imageCap.optimizeModes ?? []).map(v => ({ label: v, value: v }))"
                size="small"
                @update:value="(v: string | null) => { if (node) { node.data.optimizeMode = v ?? undefined; emit('data-changed') } }"
              />
            </div>
          </div>
          <div v-if="imageCap.supportsGuidanceScale" class="prop-panel__field">
            <label>引导尺度（{{ imageCap.guidanceMin }}-{{ imageCap.guidanceMax }}）</label>
            <n-input-number
              :value="(node.data.guidanceScale as number | undefined) ?? Math.round((imageCap.guidanceMin + imageCap.guidanceMax) / 2)"
              :min="imageCap.guidanceMin"
              :max="imageCap.guidanceMax"
              size="small"
              @update:value="(v: number | null) => { if (node) { node.data.guidanceScale = v ?? undefined; emit('data-changed') } }"
            />
          </div>
          <div v-if="imageCap.supportsSequential" class="prop-panel__row">
            <div class="prop-panel__field">
              <label>组图</label>
              <n-select
                :value="(node.data.sequential as string) || 'disabled'"
                :options="[{ label: '关闭', value: 'disabled' }, { label: '自动组图', value: 'auto' }]"
                size="small"
                @update:value="(v: string | null) => { if (node) { node.data.sequential = v ?? 'disabled'; emit('data-changed') } }"
              />
            </div>
            <div v-if="(node.data.sequential as string) === 'auto'" class="prop-panel__field">
              <label>数量</label>
              <n-input-number
                :value="(node.data.maxImages as number | undefined) ?? 4"
                :min="1"
                :max="imageCap.maxSequentialImages"
                size="small"
                @update:value="(v: number | null) => { if (node) { node.data.maxImages = v ?? undefined; emit('data-changed') } }"
              />
            </div>
          </div>
          <div class="prop-panel__row">
            <div class="prop-panel__field">
              <label>水印</label>
              <n-switch
                :value="(node.data.watermark as boolean | undefined) ?? imageCap.watermarkDefault"
                size="small"
                @update:value="(v: boolean) => { if (node) { node.data.watermark = v; emit('data-changed') } }"
              />
            </div>
            <div v-if="imageCap.supportsWebSearch" class="prop-panel__field">
              <label>联网搜索</label>
              <n-switch
                :value="Boolean(node.data.webSearch)"
                size="small"
                @update:value="(v: boolean) => { if (node) { node.data.webSearch = v; emit('data-changed') } }"
              />
            </div>
          </div>
        </template>
        <div v-else class="prop-panel__hint">选择模型后可设置尺寸/格式/水印等生成参数</div>
        <n-button
          size="small"
          type="primary"
          block
          :loading="running"
          :disabled="!(node.data.prompt as string)?.trim() || !(node.data.model as string)?.trim() || !!imgRatioError"
          @click="emit('run', node)"
        >
          <template #icon><n-icon :component="SparklesOutline" /></template>
          AI 生图
        </n-button>
        <!-- C2（17x-2/2x）：提交旁预估 chip「预估 X · 项目内剩余 Y」+ 不足红字（与生成页同口径） -->
        <div v-if="estimateText" class="prop-panel__estimate">
          <n-tag size="small" :type="estimate?.affordable ? 'info' : 'error'" :bordered="false">
            {{ estimateText }}
          </n-tag>
          <span v-if="estimateWarn" class="prop-panel__estimate-warn">{{ estimateWarn }}</span>
        </div>
        <n-button
          size="small"
          block
          tertiary
          :disabled="!node.data.fileId"
          @click="emit('focus-edit', node)"
        >
          <template #icon><n-icon :component="CropOutline" /></template>
          焦点编辑（框选裁剪）
        </n-button>
        <n-button
          size="small"
          block
          tertiary
          :disabled="!node.data.fileId"
          @click="emit('annotate', node)"
        >
          <template #icon><n-icon :component="BrushOutline" /></template>
          彩色标注（框选标改）
        </n-button>
        <!-- 2x 四轮 S6：确定性翻转/旋转（有源图才可变换；每次产新衍生图节点，原图不可变） -->
        <div v-if="node.data.fileId" class="prop-panel__transform">
          <button
            v-for="t in IMAGE_TRANSFORMS"
            :key="t.op"
            type="button"
            class="prop-panel__transform-btn"
            :title="t.title"
            :disabled="running"
            @click="emit('transform-image', { node, op: t.op })"
          >
            {{ t.label }}
          </button>
        </div>
        <div v-if="node.data.fileId" class="prop-panel__readonly">fileId: {{ node.data.fileId }}</div>
        <div v-if="node.data.taskId" class="prop-panel__readonly">taskId: {{ node.data.taskId }}</div>
        <div v-if="(node.data.errorMsg as string)" class="prop-panel__error">{{ node.data.errorMsg }}</div>
      </template>

      <!-- 视频节点：prompt/比例/时长/分辨率（S13 prompt 支持 @引用） -->
      <template v-else-if="node.type === 'video'">
        <div class="prop-panel__field">
          <label>提示词</label>
          <MentionTextarea
            ref="primaryInputRef"
            :model-value="(node.data.prompt as string) || ''"
            :candidates="candidates"
            :broken-mentions="brokenMentions"
            :rows="3"
            :maxlength="PROMPT_MAX_LEN"
            placeholder="视频生成 prompt；输入 @ 引用上游节点产出"
            @update:model-value="(v: string) => { if (node) node.data.prompt = v }"
            @blur-committed="emit('data-changed')"
            @mention-click="onMentionClick"
          />
          <!-- 修复VI（2x#4）：maxlength=平台后端 8000 硬拦；官方建议值仅提示（Seedance 超长只降质不报错） -->
          <div class="prop-panel__hint">官方建议 ≤500 汉字 / 1000 英文词，超长效果下降</div>
          <div v-if="brokenMentions.length" class="prop-panel__warn">
            断链引用：{{ brokenMentions.join(' ') }}（上游被删/断连，运行前请重连或移除）
          </div>
        </div>
        <!-- 修复III C3（2x-3）：比例独占整行（原与时长同挤 ~118px 太窄看不全；弹层 teleport 本就不受面板限） -->
        <div class="prop-panel__field">
          <label>比例</label>
          <!-- 修复IV C1c（C-4 缺口3）：v-model 直绑改显式写+data-changed，变更即落库（与离散选择器同模式） -->
          <!-- 修复VI VE（2x#6）：候选=所选模型 capability.supportedRatios（未选/失败回落保守兜底档） -->
          <n-select
            :value="(node.data.ratio as string) || null"
            size="small"
            :options="videoRatioOpts"
            @update:value="(v: string | null) => { if (node) { node.data.ratio = v ?? undefined; emit('data-changed') } }"
          />
        </div>
        <div class="prop-panel__field">
          <label>时长(秒)</label>
          <!-- 修复VI VE（2x#6）：min/max=所选模型能力区间（Seedance 2.5 可到 30s） -->
          <n-input-number
            :value="(node.data.duration as number | undefined) ?? null"
            size="small"
            :min="videoCapMinDuration"
            :max="videoCapMaxDuration"
            @update:value="(v: number | null) => { if (node) { node.data.duration = v ?? undefined; emit('data-changed') } }"
          />
        </div>
        <!-- 修复IV A5（C-5/2x-5）：分辨率独占整行（原与时长同挤一行被截断，不选中看不到完整档位） -->
        <div class="prop-panel__field">
          <label>分辨率</label>
          <!-- 修复IV C1c（C-4 缺口3）：显式写+data-changed 即落库；修复VI VE 候选=capability.supportedResolutions -->
          <n-select
            :value="(node.data.resolution as string) || null"
            size="small"
            :options="videoResOpts"
            @update:value="(v: string | null) => { if (node) { node.data.resolution = v ?? undefined; emit('data-changed') } }"
          />
        </div>
        <!-- 修复VI VE（2x#6）：生成音频/水印开关（与独立视频页同口径；切模型时按能力收敛） -->
        <div v-if="videoCap?.supportsGenerateAudio" class="prop-panel__field">
          <label>生成音频</label>
          <n-switch
            :value="(node.data.generateAudio as boolean | undefined) ?? false"
            size="small"
            @update:value="(v: boolean) => { if (node) { node.data.generateAudio = v; emit('data-changed') } }"
          />
        </div>
        <div class="prop-panel__field">
          <label>水印</label>
          <n-switch
            :value="(node.data.watermark as boolean | undefined) ?? false"
            size="small"
            @update:value="(v: boolean) => { if (node) { node.data.watermark = v; emit('data-changed') } }"
          />
        </div>
        <!-- 修复VI VE（2x#6）：首/尾帧仅对支持参考图的模型展示（maxImages=0 如 dashscope 文生视频） -->
        <div v-if="videoCapMaxImages > 0" class="prop-panel__field">
          <label>首帧（可选，@选上游图节点作开头）</label>
          <n-select
            :value="(node.data.firstFrameNodeId as string | null) ?? null"
            size="small"
            clearable
            :options="imageAncestorOptions"
            placeholder="不选 = 无首帧"
            @update:value="(v: string | null) => { if (node) { node.data.firstFrameNodeId = v ?? undefined; emit('data-changed') } }"
          />
        </div>
        <div v-if="videoCapMaxImages > 0" class="prop-panel__field">
          <label>尾帧（可选，@选上游图节点作结尾）</label>
          <n-select
            :value="(node.data.lastFrameNodeId as string | null) ?? null"
            size="small"
            clearable
            :options="imageAncestorOptions"
            placeholder="不选 = 无尾帧"
            @update:value="(v: string | null) => { if (node) { node.data.lastFrameNodeId = v ?? undefined; emit('data-changed') } }"
          />
        </div>
        <div class="prop-panel__hint">
          提示词里 @ 的图节点作<b>参考图</b>（图N），不算首/尾帧；首/尾帧在此显式选。
        </div>
        <n-button
          size="small"
          type="primary"
          block
          :loading="running"
          :disabled="!(node.data.prompt as string)?.trim()"
          @click="emit('run', node)"
        >
          <template #icon><n-icon :component="PlayOutline" /></template>
          提交视频生成
        </n-button>
        <!-- C2（17x-2/2x）：提交旁预估 chip（同图片节点数据源/口径） -->
        <div v-if="estimateText" class="prop-panel__estimate">
          <n-tag size="small" :type="estimate?.affordable ? 'info' : 'error'" :bordered="false">
            {{ estimateText }}
          </n-tag>
          <span v-if="estimateWarn" class="prop-panel__estimate-warn">{{ estimateWarn }}</span>
        </div>
        <div class="prop-panel__field">
          <label>视频模型</label>
          <n-select
            :value="(node.data.model as string) || null"
            :options="videoModelOptions"
            size="small"
            clearable
            placeholder="默认（provider 首个视频模型）"
            @update:value="onVideoModelChange"
          />
        </div>

        <!-- C11 视频抽帧：首/尾/指定秒 → 新图节点（需 video 已生成，即 data.fileId 存在） -->
        <div class="prop-panel__field">
          <label>抽帧（C11，需已生成视频）</label>
          <div class="prop-panel__row">
            <n-button
              size="small"
              tertiary
              :disabled="!node.data.fileId"
              @click="emit('extract-frame', { node, mode: 'FIRST' })"
            >
              首帧
            </n-button>
            <n-button
              size="small"
              tertiary
              :disabled="!node.data.fileId"
              @click="emit('extract-frame', { node, mode: 'LAST' })"
            >
              尾帧
            </n-button>
          </div>
          <div class="prop-panel__row">
            <n-input-number
              v-model:value="frameSecond"
              size="small"
              :min="0"
              placeholder="秒"
              style="flex:1"
            />
            <n-button
              size="small"
              tertiary
              :disabled="!node.data.fileId || frameSecond == null"
              @click="emit('extract-frame', { node, mode: 'AT', second: frameSecond ?? undefined })"
            >
              第{{ frameSecond ?? 'N' }}秒
            </n-button>
          </div>
        </div>

        <!-- C12 视频截取：时间段 [起,止) 裁剪 → 新视频节点（需 video 已生成） -->
        <div class="prop-panel__field">
          <label>截取（C12，时间段裁剪）</label>
          <div class="prop-panel__row">
            <n-input-number
              v-model:value="clipStart"
              size="small"
              :min="0"
              placeholder="起(秒)"
              style="flex:1"
            />
            <n-input-number
              v-model:value="clipEnd"
              size="small"
              :min="0"
              placeholder="止(秒)"
              style="flex:1"
            />
          </div>
          <n-button
            size="small"
            block
            tertiary
            :disabled="!node.data.fileId || clipStart == null || clipEnd == null || (clipEnd ?? 0) <= (clipStart ?? 0)"
            @click="emit('clip-video', { node, startSec: clipStart ?? 0, endSec: clipEnd ?? 0 })"
          >
            <template #icon><n-icon :component="CropOutline" /></template>
            截取片段
          </n-button>
        </div>

        <!-- 计划6 视频反推：抽帧+多模态 LLM 产 关键帧/分镜表/剧本 → CanvasView 建节点连边（需已生成视频） -->
        <div class="prop-panel__field">
          <label>反推（关键帧 / 分镜表 / 剧本）</label>
          <n-checkbox-group v-model:value="reverseModes">
            <n-space :size="12">
              <n-checkbox value="KEYFRAMES" label="关键帧" />
              <n-checkbox value="STORYBOARD" label="分镜表" />
              <n-checkbox value="SCRIPT" label="剧本" />
            </n-space>
          </n-checkbox-group>
          <div class="prop-panel__row" style="margin-top: 6px">
            <n-input-number
              v-model:value="reverseMaxFrames"
              size="small"
              :min="4"
              :max="24"
              placeholder="帧数(4-24)"
              style="flex: 1"
            />
            <n-input-number
              v-model:value="reverseThreshold"
              size="small"
              :min="0.1"
              :max="0.9"
              :step="0.05"
              placeholder="阈值0.1-0.9"
              style="flex: 1"
            />
          </div>
          <!-- 4x：反推可选大模型（null=管理员默认对话模型；存 node.data.reverseModel 随快照持久化） -->
          <n-select
            :value="(node.data.reverseModel as string) ?? null"
            size="small"
            clearable
            filterable
            placeholder="反推大模型：默认（管理员默认对话模型）"
            :options="chatModelOptions"
            style="margin-top: 6px"
            @update:value="(v: string | null) => { setReverseModel(v); emit('data-changed') }"
          />
          <div class="prop-panel__hint">
            帧数默认 12 上限 24；阈值默认 0.3（调低更易识别切镜）。仅勾「关键帧」不调大模型；
            分镜/剧本按帧计费（≤{{ reverseMaxFrames ?? 12 }} 帧多模态 token，建议选带视觉的模型）。
          </div>
          <div class="prop-panel__row">
            <n-button
              size="small"
              type="primary"
              block
              :loading="reversing"
              :disabled="!node.data.fileId || reverseModes.length === 0"
              @click="emit('reverse-analyze', {
                node,
                modes: [...reverseModes],
                maxFrames: reverseMaxFrames ?? undefined,
                sceneThreshold: reverseThreshold ?? undefined,
                model: (node.data.reverseModel as string) ?? undefined
              })"
            >
              开始反推
            </n-button>
            <n-button v-if="reversing" size="small" tertiary type="warning" @click="emit('reverse-cancel')">
              取消
            </n-button>
          </div>
        </div>
        <div v-if="(node.data.errorMsg as string)" class="prop-panel__error">{{ node.data.errorMsg }}</div>
        <div v-if="node.data.taskId" class="prop-panel__readonly">
          taskId: {{ node.data.taskId }}
          <!-- 7x-4：参考视频标志（供审查定价是否按「有参考」命中） -->
          <n-tag v-if="node.data.hasReference === true" size="tiny" type="info" :bordered="false" style="margin-left: 8px">有参考视频</n-tag>
          <n-tag v-else-if="node.data.hasReference === false" size="tiny" :bordered="false" style="margin-left: 8px">无参考</n-tag>
        </div>
        <!-- 7x-4：查看实际推送参数（submittedRequest + providerRequestSnapshot），供审查。
             组件自带触发按钮 + modal，内部自管 show 状态。 -->
        <MediaTaskRequestDetails
          v-if="node.data.taskId"
          :submitted-request="(node.data.submittedRequest as Record<string, unknown> | null) ?? null"
          :provider-request-snapshot="(node.data.providerRequestSnapshot as Record<string, unknown> | null) ?? null"
        />
      </template>

      <!-- 音频节点：上传（MVP）/ TTS / 音乐生成（待 provider） -->
      <template v-else-if="node.type === 'audio'">
        <div class="prop-panel__field">
          <label>来源</label>
          <!-- 修复IV C1c（C-4 缺口3）：显式写+data-changed 即落库 -->
          <n-select
            :value="(node.data.audioMode as string) || null"
            size="small"
            :options="audioModeOpts"
            @update:value="(v: string | null) => { if (node) { node.data.audioMode = v ?? undefined; emit('data-changed') } }"
          />
        </div>
        <n-upload
          v-if="(node.data.audioMode ?? 'upload') === 'upload'"
          :show-file-list="false"
          accept="audio/*"
          @change="(opts) => onPickFile(opts)"
        >
          <n-button size="small" block :loading="running">
            <template #icon><n-icon :component="CloudUploadOutline" /></template>
            上传音频
          </n-button>
        </n-upload>
        <n-button v-else size="small" block tertiary disabled title="TTS/音乐生成 provider 待接入">
          <template #icon><n-icon :component="SparklesOutline" /></template>
          {{ node.data.audioMode === 'tts' ? 'TTS 语音（待接入）' : '音乐生成（待接入）' }}
        </n-button>
        <div v-if="node.data.fileId" class="prop-panel__readonly">fileId: {{ node.data.fileId }}</div>
      </template>

      <!-- 分镜节点：脚本拆分镜后扇出生成，单条分镜画面描述（可编辑微调后下游生图/视频） -->
      <template v-else-if="node.type === 'storyboard'">
        <div v-if="(node.data.index as number)" class="prop-panel__readonly">分镜 {{ node.data.index }}</div>
        <div class="prop-panel__field">
          <label>分镜描述</label>
          <MentionTextarea
            ref="primaryInputRef"
            :model-value="(node.data.description as string) || ''"
            :candidates="candidates"
            :broken-mentions="brokenMentions"
            :rows="5"
            placeholder="分镜画面描述；下游图/视频节点 @本节点即注入此描述"
            @update:model-value="(v: string) => { if (node) node.data.description = v }"
            @blur-committed="emit('data-changed')"
            @mention-click="onMentionClick"
          />
          <div v-if="brokenMentions.length" class="prop-panel__warn">
            断链引用：{{ brokenMentions.join(' ') }}（上游被删/断连）
          </div>
        </div>
        <!-- 计划6 本土化转绘（分镜描述文本作为改写输入；产出新 script 节点连自本节点） -->
        <n-button
          size="small"
          block
          tertiary
          :disabled="!(node.data.description as string)?.trim()"
          @click="openLocalize(node)"
        >
          本土化转绘
        </n-button>
      </template>

      <!-- 脚本节点：剧本 → LLM 拆分镜（S13 剧本支持 @引用上游产出） -->
      <template v-else-if="node.type === 'script'">
        <div class="prop-panel__field">
          <label>剧本</label>
          <MentionTextarea
            ref="primaryInputRef"
            :model-value="(node.data.synopsis as string) || ''"
            :candidates="candidates"
            :broken-mentions="brokenMentions"
            :rows="5"
            placeholder="剧本输入；输入 @ 引用上游节点产出，经 LlmGateway 拆分镜"
            @update:model-value="(v: string) => { if (node) node.data.synopsis = v }"
            @blur-committed="emit('data-changed')"
            @mention-click="onMentionClick"
          />
          <div v-if="brokenMentions.length" class="prop-panel__warn">
            断链引用：{{ brokenMentions.join(' ') }}（上游被删/断连，运行前请重连或移除）
          </div>
        </div>
        <div class="prop-panel__field">
          <label>拆分段数 <span class="prop-panel__inline-hint">留空由大模型自定</span></label>
          <n-input-number
            :value="(node.data.segmentCount as number | null) ?? null"
            :min="1"
            :max="20"
            size="small"
            placeholder="如 6（留空=大模型自定）"
            clearable
            @update:value="(v: number | null) => { if (node) { node.data.segmentCount = v ?? undefined; emit('data-changed') } }"
          />
        </div>
        <div class="prop-panel__field">
          <label>拆分规范 <span class="prop-panel__inline-hint">留空则大模型自由发挥</span></label>
          <MentionTextarea
            :model-value="(node.data.storyboardSpec as string) || ''"
            :candidates="candidates"
            :broken-mentions="brokenMentions"
            :rows="2"
            placeholder="如：每镜含景别+运镜；留空则大模型自由发挥"
            @update:model-value="(v: string) => { if (node) node.data.storyboardSpec = v }"
            @blur-committed="emit('data-changed')"
            @mention-click="onMentionClick"
          />
        </div>
        <div class="prop-panel__field">
          <label>模型</label>
          <n-select
            :value="(node.data.model as string) || null"
            :options="chatModelOptions"
            size="small"
            clearable
            placeholder="默认（后端回落）"
            @update:value="(v: string | null) => { if (node) { node.data.model = v ?? undefined; emit('data-changed') } }"
          />
        </div>
        <n-button
          size="small"
          type="primary"
          block
          :loading="running"
          :disabled="!(node.data.synopsis as string)?.trim()"
          @click="emit('split-storyboard', node)"
        >
          <template #icon><n-icon :component="PlayOutline" /></template>
          拆分镜
        </n-button>
        <!-- 计划6 本土化转绘：剧本→目标文化版本（剧情/分镜结构不变），产出新 script 节点 -->
        <n-button
          size="small"
          block
          tertiary
          :disabled="!(node.data.synopsis as string)?.trim()"
          style="margin-top: 6px"
          @click="openLocalize(node)"
        >
          本土化转绘
        </n-button>
        <!-- 计划6 本土化产物核对：转绘生成的节点 data 带 changeLog/localizeWarning，逐条可核（2x「逐条核对」） -->
        <div v-if="localizeChanges.length" class="prop-panel__field" style="margin-top: 6px">
          <label>替换清单（changeLog，{{ localizeChanges.length }} 处）</label>
          <div
            v-for="(c, i) in localizeChanges"
            :key="i"
            class="prop-panel__readonly"
            style="margin-bottom: 2px"
          >
            {{ c.from }} → {{ c.to }}{{ c.scene ? `（${c.scene}）` : '' }}
          </div>
        </div>
        <div v-if="(node.data.localizeWarning as string)" class="prop-panel__warn">
          {{ node.data.localizeWarning }}
        </div>
        <div v-if="(node.data.errorMsg as string)" class="prop-panel__error">{{ node.data.errorMsg }}</div>
        <div v-if="sceneCount" class="prop-panel__readonly">已拆 {{ sceneCount }} 分镜</div>
      </template>
    </template>

    <!-- 2x 四轮 S8：参考缩略点击 → 图片 MediaLightbox 全屏 / 视频播放弹窗（blob objectURL） -->
    <MediaLightbox :src="lightboxSrc" :alt="lightboxAlt" @close="lightboxSrc = null" />
    <!-- D1（2x-8）：上游媒体放大（图片可缩放拖拽 / 视频播放） -->
    <Lightbox
      :open="!!upView"
      :kind="upView?.kind ?? 'image'"
      :src="upView?.src"
      :alt="upView?.alt"
      @close="upView = null"
    />
    <n-modal
      v-model:show="playOpen"
      preset="card"
      :title="playVideo ? `播放 · ${playVideo.label}` : '播放'"
      style="max-width: 720px"
      @after-leave="playVideo = null"
    >
      <video v-if="playVideo" :src="playVideo.url" controls autoplay style="width: 100%; display: block" />
    </n-modal>

    <!-- 计划6 本土化转绘表单弹窗：目标地区+保留要求 → emit localize-script（CanvasView 调接口建新 script 节点） -->
    <n-modal v-model:show="localizeOpen" preset="card" title="本土化转绘" style="max-width: 480px">
      <div class="prop-panel__field">
        <label>目标国家/地区</label>
        <n-input v-model:value="localizeLocale" size="small" placeholder="如：美国 / 西方 / 日本" />
      </div>
      <div class="prop-panel__field">
        <label>额外保留要求（可选）</label>
        <n-input
          v-model:value="localizeNotes"
          type="textarea"
          :rows="2"
          size="small"
          placeholder="如：保留春节团圆情节"
        />
      </div>
      <div class="prop-panel__field">
        <label>转绘大模型（可选）</label>
        <n-select
          v-model:value="localizeModel"
          size="small"
          clearable
          filterable
          placeholder="默认（管理员默认对话模型）"
          :options="chatModelOptions"
        />
      </div>
      <div class="prop-panel__hint">
        剧情、分镜数与顺序不变；只替换文化元素（餐具/服饰/建筑/招牌/节庆等）。改写产新剧本节点，附替换清单（changeLog）可核对。
      </div>
      <template #footer>
        <n-button size="small" type="primary" :disabled="!localizeLocale.trim()" @click="confirmLocalize">
          开始转绘
        </n-button>
      </template>
    </n-modal>
  </aside>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  NButton, NCheckbox, NCheckboxGroup, NIcon, NInput, NInputNumber, NModal, NSelect, NSpace, NSwitch, NTag, NUpload
} from 'naive-ui'
import {
  BrushOutline, CloudUploadOutline, CropOutline, PlayOutline, SparklesOutline
} from '@vicons/ionicons5'
import type { CanvasNode, MentionCandidate } from '@/types/canvas'
import type { FrameMode, ImageTransformOp } from '@/api/canvas'
import type { ReverseMode } from '@/api/media'
import { llmApi } from '@/api/llm'
import type { AvailableModel } from '@/api/llm'
import { mediaApi } from '@/api/media'
import type {
  ImageModelCapability, ImageModelVO, MediaEstimateVO, MediaModelVO, MediaRatio, MediaResolution
} from '@/api/media'
import MentionTextarea from './MentionTextarea.vue'
import Lightbox from './Lightbox.vue'
import type { UpstreamItem } from './upstream'
import MediaTaskRequestDetails from '../media/MediaTaskRequestDetails.vue'
import MediaLightbox from '../media/MediaLightbox.vue'
import ReferencePreview from './ReferencePreview.vue'
import type { CanvasReferenceItem } from '@/utils/canvasVideoAttachments'
import { uniqueLabel } from '@/utils/interpolate'
import { RATIOS, deriveWh } from '@/utils/imageSize'

/** 2x 四轮 S6：五种确定性变换按钮（label/title 面板展示，op 直传后端白名单枚举）。 */
const IMAGE_TRANSFORMS: ReadonlyArray<{ op: ImageTransformOp; label: string; title: string }> = [
  { op: 'FLIP_H', label: '↔ 翻转', title: '水平翻转：左右镜像，产新图节点' },
  { op: 'FLIP_V', label: '↕ 翻转', title: '垂直翻转：上下镜像，产新图节点' },
  { op: 'ROTATE_90', label: '⟳ 90°', title: '顺时针旋转 90°，产新图节点' },
  { op: 'ROTATE_180', label: '⟳ 180°', title: '旋转 180°，产新图节点' },
  { op: 'ROTATE_270', label: '⟲ 270°', title: '逆时针旋转 90°（270°），产新图节点' }
]

const props = withDefaults(defineProps<{
  /** 选中节点（数组中的真实引用，直编 data 即时反映到画布）。 */
  node: CanvasNode | null
  /** 该节点是否运行中（按钮 loading + 防重入）。 */
  running?: boolean
  /** 计划6：视频反推进行中（反推按钮 loading + 取消按钮显隐；与 running 分开——抽帧+LLM 长任务）。 */
  reversing?: boolean
  /** S13：@选择器候选（当前节点的祖先节点集；无连线可达则空）。 */
  candidates?: MentionCandidate[]
  /** S13：当前节点文本中的断链占位符（上游被删/断连），用于灰显提示 L7/L8。 */
  brokenMentions?: string[]
  /** S13：同画布全部节点 label（重命名查重用，L9 三入口之一）。 */
  allLabels?: string[]
  /** F3：祖先图节点选项（首/尾帧@选择器用，{label,value=nodeId}）。 */
  imageAncestorOptions?: { label: string; value: string }[]
  /** 2x 四轮 S8：参考媒体预览列表（首尾帧/图N/视频N 徽标缩略；CanvasView 同源解析装配）。 */
  references?: CanvasReferenceItem[]
  /** C2（17x-2/2x）：参与项目组 id（组池计费口径；null=个人钱包）。 */
  projectGroupId?: number | null
  /** D2（2x-8）：上游节点收集结果（CanvasView computed 供给；null=未选节点）。 */
  upstream?: { items: UpstreamItem[]; truncated: boolean } | null
}>(), {
  candidates: () => [],
  brokenMentions: () => [],
  allLabels: () => [],
  imageAncestorOptions: () => [],
  references: () => [],
  projectGroupId: null,
  upstream: null
})

const emit = defineEmits<{
  (e: 'run', node: CanvasNode): void
  /** 脚本节点拆分镜专用：后端拆 scenes → 前端扇出生成分镜子节点（替代脚本走通用 run）。 */
  (e: 'split-storyboard', node: CanvasNode): void
  (e: 'upload', payload: { node: CanvasNode; file: File }): void
  (e: 'focus-edit', node: CanvasNode): void
  /** 2x 四轮 S7：彩色标注弹层入口（框选标改 → 标注图 / AI 修改）。 */
  (e: 'annotate', node: CanvasNode): void
  /** 2x 四轮 S6：确定性翻转/旋转（后端 transform-image → 衍生图节点）。 */
  (e: 'transform-image', payload: { node: CanvasNode; op: ImageTransformOp }): void
  (e: 'extract-frame', payload: { node: CanvasNode; mode: FrameMode; second?: number }): void
  (e: 'clip-video', payload: { node: CanvasNode; startSec: number; endSec: number }): void
  /** 计划6 视频反推：modes 组合+可选帧数/阈值 → analyze（CanvasView 建节点连边；modes 空时按钮禁用）。 */
  (e: 'reverse-analyze', payload: {
    node: CanvasNode
    modes: ReverseMode[]
    maxFrames?: number
    sceneThreshold?: number
    /** 4x：指定反推用对话大模型（空=管理员默认）。 */
    model?: string
  }): void
  /** 计划6：取消进行中的反推（AbortController；已落库帧文件保留，节点不产生）。 */
  (e: 'reverse-cancel'): void
  /** 计划6 本土化转绘：script/storyboard 节点文本 → localize → 新 script 节点（changeLog 存 data）。 */
  (e: 'localize-script', payload: { node: CanvasNode; targetLocale: string; notes?: string; model?: string }): void
  /** S12：存入资产库（开 SaveToAssetDialog，L5）。 */
  (e: 'save-to-asset', node: CanvasNode): void
  /** S12：从库选择（开 AssetPicker，L6）。 */
  (e: 'pick-from-asset', node: CanvasNode): void
  /** S12：检查资产是否有新版（asset.get 比对版本）。 */
  (e: 'check-update', node: CanvasNode): void
  /** S12：更新节点引用到资产最新版（re-resolve 写回，L6「手动更新」）。 */
  (e: 'update-asset', node: CanvasNode): void
  /** C5/FR-006：node.data 被面板改动需落库（模型选择器等离散选择），父组件 scheduleSave。 */
  (e: 'data-changed'): void
  /** A1 增强：提示词里 @chip 被点击 → 跳转聚焦被引用节点（CanvasView 居中选中该节点）。 */
  (e: 'mention-focus', payload: { kind: string; id: string }): void
  /** 修复III C4（2x-4）：创建副本（CanvasView cloneNodeForDuplicate → addNode +40/+40）。 */
  (e: 'clone-node', node: CanvasNode): void
}>()

/** S12：当前节点已绑定资产（node.data.assetId 存在）。 */
const assetBound = computed(() => props.node?.data.assetId != null)
const assetName = computed(() => (props.node?.data.assetName as string | undefined) ?? '资产')
const assetVersion = computed(() => (props.node?.data.assetVersion as number | undefined) ?? 1)
const assetHasUpdate = computed(() => Boolean(props.node?.data.assetHasUpdate))

// ---------- D2（2x-8）：上游节点面板（直接上游大卡 + 更上游折叠小卡） ----------

/** 各节点型的「主文本框」（双击上游卡 @引用插入目标；各分支唯一渲染，同名 ref 安全）。 */
const primaryInputRef = ref<InstanceType<typeof MentionTextarea> | null>(null)

/** 上游列表（upstream.ts BFS 已按 depth 升序）。修复IV B2（C-2）：depth>1 不再折叠，同区直显。 */
const upItems = computed<UpstreamItem[]>(() => props.upstream?.items ?? [])
const upstreamTruncated = computed(() => props.upstream?.truncated ?? false)

const KIND_BADGE: Record<string, string> = {
  text: '文本', image: '图片', video: '视频', audio: '音频',
  script: '脚本', storyboard: '分镜', director: '导演'
}
function kindBadge(t: string): string {
  return KIND_BADGE[t] ?? t
}
/** 无缩略图占位（一个字）。 */
function kindShort(t: string): string {
  return (KIND_BADGE[t] ?? '节').slice(0, 1)
}

/** 缩略图：图片→previewUrl，视频→封面 coverPreviewUrl（仅导演台赋值，生成链恒 null——
 * 修复V A1：视频上游改模板里 video 标签直出首帧，本函数视频分支只服务导演台封面场景）；无媒体返 null。 */
function upThumbSrc(u: UpstreamItem): string | null {
  const d = u.node.data
  if (u.node.type === 'image') return (d.previewUrl as string | undefined) ?? null
  if (u.node.type === 'video') return (d.coverPreviewUrl as string | undefined) ?? null
  return null
}

/** 可放大的媒体源（单击开 Lightbox）：图片→图 URL，视频→video objectURL；无则 null。 */
function upMediaSrc(u: UpstreamItem): string | null {
  const d = u.node.data
  if (u.node.type === 'image') return (d.previewUrl as string | undefined) ?? null
  if (u.node.type === 'video') return (d.previewUrl as string | undefined) ?? null
  return null
}

/** 提示词两行摘要（@token 原样显示——上游节点引用即其产出入口，提示来源比纯文本更可读）。 */
function upPromptSnippet(n: CanvasNode): string {
  const d = n.data as Record<string, unknown>
  const raw = String(d.prompt ?? d.description ?? d.synopsis ?? d.outputText ?? '')
  return raw.replace(/\s+/g, ' ').trim().slice(0, 80)
}

/** 上游媒体单击 → Lightbox 放大（图片缩放 / 视频播放）。 */
const upView = ref<{ kind: 'image' | 'video'; src: string; alt: string } | null>(null)
function onUpstreamMediaClick(u: UpstreamItem) {
  const src = upMediaSrc(u)
  if (!src) return
  upView.value = { kind: u.node.type === 'video' ? 'video' : 'image', src, alt: String(u.node.data.label) }
}

/** 双击上游卡 → @引用追加到本节点主文本框末尾（音频等无文本框节点静默忽略）。 */
function onUpstreamDblClick(u: UpstreamItem) {
  primaryInputRef.value?.appendMention({ kind: 'node', id: u.node.id })
}

// ---------- 修复IV B4（C-6）：面板宽度拖拽 260-560，localStorage 持久化 ----------
const PROP_PANEL_WIDTH_KEY = 'canvas.propPanel.width'
const PANEL_MIN_WIDTH = 260
const PANEL_MAX_WIDTH = 560

/** 存储值非法/越界回落 260（NaN、0、9999 等一律钳到区间内）。 */
function clampPanelWidth(w: number): number {
  return Number.isFinite(w) ? Math.min(PANEL_MAX_WIDTH, Math.max(PANEL_MIN_WIDTH, Math.round(w))) : 260
}

const panelWidth = ref(clampPanelWidth(parseInt(localStorage.getItem(PROP_PANEL_WIDTH_KEY) ?? '', 10)))

let gutterDrag: { startX: number; startWidth: number } | null = null

function onGutterDown(e: PointerEvent) {
  e.stopPropagation() // 不下传画布（防拖宽起点被解读为画布手势）
  gutterDrag = { startX: e.clientX, startWidth: panelWidth.value }
  document.body.style.userSelect = 'none' // 拖拽期间不选中文本
  window.addEventListener('pointermove', onGutterMove)
  window.addEventListener('pointerup', onGutterUp, { once: true })
}

function onGutterMove(e: PointerEvent) {
  if (!gutterDrag) return
  // 面板贴右侧，拖左缘向左（dx 负）= 变宽
  panelWidth.value = clampPanelWidth(gutterDrag.startWidth - (e.clientX - gutterDrag.startX))
}

function onGutterUp() {
  gutterDrag = null
  document.body.style.userSelect = ''
  window.removeEventListener('pointermove', onGutterMove)
  localStorage.setItem(PROP_PANEL_WIDTH_KEY, String(panelWidth.value))
}

// 卸载兜底：拖拽中卸载不残留全局监听/禁选样式
onBeforeUnmount(() => {
  window.removeEventListener('pointermove', onGutterMove)
  document.body.style.userSelect = ''
})

/** C11 抽帧「指定秒」输入值（AT 模式用）。 */
const frameSecond = ref<number | null>(null)

/** C12 截取起止秒输入值。 */
const clipStart = ref<number | null>(null)
const clipEnd = ref<number | null>(null)

// ---------- C2（17x-2/2x）：画布提交旁预估条（只读；500ms 防抖询 /media/estimate） ----------
const estimate = ref<MediaEstimateVO | null>(null)
let estimateTimer: ReturnType<typeof setTimeout> | null = null

/** 参数指纹（模型/时长/分辨率/组图/参考数/项目组任一变 → 重询）。 */
const estimateFingerprint = computed(() => {
  const d = (props.node?.data ?? {}) as Record<string, unknown>
  return [
    props.node?.id, props.node?.type, d.model, d.duration, d.resolution,
    d.sequential, d.maxImages, props.references?.length, props.projectGroupId
  ].join('|')
})

watch(estimateFingerprint, () => {
  if (estimateTimer) clearTimeout(estimateTimer)
  estimateTimer = setTimeout(() => void loadEstimate(), 500)
}, { immediate: true })

async function loadEstimate() {
  const node = props.node
  if (!node || (node.type !== 'image' && node.type !== 'video')) {
    estimate.value = null
    return
  }
  const d = node.data as Record<string, unknown>
  // 图片必选模型；视频可回退 provider 默认
  if (node.type === 'image' && !d.model) {
    estimate.value = null
    return
  }
  try {
    const { data } = await mediaApi.estimatePreview(node.type === 'image'
      ? {
          kind: 'IMAGE',
          model: d.model as string,
          imageCount: d.sequential === 'auto' ? ((d.maxImages as number) || 1) : 1,
          projectGroupId: props.projectGroupId ?? undefined
        }
      : {
          kind: 'VIDEO',
          model: (d.model as string) || undefined,
          videoSeconds: Number(d.duration ?? 5),
          resolution: (d.resolution as string) || '720p',
          hasReference: (props.references?.length ?? 0) > 0,
          projectGroupId: props.projectGroupId ?? undefined
        })
    estimate.value = data.data
  } catch {
    estimate.value = null
  }
}

onBeforeUnmount(() => {
  if (estimateTimer) clearTimeout(estimateTimer)
})

/** chip 文案：组内优先点名「项目内剩余」（个人限额卡口径），不限额成员/个人看池/钱包。 */
const estimateText = computed<string | null>(() => {
  const e = estimate.value
  if (!e || e.estimatedPoints <= 0) return null
  const scope = e.personalScope
  const tail = scope
    ? (scope.inProjectAvailable != null
        ? `项目内剩余 ${scope.inProjectAvailable}`
        : `项目组池余 ${e.balance}`)
    : `钱包余 ${e.balance}`
  return `预估 ${e.estimatedPoints} · ${tail}（预估与比例无关）`
})

/** 不足红字：与生成页同分层（欠款冻结 / 个人限额卡 / 组池卡 / 钱包）。
 *  V161（修复III B）：DEBT 卡点最高优先——欠款未抵扣先说冻结并指路划拨。 */
const estimateWarn = computed<string | null>(() => {
  const e = estimate.value
  if (!e || e.affordable) return null
  const scope = e.personalScope
  if (scope && scope.bindingConstraint === 'DEBT') {
    return `欠款 ${scope.debtTotalPoints} 分未抵扣，暂停组内消费——去「项目组」划拨还款`
  }
  if (scope && !scope.affordableMember) {
    const quotaTxt = scope.quota != null ? `（限额 ${scope.quota}−已用 ${scope.used}）` : ''
    return `项目内剩余 ${scope.inProjectAvailable ?? 0} 不足${quotaTxt}`
  }
  if (e.balance < e.estimatedPoints) {
    return scope ? `项目组池剩余 ${e.balance} 不足` : `钱包余额不足（余 ${e.balance}）`
  }
  return null
})

// ---------- 计划6 视频反推 / 本土化转绘 ----------

/** 反推产物勾选（默认只取关键帧——零 LLM 成本；分镜/剧本勾上才调多模态）。 */
const reverseModes = ref<ReverseMode[]>(['KEYFRAMES'])
/** 帧数（4-24，空=后端默认 12）与场景阈值（0.1-0.9，空=后端默认 0.3）。 */
const reverseMaxFrames = ref<number | null>(null)
const reverseThreshold = ref<number | null>(null)
/** 4x：反推模型写 node.data.reverseModel（随快照持久化，重进画布保持）。 */
function setReverseModel(model: string | null) {
  if (!props.node) return
  ;(props.node.data as Record<string, unknown>).reverseModel = model ?? undefined
}

/** 转绘弹窗状态 + 发起节点（script 取 synopsis / storyboard 取 description 作输入）。 */
const localizeOpen = ref(false)
const localizeLocale = ref('')
const localizeNotes = ref('')
/** 4x：转绘大模型（弹窗一次性选择，默认空=管理员默认对话模型）。 */
const localizeModel = ref<string | null>(null)
const localizeSourceNode = ref<CanvasNode | null>(null)

function openLocalize(node: CanvasNode) {
  localizeSourceNode.value = node
  localizeLocale.value = ''
  localizeNotes.value = ''
  localizeModel.value = null
  localizeOpen.value = true
}

function confirmLocalize() {
  if (!localizeSourceNode.value || !localizeLocale.value.trim()) return
  emit('localize-script', {
    node: localizeSourceNode.value,
    targetLocale: localizeLocale.value.trim(),
    notes: localizeNotes.value.trim() || undefined,
    model: localizeModel.value ?? undefined
  })
  localizeOpen.value = false
}

/** 本土化产物节点的替换清单（仅转绘生成的 script 节点 data 有 changeLog；普通节点空列表不渲染；from/to 全缺的行跳过防空行）。 */
const localizeChanges = computed(() => {
  const log = props.node?.data?.changeLog
  if (!Array.isArray(log)) return []
  return log.filter((c): c is { from?: string; to?: string; scene?: string } =>
    !!c && !!(c.from || c.to))
})

// ---------- 2x 四轮 S8：参考缩略全屏/播放（图片 MediaLightbox / 视频 blob 播放弹窗） ----------
const lightboxSrc = ref<string | null>(null)
const lightboxAlt = ref('')
/** 播放中的视频（label + objectURL；null=弹窗关）。 */
const playVideo = ref<{ label: string; url: string } | null>(null)
const playOpen = computed({
  get: () => playVideo.value != null,
  set: (v: boolean) => { if (!v) playVideo.value = null }
})

/** 参考缩略点击：图片开全屏灯箱；视频开播放弹窗（objectURL 未就绪时忽略——懒加载未触发场景）。 */
function onRefOpen(payload: { item: CanvasReferenceItem; url: string | null }) {
  if (!payload.url) return
  if (payload.item.kind === 'image') {
    lightboxAlt.value = payload.item.label
    lightboxSrc.value = payload.url
  } else if (payload.item.kind === 'audio') {
    // 修复VI VE（2x#6）：音频已在缩略卡内嵌 audio 条自播，卡片点击不再开视频弹窗
  } else {
    playVideo.value = { label: payload.item.label, url: payload.url }
  }
}

/** n-upload 文件选中回调：取真实 File 抛给父组件上传（不走 n-upload 默认 XHR）。 */
function onPickFile(opts: { file?: { file?: File | null } } | undefined) {
  const file = opts?.file?.file
  if (file && props.node) {
    emit('upload', { node: props.node, file })
  }
}

/**
 * L9 重命名查重：失焦时若新 label 与同画布其他节点撞名，自动追加序号。
 * 契约：父组件传入的 allLabels **已剔除当前节点**（按节点 id 剔除，非按值——
 * 否则另一节点的同名也会被误剔导致查重漏判）。
 */
function onRenameBlur() {
  const node = props.node
  if (!node) return
  const label = (node.data.label as string | undefined)?.trim()
  if (!label) return
  const deduped = uniqueLabel(label, props.allLabels)
  if (deduped !== node.data.label) {
    node.data.label = deduped
  }
  // 修复IV C1b（C-4 缺口2）：名称框失焦即报存（查重改写与否都报——防抖层去重）
  emit('data-changed')
}

/** A1：提示词 @chip 被点击 → 上抛 mention-focus，CanvasView 居中选中被引用节点。 */
function onMentionClick(payload: { kind: string; id: string }) {
  emit('mention-focus', payload)
}

/** 脚本节点已拆分镜数（属性面板回显）。 */
const sceneCount = computed(() =>
  Array.isArray(props.node?.data.scenes) ? (props.node!.data.scenes as unknown[]).length : 0
)

/** 修复VI（2x#4）：提示词长度上限=平台后端 PROMPT_MAX_LEN（>8000 提交即 400）；官方建议值仅文案提示 */
const PROMPT_MAX_LEN = 8000
const audioModeOpts = [
  { label: '上传', value: 'upload' },
  { label: 'TTS 语音', value: 'tts' },
  { label: '音乐生成', value: 'music' }
]

// ---------- C5 节点选模型（text/script=chat 模型；video=MEDIA 视频模型；image=生图模型） ----------
const chatModels = ref<AvailableModel[]>([])
const videoModels = ref<MediaModelVO[]>([])
const imageModels = ref<ImageModelVO[]>([])
onMounted(async () => {
  try {
    const [c, v] = await Promise.all([llmApi.listAvailableModels(), mediaApi.listModels()])
    chatModels.value = c.data.data ?? []
    // 修复VI VE（2x#6）：视频模型改 /media/models（MediaModelVO 带 capability，与独立视频页同源；
    // llmApi.listVideoModels 无能力画像，参数只能硬编码——本 chunk 换源）
    videoModels.value = v.data.data ?? []
  } catch {
    // 模型列表可选，失败静默（下拉空态不崩；视频参数回落下方保守兜底档，不白屏）
  }
  // 生图模型独立取：图片 API 与 chat/video 解耦，单独 try 不影响既有模型列表加载
  try {
    const img = await mediaApi.listImageModels()
    imageModels.value = img.data.data ?? []
    applyDefaultImageModel()
  } catch {
    // 生图 provider 未配时列表空（下拉空态不崩）
  }
})

/**
 * 修复III C2（2x-2）：图片节点未显式选模型时补默认——管理员默认标记项 ?? 列表第一个。
 * 覆盖所有创建路径（工具条/快连/衍生/上传）；模型列表加载完成与节点切换两个时机各补一次
 * （先切节点后载列表/新建图片节点都会被兜住），emit data-changed 触发画布防抖落库。
 */
function applyDefaultImageModel() {
  const node = props.node
  if (!node || node.type !== 'image' || node.data.model) return
  const fallback = imageModels.value.find(m => m.defaultModel) ?? imageModels.value[0]
  if (!fallback) return
  node.data.model = fallback.modelId
  emit('data-changed')
}
watch(() => props.node?.id, () => applyDefaultImageModel())
/** 按 providerName 分组（与 chat ModelSelector 同范式；结构类型兼容 AvailableModel/ImageModelVO）。 */
function groupModels(list: { providerName: string; displayName: string; modelId: string }[]) {
  const grouped = new Map<string, { type: 'group'; label: string; key: string; children: { label: string; value: string }[] }>()
  for (const m of list) {
    if (!grouped.has(m.providerName)) {
      grouped.set(m.providerName, { type: 'group', label: m.providerName, key: m.providerName, children: [] })
    }
    grouped.get(m.providerName)!.children.push({ label: m.displayName, value: m.modelId })
  }
  return Array.from(grouped.values())
}
const chatModelOptions = computed(() => groupModels(chatModels.value))
const videoModelOptions = computed(() => groupModels(videoModels.value))
const imageModelOptions = computed(() => groupModels(imageModels.value))

// ---------- 修复VI VE（2x#6）：视频节点参数 capability 驱动（对齐独立视频页 VideoGenView） ----------

/** 保守兜底档：未选模型/列表加载失败/模型已下线时的回落值=原硬编码（画布不因配置缺位瘫痪）。 */
const VIDEO_CAP_FALLBACK: MediaModelVO = {
  modelId: '__fallback__',
  displayName: 'fallback',
  providerName: 'fallback',
  maxImages: 1,
  maxVideos: 0,
  maxAudios: 0,
  maxAttachments: 1,
  supportedRatios: ['16:9', '9:16', '1:1', '4:3', '3:4', '21:9'],
  supportedResolutions: ['480p', '720p', '1080p', '4K'],
  minDuration: 4,
  maxDuration: 15,
  supportsGenerateAudio: true,
  videoDataUri: false,
  referenceVideoEnabled: false
}

/** 当前视频节点能力：列表命中 → 该模型 capability；否则兜底档（未选模型也走兜底，参数区可用）。 */
const videoCap = computed<MediaModelVO>(() => {
  if (props.node?.type !== 'video') return VIDEO_CAP_FALLBACK
  const modelId = props.node.data.model as string | undefined
  return (modelId && videoModels.value.find(m => m.modelId === modelId)) || VIDEO_CAP_FALLBACK
})
const videoCapMinDuration = computed(() => videoCap.value.minDuration)
const videoCapMaxDuration = computed(() => videoCap.value.maxDuration)
const videoCapMaxImages = computed(() => videoCap.value.maxImages)

/** 与独立视频页同文案（VideoGenView RATIO_LABELS/RES_LABELS 同口径拷贝，未知档位回落原值）。 */
const VIDEO_RATIO_LABELS: Record<string, string> = {
  '16:9': '16:9 横屏（推荐）', '9:16': '9:16 竖屏', '1:1': '1:1 方形',
  '4:3': '4:3', '3:4': '3:4', '21:9': '21:9 超宽', 'adaptive': 'adaptive（沿用参考素材比例）'
}
const VIDEO_RES_LABELS: Record<string, string> = {
  '480p': '480p（省额度）', '720p': '720p（推荐）', '1080p': '1080p（高清）', '4K': '4K（超高清）'
}
const videoRatioOpts = computed(() =>
  videoCap.value.supportedRatios.map(v => ({ label: VIDEO_RATIO_LABELS[v] ?? v, value: v })))
const videoResOpts = computed(() =>
  videoCap.value.supportedResolutions.map(v => ({ label: VIDEO_RES_LABELS[v] ?? v, value: v })))

/** 分辨率档位序（收敛「最近合法值」用：4K→新模型仅 480p/720p → 落 720p 而非清空）。键全大写统一比较。 */
const RES_RANK: Record<string, number> = { '480P': 0, '768P': 1, '720P': 1, '1080P': 2, '2K': 3, '4K': 4 }
function nearestResolution(current: string | undefined, supported: MediaResolution[]): MediaResolution {
  if (!supported.length) return (current ?? '720p') as MediaResolution
  if (current && supported.includes(current as MediaResolution)) return current as MediaResolution
  const rank = current != null ? (RES_RANK[current.toUpperCase()] ?? 2) : 2
  return supported.reduce((best, r) =>
    Math.abs((RES_RANK[r.toUpperCase()] ?? 2) - rank) < Math.abs((RES_RANK[best.toUpperCase()] ?? 2) - rank) ? r : best
  , supported[0])
}

/**
 * 修复VI VE（2x#6）：切换视频模型 → 参数收敛进新能力区间（仿 onImageModelChange +
 * 独立页 applyCapabilityConstraints）。回落取「最近合法值」不清空（清空丢用户语义）；
 * 不支持生成音频 → 置 false；maxImages=0 → 清首/尾帧残留（后端对不支持字段传值直接拒绝）。
 */
function onVideoModelChange(model: string | null) {
  const node = props.node
  if (!node) return
  node.data.model = model ?? undefined
  const c = model ? videoModels.value.find(m => m.modelId === model) : undefined
  if (c) {
    const ratio = node.data.ratio as MediaRatio | undefined
    if (!ratio || !c.supportedRatios.includes(ratio)) {
      node.data.ratio = (c.supportedRatios.includes('16:9' as MediaRatio) ? '16:9' : c.supportedRatios[0])
    }
    node.data.resolution = nearestResolution(node.data.resolution as string | undefined, c.supportedResolutions)
    const dur = Number(node.data.duration ?? 5)
    node.data.duration = Math.min(Math.max(dur, c.minDuration), c.maxDuration)
    if (!c.supportsGenerateAudio) node.data.generateAudio = false
    if (c.maxImages === 0) {
      delete node.data.firstFrameNodeId
      delete node.data.lastFrameNodeId
    }
  }
  emit('data-changed')
}

// ---------- 2x-3：图片节点 capability 驱动参数 ----------

/** 当前图片节点所选模型的能力（未选模型 → null，参数区隐藏）。 */
const imageCap = computed<ImageModelCapability | null>(() => {
  if (props.node?.type !== 'image') return null
  const modelId = props.node.data.model as string | undefined
  if (!modelId) return null
  return imageModels.value.find(m => m.modelId === modelId)?.capability ?? null
})

const imgSizeOptions = computed(() => {
  if (!imageCap.value) return []
  // 修复IV A 顺手修：部分模型 capability 无 sizePresets（存量 3 个 unhandled rejection 根因）
  const opts = (imageCap.value.sizePresets ?? []).map(s => ({ label: s, value: s }))
  if (imageCap.value.supportsWhSize) opts.push({ label: '自定义宽x高', value: '__custom__' })
  return opts
})

/** C4（6x/Q5）：比例模式——切自定义宽x高清比例（互斥），切档位清自定义原文。 */
function onImageSizeChange(v: string | null) {
  const node = props.node
  if (!node) return
  node.data.size = v ?? undefined
  if (v !== '__custom__') node.data.customSize = undefined
  if (v === '__custom__') node.data.ratio = undefined
  emit('data-changed')
}

// C4：比例+档位 → 推导 WxH 预览（utils/imageSize 与后端 ImageSizeDeriver 同算法）
const imgRatioOptions = RATIOS.map(r => ({ label: r, value: r }))
const imgRatioDerive = computed(() => {
  if (props.node?.type !== 'image') return null
  const ratio = props.node.data.ratio as string | undefined
  if (!ratio) return null
  const size = props.node.data.size as string | undefined
  return deriveWh(ratio, size === '__custom__' ? null : size)
})
const imgRatioPreview = computed(() => {
  const d = imgRatioDerive.value
  if (!d || !('w' in d)) return ''
  const tier = (props.node?.data.size as string) || '2K'
  return `按比例推导：${d.w}x${d.h}（${tier} 档等面积；预估与比例无关）`
})
const imgRatioError = computed(() => {
  const d = imgRatioDerive.value
  return d && 'error' in d ? d.error : ''
})

function toUpperOptions(arr: string[]) {
  // 修复IV A 顺手修：capability 缺字段时兜底空数组（存量 unhandled rejection 同根因）
  return (arr ?? []).map(v => ({ label: v.toUpperCase(), value: v }))
}

/**
 * 切换图片模型 → 按新模型 capability 重置参数默认值（同 ImageGenView.onModelChange）。
 * 旧模型不支持/新模型不支持的残留字段必须清掉——后端对「不支持的字段传值」直接拒绝。
 */
function onImageModelChange(model: string | null) {
  const node = props.node
  if (!node) return
  node.data.model = model ?? undefined
  const c = model ? imageModels.value.find(m => m.modelId === model)?.capability : undefined
  const staleKeys = ['size', 'customSize', 'ratio', 'outputFormat', 'optimizeMode', 'guidanceScale',
    'sequential', 'maxImages', 'watermark', 'webSearch']
  if (c) {
    node.data.size = c.sizePresets[0] ?? undefined
    node.data.customSize = undefined
    node.data.ratio = undefined
    node.data.outputFormat = c.outputFormats[0] ?? undefined
    node.data.optimizeMode = c.optimizeModes[0] ?? undefined
    node.data.guidanceScale = c.supportsGuidanceScale
      ? Math.round((c.guidanceMin + c.guidanceMax) / 2) : undefined
    node.data.sequential = c.supportsSequential ? 'disabled' : undefined
    node.data.maxImages = c.supportsSequential ? Math.min(4, c.maxSequentialImages || 4) : undefined
    node.data.watermark = c.watermarkDefault
    node.data.webSearch = undefined
  } else {
    for (const k of staleKeys) delete node.data[k]
  }
  emit('data-changed')
}
</script>

<style lang="scss" scoped>
.prop-panel {
  position: relative; // 修复IV B4：左缘拖宽条 __gutter 的定位锚
  width: 260px;
  flex-shrink: 0;
  padding: var(--spacing-2);
  background: var(--color-surface);
  border-left: 1px solid var(--color-border-light);
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-2);

  &__title {
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
    text-transform: uppercase;
    padding: var(--spacing-1) var(--spacing-2);
  }

  &__empty {
    color: var(--color-text-tertiary);
    font-size: var(--font-size-sm);
    padding: var(--spacing-3);
    text-align: center;
  }

  &__field {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-1);

    label {
      font-size: var(--font-size-xs);
      color: var(--color-text-secondary);
    }
  }

  &__row {
    display: flex;
    gap: var(--spacing-2);
  }

  /* 2x 四轮 S8：参考缩略横排（超宽换行） */
  &__refs {
    display: flex;
    flex-wrap: wrap;
    gap: var(--spacing-1);
  }

  /* D2（2x-8）：上游卡片（缩略 + 名称 + 提示词两行截断；间距 8、卡内紧凑） */
  &__up-card {
    display: flex;
    align-items: flex-start;
    gap: var(--spacing-1);
    padding: var(--spacing-1);
    border: 1px solid var(--color-border-light);
    border-radius: var(--radius-base);
    cursor: default; // 双击才插入 @引用；单击无卡片级动作
    user-select: none;

    & + & { margin-top: var(--spacing-1); }

    &:hover { border-color: var(--color-primary); }
  }

  &__up-thumb {
    position: relative;
    flex-shrink: 0;
    width: 44px;
    height: 44px;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: var(--radius-small);
    background: var(--color-bg);
    border: 0;
    padding: 0;
    overflow: visible; // hover 放大出卡外
    color: var(--color-text-tertiary);

    img {
      width: 100%;
      height: 100%;
      object-fit: cover;
      border-radius: var(--radius-small);
      transition: transform 0.12s var(--ease-in-out);
      transform-origin: center;
    }

    // 修复V A1（2x-1）：视频首帧缩略——与 img 同规格；pointer-events:none 让点击冒泡到
    // 按钮开 Lightbox（video 元素自身吞点击），单击播放手势不变
    video {
      width: 100%;
      height: 100%;
      object-fit: cover;
      border-radius: var(--radius-small);
      pointer-events: none;
      transition: transform 0.12s var(--ease-in-out);
      transform-origin: center;
    }

    // hover 媒体放大预览（1.6x 出卡外，需放大看的直观提示）
    &:hover img,
    &:hover video { transform: scale(1.6); z-index: 2; position: relative; box-shadow: 0 2px 10px rgba(0, 0, 0, 0.5); }

    &.is-clickable { cursor: zoom-in; }
  }

  // P4 后续（用户反馈）：视频首帧上的 ▶ 播放标——居中覆盖缩略框，z 高于 hover 放大（z2）的 video。
  // 坑注：必须与 __up-card/__up-meta 平级——嵌进 __up-thumb 会编译成 .prop-panel__up-thumb__up-play，永不命中
  &__up-play {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 16px; // 对齐参考区 ref-preview__play（用户口径「像参考里面的一样」）
    color: #fff;
    text-shadow: 0 0 4px rgba(0, 0, 0, 0.9);
    pointer-events: none;
    z-index: 3;
  }

  &__up-ph {
    font-size: var(--font-size-sm);
  }

  &__up-meta {
    flex: 1;
    min-width: 0; // 让截断在卡内生效
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  &__up-name {
    display: flex;
    align-items: center;
    gap: 4px;
    min-width: 0;
  }

  &__up-kind {
    flex-shrink: 0;
    font-size: 10px;
    line-height: 1.4;
    padding: 0 3px;
    border: 1px solid var(--color-border-light);
    border-radius: var(--radius-small);
    color: var(--color-text-tertiary);
    background: rgba(148, 163, 184, 0.1);
  }

  /* 修复IV B2（C-2）：类型徽标/缩略占位按节点型着色（hue 字面量，同节点状态色风格） */
  &__up-kind,
  &__up-ph {
    &[data-kind='text'] { color: #94a3b8; }
    &[data-kind='image'] { color: #38bdf8; }
    &[data-kind='video'] { color: #a78bfa; }
    &[data-kind='audio'] { color: #fbbf24; }
    &[data-kind='script'] { color: #34d399; }
    &[data-kind='storyboard'] { color: #f472b6; }
    &[data-kind='director'] { color: #fb923c; }
  }

  &__up-kind[data-kind='image'] { background: rgba(56, 189, 248, 0.12); }
  &__up-kind[data-kind='video'] { background: rgba(167, 139, 250, 0.12); }
  &__up-kind[data-kind='audio'] { background: rgba(251, 191, 36, 0.12); }
  &__up-kind[data-kind='script'] { background: rgba(52, 211, 153, 0.12); }
  &__up-kind[data-kind='storyboard'] { background: rgba(244, 114, 182, 0.12); }
  &__up-kind[data-kind='director'] { background: rgba(251, 146, 60, 0.12); }

  /* 修复IV B2（C-2）：更上游直显小卡——左缩进+连线区分层级（替代原折叠列表） */
  &__up-card--far {
    margin-left: 12px;
    border-left: 2px solid rgba(var(--color-primary-rgb), 0.35);
  }

  &__up-label {
    font-size: var(--font-size-xs);
    color: var(--color-text-secondary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__up-prompt {
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
    line-height: 1.4;
    display: -webkit-box;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
    overflow: hidden;
    word-break: break-all;
  }

  /* 修复IV B4（C-6）：左缘拖宽条——视觉 4px 高亮线 + 10px 命中热区，hover/拖拽显主题色 */
  &__gutter {
    position: absolute;
    left: -5px;
    top: 0;
    bottom: 0;
    width: 10px;
    cursor: col-resize;
    z-index: 5;

    &::after {
      content: '';
      position: absolute;
      left: 3px;
      top: 0;
      bottom: 0;
      width: 4px;
      border-radius: 2px;
      background: transparent;
      transition: background var(--duration-fast) var(--ease-in-out);
    }

    &:hover::after,
    &:active::after { background: var(--color-primary); }
  }

  /* 2x 四轮 S6：确定性变换按钮排（五个小按钮挤一行，超出换行） */
  &__transform {
    display: flex;
    flex-wrap: wrap;
    gap: var(--spacing-1);
  }

  &__transform-btn {
    flex: 1 1 30%;
    padding: var(--spacing-1);
    font-size: var(--font-size-xs);
    color: var(--color-text-secondary);
    background: var(--color-bg);
    border: 1px solid var(--color-border-light);
    border-radius: var(--radius-base);
    cursor: pointer;
    white-space: nowrap;

    &:hover:not(:disabled) {
      color: var(--color-primary);
      border-color: var(--color-primary);
    }

    &:disabled {
      opacity: 0.45;
      cursor: not-allowed;
    }
  }

  &__hint,
  &__readonly {
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
    padding: var(--spacing-2);
    background: var(--color-bg);
    border-radius: var(--radius-base);
    word-break: break-all;
  }

  &__inline-hint {
    margin-left: var(--spacing-1);
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
    font-weight: normal;
  }

  &__output {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-1);
  }

  &__output-text {
    font-size: var(--font-size-xs);
    color: var(--color-text-secondary);
    padding: var(--spacing-2);
    background: var(--color-bg);
    border-radius: var(--radius-base);
    max-height: 160px;
    overflow-y: auto;
    white-space: pre-wrap;
    line-height: 1.5;
  }

  &__error {
    font-size: var(--font-size-xs);
    color: #f87171;
    padding: var(--spacing-2);
    background: rgba(239, 68, 68, 0.08);
    border-radius: var(--radius-base);
    word-break: break-all;
  }

  &__warn {
    font-size: var(--font-size-xs);
    color: #facc15;
    padding: var(--spacing-1) var(--spacing-2);
    background: rgba(250, 204, 21, 0.1);
    border-radius: var(--radius-base);
    word-break: break-all;
  }

  /* C2（17x-2/2x）：提交按钮旁预估条（chip + 不足红字分层） */
  &__estimate {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-1);
    margin-top: var(--spacing-1);
  }

  &__estimate-warn {
    font-size: var(--font-size-xs);
    color: #f87171;
    word-break: break-all;
  }

  &__asset-badge {
    font-size: var(--font-size-xs);
    color: var(--color-primary);
    padding: var(--spacing-1) var(--spacing-2);
    background: rgba(var(--color-primary-rgb), 0.12);
    border-radius: var(--radius-base);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;

    &[data-has-update='true'] {
      color: #facc15;
      background: rgba(250, 204, 21, 0.14);
    }
  }
}
</style>

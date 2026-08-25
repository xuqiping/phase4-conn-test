<template>
  <aside class="prop-panel">
    <div class="prop-panel__title">属性</div>
    <div v-if="!node" class="prop-panel__empty">选中一个节点编辑其属性</div>
    <template v-else>
      <div class="prop-panel__field">
        <label>名称</label>
        <!-- L9 重命名查重：失焦时若与同画布其他节点撞名，自动追加序号（占位符存 id 不受影响） -->
        <n-input v-model:value="(node.data.label as string)" size="small" placeholder="节点名" @blur="onRenameBlur" />
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
            :model-value="(node.data.prompt as string) || ''"
            :candidates="candidates"
            :broken-mentions="brokenMentions"
            :rows="4"
            placeholder="文本节点提示词；输入 @ 引用上游节点产出"
            @update:model-value="(v: string) => { if (node) node.data.prompt = v }"
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
            :model-value="(node.data.prompt as string) || ''"
            :candidates="candidates"
            :broken-mentions="brokenMentions"
            :rows="3"
            placeholder="图片生成 prompt；输入 @ 引用上游图节点作参考图"
            @update:model-value="(v: string) => { if (node) node.data.prompt = v }"
            @mention-click="onMentionClick"
          />
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

        <!-- 2x-3：按所选模型 capability 驱动的生成参数（与图片生成模块同源能力） -->
        <template v-if="imageCap">
          <div class="prop-panel__field">
            <label>尺寸</label>
            <n-select
              :value="(node.data.size as string) || null"
              :options="imgSizeOptions"
              size="small"
              clearable
              placeholder="模型默认"
              @update:value="(v: string | null) => { if (node) { node.data.size = v ?? undefined; if (v !== '__custom__') node.data.customSize = undefined; emit('data-changed') } }"
            />
          </div>
          <div v-if="(node.data.size as string) === '__custom__'" class="prop-panel__field">
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
                :options="imageCap.optimizeModes.map(v => ({ label: v, value: v }))"
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
          :disabled="!(node.data.prompt as string)?.trim() || !(node.data.model as string)?.trim()"
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
            :model-value="(node.data.prompt as string) || ''"
            :candidates="candidates"
            :broken-mentions="brokenMentions"
            :rows="3"
            placeholder="视频生成 prompt；输入 @ 引用上游节点产出"
            @update:model-value="(v: string) => { if (node) node.data.prompt = v }"
            @mention-click="onMentionClick"
          />
          <div v-if="brokenMentions.length" class="prop-panel__warn">
            断链引用：{{ brokenMentions.join(' ') }}（上游被删/断连，运行前请重连或移除）
          </div>
        </div>
        <div class="prop-panel__row">
          <div class="prop-panel__field">
            <label>比例</label>
            <n-select v-model:value="(node.data.ratio as string)" size="small" :options="ratioOpts" />
          </div>
          <div class="prop-panel__field">
            <label>时长(秒)</label>
            <n-input-number v-model:value="(node.data.duration as number | undefined)" size="small" :min="4" :max="15" />
          </div>
        </div>
        <div class="prop-panel__field">
          <label>分辨率</label>
          <n-select v-model:value="(node.data.resolution as string)" size="small" :options="resOpts" />
        </div>
        <div class="prop-panel__field">
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
        <div class="prop-panel__field">
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
            @update:value="(v: string | null) => { if (node) { node.data.model = v ?? undefined; emit('data-changed') } }"
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
          <n-select v-model:value="(node.data.audioMode as string)" size="small" :options="audioModeOpts" />
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
            :model-value="(node.data.description as string) || ''"
            :candidates="candidates"
            :broken-mentions="brokenMentions"
            :rows="5"
            placeholder="分镜画面描述；下游图/视频节点 @本节点即注入此描述"
            @update:model-value="(v: string) => { if (node) node.data.description = v }"
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
            :model-value="(node.data.synopsis as string) || ''"
            :candidates="candidates"
            :broken-mentions="brokenMentions"
            :rows="5"
            placeholder="剧本输入；输入 @ 引用上游节点产出，经 LlmGateway 拆分镜"
            @update:model-value="(v: string) => { if (node) node.data.synopsis = v }"
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
import type { ImageModelCapability, ImageModelVO, MediaEstimateVO } from '@/api/media'
import MentionTextarea from './MentionTextarea.vue'
import MediaTaskRequestDetails from '../media/MediaTaskRequestDetails.vue'
import MediaLightbox from '../media/MediaLightbox.vue'
import ReferencePreview from './ReferencePreview.vue'
import type { CanvasReferenceItem } from '@/utils/canvasVideoAttachments'
import { uniqueLabel } from '@/utils/interpolate'

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
}>(), {
  candidates: () => [],
  brokenMentions: () => [],
  allLabels: () => [],
  imageAncestorOptions: () => [],
  references: () => [],
  projectGroupId: null
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
}>()

/** S12：当前节点已绑定资产（node.data.assetId 存在）。 */
const assetBound = computed(() => props.node?.data.assetId != null)
const assetName = computed(() => (props.node?.data.assetName as string | undefined) ?? '资产')
const assetVersion = computed(() => (props.node?.data.assetVersion as number | undefined) ?? 1)
const assetHasUpdate = computed(() => Boolean(props.node?.data.assetHasUpdate))

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

/** 不足红字：与生成页同分层（个人限额卡 / 组池卡 / 钱包）。 */
const estimateWarn = computed<string | null>(() => {
  const e = estimate.value
  if (!e || e.affordable) return null
  const scope = e.personalScope
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
}

/** A1：提示词 @chip 被点击 → 上抛 mention-focus，CanvasView 居中选中被引用节点。 */
function onMentionClick(payload: { kind: string; id: string }) {
  emit('mention-focus', payload)
}

/** 脚本节点已拆分镜数（属性面板回显）。 */
const sceneCount = computed(() =>
  Array.isArray(props.node?.data.scenes) ? (props.node!.data.scenes as unknown[]).length : 0
)

const ratioOpts = ['16:9', '9:16', '1:1', '4:3', '3:4', '21:9'].map(v => ({ label: v, value: v }))
const resOpts = ['480p', '720p', '1080p', '4K'].map(v => ({ label: v, value: v }))
const audioModeOpts = [
  { label: '上传', value: 'upload' },
  { label: 'TTS 语音', value: 'tts' },
  { label: '音乐生成', value: 'music' }
]

// ---------- C5 节点选模型（text/script=chat 模型；video=MEDIA 视频模型；image=生图模型） ----------
const chatModels = ref<AvailableModel[]>([])
const videoModels = ref<AvailableModel[]>([])
const imageModels = ref<ImageModelVO[]>([])
onMounted(async () => {
  try {
    const [c, v] = await Promise.all([llmApi.listAvailableModels(), llmApi.listVideoModels()])
    chatModels.value = c.data.data ?? []
    videoModels.value = v.data.data ?? []
  } catch {
    // 模型列表可选，失败静默（下拉空态不崩）
  }
  // 生图模型独立取：图片 API 与 chat/video 解耦，单独 try 不影响既有模型列表加载
  try {
    const img = await mediaApi.listImageModels()
    imageModels.value = img.data.data ?? []
  } catch {
    // 生图 provider 未配时列表空（下拉空态不崩）
  }
})
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
  const opts = imageCap.value.sizePresets.map(s => ({ label: s, value: s }))
  if (imageCap.value.supportsWhSize) opts.push({ label: '自定义宽x高', value: '__custom__' })
  return opts
})

function toUpperOptions(arr: string[]) {
  return arr.map(v => ({ label: v.toUpperCase(), value: v }))
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
  const staleKeys = ['size', 'customSize', 'outputFormat', 'optimizeMode', 'guidanceScale',
    'sequential', 'maxImages', 'watermark', 'webSearch']
  if (c) {
    node.data.size = c.sizePresets[0] ?? undefined
    node.data.customSize = undefined
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

# 计划 · 人工测试遗留问题修复X（Chunk A 视频上传 / B 从库选择预览 / C 组边保留 / D 文档收尾）

> Phase 2 产出，源自 [specs/人工测试遗留问题修复X设计.md](../specs/人工测试遗留问题修复X设计.md)（Q1~Q3 拍板已入规格 §3）。
> 硬闸门：本文件过审并获明确许可前不写实现码。

## 〇、总览与依赖序

```
A1 视频区上传按钮+三kind预检 → A2 A轮收口
B1 共享地基（HoverPreviewImage 扩 video + Lightbox 抬层） → B2 AssetPicker 行改版四态+交互 → B3 B轮收口
C1 纯函数层（组边收集+组感知重映射） → C2 接线层（粘贴分流/副本去滤/appendEdges 分流） → C3 C轮收口
D 文档收尾+测试方案+问题单（依赖 A2+B3+C3 全绿）
```

A/B/C 三轮完全并行无依赖（改动文件零交集）；D 串行最后。B1→B2、C1→C2 各自串行。

**对规格的实现期细化**（critique 自查后补，原因随行）：
1. §4 X-2④ 音频行布局定稿：缩略块（72×48）显「音」类型字标，**meta 区下方整行嵌 `<audio controls>`**（宽自适应行宽，preload=none）——controls 挤不进 48px 高缩略块，整行嵌条参考 ReferencePreview 音频口径（该处 64×48 格旁挂，本处行式布局取行嵌）。
2. §4 X-1② 预检的 MIME 判空：拖入绕过 accept 或罕见浏览器 file.type 为空 → `kindFromMime` 返 null → **跳过预检直接 emit**（交后端闸），不误拦合法文件。
3. §4 X-3① 落点明确：`buildCopySet` 签名**不变**（仍收一份 edges 数组），组边纳入由**调用方 CanvasBoard:932 改传 `[...edges.value, ...groupEdges.value]`** + 函数内 crossEdges 去 `isGroupEndpoint` 过滤两件事合成——纯函数测试可直接喂混合数组。

---

## Chunk A · 视频节点上传（X-1，P0）

### A1 面板上传按钮 + 统一预检

- **目标**：视频节点区补上传口；三 kind 大小预检前端化（toast 拒不发请求）。
- **动作**（伪代码）：
  ```
  PropertyPanel.vue 视频区(<template v-else-if="node.type === 'video'"> 顶部, 提示词字段前):
    <n-upload :show-file-list="false" accept="video/*" @change="onPickFile">
      <n-button size="small" block :loading="running">
        <template #icon><n-icon :component="CloudUploadOutline" /></template>
        上传视频
      </n-button>
    </n-upload>
    <div class="prop-panel__hint">本地视频 ≤{{ KIND_LIMIT_LABEL.video }}，上传后作为节点素材</div>   // 单源常量防 VD 式漂移
  onPickFile(opts):   // :1251-1256 既有函数内改
    file = opts?.file?.file; if (!file || !props.node) return
    kind = kindFromMime(file.type)
    if (kind):
      err = sizeLimitError(kind, file.size, file.name)
      if (err): message.warning(err); return     // 前端拒，不发 upload 事件（细化2：kind null 跳过预检）
    emit('upload', { node: props.node, file })
  ```
  上传链（onUploadFile/canvasApi.upload/后端）零改动——规格 §4 X-1③④。
- **涉及文件**：components/canvas/PropertyPanel.vue、utils/mediaLimits.ts(仅 import KIND_LIMIT_LABEL，若未导出则补 export)、PropertyPanel.test.ts
- **依赖**：无
- **验证**：vitest——视频区渲染上传按钮（accept=video/*）；60MB 文件 onPickFile 不 emit upload 且 toast；50MB 内正常 emit；图片 31MB/音频 16MB 各拒一例；file.type 空串跳过预检照常 emit；既有图片/音频上传用例（合法大小）不红。

### A2 A 轮收口测试

- **动作**：vitest 全量 + vue-tsc 0 错；手测标记——传 mp4 显预览可播、上传后 C11 抽帧/C12 截取按钮出现可用、重传覆盖、下游节点 @引用取到该视频。
- **验证**：全绿 + 手测记录入变更记录。

---

## Chunk B · 从库选择全类型预览（X-2，P0）

### B1 共享地基：HoverPreviewImage 扩 video + Lightbox 抬层

- **目标**：悬浮放大组件支持视频首帧；灯箱能盖住 n-modal。
- **动作**（伪代码）：
  ```
  HoverPreviewImage.vue:
    props + kind?: 'image' | 'video'（默认 'image'，五处既有调用零改动）
    NPopover 内容: kind=image 现状 img+dims；kind=video → <video :src preload="metadata" muted playsinline>（无 dims 行）
    文件名不改（规格 §8），组件头注释补「双态」说明
  Lightbox.vue: z-index: 2000 → 3000（样式一行，关闭/缩放逻辑零动）
  ```
- **涉及文件**：components/media/HoverPreviewImage.vue、components/canvas/Lightbox.vue、HoverPreviewImage.test.ts(若无则随 B2 建)
- **依赖**：无
- **验证**：vitest——kind=video 渲染 video 分支、默认不传 kind 渲染 img（回归）、视频态无 dims 行；Lightbox 样式断言（class 存在即可，z-index 属人工）；VideoGenView/ImageGenView/ReferencePreview 既有悬浮用例回归不红。

### B2 AssetPicker 行改版：四态缩略 + 交互三分离

- **目标**：行=缩略块（四态）+名称/meta+音频条+选择按钮；点缩略=预览、点按钮=选、行空白不动作。
- **动作**（伪代码）：
  ```
  AssetPicker.vue 行结构（:64-81 重写）:
    <div class="picker__row" :class="{--archived}">        // 去 @click="onPick(a)"
      <button type="button" class="picker__thumb" :aria-label="`预览 ${a.name}`" @click="onPreview(a)">
        图片: <HoverPreviewImage :preview-src="url"><img …></HoverPreviewImage>      // url=useLazyFilePreview 产物
        视频: <HoverPreviewImage kind="video" :preview-src="url">
                <video preload="metadata" muted playsinline>…</video><span class="picker__play">▶</span>
              </HoverPreviewImage>
        音频: <span class="picker__thumb-ph">音</span>
        文本类: a.textPreview ? <p class="picker__thumb-text">{{ a.textPreview }}</p> : <span class="picker__thumb-ph">提/剧/分</span>
        失败/无 fileId: 类型字标回落（AssetPickerMediaPreview failed 范式）
      </button>
      <div class="picker__row-main">名称/meta（现状）</div>
      <audio v-if="isAudio(a)" :src="url ?? undefined" controls preload="none" @click.stop />   // meta 区下方整行（细化1）
      <n-button size="small" type="primary" tertiary :loading="pickingId === a.id" @click="onPick(a)">选择</n-button>
    </div>
  <Lightbox v-model:open 风格接入（或 open prop+close emit，按 Lightbox 既有接口）: kind/alt/src 由 previewState 驱动>
  预览态: const preview = ref<{ kind: 'image'|'video'; src: string; name: string } | null>
  onPreview(a): url 已就绪 → 开 Lightbox；未就绪/失败 → 不开（占位字标本身不可点预期，thumb aria-disabled 或 title 提示）
  懒加载: 每行 useLazyFilePreview(thumbRootEl, () => a.fileId, enabled=图/视/音)——文本类 enabled=false（无文件）
  类型判定: 图片/视频/音频按 mediaType（'图片'/'视频'/'音频'）；文本类=其余（提示词/剧本/分镜）
  ```
- **涉及文件**：components/canvas/AssetPicker.vue、AssetPicker.test.ts(新)
- **依赖**：B1
- **验证**：vitest——四类型行渲染（图 img/视 video+▶/音字标+audio 条/文 textPreview 片段、无片段回落字标）；点 thumb **不**触发 picked；点「选择」触发 picked（resolve mock）+弹窗关；行空白点击零 emit；audio @click.stop（点条不冒泡）；ARCHIVED 半透明回归；预览失败回落字标；fileId 缺失文本类不拉文件。

### B3 B 轮收口测试

- **动作**：vitest 全量 + vue-tsc 0 错；手测标记——四类型行各正确；图悬浮 300ms 放大+尺寸行、点击大图滚轮缩放；视频悬浮首帧放大、点击弹播放；音频行内播；文本片段溢出省略；Lightbox 在选择弹窗**之上**（z-index 实测）；Esc 关灯箱后弹窗仍在、选择链完好。
- **验证**：全绿 + 手测记录入变更记录。

---

## Chunk C · 组边连线保留（X-3，P0）

### C1 纯函数层：组边收集 + 组感知重映射

- **目标**：剪贴板收组端点跨集边；重映射组端保原伪 id；组悬挂防护。
- **动作**（伪代码）：
  ```
  canvasClipboard.ts:
    buildCopySet: crossEdges 过滤去 isGroupEndpoint 条件（innerEdges 的保留不动）
      // 组→组/组自环: 伪 id 恒不在选中集 →「恰一端在集」判 false 天然不收（无需特判）
      // 注释修订: VIII-1⑧ 口径改「诱导边仍排除组端点；跨集组边纳入（修复X）」
    remapCrossEdges(clip, keyToNewId, aliveNodeIds, aliveGroupIds):   // 签名 +第4参
      端点分流: 组伪 id → 保原 + alive 校验查 aliveGroupIds（groupIdOf）
                节点端 → 照旧（集内换新 id / 集外保原 + aliveNodeIds）
  nodeClone.ts:
    cloneEdgesForDuplicate: 去 isGroupEndpoint 过滤行（:54），注释修订同上
      // 单侧重映射照旧: 节点端换 newId、组端保原伪 id
  ```
- **涉及文件**：components/canvas/canvasClipboard.ts、components/canvas/nodeClone.ts、canvasClipboard.test.ts、nodeClone.test.ts
- **依赖**：无
- **验证**：单测——组端点 cross 收集两向（节点→组/组→节点）、组→组不收、组自环不收、innerEdges 仍零组边；remapCrossEdges：组端保原伪 id+节点端换新、组不在 aliveGroupIds（解散）丢边、组活节点活产出、剥 class 回归；cloneEdgesForDuplicate：组边克隆（节点端换 newId 组端保原）、普通边/自环既有用例不红。

### C2 接线层：粘贴分流 + 副本 + appendEdges 分流

- **目标**：组边沿两条路径真实落地（组池）、开关同治理、几何/落库/撤回自动。
- **动作**（伪代码）：
  ```
  CanvasBoard.vue:
    onCopyKeydown(:932): buildCopySet(nodes.value, [...edges.value, ...groupEdges.value], selected)   // 细化3
    pasteSubgraph(:993-996):
      alive = new Set(节点 id) ∪ aliveGroupIds = new Set(groups.value.map(g => g.id))
      remapCrossEdges(clip, keyToNewId, 节点alive集, 组alive集) 产物分流:
        含组伪 id → groupEdges.value.push；否则 edges.value.push（现状）
    appendEdges(:1703-1711):
      组端点边 → groupEdges.value.push(浅拷贝剥会话 class——loadSnapshot :1537 同口径)
      普通边照旧；scheduleStoreReconcile 仅普通边非空时调
      注释修订（:1704 组边不带出口径）
  CanvasView.vue onCloneNode(:2643-2645): 零改动——getEdges() 已含组边（:1644-1650），C1 去滤后自然克隆
  ```
  几何（watch groupEdges rAF）、落库（getSnapshot 合并）、undo（pushHistory 快照）全既有链零改动——规格 §1c⑦⑧。
- **涉及文件**：components/canvas/CanvasBoard.vue、CanvasBoard.test.ts
- **依赖**：C1
- **验证**：组件测试——复制连组节点（两向）开 ⛓ 粘贴→新组边落 groupEdges 池、组包围盒出现连线；关 ⛓ 粘贴零组边；副本同口径组边落组池；复制后解散组再粘贴→不产断边；Ctrl+Z 一步撤含组边；appendEdges 混合批次（普通+组边）各归各池；VII/VIII/IX 既有用例（诱导边/组边建删解散/开关双口径）全回归不红。

### C3 C 轮收口测试

- **动作**：vitest 全量 + vue-tsc 0 错；手测标记——组边两向真实消费（组下游生成广播取到新节点产物/组上游聚合新节点取到组成员产物）；开关关两处零组边；组边随批次落库刷新保持。
- **验证**：全绿 + 手测记录入变更记录。

---

## Chunk D · 文档收尾（依赖 A2+B3+C3）

- **feature-map/user-ops 增补**：无限画布创作页 feature-map+用户操作手册「2026-09-01 增补（修复X）」节——视频节点上传（产物口径/50MB/重传覆盖/预检三 kind 统一）、从库选择四态预览与三分离交互（点缩略=预览/点选择=选/行空白不动作）、组边随 ⛓ 保留（新节点=组外部对端不入组员/组解散丢边）。
- **测试方案**：`docs/测试方案/人工测试遗留问题修复X测试方案.md`（新，R 系列：R1-R4 视频上传含边界 / R5-R10 从库选择四态+层级+误选 / R11-R15 组边两向+开关+解散+撤回）。
- **速查表/help 中心**：若「上传视频」涉及帮助中心画布文章则同步一行（实现轮核对存量文档是否提及画布上传口径，防再漂移）。
- **问题单**：2x 三项挂「已实现，待人工验证（修复X）」+commit 号。

---

## 技术坑点预判

| # | 坑 | 规避 |
|---|---|---|
| 1 | 统一预检改变图片/音频超限行为（后端 400 → 前端 toast），既有用例若 mock 后端拒路径会红 | A1 用例同步改预期；超限值边界各测一例（30MB/15MB/50MB 三线） |
| 2 | `file.type` 空串/拖入绕过 accept → kindFromMime null → 误拦合法文件 | null 跳过预检直接 emit（细化2），交后端闸 |
| 3 | n-upload `@change` 在文件状态变化时多次触发 → 重复 emit | 沿用图片节点现役 `opts.file.file` 取值模式（该模式已在产线无此问题），不新发明 custom-request |
| 4 | 50MB 视频 objectURL 常驻内存 → 多节点上传累积 | 现状拖入链同款（LRU+会话级），本轮不新增面；VideoNode 快照只存 fileId 重进按链恢复（修复V A2 fileId 兜底腿已覆盖视频节点） |
| 5 | Lightbox 3000 与 AnnotateOverlay/FocusEditOverlay 2000 同页 theoretically 撞层 | 两 overlay 是编辑态独占交互，与灯箱不同屏；C 轮人工核对一次即可 |
| 6 | NPopover 内 video 关悬浮后不卸载 → 首帧解码器常驻 | naive-ui popover 默认 display-directive='if'（关即卸载）；B2 用例锁内容卸载（实现轮若默认不符则显式设） |
| 7 | picker 每行一个 useLazyFilePreview 实例 ×100 行 → IO 观察器海量 | AssetCard 网格同量级现役范式，LRU 键=fileId 复用；enabled 门控文本类不拉 |
| 8 | textPreview 假设恒在——列表态仅 TEXT 类填充，mediaCategory 列表态可能 null | 判定回落链：textPreview 有→片段，无→类型字标（规格 §4 X-2⑤已列）；实现轮核对 assembleList 是否带 mediaCategory，不依赖 |
| 9 | picker 行去整行 @click 后，键盘用户失去快速选择路径 | 「选择」button 原生可达 + thumb button Enter 开预览，双焦点点（a11y 融入 B2 用例） |
| 10 | buildCopySet 传混合边后 innerEdges 误收组边 | innerEdges 的 isGroupEndpoint 过滤**保留不动**（规格 §4 X-3①）；C1 用例锁死 |
| 11 | 组边 id 撞：粘贴组边 id 规则 `edge-{含伪 id}-…` 与普通边/存量组边碰撞 | remapCrossEdges 沿用 Date.now+remapSeq 全局序（现状规则），伪 id 入串不破坏唯一性 |
| 12 | appendEdges 分流后组边带会话 class 烤入（选中态永久残留） | 分流时浅拷贝剥 class（loadSnapshot :1537 同口径，规格 X-3⑤） |
| 13 | 副本组边展开自环：节点 X 是组 g 成员又有 X→group:g 边 → 展开产 X→X | resolveEdgesForFlow `s===t continue` 现状已丢弃（§1c⑨），零新增处理；C2 回归用例带一例 |
| 14 | CanvasBoard.test 既有「组边不带出」断言（VIII-1⑧ 口径）反转 | C2 同步改口径为「诱导边不带出+跨集组边随开关」，非删用例 |
| 15 | 组解散与粘贴竞态：aliveGroupIds 取自 groups.value 粘贴时点 | 时点判定同 IX crossEdges 哲学（粘贴当下所见）；解散链 prune groupEdges 与粘贴互斥于同一响应式批 |

## 安全检查清单（P3 逐项验）

- [ ] 上传链零新增面：前端预检纯体验，后端 `canvasApi.upload` 鉴权+KIND_MAX_BYTES 双闸不动（A1）
- [ ] `/api/files/{id}` blob 预览通道走既有 auth 头注入（axios 实例），picker 懒加载不绕鉴权（B2）
- [ ] 无新端点/无新请求参数/无新 npm·maven 依赖（全轮）
- [ ] picker resolve 链（选择按钮）现状不动，无越权面变化（B2）
- [ ] 组边纳入纯前端数据流，快照后端透传不变（C 组）

## 功能联动点清单（只列正向，边界含反向/半选/批量）

| # | 触发 | 联动 | 边界 |
|---|---|---|---|
| L1 | 视频上传成功 | 节点 status=success+预览可播；C11 抽帧/C12 截取按钮随 fileId 出现；下游 @候选含本节点 | 失败标红 errorMsg 不落 fileId；重传覆盖旧 fileId（旧 stored 文件孤儿不清理=图片节点现状口径）；上传中 running 态按钮 loading |
| L2 | 统一预检上线 | 图片/音频超限从后端 400 变前端 toast 即拒 | MIME 未知跳过预检交后端；恰卡上限值（=30/15/50MB）放行 |
| L3 | picker 点缩略图 | Lightbox 开于 modal 之上（图缩放/视频播放） | Esc/遮罩/× 关灯箱后选择弹窗仍在焦点回行；url 未就绪/失败不开灯箱 |
| L4 | picker 点「选择」 | resolve→节点数据写入→弹窗关 | resolve 失败 toast+弹窗留+按钮复位；行空白点击**不**触发（防误选口径） |
| L5 | ⛓ 开+复制连组节点 | 粘贴体/副本组边连原组（外部对端），组下游广播/上游聚合消费链含新节点 | 开关关=两处零组边；复制后解散组→粘贴丢该边不产断边；组→组边永不收集 |
| L6 | 粘贴/副本产组边 | SVG 覆盖层几何跟随（rAF）、快照落库、Ctrl+Z 一步撤、组边可选中删/解散级联删（现状链） | 批量粘贴多条组边一次 watch 批处理；副本组边与原组边平行并存（不去重，IX Q4 口径延续） |
| L7 | appendEdges 分流 | 副本组边进组池不进 VueFlow v-model；store 对账只跑普通边 | 混合批次（普通+组）两池各归位、一次 history 一步撤 |

## 运维考量清单

| 类 | 结论 | 落字 |
|---|---|---|
| 可观测性 | **不做** | 纯前端三项；上传失败 toast+节点 errorMsg 现状可观测；无新后端路径 |
| 配置开关 | **做（复用）** | ⛓ keepLinksOnCopy 既有开关直接治理组边（出问题关掉即回零组边行为，不发版）；大小上限 mediaLimits+KIND_MAX_BYTES 单源可调 |
| 可回滚 | **做预案** | 零 DB 迁移零 schema，代码 revert 即回；Lightbox z-index 独立一行可单独回滚；picker 行交互变化无持久化状态残留 |
| 限流/熔断 | **不做** | 无新第三方依赖；上传/预览走既有通道既有超时 |
| 运维入口 | **不做** | 无运维面（预览失败/上传失败均用户侧自愈重试） |
| 告警阈值 | **不做** | 无新指标面 |
| 容量/性能 | **想过** | picker 懒加载 IO 门控+LRU（100 行/页现状）；50MB 视频 objectURL 会话级生命周期现状口径；组边量级微不足道——均零新增开发 |

## 变更记录

| 日期 | 变更 | 原因 |
|---|---|---|
| 2026-09-01 | 建立 plan（A/B/C/D 四 chunk，A1-A2/B1-B3/C1-C3/D；critique 后三处实现期细化：音频行布局/预检 MIME 判空/buildCopySet 签名不变） | 修复X 规格过审进入 P2 |
| 2026-09-01 | A 轮完成（97d6547f）：A1+A2 全绿（vitest 56/56+全量 999/999、tsc 0 错） | — |
| 2026-09-01 | B 轮完成（B1=9c0cf4c6、B2=5c7c6625；全量 1009/1009、tsc 0 错）。三处实现偏差：① B2 行拆出独立 `AssetPickerRow.vue`（plan 原列仅 AssetPicker.vue）——useLazyFilePreview 为组合式，v-for 行内无法每行一实例，须子组件承载（plan 坑点7「每行一个实例」即此义）；② 音/文缩略渲染为静态 div 非按钮——无灯箱语义不设可点击假按钮（a11y），键盘路径走「选择」button+图/视真按钮 Enter；③ 失败/未就绪回落统一类型字标（不显「失败」二字，与「无 fileId」同口径，AssetPickerMediaPreview 范式延续） | 组合式实例边界 + a11y 诚实交互 + 回落口径统一 |
| 2026-09-01 | C 轮完成（C1=85d9ffd1、C2=45723976；全量 1014/1014、tsc 0 错）。一处实现更正：C2 伪代码「CanvasView onCloneNode 零改动——getEdges() 已含组边」判断有误——`getEdges()` 实只返 v-model 普通边（CanvasBoard:1644-1646），组边在 `getGroupEdges()` 独立池；已改传 `[...getEdges(), ...getGroupEdges()]` 并同步 C1 cloneEdgesForDuplicate 注释 | plan 对既有 API 断言与源码不符；照抄则副本组边静默丢失 |

## 术语表

| 术语 | 大白话 | 案例 |
|---|---|---|
| 产物口径上传 | 传上去的文件就是节点「做出来的东西」，下游直接能用 | 上传 mp4 后可 @引用、可抽帧 |
| 预检 | 请求发出去前本地先拦一道（toast 拒），省得传完才报错 | 60MB 视频本地 toast「超 50MB」 |
| IO 门控 | 滚到看得见才去拉文件，看不见不拉 | 100 行资产列表只拉屏幕内十几张 |
| LRU 缓存 | 最近用的留着、太久的丢掉 | 关弹窗再开，刚看过的缩略图秒出 |
| display-directive='if' | 浮层关了内容就真卸载（不是藏起来） | 悬浮放大关掉，video 解码器随之释放 |
| 伪 id（组端点） | 组没有真节点 id，用 `group:xxx` 冒充端点让边连到组框 | 外部节点→`group:g1`=连 g1 组整体 |
| 组池/普通池 | 组边和普通边分两个集合存，渲染/对账各走各的 | 组边进 groupEdges 不进 VueFlow v-model |
| 单侧重映射 | 复制体只换自己这头的 id，对面不动 | 副本 A'→组 g：g 还是原组 |
| 平节点口径 | 副本/粘贴体永不自动入组，站组外当外部节点 | 复制组员，副本靠组边连回组 |
| 时点判定 | 行为按「动手那一刻」的状态算，不看来时 | 复制后关开关，粘贴按关算 |

<template>
  <!-- 滑块验证码（AJ-Captcha 前端封装）：拼图缺口拖动 + AES-ECB 加密轨迹 -->
  <div class="slider-captcha" :style="{ width: width + 'px' }">
    <!-- 验证码图片区 -->
    <div v-if="captchaLoaded" class="slider-captcha__images">
      <img class="slider-captcha__bg" :src="bgSrc" alt="验证码背景" draggable="false" />
      <img
        class="slider-captcha__piece"
        :src="pieceSrc"
        :style="{ left: pieceLeft + 'px' }"
        alt="滑块"
        draggable="false"
      />
      <span v-if="statusText" class="slider-captcha__tip" :class="{ 'is-error': status === 'fail' }">
        {{ statusText }}
      </span>
      <button
        type="button"
        class="slider-captcha__refresh"
        title="刷新验证码"
        @click="fetchCaptcha"
      >⟳</button>
    </div>
    <div v-else class="slider-captcha__loading">验证码加载中…</div>

    <!-- 滑动轨道 -->
    <div
      ref="trackRef"
      class="slider-captcha__track"
      :class="{
        'is-dragging': dragging,
        'is-success': status === 'success',
        'is-fail': status === 'fail'
      }"
    >
      <span class="slider-captcha__hint">{{ status === 'idle' ? '向右拖动滑块完成验证' : statusText }}</span>
      <div
        ref="btnRef"
        class="slider-captcha__btn"
        :style="{ left: btnLeft + 'px' }"
        @mousedown.prevent="onDragStart"
        @touchstart.prevent="onDragStart"
      >
        <span v-if="status === 'success'">✓</span>
        <span v-else-if="status === 'fail'">✕</span>
        <span v-else>→</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 滑块验证码组件（封装 AJ-Captcha 滑块前端）。
 *
 * 安全语义：
 * - 滑块结果前端不可信——后端发码时复验 captchaVerification（单次有效，防重放）
 * - 轨迹用 AES-ECB + secretKey 加密（AJ-Captcha 标准）
 *
 * 使用：父组件监听 @success 拿到 captchaVerification（即发码时的 captchaToken）
 */
import { ref, computed, onMounted } from 'vue'
import CryptoJS from 'crypto-js'
import { authApi, type CaptchaResult } from '@/api/auth'

const props = withDefaults(defineProps<{
  /** 验证码图片宽度（px），需与后端生成尺寸匹配（AJ-Captcha 默认 310） */
  width?: number
}>(), {
  width: 310
})

const emit = defineEmits<{
  /** 验证成功，回传 captchaVerification（加密轨迹串，用作发码的 captchaToken） */
  (e: 'success', captchaVerification: string): void
  /** 验证失败 */
  (e: 'fail', reason: string): void
  /** 内部状态变化（idle/loading/success/fail） */
  (e: 'statusChange', status: CaptchaStatus): void
}>()

type CaptchaStatus = 'idle' | 'loading' | 'success' | 'fail'

// AJ-Captcha 滑块尺寸常量（与后端 BlockPuzzleCaptchaServiceImpl 一致）
const BTN_WIDTH = 40 // 拖动按钮宽
// 后端原图宽（用于把屏幕拖动距离映射回原图坐标）
const ORIG_IMG_WIDTH = 310
// BLOCKPuzzle 后端只校验 x，y 发固定值即可
const FIXED_Y = 5

const trackRef = ref<HTMLDivElement | null>(null)
const btnRef = ref<HTMLDivElement | null>(null)

const captchaData = ref<CaptchaResult | null>(null)
const bgSrc = ref('')
const pieceSrc = ref('')
const captchaLoaded = ref(false)

const status = ref<CaptchaStatus>('idle')
const statusText = ref('')
const dragging = ref(false)

// 拖动状态
const btnLeft = ref(0) // 按钮位移（px）
const pieceLeft = ref(0) // 滑块图位移（px，与按钮联动）
let dragStartX = 0 // 鼠标按下时的屏幕 x
let startBtnLeft = 0 // 按下时按钮的 left

const maxBtnLeft = computed(() => props.width - BTN_WIDTH)

/** AES-ECB + PKCS7 加密（AJ-Captcha 标准算法）。 */
function aesEncrypt(plaintext: string, key: string): string {
  return CryptoJS.AES.encrypt(
    CryptoJS.enc.Utf8.parse(plaintext),
    CryptoJS.enc.Utf8.parse(key),
    { mode: CryptoJS.mode.ECB, padding: CryptoJS.pad.Pkcs7 }
  ).toString()
}

/** 加载滑块验证码图片 + secretKey。 */
async function fetchCaptcha() {
  status.value = 'loading'
  statusText.value = ''
  btnLeft.value = 0
  pieceLeft.value = 0
  captchaLoaded.value = false
  try {
    const res = await authApi.getCaptcha()
    const data = res.data.data
    captchaData.value = data
    // AJ-Captcha 1.3.0 CaptchaVO：originalImageBase64/jigsawImageBase64（裸 base64，需补 dataURL 前缀）
    bgSrc.value = data.originalImageBase64 ? `data:image/png;base64,${data.originalImageBase64}` : ''
    pieceSrc.value = data.jigsawImageBase64 ? `data:image/png;base64,${data.jigsawImageBase64}` : ''
    captchaLoaded.value = true
    status.value = 'idle'
  } catch (e) {
    status.value = 'fail'
    statusText.value = '验证码加载失败'
    emit('fail', '验证码加载失败')
  }
}

/** 开始拖动。 */
function onDragStart(e: MouseEvent | TouchEvent) {
  if (status.value === 'success') return
  dragging.value = true
  status.value = 'idle'
  statusText.value = ''
  const clientX = 'touches' in e ? e.touches[0].clientX : e.clientX
  dragStartX = clientX
  startBtnLeft = btnLeft.value

  document.addEventListener('mousemove', onDragMove)
  document.addEventListener('mouseup', onDragEnd)
  document.addEventListener('touchmove', onDragMove, { passive: false })
  document.addEventListener('touchend', onDragEnd)
}

/** 拖动中。 */
function onDragMove(e: MouseEvent | TouchEvent) {
  if (!dragging.value) return
  e.preventDefault()
  const clientX = 'touches' in e ? e.touches[0].clientX : e.clientX
  const delta = clientX - dragStartX
  let next = startBtnLeft + delta
  // 边界约束
  if (next < 0) next = 0
  if (next > maxBtnLeft.value) next = maxBtnLeft.value
  btnLeft.value = next
  // 滑块图与按钮联动（图左边缘 = 按钮左边缘，减去图本身的内边距使缺口对齐）
  pieceLeft.value = next
}

/** 拖动结束 → 加密轨迹 → emit。 */
function onDragEnd() {
  if (!dragging.value) return
  dragging.value = false
  document.removeEventListener('mousemove', onDragMove)
  document.removeEventListener('mouseup', onDragEnd)
  document.removeEventListener('touchmove', onDragMove)
  document.removeEventListener('touchend', onDragEnd)

  // 如果拖到最左没移动，不触发
  if (btnLeft.value < 5) return

  submitVerification()
}

/** 组装加密轨迹 → 后端一次校验（写二次 key）→ 自算 captchaVerification emit。 */
async function submitVerification() {
  const data = captchaData.value
  if (!data || !data.secretKey || !data.token) {
    status.value = 'fail'
    statusText.value = '验证码数据缺失，请刷新'
    emit('fail', '验证码数据缺失')
    return
  }

  // 把屏幕拖动距离映射回原图坐标
  const scale = ORIG_IMG_WIDTH / props.width
  const mappedX = Math.round(btnLeft.value * scale)
  // AJ-Captcha 1.3.0 标准：pointJson = AES({x,y}, secretKey)；y 固定 5（全高滑块条约定）
  const pointPlain = JSON.stringify({ x: mappedX, y: FIXED_Y })
  const pointJson = aesEncrypt(pointPlain, data.secretKey)

  try {
    // 一次校验（坐标核验）——通过后后端才写二次 key，二次复验（业务端点）只认该 key，不可绕过
    await authApi.checkCaptcha(data.token, pointJson)
  } catch {
    status.value = 'fail'
    statusText.value = '验证失败，请重试'
    emit('fail', '滑块坐标校验失败')
    // 坐标不符后旧 token 已被后端作废（check 无论成败都删一次 key），强制刷新
    fetchCaptcha()
    return
  }

  // captchaVerification = AES(token + '---' + point明文, secretKey)（与后端二次 key 同值）
  const captchaVerification = aesEncrypt(`${data.token}---${pointPlain}`, data.secretKey)

  status.value = 'success'
  statusText.value = '验证通过'
  emit('statusChange', 'success')
  emit('success', captchaVerification)
}

/** 重置（父组件外部调用）。 */
function reset() {
  status.value = 'idle'
  statusText.value = ''
  btnLeft.value = 0
  pieceLeft.value = 0
}

onMounted(fetchCaptcha)

defineExpose({ reset, fetchCaptcha })
</script>

<style lang="scss" scoped>
.slider-captcha {
  user-select: none;
  -webkit-user-select: none;

  &__images {
    position: relative;
    width: 100%;
    border-radius: 4px;
    overflow: hidden;
    border: 1px solid var(--color-border);
  }

  &__bg {
    display: block;
    width: 100%;
    height: auto;
  }

  &__piece {
    position: absolute;
    top: 0;
    height: 100%;
    width: auto;
    transition: none;
  }

  &__tip {
    position: absolute;
    bottom: 4px;
    left: 50%;
    transform: translateX(-50%);
    font-size: 12px;
    color: var(--color-primary);
    background: rgba(255, 255, 255, 0.85);
    padding: 2px 8px;
    border-radius: 2px;
    pointer-events: none;
    &.is-error { color: var(--color-danger, #d03050); }
  }

  &__refresh {
    position: absolute;
    top: 4px;
    right: 4px;
    width: 24px;
    height: 24px;
    border: none;
    border-radius: 50%;
    background: rgba(0, 0, 0, 0.3);
    color: #fff;
    cursor: pointer;
    font-size: 14px;
    line-height: 1;
    &:hover { background: rgba(0, 0, 0, 0.5); }
  }

  &__loading {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 160px;
    color: var(--color-text-secondary);
    border: 1px solid var(--color-border);
    border-radius: 4px;
  }

  &__track {
    position: relative;
    height: 40px;
    margin-top: 8px;
    background: var(--color-fill, #f3f3f3);
    border: 1px solid var(--color-border);
    border-radius: 4px;
    display: flex;
    align-items: center;
    justify-content: center;
    transition: border-color 0.2s;

    &.is-dragging { border-color: var(--color-primary); }
    &.is-success { border-color: #18a058; background: rgba(24, 160, 88, 0.1); }
    &.is-fail { border-color: #d03050; background: rgba(208, 48, 80, 0.1); }
  }

  &__hint {
    font-size: 12px;
    color: var(--color-text-tertiary);
    pointer-events: none;
  }

  &__btn {
    position: absolute;
    left: 0;
    top: 0;
    width: 40px;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    background: #fff;
    border: 1px solid var(--color-border);
    border-radius: 4px;
    cursor: grab;
    font-size: 16px;
    box-shadow: 0 0 4px rgba(0, 0, 0, 0.1);
    transition: box-shadow 0.2s;

    &:active { cursor: grabbing; }
  }
}
</style>

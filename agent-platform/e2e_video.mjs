// 真E2E：提交混合 role（首帧+尾帧+参考图）视频任务 → 轮询至终态
import request from 'fs' // placeholder, use fetch below
const BASE = 'http://localhost:8080/api'

async function main() {
  // 1) login
  const lr = await fetch(`${BASE}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' })
  })
  const lj = await lr.json()
  const token = lj.data.accessToken
  console.log('[login] ok, user=', lj.data.accessToken ? 'admin' : '?')

  const auth = { Authorization: `Bearer ${token}` }

  // 2) 抽帧 images from asset library (project 6)
  const firstFileId = '38060387-742f-4508-882a-88a3fcf761c3.jpg' // 抽帧(AT 1s)
  const lastFileId = '93fcfda1-ec68-4f25-9b16-537d9fc2228f.jpg'  // 抽帧(LAST)
  const refFileId = null // last+ref mutually exclusive (ctaigw rejects)

  // 3) submit mixed-role video
  const body = {
    prompt: '真E2E首尾帧测试：首帧为开头画面，尾帧为结尾画面。镜头缓慢推进，过渡自然。',
    ratio: '16:9',
    duration: 5,
    resolution: '720p',
    watermark: false,
    generateAudio: false,
    attachments: [
      { fileId: firstFileId, kind: 'image', frameRole: 'first_frame' },
      { fileId: lastFileId, kind: 'image', frameRole: 'last_frame' }
    ]
  }
  console.log('[submit] first=', firstFileId, 'last=', lastFileId, 'ref=', refFileId)
  const sr = await fetch(`${BASE}/media/video`, {
    method: 'POST',
    headers: { ...auth, 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  })
  const sj = await sr.json()
  console.log('[submit] resp code=', sj.code, 'msg=', sj.message)
  if (sj.code !== 200) {
    console.log('[FAIL] submit rejected:', JSON.stringify(sj).slice(0, 400))
    return
  }
  const taskId = sj.data.id
  console.log('[submit] task id=', taskId, 'status=', sj.data.status)

  // 4) poll
  const t0 = Date.now()
  let status = sj.data.status
  while (!['SUCCEEDED', 'FAILED', 'DOWNLOAD_FAILED'].includes(status)) {
    await new Promise(r => setTimeout(r, 6000))
    const tr = await fetch(`${BASE}/media/tasks/${taskId}`, { headers: auth })
    const tj = await tr.json()
    status = tj.data?.status
    const elapsed = Math.round((Date.now() - t0) / 1000)
    console.log(`[poll ${elapsed}s] ${status} flag=${tj.data?.statusFlag ?? ''} ${tj.data?.errorMsg ?? ''}`)
    if (elapsed > 600) { console.log('[TIMEOUT] 10min'); return }
  }

  // 5) final
  const fr = await fetch(`${BASE}/media/tasks/${taskId}`, { headers: auth })
  const fj = await fr.json()
  console.log('[FINAL] status=', fj.data.status)
  console.log('  taskType=', fj.data.taskType, 'model=', fj.data.model)
  console.log('  tokensCost=', fj.data.tokensCost, 'statusFlag=', fj.data.statusFlag)
  console.log('  videoUrl=', fj.data.videoUrl)
  console.log('  resultFileId=', fj.data.resultFileId)
  if (fj.data.errorMsg) console.log('  errorMsg=', fj.data.errorMsg)
  console.log('E2E_DONE status=' + fj.data.status)
}
main().catch(e => { console.error('ERR', e); process.exit(1) })

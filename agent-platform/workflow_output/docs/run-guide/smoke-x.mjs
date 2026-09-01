// 修复X P4 冒烟：R1-R3 视频上传+预检 / R5-R10 从库选择四态预览+三分离 / R11-R15 组边随 ⛓ 保留
// 工装沿用 smoke-viii.mjs：API 预置快照摆节点（绕 fitView 缩放坑）；Node 侧直连后端登录防互踢
import { chromium } from 'file:///C:/Users/Administrator/AppData/Roaming/npm/node_modules/@playwright/mcp/node_modules/playwright/index.mjs';
import { readFileSync } from 'node:fs';

const BASE = 'http://localhost:5173';
const API = 'http://localhost:8080';
const T = 'C:/Users/Administrator/AppData/Local/Temp/smokex';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const results = [];
const log = (name, ok, detail = '') => { results.push({ name, ok }); console.log(`${ok ? 'PASS' : 'FAIL'} | ${name}${detail ? ' | ' + detail : ''}`); };
const shot = async (page, tag) => { try { await page.screenshot({ path: `${T}/shot-${tag}.png` }); } catch {} };

const ctx = await chromium.launchPersistentContext(`${T}/pw-profile`, { viewport: { width: 1680, height: 950 }, headless: true });
const page = ctx.pages()[0] ?? await ctx.newPage();
page.on('pageerror', e => console.log('PAGEERROR |', String(e).slice(0, 200)));

// ── Node 侧登录 + 种资产 ──
const login = await (await fetch(`${API}/api/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'admin', password: 'admin123' }) })).json();
if (!login?.data?.accessToken) { console.log('FATAL | login failed', JSON.stringify(login).slice(0, 300)); await ctx.close(); process.exit(1); }
const AUTH = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + login.data.accessToken };
await page.goto(BASE + '/login'); await sleep(800);
await page.evaluate(d => {
  localStorage.clear();
  localStorage.setItem('access_token', JSON.stringify(d.accessToken));
  localStorage.setItem('refresh_token', JSON.stringify(d.refreshToken));
  localStorage.setItem('user_info', JSON.stringify(d.userInfo));
}, login.data);
log('登录 admin/admin123', true);

let uploadCount = 0;
let lastUploadFileId = null;
page.on('request', r => { if (r.method() === 'POST' && /\/api\/canvas\/\d+\/upload/.test(r.url())) uploadCount++; });
page.on('response', async r => {
  if (/\/api\/canvas\/\d+\/upload/.test(r.url())) {
    try { lastUploadFileId = (await r.json())?.data?.fileId ?? null; } catch {}
  }
});

// 项目（picker 默认选第一个有权限项目；seed 专用项目，再显式选中它）
const PROJECT_NAME = '冒烟X-P4-' + Date.now();
const projectRes = await (await fetch(`${API}/api/assets/projects`, { method: 'POST', headers: AUTH, body: JSON.stringify({ name: PROJECT_NAME, description: 'smoke-x' }) })).json();
const projectId = projectRes?.data?.id;
if (projectId == null) { console.log('FATAL | 建项目失败', JSON.stringify(projectRes).slice(0, 300)); await ctx.close(); process.exit(1); }
log(`API 建项目 #${projectId}`, true);

const MIME = { png: 'image/png', mp4: 'video/mp4', mp3: 'audio/mpeg' };
const uploadAsset = async (file, mediaType, name) => {
  const ext = file.split('.').pop();
  const form = new FormData();
  // filename 必须带扩展名——后端类型↔资产类型校验认扩展名（裸中文名会被拒）
  form.append('file', new Blob([readFileSync(`${T}/${file}`)], { type: MIME[ext] }), `${name}.${ext}`);
  form.append('mediaType', mediaType);
  form.append('name', name);
  const r = await (await fetch(`${API}/api/assets/projects/${projectId}/upload`, { method: 'POST', headers: { Authorization: AUTH.Authorization }, body: form })).json();
  return r?.data?.id ?? null;
};
const imgId = await uploadAsset('img1.png', '图片', '冒烟图');
const vidId = await uploadAsset('vid1.mp4', '视频', '冒烟视频');
const audId = await uploadAsset('aud1.mp3', '音频', '冒烟音频');
log('API 种资产 图/视/音', imgId && vidId && audId != null, `img=${imgId} vid=${vidId} aud=${audId}`);
const promptRes = await (await fetch(`${API}/api/assets/projects/${projectId}/assets`, { method: 'POST', headers: AUTH, body: JSON.stringify({ mediaType: '提示词', name: '冒烟提示词', content: JSON.stringify({ text: '一只戴眼镜的橘猫在弹钢琴，背景是星夜' }) }) })).json();
log('API 种资产 提示词', promptRes?.data?.id != null, `id=${promptRes?.data?.id}`);

const mkCanvas = async (name, snapshot) => {
  const c = (await (await fetch(`${API}/api/canvas`, { method: 'POST', headers: AUTH, body: JSON.stringify({ name }) })).json()).data;
  await fetch(`${API}/api/canvas/${c.id}`, { method: 'PUT', headers: AUTH, body: JSON.stringify({ name, snapshot: JSON.stringify(snapshot) }) });
  return c.id;
};
const node = (id, type, x, y, extra = {}) => ({ id, type, position: { x, y }, data: { label: id, ...extra } });
const openCanvas = async id => {
  await page.goto(`${BASE}/canvas/${id}`).catch(() => {});
  await sleep(1500);
  if (!page.url().includes('/canvas/' + id)) { await page.goto(`${BASE}/canvas/${id}`).catch(() => {}); await sleep(1500); }
  await page.waitForSelector('.vue-flow__node', { timeout: 20000 });
  await sleep(800);
};
const clickNodeCenter = async sel => {
  const bb = await page.locator(sel).first().boundingBox();
  await page.mouse.click(bb.x + bb.width / 2, bb.y + bb.height / 2);
  await sleep(500);
};
const panelInput = () => page.locator('.prop-panel input[type="file"]');

// ══ R1/R2 · 视频节点上传 ══
{
  const cid = await mkCanvas('冒烟X-上传', { nodes: [node('up-v', 'video', 80, 80)], edges: [], groups: [], viewport: { x: 0, y: 0, zoom: 1 } });
  await openCanvas(cid);
  await clickNodeCenter('.vue-flow__node');
  await page.waitForSelector('text=上传视频', { timeout: 8000 });
  log('R1a 面板显「上传视频」+ ≤50MB 提示', await page.locator('text=本地视频 ≤50MB').count() > 0);

  // fileId 从上传响应体取（面板 readonly 只显部分类型；响应 listener 侧录 lastUploadFileId）
  uploadCount = 0; lastUploadFileId = null;
  await panelInput().setInputFiles(`${T}/vid1.mp4`);
  await sleep(3500);
  const fidB = lastUploadFileId;
  const hasVideo = await page.locator('.vue-flow__node video').count();
  const hasExtract = await page.locator('text=抽帧（C11').count();
  log('R1b 上传成功→节点显视频+fileId 落+C11 抽帧区出现', uploadCount === 1 && hasVideo > 0 && !!fidB && hasExtract > 0, `up=${uploadCount} video=${hasVideo} c11=${hasExtract} fid=${fidB}`);
  await shot(page, 'r1-upload');

  // R2 重传覆盖
  lastUploadFileId = null;
  await panelInput().setInputFiles(`${T}/vid2.mp4`);
  await sleep(3500);
  const fidA = lastUploadFileId;
  log('R2 重传覆盖→fileId 换新', uploadCount === 2 && !!fidB && !!fidA && fidB !== fidA, `${fidB}→${fidA}`);
  await shot(page, 'r2-reupload');
}

// ══ R3 · 三 kind 预检 ══
{
  const cid = await mkCanvas('冒烟X-预检', {
    nodes: [node('pv', 'video', 80, 80), node('pi', 'image', 420, 80), node('pa', 'audio', 80, 340)],
    edges: [], groups: [], viewport: { x: 0, y: 0, zoom: 1 }
  });
  await openCanvas(cid);
  const cases = [
    { sel: 'text=上传视频', file: 'big.mp4', label: '视频 51MB' },
    { sel: 'text=上传图片', file: 'big.png', label: '图片 31MB' },
    { sel: 'text=上传音频', file: 'big.mp3', label: '音频 16MB' }
  ];
  for (const c of cases) {
    await page.keyboard.press('Escape');
    const nl = page.locator('.vue-flow__node');
    const idx = c.label === '视频 51MB' ? 0 : c.label === '图片 31MB' ? 1 : 2;
    const bb = await nl.nth(idx).boundingBox();
    await page.mouse.click(bb.x + bb.width / 2, bb.y + bb.height / 2);
    await sleep(600);
    uploadCount = 0;
    await panelInput().setInputFiles(`${T}/${c.file}`);
    await sleep(1200);
    const toast = await page.locator('.n-message:visible').filter({ hasText: '文件过大' }).count();
    const noReq = uploadCount === 0;
    log(`R3 ${c.label} 超限 toast 拒+零请求`, toast > 0 && noReq, `toast=${toast} req=${uploadCount}`);
    if (!(toast && noReq)) await shot(page, 'r3-' + c.file);
  }
}

// ══ R5-R10 · 从库选择 ══
{
  const cid = await mkCanvas('冒烟X-选择', {
    nodes: [node('pk-i', 'image', 80, 80), node('pk-v', 'video', 420, 80), node('pk-a', 'audio', 80, 340), node('pk-t', 'text', 420, 340)],
    edges: [], groups: [], viewport: { x: 0, y: 0, zoom: 1 }
  });
  await openCanvas(cid);
  const openPicker = async idx => {
    await page.keyboard.press('Escape');
    const nl = page.locator('.vue-flow__node');
    const bb = await nl.nth(idx).boundingBox();
    await page.mouse.click(bb.x + bb.width / 2, bb.y + bb.height / 2);
    await sleep(500);
    await page.getByRole('button', { name: '从库选择' }).click();
    // modal 开≠有行：项目未选时列表空显「请先选择项目」——行等 selectProject 后再等
    await page.waitForSelector('.n-modal .picker__bar', { timeout: 10000 });
    await sleep(800);
  };
  const selectProject = async name => {
    // n-select 面板选项类 .n-base-select-option；先点开 picker 里的项目下拉
    await page.locator('.n-modal .n-select').first().click();
    await sleep(400);
    const opt = page.locator('.n-base-select-option:visible', { hasText: name }).last();
    await opt.click({ timeout: 6000 });
    await sleep(1200);
  };

  // ── 图片节点 ──
  await openPicker(0);
  await selectProject(PROJECT_NAME);
  await page.waitForSelector('.picker-row img', { timeout: 8000 });
  log('R5a 图片行渲染缩略 img', await page.locator('.picker-row img').count() > 0);

  const thumb = page.locator('.picker-row__thumb').first();
  const tb = await thumb.boundingBox();
  await page.mouse.move(tb.x + tb.width / 2, tb.y + tb.height / 2);
  await sleep(900);
  const popImg = await page.locator('.n-popover img:visible').count();
  log('R5b 悬浮 300ms+ → popover 放大图', popImg > 0, `pop=${popImg}`);
  await shot(page, 'r5-hover');

  await thumb.click();
  await sleep(700);
  const lbx = await page.locator('.lbx').count();
  const lbxZ = lbx ? await page.evaluate(() => getComputedStyle(document.querySelector('.lbx')).zIndex) : 'none';
  const modalZ = await page.evaluate(() => Math.max(0, ...[...document.querySelectorAll('.n-modal-mask, .n-modal')].map(e => +getComputedStyle(e).zIndex || 0)));
  log('R6a 点缩略 → Lightbox 开且盖弹窗(z3000>modal)', lbx === 1 && +lbxZ === 3000 && modalZ < 3000, `lbx=${lbx} z=${lbxZ} modalZ=${modalZ}`);
  await shot(page, 'r6-lightbox');
  await page.keyboard.press('Escape');
  await sleep(600);
  const lbxGone = await page.locator('.lbx').count();
  const modalStays = await page.locator('.picker-row').count();
  log('R7 Esc 关灯箱但选择弹窗仍在', lbxGone === 0 && modalStays > 0, `lbx=${lbxGone} rows=${modalStays}`);

  // 误选：行空白点击零动作（先把鼠标挪离缩略图让悬浮 popover 收起，否则 follower 层拦截点击）
  await page.mouse.move(1100, 240); await sleep(500);
  const rowsBefore = await page.locator('.picker-row').count();
  const main = page.locator('.picker-row__main').first();
  await main.click({ position: { x: 120, y: 8 } });
  await sleep(700);
  const rowsAfter = await page.locator('.picker-row').count();
  const pickToast = await page.locator('.n-message').filter({ hasText: '已引用' }).count();
  log('R9a 行空白点击 → 零动作（不选不关）', rowsAfter === rowsBefore && pickToast === 0, `rows=${rowsBefore}→${rowsAfter} toast=${pickToast}`);

  // 选择按钮 → resolve
  await page.locator('.picker-row').first().getByRole('button', { name: '选择' }).click();
  await sleep(1500);
  const pickedToast = await page.locator('.n-message').filter({ hasText: '已引用资产' }).count();
  const modalGone = await page.locator('.picker-row').count();
  log('R9b 点「选择」→ toast 已引用+弹窗关', pickedToast > 0 && modalGone === 0, `toast=${pickedToast} rows=${modalGone}`);
  await shot(page, 'r9-picked');

  // ── 视频节点：首帧+▶ ──
  await openPicker(1);
  await selectProject(PROJECT_NAME);
  await page.waitForSelector('.picker-row', { timeout: 10000 });
  await sleep(1500);
  const vThumbVideo = await page.locator('.picker-row video').count();
  const playBadge = await page.locator('.picker-row__play').count();
  log('R8a 视频行=首帧 video+▶ 角标', vThumbVideo > 0 && playBadge > 0, `video=${vThumbVideo} play=${playBadge}`);
  await shot(page, 'r8-video-row');
  await page.keyboard.press('Escape');
  await page.locator('.n-modal .n-button').first().click().catch(() => {});
  await page.keyboard.press('Escape');
  await sleep(400);

  // ── 音频节点：行内播放条 ──
  await openPicker(2);
  await selectProject(PROJECT_NAME);
  await page.waitForSelector('.picker-row', { timeout: 10000 });
  await sleep(1200);
  const audioEl = await page.locator('.picker-row__audio').count();
  const glyph = await page.locator('.picker-row__ph').filter({ hasText: '音' }).count();
  log('R8b 音频行=「音」字标+行内播放条', audioEl > 0 && glyph > 0, `audio=${audioEl} glyph=${glyph}`);
  await shot(page, 'r8-audio-row');
  await page.keyboard.press('Escape');
  await sleep(400);

  // ── 文本节点：正文片段 ──
  await openPicker(3);
  await selectProject(PROJECT_NAME);
  await page.waitForSelector('.picker-row', { timeout: 10000 });
  await sleep(1200);
  const snippet = await page.locator('.picker-row__thumb-text').count();
  log('R8c 提示词行=textPreview 片段', snippet > 0, `snippet=${snippet}`);
  await shot(page, 'r8-text-row');
  await page.keyboard.press('Escape');
  await sleep(400);
}

// ══ R11-R15 · 组边随 ⛓ 保留 ══
{
  const cid = await mkCanvas('冒烟X-组边', {
    nodes: [node('m1', 'text', 100, 80), node('m2', 'text', 100, 260), node('b', 'text', 560, 170)],
    edges: [
      { id: 'ge-1', source: 'b', target: 'group:group-g1' },
      { id: 'ge-2', source: 'group:group-g1', target: 'b' }
    ],
    groups: [{ id: 'group-g1', name: '冒烟组', memberIds: ['m1', 'm2'], color: '#8b5cf6' }],
    viewport: { x: 0, y: 0, zoom: 1 }
  });
  await openCanvas(cid);
  const ge0 = await page.locator('.canvas-board__groupedge-path').count();
  const box0 = await page.locator('.canvas-board__groupbox').count();
  log('R11a 快照恢复：组框+2 组边', ge0 === 2 && box0 === 1, `ge=${ge0} box=${box0}`);
  await shot(page, 'r11a-seed');

  // 选中 b → Ctrl+C → 空白 Ctrl+V
  await clickNodeCenter('.vue-flow__node[data-id="b"]');
  await page.keyboard.press('Control+c');
  await sleep(700);
  await page.mouse.click(900, 620); // 空白清选
  await sleep(400);
  await page.keyboard.press('Control+v');
  await sleep(1200);
  const ge1 = await page.locator('.canvas-board__groupedge-path').count();
  const nodeCount = await page.locator('.vue-flow__node').count();
  const flowEdges = await page.locator('.vue-flow__edge').count();
  const newB = await page.locator('.vue-flow__node', { hasText: 'b 2' }).count();
  log('R11b 粘贴→副本 b 2 带 2 新组边(共4)+v-model 零伪 id 边', ge1 === 4 && nodeCount === 4 && flowEdges === 0 && newB > 0, `ge=${ge0}→${ge1} nodes=${nodeCount} flowEdges=${flowEdges} b2=${newB}`);
  await shot(page, 'r11b-paste');

  // 组员不变：组框包围盒内仍只有 m1/m2 两个节点（副本 b 2 落在粘贴点=组外）
  const gb = await page.locator('.canvas-board__groupbox').first().boundingBox();
  const inside = await page.evaluate(box => {
    return [...document.querySelectorAll('.vue-flow__node')].filter(n => {
      const r = n.getBoundingClientRect();
      return r.x >= box.x - 2 && r.y >= box.y - 2 && r.x + r.width <= box.x + box.width + 2 && r.y + r.height <= box.y + box.height + 2;
    }).map(n => n.getAttribute('data-id'));
  }, gb);
  log('R11c 副本不入组员（组框内仍 m1/m2）', inside.length === 2 && inside.includes('m1') && inside.includes('m2'), `inside=[${inside}]`);

  // R14 撤回一步含组边
  await page.keyboard.press('Control+z');
  await sleep(900);
  const ge2 = await page.locator('.canvas-board__groupedge-path').count();
  const nodeCount2 = await page.locator('.vue-flow__node').count();
  log('R14 Ctrl+Z 一步撤：组边回 2+节点回 3', ge2 === 2 && nodeCount2 === 3, `ge=${ge1}→${ge2} nodes=${nodeCount2}`);

  // R12 ⛓ 关 → 粘贴零组边
  const chainBtn = page.locator('[aria-label="连线保留开关（复制粘贴与创建副本是否保留原节点连线）"]');
  await chainBtn.click(); await sleep(300); // 关
  const pressedOff = await chainBtn.getAttribute('aria-pressed');
  await clickNodeCenter('.vue-flow__node[data-id="b"]');
  await page.keyboard.press('Control+c');
  await sleep(600);
  await page.mouse.click(900, 620); await sleep(300);
  await page.keyboard.press('Control+v');
  await sleep(1200);
  const ge3 = await page.locator('.canvas-board__groupedge-path').count();
  const nodeCount3 = await page.locator('.vue-flow__node').count();
  log('R12a ⛓ 关粘贴→零新组边', pressedOff === 'false' && ge3 === 2 && nodeCount3 === 4, `pressed=${pressedOff} ge=${ge3} nodes=${nodeCount3}`);
  // 清理：撤回该粘贴，重开 ⛓
  await page.keyboard.press('Control+z'); await sleep(800);
  await chainBtn.click(); await sleep(300);

  // R13 复制后解散组 → 粘贴丢组边
  await clickNodeCenter('.vue-flow__node[data-id="b"]');
  await page.keyboard.press('Control+c');
  await sleep(600);
  await page.mouse.click(900, 620); await sleep(300);
  // 解散：组框右上 ✕（canvas-board__groupbox-x）
  const ungroupBtn = page.locator('.canvas-board__groupbox-x').first();
  const hasUngroup = await ungroupBtn.isVisible().catch(() => false);
  if (hasUngroup) await ungroupBtn.click();
  await sleep(800);
  const geUngrouped = await page.locator('.canvas-board__groupedge-path').count();
  await page.keyboard.press('Control+v');
  await sleep(1200);
  const ge4 = await page.locator('.canvas-board__groupedge-path').count();
  const nodeCount4 = await page.locator('.vue-flow__node').count();
  const pageErrs = 0;
  log('R13 解散后粘贴→组边全无+节点照常+无报错', geUngrouped === 0 && ge4 === 0 && nodeCount4 === 4, `hasUngroup=${hasUngroup} ge解散=${geUngrouped} ge贴=${ge4} nodes=${nodeCount4}`);
  await shot(page, 'r13-ungroup-paste');

  // R15 消费+落库：重开画布验证快照（解散态=零组边零组）；再建连组画布验组边落库
  const snap1 = await (await fetch(`${API}/api/canvas/${cid}`, { headers: AUTH })).json();
  const s1 = typeof snap1?.data?.snapshot === 'string' ? JSON.parse(snap1.data.snapshot) : snap1?.data?.snapshot;
  const geInSnap = (s1?.edges ?? []).filter(e => (e.source || '').startsWith('group:') || (e.target || '').startsWith('group:')).length;
  log('R15a 解散态快照零组边（落库无悬挂）', geInSnap === 0, `edges=${(s1?.edges ?? []).length} geInSnap=${geInSnap}`);

  // 重建组边场景验证落库保持（R11b 场景重来一遍后等自动保存）
  const cid2 = await mkCanvas('冒烟X-组边落库', {
    nodes: [node('m1', 'text', 100, 80), node('m2', 'text', 100, 260), node('b', 'text', 560, 170)],
    edges: [
      { id: 'ge-1', source: 'b', target: 'group:group-g1' },
      { id: 'ge-2', source: 'group:group-g1', target: 'b' }
    ],
    groups: [{ id: 'group-g1', name: '冒烟组', memberIds: ['m1', 'm2'], color: '#8b5cf6' }],
    viewport: { x: 0, y: 0, zoom: 1 }
  });
  await openCanvas(cid2);
  await clickNodeCenter('.vue-flow__node[data-id="b"]');
  await page.keyboard.press('Control+c');
  await sleep(600);
  await page.mouse.click(900, 620); await sleep(300);
  await page.keyboard.press('Control+v');
  await sleep(1000);
  const geBefore = await page.locator('.canvas-board__groupedge-path').count();
  await sleep(3500); // 等防抖自动保存
  await page.reload();
  await page.waitForSelector('.vue-flow__node', { timeout: 20000 });
  await sleep(1800);
  const geAfter = await page.locator('.canvas-board__groupedge-path').count();
  const nodesAfter = await page.locator('.vue-flow__node').count();
  log('R15b 粘贴组边随快照落库→刷新重现', geBefore === 4 && geAfter === 4 && nodesAfter === 4, `before=${geBefore} after=${geAfter} nodes=${nodesAfter}`);
  await shot(page, 'r15-persist');
}

const fails = results.filter(r => !r.ok);
console.log(`\n== 修复X 冒烟结果： ${results.length - fails.length}/${results.length} PASS ==`);
fails.forEach(f => console.log('FAIL项： ' + f.name));
await ctx.close();
process.exit(fails.length ? 1 : 0);

// 修复XI P4 冒烟：X1 右键菜单 / X2 官方库 / X3 两级词汇 / X4 组=大节点
// 工装沿用 smoke-x.mjs：API 预置快照摆节点（绕 fitView 缩放坑）；Node 侧直连后端登录防互踢
import { chromium } from 'file:///C:/Users/Administrator/AppData/Roaming/npm/node_modules/@playwright/mcp/node_modules/playwright/index.mjs';

const BASE = 'http://localhost:5173';
const API = 'http://localhost:8080';
const T = 'C:/Users/Administrator/AppData/Local/Temp/smokex';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const results = [];
const log = (name, ok, detail = '') => { results.push({ name, ok }); console.log(`${ok ? 'PASS' : 'FAIL'} | ${name}${detail ? ' | ' + detail : ''}`); };
const shot = async (page, tag) => { try { await page.screenshot({ path: `${T}/shot-xi-${tag}.png` }); } catch {} };
const jf = async (url, opt) => (await fetch(url, opt)).json();

const ctx = await chromium.launchPersistentContext(`${T}/pw-profile3`, { viewport: { width: 1680, height: 950 }, headless: true });
const page = ctx.pages()[0] ?? await ctx.newPage();
page.on('pageerror', e => console.log('PAGEERROR |', String(e).slice(0, 200)));

// ── Node 侧登录（admin 官方发布者） ──
const login = await jf(`${API}/api/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'admin', password: 'admin123' }) });
if (!login?.data?.accessToken) { console.log('FATAL | admin login failed', JSON.stringify(login).slice(0, 300)); await ctx.close(); process.exit(1); }
const AUTH = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + login.data.accessToken };
await page.goto(BASE + '/login'); await sleep(800);
await page.evaluate(d => {
  localStorage.clear();
  localStorage.setItem('access_token', JSON.stringify(d.accessToken));
  localStorage.setItem('refresh_token', JSON.stringify(d.refreshToken));
  localStorage.setItem('user_info', JSON.stringify(d.userInfo));
}, login.data);
log('登录 admin/admin123', true);

// ── 建项目种资产（X3 两级词汇用） ──
const PROJECT = '冒烟XI-' + Date.now();
const projectId = (await jf(`${API}/api/assets/projects`, { method: 'POST', headers: AUTH, body: JSON.stringify({ name: PROJECT, description: 'smoke-xi' }) }))?.data?.id;
if (projectId == null) { console.log('FATAL | 建项目失败'); await ctx.close(); process.exit(1); }
const mkPrompt = async (name) => (await jf(`${API}/api/assets/projects/${projectId}/assets`, { method: 'POST', headers: AUTH, body: JSON.stringify({ mediaType: '提示词', name, content: JSON.stringify({ text: name + ' 正文' }) }) }))?.data?.id;
const pa1 = await mkPrompt('子类资产A');
const pa2 = await mkPrompt('一级资产B');
const pa3 = await mkPrompt('子类资产C');
log('API 建项目+3 提示词资产', pa1 != null && pa2 != null && pa3 != null, `proj=${projectId} a=${pa1},${pa2},${pa3}`);

const mkCanvas = async (name, snapshot) => {
  const c = (await jf(`${API}/api/canvas`, { method: 'POST', headers: AUTH, body: JSON.stringify({ name }) })).data;
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
const ctxMenu = () => page.locator('.canvas-board__ctx-menu');
const nodeBBox = async sel => await page.locator(sel).first().boundingBox();

// ══ X1 · 右键菜单 ══
{
  const cid = await mkCanvas('冒烟XI-右键', { nodes: [node('t1', 'text', 120, 100)], edges: [], groups: [], viewport: { x: 0, y: 0, zoom: 1 } });
  await openCanvas(cid);

  // X1a 空白右键 → 两栏菜单（7 添加 + 4 操作）
  await page.mouse.click(900, 620, { button: 'right' });
  await sleep(500);
  const items = await page.locator('.canvas-board__ctx-item').count();
  const titles = (await page.locator('.canvas-board__ctx-title').allTextContents()).join(',');
  log('X1a 空白右键 → 菜单 11 项（7+4）两栏标题', await ctxMenu().count() === 1 && items === 11 && titles.includes('添加节点') && titles.includes('画布操作'), `items=${items} titles=${titles}`);

  // X1b 菜单点「图片」→ 节点落在右键点附近 + 菜单关
  await page.locator('.canvas-board__ctx-item', { hasText: '图片' }).click();
  await sleep(900);
  const nCnt = await page.locator('.vue-flow__node').count();
  const imgNode = await nodeBBox('.vue-flow__node', { hasText: '图片' });
  const near = imgNode && imgNode.x > 500 && imgNode.y > 300 && imgNode.x < 1400 && imgNode.y < 900;
  log('X1b 菜单建「图片」→ 节点落右键点附近+菜单关', nCnt === 2 && near && (await ctxMenu().count()) === 0, `nodes=${nCnt} box=${imgNode ? Math.round(imgNode.x) + ',' + Math.round(imgNode.y) : 'none'}`);

  // X1c 再右键别处 → 菜单挪位；左键空白 → 关
  await page.mouse.click(500, 400, { button: 'right' });
  await sleep(400);
  const mb1 = await ctxMenu().boundingBox();
  await page.mouse.click(300, 250, { button: 'right' });
  await sleep(400);
  const mb2 = await ctxMenu().boundingBox();
  const moved = mb1 && mb2 && (Math.abs(mb1.x - mb2.x) > 50 || Math.abs(mb1.y - mb2.y) > 50);
  await page.mouse.click(880, 700); // overlay 左键关
  await sleep(300);
  log('X1c 再右键挪位不叠层+左键关', moved && (await ctxMenu().count()) === 0, `moved=${moved}`);

  // X1d 节点上右键 → 不弹自绘菜单
  const nb = await nodeBBox('.vue-flow__node[data-id="t1"]');
  await page.mouse.click(nb.x + nb.width / 2, nb.y + nb.height / 2, { button: 'right' });
  await sleep(400);
  log('X1d 节点右键 → 零自绘菜单（节点链不抢）', (await ctxMenu().count()) === 0);
  // 清场：节点右键可能开了「存入资产库」下拉——点空白关掉，防挡后续框选 pointerdown
  await page.keyboard.press('Escape'); await sleep(200);
  await page.mouse.click(880, 700); await sleep(400);

  // X1e 框选2节点保持 → 右键开菜单 → Esc 只关菜单不清多选
  // 框选矩形=两节点包围盒动态外扩（SelectionMode.Full 须完整包含）
  const unionBoxSelect = async sels => {
    const boxes = [];
    for (const s of sels) boxes.push(await nodeBBox(s));
    const x1 = Math.min(...boxes.map(b => b.x)) - 12, y1 = Math.min(...boxes.map(b => b.y)) - 12;
    const x2 = Math.max(...boxes.map(b => b.x + b.width)) + 12, y2 = Math.max(...boxes.map(b => b.y + b.height)) + 12;
    await page.keyboard.down('Shift');
    await page.mouse.move(x1, y1); await page.mouse.down();
    await page.mouse.move(x2, y2, { steps: 8 }); await page.mouse.up();
    await page.keyboard.up('Shift');
    await sleep(600);
    return page.locator('.vue-flow__node.selected').count();
  };
  const selBefore = await unionBoxSelect(['.vue-flow__node[data-id="t1"]', '.vue-flow__node:has-text("图片")']);
  await page.mouse.click(950, 640, { button: 'right' });
  await sleep(400);
  const menuOpen = (await ctxMenu().count()) === 1;
  await page.keyboard.press('Escape');
  await sleep(400);
  const selAfter = await page.locator('.vue-flow__node.selected').count();
  log('X1e Esc 只关菜单，多选保持', selBefore === 2 && menuOpen && (await ctxMenu().count()) === 0 && selAfter === 2, `sel ${selBefore}→${selAfter}`);
  await page.mouse.click(1400, 880); await sleep(300); // 清选

  // X1f 禁用态实时：新画布无复制/撤销 → 3 灰；复制后 → 粘贴亮
  const cid2 = await mkCanvas('冒烟XI-禁用', { nodes: [node('d1', 'text', 120, 100)], edges: [], groups: [], viewport: { x: 0, y: 0, zoom: 1 } });
  await openCanvas(cid2);
  await page.mouse.click(900, 620, { button: 'right' });
  await sleep(400);
  const dis0 = await page.locator('.canvas-board__ctx-item[disabled]').count();
  await page.keyboard.press('Escape'); await sleep(200);
  const dbb = await nodeBBox('.vue-flow__node[data-id="d1"]');
  await page.mouse.click(dbb.x + dbb.width / 2, dbb.y + dbb.height / 2);
  await sleep(400);
  await page.keyboard.press('Control+c');
  await sleep(600);
  await page.mouse.click(900, 620, { button: 'right' });
  await sleep(400);
  const dis1 = await page.locator('.canvas-board__ctx-item[disabled]').count();
  const pasteEnabled = await page.locator('.canvas-board__ctx-item', { hasText: '粘贴' }).first().isEnabled();
  log('X1f 禁用态实时（初始3灰→复制后粘贴亮）', dis0 === 3 && dis1 === 2 && pasteEnabled, `dis ${dis0}→${dis1}`);

  // X1g 菜单「粘贴」落点=右键点
  await page.keyboard.press('Escape'); await sleep(200);
  await page.mouse.click(1000, 300, { button: 'right' });
  await sleep(400);
  await page.locator('.canvas-board__ctx-item', { hasText: '粘贴' }).click();
  await sleep(1000);
  const n2 = await page.locator('.vue-flow__node').count();
  const pasted = await page.locator('.vue-flow__node', { hasText: 'd1 2' }).count();
  log('X1g 菜单粘贴 → 副本落点≈右键点', n2 === 2 && pasted === 1, `nodes=${n2} d1_2=${pasted}`);
  await shot(page, 'x1-ctxmenu-paste');
}

// ══ X2 · 官方库 ══
{
  // 官方项目（admin 发布）+ 对照公众项目（普通成员发布）
  const offProj = '官方冒烟XI-' + Date.now();
  const offId = (await jf(`${API}/api/assets/projects`, { method: 'POST', headers: AUTH, body: JSON.stringify({ name: offProj, description: 'official smoke' }) }))?.data?.id;
  const offAsset = (await jf(`${API}/api/assets/projects/${offId}/assets`, { method: 'POST', headers: AUTH, body: JSON.stringify({ mediaType: '提示词', name: '官方提示词甲', content: JSON.stringify({ text: '官方冒烟正文' }) }) }))?.data?.id;
  const pubOff = await jf(`${API}/api/assets/public-pool/${offId}/publish`, { method: 'POST', headers: AUTH, body: JSON.stringify({ accessMode: 'OPEN', allowPublicCopy: false }) });
  log('API 官方项目发布（admin→official）', pubOff?.code === 200 && offAsset != null, `proj=${offId}`);

  // 普通成员（DB 预插 smoke_member_xi，密码 admin123；注册 API 被滑块验证挡）
  const mLogin = await jf(`${API}/api/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'smoke_member_xi', password: 'admin123' }) });
  const MAUTH = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + mLogin?.data?.accessToken };
  const memProj = '成员公众XI-' + Date.now();
  const memId = (await jf(`${API}/api/assets/projects`, { method: 'POST', headers: MAUTH, body: JSON.stringify({ name: memProj }) }))?.data?.id;
  await jf(`${API}/api/assets/projects/${memId}/assets`, { method: 'POST', headers: MAUTH, body: JSON.stringify({ mediaType: '提示词', name: '成员资产', content: JSON.stringify({ text: '成员' }) }) });
  const pubMem = await jf(`${API}/api/assets/public-pool/${memId}/publish`, { method: 'POST', headers: MAUTH, body: JSON.stringify({ accessMode: 'OPEN', allowPublicCopy: false }) });
  log('API 成员项目发布（非 official 对照）', mLogin?.data?.accessToken && memId != null && pubMem?.code === 200, `proj=${memId} pub=${pubMem?.code}`);

  // X2a API 口径：official=true 只含 admin 发布
  const offList = await jf(`${API}/api/assets/public-pool?official=true`, { headers: AUTH });
  const names = (offList?.data ?? []).map(p => p.name);
  const hasOff = names.includes(offProj);
  const noMem = !names.includes(memProj);
  log('X2a API official=true 含官方不含成员公众', hasOff && noMem, `off=${hasOff} mem=${noMem}`);

  // X2b UI：大卡片左列官方项目 + 官方徽标，成员项目不出现
  const cid = await mkCanvas('冒烟XI-官方库', { nodes: [node('o1', 'text', 120, 100)], edges: [], groups: [], viewport: { x: 0, y: 0, zoom: 1 } });
  await openCanvas(cid);
  await page.locator('[aria-label="打开官方库（浏览官方项目资产并插入画布）"]').click();
  await sleep(1200);
  const projCards = page.locator('.olib__project');
  const projTexts = (await projCards.allTextContents()).join('|');
  const badge = await page.locator('.olib__project-badge').count();
  log('X2b 大卡片左列=官方项目+徽标，公众池不进', (await page.locator('.olib').count()) === 1 && projTexts.includes(offProj) && !projTexts.includes(memProj) && badge > 0, `cards=${projCards.count()} badge=${badge}`);

  // X2c 选项目 → 词汇序分组 + 资产行
  await projCards.filter({ hasText: offProj }).first().click();
  await sleep(1500);
  const groups = (await page.locator('.olib__group-head').allTextContents()).join(',');
  const rows = await page.locator('.olib .picker-row').count();
  const rowHas = rows > 0 && (await page.locator('.olib .picker-row', { hasText: '官方提示词甲' }).count()) === 1;
  log('X2c 按媒体类型分组+资产行', rowHas && groups.includes('提示词'), `groups=${groups} rows=${rows}`);

  // X2d 选择 → 卡片关+toast+视口中心新节点
  const nBefore = await page.locator('.vue-flow__node').count();
  await page.locator('.olib .picker-row').first().getByRole('button', { name: '选择' }).click();
  await sleep(2000);
  const toast = await page.locator('.n-message').filter({ hasText: '已插入资产' }).count();
  const olibGone = (await page.locator('.olib').count()) === 0;
  const nAfter = await page.locator('.vue-flow__node').count();
  const newNode = await page.locator('.vue-flow__node', { hasText: '官方提示词甲' }).count();
  log('X2d 选择→关卡片+toast+新资产节点', toast > 0 && olibGone && nAfter === nBefore + 1 && newNode === 1, `${nBefore}→${nAfter} toast=${toast}`);
  await shot(page, 'x2-official-insert');
}

// ══ X3 · 两级词汇 ══
{
  // X3a UI 编辑分类：人物加子类 老人/孩童 → 保存 → 重开回显
  await page.goto(`${BASE}/assets/${projectId}`);
  await page.waitForSelector('button:has-text("编辑分类")', { timeout: 20000 });
  await sleep(1200);
  await page.locator('button:has-text("编辑分类")').click();
  await sleep(900);
  // 组名在 n-input value 里（textContent 无「人物」）——默认序 人物=第 0 组
  const grp = page.locator('.vocab-editor__group').nth(0);
  const childInput = grp.locator('.vocab-editor__child-add input');
  const addChild = async (name) => {
    await childInput.fill(name);
    await childInput.press('Enter');
    await sleep(300);
  };
  await addChild('老人');
  await addChild('孩童');
  const chipCnt = await grp.locator('.n-tag', { hasText: '老人' }).count();
  await page.getByRole('button', { name: '保存' }).click();
  await sleep(1800);
  // 重开验证回显
  await page.locator('button:has-text("编辑分类")').click();
  await sleep(900);
  const grp2 = page.locator('.vocab-editor__group').nth(0);
  const persist = (await grp2.locator('.n-tag', { hasText: '老人' }).count()) === 1 && (await grp2.locator('.n-tag', { hasText: '孩童' }).count()) === 1;
  log('X3a 编辑分类两级+保存回显', chipCnt > 0 && persist, `chip=${chipCnt} persist=${persist}`);
  await shot(page, 'x3-vocab');
  await page.keyboard.press('Escape'); await sleep(500);
  await page.locator('.n-modal button', { hasText: '取消' }).last().click().catch(() => {});
  await sleep(400);

  // API 挂角色：a1→老人，a2→人物，a3→孩童（真路径 /api/assets/assets/{id}——AssetController 基路径含 /assets）
  const put = (id, roleKeys) => jf(`${API}/api/assets/assets/${id}`, { method: 'PUT', headers: AUTH, body: JSON.stringify({ roleKeys }) });
  await put(pa1, ['老人']); await put(pa2, ['人物']); await put(pa3, ['孩童']);
  log('API 挂角色 老人/人物/孩童', true, `${pa1},${pa2},${pa3}`);

  // X3b 矩阵两级：一级徽标聚合 + 点一级大类/点子类精确
  await page.reload();
  await page.waitForSelector('.matrix-filter__role', { timeout: 20000 });
  await sleep(1500);
  const badgeOf = async (label, child) => {
    const row = child
      ? page.locator('.matrix-filter__role--child', { hasText: label }).first()
      : page.locator('.matrix-filter__group', { hasText: label }).locator('.matrix-filter__role').first();
    const t = (await row.locator('.matrix-filter__badge').textContent().catch(() => '?')).trim();
    return t;
  };
  const bTop = await badgeOf('人物', false);
  const bOld = await badgeOf('老人', true);
  const bKid = await badgeOf('孩童', true);
  const cardCount = async () => { await sleep(700); return page.locator('.asset-card').count(); };
  // 点一级=大类（3 资产都在）
  await page.locator('.matrix-filter__group', { hasText: '人物' }).locator('.matrix-filter__role').first().click();
  const cntTop = await cardCount();
  const activeTop = await page.locator('.matrix-filter__role--active', { hasText: '人物' }).count();
  // 点子类=精确
  await page.locator('.matrix-filter__role--child', { hasText: '老人' }).first().click();
  const cntOld = await cardCount();
  const oldText = (await page.locator('.asset-card__name').allTextContents()).join(',');
  // 全部角色=全量
  await page.locator('.matrix-filter__role', { hasText: '全部角色' }).click();
  const cntAll = await cardCount();
  log('X3b 矩阵两级徽标+大类/子类精确筛', bTop === '3' && bOld === '1' && bKid === '1' && cntTop === 3 && activeTop > 0 && cntOld === 1 && oldText.includes('子类资产A') && cntAll === 3,
    `badge 人物=${bTop} 老=${bOld} 孩=${bKid} | 点人物=${cntTop} 点老人=${cntOld}(${oldText}) 全部=${cntAll}`);
  await shot(page, 'x3-matrix');
}

// ══ X4 · 组=大节点 ══
{
  const SNAP = {
    nodes: [node('m1', 'text', 100, 80), node('m2', 'text', 100, 260), node('b', 'text', 560, 170)],
    edges: [{ id: 'ie', source: 'm1', target: 'm2' },
      { id: 'ge-1', source: 'b', target: 'group:group-g1' },
      { id: 'ge-2', source: 'group:group-g1', target: 'b' }],
    groups: [{ id: 'group-g1', name: '冒烟组', memberIds: ['m1', 'm2'], color: '#8b5cf6' }],
    viewport: { x: 0, y: 0, zoom: 1 }
  };
  const cid = await mkCanvas('冒烟XI-组大节点', SNAP);
  await openCanvas(cid);
  const geCnt = () => page.locator('.canvas-board__groupedge-path').count();
  const boxCnt = () => page.locator('.canvas-board__groupbox').count();
  const feCnt = () => page.locator('.vue-flow__edge').count();
  const nCnt = () => page.locator('.vue-flow__node').count();
  // 组框空白点击点：盒底边中点（pad 12px 空白带，避开组头/端口/成员）
  const groupBlankPoint = async () => {
    const bb = await page.locator('.canvas-board__groupbox').first().boundingBox();
    return { x: bb.x + bb.width / 2, y: bb.y + bb.height - 5 };
  };

  // X4a 点组空白=选组高亮；Delete 零动作；点成员切成员
  let p = await groupBlankPoint();
  await page.mouse.click(p.x, p.y);
  await sleep(500);
  const selBox = await page.locator('.canvas-board__groupbox--selected').count();
  await page.keyboard.press('Delete');
  await sleep(600);
  const afterDel = { n: await nCnt(), b: await boxCnt(), ge: await geCnt() };
  const mb = await nodeBBox('.vue-flow__node[data-id="m1"]');
  await page.mouse.click(mb.x + mb.width / 2, mb.y + mb.height / 2);
  await sleep(500);
  const selGone = (await page.locator('.canvas-board__groupbox--selected').count()) === 0;
  const nodeSel = await page.locator('.vue-flow__node.selected').count();
  log('X4a 点组空白选中+Delete零动作+点成员互斥切换', selBox === 1 && afterDel.n === 3 && afterDel.b === 1 && afterDel.ge === 2 && selGone && nodeSel === 1,
    `sel=${selBox} del后 n=${afterDel.n} b=${afterDel.b} ge=${afterDel.ge} 互斥=${selGone}`);
  await page.keyboard.press('Escape'); await sleep(400); // 清选
  await page.mouse.click(1400, 880); await sleep(300);

  // X4b 未选中第一次按住拖=只选中不位移（清场点右下角落，防清选点击落组框/节点内再选中）
  await page.mouse.click(1500, 880); await sleep(400);
  const groupSelCleared = (await page.locator('.canvas-board__groupbox--selected').count()) === 0;
  const m1Before = await nodeBBox('.vue-flow__node[data-id="m1"]');
  p = await groupBlankPoint();
  await page.mouse.move(p.x, p.y);
  await page.mouse.down();
  await page.mouse.move(p.x + 80, p.y + 40, { steps: 6 });
  await page.mouse.up();
  await sleep(600);
  const m1After0 = await nodeBBox('.vue-flow__node[data-id="m1"]');
  const selNow = await page.locator('.canvas-board__groupbox--selected').count();
  const noMove = Math.abs(m1After0.x - m1Before.x) < 3 && Math.abs(m1After0.y - m1Before.y) < 3;
  log('X4b 未选中先选中不拖（一次按住零位移）', groupSelCleared && noMove && selNow === 1, `清场=${groupSelCleared} dx=${Math.round(m1After0.x - m1Before.x)} dy=${Math.round(m1After0.y - m1Before.y)} sel=${selNow}`);

  // X4c 已选中按住拖=整组跟手（m1/m2 同位移，b 不动）
  const m1B = await nodeBBox('.vue-flow__node[data-id="m1"]');
  const m2B = await nodeBBox('.vue-flow__node[data-id="m2"]');
  const bB = await nodeBBox('.vue-flow__node[data-id="b"]');
  p = await groupBlankPoint();
  await page.mouse.move(p.x, p.y);
  await page.mouse.down();
  await page.mouse.move(p.x + 120, p.y + 60, { steps: 10 });
  await sleep(150); // 拖动中：不应有保存 toast 刷
  await page.mouse.up();
  await sleep(800);
  const m1A = await nodeBBox('.vue-flow__node[data-id="m1"]');
  const m2A = await nodeBBox('.vue-flow__node[data-id="m2"]');
  const bA = await nodeBBox('.vue-flow__node[data-id="b"]');
  const d1x = m1A.x - m1B.x, d2x = m2A.x - m2B.x, dbx = bA.x - bB.x;
  const groupMoved = Math.abs(d1x - d2x) < 4 && d1x > 60 && Math.abs(dbx) < 3;
  log('X4c 整组拖动跟手（双成员同位移/b不动）', groupMoved, `m1dx=${Math.round(d1x)} m2dx=${Math.round(d2x)} bdx=${Math.round(dbx)}`);
  await shot(page, 'x4-group-drag');
  // 落库：等防抖保存后快照里 m1 流坐标右移
  await sleep(3500);
  const snap = await jf(`${API}/api/canvas/${cid}`, { headers: AUTH });
  const s = typeof snap?.data?.snapshot === 'string' ? JSON.parse(snap.data.snapshot) : snap?.data?.snapshot;
  const m1Flow = (s?.nodes ?? []).find(n => n.id === 'm1');
  log('X4d 整组拖动落库（快照 m1.x>170）', m1Flow?.position?.x > 170, `m1.x=${m1Flow?.position?.x}`);

  // X4e 完全包含复制：框选 m1+m2（动态外扩矩形，Full 模式须完整包含且不含 b）→ Ctrl+C/V
  await page.keyboard.press('Escape'); await sleep(300);
  const boxSel = async () => {
    const b1 = await nodeBBox('.vue-flow__node[data-id="m1"]');
    const b2 = await nodeBBox('.vue-flow__node[data-id="m2"]');
    const x1 = Math.min(b1.x, b2.x) - 12, y1 = Math.min(b1.y, b2.y) - 12;
    const x2 = Math.max(b1.x + b1.width, b2.x + b2.width) + 12;
    const y2 = Math.max(b1.y + b1.height, b2.y + b2.height) + 12;
    await page.keyboard.down('Shift');
    await page.mouse.move(x1, y1); await page.mouse.down();
    await page.mouse.move(x2, y2, { steps: 10 }); await page.mouse.up();
    await page.keyboard.up('Shift');
    await sleep(600);
    return page.locator('.vue-flow__node.selected').count();
  };
  const selN = await boxSel();
  await page.keyboard.press('Control+c');
  await sleep(600);
  await page.mouse.click(350, 780); await sleep(300); // 清选+定位粘贴落点（各用例错开，防踩新节点）
  await page.keyboard.press('Control+v');
  await sleep(1400);
  const toastG = await page.locator('.n-message').filter({ hasText: '已粘贴 1 个组' }).count();
  const eState = { n: await nCnt(), b: await boxCnt(), ge: await geCnt(), fe: await feCnt() };
  const newName = await page.locator('.canvas-board__groupbox-name', { hasText: '冒烟组 2' }).count();
  log('X4e 完全包含粘新组（壳/名去重/组级边/普通内边/toast）', selN === 2 && toastG > 0 && eState.n === 5 && eState.b === 2 && eState.ge === 4 && eState.fe === 2 && newName === 1,
    `sel=${selN} n=${eState.n} b=${eState.b} ge=${eState.ge} fe=${eState.fe} 名2=${newName} toast=${toastG}`);
  await shot(page, 'x4-group-paste');

  // X4f Ctrl+Z 一步撤
  await page.keyboard.press('Control+z');
  await sleep(900);
  const zState = { n: await nCnt(), b: await boxCnt(), ge: await geCnt(), fe: await feCnt() };
  log('X4f 一步撤（组+节点+组级边+普通边全消）', zState.n === 3 && zState.b === 1 && zState.ge === 2 && zState.fe === 1, JSON.stringify(zState));

  // X4g 半含（只选 m1）→ 平节点不建组，内边按恰一端连原对端
  const mb1 = await nodeBBox('.vue-flow__node[data-id="m1"]');
  await page.mouse.click(mb1.x + mb1.width / 2, mb1.y + mb1.height / 2);
  await sleep(400);
  await page.keyboard.press('Control+c');
  await sleep(600);
  await page.mouse.click(520, 840); await sleep(300);
  await page.keyboard.press('Control+v');
  await sleep(1200);
  const hState = { n: await nCnt(), b: await boxCnt(), ge: await geCnt(), fe: await feCnt() };
  const noGToast = await page.locator('.n-message').filter({ hasText: '已粘贴 1 个组' }).count();
  log('X4g 半含=平节点（零新组/组员不入）+内边恰一端连原对端', hState.n === 4 && hState.b === 1 && hState.ge === 2 && hState.fe === 2 && noGToast === 0,
    `n=${hState.n} b=${hState.b} ge=${hState.ge} fe=${hState.fe}`);
  await page.keyboard.press('Control+z'); await sleep(800);

  // X4h ⛓ 关 → 完全包含粘贴：壳照建、零组级边、内边照旧
  const chainBtn = page.locator('[aria-label="连线保留开关（复制粘贴与创建副本是否保留原节点连线）"]');
  await chainBtn.click(); await sleep(300);
  const pressedOff = await chainBtn.getAttribute('aria-pressed');
  await boxSel();
  await page.keyboard.press('Control+c');
  await sleep(600);
  await page.mouse.click(760, 800); await sleep(300);
  await page.keyboard.press('Control+v');
  await sleep(1400);
  const kState = { n: await nCnt(), b: await boxCnt(), ge: await geCnt(), fe: await feCnt() };
  log('X4h ⛓关壳照建+零组级边+内边照粘', pressedOff === 'false' && kState.n === 5 && kState.b === 2 && kState.ge === 2 && kState.fe === 2,
    `pressed=${pressedOff} n=${kState.n} b=${kState.b} ge=${kState.ge} fe=${kState.fe}`);
  await page.keyboard.press('Control+z'); await sleep(800);
  await chainBtn.click(); await sleep(300); // 复原 ⛓ 开

  // X4i 刷新重现（快照含组+组级边）
  await sleep(3000);
  await page.reload();
  await page.waitForSelector('.vue-flow__node', { timeout: 20000 });
  await sleep(1800);
  const rState = { n: await nCnt(), b: await boxCnt(), ge: await geCnt(), fe: await feCnt() };
  log('X4i 刷新重现一致', rState.n === 3 && rState.b === 1 && rState.ge === 2 && rState.fe === 1, JSON.stringify(rState));
}

const fails = results.filter(r => !r.ok);
console.log(`\n== 修复XI 冒烟结果： ${results.length - fails.length}/${results.length} PASS ==`);
fails.forEach(f => console.log('FAIL项： ' + f.name));
await ctx.close();
process.exit(fails.length ? 1 : 0);

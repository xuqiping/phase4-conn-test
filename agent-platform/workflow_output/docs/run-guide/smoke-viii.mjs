// 修复VIII P4 Playwright 冒烟 v9：API 预置快照摆节点（绕开 fitView 缩放/簇叠拖拽的工装坑）
// 覆盖：M1 组端口可见 / M2 组源端口→外部节点本体→组边 / M9 handle→本体直连 / 持久化 / N1 WS 首帧鉴权 / N2 导出密码确认
import { chromium } from 'file:///C:/Users/Administrator/AppData/Roaming/npm/node_modules/@playwright/mcp/node_modules/playwright/index.mjs';

const BASE = 'http://localhost:5173';
const API = 'http://localhost:8080';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const results = [];
const log = (name, ok, detail = '') => { results.push({ name, ok }); console.log(`${ok ? 'PASS' : 'FAIL'} | ${name}${detail ? ' | ' + detail : ''}`); };
const shot = async (page, tag) => { try { await page.screenshot({ path: `C:/Users/Administrator/AppData/Local/Temp/smoke-viii-${tag}.png` }); } catch {} };

const ctx = await chromium.launchPersistentContext('C:/Users/Administrator/AppData/Local/Temp/pw-smoke-viii-profile', { viewport: { width: 1680, height: 950 }, headless: true });
const page = ctx.pages()[0] ?? await ctx.newPage();
page.on('pageerror', e => console.log('PAGEERROR |', String(e).slice(0, 200)));

await page.addInitScript(() => {
  window.__wsLog = [];
  const OrigWS = window.WebSocket;
  const WS = function (url, protos) {
    const ws = protos !== undefined ? new OrigWS(url, protos) : new OrigWS(url);
    const entry = { url: String(url), frames: [], closeCode: null };
    window.__wsLog.push(entry);
    const origSend = ws.send.bind(ws);
    ws.send = d => { entry.frames.push(typeof d === 'string' ? d : '[binary]'); return origSend(d); };
    ws.addEventListener('close', e => { entry.closeCode = e.code; });
    return ws;
  };
  WS.prototype = OrigWS.prototype;
  window.WebSocket = WS;
});

// ── Node 侧直连后端：登录 + 建画布 + 预置快照 ──
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

const canvas = (await (await fetch(`${API}/api/canvas`, { method: 'POST', headers: AUTH, body: JSON.stringify({ name: '冒烟VIII-P4' }) })).json()).data;
const node = (id, x, y) => ({ id, type: 'text', position: { x, y }, data: { label: id } });
const snapshot = JSON.stringify({
  nodes: [node('sn-1', 60, 60), node('sn-2', 520, 60), node('sn-3', 280, 330)],
  edges: [], groups: [], viewport: { x: 0, y: 0, zoom: 1 }
});
const saved = await fetch(`${API}/api/canvas/${canvas.id}`, { method: 'PUT', headers: AUTH, body: JSON.stringify({ name: '冒烟VIII-P4', snapshot }) });
log('API 预置快照（3 文本节点）', saved.ok);

await page.goto(`${BASE}/canvas/${canvas.id}`).catch(() => {});
await sleep(1500);
if (!page.url().includes('/canvas/' + canvas.id)) { await page.goto(`${BASE}/canvas/${canvas.id}`).catch(() => {}); await sleep(1500); }
await page.waitForSelector('.vue-flow__node', { timeout: 15000 });
await sleep(800);
log('打开画布 #' + canvas.id + ' 节点渲染', await page.locator('.vue-flow__node').count() === 3);

const nodeLoc = page.locator('.vue-flow__node');
const nb = [];
for (let i = 0; i < 3; i++) nb.push(await nodeLoc.nth(i).boundingBox());
// DOM 顺序=快照顺序：sn-1/sn-2 一组，sn-3 外部（下方）
const [b1, b2, b3] = nb;
const vpTf = await page.evaluate(() => document.querySelector('.vue-flow__transformationpane')?.style.transform);
const spread = b1 && b2 && b3 && b1.x < b2.x - 150 && b3.y > b1.y + 100;
log('3 节点铺开（1/2 上排 3 下排）', spread, `x=[${nb.map(b => b && Math.round(b.x))}] tf=${vpTf}`);
if (!spread) await shot(page, 'spread-fail');

// ── Shift 框选 sn-1+sn-2（vue-flow 默认 SelectionMode.Full：框须完整包含两节点）──
const paneBB = await page.evaluate(() => {
  const el = document.querySelector('.vue-flow__pane') ?? document.querySelector('.vue-flow');
  const r = el?.getBoundingClientRect();
  return r ? { x: r.x, y: r.y, width: r.width, height: r.height } : null;
});
const sx = Math.max(paneBB.x + 10, Math.min(b1.x, b2.x) - 30);
const sy = Math.max(paneBB.y + 10, Math.min(b1.y, b2.y) - 30);
const ex = Math.min(paneBB.x + paneBB.width - 10, Math.max(b1.x + b1.width, b2.x + b2.width) + 30);
const ey = Math.min(paneBB.y + paneBB.height - 10, Math.max(b1.y + b1.height, b2.y + b2.height) + 30);
await page.keyboard.down('Shift');
await page.mouse.move(sx, sy);
await page.mouse.down();
await page.mouse.move(ex, ey, { steps: 12 });
await page.mouse.up();
await page.keyboard.up('Shift');
await sleep(600);
const selCount = await page.evaluate(() => document.querySelectorAll('.vue-flow__node.selected').length);
console.log(`DEBUG | 框(${Math.round(sx)},${Math.round(sy)})→(${Math.round(ex)},${Math.round(ey)}) selected=${selCount}`);
const groupBtn = page.getByRole('button', { name: /设为组/ });
const btnVisible = await groupBtn.isVisible().catch(() => false);
if (!btnVisible) await shot(page, 'nogroupbtn');
log('框选 2 节点出现「设为组」按钮', btnVisible, `selected=${selCount}`);
await groupBtn.click();
await page.locator('.n-modal input').first().fill('冒烟组');
await page.getByRole('button', { name: '建组' }).click();
await page.waitForSelector('.canvas-board__groupbox', { timeout: 5000 }).catch(() => {});
await sleep(600);
const hasBox = await page.locator('.canvas-board__groupbox').count();

// ── M1 ──
const srcPort = page.locator('.canvas-board__groupbox-port--source');
const tgtPort = page.locator('.canvas-board__groupbox-port--target');
const m1 = hasBox > 0 && await srcPort.isVisible().catch(() => false) && await tgtPort.isVisible().catch(() => false);
log('M1 组框 source/target 端口可见', m1, `box=${hasBox}`);
await shot(page, 'm1-ports');

// ── M2：组 source 端口 → sn-3 本体松手 → 组边 ──
const portBB = await srcPort.boundingBox();
await page.mouse.move(portBB.x + portBB.width / 2, portBB.y + portBB.height / 2);
await page.mouse.down();
await page.mouse.move(b3.x + b3.width / 2, b3.y + b3.height / 2, { steps: 18 });
await page.mouse.up();
await sleep(800);
const geCount = await page.locator('.canvas-board__groupedge-path').count();
log('M2 组源端口拉线落 sn-3 本体 → 组边出现', geCount >= 1, `groupedge-path=${geCount}`);
await shot(page, 'm2-groupedge');

// ── 持久化（第一轮：组框+组边）──
await sleep(3000);
await page.reload();
await page.waitForSelector('.vue-flow__node', { timeout: 15000 });
await sleep(1800);
const geAfter = await page.locator('.canvas-board__groupedge-path').count();
const boxAfter = await page.locator('.canvas-board__groupbox').count();
log('持久化① reload 后组框+组边俱在', geAfter >= 1 && boxAfter >= 1, `groupbox=${boxAfter} groupedge=${geAfter}`);
await shot(page, 'after-reload');

// ── M9：reload 后干净态（无多选残留——多选包围盒 rect 会盖 handle 且拖拽会挪动整组选中，工装坑非产品坑）──
const nl2 = page.locator('.vue-flow__node');
const m9From = await nl2.nth(0).locator('.vue-flow__handle.source').first().boundingBox();
const m9ToB = await nl2.nth(2).boundingBox();
const hitFrom = await page.evaluate(([x, y]) => { const el = document.elementFromPoint(x, y); return el ? String(el.className).slice(0, 40) : 'null'; }, [m9From.x + m9From.width / 2, m9From.y + m9From.height / 2]);
await page.mouse.move(m9From.x + m9From.width / 2, m9From.y + m9From.height / 2);
await page.mouse.down();
await page.mouse.move(m9ToB.x + m9ToB.width / 2, m9ToB.y + m9ToB.height / 2, { steps: 18 });
const connMid = await page.evaluate(() => !!document.querySelector('.vue-flow__connection'));
await page.mouse.up();
await sleep(900);
const edgeAfter = await page.locator('.vue-flow__edge').count();
log('M9 handle→本体直连（干净态）', edgeAfter >= 1, `edges→${edgeAfter} connLine=${connMid} hit@from=${hitFrom}`);
await shot(page, 'm9-bodydrop');

// ── 持久化②（M9 普通边）──
await sleep(3000);
await page.reload();
await page.waitForSelector('.vue-flow__node', { timeout: 15000 });
await sleep(1800);
const edgeReload = await page.locator('.vue-flow__edge').count();
log('持久化② reload 后普通边在', edgeReload >= 1, `edge=${edgeReload}`);
await shot(page, 'after-reload2');

// ── N1：WS 首帧鉴权 ──
await page.goto(BASE + '/chat').catch(() => {});
await sleep(4500);
const wsLog = await page.evaluate(() => window.__wsLog ?? []);
const appWs = wsLog.filter(e => { try { return new URL(e.url).pathname.startsWith('/ws/'); } catch { return false; } }); // 排除 Vite HMR（ws://host/?token= 非应用连接）
const noTokenInUrl = appWs.every(e => !/[?&]token=/.test(e.url));
log('N1a 应用 WS URL 无 token 参数', appWs.length === 0 || noTokenInUrl, `conns=${appWs.length} ${JSON.stringify(appWs.map(e => e.url + ' 首帧:' + (e.frames[0] || '').slice(0, 40)))}`);

const probeNoAuth = await page.evaluate(() => new Promise(res => {
  const ws = new WebSocket(`ws://${location.host}/ws/chat`);
  const t = setTimeout(() => res({ outcome: 'timeout' }), 9000);
  ws.addEventListener('message', e => { clearTimeout(t); res({ outcome: 'msg', data: String(e.data) }); });
  ws.addEventListener('close', e => { clearTimeout(t); res({ outcome: 'close', code: e.code }); });
}));
log('N1b 不发首帧鉴权 → 被服务端 4401 关闭', probeNoAuth.outcome === 'close' && probeNoAuth.code === 4401, JSON.stringify(probeNoAuth));

const probeAuth = await page.evaluate(() => new Promise(res => {
  const token = JSON.parse(localStorage.getItem('access_token') || '""');
  const ws = new WebSocket(`ws://${location.host}/ws/chat`);
  const t = setTimeout(() => res({ outcome: 'timeout' }), 9000);
  ws.addEventListener('open', () => ws.send(JSON.stringify({ type: 'auth', token })));
  ws.addEventListener('message', e => { clearTimeout(t); res({ outcome: 'msg', data: String(e.data) }); try { ws.close(); } catch {} });
  ws.addEventListener('close', e => { clearTimeout(t); res({ outcome: 'close', code: e.code }); });
}));
log('N1c 首帧 {type:auth} → 收 auth_ok', probeAuth.outcome === 'msg' && /auth_ok/.test(probeAuth.data || ''), JSON.stringify(probeAuth).slice(0, 120));

// ── N2：导出供应商密码二次确认 ──
await page.goto(BASE + '/settings').catch(() => {});
await sleep(2000);
try { await page.locator('.n-tabs-tab', { hasText: /供应商/ }).click({ timeout: 4000 }); } catch {}
await page.waitForSelector('[aria-label="导出供应商"]', { timeout: 10000 });
await page.click('[aria-label="导出供应商"]');
await page.waitForSelector('.n-modal:has-text("导出供应商")', { timeout: 5000 });
const pwdInput = page.locator('.n-modal input[type="password"]');
await pwdInput.fill('wrong-password-xxx');
await page.click('[aria-label="确认导出"]');
const errShown = await page.waitForSelector('.n-message:has-text("密码错误或导出失败")', { timeout: 8000 }).then(() => true).catch(() => false);
const modalStays = await page.locator('.n-modal:has-text("导出供应商")').isVisible().catch(() => false);
log('N2a 错密码 → 报错且弹窗保留可重试', errShown && modalStays, `err=${errShown} modal=${modalStays}`);
await shot(page, 'n2a-wrongpwd');
const dlPromise = page.waitForEvent('download', { timeout: 15000 }).catch(() => null);
await pwdInput.fill('admin123');
await page.click('[aria-label="确认导出"]');
const dl = await dlPromise;
let dlOk = false, dlName = '';
if (dl) { dlName = dl.suggestedFilename(); dlOk = true; await dl.cancel().catch(() => {}); }
log('N2b 正确密码 → 触发文件下载', dlOk, `file=${dlName}`);

const fails = results.filter(r => !r.ok);
console.log(`\n== 冒烟结果： ${results.length - fails.length}/${results.length} PASS ==`);
fails.forEach(f => console.log('FAIL项： ' + f.name));
await ctx.close();
process.exit(fails.length ? 1 : 0);

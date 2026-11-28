/*
  미니앱에 다리 놓기: window.gyeot (app/src/main/assets/bridge/gyeot.js) in Chromium, on both surfaces the app gives it:
    - a 프로젝트 page (https://projects.gyeot.invalid/<id>/, the script put first in its head as ProjectActivity does), the app
      played here by a MessageChannel port given with "gyeot-app" as connectApp gives it; and 트레이딩 on the same port;
    - the user's own page on 들퍼레 홈 화면 (a page: slot, framed from https://pages.gyeot.local), its calls relayed by the real
      launcher.html to the app's bridge (window.Gyeot, stubbed) and the answers back to that frame alone.
  Checked: the shape of each call, Dates sent as milliseconds, calls made before the port waits for it, answers resolve and
  refusals reject with their code and reason, a frame inside the page cannot stand in for the app, window.gyeot cannot be
  replaced, no app within five seconds is said, a piece from another site is never relayed, and a sample page (물 마시기)
  sets its reminders and shows a refusal. Run: node bridge.test.js   (BRIDGE_SHOTS=<dir> saves the sample page)
*/
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const ASSETS = path.join(__dirname, '../../app/src/main/assets');
const SCRIPT_URL = 'https://projects.gyeot.invalid/.gyeot/bridge.js', HOME_SCRIPT_URL = 'https://pages.gyeot.local/.gyeot/bridge.js';
const BRIDGE = fs.readFileSync(path.join(ASSETS, 'bridge/gyeot.js'), 'utf8');
const tag = src => '<script src="' + src + '"></script>';
/** As core/PageBridge.inject: first in the head. */
const inject = (html, src) => html.replace(/<head[^>]*>/i, m => m + tag(src));
const shots = process.env.BRIDGE_SHOTS;
let failures = 0;
const check = (yes, what) => { console.log((yes ? 'ok   ' : 'FAIL ') + what); if (!yes) failures++; };

const PAGE = '<!doctype html><html><head><title>t</title></head><body><button id="b">go</button><script>'
  + 'window.early = gyeot.calendar.list(new Date(Date.UTC(2026,10,28)), "2026-11-30").then(v => ({ok: v}), e => ({code: e.code, message: e.message}));'
  + '</script><iframe id="f" srcdoc="<script>const c=new MessageChannel();window.parent.postMessage(\'gyeot-app\',\'*\',[c.port2]);c.port1.onmessage=e=>parent.spoofed=(parent.spoofed||0)+1;</script>"></iframe></body></html>';

async function project(browser, html, give) {
  const page = await browser.newPage({ viewport: { width: 411, height: 760 }, deviceScaleFactor: 2 });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.route('**/*', route => {
    const url = route.request().url();
    if (url === SCRIPT_URL) return route.fulfill({ status: 200, contentType: 'text/javascript; charset=utf-8', body: BRIDGE });
    if (url.startsWith('https://projects.gyeot.invalid/p1/')) return route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: inject(html, SCRIPT_URL) });
    return route.fulfill({ status: 200, contentType: 'text/javascript', body: '' });
  });
  await page.goto('https://projects.gyeot.invalid/p1/');
  page.errors = errors;
  if (give) await giveApp(page);
  return page;
}
/** The app, as connectApp gives it: a port; each call kept in window.__said, answered by window.__answer(m) when set. */
async function giveApp(page) {
  await page.evaluate(() => {
    const channel = new MessageChannel(); window.__said = []; window.__app = channel.port1;
    channel.port1.onmessage = e => { const m = JSON.parse(e.data); window.__said.push(m); if (window.__answer) { const r = window.__answer(m); if (r) channel.port1.postMessage(JSON.stringify(Object.assign({ type: 'gyeot', id: m.id }, r))); } };
    window.postMessage('gyeot-app', '*', [channel.port2]);
  });
}

(async () => {
  // A call that is never answered must fail the run, not hang it.
  setTimeout(() => { console.log("FAIL the run did not finish in 100 s"); process.exit(1); }, 100000).unref();
  const browser = await chromium.launch(process.env.CHROME ? { executablePath: process.env.CHROME } : {});

  // ── a project's page ──
  {
    const page = await project(browser, PAGE, false);
    check(await page.evaluate(() => typeof window.gyeot === 'object' && typeof gyeot.notify === 'function' && typeof gyeot.notify.cancel === 'function'
      && typeof gyeot.calendar.add === 'function' && typeof gyeot.memory.recall === 'function' && typeof gyeot.day.recall === 'function'
      && typeof gyeot.ask === 'function' && typeof gyeot.tool === 'function' && typeof gyeot.permissions === 'function'), 'window.gyeot is there before the page\'s own script');
    check(await page.evaluate(() => { try { window.gyeot = {}; } catch (e) {} try { gyeot.notify = null; } catch (e) {} return typeof gyeot.notify === 'function'; }), 'the page cannot replace it');
    await page.waitForTimeout(200);
    // The frame inside the page tried to hand it a port of its own: never used.
    await page.evaluate(() => { window.__answer = m => m.call === 'calendar.list' ? { ok: true, value: [{ title: '치과', start: 1, end: 2, allDay: false, place: '강남' }] } : null; });
    await giveApp(page);
    const early = await page.evaluate(() => Promise.race([window.early, new Promise(r => setTimeout(() => r({ timeout: true }), 2000))]));
    check(early.ok && early.ok[0].title === '치과', 'a call made before the port came waited for it and resolved: ' + JSON.stringify(early));
    const said = await page.evaluate(() => window.__said);
    check(said.length === 1 && said[0].type === 'gyeot' && /^gy-/.test(said[0].id) && said[0].call === 'calendar.list', 'the call\'s shape: ' + JSON.stringify(said[0]));
    check(said[0].args.from === Date.UTC(2026, 10, 28) && said[0].args.to === '2026-11-30', 'a Date goes as milliseconds, words as they are');
    check(!(await page.evaluate(() => window.spoofed)), 'a frame inside the page never stood in for the app');

    await page.evaluate(() => { window.__answer = m => {
      if (m.call === 'notify') return { ok: true, value: { id: 'n1', at: m.args.at || 5 } };
      if (m.call === 'tool' && m.args.name === 'forget') return { ok: false, code: 'blocked', error: '지우는 도구는 페이지에서 쓸 수 없어요.' };
      if (m.call === 'ask') return { ok: true, value: { q: '1+1', a: '2' } };
      if (m.call === 'calendar.add') return { ok: false, code: 'denied', error: '사용자가 일정 쓰는 것을 거부했어요.' };
      if (m.call === 'notify.cancel') return { ok: true, value: { cancelled: m.args.id ? 1 : 3 } };
      return { ok: true, value: null };
    }; });
    const r = await page.evaluate(async () => {
      const out = {};
      out.notify = await gyeot.notify('물', '한 컵', { at: new Date(Date.UTC(2026, 10, 28, 1)) });
      try { await gyeot.tool('forget', { what: 'x' }); out.tool = 'resolved'; } catch (e) { out.tool = { code: e.code, message: e.message, name: e.name }; }
      out.ask = await gyeot.ask('카드 하나', { kind: 'json' });
      try { await gyeot.calendar.add({ title: '치과', start: new Date(Date.UTC(2026, 10, 28, 6)), place: '강남' }); } catch (e) { out.add = e.code; }
      out.cancelAll = await gyeot.notify.cancel();
      return out;
    });
    check(r.notify.id === 'n1', 'notify resolves with its id');
    check(r.tool.code === 'blocked' && r.tool.message.includes('지우는') && r.tool.name === 'GyeotError', 'a blocked tool rejects with its code and reason: ' + JSON.stringify(r.tool));
    check(r.ask.a === '2', 'ask json: the value');
    check(r.add === 'denied', 'a denial rejects as denied');
    check(r.cancelAll.cancelled === 3, 'notify.cancel() with no id: all of its own');
    const last = await page.evaluate(() => window.__said.slice(-5));
    check(last[0].args.at === Date.UTC(2026, 10, 28, 1) && last[0].args.title === '물', 'notify: title, text, at in ms');
    check(last[1].call === 'tool' && last[1].args.name === 'forget' && last[1].args.args.what === 'x', 'tool: name and args');
    check(last[2].args.kind === 'json' && last[3].args.start === Date.UTC(2026, 10, 28, 6) && last[3].args.place === '강남', 'ask\'s kind, calendar.add\'s start in ms');
    check(!('id' in last[4].args) || last[4].args.id === undefined, 'cancel all: no id sent');
    // An answer to no call, or with another type, is left alone.
    await page.evaluate(() => { window.__app.postMessage(JSON.stringify({ type: 'gyeot', id: 'gy-999', ok: true, value: 1 })); window.__app.postMessage('not json'); });
    await page.waitForTimeout(100);
    check(page.errors.length === 0, 'no page errors ' + JSON.stringify(page.errors));
    await page.close();
  }

  // ── no app within five seconds: said ──
  {
    const page = await project(browser, '<!doctype html><html><head></head><body></body></html>', false);
    await page.clock.install();
    const started = page.evaluate(() => gyeot.permissions().then(() => 'resolved', e => e.code + ' ' + e.message));
    await page.clock.runFor(5200);
    const result = await started;
    check(result.startsWith('unavailable') && result.includes('들퍼레 앱 안에서만'), 'no app: unavailable, said ' + result);
    await page.close();
  }

  // ── 트레이딩 on the same port: its own messages and window.gyeot side by side ──
  {
    const html = fs.readFileSync(path.join(ASSETS, 'projects/trading.html'), 'utf8');
    const page = await project(browser, html, true);
    await page.evaluate(() => { window.__answer = m => m.type === 'gyeot' ? { ok: true, value: { notify: 'allow' } } : null; });
    await page.waitForTimeout(250);
    const perms = await page.evaluate(() => gyeot.permissions());
    const said = await page.evaluate(() => window.__said);
    check(said.some(m => m.type === 'model') && said.some(m => m.type === 'tv_symbol'), '트레이딩 still asks its model and tells its symbol');
    check(perms.notify === 'allow' && said.some(m => m.type === 'gyeot' && m.call === 'permissions'), 'and window.gyeot answers on the same port');
    await page.evaluate(() => window.__app.postMessage(JSON.stringify({ type: 'model', label: 'Claude · 높음' })));
    await page.waitForTimeout(100);
    check(page.errors.length === 0, '트레이딩: no page errors ' + JSON.stringify(page.errors));
    await page.close();
  }

  // ── the sample: 물 마시기 ──
  {
    const html = fs.readFileSync(path.join(ASSETS, 'projects/water.html'), 'utf8');
    const page = await project(browser, html.replace('<head>', '<head><script>document.documentElement.setAttribute("data-theme","light")</script>'), true);
    let set = 0;
    await page.exposeFunction('countSet', () => ++set);
    await page.evaluate(() => { window.__answer = m => {
      if (m.call === 'notify.cancel') return { ok: true, value: { cancelled: 0 } };
      if (m.call === 'notify') { window.countSet(); return { ok: true, value: { id: 'w' + window.__said.length, at: m.args.at } }; }
      return null;
    }; });
    await page.click('#add'); await page.click('#add');
    check(await page.textContent('#n') === '2', 'a cup, two cups');
    await page.click('#remind');
    await page.waitForTimeout(400);
    const said = await page.evaluate(() => window.__said);
    const notes = said.filter(m => m.call === 'notify');
    check(said[0].call === 'notify.cancel' && notes.length >= 1 && notes.length <= 14, 'turned on: its old ones taken away, the day\'s reminders set (' + notes.length + ')');
    check(notes.every(m => m.args.at > Date.now() && new Date(m.args.at).getHours() >= 9 && new Date(m.args.at).getHours() <= 21), 'each between 9 and 21, ahead');
    check(/다음 알림/.test(await page.textContent('#next')), 'the next one shown: ' + await page.textContent('#next'));
    if (shots) { fs.mkdirSync(shots, { recursive: true }); await page.screenshot({ path: path.join(shots, 'water.png') }); }
    // A refusal: shown where it happened, the switch back off.
    await page.evaluate(() => { window.__answer = m => m.call === 'notify' ? { ok: false, code: 'denied', error: '사용자가 알림 쓰는 것을 거부했어요.' } : { ok: true, value: { cancelled: 1 } }; });
    await page.click('#remind'); await page.waitForTimeout(150);
    await page.click('#remind'); await page.waitForTimeout(300);
    check((await page.textContent('#say')).includes('거부했어요') && !(await page.evaluate(() => document.getElementById('remind').classList.contains('on'))), 'a refusal: said on the page, the switch off');
    if (shots) await page.screenshot({ path: path.join(shots, 'water_refused.png') });
    check(page.errors.length === 0, '물 마시기: no page errors ' + JSON.stringify(page.errors));
    await page.close();
    // Outside the app (no bridge): it says so and stays a plain tracker.
    const plain = await browser.newPage();
    await plain.setContent(html);
    await plain.click('#add'); await plain.click('#remind'); await plain.waitForTimeout(100);
    check((await plain.textContent('#say')).includes('앱 안에서만') && await plain.textContent('#n') === '1', 'outside the app: a plain tracker that says why');
    await plain.close();
  }

  // ── the home screen: a page of the user's own, relayed by launcher.html ──
  {
    const ctx = await browser.newContext({ viewport: { width: 412, height: 900 }, hasTouch: true });
    await ctx.addInitScript(() => { if (location.host === 'gyeot.local') { window._calls = []; window.Gyeot = { call: (token, name, args) => window._calls.push([name, JSON.parse(args)]) }; } });
    const page = await ctx.newPage();
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    const MINE = '<!doctype html><html><head><title>내 대시보드</title></head><body><script>window.r=null;window.go=()=>gyeot.calendar.list().then(v=>window.r={ok:v},e=>window.r={code:e.code,message:e.message})</script></body></html>';
    const PIECE = '<html><body><script>window.sent=()=>parent.postMessage({gyeot:"call",message:JSON.stringify({type:"gyeot",id:"gy-1",call:"notify",args:{title:"x"}})},"*")</script></body></html>';
    await page.route('**/*', route => {
      const u = new URL(route.request().url());
      if (u.host === 'gyeot.local' && u.pathname === '/') {
        const html = fs.readFileSync(path.join(ASSETS, 'decor/launcher.html'), 'utf8').replace('/*GYEOT*/', 'window.GYEOT=' + JSON.stringify({ launcher: true, token: 'test',
          pages: [{ src: 'page:abc', title: '내 대시보드' }, { src: 'https://piece.test/x.html', title: '작품' }], bg: 'aurora' }) + ';');
        return route.fulfill({ body: html, contentType: 'text/html' });
      }
      if (u.host === 'gyeot.local') { const f = path.join(ASSETS, 'decor', path.basename(u.pathname)); if (fs.existsSync(f)) return route.fulfill({ body: fs.readFileSync(f), contentType: f.endsWith('.js') ? 'text/javascript' : 'text/css' }); }
      if (route.request().url() === HOME_SCRIPT_URL) return route.fulfill({ body: BRIDGE, contentType: 'text/javascript; charset=utf-8' });
      if (u.host === 'pages.gyeot.local') return route.fulfill({ body: inject(MINE, HOME_SCRIPT_URL), contentType: 'text/html; charset=utf-8' });
      if (u.host === 'piece.test') return route.fulfill({ body: PIECE, contentType: 'text/html' });
      return route.fulfill({ status: 404, body: '' });
    });
    await page.goto('https://gyeot.local/');
    await page.waitForTimeout(600);
    // The first page to the right of 홈 is near enough to load.
    await page.evaluate(() => launcher.goPage && launcher.goPage('내 대시보드'));
    await page.waitForTimeout(600);
    const mine = page.frames().find(f => f.url().startsWith('https://pages.gyeot.local/'));
    check(!!mine, 'the user\'s page is framed from pages.gyeot.local');
    if (mine) {
      check(await mine.evaluate(() => typeof window.gyeot === 'object'), 'window.gyeot in the user\'s page on the home screen');
      await mine.evaluate(() => { window.go(); });
      await page.waitForTimeout(200);
      const calls = await page.evaluate(() => window._calls.filter(c => c[0] === 'bridge'));
      check(calls.length === 1 && calls[0][1][0] === 'page:abc' && calls[0][1][1] === '내 대시보드', 'relayed to the app with the page\'s src and name: ' + JSON.stringify(calls.map(c => c[1].slice(0, 2))));
      const m = calls.length ? JSON.parse(calls[0][1][2]) : {};
      check(m.type === 'gyeot' && m.call === 'calendar.list', 'the call as the page made it');
      // A reply for another page goes nowhere; this page's reaches it.
      await page.evaluate(id => { launcher.bridgeReply('page:other', JSON.stringify({ type: 'gyeot', id, ok: true, value: ['wrong'] })); }, m.id);
      await page.waitForTimeout(100);
      check(await mine.evaluate(() => window.r) === null, 'another page\'s reply never reaches it');
      await page.evaluate(id => { launcher.bridgeReply('page:abc', JSON.stringify({ type: 'gyeot', id, ok: false, code: 'denied', error: '사용자가 일정 쓰는 것을 거부했어요.' })); }, m.id);
      await page.waitForTimeout(150);
      const got = await mine.evaluate(() => window.r);
      check(got && got.code === 'denied' && got.message.includes('거부'), 'its own reply reaches it: ' + JSON.stringify(got));
    }
    // A piece from another site is never relayed.
    await page.evaluate(() => launcher.goPage && launcher.goPage('작품'));
    await page.waitForTimeout(600);
    const piece = page.frames().find(f => f.url().startsWith('https://piece.test/'));
    if (piece) { await piece.evaluate(() => window.sent()); await page.waitForTimeout(150); }
    check(!!piece && (await page.evaluate(() => window._calls.filter(c => c[0] === 'bridge').length)) === 1, 'a piece from another site is never relayed');
    check(errors.length === 0, 'home: no page errors ' + JSON.stringify(errors));
    await ctx.close();
  }

  await browser.close();
  console.log(failures ? failures + ' failed' : 'all passed');
  process.exit(failures ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });

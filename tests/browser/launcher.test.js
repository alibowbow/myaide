/*
  The home screen (app/src/main/assets/decor/launcher.html with board.js and talk.js) in Chromium, as the phone's WebView
  shows it, with the app's bridge left out (the page then shows sample apps). Each check is a thing that went wrong on the
  phone once (준이 찾은 것), so it is caught here before a release:
    - on a page of the user's own (LP turntable), the hidden dock took the touches at the bottom ('카메라'·'갤러리')
    - 퍼레 dragged its heart button along as it walked
    - 퍼레 playing alone says small words now and then (talk.js), never words of a pool that is missing
    - in the evening 퍼레 stayed in glasses with a book (a lasting outfit that looks like doing something)
  Run: npm test (CI), or node launcher.test.js where Playwright and its Chromium are installed.
*/
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path'), assert = require('assert');
const DECOR = path.join(__dirname, '../../app/src/main/assets/decor');
const KNOB = '<html><body style="margin:0;height:100vh;background:#3a2a1a"><div id="k" style="position:absolute;left:0;right:0;bottom:0;height:320px;touch-action:none"></div>'
  + '<script>k.addEventListener("pointerdown",()=>parent.postMessage("knob","*"))</script></body></html>';
let failed = 0;
async function check(name, fn) {
  try { await fn(); console.log('PASS', name); } catch (e) { failed++; console.log('FAIL', name, '\n   ', e.message); }
}

async function open(browser, at) {
  const ctx = await browser.newContext({ viewport: { width: 412, height: 900 }, hasTouch: true, timezoneId: 'Asia/Seoul' });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.clock.install({ time: new Date(at) });
  // The same run every time: chance from a fixed seed, and no CSS easing (it runs on real time, not the test's clock).
  await ctx.addInitScript(() => {
    let seed = 20261126; Math.random = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
    addEventListener('DOMContentLoaded', () => { const st = document.createElement('style'); st.textContent = '*,*::before,*::after{transition:none!important}'; document.head.appendChild(st); });
  });
  await page.route('**/*', route => {
    const u = new URL(route.request().url());
    if (u.host === 'gyeot.local') {
      if (u.pathname === '/') {
        const html = fs.readFileSync(path.join(DECOR, 'launcher.html'), 'utf8')
          .replace('/*GYEOT*/', 'window.GYEOT=' + JSON.stringify({ launcher: true, pages: [{ src: 'https://piece.test/lp.html', title: 'LP' }], bg: 'aurora' }) + ';');
        return route.fulfill({ body: html, contentType: 'text/html' });
      }
      const f = path.join(DECOR, path.basename(u.pathname));
      if (fs.existsSync(f)) return route.fulfill({ body: fs.readFileSync(f), contentType: f.endsWith('.js') ? 'text/javascript' : f.endsWith('.css') ? 'text/css' : 'application/octet-stream' });
    }
    if (u.host === 'piece.test') return route.fulfill({ body: KNOB, contentType: 'text/html' });
    return route.fulfill({ status: 404, body: '' });
  });
  await page.goto('https://gyeot.local/');
  await page.clock.runFor(1500);
  await page.evaluate(() => {
    launcher.apps({ stacks: [{ id: 'photo', name: '사진' }], apps: [{ id: 'cam', label: '카메라', pkg: 'a.cam', stack: 'photo' }, { id: 'gal', label: '갤러리', pkg: 'a.gal', stack: 'photo' }], dock: ['cam', 'gal'], recent: [] });
    launcher.board({ weather: { code: 2, temp: 12, day: false }, env: { battery: 80 }, life: { heart: 60 } });
    window._solo = []; window.boardThink = why => window._solo.push(why);
  });
  await page.clock.runFor(800);
  return { page, ctx, errors };
}

(async () => {
  const browser = await chromium.launch();

  await check('talk.js has words for every play and every feeling a play shows', async () => {
    global.localStorage = { getItem: () => null, setItem() {} };
    const TALK = new Function(fs.readFileSync(path.join(DECOR, 'talk.js'), 'utf8') + '; return TALK;')();
    const board = fs.readFileSync(path.join(DECOR, 'board.js'), 'utf8');
    const plays = board.slice(board.indexOf('const PLAYS = {'), board.indexOf('// A few motions in a row'));
    const kinds = [...plays.matchAll(/^\s{4}(\w+): end =>/mg)].map(m => m[1]);
    assert(kinds.length >= 100, 'plays: ' + kinds.length);
    const thin = kinds.filter(k => TALK.lines('play:' + k).length < 4);
    assert.deepStrictEqual(thin, [], 'plays with fewer than four lines');
    const groups = new Set([...board.matchAll(/: "(joy|laugh|surprise|proud|calm|dizzy|sleepy|shy|curious|sad|cold|hot|love|sneaky|food|ouch|fume|sing)"/g)].map(m => m[1]));
    const missing = [...groups].filter(g => TALK.lines('feel:' + g).length === 0).concat(['solo', 'done', 'roam'].filter(p => TALK.lines(p).length === 0));
    assert.deepStrictEqual(missing, [], 'pools with no words');
  });

  const day = await open(browser, '2026-11-26T14:00:00+09:00');
  await check('the home screen opens without a script error', async () => assert.deepStrictEqual(day.errors, []));
  await check('on the LP page the bottom of the screen belongs to the page, not the hidden dock', async () => {
    const p = day.page;
    await p.evaluate(() => { window._knob = 0; addEventListener('message', e => { if (e.data === 'knob') window._knob++; }); });
    const r = await p.evaluate(() => { const s = document.getElementById('shelf').getBoundingClientRect(); return { x: s.left + s.width / 2, y: s.top + s.height / 2 }; });
    await p.evaluate(() => toPage(2, false));
    try {
      await p.clock.runFor(1500);
      // The page in its frame loads on real time (a slow machine takes a while).
      await p.frameLocator('iframe').locator('#k').waitFor({ timeout: 15000 });
      assert.strictEqual(await p.evaluate(([x, y]) => document.elementFromPoint(x, y).tagName, [r.x, r.y]), 'IFRAME');
      await p.touchscreen.tap(r.x, r.y);
      for (let i = 0; i < 20 && !(await p.evaluate(() => window._knob)); i++) { await p.clock.runFor(100); await p.waitForTimeout(50); }
      assert.strictEqual(await p.evaluate(() => window._knob), 1, 'the page got the touch');
      assert.strictEqual(await p.evaluate(() => document.getElementById('tip').classList.contains('on')), false, 'no dock name over the page');
    } finally { await p.evaluate(() => toPage(1, false)); await p.clock.runFor(300); }
  });
  await check('퍼레 plays alone with small words, and its heart stays in the corner', async () => {
    const p = day.page;
    await p.evaluate(() => {
      window._bubbles = []; window._moved = false; window._heartMoved = false;
      const b = document.querySelector('.bubble'), buddy = document.querySelector('.buddy'), heart = document.querySelector('.care-btn');
      new MutationObserver(() => { if (b.classList.contains('on') && b.classList.contains('sfx') && b.textContent) window._bubbles.push(b.textContent); }).observe(b, { attributes: true, childList: true, subtree: true, characterData: true });
      setInterval(() => { if (/translate\((?!0px, 0px)/.test(buddy.style.transform)) window._moved = true; if (heart.style.transform || getComputedStyle(heart).transform !== 'none') window._heartMoved = true; }, 200);
    });
    for (let i = 0; i < 180; i++) await p.clock.runFor(500);
    const got = await p.evaluate(() => ({ bubbles: window._bubbles, moved: window._moved, heart: window._heartMoved }));
    assert(got.bubbles.length >= 2, 'words in 90 s of play: ' + got.bubbles.length);
    assert(got.moved, '퍼레 walked');
    assert(!got.heart, 'the heart stayed');
    assert.deepStrictEqual(day.errors, []);
  });
  await check('a question in 퍼레\'s bubble comes with choices; a tap answers it', async () => {
    const p = day.page;
    await p.evaluate(() => { window._solo = []; launcher.thought('오늘 저녁은 뭐 먹을 거야?\n고르기: 치킨 / 라면 / 아직 몰라'); });
    await p.clock.runFor(2500);
    const picks = await p.evaluate(() => Array.from(document.querySelectorAll('.bubble .pick')).map(x => x.textContent));
    assert.deepStrictEqual(picks, ['치킨', '라면', '아직 몰라']);
    const box = await p.evaluate(() => { const r = document.querySelector('.bubble .pick').getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2, h: r.height }; });
    assert(box.h <= 30, 'small chips: ' + box.h);
    await p.touchscreen.tap(box.x, box.y);
    await p.clock.runFor(1000);
    const asked = await p.evaluate(() => window._solo.find(w => w.startsWith('answer:')) || '');
    assert(asked.startsWith('answer:오늘 저녁은 뭐 먹을 거야?') && asked.endsWith('치킨'), asked);
    // A question of its own words (no AI choices) gets choices that fit it; a sound does not.
    await p.evaluate(() => { launcher.thought('오늘 기분 점수는 몇 점이야?'); });
    await p.clock.runFor(2500);
    assert.deepStrictEqual(await p.evaluate(() => Array.from(document.querySelectorAll('.bubble .pick')).map(x => x.textContent)), ['100점!', '70점쯤', '별로…']);
    await p.evaluate(() => { launcher.thought('오잉?'); });
    await p.clock.runFor(1500);
    assert.strictEqual(await p.evaluate(() => document.querySelectorAll('.bubble .pick').length), 0);
  });
  await check('퍼레\'s toys fall and rest on the board; the dice says its number; the puzzle cube can be solved', async () => {
    const p = day.page;
    await p.evaluate(() => { document.querySelector('.bubble').classList.remove('on', 'ask'); ['ball', 'dice', 'cube'].forEach(k => boards[0]._toys.out(k)); });
    for (let i = 0; i < 20; i++) await p.clock.runFor(100);
    const rest = await p.evaluate(() => { const f = document.querySelector('.lib-head').getBoundingClientRect().top; return Array.from(document.querySelectorAll('.tb.ball, .tb.dice, .tb.cube')).map(e => ({k: e.className, gap: Math.round(f - e.getBoundingClientRect().bottom)})); });
    assert.strictEqual(rest.length, 3);
    for (const t of rest) assert(t.gap >= -2 && t.gap < 120, t.k + ' rests above App Library: ' + t.gap);
    const dice = await p.evaluate(() => { const r = document.querySelector('.tb.dice').getBoundingClientRect(); return {x: r.left + 8, y: r.top + 8}; });
    await p.touchscreen.tap(dice.x, dice.y);
    for (let i = 0; i < 30; i++) await p.clock.runFor(100);
    const pips = await p.evaluate(() => document.querySelectorAll('.tb.dice i.on').length), said = await p.evaluate(() => document.querySelector('.bubble').textContent);
    assert(pips >= 1 && pips <= 6, 'pips ' + pips);
    assert(said.startsWith(String(pips)), 'said ' + said + ' for ' + pips);
    const cube = await p.evaluate(() => { const r = document.querySelector('.tb.cube').getBoundingClientRect(); return {x: r.left + 10, y: r.top + 10}; });
    await p.touchscreen.tap(cube.x, cube.y);
    await p.clock.runFor(200);
    const lights = await p.evaluate(() => Array.from(document.querySelectorAll('.tpanel .tg button')).map(b => b.classList.contains('on')));
    assert.strictEqual(lights.length, 9); assert(lights.some(Boolean));
    let solution = -1;
    for (let m = 0; m < 512 && solution < 0; m++) { const L = lights.slice(); for (let i = 0; i < 9; i++) if (m >> i & 1) { const r = Math.floor(i / 3), c = i % 3; [[r, c], [r - 1, c], [r + 1, c], [r, c - 1], [r, c + 1]].forEach(([y, x]) => { if (y >= 0 && y < 3 && x >= 0 && x < 3) L[y * 3 + x] = !L[y * 3 + x]; }); } if (L.every(v => !v)) solution = m; }
    assert(solution >= 0, 'solvable');
    for (let i = 0; i < 9; i++) if (solution >> i & 1) await p.evaluate(i => document.querySelector('.tpanel .tg button[data-i="' + i + '"]').click(), i);
    await p.clock.runFor(300);
    assert(/다 껐다/.test(await p.evaluate(() => document.querySelector('.tpanel .tn').textContent)));
    assert(/풀었/.test(await p.evaluate(() => document.querySelector('.bubble').textContent)));
    assert.deepStrictEqual(day.errors, []);
  });
  await day.ctx.close();

  const evening = await open(browser, '2026-11-26T20:30:00+09:00');
  await check('in the evening 퍼레 wears nothing that looks like doing something for hours', async () => {
    const wear = await evening.page.evaluate(() => Array.from(document.querySelector('.bot').classList).filter(c => c.startsWith('w-')));
    for (const thing of ['w-roundglasses', 'w-book', 'w-heartglasses', 'w-cape', 'w-nerdglasses', 'w-mustache']) assert(!wear.includes(thing), thing + ' in ' + wear.join(' '));
  });
  await evening.ctx.close();

  await browser.close();
  console.log(failed ? failed + ' failed' : 'all passed');
  process.exit(failed ? 1 : 0);
})();

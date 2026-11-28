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
  + '<script>k.addEventListener("pointerdown",()=>parent.postMessage("knob","*"));'
  + 'addEventListener("message",e=>{if(e.data&&e.data.gyeot==="insets")parent.postMessage({heard:e.data,from:e.origin},"*")})</script></body></html>';
/* The LP page as the app frames it (https://lp.gyeot.local, PieceExtras.frameUrl): it tells the home screen when it plays (vinyl.js
   report), and keeps what the home screen asks of it (the media notification's buttons, launcher.lp). */
const LP = '<html><body style="margin:0;height:100vh;background:#2a1a10"><script>window.asked=[];window.say=(on,title)=>parent.postMessage({gyeot:"playing",on,title},"https://gyeot.local");'
  + 'addEventListener("message",e=>{if(e.origin==="https://gyeot.local"&&e.data&&e.data.gyeot==="lp")asked.push(e.data.cmd)})</script></body></html>';
let failed = 0;
async function check(name, fn) {
  try { await fn(); console.log('PASS', name); } catch (e) { failed++; console.log('FAIL', name, '\n   ', e.message); }
}

/** bridge: the app's bridge played by the page itself, each call kept in window._calls as [name, args] (otherwise none). */
async function open(browser, at, bridge, pages, scale, config) {
  const ctx = await browser.newContext({ viewport: { width: 412, height: 900 }, hasTouch: true, deviceScaleFactor: scale || 1, timezoneId: 'Asia/Seoul' });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.clock.install({ time: new Date(at) });
  // The same run every time: chance from a fixed seed, and no CSS easing (it runs on real time, not the test's clock).
  await ctx.addInitScript(() => {
    let seed = 20261126; Math.random = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
    addEventListener('DOMContentLoaded', () => { const st = document.createElement('style'); st.textContent = '*,*::before,*::after{transition:none!important}'; document.head.appendChild(st); });
  });
  if (bridge) await ctx.addInitScript(() => { window._calls = []; window.Gyeot = { call: (token, name, args) => window._calls.push([name, args]) }; });
  await page.route('**/*', route => {
    const u = new URL(route.request().url());
    if (u.host === 'gyeot.local') {
      if (u.pathname === '/') {
        const html = fs.readFileSync(path.join(DECOR, 'launcher.html'), 'utf8')
          .replace('/*GYEOT*/', 'window.GYEOT=' + JSON.stringify(Object.assign({ launcher: true, pages: pages || [{ src: 'https://piece.test/lp.html', frame: 'https://lp.piece.test/lp.html', title: 'LP' }], bg: 'aurora', token: bridge ? 'test' : undefined }, config || {})) + ';');
        return route.fulfill({ body: html, contentType: 'text/html' });
      }
      const f = path.join(DECOR, path.basename(u.pathname));
      if (fs.existsSync(f)) return route.fulfill({ body: fs.readFileSync(f), contentType: f.endsWith('.js') ? 'text/javascript' : f.endsWith('.css') ? 'text/css' : 'application/octet-stream' });
    }
    if (u.host === 'piece.test' || u.host === 'lp.piece.test') return route.fulfill({ body: KNOB, contentType: 'text/html' });
    if (u.host === 'lp.gyeot.local') return route.fulfill({ body: LP, contentType: 'text/html' });
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
    // Coming back, opening the screen or returning from an app: 퍼레 says its own thing, it does not quiz the user about what it cannot know.
    const asks = TALK.pools().filter(k => k === 'hi' || k === 'back' || k.startsWith('app:')).flatMap(k => TALK.lines(k).filter(l => /\?\s*[~!]*$/.test(l)).map(l => k + ': ' + l));
    assert.deepStrictEqual(asks, [], 'questions in the greetings and the app pools');
  });

  const day = await open(browser, '2026-11-26T14:00:00+09:00');
  await check('the home screen opens without a script error', async () => assert.deepStrictEqual(day.errors, []));
  // 준: "우측 lp페이지 전환도 못알아들어": 퍼레's home_page tool turns to a page by its name.
  await check('퍼레 turns the home screen to a page by name: LP, 홈, 오른쪽, 오늘', async () => {
    const p = day.page, at = () => p.evaluate(() => pageAt);
    assert.strictEqual(await p.evaluate(() => launcher.goPage('LP')), true); assert.strictEqual(await at(), 2, 'LP');
    await p.evaluate(() => launcher.goPage('홈')); assert.strictEqual(await at(), 1, '홈');
    await p.evaluate(() => launcher.goPage('오른쪽 페이지')); assert.strictEqual(await at(), 2, '오른쪽');
    await p.evaluate(() => launcher.goPage('오늘')); assert.strictEqual(await at(), 0, '오늘');
    assert.strictEqual(await p.evaluate(() => launcher.goPage('없는 페이지')), false);
    await p.evaluate(() => launcher.goPage('홈')); await p.clock.runFor(600);
  });
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
    // Laid out as a bubble (준: "말풍선 배치가 엉망이야"): the words in lines of several, the chips under them inside it, on screen.
    const lay = await p.evaluate(() => {
      const b = document.querySelector('.buddy .bubble'), r = b.getBoundingClientRect(), chips = Array.from(b.querySelectorAll('.pick')).map(x => x.getBoundingClientRect());
      return { left: r.left, right: r.right, width: r.width, vw: document.documentElement.clientWidth, inside: chips.every(c => c.left >= r.left && c.right <= r.right && c.bottom <= r.bottom + 1), below: chips.every(c => c.top > r.top + 18) };
    });
    assert(lay.width >= 180, 'a bubble wide enough for words: ' + lay.width);
    assert(lay.left >= 0 && lay.right <= lay.vw, 'on screen: ' + lay.left + '..' + lay.right);
    assert(lay.inside && lay.below, 'the chips under the words, inside the bubble');
    // Over the toys (준: "말풍선이 장난감아래로 가서 가려져"): a toy lying where the bubble is stays under it.
    const over = await p.evaluate(() => { const c = document.querySelector('.bubble .pick').getBoundingClientRect(), tb = document.querySelector('.toybox'), w = tb.getBoundingClientRect(), t = document.createElement('div');
      t.className = 'tb dice'; t.style.cssText = 'width:44px;height:44px;background:#000;transform:translate(' + (c.left - w.left - 12) + 'px,' + (c.top - w.top - 12) + 'px)'; tb.appendChild(t);
      const at = document.elementFromPoint(c.left + c.width / 2, c.top + c.height / 2); t.remove(); return at ? (at.closest('.bubble') ? 'bubble' : at.className) : 'nothing'; });
    assert.strictEqual(over, 'bubble', 'the bubble above a toy lying under it');
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
    // 퍼레's own ready words never wait for an answer (준: "맥락과 무관한 무의미한 질문들이 반복돼": "새로운 거 알게 됐어?" + 응!/아니/글쎄~).
    await p.evaluate(() => { speak(boards[0], {text: '오늘은 어떤 하루였어?'}, 3000); });
    await p.clock.runFor(500);
    assert.strictEqual(await p.evaluate(() => document.querySelectorAll('.bubble .pick').length), 0, 'no choices under a ready-made line');
  });
  await check('퍼레\'s toys fall and rest on the board; the dice says its number; the puzzle cube can be solved', async () => {
    const p = day.page;
    await p.evaluate(() => { document.querySelector('.bubble').classList.remove('on', 'asking'); const T = boards[0]._toys; ['ball', 'dice', 'cube'].forEach((k, i) => { const it = T.out(k); it.x = 80 + i * 110; it.vx = 0; }); T.wake(); });
    for (let i = 0; i < 20; i++) await p.clock.runFor(100);
    // Each rests on something: App Library's heading, or a widget, 퍼레 or another toy it fell on.
    const rest = await p.evaluate(() => { const f = document.querySelector('.lib-head').getBoundingClientRect().top; return Array.from(document.querySelectorAll('.tb.ball, .tb.dice, .tb.cube')).map(e => { const r = e.getBoundingClientRect(), on = document.elementsFromPoint(r.left + r.width / 2, r.bottom + 3).find(x => x !== e && x.closest && (x.closest('.when') || x.closest('.card') || x.closest('.lib-head') || x.closest('.buddy') || x.closest('.tb'))), it = boards[0]._toys.find(e.className.split(' ')[1]); return {k: e.className, gap: Math.round(f - r.bottom), on: !!on && !!it && it.rest > 0}; }); });
    assert.strictEqual(rest.length, 3);
    for (const t of rest) assert((t.gap >= -2 && t.gap < 120) || t.on, t.k + ' rests on something: ' + JSON.stringify(t));
    const dice = await p.evaluate(() => { const r = document.querySelector('.tb.dice').getBoundingClientRect(); return {x: r.left + 8, y: r.top + 8}; });
    await p.evaluate(() => { window._said = []; const b = document.querySelector('.bubble'); new MutationObserver(() => window._said.push(b.textContent)).observe(b, {childList: true, characterData: true, subtree: true}); });
    await p.touchscreen.tap(dice.x, dice.y);
    // Until it lands (it rolls longer when it bounces off 퍼레's head: "헤딩!" first, then the number).
    for (let i = 0; i < 80; i++) { await p.clock.runFor(100); if (i >= 29 && await p.evaluate(() => !document.querySelector('.tb.dice').classList.contains('rolling') && window._said.some(t => /^\d/.test(t)))) break; }
    const pips = await p.evaluate(() => document.querySelectorAll('.tb.dice i.on').length), said = await p.evaluate(() => window._said);
    assert(pips >= 1 && pips <= 6, 'pips ' + pips);
    assert(said.some(t => t.startsWith(String(pips))), 'said ' + said.join(' / ') + ' for ' + pips);
    // The dice's number may still be in 퍼레's bubble (it lifts the top of the board): let it go, then tap a point of the cube that
    // is the cube on screen (not 퍼레 or a widget it rests on).
    await p.evaluate(() => document.querySelector('.bubble').classList.remove('on')); await p.clock.runFor(100);
    const cube = await p.evaluate(() => { const e = document.querySelector('.tb.cube'), r = e.getBoundingClientRect();
      for (const fy of [.5, .3, .7, .15, .85]) for (const fx of [.5, .3, .7, .15, .85]) { const x = r.left + r.width * fx, y = r.top + r.height * fy, at = document.elementFromPoint(x, y); if (at && e.contains(at)) return {x, y}; }
      return {x: r.left + r.width / 2, y: r.top + r.height / 2, blocked: (document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2) || {}).className}; });
    assert(!cube.blocked, 'the cube can be touched: ' + JSON.stringify(cube));
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
  await check('a talk that ended while the screen was away does not leave 퍼레 talking in place', async () => {
    const p = day.page, talking = () => p.evaluate(() => document.querySelector('.bot').classList.contains('talking'));
    await p.evaluate(() => launcher.voice('speaking', '하이~ 오늘도 준비됐어?'));
    await p.clock.runFor(500);
    assert.strictEqual(await talking(), true);
    await p.evaluate(() => launcher.voice('idle', ''));   // the app tells it on coming back (BuddyVoice.attach)
    assert.strictEqual(await talking(), false, 'told it ended');
    await p.evaluate(() => launcher.voice('speaking', '하이~'));
    for (let i = 0; i < 26; i++) await p.clock.runFor(5000);  // never told: over by itself after two minutes
    assert.strictEqual(await talking(), false, 'ended by itself');
  });
  // 준: "음성대화 너무 느려" — the words typed along with their sound (BuddyVoice tells how long it lasts), the mouth resting
  // between two sentences while nothing sounds.
  await check('the mouth moves only while it sounds: words typed over the sound, still between two sentences', async () => {
    const p = day.page, look = () => p.evaluate(() => ({ text: document.querySelector('.bubble').textContent, on: document.querySelector('.bubble').classList.contains('on'), talking: document.querySelector('.bot').classList.contains('talking') }));
    const line = '안녕 반가워 오늘 뭐 했어';
    await p.evaluate(l => launcher.voice('speaking', l, 1600), line);
    await p.clock.runFor(1200);
    let now = await look();
    assert(now.text.length < line.length && now.talking, 'still saying it at 1.2 s of 1.6: ' + JSON.stringify(now));
    await p.clock.runFor(500);
    now = await look();
    assert.strictEqual(now.text, line, 'all typed as its sound ends');
    await p.evaluate(() => launcher.voice('gap', ''));
    now = await look();
    assert(!now.talking && now.on && now.text === line, 'the mouth rests, the words stay: ' + JSON.stringify(now));
    // A sound that ended before the words were typed: they show whole at once.
    await p.evaluate(() => launcher.voice('speaking', '산책하기 딱 좋은 날이야', 3000));
    await p.clock.runFor(400);
    await p.evaluate(() => launcher.voice('gap', ''));
    now = await look();
    assert(!now.talking && now.text === '산책하기 딱 좋은 날이야', 'whole at the gap: ' + JSON.stringify(now));
    await p.evaluate(() => launcher.voice('listening', '')); await p.evaluate(() => launcher.voice('idle', ''));
    assert.deepStrictEqual(day.errors, []);
  });
  // 준: "퍼레의 사용자 상호작용" — the face from the words themselves (board.js feelOf), not the same happy face for every line;
  // its motion once the words are out, never while it plays on its own.
  await check('퍼레\'s face follows its words: teary for ㅠㅠ, surprised for 헉, a giggle as it says 히히', async () => {
    const p = day.page, has = c => p.evaluate(c => document.querySelector('.bot').classList.contains(c), c);
    const moving = c => p.evaluate(c => document.querySelector('.buddy').classList.contains(c), c);
    // Free to move first: 퍼레 plays on its own every few seconds (then its words move nothing, below; a play's own faces
    // would come over the words' too). None starts while this check runs (its care card counts as busy), and the one going
    // on (its walk there too) ends.
    await p.evaluate(() => { boards[0]._careOn = true; });
    for (let pass = 0; pass < 2; pass++) {
      for (let i = 0; i < 40 && await p.evaluate(() => boards[0]._inPlay()); i++) await p.clock.runFor(500);
      if (pass === 0) await p.clock.runFor(5000);
    }
    await p.evaluate(() => launcher.thought('흐잉… 우산이 젖었어 ㅠㅠ'));
    await p.clock.runFor(300);
    assert(await has('teary'), 'teary for ㅠㅠ');
    await p.clock.runFor(800);
    assert(await moving('droop'), 'its motion once the words are out');
    await p.clock.runFor(8000);
    await p.evaluate(() => launcher.thought('헉, 진짜?'));
    await p.clock.runFor(200);
    assert(await has('surprised'), 'surprised for 헉');
    await p.clock.runFor(8000);
    await p.evaluate(() => launcher.voice('speaking', '히히 좋아', 800));
    await p.clock.runFor(200);
    assert(await has('giggle'), 'a giggle as it says 히히');
    await p.evaluate(() => { launcher.voice('gap', ''); launcher.voice('idle', ''); });
    await p.clock.runFor(4000);
    await p.evaluate(() => launcher.thought('오늘 뭐 하고 놀까?'));
    await p.clock.runFor(200);
    assert(await has('happy'), 'words that call for nothing keep the plain happy face');
    await p.clock.runFor(8000);
    // Playing on its own: the face only.
    await p.evaluate(() => { window._wasInPlay = boards[0]._inPlay; boards[0]._inPlay = () => true; launcher.thought('히히 재밌다'); });
    await p.clock.runFor(700);
    assert(await has('giggle') && !(await moving('giggleshake')), 'no motion while it plays');
    await p.evaluate(() => { boards[0]._inPlay = window._wasInPlay; });
    await p.clock.runFor(8000);
    // 퍼레와 대화's 퍼레 too, as an answer comes.
    const pal = await p.evaluate(() => { pal.show(); pal.done(true, '헉, 그게 진짜야?'); const bot = document.querySelector('.chatpal .bot'); return bot && bot.classList.contains('surprised'); });
    assert(pal, 'the talk\'s 퍼레 is surprised for 헉');
    // Every face it may make is one of its faces, every motion one of its motions.
    const unknown = await p.evaluate(() => FEEL_OF.filter(([re, face, motion]) => !FACES[face] || motion && !MOTIONS.some(m => m[0] === motion)).map(x => x[1] + '/' + x[2]));
    assert.deepStrictEqual(unknown, []);
    await p.evaluate(() => { boards[0]._careOn = false; });
    assert.deepStrictEqual(day.errors, []);
  });
  await check('퍼레 is plain by default and dresses up now and then, never one outfit for long', async () => {
    const p = day.page, looks = [];
    // A play's own outfit (a headband for karate) is that play's, not the day's: the one going on ends first.
    for (let i = 0; i < 80 && await p.evaluate(() => !!boards[0]._dressed); i++) await p.clock.runFor(500);
    // Forty-minute turns through the daytime of a few days (its night things while it sleeps are left out).
    for (let k = 0; looks.length < 60; k++) {
      const t = Date.parse('2026-11-27T07:00:00+09:00') + k * 40 * 60000;
      if ((new Date(t).getUTCHours() + 9) % 24 < 7) continue;
      await p.clock.setSystemTime(new Date(t));
      looks.push(await p.evaluate(() => { launcher.env({battery: 80}); return Array.from(document.querySelector('.bot').classList).filter(c => c.startsWith('w-')).sort().join(' '); }));
    }
    const daytime = looks;
    const plain = daytime.filter(l => l === '').length, outfits = new Set(daytime.filter(Boolean));
    assert(plain >= daytime.length * .5, 'plain ' + plain + ' of ' + daytime.length);
    assert(plain < daytime.length, 'dressed up now and then');
    assert(outfits.size >= 3, 'outfits: ' + [...outfits].join(' | '));
    let run = 0, longest = 0; for (let k = 1; k < looks.length; k++) { run = looks[k] && looks[k] === looks[k - 1] ? run + 1 : 0; longest = Math.max(longest, run); }
    assert(longest <= 3, 'the same outfit ' + (longest + 1) + ' turns in a row');
  });
  await day.ctx.close();

  // The talk on a page of its own: what earlier checks left playing on the board does not reach it.
  const talk = await open(browser, '2026-11-26T14:00:00+09:00');
  await check('퍼레와 대화: 퍼레 lives on its strip, KakaoTalk dates and times, the box at the bottom, circling 퍼레 makes it dizzy', async () => {
    const p = talk.page;
    await p.evaluate(() => { boardChat(); });
    await p.clock.runFor(600);
    const look = await p.evaluate(() => ({pal: !!document.querySelector('.chatpal .buddy'), day: (document.querySelector('.kday') || {}).textContent, hello: (document.querySelector('.kb.ai') || {}).textContent,
      boxBelow: document.querySelector('.pad-top').getBoundingClientRect().top > document.querySelector('#padBody').getBoundingClientRect().top}));
    assert(look.pal, '퍼레 on the chat screen');
    assert(/^\d{4}년 \d+월 \d+일 .요일$/.test(look.day || ''), 'date pill: ' + look.day);
    assert(look.hello && !/세요|드릴게요|습니다/.test(look.hello), '퍼레\'s own words: ' + look.hello);
    assert(look.boxBelow, 'the box at the bottom');
    const times = await p.evaluate(() => { const now = Date.now(); thread.splice(0, thread.length, {who: 'me', text: '어제 일', at: now - 86400000}, {who: 'ai', text: '응!', at: now - 86400000 + 1000}, {who: 'me', text: '오늘 일', at: now}, {who: 'ai', text: '좋아!', at: now + 1000}); saveChats(); drawChat();
      return {days: document.querySelectorAll('.kday').length, times: Array.from(document.querySelectorAll('.krow time')).map(t => t.textContent), kept: JSON.parse(localStorage.getItem('gyeotChats')).list.some(c => c.turns.length === 4)}; });
    assert.strictEqual(times.days, 2, 'a pill for each day');
    assert(times.times.length === 4 && times.times.every(t => /^오[전후] \d{1,2}:\d\d$/.test(t)), 'times: ' + times.times.join(','));
    assert(times.kept, 'the talk is kept');
    // 퍼레 steps off the words drawn just now (pal.away after the next frame): circle it where it stands after that.
    await p.clock.runFor(400); await p.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))); await p.clock.runFor(100);
    const c = await p.evaluate(() => { const r = document.querySelector('.chatpal .buddy').getBoundingClientRect(); return {x: r.left + r.width / 2, y: r.top + r.height / 2}; });
    await p.mouse.move(c.x + 34, c.y); await p.mouse.down();
    for (let i = 1; i <= 64; i++) { const a = i / 64 * Math.PI * 2 * 2.4; await p.mouse.move(c.x + 34 * Math.cos(a), c.y + 34 * Math.sin(a)); }
    await p.mouse.up(); await p.clock.runFor(200);
    assert(/dizzy/.test(await p.evaluate(() => document.querySelector('.chatpal .bot').getAttribute('class'))), 'dizzy after two turns round it');
    // The whole talk is 퍼레's room (준: "윗공간을 퍼레영역으로 한정짓지말고", "채팅창 전체를 자유롭게"): no strip on top, and it
    // goes up and down as well as across.
    const room = await p.evaluate(() => { const s = el("stage").getBoundingClientRect(), b = el("padBody").getBoundingClientRect(); return {stageH: s.height, bodyTop: b.top, stageTop: s.top}; });
    assert(room.stageH > 300, 'the room is the whole talk: ' + room.stageH);
    assert(room.bodyTop <= room.stageTop + 2, 'the talk starts right under the bar');
    let ys = new Set();
    // (up to 90 s: after being made dizzy it may rest a while first; once it has moved up or down, enough)
    for (let i = 0; i < 90 && ys.size < 2; i++) { await p.clock.runFor(1000); ys.add(await p.evaluate(() => Math.round(document.querySelector('.chatpal').getBoundingClientRect().top / 20))); }
    assert(ys.size >= 2, '퍼레 goes up and down');
    // The voice talk from the box (준: "아래 입력창 우측에 버튼으로"): heard and said come into the talk.
    const mic = await p.evaluate(() => { const r = el("padMic").getBoundingClientRect(), s = el("padSend").getBoundingClientRect(); return {w: r.width, beside: Math.abs(r.top - s.top) < 4}; });
    assert(mic.w > 0 && mic.beside, 'the mic beside the send button');
    // The app's voice talk icon, and the talk on plain to see (준: "음성대화 아이콘(다른창에서 쓰는거)으로 해줘", "마이크를 눌러도 변화가
    // 없어 대화모드인지 알 수가 없어"): the app first answers "idle" as the talk is joined (BuddyVoice.attach), then starts it.
    assert.strictEqual(await p.evaluate(() => el("padMic").querySelectorAll("svg path").length), 5, 'the five bars of ic_wave');
    await p.evaluate(() => { el("padMic").click(); launcher.voice("idle", ""); });
    const on = await p.evaluate(() => ({on: el("padMic").classList.contains("on"), ring: el("padMic").closest(".pad-top").classList.contains("voicing"), hint: el("q").placeholder, moving: el("padMic").querySelector("svg path").getAnimations().length, ear: document.querySelector(".chatpal .buddy").classList.contains("listening")}));
    assert.deepStrictEqual(on, {on: true, ring: true, hint: '듣고 있어… 말해 봐', moving: 1, ear: true}, 'on at once, and still on after the joining idle');
    await p.evaluate(() => launcher.voice("listening", "오늘 날"));
    assert.strictEqual(await p.evaluate(() => el("q").value), '오늘 날', 'what it hears so far shows in the box');
    // Over music only words with 퍼레's name are taken, shown as a note (BuddyVoice.idle): the note is not words heard.
    await p.evaluate(() => launcher.voice("listening", "♪"));
    assert.strictEqual(await p.evaluate(() => el("q").value), '', 'the music note is not put in the box');
    await p.evaluate(() => launcher.voice("listening", "오늘 날"));
    await p.evaluate(() => { launcher.voice("thinking", "오늘 날씨 어때"); launcher.voice("speaking", "맑고 따뜻해!", 900); launcher.voice("gap", ""); launcher.voice("speaking", "산책하기 딱 좋아 ☀️", 1100); launcher.voice("done", ""); });
    const said = await p.evaluate(() => thread.slice(-2).map(t => t.who + ':' + t.text));
    assert.deepStrictEqual(said, ['me:오늘 날씨 어때', 'ai:맑고 따뜻해! 산책하기 딱 좋아 ☀️']);
    assert.strictEqual(await p.evaluate(() => el("padMic").classList.contains("on")), false, 'the mic is off when the talk ends');
    assert.strictEqual(await p.evaluate(() => el("q").placeholder), '퍼레에게 말하기');
    // 퍼레's bubble in the talk at a size to read (준: "대화창에서 퍼레 말풍선이 작아 글자도 작고"), on screen wherever 퍼레 is.
    for (const side of [0.1, 0.9]) {
      const read = await p.evaluate(x => { const c = document.querySelector('.chatpal'), s = el('stage').clientWidth; c.style.transition = 'none'; c.style.transform = 'translate(' + Math.round(s / .72 * x) + 'px,40px)'; document.querySelector('.chatpal .buddy').classList.toggle('flip', x > .5);
        speak(c, {text: '오늘 산책 어땠어? 날씨 좋더라'}, 4000); const b = c.querySelector('.bubble'), r = document.createRange(); r.selectNodeContents(b); const t = r.getClientRects()[0], br = b.getBoundingClientRect();
        return {line: Math.round(t.height), left: Math.round(br.left), right: Math.round(br.right), vw: document.documentElement.clientWidth}; }, side);
      assert(read.line >= 16, 'words of a readable size (old: 12): ' + JSON.stringify(read));
      assert(read.left >= 0 && read.right <= read.vw + 1, 'the bubble on screen: ' + JSON.stringify(read));
    }
    // Not over the talk (준: "대화를 안 가리게 해줘"): with a screen of words, 퍼레 and its bubble keep off them as it walks and talks.
    await p.evaluate(() => { const now = Date.now(); const lines = []; for (let i = 0; i < 9; i++) lines.push({who: 'me', text: '오늘 뭐 할까 ' + i, at: now + i * 120000}, {who: 'ai', text: '산책 어때? 날씨가 좋아서 걷기 딱이야 ' + i, at: now + i * 120000 + 1000});
      thread.splice(0, thread.length, ...lines); drawChat(); document.querySelector('.chatpal .bubble').classList.remove('on'); const c = document.querySelector('.chatpal'); c.style.transform = 'translate(40px,60px)'; });
    const over = [];
    for (let i = 0; i < 30; i++) {
      await p.clock.runFor(1000);
      over.push(...await p.evaluate(() => { const ink = Array.from(document.querySelectorAll('#padBody .kb, #padBody .kday')).map(e => e.getBoundingClientRect()), hit = r => ink.some(k => r.left < k.right && r.right > k.left && r.top < k.bottom && r.bottom > k.top);
        const c = document.querySelector('.chatpal'), m = c.style.transform.match(/translate\(([-\d.]+)px,\s*([-\d.]+)px\)/); if (!m) return ['no place'];
        const s = el('stage').getBoundingClientRect(), b = c.querySelector('.buddy'), box = {left: s.left + m[1] * .72, top: s.top + m[2] * .72, right: s.left + m[1] * .72 + b.offsetWidth * .72, bottom: s.top + m[2] * .72 + b.offsetHeight * .72};
        const out = []; if (!c.querySelector('.buddy').classList.contains('walking') && hit(box)) out.push('퍼레 at ' + m[1] + ',' + m[2]);
        const bub = c.querySelector('.bubble'); if (bub.classList.contains('on') && hit(bub.getBoundingClientRect())) out.push('bubble: ' + bub.textContent); return out; }));
    }
    assert.deepStrictEqual(over, [], 'nothing said under 퍼레 or its bubble');
    await p.evaluate(() => closePad()); await p.clock.runFor(400);
    assert.deepStrictEqual(talk.errors, []);
  });
  // 준: "음성상호작용을 더 강화해줘" — the voice talk from the box goes on from what was typed (BuddyVoice.start(seed)).
  const bridged = await open(browser, '2026-11-26T14:00:00+09:00', true);
  await check('퍼레와 대화: the voice talk goes on from the typed turns; 퍼레 held starts afresh', async () => {
    const p = bridged.page;
    await p.evaluate(() => boardChat()); await p.clock.runFor(600);
    await p.evaluate(() => { el("q").value = "내일 부산 가"; el("padSend").click(); });
    assert.deepStrictEqual(await p.evaluate(() => window._calls.filter(c => c[0] === "chat").map(c => JSON.parse(c[1])[0])), ['내일 부산 가'], 'typed');
    await p.evaluate(() => launcher.reply("좋겠다! 조심히 다녀와"));
    await p.evaluate(() => { window._calls.length = 0; el("padMic").click(); });
    const voice = await p.evaluate(() => window._calls.filter(c => c[0] === "voice").map(c => { const a = JSON.parse(c[1]); return a.length ? JSON.parse(a[0]) : 'nothing'; }));
    assert.deepStrictEqual(voice, [[['user', '내일 부산 가'], ['assistant', '좋겠다! 조심히 다녀와']]], 'the two typed turns go with it');
    await p.evaluate(() => { launcher.voice("done", ""); window._calls.length = 0; boardVoice(); });
    assert.deepStrictEqual(await p.evaluate(() => window._calls.filter(c => c[0] === "voice").map(c => JSON.parse(c[1]))), [['[]']], '퍼레 held: nothing typed');
    assert.deepStrictEqual(bridged.errors, []);
  });
  // 준: "퍼레의 판단력 … 강화해줘" — an app 퍼레 opened that did not come up is said once, and those words open the way through.
  // 준, 2026-11-27: "이 둘 중에 바로가기도 만들어줘", "일단 마이튜브 바로가기", "앱내 아이콘처럼 축약된 것 말고 좀더 홈 아이콘처럼".
  const shortsPage = await open(browser, '2026-11-27T22:33:00+09:00', true);
  await check('마이튜브, 업데이트 and 화면 꾸미기 as small home icons without names in the empty space above the dots; a tap opens each', async () => {
    const p = shortsPage.page;
    const s = await p.evaluate(() => [...document.querySelectorAll('#shorts [data-short]')].map(b => {
      const ic = b.querySelector('.ic').getBoundingClientRect(), dots = el('dots').getBoundingClientRect(), lib = el('stacks').getBoundingClientRect();
      return { id: b.dataset.short, w: ic.width, h: ic.height, text: b.textContent.trim(), name: b.getAttribute('aria-label'), bottom: b.getBoundingClientRect().bottom, dots: dots.top, below: ic.top >= lib.bottom - 1 };
    }));
    assert.deepStrictEqual(s.map(x => x.id), ['mytube', 'update', 'decor', 'rec']);
    for (const x of s) {
      assert(x.w <= 44 && x.h <= 44 && x.w >= 36, 'small, smaller than an app icon: ' + JSON.stringify(x));
      assert.strictEqual(x.text, '', 'no name under it');
      assert(/마이튜브|화면 꾸미기|업데이트|녹화·녹음/.test(x.name), 'its name for the screen reader: ' + x.name);
      assert(x.below && x.bottom <= x.dots, 'under App Library, above the dots: ' + JSON.stringify(x));
    }
    await p.evaluate(() => { window._calls.length = 0; ['mytube', 'update', 'decor'].forEach(id => document.querySelector('#shorts [data-short="' + id + '"]').click()); });
    assert.deepStrictEqual(await p.evaluate(() => window._calls.filter(c => c[0] === 'open' || c[0] === 'settings').map(c => c[0] + ':' + JSON.parse(c[1])[0])), ['open:mytube', 'open:update', 'settings:decor']);
    // 업데이트 (준: "업데이트 바로가기 버튼"): a red dot when the app found a newer build, gone when there is none.
    assert.strictEqual(await p.evaluate(() => document.querySelectorAll('#shorts .dot').length), 0, 'no dot when up to date');
    await p.evaluate(() => launcher.board({ weather: { code: 0, temp: 15, day: false }, env: { battery: 80 }, life: { heart: 60 }, update: '0.4.700' }));
    const dot = await p.evaluate(() => { const b = document.querySelector('#shorts [data-short="update"]'); return { dots: b.querySelectorAll('.dot').length, name: b.getAttribute('aria-label') }; });
    assert(dot.dots === 1 && /0\.4\.700/.test(dot.name), 'a newer build: a dot, and it says so: ' + JSON.stringify(dot));
    await p.evaluate(() => launcher.board({ weather: { code: 0, temp: 15, day: false }, env: { battery: 80 }, life: { heart: 60 } }));
    assert.strictEqual(await p.evaluate(() => document.querySelectorAll('#shorts .dot').length), 0, 'gone once updated');
    // 준: "홈화면 왼쪽 상단부 날짜를 누르면 구글캘린더가 켜지게", "날짜폰트 조금만 더 키우고 시간 표시는 약간 줄이고", "하단 독에 돋보기 버튼 없애줘".
    const when = await p.evaluate(() => { const d = document.querySelector('.hero .date'), c = document.querySelector('.hero .clock');
      return { go: d && d.dataset.go, tap: d && d.classList.contains('tap'), date: d && parseFloat(getComputedStyle(d).fontSize), clock: c && parseFloat(getComputedStyle(c).fontSize), find: document.querySelectorAll('#shelf .find-btn').length, pad: document.querySelectorAll('#shelf .pad-btn').length }; });
    assert(when.go === 'gcalendar' && when.tap, 'the date is a tap to Google Calendar: ' + JSON.stringify(when));
    assert(when.date >= 13.5 && when.clock <= 76, 'the date a little bigger, the time a little smaller: ' + JSON.stringify(when));
    assert(when.find === 0 && when.pad === 1, 'no 🔍 in the dock, Launchpad still there: ' + JSON.stringify(when));
    await p.evaluate(() => { window._calls.length = 0; document.querySelector('.hero .date').click(); });
    await p.waitForTimeout(300);
    assert.deepStrictEqual(await p.evaluate(() => window._calls.filter(c => c[0] === 'open').map(c => JSON.parse(c[1])[0])), ['gcalendar'], 'a tap on the date opens it');
    assert.deepStrictEqual(shortsPage.errors, []);
    await p.screenshot({ path: '/tmp/claude-0/-home-user/138b3987-18a5-5339-9471-114de8ffce30/scratchpad/shorts_home.png' });
    await shortsPage.ctx.close();
  });
  // 준, 2026-11-28: "녹화하기가 너무 번거로워. 홈에 바로가기를 만들자 그걸 누르면 위성처럼 여러가지 팝업아이콘이 뜨고 그중 하나가
  // 녹화하기야", "팝업아이콘 구성은 녹화하기 녹음하기 …", then "화면캡쳐는 굳이 안 만들어도 되겠다".
  // At a phone's pixel density, so the preview for 준 is sharp.
  const satPage = await open(browser, '2026-11-28T10:00:00+09:00', true, null, 2.625);
  await check('녹화·녹음: a tap springs out 녹화하기 · 녹음하기 in an arc with tiny names, inside the screen at the row\'s edges; a tap outside or on it again folds them; each asks the app; a red dot and 정지 while one records', async () => {
    const p = satPage.page, SHOTS = '/tmp/claude-0/-home-user/138b3987-18a5-5339-9471-114de8ffce30/scratchpad/satellite';
    fs.mkdirSync(SHOTS, { recursive: true });
    const recAt = () => p.evaluate(() => { const r = document.querySelector('#shorts [data-short="rec"] .ic').getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2, top: r.top, w: r.width }; });
    const sat = () => p.evaluate(() => [...document.querySelectorAll('#sat .sat-b')].map(b => {
      const r = b.getBoundingClientRect(), o = b.querySelector('.o').getBoundingClientRect(), l = b.querySelector('.l').getBoundingClientRect();
      return { act: b.dataset.act, label: b.querySelector('.l').textContent, stop: b.classList.contains('stop'), left: Math.min(r.left, l.left), right: Math.max(r.right, l.right), top: o.top, bottom: l.bottom, d: o.width, x: o.left + o.width / 2, y: o.top + o.height / 2 };
    }));
    const isOpen = () => p.evaluate(() => el('sat').classList.contains('on'));
    const tapRec = async () => { const r = await recAt(); await p.touchscreen.tap(r.x, r.y); await p.clock.runFor(400); };
    const inside = (items, why) => { const W = 412; for (const x of items) assert(x.left >= 0 && x.right <= W && x.top >= 0, why + ': ' + JSON.stringify(x)); };

    const r = await recAt();
    assert(r.w <= 44 && r.w >= 36, 'the shortcut is as small as the others: ' + r.w);
    await tapRec();
    assert(await isOpen(), 'a tap opens the satellite');
    let items = await sat();
    assert.deepStrictEqual(items.map(x => x.label), ['녹화하기', '녹음하기']);
    assert.deepStrictEqual(items.map(x => x.act), ['screen', 'audio']);
    for (const x of items) assert(x.d >= 36 && x.d <= 42, 'small round icons: ' + x.d);
    for (const x of items) assert(x.bottom <= r.top - 6, 'above the shortcut, names clear of the row: ' + JSON.stringify(x));
    assert(Math.abs(items[0].y - items[1].y) < 1 && items[0].x < r.x && items[1].x > r.x, 'an arc above it, one on each side: ' + JSON.stringify(items));
    const dist = items.map(x => Math.round(Math.hypot(x.x - r.x, x.y - r.y)));
    assert(dist.every(v => Math.abs(v - dist[0]) <= 2), 'on one circle around it: ' + dist);
    inside(items, 'inside the screen');
    await p.screenshot({ path: SHOTS + '/satellite_open.png' });
    // A tap outside folds them; so does a tap on the shortcut again.
    await p.touchscreen.tap(40, 260); await p.clock.runFor(400);
    assert(!(await isOpen()), 'a tap outside folds them');
    await tapRec(); assert(await isOpen());
    await tapRec(); assert(!(await isOpen()), 'a tap on the shortcut again folds them');
    // Back folds them first.
    await tapRec(); assert.strictEqual(await p.evaluate(() => launcher.back()), true); await p.clock.runFor(400); assert(!(await isOpen()));
    // Each asks the app; the satellite folds at once.
    for (const act of ['screen', 'audio']) {
      await tapRec();
      await p.evaluate(() => { window._calls.length = 0; });
      const b = (await sat()).find(x => x.act === act);
      await p.touchscreen.tap(b.x, b.y);
      assert.deepStrictEqual(await p.evaluate(() => window._calls.filter(c => c[0] === 'record').map(c => JSON.parse(c[1])[0])), [act]);
      assert(!(await isOpen()), 'folded at once after ' + act + ' (before the sheet or the microphone question comes up)');
    }
    // At the row's edges the arc turns away from the edge and every icon with its name stays on screen.
    for (const side of ['flex-end', 'flex-start']) {
      await p.evaluate(s => { const box = el('shorts'), b = box.querySelector('[data-short="rec"]'); box.style.justifyContent = s; box.style.padding = '6px 2px';
        if (s === 'flex-start') box.prepend(b); else box.append(b); }, side);
      await tapRec();
      items = await sat();
      inside(items, 'at the ' + side + ' edge');
      const here = await recAt();
      assert(items.every(x => x.bottom <= here.top - 6), 'still above the row, clear of the other icons: ' + JSON.stringify(items));
      if (side === 'flex-end') await p.screenshot({ path: SHOTS + '/satellite_edge.png' });
      await tapRec();
    }
    await p.evaluate(() => { el('shorts').style.cssText = ''; drawShorts(); });
    // While 화면 녹화 is on: a red dot on the shortcut, and 녹화 정지 in its place.
    assert.strictEqual(await p.evaluate(() => document.querySelectorAll('#shorts [data-short="rec"] .dot').length), 0, 'no dot when nothing records');
    await p.evaluate(() => launcher.record({ screen: 'on', audio: '' }));
    const dot = await p.evaluate(() => { const b = document.querySelector('#shorts [data-short="rec"]'); return { dots: b.querySelectorAll('.dot.live').length, name: b.getAttribute('aria-label') }; });
    assert(dot.dots === 1 && /화면 녹화 중/.test(dot.name), 'a red dot, and it says so: ' + JSON.stringify(dot));
    await tapRec();
    items = await sat();
    assert.deepStrictEqual(items.map(x => x.label), ['녹화 정지', '녹음하기']);
    assert(items[0].stop, '정지 in red');
    assert.strictEqual(await p.evaluate(() => getComputedStyle(el('satVeil')).opacity), '1', 'the rest of the screen dims under it');
    await p.screenshot({ path: SHOTS + '/satellite_recording.png' });
    await p.evaluate(() => { window._calls.length = 0; });
    await p.touchscreen.tap(items[0].x, items[0].y);
    assert.deepStrictEqual(await p.evaluate(() => window._calls.filter(c => c[0] === 'record').map(c => JSON.parse(c[1])[0])), ['screenStop']);
    // 녹음 paused: still the dot, 녹음 정지 in its place; the open satellite follows the app's word at once.
    await tapRec();
    await p.evaluate(() => launcher.record({ screen: '', audio: 'paused' }));
    assert.deepStrictEqual((await sat()).map(x => x.label), ['녹화하기', '녹음 정지']);
    assert(/녹음 일시정지/.test(await p.evaluate(() => document.querySelector('#shorts [data-short="rec"]').getAttribute('aria-label'))));
    await tapRec();
    await p.evaluate(() => launcher.record({ screen: '', audio: '' }));
    assert.strictEqual(await p.evaluate(() => document.querySelectorAll('#shorts .dot').length), 0, 'gone when nothing records');
    assert.deepStrictEqual(satPage.errors, []);
    await satPage.ctx.close();
  });
  // 준, 2026-11-27: "배민에서 배달한걸 못알아채네" (the status bar said 조리완료, 퍼레 said "휴, 땀 났다").
  const food = await open(browser, '2026-11-27T17:55:00+09:00', true);
  await check('food on its way (배민 조리완료): the small pill says when it comes (a tap opens 배민); 퍼레 says it in passing, once as it sets off and once as it comes, each gone in a few seconds', async () => {
    const p = food.page;
    const board = (stage, line, min) => p.evaluate(([stage, line, min]) => { const at = Date.now(); launcher.board({ weather: { code: 0, temp: 15, day: false }, env: { battery: 80 }, life: { heart: 60 },
      delivery: { app: 'com.sampleapp', name: '배민', stage, eta: at + min * 60000, when: '6:40', at, line } }); }, [stage, line, min]);
    const bubble = () => p.evaluate(() => { const b = document.querySelector('.bubble'); return b.classList.contains('on') ? b.textContent : ''; });
    await p.clock.runFor(60000);                       // the opening greeting well past (one word in 45 s)
    await board('ready', '음식 다 됐대, 라이더님 기다리는 중', 45);
    await p.clock.runFor(1500);
    assert(!(await bubble()).includes('음식 다 됐대'), 'the stages before it sets off are not said');
    const pill = await p.evaluate(() => { const b = document.querySelector('.mini-pill.deliv'); if (!b) return null; const r = b.getBoundingClientRect(); return {text: b.textContent.trim(), h: Math.round(r.height)}; });
    assert(pill && pill.text === '배민 6:40 도착', 'the pill: ' + JSON.stringify(pill));
    assert(pill.h <= 40, 'a small pill: ' + pill.h);
    await p.evaluate(() => { window._calls.length = 0; document.querySelector('.mini-pill.deliv').click(); });
    assert.deepStrictEqual(await p.evaluate(() => window._calls.filter(c => c[0] === 'open').map(c => JSON.parse(c[1])[0])), ['delivery:com.sampleapp']);
    await p.clock.runFor(50000);
    await board('onway', '배달 출발했대, 6:40쯤 온대 🛵', 20);
    await p.clock.runFor(1200);
    assert((await bubble()).includes('출발했대'), 'said as it sets off');
    await p.clock.runFor(3000);
    assert.strictEqual(await bubble(), '', 'gone in a few seconds');
    await p.clock.runFor(50000);
    await board('near', '배달 곧 온대!', 3);
    await p.clock.runFor(1500);
    assert(!(await bubble()).includes('곧 온대'), 'close by: not said again');
    await p.clock.runFor(50000);
    await board('done', '배달 왔대, 맛있게 먹어 🍚', 0);
    await p.clock.runFor(1200);
    assert((await bubble()).includes('배달 왔대'), 'said as it comes');
    assert.strictEqual(await p.evaluate(() => document.querySelector('.mini-pill.deliv').textContent.trim()), '배민 배달 완료');
    await food.ctx.close();
  });
  // 준's plan, 알림을 행동으로 (stage 1): a parcel out today, a booking made, a plan set in a messenger, read from the phone's
  // notices (core/NoticeActions) and each one tap of action; said in passing as the food is.
  const acts = await open(browser, '2026-11-27T13:05:00+09:00', true);
  await check('알림을 행동으로: at most two small pills, the most useful first, each one tap; every event said once in passing and gone in a few seconds', async () => {
    const p = acts.page;
    const plan = { kind: 'plan', stage: 'set', key: 'm1a2b', line: '민수랑 내일 19:00 약속 생겼네', lineKey: 'act:plan:m1a2b:set', go: 'act:calendar:m1a2b', rank: 88,
      pills: [{ label: '약속', word: '일정에 넣기', go: 'act:calendar:m1a2b', rank: 72, icon: 'calendar' }, { label: '민수', word: '답장', go: 'act:reply:m1a2b', rank: 88, icon: 'reply' }] };
    const out = { kind: 'parcel', stage: 'out', key: 'p3c4d', line: '‘무선 이어폰’ 오늘 2~4시쯤 온대 📦', lineKey: 'act:parcel:p3c4d:out', go: 'act:open:p3c4d', rank: 78,
      pills: [{ label: '택배', word: '오늘 도착', go: 'act:open:p3c4d', rank: 78, icon: 'parcel' }] };
    const booked = { kind: 'booking', stage: 'booked', key: 'b5e6f', line: '', lineKey: 'act:booking:b5e6f:booked', go: 'act:calendar:b5e6f', rank: 70,
      pills: [{ label: 'KTX', word: '일정에 넣기', go: 'act:calendar:b5e6f', rank: 70, icon: 'calendar' }] };
    const board = list => p.evaluate(list => { const at = Date.now(); launcher.board({ weather: { code: 0, temp: 12, day: true }, env: { battery: 80 }, life: { heart: 60 }, noticeCount: 3,
      acts: list.map(a => Object.assign({ at }, a)) }); }, list);
    const bubble = () => p.evaluate(() => { const b = document.querySelector('.bubble'); return b.classList.contains('on') ? b.textContent : ''; });
    const pills = () => p.evaluate(() => [...document.querySelectorAll('#minis .mini-pill.act')].map(b => { const r = b.getBoundingClientRect(); return { text: b.textContent.trim(), go: b.dataset.go, h: Math.round(r.height), right: Math.round(r.right) }; }));
    await p.clock.runFor(60000);                       // the opening greeting well past (one word in 45 s)
    await board([out, booked, plan]);
    await p.clock.runFor(1200);
    let shown = await pills();
    assert.deepStrictEqual(shown.map(x => x.text), ['민수 답장', '택배 오늘 도착'], 'the two most useful: ' + JSON.stringify(shown));
    for (const x of shown) assert(x.h <= 40, 'a small pill: ' + JSON.stringify(x));
    assert(await p.evaluate(() => !!document.querySelector('#minis [data-mini="notices"]')), '알림 stays beside them');
    assert(await p.evaluate(() => Math.max(...[...document.querySelectorAll('#minis .mini-pill')].map(b => b.getBoundingClientRect().right)) <= innerWidth), 'nothing past the screen');
    assert(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'no page wider than the screen');
    assert.strictEqual(await bubble(), '민수랑 내일 19:00 약속 생겼네 ›', 'the plan said in passing (a tap on it fills the calendar)');
    await p.clock.runFor(3600);
    assert(!(await bubble()).includes('약속 생겼네'), 'gone in a few seconds');
    await p.evaluate(() => { window._calls.length = 0; [...document.querySelectorAll('#minis .mini-pill.act')].find(b => b.textContent.includes('택배')).click(); });
    assert.deepStrictEqual(await p.evaluate(() => window._calls.filter(c => c[0] === 'open').map(c => JSON.parse(c[1])[0])), ['act:open:p3c4d'], 'one tap opens the parcel');
    await p.evaluate(() => { window._calls.length = 0; [...document.querySelectorAll('#minis .mini-pill.act')].find(b => b.textContent.includes('답장')).click(); });
    assert.deepStrictEqual(await p.evaluate(() => window._calls.filter(c => c[0] === 'open').map(c => JSON.parse(c[1])[0])), ['act:reply:m1a2b'], 'one tap opens the reply');
    await p.clock.runFor(46000);
    await board([out, booked, plan]);
    await p.clock.runFor(1200);
    assert((await bubble()).startsWith('‘무선 이어폰’ 오늘'), 'the parcel next, once: ' + await bubble());
    await p.clock.runFor(3600);
    assert(!(await bubble()).includes('무선 이어폰'), 'gone again');
    await p.clock.runFor(46000);
    await board([out, booked, plan]);
    await p.clock.runFor(1200);
    assert(!/약속 생겼네|무선 이어폰/.test(await bubble()), 'each said only once: ' + await bubble());
    // It came: a new stage, said once; the booking's pill comes up when the plan has been put in the calendar.
    const came = Object.assign({}, out, { stage: 'done', line: '택배 문 앞에 왔대 📦', lineKey: 'act:parcel:p3c4d:done', rank: 60, pills: [{ label: '택배', word: '도착', go: 'act:open:p3c4d', rank: 60, icon: 'parcel' }] });
    const inCalendar = Object.assign({}, plan, { pills: [plan.pills[1]], line: '' });
    await p.clock.runFor(46000);
    await board([came, booked, inCalendar]);
    await p.clock.runFor(1200);
    assert.strictEqual(await bubble(), '택배 문 앞에 왔대 📦 ›', 'said as it comes');
    shown = await pills();
    assert.deepStrictEqual(shown.map(x => x.text), ['민수 답장', 'KTX 일정에 넣기'], 'the booking now second: ' + JSON.stringify(shown));
    const shots = process.env.SHOTS || require('os').tmpdir();
    await p.screenshot({ path: path.join(shots, 'launcher-acts-line.png') });
    await p.clock.runFor(3600);
    assert(!(await bubble()).includes('문 앞에 왔대'), 'gone in a few seconds');
    await p.screenshot({ path: path.join(shots, 'launcher-acts.png') });
    // Locked: none of it.
    await p.evaluate(() => launcher.board({ locked: true, weather: { code: 0, temp: 12, day: true }, env: { battery: 80 }, life: { heart: 60 }, acts: [{ kind: 'parcel', key: 'p1', line: '택배 오늘 온대', lineKey: 'x', pills: [{ label: '택배', word: '오늘 도착', go: 'act:open:p1', rank: 78 }] }] }));
    assert.strictEqual((await pills()).length, 0, 'nothing on the lock screen');
    assert.deepStrictEqual(acts.errors, []);
    await acts.ctx.close();
  });
  // 문 하나 (준): the jobs 퍼레 took on (BuddyJobs), whichever engine runs them, have one look on the home screen.
  const jobs = await open(browser, '2026-11-27T15:00:00+09:00', true);
  await check('퍼레\'s jobs: a small line under the search ("● 배민 담는 중 · 3단계" with 중지; a tap opens its result); when one ends, one word in passing that a tap opens, gone in a few seconds', async () => {
    const p = jobs.page;
    const line = () => p.evaluate(() => [...document.querySelectorAll('#jobs .job-line')].map(l => { const r = l.getBoundingClientRect(); return {text: l.textContent.replace(/\s+/g, ' ').trim(), cls: l.className, h: Math.round(r.height), w: Math.round(r.width), right: Math.round(r.right)}; }));
    const bubble = () => p.evaluate(() => { const b = document.querySelector('.bubble'); return b.classList.contains('on') ? {text: b.textContent, go: b.dataset.go} : null; });
    await p.clock.runFor(60000);                       // the opening greeting well past
    assert.deepStrictEqual(await line(), [], 'no job, no line');
    assert.strictEqual(await p.evaluate(() => getComputedStyle(document.getElementById('jobs')).display), 'none');
    await p.evaluate(() => launcher.jobs([{ id: 'j1', kind: 'phone', line: '배민 담는 중 · 3단계', state: 'running', stop: true, open: true }]));
    let now = await line();
    assert.strictEqual(now.length, 1); assert.strictEqual(now[0].text, '배민 담는 중 · 3단계중지');
    assert(now[0].cls.includes('running'), now[0].cls);
    assert(now[0].h <= 34, 'as small as a mini pill: ' + now[0].h);
    assert(now[0].right <= 412 - 16, 'inside the screen: ' + now[0].right);
    await p.evaluate(() => { window._calls.length = 0; document.querySelector('#jobs .stop').click(); });
    assert.deepStrictEqual(await p.evaluate(() => window._calls.filter(c => c[0] === 'jobStop').map(c => JSON.parse(c[1])[0])), ['j1'], '중지: that job, at once');
    assert.strictEqual(await p.evaluate(() => document.querySelector('#jobs .stop').textContent), '멈추는 중');
    await p.evaluate(() => { window._calls.length = 0; document.querySelector('#jobs .job').click(); });
    assert.deepStrictEqual(await p.evaluate(() => window._calls.filter(c => c[0] === 'open').map(c => JSON.parse(c[1])[0])), ['job:j1'], 'its result');
    // Two at once: one line each, the newest last; a long one is cut, never wider than the screen.
    await p.evaluate(() => launcher.jobs([{ id: 'j1', line: '배민 담는 중 · 5단계', state: 'running', stop: true, open: true },
      { id: 'j2', line: '작업창 · 이번 달 카드 지출을 분석해서 보고서로 정리하는 중', state: 'running', stop: true, open: true }]));
    now = await line();
    assert.strictEqual(now.length, 2); assert(now.every(l => l.right <= 412 - 16), JSON.stringify(now));
    // It ends: the line says how (no 중지), and 퍼레 says it once in passing; a tap on the words opens its result.
    await p.evaluate(() => { launcher.jobs([{ id: 'j1', line: '배민 · 끝났어', state: 'done', stop: false, open: true }]); launcher.jobDone({ id: 'j1', text: '배민 일 끝났어! 눌러 봐', go: 'job:j1' }); });
    now = await line();
    assert.strictEqual(now[0].text, '배민 · 끝났어'); assert(now[0].cls.includes('done'));
    await p.clock.runFor(300);
    const said = await bubble();
    assert(said && said.text.startsWith('배민 일 끝났어! 눌러 봐') && said.go === 'job:j1', 'said in passing: ' + JSON.stringify(said));
    await p.evaluate(() => { window._calls.length = 0; document.querySelector('.bubble').click(); });
    assert.deepStrictEqual(await p.evaluate(() => window._calls.filter(c => c[0] === 'open').map(c => JSON.parse(c[1])[0])), ['job:j1'], 'the words open its result');
    await p.evaluate(() => launcher.jobDone({ id: 'j3', text: '연구실 연구 끝났어! 눌러 봐', go: 'job:j3' }));
    await p.clock.runFor(4800);
    assert.strictEqual(await bubble(), null, 'gone in a few seconds');
    // Away when it ends: said when the screen is seen again, once.
    await p.evaluate(() => { launcher.hidden(); launcher.jobDone({ id: 'j4', text: '작업창 일 끝났어! 눌러 봐', go: 'job:j4' }); });
    await p.clock.runFor(1000);
    // (퍼레's own little words may come and go meanwhile — CI once caught '부글부글~ 😤' — but not the job's word)
    assert(!(((await bubble()) || {}).text || '').startsWith('작업창 일 끝났어'), 'not while away');
    await p.evaluate(() => launcher.shown()); await p.clock.runFor(1200);
    assert(((await bubble()) || {}).text?.startsWith('작업창 일 끝났어'), 'said when seen again');
    await p.clock.runFor(6000); await p.evaluate(() => { launcher.hidden(); launcher.shown(); }); await p.clock.runFor(1500);
    assert(!(((await bubble()) || {}).text || '').startsWith('작업창 일 끝났어'), 'once');
    await p.evaluate(() => launcher.jobs([]));
    assert.deepStrictEqual(await line(), []);
    assert.deepStrictEqual(jobs.errors, []);
  });
  await jobs.ctx.close();
  // 퍼레's routine suggestion (BuddyHabits, core/Habits): a habit said once in passing with two small choices, gone in seconds.
  const habit = await open(browser, '2026-11-27T20:00:00+09:00', true);
  await check('a routine suggestion: said once in passing with two small choices (열어 줘 / 괜찮아), gone in seconds unless tapped; a tap tells the app; never twice, never while away or locked', async () => {
    const p = habit.page;
    const look = () => p.evaluate(() => { const b = document.querySelector('.buddy .bubble'), r = b.getBoundingClientRect();
      return { on: b.classList.contains('on'), asking: b.classList.contains('asking'), mine: b.classList.contains('mine'), text: b.childNodes[0] ? b.childNodes[0].textContent : '',
        picks: [...b.querySelectorAll('.pick')].map(c => { const x = c.getBoundingClientRect(); return { word: c.textContent, h: Math.round(x.height), inside: x.left >= r.left && x.right <= r.right && x.bottom <= r.bottom + 1 }; }),
        left: Math.round(r.left), right: Math.round(r.right), vw: document.documentElement.clientWidth }; });
    const heard = () => p.evaluate(() => window._calls.filter(c => c[0] === 'routine').map(c => JSON.parse(c[1])));
    await p.clock.runFor(60000);                       // the opening greeting well past
    const car = { key: 'car|com.nhn.android.nmap', text: '차 타면 네이버 지도 자주 켜더라. 다음부터 차 타면 바로 열어 줄까?', yes: '열어 줘', no: '괜찮아' };
    assert.strictEqual(await p.evaluate(o => launcher.routine(o), car), true);
    await p.clock.runFor(1500);
    let now = await look();
    assert(now.on && now.asking, 'said: ' + JSON.stringify(now));
    assert.strictEqual(now.text, car.text);
    assert.deepStrictEqual(now.picks.map(x => x.word), ['열어 줘', '괜찮아']);
    assert(now.picks.every(x => x.h <= 30 && x.inside), 'two small chips inside the bubble: ' + JSON.stringify(now.picks));
    assert(now.left >= 0 && now.right <= now.vw, 'on screen: ' + now.left + '..' + now.right);
    assert.deepStrictEqual(await heard(), [[car.key, 'shown']], 'the app hears it was shown, once');
    if (process.env.GYEOT_PREVIEW) await p.screenshot({ path: path.join(process.env.GYEOT_PREVIEW, 'routine-bubble.png') });
    // Not tapped: gone in a few seconds (준: "오랫동안 풍선띄우는건 자제"), and never said again.
    const showing = async text => { const x = await look(); return x.on && x.text === text; };
    await p.clock.runFor(8200);
    assert(!(await showing(car.text)), 'gone in seconds');
    assert.strictEqual(await p.evaluate(o => launcher.routine(o), car), false, 'once only');
    for (let i = 0; i < 16; i++) { await p.clock.runFor(500); assert(!(await showing(car.text)), 'not said again'); }
    assert.strictEqual((await heard()).length, 1);
    // Another, tapped: the tap goes to the app, the choice shows as the user's, and the app's one line follows.
    const charge = { key: 'charge|com.google.android.youtube', text: '충전기 꽂으면 YouTube 자주 켜더라. 다음부터 충전기 꽂으면 바로 열어 줄까?', yes: '열어 줘', no: '괜찮아' };
    await p.evaluate(o => launcher.routine(o), charge);
    await p.clock.runFor(1500);
    const chip = await p.evaluate(() => { const r = document.querySelector('.bubble .pick').getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; });
    await p.touchscreen.tap(chip.x, chip.y);
    await p.clock.runFor(200);
    assert.deepStrictEqual((await heard()).slice(-2), [[charge.key, 'shown'], [charge.key, 'yes']], 'the tap tells the app');
    now = await look();
    assert(now.mine && now.text === '열어 줘' && !now.asking, 'the choice as the user\'s: ' + JSON.stringify(now));
    await p.evaluate(() => launcher.jobDone({ id: 'routine', text: '좋아! 충전기를 꽂으면 YouTube 열게. 매번.', go: '' }));
    await p.clock.runFor(300);
    now = await look();
    assert(now.on && now.text.startsWith('좋아! 충전기를 꽂으면 YouTube 열게'), 'said back in one line: ' + JSON.stringify(now));
    assert(!now.mine, '퍼레\'s own words, not in the user\'s colour');
    if (process.env.GYEOT_PREVIEW) await p.screenshot({ path: path.join(process.env.GYEOT_PREVIEW, 'routine-yes.png') });
    await p.clock.runFor(5000);
    assert(!((await look()).on && (await look()).text.startsWith('좋아!')), 'gone in a few seconds');
    // Away: kept until the screen is seen again; locked: not said (nothing of the user's day then).
    const home = { key: 'visit:집|com.netflix.mediaclient', text: '집에 오면 넷플릭스 자주 켜더라. 다음부터 집에 오면 바로 열어 줄까?', yes: '열어 줘', no: '괜찮아' };
    await p.evaluate(o => { launcher.hidden(); launcher.routine(o); }, home);
    await p.clock.runFor(3000);
    assert(!(await showing(home.text)), 'not while away');
    await p.evaluate(() => launcher.board({ weather: { code: 2, temp: 12, day: false }, env: { battery: 80 }, life: { heart: 60 }, locked: true }));
    await p.evaluate(() => launcher.shown());
    await p.clock.runFor(9000);
    assert(!((await look()).text || '').startsWith('집에 오면'), 'not while locked');
    // Under 퍼레와 대화 (퍼레 out of sight): it waits until the talk is closed.
    await p.evaluate(() => { launcher.board({ weather: { code: 2, temp: 12, day: false }, env: { battery: 80 }, life: { heart: 60 } }); window.boardChat(); launcher.hidden(); launcher.shown(); });
    await p.clock.runFor(9000);
    assert.strictEqual((await heard()).filter(h => h[0] === home.key).length, 0, 'not said under the talk');
    await p.evaluate(() => closePad());
    for (let i = 0; i < 40 && !((await look()).text || '').startsWith('집에 오면'); i++) await p.clock.runFor(500);
    assert(((await look()).text || '').startsWith('집에 오면'), 'said once seen again, unlocked');
    const no = await p.evaluate(() => { const r = document.querySelectorAll('.bubble .pick')[1].getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; });
    await p.touchscreen.tap(no.x, no.y);
    await p.clock.runFor(200);
    assert.deepStrictEqual((await heard()).slice(-1), [[home.key, 'no']], '괜찮아 tells the app');
    assert.deepStrictEqual(habit.errors, []);
  });
  await habit.ctx.close();
  await check('an app that did not open: its words stay while 퍼레 listens, one tap opens the way through', async () => {
    const p = bridged.page, look = () => p.evaluate(() => { const b = document.querySelector('.bubble'); return { text: b.textContent, go: b.dataset.go, act: b.classList.contains('act'), on: b.classList.contains('on') }; });
    await p.evaluate(() => closePad()); await p.clock.runFor(400);
    const line = '어, ‘카카오톡’ 안 열렸어.';
    await p.evaluate(l => launcher.voice('speaking', l, 1200, 'fix:overlay'), line);
    await p.clock.runFor(1400);
    await p.evaluate(() => { launcher.voice('gap', ''); launcher.voice('listening', ''); });
    let now = await look();
    assert(now.on && now.act && now.go === 'fix:overlay' && now.text.startsWith(line), 'kept while it listens: ' + JSON.stringify(now));
    await p.evaluate(() => { window._calls.length = 0; document.querySelector('.bubble').click(); });
    assert.deepStrictEqual(await p.evaluate(() => window._calls.filter(c => c[0] === 'open').map(c => JSON.parse(c[1])[0])), ['fix:overlay'], 'the way through');
    // Words heard let them go; an answer of its own is not tappable.
    await p.evaluate(l => { launcher.voice('speaking', l, 1200, 'fix:overlay'); }, line); await p.clock.runFor(1400);
    await p.evaluate(() => launcher.voice('listening', '근데'));
    now = await look();
    assert(!now.act && now.go === '' && now.text === '근데', 'heard words replace them: ' + JSON.stringify(now));
    await p.evaluate(() => launcher.voice('speaking', '좋아, 그럼 멜론으로 틀어 줄게', 900)); await p.clock.runFor(1200);
    now = await look();
    assert(!now.act && now.go === '', 'an answer is not tappable: ' + JSON.stringify(now));
    await p.evaluate(() => launcher.voice('idle', ''));
    assert.deepStrictEqual(bridged.errors, []);
  });
  await bridged.ctx.close();

  // 준: "음성 대화로 주사위 꺼내봐 해도 안된다고해", "의상바꿔도 안되고", "음성대화로 퍼레의 상호작용이 안돼"
  await check('asked by voice or in the talk, 퍼레 takes out a toy, changes clothes and keeps them, plays what it is asked', async () => {
    const p = talk.page;
    await p.evaluate(() => { if (el('pad').classList.contains('on')) closePad(); }); await p.clock.runFor(500);
    await p.evaluate(() => launcher.self({toy: 'dice'}));
    for (let i = 0; i < 16; i++) await p.clock.runFor(500);
    assert(await p.evaluate(() => !!document.querySelector('.toy.dice, .dice')), 'a die on the board');
    await p.evaluate(() => launcher.self({toy: 'away'}));
    // Put away within a few seconds (a die still rolling on a slow machine is let land first: CI once counted 1 at 1.5 s).
    let left = 1; for (let i = 0; i < 12 && left; i++) { await p.clock.runFor(500); left = await p.evaluate(() => boards[0]._toys.count()); }
    assert.strictEqual(left, 0, 'all put away');
    // and left away a while: 퍼레 does not take one out again on its own right after
    for (let i = 0; i < 40; i++) await p.clock.runFor(500);
    assert.strictEqual(await p.evaluate(() => boards[0]._toys.count()), 0, 'still put away 20 s later');
    // Asked for a toy while 퍼레 is in a play (the ask waits its turn), then to put them away: the toy does not come out afterwards.
    await p.evaluate(() => launcher.self({play: 'yoga'})); await p.clock.runFor(300);
    await p.evaluate(() => launcher.self({toy: 'ball'})); await p.clock.runFor(100);
    await p.evaluate(() => launcher.self({toy: 'away'}));
    for (let i = 0; i < 24; i++) await p.clock.runFor(500);
    assert.strictEqual(await p.evaluate(() => boards[0]._toys.count()), 0, 'a toy asked before "away" stays away');
    await p.evaluate(() => launcher.self({wear: ['catears', 'scarf']}));
    await p.clock.runFor(30000);
    // Through a play it keeps them on (the play's things on top) …
    const during = await p.evaluate(() => Array.from(document.querySelector('.bot').classList).filter(c => c.startsWith('w-')));
    assert(during.includes('w-catears') && during.includes('w-scarf'), 'kept on: ' + during.join(' '));
    for (let i = 0; i < 80 && await p.evaluate(() => !!boards[0]._dressed); i++) await p.clock.runFor(500);
    // … and after the next turn of clothes.
    await p.clock.setSystemTime(new Date('2026-11-26T15:10:00+09:00'));
    const worn = await p.evaluate(() => { launcher.env({battery: 80}); return Array.from(document.querySelector('.bot').classList).filter(c => c.startsWith('w-')).sort().join(' '); });
    assert.strictEqual(worn, 'w-catears w-scarf', 'still worn after a turn of clothes: ' + worn);
    await p.evaluate(() => launcher.self({wear: [], any: true}));
    const any = await p.evaluate(() => Array.from(document.querySelector('.bot').classList).filter(c => c.startsWith('w-')));
    assert.strictEqual(any.length, 1, 'one cute thing of its own: ' + any.join(' '));
    await p.evaluate(() => launcher.self({wear: []}));
    assert.strictEqual(await p.evaluate(() => Array.from(document.querySelector('.bot').classList).filter(c => c.startsWith('w-')).length), 0, 'plain');
    // A play asked for in the talk waits for the talk to close.
    await p.evaluate(() => { boardChat(); launcher.self({play: 'dance'}); });
    await p.clock.runFor(600);
    await p.evaluate(() => closePad());
    let danced = false;
    // (after the play going on, if any: b._ask waits up to eight seconds for it)
    for (let i = 0; i < 22 && !danced; i++) { await p.clock.runFor(500); danced = await p.evaluate(() => document.querySelector('.buddy').classList.contains('dance')); }
    assert(danced, 'dancing once the talk closed');
    assert.deepStrictEqual(talk.errors, []);
  });
  await talk.ctx.close();

  const dawn = await open(browser, '2026-11-27T03:10:00+09:00');
  await check('at dawn 퍼레 sleeps, wakes a moment after the screen comes on, and dozes off again when left alone', async () => {
    const p = dawn.page, asleep = () => p.evaluate(() => document.querySelector('.buddy').classList.contains('asleep'));
    await p.clock.runFor(1000);
    assert.strictEqual(await asleep(), true, 'asleep as the screen opens');
    for (let i = 0; i < 12; i++) await p.clock.runFor(500);
    assert.strictEqual(await asleep(), false, 'awake a few seconds later');
    assert((await p.evaluate(() => document.querySelector('.bubble').textContent)).length > 0, 'a sleepy word');
    for (let i = 0; i < 30; i++) await p.clock.runFor(5000);
    assert.strictEqual(await asleep(), true, 'asleep again after two quiet minutes');
    await p.evaluate(() => { launcher.hidden(); }); await p.clock.runFor(60000); await p.evaluate(() => { launcher.shown(); });
    await p.clock.runFor(800);
    assert.strictEqual(await asleep(), true, 'the screen on again: still asleep for a moment');
    for (let i = 0; i < 12; i++) await p.clock.runFor(500);
    assert.strictEqual(await asleep(), false, 'and awake');
    assert.deepStrictEqual(dawn.errors, []);
  });
  await dawn.ctx.close();

  // December (준: "크리스마스 트리도 만들어줘 퍼레가 장식하거나 감상하면서 놀게", "루돌프랑 루돌프 썰매도", "장난감 … 아직 부족").
  const xmas = await open(browser, '2026-12-10T15:00:00+09:00');
  await check('in December the tree stands on the board; 퍼레 hangs ornaments then the star, rides Rudolph\'s sleigh; the new toys come out', async () => {
    const p = xmas.page;
    for (let i = 0; i < 6; i++) await p.clock.runFor(500);
    assert(await p.evaluate(() => !!document.querySelector('.xtree')), 'a tree in December');
    const hung = await p.evaluate(() => { const T = boards[0]._toys, got = []; for (let i = 0; i < 10; i++) got.push(T.treeHang()); return got; });
    assert.strictEqual(hung[8], '별', 'the star after eight ornaments: ' + hung.join(','));
    assert.strictEqual(hung[9], '', 'then nothing more');
    assert.strictEqual(await p.evaluate(() => document.querySelectorAll('.xtree .orn.on').length), 8);
    await p.evaluate(() => boards[0]._toys.sleigh());
    await p.clock.runFor(1000);
    assert(await p.evaluate(() => !!document.querySelector('.sleighride .rider .bot')), '퍼레 rides the sleigh');
    for (let i = 0; i < 12; i++) await p.clock.runFor(500);
    assert.strictEqual(await p.evaluate(() => !!document.querySelector('.sleighride')), false, 'and is back');
    const out = await p.evaluate(() => ['duck', 'car', 'rocket', 'drum', 'musicbox', 'beachball', 'marbles', 'blocks'].map(k => !!boards[0]._toys.out(k)));
    assert(out.every(Boolean), 'every new toy: ' + out);
    await p.evaluate(() => launcher.self({toy: 'tree'}));
    for (let i = 0; i < 8; i++) await p.clock.runFor(500);
    assert.deepStrictEqual(xmas.errors, []);
  });
  await xmas.ctx.close();

  const evening = await open(browser, '2026-11-26T20:30:00+09:00');
  await check('in the evening 퍼레 wears nothing that looks like doing something for hours', async () => {
    const wear = await evening.page.evaluate(() => Array.from(document.querySelector('.bot').classList).filter(c => c.startsWith('w-')));
    for (const thing of ['w-roundglasses', 'w-book', 'w-heartglasses', 'w-cape', 'w-nerdglasses', 'w-mustache']) assert(!wear.includes(thing), thing + ' in ' + wear.join(' '));
  });
  await evening.ctx.close();

  // A page of its own (the day page's run is seeded: its toys and plays must not be moved by this one).
  const own = await open(browser, '2026-11-26T14:00:00+09:00');
  await check('a page of the user\'s own hears where the system bar is (the LP page\'s list bar sits above it and the dots)', async () => {
    const p = own.page;
    await p.evaluate(() => { window._heard = []; addEventListener('message', e => { if (e.data && e.data.heard) window._heard.push(e.data); }); });
    await p.evaluate(() => toPage(0, false)); await p.clock.runFor(300); // far away: the frame is emptied, and loads again
    assert.strictEqual(await p.evaluate(() => document.querySelector('.page.custom iframe').getAttribute('src')), null);
    await p.evaluate(() => toPage(2, false));
    // a page whose piece the app serves from its own address (the LP piece: PieceExtras.frameUrl) is framed from there
    assert.strictEqual(await p.evaluate(() => document.querySelector('.page.custom iframe').getAttribute('src')), 'https://lp.piece.test/lp.html');
    try {
      await p.frameLocator('iframe').locator('#k').waitFor({ timeout: 15000 });
      for (let i = 0; i < 20 && !(await p.evaluate(() => window._heard.length)); i++) { await p.clock.runFor(100); await p.waitForTimeout(50); }
      await p.evaluate(() => launcher.insets(30, 26, 0));
      for (let i = 0; i < 20 && (await p.evaluate(() => window._heard.length)) < 2; i++) { await p.clock.runFor(100); await p.waitForTimeout(50); }
      const heard = await p.evaluate(() => window._heard);
      assert(heard.length >= 2, 'told when it loaded and when the bars changed: ' + JSON.stringify(heard));
      assert.strictEqual(heard[0].from, 'https://gyeot.local');
      assert.deepStrictEqual(heard[heard.length - 1], { heard: { gyeot: 'insets', top: 30, bottom: 26 }, from: 'https://gyeot.local' });
    } finally { await p.evaluate(() => toPage(1, false)); await p.clock.runFor(300); }
  });
  await own.ctx.close();

  // 준, 2026-11-28: "홈화면 왼쪽 페이지 자체를 내 ai브라우저로 https://claude.ai/code 이페이지를 띄울 수 있어?", "왼쪽 페이지는 하단 독
  // 없애고", "내 들퍼레 ai 브라우저가 안 뜨고 클로드 페이지만 떠"): the page reports where it is; resting on the left page the app
  // opens the AI 브라우저 there and the pages go back to 홈 under it.
  const SHOTS = process.env.LAUNCHER_SHOTS || path.join(require('os').tmpdir(), 'leftpage');
  const left = await open(browser, '2026-11-28T11:00:00+09:00', true, null, 2, { claude: true, brain: 'GPT-6 Luna · Light' });
  await check('왼쪽 · Claude Code: the first page is its place (오늘 not shown); the page tells the app where it is mid-swipe and settled, and when Launchpad covers it; no dock there; the dots stay low and a tap on one turns the page', async () => {
    const p = left.page; fs.mkdirSync(SHOTS, { recursive: true });
    const reports = () => p.evaluate(() => window._calls.filter(c => c[0] === 'left').map(c => JSON.parse(c[1])));
    const last = async () => (await reports()).pop();
    const settle = async () => { for (let i = 0; i < 6; i++) { await p.waitForTimeout(40); await p.clock.runFor(100); } };
    const until = async (test, why) => { for (let i = 0; i < 60; i++) { if (await test()) return; await p.waitForTimeout(50); await p.clock.runFor(100); } assert.fail(why); };
    const s = await p.evaluate(() => ({ claude: !el('claudePage').hidden, today: !el('todayPage').hidden, first: el('pages').firstElementChild.id, dots: el('dots').children.length }));
    assert.deepStrictEqual(s, { claude: true, today: false, first: 'claudePage', dots: 3 }, 'Claude Code in 오늘\'s place');
    await settle();
    assert.deepStrictEqual(await last(), [1, true, false], 'on 홈 at first, settled (the app makes nothing yet)');
    // Halfway: told as it moves.
    await p.evaluate(() => { window._calls.length = 0; pagesBox.style.scrollSnapType = 'none'; pagesBox.scrollLeft = pagesBox.clientWidth / 2; });
    await settle();
    const half = await reports();
    assert(half.some(r => r[0] === 0.5 && r[1] === false && r[2] === false), 'its place mid-swipe: ' + JSON.stringify(half));
    await p.screenshot({ path: path.join(SHOTS, 'home-left-half.png') });
    // Settled on it: no dock (its touches too), the dots low in the strip under the app's page.
    await p.evaluate(() => { pagesBox.style.scrollSnapType = ''; toPage(0, false); });
    await settle();
    assert.deepStrictEqual(await last(), [0, true, false], 'settled on the left page');
    const there = await p.evaluate(() => {
      const r = el('shelf').getBoundingClientRect(), at = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2), dots = el('dots').getBoundingClientRect();
      return { dock: getComputedStyle(document.querySelector('.dock')).opacity, hit: !!(at && at.closest && at.closest('.dk')), onClaude: document.body.classList.contains('on-claude'),
        dots: getComputedStyle(el('dots')).opacity, fromBottom: innerHeight - dots.bottom, height: dots.height, at: [...el('dots').children].map(i => { const b = i.getBoundingClientRect(); return [b.left + b.width / 2, b.top + b.height / 2]; }) };
    });
    assert(there.onClaude && there.dock === '0' && !there.hit, 'no dock on the left page: ' + JSON.stringify(there));
    assert(there.dots === '1' && there.fromBottom >= 16 && there.fromBottom + there.height <= 16 + 24, 'the dots in the strip over the system bar: ' + JSON.stringify(there));
    await p.screenshot({ path: path.join(SHOTS, 'left-page-no-dock.png') });
    // A tap on 홈's dot turns to it; the dock is back there.
    await p.touchscreen.tap(there.at[1][0], there.at[1][1]);
    await until(async () => { const l = await last(); return await p.evaluate(() => pageAt) === 1 && l[0] === 1 && l[1]; }, 'the dot\'s tap turns to 홈');
    const home = await p.evaluate(() => { const r = el('shelf').getBoundingClientRect(), at = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
      return { dock: getComputedStyle(document.querySelector('.dock')).opacity, hit: !!(at && at.closest && at.closest('.dk')), onClaude: document.body.classList.contains('on-claude') }; });
    assert.deepStrictEqual(home, { dock: '1', hit: true, onClaude: false }, 'the dock again on 홈');
    // Launchpad over the left page: the app is told (its page steps aside), and again when it closes.
    await p.evaluate(() => toPage(0, false)); await settle();
    await p.evaluate(() => launcher.pad(false)); await settle();
    assert.deepStrictEqual(await last(), [0, true, true], 'covered');
    await p.evaluate(() => launcher.back()); await settle();
    assert.deepStrictEqual(await last(), [0, true, false], 'uncovered');
    // The AI 브라우저 came over it (준: "내 들퍼레 ai 브라우저가 안 뜨고 클로드 페이지만 떠"): 홈 under it at once, so coming back lands there.
    await p.evaluate(() => toPage(0, false)); await settle();
    assert.strictEqual(await p.evaluate(() => launcher.toHome() && pageAt), 1, '홈 under the browser');
    await settle(); assert.deepStrictEqual(await last(), [1, true, false], 'told: 홈');
    // 퍼레와 대화's model, chosen right in the talk (준: "대화모델 설정가능하게 해줘", "대화창에서", "모델선택을 왜 거기에둬? 안하던 행동을
    // 하네"): as 메인 대화 has it, a line under the box, the model in bold and its thinking level quiet beside it.
    await p.evaluate(() => openChat()); await settle();
    const line = await p.evaluate(() => { const b = el('chatModel'), r = b.getBoundingClientRect(), box = document.querySelector('.pad-top .field').getBoundingClientRect();
      return { shown: !b.hidden && r.height > 0, model: b.querySelector('b').textContent, level: (b.querySelector('i') || {}).textContent, bold: getComputedStyle(b.querySelector('b')).fontWeight,
        label: b.getAttribute('aria-label'), h: r.height, under: r.top >= box.bottom - 1, left: Math.abs(r.left - box.left) < 16, w: r.width, whole: b.scrollWidth <= b.clientWidth + 1,
        inBar: !!document.querySelector('#chatBar .brain'), x: r.left + r.width / 2, y: r.top + r.height / 2 }; });
    assert(line.shown && line.model === 'GPT-6 Luna' && line.level === 'Light' && +line.bold >= 600, 'the model in bold, its level beside: ' + JSON.stringify(line));
    assert(line.under && line.left && line.h <= 26 && line.w < 300 && line.whole && !line.inBar, 'a small line under the box, not by the title: ' + JSON.stringify(line));
    assert(line.label.includes('대화 모델'), line.label);
    await p.screenshot({ path: path.join(SHOTS, 'chat-model-line.png'), clip: { x: 0, y: 900 - 160, width: 412, height: 160 } });
    await p.evaluate(() => { window._calls.length = 0; }); await p.touchscreen.tap(line.x, line.y); await settle();
    assert(await p.evaluate(() => window._calls.some(c => c[0] === 'brain')), 'a tap asks the app for the picker');
    await p.evaluate(() => launcher.brain('Qwen'));
    assert.deepStrictEqual(await p.evaluate(() => [el('chatModel').querySelector('b').textContent, !!el('chatModel').querySelector('i')]), ['Qwen', false], 'the new choice shows at once');
    await p.evaluate(() => drawList());
    assert(await p.evaluate(() => el('chatModel').hidden), 'not on the list of talks');
    await p.evaluate(() => closePad()); await settle();
    // 퍼레's home_page tool knows it by name.
    await p.evaluate(() => launcher.goPage('홈')); assert.strictEqual(await p.evaluate(() => pageAt), 1);
    assert.strictEqual(await p.evaluate(() => launcher.goPage('Claude Code')), true); assert.strictEqual(await p.evaluate(() => pageAt), 0);
    // 화면 꾸미기 turns it off: 오늘 again, with the dock, and the app is told nothing more.
    await p.evaluate(() => { toPage(0, false); launcher.claude(false); window._calls.length = 0; }); await settle();
    const off = await p.evaluate(() => ({ claude: !el('claudePage').hidden, today: !el('todayPage').hidden, onClaude: document.body.classList.contains('on-claude'), dock: getComputedStyle(document.querySelector('.dock')).opacity }));
    assert.deepStrictEqual(off, { claude: false, today: true, onClaude: false, dock: '1' }, '오늘 back on the left');
    await p.evaluate(() => { toPage(1, false); toPage(0, false); }); await settle();
    assert.deepStrictEqual(await reports(), [], 'no reports while it is 오늘');
    await p.evaluate(() => launcher.claude(true)); await settle();
    assert.deepStrictEqual(await last(), [0, true, false], 'on again: told where it is at once');
    assert.deepStrictEqual(left.errors, []);
  });
  await left.ctx.close();

  // 준: "퍼레가 lp에서 노래 나오는 걸 인식못하네": the LP page on a page of the home screen says it plays → 퍼레 hears music at once (its notes,
  // a dance), not a quarter minute later from the app's word; stopped → off at once, unless other apps' music plays (the app's musicApps).
  const lp = await open(browser, '2026-11-26T15:00:00+09:00', false, [{ src: 'https://piece.test/lp.html', frame: 'https://lp.gyeot.local/lp.html', title: 'LP' }]);
  await check('the LP page says it plays: 퍼레 hears music at once (notes, it dances), its page stays though far; stopped: off at once, unless other music plays; the notification\'s asks reach it', async () => {
    const p = lp.page, f = () => p.frames().find(x => x.url().startsWith('https://lp.gyeot.local/'));
    for (let i = 0; i < 40 && !(f() && await f().evaluate(() => !!window.say).catch(() => false)); i++) { await p.clock.runFor(100); await p.waitForTimeout(50); }
    const hears = () => p.evaluate(() => ({ music: !!boards[0]._env.music, notes: document.querySelector('.bot').classList.contains('w-notes'), dance: document.querySelector('.buddy').classList.contains('dance') }));
    const settle = async () => { for (let i = 0; i < 4; i++) { await p.waitForTimeout(40); await p.clock.runFor(100); } };
    assert.deepStrictEqual(await hears(), { music: false, notes: false, dance: false }, 'no music at first');
    await f().evaluate(() => say(true, '좋은 날'));
    await settle();
    const on = await hears();
    assert.deepStrictEqual([on.music, on.notes], [true, true], 'music at once: ' + JSON.stringify(on));
    let danced = on.dance;
    for (let i = 0; i < 10 && !danced; i++) { await p.clock.runFor(300); danced = (await hears()).dance; }
    assert(danced, 'it dances to it');
    // the app's quarter-minute word (no music of other apps; the LP it heard too) keeps it on
    await p.evaluate(() => launcher.env({ battery: 80, music: true, musicApps: false, lp: true }));
    assert.strictEqual((await hears()).music, true);
    // far away (the home screen's other side): a page playing is not let go
    await p.evaluate(() => toPage(0, false)); await p.clock.runFor(300);
    assert.strictEqual(await p.evaluate(() => document.querySelector('.page.custom iframe').getAttribute('src')), 'https://lp.gyeot.local/lp.html', 'still there, playing');
    // the media notification's 일시 정지 (LpMedia.js → launcher.lp)
    await p.evaluate(() => launcher.lp('pause')); await settle();
    assert.deepStrictEqual(await f().evaluate(() => asked), ['pause']);
    await f().evaluate(() => say(false, '좋은 날'));
    await settle();
    // the app's word still says music (from before): the LP page's own word wins, other apps play none
    assert.deepStrictEqual(await hears().then(h => [h.music, h.notes]), [false, false], 'off at once');
    assert.strictEqual(await p.evaluate(() => document.querySelector('.page.custom iframe').getAttribute('src')), null, 'far, stopped: let go as any page');
    // other apps' music: on, whatever the LP page says
    await p.evaluate(() => launcher.env({ battery: 80, music: true, musicApps: true, lp: false }));
    assert.strictEqual((await hears()).music, true, 'other music heard');
    await p.evaluate(() => launcher.env({ battery: 80, music: false, musicApps: false, lp: false }));
    assert.strictEqual((await hears()).music, false);
    // a word from anywhere else is not taken
    await p.evaluate(() => toPage(2, false)); await p.clock.runFor(300);
    for (let i = 0; i < 40 && !(f() && await f().evaluate(() => !!window.say).catch(() => false)); i++) { await p.clock.runFor(100); await p.waitForTimeout(50); }
    await p.evaluate(() => window.postMessage({ gyeot: 'playing', on: true }, '*')); await settle();
    assert.strictEqual((await hears()).music, false, 'only the LP page\'s own word');
    assert.deepStrictEqual(lp.errors, []);
  });
  await lp.ctx.close();

  await browser.close();
  console.log(failed ? failed + ' failed' : 'all passed');
  process.exit(failed ? 1 : 0);
})();

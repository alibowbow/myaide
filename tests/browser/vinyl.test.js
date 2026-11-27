/*
  The LP page (Opus 5.5 · 057 Hi-Fi Walnut — Vinyl Listening Room) as the app serves it on a home page: the live piece with
  the app's fixes (app/src/main/resources/dev/jun/gyeot/core/piece-057.json, as core/PieceTune applies them) and the add-on
  (app/src/main/assets/piece), in a frame of a page at https://gyeot.local like the launcher's, the app's /__gyeot/… answers
  given here as PieceExtras gives them. 준이 바란 것:
    - "하방 스크롤 안하고 곡목록 보기 및 선택가능하게해줘": a small bar on the first screen (no scrolling), a list of every record's
      tracks over it, a track of another record picked from it;
    - "내 폴더에 있는 음악들도 재생가능하게 해줘", "파일 선택해서 리스트 추가 할 수 있게": + 파일 추가 asks the app's picker, the
      files then on the list play on the turntable (the audio element, not the synthesizer), the arm and the clock follow, the
      next file comes at the end, the VU meters read them at the records' level.
  The app serves the LP piece from its own address (https://lp.gyeot.local/<the piece's path>, PieceExtras.frameUrl), where no
  other piece's code runs; on the creator's site it comes with the add-on but no key (no 내 음악).
  The piece is read from its site once into tests/browser/.cache (outside git: a piece is never copied into the app or the
  repository). Without a connection the checks are SKIPPED, said so plainly, and the run does not count as passed.
  The web view asks a tap before sound (setMediaPlaybackRequiresUserGesture): Chromium runs with the same rule.
  The three.js deck (deck3d.js; 준: "디테일한 three js로 해줘", "플스4느낌", and "Three js로 가면 휴대폰 성능저하심하려나"): it draws in
  place of the CSS deck, the record turns while playing and stops when paused, the arm is where the piece's arm is, it draws
  nothing while nothing moves and at most 30 frames a second, and with WebGL off the piece's own deck is there. Chromium draws
  WebGL here in software (SwiftShader): the frame time printed is this computer's, the draw calls and triangles the phone's.
  Run: node vinyl.test.js   (VINYL_SHOTS=<dir> also saves preview_list_closed.png and preview_list_open.png;
  VINYL_PLAIN=1 serves the piece with the noise fixes only, to see the checks fail without the add-on)
*/
const { chromium, request } = require('playwright');
const fs = require('fs'), path = require('path'), assert = require('assert');
const ROOT = path.join(__dirname, '../..');
const ASSETS = path.join(ROOT, 'app/src/main/assets/piece');
const FIXES = JSON.parse(fs.readFileSync(path.join(ROOT, 'app/src/main/resources/dev/jun/gyeot/core/piece-057.json'), 'utf8'));
const HOST = 'https://miaai-lab.github.io', LPH = 'https://lp.gyeot.local', SITE_PIECE = HOST + FIXES.path, PIECE = LPH + FIXES.path, KEY = 'testkey0123456789abcdef';
const CACHE = path.join(__dirname, '.cache'), COPY = path.join(CACHE, '057-vinyl-listening-room.html');
const SHOTS = process.env.VINYL_SHOTS || '';
let failed = 0, passed = 0;
async function check(name, fn) {
  try { await fn(); passed++; console.log('PASS', name); } catch (e) { failed++; console.log('FAIL', name, '\n   ', e.message.split('\n').join('\n    ')); }
}

async function live() {
  try {
    const ctx = await request.newContext(process.env.HTTPS_PROXY ? { proxy: { server: process.env.HTTPS_PROXY } } : {});
    const r = await ctx.get(SITE_PIECE, { timeout: 20000 });
    if (!r.ok()) throw new Error('HTTP ' + r.status());
    const body = await r.text(); await ctx.dispose();
    fs.mkdirSync(CACHE, { recursive: true }); fs.writeFileSync(COPY, body);
    return body;
  } catch (e) {
    if (fs.existsSync(COPY)) { console.log('(the piece\'s site did not answer: ' + e.message + '; using the copy read before)'); return fs.readFileSync(COPY, 'utf8'); }
    return null;
  }
}
/** As core/PieceTune.apply: each find exactly once in the page as the fixes before it left it. */
function tune(html, fixes) {
  let out = html;
  fixes.forEach((f, i) => {
    const at = out.indexOf(f.find);
    assert(at >= 0, 'fix ' + (i + 1) + ' not found: ' + f.why);
    assert(out.indexOf(f.find, at + 1) < 0, 'fix ' + (i + 1) + ' found twice: ' + f.why);
    out = out.slice(0, at) + f.put + out.slice(at + f.find.length);
  });
  return out;
}

/* Sound files the test makes: 22.05 kHz 16-bit stereo WAV, pink noise at −14 dBFS RMS (≈ −14 LUFS) or a quiet tone. */
function wav(seconds, kind, seed) {
  const sr = 22050, n = Math.round(sr * seconds), b = Buffer.alloc(44 + n * 4);
  b.write('RIFF', 0); b.writeUInt32LE(36 + n * 4, 4); b.write('WAVEfmt ', 8); b.writeUInt32LE(16, 16); b.writeUInt16LE(1, 20); b.writeUInt16LE(2, 22);
  b.writeUInt32LE(sr, 24); b.writeUInt32LE(sr * 4, 28); b.writeUInt16LE(4, 32); b.writeUInt16LE(16, 34); b.write('data', 36); b.writeUInt32LE(n * 4, 40);
  let s = seed, x = new Float64Array(n), b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
  const rnd = () => (s = (s * 16807) % 2147483647) / 2147483647 * 2 - 1;
  for (let i = 0; i < n; i++) {
    if (kind === 'pink') { const w = rnd(); b0 = 0.99886 * b0 + w * 0.0555179; b1 = 0.99332 * b1 + w * 0.0750759; b2 = 0.969 * b2 + w * 0.153852; b3 = 0.8665 * b3 + w * 0.3104856; b4 = 0.55 * b4 + w * 0.5329522; b5 = -0.7616 * b5 - w * 0.016898; x[i] = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362; b6 = w * 0.115926; }
    else x[i] = Math.sin(2 * Math.PI * 330 * i / sr);
  }
  let e = 0; for (const v of x) e += v * v;
  const k = Math.pow(10, -14 / 20) / Math.sqrt(e / n);
  for (let i = 0; i < n; i++) { const v = Math.max(-32767, Math.min(32767, Math.round(x[i] * k * 32767))); b.writeInt16LE(v, 44 + i * 4); b.writeInt16LE(v, 46 + i * 4); }
  return b;
}
const FILES = { 'a1b2c3d4e5f6': wav(6, 'pink', 7), '0f1e2d3c4b5a': wav(5, 'tone', 9), '99aa88bb77cc': wav(4, 'pink', 11) };
// (A1's length as the phone first said it is wrong: the page corrects it when its element reads the file)
const ITEMS = [
  { id: 'a1b2c3d4e5f6', title: '분홍 소리 시험', artist: '들퍼레 시험', dur: 9, mime: 'audio/wav', size: FILES['a1b2c3d4e5f6'].length, seek: true, art: false },
  { id: '0f1e2d3c4b5a', title: 'A Quiet Tone', artist: '', dur: 5, mime: 'audio/wav', size: FILES['0f1e2d3c4b5a'].length, seek: true, art: false }];

/** The launcher's page around the piece's frame: the system bar 24 px, the page dots at bottom+10 (as launcher.html). */
const FRAME = '<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;height:100%;background:#000;overflow:hidden}'
  + 'iframe{position:absolute;inset:0;width:100%;height:100%;border:0}.edge{position:absolute;top:0;bottom:0;width:22px;z-index:2}.l{left:0}.r{right:0}'
  + '.pager{position:fixed;left:0;right:0;bottom:34px;display:flex;justify-content:center;gap:7px;z-index:5;pointer-events:none}.pager i{width:6px;height:6px;border-radius:50%;background:rgba(255,255,255,.28)}.pager i.on{background:#f5f5f7}</style></head>'
  + '<body><iframe sandbox="allow-scripts allow-same-origin allow-pointer-lock allow-forms" allow="autoplay" src="' + PIECE + '"></iframe><div class="edge l"></div><div class="edge r"></div>'
  + '<div class="pager"><i></i><i></i><i class="on"></i></div><script>const f=document.querySelector("iframe");f.addEventListener("load",()=>f.contentWindow.postMessage({gyeot:"insets",top:24,bottom:24},new URL(f.src).origin));</script></body></html>';
/** Two pages side by side as the launcher's (a sideways scroller, launcher.html #pages): another page shown, the LP page next to it, out of sight. */
const PAGES = '<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;height:100%;background:#000;overflow:hidden}'
  + '#pages{position:absolute;inset:0;display:flex;overflow-x:auto;overflow-y:hidden;scroll-snap-type:x mandatory;overscroll-behavior:none;scrollbar-width:none}'
  + '.page{flex:0 0 100%;height:100%;scroll-snap-align:start;position:relative;overflow:hidden}iframe{position:absolute;inset:0;width:100%;height:100%;border:0}#home{background:#223}</style></head>'
  + '<body><div id="pages"><section class="page" id="home"></section><section class="page"><iframe sandbox="allow-scripts allow-same-origin allow-pointer-lock allow-forms" allow="autoplay" src="' + PIECE + '"></iframe></section></div>'
  + '<script>window.toPage=i=>{const p=document.getElementById("pages");p.scrollTo({left:i*p.clientWidth,behavior:"instant"})};'
  + 'window.tellShown=on=>{const f=document.querySelector("iframe");f.contentWindow.postMessage({gyeot:"shown",on},new URL(f.src).origin)};</script></body></html>';

async function open(browser, page_html, shelf, calls, deck, o = {}) {
  const ctx = await browser.newContext({ viewport: { width: 412, height: 900 }, deviceScaleFactor: o.dsf || 2, hasTouch: true, isMobile: true });
  await ctx.addInitScript(o => { window.__GY_DECK = o; }, deck || {});
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('console', m => { if (m.type() === 'error' && !/404|Failed to load resource/.test(m.text())) errors.push('console: ' + m.text()); });
  await page.route('**/*', async route => {
    const r = route.request(), u = new URL(r.url());
    if (u.host === 'gyeot.local' && u.pathname === '/frame.html') return route.fulfill({ body: FRAME, contentType: 'text/html' });
    if (u.host === 'gyeot.local' && u.pathname === '/pages.html') return route.fulfill({ body: PAGES, contentType: 'text/html' });
    if (u.origin !== LPH) return route.fulfill({ status: 404, body: '' });
    if (u.pathname === FIXES.path) return route.fulfill({ body: page_html, contentType: 'text/html; charset=utf-8' });
    const p = u.pathname;
    if (p === '/__gyeot/lib/three.module.js') return route.fulfill({ body: fs.readFileSync(path.join(ASSETS, 'three-0.170.0.module.min.js')), contentType: 'text/javascript' });
    let m = p.match(/^\/__gyeot\/addon\/(vinyl\.css|vinyl\.js|deck3d\.js|walnut-(?:col|nor|rgh)\.jpg)$/);
    if (m) return route.fulfill({ body: fs.readFileSync(path.join(ASSETS, m[1])), contentType: m[1].endsWith('.css') ? 'text/css' : m[1].endsWith('.jpg') ? 'image/jpeg' : 'text/javascript' });
    if (!p.startsWith('/__gyeot/')) return route.fulfill({ status: 404, body: '' });
    if (u.searchParams.get('k') !== KEY) return route.fulfill({ status: 403, body: '' });
    const json = o => route.fulfill({ body: JSON.stringify(o), contentType: 'application/json', headers: { 'Cache-Control': 'no-store' } });
    const list = () => ({ rev: shelf.rev, items: shelf.items, pick: shelf.pick });
    const locked = { locked: true, rev: -1, items: [], pick: '' };
    if (p === '/__gyeot/music/list.json') { calls.list++; return json(shelf.locked ? locked : list()); }
    if (p === '/__gyeot/music/pick') {
      if (shelf.locked) return json(locked);
      calls.pick.push(u.searchParams.get('kind')); shelf.pick = 'open';
      if (shelf.pickNever) return json({ pending: true, rev: shelf.rev, via: 'app' }); // (the picker gone without an answer)
      // The user picks two files in Android's picker a moment later (MusicPickActivity → PieceMusicStore).
      setTimeout(() => { shelf.items = ITEMS.slice(); shelf.rev++; shelf.pick = 'done'; }, 700);
      return json({ pending: true, rev: shelf.rev, via: 'app' });
    }
    if (p === '/__gyeot/deck/stats') { (calls.stats = calls.stats || []).push(Object.fromEntries(u.searchParams)); return route.fulfill({ status: 204, body: '' }); }
    m = p.match(/^\/__gyeot\/music\/([0-9a-f]{12})(\/art|\/remove)?$/);
    if (!m) return route.fulfill({ status: 404, body: '' });
    if (r.method() === 'DELETE' || m[2] === '/remove') { calls.del.push(m[1]); shelf.items = shelf.items.filter(t => t.id !== m[1]); shelf.rev++; return json(list()); }
    const bytes = FILES[m[1]];
    if (m[2] || !bytes || !shelf.items.some(t => t.id === m[1])) return route.fulfill({ status: 404, body: '' });
    calls.media.push(m[1]);
    const range = /bytes=(\d*)-(\d*)/.exec(r.headers()['range'] || '');
    if (!range) return route.fulfill({ status: 200, body: bytes, headers: { 'Content-Type': 'audio/wav', 'Accept-Ranges': 'bytes', 'Content-Length': String(bytes.length) } });
    const first = range[1] === '' ? bytes.length - +range[2] : +range[1], last = range[1] !== '' && range[2] !== '' ? Math.min(+range[2], bytes.length - 1) : bytes.length - 1;
    if (first >= bytes.length) return route.fulfill({ status: 416, body: '', headers: { 'Content-Range': 'bytes */' + bytes.length } });
    return route.fulfill({ status: 206, body: bytes.subarray(first, last + 1), headers: { 'Content-Type': 'audio/wav', 'Accept-Ranges': 'bytes',
      'Content-Range': 'bytes ' + first + '-' + last + '/' + bytes.length, 'Content-Length': String(last - first + 1) } });
  });
  await page.goto('https://gyeot.local/' + (o.pages ? 'pages.html' : 'frame.html'));
  const frame = page.frames().find(f => f.url().startsWith(PIECE)) || await new Promise(res => page.on('frameattached', f => res(f)));
  await frame.waitForLoadState('load');
  return { page, ctx, frame, errors };
}
const T = { test: true };
const until = async (frame, fn, arg, ms = 12000, what = '') => {
  try { await frame.waitForFunction(fn, arg, { timeout: ms, polling: 100 }); }
  catch (e) { throw new Error('waited ' + ms + ' ms for ' + (what || fn.toString().slice(0, 140)) + ': ' + JSON.stringify(await frame.evaluate(() => window.__lp && { a: __lp.albumIdx, t: __lp.trackIdx, playing: __lp.playing, busy: __lp.busy, pos: __lp.songPos }).catch(() => null))); }
};
const armAngle = f => f.evaluate(() => __lp.arm.angle);
const needle = f => f.evaluate(() => +(/rotate\(([-\d.]+)/.exec(document.getElementById('ndL').getAttribute('transform')) || [0, -50])[1]);

(async () => {
  const original = await live();
  if (!original) {
    console.log('SKIPPED: the piece\'s site (' + HOST + ') did not answer and there is no copy in tests/browser/.cache: nothing was checked.');
    process.exit(2);
  }
  await check('every fix of piece-057.json is found exactly once in the live piece, in order (else the app shows the piece as it is)', async () => {
    FIXES.fixes.forEach((f, i) => assert.strictEqual(original.split(f.find).length - 1, 1, 'fix ' + (i + 1) + ' (' + f.why + ') occurs ' + (original.split(f.find).length - 1) + ' times in the piece'));
    tune(original, FIXES.fixes);
  });
  const fixes = process.env.VINYL_PLAIN ? FIXES.fixes.filter(f => f.why.startsWith('noise:')) : FIXES.fixes;
  const html = tune(original, fixes).split('__GY_KEY__').join(KEY);

  const browser = await chromium.launch({ args: ['--autoplay-policy=user-gesture-required'] });
  const shelf = { rev: 1, items: [], pick: '' }, calls = { list: 0, pick: [], media: [], del: [] };
  // The list and 내 음악 with the 3D deck off: software WebGL here is far slower than a phone's GPU, and its frames would slow
  // the piece (whose clock takes at most 0.1 s a frame, and whose VU needles are stepped by the frame). The deck is checked after.
  const { page, ctx, frame, errors } = await open(browser, html, shelf, calls, { off: true });
  try {
    await check('the add-on is on the page: its bar, and 내 음악\'s sleeve once the app has answered', async () => {
      await until(frame, () => !!document.getElementById('gyBar') && document.querySelector('.sleeve[data-album="3"]'), null, 10000, 'the add-on and 내 음악\'s sleeve');
      await frame.waitForTimeout(400);
      // four records in the crate, four columns, every sleeve's picture at the same height (a one-line name too)
      const c = await frame.evaluate(() => ({ cols: getComputedStyle(document.querySelector('.sleeves')).gridTemplateColumns.split(' ').length,
        tops: [...document.querySelectorAll('.sleeve .art')].map(a => Math.round(a.getBoundingClientRect().top)) }));
      assert.strictEqual(c.cols, 4);
      assert.strictEqual(new Set(c.tops).size, 1, 'the sleeves\' pictures in a line: ' + c.tops);
    });

    await check('the page opens without a script error', async () => assert.deepStrictEqual(errors, []));
    await check('on the first screen, no scrolling: a small bar (≤ 40 px high) clear of the edge strips and above the page dots', async () => {
      const b = await frame.evaluate(() => { const r = document.getElementById('gyBar').getBoundingClientRect(); const at = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
        return { top: r.top, bottom: r.bottom, left: r.left, right: r.right, h: r.height, w: r.width, vh: innerHeight, vw: innerWidth, y: scrollY, mine: !!at && !!at.closest('#gyBar') }; });
      assert.strictEqual(b.y, 0, 'not scrolled');
      assert(b.h <= 40, 'height ' + b.h);
      assert(b.top >= 0 && b.bottom <= b.vh - 24 - 16, 'above the system bar and the dots (bottom+10…16): ' + b.bottom + ' of ' + b.vh);
      assert(b.left >= 22 && b.right <= b.vw - 22, 'clear of the 22 px edges: ' + b.left + '…' + b.right);
      assert(b.w <= 320, 'not a full-width bar: ' + b.w);
      assert(b.mine, 'the bar is what a tap there reaches');
      const t = await frame.locator('#gyBar .gy-t').textContent();
      assert.strictEqual(t, 'Lanterns on the Towpath');
      if (SHOTS) await page.screenshot({ path: path.join(SHOTS, 'preview_list_closed.png') });
    });
    await check('the list opens over the page with every record (and 내 음악), without scrolling the page', async () => {
      await frame.locator('#gyBar .gy-list').tap();
      const s = await frame.evaluate(() => { const r = document.getElementById('gySheet').getBoundingClientRect();
        return { top: r.top, bottom: r.bottom, left: r.left, right: r.right, vw: innerWidth, y: scrollY, chips: [...document.querySelectorAll('.gy-chip')].map(c => c.textContent),
          rows: [...document.querySelectorAll('.gy-row .gy-ttl b')].map(b => b.textContent), current: document.querySelector('.gy-row[aria-current="true"] b')?.textContent }; });
      assert.strictEqual(s.y, 0);
      assert(s.top >= 0 && s.bottom <= 900 - 24 - 26 - 38, 'over the bar, on screen: ' + s.top + '…' + s.bottom);
      assert(s.left >= 22 && s.right <= s.vw - 22);
      assert.deepStrictEqual(s.chips, ['Moss & Lantern', 'Tidewater', 'Brass Const.', '내 음악']);
      assert.deepStrictEqual(s.rows, ['Lanterns on the Towpath', 'Moss Keeps Time', 'The Last Ferry Home']);
      assert.strictEqual(s.current, 'Lanterns on the Towpath');
    });
    await check('a track of another record picked in the list: that record, that track, playing (the synthesizer)', async () => {
      await frame.locator('.gy-chip', { hasText: 'Tidewater' }).tap();
      await frame.locator('.gy-row', { hasText: 'Salt on the Window' }).tap();
      await until(frame, () => __lp.albumIdx === 1 && __lp.trackIdx === 1 && __lp.playing && !__lp.busy, null, 12000, 'Tidewater ii. playing');
      const st = await frame.evaluate(() => ({ piece: [...document.querySelectorAll('#tracks button')].map(b => b.getAttribute('aria-current')), sheet: document.querySelector('.gy-row[aria-current="true"] b')?.textContent,
        bar: document.querySelector('#gyBar .gy-t').textContent, session: !!__lp.session, ctx: __lp.actx && __lp.actx.state }));
      assert.deepStrictEqual(st.piece, ['false', 'true', 'false'], 'the piece\'s own list marks it');
      assert.strictEqual(st.sheet, 'Salt on the Window');
      assert.strictEqual(st.bar, 'Salt on the Window');
      assert(st.session, 'the synthesizer plays the record');
      assert.strictEqual(st.ctx, 'running', 'the tap let the sound start');
    });
    await check('+ 파일 추가 asks the app\'s picker; the picked files come onto 내 음악\'s list', async () => {
      await frame.locator('.gy-chip', { hasText: '내 음악' }).tap();
      assert.match(await frame.locator('.gy-empty').textContent(), /음악 파일을 골라/);
      await frame.locator('.gy-add', { hasText: '+ 파일 추가' }).tap();
      await until(frame, () => document.querySelectorAll('.gy-row').length === 2, null, 8000, 'two files on the list');
      assert.deepStrictEqual(calls.pick, ['files']);
      const rows = await frame.evaluate(() => [...document.querySelectorAll('.gy-row')].map(b => b.querySelector('.gy-num').textContent + ' ' + b.querySelector('b').textContent + ' ' + b.querySelector('.gy-dur').textContent));
      assert.deepStrictEqual(rows, ['A1 분홍 소리 시험 0:09', 'A2 A Quiet Tone 0:05'], 'the lengths the phone said');
      assert.match(await frame.locator('.gy-note').textContent(), /2곡을 담았어요/);
      assert.deepStrictEqual(await frame.evaluate(() => [...document.querySelectorAll('.gy-add')].map(b => b.textContent + (b.disabled ? ' (off)' : ''))), ['+ 파일 추가', '+ 폴더'], 'ready to add more');
      if (SHOTS) await page.screenshot({ path: path.join(SHOTS, 'preview_list_mine.png') });
    });
    let synthDb = 0;
    await check('a file picked from 내 음악 plays on the turntable: the audio element, not the synthesizer; the VU meters, the arm and the clock follow it', async () => {
      // The synthesizer's level at the master, to compare (vinyl.js TRIM).
      synthDb = await frame.evaluate(async () => { const an = __lp.actx.createAnalyser(); an.fftSize = 2048; __lp.master.connect(an); const b = new Float32Array(2048); let e = 0, n = 0;
        for (let i = 0; i < 30; i++) { an.getFloatTimeDomainData(b); let s = 0; for (const x of b) s += x * x; e += s / b.length; n++; await new Promise(r => setTimeout(r, 50)); } an.disconnect(); return 10 * Math.log10(e / n); });
      await frame.locator('.gy-row', { hasText: '분홍 소리 시험' }).tap();
      await until(frame, () => __lp.albumIdx === 3 && __lp.playing && !__lp.busy && !__gy.audio.paused && __gy.audio.currentTime > 0.3, null, 15000, '내 음악 A1 playing');
      // its real length from the file: the piece's list line and the clock corrected in place (the list not built again)
      await frame.evaluate(() => { window._line = document.querySelector('#tracks button'); });
      await until(frame, () => Math.abs(__lp.ALBUMS[3].tracks[0].dur - 6) < 0.1, null, 5000, 'A1\'s real length');
      const fixed = await frame.evaluate(() => ({ same: document.querySelector('#tracks button') === window._line, dur: document.querySelector('#tracks button .dur').textContent, total: document.getElementById('tDur').textContent }));
      assert.deepStrictEqual(fixed, { same: true, dur: '0:06', total: '0:06' });
      const a0 = await armAngle(frame), t0 = await frame.locator('#tEl').textContent();
      const st = await frame.evaluate(() => ({ src: __gy.audio.src, session: __lp.session, label: getComputedStyle(document.getElementById('lab3')).display, title: document.getElementById('lab3t').textContent,
        cover: document.getElementById('cover').className, sleeve: document.querySelector('.sleeve[data-album="3"]').getAttribute('aria-pressed') }));
      assert.match(st.src, /\/__gyeot\/music\/a1b2c3d4e5f6\?k=/);
      assert.strictEqual(st.session, null, 'no synthesizer session');
      assert.strictEqual(st.label, 'inline', 'the 내 음악 label is on the record');
      assert.strictEqual(st.title, '분홍 소리 시험');
      assert.strictEqual(st.cover, 'art a3'); assert.strictEqual(st.sleeve, 'true');
      // The level: as loud as the records (±2.5 dB), and about 0 VU (the red 0 is at 20.8°).
      const mineDb = await frame.evaluate(async () => { const an = __lp.actx.createAnalyser(); an.fftSize = 2048; __lp.master.connect(an); const b = new Float32Array(2048); let e = 0, n = 0;
        for (let i = 0; i < 24; i++) { an.getFloatTimeDomainData(b); let s = 0; for (const x of b) s += x * x; e += s / b.length; n++; await new Promise(r => setTimeout(r, 50)); } an.disconnect(); return 10 * Math.log10(e / n); });
      const needles = []; for (let i = 0; i < 8; i++) { needles.push(await needle(frame)); await frame.waitForTimeout(100); }
      const mean = needles.reduce((a, b) => a + b, 0) / needles.length;
      console.log('     levels at the master: records ' + synthDb.toFixed(1) + ' dB, −14 LUFS file ' + mineDb.toFixed(1) + ' dB; needle ' + mean.toFixed(1) + '°');
      assert(Math.abs(mineDb - synthDb) <= 2.5, 'the file as loud as the records: ' + mineDb.toFixed(1) + ' vs ' + synthDb.toFixed(1) + ' dB');
      assert(mean > 8 && mean < 34, 'the VU needle near 0 VU (20.8°): ' + needles.map(x => x.toFixed(0)).join(' '));
      await frame.waitForTimeout(1500);
      const a1 = await armAngle(frame), t1 = await frame.locator('#tEl').textContent(), k1 = await frame.evaluate(() => __lp.trackIdx);
      assert(Math.abs(a1 - a0) > 0.05, 'the arm moves in with the music: ' + a0 + ' → ' + a1);
      assert(t1 !== t0 || k1 !== 0, 'the clock runs (or the file has ended and the next one begun): ' + t0 + ' → ' + t1 + ', track ' + k1);
    });
    await check('at the end of a file the next one starts (from its beginning), and ⏮ goes back', async () => {
      await until(frame, () => __lp.trackIdx === 1 && __lp.playing && !__gy.audio.paused && __gy.audio.currentTime > 0.2, null, 12000, 'A2 after A1');
      assert.match(await frame.evaluate(() => __gy.audio.src), /0f1e2d3c4b5a/);
      assert.strictEqual(await frame.locator('#gyBar .gy-t').textContent(), 'A Quiet Tone');
      assert.strictEqual(await frame.locator('#ttitle').textContent(), 'A2 A Quiet Tone');
      // The piece's own buttons, under the list: it closes first (a tap beside it only closes it).
      await frame.locator('#gyBar .gy-list').tap();
      assert.strictEqual(await frame.evaluate(() => document.getElementById('gySheet').hidden), true, 'the list closes');
      await frame.locator('#bPrev').tap();
      await until(frame, () => __lp.trackIdx === 0 && __lp.playing && !__lp.busy && !__gy.audio.paused, null, 10000, 'back to A1');
      assert.match(await frame.evaluate(() => __gy.audio.src), /a1b2c3d4e5f6/);
      await frame.locator('#bNext').tap();
      await until(frame, () => __lp.trackIdx === 1 && __lp.playing && !__lp.busy && !__gy.audio.paused, null, 10000, '⏭ to A2');
      assert(calls.media.length >= 3, 'the files were read from /__gyeot/music: ' + calls.media.join(','));
    });
    await check('the VU needles hold when frames come far apart (a busy phone): near 0 VU, never off the scale', async () => {
      // each frame made 95 ms long (the piece takes at most 0.1 s a frame): one step a frame would swing the needles off
      await frame.evaluate(() => { window._slow = __gy.onFrame(() => { const t = performance.now(); while (performance.now() - t < 95); }); });
      try {
        await frame.waitForTimeout(500);
        const n = []; for (let i = 0; i < 12; i++) { n.push(await needle(frame)); await frame.waitForTimeout(120); }
        console.log('     needle with 95 ms frames: ' + n.map(x => x.toFixed(0)).join(' ') + '°');
        assert(n.every(x => x > 8 && x < 34), 'between 8° and 34° (0 VU is 20.8°): ' + n.map(x => x.toFixed(1)).join(' '));
      } finally { await frame.evaluate(() => window._slow && window._slow()); }
    });
    await check('the bar\'s ⏯ pauses and plays, and a file taken off the list (two taps on ×) leaves it', async () => {
      await frame.evaluate(() => scrollTo(0, 0));
      await frame.locator('#gyBar .gy-play').tap();
      await until(frame, () => !__lp.playing && !__lp.busy && __gy.audio.paused, null, 6000, 'paused');
      await frame.locator('#gyBar .gy-list').tap();
      await frame.locator('.gy-chip', { hasText: '내 음악' }).tap();
      const x = frame.locator('.gy-x').nth(0);
      await x.tap(); assert.strictEqual(await x.textContent(), '빼기');
      await x.tap();
      await until(frame, () => document.querySelectorAll('.gy-row').length === 1, null, 5000, 'one file left');
      assert.deepStrictEqual(calls.del, ['a1b2c3d4e5f6']);
      assert.strictEqual(await frame.evaluate(() => __lp.ALBUMS[3].tracks[__lp.trackIdx].id), '0f1e2d3c4b5a', 'still on the file that is left');
      // Another record's list while 내 음악 is on: its own numbers, and all four records' chips on the sheet's width.
      await frame.locator('.gy-chip', { hasText: 'Moss & Lantern' }).tap(); await frame.waitForTimeout(250);
      const v = await frame.evaluate(() => { const box = document.querySelector('.gy-albums').getBoundingClientRect();
        return { nums: [...document.querySelectorAll('.gy-row .gy-num')].map(n => n.textContent),
          chips: [...document.querySelectorAll('.gy-chip')].map(c => { const r = c.getBoundingClientRect(); return r.left >= box.left - 0.5 && r.right <= box.right + 0.5; }) }; });
      assert.deepStrictEqual(v.nums, ['i.', 'ii.', 'iii.']);
      assert.deepStrictEqual(v.chips, [true, true, true, true], 'every record\'s chip in sight');
      if (SHOTS) await page.screenshot({ path: path.join(SHOTS, 'preview_list_open.png') });
      await frame.locator('#gyBar .gy-list').tap();
      assert.strictEqual(await frame.evaluate(() => document.getElementById('gySheet').hidden), true, 'the list closes');
      await frame.locator('#gyBar .gy-play').tap();
      await until(frame, () => __lp.playing && !__lp.busy && !__gy.audio.paused, null, 8000, 'playing again from the bar');
    });
    await check('no script error all along', async () => assert.deepStrictEqual(errors, []));
  } finally { await ctx.close(); }
  // Alone on the creator's own site (as the app serves it there: the add-on, never a key), where nothing answers 내 음악.
  const alone = await browser.newContext({ viewport: { width: 412, height: 900 }, hasTouch: true, isMobile: true });
  try {
    const page = await alone.newPage(), errs = [], asked = [];
    page.on('pageerror', e => errs.push(e.message));
    const keyless = tune(original, fixes).split('__GY_KEY__').join('');
    await page.route('**/*', route => {
      const u = new URL(route.request().url());
      if (u.pathname.startsWith('/__gyeot/music') || u.pathname.startsWith('/__gyeot/deck')) asked.push(u.pathname);
      if (u.origin === HOST && u.pathname === FIXES.path) return route.fulfill({ body: keyless, contentType: 'text/html; charset=utf-8' });
      const m = u.pathname.match(/^\/__gyeot\/addon\/(vinyl\.css|vinyl\.js|deck3d\.js)$/);
      if (u.origin === HOST && m) return route.fulfill({ body: fs.readFileSync(path.join(ASSETS, m[1])), contentType: m[1].endsWith('.css') ? 'text/css' : 'text/javascript' });
      return route.fulfill({ status: 404, body: '' });
    });
    await page.goto(SITE_PIECE);
    await check('on the creator\'s site, no key: the bar above the phone\'s dock, the three records\' lists, no 내 음악 (not even asked), no error', async () => {
      await page.waitForSelector('#gyBar');
      await page.waitForTimeout(600);
      const b = await page.evaluate(() => { const r = document.getElementById('gyBar').getBoundingClientRect(); return { bottom: r.bottom, vh: innerHeight }; });
      assert(Math.abs(b.vh - b.bottom - (120 + 26)) <= 1, 'over the dock of the phone\'s own home screen: ' + (b.vh - b.bottom));
      await page.locator('#gyBar .gy-list').tap();
      assert.deepStrictEqual(await page.evaluate(() => [...document.querySelectorAll('.gy-chip')].map(c => c.textContent)), ['Moss & Lantern', 'Tidewater', 'Brass Const.']);
      assert.strictEqual(await page.evaluate(() => document.querySelectorAll('.sleeve').length), 3);
      assert.strictEqual(await page.evaluate(() => getComputedStyle(document.querySelector('.sleeves')).gridTemplateColumns.split(' ').length), 3, 'the crate\'s own three columns');
      assert.strictEqual(await page.evaluate(() => getComputedStyle(document.documentElement).fontSynthesisStyle), 'none', 'Korean never slanted by the browser');
      assert.deepStrictEqual(asked, [], 'nothing of 내 음악 asked without a key');
      assert.deepStrictEqual(errs, []);
    });
  } finally { await alone.close(); }
  // Locked (a wallpaper on the lock screen): the app says so; the page asks again once it is shown unlocked. And a picker that
  // went away without an answer: its button opens it again.
  {
    const shelfL = { rev: 3, items: ITEMS.slice(), pick: '', locked: true }, callsL = { list: 0, pick: [], media: [], del: [] };
    const o = await open(browser, html, shelfL, callsL, { off: true });
    try {
      await check('opened while locked: no 내 음악 yet; shown unlocked, its list comes', async () => {
        await until(o.frame, () => !!document.getElementById('gyBar'), null, 10000, 'the add-on');
        await o.frame.waitForTimeout(800);
        assert.strictEqual(await o.frame.evaluate(() => document.querySelectorAll('.sleeve').length), 3, 'nothing of the user\'s while locked');
        const asked = callsL.list;
        shelfL.locked = false;
        await o.frame.evaluate(() => dispatchEvent(new Event('focus')));
        await until(o.frame, () => document.querySelectorAll('.sleeve').length === 4 && __lp.ALBUMS[3] && __lp.ALBUMS[3].tracks.length === 2, null, 6000, '내 음악 once unlocked');
        assert(callsL.list > asked, 'asked again');
      });
      await check('the picker gone without an answer: + 파일 추가 opens it again', async () => {
        shelfL.pickNever = true;
        await o.frame.locator('#gyBar .gy-list').tap();
        await o.frame.locator('.gy-chip', { hasText: '내 음악' }).tap();
        await o.frame.locator('.gy-add', { hasText: '+ 파일 추가' }).tap();
        await until(o.frame, () => [...document.querySelectorAll('.gy-add')].some(b => b.textContent === '고르는 중…' && !b.disabled), null, 3000, 'waiting, still pressable');
        await o.frame.locator('.gy-add', { hasText: '고르는 중…' }).tap();
        await until(o.frame, () => true, null, 500);
        await o.frame.waitForTimeout(400);
        assert.deepStrictEqual(callsL.pick, ['files', 'files'], 'asked twice');
        assert.deepStrictEqual(o.errors, []);
      });
    } finally { await o.ctx.close(); }
  }
  await browser.close();
  // The three.js deck, on its own page: drawn at one device pixel per CSS pixel and never stepping down to the CSS deck
  // here (software WebGL), as the phone draws it otherwise.
  const b3 = await chromium.launch({ args: ['--autoplay-policy=user-gesture-required'] });
  try {
    const shelf3 = { rev: 1, items: [], pick: '' }, calls3 = { list: 0, pick: [], media: [], del: [] };
    const { ctx, frame, errors } = await open(b3, html, shelf3, calls3, { test: true, adapt: false, pr: 1 });
    await until(frame, () => !!document.getElementById('gyBar'), null, 20000, 'the add-on');
    await check('the three.js deck draws in place of the CSS deck: walnut, the record, the arm; the CSS deck\'s parts hidden, its buttons still there', async () => {
      await until(frame, () => window.__gyDeck && (__gyDeck.ready || __gyDeck.css), null, 60000, 'the 3D deck');
      const d = await frame.evaluate(() => {
        const deck = document.getElementById('deck'), cv = deck.querySelector('canvas.gy-deck'), r = cv && cv.getBoundingClientRect(), b = deck.getBoundingClientRect();
        const vis = s => getComputedStyle(deck.querySelector(s)).visibility;
        // the plinth's wood (left of the platter), the vinyl (between the label and the edge), the label, the arm rest's post
        const k = b.width / 1000, at = (x, y) => [x * k, y * 780 / 780 * k];
        return { ready: __gyDeck.ready, css: __gyDeck.css, cls: deck.classList.contains('gy3d'), canvas: r && [r.width, r.height], deck: [b.width, b.height],
          plinth: vis('svg.plinth'), slot: vis('.slot'), arm: vis('svg.arm'), button: vis('.startstop'), hit: vis('.hit'),
          px: __gyDeck.probe([at(30, 390), at(200, 390), at(400, 330), at(873, 561), at(940, 700)]), stats: __gyDeck.stats() };
      });
      assert(d.ready && !d.css, 'drawn: ' + d.css);
      assert(d.cls, 'the deck shows the 3D one');
      assert.deepStrictEqual(d.canvas, d.deck, 'one canvas, the deck\'s size');
      assert.deepStrictEqual([d.plinth, d.slot, d.arm], ['hidden', 'hidden', 'hidden'], 'the CSS deck\'s parts hidden');
      assert.deepStrictEqual([d.button, d.hit], ['visible', 'visible'], 'its buttons still there to press');
      const [wood, vinyl, label, , wood2] = d.px, lum = p => 0.2126 * p[0] + 0.7152 * p[1] + 0.0722 * p[2];
      for (const w of [wood, wood2]) assert(w[0] > w[2] + 12 && lum(w) > 25 && lum(w) < 200, 'walnut (warm, lit): ' + w);
      assert(lum(vinyl) < 40, 'the vinyl black: ' + vinyl);
      assert(label[1] > label[0] && label[1] > label[2], 'the green label: ' + label);
      assert(d.px.every(p => p[3] === 255), 'opaque');
      console.log('     ready in ' + d.stats.prep.first + ' ms (pictures ' + d.stats.prep.pictures + ', light ' + (d.stats.prep.pmrem - d.stats.prep.pictures) + ', shaders ' + (d.stats.prep.shaders - d.stats.prep.pmrem) + ', bake ' + d.stats.prep.lastBake + '); ' + d.stats.w + '×' + d.stats.h + ' ×' + d.stats.pr);
    });
    await check('a still deck is not drawn again (one frame stays on the canvas)', async () => {
      const n0 = await frame.evaluate(() => __gyDeck.draws);
      await frame.waitForTimeout(1500);
      const n1 = await frame.evaluate(() => __gyDeck.draws);
      assert.strictEqual(n1, n0, 'frames drawn while nothing moved: ' + (n1 - n0));
    });
    await check('3D, playing: the record turns, the arm is where the piece\'s arm is (within 2 px), at most 30 frames a second', async () => {
      await frame.locator('#recHit').tap();                // the record pressed: it plays
      await until(frame, () => __lp.playing && __lp.omega > 150, null, 20000, 'playing, the platter up to speed');
      const k = await frame.evaluate(() => document.getElementById('deck').getBoundingClientRect().width / 1000);
      // the label's lettering (its arcs at r 73 and 84): the same points on the canvas, a quarter of a turn or so apart
      const ring = [...Array(48)].map((_, i) => { const r = i % 2 ? 73 : 84, a = i / 48 * 6.2832; return [(400 + r * Math.cos(a)) * k, (390 + r * Math.sin(a)) * k]; });
      const p0 = await frame.evaluate(r => ({ px: __gyDeck.probe(r), spin: __lp.spin, n: __gyDeck.draws, t: performance.now() }), ring);
      await until(frame, s0 => { const d = ((__lp.spin - s0) % 360 + 360) % 360; return d > 40 && d < 320; }, p0.spin, 4000, 'the record a little further round');
      const q = await frame.evaluate(r => ({ px: __gyDeck.probe(r), spin: __lp.spin }), ring);
      const moved = p0.px.reduce((s, a, i) => s + Math.abs(a[0] - q.px[i][0]) + Math.abs(a[1] - q.px[i][1]) + Math.abs(a[2] - q.px[i][2]), 0) / ring.length;
      assert(moved > 6, 'the label turned on the canvas (mean change ' + moved.toFixed(1) + ' over ' + (q.spin - p0.spin).toFixed(0) + '°)');
      await frame.waitForTimeout(2000);
      const p1 = await frame.evaluate(() => ({ n: __gyDeck.draws, t: performance.now() }));
      const n = p1.n - p0.n, s = (p1.t - p0.t) / 1000;
      assert(n >= 2, 'drawn while turning: ' + n);
      assert(n <= Math.ceil(s * 30) + 2, 'at most 30 a second: ' + n + ' in ' + s.toFixed(2) + ' s');
      const st = await frame.evaluate(() => __gyDeck.stylus());
      const off = Math.hypot(st.x - st.css.x, st.y - st.css.y);
      assert(off <= 2, 'the stylus ' + off.toFixed(2) + ' px from the piece\'s: ' + JSON.stringify(st));
      const b = await frame.evaluate(() => ({ ms: __gyDeck.bench(6), s: __gyDeck.stats() }));
      console.log('     one frame here (software WebGL): ' + b.ms.toFixed(1) + ' ms, while playing ' + b.s.fps + ' fps; ' + b.s.calls + ' draw calls, ' + b.s.triangles + ' triangles, '
        + b.s.programs + ' programs, ' + b.s.w + '×' + b.s.h + ' ×' + b.s.pr + '; drew ' + n + ' frames in ' + s.toFixed(1) + ' s');
    });
    await check('3D, paused: the platter runs down and the drawing stops', async () => {
      await frame.locator('#gyBar .gy-play').tap();
      await until(frame, () => !__lp.playing && !__lp.busy && __lp.omega === 0 && !__lp.tweening, null, 25000, 'the platter stopped');
      await frame.waitForTimeout(300);
      const a = await frame.evaluate(() => ({ n: __gyDeck.draws, spin: __lp.spin }));
      await frame.waitForTimeout(1500);
      const b = await frame.evaluate(() => ({ n: __gyDeck.draws, spin: __lp.spin }));
      assert.strictEqual(b.spin, a.spin, 'the record stopped');
      assert.strictEqual(b.n, a.n, 'no frames while it stands');
      const st = await frame.evaluate(() => __gyDeck.stylus());
      assert(Math.hypot(st.x - st.css.x, st.y - st.css.y) <= 2, 'the arm back on its rest with the piece\'s: ' + JSON.stringify(st));
    });
    await check('3D, another record: the record flies out and in with the piece\'s own animation, the new label and rings come home', async () => {
      frame.evaluate(() => __lp.changeAlbum(2));               // (as its sleeve in the crate does; the crate is below the fold)
      await until(frame, () => !__gyDeck.state().home, null, 15000, 'the record off the platter');
      const away = await frame.evaluate(() => __gyDeck.state());
      await until(frame, () => __lp.albumIdx === 2 && !__lp.busy && __gyDeck.state().home && __gyDeck.state().label === 'lab2' && !__gyDeck.state().needB, null, 30000, 'the new record home, its label and rings drawn');
      const k = await frame.evaluate(() => document.getElementById('deck').getBoundingClientRect().width / 1000);
      const px = await frame.evaluate(p => __gyDeck.probe(p), [...Array(16)].map((_, i) => [(400 + 30 * Math.cos(i / 16 * 6.2832)) * k, (390 + 30 * Math.sin(i / 16 * 6.2832)) * k]));
      const blue = px.map(p => p[2] - p[0]).sort((a, b) => a - b)[8];
      assert(blue > 6, 'the navy Brass Constellations label (blue over red, the median of 16 points: ' + blue + ')');
      assert.strictEqual(away.home, false);
    });
    await check('3D: no script error', async () => assert.deepStrictEqual(errors, []));
    await check('3D given up (as when too slow): the CSS deck back, the context let go, the app told why once', async () => {
      await frame.evaluate(() => __gyDeck.off('test'));
      const d = await frame.evaluate(() => { const deck = document.getElementById('deck');
        return { css: __gyDeck.css, lost: __gyDeck.lost(), canvas: !!deck.querySelector('canvas.gy-deck'), cls: deck.classList.contains('gy3d'), plinth: getComputedStyle(deck.querySelector('svg.plinth')).visibility,
          handles: ['probe', 'bench', '_'].filter(k => k in __gyDeck) }; });
      assert.deepStrictEqual(d, { css: 'test', lost: true, canvas: false, cls: false, plinth: 'visible', handles: [] });
      await frame.waitForTimeout(400);
      assert.deepStrictEqual((calls3.stats || []).filter(q => q.css).map(q => q.css), ['test']);
      const n = await frame.evaluate(() => __gyDeck.draws); await frame.waitForTimeout(800);
      assert.strictEqual(await frame.evaluate(() => __gyDeck.draws), n, 'no more frames');
      assert.deepStrictEqual(errors, []);
    });
    await ctx.close();

    // At most 30 frames a second, counted without drawing (software WebGL here draws far fewer, so a slow draw cannot hide a
    // missing limit), at a phone's 3 device pixels per CSS pixel: drawn at 2. Then the steps down when slow, to the CSS deck.
    const shelf4 = { rev: 1, items: [], pick: '' }, calls4 = { list: 0, pick: [], media: [], del: [] };
    const q = await open(b3, html, shelf4, calls4, { test: true, adapt: false, noRender: true }, { dsf: 3 });
    await until(q.frame, () => !!document.getElementById('gyBar'), null, 20000, 'the add-on');
    await check('3D at most 30 frames a second while the record turns, at most 2 device pixels per CSS pixel', async () => {
      await until(q.frame, () => window.__gyDeck && (__gyDeck.ready || __gyDeck.css), null, 90000, 'the 3D deck');
      const st = await q.frame.evaluate(() => ({ s: __gyDeck.stats(), w: document.getElementById('deck').getBoundingClientRect().width, dpr: devicePixelRatio }));
      assert.strictEqual(st.dpr, 3);
      assert.strictEqual(st.s.pr, 2, 'pixel ratio ' + st.s.pr);
      assert(Math.abs(st.s.w - Math.round(st.w * 2)) <= 1, 'the canvas at 2×: ' + st.s.w + ' for ' + st.w);
      await q.frame.locator('#recHit').tap();
      await until(q.frame, () => __lp.playing && __lp.omega > 150, null, 20000, 'playing, the platter up to speed');
      const a = await q.frame.evaluate(() => ({ n: __gyDeck.draws, f: __gy.frames, t: performance.now() }));
      await q.frame.waitForTimeout(3000);
      const b = await q.frame.evaluate(() => ({ n: __gyDeck.draws, f: __gy.frames, t: performance.now() }));
      const s = (b.t - a.t) / 1000, n = b.n - a.n, f = b.f - a.f;
      console.log('     in ' + s.toFixed(2) + ' s: ' + f + ' frames of the piece, ' + n + ' drawn');
      assert(f > s * 40, 'the piece\'s frames come faster than 30 a second here (' + f + '), so the limit is what holds the deck');
      assert(n <= Math.ceil(s * 30) + 2, 'at most 30 a second: ' + n + ' in ' + s.toFixed(2) + ' s');
      assert(n >= s * 20, 'and it does draw while turning: ' + n);
    });
    await check('3D too slow: fewer pixels (2 → 1.6 → 1.3), then the CSS deck and the context let go', async () => {
      const seen = [];
      await q.frame.evaluate(() => { __gyDeck.opt.slow = [0, 0]; __gyDeck.opt.adapt = true; });
      for (let i = 0; i < 400; i++) {
        const st = await q.frame.evaluate(() => ({ pr: __gyDeck.stats().pr, css: __gyDeck.css }));
        if (seen[seen.length - 1] !== st.pr) seen.push(st.pr);
        if (st.css) break;
        await q.frame.waitForTimeout(100);
      }
      const d = await q.frame.evaluate(() => ({ css: __gyDeck.css, lost: __gyDeck.lost && __gyDeck.lost(), canvas: !!document.querySelector('#deck canvas.gy-deck'), cls: document.getElementById('deck').classList.contains('gy3d') }));
      assert.deepStrictEqual(seen, [2, 1.6, 1.3], 'the steps: ' + seen);
      assert.match(String(d.css), /^slow \d+ms$/);
      assert.deepStrictEqual([d.lost, d.canvas, d.cls], [true, false, false]);
      await q.frame.waitForTimeout(300);
      assert.deepStrictEqual((calls4.stats || []).filter(x => x.css).map(x => x.css.replace(/\d+/, 'N')), ['slow Nms'], 'the app told why');
      assert.deepStrictEqual(q.errors, []);
    });
    await q.ctx.close();

    // Out of sight in the launcher (the page next to the one shown): the piece gets no frames, though its page is not hidden.
    const shelf5 = { rev: 2, items: ITEMS.slice(), pick: '' }, calls5 = { list: 0, pick: [], media: [], del: [] };
    const w = await open(b3, html, shelf5, calls5, { test: true, adapt: false, pr: 1 }, { pages: true });
    const lp = w.frame, home = w.page;
    await check('the page next to the one shown: no frames, and nothing of the 3D deck made (three.js not even read)', async () => {
      await until(lp, () => !!document.getElementById('gyBar') && document.querySelector('.sleeve[data-album="3"]'), null, 20000, 'the add-on');
      // (a few frames come as it loads, before the browser sees it is out of sight; then none)
      await lp.waitForTimeout(1500);
      const f0 = await lp.evaluate(() => __gy.frames);
      await lp.waitForTimeout(1500);
      const d = await lp.evaluate(() => ({ frames: __gy.frames, started: __gyDeck.started, canvas: !!document.querySelector('#deck canvas'), hidden: document.hidden,
        three: performance.getEntriesByType('resource').some(e => /three\.module/.test(e.name)) }));
      console.log('     frames as it loaded out of sight: ' + f0);
      assert.deepStrictEqual(d, { frames: f0, started: false, canvas: false, hidden: false, three: false });
    });
    await check('shown, the deck begins; out of sight again, it draws nothing', async () => {
      await home.evaluate(() => toPage(1));
      await until(lp, () => window.__gyDeck && (__gyDeck.ready || __gyDeck.css), null, 90000, 'the 3D deck once seen');
      assert.strictEqual(await lp.evaluate(() => __gyDeck.css), null);
      await lp.locator('#gyBar .gy-list').tap();
      await lp.locator('.gy-chip', { hasText: '내 음악' }).tap();
      await lp.locator('.gy-row', { hasText: '분홍 소리 시험' }).tap();
      await until(lp, () => __lp.albumIdx === 3 && __lp.playing && !__lp.busy && !__gy.audio.paused && __gy.audio.currentTime > 0.2, null, 20000, 'A1 playing');
      await home.evaluate(() => toPage(0));
      await lp.waitForTimeout(400);
      const a = await lp.evaluate(() => ({ n: __gyDeck.draws, f: __gy.frames }));
      await lp.waitForTimeout(1500);
      const b = await lp.evaluate(() => ({ n: __gyDeck.draws, f: __gy.frames }));
      assert.deepStrictEqual([b.f - a.f, b.n - a.n], [0, 0], 'frames and draws out of sight');
    });
    await check('out of sight, a file\'s end starts the next one, and after the last the side ends', async () => {
      await until(lp, () => __lp.trackIdx === 1 && !__gy.audio.paused && /0f1e2d3c4b5a/.test(__gy.audio.src) && __gy.audio.currentTime > 0.3, null, 12000, 'A2 after A1, out of sight');
      assert.strictEqual(await lp.evaluate(() => document.querySelector('#gyBar .gy-t').textContent), 'A Quiet Tone');
      await until(lp, () => !__lp.playing && __lp.runout && __gy.audio.ended, null, 12000, 'the side ended after A2');
      const f = await lp.evaluate(() => __gy.frames); await lp.waitForTimeout(300);
      assert.strictEqual(await lp.evaluate(() => __gy.frames), f, 'still no frames');
    });
    await check('hidden (another app, the screen off), a file stops with the record; a tap plays it again', async () => {
      await home.evaluate(() => toPage(1));
      await until(lp, () => !__lp.busy && !__lp.runout, null, 20000, 'the arm home');
      await lp.locator('#gyBar .gy-play').tap();
      await until(lp, () => __lp.playing && !__lp.busy && !__gy.audio.paused, null, 15000, 'playing');
      await lp.evaluate(() => { Object.defineProperty(document, 'hidden', { configurable: true, get: () => true }); document.dispatchEvent(new Event('visibilitychange')); });
      await lp.waitForTimeout(300);
      const h = await lp.evaluate(() => ({ paused: __gy.audio.paused, playing: __lp.playing }));
      await lp.evaluate(() => { delete document.hidden; document.dispatchEvent(new Event('visibilitychange')); });
      assert.deepStrictEqual(h, { paused: true, playing: false });
      await until(lp, () => !__lp.busy, null, 5000, 'the arm up');
      await lp.waitForTimeout(800);
      assert.strictEqual(await lp.evaluate(() => __gy.audio.paused), true, 'not by itself when shown again');
      await lp.locator('#gyBar .gy-play').tap();
      await until(lp, () => __lp.playing && !__gy.audio.paused, null, 15000, 'a tap plays it');
      assert.deepStrictEqual(w.errors, []);
    });
    await check('where the home screen says a page is out of sight (the wallpaper\'s board), the deck draws nothing though frames come', async () => {
      await home.evaluate(() => toPage(1));
      await until(lp, () => __gy.frames > 0 && __lp.playing, null, 5000, 'in sight, playing');
      await home.evaluate(() => tellShown(false));
      await lp.waitForTimeout(300);
      const a = await lp.evaluate(() => ({ n: __gyDeck.draws, f: __gy.frames, shown: __gy.shown }));
      await lp.waitForTimeout(1200);
      const b = await lp.evaluate(() => ({ n: __gyDeck.draws, f: __gy.frames }));
      await home.evaluate(() => tellShown(true));
      await lp.waitForTimeout(600);
      const c = await lp.evaluate(() => __gyDeck.draws);
      assert.strictEqual(a.shown, false);
      assert(b.f - a.f > 20, 'frames still come: ' + (b.f - a.f));
      assert.strictEqual(b.n - a.n, 0, 'nothing drawn while said out of sight');
      assert(c > b.n, 'drawn again once in sight: ' + b.n + ' → ' + c);
    });
    await w.ctx.close();
  } finally { await b3.close(); }
  // WebGL off: the piece's own CSS deck, as before.
  const plain = await chromium.launch({ args: ['--disable-webgl', '--disable-3d-apis'] });
  try {
    const shelf2 = { rev: 1, items: [], pick: '' }, calls2 = { list: 0, pick: [], media: [], del: [] };
    const o = await open(plain, html, shelf2, calls2);
    await check('without WebGL the piece\'s own deck shows (the 3D one says why, the app hears it once)', async () => {
      await until(o.frame, () => window.__gyDeck && window.__gyDeck.css, null, 15000, 'the deck giving up');
      const d = await o.frame.evaluate(() => { const deck = document.getElementById('deck');
        return { css: __gyDeck.css, cls: deck.classList.contains('gy3d'), canvas: !!deck.querySelector('canvas.gy-deck'), plinth: getComputedStyle(deck.querySelector('svg.plinth')).visibility,
          arm: getComputedStyle(deck.querySelector('svg.arm')).visibility }; });
      assert.strictEqual(d.css, 'no webgl2'); assert.strictEqual(d.cls, false); assert.strictEqual(d.canvas, false);
      assert.deepStrictEqual([d.plinth, d.arm], ['visible', 'visible']);
      assert.deepStrictEqual(o.errors, []);
      await until(o.frame, () => true, null, 1000);
      await o.frame.waitForTimeout(800);
      assert.deepStrictEqual((calls2.stats || []).map(q => q.css), ['no webgl2'], 'the app is told once why');
    });
    await o.ctx.close();
  } finally { await plain.close(); }
  console.log(failed ? failed + ' failed, ' + passed + ' passed' : 'all ' + passed + ' passed');
  process.exit(failed ? 1 : 0);
})();

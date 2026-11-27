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
  On the Galaxy Z Fold (준: "갤폴드인데 lp페이지는 여전히 스크롤 해야해 차라리 탭으로 구분하면 어떨까", "우측 조작패널들 조작이 안돼 피치 큐",
  "mp3들은 자동인식해서 목록들을 쭉 가져오면 안될까 … 음성대화녹음파일들은 파일명 보고 자동으로 걸러", "내음악을 원음대로 재생만 돼 lp모드가
  없어"): a wide, short screen shows 턴테이블 · 앰프 · 곡목록 one at a time with no page scroll (a tall one keeps the long page, and
  folding switches it live); the PITCH fader bends a file's rate (its pitch with it) and a record's clock, platter and voices,
  the CUE lever lifts the arm and lowers it again, both drawn moving in the CSS and the 3D deck; + 휴대폰 노래 lists the songs
  the app found (asking for the permission first, one tap to the settings when refused) drawing only the rows in sight, and
  adds the chosen ones; 내 음악 plays through the LP chain or 원음, switched in its sheet.
  The three.js deck (deck3d.js; 준: "디테일한 three js로 해줘", "플스4느낌", and "Three js로 가면 휴대폰 성능저하심하려나"): it draws in
  place of the CSS deck, the record turns while playing and stops when paused, the arm is where the piece's arm is, it draws
  nothing while nothing moves and at most 30 frames a second, and with WebGL off the piece's own deck is there. Chromium draws
  WebGL here in software (SwiftShader): the frame time printed is this computer's, the draw calls and triangles the phone's.
  Run: node vinyl.test.js   (VINYL_SHOTS=<dir> also saves preview_list_closed.png, preview_list_open.png, scan_sheet.png,
  tabs_deck|amp|list.png, tabs_deck_3d.png and tall.png;
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
const FILES = { 'a1b2c3d4e5f6': wav(6, 'pink', 7), '0f1e2d3c4b5a': wav(5, 'tone', 9), '99aa88bb77cc': wav(4, 'pink', 11), '5050aaaa0001': wav(40, 'pink', 13) };
/* The phone's songs as the app lists them (PieceExtras /music/scan: recordings and short sounds already left out, 7 of them,
   sorted by artist): 300, one of them (A Quiet Tone) on the list already. */
const SONGS = [...Array(300)].map((_, i) => ({ id: i === 1 ? '5050aaaa0001' : i === 5 ? '0f1e2d3c4b5a' : 'c0ffee' + i.toString(16).padStart(6, '0'),
  title: i === 1 ? '좋은 날' : i === 5 ? 'A Quiet Tone' : '노래 ' + String(i).padStart(3, '0'), artist: i < 4 ? '아이유' : '가수 ' + Math.floor(i / 10), dur: 180 + i, on: false }));
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
  const ctx = await browser.newContext({ viewport: o.viewport || { width: 412, height: 900 }, deviceScaleFactor: o.dsf || 2, hasTouch: true, isMobile: true });
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
    if (p === '/__gyeot/music/scan') {
      // 휴대폰 노래 (PieceExtras): without the permission, ask=1 asks (Android's question: answered after a moment), ask=0 says how it went
      if (shelf.locked) return json(locked);
      const sc = shelf.scan || { perm: 'granted', items: [], hidden: 0 }, ask = u.searchParams.get('ask') !== '0';
      (calls.scan = calls.scan || []).push(ask ? 'ask' : 'again');
      if (sc.perm !== 'granted') {
        if (ask) { sc.perm = 'asking'; setTimeout(() => { sc.perm = sc.answer || 'denied'; }, 600); }
        return json({ perm: sc.perm });
      }
      return json({ perm: 'granted', items: sc.items.map(it => Object.assign({}, it, { on: shelf.items.some(t => t.id === it.id) })), hidden: sc.hidden });
    }
    if (p === '/__gyeot/music/add-media') {
      const ids = (u.searchParams.get('ids') || '').split(',').filter(x => /^[0-9a-f]{12}$/.test(x)), sc = shelf.scan || { items: [] };
      (calls.addMedia = calls.addMedia || []).push(ids); shelf.pick = 'open';
      setTimeout(() => {
        for (const id of ids) { const it = sc.items.find(x => x.id === id); if (it && !shelf.items.some(t => t.id === id)) shelf.items.push({ id, title: it.title, artist: it.artist, dur: it.dur, mime: 'audio/wav', size: 1000, seek: true, art: false }); }
        shelf.rev++; shelf.pick = 'done';
      }, 600);
      return json({ pending: true, rev: shelf.rev, n: ids.length, via: 'app' });
    }
    if (p === '/__gyeot/music/settings') { calls.settings = (calls.settings || 0) + 1; return json({ via: 'app' }); }
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
/** A drag on a control of the deck (in the frame), by dx, dy CSS pixels, with the mouse (pointer events). */
async function drag(page, frame, sel, dx, dy, steps = 8) {
  const b = await frame.locator(sel).boundingBox(), x = b.x + b.width / 2, y = b.y + b.height / 2;
  await page.mouse.move(x, y); await page.mouse.down();
  for (let i = 1; i <= steps; i++) await page.mouse.move(x + dx * i / steps, y + dy * i / steps);
  await page.mouse.up();
}
async function doubleTap(page, frame, sel) {
  const b = await frame.locator(sel).boundingBox(), x = b.x + b.width / 2, y = b.y + b.height / 2;
  await page.mouse.click(x, y); await page.waitForTimeout(90); await page.mouse.click(x, y);
}
/** The master's level now, in dB (as the VU meters hear it). */
const level = f => f.evaluate(async () => { const an = __lp.actx.createAnalyser(); an.fftSize = 2048; __lp.master.connect(an); const b = new Float32Array(2048); let e = 0;
  for (let i = 0; i < 20; i++) { an.getFloatTimeDomainData(b); let s = 0; for (const x of b) s += x * x; e += s / b.length; await new Promise(r => setTimeout(r, 50)); } an.disconnect(); return 10 * Math.log10(e / 20); });
const knobY = f => f.evaluate(() => +(/translate\(0 ([-\d.]+)\)/.exec(document.getElementById('gyPitchKnob').getAttribute('transform') || '') || [0, 0])[1]);
/* The hand on the deck, in its own units (1000 × 780; the frame fills the page, so its pixels are the page's). */
const deckAt = f => f.evaluate(() => { const r = document.getElementById('deck').getBoundingClientRect(); return { x: r.left, y: r.top, k: r.width / 1000 }; });
/** Where a point of a side is on the record: its radius by the piece's own map (album pos → R_OUT … R_IN). */
const radiusOf = (f, k, s) => f.evaluate(([k, s]) => { const A = __lp.ALBUMS[__lp.albumIdx]; return __lp.R_OUT - (__lp.R_OUT - __lp.R_IN) * (A.starts[k] + s) / A.total; }, [k, s]);
/** The tonearm picked up 470 units along it and swung round its pivot to angle (degrees), held (ms), then let go. */
async function armTo(page, f, angle, hold = 0) {
  const d = await deckAt(f), a0 = await f.evaluate(() => __lp.arm.angle);
  const P = a => ({ x: d.x + (850 + 470 * Math.cos(a * Math.PI / 180)) * d.k, y: d.y + (150 + 470 * Math.sin(a * Math.PI / 180)) * d.k });
  let p = P(a0); await page.mouse.move(p.x, p.y); await page.mouse.down();
  for (let i = 1; i <= 10; i++) { p = P(a0 + (angle - a0) * i / 10); await page.mouse.move(p.x, p.y); await page.waitForTimeout(30); }
  if (hold) await page.waitForTimeout(hold);
  return async () => page.mouse.up();
}
/** A finger on the record at radius 200 (left of the spindle), turned round it at deg/s for ms (0: held still), then lifted. */
async function hand(page, f, degPerS, ms) {
  const d = await deckAt(f), P = a => ({ x: d.x + (400 + 200 * Math.cos(a * Math.PI / 180)) * d.k, y: d.y + (390 + 200 * Math.sin(a * Math.PI / 180)) * d.k });
  let p = P(180); await page.mouse.move(p.x, p.y); await page.mouse.down();
  const t0 = Date.now();
  while (Date.now() - t0 < ms) { p = P(180 + degPerS * (Date.now() - t0) / 1000); await page.mouse.move(p.x, p.y); await page.waitForTimeout(degPerS ? 12 : 50); }
  return async () => page.mouse.up();
}
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
      const brandTop = await frame.evaluate(() => document.querySelector('.brand').getBoundingClientRect().top);
      assert(brandTop >= 24 + 4, 'the brand row below the status bar (24 px here): ' + brandTop);
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
      assert.deepStrictEqual(await frame.evaluate(() => [...document.querySelectorAll('.gy-add')].map(b => b.textContent + (b.disabled ? ' (off)' : ''))), ['+ 휴대폰 노래', '+ 파일 추가', '+ 폴더'], 'ready to add more');
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
    await check('+ 휴대폰 노래 refused: what is missing, a tap to the app\'s settings; allowed there, the songs come when the page is back', async () => {
      shelf.scan = { perm: 'ask', answer: 'denied', items: SONGS, hidden: 7 };
      await frame.locator('#gyBar .gy-list').tap();
      await frame.locator('.gy-chip', { hasText: '내 음악' }).tap();
      await frame.locator('.gy-add', { hasText: '+ 휴대폰 노래' }).tap();
      await until(frame, () => !!document.querySelector('#gyScan .gy-snote [data-do="settings"]'), null, 6000, 'the refusal said');
      const v = await frame.evaluate(() => ({ sheet: document.getElementById('gySheet').hidden, scan: document.getElementById('gyScan').hidden, note: document.querySelector('#gyScan .gy-snote').textContent,
        buttons: [...document.querySelectorAll('#gyScan .gy-snote [data-do]')].map(b => b.textContent) }));
      assert.deepStrictEqual([v.sheet, v.scan], [true, false], 'the checklist in the list\'s place');
      assert.match(v.note, /권한/); assert.match(v.note, /파일 추가는 그대로/);
      assert.deepStrictEqual(v.buttons, ['허용하기', '설정 열기']);
      assert.deepStrictEqual(calls.scan.slice(0, 2), ['ask', 'again'], 'asked once, then waited for the answer');
      await frame.locator('#gyScan [data-do="settings"]').tap();
      await until(frame, () => /설정/.test(document.querySelector('#gyScan .gy-snote').textContent) && !document.querySelector('#gyScan [data-do]'), null, 3000, 'the settings opened');
      assert.strictEqual(calls.settings, 1);
      // allowed in the settings, back on the page: the list comes without another tap
      shelf.scan.perm = 'granted';
      await frame.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
      await until(frame, () => document.querySelectorAll('#gyScan .gy-srow').length > 0, null, 5000, 'the songs once allowed');
    });
    await check('휴대폰 노래: the songs the app found in a checklist (only the rows in sight drawn), 모두 선택, three chosen onto 내 음악', async () => {
      const v = await frame.evaluate(() => { const s = document.getElementById('gyScan').getBoundingClientRect();
        return { title: document.querySelector('.gy-stitle').textContent, rows: document.querySelectorAll('.gy-srow').length, pad: document.querySelector('.gy-spad').style.height,
          first: [...document.querySelectorAll('.gy-srow')].slice(0, 3).map(r => r.querySelector('b').textContent + ' · ' + r.querySelector('small').textContent + ' · ' + r.querySelector('.gy-dur').textContent),
          on: [...document.querySelectorAll('.gy-srow[aria-disabled="true"]')].map(r => r.querySelector('b').textContent + ' ' + r.querySelector('.gy-dur').textContent),
          sum: document.querySelector('.gy-ssum').textContent, take: document.querySelector('.gy-take').disabled, top: s.top, bottom: s.bottom, vh: innerHeight, y: scrollY }; });
      assert.strictEqual(v.title, '휴대폰 노래 300곡');
      assert.deepStrictEqual(v.first, ['노래 000 · 아이유 · 3:00', '좋은 날 · 아이유 · 3:01', '노래 002 · 아이유 · 3:02'], 'title · artist · length, in the app\'s order');
      assert(v.rows > 4 && v.rows <= 24, 'only the rows in sight on the page: ' + v.rows + ' of 300');
      assert.strictEqual(v.pad, 300 * 40 + 'px');
      assert.deepStrictEqual(v.on, ['A Quiet Tone 담김'], 'a song on the list already: shown, not to choose');
      assert.match(v.sum, /녹음·짧은 소리 7개는 뺐어요/);
      assert.strictEqual(v.take, true, '담기 waits for a choice');
      assert(v.top >= 0 && v.bottom <= v.vh - 24 - 26 - 38 && v.y === 0, 'over the bar, on screen: ' + v.top + '…' + v.bottom);
      await frame.evaluate(() => { const l = document.querySelector('.gy-slist'); l.scrollTop = l.scrollHeight; });
      await until(frame, () => [...document.querySelectorAll('.gy-srow b')].some(b => b.textContent === '노래 299'), null, 2000, 'the last song drawn when scrolled to');
      assert(await frame.evaluate(() => document.querySelectorAll('.gy-srow').length) <= 24, 'still only the rows in sight');
      await frame.evaluate(() => { document.querySelector('.gy-slist').scrollTop = 0; });
      await frame.locator('.gy-all').tap();
      assert.deepStrictEqual(await frame.evaluate(() => [document.querySelector('.gy-all').textContent, document.querySelector('.gy-take').textContent]), ['모두 해제', '299곡 담기'], 'every song not on the list');
      await frame.locator('.gy-all').tap();
      assert.deepStrictEqual(await frame.evaluate(() => [document.querySelector('.gy-all').textContent, document.querySelector('.gy-take').disabled]), ['모두 선택', true]);
      for (const t of ['좋은 날', '노래 002', '노래 003']) await frame.locator('.gy-srow', { hasText: t }).tap();
      await frame.locator('.gy-srow', { hasText: 'A Quiet Tone' }).tap({ force: true });   // (on the list already: nothing)
      assert.strictEqual(await frame.locator('.gy-take').textContent(), '3곡 담기');
      if (SHOTS) await page.screenshot({ path: path.join(SHOTS, 'scan_sheet.png') });
      await frame.locator('.gy-take').tap();
      await until(frame, () => !document.getElementById('gySheet').hidden && document.querySelectorAll('#gySheet .gy-row').length === 4, null, 8000, 'the three on 내 음악');
      assert.deepStrictEqual(calls.addMedia, [['5050aaaa0001', 'c0ffee000002', 'c0ffee000003']], 'their ids only (the app looks each up again)');
      assert.match(await frame.locator('.gy-note').textContent(), /3곡을 담았어요/);
      assert.deepStrictEqual(await frame.evaluate(() => [...document.querySelectorAll('#gySheet .gy-row b')].map(b => b.textContent)), ['A Quiet Tone', '좋은 날', '노래 002', '노래 003']);
    });
    await check('내 음악 plays as an LP by default (wow delay, warm filters, saturation, narrower stereo); 원음 goes straight to the master; switched while playing, kept', async () => {
      await frame.locator('#gySheet .gy-row', { hasText: '좋은 날' }).tap();
      await until(frame, () => __lp.playing && !__lp.busy && !__gy.audio.paused && /5050aaaa0001/.test(__gy.audio.src) && __gy.audio.currentTime > 0.3, null, 15000, '좋은 날 playing');
      const lp = await frame.evaluate(() => ({ sound: __gy.sound, chain: __gy.chain(), liner: document.getElementById('liner').textContent, on: document.querySelector('.gy-sound [aria-checked="true"]') && document.querySelector('.gy-sound [aria-checked="true"]').textContent }));
      assert.strictEqual(lp.sound, 'lp');
      assert.deepStrictEqual(lp.chain, ['MediaElementAudioSourceNode', 'GainNode', 'GainNode', 'DelayNode', 'BiquadFilterNode', 'BiquadFilterNode', 'WaveShaperNode', 'ChannelSplitterNode', 'ChannelMergerNode', 'GainNode']);
      assert.match(lp.liner, /LP로 들어요/);
      const dbLp = await level(frame);
      await frame.locator('#gySheet .gy-sound [data-s="clean"]').tap();
      await until(frame, () => __gy.sound === 'clean' && __gy.chain().length === 2, null, 2000, '원음');
      const clean = await frame.evaluate(() => ({ chain: __gy.chain(), kept: localStorage.getItem('gy.lp.sound'), liner: document.getElementById('liner').textContent, playing: !__gy.audio.paused }));
      assert.deepStrictEqual(clean.chain, ['MediaElementAudioSourceNode', 'GainNode'], 'the file as it is');
      assert.strictEqual(clean.kept, 'clean'); assert.match(clean.liner, /원곡 그대로/); assert.strictEqual(clean.playing, true, 'it plays on');
      const dbClean = await level(frame);
      console.log('     the master with 내 음악: LP ' + dbLp.toFixed(1) + ' dB, 원음 ' + dbClean.toFixed(1) + ' dB');
      assert(dbLp > -45 && dbClean > -45 && Math.abs(dbLp - dbClean) < 3, 'sound in both, about as loud: ' + dbLp.toFixed(1) + ' / ' + dbClean.toFixed(1));
      await frame.locator('#gySheet .gy-sound [data-s="lp"]').tap();
      await until(frame, () => __gy.sound === 'lp' && __gy.chain().includes('WaveShaperNode'), null, 2000, 'LP again');
      await frame.locator('#gyBar .gy-list').tap();
      assert.strictEqual(await frame.evaluate(() => document.getElementById('gySheet').hidden), true);
    });
    await check('PITCH: dragged up, a file plays faster and higher (its rate, its pitch not kept) and the knob moves; a double tap back to 0', async () => {
      const k = await frame.evaluate(() => 780 / document.getElementById('deck').getBoundingClientRect().height);  // deck units per px
      await drag(page, frame, '.gy-pitch', 0, -42 / k);                       // half the travel: +4 %
      await until(frame, () => __gy.pitch > 3, null, 2000, 'the fader moved');
      const p = await frame.evaluate(() => ({ pitch: __gy.pitch, rate: __gy.audio.playbackRate, keep: __gy.audio.preservesPitch, speed: __lp.speed, aria: document.querySelector('.gy-pitch').getAttribute('aria-valuenow') }));
      assert(p.pitch >= 3.5 && p.pitch <= 4.5, 'about +4 %: ' + p.pitch);
      assert(Math.abs(p.rate - (1 + p.pitch / 100)) < 1e-6, 'the file\'s rate: ' + p.rate);
      assert.strictEqual(p.keep, false, 'its pitch bends with it (not a time stretch)');
      assert(Math.abs(p.speed - p.rate) < 1e-9, 'the piece\'s speed too (the arm, the platter): ' + p.speed);
      assert.strictEqual(p.aria, String(p.pitch));
      assert(Math.abs(await knobY(frame) + p.pitch / 8 * 84) < 0.05, 'the knob up by its share of the travel');
      const t0 = await frame.evaluate(() => ({ a: __gy.audio.currentTime, w: performance.now() }));
      await frame.waitForTimeout(1500);
      const t1 = await frame.evaluate(() => ({ a: __gy.audio.currentTime, w: performance.now() }));
      console.log('     at +' + p.pitch + ' %: the file ran ' + ((t1.a - t0.a) / ((t1.w - t0.w) / 1000)).toFixed(3) + '× real time');
      await doubleTap(page, frame, '.gy-pitch');
      await until(frame, () => __gy.pitch === 0, null, 2000, 'back to 0');
      assert.deepStrictEqual(await frame.evaluate(() => [__gy.audio.playbackRate, __lp.speed]), [1, 1]);
      assert.strictEqual(await knobY(frame), 0);
    });
    // 준, 2026-11-27: "볼륨 돌리는게 조작이 이상해": it went by how far the finger moved up and right, so going round it clockwise on
    // its right half turned it down. Now it turns with the finger going round its middle: clockwise up, the pointer under the finger.
    await check('the volume knob turns as the finger goes round it: clockwise up (the right half too), back the other way down; a wobble in the middle does nothing', async () => {
      await frame.locator('#knob').scrollIntoViewIfNeeded();
      await frame.focus('#knob'); await page.keyboard.press('Home');
      const turn = async (from, to, r = 0.4) => {
        const b = await frame.locator('#knob').boundingBox(), cx = b.x + b.width / 2, cy = b.y + b.height / 2, R = b.width * r;
        const at = deg => [cx + R * Math.cos(deg * Math.PI / 180), cy + R * Math.sin(deg * Math.PI / 180)];
        await page.mouse.move(...at(from)); await page.mouse.down();
        for (let i = 1; i <= 18; i++) await page.mouse.move(...at(from + (to - from) * i / 18));
        await page.mouse.up();
        return frame.evaluate(() => +document.getElementById('knob').getAttribute('aria-valuenow'));
      };
      const up = await turn(-90, 90);                          // the top, round the right, to the bottom: clockwise half a turn
      assert(up >= 64 && up <= 69, 'clockwise on the right half turns it up by 180/270: ' + up);
      const ptr = await frame.evaluate(() => getComputedStyle(document.getElementById('pointer')).transform);
      assert(ptr && ptr !== 'none', 'the pointer turned');
      const back = await turn(270, 180);                       // the top, round the left, a quarter back: down by 90/270
      assert(Math.abs(back - (up - 33)) <= 2, 'counterclockwise turns it down: ' + up + ' → ' + back);
      const still = await turn(0, 180, 0.08);                  // round the very middle (where the angle jumps): nothing
      assert.strictEqual(still, back, 'a wobble in the middle');
    });
    await check('CUE: a tap lifts the arm off the playing record (the sound stops, the platter turns on, the lever up); a tap lowers it and it plays on from there; start · stop under it stops the motor', async () => {
      await until(frame, () => __lp.playing && !__lp.busy && !__gy.audio.paused, null, 8000, 'playing');
      await frame.locator('.gy-cue').tap();
      await until(frame, () => !__lp.playing && !__lp.busy && __lp.arm.lift > 0.99 && document.querySelector('.gy-cue').getAttribute('aria-pressed') === 'true', null, 5000, 'the arm lifted (said on the lever)');
      const up = await frame.evaluate(() => ({ paused: __gy.audio.paused, motor: __lp.motorOn, omega: __lp.omega, lever: document.getElementById('cueLever').getAttribute('transform'), pressed: document.querySelector('.gy-cue').getAttribute('aria-pressed'), pos: __lp.songPos }));
      assert.deepStrictEqual([up.paused, up.motor, up.lever, up.pressed], [true, true, 'rotate(-38.0 0 4)', 'true'], 'paused, the motor on, the CSS deck\'s lever up');
      await frame.waitForTimeout(700);
      const still = await frame.evaluate(() => ({ pos: __lp.songPos, omega: __lp.omega }));
      assert.strictEqual(still.pos, up.pos, 'the clock waits');
      assert(still.omega > 150, 'the platter keeps turning: ' + still.omega);
      await frame.locator('.gy-cue').tap();
      await until(frame, () => __lp.playing && !__lp.busy && !__gy.audio.paused && __lp.arm.lift === 0, null, 6000, 'the arm down, playing');
      const down = await frame.evaluate(() => ({ t: __gy.audio.currentTime, lever: document.getElementById('cueLever').getAttribute('transform') }));
      assert(Math.abs(down.t - up.pos) < 1.5, 'on from where it was lifted: ' + up.pos.toFixed(2) + ' → ' + down.t.toFixed(2));
      assert(Math.abs(+(/rotate\(([-\d.]+) 0 4\)/.exec(down.lever) || [0, 99])[1]) < 0.3, 'the lever down: ' + down.lever);
      await frame.locator('.gy-cue').tap();
      await until(frame, () => !__lp.playing && !__lp.busy && __lp.arm.lift > 0.99, null, 5000, 'lifted again');
      await frame.locator('#deckBtn').tap();
      await until(frame, () => !__lp.motorOn, null, 2000, 'the motor stopped');
      assert.strictEqual(await frame.evaluate(() => __lp.playing), false, 'and nothing plays');
      await frame.locator('#gyBar .gy-play').tap();
      await until(frame, () => __lp.playing && !__lp.busy && !__gy.audio.paused, null, 8000, 'playing again from the bar');
    });
    await check('no script error all along', async () => assert.deepStrictEqual(errors, []));
  } finally { await ctx.close(); }
  // The unfolded Fold (a screen wide for its height): one part at a time, no page scroll; folded, the long page again.
  {
    const shelfT = { rev: 2, items: ITEMS.slice(), pick: '' }, callsT = { list: 0, pick: [], media: [], del: [] };
    const t = await open(browser, html, shelfT, callsT, { off: true }, { viewport: { width: 780, height: 820 } });
    const parts = { deck: '#deck', amp: '#amp', list: '.panel' };
    const fit = k => t.frame.evaluate(([k, parts]) => {
      const r = s => document.querySelector(s).getBoundingClientRect();
      return { tab: __gy.tab, sh: document.scrollingElement.scrollHeight, sw: document.scrollingElement.scrollWidth, ih: innerHeight, iw: innerWidth, y: scrollY,
        part: r(parts[k]).toJSON(), bar: r('#gyBar').top, brand: r('.brand').bottom, stage: r('.stage').toJSON(),
        others: Object.keys(parts).filter(x => x !== k).map(x => document.querySelector(parts[x]).getClientRects().length ? 'shown' : 'none'), shown: [...document.querySelectorAll('.gy-tabs [aria-selected="true"]')].map(b => b.textContent) };
    }, [k, parts]);
    try {
      await check('a wide, short screen (the unfolded Fold, 780×820): 턴테이블 · 앰프 · 곡목록 in the brand row; each part fits whole, clear of the bar, and nothing scrolls the page', async () => {
        await until(t.frame, () => !!window.__gy && document.querySelector('.sleeve[data-album="3"]'), null, 10000, 'the add-on');
        const g = await t.frame.evaluate(() => { const r = document.querySelector('.gy-tabs').getBoundingClientRect(), b = document.querySelector('.brand').getBoundingClientRect();
          return { tabbed: document.documentElement.classList.contains('gy-tabbed'), tab: __gy.tab, names: [...document.querySelectorAll('.gy-tabs button')].map(b => b.textContent),
            h: r.height, w: r.width, inRow: r.top >= b.top - 1 && r.bottom <= b.bottom + 1, right: b.right - r.right }; });
        assert.deepStrictEqual([g.tabbed, g.tab, g.names], [true, 'deck', ['턴테이블', '앰프', '곡목록']]);
        assert(g.h <= 36 && g.w <= 260, 'small pills: ' + g.w + '×' + g.h);
        assert(g.inRow && g.right <= 1, 'at the end of the brand row');
        // 준, 2026-11-27: "시계 상태표시줄이 가려져": the brand row and the tabs below the status bar the home screen tells (24 px here).
        const top = await t.frame.evaluate(() => Math.min(document.querySelector('.brand').getBoundingClientRect().top, document.querySelector('.gy-tabs').getBoundingClientRect().top));
        assert(top >= 24 + 4, 'clear of the status bar: ' + top);
        for (const k of ['deck', 'amp', 'list']) {
          await t.frame.locator('.gy-tabs [data-tab="' + k + '"]').tap();
          const m = await fit(k);
          assert.strictEqual(m.tab, k);
          assert(m.sh <= m.ih && m.sw <= m.iw && m.y === 0, k + ': the page does not scroll (' + m.sh + ' of ' + m.ih + ')');
          assert(m.part.height > 100 && m.part.top >= m.brand && m.part.bottom <= m.bar - 4, k + ': whole, between the brand row and the bar: ' + m.part.top + '…' + m.part.bottom + ' (bar ' + m.bar + ')');
          assert.deepStrictEqual(m.others, ['none', 'none'], k + ': the other parts hidden');
          if (SHOTS) await t.page.screenshot({ path: path.join(SHOTS, 'tabs_' + k + '.png') });
          if (k === 'deck') assert(Math.abs(m.part.width - Math.min(m.stage.width, m.stage.height * 1000 / 780)) < 2, 'the deck as large as fits: ' + m.part.width + ' in ' + m.stage.width + '×' + m.stage.height);
          if (k === 'list') {
            const l = await t.frame.evaluate(() => { const box = document.querySelector('.panel').getBoundingClientRect(), r = s => document.querySelector(s).getBoundingClientRect();
              return { tracks: getComputedStyle(document.getElementById('tracks')).overflowY, play: r('#bPlay').bottom <= box.bottom, crate: r('.sleeves').bottom <= box.bottom + 1, sleeves: document.querySelectorAll('.sleeve').length }; });
            assert.deepStrictEqual(l, { tracks: 'auto', play: true, crate: true, sleeves: 4 }, 'the tracks scroll in their own box; the transport and the crate in sight');
          }
        }
      });
      await check('the last tab is remembered: the page opened again shows it', async () => {
        await t.frame.locator('.gy-tabs [data-tab="amp"]').tap();
        await t.frame.evaluate(() => { window._before = 1; setTimeout(() => location.reload(), 50); });
        await until(t.frame, () => !window._before && !!window.__gy && __gy.tab === 'amp', null, 10000, 'the 앰프 tab again, on the page opened again');
        assert.strictEqual(await t.frame.evaluate(() => getComputedStyle(document.getElementById('deck')).display), 'none');
      });
      await check('folded (412×900, the cover screen): the piece\'s long page again, no tabs; unfolded, the tabs come back', async () => {
        await t.page.setViewportSize({ width: 412, height: 900 });
        await until(t.frame, () => !document.documentElement.classList.contains('gy-tabbed'), null, 3000, 'the long page');
        const m = await t.frame.evaluate(() => ({ tabs: getComputedStyle(document.querySelector('.gy-tabs')).display, parts: ['#deck', '#amp', '.panel'].map(s => getComputedStyle(document.querySelector(s)).display), sh: document.scrollingElement.scrollHeight, ih: innerHeight, tab: __gy.tab }));
        assert.strictEqual(m.tabs, 'none'); assert(!m.parts.includes('none'), 'every part on the page: ' + m.parts);
        assert(m.sh > m.ih * 1.5, 'the long page (scrolls as before): ' + m.sh); assert.strictEqual(m.tab, null);
        if (SHOTS) await t.page.screenshot({ path: path.join(SHOTS, 'tall.png') });
        await t.page.setViewportSize({ width: 780, height: 820 });
        await until(t.frame, () => document.documentElement.classList.contains('gy-tabbed') && __gy.tab === 'amp', null, 3000, 'the tabs again');
      });
      await check('PITCH on a record (the synthesizer): its clock, its platter and its voices at the fader\'s speed', async () => {
        await t.frame.locator('.gy-tabs [data-tab="deck"]').tap();
        await t.frame.locator('#gyBar .gy-play').tap();
        await until(t.frame, () => __lp.playing && !__lp.busy && !!__lp.session && __lp.omega > 190, null, 15000, 'the record playing, up to speed');
        const k = await t.frame.evaluate(() => 780 / document.getElementById('deck').getBoundingClientRect().height);
        await drag(t.page, t.frame, '.gy-pitch', 0, -100 / k);                  // past the top: +8 %
        await until(t.frame, () => __gy.pitch === 8 && __lp.omega > 214, null, 6000, '+8 %, the platter faster');
        const a = await t.frame.evaluate(() => ({ s: __lp.songPos, t: __lp.actx.currentTime, speed: __lp.speed }));
        await t.frame.waitForTimeout(2000);
        const b = await t.frame.evaluate(() => ({ s: __lp.songPos, t: __lp.actx.currentTime }));
        const run = (b.s - a.s) / (b.t - a.t);
        console.log('     at +8 %: the record\'s clock ran ' + run.toFixed(3) + '× the sound\'s; the platter ' + (await t.frame.evaluate(() => __lp.omega)).toFixed(1) + '°/s');
        assert.strictEqual(a.speed, 1.08);
        assert(Math.abs(run - 1.08) < 0.025, 'the song\'s time at 1.08: ' + run);
        assert(Math.abs(await knobY(t.frame) + 84) < 0.05, 'the knob at the top mark');
        await t.frame.locator('.gy-pitch').focus(); await t.frame.press('.gy-pitch', 'Home');
        await until(t.frame, () => __gy.pitch === 0 && __lp.speed === 1, null, 2000, 'back to 0 (Home)');
        await t.frame.locator('#gyBar .gy-play').tap();
        await until(t.frame, () => !__lp.playing && !__lp.busy, null, 6000, 'paused');
        assert.deepStrictEqual(t.errors, []);
      });
    } finally { await t.ctx.close(); }
  }
  // The hand on the deck (준: "톤암(바늘)을 직접 드래그해 LP 판 원하는 위치에 올리면 해당 구간의 음악이 재생되게", "LP 판을 손가락으로 터치해
  // 멈추거나(브레이크), 돌릴 때 속도에 맞춰 음정과 속도가 변하는 물리 효과", "바늘이 판에 닿는 순간 미세한 '치직'", "빛 반사(글레어)가 도는
  // 바이닐 질감과 부드러운 톤암 리프트"), on the unfolded Fold's deck tab, the CSS deck (the 3D one after).
  {
    const MINE2 = [{ id: '5050aaaa0001', title: '좋은 날', artist: '아이유', dur: 40, mime: 'audio/wav', size: FILES['5050aaaa0001'].length, seek: true, art: false },
      { id: '0f1e2d3c4b5a', title: 'A Quiet Tone', artist: '', dur: 5, mime: 'audio/wav', size: FILES['0f1e2d3c4b5a'].length, seek: true, art: false }];
    const shelfH = { rev: 2, items: MINE2.slice(), pick: '' }, callsH = { list: 0, pick: [], media: [], del: [] };
    const h = await open(browser, html, shelfH, callsH, { off: true }, { viewport: { width: 780, height: 820 } });
    const f = h.frame, page = h.page;
    try {
      await until(f, () => !!window.__gy && document.querySelector('.sleeve[data-album="3"]') && __gy.tab === 'deck', null, 10000, 'the add-on, the deck tab');
      await check('the gaps between the tracks are lighter lands on the record, where each track starts (the piece\'s dark bands gone); the glare stays still while the record turns', async () => {
        const want = await f.evaluate(() => { const A = __lp.ALBUMS[0]; return A.starts.slice(1).map(s => (__lp.R_OUT - (__lp.R_OUT - __lp.R_IN) * (s - __lp.GAP / 2) / A.total).toFixed(1)).join(','); });
        const l = await f.evaluate(() => ({ r: __gy.lands(), g1: getComputedStyle(document.getElementById('record')).getPropertyValue('--g1').trim(), bg: document.querySelector('#record .gy-lands').style.background }));
        assert.strictEqual(l.r, want, 'a land at each track\'s start');
        assert.strictEqual(l.g1, '-9%', 'the piece\'s dark bands hidden');
        assert.match(l.bg, /rgba\(236, 240, 248, 0\.22\)/, 'lighter than the vinyl');
        await f.locator('#gyBar .gy-play').tap();
        await until(f, () => __lp.playing && !__lp.busy && __lp.omega > 190, null, 15000, 'the record playing');
        const g0 = await f.evaluate(() => ({ spin: __lp.spin, rec: getComputedStyle(document.getElementById('record')).transform, glare: getComputedStyle(document.getElementById('specular')).transform, o: getComputedStyle(document.getElementById('specular')).opacity }));
        await f.waitForTimeout(400);
        const g1 = await f.evaluate(() => ({ spin: __lp.spin, rec: getComputedStyle(document.getElementById('record')).transform, glare: getComputedStyle(document.getElementById('specular')).transform }));
        assert.notStrictEqual(g1.rec, g0.rec, 'the record turned');
        assert.deepStrictEqual([g0.glare, g1.glare, g0.o], ['none', 'none', '1'], 'the glare over it stays where the light is');
      });
      await check('the arm lifts and lowers in half a second, eased (CUE), and each time the stylus lands a soft crackle, well under the music', async () => {
        const music = await level(f);
        await f.evaluate(() => { window._lift = []; window._un = __gy.onFrame(() => _lift.push([performance.now(), __lp.arm.lift])); });
        await f.locator('.gy-cue').tap();
        await until(f, () => !__lp.playing && !__lp.busy && __lp.arm.lift === 1, null, 5000, 'cued up');
        const up = await f.evaluate(() => { _un(); return _lift; });
        const t0 = up.find(x => x[1] > 0)[0], t1 = up.find(x => x[1] >= 1)[0], dur = (t1 - t0) / 1000, at = u => { const t = t0 + u * (t1 - t0); return up.find(x => x[0] >= t)[1]; };
        console.log('     the arm up in ' + dur.toFixed(2) + ' s: at a fifth ' + at(0.2).toFixed(2) + ', halfway ' + at(0.5).toFixed(2) + ', four fifths ' + at(0.8).toFixed(2));
        assert(dur >= 0.4 && dur <= 0.62, 'about half a second: ' + dur);
        assert(at(0.2) < 0.12 && Math.abs(at(0.5) - 0.5) < 0.2 && at(0.8) > 0.88, 'eased in and out');
        assert(up.every((x, i) => i === 0 || x[1] >= up[i - 1][1]), 'only up');
        const n0 = await f.evaluate(() => __gy.drops);
        // the landing's own sound (its node, alone) against the music at the master
        await f.evaluate(() => { window._peak = -200; window._an = null; window._iv = setInterval(() => { const n = __gy.dropNode; if (!n) return; if (!_an) { _an = n.context.createAnalyser(); _an.fftSize = 1024; n.connect(_an); } const b = new Float32Array(1024); _an.getFloatTimeDomainData(b); let s = 0; for (const x of b) s += x * x; _peak = Math.max(_peak, 10 * Math.log10(s / 1024 + 1e-12)); }, 10); });
        await f.locator('.gy-cue').tap();
        await until(f, () => __lp.playing && !__lp.busy && __lp.arm.lift === 0, null, 5000, 'down again, playing');
        await f.waitForTimeout(500);
        const c = await f.evaluate(() => { clearInterval(_iv); return { drops: __gy.drops, peak: _peak }; });
        console.log('     the landing\'s crackle at its loudest ' + c.peak.toFixed(1) + ' dB, the music ' + music.toFixed(1) + ' dB');
        assert.strictEqual(c.drops, n0 + 1, 'one landing, one crackle');
        assert(c.peak > music - 40 && c.peak < music - 10, 'heard, but well under the music: ' + c.peak.toFixed(1) + ' vs ' + music.toFixed(1));
      });
      await check('the arm picked up by hand rises and follows the finger; set down over a track it plays that track from that point (the piece\'s groove map), off the record it rests', async () => {
        const n0 = await f.evaluate(() => __gy.drops);
        const r = await radiusOf(f, 1, 30), a = await f.evaluate(r => __lp.angleForRadius(r), r);
        const go = await armTo(page, f, a, 600);
        const held = await f.evaluate(() => ({ lift: __lp.arm.lift, a: __lp.arm.angle, playing: __lp.playing, motor: __lp.motorOn, bar: document.querySelector('#gyBar .gy-t').textContent }));
        assert.strictEqual(held.lift, 1, 'up in the hand');
        assert(Math.abs(held.a - a) < 0.3, 'where the finger took it: ' + held.a + ' for ' + a);
        assert.deepStrictEqual([held.playing, held.motor], [false, true], 'the sound stopped, the platter turning');
        assert.strictEqual(held.bar, 'ii. Moss Keeps Time · 0:30', 'the bar says where it would play');
        if (SHOTS) await page.screenshot({ path: path.join(SHOTS, 'arm_held.png') });
        await go();
        await until(f, () => __lp.playing && !__lp.busy && __lp.arm.lift === 0, null, 8000, 'down there, playing');
        const p = await f.evaluate(() => ({ k: __lp.trackIdx, s: __lp.songPos, drops: __gy.drops }));
        assert.strictEqual(p.k, 1, 'track ii');
        assert(p.s >= 29.8 && p.s < 31.5, 'from 0:30: ' + p.s.toFixed(2));
        assert.strictEqual(p.drops, n0 + 1, 'it landed with its crackle');
        // in a gap between the tracks: the next track's start
        const gap = await f.evaluate(() => { const A = __lp.ALBUMS[0]; return __lp.R_OUT - (__lp.R_OUT - __lp.R_IN) * (A.starts[2] - __lp.GAP / 2) / A.total; });
        await (await armTo(page, f, await f.evaluate(r => __lp.angleForRadius(r), gap)))();
        await until(f, () => __lp.playing && !__lp.busy, null, 8000, 'playing from the gap');
        const q = await f.evaluate(() => ({ k: __lp.trackIdx, s: __lp.songPos }));
        assert(q.k === 2 && q.s < 1.5, 'iii. from its start: ' + JSON.stringify(q));
        // off the record: to its rest, the motor off
        await (await armTo(page, f, 93))();
        await until(f, () => !__lp.busy && !__lp.playing && !__lp.tweening, null, 8000, 'at rest');
        const rest = await f.evaluate(() => ({ a: __lp.arm.angle, lift: __lp.arm.lift, motor: __lp.motorOn, drops: __gy.drops }));
        assert.deepStrictEqual(rest, { a: 90, lift: 0, motor: false, drops: n0 + 2 }, 'on its rest (no crackle there)');
      });
      await check('a finger held on the record stops it in a fraction of a second, the music slowing, dropping and fading with it (not cut); let go, the motor brings it and the music back up', async () => {
        await f.locator('#gyBar .gy-play').tap();
        await until(f, () => __lp.playing && !__lp.busy && __lp.omega > 195, null, 15000, 'playing at speed');
        const s0 = await f.evaluate(() => ({ s: __lp.songPos, t: __lp.actx.currentTime }));
        const lift = await hand(page, f, 0, 450);
        const b = await f.evaluate(() => ({ omega: __lp.omega, speed: __lp.speed, bus: __lp.musicBus.gain.value, s: __lp.songPos, t: __lp.actx.currentTime, playing: __lp.playing }));
        console.log('     held 0.45 s: the platter ' + b.omega.toFixed(1) + '°/s, the song\'s clock ' + ((b.s - s0.s) / (b.t - s0.t)).toFixed(2) + '× (from 1), the music ×' + (b.bus / 0.85).toFixed(3));
        assert(b.omega < 5, 'stopped: ' + b.omega);
        assert(b.speed <= 0.05 && b.bus < 0.05, 'the sound slowed and quiet: ' + b.speed + ', ' + b.bus);
        assert(b.s - s0.s > 0.02 && b.s - s0.s < 0.35, 'the clock slowed (not cut): ' + (b.s - s0.s).toFixed(2) + ' s of song in ' + (b.t - s0.t).toFixed(2) + ' s');
        assert.strictEqual(b.playing, true, 'still on the record');
        await lift();
        await f.waitForTimeout(500);
        const m = await f.evaluate(() => ({ omega: __lp.omega, speed: __lp.speed, bus: __lp.musicBus.gain.value, coupled: __gy.coupled }));
        assert(m.coupled && m.omega > 60 && m.omega < 195 && Math.abs(m.speed - m.omega / 200) < 0.02, 'spinning up, the sound with it: ' + JSON.stringify(m));
        await until(f, () => !__gy.coupled, null, 6000, 'back at speed');
        const e = await f.evaluate(() => ({ omega: __lp.omega, speed: __lp.speed, bus: __lp.musicBus.gain.value, playing: __lp.playing, hand: __lp.hand }));
        assert(Math.abs(e.omega - 200) < 1.5 && e.speed === 1 && Math.abs(e.bus - 0.85) < 0.01 && e.playing && !e.hand, 'as before (and the hold was no tap: still playing): ' + JSON.stringify(e));
      });
      await check('내 음악 is one side: the arm set down on a file plays that file from that point; turned by hand it plays at the finger\'s speed (rate and pitch), backwards or still it is quiet', async () => {
        await f.locator('#gyBar .gy-play').tap();
        await until(f, () => !__lp.playing && !__lp.busy, null, 6000, 'paused');
        await f.evaluate(() => __lp.changeAlbum(3));
        await until(f, () => __lp.albumIdx === 3 && !__lp.busy, null, 15000, '내 음악 on the platter');
        assert.strictEqual(await f.evaluate(() => __gy.lands()), await f.evaluate(() => { const A = __lp.ALBUMS[3]; return (__lp.R_OUT - (__lp.R_OUT - __lp.R_IN) * (A.starts[1] - __lp.GAP / 2) / A.total).toFixed(1); }), 'a land between the two files');
        await (await armTo(page, f, await f.evaluate(r => __lp.angleForRadius(r), await radiusOf(f, 0, 20))))();
        await until(f, () => __lp.playing && !__lp.busy && !__gy.audio.paused && /5050aaaa0001/.test(__gy.audio.src), null, 15000, '좋은 날 playing');
        const t = await f.evaluate(() => __gy.audio.currentTime);
        assert(t >= 19.5 && t < 22, 'from 0:20: ' + t.toFixed(2));
        const lift = await hand(page, f, 360, 900);
        const s = await f.evaluate(() => ({ omega: __lp.omega, rate: __gy.audio.playbackRate, keep: __gy.audio.preservesPitch, w: __gy.hand && __gy.hand.w }));
        console.log('     turned at ' + (s.w || 0).toFixed(0) + '°/s: the platter ' + s.omega.toFixed(0) + '°/s, the file at ' + s.rate.toFixed(2) + '×');
        assert(s.omega > 270 && s.omega < 450, 'the record at the finger\'s speed: ' + s.omega);
        assert(Math.abs(s.rate - s.omega / 200) < 0.06 && s.keep === false, 'the file\'s rate (its pitch with it): ' + s.rate + ' for ' + s.omega);
        await lift();
        await until(f, () => !__gy.coupled, null, 6000, 'back at speed');
        assert.strictEqual(await f.evaluate(() => __gy.audio.playbackRate), 1);
        const back = await hand(page, f, -300, 600);
        const bw = await f.evaluate(() => ({ omega: __lp.omega, rate: __gy.audio.playbackRate, loud: __gy.loud }));
        assert(bw.omega < -100, 'backwards: ' + bw.omega);
        assert.deepStrictEqual([bw.rate, bw.loud], [0.0625, 0], 'an element cannot play backwards: at its slowest, silent');
        await back();
        await until(f, () => !__gy.coupled, null, 8000, 'back at speed');
        assert.deepStrictEqual(await f.evaluate(() => [__gy.audio.playbackRate, __lp.playing]), [1, true]);
      });
      await check('a short tap on the record is still the piece\'s play · pause; no script error', async () => {
        await f.locator('#recHit').tap();
        await until(f, () => !__lp.playing && !__lp.busy, null, 6000, 'paused by a tap');
        assert.deepStrictEqual(h.errors, []);
      });
    } finally { await h.ctx.close(); }
  }
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
    await check('3D: the lands between the tracks lighter than the grooves beside them; the glare stays where the light is while the record turns under it', async () => {
      const k = await frame.evaluate(() => document.getElementById('deck').getBoundingClientRect().width / 1000);
      const lands = await frame.evaluate(() => __gyDeck._.rings.lands());
      assert.strictEqual(lands.length, 2, 'two lands on a three-track side');
      const pts = (r, n, from = 0, to = 6.2832) => [...Array(n)].map((_, i) => { const a = from + (to - from) * i / n; return [(400 + r * Math.cos(a)) * k, (390 + r * Math.sin(a)) * k]; });
      const lum = p => 0.2126 * p[0] + 0.7152 * p[1] + 0.0722 * p[2], mean = a => a.reduce((s, x) => s + x, 0) / a.length;
      // (the left half of the record: the arm is on the right)
      for (const [o, i] of lands) {
        const mid = (o + i) / 2, at = r => frame.evaluate(p => __gyDeck.probe(p), pts(r, 40, 1.75, 4.55));
        const land = mean((await at(mid)).map(lum)), out = mean((await at(o + 8)).map(lum)), inn = mean((await at(i - 8)).map(lum));
        console.log('     the land at ' + mid.toFixed(0) + ': ' + land.toFixed(1) + ' against the grooves ' + out.toFixed(1) + ' / ' + inn.toFixed(1));
        assert(land > out + 5 && land > inn + 5, 'lighter than the grooves on both sides: ' + [land, out, inn].map(x => x.toFixed(1)));
      }
      const ring = pts(265, 72, 1.75, 4.55), label = [...Array(72)].map((_, i) => { const r = i % 2 ? 73 : 84, a = i / 72 * 6.2832; return [(400 + r * Math.cos(a)) * k, (390 + r * Math.sin(a)) * k]; });
      const s0 = await frame.evaluate(([a, b]) => ({ v: __gyDeck.probe(a), l: __gyDeck.probe(b), spin: __lp.spin }), [ring, label]);
      await until(frame, s => { const d = ((__lp.spin - s) % 360 + 360) % 360; return d > 50 && d < 310; }, s0.spin, 5000, 'the record a little further round');
      const s1 = await frame.evaluate(([a, b]) => ({ v: __gyDeck.probe(a), l: __gyDeck.probe(b) }), [ring, label]);
      const diff = (a, b) => mean(a.map((p, i) => Math.abs(lum(p) - lum(b[i])))), v = s0.v.map(lum);
      const still = diff(s0.v, s1.v), turned = diff(s0.l, s1.l), glare = Math.max(...v) - Math.min(...v);
      console.log('     round the vinyl: the light ' + glare.toFixed(1) + ' from dark to bright, moved ' + still.toFixed(1) + ' as the record turned (its label ' + turned.toFixed(1) + ')');
      assert(glare > 8, 'a glare on the vinyl: ' + glare);
      assert(still < 2 && turned > 3 * still + 4, 'the glare stays, the label turns: ' + still.toFixed(1) + ' / ' + turned.toFixed(1));
    });
    await check('3D: the arm in the hand drawn lifted where the finger has it (its shadow farther); set down, it plays there', async () => {
      const r = await radiusOf(frame, 1, 20), a = await frame.evaluate(r => __lp.angleForRadius(r), r);
      const go = await armTo(frame.page(), frame, a, 600);
      await frame.waitForTimeout(150);
      const d = await frame.evaluate(() => ({ st: __gyDeck.stylus(), lift: __gyDeck._.B.liftG.rotation.z, tilt: __gyDeck._.D.children.find(o => o.material && o.material.uniforms && o.material.uniforms.tilt).material.uniforms.tilt.value }));
      assert(Math.hypot(d.st.x - d.st.css.x, d.st.y - d.st.css.y) <= 2, 'the 3D arm where the piece\'s is: ' + JSON.stringify(d.st));
      assert(Math.abs(d.lift - 1.2 * Math.PI / 180) < 1e-6 && d.tilt > 0.02, 'lifted, its shadow thrown farther: ' + d.lift + ', ' + d.tilt);
      await go();
      await until(frame, () => __lp.playing && !__lp.busy && __lp.trackIdx === 1, null, 10000, 'playing ii.');
      assert(Math.abs(await frame.evaluate(() => __lp.songPos) - 20) < 1.5);
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
    await check('3D: the pitch fader\'s slider and the anti-skate mark move in the 3D deck too (drawn again), the CSS deck\'s own copies hidden', async () => {
      const k = await frame.evaluate(() => document.getElementById('deck').getBoundingClientRect().width / 1000);
      const n0 = await frame.evaluate(() => __gyDeck.draws);
      await drag(frame.page(), frame, '.gy-pitch', 0, -42 * k);              // +4 %
      await until(frame, () => __gy.pitch > 3 && Math.abs(__gyDeck._.B.pitch.position.z - (474 - __gy.pitch / 8 * 84)) < 0.01 && __gyDeck.draws > 0, null, 3000, 'the slider up');
      await frame.waitForTimeout(200);
      const d = await frame.evaluate(k => { const z = __gyDeck._.B.pitch.position.z; return { z, n: __gyDeck.draws, art: getComputedStyle(document.querySelector('#deck svg.gy-art')).visibility,
        at: __gyDeck.probe([[952 * k, (z + 6) * k], [952 * k, (474 + 6) * k]]) }; }, k);   // (beside its dark middle line)
      const brass = p => p[0] - p[2] > 30;                                   // (the lamp is far: a dim brass, as baked before)
      assert(d.n > n0, 'drawn again: ' + n0 + ' → ' + d.n);
      assert(brass(d.at[0]), 'brass where the slider is now: ' + d.at[0]);
      assert(!brass(d.at[1]), 'and the slot where it was: ' + d.at[1]);
      assert.strictEqual(d.art, 'hidden', 'the CSS deck\'s moving parts hidden under the 3D one');
      await drag(frame.page(), frame, '.gy-skate', 24, 0);
      await until(frame, () => Math.abs(__gyDeck._.B.skate.rotation.y) > 0.2, null, 3000, 'the anti-skate mark turned');
      await doubleTap(frame.page(), frame, '.gy-pitch');
      await until(frame, () => __gy.pitch === 0 && __gyDeck._.B.pitch.position.z === 474, null, 3000, 'the slider home');
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

    // The unfolded Fold: the 3D deck in its tab, as large as fits; hidden with another tab, nothing drawn or baked for it.
    const shelf6 = { rev: 1, items: [], pick: '' }, calls6 = { list: 0, pick: [], media: [], del: [] };
    const t3 = await open(b3, html, shelf6, calls6, { test: true, adapt: false, pr: 1 }, { viewport: { width: 780, height: 820 } });
    await until(t3.frame, () => !!document.getElementById('gyBar'), null, 20000, 'the add-on');
    await check('3D in its tab (780×820): drawn as large as the deck; hidden with another tab it draws and bakes nothing; shown again, the same size, drawing', async () => {
      await until(t3.frame, () => window.__gyDeck && (__gyDeck.ready || __gyDeck.css), null, 90000, 'the 3D deck');
      const s0 = await t3.frame.evaluate(() => ({ s: __gyDeck.stats(), w: document.getElementById('deck').getBoundingClientRect().width, tab: __gy.tab }));
      assert.strictEqual(s0.s.css, null); assert.strictEqual(s0.tab, 'deck');
      assert(Math.abs(s0.s.w - Math.round(s0.w)) <= 1 && s0.w > 700, 'the canvas the deck\'s size: ' + s0.s.w + ' for ' + s0.w);
      if (SHOTS) await t3.page.screenshot({ path: path.join(SHOTS, 'tabs_deck_3d.png') });
      await t3.frame.locator('#gyBar .gy-play').tap();
      await until(t3.frame, () => __lp.playing && __lp.omega > 150, null, 20000, 'playing, the platter up to speed');
      const a0 = await t3.frame.evaluate(() => __gyDeck.draws); await t3.frame.waitForTimeout(800);
      assert(await t3.frame.evaluate(() => __gyDeck.draws) > a0, 'drawing while the record turns');
      await t3.frame.locator('.gy-tabs [data-tab="amp"]').tap();
      await t3.frame.waitForTimeout(500);
      const h0 = await t3.frame.evaluate(() => __gyDeck.draws); await t3.frame.waitForTimeout(1500);
      const h1 = await t3.frame.evaluate(() => ({ n: __gyDeck.draws, s: __gyDeck.stats(), playing: __lp.playing }));
      assert.strictEqual(h1.n - h0, 0, 'nothing drawn while its tab is hidden (the record still playing: ' + h1.playing + ')');
      assert.deepStrictEqual([h1.s.w, h1.s.resized], [s0.s.w, 0], 'no size of 0 and no bake for a hidden deck');
      await t3.frame.locator('.gy-tabs [data-tab="deck"]').tap();
      await t3.frame.waitForTimeout(900);
      const b1 = await t3.frame.evaluate(() => ({ n: __gyDeck.draws, s: __gyDeck.stats() }));
      assert(b1.n > h1.n, 'drawn again once shown: ' + h1.n + ' → ' + b1.n);
      assert.deepStrictEqual([b1.s.w, b1.s.resized, b1.s.css], [s0.s.w, 0, null], 'the same size, nothing baked again');
      await t3.frame.locator('#gyBar .gy-play').tap();
      assert.deepStrictEqual(t3.errors, []);
    });
    await t3.ctx.close();

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

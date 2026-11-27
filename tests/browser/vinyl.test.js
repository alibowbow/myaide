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
  The piece is read from its site once into tests/browser/.cache (outside git: a piece is never copied into the app or the
  repository). Without a connection the checks are SKIPPED, said so plainly, and the run does not count as passed.
  The web view asks a tap before sound (setMediaPlaybackRequiresUserGesture): Chromium runs with the same rule.
  Run: node vinyl.test.js   (VINYL_SHOTS=<dir> also saves preview_list_closed.png and preview_list_open.png;
  VINYL_PLAIN=1 serves the piece with the noise fixes only, to see the checks fail without the add-on)
*/
const { chromium, request } = require('playwright');
const fs = require('fs'), path = require('path'), assert = require('assert');
const ROOT = path.join(__dirname, '../..');
const ASSETS = path.join(ROOT, 'app/src/main/assets/piece');
const FIXES = JSON.parse(fs.readFileSync(path.join(ROOT, 'app/src/main/resources/dev/jun/gyeot/core/piece-057.json'), 'utf8'));
const HOST = 'https://miaai-lab.github.io', PIECE = HOST + FIXES.path, KEY = 'testkey0123456789abcdef';
const CACHE = path.join(__dirname, '.cache'), COPY = path.join(CACHE, '057-vinyl-listening-room.html');
const SHOTS = process.env.VINYL_SHOTS || '';
let failed = 0, passed = 0;
async function check(name, fn) {
  try { await fn(); passed++; console.log('PASS', name); } catch (e) { failed++; console.log('FAIL', name, '\n   ', e.message.split('\n').join('\n    ')); }
}

async function live() {
  try {
    const ctx = await request.newContext(process.env.HTTPS_PROXY ? { proxy: { server: process.env.HTTPS_PROXY } } : {});
    const r = await ctx.get(PIECE, { timeout: 20000 });
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
const ITEMS = [
  { id: 'a1b2c3d4e5f6', title: '분홍 소리 시험', artist: '들퍼레 시험', dur: 6, mime: 'audio/wav', size: FILES['a1b2c3d4e5f6'].length, seek: true, art: false },
  { id: '0f1e2d3c4b5a', title: 'A Quiet Tone', artist: '', dur: 5, mime: 'audio/wav', size: FILES['0f1e2d3c4b5a'].length, seek: true, art: false }];

/** The launcher's page around the piece's frame: the system bar 24 px, the page dots at bottom+10 (as launcher.html). */
const FRAME = '<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;height:100%;background:#000;overflow:hidden}'
  + 'iframe{position:absolute;inset:0;width:100%;height:100%;border:0}.edge{position:absolute;top:0;bottom:0;width:22px;z-index:2}.l{left:0}.r{right:0}'
  + '.pager{position:fixed;left:0;right:0;bottom:34px;display:flex;justify-content:center;gap:7px;z-index:5;pointer-events:none}.pager i{width:6px;height:6px;border-radius:50%;background:rgba(255,255,255,.28)}.pager i.on{background:#f5f5f7}</style></head>'
  + '<body><iframe sandbox="allow-scripts allow-same-origin allow-pointer-lock allow-forms" allow="autoplay" src="' + PIECE + '"></iframe><div class="edge l"></div><div class="edge r"></div>'
  + '<div class="pager"><i></i><i></i><i class="on"></i></div><script>const f=document.querySelector("iframe");f.addEventListener("load",()=>f.contentWindow.postMessage({gyeot:"insets",top:24,bottom:24},new URL(f.src).origin));</script></body></html>';

async function open(browser, page_html, shelf, calls) {
  const ctx = await browser.newContext({ viewport: { width: 412, height: 900 }, deviceScaleFactor: 2, hasTouch: true, isMobile: true });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('console', m => { if (m.type() === 'error' && !/404|Failed to load resource/.test(m.text())) errors.push('console: ' + m.text()); });
  await page.route('**/*', async route => {
    const r = route.request(), u = new URL(r.url());
    if (u.host === 'gyeot.local' && u.pathname === '/frame.html') return route.fulfill({ body: FRAME, contentType: 'text/html' });
    if (u.origin !== HOST) return route.fulfill({ status: 404, body: '' });
    if (u.pathname === FIXES.path) return route.fulfill({ body: page_html, contentType: 'text/html; charset=utf-8' });
    const p = u.pathname;
    if (p === '/__gyeot/lib/three.module.js') return route.fulfill({ body: fs.readFileSync(path.join(ASSETS, 'three-0.170.0.module.min.js')), contentType: 'text/javascript' });
    let m = p.match(/^\/__gyeot\/addon\/(vinyl\.css|vinyl\.js|deck3d\.js)$/);
    if (m) return route.fulfill({ body: fs.readFileSync(path.join(ASSETS, m[1])), contentType: m[1].endsWith('.css') ? 'text/css' : 'text/javascript' });
    if (!p.startsWith('/__gyeot/')) return route.fulfill({ status: 404, body: '' });
    if (u.searchParams.get('k') !== KEY) return route.fulfill({ status: 403, body: '' });
    const json = o => route.fulfill({ body: JSON.stringify(o), contentType: 'application/json', headers: { 'Cache-Control': 'no-store' } });
    const list = () => ({ rev: shelf.rev, items: shelf.items, pick: shelf.pick });
    if (p === '/__gyeot/music/list.json') { calls.list++; return json(list()); }
    if (p === '/__gyeot/music/pick') {
      calls.pick.push(u.searchParams.get('kind')); shelf.pick = 'open';
      // The user picks two files in Android's picker a moment later (MusicPickActivity → PieceMusicStore).
      setTimeout(() => { shelf.items = ITEMS.slice(); shelf.rev++; shelf.pick = 'done'; }, 700);
      return json({ pending: true, rev: shelf.rev, via: 'app' });
    }
    if (p === '/__gyeot/deck/stats') return route.fulfill({ status: 204, body: '' });
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
  await page.goto('https://gyeot.local/frame.html');
  const frame = page.frames().find(f => f.url().startsWith(PIECE)) || await new Promise(res => page.on('frameattached', f => res(f)));
  await frame.waitForLoadState('load');
  return { page, ctx, frame, errors };
}
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
  const { page, ctx, frame, errors } = await open(browser, html, shelf, calls);
  try {
    await check('the add-on is on the page: its bar, and 내 음악\'s sleeve once the app has answered', async () => {
      await until(frame, () => !!document.getElementById('gyBar') && document.querySelector('.sleeve[data-album="3"]'), null, 10000, 'the add-on and 내 음악\'s sleeve');
      await frame.waitForTimeout(400);
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
      assert.deepStrictEqual(rows, ['A1 분홍 소리 시험 0:06', 'A2 A Quiet Tone 0:05']);
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
      const a1 = await armAngle(frame), t1 = await frame.locator('#tEl').textContent();
      assert(Math.abs(a1 - a0) > 0.05, 'the arm moves in with the music: ' + a0 + ' → ' + a1);
      assert.notStrictEqual(t1, t0, 'the clock runs: ' + t0 + ' → ' + t1);
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
  // Alone (a wallpaper of one page, the full-screen preview) and where the app does not answer 내 음악 (a browser).
  const alone = await browser.newContext({ viewport: { width: 412, height: 900 }, hasTouch: true, isMobile: true });
  try {
    const page = await alone.newPage(), errs = [];
    page.on('pageerror', e => errs.push(e.message));
    await page.route('**/*', route => {
      const u = new URL(route.request().url());
      if (u.origin === HOST && u.pathname === FIXES.path) return route.fulfill({ body: html, contentType: 'text/html; charset=utf-8' });
      const m = u.pathname.match(/^\/__gyeot\/addon\/(vinyl\.css|vinyl\.js|deck3d\.js)$/);
      if (u.origin === HOST && m) return route.fulfill({ body: fs.readFileSync(path.join(ASSETS, m[1])), contentType: m[1].endsWith('.css') ? 'text/css' : 'text/javascript' });
      return route.fulfill({ status: 404, body: '' });
    });
    await page.goto(PIECE);
    await check('alone, without the app\'s answers: the bar above the phone\'s dock, the three records\' lists, no 내 음악, no error', async () => {
      await page.waitForSelector('#gyBar');
      await page.waitForTimeout(600);
      const b = await page.evaluate(() => { const r = document.getElementById('gyBar').getBoundingClientRect(); return { bottom: r.bottom, vh: innerHeight }; });
      assert(Math.abs(b.vh - b.bottom - (120 + 26)) <= 1, 'over the dock of the phone\'s own home screen: ' + (b.vh - b.bottom));
      await page.locator('#gyBar .gy-list').tap();
      assert.deepStrictEqual(await page.evaluate(() => [...document.querySelectorAll('.gy-chip')].map(c => c.textContent)), ['Moss & Lantern', 'Tidewater', 'Brass Const.']);
      assert.strictEqual(await page.evaluate(() => document.querySelectorAll('.sleeve').length), 3);
      assert.deepStrictEqual(errs, []);
    });
  } finally { await alone.close(); await browser.close(); }
  console.log(failed ? failed + ' failed, ' + passed + ' passed' : 'all ' + passed + ' passed');
  process.exit(failed ? 1 : 0);
})();

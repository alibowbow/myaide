/*
  들퍼레's add-on for the LP page (Opus 5.5 · 057 Hi-Fi Walnut — Vinyl Listening Room). The app serves the piece with a few
  hooks (core/PieceTune, resources piece-057.json) and puts this file before </body>; it works only on top of them
  (window.__lp, the piece's own state as getters), and does nothing without.

  준이 바란 것: "하방 스크롤 안하고 곡목록 보기 및 선택가능하게해줘", "내 폴더에 있는 음악들도 재생가능하게 해줘",
  "파일 선택해서 리스트 추가 할 수 있게". So:
    - a small now-playing bar at the bottom (the track, play/pause, the list) and the list in a sheet over it, every record's
      tracks a tap away without scrolling; the page itself stays as the piece made it;
    - 내 음악, a fourth record: music files picked on the phone (PieceExtras /__gyeot/music/…, the app's file picker), played
      by one audio element into the piece's own sound path (master: the volume knob, mute, the VU meters, the surface noise)
      but not its synthesizer bus (no saturation, no reverb, no wow: the music as it is). The piece's own transport runs it:
      the tonearm follows the files' real lengths, the silent bands sit between them, the arm returns after the last.

  For the three.js deck (deck3d.js), window.__gy: onFrame(fn(dt)) in the piece's own frame (one loop; none while the page is
  hidden), onTrack(fn) when the track, the record or 내 음악's list changes, state() (spin, arm angle and lift, …), label()
  (the label's SVG group and a key that changes when it looks different), grooves() (the tracks' radii), report(stats).
*/
(() => {
  'use strict';
  const L = window.__lp;
  if (!L || window.__gy) return;
  const KEY = (window.__GY && window.__GY.key) || '';
  const ALBUMS = L.ALBUMS;
  const $ = id => document.getElementById(id);
  const q = u => u + (u.includes('?') ? '&' : '?') + 'k=' + encodeURIComponent(KEY);
  const fmt = s => L.fmt(s);
  const clip = (s, n) => { s = String(s || ''); return s.length > n ? s.slice(0, n - 1).trimEnd() + '…' : s; };
  const esc = s => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const idle = (most = 8000) => new Promise(res => { const t0 = performance.now(); const tick = () => (!L.busy || performance.now() - t0 > most) ? res() : setTimeout(tick, 60); tick(); });
  // A file's level against the synthesizer records': measured with Web Audio (tests/browser/vinyl.test.js), a −14 LUFS
  // recording at TRIM sounds as loud as the records, and reads 0 VU at the default volume with VU as the meters' offset.
  const TRIM = 0.65, VU = 23.5;

  /* ── the hook ── */
  const frameFns = new Set(), trackFns = new Set();
  window.__gyFrame = dt => { for (const f of frameFns) try { f(dt); } catch (e) { /* one listener never stops the others */ } };
  window.__gyTrack = () => { for (const f of trackFns) try { f(); } catch (e) {} };

  /* ── where the bottom of the screen is (the home screen tells; alone on a wallpaper, under the phone's dock) ── */
  const root = document.documentElement;
  let heard = false;
  function insets(top, bottom) { root.style.setProperty('--gyB', Math.max(0, Math.round(+bottom || 0)) + 'px'); }
  insets(0, window.parent !== window ? 48 : 120);
  addEventListener('message', e => {
    if (e.origin !== 'https://gyeot.local' || !e.data || e.data.gyeot !== 'insets') return;
    heard = true; insets(e.data.top, e.data.bottom);
  });
  window.__gyInsets = (top, bottom) => { heard = true; insets(top, bottom); };

  /* ── 내 음악 ── */
  const MINE = { id: 3, title: '내 음악', artist: '휴대폰의 음악', cat: 'MY MUSIC', liner: '휴대폰에서 고른 음악을 이 턴테이블에 올렸어요. 소리는 원곡 그대로이고, 레코드 표면 소리만 살짝 깔려요.',
    bpm: 60, beats: 4, wow: 0, crackle: 0.5, rain: 0, scale: [], vu: VU, mine: null, tracks: [], starts: [], total: 1 };
  let host = false, rev = -1, waiting = null, pollT = 0;
  const bad = new Map();
  function restack() {
    let s = 0; MINE.starts = [];
    for (const T of MINE.tracks) { MINE.starts.push(s); s += T.dur + L.GAP; }
    MINE.total = Math.max(1, s - L.GAP);
    MINE.cat = 'MY MUSIC · ' + MINE.tracks.length + '곡 · ' + fmt(MINE.total);
  }
  const mineOn = () => L.albumIdx === 3 && ALBUMS[3] === MINE;
  const current = () => ALBUMS[L.albumIdx].tracks[L.trackIdx];

  const player = (() => {
    const au = new Audio(); au.preload = 'auto';
    let node = null, want = false, at = -1, badRun = 0, unlocked = false, unlocking = false;
    function wire() {
      if (node || !L.actx || !L.master) return;
      try { node = L.actx.createMediaElementSource(au); const trim = L.actx.createGain(); trim.gain.value = TRIM; node.connect(trim); trim.connect(L.master); }
      catch (e) { node = null; }
    }
    function start(i, s) {
      const T = MINE.tracks[i]; if (!T) return;
      wire();
      if (!node) return; // no Web Audio: no sound outside the volume knob
      const url = q('/__gyeot/music/' + T.id);
      if (at !== i || au.dataset.id !== T.id) { au.src = url; au.dataset.id = T.id; at = i; }
      try { au.currentTime = Math.max(0, s || 0); } catch (e) {}
      want = true; unlocking = false;
      const p = au.play();
      if (p && p.catch) p.catch(err => {
        if (!want || (err && err.name === 'AbortError')) return;
        // The web view lets sound start only after a tap (and this one came too long after): the next tap plays it.
        want = false; L.pause(); say('한 번 더 눌러 주세요');
      });
    }
    function stop() { want = false; try { au.pause(); } catch (e) {} }
    function pos() {
      const T = MINE.tracks[L.trackIdx];
      if (T && au.dataset.id === T.id && au.ended) return T.dur;
      return au.dataset.id === (T && T.id) ? (au.currentTime || 0) : L.songPos;
    }
    // The first tap lets this element sound later without one (the web view asks a tap for each element once).
    function unlock() {
      if (unlocked || unlocking || want || !MINE.tracks.length) return;
      unlocking = true;
      au.muted = true; au.src = SILENT; au.dataset.id = '';
      const p = au.play();
      const done = () => { if (unlocking) { try { au.pause(); } catch (e) {} } au.muted = false; unlocking = false; };
      if (p && p.then) p.then(() => { unlocked = true; done(); }, done); else done();
    }
    au.addEventListener('loadedmetadata', () => {
      const T = MINE.tracks.find(t => t.id === au.dataset.id);
      if (!T || !isFinite(au.duration) || au.duration <= 0) return;
      T.real = true;
      if (Math.abs(au.duration - T.dur) > 0.5) { T.dur = au.duration; restack(); if (mineOn()) L.applyAlbum(); }
    });
    au.addEventListener('playing', () => { badRun = 0; bad.delete(au.dataset.id); });
    au.addEventListener('ended', () => {
      // Hidden, the piece's frame (which turns the page) is stopped: the next file starts here.
      if (!want || !document.hidden || !mineOn()) return;
      const i = L.trackIdx;
      if (i < MINE.tracks.length - 1) { L.setTrack(i + 1); L.refreshTrackUI(); start(i + 1, 0); }
    });
    au.addEventListener('error', () => {
      if (!want || !au.dataset.id || !mineOn()) return;
      const id = au.dataset.id, T = MINE.tracks.find(t => t.id === id);
      fetch(q('/__gyeot/music/' + id), { headers: { Range: 'bytes=0-1' } }).then(r => r.status, () => 0).then(async st => {
        bad.set(id, st === 404 ? '찾을 수 없어요' : '재생할 수 없는 형식이에요');
        drawSheet(false);
        if (++badRun >= MINE.tracks.length) { want = false; await idle(); L.pause(); say(T ? clip(T.title, 18) + ': ' + bad.get(id) : bad.get(id)); return; }
        await idle(); if (mineOn() && current() && current().id === id) L.gotoTrack(L.trackIdx + 1);
      });
    });
    return { au, start, stop, pos, unlock };
  })();
  MINE.mine = player;
  // A tenth of a second of silence (8 kHz, 8-bit WAV): only to let the element sound after the first tap.
  const SILENT = (() => { const n = 800, b = new Uint8Array(44 + n), v = new DataView(b.buffer); const s = (o, t) => { for (let i = 0; i < t.length; i++) b[o + i] = t.charCodeAt(i); };
    s(0, 'RIFF'); v.setUint32(4, 36 + n, true); s(8, 'WAVEfmt '); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true); v.setUint32(24, 8000, true);
    v.setUint32(28, 8000, true); v.setUint16(32, 1, true); v.setUint16(34, 8, true); s(36, 'data'); v.setUint32(40, n, true); b.fill(128, 44);
    let t = ''; for (const x of b) t += String.fromCharCode(x); return 'data:audio/wav;base64,' + btoa(t); })();
  for (const ev of ['pointerup', 'click', 'keydown']) document.addEventListener(ev, () => player.unlock(), true);

  // A file whose size the phone does not know (a cloud document) plays straight through: its progress bar is not for seeking.
  document.addEventListener('pointerdown', e => {
    const T = mineOn() && current();
    if (T && T.seek === false && e.target && e.target.closest && e.target.closest('#prog')) { e.stopPropagation(); e.preventDefault(); say('이 곡은 건너뛰며 들을 수 없어요'); }
  }, true);

  function applyList(j) {
    if (!j || !Array.isArray(j.items)) return;
    const items = j.items.filter(it => it && /^[0-9a-f]{12}$/.test(it.id));
    // Never a moment on an empty 내 음악 (the piece's frame reads the current track): off it first, then empty.
    if (mineOn() && !items.length && MINE.tracks.length) {
      player.stop();
      (async () => { await idle(); if (L.playing) await L.pause(); await idle(); await L.changeAlbum(0); applyList(j); })();
      return;
    }
    rev = j.rev;
    const was = new Map(MINE.tracks.map(t => [t.id, t]));
    const curId = mineOn() && current() ? current().id : null;
    MINE.tracks = items.map(it => {
      const o = was.get(it.id), known = o && o.real;
      return { title: it.title || '제목 없는 곡', artist: it.artist || '', dur: known ? o.dur : it.dur > 0 ? it.dur : 180, real: !!known, id: it.id, seek: it.seek !== false, art: !!it.art, bars: 1 };
    });
    restack();
    if (ALBUMS[3] !== MINE && ALBUMS.length === 3) ALBUMS.push(MINE);
    if (mineOn()) {
      const k = curId ? MINE.tracks.findIndex(t => t.id === curId) : -1;
      if (k >= 0) L.setTrack(k, true);
      else {
        // The playing file left the list: the record stops, back at its first track.
        L.setTrack(0); player.stop();
        (async () => { await idle(); if (L.playing) await L.pause(); L.setTrack(0); L.refreshTrackUI(); })();
      }
      L.applyAlbum();
    }
    sleeve(); label(); drawSheet(false); window.__gyTrack();
  }
  async function refresh() {
    if (!host) return;
    let j;
    try { j = await (await fetch(q('/__gyeot/music/list.json?rev=' + rev), { cache: 'no-store' })).json(); } catch (e) { return; }
    const before = MINE.tracks.length;
    if (j.rev !== rev) {
      const was = waiting; waiting = null;
      applyList(j);
      if (was) { const added = MINE.tracks.length - before; note(added > 0 ? added + '곡을 담았어요' : '새로 담을 음악 파일이 없었어요'); }
    } else if (waiting) {
      if (j.pick === 'notification') note('알림을 눌러 파일을 골라 주세요');
      else if (j.pick === 'cancelled' || (j.pick === 'done' && Date.now() - waiting.since > 1500)) { note(j.pick === 'done' ? '새로 담을 음악 파일이 없었어요' : ''); waiting = null; drawSheet(false); }
    }
  }
  function poll() {
    clearTimeout(pollT);
    if (!waiting) return;
    if (Date.now() - waiting.since > 180000) { waiting = null; note(''); drawSheet(false); return; }
    pollT = setTimeout(async () => { await refresh(); poll(); }, 1200);
  }
  async function addMusic(kind) {
    if (waiting) return;
    waiting = { since: Date.now(), kind }; note(kind === 'folder' ? '폴더를 고르는 창을 열어요…' : '파일을 고르는 창을 열어요…'); drawSheet(false);
    try {
      const j = await (await fetch(q('/__gyeot/music/pick?kind=' + kind), { cache: 'no-store' })).json();
      if (j.via === 'notification') note('알림을 눌러 파일을 골라 주세요');
    } catch (e) { waiting = null; note('여기서는 파일을 고를 수 없어요'); drawSheet(false); return; }
    poll();
  }
  async function removeMusic(id) {
    try { applyList(await (await fetch(q('/__gyeot/music/' + id), { method: 'DELETE', cache: 'no-store' })).json()); }
    catch (e) { note('목록에서 빼지 못했어요'); }
  }
  addEventListener('focus', refresh);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });

  /* ── the fourth sleeve and label ── */
  const SVGNS = 'http://www.w3.org/2000/svg';
  let sleeveEl = null;
  function sleeve() {
    if (!host || sleeveEl) return;
    const box = document.querySelector('.sleeves'); if (!box) return;
    sleeveEl = document.createElement('button'); sleeveEl.type = 'button'; sleeveEl.className = 'sleeve'; sleeveEl.dataset.album = '3';
    sleeveEl.setAttribute('aria-pressed', String(L.albumIdx === 3));
    sleeveEl.innerHTML = '<span class="art a3"><span class="t">내 음악</span></span><span>내 음악</span>';
    sleeveEl.addEventListener('click', () => { if (MINE.tracks.length) L.changeAlbum(3); else openSheet(3); });
    box.appendChild(sleeveEl);
  }
  function label() {
    const svg = document.querySelector('#record svg.label'); if (!svg || !host) return;
    let g = $('lab3');
    if (!g) {
      g = document.createElementNS(SVGNS, 'g'); g.id = 'lab3'; g.setAttribute('font-family', 'Palatino Linotype, P052, Georgia, serif');
      if (L.albumIdx !== 3) g.style.display = 'none';
      g.innerHTML = '<circle r="100" fill="#4f201c"/><circle r="100" fill="none" stroke="#1c0b09" stroke-width="3"/><circle r="94" fill="none" stroke="#c9a227" stroke-width="1.3"/>'
        + '<circle r="56" fill="none" stroke="#efe6d8" stroke-opacity=".28" stroke-width=".7"/>'
        + '<clipPath id="lab3clip"><circle cy="-26" r="25"/></clipPath>'
        + '<g id="lab3note" transform="translate(0 -34)" fill="#c9a227"><path d="M-3 -12v18.5a5 4 0 1 1-2.4-3.4V-12l11-3v4z"/><circle cx="-7.6" cy="6.5" r="1.4" fill="#ffc86a" opacity=".7"/></g>'
        + '<image id="lab3art" x="-25" y="-51" width="50" height="50" clip-path="url(#lab3clip)" preserveAspectRatio="xMidYMid slice" style="display:none"/>'
        + '<text font-size="9.4" letter-spacing="2.4" fill="#efe6d8"><textPath href="#arcTop" startOffset="25%" text-anchor="middle">MY MUSIC · 내 음악</textPath></text>'
        + '<text font-size="6.4" letter-spacing="1.6" fill="#c9a227"><textPath href="#arcBot" startOffset="25%" text-anchor="middle">WALNUT &amp; BRASS · FROM YOUR PHONE</textPath></text>'
        + '<text id="lab3t" y="22" text-anchor="middle" font-size="8.2" font-style="italic" fill="#efe6d8"></text>'
        + '<text id="lab3a" y="32" text-anchor="middle" font-size="6.2" fill="#efe6d8" fill-opacity=".72"></text>'
        + '<text id="lab3s" y="44" text-anchor="middle" font-size="6.2" letter-spacing="1.6" fill="#c9a227"></text>';
      svg.appendChild(g);
    }
    const i = L.albumIdx === 3 ? L.trackIdx : 0, T = MINE.tracks[i];
    $('lab3t').textContent = T ? clip(T.title, 24) : '내 음악';
    $('lab3a').textContent = T ? clip(T.artist, 30) : '';
    $('lab3s').textContent = 'SIDE A · ' + (T ? (i + 1) + ' / ' + MINE.tracks.length : '33⅓ RPM');
    const art = $('lab3art'), pic = T && T.art ? q('/__gyeot/music/' + T.id + '/art') : '';
    if (pic) { if (art.getAttribute('href') !== pic) art.setAttribute('href', pic); art.style.display = ''; $('lab3note').style.display = 'none'; }
    else { art.removeAttribute('href'); art.style.display = 'none'; $('lab3note').style.display = ''; }
    // The cover in the liner notes shows the file's own picture too.
    const cover = $('cover');
    if (cover) {
      const on = mineOn() && pic;
      cover.classList.toggle('pic', !!on); cover.style.backgroundImage = on ? 'url("' + pic + '")' : '';
    }
    document.body.classList.toggle('gy-mine', mineOn());
  }

  /* ── the bar ── */
  const ICON = { play: '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M5 3.2v9.6L13 8z" fill="currentColor"/></svg>',
    pause: '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4.4 3h2.7v10H4.4zM8.9 3h2.7v10H8.9z" fill="currentColor"/></svg>',
    list: '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M2.5 4.2h11M2.5 8h11M2.5 11.8h7" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>' };
  const bar = document.createElement('div'); bar.id = 'gyBar'; bar.setAttribute('role', 'group'); bar.setAttribute('aria-label', '지금 곡');
  bar.innerHTML = '<button type="button" class="gy-title" aria-haspopup="dialog"><span class="gy-n"></span><span class="gy-t"></span></button>'
    + '<button type="button" class="gy-play" aria-label="재생">' + ICON.play + '</button>'
    + '<button type="button" class="gy-list" aria-label="곡 목록" aria-haspopup="dialog" aria-expanded="false">' + ICON.list + '</button><i class="gy-prog"></i>';
  document.body.appendChild(bar);
  const barN = bar.querySelector('.gy-n'), barT = bar.querySelector('.gy-t'), barPlay = bar.querySelector('.gy-play'), barList = bar.querySelector('.gy-list'), barProg = bar.querySelector('.gy-prog');
  bar.querySelector('.gy-title').addEventListener('click', () => sheet.hidden ? openSheet(L.albumIdx) : closeSheet());
  barList.addEventListener('click', () => sheet.hidden ? openSheet(L.albumIdx) : closeSheet());
  barPlay.addEventListener('click', () => L.toggle());
  let sayUntil = 0, sayT = 0;
  function say(words) { sayUntil = Date.now() + 2600; barT.textContent = words; barT.classList.add('say'); barN.textContent = ''; clearTimeout(sayT); sayT = setTimeout(drawBar, 2700); }
  function drawBar() {
    if (Date.now() < sayUntil) return;
    barT.classList.remove('say');
    const A = ALBUMS[L.albumIdx], T = current();
    barN.textContent = T ? L.NUM[L.trackIdx] : '';
    barT.textContent = T ? T.title : A.title;
    bar.querySelector('.gy-title').setAttribute('aria-label', (T ? L.NUM[L.trackIdx] + ' ' + T.title + ' · ' : '') + A.title + '. 눌러서 곡 목록');
  }
  let lastOn = null, lastU = -1;
  function frame() {
    const on = L.playing || (L.busy && L.motorOn);
    if (on !== lastOn) { lastOn = on; barPlay.innerHTML = on ? ICON.pause : ICON.play; barPlay.setAttribute('aria-label', on ? '일시 정지' : '재생'); }
    const A = ALBUMS[L.albumIdx], T = A.tracks[L.trackIdx];
    const u = T ? Math.max(0, Math.min(1, L.songPos / L.trackDur(A, T))) : 0;
    if (Math.abs(u - lastU) > 0.001) { lastU = u; barProg.style.transform = 'scaleX(' + u.toFixed(4) + ')'; }
  }
  frameFns.add(frame);

  /* ── the sheet ── */
  const scrim = document.createElement('div'); scrim.id = 'gyScrim'; scrim.hidden = true;
  const sheet = document.createElement('section'); sheet.id = 'gySheet'; sheet.hidden = true; sheet.setAttribute('role', 'dialog'); sheet.setAttribute('aria-label', '곡 목록');
  sheet.innerHTML = '<div class="gy-albums" role="tablist" aria-label="레코드"></div><div class="gy-head"><span class="gy-about"></span></div><div class="gy-note" aria-live="polite"></div><ol class="gy-tracks"></ol>';
  document.body.appendChild(scrim); document.body.appendChild(sheet);
  const chipsEl = sheet.querySelector('.gy-albums'), headEl = sheet.querySelector('.gy-head'), aboutEl = sheet.querySelector('.gy-about'), noteEl = sheet.querySelector('.gy-note'), listEl = sheet.querySelector('.gy-tracks');
  scrim.addEventListener('pointerdown', e => { e.preventDefault(); closeSheet(); });
  addEventListener('keydown', e => { if (e.key === 'Escape' && !sheet.hidden) { e.stopPropagation(); closeSheet(); } }, true);
  let view = 0, shown = 40, noteWords = '';
  const ROMAN = ['i.', 'ii.', 'iii.', 'iv.']; // the piece's own numbers (its NUM follows the record that is on, not the one shown)
  const SHORT = k => { const t = document.querySelector('.sleeve[data-album="' + k + '"] .art .t'); return t ? t.textContent : ALBUMS[k].title; };
  let noteT = 0;
  /** A short line under the list's head (what 파일 추가 is doing); a result goes away after a while. */
  function note(words) {
    noteWords = words || ''; noteEl.textContent = noteWords; clearTimeout(noteT);
    if (noteWords && !waiting) noteT = setTimeout(() => note(''), 6000);
  }
  function openSheet(k) {
    view = ALBUMS[k] ? k : L.albumIdx; shown = 40;
    sheet.hidden = false; scrim.hidden = false; barList.setAttribute('aria-expanded', 'true');
    drawSheet(true);
    if (host) refresh();
  }
  function closeSheet() { sheet.hidden = true; scrim.hidden = true; barList.setAttribute('aria-expanded', 'false'); }
  window.__gyEmpty = i => { if (host && i === 3) openSheet(3); };
  function drawSheet(scroll) {
    if (sheet.hidden) return;
    const albums = ALBUMS.filter(A => A !== MINE || host);
    if (view >= albums.length) view = L.albumIdx;
    chipsEl.innerHTML = albums.map((A, k) => '<button type="button" class="gy-chip' + (k === L.albumIdx ? ' on' : '') + '" role="tab" data-v="' + k + '" aria-selected="' + (k === view) + '">' + esc(SHORT(k)) + '</button>').join('');
    const A = ALBUMS[view];
    headEl.querySelectorAll('.gy-add').forEach(b => b.remove());
    if (A === MINE) {
      aboutEl.textContent = MINE.tracks.length ? MINE.tracks.length + '곡 · ' + fmt(MINE.total) : '휴대폰의 음악';
      for (const [kind, words] of [['files', '+ 파일 추가'], ['folder', '+ 폴더']]) {
        const b = document.createElement('button'); b.type = 'button'; b.className = 'gy-add'; b.dataset.kind = kind;
        b.textContent = waiting && waiting.kind === kind ? '고르는 중…' : words; b.disabled = !!waiting;
        headEl.appendChild(b);
      }
    } else aboutEl.textContent = A.artist + ' · ' + A.tracks.length + '곡 · ' + fmt(A.total);
    noteEl.textContent = noteWords;
    if (A === MINE && !MINE.tracks.length) {
      listEl.innerHTML = '<li class="gy-empty">휴대폰에 있는 음악 파일을 골라 이 턴테이블에 올려요. 원곡 소리 그대로, 레코드 표면 소리만 살짝 깔려요.</li>';
      return;
    }
    const n = Math.min(A.tracks.length, shown);
    let html = '';
    for (let k = 0; k < n; k++) {
      const T = A.tracks[k], why = A === MINE ? bad.get(T.id) : '';
      const sub = why ? '<small class="bad">' + esc(why) + '</small>' : A === MINE && T.artist ? '<small>' + esc(T.artist) + '</small>' : '';
      const num = A === MINE ? 'A' + (k + 1) : ROMAN[k] || (k + 1) + '.';
      html += '<li><button type="button" class="gy-row" data-k="' + k + '" aria-current="' + (view === L.albumIdx && k === L.trackIdx) + '">'
        + '<span class="gy-num">' + esc(num) + '</span><span class="gy-ttl"><b>' + esc(T.title) + '</b>' + sub + '</span><span class="gy-dur">' + fmt(L.trackDur(A, T)) + '</span></button>'
        + (A === MINE ? '<button type="button" class="gy-x" data-id="' + T.id + '" aria-label="' + esc(T.title) + ' 목록에서 빼기">×</button>' : '') + '</li>';
    }
    if (A.tracks.length > n) html += '<li><button type="button" class="gy-more">' + (A.tracks.length - n) + '곡 더 보기</button></li>';
    listEl.innerHTML = html;
    if (scroll) {
      const chip = chipsEl.querySelector('[aria-selected="true"]');
      chipsEl.scrollLeft = chip ? Math.max(0, chip.offsetLeft + chip.offsetWidth - chipsEl.clientWidth + 10) : 0;
      const cur = listEl.querySelector('[aria-current="true"]');
      listEl.scrollTop = cur ? Math.max(0, cur.parentNode.offsetTop - listEl.offsetTop - listEl.clientHeight / 2 + 20) : 0;
    }
  }
  function marks() {
    if (sheet.hidden) return;
    chipsEl.querySelectorAll('.gy-chip').forEach(c => c.classList.toggle('on', +c.dataset.v === L.albumIdx));
    listEl.querySelectorAll('.gy-row').forEach(b => b.setAttribute('aria-current', String(view === L.albumIdx && +b.dataset.k === L.trackIdx)));
  }
  chipsEl.addEventListener('click', e => { const c = e.target.closest('.gy-chip'); if (!c) return; view = +c.dataset.v; shown = 40; note(''); drawSheet(true); });
  headEl.addEventListener('click', e => { const b = e.target.closest('.gy-add'); if (b && !b.disabled) addMusic(b.dataset.kind); });
  let sureT = 0;
  listEl.addEventListener('click', e => {
    const more = e.target.closest('.gy-more'); if (more) { shown += 40; drawSheet(false); return; }
    const x = e.target.closest('.gy-x');
    if (x) {
      // Two taps: the first asks (빼기), the second takes the file off the list (the file itself stays on the phone).
      if (x.classList.contains('sure')) { clearTimeout(sureT); removeMusic(x.dataset.id); return; }
      listEl.querySelectorAll('.gy-x.sure').forEach(b => { b.classList.remove('sure'); b.textContent = '×'; });
      x.classList.add('sure'); x.textContent = '빼기'; clearTimeout(sureT);
      sureT = setTimeout(() => { x.classList.remove('sure'); x.textContent = '×'; }, 3000);
      return;
    }
    const row = e.target.closest('.gy-row'); if (row) pick(view, +row.dataset.k);
  });
  /** A track picked in the list: its record on the platter, the arm to it, and playing. */
  let picking = false;
  async function pick(a, k) {
    if (picking || !ALBUMS[a] || !ALBUMS[a].tracks[k]) return;
    picking = true;
    try {
      await idle();
      if (a !== L.albumIdx) {
        // Paused first, so the new record starts at the picked track (not its first, then a jump).
        if (L.playing) await L.pause();
        await L.changeAlbum(a);
        await idle();
      }
      if (k !== L.trackIdx) {
        if (L.playing) { await L.gotoTrack(k); return; }
        L.setTrack(k); L.refreshTrackUI();
      }
      if (!L.playing) await L.play();
    } finally { picking = false; }
  }

  /* ── for the three.js deck ── */
  const labelKey = () => { const i = L.albumIdx; if (i !== 3) return 'lab' + i; const T = current(); return 'lab3:' + (T ? T.id + ':' + (T.art ? 1 : 0) : ''); };
  let reported = false;
  window.__gy = {
    version: 1, lp: L,
    onFrame(fn) { frameFns.add(fn); return () => frameFns.delete(fn); },
    onTrack(fn) { trackFns.add(fn); return () => trackFns.delete(fn); },
    state() {
      return { spin: L.spin, omega: L.omega, motorOn: L.motorOn, armAngle: L.arm.angle, armLift: L.arm.lift, albumIdx: L.albumIdx, trackIdx: L.trackIdx,
        songPos: L.songPos, playing: L.playing, busy: L.busy, runout: L.runout, tweening: L.tweening };
    },
    /** The label on the record now: its SVG group (with arcTop/arcBot in the same svg's defs), a key, the file's picture. */
    label() {
      const T = current(), el = $('lab' + L.albumIdx);
      return { album: L.albumIdx, el, svg: el ? el.ownerSVGElement : null, key: labelKey(), art: mineOn() && T && T.art ? q('/__gyeot/music/' + T.id + '/art') : null };
    },
    /** The tracks' bands in deck units (radius from the spindle; 300 is the record's edge): lead-in at rOut, run-out below rIn. */
    grooves() {
      const A = ALBUMS[L.albumIdx], r = t => L.R_OUT - (L.R_OUT - L.R_IN) * Math.max(0, Math.min(1, t / A.total));
      return { album: L.albumIdx, rOut: L.R_OUT, rIn: L.R_IN, mine: A === MINE,
        tracks: A.tracks.map((T, k) => ({ r0: r(A.starts[k]), r1: r(A.starts[k] + L.trackDur(A, T)), title: T.title })) };
    },
    /** The deck's frame times, sent once to the app's diagnostic report. */
    report(s) {
      if (reported || !host || !s) return; reported = true;
      const p = new URLSearchParams();
      for (const k of ['avg', 'p95', 'fps', 'pr', 'w', 'h']) if (s[k] != null && isFinite(s[k])) p.set(k, String(Math.round(s[k] * 100) / 100));
      if (s.css) p.set('css', String(s.css).toLowerCase().replace(/[^a-z0-9 _-]/g, '').slice(0, 40));
      fetch(q('/__gyeot/deck/stats?' + p.toString()), { cache: 'no-store' }).catch(() => {});
    },
    stats: null,
    /** For checks: the audio element that plays 내 음악. */
    audio: player.au
  };
  trackFns.add(() => { drawBar(); label(); marks(); });

  /* ── start ── */
  drawBar();
  if (KEY) {
    fetch(q('/__gyeot/music/list.json'), { cache: 'no-store' }).then(r => r.ok ? r.json() : Promise.reject(r.status)).then(j => {
      host = true; applyList(j); if (!ALBUMS.includes(MINE)) ALBUMS.push(MINE); sleeve(); label();
    }, () => { /* opened outside the app: the three records only */ });
  }
  dispatchEvent(new Event('gy:ready'));
})();

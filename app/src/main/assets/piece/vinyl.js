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
      but not its synthesizer bus. The piece's own transport runs it: the tonearm follows the files' real lengths, the silent
      bands sit between them, the arm returns after the last.
  Then (준, on the Galaxy Z Fold): "갤폴드인데 lp페이지는 여전히 스크롤 해야해 차라리 탭으로 구분하면 어떨까", "우측 조작패널들
  조작이 안돼 피치 큐", "mp3들은 자동인식해서 목록들을 쭉 가져오면 안될까 … 음성대화녹음파일들은 파일명 보고 자동으로 걸러",
  "내음악을 원음대로 재생만 돼 lp모드가 없어". So:
    - a screen wide for its height (the unfolded Fold, a tablet, a desktop: innerWidth ≥ 560 and innerHeight < 1.45 ×
      innerWidth) shows one part at a time, each sized to fit: 턴테이블 · 앰프 · 곡목록, small pills at the end of the brand row
      (the last one remembered). Nothing there scrolls but the track list inside its own box. A tall screen (the folded
      cover screen, a phone) keeps the piece's own long page;
    - the deck's right-hand controls work (the piece only drew them): PITCH drags ±8 % (a detent at 0, a double tap back to
      it) and bends speed and pitch together as a turntable does (a record through the piece's own clock and one detune,
      H16–H21; a file by its playbackRate without keeping its pitch); CUE lifts the arm off the record (the sound stops, the
      platter keeps turning) and lowers it again; SKATE turns (a real deck's anti-skate is not heard either). They are see-
      through boxes over the deck at the drawn places, so they work with the 3D deck too, which draws them from controls();
    - + 휴대폰 노래: the songs Android's media index knows (PieceExtras /__gyeot/music/scan: music only, without ringtones,
      alarms, podcasts, recordings, voice and call recordings by their folders and names, or anything under 45 s), in a
      checklist sheet (only the rows in sight drawn), the chosen ones onto 내 음악 (/__gyeot/music/add-media);
    - 내 음악 plays as an LP by default (원음 / LP in its sheet): a little wow and flutter, a warmer tone, a light tape
      saturation and a slightly narrower stereo before the piece's master and its surface noise; 원음 is the file as it is.

  For the three.js deck (deck3d.js), window.__gy: onFrame(fn(dt)) in the piece's own frame (one loop; none while the page is
  hidden or out of the launcher's sight), onTrack(fn) when the track, the record or 내 음악's list changes, state() (spin, arm
  angle and lift, …), controls() (the pitch fader's offset in deck units, the anti-skate knob's angle), label() (the label's
  SVG group and a key that changes when it looks different), grooves() (the tracks' radii), report(stats), shown (whether
  the home screen says the page is in sight; null where nobody says).

  A file goes on to the next one out of the launcher's sight too (no frames there: its end starts the next here), stops when
  the page is hidden (another app, the screen off: it has no notification or audio focus of its own), and a page opened while
  the phone is locked asks for 내 음악 again once it is shown unlocked.
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
  const TRIM = 0.65, VU = 23.5, MOST = 500;
  // What this viewer chose last (the tab, the sound): a convenience only, so a blocked storage just means the defaults.
  const kept = { get(k, d) { try { const v = localStorage.getItem(k); return v == null ? d : v; } catch (e) { return d; } }, set(k, v) { try { localStorage.setItem(k, v); } catch (e) {} } };

  /* ── the hook ── */
  const frameFns = new Set(), trackFns = new Set();
  // When the piece's frame last ran: a frame out of sight (a launcher's page next to the one shown) gets none, though its page
  // is not hidden.
  let lastFrame = 0, frameCount = 0;
  window.__gyFrame = dt => { lastFrame = performance.now(); frameCount++; for (const f of frameFns) try { f(dt); } catch (e) { /* one listener never stops the others */ } };
  const framing = () => performance.now() - lastFrame < 250;
  window.__gyTrack = () => { for (const f of trackFns) try { f(); } catch (e) {} };

  /* ── where the bottom of the screen is (the home screen tells; alone on a wallpaper, under the phone's dock) ── */
  const root = document.documentElement;
  let heard = false;
  function insets(top, bottom) { root.style.setProperty('--gyB', Math.max(0, Math.round(+bottom || 0)) + 'px'); }
  insets(0, window.parent !== window ? 48 : 120);
  // and whether the page is in sight, where the home screen says so (the wallpaper's board slides its pages: the browser keeps
  // running a page next to the one shown); null where nobody says (the launcher's pages: the browser stops those itself)
  let inSight = null;
  addEventListener('message', e => {
    if (e.origin !== 'https://gyeot.local' || !e.data) return;
    if (e.data.gyeot === 'insets') { heard = true; insets(e.data.top, e.data.bottom); }
    else if (e.data.gyeot === 'shown') inSight = !!e.data.on;
  });
  window.__gyInsets = (top, bottom) => { heard = true; insets(top, bottom); };

  /* ── 내 음악 ── */
  // How 내 음악 sounds: 'lp' (the vinyl chain below, the surface a little louder) or 'clean' (the file as it is).
  let sound = kept.get('gy.lp.sound', 'lp') === 'clean' ? 'clean' : 'lp';
  const LINER = { lp: '휴대폰에서 고른 음악을 이 턴테이블에 올렸어요. LP로 들어요: 살짝 흔들리는 회전, 따뜻한 음색, 레코드 표면 소리.',
    clean: '휴대폰에서 고른 음악을 이 턴테이블에 올렸어요. 소리는 원곡 그대로이고, 레코드 표면 소리만 살짝 깔려요.' };
  const CRACKLE = { lp: 0.75, clean: 0.5 };
  const MINE = { id: 3, title: '내 음악', artist: '휴대폰의 음악', cat: 'MY MUSIC', liner: LINER[sound],
    bpm: 60, beats: 4, wow: 0, crackle: CRACKLE[sound], rain: 0, scale: [], vu: VU, mine: null, tracks: [], starts: [], total: 1 };
  let host = false, locked = false, rev = -1, waiting = null, pollT = 0, lockT = 0;
  const bad = new Map();
  function restack() {
    let s = 0; MINE.starts = [];
    for (const T of MINE.tracks) { MINE.starts.push(s); s += T.dur + L.GAP; }
    MINE.total = Math.max(1, s - L.GAP);
    MINE.cat = 'MY MUSIC · ' + MINE.tracks.length + '곡 · ' + fmt(MINE.total);
  }
  const mineOn = () => L.albumIdx === 3 && ALBUMS[3] === MINE;
  const current = () => ALBUMS[L.albumIdx].tracks[L.trackIdx];

  /**
   * The LP sound for a file: the wow of a platter turning at 33⅓ (0.556 Hz, about 0.12 %) and a little flutter (7.3 Hz,
   * 0.04 %) as a short delay whose length is swung; a softer top (12.5 kHz) and a touch of low-mid warmth; a light tape-like
   * saturation (unity for quiet sound, rounding only the loudest); the stereo a little narrower (90 % width). The piece's own
   * surface noise sits on the master after it. Made once, on the piece's own audio context.
   */
  function vinylChain(ctx) {
    const inp = ctx.createGain(); inp.channelCount = 2; inp.channelCountMode = 'explicit'; inp.channelInterpretation = 'speakers'; // a mono file to both sides
    const dl = ctx.createDelay(0.05); dl.delayTime.value = 0.006;
    for (const [f, a] of [[100 / 180, 0.0012 / (2 * Math.PI * (100 / 180))], [7.3, 0.0004 / (2 * Math.PI * 7.3)]]) {
      const o = ctx.createOscillator(); o.frequency.value = f; const g = ctx.createGain(); g.gain.value = a; o.connect(g); g.connect(dl.delayTime); o.start();
    }
    const body = ctx.createBiquadFilter(); body.type = 'peaking'; body.frequency.value = 240; body.Q.value = 0.8; body.gain.value = 1.6;
    const top = ctx.createBiquadFilter(); top.type = 'lowpass'; top.frequency.value = 12500; top.Q.value = 0.5;
    const sat = ctx.createWaveShaper(), n = 2048, cv = new Float32Array(n);
    for (let i = 0; i < n; i++) { const x = (i / (n - 1)) * 2 - 1; cv[i] = Math.tanh(1.1 * x) / 1.1; }
    sat.curve = cv; sat.oversample = '2x';
    const split = ctx.createChannelSplitter(2), merge = ctx.createChannelMerger(2), out = ctx.createGain(); out.gain.value = 0.97;
    const W = 0.8, same = (1 + W) / 2, cross = (1 - W) / 2;
    for (const [from, to, k] of [[0, 0, same], [1, 0, cross], [1, 1, same], [0, 1, cross]]) { const g = ctx.createGain(); g.gain.value = k; split.connect(g, from); g.connect(merge, 0, to); }
    inp.connect(dl); dl.connect(body); body.connect(top); top.connect(sat); sat.connect(split); merge.connect(out);
    return { inp, out, nodes: [inp, dl, body, top, sat, split, merge, out] };
  }

  const player = (() => {
    const au = new Audio(); au.preload = 'auto';
    // The pitch fader bends a file's speed and pitch together, as a turntable does (not a time stretch).
    au.preservesPitch = false; au.mozPreservesPitch = false; au.webkitPreservesPitch = false;
    let node = null, trim = null, lp = null, routed = '', swapping = false, want = false, at = -1, badRun = 0, unlocked = false, unlocking = false;
    function wire() {
      if (node || !L.actx || !L.master) return;
      try { node = L.actx.createMediaElementSource(au); trim = L.actx.createGain(); trim.gain.value = TRIM; node.connect(trim); route(); }
      catch (e) { node = null; }
    }
    /** The file to the master through the LP chain or straight; switched while playing, a 35 ms dip so it never clicks. */
    function route() {
      if (!trim || swapping || routed === sound) return;
      const ctx = L.actx;
      const swap = () => {
        const to = sound;
        try { trim.disconnect(); } catch (e) {}
        if (to === 'lp') { if (!lp) { lp = vinylChain(ctx); lp.out.connect(L.master); } trim.connect(lp.inp); } else trim.connect(L.master);
        routed = to;
      };
      if (!routed || au.paused) { swap(); return; }
      swapping = true; trim.gain.setTargetAtTime(0, ctx.currentTime, 0.006);
      setTimeout(() => { swap(); swapping = false; trim.gain.setTargetAtTime(TRIM, ctx.currentTime, 0.01); if (routed !== sound) route(); }, 35);
    }
    function rate(r) { try { au.defaultPlaybackRate = r; au.playbackRate = r; } catch (e) {} }
    function start(i, s) {
      const T = MINE.tracks[i]; if (!T) return;
      if (document.hidden) { want = false; return; } // never starting unseen (it goes on when shown: visibilitychange)
      wire();
      if (!node) return; // no Web Audio: no sound outside the volume knob
      const url = q('/__gyeot/music/' + T.id);
      if (at !== i || au.dataset.id !== T.id) { au.src = url; au.dataset.id = T.id; at = i; }
      rate(L.speed || 1);
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
      if (Math.abs(au.duration - T.dur) > 0.5) { T.dur = au.duration; restack(); if (mineOn()) retime(MINE.tracks.indexOf(T)); }
    });
    au.addEventListener('playing', () => { badRun = 0; bad.delete(au.dataset.id); });
    au.addEventListener('ended', () => {
      // The piece's frame turns to the next file. Out of sight in the launcher (the page next to the one shown) it gets no
      // frames, though its page is not hidden: then the next file starts here, and after the last the side ends.
      if (!want || !mineOn() || framing()) return;
      const i = L.trackIdx;
      if (i < MINE.tracks.length - 1) { L.setTrack(i + 1); L.refreshTrackUI(); start(i + 1, 0); }
      else { want = false; if (L.endOfSide) L.endOfSide(); }
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
    /** The nodes from the file to the piece's master, in order (for checks: the LP chain or the plain one). */
    const chain = () => !node ? [] : [node, trim].concat(routed === 'lp' && lp ? lp.nodes : []).map(n => n.constructor.name);
    return { au, start, stop, pos, unlock, route, rate, chain, get want() { return want; }, get routed() { return routed; } };
  })();
  MINE.mine = player;
  // A tenth of a second of silence (8 kHz, 8-bit WAV): only to let the element sound after the first tap.
  const SILENT = (() => { const n = 800, b = new Uint8Array(44 + n), v = new DataView(b.buffer); const s = (o, t) => { for (let i = 0; i < t.length; i++) b[o + i] = t.charCodeAt(i); };
    s(0, 'RIFF'); v.setUint32(4, 36 + n, true); s(8, 'WAVEfmt '); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true); v.setUint32(24, 8000, true);
    v.setUint32(28, 8000, true); v.setUint16(32, 1, true); v.setUint16(34, 8, true); s(36, 'data'); v.setUint32(40, n, true); b.fill(128, 44);
    let t = ''; for (const x of b) t += String.fromCharCode(x); return 'data:audio/wav;base64,' + btoa(t); })();
  for (const ev of ['pointerup', 'click', 'keydown']) document.addEventListener(ev, () => player.unlock(), true);

  /** 원음 or LP for 내 음악: the chain, the surface's level, the liner notes; kept for next time. */
  function setSound(s) {
    sound = s === 'clean' ? 'clean' : 'lp'; kept.set('gy.lp.sound', sound);
    MINE.crackle = CRACKLE[sound]; MINE.liner = LINER[sound];
    player.route();
    if (mineOn()) { const ln = $('liner'); if (ln) ln.textContent = MINE.liner; if (L.playing && L.setSurface) L.setSurface(true); }
    drawSheet(false);
  }

  // A file whose size the phone does not know (a cloud document) plays straight through: its progress bar is not for seeking.
  document.addEventListener('pointerdown', e => {
    const T = mineOn() && current();
    if (T && T.seek === false && e.target && e.target.closest && e.target.closest('#prog')) { e.stopPropagation(); e.preventDefault(); say('이 곡은 건너뛰며 들을 수 없어요'); }
  }, true);

  /** A file's real length came (its element read it): the record's bands, its line in the piece's list, the clock, the sheet. */
  function retime(k) {
    const A = MINE, rec = $('record');
    if (rec) {
      const band = n => { if (A.starts[n] == null) return '-9%'; const t = A.starts[n] - L.GAP / 2; return ((L.R_OUT - (L.R_OUT - L.R_IN) * t / A.total) / 300 * 100).toFixed(2) + '%'; };
      rec.style.setProperty('--g1', band(1)); rec.style.setProperty('--g2', band(2));
    }
    const b = document.querySelectorAll('#tracks button')[k], d = b && b.querySelector('.dur');
    if (d && A.tracks[k]) d.textContent = fmt(L.trackDur(A, A.tracks[k]));
    const cat = $('cat'); if (cat) cat.textContent = A.cat;
    L.refreshTrackUI(); drawSheet(false);
  }
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
  /** Locked, the app says nothing of the list: asked again when the page is shown, tapped, or every 15 s while it is seen. */
  function lockedNow() {
    locked = true; clearTimeout(lockT);
    lockT = setTimeout(() => { if (!locked) return; if (document.hidden) lockedNow(); else refresh(); }, 15000);
  }
  async function refresh() {
    if (!host) return;
    let j;
    try { j = await (await fetch(q('/__gyeot/music/list.json?rev=' + rev), { cache: 'no-store' })).json(); } catch (e) { return; }
    if (j && j.locked) { lockedNow(); return; }
    locked = false; clearTimeout(lockT);
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
  // Tapped again while it waits (the picker went away without an answer), the picker opens again.
  async function addMusic(kind) {
    clearTimeout(pollT);
    waiting = { since: Date.now(), kind }; note(kind === 'folder' ? '폴더를 고르는 창을 열어요…' : '파일을 고르는 창을 열어요…'); drawSheet(false);
    try {
      const j = await (await fetch(q('/__gyeot/music/pick?kind=' + kind), { cache: 'no-store' })).json();
      if (j.locked) { waiting = null; lockedNow(); note('잠금을 풀면 고를 수 있어요'); drawSheet(false); return; }
      if (j.via === 'notification') note('알림을 눌러 파일을 골라 주세요');
    } catch (e) { waiting = null; note('여기서는 파일을 고를 수 없어요'); drawSheet(false); return; }
    poll();
  }
  async function removeMusic(id) {
    try { applyList(await (await fetch(q('/__gyeot/music/' + id), { method: 'DELETE', cache: 'no-store' })).json()); }
    catch (e) { note('목록에서 빼지 못했어요'); }
  }
  addEventListener('focus', refresh);
  let tappedAt = 0;
  document.addEventListener('pointerdown', () => { if (locked && Date.now() - tappedAt > 2000) { tappedAt = Date.now(); refresh(); } }, true);
  // Hidden (another app in front, the screen off), a file stops as the synthesizer's records do, but for good: it has no
  // notification to stop it from and holds no audio focus (calls, other music, directions would sound over it). The record
  // stops with it; a tap plays it again.
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) { refresh(); if (!scan.hidden && scanS.state && scanS.state !== 'list') askScan(false); return; }
    if (!mineOn() || !(L.playing || player.want)) return;
    player.stop();
    if (L.playing && !L.busy) L.pause();
  });

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
    box.appendChild(sleeveEl); box.classList.add('gy-four');
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
  function say(words, ms = 2600) { sayUntil = Date.now() + ms; barT.textContent = words; barT.classList.add('say'); barN.textContent = ''; clearTimeout(sayT); sayT = setTimeout(drawBar, ms + 100); }
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

  /* ── one part at a time on a screen wide for its height (the unfolded Fold): 턴테이블 · 앰프 · 곡목록 ── */
  const TABS = [['deck', '턴테이블'], ['amp', '앰프'], ['list', '곡목록']];
  let tab = kept.get('gy.lp.tab', 'deck');
  if (!TABS.some(t => t[0] === tab)) tab = 'deck';
  const brand = document.querySelector('.brand');
  const tabsEl = document.createElement('div'); tabsEl.className = 'gy-tabs'; tabsEl.setAttribute('role', 'tablist'); tabsEl.setAttribute('aria-label', 'LP 화면');
  tabsEl.innerHTML = TABS.map(([k, w]) => '<button type="button" role="tab" data-tab="' + k + '">' + w + '</button>').join('');
  if (brand) brand.appendChild(tabsEl);
  const tabbed = () => !!brand && innerWidth >= 560 && innerHeight < 1.45 * innerWidth;
  function layout() {
    root.classList.toggle('gy-tabbed', tabbed());
    root.dataset.gyTab = tab;
    for (const b of tabsEl.children) { const on = b.dataset.tab === tab; b.setAttribute('aria-selected', String(on)); b.tabIndex = on ? 0 : -1; }
  }
  function showTab(k) { if (!TABS.some(t => t[0] === k)) return; tab = k; kept.set('gy.lp.tab', k); layout(); }
  tabsEl.addEventListener('click', e => { const b = e.target.closest('[data-tab]'); if (b) showTab(b.dataset.tab); });
  tabsEl.addEventListener('keydown', e => {
    const d = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1 : 0; if (!d) return;
    e.preventDefault(); e.stopPropagation();
    const i = (TABS.findIndex(t => t[0] === tab) + d + TABS.length) % TABS.length; showTab(TABS[i][0]); tabsEl.children[i].focus();
  });
  addEventListener('resize', layout);
  layout();

  /* ── the deck's right-hand controls: PITCH, CUE and SKATE (the piece drew them; here they work) ── */
  const deckEl = $('deck');
  // Deck units (the SVG's 1000 × 780): the fader's knob sits at y 474, its marks ±42 (±4 %) and ±84 (±8 %) from it; up is faster.
  const FADER = { y: 474, travel: 84, most: 8 };
  let pitch = 0, skate = 0, knobG = null, skateMark = null;
  if (deckEl) {
    // The moving parts of the CSS deck in an SVG of their own over the plinth (the plinth's filtered wood is not drawn again
    // each time they move); the 3D deck hides it and draws its own.
    const plinth = deckEl.querySelector('svg.plinth');
    const fg = plinth && plinth.querySelector('g[transform="translate(952 330)"]'), sg = plinth && plinth.querySelector('g[transform="translate(944 150)"]');
    const kr = fg && fg.querySelector('rect[y="133"]'), kl = fg && fg.querySelector('path[d="M-12 144h24"]'), mk = sg && sg.querySelector('path[d="M0 -14v8"]');
    if (kr && kl && mk) {
      const art = document.createElementNS(SVGNS, 'svg'); art.setAttribute('class', 'gy-art'); art.setAttribute('viewBox', '0 0 1000 780'); art.setAttribute('aria-hidden', 'true');
      art.innerHTML = '<g transform="translate(952 330)"><g id="gyPitchKnob"></g></g><g transform="translate(944 150)"><g id="gySkate"></g></g>';
      knobG = art.querySelector('#gyPitchKnob'); skateMark = art.querySelector('#gySkate');
      knobG.append(kr, kl); skateMark.append(mk);
      // which way is faster, by the marks
      fg.insertAdjacentHTML('beforeend', '<g font-family="Palatino Linotype, P052, Georgia, serif" font-size="11" letter-spacing=".5" text-anchor="middle" fill="#ecd27a" fill-opacity=".8"><text x="-36" y="64">+8</text><text x="-36" y="232">−8</text></g>');
      plinth.after(art);
    }
  }
  const ctl = (tag, cls, x, y, w, h, attrs) => {
    const el = document.createElement(tag); el.className = 'gy-ctl ' + cls; if (tag === 'button') el.type = 'button';
    for (const k in attrs) el.setAttribute(k, attrs[k]);
    Object.assign(el.style, { left: (x / 10) + '%', top: (y / 7.8) + '%', width: (w / 10) + '%', height: (h / 7.8) + '%' });
    deckEl.appendChild(el); return el;
  };
  const sign = p => (p > 0 ? '+' : p < 0 ? '−' : '±') + Math.abs(p).toFixed(1) + '%';
  function drawControls() {
    if (knobG) knobG.setAttribute('transform', 'translate(0 ' + (-pitch / FADER.most * FADER.travel).toFixed(2) + ')');
    if (skateMark) skateMark.setAttribute('transform', 'rotate(' + skate.toFixed(1) + ')');
  }
  /** The pitch fader: ±8 %, held at 0 near it, speed and pitch together (a record through the piece, a file by its rate). */
  function setPitch(p, tell) {
    p = Math.max(-FADER.most, Math.min(FADER.most, +p || 0));
    if (Math.abs(p) < 0.35) p = 0;                                 // the detent at 0
    p = Math.round(p * 10) / 10;
    if (p !== pitch) {
      pitch = p; const r = 1 + p / 100;
      if (L.setSpeed) L.setSpeed(r);
      player.rate(r);
      drawControls();
      if (fader) { fader.setAttribute('aria-valuenow', String(p)); fader.setAttribute('aria-valuetext', p ? sign(p) : '0%, 33⅓'); }
    }
    if (tell) say('피치 ' + (p ? sign(p) : '0% · 33⅓'), 1600);
  }
  let fader = null;
  if (deckEl) {
    fader = ctl('div', 'gy-pitch', 916, 360, 84, 250, { role: 'slider', tabindex: '0', 'aria-label': '피치 (위로 빠르게)', 'aria-orientation': 'vertical', 'aria-valuemin': '-8', 'aria-valuemax': '8', 'aria-valuenow': '0', 'aria-valuetext': '0%, 33⅓' });
    const cue = ctl('button', 'gy-cue', 916, 200, 84, 104, { 'aria-label': '큐 레버: 바늘 들기 · 내리기', 'aria-pressed': 'false' });
    const knob = ctl('div', 'gy-skate', 916, 114, 84, 84, { role: 'slider', tabindex: '0', 'aria-label': '안티스케이팅', 'aria-valuemin': '0', 'aria-valuemax': '3', 'aria-valuenow': '1.5' });
    const unit = () => 780 / Math.max(1, deckEl.getBoundingClientRect().height);   // deck units per CSS pixel
    let fd = null, lastTap = 0;
    fader.addEventListener('pointerdown', e => {
      e.preventDefault(); e.stopPropagation();
      const now = performance.now(), again = now - lastTap < 320; lastTap = now;
      fd = { y: e.clientY, p: pitch, moved: false, again };
      try { fader.setPointerCapture(e.pointerId); } catch (_) {}
    });
    fader.addEventListener('pointermove', e => {
      if (!fd) return;
      const du = (e.clientY - fd.y) * unit();
      if (Math.abs(du) > 4) fd.moved = true;
      if (fd.moved) setPitch(fd.p - du / FADER.travel * FADER.most, true);
    });
    const fEnd = () => { if (fd && !fd.moved && fd.again) { setPitch(0, true); lastTap = 0; } fd = null; };
    fader.addEventListener('pointerup', fEnd); fader.addEventListener('pointercancel', () => { fd = null; });
    fader.addEventListener('keydown', e => {
      const m = { ArrowUp: 0.5, ArrowRight: 0.5, ArrowDown: -0.5, ArrowLeft: -0.5, PageUp: 2, PageDown: -2 };
      if (e.key in m) setPitch(pitch + m[e.key], true); else if (e.key === 'Home' || e.key === '0') setPitch(0, true); else return;
      e.preventDefault(); e.stopPropagation();
    });
    // CUE: the arm up off a playing record (the platter keeps turning), and down again where it was; the lever follows the
    // arm's lift in both decks (the piece's drawArm, the 3D deck's pose)
    cue.addEventListener('click', async e => {
      e.stopPropagation();
      if (L.busy || L.tweening) return;
      if (L.playing) { if (L.cueUp && await L.cueUp()) say('바늘을 들었어요', 1600); }
      else if (!L.runout) L.play();
    });
    frameFns.add(() => { const up = String(L.arm.lift > 0.5 && !L.busy); if (cue.getAttribute('aria-pressed') !== up) cue.setAttribute('aria-pressed', up); });
    // The start · stop key under a cued arm stops the motor (the piece's own key would lower the arm and play).
    const key = $('deckBtn');
    if (key) key.addEventListener('click', e => { if (!L.playing && !L.busy && !L.runout && L.motorOn && L.stopMotor) { e.stopImmediatePropagation(); L.stopMotor(); } }, true);
    // SKATE: a knob to turn (0 to 3 on its dial); as on a real deck, nothing to hear
    let sd = null;
    const setSkate = a => { skate = Math.max(-120, Math.min(120, a)); const v = (skate + 120) / 80; knob.setAttribute('aria-valuenow', v.toFixed(1)); drawControls(); say('안티스케이팅 ' + v.toFixed(1), 1400); };
    knob.addEventListener('pointerdown', e => { e.preventDefault(); e.stopPropagation(); sd = { x: e.clientX, y: e.clientY, a: skate }; try { knob.setPointerCapture(e.pointerId); } catch (_) {} });
    knob.addEventListener('pointermove', e => { if (sd) setSkate(sd.a + ((e.clientX - sd.x) - (e.clientY - sd.y)) * 1.4); });
    knob.addEventListener('pointerup', () => { sd = null; }); knob.addEventListener('pointercancel', () => { sd = null; });
    knob.addEventListener('keydown', e => { const m = { ArrowUp: 8, ArrowRight: 8, ArrowDown: -8, ArrowLeft: -8 }; if (!(e.key in m)) return; setSkate(skate + m[e.key]); e.preventDefault(); e.stopPropagation(); });
  }

  /* ── the sheet ── */
  const scrim = document.createElement('div'); scrim.id = 'gyScrim'; scrim.hidden = true;
  const sheet = document.createElement('section'); sheet.id = 'gySheet'; sheet.hidden = true; sheet.setAttribute('role', 'dialog'); sheet.setAttribute('aria-label', '곡 목록');
  sheet.innerHTML = '<div class="gy-albums" role="tablist" aria-label="레코드"></div><div class="gy-head"><span class="gy-about"></span></div><div class="gy-adds"></div><div class="gy-note" aria-live="polite"></div><ol class="gy-tracks"></ol>';
  document.body.appendChild(scrim); document.body.appendChild(sheet);
  const chipsEl = sheet.querySelector('.gy-albums'), headEl = sheet.querySelector('.gy-head'), aboutEl = sheet.querySelector('.gy-about'), addsEl = sheet.querySelector('.gy-adds'), noteEl = sheet.querySelector('.gy-note'), listEl = sheet.querySelector('.gy-tracks');
  scrim.addEventListener('pointerdown', e => { e.preventDefault(); if (!scan.hidden) closeScan(false); else closeSheet(); });
  addEventListener('keydown', e => {
    if (e.key !== 'Escape') return;
    if (!scan.hidden) { e.stopPropagation(); closeScan(true); } else if (!sheet.hidden) { e.stopPropagation(); closeSheet(); }
  }, true);
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
  const ADDS = [['songs', '+ 휴대폰 노래', '담는 중…'], ['files', '+ 파일 추가', '고르는 중…'], ['folder', '+ 폴더', '고르는 중…']];
  function drawSheet(scroll) {
    if (sheet.hidden) return;
    const albums = ALBUMS.filter(A => A !== MINE || host);
    if (view >= albums.length) view = L.albumIdx;
    chipsEl.innerHTML = albums.map((A, k) => '<button type="button" class="gy-chip' + (k === L.albumIdx ? ' on' : '') + '" role="tab" data-v="' + k + '" aria-selected="' + (k === view) + '">' + esc(SHORT(k)) + '</button>').join('');
    const A = ALBUMS[view];
    headEl.querySelectorAll('.gy-sound').forEach(b => b.remove());
    if (A === MINE) {
      aboutEl.textContent = MINE.tracks.length ? MINE.tracks.length + '곡 · ' + fmt(MINE.total) : '휴대폰의 음악';
      // 원음 / LP: how 내 음악 sounds (LP by default)
      const s = document.createElement('span'); s.className = 'gy-sound'; s.setAttribute('role', 'radiogroup'); s.setAttribute('aria-label', '내 음악 소리');
      s.innerHTML = [['clean', '원음'], ['lp', 'LP']].map(([k, w]) => '<button type="button" role="radio" data-s="' + k + '" aria-checked="' + (sound === k) + '">' + w + '</button>').join('');
      headEl.appendChild(s);
      addsEl.innerHTML = ADDS.map(([kind, words, busy]) => '<button type="button" class="gy-add" data-kind="' + kind + '">' + (waiting && waiting.kind === kind ? busy : words) + '</button>').join('');
    } else { aboutEl.textContent = A.artist + ' · ' + A.tracks.length + '곡 · ' + fmt(A.total); addsEl.innerHTML = ''; }
    noteEl.textContent = noteWords;
    if (A === MINE && !MINE.tracks.length) {
      listEl.innerHTML = '<li class="gy-empty">휴대폰의 노래나 음악 파일을 골라 이 턴테이블에 올려요. LP로 들으면 살짝 흔들리는 회전과 따뜻한 음색, 원음이면 원곡 그대로예요.</li>';
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
  headEl.addEventListener('click', e => { const s = e.target.closest('[data-s]'); if (s) setSound(s.dataset.s); });
  addsEl.addEventListener('click', e => { const b = e.target.closest('.gy-add'); if (!b) return; if (b.dataset.kind === 'songs') openScan(); else addMusic(b.dataset.kind); });
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

  /* ── 휴대폰 노래: the phone's songs (its media index, recordings and short sounds left out by the app), as a checklist ── */
  const scan = document.createElement('section'); scan.id = 'gyScan'; scan.hidden = true; scan.setAttribute('role', 'dialog'); scan.setAttribute('aria-label', '휴대폰 노래');
  scan.innerHTML = '<div class="gy-shead"><b class="gy-stitle">휴대폰 노래</b><button type="button" class="gy-all" hidden>모두 선택</button><button type="button" class="gy-sx" aria-label="닫기">×</button></div>'
    + '<div class="gy-snote" aria-live="polite"></div><div class="gy-slist" role="listbox" aria-multiselectable="true" aria-label="휴대폰 노래"><div class="gy-spad"></div></div>'
    + '<div class="gy-sfoot"><span class="gy-ssum"></span><button type="button" class="gy-take" disabled>담기</button></div>';
  document.body.appendChild(scan);
  const sTitle = scan.querySelector('.gy-stitle'), sAll = scan.querySelector('.gy-all'), sNote = scan.querySelector('.gy-snote'), sList = scan.querySelector('.gy-slist'),
    sPad = scan.querySelector('.gy-spad'), sSum = scan.querySelector('.gy-ssum'), sTake = scan.querySelector('.gy-take');
  const ROW = 40;
  const scanS = { items: [], chosen: new Set(), hidden: 0, more: 0, state: '', since: 0, t: 0, a: -1, b: -1 };
  const free = () => scanS.items.filter(it => !it.on);
  function openScan() {
    closeSheet(); scan.hidden = false; scrim.hidden = false;
    Object.assign(scanS, { items: [], hidden: 0, more: 0, state: 'finding', since: 0 }); scanS.chosen.clear();
    sNote.textContent = '휴대폰에서 노래를 찾는 중…'; drawScan();
    askScan(true);
  }
  function closeScan(back) { scan.hidden = true; clearTimeout(scanS.t); if (back) openSheet(3); else scrim.hidden = true; }
  /** The list from the app; without the permission, it asks for it (once, then each 1 s for the answer). */
  async function askScan(ask) {
    clearTimeout(scanS.t);
    let j;
    try { j = await (await fetch(q('/__gyeot/music/scan?ask=' + (ask ? 1 : 0)), { cache: 'no-store' })).json(); }
    catch (e) { scanS.state = 'none'; sNote.textContent = '여기서는 휴대폰 노래를 찾을 수 없어요'; return; }
    if (scan.hidden) return;
    if (j.locked) { scanS.state = 'locked'; sNote.textContent = '잠금을 풀면 볼 수 있어요'; return; }
    if (Array.isArray(j.items)) {
      scanS.items = j.items.filter(it => it && /^[0-9a-f]{12}$/.test(it.id)); scanS.hidden = j.hidden | 0; scanS.more = j.more | 0; scanS.state = 'list';
      sNote.textContent = scanS.items.length ? '' : '휴대폰에서 노래를 찾지 못했어요. + 파일 추가로 골라 보세요.';
      sList.scrollTop = 0; drawScan(); return;
    }
    scanS.state = j.perm || 'ask';
    if (scanS.state === 'asking' || scanS.state === 'notification') {
      sNote.textContent = scanS.state === 'asking' ? '음악 파일을 읽어도 되는지 휴대폰이 물어요…' : '알림을 눌러 허용해 주세요';
      if (!scanS.since) scanS.since = Date.now();
      if (Date.now() - scanS.since < 120000) scanS.t = setTimeout(() => askScan(false), 1000);
      return;
    }
    // Refused (or never asked): what is blocked, and the way through in one tap; 파일 추가 needs no permission.
    sNote.innerHTML = '<span>휴대폰 노래를 보려면 ‘음악 및 오디오’ 권한이 필요해요. + 파일 추가는 그대로 돼요.</span>'
      + (scanS.state === 'blocked' ? '' : '<button type="button" class="gy-add" data-do="ask">허용하기</button>')
      + '<button type="button" class="gy-add" data-do="settings">설정 열기</button>';
  }
  sNote.addEventListener('click', async e => {
    const b = e.target.closest('[data-do]'); if (!b) return;
    if (b.dataset.do === 'ask') { scanS.since = 0; sNote.textContent = '음악 파일을 읽어도 되는지 휴대폰이 물어요…'; askScan(true); return; }
    try { await fetch(q('/__gyeot/music/settings'), { cache: 'no-store' }); sNote.textContent = '설정의 ‘권한 → 음악 및 오디오’에서 허용하고 돌아오면 바로 보여요'; }
    catch (e2) { sNote.textContent = '설정을 열지 못했어요'; }
  });
  function drawScan() {
    const n = scanS.items.length, f = free();
    sTitle.textContent = '휴대폰 노래' + (n ? ' ' + n + '곡' : '');
    sAll.hidden = !f.length;
    sAll.textContent = f.length && f.every(it => scanS.chosen.has(it.id)) ? '모두 해제' : '모두 선택';
    sPad.style.height = n * ROW + 'px';
    scanS.a = scanS.b = -1; rows();
    const k = scanS.chosen.size;
    sSum.textContent = (k ? k + '곡 고름' : n ? '담을 노래를 골라 주세요' : '') + (scanS.hidden ? ' · 녹음·짧은 소리 ' + scanS.hidden + '개는 뺐어요' : '')
      + (scanS.more ? ' · 앞의 ' + n + '곡만 보여요' : '');
    sTake.disabled = !k; sTake.textContent = k ? k + '곡 담기' : '담기';
  }
  /** Only the rows in sight (and a few around them) are on the page; the rest is the scroller's height. */
  function rows() {
    const n = scanS.items.length, top = sList.scrollTop, h = sList.clientHeight || 320;
    const a = Math.max(0, Math.floor(top / ROW) - 4), b = Math.min(n, Math.ceil((top + h) / ROW) + 4);
    if (a === scanS.a && b === scanS.b) return;
    scanS.a = a; scanS.b = b;
    let html = '';
    for (let i = a; i < b; i++) {
      const it = scanS.items[i], on = scanS.chosen.has(it.id);
      html += '<button type="button" class="gy-srow" role="option" data-i="' + i + '" style="top:' + (i * ROW) + 'px" aria-selected="' + (it.on || on) + '"' + (it.on ? ' aria-disabled="true"' : '') + '>'
        + '<span class="gy-box" aria-hidden="true"></span><span class="gy-ttl"><b>' + esc(it.title) + '</b>' + (it.artist ? '<small>' + esc(it.artist) + '</small>' : '') + '</span>'
        + '<span class="gy-dur">' + (it.on ? '담김' : it.dur > 0 ? fmt(it.dur) : '') + '</span></button>';
    }
    sPad.innerHTML = html;
  }
  let rowsRaf = 0;
  sList.addEventListener('scroll', () => { if (!rowsRaf) rowsRaf = requestAnimationFrame(() => { rowsRaf = 0; rows(); }); }, { passive: true });
  sList.addEventListener('click', e => {
    const r = e.target.closest('.gy-srow'); if (!r) return;
    const it = scanS.items[+r.dataset.i]; if (!it || it.on) return;
    if (scanS.chosen.has(it.id)) scanS.chosen.delete(it.id); else scanS.chosen.add(it.id);
    drawScan();
  });
  sAll.addEventListener('click', () => {
    const f = free(), all = f.length && f.every(it => scanS.chosen.has(it.id));
    scanS.chosen.clear(); if (!all) for (const it of f) scanS.chosen.add(it.id);
    drawScan();
  });
  scan.querySelector('.gy-sx').addEventListener('click', () => closeScan(true));
  sTake.addEventListener('click', async () => {
    let ids = scanS.items.filter(it => scanS.chosen.has(it.id)).map(it => it.id);
    const room = MOST - MINE.tracks.length;
    if (room <= 0) { sNote.textContent = '내 음악은 ' + MOST + '곡까지 담겨요. 몇 곡을 빼고 담아 주세요.'; return; }
    const cut = ids.length > room; if (cut) ids = ids.slice(0, room);
    let j;
    try { j = await (await fetch(q('/__gyeot/music/add-media?ids=' + ids.join(',')), { cache: 'no-store' })).json(); }
    catch (e) { sNote.textContent = '담지 못했어요'; return; }
    if (j.locked) { sNote.textContent = '잠금을 풀면 담을 수 있어요'; return; }
    if (!j.pending) { askScan(false); return; }
    clearTimeout(pollT);
    waiting = { since: Date.now(), kind: 'songs' };
    closeScan(true); view = 3; note(ids.length + '곡을 담는 중…' + (cut ? ' (' + MOST + '곡까지)' : '')); drawSheet(false);
    poll();
  });

  /* ── for the three.js deck ── */
  const labelKey = () => { const i = L.albumIdx; if (i !== 3) return 'lab' + i; const T = current(); return 'lab3:' + (T ? T.id + ':' + (T.art ? 1 : 0) : ''); };
  const reported = {}, toReport = {};
  window.__gy = {
    version: 1, lp: L,
    onFrame(fn) { frameFns.add(fn); return () => frameFns.delete(fn); },
    onTrack(fn) { trackFns.add(fn); return () => trackFns.delete(fn); },
    state() {
      return { spin: L.spin, omega: L.omega, motorOn: L.motorOn, armAngle: L.arm.angle, armLift: L.arm.lift, albumIdx: L.albumIdx, trackIdx: L.trackIdx,
        songPos: L.songPos, playing: L.playing, busy: L.busy, runout: L.runout, tweening: L.tweening };
    },
    /** The deck's controls as drawn: the pitch fader's knob, deck units from its middle (up is negative), and the anti-skate knob's turn in degrees. */
    controls() { return { fader: -pitch / FADER.most * FADER.travel, skate }; },
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
    /** The deck's frame times, and why the CSS deck took over if it did: each sent once to the app's diagnostic report (when the app is there). */
    report(s) {
      const kind = s && s.css ? 'css' : 'stats';
      if (!s || reported[kind]) return;
      if (!host) { toReport[kind] = toReport[kind] || s; return; }
      reported[kind] = true;
      const p = new URLSearchParams();
      for (const k of ['avg', 'p95', 'fps', 'pr', 'w', 'h', 'dc', 'tri', 'gpu']) if (s[k] != null && isFinite(s[k])) p.set(k, String(Math.round(s[k] * 100) / 100));
      if (s.css) p.set('css', String(s.css).toLowerCase().replace(/[^a-z0-9 _-]/g, '').slice(0, 40));
      fetch(q('/__gyeot/deck/stats?' + p.toString()), { cache: 'no-store' }).catch(() => {});
    },
    stats: null,
    /** Whether the home screen says the page is in sight (null: nobody says). */
    get shown() { return inSight; },
    /** For checks: the audio element that plays 내 음악, how many frames the piece has run, the tab, 내 음악's sound and its chain, the pitch. */
    audio: player.au,
    get frames() { return frameCount; },
    get tab() { return root.classList.contains('gy-tabbed') ? tab : null; },
    get sound() { return sound; },
    chain: () => player.chain(),
    get pitch() { return pitch; }
  };
  trackFns.add(() => { drawBar(); label(); marks(); });

  /* ── start ── */
  drawBar();
  if (KEY) {
    fetch(q('/__gyeot/music/list.json'), { cache: 'no-store' }).then(r => r.ok ? r.json() : Promise.reject(r.status)).then(j => {
      host = true;
      for (const k in toReport) window.__gy.report(toReport[k]);
      if (j && j.locked) { lockedNow(); return; } // opened while locked: the list once it is unlocked
      applyList(j); if (!ALBUMS.includes(MINE)) ALBUMS.push(MINE); sleeve(); label();
    }, () => { /* opened outside the app: the three records only */ });
  }
  dispatchEvent(new Event('gy:ready'));
})();

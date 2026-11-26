"use strict";
/*
  퍼레 홈's board, shared by the live wallpaper (home.html) and the home screen app (launcher.html): makeBoard(parent) draws
  one, setBoardData(k) fills them all. A tap calls window.boardGo(what) (the wallpaper navigates to /do/…, the home screen
  app asks its bridge); window.boardRoom() is the height the board may take (what does not fit goes, least needed first).
*/
const G = window.GYEOT || {slots:[{src:"home",title:"퍼레 홈"}], photo:false, photoAt:0, mascot:false, start:0};
const $ = (root, sel) => root.querySelector(sel);
if (G.accent && /^#[0-9a-f]{6}$/i.test(G.accent)) {
  const r = parseInt(G.accent.slice(1, 3), 16), g = parseInt(G.accent.slice(3, 5), 16), b = parseInt(G.accent.slice(5, 7), 16);
  document.documentElement.style.setProperty("--accent", G.accent);
  document.documentElement.style.setProperty("--accent-soft", "rgba(" + r + "," + g + "," + b + ",.15)");
}
const esc = s => String(s == null ? "" : s).replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"}[c]));
let info = null, lockedNow = false;
const boards = [];

/* ── icons (simple rounded strokes) ───────────────────────── */
const ICON = {
  chart:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19h16"/><path d="M5 15l4-4 3.5 3L19 7.5"/><path d="M15 7.5h4v4"/></svg>',
  bell:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 16V11a6 6 0 1 1 12 0v5l1.5 2h-15z"/><path d="M10 20.5a2.2 2.2 0 0 0 4 0"/></svg>',
  spark:'<svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M8.5 6.5h7A4.5 4.5 0 0 1 20 11v3.5a4.5 4.5 0 0 1-4.5 4.5h-7A4.5 4.5 0 0 1 4 14.5V11a4.5 4.5 0 0 1 4.5-4.5zM12 6.5V4.6"/><path d="M9.4 11.6v2M14.6 11.6v2" stroke-width="2.4"/><circle cx="12" cy="3.4" r="1.1" fill="#fff" stroke="none"/></svg>'
};
/*
  The friend on the phone's screen (준: "너의 미리보기 화면에 있는 캐릭터", "모션 표정 자연스러운 움직임 구현"): a round
  coral robot whose parts move on their own. Eyes: pills that blink and glance, arcs when happy, rounds when surprised,
  lines when asleep, hearts when loved; an antenna that springs; cheeks that blush. alive() runs it.
*/
const EYE = '<rect class="pill" x="-4" y="-7.5" width="8" height="15" rx="4"/><path class="arc" d="M-5.5 2.5 Q0 -5 5.5 2.5"/><circle class="o" r="5.4"/><path class="shut" d="M-5.5 1 Q0 3.5 5.5 1"/><path class="heart" d="M0 5 C-7 0 -6 -6 -2.6 -6 C-1.2 -6 -.3 -5 0 -4 C.3 -5 1.2 -6 2.6 -6 C6 -6 7 0 0 5Z"/>';
const BUDDY = '<svg class="bot" viewBox="0 0 100 100" aria-hidden="true"><defs><linearGradient id="botBody" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ff9e7a"/><stop offset="1" stop-color="#ff6640"/></linearGradient>'
  + '<radialGradient id="botShine" cx=".32" cy=".22" r=".55"><stop offset="0" stop-color="#fff" stop-opacity=".5"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient></defs>'
  + '<g class="b-all"><g class="b-antenna"><line x1="50" y1="20" x2="50" y2="8.5" stroke="#ff6640" stroke-width="4" stroke-linecap="round"/><circle class="b-bulb" cx="50" cy="7" r="5.2"/></g>'
  + '<g class="b-body"><rect x="12" y="18" width="76" height="70" rx="30" fill="url(#botBody)"/><rect x="12" y="18" width="76" height="70" rx="30" fill="url(#botShine)"/>'
  + '<rect x="22" y="31" width="56" height="37" rx="18" fill="#fff" opacity=".97"/>'
  + '<g class="b-look"><g class="b-eye" transform="translate(39 48)">' + EYE + '</g><g class="b-eye" transform="translate(61 48)">' + EYE + '</g>'
  + '<path class="b-mouth" d="M45.5 60 Q50 64.5 54.5 60"/></g>'
  + '<circle class="b-cheek" cx="28.5" cy="60" r="4.6"/><circle class="b-cheek" cx="71.5" cy="60" r="4.6"/></g></g></svg>';

function makeBoard(slot) {
  const b = document.createElement("section"); b.className = "board";
  let stars = "";
  for (let i = 0; i < 14; i++) { const x = (i * 37 + 11) % 100, y = (i * 53 + 7) % 46, d = (i * 0.7) % 5; stars += '<i class="star" style="left:' + x + '%;top:' + y + '%;animation-delay:' + d.toFixed(1) + 's"></i>'; }
  b.innerHTML =
    '<div class="sky"><i class="blob b1"></i><i class="blob b2"></i><i class="blob b3"></i><div class="stars">' + stars + '</div></div>'
  + '<div class="wrap">'
  +   '<header class="hero"><div class="when"><div class="date"></div><div class="clock"></div><div class="hello"></div></div>'
  +     '<button class="buddy" aria-label="퍼레"><div class="bubble"></div><span class="think private"><span class="dots"><i></i><i></i><i></i></span><span class="n"></span></span><span class="zz">z</span><div class="body">' + BUDDY + '</div><div class="ground"></div></button></header>'
  +   '<div class="grid">'
  +     '<div class="card wide event tap private" data-go="calendar" data-rank="5"><div class="cal"><span class="dow"></span><span class="day"></span></div><div class="ev"><div class="eyebrow">다음 일정</div><div class="ev-title">일정을 보는 중…</div><div class="ev-meta"></div></div><div class="soon"></div></div>'
  +     '<div class="card wide coins tap" data-go="trading" data-rank="6"><div class="eyebrow">' + ICON.chart + '시세</div><div class="coin-list"><div class="muted">시세를 받는 중…</div></div></div>'
  +     '<div class="card wide notices tap private hidden" data-go="notices" data-rank="1"><div class="eyebrow">' + ICON.bell + '알림<span class="count"></span></div><div class="list"></div></div>'
  +     '<div class="card wide idea-card tap private hidden" data-go="home" data-rank="2"><div class="idea"><div class="spark">' + ICON.spark + '</div><div><div class="eyebrow">이건 어때요?</div><div class="t"></div><div class="s"></div></div></div></div>'
  +   '</div>'
  + '</div>';
  slot.appendChild(b);
  // Cards pop in one after another, like a console's home screen waking up.
  b.querySelectorAll(".hero,.grid>.card").forEach((c, i) => { c.classList.add("pop"); c.style.animationDelay = (60 + i * 55) + "ms"; });
  const buddy = $(b, ".buddy");
  buddy.addEventListener("click", () => cheer(b));
  alive(b);
  wireTaps(b); tick(b);
  boards.push(b); if (info) show(b);
  return b;
}

/* Touch: a soft press, a springy release, then the screen opens (the app handles /do/…). */
function wireTaps(root) {
  root.querySelectorAll(".tap").forEach(el => {
    let x = 0, y = 0, moved = false;
    el.addEventListener("pointerdown", e => { x = e.clientX; y = e.clientY; moved = false; el.classList.add("down"); });
    el.addEventListener("pointermove", e => { if (Math.abs(e.clientX - x) + Math.abs(e.clientY - y) > 12) { moved = true; el.classList.remove("down"); } });
    const up = () => el.classList.remove("down");
    el.addEventListener("pointerup", up); el.addEventListener("pointercancel", up); el.addEventListener("pointerleave", up);
    el.addEventListener("click", () => { if (moved || lockedNow) return; const what = el.dataset.go; setTimeout(() => go(what), 140); });
  });
}
function go(what) {
  if (what === "calendar" && info && info.calendarOn === false) what = "calendarSettings";
  (window.boardGo || (w => { location.href = "/do/" + encodeURIComponent(w); }))(what);
}

/* ── time ─────────────────────────────────────────────────── */
const DOW = ["일요일","월요일","화요일","수요일","목요일","금요일","토요일"];
function tone(h) { return h >= 5 && h < 10 ? "morning" : h >= 10 && h < 17 ? "day" : h >= 17 && h < 20 ? "evening" : "night"; }
function hello(h) {
  return h < 5 ? "조용한 새벽이에요" : h < 11 ? "좋은 아침이에요" : h < 14 ? "맛있는 점심 드세요" : h < 18 ? "오후도 힘내요" : h < 22 ? "편안한 저녁이에요" : "오늘도 수고했어요";
}
function tick(b) {
  const d = new Date(), h = d.getHours(), m = d.getMinutes();
  if (b.dataset.tone !== tone(h)) b.dataset.tone = tone(h);
  $(b, ".date").textContent = (d.getMonth() + 1) + "월 " + d.getDate() + "일 " + DOW[d.getDay()];
  $(b, ".clock").innerHTML = "<small>" + (h < 12 ? "오전" : "오후") + "</small>" + ((h % 12) || 12) + ":" + String(m).padStart(2, "0");
  $(b, ".hello").textContent = hello(h);
  $(b, ".cal .dow").textContent = DOW[d.getDay()];
  $(b, ".cal .day").textContent = d.getDate();
}
setInterval(() => boards.forEach(tick), 10000);
function clockOf(ms) { const d = new Date(ms), h = d.getHours(); return (h < 12 ? "오전 " : "오후 ") + ((h % 12) || 12) + ":" + String(d.getMinutes()).padStart(2, "0"); }
function until(ms, end) {
  const now = Date.now();
  if (ms <= now && end > now) return {text: "진행 중", now: true};
  const min = Math.round((ms - now) / 60000);
  if (min < 1) return {text: "곧", now: false};
  if (min < 60) return {text: min + "분 후", now: false};
  const hr = Math.round(min / 60);
  return {text: hr < 24 ? hr + "시간 후" : "내일", now: false};
}
function ago(ms) { const min = Math.max(0, Math.round((Date.now() - ms) / 60000)); return min < 1 ? "방금" : min < 60 ? min + "분 전" : Math.round(min / 60) + "시간 전"; }
function money(v) { return "$" + (v >= 1000 ? Math.round(v).toLocaleString("en-US") : v >= 1 ? v.toFixed(2) : v.toPrecision(3)); }
/* A notice's app: its own icon (the app serves /appicon/…), else its first letter on the accent. */
function appMark(n) {
  const letter = esc(Array.from(n.app || "?")[0]);
  // No icon after all: the accent square comes back under it.
  return n.pkg && /^[A-Za-z0-9_.]+$/.test(n.pkg)
    ? '<div class="app icon" data-letter="' + letter + '"><img alt="" src="/appicon/' + n.pkg + '.png" onerror="this.parentNode.className=\'app\';this.parentNode.textContent=this.parentNode.dataset.letter"></div>'
    : '<div class="app">' + letter + '</div>';
}

/* ── what the app says ────────────────────────────────────── */
function show(b) {
  const k = info || {};
  b.classList.toggle("locked", !!k.locked);
  // 시세: BTC · SOXL · KORU
  const coins = k.quotes || [];
  $(b, ".coin-list").innerHTML = coins.length === 0 ? '<div class="muted">시세를 받지 못했어요</div>' : coins.map(c => {
    const dir = c.change >= 0 ? "up" : "down", sign = c.change >= 0 ? "+" : "";
    return '<div class="coin"><b>' + esc(c.symbol) + '</b><span class="px">' + money(c.price) + '</span><span class="pct ' + dir + '">' + (c.change >= 0 ? "▲ " : "▼ ") + Math.abs(c.change).toFixed(2) + '%</span></div>';
  }).join("");
  if (k.locked) { fit(b); return; }
  // 다음 일정
  const ev = $(b, ".event");
  if (k.calendarOn === false) { $(ev, ".ev-title").textContent = "캘린더를 연결해 주세요"; $(ev, ".ev-meta").textContent = "다음 일정이 여기에 보여요"; $(ev, ".soon").textContent = ""; }
  else if (!k.next) { $(ev, ".ev-title").textContent = "오늘 남은 일정이 없어요"; $(ev, ".ev-meta").textContent = "편하게 보내도 돼요"; $(ev, ".soon").textContent = ""; }
  else {
    const n = k.next, u = until(n.begin, n.end);
    $(ev, ".ev-title").textContent = n.title;
    $(ev, ".ev-meta").textContent = (n.allDay ? "하루 종일" : clockOf(n.begin) + " – " + clockOf(n.end).replace(/^오[전후] /, "")) + (n.place ? " · " + n.place : "") + (k.eventsLeft > 1 ? " · 그 뒤로 " + (k.eventsLeft - 1) + "개" : "");
    const soon = $(ev, ".soon"); soon.textContent = n.allDay ? "오늘" : u.text; soon.classList.toggle("now", u.now);
  }
  // 알림
  const nc = $(b, ".notices"), list = k.notices || [];
  nc.classList.toggle("hidden", !Array.isArray(k.notices) || list.length === 0);
  $(nc, ".count").textContent = k.noticeCount > 0 ? k.noticeCount : "";
  $(nc, ".list").innerHTML = list.slice(0, 2).map(n => '<div class="note">' + appMark(n) + '<div class="words"><div class="t">' + esc(n.title || n.app) + '</div><div class="s">' + esc(n.text) + '</div></div><div class="ago">' + ago(n.at) + '</div></div>').join("");
  // 퍼레의 제안
  const idea = $(b, ".idea-card"), s = (k.suggestions || [])[0];
  idea.classList.toggle("hidden", !s);
  if (s) { $(idea, ".t").textContent = s.title; $(idea, ".s").textContent = s.body; }
  // 일하는 중: a thought bubble over 퍼레 (its count, or 실험 for the lab)
  const think = $(b, ".think"), busy = (k.working || 0) > 0 || k.lab;
  think.classList.toggle("on", busy);
  $(think, ".n").textContent = k.working > 0 ? k.working : "실험";
  fit(b);
}
/* The board keeps to the top half: the rest is the home screen's own icons (준: "아이콘들이 배치될 공간을 확보"). What does not
   fit goes, least needed first (lowest rank: notices, then 퍼레's suggestion, then the event, then the quotes). */
function fit(b) {
  const wrap = $(b, ".wrap"), room = window.boardRoom ? window.boardRoom() : innerHeight * 0.5;
  const cards = Array.from(b.querySelectorAll(".grid>.card[data-rank]"));
  cards.forEach(c => c.style.display = "");
  const shown = cards.filter(c => !c.classList.contains("hidden") && getComputedStyle(c).display !== "none").sort((a, c) => (+a.dataset.rank) - (+c.dataset.rank));
  for (const c of shown) { if (wrap.scrollHeight <= room) break; c.style.display = "none"; }
  // Opened wide (a foldable's inner screen), a wide card left alone on its row takes the whole row.
  const wides = Array.from(b.querySelectorAll(".grid>.notices,.grid>.idea-card")).filter(c => c.style.display !== "none" && getComputedStyle(c).display !== "none");
  b.querySelectorAll(".grid>.notices,.grid>.idea-card").forEach(c => c.style.gridColumn = innerWidth >= 600 && wides.length === 1 && wides[0] === c ? "1 / -1" : "");
}
function say(b) {
  const k = info || {}, lines = [];
  if (!k.locked && k.next) lines.push("다음은 ‘" + k.next.title + "’" + (k.next.allDay ? ", 오늘 하루 종일이에요" : ", " + clockOf(k.next.begin) + "이에요!"));
  if (!k.locked && k.working > 0) lines.push("맡긴 일 " + k.working + "개를 열심히 하고 있어요");
  if (!k.locked && k.lab) lines.push("연구실에서 실험을 돌리고 있어요");
  for (const q of k.quotes || []) lines.push((q.symbol === "BTC" ? "비트코인" : q.symbol) + " · 하루 사이 " + Math.abs(q.change).toFixed(1) + "% " + (q.change >= 0 ? "올랐어요" : "내렸어요"));
  if (!k.locked && k.noticeCount > 0) lines.push("새 알림이 " + k.noticeCount + "개 있어요");
  lines.push("부를 땐 오른쪽 바를 당겨 주세요", hello(new Date().getHours()) + "!", "오늘도 같이 힘내요");
  b._said = ((b._said || 0) + 1) % lines.length;
  return lines[b._said];
}
function cheer(b) {
  const buddy = $(b, ".buddy"), bubble = $(b, ".bubble");
  buddy.classList.remove("hop", "wiggle", "tilt", "stretch"); void buddy.offsetWidth; buddy.classList.add("hop");
  setTimeout(() => buddy.classList.remove("hop"), 760);
  // Tapped: happy eyes; three taps in a moment, hearts.
  const now = Date.now(); b._taps = (b._taps || []).filter(t => now - t < 1800).concat(now);
  if (b._feel) b._feel(b._taps.length >= 3 ? "love" : "happy", b._taps.length >= 3 ? 2600 : 1500);
  bubble.textContent = say(b); bubble.classList.add("on");
  clearTimeout(b._bubble); b._bubble = setTimeout(() => bubble.classList.remove("on"), 3400);
}

/*
  What keeps the friend alive, per board, only while the screen is seen: breathing and a slow sway (CSS), blinks at
  uneven gaps (now and then twice), glances that come to rest, a look toward wherever the screen is touched, and now and
  then a small act of its own (a tilt, a stretch, a wiggle of the antenna, a look around). Its mood follows the day:
  thinking while 퍼레 works, surprised at a new notice, asleep late at night; a feeling (happy, love) wins for a moment.
*/
function alive(b) {
  const svg = $(b, ".bot"), look = $(b, ".b-look"), buddy = $(b, ".buddy"), rnd = (a, z) => a + Math.random() * (z - a);
  let mood = "", feeling = "", feelTimer = 0;
  const show = () => { svg.classList.remove("happy", "love", "surprised", "sleepy", "thinking"); const m = feeling || mood; if (m) svg.classList.add(m); buddy.classList.toggle("asleep", m === "sleepy"); };
  const gaze = (x, y, ms) => { look.style.transition = "transform " + (ms || 320) + "ms cubic-bezier(.3,.7,.3,1)"; look.style.transform = "translate(" + x.toFixed(1) + "px," + y.toFixed(1) + "px)"; };
  b._feel = (what, ms) => { feeling = what; show(); clearTimeout(feelTimer); feelTimer = setTimeout(() => { feeling = ""; show(); }, ms || 1500); };
  b._mood = what => { if (what !== mood) { mood = what; show(); if (what === "thinking") gaze(4.5, -4.5, 500); else if (what === "sleepy") gaze(0, 1.5, 900); else gaze(0, 0, 500); } };
  // Blinks: every 2.4 to 6 s, sometimes twice.
  const blink = () => {
    if (!document.hidden && !feeling && mood !== "sleepy") {
      svg.classList.add("blink"); setTimeout(() => svg.classList.remove("blink"), 130);
      if (Math.random() < .18) setTimeout(() => { svg.classList.add("blink"); setTimeout(() => svg.classList.remove("blink"), 120); }, 260);
    }
    setTimeout(blink, rnd(2400, 6000));
  };
  setTimeout(blink, rnd(800, 2500));
  // Glances: small, mostly near the middle, resting a while.
  const glance = () => {
    if (!document.hidden && !feeling && !mood && !b._following) gaze(Math.random() < .55 ? rnd(-3, 3) : rnd(-6.5, 6.5), rnd(-3.5, 2.5), rnd(220, 420));
    setTimeout(glance, rnd(1600, 4200));
  };
  setTimeout(glance, 1200);
  // A small act of its own now and then.
  const act = () => {
    if (!document.hidden && !feeling && mood !== "sleepy" && !buddy.classList.contains("hop")) {
      const kind = ["tilt", "stretch", "wiggle", "around", "tilt"][Math.floor(Math.random() * 5)];
      if (kind === "around") { gaze(-6.5, 0, 380); setTimeout(() => gaze(6.5, 0, 520), 700); setTimeout(() => gaze(0, 0, 400), 1500); }
      else { buddy.classList.add(kind); setTimeout(() => buddy.classList.remove(kind), 1300); }
    }
    setTimeout(act, rnd(8000, 15000));
  };
  setTimeout(act, rnd(5000, 9000));
  // Touch anywhere on the screen: it looks there for a moment.
  addEventListener("pointerdown", e => {
    if (document.hidden || mood === "sleepy" || e.target.closest(".buddy")) return;
    const r = svg.getBoundingClientRect(), dx = e.clientX - (r.left + r.width / 2), dy = e.clientY - (r.top + r.height / 2), d = Math.hypot(dx, dy) || 1;
    b._following = true; gaze(dx / d * 6.5, Math.max(-4.5, Math.min(3.5, dy / d * 4.5)), 220);
    clearTimeout(b._follow); b._follow = setTimeout(() => { b._following = false; gaze(0, 0, 600); }, 1700);
  }, {passive: true});
}
/** The friend's mood from what the app says (thinking, a new notice, night). */
function moodOf(b, k) {
  if (!b._mood) return;
  const h = new Date().getHours(), night = h >= 0 && h < 6;
  const more = (k.noticeCount || 0) > (b._notices == null ? Infinity : b._notices); b._notices = k.noticeCount || 0;
  if (more && b._feel) b._feel("surprised", 1400);
  b._mood(!k.locked && ((k.working || 0) > 0 || k.lab) ? "thinking" : night ? "sleepy" : "");
}
/** The app's data for every board drawn. */
function setBoardData(k) { info = k || {}; lockedNow = !!info.locked; boards.forEach(show); boards.forEach(b => moodOf(b, info)); }
addEventListener("resize", () => boards.forEach(fit));

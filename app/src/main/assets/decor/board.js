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
const EYE = '<rect class="pill" x="-4" y="-7.5" width="8" height="15" rx="4"/><path class="arc" d="M-5.5 2.5 Q0 -5 5.5 2.5"/><circle class="o" r="5.4"/><path class="shut" d="M-5.5 1 Q0 3.5 5.5 1"/><path class="heart" d="M0 5 C-7 0 -6 -6 -2.6 -6 C-1.2 -6 -.3 -5 0 -4 C.3 -5 1.2 -6 2.6 -6 C6 -6 7 0 0 5Z"/>'
  + '<path class="dz" d="M0 0 m-.8 0 a.8 .8 0 1 1 1.6 0 a2.2 2.2 0 1 1 -4.4 0 a3.6 3.6 0 1 1 7.2 0 a5 5 0 1 1 -10 0"/><path class="brow" d="M-5 -9 L4.5 -11.5"/>';
/*
  퍼레's parts: arms at its sides (they wave, dance and hold the umbrella), a mouth for each face (smile, open, frown, wavy,
  chewing), and what it wears or shows as its home, the phone, changes (준: "내 휴대폰 공간안의 환경을 이해하고 그 공간에서 살아가는
  다마고치 같은 느낌"): an umbrella in the rain, a scarf in the cold, sunglasses in hot sun, a nightcap at night, headphones while
  music plays through them, a bolt while it charges (eats), a drop of sweat in the heat, notes to music, a question mark offline.
  The svg's classes pick them (envOf); each is hidden otherwise.
*/
const BUDDY = '<svg class="bot" viewBox="0 0 100 100" aria-hidden="true"><defs><linearGradient id="botBody" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ff9e7a"/><stop offset="1" stop-color="#ff6640"/></linearGradient>'
  + '<radialGradient id="botShine" cx=".32" cy=".22" r=".55"><stop offset="0" stop-color="#fff" stop-opacity=".5"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient></defs>'
  + '<g class="b-all">'
  +   '<g class="acc umbrella"><path d="M86 66 L60 -6" stroke="#5b6470" stroke-width="2.4" stroke-linecap="round"/><path d="M86 66 q3 5 -2 6" fill="none" stroke="#5b6470" stroke-width="2.4" stroke-linecap="round"/>'
  +     '<path d="M24 6 C30 -16 84 -26 98 -2 Q90 -6 84 0 Q76 -8 68 -1 Q59 -8 50 1 Q41 -4 34 3 Q29 0 24 6Z" fill="#7cc4e8"/><path d="M61 -16 C58 -10 55 -3 50 1" fill="none" stroke="#5aa9d0" stroke-width="1.4"/></g>'
  +   '<g class="b-arm l"><rect x="4" y="50" width="13" height="22" rx="6.5" fill="#ff7a55"/></g><g class="b-arm r"><rect x="83" y="50" width="13" height="22" rx="6.5" fill="#ff7a55"/></g>'
  +   '<g class="b-antenna"><line x1="50" y1="20" x2="50" y2="8.5" stroke="#ff6640" stroke-width="4" stroke-linecap="round"/><circle class="b-bulb" cx="50" cy="7" r="5.2"/></g>'
  +   '<g class="b-body"><rect x="12" y="18" width="76" height="70" rx="30" fill="url(#botBody)"/><rect x="12" y="18" width="76" height="70" rx="30" fill="url(#botShine)"/>'
  +     '<rect x="22" y="31" width="56" height="37" rx="18" fill="#fff" opacity=".97"/>'
  +     '<g class="b-look"><g class="b-eye l" transform="translate(39 48)">' + EYE + '</g><g class="b-eye r" transform="translate(61 48) scale(-1 1)">' + EYE + '</g>'
  +       '<path class="b-mouth" d="M45.5 60 Q50 64.5 54.5 60"/><ellipse class="m-open" cx="50" cy="61.5" rx="3.6" ry="3.2"/><path class="m-frown" d="M45.5 63 Q50 58.5 54.5 63"/>'
  +       '<path class="m-wave" d="M43.5 61 q1.6 -2 3.2 0 t3.2 0 t3.2 0 t3.2 0"/><path class="m-chew" d="M46 60.5 Q50 63.5 54 60.5 Q50 61.8 46 60.5Z"/>'
  +       '<g class="acc shades"><rect x="29" y="41" width="20" height="13" rx="5.5" fill="#23232e"/><rect x="51" y="41" width="20" height="13" rx="5.5" fill="#23232e"/><path d="M49 45 h2" stroke="#23232e" stroke-width="2"/><path d="M32 44 l5 -1.5" stroke="#fff" stroke-opacity=".5" stroke-width="1.6" stroke-linecap="round"/></g></g>'
  +     '<circle class="b-cheek" cx="28.5" cy="60" r="4.6"/><circle class="b-cheek" cx="71.5" cy="60" r="4.6"/>'
  +     '<g class="acc scarf"><path d="M16 73 Q50 86 84 73 L85 81 Q50 94 15 81Z" fill="#6f9fe8"/><path d="M15 77 Q50 89 85 77" fill="none" stroke="#fff" stroke-opacity=".45" stroke-width="1.6" stroke-dasharray="3 3"/><rect x="62" y="80" width="10" height="17" rx="3.5" fill="#5d8bd6"/></g>'
  +     '<g class="acc bow"><path d="M50 84 L41 79 L41 89Z M50 84 L59 79 L59 89Z" fill="#3c4a7a"/><circle cx="50" cy="84" r="2.6" fill="#2d3866"/></g></g>'
  +   '<g class="acc cap"><path d="M20 30 C22 6 56 -6 84 2 C74 6 70 12 72 22 C58 16 36 18 20 30Z" fill="#8f7cf0"/><path d="M20 30 C36 20 58 18 76 24" fill="none" stroke="#fff" stroke-width="4.5" stroke-linecap="round"/><circle cx="86" cy="3" r="5.5" fill="#fff"/></g>'
  +   '<g class="acc phones"><path d="M14 50 C12 14 88 14 86 50" fill="none" stroke="#3b3f52" stroke-width="5" stroke-linecap="round"/><rect x="5" y="40" width="13" height="22" rx="6" fill="#3b3f52"/><rect x="82" y="40" width="13" height="22" rx="6" fill="#3b3f52"/></g>'
  +   '<path class="acc bolt" d="M90 12 l-7 11 h5.5 l-3 10 l9 -13 h-5.5 l3.5 -8z" fill="#ffd34d" stroke="#e0a800" stroke-width=".8" stroke-linejoin="round"/>'
  +   '<path class="acc sweat" d="M84 26 q5 7 0 10.5 q-5 -3.5 0 -10.5z" fill="#8fd3ff"/>'
  +   '<g class="acc notes"><text class="note n1" x="88" y="22">♪</text><text class="note n2" x="2" y="30">♫</text></g>'
  +   '<text class="acc q" x="82" y="14">?</text>'
  +   '<g class="acc cookie"><circle cx="50" cy="75" r="7" fill="#d9a066"/><circle cx="47.5" cy="73" r="1.2" fill="#6b4226"/><circle cx="52.5" cy="76.5" r="1.2" fill="#6b4226"/><circle cx="51.5" cy="72" r="1" fill="#6b4226"/></g>'
  + '</g></svg>';

function makeBoard(slot) {
  const b = document.createElement("section"); b.className = "board";
  let stars = "";
  for (let i = 0; i < 14; i++) { const x = (i * 37 + 11) % 100, y = (i * 53 + 7) % 46, d = (i * 0.7) % 5; stars += '<i class="star" style="left:' + x + '%;top:' + y + '%;animation-delay:' + d.toFixed(1) + 's"></i>'; }
  b.innerHTML =
    '<div class="sky"><i class="blob b1"></i><i class="blob b2"></i><i class="blob b3"></i><div class="stars">' + stars + '</div></div>'
  + '<div class="wrap">'
  +   '<header class="hero"><div class="when"><div class="date"></div><div class="clock"></div><div class="hello"></div></div>'
  +     '<button class="buddy" aria-label="퍼레"><div class="bubble"></div><span class="think private"><span class="dots"><i></i><i></i><i></i></span><span class="n"></span></span><span class="zz">z</span><div class="body">' + BUDDY + '</div><div class="ground"></div></button><div class="care" role="dialog" aria-label="퍼레 돌보기"></div></header>'
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
  touchBuddy(b);
  const bubble = $(b, ".bubble");
  bubble.addEventListener("click", e => {
    e.stopPropagation();
    const go = bubble.dataset.go; bubble.classList.remove("on", "act");
    if (go && !lockedNow) (window.boardGo || (w => { location.href = "/do/" + encodeURIComponent(w); }))(go);
  });
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
  // No icon after all: the accent square comes back under it. ("nmark", never "app": on the home screen app that is a button.)
  return n.pkg && /^[A-Za-z0-9_.]+$/.test(n.pkg)
    ? '<div class="nmark icon" data-letter="' + letter + '"><img alt="" src="/appicon/' + n.pkg + '.png" onerror="this.parentNode.className=\'nmark\';this.parentNode.textContent=this.parentNode.dataset.letter"></div>'
    : '<div class="nmark">' + letter + '</div>';
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
  think.classList.toggle("on", !!busy);
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
  // The laid-out height (offsetHeight), not scrollHeight: cards popping in (a transform) would read taller and be hidden.
  for (const c of shown) { if (wrap.offsetHeight <= room) break; c.style.display = "none"; }
  // Opened wide (a foldable's inner screen), a wide card left alone on its row takes the whole row.
  const wides = Array.from(b.querySelectorAll(".grid>.notices,.grid>.idea-card")).filter(c => c.style.display !== "none" && getComputedStyle(c).display !== "none");
  b.querySelectorAll(".grid>.notices,.grid>.idea-card").forEach(c => c.style.gridColumn = innerWidth >= 600 && wides.length === 1 && wides[0] === c ? "1 / -1" : "");
}
/*
  What 퍼레 says when tapped (준: "의미없이 자꾸 코인시세를 반복해서 말해", "생각을 하고 말하게", "감성적인 이야기", "나를 이해한 바탕으로
  일상적인 말걸기, 업무적인 것 말고", "유우머도"): the everyday lines the model wrote from what it knows of the user (k.lines),
  one after another and none twice until all are said; without them, gentle lines for the hour. No prices, no work.
*/
const OWN = {
  dawn: ["이 시간까지 깨어 있는 건 비밀로 해 둘게요", "별들도 슬슬 졸린가 봐요, 깜빡깜빡", "새벽 공기는 생각을 정리하기 딱 좋죠"],
  morning: ["좋은 아침이에요! 오늘 첫 웃음은 제가 맡을게요", "커피 향 나는 하루 되길 바라요", "아침 햇살 충전 완료, 저는 100%예요"],
  lunch: ["점심은 맛있게! 행복은 위장에서 시작된대요", "배꼽시계가 정확한 시간이네요", "오늘 메뉴가 기분을 정해요, 신중하게요"],
  afternoon: ["오후엔 살짝 기지개 한 번 어때요?", "졸릴 땐 저를 보세요, 눈 깜빡 응원 중", "오늘 하늘 한 번 올려다봤어요?"],
  evening: ["오늘도 수고 많았어요, 진심이에요", "저녁 바람이 오늘 이야기를 들어 줄 거예요", "하루를 무사히 보낸 당신, 칭찬해요"],
  night: ["오늘 하루도 잘 버텼어요, 푹 쉬어요", "밤하늘 보며 한숨 돌려요, 저도 옆에 있을게요", "내일의 나에게 맡기고 이제 쉬어요"]
};
function ownLines() {
  const h = new Date().getHours();
  const part = h < 5 ? "dawn" : h < 11 ? "morning" : h < 14 ? "lunch" : h < 18 ? "afternoon" : h < 22 ? "evening" : "night";
  return OWN[part].concat(["두 번 톡 치면 저랑 대화해요"]);
}
function say(b) {
  const k = info || {}, theirs = Array.isArray(k.lines) && k.lines.length ? k.lines.slice() : null;
  const lines = theirs ? theirs.concat(["두 번 톡 치면 저랑 대화해요"]) : ownLines();
  b._used = b._used && b._usedFor === lines.join("|") ? b._used : new Set(); b._usedFor = lines.join("|");
  let left = lines.filter(l => !b._used.has(l)); if (!left.length) { b._used.clear(); left = lines.slice(); }
  const pick = left[Math.floor(Math.random() * left.length)]; b._used.add(pick);
  return pick;
}
function cheer(b) {
  move(b, "hop", 760);
  if (b._feel) b._feel(Math.random() < .25 ? "wink" : "happy", 1500);
  askThink(b, "tap", say(b), true);
}
/** A motion of 퍼레's (a class on it for a while): hop, spin, dance, wave, shiver, wobble, nuzzle, shake, nod, rumble … */
function move(b, kind, ms) {
  const buddy = $(b, ".buddy"); buddy.classList.remove(kind); void buddy.offsetWidth; buddy.classList.add(kind);
  clearTimeout(buddy["_m" + kind]); buddy["_m" + kind] = setTimeout(() => buddy.classList.remove(kind), ms || 1200);
}
/**
  퍼레 thinks of what to say (the AI, window.boardThink) for this moment; at most every 12 s unless the user asked by tapping,
  else (or with no AI here) the line given.
*/
let thinkAt = 0;
function askThink(b, why, fallback, asked) {
  const now = Date.now();
  if (window.boardThink && (asked || now - thinkAt > 12000)) { thinkAt = now; boards.forEach(thinking); window.boardThink(why); return; }
  if (fallback) speak(b, {text: fallback}, 3600);
}

/*
  Touching 퍼레 (준: "퍼레 캐릭터 다양한 모션 애니메이션 표정 인터랙티브한 반응 등도 세세하게 구현 고도화"): a tap, it hops and thinks
  of something to say; two taps, the talk with it (준: "퍼레대화는 퍼레를 더블터치하면 열리는걸로"); more taps in a row, it gets
  dizzy; stroked back and forth, it is petted (hearts float up); held, it squishes and its care card opens.
*/
function touchBuddy(b) {
  const buddy = $(b, ".buddy");
  let down = null, quiet = 0, chatTimer = 0;
  buddy.addEventListener("pointerdown", e => {
    down = {lx: e.clientX, dir: 0, turns: 0, path: 0, petted: false};
    clearTimeout(b._longT);
    b._longT = setTimeout(() => {
      if (!down || down.path > 16 || lockedNow) return;
      quiet = Date.now(); buddy.classList.add("squish"); if (navigator.vibrate) try { navigator.vibrate(12); } catch (x) {}
      openCare(b);
    }, 520);
  });
  buddy.addEventListener("pointermove", e => {
    if (!down) return;
    const dx = e.clientX - down.lx; down.lx = e.clientX; down.path += Math.abs(dx);
    const dir = Math.abs(dx) > 1.5 ? Math.sign(dx) : 0;
    if (dir && down.dir && dir !== down.dir) down.turns++;
    if (dir) down.dir = dir;
    if (down.path > 16) clearTimeout(b._longT);
    if (!down.petted && (down.turns >= 2 || down.path > 120)) { down.petted = true; quiet = Date.now(); pet(b); }
  });
  const up = () => { clearTimeout(b._longT); buddy.classList.remove("squish"); if (down && down.petted) quiet = Date.now(); down = null; };
  buddy.addEventListener("pointerup", up); buddy.addEventListener("pointercancel", up); buddy.addEventListener("pointerleave", up);
  buddy.addEventListener("click", () => {
    if (Date.now() - quiet < 450) return;
    const now = Date.now(); b._taps = (b._taps || []).filter(t => now - t < 1500).concat(now);
    const n = b._taps.length;
    // Two quick taps: the talk, unless a third comes at once (then it is being poked).
    if (n === 2 && now - b._taps[0] < 400) {
      chatTimer = setTimeout(() => { chatTimer = 0; b._taps = []; if (window.boardCare) window.boardCare("talk"); if (window.boardChat) window.boardChat(); else (window.boardGo || (w => { location.href = "/do/" + encodeURIComponent(w); }))("chat"); }, 260);
      return;
    }
    if (n >= 3) { clearTimeout(chatTimer); chatTimer = 0; if (n === 3) { move(b, "wobble", 900); if (b._feel) b._feel("surprised", 900); } else if (n === 4) dizzy(b); return; }
    cheer(b);
  });
}
/** Poked again and again: spiral eyes, a wobble, and a word about it. */
function dizzy(b) {
  move(b, "wobble", 1700); if (b._feel) b._feel("dizzy", 2400);
  askThink(b, "poke", ["어지러워요… 빙글빙글", "콕콕은 그만, 별이 보여요", "으앗, 머리가 핑 돌아요"][Math.floor(Math.random() * 3)]);
}
/** Stroked: hearts float up, it leans into the hand, its heart grows. */
function pet(b) {
  hearts(b, 5); move(b, "nuzzle", 1900); if (b._feel) b._feel("love", 2400);
  if (window.boardCare) window.boardCare("pet");
  askThink(b, "pet", ["헤헤, 간지러워요", "쓰다듬어 줘서 좋아요", "기분이 몽글몽글해요"][Math.floor(Math.random() * 3)]);
}
function hearts(b, n) {
  const buddy = $(b, ".buddy");
  for (let i = 0; i < n; i++) {
    const h = document.createElement("i"); h.className = "heartup";
    h.style.left = (24 + Math.random() * 46).toFixed(0) + "%"; h.style.animationDelay = (i * .13).toFixed(2) + "s";
    h.style.setProperty("--dx", (Math.random() * 34 - 17).toFixed(0) + "px");
    buddy.appendChild(h); setTimeout(() => h.remove(), 1700 + i * 130);
  }
}
/** 퍼레 is thinking (the AI writes its line): the thinking face, dots in the bubble, until the line arrives. */
function thinking(b) {
  const bubble = $(b, ".bubble");
  bubble.innerHTML = '<span class="typing"><i></i><i></i><i></i></span>'; bubble.dataset.go = ""; bubble.classList.remove("act"); bubble.classList.add("on");
  if (b._mood) b._mood("thinking");
  clearTimeout(b._bubble); b._bubble = setTimeout(() => { if (!b._spoken) bubble.classList.remove("on"); }, 15000);
  b._spoken = false;
}
/** The line the AI wrote, typed into the bubble as it is said (the mouth moves while it types). */
function thought(line) {
  boards.forEach(b => {
    const bubble = $(b, ".bubble"), svg = $(b, ".bot"); b._spoken = true;
    if (b._mood) b._mood(""); if (b._feel) b._feel("happy", 1600);
    bubble.textContent = ""; bubble.classList.add("on"); if (svg) svg.classList.add("talking");
    let i = 0; const chars = Array.from(line);
    clearInterval(b._typing);
    b._typing = setInterval(() => {
      i++; bubble.textContent = chars.slice(0, i).join("");
      if (i >= chars.length) { clearInterval(b._typing); if (svg) svg.classList.remove("talking"); clearTimeout(b._bubble); b._bubble = setTimeout(() => bubble.classList.remove("on"), 6500); }
    }, 38);
  });
}
function thoughtFail() { boards.forEach(b => { if (b._mood) b._mood(""); speak(b, {text: say(b)}, 3400); }); }
/** The bubble over the friend: words, and what a tap on it opens (if anything). */
function speak(b, line, ms) {
  const bubble = $(b, ".bubble");
  bubble.textContent = line.text; bubble.dataset.go = line.go || "";
  if (line.go) { const more = document.createElement("b"); more.className = "more"; more.textContent = " ›"; bubble.appendChild(more); }
  bubble.classList.toggle("act", !!line.go); bubble.classList.add("on");
  clearTimeout(b._bubble); b._bubble = setTimeout(() => bubble.classList.remove("on", "act"), ms || 6500);
}

/*
  퍼레 speaks first (준: "들퍼레가 먼저 말 거는 것도 구현해줘", "말풍선으로"): when the screen is seen again after a while, or
  when something new happens while it is seen. The most useful thing first: work done, an event close, a new notice, a
  big move in the prices, the hour's greeting, 퍼레's suggestion. At most one in 45 s, never the same thing twice (what was
  said is kept a few days on this screen), nothing of the user's day while locked. A tap on the bubble opens its screen.
*/
let seenNow = true, hiddenAt = 0, lastSpoke = 0, before = null;
const SAID = (() => { try { return JSON.parse(localStorage.getItem("gyeotSaid") || "{}"); } catch (e) { return {}; } })();
function said(key) { return !!SAID[key]; }
function keep(key) {
  const now = Date.now(); SAID[key] = now;
  for (const k of Object.keys(SAID)) if (now - SAID[k] > 3 * 86400000) delete SAID[k];
  try { localStorage.setItem("gyeotSaid", JSON.stringify(SAID)); } catch (e) {}
}
function today() { const d = new Date(); return d.getFullYear() + "-" + (d.getMonth() + 1) + "-" + d.getDate(); }
function period(h) { return h >= 5 && h < 11 ? "morning" : h >= 11 && h < 14 ? "lunch" : h >= 17 && h < 22 ? "evening" : ""; }
/** What to say now, or null: news first (on any update), the quieter things only when the screen has just been opened. */
/** What to say first, or null: an event about to start, else (when the screen has just been opened) one of 퍼레's everyday lines. */
function nextLine(k, prev, opened) {
  if (!k) return null;
  const now = Date.now(), lines = [];
  const n = !k.locked && k.next;
  if (n && !n.allDay) {
    const min = Math.round((n.begin - now) / 60000);
    if (min >= 0 && min <= 15) lines.push({key: "soon15:" + n.title + n.begin, text: (min <= 1 ? "곧" : min + "분 뒤") + " ‘" + n.title + "’예요" + (n.place ? " · " + n.place : ""), go: "calendar", feel: "surprised"});
  }
  if (opened) {
    const own = Array.isArray(k.lines) && k.lines.length ? k.lines : ownLines().slice(0, -1);
    for (const t of own) lines.push({key: "line:" + today() + ":" + t, text: t, go: "", feel: "happy"});
  }
  return lines.find(l => !said(l.key)) || null;
}
function talk(opened) {
  if (!seenNow || !info) return;
  const now = Date.now();
  if (now - lastSpoke < 45000) return;
  const line = nextLine(info, before, opened);
  if (!line) return;
  lastSpoke = now; keep(line.key);
  boards.forEach(b => {
    const buddy = $(b, ".buddy"); buddy.classList.remove("hop"); void buddy.offsetWidth; buddy.classList.add("hop"); setTimeout(() => buddy.classList.remove("hop"), 760);
    if (line.feel && b._feel) b._feel(line.feel, 1800);
    speak(b, line, 6500);
  });
}
/** The host says when the screen is seen (the home screen app's resume, the wallpaper shown) and when it is not. */
function boardSeen(on) {
  if (!on) { if (seenNow) hiddenAt = Date.now(); seenNow = false; return; }
  const away = hiddenAt ? Date.now() - hiddenAt : 0; seenNow = true;
  if (away > 90000 && window.boardCare) window.boardCare("back");
  if (away > 90000) setTimeout(() => {
    if (window.boardThink && Date.now() - (window._thoughtAt || 0) > 300000 && !nextLine(info, before, false)) { window._thoughtAt = Date.now(); boards.forEach(thinking); window.boardThink("back"); }
    else talk(true);
  }, 900);
}
setTimeout(() => talk(true), 2200);

/*
  What keeps the friend alive, per board, only while the screen is seen: breathing and a slow sway (CSS), blinks at
  uneven gaps (now and then twice), glances that come to rest, a look toward wherever the screen is touched, and now and
  then a small act of its own (a tilt, a stretch, a wiggle of the antenna, a look around). Its mood follows the day:
  thinking while 퍼레 works, surprised at a new notice, asleep late at night; a feeling (happy, love) wins for a moment.
*/
const FEELS = ["happy", "love", "surprised", "sleepy", "thinking", "sad", "dizzy", "yum", "yawn", "wink", "laugh", "shy", "full"];
function alive(b) {
  const svg = $(b, ".bot"), look = $(b, ".b-look"), buddy = $(b, ".buddy"), rnd = (a, z) => a + Math.random() * (z - a);
  let mood = "", feeling = "", feelTimer = 0;
  const show = () => { svg.classList.remove(...FEELS); const m = feeling || mood; if (m) svg.classList.add(m); buddy.classList.toggle("asleep", m === "sleepy"); };
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
  /*
    A small act of its own now and then, from how its home is (envOf): it dances while music plays, munches while charging,
    its tummy rumbles when the battery is low, it shivers in the cold, fans itself in the heat, puzzles over no network,
    yawns late, sulks a little when left alone long, spins for joy when it is very fond of the user; else a tilt, a stretch,
    a look around, a wave, a nod.
  */
  const act = () => {
    if (!document.hidden && !feeling && mood !== "sleepy" && !buddy.classList.contains("hop") && !buddy.classList.contains("dance")) {
      const e = b._env || {}, h = new Date().getHours(), opts = ["tilt", "stretch", "wiggle", "around", "wave", "nod"];
      if (e.music) opts.push("dance", "dance", "dance", "dance");
      if (e.hungry) opts.push("rumble", "rumble");
      if (e.charging) opts.push("munch", "munch");
      if (e.cold) opts.push("shiver", "shiver");
      if (e.hot) opts.push("fan", "fan");
      if (e.rain) opts.push("rainy");
      if (e.offline) opts.push("puzzled", "puzzled");
      if (e.heart >= 80) opts.push("spin", "joy");
      if (e.heart != null && e.heart < 35) opts.push("sulk", "sulk");
      if (h >= 22 || h < 7) opts.push("yawn", "yawn");
      const kind = opts[Math.floor(Math.random() * opts.length)];
      if (kind === "around") { gaze(-6.5, 0, 380); setTimeout(() => gaze(6.5, 0, 520), 700); setTimeout(() => gaze(0, 0, 400), 1500); }
      else if (kind === "dance") { move(b, "dance", 4800); b._feel("happy", 4800); }
      else if (kind === "rumble") { move(b, "rumble", 1100); b._feel("sad", 1900); }
      else if (kind === "munch") b._feel("yum", 1800);
      else if (kind === "shiver") move(b, "shiver", 1300);
      else if (kind === "fan") { move(b, "wave", 1400); b._feel("full", 1400); }
      else if (kind === "rainy") { gaze(2, -5, 500); setTimeout(() => gaze(0, 0, 600), 1600); }
      else if (kind === "puzzled") { move(b, "tilt", 1300); gaze(5, -4.5, 400); setTimeout(() => gaze(0, 0, 600), 1500); }
      else if (kind === "spin") { move(b, "spin", 950); b._feel("laugh", 1300); }
      else if (kind === "joy") { move(b, "hop", 760); setTimeout(() => move(b, "hop", 760), 800); b._feel("happy", 1700); }
      else if (kind === "sulk") { gaze(-6, 2.5, 600); b._feel("sad", 2600); setTimeout(() => gaze(0, 0, 700), 2600); }
      else if (kind === "yawn") { move(b, "stretch", 1300); b._feel("yawn", 1700); }
      else move(b, kind, 1300);
    }
    setTimeout(act, rnd(7000, 13000));
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
function setBoardData(k) {
  const prev = info, first = !prev; info = k || {}; lockedNow = !!info.locked;
  boards.forEach(show); boards.forEach(b => { moodOf(b, info); envOf(b, info); if (b._careOn) drawCare(b); });
  // While it may not speak yet, the older picture is kept, so the news is still news when it may.
  if (first) { before = null; setTimeout(() => talk(true), 900); }
  else if (Date.now() - lastSpoke >= 45000) { before = prev; talk(false); }
  else if (!before) before = prev;
}
addEventListener("resize", () => boards.forEach(fit));

/*
  퍼레's home, the phone (준: "내 휴대폰 공간안의 환경을 이해하고 그 공간에서 살아가는 다마고치 같은 느낌"), from what the app says
  (k.env: battery, charging, music, headphones, silent, offline, room left; k.weather; k.life: its heart, days, stage): what it
  wears (at most two things: a nightcap at night, an umbrella in the rain, headphones to music through them, a scarf in the
  cold, sunglasses in hot sun), what shows by it (a bolt while it charges, sweat in the heat, notes to music, ? offline, a bow
  tie once grown), and news it reacts to (plugged in: it eats; music: it dances; a low battery: it asks for a meal, at most
  every half hour).
*/
function envOf(b, k) {
  const svg = $(b, ".bot"); if (!svg || !k) return;
  const e = k.env || {}, w = k.weather || {}, life = k.life || {}, h = new Date().getHours(), code = w.code, temp = w.temp;
  const rain = code != null && ((code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95);
  const snow = code != null && ((code >= 71 && code <= 77) || code === 85 || code === 86);
  const cold = (temp != null && temp <= 5) || snow, hot = temp != null && temp >= 28, night = h < 6;
  const sunny = code != null && code <= 1 && w.day !== false && temp != null && temp >= 24;
  const hungry = e.battery != null && e.battery <= 20 && !e.charging;
  b._env = {music: !!e.music, phones: !!e.phones, charging: !!e.charging, hungry, cold, hot, rain, snow, offline: !!e.offline, heart: life.heart, night, battery: e.battery, roomFree: e.roomFree, silent: !!e.silent};
  const wear = [];
  if (night) wear.push("cap");
  if (rain) wear.push("umbrella");
  if (e.music && e.phones && wear.length < 2) wear.push("phones");
  if (cold && wear.length < 2) wear.push("scarf");
  if (sunny && !night && wear.length < 2) wear.push("shades");
  ["cap", "umbrella", "phones", "scarf", "shades"].forEach(x => svg.classList.toggle("w-" + x, wear.includes(x)));
  svg.classList.toggle("w-bolt", !!e.charging); svg.classList.toggle("w-sweat", hot && !night);
  svg.classList.toggle("w-notes", !!e.music); svg.classList.toggle("w-q", !!e.offline);
  svg.classList.toggle("w-bow", life.stage === "친구" || life.stage === "단짝");
  $(b, ".buddy").classList.toggle("baby", life.stage === "아기");
  const was = b._was; b._was = {charging: !!e.charging, music: !!e.music};
  if (!was || lockedNow) return;
  if (e.charging && !was.charging) { if (b._feel) b._feel("yum", 2200); move(b, "hop", 760); askThink(b, "charge", "냠냠, 충전이 제일 맛있어요"); }
  else if (e.music && !was.music) { move(b, "dance", 5200); if (b._feel) b._feel("happy", 5200); askThink(b, "music", "음악이다! 몸이 저절로 들썩여요"); }
  else if (hungry && seenNow && Date.now() - (b._hungryAt || 0) > 1800000) { b._hungryAt = Date.now(); move(b, "rumble", 1100); if (b._feel) b._feel("sad", 2000); askThink(b, "hungry", "꼬르륵… 충전해 주시면 힘이 날 것 같아요"); }
}
/** The app's word on 퍼레's life after a care act (launcher.life): its heart and days, and how it took it. */
function boardLife(life) {
  if (!life) return;
  const felt = life.felt; info = info || {}; info.life = Object.assign({}, life); delete info.life.felt;
  boards.forEach(b => {
    envOf(b, info); if (b._careOn) drawCare(b);
    if (felt === "yum") { const svg = $(b, ".bot"); svg.classList.remove("eating"); void svg.getBoundingClientRect(); svg.classList.add("eating"); setTimeout(() => svg.classList.remove("eating"), 1700); if (b._feel) b._feel("yum", 1800); setTimeout(() => askThink(b, "snack", "냠냠, 정말 맛있어요!", true), 900); }
    else if (felt === "full") { move(b, "shake", 800); if (b._feel) b._feel("full", 1600); askThink(b, "full", "아까 먹어서 배불러요, 나중에 주세요", true); }
    else if (felt === "fun") { move(b, "spin", 950); setTimeout(() => move(b, "hop", 760), 950); if (b._feel) b._feel("laugh", 2000); askThink(b, "play", "와아, 한 번 더 놀아요!", true); }
  });
}
/** The phone as it is now (launcher.env, asked every quarter minute while seen), so music and charging are noticed soon. */
function boardEnv(env) { if (!env) return; info = info || {}; info.env = env; boards.forEach(b => { envOf(b, info); if (b._careOn) drawCare(b); }); }

/*
  The care card (held 퍼레): its name, how grown up it is and the days together; its heart (five hearts), its food (the
  battery, and whether it is eating), what it is up to now; a stroke, a snack, play. Small, under 퍼레; a tap elsewhere or
  twelve quiet seconds close it.
*/
function openCare(b) {
  if (lockedNow) return;
  const card = $(b, ".care"); $(b, ".bubble").classList.remove("on", "act");
  b._careOn = true; drawCare(b); card.classList.add("on");
  clearTimeout(b._careT); b._careT = setTimeout(() => closeCare(b), 12000);
  if (!card._wired) {
    card._wired = true;
    card.addEventListener("click", e => {
      e.stopPropagation(); clearTimeout(b._careT); b._careT = setTimeout(() => closeCare(b), 12000);
      const btn = e.target.closest("button"); if (!btn) return;
      if (btn.dataset.care === "close") { closeCare(b); return; }
      const kind = btn.dataset.care;
      if (kind === "pet") { pet(b); return; }
      if (window.boardCare) window.boardCare(kind);
    });
    addEventListener("pointerdown", e => { if (b._careOn && !e.target.closest(".care") && !e.target.closest(".buddy")) closeCare(b); }, {passive: true});
  }
}
function closeCare(b) { b._careOn = false; clearTimeout(b._careT); $(b, ".care").classList.remove("on"); }
function drawCare(b) {
  const k = info || {}, life = k.life || {}, e = b._env || {}, heart = life.heart == null ? 55 : life.heart;
  const full = Math.round(heart / 20), hearts = "♥".repeat(full) + '<s>' + "♥".repeat(5 - full) + '</s>';
  const food = e.battery == null ? "" : '<div class="care-row"><span class="k">밥</span><span class="meter' + (e.battery <= 20 && !e.charging ? " low" : "") + '"><i style="width:' + e.battery + '%"></i></span><span class="v">' + (e.charging ? "충전하며 냠냠" : e.battery <= 20 ? "배고파요" : e.battery >= 80 ? "배불러요" : "든든해요") + '</span></div>';
  const now = e.night ? "자는 시간이라 졸려요" : e.music ? "음악에 맞춰 들썩이는 중" : e.charging ? "충전기에서 밥 먹는 중" : e.hungry ? "배터리가 적어서 배고파요" : e.rain ? "비가 와서 우산 썼어요" : e.cold ? "추워서 목도리 했어요" : e.hot ? "더워서 땀이 송골송골" : e.offline ? "인터넷이 끊겨서 갸우뚱" : e.roomFree != null && e.roomFree < 10 ? "방(저장 공간)이 꽉 찼어요" : heart >= 80 ? "기분 최고예요" : heart < 35 ? "조금 심심해요" : "편안하게 쉬는 중";
  const acts = window.boardCare ? '<div class="care-acts"><button data-care="pet">쓰다듬기</button><button data-care="snack">' + (life.snackReady === false ? "간식(배불러요)" : "간식 주기") + '</button><button data-care="play">놀아 주기</button></div>' : "";
  $(b, ".care").innerHTML = '<div class="care-head"><b>' + esc(k.name || "퍼레") + '</b><span>' + esc(life.stage || "아기") + ' · 함께한 지 ' + (life.days || 1) + '일</span><button class="care-x" data-care="close" aria-label="닫기">✕</button></div>'
    + '<div class="care-row"><span class="k">마음</span><span class="hearts">' + hearts + '</span></div>' + food
    + '<div class="care-row"><span class="k">지금</span><span class="v now">' + now + '</span></div>' + acts;
}

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
const EYE = '<rect class="pill" x="-4" y="-7.5" width="8" height="15" rx="4"/><circle class="gl" cx="-1.3" cy="-3.6" r="1.4"/><path class="arc" d="M-5.5 2.5 Q0 -5 5.5 2.5"/><circle class="o" r="5.4"/><path class="shut" d="M-5.5 1 Q0 3.5 5.5 1"/>'
  + '<path class="heart" d="M0 5 C-7 0 -6 -6 -2.6 -6 C-1.2 -6 -.3 -5 0 -4 C.3 -5 1.2 -6 2.6 -6 C6 -6 7 0 0 5Z"/>'
  + '<path class="dz" d="M0 0 m-.8 0 a.8 .8 0 1 1 1.6 0 a2.2 2.2 0 1 1 -4.4 0 a3.6 3.6 0 1 1 7.2 0 a5 5 0 1 1 -10 0"/><path class="brow" d="M-5 -9 L4.5 -11.5"/>'
  + '<path class="x" d="M-4.3 -4.3 L4.3 4.3 M4.3 -4.3 L-4.3 4.3"/><circle class="dot" r="2.7"/><path class="line" d="M-5 0 H5"/>'
  + '<g class="wide"><circle r="6.4"/><circle class="w-gl" cx="-2.2" cy="-2.6" r="2"/><circle class="w-gl" cx="2" cy="2.2" r=".9"/></g>'
  + '<path class="u" d="M-5.5 -1.5 Q0 4.5 5.5 -1.5"/><path class="sq" d="M-4.5 -4.5 L3.5 0 L-4.5 4.5"/><path class="tear" d="M2.5 6 q2.6 4.2 0 6.2 q-2.6 -2 0 -6.2z"/>';
/*
  퍼레's parts. Faces (FACES) are made of an eye (pill, arc, round, shut, heart, spiral, x, dot, line, wide, u, squint …), a
  mouth (smile, open, frown, wavy, chewing, flat, grin, tongue, small o, cat, teeth, pout, kiss, smirk), brows and effects
  (tears, blush lines, an anger mark, sweat, "!", gloom, steam, a kiss heart, notes, "?"), picked by data attributes on the svg.
  What it wears (준: "내 휴대폰 공간안의 환경을 이해하고 그 공간에서 살아가는 다마고치 같은 느낌", "3배로 늘려줘") follows the weather,
  the hour, the day and the phone (envOf): each piece is hidden until its w- class is on.
*/
const BUDDY = '<svg class="bot" viewBox="0 0 100 100" aria-hidden="true"><defs><linearGradient id="botBody" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ff9e7a"/><stop offset="1" stop-color="#ff6640"/></linearGradient>'
  + '<radialGradient id="botShine" cx=".32" cy=".22" r=".55"><stop offset="0" stop-color="#fff" stop-opacity=".5"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient></defs>'
  + '<g class="b-all">'
  // behind 퍼레: what it holds high or that floats over it
  +   '<g class="acc umbrella"><path d="M86 66 L60 -6" stroke="#5b6470" stroke-width="2.4" stroke-linecap="round"/><path d="M86 66 q3 5 -2 6" fill="none" stroke="#5b6470" stroke-width="2.4" stroke-linecap="round"/>'
  +     '<path d="M24 6 C30 -16 84 -26 98 -2 Q90 -6 84 0 Q76 -8 68 -1 Q59 -8 50 1 Q41 -4 34 3 Q29 0 24 6Z" fill="#7cc4e8"/><path d="M61 -16 C58 -10 55 -3 50 1" fill="none" stroke="#5aa9d0" stroke-width="1.4"/></g>'
  +   '<g class="acc balloon"><path d="M90 60 Q96 30 92 4" fill="none" stroke="#9aa0b4" stroke-width="1.2"/><path d="M92 6 C80 -2 80 -16 88 -16 C91 -16 92 -14 92 -12 C92 -14 93 -16 96 -16 C104 -16 104 -2 92 6Z" fill="#ff6f91"/><path d="M86 -11 q2 -3 4 -2" stroke="#fff" stroke-opacity=".7" stroke-width="1.4" fill="none" stroke-linecap="round"/></g>'
  +   '<g class="acc storm"><path d="M30 -2 a10 10 0 0 1 14 -10 a12 12 0 0 1 22 2 a9 9 0 0 1 6 16 H34 a9 9 0 0 1 -4 -8z" fill="#8d93a8"/><path d="M52 6 l-4 8 h4 l-2 7 l7 -10 h-4 l3 -5z" fill="#ffd34d"/></g>'
  +   '<g class="b-arm l"><rect x="4" y="50" width="13" height="22" rx="6.5" fill="#ff7a55"/></g><g class="b-arm r"><rect x="83" y="50" width="13" height="22" rx="6.5" fill="#ff7a55"/></g>'
  +   '<g class="b-antenna"><line x1="50" y1="20" x2="50" y2="8.5" stroke="#ff6640" stroke-width="4" stroke-linecap="round"/><circle class="b-bulb" cx="50" cy="7" r="5.2"/>'
  +     '<g class="acc flower"><circle cx="50" cy="1.5" r="3.4" fill="#ffb3c8"/><circle cx="55" cy="5.5" r="3.4" fill="#ffb3c8"/><circle cx="53" cy="11" r="3.4" fill="#ffb3c8"/><circle cx="47" cy="11" r="3.4" fill="#ffb3c8"/><circle cx="45" cy="5.5" r="3.4" fill="#ffb3c8"/><circle cx="50" cy="7" r="2.6" fill="#ffd36e"/></g></g>'
  +   '<g class="b-body"><rect x="12" y="18" width="76" height="70" rx="30" fill="url(#botBody)"/><rect x="12" y="18" width="76" height="70" rx="30" fill="url(#botShine)"/>'
  +     '<g class="acc blanket"><path d="M11 60 Q50 52 89 60 L90 90 Q50 98 10 90Z" fill="#9fc8ff"/><g fill="#fff" opacity=".6"><circle cx="24" cy="70" r="2"/><circle cx="40" cy="78" r="2"/><circle cx="58" cy="70" r="2"/><circle cx="74" cy="80" r="2"/><circle cx="32" cy="86" r="2"/><circle cx="66" cy="88" r="2"/></g></g>'
  +     '<rect x="22" y="31" width="56" height="37" rx="18" fill="#fff" opacity=".97"/>'
  +     '<g class="fx fx-gloom"><path d="M32 33 v7 M40 33 v9 M48 33 v6 M56 33 v9 M64 33 v7" stroke="#8f8fd6" stroke-width="1.6" stroke-linecap="round" opacity=".7"/></g>'
  +     '<g class="b-look"><g class="b-eye l" transform="translate(39 48)">' + EYE + '</g><g class="b-eye r" transform="translate(61 48) scale(-1 1)">' + EYE + '</g>'
  +       '<path class="b-mouth" d="M45.5 60 Q50 64.5 54.5 60"/><ellipse class="m-open" cx="50" cy="61.5" rx="3.6" ry="3.2"/><path class="m-frown" d="M45.5 63 Q50 58.5 54.5 63"/>'
  +       '<path class="m-wave" d="M43.5 61 q1.6 -2 3.2 0 t3.2 0 t3.2 0 t3.2 0"/><path class="m-chew" d="M46 60.5 Q50 63.5 54 60.5 Q50 61.8 46 60.5Z"/>'
  +       '<path class="m-flat" d="M46 61 H54"/><path class="m-grin" d="M43.5 59 H56.5 Q56 66 50 66 Q44 66 43.5 59Z"/>'
  +       '<g class="m-tongue"><path d="M45.5 60 Q50 64.5 54.5 60" fill="none"/><path d="M48.2 62.2 q0 4 2.4 4 q2.4 0 2.2 -4.6z" fill="#ff7c93"/></g>'
  +       '<circle class="m-osm" cx="50" cy="61.5" r="1.9"/><path class="m-cat" d="M44.5 60 q2.75 3.2 5.5 0 q2.75 3.2 5.5 0"/>'
  +       '<g class="m-teeth"><rect x="44" y="58.6" width="12" height="5.6" rx="2"/><path d="M44 61.4 H56 M48 58.6 V64.2 M52 58.6 V64.2"/></g>'
  +       '<path class="m-pout" d="M48.5 59.5 q3 1.5 0 2.8 q3 1.4 0 2.8"/><path class="m-kiss" d="M49 59 q3 1.6 0 3 q3 1.6 0 3"/><path class="m-smirk" d="M46 61.5 Q51 62.5 55 58.8"/>'
  +       '<g class="acc shades"><rect x="29" y="41" width="20" height="13" rx="5.5" fill="#23232e"/><rect x="51" y="41" width="20" height="13" rx="5.5" fill="#23232e"/><path d="M49 45 h2" stroke="#23232e" stroke-width="2"/><path d="M32 44 l5 -1.5" stroke="#fff" stroke-opacity=".5" stroke-width="1.6" stroke-linecap="round"/></g>'
  +       '<g class="acc mask"><rect x="23" y="39" width="54" height="16" rx="8" fill="#5b6bbf"/><path d="M31 47 q4 3 8 0 M61 47 q4 3 8 0" stroke="#fff" stroke-width="1.8" fill="none" stroke-linecap="round"/></g>'
  +       '<g class="acc goggles"><path d="M20 34 H80" stroke="#7a5236" stroke-width="3.5"/><circle cx="38" cy="34" r="7.5" fill="#9fd7ff" stroke="#7a5236" stroke-width="2.4"/><circle cx="62" cy="34" r="7.5" fill="#9fd7ff" stroke="#7a5236" stroke-width="2.4"/><path d="M35 31 l3 -2 M59 31 l3 -2" stroke="#fff" stroke-width="1.6" stroke-linecap="round"/></g></g>'
  +     '<circle class="b-cheek" cx="28.5" cy="60" r="4.6"/><circle class="b-cheek" cx="71.5" cy="60" r="4.6"/>'
  +     '<g class="fx fx-blush"><path d="M24 58 l-2 4 M28 58 l-2 4 M32 58 l-2 4 M68 58 l-2 4 M72 58 l-2 4 M76 58 l-2 4" stroke="#ff7c93" stroke-width="1.5" stroke-linecap="round"/></g>'
  +     '<g class="fx fx-stream"><rect x="36.5" y="54" width="5" height="16" rx="2.5" fill="#8fd3ff" opacity=".85"/><rect x="58.5" y="54" width="5" height="16" rx="2.5" fill="#8fd3ff" opacity=".85"/></g>'
  +     '<path class="fx fx-kiss" d="M64 58 c-3 -2 -3 -5 -1 -5 c.8 0 1 .6 1 1 c0 -.4 .2 -1 1 -1 c2 0 2 3 -1 5z" fill="#ff6f91"/>'
  +     '<g class="acc scarf"><path d="M16 73 Q50 86 84 73 L85 81 Q50 94 15 81Z" fill="#6f9fe8"/><path d="M15 77 Q50 89 85 77" fill="none" stroke="#fff" stroke-opacity=".45" stroke-width="1.6" stroke-dasharray="3 3"/><rect x="62" y="80" width="10" height="17" rx="3.5" fill="#5d8bd6"/></g>'
  +     '<g class="acc bib"><path d="M31 69 Q50 80 69 69 L66 82 Q50 92 34 82Z" fill="#fff"/><path d="M50 81 c-3 -2 -3 -5 -1 -5 c.8 0 1 .6 1 1 c0 -.4 .2 -1 1 -1 c2 0 2 3 -1 5z" fill="#ff8fa3"/></g>'
  +     '<g class="acc book"><path d="M28 72 L50 76 L72 72 L72 88 L50 92 L28 88Z" fill="#fff" stroke="#7aa0d6" stroke-width="1.4"/><path d="M50 76 V92 M33 77 l13 2.5 M33 81 l13 2.5 M54 79 l13 -2.5 M54 83 l13 -2.5" stroke="#b9c9e6" stroke-width="1.1"/></g>'
  +   '</g>'
  // on its head
  +   '<g class="acc cap"><path d="M20 30 C22 6 56 -6 84 2 C74 6 70 12 72 22 C58 16 36 18 20 30Z" fill="#8f7cf0"/><path d="M20 30 C36 20 58 18 76 24" fill="none" stroke="#fff" stroke-width="4.5" stroke-linecap="round"/><circle cx="86" cy="3" r="5.5" fill="#fff"/></g>'
  +   '<g class="acc santa"><path d="M20 30 C22 6 56 -6 84 2 C74 6 70 12 72 22 C58 16 36 18 20 30Z" fill="#e5383b"/><path d="M20 30 C36 20 58 18 76 24" fill="none" stroke="#fff" stroke-width="5.5" stroke-linecap="round"/><circle cx="86" cy="3" r="5.5" fill="#fff"/></g>'
  +   '<g class="acc beanie"><path d="M17 31 C18 6 82 6 83 31Z" fill="#e8737a"/><rect x="15" y="25" width="70" height="9" rx="4.5" fill="#f3a5a5"/><path d="M30 12 v12 M42 8 v16 M58 8 v16 M70 12 v12" stroke="#d9606a" stroke-width="1.4"/><circle cx="50" cy="6" r="6.5" fill="#fff"/></g>'
  +   '<g class="acc hood"><path d="M9 54 C6 4 94 4 91 54 C86 36 72 29 50 29 C28 29 14 36 9 54Z" fill="#ffd23f"/><path d="M50 4 V29" stroke="#e8b923" stroke-width="1.4"/></g>'
  +   '<g class="acc straw"><path d="M30 24 C30 6 70 6 70 24Z" fill="#e8b84f"/><rect x="30" y="18" width="40" height="5" fill="#e3646a"/><ellipse cx="50" cy="24" rx="41" ry="7.5" fill="#f2c869"/><path d="M14 24 Q50 30 86 24" stroke="#d9a63e" stroke-width="1" fill="none"/></g>'
  +   '<g class="acc party"><path d="M50 -14 L36 24 Q50 28 64 24Z" fill="#7ad3c8"/><path d="M45 0 L58 8 M41 11 L61 19" stroke="#fff" stroke-width="2.4"/><circle cx="50" cy="-14" r="4" fill="#ffd36e"/><path d="M38 24 Q50 30 62 24" stroke="#3aa597" stroke-width="2" fill="none"/></g>'
  +   '<g class="acc muffs"><path d="M15 46 C13 10 87 10 85 46" fill="none" stroke="#c98bff" stroke-width="4" stroke-linecap="round"/><circle cx="12" cy="48" r="9" fill="#f4c6ff"/><circle cx="88" cy="48" r="9" fill="#f4c6ff"/></g>'
  +   '<g class="acc snowtop"><path d="M22 23 Q28 11 39 17 Q48 8 58 16 Q69 9 78 21 Q68 25 50 24 Q32 25 22 23Z" fill="#fff"/><circle cx="34" cy="17" r="1.4" fill="#dbe8ff"/><circle cx="62" cy="14" r="1.4" fill="#dbe8ff"/></g>'
  +   '<path class="acc leaf" d="M70 14 l3 -5 l2 4 l4 -2 l-1 5 l5 1 l-4 3 l2 4 l-5 -1 l-2 5 l-2 -5 l-4 2 l1 -5 l-4 -2 z" fill="#f08a3c"/>'
  +   '<g class="acc phones"><path d="M14 50 C12 14 88 14 86 50" fill="none" stroke="#3b3f52" stroke-width="5" stroke-linecap="round"/><rect x="5" y="40" width="13" height="22" rx="6" fill="#3b3f52"/><rect x="82" y="40" width="13" height="22" rx="6" fill="#3b3f52"/></g>'
  // in its hands, beside it
  +   '<g class="acc handfan"><g class="fan-blade"><path d="M92 50 L80 30 A22 22 0 0 1 104 30 Z" fill="#7ad3c8"/><path d="M92 50 L86 28 M92 50 L92 27 M92 50 L98 28" stroke="#fff" stroke-width="1.2"/></g></g>'
  +   '<g class="acc icecream"><path d="M4 50 L10 66 L16 50Z" fill="#e3a857"/><path d="M5 52 l9 0 M7 57 l6 0" stroke="#c98a3c" stroke-width="1"/><circle cx="10" cy="46" r="6.5" fill="#ffc3d8"/><circle cx="10" cy="39" r="5" fill="#bfe6c9"/></g>'
  +   '<g class="acc coffee"><rect x="0" y="56" width="13" height="13" rx="3" fill="#fff" stroke="#c7b8a8" stroke-width="1.2"/><path d="M13 59 q5 0 5 3.5 q0 3.5 -5 3.5" fill="none" stroke="#c7b8a8" stroke-width="1.6"/><path class="steam" d="M4 52 q-2 -3 0 -6 M9 52 q-2 -3 0 -6" stroke="#c9cbd6" stroke-width="1.3" fill="none" stroke-linecap="round"/></g>'
  +   '<g class="acc lunch"><path d="M-2 62 H20 Q19 74 9 74 Q-1 74 -2 62Z" fill="#7aa0d6"/><ellipse cx="9" cy="62" rx="11" ry="3" fill="#fff"/><path d="M15 50 L20 66" stroke="#b9bccb" stroke-width="2" stroke-linecap="round"/></g>'
  +   '<g class="acc pumpkin"><ellipse cx="92" cy="80" rx="10" ry="8" fill="#ff9b3d"/><path d="M86 74 q-2 6 0 12 M98 74 q2 6 0 12 M92 72 v16" stroke="#e07b1c" stroke-width="1.2" fill="none"/><path d="M92 72 q1 -5 4 -6" stroke="#5c8a3a" stroke-width="2" fill="none" stroke-linecap="round"/></g>'
  +   '<g class="acc luckybag"><path d="M0 70 Q-2 84 10 86 Q22 84 20 70 Q14 66 10 66 Q6 66 0 70Z" fill="#e5383b"/><path d="M3 68 Q10 72 17 68" stroke="#ffd36e" stroke-width="2" fill="none"/><circle cx="10" cy="78" r="3" fill="#ffd36e"/></g>'
  +   '<path class="acc bolt" d="M90 12 l-7 11 h5.5 l-3 10 l9 -13 h-5.5 l3.5 -8z" fill="#ffd34d" stroke="#e0a800" stroke-width=".8" stroke-linejoin="round"/>'
  +   '<path class="acc sweat" d="M84 26 q5 7 0 10.5 q-5 -3.5 0 -10.5z" fill="#8fd3ff"/>'
  +   '<g class="acc notes"><text class="note n1" x="88" y="22">♪</text><text class="note n2" x="2" y="30">♫</text></g>'
  +   '<text class="acc q" x="82" y="14">?</text>'
  +   '<g class="acc cookie"><circle cx="50" cy="75" r="7" fill="#d9a066"/><circle cx="47.5" cy="73" r="1.2" fill="#6b4226"/><circle cx="52.5" cy="76.5" r="1.2" fill="#6b4226"/><circle cx="51.5" cy="72" r="1" fill="#6b4226"/></g>'
  // a face's effects outside the face
  +   '<g class="fx fx-anger"><path d="M78 18 q3 3 6 0 M86 20 q-3 3 0 6 M84 28 q-3 -3 -6 0 M76 26 q3 -3 0 -6" stroke="#ff4d5e" stroke-width="2.2" fill="none" stroke-linecap="round"/></g>'
  +   '<path class="fx fx-sweat2" d="M18 28 q5 7 0 10.5 q-5 -3.5 0 -10.5z" fill="#8fd3ff"/>'
  +   '<text class="fx fx-bang" x="80" y="16">!</text><text class="fx fx-q2" x="80" y="16">?</text>'
  +   '<g class="fx fx-steam"><circle cx="8" cy="22" r="5" fill="#fff" opacity=".75"/><circle cx="3" cy="16" r="3.5" fill="#fff" opacity=".6"/><circle cx="92" cy="22" r="5" fill="#fff" opacity=".75"/><circle cx="97" cy="16" r="3.5" fill="#fff" opacity=".6"/></g>'
  +   '<g class="fx fx-notes"><text x="74" y="62">♪</text><text x="84" y="50">♫</text></g>'
  + '</g></svg>';

function makeBoard(slot) {
  const b = document.createElement("section"); b.className = "board";
  let stars = "";
  for (let i = 0; i < 14; i++) { const x = (i * 37 + 11) % 100, y = (i * 53 + 7) % 46, d = (i * 0.7) % 5; stars += '<i class="star" style="left:' + x + '%;top:' + y + '%;animation-delay:' + d.toFixed(1) + 's"></i>'; }
  b.innerHTML =
    '<div class="sky"><i class="blob b1"></i><i class="blob b2"></i><i class="blob b3"></i><div class="stars">' + stars + '</div></div>'
  + '<div class="wrap">'
  +   '<header class="hero"><div class="when"><div class="date"></div><div class="clock"></div><div class="hello"></div></div>'
  +     '<button class="buddy" aria-label="퍼레"><div class="bubble"></div><span class="think private"><span class="dots"><i></i><i></i><i></i></span><span class="n"></span></span><span class="zz">z</span><div class="body">' + BUDDY + '</div><div class="ground"></div></button><button class="care-btn" aria-label="퍼레 돌보기"></button><div class="care" role="dialog" aria-label="퍼레 돌보기"></div></header>'
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
  $(b, ".care-btn").addEventListener("click", e => { e.stopPropagation(); if (b._careOn) closeCare(b); else openCare(b); });
  const bubble = $(b, ".bubble");
  bubble.addEventListener("click", e => {
    e.stopPropagation();
    const go = bubble.dataset.go; bubble.classList.remove("on", "act");
    if (go && !lockedNow) (window.boardGo || (w => { location.href = "/do/" + encodeURIComponent(w); }))(go);
  });
  alive(b); roam(b);
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
// 퍼레's own lines when no AI answers: the little explorer and MC (준: "1+2인데 호칭은 내가 설정할 수 있게해줘"), {t} the title.
const OWN = {
  dawn: ["쉿, 새벽 탐험대 출동 중이에요. {t}도 몰래 합류?", "새벽의 휴대폰 마을은 고요해요… 발소리 살금살금", "별빛 BGM 깔고 오늘의 마지막 방송 갑니다"],
  morning: ["좋은 아침이에요 {t}! 오늘의 탐험 장비 점검 완료!", "두구두구… 오늘의 첫 하이라이트는 {t}의 기지개!", "아침 햇살로 식량 충전, 출발 준비 끝이에요"],
  lunch: ["점심 시간 속보! 배꼽시계가 정각을 알렸어요", "{t}, 오늘 점심 마을은 어디로 탐험할까요?", "짠! 오늘의 메뉴 추천 코너… 는 {t} 마음대로!"],
  afternoon: ["오후의 휴대폰 마을 날씨, 맑고 조금 졸림이에요", "{t}! 기지개 한 번이면 오후 탐험 체력 회복!", "뿅! 오후의 하이라이트 찾으러 순찰 중이에요"],
  evening: ["오늘 탐험 수고 많았어요 {t}, 진심 박수 짝짝!", "저녁 방송 시작합니다~ 오늘 어땠는지 사연 받아요", "노을 지는 마을 탐험 중… 오늘도 멋진 하루였어요"],
  night: ["텐트 칠 시간이에요. 오늘 탐험 일지 마감!", "오늘의 방송은 여기까지! 내일 신작으로 만나요", "{t}, 푹 쉬어요. 저는 불침번 설게요"]
};
function ownLines() {
  const h = new Date().getHours();
  const part = h < 5 ? "dawn" : h < 11 ? "morning" : h < 14 ? "lunch" : h < 18 ? "afternoon" : h < 22 ? "evening" : "night";
  // No how-to lines (준: "두 번 톡 치면 저랑 대화해요 같은 공지성 메세지 안 띄우기", "작동기능은 이미 내가 알고 있는데 굳이").
  const t = (info && info.title) || "대장님";
  return OWN[part].map(l => l.replaceAll("{t}", t));
}
function say(b) {
  const k = info || {}, theirs = Array.isArray(k.lines) && k.lines.length ? k.lines.slice() : null;
  const lines = theirs || ownLines();
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
  // Do not disturb: 퍼레 keeps its words for when it is asked.
  if (!asked && b._env && b._env.dnd) return;
  if (window.boardThink && (asked || now - thinkAt > 12000)) { thinkAt = now; boards.forEach(thinking); window.boardThink(why); return; }
  if (fallback) speak(b, {text: fallback}, 3600);
}

/*
  Touching 퍼레 (준: "퍼레 캐릭터 다양한 모션 애니메이션 표정 인터랙티브한 반응 등도 세세하게 구현 고도화"): a tap, it hops and thinks
  of something to say; two taps, the talk with it (준: "퍼레대화는 퍼레를 더블터치하면 열리는걸로"); more taps in a row, it gets
  dizzy; stroked back and forth, it is petted (hearts float up); held, it listens and talks back in its own voice (the home
  screen app's voice talk, 준: "퍼레 음성대화는 퍼레를 꾹 누르고 있으면 실행되게"; a tap ends it). The small heart by it opens its
  care card (on the wallpaper, holding it does too).
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
      if (window.boardVoice) { closeCare(b); if (b._home) b._home(); window.boardVoice(); } else openCare(b);
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
    if (voiceOn) { if (window.boardVoiceStop) window.boardVoiceStop(); return; }
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
    const own = Array.isArray(k.lines) && k.lines.length ? k.lines : ownLines();
    for (const t of own) lines.push({key: "line:" + today() + ":" + t, text: t, go: "", feel: "happy"});
  }
  return lines.find(l => !said(l.key)) || null;
}
function talk(opened) {
  if (!seenNow || !info || (info.env && info.env.dnd)) return;
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
  // Back from a short while in an app: now and then a word about it (the app knows which one).
  if (away > 20000 && away <= 90000 && Math.random() < .35 && Date.now() - (window._appTalkAt || 0) > 600000) { window._appTalkAt = Date.now(); setTimeout(() => boards.forEach(b => askThink(b, "app", "")), 900); }
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
/*
  퍼레's faces (준: "표정 움직임 너무 부족", "3배로 늘려줘"): each an eye, a mouth, brows and effects ("eye mouth brow fx …"),
  shown by data attributes on the svg (board.css). The old names stay (happy, love, sleepy …) and many more join them.
*/
const FACES = {
  happy: "arc smile none cheeks", laugh: "arc big none cheeks", wink: "wink smile", love: "heart smile none cheeks", surprised: "o osm",
  shock: "wide open none bang", sad: "small frown sad", teary: "small wave sad tears glint", cry: "shut open sad stream", dizzy: "dz wave",
  yum: "arc chew none cheeks", full: "arc wave", yawn: "shut yawn", drowsy: "half flat", sleepy: "shut none", shy: "small smile none blush cheeks",
  thinking: "pill flat", blow: "pill osm none cheeks", angry: "pill teeth angry anger", fume: "sq flat angry steam", proud: "u smile none cheeks",
  relief: "u smile none sweat2", content: "u cat none cheeks", cheeky: "wink tongue", kiss: "shut kiss none kiss blush", excited: "wide osm none glint",
  curious: "wide flat none q2", suspicious: "line smirk", blank: "dot flat", dazed: "dot osm", meh: "line flat", annoyed: "line teeth angry",
  nervous: "small teeth none sweat2", flustered: "wide wave none sweat2 blush", moved: "pill wave sad tears glint", determined: "pill flat angry",
  thrilled: "sq grin none cheeks", smug: "half smirk", sorry: "u frown sad sweat2", ko: "x wave", ouch: "sq teeth", smitten: "heart cat none blush",
  sneaky: "half smirk none sweat2", gloomy: "small flat sad gloom", idea: "wide osm none bang glow", sing: "u osm none notes", joyful: "arc big none tears cheeks",
  pout: "small pout angry", giggle: "arc cat none cheeks", amazed: "wide big none glint", sparkly: "pill smile none glint cheeks", grumpy: "line frown angry"
};
const FEELS = Object.keys(FACES);
/** Motions 퍼레 makes on its own (a class on .buddy for ms, with a face): see board.css "motions, more". */
const MOTIONS = [
  ["bounce", 1000, "happy"], ["jumpturn", 1100, "thrilled"], ["backflip", 1100, "excited"], ["frontflip", 1100, "excited"], ["hula", 3100, "sing"],
  ["moonwalk", 2100, "smug"], ["robot", 1700, "blank"], ["headbang", 2100, "thrilled"], ["clap", 1900, "happy"], ["salute", 1700, "proud"],
  ["point", 1500, "idea"], ["flex", 1700, "proud"], ["shrug", 1300, "meh"], ["facepalm", 1900, "sorry"], ["thinker", 2100, "curious"],
  ["cheer", 2100, "laugh"], ["boxing", 2100, "determined"], ["swim", 2500, "content"], ["airplane", 2300, "excited"], ["jelly", 1000, "giggle"],
  ["sneeze", 1300, "ouch"], ["hiccup", 1700, "dazed"], ["bow", 1700, "content"], ["hide", 2100, "shy"], ["ninja", 1500, "sneaky"],
  ["tiptoe", 2500, "sneaky"], ["skip", 2300, "happy"], ["roll", 1300, "dizzy"], ["tornado", 1500, "dizzy"], ["float", 3100, "content"],
  ["melt", 2500, "drowsy"], ["tall", 1700, "proud"], ["yoga", 3100, "relief"], ["jacks", 2600, "determined"], ["chop", 1000, "fume"],
  ["wavehands", 1500, "happy"], ["twirl", 2100, "giggle"], ["droop", 2300, "gloomy"], ["perk", 800, "surprised"], ["tremble", 1600, "nervous"],
  ["victory", 1300, "proud"], ["slide", 1700, "cheeky"], ["zoom", 1000, "amazed"], ["boing", 600, "sparkly"], ["rock", 3100, "sing"],
  ["leanin", 1700, "curious"], ["tumble", 1900, "ko"], ["doubletake", 1200, "shock"], ["blinkfast", 900, "flustered"]
];
/** Faces it pulls on its own now and then: [face, motion, gaze]. */
const IDLE_FACES = [["curious", "tilt", [5, -3]], ["smug"], ["giggle", "jelly"], ["sing", "rock"], ["content"], ["sparkly", "boing"], ["thinking", "", [4, -4.5]],
  ["cheeky", "wiggle"], ["meh"], ["proud", "tall"], ["dazed"], ["excited", "bounce"], ["suspicious", "", [-6, 0]], ["idea", "perk"], ["blank"], ["pout"]];
/** The face named (or the plain one): its eye, mouth, brows and effects on the svg. */
function applyFace(svg, name) {
  const f = (FACES[name] || "").split(" ");
  const set = (k, v) => { if (v && v !== "none" && v !== "pill") svg.dataset[k] = v; else delete svg.dataset[k]; };
  set("eye", f[0]); set("mouth", f[1]); set("brow", f[2]);
  const fx = f.slice(3).join(" "); if (fx) svg.dataset.fx = fx; else delete svg.dataset.fx;
}
function alive(b) {
  const svg = $(b, ".bot"), look = $(b, ".b-look"), buddy = $(b, ".buddy"), rnd = (a, z) => a + Math.random() * (z - a);
  let mood = "", feeling = "", feelTimer = 0;
  const show = () => { svg.classList.remove(...FEELS); const m = feeling || mood; if (m) svg.classList.add(m); applyFace(svg, m); buddy.classList.toggle("asleep", m === "sleepy"); };
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
      const e = b._env || {}, h = new Date().getHours(), opts = ["tilt", "stretch", "wiggle", "around", "wave", "nod", "face", "face", "face", "motion", "motion", "motion", "motion"];
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
      if (kind === "face") { const f = IDLE_FACES[Math.floor(Math.random() * IDLE_FACES.length)]; b._feel(f[0], 1800); if (f[1]) move(b, f[1], 1400); if (f[2]) gaze(f[2][0], f[2][1], 400); }
      else if (kind === "motion") { const m = MOTIONS[Math.floor(Math.random() * MOTIONS.length)]; move(b, m[0], m[1]); if (m[2]) b._feel(m[2], m[1]); }
      else if (kind === "around") { gaze(-6.5, 0, 380); setTimeout(() => gaze(6.5, 0, 520), 700); setTimeout(() => gaze(0, 0, 400), 1500); }
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
  (k.env: battery, charging, music, headphones, silent, offline, room left; k.weather; k.life: its heart, its memories): what it
  wears (at most two things: a nightcap at night, an umbrella in the rain, headphones to music through them, a scarf in the
  cold, sunglasses in hot sun), what shows by it (a bolt while it charges, sweat in the heat or a hot phone, notes to music, ?
  offline, sleepy eyes in power saving), and news it reacts to (plugged in: it eats; music: it dances; a hot phone; power
  saving; a low battery: it asks for a meal, at most every half hour). In do-not-disturb it speaks only when asked.
*/
function envOf(b, k) {
  const svg = $(b, ".bot"); if (!svg || !k) return;
  const e = k.env || {}, w = k.weather || {}, life = k.life || {}, h = new Date().getHours(), code = w.code, temp = w.temp;
  const rain = code != null && ((code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95);
  const snow = code != null && ((code >= 71 && code <= 77) || code === 85 || code === 86);
  const cold = (temp != null && temp <= 5) || snow, heat = (e.heat || 0) >= 41, hot = (temp != null && temp >= 28) || heat, night = h < 6;
  const sunny = code != null && code <= 1 && w.day !== false && temp != null && temp >= 24;
  const hungry = e.battery != null && e.battery <= 20 && !e.charging;
  b._env = {music: !!e.music, phones: !!e.phones, charging: !!e.charging, hungry, cold, hot, heat, rain, snow, offline: !!e.offline, heart: life.heart, night, battery: e.battery, roomFree: e.roomFree, silent: !!e.silent, save: !!e.powerSave, dnd: !!e.dnd, airplane: !!e.airplane};
  /*
    What it wears, one thing per place (head, face, neck, hand, body), from the weather, the hour, the season and the day, and
    from the phone (준: "3배로 늘려줘" for 차림): a nightcap at night, a Santa hat in late December, a beanie or earmuffs in the cold,
    a rain hood in drizzle and an umbrella in rain, a straw hat or sunglasses in hot sun, a party hat on Saturdays, a flower in
    spring, a leaf in autumn, snow on its head when it snows; a sleep mask in do-not-disturb, goggles in airplane mode; a bib
    while it charges (eats), a scarf in the cold; a coffee in the morning, lunch at noon, an ice cream or a fan in the heat, a
    book in the evening, a pumpkin at Halloween, a lucky bag at New Year; a blanket in power saving; a heart balloon when it
    is very fond of the user; a storm cloud in thunder.
  */
  const d = new Date(), month = d.getMonth() + 1, date = d.getDate(), day = d.getDay(), minutes = h * 60 + d.getMinutes();
  const drizzle = code != null && code >= 51 && code <= 57, storm = code != null && code >= 95, freezing = temp != null && temp <= 0, scorching = temp != null && temp >= 30;
  const head = night ? (month === 12 && date >= 20 ? "santa" : "cap") : e.music && e.phones ? "phones" : month === 12 && date >= 20 ? "santa"
    : snow ? "snowtop" : freezing ? "beanie" : cold ? "muffs" : drizzle ? "hood" : rain ? "" : hot && sunny ? "straw" : day === 6 ? "party"
    : month >= 3 && month <= 5 ? "flower" : month >= 9 && month <= 11 ? "leaf" : "";
  const face = e.dnd ? "mask" : e.airplane ? "goggles" : sunny && !night && head !== "straw" ? "shades" : "";
  const neck = e.charging ? "bib" : cold ? "scarf" : "";
  const hand = rain && !drizzle ? "umbrella" : night ? "" : month === 10 && date >= 25 ? "pumpkin" : month === 1 && date <= 3 ? "luckybag"
    : scorching ? (h >= 12 && h < 17 ? "icecream" : "handfan") : hot ? "handfan" : minutes >= 6 * 60 + 30 && minutes < 10 * 60 ? "coffee"
    : minutes >= 11 * 60 + 40 && minutes < 13 * 60 + 20 ? "lunch" : h >= 20 && h < 23 ? "book" : (life.heart || 0) >= 90 ? "balloon" : "";
  const body = e.powerSave ? "blanket" : "";
  const wear = [head, face, neck, hand, body, storm ? "storm" : ""].filter(Boolean);
  ["cap", "umbrella", "phones", "scarf", "shades", "santa", "beanie", "muffs", "hood", "straw", "party", "flower", "leaf", "snowtop", "mask", "goggles", "bib",
   "pumpkin", "luckybag", "icecream", "handfan", "coffee", "lunch", "book", "balloon", "blanket", "storm"].forEach(x => svg.classList.toggle("w-" + x, wear.includes(x)));
  svg.classList.toggle("w-bolt", !!e.charging); svg.classList.toggle("w-sweat", hot && !night);
  svg.classList.toggle("w-notes", !!e.music); svg.classList.toggle("w-q", !!e.offline);
  svg.classList.toggle("w-save", !!e.powerSave); svg.classList.toggle("w-dnd", !!e.dnd);
  const cb = $(b, ".care-btn"); if (cb) cb.style.setProperty("--fill", Math.max(8, Math.min(100, life.heart == null ? 55 : life.heart)) + "%");
  $(b, ".buddy").classList.toggle("lowpower", !!e.powerSave);
  const was = b._was; b._was = {charging: !!e.charging, music: !!e.music, heat, save: !!e.powerSave};
  if (!was || lockedNow) return;
  if (heat && !was.heat) { move(b, "wave", 1400); if (b._feel) b._feel("full", 1800); askThink(b, "heat", "휴대폰이 후끈해요, 저 땀나요"); return; }
  if (e.powerSave && !was.save) { if (b._feel) b._feel("yawn", 1700); askThink(b, "save", "절전 모드네요, 저도 살살 움직일게요"); return; }
  if (e.charging && !was.charging) { if (b._feel) b._feel("yum", 2200); move(b, "hop", 760); askThink(b, "charge", "냠냠, 충전이 제일 맛있어요"); }
  else if (e.music && !was.music) { move(b, "dance", 5200); if (b._feel) b._feel("happy", 5200); askThink(b, "music", "음악이다! 몸이 저절로 들썩여요"); }
  else if (hungry && seenNow && Date.now() - (b._hungryAt || 0) > 1800000) { b._hungryAt = Date.now(); move(b, "rumble", 1100); if (b._feel) b._feel("sad", 2000); askThink(b, "hungry", "꼬르륵… 충전해 주시면 힘이 날 것 같아요"); }
}
/** The app's word on 퍼레's life after a care act (launcher.life): its heart and memories, and how it took it. */
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
  The care card (the heart by 퍼레): its state only (준: "하트누르면 상태표시만 나오게해", "쓰다듬기 놀아주기 등은 없애줘", "그냥 내가
  터치로 상호작용하는거로 자체가 쓰다듬기 놀아주기 가능"): its heart (five hearts), its food (the battery, and whether it is
  eating), what it is up to now. Small, under 퍼레; a tap elsewhere or twelve quiet seconds close it. It does not grow up.
*/
function openCare(b) {
  if (lockedNow) return;
  if (b._home) b._home();
  const card = $(b, ".care"); $(b, ".bubble").classList.remove("on", "act");
  b._careOn = true; drawCare(b); card.classList.add("on");
  clearTimeout(b._careT); b._careT = setTimeout(() => closeCare(b), 12000);
  if (!card._wired) {
    card._wired = true;
    card.addEventListener("click", e => {
      e.stopPropagation(); clearTimeout(b._careT); b._careT = setTimeout(() => closeCare(b), 12000);
      const btn = e.target.closest("button"); if (!btn) return;
      if (btn.dataset.care === "close") { closeCare(b); return; }
    });
    addEventListener("pointerdown", e => { if (b._careOn && !e.target.closest(".care") && !e.target.closest(".buddy")) closeCare(b); }, {passive: true});
  }
}
function closeCare(b) { b._careOn = false; clearTimeout(b._careT); $(b, ".care").classList.remove("on"); }
function drawCare(b) {
  const k = info || {}, life = k.life || {}, e = b._env || {}, heart = life.heart == null ? 55 : life.heart;
  const full = Math.round(heart / 20), hearts = "♥".repeat(full) + '<s>' + "♥".repeat(5 - full) + '</s>';
  const food = e.battery == null ? "" : '<div class="care-row"><span class="k">밥</span><span class="meter' + (e.battery <= 20 && !e.charging ? " low" : "") + '"><i style="width:' + e.battery + '%"></i></span><span class="v">' + (e.charging ? "충전하며 냠냠" : e.battery <= 20 ? "배고파요" : e.battery >= 80 ? "배불러요" : "든든해요") + '</span></div>';
  const now = e.night ? "자는 시간이라 졸려요" : e.heat ? "휴대폰이 뜨거워서 땀이 나요" : e.music ? "음악에 맞춰 들썩이는 중" : e.charging ? "충전기에서 밥 먹는 중" : e.hungry ? "배터리가 적어서 배고파요" : e.dnd ? "방해 금지라 조용히 곁에 있어요" : e.save ? "절전 모드라 기운을 아끼는 중" : e.airplane ? "비행기 모드, 어디 가세요?" : e.rain ? "비가 와서 우산 썼어요" : e.cold ? "추워서 목도리 했어요" : e.hot ? "더워서 땀이 송골송골" : e.offline ? "인터넷이 끊겨서 갸우뚱" : heart >= 80 ? "기분 최고예요" : heart < 35 ? "조금 심심해요" : "편안하게 쉬는 중";
  $(b, ".care").innerHTML = '<div class="care-head"><b>' + esc(k.name || "퍼레") + '</b><span>내 휴대폰에 사는 친구</span><button class="care-x" data-care="close" aria-label="닫기">✕</button></div>'
    + '<div class="care-row"><span class="k">마음</span><span class="hearts">' + hearts + '</span></div>' + food
    + '<div class="care-row"><span class="k">지금</span><span class="v now">' + now + '</span></div>';
}

/*
  The phone moved (준: "퍼레가 내 휴대폰 환경에서 살아있는 ai 봇처럼 행동, 환경을 이해하고 반응"): tilted, 퍼레 leans with it;
  shaken, it gets dizzy and says so. Only the accelerometer (the cheapest sensor), smoothed; nothing while the page is hidden.
*/
(function motion() {
  let lean = 0, shown = 0, jolts = [], shakenAt = 0, raf = 0;
  addEventListener("devicemotion", e => {
    const a = e.accelerationIncludingGravity; if (!a || document.hidden || a.x == null) return;
    const now = Date.now(), m = Math.hypot(a.x || 0, a.y || 0, a.z || 0);
    // Tilt: the right side down reads as x below zero (the push against gravity points to the left edge).
    const want = Math.abs(a.x) < 1.2 ? 0 : -Math.asin(Math.max(-1, Math.min(1, a.x / 9.81))) * 57.3 * .4;
    lean += (Math.max(-14, Math.min(14, want)) - lean) * .12;
    if (!raf && Math.abs(lean - shown) > .4) raf = requestAnimationFrame(() => { raf = 0; shown = lean; boards.forEach(b => { const svg = $(b, ".bot"); if (svg) svg.style.transform = Math.abs(shown) < .6 ? "" : "rotate(" + shown.toFixed(1) + "deg)"; }); });
    if (m > 23) {
      jolts = jolts.filter(t => now - t < 900).concat(now);
      if (jolts.length >= 3 && now - shakenAt > 6000) {
        shakenAt = now; jolts = [];
        boards.forEach(b => { move(b, "wobble", 1700); if (b._feel) b._feel("dizzy", 2400); askThink(b, "shake", "으앗, 지진인 줄 알았어요… 어질어질"); });
        if (window.boardCare) window.boardCare("shake");
      }
    }
  }, {passive: true});
})();

/*
  퍼레's voice talk (held 퍼레, the home screen app's BuddyVoice): what it shows while it listens (a soft ring, what it hears
  so far in the bubble), thinks (the dots), speaks (the words as they are said, its mouth moving) and when it ends.
*/
let voiceOn = false;
function boardVoiceState(state, text) {
  boards.forEach(b => {
    const buddy = $(b, ".buddy"), bubble = $(b, ".bubble"), svg = $(b, ".bot");
    clearTimeout(b._bubble); clearInterval(b._typing);
    buddy.classList.toggle("listening", state === "listening");
    if (state !== "speaking") svg.classList.remove("talking");
    if (state === "listening") {
      voiceOn = true; if (b._mood) b._mood(""); if (b._feel) b._feel("", 1);
      bubble.classList.remove("act"); bubble.dataset.go = "";
      bubble.innerHTML = text ? '<span class="heard">' + esc(text) + '</span>' : '<span class="heard muted">듣고 있어요…</span>';
      bubble.classList.add("on");
    } else if (state === "thinking") {
      voiceOn = true; thinking(b); clearTimeout(b._bubble);
    } else if (state === "speaking") {
      voiceOn = true; if (b._mood) b._mood(""); if (b._feel) b._feel("happy", 1800);
      bubble.textContent = ""; bubble.classList.add("on"); svg.classList.add("talking");
      const chars = Array.from(text || ""); let i = 0;
      b._typing = setInterval(() => { i++; bubble.textContent = chars.slice(0, i).join(""); if (i >= chars.length) clearInterval(b._typing); }, 70);
    } else {
      voiceOn = false; if (b._mood) b._mood("");
      if (text) { speak(b, {text}, 3200); if (state === "done" && b._feel) b._feel("wink", 1400); }
      else bubble.classList.remove("on");
    }
  });
}

/*
  퍼레 plays on its own in the top right (준: "퍼레가 우상단 공간을 돌아다니면서 혼자 놀 수 있게해줘", "모션 갯수도 추가하고"): now
  and then it hops to another spot in the room between the clock widget and the screen's edge and plays there: bounces a ball
  and kicks it, chases a butterfly, blows soap bubbles, cartwheels, stands on its hands, peeks in from the edge, does its
  exercises, looks over at the clock, jumps for joy, dances, naps late in the evening. Not while it talks, listens, thinks,
  is held, sleeps at night or its card is open; a tap, its card or the voice talk bring it home first.
*/
function roam(b) {
  const buddy = $(b, ".buddy"), hero = $(b, ".hero"), cb = $(b, ".care-btn"), rnd = (a, z) => a + Math.random() * (z - a);
  let x = 0, y = 0, playing = false;
  // How far it may go: left to just past the widget's words, a little up and down.
  /*
    Its playground (준: "너무 오른쪽 구석에만 있어", "노란테두리영역을 활용해줘"): from the top of its corner down to just above App
    Library, and at each height left to whatever stands there (the clock widget, the prices, the small buttons), never over them.
  */
  const home = () => { const r = buddy.getBoundingClientRect(); return {left: r.left - x, top: r.top - y, w: r.width, h: r.height}; };
  const obstacles = () => Array.from(document.querySelectorAll(".when > *, .grid > .card, .mini-pill"))
    .filter(el => el.offsetParent && el.offsetWidth > 0 && !buddy.contains(el)).map(el => el.getBoundingClientRect());
  const floorOf = hm => { const lib = document.querySelector(".lib-head"); return lib && lib.offsetParent ? lib.getBoundingClientRect().top - hm.h - 2 : hm.top + 40; };
  const leftAt = (ty, hm, obs) => { let left = 14; for (const o of obs) if (o.bottom > ty + 10 && o.top < ty + hm.h - 10) left = Math.max(left, o.right + 10); return left; };
  /** A free spot in the playground, relative to its corner. */
  const spot = () => {
    const hm = home(), obs = obstacles(), lo = hm.top - 10, hi = Math.max(lo, floorOf(hm));
    for (let i = 0; i < 8; i++) {
      const ty = rnd(lo, hi), left = leftAt(ty, hm, obs);
      if (left < hm.left - 20) return {x: Math.round(rnd(left, hm.left) - hm.left), y: Math.round(ty - hm.top)};
    }
    return {x: 0, y: 0};
  };
  /** How far left it may go at its height now, and how far up and down. */
  const room = () => { const hm = home(), obs = obstacles(); return {min: Math.min(0, Math.round(leftAt(hm.top + y, hm, obs) - hm.left)), up: -10, down: Math.max(0, Math.round(floorOf(hm) - hm.top))}; };
  const busy = () => document.hidden || voiceOn || b._careOn || lockedNow || buddy.classList.contains("squish") || buddy.classList.contains("asleep")
    || $(b, ".bubble").classList.contains("on") || $(b, ".bot").classList.contains("thinking");
  const place = ms => {
    const t = "translate(" + x.toFixed(0) + "px," + y.toFixed(0) + "px)";
    buddy.style.transition = cb.style.transition = "transform " + ms + "ms cubic-bezier(.45,.1,.55,.9)";
    buddy.style.transform = cb.style.transform = t;
  };
  const walk = (nx, ny, done, how) => {
    const dist = Math.hypot(nx - x, ny - y);
    if (dist < 4) { if (done) done(); return; }
    const ms = Math.round(Math.max(450, dist / 75 * 1000));
    buddy.style.setProperty("--face", nx < x ? "-1" : "1");
    buddy.classList.add(how || "walking"); x = nx; y = ny; place(ms);
    setTimeout(() => { buddy.classList.remove(how || "walking"); if (done) done(); }, ms);
  };
  const toy = (cls, html, ms) => { const t = document.createElement("i"); t.className = "toy " + cls; if (html) t.innerHTML = html; buddy.appendChild(t); setTimeout(() => t.remove(), ms); return t; };
  const feel = (f, ms) => { if (b._feel) b._feel(f, ms); };
  const gazeTo = (gx, gy) => { const look = $(b, ".b-look"); look.style.transition = "transform 260ms ease"; look.style.transform = "translate(" + gx + "px," + gy + "px)"; };
  const PLAYS = {
    ball: end => { toy("ball", "", 2900); [0, 600, 1200].forEach((t, i) => setTimeout(() => { gazeTo(0, -4.5); setTimeout(() => gazeTo(0, 1.5), 300); }, t)); setTimeout(() => { move(b, "hop", 760); feel("laugh", 1200); }, 1900); setTimeout(() => { gazeTo(0, 0); end(); }, 2900); },
    butterfly: end => { toy("fly", '<b></b><b></b>', 4200); const path = [[-6, -4], [5, -5], [-4, -2], [6, -4.5], [0, -6]]; path.forEach((g, i) => setTimeout(() => gazeTo(g[0], g[1]), i * 800)); setTimeout(() => move(b, "hop", 760), 1600); setTimeout(() => move(b, "hop", 760), 3000); setTimeout(() => { feel("surprised", 900); }, 3600); setTimeout(() => { gazeTo(0, 0); end(); }, 4300); },
    bubbles: end => { feel("blow", 2600); for (let i = 0; i < 4; i++) setTimeout(() => { const t = toy("soap", "", 2600); t.style.setProperty("--dx", rnd(-26, 26).toFixed(0) + "px"); t.style.left = rnd(30, 60).toFixed(0) + "%"; }, 300 + i * 420); setTimeout(() => { feel("happy", 1200); end(); }, 3200); },
    cartwheel: end => { const r = room(), nx = x - 40 < r.min ? Math.min(0, x + 50) : x - 50; walk(nx, y, () => { feel("laugh", 1100); end(); }, "cartwheel"); },
    handstand: end => { move(b, "handstand", 2600); feel("laugh", 2600); setTimeout(end, 2700); },
    peek: end => walk(0, 0, () => { move(b, "peek", 2600); setTimeout(() => feel("surprised", 700), 1500); setTimeout(() => feel("laugh", 900), 2200); setTimeout(end, 2700); }),
    exercise: end => { move(b, "exercise", 2500); feel("happy", 2500); setTimeout(end, 2600); },
    clock: end => { const hm = home(), left = leftAt(hm.top - 4, hm, obstacles()); walk(Math.min(0, Math.round(left - hm.left)), -4, () => { buddy.style.setProperty("--face", "-1"); move(b, "tilt", 1300); gazeTo(-6.5, -2); setTimeout(() => { move(b, "nod", 900); }, 1300); setTimeout(() => { gazeTo(0, 0); end(); }, 2400); }); },
    jump: end => { move(b, "hop", 760); setTimeout(() => move(b, "hop", 760), 800); feel("happy", 1700); setTimeout(end, 1700); },
    dance: end => { move(b, "dance", 3600); feel("happy", 3600); setTimeout(end, 3700); },
    spin: end => { move(b, "spin", 950); feel("laugh", 1300); setTimeout(end, 1100); },
    nap: end => { buddy.classList.add("nap"); feel("yawn", 1500); setTimeout(() => { if (b._mood) b._mood("sleepy"); }, 1500); setTimeout(() => { buddy.classList.remove("nap"); if (b._mood) b._mood(""); move(b, "stretch", 1300); end(); }, 6500); },
    // more plays (준: "혼자놀기도 늘려"): a motion or two with a face, some with a toy or a thing to hold
    boxing: end => seq(end, [["boxing", 2100, "determined"], ["victory", 1300, "proud"]]),
    airplane: end => { const r = room(); feel("excited", 2400); buddy.classList.add("airplane"); const t = spot(); walk(t.x, t.y, () => { buddy.classList.remove("airplane"); end(); }); },
    swim: end => seq(end, [["swim", 2500, "content"], ["float", 3100, "relief"]]),
    moonwalk: end => seq(end, [["moonwalk", 2100, "smug"], ["salute", 1700, "proud"]]),
    robotdance: end => seq(end, [["robot", 1700, "blank"], ["robot", 1700, "blank"], ["boing", 600, "sparkly"]]),
    karate: end => seq(end, [["chop", 1000, "fume"], ["chop", 1000, "fume"], ["bow", 1700, "content"]]),
    yoga: end => seq(end, [["yoga", 3100, "relief"], ["float", 3100, "content"]]),
    jacks: end => seq(end, [["jacks", 2600, "determined"], ["flex", 1700, "proud"]]),
    cheerleader: end => seq(end, [["cheer", 2100, "laugh"], ["jumpturn", 1100, "thrilled"]]),
    hula: end => seq(end, [["hula", 3100, "sing"]]),
    rockstar: end => seq(end, [["headbang", 2100, "thrilled"], ["victory", 1300, "proud"]]),
    magic: end => seq(end, [["zoom", 1000, "amazed"], ["boing", 600, "sparkly"], ["bow", 1700, "proud"]]),
    ninja: end => { feel("sneaky", 1600); buddy.classList.add("ninja"); const t = spot(); walk(t.x, t.y, () => { buddy.classList.remove("ninja"); move(b, "hide", 2100); feel("shy", 2100); setTimeout(end, 2200); }, "tiptoe-walk"); },
    hideseek: end => seq(end, [["hide", 2100, "shy"], ["doubletake", 1200, "shock"], ["giggle", 0, "giggle"]]),
    skipping: end => { const t = spot(); feel("happy", 2600); walk(t.x, t.y, end, "skipping"); },
    rolling: end => { const r = room(); feel("dizzy", 1800); walk(x - 40 < r.min ? Math.min(0, x + 50) : x - 50, y, () => { feel("ko", 1000); setTimeout(end, 1000); }, "rolling"); },
    tornado: end => seq(end, [["tornado", 1500, "dizzy"], ["tremble", 1600, "dazed"]]),
    balloon: end => { const svg = $(b, ".bot"); svg.classList.add("w-balloon"); move(b, "float", 3100); feel("content", 3100); setTimeout(() => { svg.classList.remove("w-balloon"); envOf(b, info); end(); }, 3200); },
    juggling: end => { toy("jball b1", "", 3600); toy("jball b2", "", 3600); toy("jball b3", "", 3600); feel("determined", 3600); const t = setInterval(() => gazeTo(rnd(-4, 4), -4), 300); setTimeout(() => { clearInterval(t); gazeTo(0, 0); feel("proud", 1200); move(b, "bow", 1700); end(); }, 3700); },
    paperplane: end => { move(b, "point", 1500); feel("excited", 2500); setTimeout(() => { toy("plane", "", 2500); gazeTo(-6.5, -3); }, 600); setTimeout(() => { gazeTo(0, 0); end(); }, 3000); },
    kite: end => { toy("kite", "", 4600); feel("happy", 4600); move(b, "point", 1500); gazeTo(-4, -5); setTimeout(() => { gazeTo(0, 0); end(); }, 4600); },
    gum: end => { feel("blow", 2300); toy("gum", "", 2500); setTimeout(() => { feel("shock", 900); move(b, "doubletake", 1200); }, 1900); setTimeout(end, 3000); },
    meditate: end => { feel("relief", 3100); move(b, "float", 3100); setTimeout(() => { feel("content", 1200); end(); }, 3200); },
    singsong: end => seq(end, [["rock", 3100, "sing"], ["bow", 1700, "content"]]),
    reading: end => { const svg = $(b, ".bot"); svg.classList.add("w-book"); feel("content", 4000); gazeTo(0, 3.5); setTimeout(() => move(b, "nod", 900), 2000); setTimeout(() => { svg.classList.remove("w-book"); envOf(b, info); gazeTo(0, 0); end(); }, 4200); },
    coffeebreak: end => { const svg = $(b, ".bot"); svg.classList.add("w-coffee"); feel("relief", 3500); setTimeout(() => feel("content", 1500), 1800); setTimeout(() => { svg.classList.remove("w-coffee"); envOf(b, info); end(); }, 3600); },
    cloudwatch: end => { gazeTo(-3, -5); feel("curious", 3000); setTimeout(() => gazeTo(4, -5), 1400); setTimeout(() => { gazeTo(0, 0); feel("idea", 1200); move(b, "perk", 800); end(); }, 3000); },
    tumbling: end => seq(end, [["tumble", 1900, "ko"], ["shake", 800, "dazed"], ["boing", 600, "relief"]]),
    sneezing: end => seq(end, [["sneeze", 1300, "ouch"], ["hiccup", 1700, "dazed"]]),
    melting: end => seq(end, [["melt", 2500, "drowsy"], ["tall", 1700, "proud"]]),
    clapping: end => seq(end, [["clap", 1900, "happy"], ["bounce", 1000, "laugh"]])
  };
  // A few motions in a row, each with its face.
  const seq = (end, steps) => {
    let at = 0;
    const next = () => { if (at >= steps.length) { end(); return; } const [m, ms, f] = steps[at++]; if (f) feel(f, Math.max(ms, 900)); if (ms) move(b, m, ms); setTimeout(next, (ms || 900) + 120); };
    next();
  };
  const play = () => {
    if (playing || busy()) return;
    const h = new Date().getHours(), e = b._env || {}, kinds = Object.keys(PLAYS).filter(k => k !== "nap");
    if (h >= 21 || h < 1 || e.save) kinds.push("nap", "nap");
    if (e.rain) ["butterfly", "kite", "paperplane", "cloudwatch"].forEach(k => kinds.splice(kinds.indexOf(k), 1));
    const kind = kinds[Math.floor(Math.random() * kinds.length)];
    playing = true;
    const here = () => { try { PLAYS[kind](() => { playing = false; }); } catch (err) { playing = false; } };
    // Most plays start somewhere new in the playground.
    if (Math.random() < .85 && !["peek", "clock", "airplane", "ninja", "skipping", "rolling", "cartwheel"].includes(kind)) { const t = spot(); walk(t.x, t.y, here); } else here();
    setTimeout(() => { playing = false; }, 16000);
  };
  const tick = () => { play(); setTimeout(tick, rnd(9000, 17000)); };
  setTimeout(tick, rnd(5000, 9000));
  // Out of its corner soon after the screen opens, and now and then a stroll between plays.
  setTimeout(() => { if (!busy() && !playing) { const t = spot(); walk(t.x, t.y); } }, 2200);
  const stroll = () => { if (!busy() && !playing && Math.random() < .6) { const t = spot(); walk(t.x, t.y); } setTimeout(stroll, rnd(14000, 26000)); };
  setTimeout(stroll, rnd(12000, 20000));
  // Home: back to its place (a tap, its card, the voice talk, the screen opened again).
  b._home = () => { if (x || y) { x = 0; y = 0; place(400); } };
  // One play by name, now (for a look at it; the home screen never calls it).
  b._play = kind => { if (PLAYS[kind] && !playing) { playing = true; PLAYS[kind](() => { playing = false; }); } };
  b._playNames = () => Object.keys(PLAYS);
  b._playNow = (kind, done) => PLAYS[kind](done || (() => {}));
  b._walk = (nx, ny) => walk(nx, ny);
  b._spot = () => spot();
  addEventListener("resize", () => b._home());
}

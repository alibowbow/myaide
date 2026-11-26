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
  + '<path class="u" d="M-5.5 -1.5 Q0 4.5 5.5 -1.5"/><path class="sq" d="M-4.5 -4.5 L3.5 0 L-4.5 4.5"/><path class="tear" d="M2.5 6 q2.6 4.2 0 6.2 q-2.6 -2 0 -6.2z"/>'
  // more eyes (준: "표정 … 100개"): a sharp caret, a tall oval, a heavy lid, a ring, a wavy line, a plus
  + '<path class="tri" d="M-5.5 2.5 L0 -4 L5.5 2.5"/><g class="oval"><ellipse rx="4.2" ry="7.2"/><circle class="w-gl" cx="-1.4" cy="-3.4" r="1.6"/></g>'
  + '<g class="lid"><path d="M-5.6 -1 A5.6 5.6 0 0 0 5.6 -1Z"/><path class="lid-line" d="M-6.4 -1.4 H6.4"/></g><circle class="ring" r="5"/>'
  + '<path class="wavy" d="M-6 0 q1.5 -2.4 3 0 t3 0 t3 0 t3 0"/><path class="plus" d="M-4.6 0 H4.6 M0 -4.6 V4.6"/>';
/*
  퍼레's parts. Faces (FACES) are made of an eye (pill, arc, round, shut, heart, spiral, x, dot, line, wide, u, squint …), a
  mouth (smile, open, frown, wavy, chewing, flat, grin, tongue, small o, cat, teeth, pout, kiss, smirk), brows and effects
  (tears, blush lines, an anger mark, sweat, "!", gloom, steam, a kiss heart, notes, "?"), picked by data attributes on the svg.
  What it wears (준: "내 휴대폰 공간안의 환경을 이해하고 그 공간에서 살아가는 다마고치 같은 느낌", "3배로 늘려줘") follows the weather,
  the hour, the day and the phone (envOf): each piece is hidden until its w- class is on.
*/
const BUDDY = '<svg class="bot" viewBox="0 0 100 100" aria-hidden="true"><defs><linearGradient id="botBody" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ff9e7a"/><stop offset="1" stop-color="#ff6640"/></linearGradient>'
  + '<radialGradient id="botShine" cx=".32" cy=".22" r=".55"><stop offset="0" stop-color="#fff" stop-opacity=".5"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient>'
  + '<linearGradient id="botPale" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#7b8cff" stop-opacity=".45"/><stop offset=".55" stop-color="#7b8cff" stop-opacity="0"/></linearGradient>'
  + '<clipPath id="botClip"><rect x="12" y="18" width="76" height="70" rx="30"/></clipPath></defs>'
  + '<g class="b-all">'
  // behind 퍼레: what it holds high or that floats over it
  +   '<g class="acc umbrella"><path d="M86 66 L60 -6" stroke="#5b6470" stroke-width="2.4" stroke-linecap="round"/><path d="M86 66 q3 5 -2 6" fill="none" stroke="#5b6470" stroke-width="2.4" stroke-linecap="round"/>'
  +     '<path d="M24 6 C30 -16 84 -26 98 -2 Q90 -6 84 0 Q76 -8 68 -1 Q59 -8 50 1 Q41 -4 34 3 Q29 0 24 6Z" fill="#7cc4e8"/><path d="M61 -16 C58 -10 55 -3 50 1" fill="none" stroke="#5aa9d0" stroke-width="1.4"/></g>'
  +   '<g class="acc balloon"><path d="M90 60 Q96 30 92 4" fill="none" stroke="#9aa0b4" stroke-width="1.2"/><path d="M92 6 C80 -2 80 -16 88 -16 C91 -16 92 -14 92 -12 C92 -14 93 -16 96 -16 C104 -16 104 -2 92 6Z" fill="#ff6f91"/><path d="M86 -11 q2 -3 4 -2" stroke="#fff" stroke-opacity=".7" stroke-width="1.4" fill="none" stroke-linecap="round"/></g>'
  +   '<g class="acc storm"><path d="M30 -2 a10 10 0 0 1 14 -10 a12 12 0 0 1 22 2 a9 9 0 0 1 6 16 H34 a9 9 0 0 1 -4 -8z" fill="#8d93a8"/><path d="M52 6 l-4 8 h4 l-2 7 l7 -10 h-4 l3 -5z" fill="#ffd34d"/></g>'
  // more to wear, behind it (준: "모션 종류 4가지도 각각 100개씩"): a cape, a backpack, a rainbow, the moon
  +   '<path class="acc cape" d="M22 34 Q2 62 5 90 Q22 85 50 87 Q78 85 95 90 Q98 62 78 34Z" fill="#e5484d"/>'
  +   '<g class="acc backpack"><rect x="24" y="8" width="52" height="40" rx="14" fill="#5bc0eb"/><path d="M42 9 Q50 1 58 9" stroke="#3a9fcb" stroke-width="3" fill="none"/></g>'
  +   '<g class="acc rainbow" fill="none" stroke-width="3"><path d="M8 34 A42 42 0 0 1 92 34" stroke="#ff6f6f"/><path d="M12 34 A38 38 0 0 1 88 34" stroke="#ffd36e"/><path d="M16 34 A34 34 0 0 1 84 34" stroke="#7cc576"/><path d="M20 34 A30 30 0 0 1 80 34" stroke="#5bc0eb"/></g>'
  +   '<path class="acc moon" d="M88 -6 a9 9 0 1 0 8 13 a7 7 0 1 1 -8 -13z" fill="#ffe07a"/>'
  // Short round arms at its sides (준: "팔이 움직일 때 너무 덜렁덜렁 달린 느낌이야", "귀엽게", "팔이 약간 삼각모양인데 둥근형태"):
  // "와들디 팔을 참고", "팔이너무 귀같아", "닌텐도 커비처럼"): small soft ovals low on its sides, tipped down like arms at rest (at
  // mid-height they read as ears), half tucked behind the body, turning where they join it.
  +   '<g class="b-arm l"><g transform="rotate(-32 12 70)"><ellipse cx="9.5" cy="70" rx="9" ry="7" fill="#ff7a55"/><ellipse cx="10" cy="73.4" rx="6.8" ry="3" fill="#e5532f" opacity=".32"/><ellipse cx="6.8" cy="67.4" rx="3" ry="1.9" fill="#fff" opacity=".3"/><g class="acc mittens"><ellipse cx="9.5" cy="70" rx="9.2" ry="7.2" fill="#6f9fe8"/><rect x="13.2" y="63" width="3.4" height="14" rx="1.7" fill="#fff"/></g></g></g>'
  +   '<g class="b-arm r"><g transform="rotate(32 88 70)"><ellipse cx="90.5" cy="70" rx="9" ry="7" fill="#ff7a55"/><ellipse cx="90" cy="73.4" rx="6.8" ry="3" fill="#e5532f" opacity=".32"/><ellipse cx="93.2" cy="67.4" rx="3" ry="1.9" fill="#fff" opacity=".3"/><g class="acc mittens"><ellipse cx="90.5" cy="70" rx="9.2" ry="7.2" fill="#6f9fe8"/><rect x="83.4" y="63" width="3.4" height="14" rx="1.7" fill="#fff"/></g></g></g>'
  +   '<g class="b-antenna"><line x1="50" y1="20" x2="50" y2="8.5" stroke="#ff6640" stroke-width="4" stroke-linecap="round"/><circle class="b-bulb" cx="50" cy="7" r="5.2"/>'
  +     '<g class="acc flower"><circle cx="50" cy="1.5" r="3.4" fill="#ffb3c8"/><circle cx="55" cy="5.5" r="3.4" fill="#ffb3c8"/><circle cx="53" cy="11" r="3.4" fill="#ffb3c8"/><circle cx="47" cy="11" r="3.4" fill="#ffb3c8"/><circle cx="45" cy="5.5" r="3.4" fill="#ffb3c8"/><circle cx="50" cy="7" r="2.6" fill="#ffd36e"/></g></g>'
  +   '<g class="b-body"><rect x="12" y="18" width="76" height="70" rx="30" fill="url(#botBody)"/><rect x="12" y="18" width="76" height="70" rx="30" fill="url(#botShine)"/>'
  +     '<g class="acc blanket" clip-path="url(#botClip)"><path d="M11 60 Q50 52 89 60 L90 90 Q50 98 10 90Z" fill="#9fc8ff"/><g fill="#fff" opacity=".6"><circle cx="24" cy="70" r="2"/><circle cx="40" cy="78" r="2"/><circle cx="58" cy="70" r="2"/><circle cx="74" cy="80" r="2"/><circle cx="32" cy="86" r="2"/><circle cx="66" cy="88" r="2"/></g></g>'
  +     '<g class="acc raincoat" clip-path="url(#botClip)"><path d="M12 62 Q50 56 88 62 L90 90 Q50 98 10 90Z" fill="#ffd23f"/><path d="M50 60 V95" stroke="#e8b923" stroke-width="1.4"/><circle cx="46" cy="74" r="1.4" fill="#e8b923"/><circle cx="46" cy="84" r="1.4" fill="#e8b923"/></g>'
  +     '<g class="acc sweater" clip-path="url(#botClip)"><path d="M12 64 Q50 58 88 64 L89 90 Q50 98 11 90Z" fill="#8fb7e8"/><path d="M14 72 Q50 66 86 72 M13 82 Q50 76 87 82" fill="none" stroke="#fff" stroke-width="2" stroke-dasharray="3 3"/></g>'
  +     '<g class="acc apron" clip-path="url(#botClip)"><path d="M30 64 H70 L72 92 Q50 96 28 92Z" fill="#fff" stroke="#e3e5ee" stroke-width="1"/><rect x="40" y="76" width="20" height="9" rx="2" fill="none" stroke="#ff8fa3" stroke-width="1.4"/></g>'
  +     '<g class="acc pajamas" clip-path="url(#botClip)"><path d="M12 64 Q50 58 88 64 L89 90 Q50 98 11 90Z" fill="#b8c4ff"/><path d="M22 63 V94 M34 61 V96 M46 60 V97 M58 60 V97 M70 61 V96 M82 63 V93" stroke="#fff" stroke-width="2.4" opacity=".55"/></g>'
  +     '<g class="acc hoodie" clip-path="url(#botClip)"><path d="M12 64 Q50 58 88 64 L89 90 Q50 98 11 90Z" fill="#a78bfa"/><path d="M40 64 Q50 74 60 64" fill="none" stroke="#8b6ff0" stroke-width="2"/><path d="M44 66 V76 M56 66 V76" stroke="#fff" stroke-width="1.4" stroke-linecap="round"/><rect x="36" y="80" width="28" height="8" rx="3" fill="#8b6ff0"/></g>'
  +     '<g class="acc backpack" clip-path="url(#botClip)"><path d="M19 30 Q16 58 21 86 M81 30 Q84 58 79 86" stroke="#3a9fcb" stroke-width="4.5" fill="none" stroke-linecap="round"/></g>'
  +     '<rect x="22" y="31" width="56" height="37" rx="18" fill="#fff" opacity=".97"/>'
  +     '<rect class="fx fx-pale" x="22" y="31" width="56" height="37" rx="18" fill="url(#botPale)"/><rect class="fx fx-red" x="22" y="31" width="56" height="37" rx="18" fill="#ff6b6b" opacity=".16"/>'
  +     '<g class="fx fx-gloom"><path d="M32 33 v7 M40 33 v9 M48 33 v6 M56 33 v9 M64 33 v7" stroke="#8f8fd6" stroke-width="1.6" stroke-linecap="round" opacity=".7"/></g>'
  +     '<g class="b-look"><g class="b-eye l" transform="translate(39 48)">' + EYE + '</g><g class="b-eye r" transform="translate(61 48) scale(-1 1)">' + EYE + '</g>'
  +       '<path class="b-mouth" d="M45.5 60 Q50 64.5 54.5 60"/><ellipse class="m-open" cx="50" cy="61.5" rx="3.6" ry="3.2"/><path class="m-frown" d="M45.5 63 Q50 58.5 54.5 63"/>'
  +       '<path class="m-wave" d="M43.5 61 q1.6 -2 3.2 0 t3.2 0 t3.2 0 t3.2 0"/><path class="m-chew" d="M46 60.5 Q50 63.5 54 60.5 Q50 61.8 46 60.5Z"/>'
  +       '<path class="m-flat" d="M46 61 H54"/><path class="m-grin" d="M43.5 59 H56.5 Q56 66 50 66 Q44 66 43.5 59Z"/>'
  +       '<g class="m-tongue"><path d="M45.5 60 Q50 64.5 54.5 60" fill="none"/><path d="M48.2 62.2 q0 4 2.4 4 q2.4 0 2.2 -4.6z" fill="#ff7c93"/></g>'
  +       '<circle class="m-osm" cx="50" cy="61.5" r="1.9"/><path class="m-cat" d="M44.5 60 q2.75 3.2 5.5 0 q2.75 3.2 5.5 0"/>'
  +       '<g class="m-teeth"><rect x="44" y="58.6" width="12" height="5.6" rx="2"/><path d="M44 61.4 H56 M48 58.6 V64.2 M52 58.6 V64.2"/></g>'
  +       '<path class="m-pout" d="M48.5 59.5 q3 1.5 0 2.8 q3 1.4 0 2.8"/><path class="m-kiss" d="M49 59 q3 1.6 0 3 q3 1.6 0 3"/><path class="m-smirk" d="M46 61.5 Q51 62.5 55 58.8"/>'
  +       '<g class="m-drool"><path d="M45.5 60 Q50 64.5 54.5 60" fill="none"/><path d="M53.2 61.6 q1.6 3 0 4.6 q-1.6 -1.6 0 -4.6z" fill="#8fd3ff"/></g>'
  +       '<path class="m-tri" d="M45 58.5 H55 L50 66.5Z"/><path class="m-zig" d="M43 61.5 l2.4 -2 l2.4 2 l2.4 -2 l2.4 2 l2.4 -2 l2.4 2"/>'
  +       '<g class="m-bite"><path d="M45 61 H55" fill="none"/><path d="M47.6 61 v2.2 h2 v-2.2 M50.4 61 v2.2 h2 v-2.2" fill="#fff"/></g>'
  +       '<path class="m-wide" d="M41.5 58 H58.5 Q57.5 68 50 68 Q42.5 68 41.5 58Z"/><circle class="m-whistle" cx="53" cy="61" r="1.6"/>'
  +       '<path class="m-hat" d="M46.5 62.4 L50 59 L53.5 62.4"/><path class="m-tiny" d="M47.6 60.6 Q50 62.4 52.4 60.6"/>'
  +       '<g class="acc shades"><rect x="29" y="41" width="20" height="13" rx="5.5" fill="#23232e"/><rect x="51" y="41" width="20" height="13" rx="5.5" fill="#23232e"/><path d="M49 45 h2" stroke="#23232e" stroke-width="2"/><path d="M32 44 l5 -1.5" stroke="#fff" stroke-opacity=".5" stroke-width="1.6" stroke-linecap="round"/></g>'
  +       '<g class="acc mask"><rect x="23" y="39" width="54" height="16" rx="8" fill="#5b6bbf"/><path d="M31 47 q4 3 8 0 M61 47 q4 3 8 0" stroke="#fff" stroke-width="1.8" fill="none" stroke-linecap="round"/></g>'
  +       '<g class="acc goggles"><path d="M20 34 H80" stroke="#7a5236" stroke-width="3.5"/><circle cx="38" cy="34" r="7.5" fill="#9fd7ff" stroke="#7a5236" stroke-width="2.4"/><circle cx="62" cy="34" r="7.5" fill="#9fd7ff" stroke="#7a5236" stroke-width="2.4"/><path d="M35 31 l3 -2 M59 31 l3 -2" stroke="#fff" stroke-width="1.6" stroke-linecap="round"/></g></g>'
  +       '<g class="acc roundglasses"><circle cx="39" cy="48" r="8.5"/><circle cx="61" cy="48" r="8.5"/><path d="M47.5 47 Q50 45 52.5 47"/></g>'
  +       '<g class="acc heartglasses"><path d="M39 56 C27 48 28 39 34 39 C36.5 39 38.5 41 39 42.5 C39.5 41 41.5 39 44 39 C50 39 51 48 39 56Z"/><path d="M61 56 C49 48 50 39 56 39 C58.5 39 60.5 41 61 42.5 C61.5 41 63.5 39 66 39 C72 39 73 48 61 56Z"/><path d="M49.5 45 H50.5" stroke="#ff3f7f" stroke-width="2"/></g>'
  +       '<g class="acc monocle"><circle cx="61" cy="48" r="8" fill="#dff3ff" fill-opacity=".35" stroke="#d4a72c" stroke-width="1.8"/><path d="M69 50 Q74 62 70 74" fill="none" stroke="#d4a72c" stroke-width="1"/></g>'
  +       '<g class="acc swimgoggles"><path d="M22 48 H78" stroke="#ff6f91" stroke-width="3"/><ellipse cx="39" cy="48" rx="8.5" ry="7" fill="#7fd1ff" fill-opacity=".5" stroke="#ff6f91" stroke-width="2"/><ellipse cx="61" cy="48" rx="8.5" ry="7" fill="#7fd1ff" fill-opacity=".5" stroke="#ff6f91" stroke-width="2"/></g>'
  +       '<path class="acc mustache" d="M50 57.5 C46 53 38 54 36 59.5 C40 57.5 44 59.5 50 59.5 C56 59.5 60 57.5 64 59.5 C62 54 54 53 50 57.5Z" fill="#5a3b28"/>'
  +       '<g class="acc eyepatch"><path d="M24 37 L76 57" stroke="#26283a" stroke-width="1.8"/><ellipse cx="61" cy="48" rx="8" ry="7" fill="#26283a"/></g>'
  +       '<circle class="acc clownnose" cx="50" cy="55" r="4.2" fill="#ff3b3b"/>'
  +       '<g class="acc nerdglasses"><rect x="30" y="41" width="18" height="14" rx="3"/><rect x="52" y="41" width="18" height="14" rx="3"/><path d="M48 46 H52"/></g>'
  +     '<circle class="b-cheek" cx="28.5" cy="60" r="4.6"/><circle class="b-cheek" cx="71.5" cy="60" r="4.6"/>'
  +     '<g class="fx fx-blush"><path d="M24 58 l-2 4 M28 58 l-2 4 M32 58 l-2 4 M68 58 l-2 4 M72 58 l-2 4 M76 58 l-2 4" stroke="#ff7c93" stroke-width="1.5" stroke-linecap="round"/></g>'
  +     '<g class="fx fx-stream"><rect x="36.5" y="54" width="5" height="16" rx="2.5" fill="#8fd3ff" opacity=".85"/><rect x="58.5" y="54" width="5" height="16" rx="2.5" fill="#8fd3ff" opacity=".85"/></g>'
  +     '<path class="fx fx-kiss" d="M64 58 c-3 -2 -3 -5 -1 -5 c.8 0 1 .6 1 1 c0 -.4 .2 -1 1 -1 c2 0 2 3 -1 5z" fill="#ff6f91"/>'
  +     '<g class="acc scarf"><path d="M16 73 Q50 86 84 73 L85 81 Q50 94 15 81Z" fill="#6f9fe8"/><path d="M15 77 Q50 89 85 77" fill="none" stroke="#fff" stroke-opacity=".45" stroke-width="1.6" stroke-dasharray="3 3"/><rect x="62" y="80" width="10" height="17" rx="3.5" fill="#5d8bd6"/></g>'
  +     '<g class="acc bib"><path d="M31 69 Q50 80 69 69 L66 82 Q50 92 34 82Z" fill="#fff"/><path d="M50 81 c-3 -2 -3 -5 -1 -5 c.8 0 1 .6 1 1 c0 -.4 .2 -1 1 -1 c2 0 2 3 -1 5z" fill="#ff8fa3"/></g>'
  +     '<g class="acc book"><path d="M28 72 L50 76 L72 72 L72 88 L50 92 L28 88Z" fill="#fff" stroke="#7aa0d6" stroke-width="1.4"/><path d="M50 76 V92 M33 77 l13 2.5 M33 81 l13 2.5 M54 79 l13 -2.5 M54 83 l13 -2.5" stroke="#b9c9e6" stroke-width="1.1"/></g>'
  +     '<g class="acc bowtie"><path d="M50 77 L40 71 L40 83Z M50 77 L60 71 L60 83Z" fill="#e5484d"/><circle cx="50" cy="77" r="2.6" fill="#c23a3f"/></g>'
  +     '<g class="acc pearls" fill="#fff" stroke="#e2dbe8" stroke-width=".6"><circle cx="28" cy="71" r="2.2"/><circle cx="33" cy="75" r="2.2"/><circle cx="38.5" cy="78" r="2.2"/><circle cx="44" cy="79.6" r="2.2"/><circle cx="50" cy="80.2" r="2.4"/><circle cx="56" cy="79.6" r="2.2"/><circle cx="61.5" cy="78" r="2.2"/><circle cx="67" cy="75" r="2.2"/><circle cx="72" cy="71" r="2.2"/></g>'
  +     '<g class="acc medal"><path d="M42 68 L50 80 L58 68" fill="none" stroke="#e5484d" stroke-width="3"/><circle cx="50" cy="83" r="5.5" fill="#ffcf3f" stroke="#e0a800" stroke-width="1.2"/><path d="M50 80.4 v5.2" stroke="#e0a800" stroke-width="1"/></g>'
  +     '<g class="acc bell"><path d="M24 70 Q50 82 76 70" fill="none" stroke="#e5484d" stroke-width="3.4"/><circle cx="50" cy="80.5" r="4.6" fill="#ffcf3f" stroke="#e0a800" stroke-width="1"/><path d="M47 81.5 H53" stroke="#b8860b" stroke-width="1"/></g>'
  +     '<g class="acc lei"><circle cx="26" cy="70" r="3.2" fill="#ff6f91"/><circle cx="32" cy="75" r="3.2" fill="#ffd36e"/><circle cx="39" cy="78.5" r="3.2" fill="#7ad3c8"/><circle cx="46" cy="80" r="3.2" fill="#ff6f91"/><circle cx="54" cy="80" r="3.2" fill="#ffd36e"/><circle cx="61" cy="78.5" r="3.2" fill="#7ad3c8"/><circle cx="68" cy="75" r="3.2" fill="#ff6f91"/><circle cx="74" cy="70" r="3.2" fill="#ffd36e"/></g>'
  +     '<g class="acc neckbandana"><path d="M26 70 Q50 78 74 70 L50 90Z" fill="#3e7bd6"/><g fill="#fff"><circle cx="42" cy="76" r="1"/><circle cx="50" cy="79" r="1"/><circle cx="58" cy="76" r="1"/><circle cx="50" cy="85" r="1"/></g></g>'
  +     '<g class="acc ribbon"><path d="M50 74 L41 69 L41 79Z M50 74 L59 69 L59 79Z" fill="#ff7eb6"/><path d="M48 76 L45 85 M52 76 L55 85" stroke="#ff7eb6" stroke-width="2.4" stroke-linecap="round"/><circle cx="50" cy="74" r="2.4" fill="#ff5fa2"/></g>'
  +     '<g class="acc whistle"><path d="M30 68 Q50 78 70 68" fill="none" stroke="#5b8def" stroke-width="1.6"/><path d="M57 75 h9 v5 h-9 a2.5 2.5 0 0 1 0 -5z" fill="#c9ced9" stroke="#9aa3b5" stroke-width=".8"/></g>'
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
  // more on its head
  +   '<g class="acc beret" transform="rotate(-8 46 20)"><ellipse cx="46" cy="20" rx="30" ry="10" fill="#e5484d"/><path d="M20 22 Q46 30 74 22" fill="none" stroke="#c23a3f" stroke-width="2"/><rect x="45" y="6" width="3" height="5" rx="1.5" fill="#c23a3f"/></g>'
  +   '<g class="acc bucket"><path d="M28 22 Q30 6 50 6 Q70 6 72 22Z" fill="#f2d08a"/><path d="M14 26 Q50 16 86 26 Q86 31 80 31 Q50 23 20 31 Q14 31 14 26Z" fill="#e8bf6a"/><path d="M29 18 Q50 13 71 18" stroke="#c99b45" stroke-width="1.6" fill="none"/></g>'
  +   '<g class="acc crown"><path d="M30 24 L32 6 L41 16 L50 3 L59 16 L68 6 L70 24Z" fill="#ffcf3f" stroke="#e0a800" stroke-width="1.2" stroke-linejoin="round"/><circle cx="50" cy="17" r="2.6" fill="#ff5f8f"/><circle cx="38" cy="19" r="1.8" fill="#5bc0eb"/><circle cx="62" cy="19" r="1.8" fill="#5bc0eb"/></g>'
  +   '<g class="acc tiara"><path d="M28 24 Q50 14 72 24" fill="none" stroke="#d9dbe8" stroke-width="2.4"/><path d="M42 20 L46 12 L50 18 L54 12 L58 20" fill="none" stroke="#d9dbe8" stroke-width="2" stroke-linejoin="round"/><circle cx="50" cy="15" r="2.4" fill="#ff9ecb"/></g>'
  +   '<g class="acc bow" transform="translate(72 22) rotate(18)"><path d="M0 0 L-10 -6 L-10 6Z M0 0 L10 -6 L10 6Z" fill="#ff7eb6"/><circle r="3" fill="#ff5fa2"/></g>'
  +   '<path class="acc headband" d="M16 34 C18 10 82 10 84 34" fill="none" stroke="#7ad3c8" stroke-width="4.5" stroke-linecap="round"/>'
  +   '<g class="acc bandana"><path d="M16 30 C20 10 80 10 84 30 Q50 22 16 30Z" fill="#3e7bd6"/><g fill="#fff"><circle cx="34" cy="20" r="1.2"/><circle cx="50" cy="16" r="1.2"/><circle cx="66" cy="20" r="1.2"/><circle cx="42" cy="24" r="1"/><circle cx="58" cy="24" r="1"/></g><path d="M82 28 l9 4 l-6 5z" fill="#3e7bd6"/></g>'
  +   '<g class="acc chef"><rect x="32" y="12" width="36" height="14" rx="3" fill="#fff" stroke="#e3e5ee" stroke-width="1.2"/><circle cx="36" cy="8" r="8" fill="#fff"/><circle cx="50" cy="3" r="10" fill="#fff"/><circle cx="64" cy="8" r="8" fill="#fff"/><path d="M32 20 H68" stroke="#e3e5ee" stroke-width="1.2"/></g>'
  +   '<g class="acc grad"><path d="M28 22 Q50 30 72 22 V14 H28Z" fill="#2b2f45"/><path d="M50 2 L86 12 L50 22 L14 12Z" fill="#363b57"/><path d="M50 12 L80 16 V30" stroke="#ffcf3f" stroke-width="1.6" fill="none"/><circle cx="80" cy="31" r="2.2" fill="#ffcf3f"/></g>'
  +   '<g class="acc pirate"><path d="M14 26 Q20 4 50 4 Q80 4 86 26 Q50 16 14 26Z" fill="#26283a"/><path d="M20 24 Q50 15 80 24" stroke="#d9a63e" stroke-width="1.6" fill="none"/><circle cx="50" cy="12" r="3.4" fill="#fff"/><path d="M46.5 17.5 l7 -2 M46.5 15.5 l7 2" stroke="#fff" stroke-width="1.1"/></g>'
  +   '<g class="acc viking"><path d="M22 28 C22 8 78 8 78 28Z" fill="#9aa3b5"/><path d="M22 26 H78" stroke="#7d869a" stroke-width="3"/><path d="M24 18 C14 16 8 8 10 0 C14 8 20 10 26 12Z M76 18 C86 16 92 8 90 0 C86 8 80 10 74 12Z" fill="#f3ead2" stroke="#d8ccae" stroke-width="1"/></g>'
  +   '<g class="acc cowboy"><path d="M32 22 C30 6 40 4 50 8 C60 4 70 6 68 22Z" fill="#b07a45"/><path d="M8 22 Q14 30 50 28 Q86 30 92 22 Q88 18 80 22 Q50 26 20 22 Q12 18 8 22Z" fill="#9a6838"/><path d="M33 19 Q50 23 67 19" stroke="#6e4524" stroke-width="2.4" fill="none"/></g>'
  +   '<g class="acc tophat"><rect x="32" y="-6" width="36" height="28" rx="3" fill="#2b2b3a"/><rect x="32" y="13" width="36" height="5" fill="#e5484d"/><ellipse cx="50" cy="23" rx="28" ry="4.5" fill="#2b2b3a"/></g>'
  +   '<g class="acc visor"><path d="M17 32 C18 16 82 16 83 32" fill="none" stroke="#fff" stroke-width="4.5"/><path d="M26 30 Q50 22 74 30 Q50 39 26 30Z" fill="#5bc0eb"/></g>'
  +   '<g class="acc flowercrown"><path d="M18 30 Q50 6 82 30" fill="none" stroke="#7cc576" stroke-width="2"/><circle cx="22" cy="27" r="3.6" fill="#ff9ecb"/><circle cx="32" cy="19" r="3.6" fill="#ffd36e"/><circle cx="44" cy="15" r="3.6" fill="#c4b5fd"/><circle cx="56" cy="15" r="3.6" fill="#ff9ecb"/><circle cx="68" cy="19" r="3.6" fill="#ffd36e"/><circle cx="78" cy="27" r="3.6" fill="#c4b5fd"/><g fill="#fff"><circle cx="22" cy="27" r="1.3"/><circle cx="32" cy="19" r="1.3"/><circle cx="44" cy="15" r="1.3"/><circle cx="56" cy="15" r="1.3"/><circle cx="68" cy="19" r="1.3"/><circle cx="78" cy="27" r="1.3"/></g></g>'
  +   '<g class="acc sprout"><path d="M34 19 V10" stroke="#5aa95a" stroke-width="2" stroke-linecap="round"/><path d="M34 11 C28 11 26 6 27 4 C31 4 34 7 34 11Z M34 10 C40 9 42 4 41 2 C37 2 34 5 34 10Z" fill="#7cc576"/></g>'
  +   '<ellipse class="acc halo" cx="50" cy="-3" rx="16" ry="4" fill="none" stroke="#ffd94d" stroke-width="2.6"/>'
  +   '<path class="acc horns" d="M28 22 Q22 10 26 4 Q30 14 36 18Z M72 22 Q78 10 74 4 Q70 14 64 18Z" fill="#e5383b"/>'
  +   '<g class="acc witch"><path d="M34 22 L54 -16 Q58 -20 60 -14 L66 22Z" fill="#5b3fa0"/><ellipse cx="50" cy="23" rx="30" ry="5" fill="#4a3285"/><rect x="35" y="14" width="30" height="4" fill="#ffb347"/></g>'
  +   '<g class="acc bunny"><path d="M38 20 C30 -6 34 -14 38 -14 C44 -14 46 -2 44 20Z M62 20 C70 -6 66 -14 62 -14 C56 -14 54 -2 56 20Z" fill="#fff" stroke="#e8e0ec" stroke-width="1.2"/><path d="M39 16 C35 0 37 -8 39 -9 C41 -8 42 2 41 16Z M61 16 C65 0 63 -8 61 -9 C59 -8 58 2 59 16Z" fill="#ffc4d8"/></g>'
  +   '<g class="acc catears"><path d="M22 26 L24 4 L40 18Z M78 26 L76 4 L60 18Z" fill="#ff8a65"/><path d="M26 20 L27 10 L35 17Z M74 20 L73 10 L65 17Z" fill="#ffc4b0"/></g>'
  +   '<g class="acc froghat"><path d="M16 30 C16 6 84 6 84 30Z" fill="#7cc576"/><circle cx="34" cy="8" r="7" fill="#7cc576"/><circle cx="66" cy="8" r="7" fill="#7cc576"/><circle cx="34" cy="8" r="4" fill="#fff"/><circle cx="66" cy="8" r="4" fill="#fff"/><circle cx="34" cy="9" r="2" fill="#2b2b3a"/><circle cx="66" cy="9" r="2" fill="#2b2b3a"/></g>'
  +   '<g class="acc mushroom"><path d="M14 28 C14 2 86 2 86 28 Q50 22 14 28Z" fill="#e5484d"/><circle cx="32" cy="14" r="4" fill="#fff"/><circle cx="52" cy="9" r="5" fill="#fff"/><circle cx="70" cy="16" r="3.4" fill="#fff"/></g>'
  // in its hands, beside it
  +   '<g class="acc handfan"><g class="fan-blade"><path d="M92 50 L80 30 A22 22 0 0 1 104 30 Z" fill="#7ad3c8"/><path d="M92 50 L86 28 M92 50 L92 27 M92 50 L98 28" stroke="#fff" stroke-width="1.2"/></g></g>'
  +   '<g class="acc icecream"><path d="M4 50 L10 66 L16 50Z" fill="#e3a857"/><path d="M5 52 l9 0 M7 57 l6 0" stroke="#c98a3c" stroke-width="1"/><circle cx="10" cy="46" r="6.5" fill="#ffc3d8"/><circle cx="10" cy="39" r="5" fill="#bfe6c9"/></g>'
  +   '<g class="acc coffee"><rect x="0" y="56" width="13" height="13" rx="3" fill="#fff" stroke="#c7b8a8" stroke-width="1.2"/><path d="M13 59 q5 0 5 3.5 q0 3.5 -5 3.5" fill="none" stroke="#c7b8a8" stroke-width="1.6"/><path class="steam" d="M4 52 q-2 -3 0 -6 M9 52 q-2 -3 0 -6" stroke="#c9cbd6" stroke-width="1.3" fill="none" stroke-linecap="round"/></g>'
  +   '<g class="acc lunch"><path d="M-2 62 H20 Q19 74 9 74 Q-1 74 -2 62Z" fill="#7aa0d6"/><ellipse cx="9" cy="62" rx="11" ry="3" fill="#fff"/><path d="M15 50 L20 66" stroke="#b9bccb" stroke-width="2" stroke-linecap="round"/></g>'
  +   '<g class="acc pumpkin"><ellipse cx="92" cy="80" rx="10" ry="8" fill="#ff9b3d"/><path d="M86 74 q-2 6 0 12 M98 74 q2 6 0 12 M92 72 v16" stroke="#e07b1c" stroke-width="1.2" fill="none"/><path d="M92 72 q1 -5 4 -6" stroke="#5c8a3a" stroke-width="2" fill="none" stroke-linecap="round"/></g>'
  +   '<g class="acc luckybag"><path d="M0 70 Q-2 84 10 86 Q22 84 20 70 Q14 66 10 66 Q6 66 0 70Z" fill="#e5383b"/><path d="M3 68 Q10 72 17 68" stroke="#ffd36e" stroke-width="2" fill="none"/><circle cx="10" cy="78" r="3" fill="#ffd36e"/></g>'
  // more in its hands
  +   '<g class="acc lollipop"><path d="M10 72 V54" stroke="#fff" stroke-width="1.8"/><circle cx="10" cy="48" r="7" fill="#ff8fb3"/><path d="M10 48 m-4 0 a4 4 0 1 1 4 4" fill="none" stroke="#fff" stroke-width="1.4"/></g>'
  +   '<g class="acc popsicle"><rect x="5" y="40" width="10" height="16" rx="4" fill="#7ad3c8"/><rect x="5" y="40" width="10" height="6" rx="3" fill="#ff8fb3"/><rect x="8.8" y="56" width="2.4" height="8" fill="#e3c08a"/></g>'
  +   '<g class="acc watermelon"><path d="M-2 56 A12 12 0 0 0 22 56Z" fill="#ff5d73"/><path d="M-2 56 A12 12 0 0 0 22 56" fill="none" stroke="#3fa34d" stroke-width="2.4"/><g fill="#2b2b3a"><circle cx="5" cy="60" r=".9"/><circle cx="10" cy="62" r=".9"/><circle cx="15" cy="60" r=".9"/></g></g>'
  +   '<g class="acc sunflower"><path d="M92 72 V40" stroke="#5aa95a" stroke-width="2"/><circle cx="92" cy="34" r="9.5" fill="none" stroke="#ffcf3f" stroke-width="4" stroke-dasharray="3 2"/><circle cx="92" cy="34" r="7" fill="#ffcf3f"/><circle cx="92" cy="34" r="4" fill="#8a5a2b"/></g>'
  +   '<g class="acc tulip"><path d="M92 72 V48" stroke="#5aa95a" stroke-width="2"/><path d="M86 40 L88 48 Q92 52 96 48 L98 40 L95 44 L92 38 L89 44Z" fill="#ff6f91"/></g>'
  +   '<g class="acc dandelion"><path d="M92 72 V44" stroke="#8bbf6a" stroke-width="1.6"/><circle cx="92" cy="38" r="7" fill="#fff" fill-opacity=".85" stroke="#e8eef8" stroke-dasharray="1.5 1.5"/></g>'
  +   '<g class="acc magnifier"><circle cx="90" cy="44" r="7" fill="#dff3ff" fill-opacity=".5" stroke="#6b4f3a" stroke-width="2.4"/><path d="M90 51 V64" stroke="#6b4f3a" stroke-width="3" stroke-linecap="round"/></g>'
  +   '<g class="acc map"><path d="M-4 54 L4 50 L12 54 L20 50 V68 L12 72 L4 68 L-4 72Z" fill="#f3e3b5" stroke="#d6c28e" stroke-width="1"/><path d="M0 60 q5 -4 8 1 t9 -3" fill="none" stroke="#e5484d" stroke-width="1.2" stroke-dasharray="2 1.5"/></g>'
  +   '<g class="acc flashlight"><path d="M84 44 L72 18 H108 L96 44Z" fill="#fff6b0" opacity=".3"/><rect x="86" y="50" width="8" height="16" rx="2" fill="#5b6470"/><path d="M85 50 H95 L97 44 H83Z" fill="#8a93a3"/></g>'
  +   '<g class="acc lantern"><path d="M90 40 V34" stroke="#8a93a3" stroke-width="1.4"/><rect x="84" y="40" width="12" height="16" rx="3" fill="#ffd36e" stroke="#c98a3c" stroke-width="1.4"/><circle class="flame" cx="90" cy="48" r="3" fill="#fff6b0"/></g>'
  +   '<g class="acc gift"><rect x="-2" y="56" width="20" height="16" rx="2" fill="#e5484d"/><rect x="6" y="56" width="4" height="16" fill="#ffd36e"/><path d="M8 56 C2 48 -2 54 8 56 C18 54 14 48 8 56Z" fill="#ffd36e"/></g>'
  +   '<g class="acc wand"><path d="M88 68 L96 40" stroke="#c4b5fd" stroke-width="2.2" stroke-linecap="round"/><circle cx="96.5" cy="38" r="4" fill="#ff9ecb"/></g>'
  +   '<g class="acc mic"><rect x="88" y="52" width="4" height="16" rx="2" fill="#2b2b3a"/><circle cx="90" cy="48" r="5.5" fill="#9aa3b5"/><path d="M85.5 46 h9 M85.5 49 h9" stroke="#6f7787" stroke-width=".8"/></g>'
  +   '<g class="acc teddy"><circle cx="9" cy="64" r="8" fill="#c8915a"/><circle cx="9" cy="54" r="6" fill="#c8915a"/><circle cx="4.5" cy="49.5" r="2.4" fill="#c8915a"/><circle cx="13.5" cy="49.5" r="2.4" fill="#c8915a"/><circle cx="7" cy="54" r=".9" fill="#2b2b3a"/><circle cx="11" cy="54" r=".9" fill="#2b2b3a"/><circle cx="9" cy="56.5" r="1.2" fill="#6b4226"/></g>'
  +   '<g class="acc toothbrush"><rect x="89" y="40" width="3" height="26" rx="1.5" fill="#5bc0eb"/><rect x="86" y="37" width="9" height="6" rx="1.5" fill="#fff"/><path d="M84 35 q3 -3 6 0 q3 -3 6 0" fill="none" stroke="#bfe6ff" stroke-width="1.4"/></g>'
  +   '<g class="acc tea"><path d="M0 58 H14 V66 Q14 70 10 70 H4 Q0 70 0 66Z" fill="#fff" stroke="#c7b8a8" stroke-width="1.2"/><path d="M14 60 q4 0 4 3 q0 3 -4 3" fill="none" stroke="#c7b8a8" stroke-width="1.4"/><path d="M8 58 V51" stroke="#c7b8a8" stroke-width=".8"/><rect x="6" y="47" width="4" height="4" fill="#7cc576"/></g>'
  +   '<g class="acc cocoa"><rect x="0" y="56" width="14" height="14" rx="3" fill="#e5484d"/><path d="M14 59 q5 0 5 3.5 q0 3.5 -5 3.5" fill="none" stroke="#e5484d" stroke-width="1.8"/><ellipse cx="7" cy="56.5" rx="6" ry="1.8" fill="#7a4a2a"/><rect x="3" y="53" width="3" height="3" rx=".6" fill="#fff"/><rect x="7" y="54" width="3" height="3" rx=".6" fill="#ffd6e6"/></g>'
  +   '<g class="acc fishbread"><path d="M-2 62 Q6 52 16 58 L22 52 L21 62 L22 70 L16 64 Q6 72 -2 62Z" fill="#e0a052"/><circle cx="4" cy="60" r="1" fill="#6b4226"/><path d="M8 58 q2 2 0 4 M11 58 q2 2 0 4" stroke="#b97a32" stroke-width=".9" fill="none"/></g>'
  +   '<g class="acc sweetpotato"><ellipse cx="8" cy="62" rx="10" ry="6" fill="#9c4a6b" transform="rotate(-20 8 62)"/><path d="M2 59 q6 6 12 3" fill="none" stroke="#ffcf6e" stroke-width="2.4"/></g>'
  +   '<g class="acc riceball"><path d="M8 49 Q10 49 18 66 Q18 69 15 69 H1 Q-2 69 -2 66 Q6 49 8 49Z" fill="#fff" stroke="#e2e3ea" stroke-width="1"/><rect x="3" y="61" width="10" height="8" fill="#2f4a3a"/></g>'
  +   '<circle class="acc snowball" cx="9" cy="60" r="7" fill="#fff" stroke="#dbe8ff" stroke-width="1.2"/>'
  +   '<g class="acc flag"><path d="M88 70 V30" stroke="#8a93a3" stroke-width="1.8"/><path class="cloth" d="M88 31 H104 L100 37 L104 43 H88Z" fill="#5bc0eb"/></g>'
  +   '<g class="acc camera"><rect x="-3" y="56" width="22" height="15" rx="3" fill="#3b3f52"/><rect x="2" y="53" width="7" height="4" rx="1" fill="#3b3f52"/><circle cx="8" cy="63.5" r="5" fill="#9fd7ff" stroke="#c9ced9" stroke-width="1.6"/><circle cx="15.5" cy="59" r="1.2" fill="#ff6f6f"/></g>'
  +   '<g class="acc bubblewand"><path d="M90 70 L92 44" stroke="#c4b5fd" stroke-width="2" stroke-linecap="round"/><circle cx="92.5" cy="38" r="5.5" fill="none" stroke="#c4b5fd" stroke-width="1.8"/><circle class="pop1" cx="84" cy="26" r="3.4" fill="#dff3ff" fill-opacity=".45" stroke="#fff" stroke-width=".8"/><circle class="pop2" cx="96" cy="20" r="2.4" fill="#dff3ff" fill-opacity=".45" stroke="#fff" stroke-width=".8"/></g>'
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
  // more effects: Zz, hearts, flowers, a little fire, a sleep bubble, a swirl, shock lines, a rain cloud
  +   '<g class="fx fx-zzz"><text x="76" y="22">Z</text><text x="85" y="12">z</text></g>'
  +   '<g class="fx fx-hearts"><path d="M14 30 c-4 -2.6 -4 -6.4 -1.4 -6.4 c1 0 1.4 .8 1.4 1.4 c0 -.6 .4 -1.4 1.4 -1.4 c2.6 0 2.6 3.8 -1.4 6.4z"/><path d="M86 24 c-4 -2.6 -4 -6.4 -1.4 -6.4 c1 0 1.4 .8 1.4 1.4 c0 -.6 .4 -1.4 1.4 -1.4 c2.6 0 2.6 3.8 -1.4 6.4z"/></g>'
  +   '<g class="fx fx-flower"><g transform="translate(16 26)"><circle cx="0" cy="-3" r="2.4"/><circle cx="3" cy="0" r="2.4"/><circle cx="0" cy="3" r="2.4"/><circle cx="-3" cy="0" r="2.4"/><circle r="1.8" fill="#ffd36e"/></g><g transform="translate(84 30)"><circle cx="0" cy="-3" r="2.4"/><circle cx="3" cy="0" r="2.4"/><circle cx="0" cy="3" r="2.4"/><circle cx="-3" cy="0" r="2.4"/><circle r="1.8" fill="#ffd36e"/></g></g>'
  +   '<g class="fx fx-fire"><path d="M20 26 q-5 -6 0 -13 q1 5 4 6 q1 -4 -1 -7 q7 5 3 14z"/><path d="M80 26 q5 -6 0 -13 q-1 5 -4 6 q-1 -4 1 -7 q-7 5 -3 14z"/></g>'
  +   '<circle class="fx fx-bubble" cx="58" cy="57" r="3.4"/>'
  +   '<path class="fx fx-swirl" d="M72 10 m-1 0 a1 1 0 1 1 2 0 a2.6 2.6 0 1 1 -5.2 0 a4.2 4.2 0 1 1 8.4 0"/>'
  +   '<g class="fx fx-lines"><path d="M8 34 v12 M3 38 v6 M92 34 v12 M97 38 v6"/></g>'
  +   '<g class="fx fx-cloud"><path d="M36 2 a6 6 0 0 1 9 -6 a8 8 0 0 1 14 2 a6 6 0 0 1 4 10 H39 a6 6 0 0 1 -3 -6z" fill="#a5acc4"/><path d="M42 12 l-1.5 4 M50 12 l-1.5 4 M58 12 l-1.5 4" stroke="#8fd3ff" stroke-width="1.6" stroke-linecap="round"/></g>'
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
    const pick = e.target.closest ? e.target.closest(".pick") : null;
    if (pick) { answer(b, bubble.dataset.q || "", pick.textContent); return; }
    if (bubble.classList.contains("ask")) return; // a question waits for its answer
    const go = bubble.dataset.go; bubble.classList.remove("on", "act");
    if (go && !lockedNow) (window.boardGo || (w => { location.href = "/do/" + encodeURIComponent(w); }))(go);
  });
  alive(b); toys(b); roam(b);
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
  return h < 5 ? "조용한 새벽이에요" : h < 11 ? "좋은 아침이에요" : h < 14 ? "맛있는 점심 드세요" : h < 18 ? "느긋한 오후예요" : h < 22 ? "편안한 저녁이에요" : "고요한 밤이에요";
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
  dawn: ["쉿, 새벽 탐험대 출동 중이야. {t}도 몰래 합류할래?", "새벽의 휴대폰 마을은 조용해… 발소리 살금살금", "별빛 BGM 깔고 오늘 마지막 방송 간다"],
  morning: ["좋은 아침, {t}! 오늘 탐험 장비 점검 끝!", "두구두구… 오늘 첫 하이라이트는 {t}의 기지개!", "아침 햇살로 든든하게 충전, 출발 준비 끝"],
  lunch: ["점심 시간 속보! 배꼽시계가 딱 정각을 알렸어", "{t}, 오늘 점심은 어디로 탐험 갈까?", "짠! 오늘의 메뉴 추천은… {t} 마음대로!"],
  afternoon: ["오후의 휴대폰 마을은 맑음, 그리고 살짝 졸림", "뿅! 오후 순찰 출발!", "뿅! 오후 하이라이트 찾으러 순찰 중이야"],
  evening: ["노을 지는 마을 산책 중이야", "저녁 방송 시작~ 오늘 하루 어땠는지 들려줘", "노을 지는 마을 산책 중… 오늘도 멋진 하루였어"],
  night: ["텐트 칠 시간이야. 오늘 탐험 일지 마감!", "오늘 방송은 여기까지! 내일 또 만나", "오늘 밤 불침번은 내가 설게"]
};
function ownLines() {
  const h = new Date().getHours();
  const part = h < 5 ? "dawn" : h < 11 ? "morning" : h < 14 ? "lunch" : h < 18 ? "afternoon" : h < 22 ? "evening" : "night";
  // No how-to lines (준: "두 번 톡 치면 저랑 대화해요 같은 공지성 메세지 안 띄우기", "작동기능은 이미 내가 알고 있는데 굳이").
  const t = (info && info.title) || "대장님";
  return OWN[part].map(l => l.replaceAll("{t}", t));
}
/** A preset line for this moment (talk.js: by the hour, the day, the weather, the phone …); an app's kind for "app". */
function preset(moment, fitting) {
  if (typeof TALK === "undefined") return moment === "hi" ? ownLines()[Math.floor(Math.random() * 3)] : "";
  let m = moment;
  if (m === "app") { const kind = window.boardLastApp || ""; m = TALK.pools().includes("app:" + kind) ? "app:" + kind : "back"; }
  return emojied(TALK.pick(m, boards[0] && boards[0]._env, info, fitting), m);
}
/*
  An emoji now and then after 퍼레's own words (준: "이모지도 쓸 수 있게 해줘"): one that fits the moment, about one line in three,
  never on a line that has one already; no sparkles or stars.
*/
const EMOJI = {hi: ["👋", "😊", "🙌"], back: ["🤗", "😆", "🙌"], tap: ["😊", "😆", "✌️", "💜", "🙂"], poke: ["😵‍💫", "🌀", "😣"], pet: ["🥰", "💕", "☺️"],
  shake: ["😵", "🎢"], charge: ["🍚", "😋"], hungry: ["🥺", "🔋"], heat: ["🥵", "💦"], save: ["🐢", "😪"], music: ["🎵", "🎶", "🎧"], answer: ["😊", "👍", "💜", "😆"],
  roam: ["🐾", "👣"], done: ["😆", "👍"], solo: ["🎵", "😆", "🙌"], wake: ["😪", "🥱", "☀️"], app: ["😊", "👀"],
  "feel:joy": ["😆", "🥳"], "feel:laugh": ["🤣", "😂"], "feel:surprise": ["😲", "😮"], "feel:proud": ["😎", "💪"], "feel:calm": ["😌", "🍃"], "feel:dizzy": ["😵‍💫"],
  "feel:sleepy": ["😪", "💤"], "feel:shy": ["😳", "🙈"], "feel:curious": ["🤔", "👀"], "feel:sad": ["🥲", "😢"], "feel:cold": ["🥶"], "feel:hot": ["🥵"],
  "feel:love": ["💕", "🥰"], "feel:sneaky": ["🤫", "😏"], "feel:food": ["😋", "🍪"], "feel:ouch": ["🤕", "😣"], "feel:fume": ["😤"], "feel:sing": ["🎵", "🎶"]};
function emojied(text, moment) {
  if (!text || /\p{Extended_Pictographic}/u.test(text) || Math.random() > .35) return text;
  const list = EMOJI[String(moment || "").startsWith("app:") ? "app" : moment];
  return list ? text + " " + list[Math.floor(Math.random() * list.length)] : text;
}
function say(b) {
  const k = info || {}, theirs = Array.isArray(k.lines) && k.lines.length ? k.lines.slice() : null;
  // The AI's everyday lines (written ahead) first; a preset only when one fits this moment (rain, music, a holiday …),
  // else nothing (준: "터치할때 프리셋 문구를 너무 많이 뱉어", "상황에 맞게 적절히 꼭 필요할때 써야지").
  if (!theirs) return preset("tap", true);
  const lines = theirs;
  b._used = b._used && b._usedFor === lines.join("|") ? b._used : new Set(); b._usedFor = lines.join("|");
  let left = lines.filter(l => !b._used.has(l)); if (!left.length) { b._used.clear(); left = lines.slice(); }
  const pick = left[Math.floor(Math.random() * left.length)]; b._used.add(pick);
  return pick;
}
function cheer(b) {
  // A tap always gets a little motion and a face; words only when it has not spoken lately (askThink).
  const [m, ms] = [["hop", 760], ["hop", 760], ["boing", 600], ["nod", 900], ["jelly", 1000], ["giggleshake", 1400]][Math.floor(Math.random() * 6)];
  move(b, m, ms);
  if (b._feel) b._feel(["happy", "wink", "giggle", "cheeky", "sparkly", "chuckle"][Math.floor(Math.random() * 6)], 1500);
  askThink(b, "tap", null, true);
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
  // Words only now and then (준: "터치할때 프리셋 문구를 너무 많이 뱉어", "상황에 맞게 적절히 꼭 필요할때 써야지"): after words
  // lately, a tap, a stroke or a shake gets its motion and face alone.
  const hush = {tap: 25000, pet: 40000, poke: 15000, shake: 40000}[why] || 0;
  if (hush && now - (b._spoke || 0) < hush) return;
  // The AI or a preset (talk.js TALK.mix): a tap goes to the AI; quick reactions and the phone's moments mostly presets.
  const ai = !!window.boardThink && now - thinkAt > (asked ? 4000 : 12000);
  const way = typeof TALK === "undefined" ? (ai ? "ai" : "preset") : TALK.mix(why, ai);
  if (way === "preset") { const own = fallback || (why === "tap" ? say(b) : preset(why)); if (own) speak(b, {text: own}, 3600); return; }
  thinkAt = now; b._spoke = now;
  boards.forEach(thinking); window.boardThink(why);
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
  // Circled round twice with the finger that touched it: dizzy (the finger may leave it, the touch stays its own).
  const spin = circler(() => { const r = buddy.getBoundingClientRect(); return {x: r.left + r.width / 2, y: r.top + r.height / 2}; }, () => { if (down) { down.petted = true; quiet = Date.now(); clearTimeout(b._longT); } dizzy(b); });
  buddy.addEventListener("pointerdown", e => {
    try { buddy.setPointerCapture(e.pointerId); } catch (x) {}
    spin.down(e);
    down = {lx: e.clientX, dir: 0, turns: 0, path: 0, petted: false, minX: e.clientX, maxX: e.clientX, minY: e.clientY, maxY: e.clientY};
    clearTimeout(b._longT);
    b._longT = setTimeout(() => {
      if (!down || down.path > 16 || lockedNow) return;
      quiet = Date.now(); buddy.classList.add("squish"); if (navigator.vibrate) try { navigator.vibrate(12); } catch (x) {}
      if (window.boardVoice) { closeCare(b); if (b._home) b._home(); window.boardVoice(); } else openCare(b);
    }, 520);
  });
  buddy.addEventListener("pointermove", e => {
    if (!down) return;
    spin.move(e); if (down.petted && Date.now() - quiet < 300) return;
    const dx = e.clientX - down.lx; down.lx = e.clientX; down.path += Math.abs(dx);
    const dir = Math.abs(dx) > 1.5 ? Math.sign(dx) : 0;
    if (dir && down.dir && dir !== down.dir) down.turns++;
    if (dir) down.dir = dir;
    if (down.path > 16) clearTimeout(b._longT);
    down.minX = Math.min(down.minX, e.clientX); down.maxX = Math.max(down.maxX, e.clientX); down.minY = Math.min(down.minY, e.clientY); down.maxY = Math.max(down.maxY, e.clientY);
    // Stroked side to side (round and round is circling it: dizzy, above).
    if (!down.petted && (down.turns >= 2 || down.path > 120) && down.maxY - down.minY < (down.maxX - down.minX) * .5) { down.petted = true; quiet = Date.now(); pet(b); }
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
/**
  A finger circling round 퍼레 (준: "터치로 주변을 빙글빙글 돌리면 어지러워 한다던가"): two whole turns within four seconds, said
  to done; centre gives where 퍼레 is. Its down, move and up take the pointer's events.
*/
function circler(centre, done) {
  let last = null, sum = 0, start = 0;
  return {
    down() { last = null; sum = 0; start = Date.now(); },
    move(e) {
      const c = centre(), dx = e.clientX - c.x, dy = e.clientY - c.y;
      if (Math.hypot(dx, dy) < 14) return;
      const a = Math.atan2(dy, dx);
      if (last != null) { let d = a - last; if (d > Math.PI) d -= 2 * Math.PI; if (d < -Math.PI) d += 2 * Math.PI; sum += d; }
      last = a;
      if (Date.now() - start > 4000) { sum = 0; start = Date.now(); }
      if (Math.abs(sum) >= 4 * Math.PI) { sum = 0; start = Date.now(); done(); }
    },
    up() { last = null; }
  };
}
/** Poked again and again: spiral eyes, a wobble, and a word about it. */
function dizzy(b) {
  move(b, "wobble", 1700); if (b._feel) b._feel("dizzy", 2400);
  askThink(b, "poke");
}
/** Stroked: hearts float up, it leans into the hand, its heart grows. */
function pet(b) {
  hearts(b, 5); move(b, "nuzzle", 1900); if (b._feel) b._feel("love", 2400);
  if (window.boardCare) window.boardCare("pet");
  askThink(b, "pet");
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
  bubble.innerHTML = '<span class="typing"><i></i><i></i><i></i></span>'; bubble.dataset.go = ""; bubble.classList.remove("act", "sfx", "ask"); bubble.classList.add("on");
  if (b._mood) b._mood("thinking");
  clearTimeout(b._bubble); b._bubble = setTimeout(() => { if (!b._spoken) bubble.classList.remove("on"); }, 15000);
  b._spoken = false;
}
/** The line the AI wrote, typed into the bubble as it is said (the mouth moves while it types). */
function thought(said) {
  window._soloAsk = 0; window._answerAsk = 0;
  // A question comes with its choices on a second line ("고르기: 응 / 아니 / 글쎄").
  const parts = String(said || "").split("\n"), line = parts[0].trim();
  const given = (parts.slice(1).find(l => /^\s*고르기\s*[:：]/.test(l)) || "").replace(/^\s*고르기\s*[:：]/, "").split(/[\/|·]/).map(x => x.trim()).filter(x => x && x.length <= 12).slice(0, 3);
  const picks = asking(line) ? (given.length >= 2 ? given : choicesFor(line)) : [];
  boards.forEach(b => {
    b._spoke = Date.now();
    const bubble = $(b, ".bubble"), svg = $(b, ".bot"); b._spoken = true;
    if (b._mood) b._mood(""); if (b._feel) b._feel("happy", 1600);
    bubble.textContent = ""; bubble.classList.remove("sfx", "ask"); bubble.classList.add("on"); if (svg) svg.classList.add("talking");
    let i = 0; const chars = Array.from(line);
    clearInterval(b._typing);
    b._typing = setInterval(() => {
      i++; bubble.textContent = chars.slice(0, i).join("");
      if (i >= chars.length) { clearInterval(b._typing); if (svg) svg.classList.remove("talking"); clearTimeout(b._bubble); if (picks.length) offer(b, line, picks); else b._bubble = setTimeout(() => bubble.classList.remove("on"), 6500); }
    }, 38);
  });
}
/*
  퍼레's questions are answered with a tap (준: "퍼레가 가끔 나에게 말풍선으로 질문하는데 그럴경우에는 선택가능한 선택지를 주고 내가
  터치해서 고를 수 있게해줘"): two or three small choices under the question (the AI's own, or ones that fit the question), the
  bubble waiting for one (20 s); the one picked gets an answer (the AI, else 퍼레's own words) and is remembered.
*/
/** Whether a line asks the user something (not a sound like "오잉?"). */
function asking(line) { const t = String(line || "").replace(/[\p{Extended_Pictographic}\u200d\ufe0f]/gu, "").trim(); return /\?\s*[~!]*$/.test(t) && t.replace(/[^가-힣a-zA-Z0-9]/g, "").length >= 4; }
/** Choices that fit a question with none of its own. */
function choicesFor(q) {
  const t = String(q || "");
  if (/몇 ?점/.test(t)) return ["100점!", "70점쯤", "별로…"];
  if (/뭐 ?먹|메뉴|간식|배고/.test(t)) return ["밥!", "면!", "아직 몰라"];
  if (/누구게|뭐게|무슨 생각|맞혀|퀴즈/.test(t)) return ["궁금해!", "몰라~", "알려 줘!"];
  if (/어디/.test(t)) return ["집!", "밖이야", "비밀~"];
  if (/(할래|할까|어때|갈까|볼래|줄래|않을래|어떨까|좋을까)\s*\?/.test(t)) return ["좋아!", "다음에", "음… 글쎄"];
  if (/(했어|봤어|먹었어|잤어|마셨어|있었어|왔어|들었어|갔어)\s*\?/.test(t)) return ["응!", "아직", "비밀~"];
  return ["응!", "아니", "글쎄~"];
}
function offer(b, question, picks) {
  const bubble = $(b, ".bubble");
  const box = document.createElement("div"); box.className = "picks";
  picks.forEach(p => { const c = document.createElement("button"); c.className = "pick"; c.type = "button"; c.textContent = p; box.appendChild(c); });
  bubble.appendChild(box); bubble.dataset.q = question; bubble.classList.add("on", "ask");
  clearTimeout(b._bubble); b._bubble = setTimeout(() => bubble.classList.remove("on", "ask"), 20000);
}
/** A choice picked: 퍼레 hears it, answers (the AI, or its own words when there is none) and keeps it in mind. */
function answer(b, question, picked) {
  const bubble = $(b, ".bubble");
  bubble.classList.remove("ask"); bubble.textContent = picked; bubble.classList.add("mine");
  move(b, "hop", 760); if (b._feel) b._feel("happy", 1400);
  setTimeout(() => {
    bubble.classList.remove("mine");
    if (window.boardThink && !lockedNow) { window._answerAsk = Date.now(); thinkAt = Date.now(); boards.forEach(thinking); window.boardThink("answer:" + question.slice(0, 80) + "\u0001" + picked.slice(0, 20)); }
    else { const w = preset("answer"); speak(b, {text: w || "헤헤, 그렇구나!"}, 3600); }
  }, 700);
}
function thoughtFail() {
  // A line it wanted while playing alone: no line, no tap words in its place.
  if (Date.now() - (window._soloAsk || 0) < 30000) { window._soloAsk = 0; boards.forEach(b => { if (b._mood) b._mood(""); }); return; }
  if (Date.now() - (window._answerAsk || 0) < 30000) { window._answerAsk = 0; boards.forEach(b => { if (b._mood) b._mood(""); speak(b, {text: preset("answer") || "헤헤, 그렇구나!"}, 3600); }); return; }
  boards.forEach(b => { if (b._mood) b._mood(""); const line = say(b); if (line) speak(b, {text: line}, 3400); else $(b, ".bubble").classList.remove("on"); }); }
/** The bubble over the friend: words, and what a tap on it opens (if anything). */
function speak(b, line, ms) {
  b._spoke = Date.now();
  const bubble = $(b, ".bubble");
  bubble.textContent = line.text; bubble.dataset.go = line.go || "";
  if (line.go) { const more = document.createElement("b"); more.className = "more"; more.textContent = " ›"; bubble.appendChild(more); }
  bubble.classList.toggle("act", !!line.go); bubble.classList.toggle("sfx", !!line.sfx); bubble.classList.remove("ask"); bubble.classList.add("on");
  clearTimeout(b._bubble); b._bubble = setTimeout(() => bubble.classList.remove("on", "act"), ms || 6500);
  if (!line.sfx && !line.go && asking(line.text)) offer(b, line.text, choicesFor(line.text));
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
    if (min >= 0 && min <= 15) lines.push({key: "soon15:" + n.title + n.begin, text: (min <= 1 ? "곧" : min + "분 뒤") + " ‘" + n.title + "’ 시작이야" + (n.place ? " · " + n.place : ""), go: "calendar", feel: "surprised"});
  }
  if (opened) {
    // The hour's greeting from the presets, at most once in three hours (준: "저녁이라고 물론 맥락에 맞는 말을 한다지만 반복적인
    // 패턴이 있어"), or one of the AI's everyday lines.
    const theirs = Array.isArray(k.lines) && k.lines.length ? k.lines : [];
    let greeted = 0; try { greeted = +localStorage.getItem("gyeotHi") || 0; } catch (e) {}
    const hi = now - greeted > 3 * 3600000 ? preset("hi") : "";
    if (hi) try { localStorage.setItem("gyeotHi", String(now)); } catch (e) {}
    const own = hi ? [hi].concat(theirs) : theirs;
    for (const t of own) lines.push({key: "line:" + today() + ":" + t, text: t, go: "", feel: "happy"});
  }
  return lines.find(l => !said(l.key)) || null;
}
function talk(opened) {
  if (!seenNow || !info || (info.env && info.env.dnd)) return;
  // Asleep at dawn: it says its news once it is awake, not in its sleep.
  if (sleepHours() && boards.some(b => !awake(b))) return;
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
  // At dawn: asleep when the screen comes on, awake a few seconds later (its sleepy word in place of a greeting).
  if (sleepHours()) { boards.forEach(b => { if (!awake(b)) { if (b._mood) b._mood("sleepy"); wake(b); } }); return; }
  if (away > 90000 && window.boardCare) window.boardCare("back");
  // Back from a short while in an app: now and then a word about it (the app knows which one).
  if (away > 20000 && away <= 90000 && Math.random() < .35 && Date.now() - (window._appTalkAt || 0) > 600000) { window._appTalkAt = Date.now(); setTimeout(() => boards.forEach(b => askThink(b, "app", "")), 900); }
  if (away > 90000) setTimeout(() => {
    if (nextLine(info, before, false)) { talk(true); return; }
    // Coming back: the AI's greeting (at most every five minutes) or a preset one (talk.js "back").
    if (window.boardThink && Date.now() - (window._thoughtAt || 0) > 300000 && (typeof TALK === "undefined" || TALK.mix("back", true) !== "preset")) { window._thoughtAt = Date.now(); thinkAt = Date.now(); boards.forEach(thinking); window.boardThink("back"); }
    else if (seenNow && info && !(info.env && info.env.dnd) && Date.now() - lastSpoke > 45000) { const own = preset("back"); if (own) { lastSpoke = Date.now(); boards.forEach(b => { if (b._feel) b._feel("happy", 1600); speak(b, {text: own}, 5000); }); } else talk(true); }
    else talk(true);
  }, 900);
}
setTimeout(() => { if (sleepHours()) boards.forEach(b => { if (b._mood) b._mood("sleepy"); wake(b); }); else talk(true); }, 2200);

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
// Cute, not odd (준: "표정도 너무 이상한것보다 귀엽게"): no gritted teeth, zigzag or triangle mouths, X or ring eyes, fire, anger
// marks or a pale or red face; pouts with puffed cheeks, little round mouths, closed-eye wobbles and a drop of sweat instead.
const FACES = {
  happy: "arc smile none cheeks", laugh: "arc big none cheeks", wink: "wink smile", love: "heart smile none cheeks", surprised: "o osm",
  shock: "wide osm none glint", sad: "small frown sad", teary: "small wave sad tears glint", cry: "small wave sad tears", dizzy: "dz osm none cheeks",
  yum: "arc chew none cheeks", full: "arc wave", yawn: "shut yawn", drowsy: "half flat", sleepy: "shut none", shy: "small smile none blush cheeks",
  thinking: "pill flat", blow: "pill osm none cheeks", angry: "pill pout angry cheeks", fume: "pill pout angry cheeks", proud: "u smile none cheeks",
  relief: "u smile none sweat2", content: "u cat none cheeks", cheeky: "wink tongue", kiss: "shut kiss none kiss blush", excited: "wide osm none glint",
  curious: "wide osm none q2", suspicious: "half smirk none", blank: "pill osm", dazed: "dz osm", meh: "pill tiny none", annoyed: "pill pout angry",
  nervous: "small wave none sweat2", flustered: "wide wave none sweat2 blush", moved: "pill wave sad tears glint", determined: "pill flat angry",
  thrilled: "sq grin none cheeks", smug: "half smirk", sorry: "u frown sad sweat2", ko: "shut wave none", ouch: "shut pout none sweat2", smitten: "heart cat none blush",
  sneaky: "half smirk none sweat2", gloomy: "small tiny sad", idea: "wide osm none bang glow", sing: "u osm none notes", joyful: "arc big none tears cheeks",
  pout: "small pout angry", giggle: "arc cat none cheeks", amazed: "wide big none glint", sparkly: "pill smile none glint cheeks", grumpy: "small pout angry",
  // more faces (준: "모션 종류 4가지도 각각 100개씩"): new eyes (tri, oval, lid, ring, wavy, plus), mouths (drool, tri, zig, bite, wide,
  // whistle, hat, tiny) and effects (zzz, hearts, flower, fire, bubble, swirl, lines, cloud, pale, red)
  snooze: "shut none none zzz bubble", dreamy: "lid smile none hearts", awed: "oval big none glint", shout: "pill osm angry",
  panic: "wide wave none sweat2", woozy: "dz wave none cheeks", confused: "small flat none q2", drooling: "arc osm none cheeks",
  starving: "small osm sad", innocent: "u hat none", whistling: "half whistle none notes", bashful: "arc tiny none blush",
  embarrassed: "small wave none blush sweat2", furious: "pill pout angry cheeks", firedup: "pill smile angry glint", chilly: "small wave sad",
  queasy: "shut wave sad sweat2", overheated: "half wave none sweat2", lovey: "heart wide none hearts cheeks", blissful: "arc wide none flower cheeks",
  cheerful: "tri smile none cheeks", gleeful: "tri wide none cheeks", devious: "half cat none", bored: "lid flat none", unimpressed: "half tiny none",
  skeptical: "half smirk none", sulky: "small pout sad", heartbroken: "small wave sad tears", stunned: "wide osm none glint",
  speechless: "pill tiny none sweat2", eek: "wide osm sad sweat2", yikes: "wide wave sad sweat2", sly: "half cat none", sweetdream: "shut cat none zzz",
  nosy: "oval osm none q2", focused: "pill flat none", anxious: "small wave sad sweat2", cringe: "shut wave sad sweat2", triumphant: "arc big none cheeks flower",
  delighted: "wide wide none glint", lol: "arc big none cheeks", grateful: "u smile sad flower", zen: "lid tiny none", horrified: "wide osm sad sweat2",
  playful: "wink wide none cheeks", hopeful: "oval smile sad glint", lonely: "small tiny sad", awkward: "pill wave none sweat2", chuckle: "arc hat none cheeks",
  pleading: "wide frown sad glint tears"
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
  ["leanin", 1700, "curious"], ["tumble", 1900, "ko"], ["doubletake", 1200, "shock"], ["blinkfast", 900, "flustered"],
  // to a hundred (준: "모션 종류 4가지도 각각 100개씩")
  ["swaying", 3100, "content"], ["bobbing", 2100, "sing"], ["pogo", 1900, "gleeful"], ["squash", 1100, "giggle"], ["stomp", 1300, "pout"], ["shimmy",
  1800, "playful"], ["leanback", 1700, "lol"], ["crouch", 1900, "sneaky"], ["pounce", 1300, "firedup"], ["sprint", 2100, "determined"], ["shuffle",
  1700, "smug"], ["heartbeat", 1600, "lovey"], ["inflate", 1700, "blow"], ["shrink", 1700, "shy"], ["spinslow", 2100, "zen"], ["flipside", 1700,
  "dazed"], ["rockback", 2500, "content"], ["seesaw", 2500, "cheerful"], ["pendulum", 2500, "woozy"], ["levitate", 2500, "zen"], ["sink", 1900,
  "gloomy"], ["trip", 1500, "eek"], ["slip", 1600, "horrified"], ["shiverbig", 1700, "chilly"], ["lookaround", 2100, "curious"], ["eyeroll", 1300,
  "unimpressed"], ["lookup", 1700, "hopeful"], ["lookdown", 1700, "bashful"], ["giggleshake", 1400, "chuckle"], ["sob", 1900, "heartbroken"], ["pant",
  2200, "overheated"], ["stretchup", 1900, "relief"], ["armswing", 1900, "happy"], ["propeller", 2100, "excited"], ["flapping", 2100, "gleeful"],
  ["conductor", 2100, "sing"], ["drum", 1700, "firedup"], ["hugself", 2100, "blissful"], ["akimbo", 1900, "proud"], ["fistpump", 1700, "triumphant"],
  ["highfive", 1100, "delighted"], ["wavebye", 1700, "happy"], ["beckon", 1700, "sly"], ["shush", 1900, "innocent"], ["scratch", 1900, "confused"],
  ["clapfast", 1600, "thrilled"], ["antwiggle", 1600, "cheeky"], ["antboing", 1100, "surprised"], ["antscan", 2100, "nosy"], ["bodywave", 1300,
  "giggle"], ["zigzag", 1700, "excited"], ["orbit", 1700, "woozy"]
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
/*
  퍼레 sleeps at dawn (준: "새벽에는 퍼레가 잠도 잘 수 있게 해줘", "자다가 휴대폰 켜면 잠시 뒤 일어나게"): from midnight to seven it
  is asleep; the screen turned on, it sleeps on a few seconds, then wakes (a yawn, a stretch, a sleepy word); a touch wakes it
  sooner; left alone two minutes, it dozes off again.
*/
const sleepHours = () => new Date().getHours() < 7;
function awake(b) { return !!b._awakeAt && Date.now() - b._awakeAt < 120000; }
function wake(b, soon) {
  if (!b._mood || b._waking || awake(b)) return;
  b._waking = true;
  setTimeout(() => {
    b._waking = false; if (!sleepHours() || voiceOn) return;
    b._awakeAt = Date.now(); b._mood("");
    if (b._feel) b._feel("yawn", 1700); move(b, "stretch", 1300);
    setTimeout(() => { if (!$(b, ".bubble").classList.contains("on")) { const w = preset("wake"); if (w) speak(b, {text: w}, 3400); } }, 1500);
  }, soon ? 600 : 2500 + Math.random() * 2500);
}
function doze(b) { if (!b._mood) return; b._awakeAt = 0; if (b._feel) b._feel("drowsy", 1800); setTimeout(() => { if (!awake(b) && sleepHours()) b._mood("sleepy"); }, 1800); }
setInterval(() => { if (sleepHours() && seenNow && !voiceOn) boards.forEach(b => { if (b._awakeAt && !awake(b) && !b._careOn) doze(b); }); }, 15000);
// A touch: a sleeping 퍼레 wakes soon; an awake one stays awake a while longer.
addEventListener("pointerdown", () => { if (!sleepHours()) return; boards.forEach(b => { if (awake(b)) b._awakeAt = Date.now(); else wake(b, true); }); }, {passive: true, capture: true});
/** The friend's mood from what the app says (thinking, a new notice, dawn's sleep). */
function moodOf(b, k) {
  if (!b._mood) return;
  const more = (k.noticeCount || 0) > (b._notices == null ? Infinity : b._notices); b._notices = k.noticeCount || 0;
  if (more && b._feel && !(sleepHours() && !awake(b))) b._feel("surprised", 1400);
  b._mood(!k.locked && ((k.working || 0) > 0 || k.lab) ? "thinking" : sleepHours() && !awake(b) ? "sleepy" : "");
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
/** Everything 퍼레 can wear or hold (an acc in BUDDY, shown by its w- class): 100 with the phone's own signs. */
const WEAR = ["cap", "umbrella", "phones", "scarf", "shades", "santa", "beanie", "muffs", "hood", "straw", "party", "flower", "leaf", "snowtop", "mask", "goggles", "bib",
  "pumpkin", "luckybag", "icecream", "handfan", "coffee", "lunch", "book", "balloon", "blanket", "storm",
  "beret", "bucket", "crown", "tiara", "bow", "headband", "bandana", "chef", "grad", "pirate", "viking", "cowboy", "tophat", "visor", "flowercrown", "sprout", "halo",
  "horns", "witch", "bunny", "catears", "froghat", "mushroom", "roundglasses", "heartglasses", "monocle", "swimgoggles", "mustache", "eyepatch", "clownnose", "nerdglasses",
  "bowtie", "pearls", "medal", "bell", "lei", "neckbandana", "ribbon", "whistle", "lollipop", "popsicle", "watermelon", "sunflower", "tulip", "dandelion", "magnifier", "map",
  "flashlight", "lantern", "gift", "wand", "mic", "teddy", "toothbrush", "tea", "cocoa", "fishbread", "sweetpotato", "riceball", "snowball", "flag",
  "camera", "bubblewand", "raincoat", "sweater", "apron", "pajamas", "hoodie", "cape", "backpack", "mittens", "rainbow", "moon"];
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
    is very fond of the user (some days); a storm cloud in thunder.
  */
  const d = new Date(), month = d.getMonth() + 1, date = d.getDate(), day = d.getDay(), minutes = h * 60 + d.getMinutes();
  const drizzle = code != null && code >= 51 && code <= 57, storm = code != null && code >= 95, freezing = temp != null && temp <= 0, scorching = temp != null && temp >= 30;
  // To a hundred (준: "모션 종류 4가지도 각각 100개씩"): the day's own outfit on an ordinary day (the same all day, another tomorrow).
  const doy = Math.floor((d - new Date(d.getFullYear(), 0, 0)) / 86400000), weekend = day === 0 || day === 6;
  const pickDay = (list, salt, chance) => ((doy * 37 + salt * 53) % 100) < chance ? list[(doy * 11 + salt * 5) % list.length] : "";
  const sunnyMild = code != null && code <= 1 && w.day !== false && temp != null && temp >= 20 && temp < 28, april1 = month === 4 && date === 1;
  const lunchTime = minutes >= 11 * 60 + 40 && minutes < 13 * 60 + 20, summer = month >= 7 && month <= 8;
  const seasonal = month === 3 ? (doy % 2 ? "sprout" : "flower") : month >= 4 && month <= 5 ? (weekend ? "flowercrown" : "flower")
    : month === 10 && doy % 3 === 0 ? "mushroom" : month >= 9 && month <= 11 ? "leaf" : "";
  const head = night ? (month === 12 && date >= 20 ? "santa" : "cap") : e.music && e.phones ? "phones" : month === 12 && date >= 20 ? "santa"
    : month === 10 && date === 31 ? "horns" : month === 10 && date >= 25 ? "witch" : month === 1 && date <= 3 ? "crown"
    : snow ? "snowtop" : freezing ? "beanie" : cold ? "muffs" : drizzle ? "hood" : rain ? "froghat" : hot && sunny ? "straw" : sunnyMild ? "visor"
    : lunchTime && weekend ? "chef" : day === 6 ? "party"
    : seasonal && doy % 2 === 0 ? seasonal
    : pickDay(["beret", "bucket", "bow", "headband", "bandana", "cowboy", "pirate", "viking", "bunny", "catears", "tiara", "grad", "tophat", "halo"], 1, 50) || seasonal;
  // Nothing that looks like something it is doing stays on for hours (준: "왜 안경 쓰고 책보는 모습이 계속 지속될까", "아까는
  // 하트눈 날개모습이 지속되더니"): glasses, heart eyes, a cape, a book and the like come with a play (PROPS) and go with it;
  // what stays is for a reason, the weather, the hour, the phone or a holiday.
  const face = e.dnd ? "mask" : e.airplane ? "goggles" : april1 ? "clownnose"
    : sunny && !night && head !== "straw" && head !== "visor" ? "shades" : "";
  const neck = e.charging ? "bib" : cold ? "scarf" : head === "tophat" || head === "beret" ? "bowtie" : hot && summer ? "lei"
    : head === "bandana" ? "neckbandana" : head === "tiara" ? "pearls" : head === "bow" ? "ribbon"
    : pickDay(["medal", "whistle", "pearls", "ribbon", "neckbandana", "bell"], 3, 20);
  const hand = rain && !drizzle ? "umbrella" : night ? "teddy"
    : month === 10 && date >= 25 ? "pumpkin" : month === 1 && date <= 3 ? "luckybag" : month === 12 && (date === 24 || date === 25) ? "gift"
    : snow ? "snowball" : scorching ? (h >= 12 && h < 17 ? "icecream" : "handfan") : hot ? (summer && doy % 2 ? "popsicle" : "handfan")
    : minutes >= 7 * 60 && minutes < 9 * 60 ? (freezing ? "cocoa" : cold ? "tea" : "coffee")
    : minutes >= 12 * 60 && minutes < 13 * 60 ? (doy % 3 === 0 ? "riceball" : "lunch") : minutes >= 22 * 60 && minutes < 22 * 60 + 20 ? "toothbrush"
    : e.music ? "mic" : cold && h >= 14 && h < 18 ? (doy % 2 ? "fishbread" : "sweetpotato") : "";
  const body = e.powerSave ? "blanket" : rain && !drizzle ? "raincoat" : night ? "pajamas" : cold ? "sweater" : temp != null && temp >= 6 && temp < 14 ? "hoodie"
    : head === "chef" || hand === "riceball" ? "apron" : "";
  // No lasting outfit from how fond it is (준: "자꾸 눈하트에 이상한날개 단 상태에서 캐릭터가 안 풀려"): those are days' picks now.
  const back = night ? "moon" : freezing && !snow ? "mittens" : code != null && code >= 1 && code <= 3 && h >= 15 && h < 18 && doy % 5 === 0 ? "rainbow" : "";
  /*
    Plain is how 퍼레 is (준: "특정 의상 고정 경향이 있어 하나로 고정하지 말게 해줘", "보통은 기본 모드로 있고", "종종 의상을 바꾸게
    해", "기본모드로 있는게 디폴트야"): what stays is for a reason, an umbrella in rain, its night things while it is sleep
    time, a storm cloud in thunder; and now and then, for a while, one or two things the day calls for, different each time
    (forty-minute turns, about three in ten dressed up).
  */
  const turn = Math.floor(d.getTime() / 2400000), mix = n => { let x = Math.imul(turn ^ 0x9e3779b9, 2654435761) ^ Math.imul(n + 1, 40503); x ^= x >>> 15; x = Math.imul(x, 2246822507); x ^= x >>> 13; return x >>> 0; };
  const sleepTime = h < 7, choices = sleepTime ? [] : [head, face, neck, hand, body, back].filter(Boolean), picked = [];
  if (mix(1) % 100 < 30) for (let i = 0, n = 1 + mix(2) % 2; i < n && choices.length; i++) picked.push(choices.splice(mix(3 + i) % choices.length, 1)[0]);
  const wear = [rain && !drizzle ? "umbrella" : "", storm ? "storm" : "", sleepTime ? "cap" : "", sleepTime ? "pajamas" : "", sleepTime && h < 6 ? "moon" : ""].filter(Boolean).concat(picked);
  if (!b._dressed) WEAR.forEach(x => svg.classList.toggle("w-" + x, wear.includes(x)));
  svg.classList.toggle("w-bolt", !!e.charging); svg.classList.toggle("w-sweat", hot && !night);
  svg.classList.toggle("w-notes", !!e.music); svg.classList.toggle("w-q", !!e.offline);
  svg.classList.toggle("w-save", !!e.powerSave); svg.classList.toggle("w-dnd", !!e.dnd);
  const cb = $(b, ".care-btn"); if (cb) cb.style.setProperty("--fill", Math.max(8, Math.min(100, life.heart == null ? 55 : life.heart)) + "%");
  $(b, ".buddy").classList.toggle("lowpower", !!e.powerSave);
  const was = b._was; b._was = {charging: !!e.charging, music: !!e.music, heat, save: !!e.powerSave};
  if (!was || lockedNow) return;
  if (heat && !was.heat) { move(b, "wave", 1400); if (b._feel) b._feel("full", 1800); askThink(b, "heat"); return; }
  if (e.powerSave && !was.save) { if (b._feel) b._feel("yawn", 1700); askThink(b, "save"); return; }
  if (e.charging && !was.charging) { if (b._feel) b._feel("yum", 2200); move(b, "hop", 760); askThink(b, "charge"); }
  else if (e.music && !was.music) { move(b, "dance", 5200); if (b._feel) b._feel("happy", 5200); askThink(b, "music"); }
  else if (hungry && seenNow && Date.now() - (b._hungryAt || 0) > 1800000) { b._hungryAt = Date.now(); move(b, "rumble", 1100); if (b._feel) b._feel("sad", 2000); askThink(b, "hungry"); }
}
/** The app's word on 퍼레's life after a care act (launcher.life): its heart and memories, and how it took it. */
function boardLife(life) {
  if (!life) return;
  const felt = life.felt; info = info || {}; info.life = Object.assign({}, life); delete info.life.felt;
  boards.forEach(b => {
    envOf(b, info); if (b._careOn) drawCare(b);
    if (felt === "yum") { const svg = $(b, ".bot"); svg.classList.remove("eating"); void svg.getBoundingClientRect(); svg.classList.add("eating"); setTimeout(() => svg.classList.remove("eating"), 1700); if (b._feel) b._feel("yum", 1800); setTimeout(() => askThink(b, "snack", "냠냠, 정말 맛있어요!", true), 900); }
    else if (felt === "full") { move(b, "shake", 800); if (b._feel) b._feel("full", 1600); askThink(b, "full", "아까 먹어서 배불러, 나중에 줘", true); }
    else if (felt === "fun") { move(b, "spin", 950); setTimeout(() => move(b, "hop", 760), 950); if (b._feel) b._feel("laugh", 2000); askThink(b, "play", "와아, 한 번 더 놀자!", true); }
  });
}
/** The phone as it is now (launcher.env, asked every quarter minute while seen), so music and charging are noticed soon. */
function boardEnv(env) { if (!env) return; info = info || {}; info.env = env; boards.forEach(b => { envOf(b, info); if (b._careOn) drawCare(b); }); }
// Its clothes follow the time (a dress-up turn begins or ends): looked at again every minute while the screen is seen.
setInterval(() => { if (info && seenNow && !document.hidden) boards.forEach(b => envOf(b, info)); }, 60000);

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
  const food = e.battery == null ? "" : '<div class="care-row"><span class="k">밥</span><span class="meter' + (e.battery <= 20 && !e.charging ? " low" : "") + '"><i style="width:' + e.battery + '%"></i></span><span class="v">' + (e.charging ? "충전하며 냠냠" : e.battery <= 20 ? "배고파" : e.battery >= 80 ? "배불러" : "든든해") + '</span></div>';
  const now = e.night ? "자는 시간이라 졸려" : e.heat ? "휴대폰이 뜨거워서 땀이 나" : e.music ? "음악에 맞춰 들썩이는 중" : e.charging ? "충전기에서 밥 먹는 중" : e.hungry ? "배터리가 적어서 배고파" : e.dnd ? "방해 금지라 조용히 곁에 있어" : e.save ? "절전 모드라 기운을 아끼는 중" : e.airplane ? "비행기 모드, 어디 가?" : e.rain ? "비가 와서 우산 썼어" : e.cold ? "추워서 목도리 했어" : e.hot ? "더워서 땀이 송골송골" : e.offline ? "인터넷이 끊겨서 갸우뚱" : heart >= 80 ? "기분 최고야" : heart < 35 ? "조금 심심해" : "편안하게 쉬는 중";
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
        boards.forEach(b => { move(b, "wobble", 1700); if (b._feel) b._feel("dizzy", 2400); askThink(b, "shake"); });
        if (window.boardCare) window.boardCare("shake");
      }
    }
  }, {passive: true});
})();

/*
  퍼레's voice talk (held 퍼레, the home screen app's BuddyVoice): what it shows while it listens (a soft ring, what it hears
  so far in the bubble), thinks (the dots), speaks (the words as they are said, its mouth moving) and when it ends.
*/
let voiceOn = false, voiceAt = 0;
// A talk the page heard nothing of for two minutes (it ended while another app was in front): over, the mouth still.
setInterval(() => { if (voiceOn && !document.hidden && Date.now() - voiceAt > 120000) boardVoiceState("idle", ""); }, 10000);
function boardVoiceState(state, text) {
  voiceAt = Date.now();
  boards.forEach(b => {
    const buddy = $(b, ".buddy"), bubble = $(b, ".bubble"), svg = $(b, ".bot");
    clearTimeout(b._bubble); clearInterval(b._typing);
    buddy.classList.toggle("listening", state === "listening");
    if (state !== "speaking") svg.classList.remove("talking");
    if (state === "listening") {
      voiceOn = true; if (b._mood) b._mood(""); if (b._feel) b._feel("", 1);
      bubble.classList.remove("act"); bubble.dataset.go = "";
      bubble.innerHTML = text ? '<span class="heard">' + esc(text) + '</span>' : '<span class="heard muted">듣고 있어…</span>';
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
/** What stands on the board, as far as its words go (준: "움직임이 제약돼"): the clock's box is as wide as its row, its digits are not. */
function reachRect(el) { const box = el.getBoundingClientRect(); try { const r = document.createRange(); r.selectNodeContents(el); const c = r.getBoundingClientRect(); if (c.width > 0 && c.right < box.right) return {left: box.left, top: box.top, right: c.right, bottom: box.bottom}; } catch (e) {} return box; }
function roam(b) {
  const buddy = $(b, ".buddy"), hero = $(b, ".hero"), rnd = (a, z) => a + Math.random() * (z - a);
  let x = 0, y = 0, playing = false;
  // How far it may go: left to just past the widget's words, a little up and down.
  /*
    Its playground (준: "너무 오른쪽 구석에만 있어", "노란테두리영역을 활용해줘"): from the top of its corner down to just above App
    Library, and at each height left to whatever stands there (the clock widget, the prices, the small buttons), never over them.
  */
  const home = () => { const r = buddy.getBoundingClientRect(); return {left: r.left - x, top: r.top - y, w: r.width, h: r.height}; };
  const reach = reachRect;
  const obstacles = () => Array.from(document.querySelectorAll(".when > *, .grid > .card, .mini-pill"))
    .filter(el => el.offsetParent && el.offsetWidth > 0 && !buddy.contains(el)).map(reach);
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
    // Its heart stays in the corner (준: "퍼레가 하트 버튼을 끌고 다니느라 움직임이 제약돼"): only 퍼레 goes.
    buddy.style.transition = "transform " + ms + "ms cubic-bezier(.45,.1,.55,.9)";
    buddy.style.transform = t;
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
  /*
    Words while it plays on its own (준: "퍼레가 혼자 놀때 프리셋 말풍선을 적극 활용해", "프리셋 감정표현 의성어 등등", "필요한 경우엔 혼자
    놀때도 ai써서 이야기하게"): a small bubble (talk.js) with the play's own words, the sound of the feeling on its face, a word
    when it ends or as it walks; two at most in one play, never over words already in its bubble, not while 방해 금지 or locked.
    They do not count as 퍼레 having spoken, so a tap still gets its answer. Now and then, when an AI is here, it says
    something of its own about the play and the moment instead (BuddyTalk "solo:"), at most every eight minutes.
  */
  let saidInPlay = 0, saidAt = 0, soloAiAt = 0, inPlay = false;
  const quietNow = () => $(b, ".bubble").classList.contains("on") || (b._env && b._env.dnd) || lockedNow || document.hidden || voiceOn || b._careOn;
  const blurt = (moment, ms) => {
    if (quietNow() || saidInPlay >= 2 || Date.now() - saidAt < 2200) return false;
    const w = preset(moment); if (!w) return false;
    saidInPlay++; saidAt = Date.now();
    const before = b._spoke; speak(b, {text: w, sfx: true}, ms || 2000); b._spoke = before;
    return true;
  };
  const GROUP = {happy: "joy", cheerful: "joy", gleeful: "joy", delighted: "joy", excited: "joy", thrilled: "joy", sparkly: "joy", playful: "joy", cheeky: "joy",
    laugh: "laugh", giggle: "laugh", chuckle: "laugh", lol: "laugh", surprised: "surprise", shock: "surprise", amazed: "surprise", awed: "surprise", eek: "surprise", horrified: "surprise",
    proud: "proud", triumphant: "proud", smug: "proud", determined: "proud", focused: "proud", firedup: "proud", content: "calm", relief: "calm", zen: "calm", blissful: "calm", dreamy: "calm", hopeful: "calm",
    dizzy: "dizzy", dazed: "dizzy", woozy: "dizzy", ko: "dizzy", yawn: "sleepy", drowsy: "sleepy", snooze: "sleepy", sleepy: "sleepy", shy: "shy", bashful: "shy", awkward: "shy", innocent: "shy", sorry: "shy",
    curious: "curious", nosy: "curious", confused: "curious", idea: "curious", heartbroken: "sad", gloomy: "sad", pout: "sad", sulky: "sad", unimpressed: "sad", meh: "sad",
    chilly: "cold", overheated: "hot", lovey: "love", sneaky: "sneaky", devious: "sneaky", sly: "sneaky", drooling: "food", yum: "food", ouch: "ouch", fume: "fume", sing: "sing"};
  const feel = (f, ms) => { if (b._feel) b._feel(f, ms); if (inPlay && GROUP[f] && Math.random() < .3) setTimeout(() => blurt("feel:" + GROUP[f], 1700), 260); };
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
    clapping: end => seq(end, [["clap", 1900, "happy"], ["bounce", 1000, "laugh"]]),
    // to a hundred (준: "모션 종류 4가지도 각각 100개씩"): the new motions and faces in little stories, and a few new toys
    pogostick: end => seq(end, [["pogo", 1900, "gleeful"], ["bounce", 1000, "laugh"]]),
    jello: end => seq(end, [["squash", 1100, "giggle"], ["jelly", 1000, "giggle"], ["giggleshake", 1400, "chuckle"]]),
    tantrum: end => seq(end, [["stomp", 1300, "pout"], ["sink", 1900, "sulky"], ["boing", 600, "cheerful"]]),
    shimmydance: end => seq(end, [["shimmy", 1800, "playful"], ["swaying", 3100, "sing"]]),
    laughfit: end => seq(end, [["leanback", 1700, "lol"], ["giggleshake", 1400, "chuckle"]]),
    sneakattack: end => seq(end, [["crouch", 1900, "sneaky"], ["pounce", 1300, "firedup"], ["victory", 1300, "proud"]]),
    marathon: end => seq(end, [["sprint", 2100, "determined"], ["pant", 2200, "overheated"], ["akimbo", 1900, "proud"]]),
    sidestep: end => seq(end, [["shuffle", 1700, "smug"], ["shuffle", 1700, "devious"]]),
    crush: end => seq(end, [["heartbeat", 1600, "lovey"], ["hugself", 2100, "blissful"]]),
    balloonpop: end => seq(end, [["inflate", 1700, "blow"], ["squash", 1100, "shock"], ["giggleshake", 1400, "chuckle"]]),
    tinyme: end => seq(end, [["shrink", 1700, "shy"], ["tall", 1700, "proud"]]),
    ballerina: end => seq(end, [["spinslow", 2100, "zen"], ["bow", 1700, "content"]]),
    rollover: end => seq(end, [["flipside", 1700, "dazed"], ["float", 3100, "snooze"]]),
    rockingchair: end => seq(end, [["rockback", 2500, "content"], ["rockback", 2500, "zen"]]),
    seesawride: end => seq(end, [["seesaw", 2500, "cheerful"], ["bounce", 1000, "laugh"]]),
    swing: end => seq(end, [["pendulum", 2500, "gleeful"], ["pendulum", 2500, "woozy"]]),
    hover: end => seq(end, [["levitate", 2500, "zen"], ["float", 3100, "content"]]),
    quicksand: end => seq(end, [["sink", 1900, "eek"], ["stretchup", 1900, "relief"]]),
    oops: end => seq(end, [["trip", 1500, "eek"], ["shrug", 1300, "awkward"]]),
    bananaslip: end => seq(end, [["slip", 1600, "horrified"], ["shake", 800, "dazed"], ["bow", 1700, "sorry"]]),
    brrr: end => seq(end, [["shiverbig", 1700, "chilly"], ["hugself", 2100, "content"]]),
    explorer: end => seq(end, [["lookaround", 2100, "curious"], ["antscan", 2100, "nosy"], ["point", 1500, "idea"]]),
    whatever: end => seq(end, [["eyeroll", 1300, "unimpressed"], ["shrug", 1300, "meh"]]),
    skygaze: end => seq(end, [["lookup", 1700, "hopeful"], ["lookup", 1700, "awed"]]),
    shytime: end => seq(end, [["lookdown", 1700, "bashful"], ["hide", 2100, "shy"]]),
    chuckles: end => seq(end, [["giggleshake", 1400, "chuckle"], ["clap", 1900, "happy"]]),
    sadmovie: end => seq(end, [["sob", 1900, "heartbroken"], ["droop", 2300, "gloomy"], ["boing", 600, "relief"]]),
    pushups: end => seq(end, [["crouch", 1900, "determined"], ["tall", 1700, "focused"], ["crouch", 1900, "determined"], ["flex", 1700, "proud"]]),
    morningstretch: end => seq(end, [["stretchup", 1900, "yawn"], ["shake", 800, "cheerful"]]),
    swingarms: end => seq(end, [["armswing", 1900, "happy"], ["wavebye", 1700, "happy"]]),
    helicopter: end => seq(end, [["propeller", 2100, "excited"], ["float", 3100, "content"]]),
    birdie: end => seq(end, [["flapping", 2100, "gleeful"], ["levitate", 2500, "dreamy"]]),
    orchestra: end => seq(end, [["conductor", 2100, "sing"], ["bow", 1700, "proud"]]),
    drummer: end => seq(end, [["drum", 1700, "firedup"], ["headbang", 2100, "thrilled"]]),
    selfhug: end => seq(end, [["hugself", 2100, "blissful"], ["heartbeat", 1600, "lovey"]]),
    superhero: end => seq(end, [["akimbo", 1900, "proud"], ["fistpump", 1700, "triumphant"], ["zoom", 1000, "amazed"]]),
    champion: end => seq(end, [["fistpump", 1700, "triumphant"], ["victory", 1300, "proud"], ["cheer", 2100, "laugh"]]),
    highfives: end => seq(end, [["highfive", 1100, "delighted"], ["highfive", 1100, "delighted"], ["clapfast", 1600, "thrilled"]]),
    goodbye: end => seq(end, [["wavebye", 1700, "happy"], ["bow", 1700, "content"]]),
    comehere: end => seq(end, [["beckon", 1700, "sly"], ["giggleshake", 1400, "playful"]]),
    secret: end => seq(end, [["shush", 1900, "innocent"], ["lookaround", 2100, "nosy"], ["giggleshake", 1400, "chuckle"]]),
    puzzled: end => seq(end, [["scratch", 1900, "confused"], ["perk", 800, "idea"]]),
    applause: end => seq(end, [["clapfast", 1600, "thrilled"], ["bounce", 1000, "laugh"]]),
    antennadance: end => seq(end, [["antwiggle", 1600, "cheeky"], ["antboing", 1100, "surprised"], ["dance", 1800, "happy"]]),
    radar: end => seq(end, [["antscan", 2100, "nosy"], ["perk", 800, "idea"]]),
    noodle: end => seq(end, [["bodywave", 1300, "giggle"], ["jelly", 1000, "giggle"]]),
    zigzagrun: end => seq(end, [["zigzag", 1700, "excited"], ["pant", 2200, "overheated"]]),
    dizzyorbit: end => seq(end, [["orbit", 1700, "woozy"], ["tornado", 1500, "dizzy"], ["flipside", 1700, "dazed"]]),
    daydream: end => { feel("dreamy", 3400); move(b, "float", 3100); for (let i = 0; i < 3; i++) setTimeout(() => { const t = toy("heartpop", "", 2000); t.style.left = rnd(20, 80).toFixed(0) + "%"; }, 300 + i * 700); setTimeout(() => { feel("blissful", 1000); end(); }, 3400); },
    snacktime: end => seq(end, [["leanin", 1700, "drooling"], ["nod", 900, "yum"], ["bounce", 1000, "happy"]]),
    leafcatch: end => { for (let i = 0; i < 3; i++) setTimeout(() => { const t = toy("leafy", "", 2600); t.style.left = rnd(25, 75).toFixed(0) + "%"; }, i * 650); gazeTo(0, -5); feel("curious", 2000); setTimeout(() => gazeTo(0, 3), 1300); setTimeout(() => { gazeTo(0, 0); move(b, "hop", 760); feel("cheerful", 1200); end(); }, 2900); },
    snowplay: end => { for (let i = 0; i < 6; i++) setTimeout(() => { const t = toy("flake", "", 2800); t.style.left = rnd(5, 95).toFixed(0) + "%"; }, i * 350); feel("gleeful", 2600); move(b, "jumpturn", 1100); setTimeout(() => { feel("chilly", 1300); move(b, "shiverbig", 1700); }, 1500); setTimeout(end, 3300); },
    confetti: end => { for (let i = 0; i < 10; i++) { const t = toy("confetti c" + (i % 4), "", 2400); t.style.left = rnd(0, 100).toFixed(0) + "%"; t.style.animationDelay = (i * 60) + "ms"; } seq(end, [["cheer", 2100, "triumphant"], ["bounce", 1000, "laugh"]]); },
    yoyo: end => { toy("yoyo", "<b></b>", 3200); move(b, "point", 1500); feel("focused", 3200); [0, 800, 1600, 2400].forEach(t => setTimeout(() => { gazeTo(0, 4); setTimeout(() => gazeTo(0, -1), 400); }, t)); setTimeout(() => { gazeTo(0, 0); feel("proud", 1000); end(); }, 3300); },
    frisbee: end => { move(b, "point", 1500); feel("excited", 2600); setTimeout(() => { toy("frisbee", "", 2400); gazeTo(-6, -3); }, 500); setTimeout(() => { gazeTo(5, -2); move(b, "hop", 760); }, 1800); setTimeout(() => { gazeTo(0, 0); feel("gleeful", 1000); end(); }, 3000); },
    pinwheel: end => { toy("pinwheel", "<b></b>", 3400); feel("blow", 1800); setTimeout(() => feel("delighted", 1500), 1800); setTimeout(end, 3400); },
    fireflies: end => { for (let i = 0; i < 5; i++) { const t = toy("firefly", "", 3600); t.style.left = rnd(0, 100).toFixed(0) + "%"; t.style.top = rnd(-10, 40).toFixed(0) + "%"; t.style.animationDelay = (i * 300) + "ms"; } feel("awed", 3500); gazeTo(-4, -4); setTimeout(() => gazeTo(4, -3), 1200); setTimeout(() => gazeTo(0, -5), 2400); setTimeout(() => { gazeTo(0, 0); end(); }, 3600); },
    dice: end => { toy("dice", "", 1800); feel("curious", 1600); setTimeout(() => { feel("gleeful", 1200); move(b, "bounce", 1000); }, 1700); setTimeout(end, 2900); },
    // Its real toys (toys(): they stay out a while, bounce off the board, and the user can touch them too)
    kickball: end => { const T = b._toys; if (!T) return end(); const ball = T.out("ball"); feel("excited", 1600);
      setTimeout(() => T.kick(ball, -1), 600);
      setTimeout(() => b._goNear(T.sx(ball), () => { T.kick(ball); move(b, "hop", 760); feel("laugh", 1200); }), 2500);
      setTimeout(end, 5800); },
    rolldice: end => { const T = b._toys; if (!T) return end(); const d = T.out("dice"); feel("curious", 1400); setTimeout(() => T.throwDice(d), 500); setTimeout(() => T.throwDice(d), 3800); setTimeout(end, 6400); },
    puzzlecube: end => { const T = b._toys; if (!T) return end(); const c = T.out("cube"); feel("focused", 3200); setTimeout(() => c.el.classList.add("twist"), 400); gazeTo(-3, 3);
      setTimeout(() => { c.el.classList.remove("twist"); gazeTo(0, 0); if (Math.random() < .5) { feel("triumphant", 1500); move(b, "victory", 1300); } else { feel("confused", 1500); move(b, "scratch", 1900); } }, 3600); setTimeout(end, 5600); },
    spintop: end => { const T = b._toys; if (!T) return end(); const t = T.out("top"); feel("excited", 1500); setTimeout(() => T.spin(t), 700); gazeTo(-4, 3); setTimeout(() => { gazeTo(0, 0); end(); }, 8800); },
    balloonbat: end => { const T = b._toys; if (!T) return end(); const bl = T.out("balloon"); feel("gleeful", 1500);
      [900, 2700, 4500].forEach(t => setTimeout(() => { bl.vy = -rnd(300, 420); bl.vx = rnd(-120, 120); bl.rest = 0; T.wake(); move(b, "hop", 760); }, t)); setTimeout(end, 6200); },
    flutter: end => { const T = b._toys; if (!T) return end(); T.flies(2 + Math.floor(Math.random() * 2)); feel("awed", 2000); gazeTo(-3, -5); setTimeout(() => { gazeTo(0, 0); end(); }, 3200); },
    meteors: end => { const T = b._toys; if (!T) return end(); T.shower(); feel("awed", 4000); setTimeout(end, 9500); }
  };
  /** What it puts on for a play, off again when the play ends (its lasting clothes come back, envOf). */
  const PROPS = {reading: ["book", "roundglasses"], magic: ["wand", "tophat"], explorer: ["magnifier", "backpack", "map"], radar: ["magnifier"],
    superhero: ["cape"], champion: ["medal", "crown"], rockstar: ["mic", "shades"], singsong: ["mic"], orchestra: ["wand", "bowtie"],
    bubbles: ["bubblewand"], hula: ["lei", "flower"], swim: ["swimgoggles"], crush: ["heartglasses"], daydream: ["heartglasses"],
    secret: ["mustache"], puzzled: ["nerdglasses"], ninja: ["bandana"], sneakattack: ["bandana"], karate: ["headband"], boxing: ["headband"],
    marathon: ["headband", "whistle"], cheerleader: ["bow"], ballerina: ["tiara"], skygaze: ["camera"], cloudwatch: ["camera"],
    snacktime: ["lollipop"], kite: ["visor"], frisbee: ["cap"], goodbye: ["flag"], confetti: ["party"], sadmovie: ["blanket"],
    coffeebreak: ["coffee"], meditate: ["halo"], yoga: ["headband"], brrr: ["scarf", "mittens"], morningstretch: ["toothbrush"]};
  let dressed = false;
  const dress = kind => {
    const props = PROPS[kind]; if (!props || !props.length) return;
    const svg = $(b, ".bot"); if (!svg) return;
    WEAR.forEach(x => svg.classList.remove("w-" + x)); props.forEach(x => svg.classList.add("w-" + x)); dressed = true; b._dressed = true;
  };
  const undress = () => { if (dressed) { dressed = false; b._dressed = false; envOf(b, info); } };
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
    const drop = k => { const i = kinds.indexOf(k); if (i >= 0) kinds.splice(i, 1); };
    if (e.rain) ["butterfly", "kite", "paperplane", "cloudwatch", "flutter", "meteors"].forEach(drop);
    const dark = h >= 19 || h < 5;
    drop(dark ? "flutter" : "meteors");
    // Its own toys come out often (준: "심심하지 않게"); the shower now and then on a dark evening.
    if (b._toys) ["kickball", "rolldice", "puzzlecube", "spintop", "balloonbat"].concat(dark ? [] : ["flutter"]).forEach(k => { if (kinds.includes(k)) kinds.push(k, k, k); });
    else ["kickball", "rolldice", "puzzlecube", "spintop", "balloonbat", "flutter", "meteors"].forEach(k => { const i = kinds.indexOf(k); if (i >= 0) kinds.splice(i, 1); });
    const kind = kinds[Math.floor(Math.random() * kinds.length)];
    playing = true;
    const here = () => {
      saidInPlay = 0; inPlay = true; dress(kind);
      const ended = () => { playing = false; inPlay = false; undress(); if (Math.random() < .22) { saidInPlay = Math.min(saidInPlay, 1); setTimeout(() => blurt("done", 1800), 200); } };
      try { PLAYS[kind](ended); } catch (err) { playing = false; inPlay = false; undress(); }
      // The AI, now and then: something of its own about this play and the moment.
      const ai = window.boardThink && !quietNow() && Date.now() - soloAiAt > 480000 && Date.now() - (b._spoke || 0) > 60000 && Date.now() - thinkAt > 12000;
      if (ai && Math.random() < .12) { soloAiAt = Date.now(); thinkAt = Date.now(); window._soloAsk = Date.now(); saidInPlay = 2; window.boardThink("solo:" + kind); return; }
      // Mostly its words for the play, else a sound.
      setTimeout(() => { if (Math.random() < .6) blurt("play:" + kind, 2200); else if (Math.random() < .5) blurt("solo", 1700); }, 450);
    };
    // Most plays start somewhere new in the playground.
    if (Math.random() < .85 && !["peek", "clock", "airplane", "ninja", "skipping", "rolling", "cartwheel"].includes(kind)) { const t = spot(); walk(t.x, t.y, here); } else here();
    setTimeout(() => { playing = false; inPlay = false; undress(); }, 16000);
  };
  const tick = () => { play(); setTimeout(tick, rnd(9000, 17000)); };
  setTimeout(tick, rnd(5000, 9000));
  // Out of its corner soon after the screen opens, and now and then a stroll between plays.
  setTimeout(() => { if (!busy() && !playing) { const t = spot(); walk(t.x, t.y); } }, 2200);
  const stroll = () => {
    if (!busy() && !playing && Math.random() < .6) {
      const t = spot(); walk(t.x, t.y);
      if (Math.random() < .3) { saidInPlay = 0; setTimeout(() => blurt("roam", 1800), 300); }
    }
    setTimeout(stroll, rnd(14000, 26000));
  };
  setTimeout(stroll, rnd(12000, 20000));
  // Home: back to its place (a tap, its card, the voice talk, the screen opened again).
  b._home = () => { if (x || y) { x = 0; y = 0; place(400); } };
  // One play by name, now (for a look at it; the home screen never calls it).
  b._play = kind => { if (PLAYS[kind] && !playing) { playing = true; PLAYS[kind](() => { playing = false; }); } };
  b._playNames = () => Object.keys(PLAYS);
  b._playNow = (kind, done) => PLAYS[kind](done || (() => {}));
  b._walk = (nx, ny) => walk(nx, ny);
  // For the toys: a small word now (not counted as 퍼레 speaking), its eyes, and a walk to a spot on the board.
  // over: a word that matters more (the dice's number) may take the place of a sound ("아야!"), never of real words.
  b._sfx = (text, ms, over) => { const bub = $(b, ".bubble"); if (!text || (over && bub.classList.contains("sfx") ? (b._env && b._env.dnd) || lockedNow || document.hidden || voiceOn || b._careOn : quietNow())) return false; const before = b._spoke; speak(b, {text, sfx: true}, ms || 1800); b._spoke = before; saidAt = Date.now(); return true; };
  b._gazeTo = gazeTo;
  b._goNear = (vx, done) => { const hm = home(), r = room(), target = Math.round(vx - (hm.left + hm.w / 2)); walk(Math.max(r.min, Math.min(0, target)), y, done); };
  b._spot = () => spot();
  addEventListener("resize", () => b._home());
}

/*
  퍼레's toys (준: "퍼레가 가지고 놀 다양한 오브젝트 토이들을 만들어줘 심심하지 않게", "반응성이 구현된 물리적인 오브젝트도 좋고 큐브같이
  두뇌를 써야하는 장난감도 좋고 주사위 같은 단순 기구도 되고 공같은 것도 되고", "나비 같이 날아다니는 것 유성우 등등"): things on the
  board that fall, bounce and roll off the clock, the cards and 퍼레 itself, and that the user can touch too:
    ball      flicked or tapped, it flies and bounces; 퍼레 kicks it and says ouch when it is hit
    dice      tapped, it is thrown and lands on a number 퍼레 reads out
    cube      a puzzle cube: tapped, a small 불 끄기 puzzle (3×3, a tap turns a light and its neighbours) to solve
    top       tapped, it spins, wobbles as it slows and falls over
    balloon   floats up; batted, it flies; tapped twice quickly, it pops
    flies     butterflies wander the board, rest on 퍼레's head and flee from a finger
    meteors   at night a shower crosses the sky, and 퍼레 asks whether a wish was made
  A few at a time; each goes after a while. The loop runs only while something moves (nothing at rest costs a frame).
*/
function toys(b) {
  if (matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches) { b._toys = null; return; }
  const wrap = $(b, ".wrap"), buddy = $(b, ".buddy");
  const layer = document.createElement("div"); layer.className = "toybox"; wrap.appendChild(layer);
  const items = []; let raf = 0, last = 0, solidsAt = 0, solidsCache = [];
  const G = 1500, now = () => performance.now(), rnd = (a, z) => a + Math.random() * (z - a);
  const W = () => wrap.getBoundingClientRect();
  const local = (r, w) => ({l: r.left - w.left, t: r.top - w.top, r: r.right - w.left, b: r.bottom - w.top});
  /** The floor: just above App Library (or the board's bottom). */
  const floor = w => { const lib = document.querySelector(".lib-head"); return lib && lib.offsetParent ? Math.min(w.height + 60, lib.getBoundingClientRect().top - w.top - 2) : w.height - 2; };
  const solids = w => {
    if (now() - solidsAt > 500) { solidsAt = now(); solidsCache = Array.from(document.querySelectorAll(".when > *, .grid > .card, .mini-pill")).filter(e => e.offsetParent && e.offsetWidth > 0).map(e => local(reachRect(e), w)); }
    return solidsCache;
  };
  const say = (t, ms, over) => { if (b._sfx) b._sfx(t, ms, over); };
  const pick = moment => typeof TALK === "undefined" ? "" : preset(moment);

  // ── the body of a toy: where it is, how it moves ──
  const make = (kind, html, o) => {
    const el = document.createElement("div"); el.className = "tb " + kind; if (html) el.innerHTML = html; layer.appendChild(el);
    const it = Object.assign({kind, el, x: 0, y: 0, vx: 0, vy: 0, a: 0, va: 0, r: 9, bounce: .6, friction: .98, grav: 1, drag: 0, held: false, born: now(), life: 90000, rest: 0, solid: true}, o || {});
    items.push(it); wire(it); draw(it); wake(); return it;
  };
  const gone = it => { const i = items.indexOf(it); if (i >= 0) items.splice(i, 1); it.el.classList.add("bye"); setTimeout(() => it.el.remove(), 400); if (it.panel) it.panel.remove(); };
  const draw = it => { it.el.style.transform = "translate3d(" + (it.x - it.r).toFixed(1) + "px," + (it.y - it.r).toFixed(1) + "px,0) rotate(" + it.a.toFixed(3) + "rad)"; };
  /** Where 퍼레 holds a thing: by its hand, on the board. */
  const hand = () => { const w = W(), bb = buddyBox(w); return {x: Math.max(16, bb.l - 14 - Math.random() * 16), y: (bb.t + bb.b) / 2}; };
  const buddyBox = w => { const r = buddy.getBoundingClientRect(); return {l: r.left - w.left + 14, t: r.top - w.top + 22, r: r.right - w.left - 14, b: r.bottom - w.top - 6}; };
  const bump = (it, o) => {
    const cx = Math.max(o.l, Math.min(it.x, o.r)), cy = Math.max(o.t, Math.min(it.y, o.b));
    let dx = it.x - cx, dy = it.y - cy, d2 = dx * dx + dy * dy;
    if (d2 >= it.r * it.r) return 0;
    let d = Math.sqrt(d2);
    if (d < .01) { dx = 0; dy = -1; d = 1; it.y = o.t - it.r; } // inside: out the top
    const nx = dx / d, ny = dy / d; it.x = cx + nx * it.r; it.y = cy + ny * it.r;
    const vn = it.vx * nx + it.vy * ny;
    if (vn < 0) { it.vx -= (1 + it.bounce) * vn * nx; it.vy -= (1 + it.bounce) * vn * ny; if (ny < -.6 && Math.abs(it.vy) < 50) it.vy = 0; }
    return -vn;
  };
  const step = dt => {
    const w = W(), F = floor(w), R = w.width - 4, L = 4, T = 8, s = solids(w), bb = buddyBox(w);
    for (const it of items.slice()) {
      if (it.tick) it.tick(it, dt, w, F);
      if (now() - it.born > it.life && !it.held && !it.panel) { gone(it); continue; }
      if (it.held || it.free) { draw(it); continue; }
      it.vy += G * it.grav * dt;
      if (it.drag) { const k = Math.max(0, 1 - it.drag * dt); it.vx *= k; it.vy *= k; }
      it.x += it.vx * dt; it.y += it.vy * dt; it.a += it.va * dt;
      if (it.x - it.r < L) { it.x = L + it.r; it.vx = Math.abs(it.vx) * it.bounce; }
      if (it.x + it.r > R) { it.x = R - it.r; it.vx = -Math.abs(it.vx) * it.bounce; }
      if (it.y - it.r < T) { it.y = T + it.r; it.vy = Math.abs(it.vy) * it.bounce; }
      let ground = false;
      if (it.y + it.r > F) { const hit = it.vy; it.y = F - it.r; it.vy = Math.abs(it.vy) > 70 ? -Math.abs(it.vy) * it.bounce : 0; it.vx *= it.friction; ground = true; if (hit > 300 && it.onBounce) it.onBounce(it, hit); }
      if (it.solid) for (const o of s) { const hit = bump(it, o); if (hit > 0 && o.b > it.y) { ground = ground || (it.y < o.t); if (hit > 300 && it.onBounce) it.onBounce(it, hit); } }
      if (it.solid) { const hit = bump(it, bb); if (hit > 260 && now() - (it.ouch || 0) > 1500) { it.ouch = now(); ouch(it); } }
      if (ground && it.roll) it.va = it.vx / it.r;
      if (ground && Math.abs(it.vx) < 8) it.vx = 0;
      const still = it.vx === 0 && Math.abs(it.vy) < 1 && !it.spin;
      it.rest = still ? it.rest + dt : 0;
      if (it.rest > .25 && it.onRest && !it.rested) { it.rested = true; it.onRest(it); }
      if (!still) it.rested = false;
      draw(it);
    }
    // Two toys knock into each other.
    for (let i = 0; i < items.length; i++) for (let j = i + 1; j < items.length; j++) {
      const p = items[i], q = items[j]; if (!p.solid || !q.solid || p.free || q.free) continue;
      const dx = q.x - p.x, dy = q.y - p.y, d = Math.hypot(dx, dy), min = p.r + q.r;
      if (d > 0 && d < min) { const nx = dx / d, ny = dy / d, push = (min - d) / 2; p.x -= nx * push; p.y -= ny * push; q.x += nx * push; q.y += ny * push;
        const rv = (q.vx - p.vx) * nx + (q.vy - p.vy) * ny; if (rv < 0) { const k = -rv * .9; p.vx -= k * nx; p.vy -= k * ny; q.vx += k * nx; q.vy += k * ny; } }
    }
  };
  const busy = () => items.some(it => it.held || it.free || it.spin || it.panel || it.rest < .3);
  const loop = t => { const dt = Math.min(.033, (t - last) / 1000 || .016); last = t; step(dt); raf = busy() || items.some(it => now() - it.born > it.life) ? requestAnimationFrame(loop) : 0; };
  const wake = () => { if (!raf && !document.hidden) { last = performance.now(); raf = requestAnimationFrame(loop); } };
  setInterval(() => { if (items.some(it => now() - it.born > it.life)) wake(); }, 5000);
  // Its toys wait while the screen is away, and go on when it is back.
  document.addEventListener("visibilitychange", () => { if (!document.hidden) wake(); });
  /** A toy hit 퍼레 hard. */
  const ouch = it => {
    move(b, "wobble", 900); if (b._feel) b._feel(Math.random() < .5 ? "surprised" : "ouch", 1100);
    say(["아야!", "앗!", "으앗!", "헤딩!", "깜짝이야!"][Math.floor(Math.random() * 5)], 1300);
  };

  // ── touch: drag and throw, or tap ──
  const wire = it => {
    let down = null, path = [];
    it.el.addEventListener("pointerdown", e => {
      e.stopPropagation(); try { it.el.setPointerCapture(e.pointerId); } catch (x) {}
      const w = W(); down = {x: e.clientX, y: e.clientY, t: now(), moved: false}; path = [{x: e.clientX - w.left, y: e.clientY - w.top, t: now()}];
      if (it.grab) { it.held = true; it.vx = it.vy = 0; wake(); }
    });
    it.el.addEventListener("pointermove", e => {
      if (!down) return; const w = W();
      if (Math.hypot(e.clientX - down.x, e.clientY - down.y) > 6) down.moved = true;
      if (it.held) { it.x = e.clientX - w.left; it.y = e.clientY - w.top; path.push({x: it.x, y: it.y, t: now()}); if (path.length > 6) path.shift(); }
    });
    const up = e => {
      if (!down) return; const was = down; down = null;
      if (it.held) {
        it.held = false;
        const a = path[0], z = path[path.length - 1], dt = Math.max(16, z.t - a.t) / 1000;
        it.vx = Math.max(-1800, Math.min(1800, (z.x - a.x) / dt)); it.vy = Math.max(-1800, Math.min(1800, (z.y - a.y) / dt)); it.va = it.vx / 30;
      }
      if (!was.moved && it.tap) it.tap(it, e);
      it.born = Math.max(it.born, now() - it.life + 30000); // touched: it stays a while longer
      wake();
    };
    it.el.addEventListener("pointerup", up); it.el.addEventListener("pointercancel", up);
    it.el.addEventListener("click", e => e.stopPropagation());
  };

  // ── the toys ──
  const find = kind => items.find(it => it.kind === kind);
  const at = (it, p) => { it.x = p.x; it.y = p.y; draw(it); return it; };
  const KIND = {
    ball: () => at(make("ball", "", {r: 9, bounce: .74, friction: .985, roll: true, grab: true,
      tap: it => { it.vy = -rnd(520, 760); it.vx = rnd(-260, 260); },
      onBounce: (it, v) => { it.el.classList.remove("squash"); void it.el.offsetWidth; it.el.classList.add("squash"); }}), hand()),
    dice: () => at(make("dice", '<i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i>', {r: 8, bounce: .38, friction: .86, grab: true,
      tap: it => throwDice(it), onRest: it => { if (it.rolling) { it.rolling = false; land(it); } }}), hand()) && faced(find("dice")),
    cube: () => at(make("cube", '<i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i>', {r: 10, bounce: .3, friction: .85, grab: true,
      tap: it => puzzle(it)}), hand()),
    top: () => at(make("top", "<b></b>", {r: 9, bounce: .2, friction: .9, grab: true, spin: 0,
      tap: it => { it.spin = 1; it.a = 0; it.el.classList.remove("fallen"); wake(); },
      tick: (it, dt) => {
        if (!it.spin) return;
        it.spin = Math.max(0, it.spin - dt / 7);
        it.el.style.setProperty("--spin", (it.spin * 22).toFixed(1));
        const wob = (1 - it.spin) * .35 * Math.sin(now() / 90);
        it.a = wob;
        if (it.spin < .12) { it.spin = 0; it.a = (Math.random() < .5 ? -1 : 1) * 1.3; it.el.classList.add("fallen"); it.vx = it.a * 40; if (b._feel) b._feel("surprised", 900); say(["쓰러졌다!", "빙글빙글… 쿵!", "오래 돌았다!"][Math.floor(Math.random() * 3)], 1500); }
        it.el.classList.toggle("spinning", it.spin > 0);
      }}), hand()),
    balloon: () => at(make("balloon", "<b></b>", {r: 11, bounce: .5, friction: .95, grav: -.05, drag: .9, grab: true,
      tap: it => { if (now() - (it.tapped || 0) < 400) { pop(it); return; } it.tapped = now(); it.vy = -rnd(260, 380); it.vx = rnd(-120, 120); }}), hand())
  };
  const throwDice = it => {
    it.rolling = true; it.rested = false; it.vy = -rnd(520, 700); it.vx = rnd(-220, 220); it.va = rnd(-18, 18);
    it.el.classList.add("rolling"); wake();
  };
  const PIPS = [[4], [0, 8], [0, 4, 8], [0, 2, 6, 8], [0, 2, 4, 6, 8], [0, 2, 3, 5, 6, 8]];
  const land = it => {
    const n = 1 + Math.floor(Math.random() * 6); it.face = n; it.va = 0; it.a = 0; it.el.classList.remove("rolling");
    Array.from(it.el.children).forEach((p, i) => p.classList.toggle("on", PIPS[n - 1].includes(i))); draw(it);
    if (b._feel) b._feel(n === 6 ? "thrilled" : n === 1 ? "pout" : "happy", 1200);
    say(n === 6 ? "6이다! 대박!" : n === 1 ? "1… 에이~" : n + "!" + (n >= 4 ? " 좋아!" : ""), 1500, true);
  };
  const faced = it => { const n = 1 + Math.floor(Math.random() * 6); Array.from(it.el.children).forEach((p, i) => p.classList.toggle("on", PIPS[n - 1].includes(i))); return it; };
  const pop = it => {
    it.el.classList.add("popped"); say("펑!", 1100); if (b._feel) b._feel("shock", 900); move(b, "doubletake", 1200);
    const i = items.indexOf(it); if (i >= 0) items.splice(i, 1); setTimeout(() => it.el.remove(), 350);
  };

  // ── 퍼즐 큐브: 불 끄기 (3×3), a tap turns a light and the ones beside it; all off is solved ──
  const puzzle = it => {
    if (it.panel) { it.panel.remove(); it.panel = null; return; }
    const lights = Array(9).fill(false);
    const press = i => { const r = Math.floor(i / 3), c = i % 3; [[r, c], [r - 1, c], [r + 1, c], [r, c - 1], [r, c + 1]].forEach(([y, x]) => { if (y >= 0 && y < 3 && x >= 0 && x < 3) lights[y * 3 + x] = !lights[y * 3 + x]; }); };
    for (let k = 0; k < 4 || lights.every(v => !v); k++) press(Math.floor(Math.random() * 9));
    const p = document.createElement("div"); p.className = "tpanel"; it.panel = p;
    p.innerHTML = '<div class="th"><b>불 끄기</b><button class="tx" aria-label="닫기">✕</button></div><div class="tg">' + lights.map((_, i) => '<button data-i="' + i + '"></button>').join("") + '</div><div class="tn"></div>';
    layer.appendChild(p);
    // Beside the cube, on the side away from 퍼레, so its face shows how it goes.
    const w = W(), bb = buddyBox(w), left = it.x - 128 - 18 >= 4 && it.x < bb.r ? it.x - 128 - 18 : it.x + 18;
    p.style.left = Math.max(4, Math.min(w.width - 132, left)) + "px"; p.style.top = Math.max(8, Math.min(floor(w) - 160, it.y - 80)) + "px";
    let moves = 0;
    const show = () => { p.querySelectorAll(".tg button").forEach((x, i) => x.classList.toggle("on", lights[i])); const left = lights.filter(Boolean).length; p.querySelector(".tn").textContent = left ? "켜진 불 " + left + " · " + moves + "번" : moves + "번 만에 다 껐다!"; };
    show();
    p.addEventListener("pointerdown", e => e.stopPropagation());
    p.addEventListener("click", e => {
      e.stopPropagation();
      if (e.target.closest(".tx")) { p.remove(); it.panel = null; return; }
      const cell = e.target.closest(".tg button"); if (!cell || lights.every(v => !v)) return;
      press(+cell.dataset.i); moves++; show();
      if (lights.every(v => !v)) {
        it.el.classList.add("solved"); move(b, "jumpturn", 1100); if (b._feel) b._feel("triumphant", 1800); say(moves <= 5 ? "천재다! 풀었어!" : "풀었다! 짝짝짝!", 1800, true);
        for (let i = 0; i < 8; i++) { const c = document.createElement("i"); c.className = "toy confetti c" + (i % 4); c.style.left = (20 + Math.random() * 60).toFixed(0) + "%"; buddy.appendChild(c); setTimeout(() => c.remove(), 2400); }
        setTimeout(() => { if (it.panel === p) { p.remove(); it.panel = null; } it.el.classList.remove("solved"); }, 2200);
      }
    });
  };

  // ── butterflies: they wander, rest on 퍼레's head, flee from a finger ──
  const flies = n => {
    const w0 = W(), hd = hand();
    for (let i = 0; i < n; i++) {
      const it = make("fly", "<b></b><b></b>", {r: 8, free: true, solid: false, life: rnd(32000, 46000), tx: 0, ty: 0, next: 0, sit: 0, speed: rnd(55, 85),
        tick: (it, dt, w, F) => {
          if (now() > it.next) {
            it.next = now() + rnd(1400, 3200);
            const head = buddyBox(w);
            if (Math.random() < .22) { it.tx = (head.l + head.r) / 2 + rnd(-10, 10); it.ty = head.t - 4; it.sit = now() + rnd(1500, 2600); }
            else { it.tx = rnd(w.width * .35, w.width - 14); it.ty = rnd(20, Math.max(40, F - 30)); it.sit = 0; }
          }
          const dx = it.tx - it.x, dy = it.ty - it.y, d = Math.hypot(dx, dy);
          if (it.sit && d < 4) { it.el.classList.add("sitting"); return; }
          it.el.classList.remove("sitting");
          const sp = it.flee ? 260 : it.speed;
          if (d > 1) { it.x += dx / d * sp * dt + Math.sin(now() / 160 + it.born) * .6; it.y += dy / d * sp * dt + Math.cos(now() / 120 + it.born) * .8; }
          it.el.style.setProperty("--face", dx < 0 ? -1 : 1);
          if (it.flee && d < 6) it.flee = false;
        },
        tap: it => { it.flee = true; it.tx = rnd(0, W().width); it.ty = 6; it.next = now() + 1800; it.sit = 0; if (b._feel) b._feel("surprised", 800); }});
      it.x = hd.x + rnd(-20, 20); it.y = hd.y - 30 - i * 8; draw(it);
    }
  };

  // ── 유성우: streaks across the sky; afterwards 퍼레 asks about the wish (its choices) ──
  const shower = () => {
    const w = W(); let k = 0;
    const one = () => {
      if (k++ >= 12) { setTimeout(() => { if (!$(b, ".bubble").classList.contains("on")) speak(b, {text: "별똥별에 소원 빌었어?"}, 9000); }, 1200); return; }
      const it = make("meteor", "", {r: 6, free: true, solid: false, life: 1600, vx: -rnd(380, 560), vy: rnd(160, 260),
        tick: (it, dt) => { it.x += it.vx * dt; it.y += it.vy * dt; it.a = Math.atan2(it.vy, it.vx); if (it.x < -30 || it.y > W().height) gone(it); },
        tap: it => { it.el.classList.add("caught"); say("소원 빌었다!", 1400); if (b._feel) b._feel("awed", 1400); }});
      it.x = rnd(w.width * .45, w.width + 20); it.y = rnd(-10, 50); draw(it);
      setTimeout(one, rnd(450, 1300));
    };
    if (b._gazeTo) b._gazeTo(4, -5);
    one();
  };

  b._toys = {
    /** Out comes a toy (or the one already out); null on a reduced-motion phone. */
    out(kind) { const had = find(kind); if (had) { had.born = now(); return had; } if (items.filter(it => !it.free).length >= 5) gone(items.find(it => !it.free)); return KIND[kind] ? KIND[kind]() : null; },
    find, throwDice, flies, shower, wake, hand,
    /** A toy's place across the screen (for 퍼레 to walk to it). */
    sx: it => W().left + it.x,
    kick(it, dir) { it.vx = (dir || (Math.random() < .5 ? -1 : 1)) * rnd(380, 640); it.vy = -rnd(380, 600); it.rest = 0; wake(); },
    spin(it) { it.tap(it); },
    count: () => items.length
  };
}

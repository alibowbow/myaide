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
  coral robot whose parts move on their own. Eyes: pills that blink and glance and bend into each face (squashed by a smile, cut by a lid, wide when surprised),
  lines when asleep, hearts when loved; an antenna that springs; cheeks that blush. alive() runs it.
*/
const EYE = '<path class="pill" d="M-3.9 -2.6 C-3.9 -5.5 -2.2 -7.4 0 -7.4 C2.2 -7.4 3.9 -5.5 3.9 -2.6 C3.95 -0.9 3.95 0.9 3.9 2.6 C3.9 5.5 2.2 7.4 0 7.4 C-2.2 7.4 -3.9 5.5 -3.9 2.6 C-3.95 0.9 -3.95 -0.9 -3.9 -2.6Z"/><circle class="gl" cx="-1.3" cy="-3.6" r="1.4"/><path class="arc" d="M-5.5 2.5 Q0 -5 5.5 2.5"/><circle class="o" r="5.4"/><path class="shut" d="M-5.5 1 Q0 3.5 5.5 1"/>'
  + '<path class="heart" d="M0 5 C-7 0 -6 -6 -2.6 -6 C-1.2 -6 -.3 -5 0 -4 C.3 -5 1.2 -6 2.6 -6 C6 -6 7 0 0 5Z"/>'
  + '<path class="dz" d="M0 0 m-.8 0 a.8 .8 0 1 1 1.6 0 a2.2 2.2 0 1 1 -4.4 0 a3.6 3.6 0 1 1 7.2 0 a5 5 0 1 1 -10 0"/><path class="brow" d="M-3.2 -9 Q0 -10.3 3.2 -9"/>'
  // what shines in its eyes (준: "표정만 커비 같은 표정으로", from the expression sheet): a white heart when in love, a star when thrilled
  + '<path class="hl-heart" d="M0 2.2 C-3.4 0 -3.1 -3.4 -1.3 -3.4 C-.6 -3.4 -.1 -2.9 0 -2.4 C.1 -2.9 .6 -3.4 1.3 -3.4 C3.1 -3.4 3.4 0 0 2.2Z"/><path class="hl-star" d="M0 -5 Q.75 -.75 3.3 0 Q.75 .75 0 5 Q-.75 .75 -3.3 0 Q-.75 -.75 0 -5Z"/>'
  + '<path class="x" d="M-4.3 -4.3 L4.3 4.3 M4.3 -4.3 L-4.3 4.3"/><circle class="dot" r="2.7"/><path class="line" d="M-5 0 H5"/>'
  + '<g class="wide"><circle r="6.4"/><circle class="w-gl" cx="-2.2" cy="-2.6" r="2"/><circle class="w-gl" cx="2" cy="2.2" r=".9"/></g>'
  + '<path class="u" d="M-5.5 -1.5 Q0 4.5 5.5 -1.5"/><path class="sq" d="M-4.5 -4.5 L3.5 0 L-4.5 4.5"/><path class="tear" d="M0.6 6.2 q3.4 4.8 0 7.2 q-3.4 -2.4 0 -7.2z"/>'
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
  + '<clipPath id="botClip"><rect x="12" y="18" width="76" height="74.6" rx="30"/></clipPath><linearGradient id="botLower" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#b19bff"/><stop offset="1" stop-color="#8b6ff0"/></linearGradient>'
  + '<radialGradient id="cheekG"><stop offset=".7" stop-color="#ff8ea6"/><stop offset="1" stop-color="#ff8ea6" stop-opacity="0"/></radialGradient>'
  + '<linearGradient id="armL" gradientUnits="userSpaceOnUse" x1="0" y1="18" x2="0" y2="88"><stop offset="0" stop-color="#ffb08f"/><stop offset="1" stop-color="#ff7d58"/></linearGradient>'
  + '<linearGradient id="armR" gradientUnits="userSpaceOnUse" x1="0" y1="18" x2="0" y2="88"><stop offset="0" stop-color="#ffb08f"/><stop offset="1" stop-color="#ff7d58"/></linearGradient>'
  + '</defs>'
  + '<g class="b-all">'
  // behind 퍼레: what it holds high or that floats over it
  // ── GPT outfits (start) ──
  +   '<g class="acc bee"><defs><linearGradient id="bee-w" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#f6fbff"/><stop offset="1" stop-color="#c9ddf4"/></linearGradient></defs><ellipse cx="7" cy="40.8" rx="15.4" ry="14.8" transform="rotate(-20 7 40.8)" fill="url(#bee-w)" fill-opacity=".92" stroke="#6aa6de" stroke-width="1"/><path d="M-1.4 37.2 Q5 40.4 11.6 45" fill="none" stroke="#b6d5f1" stroke-width=".7" stroke-linecap="round"/><path d="M-6 36 C-4.6 31 0 27.4 5.6 27.2" fill="none" stroke="#fff" stroke-width="1" stroke-linecap="round" opacity=".8"/><ellipse cx="93" cy="40.8" rx="15.4" ry="14.8" transform="rotate(20 93 40.8)" fill="url(#bee-w)" fill-opacity=".92" stroke="#6aa6de" stroke-width="1"/><path d="M101.4 37.2 Q95 40.4 88.4 45" fill="none" stroke="#b6d5f1" stroke-width=".7" stroke-linecap="round"/><path d="M106 36 C104.6 31 100 27.4 94.4 27.2" fill="none" stroke="#fff" stroke-width="1" stroke-linecap="round" opacity=".8"/></g>'
  +   '<g class="acc topstar"><path d="M28.25 -2.95L31.75 0.25" stroke="#fff" stroke-opacity=".4" stroke-width="3.8" stroke-linecap="round"/><path d="M71.75 -2.95L68.25 0.25" stroke="#fff" stroke-opacity=".4" stroke-width="3.8" stroke-linecap="round"/><path d="M23.55 6.2L30.15 6.2" stroke="#fff" stroke-opacity=".4" stroke-width="3.8" stroke-linecap="round"/><path d="M76.45 6.2L69.85 6.2" stroke="#fff" stroke-opacity=".4" stroke-width="3.8" stroke-linecap="round"/><path d="M28.25 14.55L31.75 11.85" stroke="#fff" stroke-opacity=".4" stroke-width="3.8" stroke-linecap="round"/><path d="M71.75 14.55L68.25 11.85" stroke="#fff" stroke-opacity=".4" stroke-width="3.8" stroke-linecap="round"/><path d="M48.88 -6.28Q50 -8.1 51.12 -6.28L53.95 -1.68Q54.48 -0.81 55.52 -0.59L60.96 0.56Q63.12 1.02 61.65 2.6L57.96 6.6Q57.26 7.36 57.36 8.37L57.89 13.67Q58.11 15.78 56.08 14.93L50.97 12.81Q50 12.4 49.03 12.81L43.92 14.93Q41.89 15.78 42.11 13.67L42.64 8.37Q42.74 7.36 42.04 6.6L38.35 2.6Q36.88 1.02 39.04 0.56L44.48 -0.59Q45.52 -0.81 46.05 -1.68L48.88 -6.28Z" fill="#fff" fill-opacity=".2" stroke="#fff" stroke-opacity=".2" stroke-width="6" stroke-linejoin="round"/><path d="M48.88 -6.28Q50 -8.1 51.12 -6.28L53.95 -1.68Q54.48 -0.81 55.52 -0.59L60.96 0.56Q63.12 1.02 61.65 2.6L57.96 6.6Q57.26 7.36 57.36 8.37L57.89 13.67Q58.11 15.78 56.08 14.93L50.97 12.81Q50 12.4 49.03 12.81L43.92 14.93Q41.89 15.78 42.11 13.67L42.64 8.37Q42.74 7.36 42.04 6.6L38.35 2.6Q36.88 1.02 39.04 0.56L44.48 -0.59Q45.52 -0.81 46.05 -1.68L48.88 -6.28Z" fill="none" stroke="#fff" stroke-opacity=".35" stroke-width="3.6" stroke-linejoin="round"/></g>'
  // ── GPT outfits (end) ──
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
  // (퍼레's own body: orange above, purple below — 준: "주황로봇이 맞는데 하단 색상이 보라색으로 디자인됐어", "의상이 아니라 기본디자인")
  // "와들디 팔을 참고", "팔이너무 귀같아", "닌텐도 커비처럼", "팔참고", "퍼레 기본디자인에 충실해줘 거기서 팔을 바꾸고"): round arms,
  // rounded mitts widest where they join the body, not the same above and below (준: "왜 베이스가 더 넓어야하는데 베이스가 더
  // 좁아?", "우리는 팔시작부가 뭉툭하자나", "곡선을 좀 더 예쁘게 와들디처럼", "대칭반원이 아니야", "와들디를 잘봐 위아래 대칭이
  // 아냐", "D는 너무 말려들어갔어", "G가 나은듯"): the top flows out of the body's side in one long gentle curve, the fullest part is low and
  // outside, the bottom rounds off and runs back into the body without curling up; the body's own colour at each height (준: "팔색깔이 몸통과 다른것 같아"), softly
  // darker below; turning where they join it.
  +   '<g class="b-arm l"><clipPath id="armClipL"><path d="M11 46.6 C8 48.2 2.4 54.6 1.6 61.6 C1 67.2 3 72.6 7.4 75 C10.4 76.6 13.8 76.6 17 75.8 C21 74.6 22.6 66 22.2 60 C21.8 52 17 43.4 11 46.6Z"/></clipPath><path d="M11 46.6 C8 48.2 2.4 54.6 1.6 61.6 C1 67.2 3 72.6 7.4 75 C10.4 76.6 13.8 76.6 17 75.8 C21 74.6 22.6 66 22.2 60 C21.8 52 17 43.4 11 46.6Z" fill="url(#armL)"/><g class="acc mittens" clip-path="url(#armClipL)"><path d="M11 46.6 C8 48.2 2.4 54.6 1.6 61.6 C1 67.2 3 72.6 7.4 75 C10.4 76.6 13.8 76.6 17 75.8 C21 74.6 22.6 66 22.2 60 C21.8 52 17 43.4 11 46.6Z" fill="#6f9fe8"/><path d="M12.4 49.6 Q9 60.6 13 72.6" fill="none" stroke="#fff" stroke-width="3" stroke-linecap="round"/></g><!--gpt--><g class="acc shark"><defs><linearGradient id="shark-pL" gradientUnits="userSpaceOnUse" x1="0" y1="44" x2="0" y2="66"><stop offset="0" stop-color="#6ca6ef"/><stop offset="1" stop-color="#4880d8"/></linearGradient></defs><path d="M14.4 44.4 C11 44.8 8.6 46.4 6.6 48.8 C4.2 51.6 2.2 55 1.2 58.6 C0.6 60.6 -0.4 63.6 0.4 65 Q1.2 66 3.4 65.6 C7.2 65 10.8 63.6 14.6 62.4Z" fill="url(#shark-pL)" stroke="#3f74c8" stroke-opacity=".4" stroke-width=".6" stroke-linejoin="round"/></g><g class="acc wizard"><defs><radialGradient id="wizard-w" cx=".45" cy=".42" r=".6"><stop stop-color="#fffdf2"/><stop offset=".6" stop-color="#fff1c2"/><stop offset="1" stop-color="#ffe08a"/></radialGradient><linearGradient id="wizard-wr" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#fdd86c"/><stop offset="1" stop-color="#f2aa3a"/></linearGradient></defs><path d="M-0.6 50.4 L5.6 64.6" stroke="#5c3a34" stroke-width="2.9" stroke-linecap="round"/><path d="M-0.2 52 L4.2 62.2" stroke="#8f6156" stroke-width=".8" stroke-linecap="round"/><path d="M-3 38 0.4 42.3 5.9 42.7 2.9 47.3 4.2 52.7 -1.1 51.2 -5.8 54.1 -6.1 48.6 -10.3 45 -5.1 43.1Z" fill="url(#wizard-wr)" stroke="#f6bd48" stroke-width="1.3" stroke-linejoin="round"/><path d="M-2.5 41 -0.2 44 3.7 44.3 1.5 47.5 2.5 51.2 -1.2 50.2 -4.5 52.2 -4.7 48.3 -7.6 45.9 -4 44.5Z" fill="url(#wizard-w)" stroke="#fff3c8" stroke-width="0.8" stroke-linejoin="round"/></g><g class="acc xscarf"><defs><radialGradient id="xscarf-mL" cx=".36" cy=".32" r=".78"><stop offset="0" stop-color="#f45a57"/><stop offset=".55" stop-color="#dc2f38"/><stop offset="1" stop-color="#b21e2a"/></radialGradient><linearGradient id="xscarf-cL" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fffaf8"/><stop offset=".55" stop-color="#fbefee"/><stop offset="1" stop-color="#e9dada"/></linearGradient></defs><ellipse cx="16.9" cy="92.4" rx="3.5" ry="5.2" transform="rotate(-28 16.9 92.4)" fill="url(#xscarf-mL)"/><ellipse cx="7.8" cy="88" rx="9.5" ry="10.1" transform="rotate(14 7.8 88)" fill="url(#xscarf-mL)"/><path d="M13.7 88.2 Q14.7 92.4 13.3 96.4" fill="none" stroke="#a51a28" stroke-width=".85" stroke-linecap="round" opacity=".8"/><path d="M8 88.6 L13.6 88.6 M11.2 88.6 L12.6 87.1 M11.2 88.6 L12.6 90.1 M8 88.6 L10.8 93.4 M9.6 91.4 L11.6 91.8 M9.6 91.4 L9 93.3 M8 88.6 L5.2 93.4 M6.4 91.4 L7 93.3 M6.4 91.4 L4.4 91.8 M8 88.6 L2.4 88.6 M4.8 88.6 L3.4 90.1 M4.8 88.6 L3.4 87.1 M8 88.6 L5.2 83.8 M6.4 85.8 L4.4 85.4 M6.4 85.8 L7 83.9 M8 88.6 L10.8 83.8 M9.6 85.8 L9 83.9 M9.6 85.8 L11.6 85.4" stroke="#fff" stroke-width="1.1" stroke-linecap="round" fill="none"/><rect x="2.9" y="76" width="19.8" height="8.9" rx="4.5" transform="rotate(17 12.8 80.5)" fill="url(#xscarf-cL)"/><path d="M5.9 77.8 Q12.8 77 18.7 78" transform="rotate(17 12.8 80.5)" fill="none" stroke="#fff" stroke-width="1.2" stroke-linecap="round" opacity=".9"/><path d="M5.4 83.5 Q12.8 84.5 20.2 83.5" transform="rotate(17 12.8 80.5)" fill="none" stroke="#d9c6c6" stroke-width="1" stroke-linecap="round" opacity=".7"/></g><g class="acc xsweater"><linearGradient id="xsweater-vL" gradientUnits="userSpaceOnUse" x1="0" y1="72" x2="0" y2="94"><stop offset="0" stop-color="#ea464b"/><stop offset="1" stop-color="#cd2b33"/></linearGradient><linearGradient id="xsweater-kL" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ea4c53"/><stop offset=".5" stop-color="#da333c"/><stop offset="1" stop-color="#bf2630"/></linearGradient><path d="M21 74.2C18 72.5 15.6 72 13.4 72.1C8.9 72.3 4.8 74.4 2.5 78C.7 80.9 -.3 84.6 -.3 88.6L14 96L20 92Z" fill="url(#xsweater-vL)"/><path d="M11.8 73.7C8.4 74 5.5 75.8 3.7 78.6C2.1 81.5 1.2 85 1.1 88.6" fill="none" stroke="#8f1019" stroke-width="2.4" opacity=".16"/><ellipse cx="6.9" cy="75.2" rx="2.8" ry="1.15" transform="rotate(-38 6.9 75.2)" fill="#fff" opacity=".42"/><path d="M2.2 80.5 5.5 80.2 6.5 83.4 9.8 83.1 10.7 86.3 14.1 86.1 15 89.3" fill="none" stroke="#fdf2ec" stroke-width="1.3" stroke-linejoin="round" stroke-linecap="round"/><g transform="rotate(16 7.2 92)"><rect x="-.5" y="86.9" width="15.4" height="10.2" rx="4.6" fill="url(#xsweater-kL)"/><path d="M1.1 88.6V95.4M3 87.6V96.4M4.9 87.5V96.5M6.8 87.5V96.5M8.7 87.5V96.5M10.6 87.5V96.5M12.5 88.1V95.9" stroke="#9a1720" stroke-width=".55" opacity=".7"/><path d="M2 88.6V92.3M4 88.1V92.4M5.9 88.1V92.4M7.8 88.1V92.4M9.7 88.1V92.4M11.6 88.3V92.4M13.5 89.3V92.3" stroke="#f46c6e" stroke-width=".7" stroke-linecap="round" opacity=".45"/><path d="M2.5 87.4H11.9" stroke="#ff9f9c" stroke-width=".6" stroke-linecap="round" opacity=".5"/></g></g><!--/gpt--></g>'
  +   '<g class="b-arm r"><clipPath id="armClipR"><path d="M89 46.6 C92 48.2 97.6 54.6 98.4 61.6 C99 67.2 97 72.6 92.6 75 C89.6 76.6 86.2 76.6 83 75.8 C79 74.6 77.4 66 77.8 60 C78.2 52 83 43.4 89 46.6Z"/></clipPath><path d="M89 46.6 C92 48.2 97.6 54.6 98.4 61.6 C99 67.2 97 72.6 92.6 75 C89.6 76.6 86.2 76.6 83 75.8 C79 74.6 77.4 66 77.8 60 C78.2 52 83 43.4 89 46.6Z" fill="url(#armR)"/><g class="acc mittens" clip-path="url(#armClipR)"><path d="M89 46.6 C92 48.2 97.6 54.6 98.4 61.6 C99 67.2 97 72.6 92.6 75 C89.6 76.6 86.2 76.6 83 75.8 C79 74.6 77.4 66 77.8 60 C78.2 52 83 43.4 89 46.6Z" fill="#6f9fe8"/><path d="M87.6 49.6 Q91 60.6 87 72.6" fill="none" stroke="#fff" stroke-width="3" stroke-linecap="round"/></g><!--gpt--><g class="acc shark"><defs><linearGradient id="shark-pR" gradientUnits="userSpaceOnUse" x1="0" y1="44" x2="0" y2="66"><stop offset="0" stop-color="#6ca6ef"/><stop offset="1" stop-color="#4880d8"/></linearGradient></defs><path d="M85.6 44.4 C89 44.8 91.4 46.4 93.4 48.8 C95.8 51.6 97.8 55 98.8 58.6 C99.4 60.6 100.4 63.6 99.6 65 Q98.8 66 96.6 65.6 C92.8 65 89.2 63.6 85.4 62.4Z" fill="url(#shark-pR)" stroke="#3f74c8" stroke-opacity=".4" stroke-width=".6" stroke-linejoin="round"/></g><g class="acc xscarf"><g transform="matrix(-1 0 0 1 100 0)"><defs><radialGradient id="xscarf-mR" cx=".36" cy=".32" r=".78"><stop offset="0" stop-color="#f45a57"/><stop offset=".55" stop-color="#dc2f38"/><stop offset="1" stop-color="#b21e2a"/></radialGradient><linearGradient id="xscarf-cR" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fffaf8"/><stop offset=".55" stop-color="#fbefee"/><stop offset="1" stop-color="#e9dada"/></linearGradient></defs><ellipse cx="16.9" cy="92.4" rx="3.5" ry="5.2" transform="rotate(-28 16.9 92.4)" fill="url(#xscarf-mR)"/><ellipse cx="7.8" cy="88" rx="9.5" ry="10.1" transform="rotate(14 7.8 88)" fill="url(#xscarf-mR)"/><path d="M13.7 88.2 Q14.7 92.4 13.3 96.4" fill="none" stroke="#a51a28" stroke-width=".85" stroke-linecap="round" opacity=".8"/><path d="M8 88.6 L13.6 88.6 M11.2 88.6 L12.6 87.1 M11.2 88.6 L12.6 90.1 M8 88.6 L10.8 93.4 M9.6 91.4 L11.6 91.8 M9.6 91.4 L9 93.3 M8 88.6 L5.2 93.4 M6.4 91.4 L7 93.3 M6.4 91.4 L4.4 91.8 M8 88.6 L2.4 88.6 M4.8 88.6 L3.4 90.1 M4.8 88.6 L3.4 87.1 M8 88.6 L5.2 83.8 M6.4 85.8 L4.4 85.4 M6.4 85.8 L7 83.9 M8 88.6 L10.8 83.8 M9.6 85.8 L9 83.9 M9.6 85.8 L11.6 85.4" stroke="#fff" stroke-width="1.1" stroke-linecap="round" fill="none"/><rect x="2.9" y="76" width="19.8" height="8.9" rx="4.5" transform="rotate(17 12.8 80.5)" fill="url(#xscarf-cR)"/><path d="M5.9 77.8 Q12.8 77 18.7 78" transform="rotate(17 12.8 80.5)" fill="none" stroke="#fff" stroke-width="1.2" stroke-linecap="round" opacity=".9"/><path d="M5.4 83.5 Q12.8 84.5 20.2 83.5" transform="rotate(17 12.8 80.5)" fill="none" stroke="#d9c6c6" stroke-width="1" stroke-linecap="round" opacity=".7"/></g></g><g class="acc xsweater"><g transform="matrix(-1 0 0 1 100 0)"><linearGradient id="xsweater-vR" gradientUnits="userSpaceOnUse" x1="0" y1="72" x2="0" y2="94"><stop offset="0" stop-color="#ea464b"/><stop offset="1" stop-color="#cd2b33"/></linearGradient><linearGradient id="xsweater-kR" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ea4c53"/><stop offset=".5" stop-color="#da333c"/><stop offset="1" stop-color="#bf2630"/></linearGradient><path d="M21 74.2C18 72.5 15.6 72 13.4 72.1C8.9 72.3 4.8 74.4 2.5 78C.7 80.9 -.3 84.6 -.3 88.6L14 96L20 92Z" fill="url(#xsweater-vR)"/><path d="M11.8 73.7C8.4 74 5.5 75.8 3.7 78.6C2.1 81.5 1.2 85 1.1 88.6" fill="none" stroke="#8f1019" stroke-width="2.4" opacity=".16"/><ellipse cx="6.9" cy="75.2" rx="2.8" ry="1.15" transform="rotate(-38 6.9 75.2)" fill="#fff" opacity=".42"/><path d="M2.2 80.5 5.5 80.2 6.5 83.4 9.8 83.1 10.7 86.3 14.1 86.1 15 89.3" fill="none" stroke="#fdf2ec" stroke-width="1.3" stroke-linejoin="round" stroke-linecap="round"/><g transform="rotate(16 7.2 92)"><rect x="-.5" y="86.9" width="15.4" height="10.2" rx="4.6" fill="url(#xsweater-kR)"/><path d="M1.1 88.6V95.4M3 87.6V96.4M4.9 87.5V96.5M6.8 87.5V96.5M8.7 87.5V96.5M10.6 87.5V96.5M12.5 88.1V95.9" stroke="#9a1720" stroke-width=".55" opacity=".7"/><path d="M2 88.6V92.3M4 88.1V92.4M5.9 88.1V92.4M7.8 88.1V92.4M9.7 88.1V92.4M11.6 88.3V92.4M13.5 89.3V92.3" stroke="#f46c6e" stroke-width=".7" stroke-linecap="round" opacity=".45"/><path d="M2.5 87.4H11.9" stroke="#ff9f9c" stroke-width=".6" stroke-linecap="round" opacity=".5"/></g></g></g><!--/gpt--></g>'
  +   '<g class="b-antenna"><line x1="50" y1="20" x2="50" y2="8" stroke="#ff6640" stroke-width="4" stroke-linecap="round"/><circle class="b-bulb" cx="50" cy="5" r="5.9"/>'
  +     '<g class="acc flower"><circle cx="50" cy="1.5" r="3.4" fill="#ffb3c8"/><circle cx="55" cy="5.5" r="3.4" fill="#ffb3c8"/><circle cx="53" cy="11" r="3.4" fill="#ffb3c8"/><circle cx="47" cy="11" r="3.4" fill="#ffb3c8"/><circle cx="45" cy="5.5" r="3.4" fill="#ffb3c8"/><circle cx="50" cy="7" r="2.6" fill="#ffd36e"/></g></g>'
  +   '<g class="b-body"><rect x="12" y="18" width="76" height="74.6" rx="30" fill="url(#botBody)"/><rect x="12" y="18" width="76" height="74.6" rx="30" fill="url(#botShine)"/><path class="b-lower" clip-path="url(#botClip)" d="M8 74 C10 70.6 14.6 67.2 19.6 67.2 Q50 79.6 80.4 67.2 C85.4 67.2 90 70.6 92 74 L92 97 L8 97Z" fill="url(#botLower)"/>'
  +     '<g class="acc blanket" clip-path="url(#botClip)"><path d="M11 60 Q50 52 89 60 L90 90 Q50 98 10 90Z" fill="#9fc8ff"/><g fill="#fff" opacity=".6"><circle cx="24" cy="70" r="2"/><circle cx="40" cy="78" r="2"/><circle cx="58" cy="70" r="2"/><circle cx="74" cy="80" r="2"/><circle cx="32" cy="86" r="2"/><circle cx="66" cy="88" r="2"/></g></g>'
  +     '<g class="acc raincoat" clip-path="url(#botClip)"><path d="M12 62 Q50 56 88 62 L90 90 Q50 98 10 90Z" fill="#ffd23f"/><path d="M50 60 V95" stroke="#e8b923" stroke-width="1.4"/><circle cx="46" cy="74" r="1.4" fill="#e8b923"/><circle cx="46" cy="84" r="1.4" fill="#e8b923"/></g>'
  +     '<g class="acc sweater" clip-path="url(#botClip)"><path d="M12 64 Q50 58 88 64 L89 90 Q50 98 11 90Z" fill="#8fb7e8"/><path d="M14 72 Q50 66 86 72 M13 82 Q50 76 87 82" fill="none" stroke="#fff" stroke-width="2" stroke-dasharray="3 3"/></g>'
  +     '<g class="acc apron" clip-path="url(#botClip)"><path d="M30 64 H70 L72 92 Q50 96 28 92Z" fill="#fff" stroke="#e3e5ee" stroke-width="1"/><rect x="40" y="76" width="20" height="9" rx="2" fill="none" stroke="#ff8fa3" stroke-width="1.4"/></g>'
  +     '<g class="acc pajamas" clip-path="url(#botClip)"><path d="M12 64 Q50 58 88 64 L89 90 Q50 98 11 90Z" fill="#b8c4ff"/><path d="M22 63 V94 M34 61 V96 M46 60 V97 M58 60 V97 M70 61 V96 M82 63 V93" stroke="#fff" stroke-width="2.4" opacity=".55"/></g>'
  +     '<g class="acc hoodie" clip-path="url(#botClip)"><path d="M12 64 Q50 58 88 64 L89 90 Q50 98 11 90Z" fill="#a78bfa"/><path d="M40 64 Q50 74 60 64" fill="none" stroke="#8b6ff0" stroke-width="2"/><path d="M44 66 V76 M56 66 V76" stroke="#fff" stroke-width="1.4" stroke-linecap="round"/><rect x="36" y="80" width="28" height="8" rx="3" fill="#8b6ff0"/></g>'
  +     '<g class="acc backpack" clip-path="url(#botClip)"><path d="M19 30 Q16 58 21 86 M81 30 Q84 58 79 86" stroke="#3a9fcb" stroke-width="4.5" fill="none" stroke-linecap="round"/></g>'
  +     '<path class="b-face" d="M50 29.8 C67.5 29.8 78.3 35 78.4 47.5 C78.5 56 77.2 62.5 75.6 69.4 Q50 78.8 24.4 69.4 C22.8 62.5 21.5 56 21.6 47.5 C21.7 35 32.5 29.8 50 29.8Z" fill="#fff" opacity=".97"/>'
  +     '<path class="fx fx-pale" d="M50 29.8 C67.5 29.8 78.3 35 78.4 47.5 C78.5 56 77.2 62.5 75.6 69.4 Q50 78.8 24.4 69.4 C22.8 62.5 21.5 56 21.6 47.5 C21.7 35 32.5 29.8 50 29.8Z" fill="url(#botPale)"/><path class="fx fx-red" d="M50 29.8 C67.5 29.8 78.3 35 78.4 47.5 C78.5 56 77.2 62.5 75.6 69.4 Q50 78.8 24.4 69.4 C22.8 62.5 21.5 56 21.6 47.5 C21.7 35 32.5 29.8 50 29.8Z" fill="#ff6b6b" opacity=".16"/>'
  +     '<g class="fx fx-gloom"><path d="M32 33 v7 M40 33 v9 M48 33 v6 M56 33 v9 M64 33 v7" stroke="#8f8fd6" stroke-width="1.6" stroke-linecap="round" opacity=".7"/></g>'
  +     '<g class="b-look"><g class="b-eye l" transform="translate(38 49.6) scale(1.13)">' + EYE + '</g><g class="b-eye r" transform="translate(62 49.6) scale(-1.13 1.13)">' + EYE + '</g>'
  +       '<path class="b-mouth" d="M44.8 60.2 Q50 64.8 55.2 60.2"/><ellipse class="m-open" cx="50" cy="61.5" rx="3.6" ry="3.2"/><path class="m-frown" d="M45.5 63 Q50 58.5 54.5 63"/>'
  +       '<path class="m-wave" d="M43.5 61 q1.6 -2 3.2 0 t3.2 0 t3.2 0 t3.2 0"/><path class="m-chew" d="M46 60.5 Q50 63.5 54 60.5 Q50 61.8 46 60.5Z"/>'
  +       '<path class="m-flat" d="M46 61 H54"/><path class="m-grin" d="M43.5 59 H56.5 Q56 66 50 66 Q44 66 43.5 59Z"/>'
  +       '<g class="m-tongue"><path d="M45.5 60 Q50 64.5 54.5 60" fill="none"/><path d="M48.2 62.2 q0 4 2.4 4 q2.4 0 2.2 -4.6z" fill="#ff7c93"/></g>'
  +       '<ellipse class="m-osm" cx="50" cy="61.6" rx="2.2" ry="2.5"/><path class="m-cat" d="M44.5 60 q2.75 3.2 5.5 0 q2.75 3.2 5.5 0"/>'
  +       '<g class="m-teeth"><rect x="44" y="58.6" width="12" height="5.6" rx="2"/><path d="M44 61.4 H56 M48 58.6 V64.2 M52 58.6 V64.2"/></g>'
  +       '<path class="m-pout" d="M48.5 59.5 q3 1.5 0 2.8 q3 1.4 0 2.8"/><path class="m-kiss" d="M49 59 q3 1.6 0 3 q3 1.6 0 3"/><path class="m-smirk" d="M46 61.5 Q51 62.5 55 58.8"/>'
  +       '<g class="m-drool"><path d="M45.5 60 Q50 64.5 54.5 60" fill="none"/><path d="M53.2 61.6 q1.6 3 0 4.6 q-1.6 -1.6 0 -4.6z" fill="#8fd3ff"/></g>'
  +       '<path class="m-tri" d="M45 58.5 H55 L50 66.5Z"/><path class="m-zig" d="M43 61.5 l2.4 -2 l2.4 2 l2.4 -2 l2.4 2 l2.4 -2 l2.4 2"/>'
  +       '<g class="m-bite"><path d="M45 61 H55" fill="none"/><path d="M47.6 61 v2.2 h2 v-2.2 M50.4 61 v2.2 h2 v-2.2" fill="#fff"/></g>'
  +       '<path class="m-wide" d="M41.5 58 H58.5 Q57.5 68 50 68 Q42.5 68 41.5 58Z"/>'
  // the tongue in an open mouth (준: "단선끄적임" — a mouth with something in it, not only a dark shape)
  +       '<ellipse class="m-tg tg-open" cx="50" cy="63.7" rx="2.3" ry="1.2"/><ellipse class="m-tg tg-osm" cx="50" cy="62.9" rx="1.3" ry=".8"/><ellipse class="m-tg tg-grin" cx="50" cy="64.1" rx="2.8" ry="1.4"/><ellipse class="m-tg tg-wide" cx="50" cy="65.4" rx="3.8" ry="1.8"/>'
  // more mouths (준: "입모양 … 아직 부족"): small, each its own
  +       '<g class="m-haha"><path d="M44 59 H56 Q55.6 66.4 50 66.4 Q44.4 66.4 44 59Z" fill="#5a2134" stroke="#5a2134" stroke-width="1"/><path d="M44.5 58.9 H55.5 Q55.5 60.7 54.4 60.7 H45.6 Q44.5 60.7 44.5 58.9Z" fill="#fff" stroke="none"/><ellipse cx="50" cy="64.6" rx="2.9" ry="1.4" fill="#ff8597" stroke="none"/></g><g class="m-yay"><path d="M44.4 58.8 Q50 58.2 55.6 58.8 Q56.6 59 56 60.2 L51.5 66.1 Q50 67.6 48.5 66.1 L44 60.2 Q43.4 59 44.4 58.8Z" fill="#5a2134" stroke="none"/><path d="M47.9 64.4 Q50 62.9 52.1 64.4 L51.3 65.5 Q50 66.8 48.7 65.5Z" fill="#ff8597" stroke="none"/></g><g class="m-nya"><path d="M47.3 61 H52.7 Q52.5 65.6 50 65.6 Q47.5 65.6 47.3 61Z" fill="#5a2134" stroke="none"/><ellipse cx="50" cy="64.3" rx="1.5" ry=".8" fill="#ff8597" stroke="none"/><path d="M44.4 60 q2.8 3 5.6 0 q2.8 3 5.6 0"/></g><g class="m-gasp"><ellipse cx="50" cy="62.2" rx="2.8" ry="4.1" fill="#5a2134" stroke="none"/><ellipse cx="50" cy="64.8" rx="1.6" ry=".95" fill="#ff8597" stroke="none"/></g><g class="m-ooh"><circle cx="50" cy="61.8" r="1.9" stroke-width="1.7"/></g><g class="m-blep"><path d="M48.5 61 q0 3.2 1.5 3.2 q1.5 0 1.5 -3.2z" fill="#ff8597" stroke="none"/><path d="M50 61.6 V63" stroke="#ec6079" stroke-width=".6"/><path d="M46.2 61 H53.8"/></g><g class="m-pant"><path d="M44.5 58.8 H55.5 Q55 64.8 50 64.8 Q45 64.8 44.5 58.8Z" fill="#5a2134" stroke="none"/><path d="M47.6 62.2 Q50 61.2 52.4 62.2 V65.6 Q52.4 68 50 68 Q47.6 68 47.6 65.6Z" fill="#ff8597" stroke="none"/><path d="M50 63.2 V65.8" stroke="#ec6079" stroke-width=".8"/></g><g class="m-sob"><path d="M44.2 65.4 Q44.4 59.4 50 59.3 Q55.6 59.4 55.8 65.4 Q55 66.1 53.9 65.2 Q50 62.9 46.1 65.2 Q45 66.1 44.2 65.4Z" fill="#5a2134" stroke="none"/><path d="M47.2 64.2 Q50 61.9 52.8 64.2 Q50 63.5 47.2 64.2Z" fill="#ff8597" stroke="none"/></g><g class="m-eep"><path d="M46.2 64 Q46.4 59.4 50 59.4 Q53.6 59.4 53.8 64 Q50 63.1 46.2 64Z" fill="#5a2134" stroke="none"/><ellipse cx="50" cy="62.8" rx="1.6" ry=".6" fill="#ff8597" stroke="none"/></g><g class="m-smooch"><path d="M50 65.2 C46.6 63.2 46 60 48.1 59.5 C49 59.3 49.7 59.9 50 60.6 C50.3 59.9 51 59.3 51.9 59.5 C54 60 53.4 63.2 50 65.2Z" fill="#ff6f91" stroke="none"/></g><g class="m-hmm"><path d="M48.4 60.2 Q48.6 61.5 49.8 61.5 H54.4"/></g><g class="m-squig"><path d="M46.4 61.6 Q48.2 59.6 50 61.2 Q51.8 62.8 53.6 60.8"/></g><g class="m-bunny"><rect x="48.1" y="61.5" width="1.9" height="2.9" rx=".6" fill="#fff" stroke-width=".8"/><rect x="50" y="61.5" width="1.9" height="2.9" rx=".6" fill="#fff" stroke-width=".8"/><path d="M45 60.2 Q50 64.6 55 60.2"/></g><g class="m-nom"><g class="nm-o"><ellipse cx="50" cy="62" rx="2.6" ry="2.4" fill="#5a2134" stroke="none"/><ellipse cx="50" cy="63.5" rx="1.5" ry=".75" fill="#ff8597" stroke="none"/></g><path class="nm-c" d="M47 61.4 Q50 63.4 53 61.4"/></g><g class="m-wee"><path d="M47.3 60.2 H52.7 Q52.4 64 50 64 Q47.6 64 47.3 60.2Z" fill="#5a2134" stroke="none"/><ellipse cx="50" cy="63" rx="1.4" ry=".7" fill="#ff8597" stroke="none"/></g><g class="m-fang"><path d="M51.3 62.3 L54.7 61.1 L53.5 65Z" fill="#fff" stroke-width=".8"/><path d="M45 60.2 Q50 64.6 55 60.2"/></g><g class="m-square"><rect x="46.6" y="59.2" width="6.8" height="5.4" rx="1.7" fill="#5a2134" stroke="none"/><ellipse cx="50" cy="63.5" rx="2.2" ry=".85" fill="#ff8597" stroke="none"/></g><g class="m-lopsy"><path d="M45.4 60.2 Q50 61 55.6 58.6 Q55.4 64.8 50.6 64.6 Q46.6 64.4 45.4 60.2Z" fill="#5a2134" stroke="none"/><ellipse cx="51.6" cy="63.4" rx="2" ry=".9" fill="#ff8597" stroke="none" transform="rotate(-12 51.6 63.4)"/></g><circle class="m-whistle" cx="53" cy="61" r="1.6"/>'
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
  +     '<circle class="b-cheek" cx="26.9" cy="63.4" r="6.4"/><circle class="b-cheek" cx="73.1" cy="63.4" r="6.4"/>'
  +     '<g class="fx fx-blush"><path d="M24 58 l-2 4 M28 58 l-2 4 M32 58 l-2 4 M68 58 l-2 4 M72 58 l-2 4 M76 58 l-2 4" stroke="#ff7c93" stroke-width="1.5" stroke-linecap="round"/></g>'
  +     '<g class="fx fx-stream"><rect x="36.5" y="54" width="5" height="16" rx="2.5" fill="#8fd3ff" opacity=".85"/><rect x="58.5" y="54" width="5" height="16" rx="2.5" fill="#8fd3ff" opacity=".85"/></g>'
  +     '<path class="fx fx-kiss" d="M64 58 c-3 -2 -3 -5 -1 -5 c.8 0 1 .6 1 1 c0 -.4 .2 -1 1 -1 c2 0 2 3 -1 5z" fill="#ff6f91"/>'
  +     '<g class="acc scarf"><path d="M16 73 Q50 86 84 73 L85 81 Q50 94 15 81Z" fill="#6f9fe8"/><path d="M15 77 Q50 89 85 77" fill="none" stroke="#fff" stroke-opacity=".45" stroke-width="1.6" stroke-dasharray="3 3"/><rect x="62" y="80" width="10" height="17" rx="3.5" fill="#5d8bd6"/></g>'
  +     '<g class="acc bib"><path d="M33 75 Q50 83.4 67 75 L64.6 84.6 Q50 93 35.4 84.6Z" fill="#a9dcff" stroke="#6fb8ec" stroke-width="1.1" stroke-linejoin="round"/><path d="M35.6 82.6 Q50 90.4 64.4 82.6" fill="none" stroke="#fff" stroke-width="1" stroke-dasharray="1.6 1.4" opacity=".85"/><path d="M50 83.4 c-3 -2 -3 -5 -1 -5 c.8 0 1 .6 1 1 c0 -.4 .2 -1 1 -1 c2 0 2 3 -1 5z" fill="#ff6f91"/></g>'
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
  // ── GPT outfits (start) ──
  // (준: "이런식으로 의상 소품들도 만들자", "재미난 의상들 많이 만들어보자", "크리스마스 관련도 만들고"): drawn from GPT's sheets, over the body and face
  +   '<g class="acc antlers"><defs><linearGradient id="antlers-a" x1="0" y1="0" x2="1" y2=".3"><stop offset="0" stop-color="#8e5a3a"/><stop offset=".55" stop-color="#7a4a2f"/><stop offset="1" stop-color="#673a23"/></linearGradient><linearGradient id="antlers-eo" x1="0" y1="0" x2=".4" y2="1"><stop offset="0" stop-color="#d48d5a"/><stop offset=".6" stop-color="#c67a47"/><stop offset="1" stop-color="#b0623a"/></linearGradient><linearGradient id="antlers-ei" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#e49b77"/><stop offset=".4" stop-color="#fbc6a9"/><stop offset="1" stop-color="#fdd4bc"/></linearGradient><radialGradient id="antlers-n" cx=".34" cy=".26" r=".85"><stop offset="0" stop-color="#ff8c8e"/><stop offset=".32" stop-color="#f9444b"/><stop offset=".7" stop-color="#e63a41"/><stop offset="1" stop-color="#b9222e"/></radialGradient></defs><path d="M28.4 21.6 C26.6 17.4 25 14.6 22.4 12.4 C19 10.4 14.6 8.8 11.6 6 C9.6 4 8.8 2 8.8 0 C8.8 -2 10.2 -3 12.2 -3 C14.2 -3 15.6 -1.8 15.8 0 C15.9 1 16.2 1.8 16.6 1.8 C16.8 1 16.5 -1 16.5 -3 L16.6 -8 C16.6 -10.6 18.6 -12.4 21 -12.4 C23.4 -12.4 25.2 -10.8 25.2 -8.6 L24.2 -4 L24 0.6 C24.2 2 24.8 3.4 25.6 3.4 C26.2 3.4 26.2 2.4 26.6 1.2 C27 -0.8 27.6 -2 28.4 -2.8 C29.4 -3.8 31.2 -3.8 32.4 -3.2 C33.6 -2.6 34.2 -1.4 34 0.4 C33.8 2.4 32.6 3.4 32 4.6 C31.2 6.4 31 8.6 31.2 10 C31.4 12 32.4 13.6 33.2 15 C34 16.6 34.4 19 34.4 21.6Z" fill="url(#antlers-a)" stroke="#fff" stroke-opacity=".75" stroke-width="2.6" stroke-linejoin="round" paint-order="stroke"/><path d="M16.7 1.4 Q17.4 4 19.6 6.2 M25.5 3 Q25.7 5.6 26.8 7.6" fill="none" stroke="#4e2a18" stroke-opacity=".35" stroke-width="1.1" stroke-linecap="round"/><path d="M11.2 -0.8 Q11.6 -1.8 12.6 -2 M19 -9.4 Q19.6 -10.8 21.2 -11.2 M29.4 -1.6 Q30 -2.6 31.2 -2.8" fill="none" stroke="#b07a55" stroke-opacity=".5" stroke-width="1.5" stroke-linecap="round"/><path d="M71.6 21.6 C73.4 17.4 75 14.6 77.6 12.4 C81 10.4 85.4 8.8 88.4 6 C90.4 4 91.2 2 91.2 0 C91.2 -2 89.8 -3 87.8 -3 C85.8 -3 84.4 -1.8 84.2 0 C84.1 1 83.8 1.8 83.4 1.8 C83.2 1 83.5 -1 83.5 -3 L83.4 -8 C83.4 -10.6 81.4 -12.4 79 -12.4 C76.6 -12.4 74.8 -10.8 74.8 -8.6 L75.8 -4 L76 0.6 C75.8 2 75.2 3.4 74.4 3.4 C73.8 3.4 73.8 2.4 73.4 1.2 C73 -0.8 72.4 -2 71.6 -2.8 C70.6 -3.8 68.8 -3.8 67.6 -3.2 C66.4 -2.6 65.8 -1.4 66 0.4 C66.2 2.4 67.4 3.4 68 4.6 C68.8 6.4 69 8.6 68.8 10 C68.6 12 67.6 13.6 66.8 15 C66 16.6 65.6 19 65.6 21.6Z" fill="url(#antlers-a)" stroke="#fff" stroke-opacity=".75" stroke-width="2.6" stroke-linejoin="round" paint-order="stroke"/><path d="M83.3 1.4 Q82.6 4 80.4 6.2 M74.5 3 Q74.3 5.6 73.2 7.6" fill="none" stroke="#4e2a18" stroke-opacity=".35" stroke-width="1.1" stroke-linecap="round"/><path d="M88.8 -0.8 Q88.4 -1.8 87.4 -2 M81 -9.4 Q80.4 -10.8 78.8 -11.2 M70.6 -1.6 Q70 -2.6 68.8 -2.8" fill="none" stroke="#b07a55" stroke-opacity=".5" stroke-width="1.5" stroke-linecap="round"/><path d="M5 15.6 C5.4 12.6 8.6 11.4 12.6 11.4 C17.6 11.4 22.4 13.4 25.6 17.2 L27 22 L22 28.6 C19.6 29.2 16.8 29 14.4 28 C9.4 26 5.4 21.4 5 15.6Z" fill="url(#antlers-eo)" stroke="#fff" stroke-opacity=".7" stroke-width="2.4" paint-order="stroke"/><path d="M9.4 17.4 C10.6 16.2 13 16.2 15.6 17 C19 18 21.6 20.2 22.8 23 C21.8 25.4 18.6 26 15.8 25.2 C12.6 24.2 10 21 9.4 17.4Z" fill="url(#antlers-ei)"/><path d="M95 15.6 C94.6 12.6 91.4 11.4 87.4 11.4 C82.4 11.4 77.6 13.4 74.4 17.2 L73 22 L78 28.6 C80.4 29.2 83.2 29 85.6 28 C90.6 26 94.6 21.4 95 15.6Z" fill="url(#antlers-eo)" stroke="#fff" stroke-opacity=".7" stroke-width="2.4" paint-order="stroke"/><path d="M90.6 17.4 C89.4 16.2 87 16.2 84.4 17 C81 18 78.4 20.2 77.2 23 C78.2 25.4 81.4 26 84.2 25.2 C87.4 24.2 90 21 90.6 17.4Z" fill="url(#antlers-ei)"/><g fill="none" stroke-linecap="round"><path d="M16 37.8 C16.2 31 22 25.4 34 19.6 C40 17.8 45 16.9 50 16.9 C55 16.9 60 17.8 66 19.6 C78 25.4 83.8 31 84 37.8" stroke="#5f3420" stroke-width="5"/><path d="M16 37.8 C16.2 31 22 25.4 34 19.6 C40 17.8 45 16.9 50 16.9 C55 16.9 60 17.8 66 19.6 C78 25.4 83.8 31 84 37.8" transform="translate(0 -.8)" stroke="#6f4027" stroke-width="3.4"/><path d="M16 37.8 C16.2 31 22 25.4 34 19.6 C40 17.8 45 16.9 50 16.9 C55 16.9 60 17.8 66 19.6 C78 25.4 83.8 31 84 37.8" transform="translate(0 -1.5)" stroke="#8c5636" stroke-width="1.8"/></g><ellipse cx="50" cy="61.7" rx="6.3" ry="4.9" fill="url(#antlers-n)"/><ellipse cx="47.4" cy="58.7" rx="2" ry="1.1" transform="rotate(-18 47.4 58.7)" fill="#fff" opacity=".85"/></g>'
  +   '<g class="acc astro"><defs><radialGradient id="astro-g1" cx=".5" cy=".46" r=".5"><stop offset="0" stop-color="#ffffff" stop-opacity=".08"/><stop offset=".6" stop-color="#f2f0ff" stop-opacity=".16"/><stop offset=".86" stop-color="#cdc9f4" stop-opacity=".38"/><stop offset="1" stop-color="#b8b3ea" stop-opacity=".55"/></radialGradient><linearGradient id="astro-g2" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffffff"/><stop offset=".55" stop-color="#f2f1f8"/><stop offset="1" stop-color="#d6d4e6"/></linearGradient><radialGradient id="astro-g3" cx=".38" cy=".35" r=".7"><stop offset="0" stop-color="#ffe68a"/><stop offset="1" stop-color="#ffc93a"/></radialGradient><linearGradient id="astro-g4" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#a48cfb"/><stop offset="1" stop-color="#8a6cee"/></linearGradient></defs><line x1="50" y1="19" x2="50" y2="-6" stroke="#ff6640" stroke-width="4" stroke-linecap="round"/><circle class="b-bulb" cx="50" cy="-8.8" r="5.7" fill="#ffd36e"/><circle cx="48" cy="-10.8" r="1.6" fill="#fff" opacity=".55"/><ellipse cx="50" cy="46" rx="47" ry="42.8" fill="url(#astro-g1)"/><ellipse cx="50" cy="46" rx="45.6" ry="41.4" fill="none" stroke="#c7c3ef" stroke-width="2.4" opacity=".45"/><ellipse cx="50" cy="46" rx="47" ry="42.8" fill="none" stroke="#a9a5d8" stroke-width="1.3"/><ellipse cx="50" cy="46" rx="45.2" ry="41" fill="none" stroke="#fff" stroke-width=".8" opacity=".75"/><path d="M19.7 28.3 Q26 19.5 35.7 14.9" fill="none" stroke="#fff" stroke-width="5.8" stroke-linecap="round" opacity=".88"/><ellipse cx="43.7" cy="12.9" rx="2.7" ry="1.9" transform="rotate(-12 43.7 12.9)" fill="#fff" opacity=".9"/><path d="M13.9 37.6 L17.4 33" fill="none" stroke="#fff" stroke-width="3.8" stroke-linecap="round" opacity=".85"/><path d="M13.5 79 C14.5 89.5 30 95 50 95 C70 95 85.5 89.5 86.5 79Z" fill="url(#astro-g4)"/><path d="M11 69 Q50 84 89 69 C92.4 70 92 78 88 79 Q50 99 12 79 C8 78 7.6 70 11 69Z" fill="url(#astro-g2)" stroke="#c6c3da" stroke-width=".7"/><path d="M12.4 70.6 Q50 85 87.6 70.6" fill="none" stroke="#fff" stroke-width="1" opacity=".9"/><path d="M14.5 76.3 Q22 79.6 30.5 81 M85.5 76.3 Q78 79.6 69.5 81" fill="none" stroke="#c9c7dc" stroke-width="4.4" stroke-linecap="round"/><path d="M14.5 76.3 Q22 79.6 30.5 81 M85.5 76.3 Q78 79.6 69.5 81" fill="none" stroke="#e1e0ec" stroke-width="3.4" stroke-linecap="round"/><rect x="37.7" y="80.3" width="10" height="4.2" rx="2.1" fill="#ffc928" stroke="#e8a800" stroke-width=".5"/><circle cx="55" cy="82.5" r="2.2" fill="#8b5cf6"/><circle cx="61" cy="81.9" r="2.2" fill="#8b5cf6"/></g>'
  +   '<g class="acc banana"><defs><linearGradient id="banana-p" gradientUnits="userSpaceOnUse" x1="0" y1="2" x2="0" y2="92"><stop offset="0" stop-color="#fee76f"/><stop offset=".45" stop-color="#fedd5c"/><stop offset="1" stop-color="#fdd24b"/></linearGradient><linearGradient id="banana-f" gradientUnits="userSpaceOnUse" x1="0" y1="4" x2="0" y2="48"><stop offset="0" stop-color="#feea8e"/><stop offset=".55" stop-color="#fee67c"/><stop offset="1" stop-color="#fee06a" stop-opacity="0"/></linearGradient><linearGradient id="banana-d" gradientUnits="userSpaceOnUse" x1="0" y1="50" x2="0" y2="92"><stop offset="0" stop-color="#fcc33c"/><stop offset="1" stop-color="#fab933"/></linearGradient><linearGradient id="banana-l" gradientUnits="userSpaceOnUse" x1="0" y1="21" x2="0" y2="79"><stop offset=".1" stop-color="#fee88b"/><stop offset=".32" stop-color="#fee26e" stop-opacity="0"/><stop offset=".7" stop-color="#fee27a" stop-opacity="0"/><stop offset=".88" stop-color="#feeea6"/><stop offset="1" stop-color="#fee98e"/></linearGradient><linearGradient id="banana-r" gradientUnits="userSpaceOnUse" x1="0" y1="27" x2="0" y2="76"><stop offset="0" stop-color="#f48e6e"/><stop offset=".7" stop-color="#fa9674"/><stop offset=".86" stop-color="#fdd394"/><stop offset="1" stop-color="#feeaa4"/></linearGradient><linearGradient id="banana-s" x1="0" y1="0" x2="1" y2=".4"><stop offset="0" stop-color="#a5603f"/><stop offset=".55" stop-color="#93522f"/><stop offset="1" stop-color="#874a2e"/></linearGradient><linearGradient id="banana-b" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fee07a"/><stop offset="1" stop-color="#fcc844"/></linearGradient><radialGradient id="banana-h" cx=".5" cy=".5" r=".5"><stop offset="0" stop-color="#fff2a6" stop-opacity=".45"/><stop offset="1" stop-color="#fff3b0" stop-opacity="0"/></radialGradient></defs><rect x="47.6" y="-2.3" width="2.8" height="12.8" rx="1.2" fill="#fd6a3f"/><circle cx="49" cy="-2.3" r="4.4" fill="url(#banana-b)"/><path d="M46.1 -3.9 Q47 -5.9 49.2 -6.1" fill="none" stroke="#fff" stroke-width="1" stroke-linecap="round" opacity=".6"/><path fill-rule="evenodd" fill="url(#banana-p)" d="M77.6 7.6 C77.5 8.4 76.3 9.9 76.8 12.2S79 18 80.6 21.4S85.2 28.6 86.6 32.4S88.6 40.5 89 44.4S89.3 52.2 88.9 56S87.8 63.6 86.4 67.4S83.1 75.8 80.6 79S74.8 84.6 71.4 86.6S63.9 89.8 60 90.8S51.8 92.4 48 92.7S40.4 92.8 37 92.5S29 90.9 27.4 90.6 L17.1 81.4 C16.9 80.8 16.5 80 15.7 78S13.2 72.7 12.3 69.6S10.7 63.1 10.3 59.4S9.8 51.2 10.1 47.4S11.2 40.2 12.4 36.8S15.2 30.1 17.2 27.2S21.7 21.4 24.4 19.2S30.1 15.3 33.2 13.8S39.8 11.4 43 10.2S50.1 7.9 52.6 6.8S56.7 4.6 58.2 3.6S60.9 1.4 61.4 .9Z M50 27.8 C68.6 27.8 79.9 33.6 80 47.5 C80.1 56.4 78.6 63.2 76.9 70.4 Q50 79 23.1 70.4 C21.4 63.2 19.9 56.4 20 47.5 C20.1 33.6 31.4 27.8 50 27.8Z"/><path fill="url(#banana-d)" stroke="#eaa62c" stroke-width=".7" stroke-opacity=".45" d="M73.4 5.6 C73.5 7.1 72.9 11.3 73.8 14.6S77.2 21.6 78.8 25.4S82.4 33.4 83.4 37.6S84.7 46.3 84.6 50.6S84 59.6 82.8 63.4S80 70.7 77.6 73.6S72.2 78.7 68.6 80.6S60.4 83.7 56 84.8S46.6 86.4 42 87.2S30.8 89.4 28.6 89.8 L27.4 90.6 C29 90.9 33.6 92.2 37 92.5S44.2 93 48 92.7S56.1 91.8 60 90.8S68 88.6 71.4 86.6S78.1 82.2 80.6 79S85 71.2 86.4 67.4S88.5 59.8 88.9 56S89.4 48.3 89 44.4S88 36.2 86.6 32.4S82.2 24.8 80.6 21.4S77.3 14.5 76.8 12.2S77.5 8.4 77.6 7.6Z"/><path fill="url(#banana-f)" d="M10.1 47.4C10.5 45.6 11.2 40.2 12.4 36.8S15.2 30.1 17.2 27.2S21.7 21.4 24.4 19.2S30.1 15.3 33.2 13.8S39.8 11.4 43 10.2S50.1 7.9 52.6 6.8S56.8 4.4 58.2 3.6S60.4 2.3 60.8 2L61.8 4.4C59.9 5.4 54.2 8.6 50.4 10.6S42.7 13.9 39.2 16.4S32.2 22.3 29.4 25.8S24.2 33.4 22.6 37.4S20.3 47.9 19.8 50Z"/><ellipse cx="33" cy="21" rx="15" ry="7.5" transform="rotate(-32 33 21)" fill="url(#banana-h)"/><path d="M61.8 4.4C59.9 5.4 54.2 8.6 50.4 10.6S42.7 13.9 39.2 16.4S32.2 22.3 29.4 25.8S24.2 33.4 22.6 37.4S20.1 45.8 19.8 50S20.1 58.6 20.6 62.6S22.3 70.8 22.6 73.8S22.3 79.5 22.2 80.6" fill="none" stroke="#efb23a" stroke-width=".7" stroke-linecap="round" opacity=".45"/><path fill-rule="evenodd" fill="url(#banana-l)" d="M50 21.2 C70.6 21.2 81.1 29.4 81.2 46.5 C81.3 57 79.9 64.6 77.9 71.4 C72.6 77.8 61 78.8 50 78.8 C39 78.8 27.4 77.8 22.1 71.4 C20.1 64.6 18.7 57 18.8 46.5 C18.9 29.4 29.4 21.2 50 21.2Z M50 27.8 C68.6 27.8 79.9 33.6 80 47.5 C80.1 56.4 78.6 63.2 76.9 70.4 Q50 79 23.1 70.4 C21.4 63.2 19.9 56.4 20 47.5 C20.1 33.6 31.4 27.8 50 27.8Z"/><path fill-rule="evenodd" fill="url(#banana-r)" d="M50 27.8 C68.6 27.8 79.9 33.6 80 47.5 C80.1 56.4 78.6 63.2 76.9 70.4 Q50 79 23.1 70.4 C21.4 63.2 19.9 56.4 20 47.5 C20.1 33.6 31.4 27.8 50 27.8Z M50 29.8 C67.5 29.8 78.3 35 78.4 47.5 C78.5 56 77.2 62.5 75.6 69.4 Q50 78.8 24.4 69.4 C22.8 62.5 21.5 56 21.6 47.5 C21.7 35 32.5 29.8 50 29.8Z"/><path d="M23.1 70.4C21.4 63.2 19.9 56.4 20 47.5C20.1 33.6 31.4 27.8 50 27.8S79.9 33.6 80 47.5C80.1 56.4 78.6 63.2 76.9 70.4" fill="none" stroke="#d9772f" stroke-width="1.1" opacity=".3"/><g transform="rotate(28 23.4 85.6)"><ellipse cx="23.4" cy="85.6" rx="7.4" ry="4.6" fill="#80452d"/><ellipse cx="23.8" cy="85.3" rx="6.1" ry="3.6" fill="#9c5739"/><ellipse cx="22.6" cy="85.6" rx="3" ry="1.8" fill="#6e3927"/><path d="M17.6 83.4 Q23.4 80.6 29.2 83.4" fill="none" stroke="#b37452" stroke-width=".9" stroke-linecap="round" opacity=".8"/></g><path d="M61.4 0.9 C61.6 -1.6 62.2 -4 63 -5.4 C63.6 -6.4 64.8 -6.6 66.4 -6.3 L77.4 -3.3 C79.4 -2.7 80.8 -1.4 81.3 0.6 C81.7 2.4 81 4.6 79.6 6 C79 6.9 78.3 7.5 77.6 7.6 Z" fill="url(#banana-s)"/><path d="M74.6 -3.6 C76.2 -1 77.2 3 77 7.3 C78 7.4 79.1 6.8 79.8 5.8 C81 4.2 81.6 2.4 81.3 0.6 C80.9 -1.3 79.5 -2.7 77.4 -3.3Z" fill="#7a3f27"/><path d="M63.4 -5.4 C65 -5.9 70 -5 75.6 -3.4" fill="none" stroke="#bd7d5a" stroke-width="1" stroke-linecap="round" opacity=".7"/><path d="M67.6 -5.2 C67.2 -2.6 67.2 0.2 67.6 3" fill="none" stroke="#b5714f" stroke-width="1.4" stroke-linecap="round" opacity=".45"/></g>'
  +   '<g class="acc bee"><defs><linearGradient id="bee-o" gradientUnits="userSpaceOnUse" x1="0" y1="18" x2="0" y2="92.6"><stop offset="0" stop-color="#ff9e7a"/><stop offset="1" stop-color="#ff6640"/></linearGradient><linearGradient id="bee-y" gradientUnits="userSpaceOnUse" x1="0" y1="64" x2="0" y2="97"><stop offset="0" stop-color="#ffd85c"/><stop offset="1" stop-color="#f7c240"/></linearGradient><linearGradient id="bee-k" gradientUnits="userSpaceOnUse" x1="0" y1="64" x2="0" y2="97"><stop offset="0" stop-color="#74483f"/><stop offset="1" stop-color="#55303a"/></linearGradient><linearGradient id="bee-s" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#7a3a10" stop-opacity=".22"/><stop offset=".18" stop-color="#7a3a10" stop-opacity="0"/><stop offset=".82" stop-color="#7a3a10" stop-opacity="0"/><stop offset="1" stop-color="#7a3a10" stop-opacity=".22"/></linearGradient><linearGradient id="bee-h" gradientUnits="userSpaceOnUse" x1="0" y1="13" x2="0" y2="40"><stop offset="0" stop-color="#ffe684"/><stop offset=".55" stop-color="#fdd85e"/><stop offset="1" stop-color="#f7b836"/></linearGradient><radialGradient id="bee-b" cx=".38" cy=".32" r=".7"><stop offset="0" stop-color="#fff0b0"/><stop offset=".5" stop-color="#fdd965"/><stop offset="1" stop-color="#f3b836"/></radialGradient></defs><path d="M11.7 66 L15.4 66 L15.4 76.8 L14.8 76.8 C13.6 74.6 12.4 71.6 11.9 68.4Z" fill="url(#bee-o)"/><path d="M 88.3 66 L 84.6 66 L 84.6 76.8 L 85.2 76.8 C 86.4 74.6 87.6 71.6 88.1 68.4 Z" fill="url(#bee-o)"/><path d="M15 65.4 Q19 66.4 24 68.9 Q50 79.4 76.4 68.9 Q81 66.4 85.4 65.4 L85.4 77 C83.6 85 70 96.1 50 96.1 C30 96.1 16.4 85 15 77Z" fill="url(#bee-k)"/><path d="M15 71.8 Q50 90 85.4 71.8 L85.4 77 L84.8 80.6 Q50 95.8 15.6 80.6 L15 77Z" fill="url(#bee-y)"/><path d="M28.2 91.5 C34 94.4 42 95 50 95 C58 95 66 94.4 71.8 91.5 C66 94.2 60 96.1 50 96.1 C40 96.1 34 94.2 28.2 91.5Z" fill="#d9933a"/><path d="M15 65.4 Q19 66.4 24 68.9 Q50 79.4 76.4 68.9 Q81 66.4 85.4 65.4 L85.4 77 C83.6 85 70 96.1 50 96.1 C30 96.1 16.4 85 15 77Z" fill="url(#bee-s)"/><path d="M23.8 38.6 C25.8 31 34.4 25.4 50.4 25 C66.4 25.4 75 31 77 38.6" fill="none" stroke="#c4502c" stroke-width="1.6" stroke-linecap="round" opacity=".28"/><path d="M19.6 37.6 C19.8 21.6 32.4 12.9 50.4 12.9 C68.6 12.9 81.6 21.6 81.8 37.4 C81.7 39.6 78.4 40.2 78 37.8 C76 30 66.4 24.4 50.4 24 C34.4 24.4 25 30 23.2 37.8 C22.8 40.2 19.7 39.6 19.6 37.6Z" fill="url(#bee-h)"/><path d="M24.4 30 C29.4 20 38.4 15.2 50.4 15 C58.4 15 64.4 16.4 69.4 19.2" fill="none" stroke="#fff3b8" stroke-width="1.2" stroke-linecap="round" opacity=".7"/><rect x="48.5" y="-6" width="3" height="20.5" rx="1.2" fill="#ff6b42"/><circle cx="50.3" cy="-10.2" r="5.7" fill="url(#bee-b)"/><path d="M39.5 16.8 C39 12.6 36.6 8.8 32.4 6.2" fill="none" stroke="#6b4030" stroke-width="3.3" stroke-linecap="round"/><path d="M 60.5 16.8 C 61 12.6 63.4 8.8 67.6 6.2" fill="none" stroke="#6b4030" stroke-width="3.3" stroke-linecap="round"/><circle cx="28.8" cy="2.8" r="5.9" fill="url(#bee-b)"/><circle cx="71.2" cy="2.8" r="5.9" fill="url(#bee-b)"/></g>'
  +   '<g class="acc bells"><radialGradient id="bells-b" cx=".36" cy=".3" r=".75"><stop offset="0" stop-color="#fff3c2"/><stop offset=".28" stop-color="#ffd75c"/><stop offset=".7" stop-color="#fbb51e"/><stop offset="1" stop-color="#ee9c0b"/></radialGradient><linearGradient id="bells-l" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ec9a0c"/><stop offset="1" stop-color="#d27c05"/></linearGradient><linearGradient id="bells-c" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#e83a40"/><stop offset=".55" stop-color="#da2f37"/><stop offset="1" stop-color="#c22530"/></linearGradient><path d="M16 76.7Q50 95 84 76.7L82.8 78.6Q50 98.6 17.2 78.6Z" fill="#4a2f9a" opacity=".28"/><path d="M12.36 67.4Q50 86 87.64 67.4L84.4 77Q50 95 15.6 77Z" fill="url(#bells-c)"/><path d="M12.66 68.15Q50 86.9 87.34 68.15" fill="none" stroke="#ff6158" stroke-width="1.5" stroke-linecap="round"/><path d="M15.8 76.65Q50 94.3 84.2 76.65" fill="none" stroke="#a51f32" stroke-width=".8" stroke-linecap="round" opacity=".8"/><path d="M12.36 67.4Q50 86 87.64 67.4L84.4 77Q50 95 15.6 77Z" fill="none" stroke="#b81e2c" stroke-width=".4" opacity=".6"/><g transform="translate(22.1 83.9) rotate(20)"><ellipse cx="0" cy="-8.25" rx="1.39" ry="1.05" fill="#f4a91f" stroke="#d48407" stroke-width=".35"/><ellipse cx="0" cy="0" rx="7.7" ry="7.5" fill="url(#bells-b)" stroke="#d68606" stroke-width=".4"/><path d="M-7.58 2.17Q0 6.38 7.58 2.17A7.7 7.5 0 0 1 -7.58 2.17Z" fill="url(#bells-l)"/><path d="M-7.58 2.33Q0 6.52 7.58 2.33" fill="none" stroke="#a9620a" stroke-width="0.53" opacity=".75"/><path d="M-7.85 1.2Q0 5.4 7.85 1.2" fill="none" stroke="#ffd04a" stroke-width="1.28" stroke-linecap="round"/><ellipse cx="-2.31" cy="-2.85" rx="3.85" ry="2.48" transform="rotate(-28 -2.31 -2.85)" fill="#fffbe8" opacity=".55"/><ellipse cx="-2.93" cy="-3.3" rx="1.69" ry="0.98" transform="rotate(-28 -2.93 -3.3)" fill="#fffdf2" opacity=".85"/><path d="M-1.93 -2.03L-1.77 2.48Q-1 3.07 -0.23 2.48L-0.08 -2.03Q-1 -2.03 -1.93 -2.03Z" fill="#6e3b14" stroke="#e6c47c" stroke-width=".3"/><circle cx="-1" cy="-2.03" r="1.62" fill="#6e3b14" stroke="#e6c47c" stroke-width=".3"/><path d="M-1 -1.5L-1 2.4" stroke="#6e3b14" stroke-width="1.54"/></g><g transform="translate(77.9 83.9) rotate(-20)"><ellipse cx="0" cy="-8.25" rx="1.39" ry="1.05" fill="#f4a91f" stroke="#d48407" stroke-width=".35"/><ellipse cx="0" cy="0" rx="7.7" ry="7.5" fill="url(#bells-b)" stroke="#d68606" stroke-width=".4"/><path d="M-7.58 2.17Q0 6.38 7.58 2.17A7.7 7.5 0 0 1 -7.58 2.17Z" fill="url(#bells-l)"/><path d="M-7.58 2.33Q0 6.52 7.58 2.33" fill="none" stroke="#a9620a" stroke-width="0.53" opacity=".75"/><path d="M-7.85 1.2Q0 5.4 7.85 1.2" fill="none" stroke="#ffd04a" stroke-width="1.28" stroke-linecap="round"/><ellipse cx="-2.31" cy="-2.85" rx="3.85" ry="2.48" transform="rotate(-28 -2.31 -2.85)" fill="#fffbe8" opacity=".55"/><ellipse cx="-2.93" cy="-3.3" rx="1.69" ry="0.98" transform="rotate(-28 -2.93 -3.3)" fill="#fffdf2" opacity=".85"/><path d="M0.08 -2.03L0.23 2.48Q1 3.07 1.77 2.48L1.93 -2.03Q1 -2.03 0.08 -2.03Z" fill="#6e3b14" stroke="#e6c47c" stroke-width=".3"/><circle cx="1" cy="-2.03" r="1.62" fill="#6e3b14" stroke="#e6c47c" stroke-width=".3"/><path d="M1 -1.5L1 2.4" stroke="#6e3b14" stroke-width="1.54"/></g><g transform="translate(50.2 88.4)"><ellipse cx="0" cy="-8.91" rx="1.67" ry="1.13" fill="#f4a91f" stroke="#d48407" stroke-width=".35"/><ellipse cx="0" cy="0" rx="9.3" ry="8.1" fill="url(#bells-b)" stroke="#d68606" stroke-width=".4"/><path d="M-9.16 2.35Q0 6.88 9.16 2.35A9.3 8.1 0 0 1 -9.16 2.35Z" fill="url(#bells-l)"/><path d="M-9.16 2.51Q0 7.05 9.16 2.51" fill="none" stroke="#a9620a" stroke-width="0.57" opacity=".75"/><path d="M-9.48 1.3Q0 5.83 9.48 1.3" fill="none" stroke="#ffd04a" stroke-width="1.38" stroke-linecap="round"/><ellipse cx="-2.79" cy="-3.08" rx="4.65" ry="2.67" transform="rotate(-28 -2.79 -3.08)" fill="#fffbe8" opacity=".55"/><ellipse cx="-3.53" cy="-3.56" rx="2.05" ry="1.05" transform="rotate(-28 -3.53 -3.56)" fill="#fffdf2" opacity=".85"/><path d="M-1.12 -2.19L-0.93 2.67Q0 3.32 0.93 2.67L1.12 -2.19Q0 -2.19 -1.12 -2.19Z" fill="#6e3b14" stroke="#e6c47c" stroke-width=".3"/><circle cx="0" cy="-2.19" r="1.95" fill="#6e3b14" stroke="#e6c47c" stroke-width=".3"/><path d="M0 -1.62L0 2.59" stroke="#6e3b14" stroke-width="1.86"/></g></g>'
  +   '<g class="acc berry"><defs><linearGradient id="berry-red" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#f75d58"/><stop offset=".55" stop-color="#ee4c4f"/><stop offset="1" stop-color="#d8363f"/></linearGradient><radialGradient id="berry-hi" cx=".3" cy=".18" r=".55"><stop offset="0" stop-color="#ff8a7c" stop-opacity=".55"/><stop offset="1" stop-color="#ff8a7c" stop-opacity="0"/></radialGradient><linearGradient id="berry-leaf" x1="0" y1="0" x2=".3" y2="1"><stop offset="0" stop-color="#aed86b"/><stop offset=".6" stop-color="#97c95b"/><stop offset="1" stop-color="#6ea646"/></linearGradient><radialGradient id="berry-ball" cx=".38" cy=".32" r=".7"><stop offset="0" stop-color="#fff" stop-opacity=".35"/><stop offset=".5" stop-color="#fff" stop-opacity="0"/><stop offset="1" stop-color="#e8960f" stop-opacity=".3"/></radialGradient></defs><path d="M50 9.5 C27 9.5 5.2 19.4 5.2 42 C5.2 50 7.6 55.6 11.2 57.9 C12.8 58.9 14.4 58 14.2 56 C15.2 44 26 31.2 50 31 C74 31.2 84.8 44 85.8 56 C85.6 58 87.2 58.9 88.8 57.9 C92.4 55.6 94.8 50 94.8 42 C94.8 19.4 73 9.5 50 9.5Z" fill="url(#berry-red)"/><path d="M50 9.5 C27 9.5 5.2 19.4 5.2 42 C5.2 50 7.6 55.6 11.2 57.9 C12.8 58.9 14.4 58 14.2 56 C15.2 44 26 31.2 50 31 C74 31.2 84.8 44 85.8 56 C85.6 58 87.2 58.9 88.8 57.9 C92.4 55.6 94.8 50 94.8 42 C94.8 19.4 73 9.5 50 9.5Z" fill="url(#berry-hi)"/><path d="M13.4 54.6 C14.4 42.4 25.4 29.1 50 28.9" fill="none" stroke="#e8474c" stroke-width="2.6"/><path d="M 86.6 54.6 C 85.6 42.4 74.6 29.1 50 28.9" fill="none" stroke="#e8474c" stroke-width="2.6"/><path d="M12.6 54 C13.4 41 24.8 27.6 50 27.4" fill="none" stroke="#c3333a" stroke-width=".9" opacity=".7"/><path d="M 87.4 54 C 86.6 41 75.2 27.6 50 27.4" fill="none" stroke="#c3333a" stroke-width=".9" opacity=".7"/><path d="M14.2 56 C15.2 44 26 31.2 50 31" fill="none" stroke="#b92f37" stroke-width=".7" opacity=".6"/><path d="M 85.8 56 C 84.8 44 74 31.2 50 31" fill="none" stroke="#b92f37" stroke-width=".7" opacity=".6"/><path d="M9.4 34 C11.6 25 17 18.4 25 14.6" fill="none" stroke="#ff8f84" stroke-width="1.6" stroke-linecap="round" opacity=".55"/><g fill="#f7c766"><path transform="translate(24.3 19.3) rotate(32)" d="M0-2.4C1-1 1.5.2 1.5.9A1.5 1.5 0 0 1-1.5.9C-1.5.2-1-1 0-2.4Z"/><path transform="translate(75.7 19.3) rotate(-32)" d="M0-2.4C1-1 1.5.2 1.5.9A1.5 1.5 0 0 1-1.5.9C-1.5.2-1-1 0-2.4Z"/><path transform="translate(49.9 22.9) rotate(0)" d="M0-2.4C1-1 1.5.2 1.5.9A1.5 1.5 0 0 1-1.5.9C-1.5.2-1-1 0-2.4Z"/><path transform="translate(36.7 25.4) rotate(14)" d="M0-2.4C1-1 1.5.2 1.5.9A1.5 1.5 0 0 1-1.5.9C-1.5.2-1-1 0-2.4Z"/><path transform="translate(63.3 25.4) rotate(-14)" d="M0-2.4C1-1 1.5.2 1.5.9A1.5 1.5 0 0 1-1.5.9C-1.5.2-1-1 0-2.4Z"/><path transform="translate(13.1 29.6) rotate(30)" d="M0-2.4C1-1 1.5.2 1.5.9A1.5 1.5 0 0 1-1.5.9C-1.5.2-1-1 0-2.4Z"/><path transform="translate(86.9 29.6) rotate(-30)" d="M0-2.4C1-1 1.5.2 1.5.9A1.5 1.5 0 0 1-1.5.9C-1.5.2-1-1 0-2.4Z"/><path transform="translate(24.9 30.2) rotate(18)" d="M0-2.4C1-1 1.5.2 1.5.9A1.5 1.5 0 0 1-1.5.9C-1.5.2-1-1 0-2.4Z"/><path transform="translate(75.1 30.2) rotate(-18)" d="M0-2.4C1-1 1.5.2 1.5.9A1.5 1.5 0 0 1-1.5.9C-1.5.2-1-1 0-2.4Z"/><path transform="translate(12.8 41) rotate(22)" d="M0-2.4C1-1 1.5.2 1.5.9A1.5 1.5 0 0 1-1.5.9C-1.5.2-1-1 0-2.4Z"/><path transform="translate(87.2 41) rotate(-22)" d="M0-2.4C1-1 1.5.2 1.5.9A1.5 1.5 0 0 1-1.5.9C-1.5.2-1-1 0-2.4Z"/></g><path transform="translate(.4 1.3)" d="M50 6.6 C43 6.4 35.6 9.6 32 14.2 C29.6 17.4 29.4 21.2 30.8 23.6 C35 24 41 22 45.2 18.6 C48.8 15.6 50.4 11 50 6.6Z" fill="#b8323a" opacity=".35"/><path transform="translate(.4 1.3)" d="M 50 6.6 C 57 6.4 64.4 9.6 68 14.2 C 70.4 17.4 70.6 21.2 69.2 23.6 C 65 24 59 22 54.8 18.6 C 51.2 15.6 49.6 11 50 6.6Z" fill="#b8323a" opacity=".35"/><g fill="url(#berry-leaf)" stroke="#64a042" stroke-width=".55" stroke-opacity=".8"><path d="M48.6 5.8 C46 2.4 40 .8 34 2 C32.4 2.4 32.6 3.8 34.4 4.4 C39 5.8 44 6.2 48.6 5.8Z"/><path d="M 51.4 5.8 C 54 2.4 60 0.8 66 2 C 67.6 2.4 67.4 3.8 65.6 4.4 C 61 5.8 56 6.2 51.4 5.8Z"/><path d="M50 5.6 C43 4.2 33 3.6 24.8 4.8 C20 5.5 17.6 7.4 18.6 9.4 C19.8 11.4 24.2 12.8 30 13.3 C37 13.8 44 12 50 8.8Z"/><path d="M 50 5.6 C 57 4.2 67 3.6 75.2 4.8 C 80 5.5 82.4 7.4 81.4 9.4 C 80.2 11.4 75.8 12.8 70 13.3 C 63 13.8 56 12 50 8.8Z"/></g><line x1="49.8" y1="6.4" x2="49.8" y2="-5" stroke="#ff6640" stroke-width="3.6" stroke-linecap="round"/><line x1="51" y1="6" x2="51" y2="-4.6" stroke="#e0502f" stroke-width="1.1" opacity=".55"/><circle class="b-bulb" cx="49.8" cy="-9.8" r="5.5" fill="#ffd36e"/><circle cx="49.8" cy="-9.8" r="5.5" fill="url(#berry-ball)"/><circle cx="47.8" cy="-11.9" r="1.5" fill="#fff" opacity=".5"/><g fill="url(#berry-leaf)" stroke="#64a042" stroke-width=".55" stroke-opacity=".8"><path d="M50 6.6 C43 6.4 35.6 9.6 32 14.2 C29.6 17.4 29.4 21.2 30.8 23.6 C35 24 41 22 45.2 18.6 C48.8 15.6 50.4 11 50 6.6Z"/><path d="M 50 6.6 C 57 6.4 64.4 9.6 68 14.2 C 70.4 17.4 70.6 21.2 69.2 23.6 C 65 24 59 22 54.8 18.6 C 51.2 15.6 49.6 11 50 6.6Z"/></g><path d="M49.2 8.6 Q41.6 13 34 21.4" fill="none" stroke="#7cb150" stroke-width=".6" stroke-linecap="round" opacity=".8"/><path d="M 50.8 8.6 Q 58.4 13 66 21.4" fill="none" stroke="#7cb150" stroke-width=".6" stroke-linecap="round" opacity=".8"/></g>'
  +   '<g class="acc cane"><defs><radialGradient id="cane-hs" cx=".35" cy=".3" r=".7"><stop offset="0" stop-color="#fff" stop-opacity=".35"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient><linearGradient id="cane-bw" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#58b75c"/><stop offset="1" stop-color="#2b8237"/></linearGradient></defs><circle cx="18.6" cy="88" r="9.4" fill="url(#armL)"/><circle cx="18.6" cy="88" r="9.4" fill="url(#cane-hs)"/><path d="M2.8 67.6 A9.5 9.5 0 0 1 8.5 55.5 A9.5 9.5 0 0 1 20.7 61.2 L34.8 100" fill="none" stroke="#b51d2a" stroke-width="11.3" stroke-linecap="round"/><path d="M2.8 67.6 A9.5 9.5 0 0 1 8.5 55.5 A9.5 9.5 0 0 1 20.7 61.2 L34.8 100" fill="none" stroke="#e8363f" stroke-width="10.2" stroke-linecap="round"/><path d="M7.5 65.5 L7.4 64.9 L7.4 63.7 L7.7 62.6 L8.3 61.6 L-2.7 66.7 L-2.4 67.9 L-2.4 67.9 L-2.4 67.9 L-2.4 67.9Z M8.4 61.5 L9.3 60.7 L10.3 60.2 L11.5 60 L12.6 60.1 L3 52.7 L2 53.6 L1 54.6 L0.1 55.6 L-0.7 56.8Z M12.8 60.1 L13.9 60.5 L14.8 61.2 L15.5 62.1 L16.1 63.5 L18.1 51.2 L16.8 50.7 L15.5 50.3 L14.1 50 L12.8 49.8Z M16.7 65.1 L17.1 66.3 L17.6 67.6 L18 68.8 L18.5 70 L27.1 63.9 L26.7 62.7 L26.2 61.5 L25.8 60.3 L25.3 58.9Z M20.1 74.5 L20.5 75.7 L21 76.9 L21.4 78.2 L21.9 79.4 L30.8 74 L30.3 72.8 L29.9 71.6 L29.5 70.4 L29 69.1Z M23.6 84.2 L24.1 85.5 L24.5 86.7 L25 87.9 L25.4 89.1 L34.3 83.8 L33.9 82.5 L33.4 81.3 L33 80.1 L32.5 78.9Z M27.2 94 L27.6 95.2 L28.1 96.4 L28.5 97.6 L29 98.8 L37.9 93.5 L37.4 92.3 L37 91 L36.5 89.8 L36.1 88.6Z" fill="#fff8f5"/><path d="M20.3 55.2 A12.6 12.6 0 0 1 22.2 57.5 A12.6 12.6 0 0 1 23.6 60.1 L36.8 96.6" fill="none" stroke="#8f0f1f" stroke-opacity=".2" stroke-width="2.8" stroke-linecap="round"/><path d="M5.4 65.6 A6.4 6.4 0 0 1 7.4 59.7 A6.4 6.4 0 0 1 13.5 58.2" fill="none" stroke="#7a0c1a" stroke-opacity=".16" stroke-width="2.4" stroke-linecap="round"/><path d="M-0.5 64.1 A12.3 12.3 0 0 1 3.3 55.5 A12.3 12.3 0 0 1 12.1 52.2" fill="none" stroke="#fff" stroke-opacity=".55" stroke-width="1.4" stroke-linecap="round"/><path d="M19.1 64.9 L30.8 97.2" fill="none" stroke="#fff" stroke-opacity=".45" stroke-width="1.3" stroke-linecap="round"/><g transform="translate(26.4 75.8) rotate(-16) scale(1.06)"><path d="M-1.8 1.2 L-7.4 8.4 L-4.8 8.4 L-4 11 L1.2 2.4Z M1.8 1.4 L7.8 9.8 L5.2 9.4 L4.8 12.2 L-1 2.4Z" fill="#2a7f34"/><path d="M-1.4 -1 C-4.4 -7.2 -11.6 -7.8 -12.6 -3.8 C-13.4 -0.6 -12.8 3.8 -10.8 5.2 C-7.8 7 -3.8 3.8 -1.4 1.4Z M1.4 -1 C4.4 -7.2 11.6 -7.8 12.6 -3.8 C13.4 -0.6 12.8 3.8 10.8 5.2 C7.8 7 3.8 3.8 1.4 1.4Z" fill="url(#cane-bw)"/><path d="M-3.4 0 C-5.6 -2.8 -9 -2.6 -9.2 -0.4 C-9 1.6 -5.8 1.8 -3.4 0Z M3.4 0 C5.6 -2.8 9 -2.6 9.2 -0.4 C9 1.6 5.8 1.8 3.4 0Z" fill="#1f6a29"/><path d="M-10.8 -4 C-9.2 -6 -6.4 -5.8 -4.8 -4.2 M4.8 -4.2 C6.4 -5.8 9.2 -6 10.8 -4" fill="none" stroke="#a6e8a2" stroke-opacity=".7" stroke-width="1" stroke-linecap="round"/><rect x="-2.8" y="-3.2" width="5.6" height="6.4" rx="2.4" fill="#379643"/><path d="M-1.6 -1.8 Q0 -2.6 1.6 -1.8" fill="none" stroke="#a6e8a2" stroke-opacity=".7" stroke-width=".9" stroke-linecap="round"/></g></g>'
  +   '<g class="acc chef2"><defs><linearGradient id="chef2-a1" gradientUnits="userSpaceOnUse" x1="0" y1="-10" x2="0" y2="18.5"><stop offset="0" stop-color="#fff"/><stop offset=".5" stop-color="#fdfbfd"/><stop offset=".77" stop-color="#efe8f5"/><stop offset="1" stop-color="#e1d7ed"/></linearGradient><linearGradient id="chef2-a2" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#efe8f5"/><stop offset=".3" stop-color="#fefcfe"/><stop offset=".85" stop-color="#fcf9fc"/><stop offset="1" stop-color="#ede6f3"/></linearGradient><linearGradient id="chef2-a4" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fff"/><stop offset=".7" stop-color="#fcfaff"/><stop offset="1" stop-color="#f0ebf8"/></linearGradient><linearGradient id="chef2-a5" gradientUnits="userSpaceOnUse" x1="14" y1="0" x2="86" y2="0"><stop offset=".18" stop-color="#e4dbf2"/><stop offset=".25" stop-color="#e4dbf2" stop-opacity="0"/><stop offset=".75" stop-color="#e4dbf2" stop-opacity="0"/><stop offset=".82" stop-color="#e4dbf2"/></linearGradient><radialGradient id="chef2-a6" cx=".36" cy=".3" r=".75"><stop offset="0" stop-color="#fff6c8" stop-opacity=".5"/><stop offset=".45" stop-color="#ffe9a0" stop-opacity="0"/><stop offset=".8" stop-color="#f6ad2c" stop-opacity=".1"/><stop offset="1" stop-color="#ef9f26" stop-opacity=".4"/></radialGradient></defs><path d="M27.1 79V89.6C27.1 94 30 95.9 33.9 96.1Q50 98.7 66.1 96.1C70 95.9 72.9 94 72.9 89.6V79" fill="none" stroke="#a28ff1" stroke-width="2" stroke-dasharray="0 2" stroke-linecap="round"/><path d="M27.1 79V89.6C27.1 94 30 95.9 33.9 96.1Q50 98.7 66.1 96.1C70 95.9 72.9 94 72.9 89.6V79" fill="none" stroke="#fff" stroke-width="0.85" stroke-dasharray="0.5 1.5" stroke-dashoffset="0.25" stroke-linecap="round"/><path d="M27.95 76.6Q50 83 72.05 76.6V89.6C72.05 93.5 69.6 95.1 66 95.3Q50 97.9 34 95.3C30.4 95.1 27.95 93.5 27.95 89.6Z" fill="url(#chef2-a4)"/><path d="M27.95 79V89.6C27.95 93.5 30.4 95.1 34 95.3Q50 97.9 66 95.3C69.6 95.1 72.05 93.5 72.05 89.6V79" fill="none" stroke="#a28ff1" stroke-width="0.85"/><path d="M40.2 83.7V86.6C40.2 91.5 44.4 93.4 50 93.4C55.6 93.4 59.8 91.5 59.8 86.6V83.7" fill="none" stroke="#a28ff1" stroke-width="1.7" stroke-dasharray="0 1.7" stroke-linecap="round"/><path d="M40.2 83.7V86.6C40.2 91.5 44.4 93.4 50 93.4C55.6 93.4 59.8 91.5 59.8 86.6V83.7" fill="none" stroke="#fff" stroke-width="0.7" stroke-dasharray="0.4 1.3" stroke-dashoffset="0.2" stroke-linecap="round"/><path d="M40.9 83.7V86.6C40.9 91 44.8 92.7 50 92.7C55.2 92.7 59.1 91 59.1 86.6V83.7Z" fill="#fdfbff"/><path d="M40.9 83.7V86.6C40.9 91 44.8 92.7 50 92.7C55.2 92.7 59.1 91 59.1 86.6V83.7" fill="none" stroke="#a28ff1" stroke-width="0.8"/><path d="M40.2 81.55H59.8" stroke="#a28ff1" stroke-width="1.5" stroke-dasharray="0 1.62" stroke-linecap="round"/><rect x="38.9" y="81.35" width="22.2" height="2.5" rx="1.25" fill="#a28ff1"/><path d="M40.3 82.6H59.7" stroke="#fff" stroke-width=".45" stroke-dasharray=".75 .87" stroke-linecap="round" opacity=".95"/><path d="M14.6 70.2Q50 83.7 85.4 70.2" fill="none" stroke="#6f55d8" stroke-width="1" opacity=".2"/><path d="M14 70.5Q50 84.3 86 70.5C87.7 71.1 87.6 73.8 86 74.5Q50 87.6 14 74.5C12.4 73.8 12.3 71.1 14 70.5Z" fill="#fefcff" stroke="#cfc8e8" stroke-width=".5"/><path d="M14.6 73.95Q50 87 85.4 73.95" fill="none" stroke="url(#chef2-a5)" stroke-width="1.1"/><path d="M30 27.4Q50 21.4 70 27.4L70.3 28.7Q50 22.9 29.7 28.7Z" fill="#d9553a" opacity=".2"/><path d="M25.2 9H74.8L72.1 25.9Q71.9 27.55 70 27.4Q50 21.4 30 27.4Q28.1 27.55 27.9 25.9Z" fill="url(#chef2-a2)" stroke="#cdc4e2" stroke-width=".8"/><path d="M29.8 26.65Q50 20.65 70.2 26.65" fill="none" stroke="#e6ddef" stroke-width="1.1" stroke-linecap="round"/><path d="M31.4 10.9Q40.8 17.6 50 11.1Q59.2 17.6 68.6 10.9" fill="none" stroke="#d8cdea" stroke-width="2" opacity=".45" stroke-linecap="round"/><path d="M50.3-10V-3.2" stroke="#f05a35" stroke-width="3" stroke-linecap="round"/><g fill="none" stroke="#cdc4e2" stroke-width="1.6"><path d="M29.6 2C29.4-5.6 34.6-9.6 40.6-9.6C45.2-9.6 47.6-8.8 50.3-4.6C53-8.8 55.4-9.4 60-9.4C65.8-9.4 70.6-5.6 70.4 2L68.4 9.8Q59.2 16.6 50 10Q40.8 16.6 31.6 9.8Z"/><circle cx="24.8" cy="8.6" r="9.3"/><circle cx="75.2" cy="8.6" r="9.3"/></g><g fill="url(#chef2-a1)"><path d="M29.6 2C29.4-5.6 34.6-9.6 40.6-9.6C45.2-9.6 47.6-8.8 50.3-4.6C53-8.8 55.4-9.4 60-9.4C65.8-9.4 70.6-5.6 70.4 2L68.4 9.8Q59.2 16.6 50 10Q40.8 16.6 31.6 9.8Z"/><circle cx="24.8" cy="8.6" r="9.3"/><circle cx="75.2" cy="8.6" r="9.3"/></g><path d="M50.4-4.2Q51.2-2.4 51.6-.6" fill="none" stroke="#d3c9e4" stroke-width=".7" stroke-linecap="round"/><circle class="b-bulb" cx="50.4" cy="-13" r="5.8" fill="#ffd36e"/><circle cx="50.4" cy="-13" r="5.8" fill="url(#chef2-a6)"/></g>'
  +   '<g class="acc cowboy2"><defs><radialGradient id="cowboy2-gb" gradientUnits="userSpaceOnUse" cx="50" cy="44" r="50" gradientTransform="translate(50 44) scale(1 .833) translate(-50 -44)"><stop offset=".5" stop-color="#7a3e2c"/><stop offset=".66" stop-color="#844632"/><stop offset=".75" stop-color="#8b4b35"/><stop offset=".83" stop-color="#9a563c"/><stop offset=".95" stop-color="#aa6345"/></radialGradient><linearGradient id="cowboy2-gc" x1="0" y1="0" x2=".3" y2="1"><stop offset="0" stop-color="#cb8259"/><stop offset="1" stop-color="#b96d4a"/></linearGradient><linearGradient id="cowboy2-gs" x1="0" y1="0" x2=".2" y2="1"><stop offset="0" stop-color="#ffe680"/><stop offset=".55" stop-color="#ffd457"/><stop offset="1" stop-color="#f2a925"/></linearGradient><linearGradient id="cowboy2-gh" gradientUnits="userSpaceOnUse" x1="0" y1="29.5" x2="0" y2="34.5"><stop offset="0" stop-color="#6e3326" stop-opacity=".38"/><stop offset="1" stop-color="#6e3326" stop-opacity="0"/></linearGradient><linearGradient id="cowboy2-gr" gradientUnits="userSpaceOnUse" x1="0" y1="62" x2="0" y2="91"><stop offset=".1" stop-color="#e9494d"/><stop offset="1" stop-color="#d12e38"/></linearGradient><linearGradient id="cowboy2-gw" gradientUnits="userSpaceOnUse" x1="15" y1="0" x2="33" y2="0"><stop offset="0" stop-color="#a81f2b" stop-opacity=".5"/><stop offset="1" stop-color="#a81f2b" stop-opacity="0"/></linearGradient></defs><line x1="50" y1="2" x2="50" y2="-6" stroke="#f0643f" stroke-width="2.9" stroke-linecap="round"/><circle cx="50" cy="-9.7" r="5.2" fill="#ffd45e"/><circle cx="48.3" cy="-11.4" r="1.5" fill="#fff" opacity=".55"/><g transform="translate(0 .5)"><path d="M14.6 44.6C10.5 44.2 6.2 41.6 3.9 37.6C1.9 34.2 1.6 30 1.8 26.4C2 21.6 3.6 17.4 6.6 14.8C8.6 13.1 11 12.5 13 12.6C17 12.8 21.2 14.4 25.6 16.8L74.4 16.8C78.8 14.4 83 12.8 87 12.6C89 12.5 91.4 13.1 93.4 14.8C96.4 17.4 98 21.6 98.2 26.4C98.4 30 97.6 34 95.4 37.4C93 41 89.4 43.8 85.4 44.6C85.4 41.2 84.4 37.6 82.4 35C80.6 32.6 77.6 30.8 74 30C66 29 58 30.3 50 30.3C42 30.3 34 29 26 30C22.4 30.8 19.4 32.6 17.6 35C15.6 37.6 14.6 41.2 14.6 44.6Z" fill="url(#cowboy2-gb)" stroke="#7e4029" stroke-width=".45" stroke-opacity=".5"/><path d="M14.9 43.8C14.9 40.8 15.8 37.4 17.8 35C19.6 32.8 22.6 31 26.4 30.3M85.1 43.8C85.1 40.8 84.2 37.4 82.2 35C80.4 32.8 77.4 31 73.6 30.3" fill="none" stroke="#5e2b1e" stroke-width="1.3" stroke-linecap="round" opacity=".45"/><path d="M25.4 18.6C25.8 12.4 27.4 6.6 30.4 3.2C32.6 .6 36-1.2 40-1.2C44-1.2 46.6 .8 50 .8C53.4 .8 56-1.2 60-1.2C64-1.2 67.4 .6 69.6 3.2C72.6 6.6 74.2 12.4 74.6 18.6Z" fill="url(#cowboy2-gc)"/><path d="M70.4 5C72.2 8.4 73.2 12.6 73.6 17L71.2 17C71 12.8 70.4 8.8 68.8 5.4Z" fill="#7a3e26" opacity=".14"/><path d="M25.6 16.3Q50 15.8 74.4 16.3L74.5 18.8C72.4 19.9 70.4 21 68 22C63 24.1 57 25.5 50 25.6C43 25.5 37 24.1 32 22C29.6 21 27.6 19.9 25.5 18.8Z" fill="#9a4236"/><path d="M25.7 16.5Q50 16 74.3 16.5" fill="none" stroke="#c0604c" stroke-width=".5" opacity=".5"/><path d="M24 29.6C34 28.9 42 30.2 50 30.2C58 30.2 66 28.9 76 29.6L77 35C66 33.6 58 34.6 50 34.6C42 34.6 34 33.6 23 35Z" fill="url(#cowboy2-gh)"/><path d="M1.9 23.6C2.4 19.8 4 16.6 6.6 14.8C8.6 13.1 11 12.5 13 12.6C16.6 12.7 19.6 13.8 22 15.6C25 17.4 27.6 19.4 30 20.6C35 22.8 42 24.6 50 24.7C58 24.6 65 22.8 70 20.6C72.4 19.4 75 17.4 78 15.6C80.4 13.8 83.4 12.7 87 12.6C89 12.5 91.4 13.1 93.4 14.8C96 16.6 97.6 19.8 98 23.6C97.2 20.8 95.6 18.4 93.4 17C91.4 15.8 89.4 15.6 87.4 15.9C84.6 16.3 82 17.6 79.6 19.4C76.6 21.6 73.4 23.8 70 25.4C64.6 27.8 57.6 28.8 50 28.8C42.4 28.8 35.4 27.8 30 25.4C26.6 23.8 23.4 21.6 20.4 19.4C18 17.6 15.4 16.3 12.6 15.9C10.6 15.6 8.6 15.8 6.6 17C4.4 18.4 2.8 20.8 1.9 23.6Z" fill="#c97d56"/><path d="M5.2 16.4C7 14.4 9.6 13.3 13 13.3C19 13.4 23.6 17.4 30 20.9C35 23.4 42 25.3 50 25.4C58 25.3 65 23.4 70 20.9C76.4 17.4 81 13.4 87 13.3C90.4 13.3 93 14.4 94.8 16.4" fill="none" stroke="#dc9a74" stroke-width="1" stroke-linecap="round" opacity=".85"/></g><path d="M50 6.7L52.5 11.6L57.9 12.4L54 16.3L54.9 21.7L50 19.2L45.1 21.7L46 16.3L42.1 12.4L47.5 11.6Z" fill="url(#cowboy2-gs)" stroke="url(#cowboy2-gs)" stroke-width="1.3" stroke-linejoin="round"/><path d="M46.6 12.8C47.6 11.6 48.6 10.2 49.4 8.6C49.8 10.6 49.4 12.6 48.2 13.8Z" fill="#fff8d2" opacity=".32"/><path d="M45.6 21.2L50 18.9L54.4 21.2" fill="none" stroke="#e39a1c" stroke-width=".9" stroke-linejoin="round" stroke-linecap="round" opacity=".45"/><path d="M16.2 62.4C19.6 62.6 23 64 27 66C33 68.2 40.6 69.6 50 69.8C59.4 69.6 67 68.2 72 66.3C75 65.2 77.6 64.2 79.6 63.6L82 69.5C80.6 72.2 77.6 74.4 73 77C66 81.6 58 86 50 90.8C42 86 33 81.4 26 76.4C22.4 73.8 19 71.2 16.8 68.8C15.2 67.2 14.6 65.4 14.8 64C15 63 15.4 62.5 16.2 62.4Z" fill="url(#cowboy2-gr)"/><path d="M16.8 68.8C19 71.2 22.4 73.8 26 76.4C29 78.6 31 79.8 33 81L33 74.6C30.6 74.2 28.4 73.4 26.2 72.4C22.6 70.8 19.6 68.8 17.4 66.6C16.2 65.4 15.2 64.6 14.8 64C14.6 65.4 15.2 67.2 16.8 68.8Z" fill="url(#cowboy2-gw)"/><path d="M16.6 63.2C20 63.4 23.4 64.8 27.2 66.8C33.2 69.4 41 70.8 50 70.8C59 70.8 67 69.2 72.2 67.4C75 66.4 77.4 65.4 79.4 64.8" fill="none" stroke="#ff8a84" stroke-width=".7" opacity=".55"/><path d="M17.6 67.4C21 70 24.6 72 28.4 73.4C35 75.4 42 76.2 50 76.2C58 76.2 65 75.4 71.6 73.4C74.6 72.4 77.2 71.2 79.6 69.8" fill="none" stroke="#b81f2e" stroke-width="1.6" stroke-linecap="round" opacity=".32"/><g fill="none" stroke="#ffd6d1" stroke-width=".6" stroke-linecap="round" opacity=".9"><path d="M22 70.2A1.1 1.1 0 0 1 22 68.2"/><path d="M27.1 71.1A1.6 1.6 0 1 1 25.8 70.9"/><path d="M28.1 69.7L28.9 71"/><path d="M31.4 75.1C34.4 75.1 35.1 77.2 33.6 78.3C32.1 79.3 30.3 77.9 31.4 75.1Z"/><path d="M39.2 71.3C41.9 72.6 41.5 74.9 39.8 75.1C38 75.4 37 73.3 39.2 71.3Z"/><path d="M50 73.7C53 76 52.2 79.1 50 79.1C47.8 79.1 47 76 50 73.7Z"/><ellipse cx="41.8" cy="82" rx="2" ry="1.8" transform="rotate(20 41.8 82)"/><path d="M50 87.5C47.2 86.1 48 84.1 50 84.1C52 84.1 52.8 86.1 50 87.5Z"/><path d="M60.3 71.8C61.9 74.4 60.3 76.2 58.7 75.4C57 74.7 57.4 72.3 60.3 71.8Z"/><ellipse cx="58.1" cy="82" rx="1.9" ry="1.8" transform="rotate(-15 58.1 82)"/><ellipse cx="68.4" cy="71.2" rx="1.5" ry="1.1" transform="rotate(-25 68.4 71.2)"/><path d="M75.6 69.9C76.3 72.7 74.3 74 73 72.9C71.7 71.9 72.7 69.7 75.6 69.9Z"/><path d="M65.8 77.1A1.3 1.3 0 1 1 65 76.8"/><path d="M68 78.8C69.4 77.4 69.8 75.4 69.2 73.4"/><circle cx="30.2" cy="70.8" r=".7"/></g><path d="M80 69C80.2 72.4 81.6 75.6 83.4 77.6C85.4 79.6 88.4 80.8 91.2 81.2C92.2 81.3 92.8 80.6 92.8 79.6C92.8 76.8 92.2 74 90.4 71.6C88.8 69.6 86.6 68.4 84.2 68Z" fill="#e2444a" stroke="#c42c37" stroke-width=".4"/><path d="M81.6 70C83.4 71.2 85.2 72.8 87 74.8C84.8 74.6 82.8 72.8 81.6 70Z" fill="#a91d2a" opacity=".5"/><path d="M83 63.2C84.6 61 87 59.4 90 59C92.6 58.6 95 58.8 96.4 59.8C97.4 60.6 97 61.8 96 62.6C94.4 64.4 92 66.2 89.4 67.6C87.6 68.6 85.4 69.2 83.6 68.8Z" fill="#e2444a" stroke="#c42c37" stroke-width=".4"/><path d="M84.6 65.6C87.4 64.2 90.4 63.1 93.2 62.6C93.8 63.6 92 65.4 89.6 66.6C87.8 67.4 85.8 67.6 84.6 65.6Z" fill="#b9222f" opacity=".28"/><path d="M84.4 65.4C87.2 63.8 90.2 62.8 93.4 62.4" fill="none" stroke="#a81d2a" stroke-width=".65" stroke-linecap="round" opacity=".55"/><path d="M77.6 64.4C78.2 62.8 80 62 81.8 62.2C83.4 62.4 84.6 63.4 85 64.8L84.8 68C84 69.6 82.4 70.4 80.8 70.2C79.2 70 78 69 77.6 67.6Z" fill="#cf3139"/><ellipse cx="80.9" cy="66" rx="2.55" ry="2.85" transform="rotate(-20 80.9 66)" fill="#e0424a" stroke="#b5222e" stroke-width=".5" stroke-opacity=".6"/><path d="M79.4 65.4C79.4 64.2 80.2 63.4 81.4 63.3C80.8 63.9 80.3 64.6 80.1 65.6Z" fill="#ff9a92" opacity=".75"/></g>'
  +   '<g class="acc detective"><defs><radialGradient id="detective-cr" cx=".5" cy=".3" r=".75"><stop offset="0" stop-color="#c4824f"/><stop offset=".55" stop-color="#ad6d42"/><stop offset="1" stop-color="#874f32"/></radialGradient><linearGradient id="detective-vz" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#e6a96e"/><stop offset=".8" stop-color="#dc9b5e"/><stop offset="1" stop-color="#c7844a"/></linearGradient><linearGradient id="detective-fl" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#96613a"/><stop offset="1" stop-color="#72432a"/></linearGradient><radialGradient id="detective-gl" cx=".4" cy=".35" r=".7"><stop offset="0" stop-color="#f2f8ff"/><stop offset="1" stop-color="#b3cff0"/></radialGradient><linearGradient id="detective-rim" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#f7c54a"/><stop offset="1" stop-color="#c47f1a"/></linearGradient><linearGradient id="detective-sh" x1="0" y1="0" x2="0" y2="1"><stop offset=".3" stop-color="#a8574a" stop-opacity=".38"/><stop offset="1" stop-color="#a8574a" stop-opacity="0"/></linearGradient></defs><g transform="translate(0,-3.5)"><path d="M26 30.6 C36 34.6 63.6 34.6 73.6 30.6 L73.4 32 C64 38.6 35.6 38.6 26.2 32Z" fill="url(#detective-sh)"/><path d="M71 27 C80 26.4 88.4 28 92 32.6 C94.2 35.4 95.2 39 94.6 41.4 C94 43.6 91.2 44 89 42.6 C86.4 40.8 82.4 38.6 76.6 36.6 C73.4 35.4 71 32 71 27Z" fill="url(#detective-fl)"/><path d="M28 27 C20.4 27.4 14.6 29.2 12.2 31.8 C10.8 33.4 10.4 35.8 11.6 37.2 C13 38.6 15.8 38 18.4 36.4 C21.2 34.8 24.4 33.4 28 32.4Z" fill="url(#detective-fl)"/><path d="M13.6 31 C12.8 22 17 14.5 24.5 9.6 C31 5.4 39 3.4 49.5 3.4 C60 3.4 68 5.4 74.5 9.6 C82 14.5 86.2 22 85.4 31 C74 27 62 26 49.5 26 C37 26 25 27 13.6 31Z" fill="url(#detective-cr)" stroke="#7d4b2b" stroke-width=".5"/><ellipse cx="47" cy="11" rx="16" ry="6.5" fill="#fff" opacity=".09"/><g fill="none" stroke="#efc7a4" stroke-width=".75" opacity=".7"><path d="M34 8.8 Q49.5 6.2 65 8.8 M33.4 10 Q49.5 7.4 65.6 10 M24 18.2 Q49.5 14 75 18.2 M23.6 19.4 Q49.5 15.2 75.4 19.4 M15.4 26.6 Q20 24.6 25 23.6 M74 23.6 Q79 24.6 83.6 26.6"/><path d="M44 4 C38.5 10 36 18 35.6 26 M55 4 C60.5 10 63 18 63.4 26 M40.5 4.8 C32 9.6 27.4 18 26.4 27.6 M58.5 4.8 C67 9.6 71.6 18 72.6 27.6"/></g><g fill="none" stroke="#8a4a2e" stroke-width=".9" opacity=".85"><path d="M49.5 3.6 V26 M47 3.8 C42 10 39.5 18 39 25.6 M52 3.8 C57 10 59.5 18 60 25.6 M45 4 C35 9 30 18 29 27 M54 4 C64 9 69 18 70 27 M42 4.6 C30 8 22 17 20 29 M57 4.6 C69 8 77 17 79 29"/></g><path d="M25.4 29.6 C30 25 40 22.8 49.8 22.8 C59.6 22.8 69 25 73.4 29.4 C74.2 30.4 73.6 31.4 72.2 31.9 C64 34 56 34.6 49.8 34.6 C43 34.6 35 34 27.4 32 C25.6 31.5 24.8 30.5 25.4 29.6Z" fill="url(#detective-vz)" stroke="#93573a" stroke-width=".7"/><path d="M38 23.6 L35.8 33.6 M49.8 22.8 V34.6 M61.6 23.6 L63.8 33.6" stroke="#8a4a2e" stroke-width=".8" opacity=".7"/><path d="M27 30.4 Q49.8 26.6 72.6 30.4 M43.6 23 L42.6 34.4 M56 23 L57 34.4" fill="none" stroke="#efc7a4" stroke-width=".6" opacity=".6"/><path d="M30 26.2 Q49.8 21.6 69.6 26.2" fill="none" stroke="#f6d59f" stroke-width=".8" opacity=".55"/><rect x="47.7" y="-7" width="3.6" height="10" rx="1.2" fill="#ff6640"/><circle cx="49.5" cy="-11.1" r="5.6" fill="#ffd36e"/><path d="M46.4 -13.4 Q47.6 -15.4 50 -15.6" fill="none" stroke="#fff" stroke-width="1.1" stroke-linecap="round" opacity=".7"/><path fill-rule="evenodd" fill="#71432a" d="M47.5 2.8 C43 -2 36 -4.2 33.2 -1.8 C31.4 0.4 33.6 4.4 38.6 5 C42.4 5.4 45.4 4.2 47.5 2.8Z M44.6 2.2 C41 0.8 37.8 0.2 36.4 0.8 C36 1.6 38 2.4 40.4 2.6 C42 2.7 43.4 2.5 44.6 2.2Z M51.5 2.8 C56 -2 63 -4.2 65.8 -1.8 C67.6 0.4 65.4 4.4 60.4 5 C56.6 5.4 53.6 4.2 51.5 2.8Z M54.4 2.2 C58 0.8 61.2 0.2 62.6 0.8 C63 1.6 61 2.4 58.6 2.6 C57 2.7 55.6 2.5 54.4 2.2Z"/><rect x="46.3" y="0.5" width="6.4" height="4.6" rx="2.1" fill="#80502f"/><path d="M47.6 1.6 H51.4" stroke="#a87650" stroke-width=".7" stroke-linecap="round"/><path d="M35 -1.6 Q38 -3 42 -1.2 M64 -1.6 Q61 -3 57 -1.2" fill="none" stroke="#9a6a48" stroke-width=".7" stroke-linecap="round" opacity=".8"/></g><path d="M5.4 55 L9.6 64.6" stroke="#6e3e22" stroke-width="3.4" stroke-linecap="round"/><circle cx="1.2" cy="44.4" r="12.2" fill="#a8681a"/><circle cx="1.2" cy="44.4" r="11.6" fill="url(#detective-rim)"/><circle cx="1.2" cy="44.4" r="9.5" fill="url(#detective-gl)" stroke="#b07422" stroke-width=".5"/><path d="M-5.2 41 A7 7 0 0 1 -0.4 36.6" fill="none" stroke="#fff" stroke-width="1.6" stroke-linecap="round" opacity=".9"/><circle cx="-5.4" cy="44.6" r=".9" fill="#fff" opacity=".9"/><circle cx="9.5" cy="65.2" r="3.7" fill="url(#armL)"/></g>'
  +   '<g class="acc dino"><defs><linearGradient id="dino-g" gradientUnits="userSpaceOnUse" x1="0" y1="18" x2="0" y2="70"><stop offset="0" stop-color="#96c266"/><stop offset=".5" stop-color="#8bba5c"/><stop offset="1" stop-color="#79ab4e"/></linearGradient><linearGradient id="dino-y" x1=".15" y1=".1" x2=".85" y2=".9"><stop offset="0" stop-color="#ffeb92"/><stop offset=".45" stop-color="#fee581"/><stop offset=".6" stop-color="#fdd963"/><stop offset="1" stop-color="#f8cd4e"/></linearGradient><linearGradient id="dino-gum" gradientUnits="userSpaceOnUse" x1="0" y1="35" x2="0" y2="46"><stop offset="0" stop-color="#d9654c"/><stop offset="1" stop-color="#e8795d"/></linearGradient><linearGradient id="dino-t" gradientUnits="userSpaceOnUse" x1="0" y1="36" x2="0" y2="46"><stop offset="0" stop-color="#fff"/><stop offset="1" stop-color="#e8e3ef"/></linearGradient></defs><path d="M64 26 L57.6 17.8 Q62.26 13.99 67.68 11.37 Q69.2 10.4 69.98 12.02 Q74.23 17.22 75.6 23.8Z" fill="url(#dino-y)" stroke="#e8b13a" stroke-opacity=".45" stroke-width=".5" stroke-linejoin="round"/><path d="M80 34 L73 22.6 Q81.4 22.35 89.13 25.65 Q91 26 90.4 27.8 Q89.83 33.94 86.6 39.2Z" fill="url(#dino-y)" stroke="#e8b13a" stroke-opacity=".45" stroke-width=".5" stroke-linejoin="round"/><path d="M84 47 L85.8 37.2 Q91.23 38.82 94.42 43.5 Q96.2 44.8 94.88 46.56 Q92.52 51.54 88.4 55.2Z" fill="url(#dino-y)" stroke="#e8b13a" stroke-opacity=".45" stroke-width=".5" stroke-linejoin="round"/><path d="M62.2 17 Q65.2 14.4 67.9 12.9 M77 24.6 Q81 23.7 86 24.2 M88.4 40.5 Q91 41.2 93 43" fill="none" stroke="#fff" stroke-opacity=".35" stroke-width="1.1" stroke-linecap="round"/><path d="M17 62 C17 44 29 32.9 50 32.9 C71 32.9 83 44 83 62 L78.6 62 L77.7 56 C76 50.5 72 46.8 66.5 44.2 C62 42 56 41.4 50 41.4 C44 41.4 38 42 33.5 44.2 C28 46.8 24 50.5 22.3 56 L21.4 62Z" fill="url(#dino-gum)"/><path d="M22.3 56 C24 50.5 28 46.8 33.5 44.2 C38 42 44 41.4 50 41.4 C56 41.4 62 42 66.5 44.2 C72 46.8 76 50.5 77.7 56" fill="none" stroke="#c0503a" stroke-opacity=".16" stroke-width="1.4" transform="translate(0 .5)"/><path d="M82.2 67.34 L82.3 63 C82.3 48 70 34.4 50 34.4 C30 34.4 17.7 48 17.7 63 L17.8 67.34" fill="none" stroke="#b9472f" stroke-opacity=".22" stroke-width="3"/><path d="M25.4 42.6 L33.3 36.8 L33.3 38.2 L30.86 45.24 Q30.6 46 29.85 45.71 L25.4 44 Z M33.3 36.8 L44.2 33.4 L44.2 34.8 L40.8 40.61 Q40.4 41.3 39.67 40.98 L33.3 38.2 Z M44.2 33.4 L55.8 33.4 L55.8 34.8 L50.56 40.13 Q50 40.7 49.44 40.13 L44.2 34.8 Z M55.8 33.4 L66.7 36.8 L66.7 38.2 L60.33 40.98 Q59.6 41.3 59.2 40.61 L55.8 34.8 Z M66.7 36.8 L74.6 42.6 L74.6 44 L70.15 45.71 Q69.4 46 69.14 45.24 L66.7 38.2 Z" fill="#8a3a4a" fill-opacity=".16" transform="translate(.3 .9)"/><path d="M25.4 42.6 L33.3 36.8 L33.3 38.2 L30.86 45.24 Q30.6 46 29.85 45.71 L25.4 44 Z M33.3 36.8 L44.2 33.4 L44.2 34.8 L40.8 40.61 Q40.4 41.3 39.67 40.98 L33.3 38.2 Z M44.2 33.4 L55.8 33.4 L55.8 34.8 L50.56 40.13 Q50 40.7 49.44 40.13 L44.2 34.8 Z M55.8 33.4 L66.7 36.8 L66.7 38.2 L60.33 40.98 Q59.6 41.3 59.2 40.61 L55.8 34.8 Z M66.7 36.8 L74.6 42.6 L74.6 44 L70.15 45.71 Q69.4 46 69.14 45.24 L66.7 38.2 Z" fill="url(#dino-t)" stroke="#cbc2d6" stroke-width=".5" stroke-linejoin="round"/><path d="M12.4 69.5 C11.7 67.9 11.4 65.6 11.3 62.5 C11.1 58 11.6 53 11.7 48 A30.3 30.3 0 0 1 31.64 19.53 C35.96 17.96 45.5 17.2 50 17.2 C54.5 17.2 64.04 17.96 68.36 19.53 A30.3 30.3 0 0 1 88.3 48 C88.4 53 88.9 58 88.7 62.5 C88.6 65.6 88.3 67.9 87.6 69.5 C86 68.45 84.15 67.66 82.2 67.34 L82.3 63 C82.3 48 70 34.4 50 34.4 C30 34.4 17.7 48 17.7 63 L17.8 67.34 C15.85 67.66 14 68.45 12.4 69.5Z" fill="url(#dino-g)"/><path d="M17.4 44 A25.6 25.6 0 0 1 32 24.6" fill="none" stroke="#fff" stroke-opacity=".07" stroke-width="7" stroke-linecap="round"/><path d="M13.4 46 A28.6 28.6 0 0 1 24.5 25.6" fill="none" stroke="#e4f4cc" stroke-opacity=".2" stroke-width="1.3" stroke-linecap="round"/><path d="M12.4 69.5 C11.7 67.9 11.4 65.6 11.3 62.5 C11.1 58 11.6 53 11.7 48 A30.3 30.3 0 0 1 31.64 19.53 C35.96 17.96 45.5 17.2 50 17.2 C54.5 17.2 64.04 17.96 68.36 19.53 A30.3 30.3 0 0 1 88.3 48 C88.4 53 88.9 58 88.7 62.5 C88.6 65.6 88.3 67.9 87.6 69.5" fill="none" stroke="#6b9c44" stroke-opacity=".75" stroke-width=".6"/><path d="M82.2 67.34 L82.3 63 C82.3 48 70 34.4 50 34.4 C30 34.4 17.7 48 17.7 63 L17.8 67.34" fill="none" stroke="#6a9a42" stroke-opacity=".7" stroke-width=".9"/><g fill="#5f9440" fill-opacity=".5"><ellipse cx="19.8" cy="37.2" rx="1.9" ry="1.3" transform="rotate(-35 19.8 37.2)"/><ellipse cx="17.8" cy="41.8" rx="1.4" ry="1.1" transform="rotate(-35 17.8 41.8)"/><ellipse cx="15.2" cy="43.8" rx="1.3" ry="1.6" transform="rotate(-15 15.2 43.8)"/><ellipse cx="79.2" cy="38.4" rx="1.1" ry="0.95" transform="rotate(30 79.2 38.4)"/><ellipse cx="83" cy="42.6" rx="1.5" ry="2.4" transform="rotate(20 83 42.6)"/></g><g fill="#567a40" fill-opacity=".85"><ellipse cx="46.5" cy="29.8" rx="1.25" ry=".85" transform="rotate(25 46.5 29.8)"/><ellipse cx="53.5" cy="29.8" rx="1.25" ry=".85" transform="rotate(-25 53.5 29.8)"/></g><circle cx="37.5" cy="27.8" r="2.9" fill="#35303f"/><circle cx="36.8" cy="26.8" r=".9" fill="#fff"/><circle cx="62.5" cy="27.8" r="2.9" fill="#35303f"/><circle cx="61.8" cy="26.8" r=".9" fill="#fff"/></g>'
  +   '<g class="acc elfhat"><defs><linearGradient id="elfhat-g" x1=".1" y1="0" x2=".5" y2="1"><stop offset="0" stop-color="#5fb254"/><stop offset=".5" stop-color="#4b9c45"/><stop offset="1" stop-color="#3b8638"/></linearGradient><linearGradient id="elfhat-s" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#2a6a2c" stop-opacity="0"/><stop offset=".55" stop-color="#2a6a2c" stop-opacity=".55"/><stop offset="1" stop-color="#2a6a2c" stop-opacity=".35"/></linearGradient><linearGradient id="elfhat-r" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#f2494b"/><stop offset=".65" stop-color="#e6373e"/><stop offset="1" stop-color="#d22e37"/></linearGradient><radialGradient id="elfhat-b" cx=".4" cy=".32" r=".72"><stop offset="0" stop-color="#ffe697"/><stop offset=".5" stop-color="#f6c232"/><stop offset="1" stop-color="#d68506"/></radialGradient><radialGradient id="elfhat-k" cx=".42" cy=".3" r=".8"><stop offset="0" stop-color="#fff" stop-opacity=".3"/><stop offset=".5" stop-color="#fff" stop-opacity="0"/><stop offset="1" stop-color="#e59a2e" stop-opacity=".45"/></radialGradient></defs><path d="M16 31 C18.6 24 23.4 18.6 28.4 14.8 C34 10.4 41 5.6 47 1.6 C51 -1.2 55.6 -4.6 61.6 -6.4 C67.6 -8 75.6 -7.6 80.8 -5 C85.8 -2.4 89.2 2 90.6 6.4 C91.4 8.8 91.8 10.6 91.6 12.2 L88.4 12 C87.4 9 85 7.4 82 7.4 C79 7.4 77.4 9 77 11.6 C76.6 14.6 76.8 17.8 77.6 21 L83 26 Q50.5 13.5 16 31Z" fill="none" stroke="#fff" stroke-width="1.4" stroke-linejoin="round" opacity=".4"/><path d="M16 31 C18.6 24 23.4 18.6 28.4 14.8 C34 10.4 41 5.6 47 1.6 C51 -1.2 55.6 -4.6 61.6 -6.4 C67.6 -8 75.6 -7.6 80.8 -5 C85.8 -2.4 89.2 2 90.6 6.4 C91.4 8.8 91.8 10.6 91.6 12.2 L88.4 12 C87.4 9 85 7.4 82 7.4 C79 7.4 77.4 9 77 11.6 C76.6 14.6 76.8 17.8 77.6 21 L83 26 Q50.5 13.5 16 31Z" fill="url(#elfhat-g)"/><path d="M80 -5.4 C85.4 -2.6 89 1.6 90.6 6.4 C91.4 8.8 91.8 10.6 91.6 12.2 L88.4 12 C87.4 9 85 7.4 82 7.4 C79 7.4 77.4 9 77 11.6 C76.6 14.6 76.8 17.8 77.6 21 L80 24 L71 23 C73 16 74 9 74.6 4 C75.2 0 77.6 -3 80 -5.4Z" fill="url(#elfhat-s)"/><path d="M72 -1.2 C75.4 1 77.2 4 77.6 8.6 C76.6 6 75 3.8 73 2.4 C72.2 1.6 71.8 0.4 72 -1.2Z" fill="#256126" opacity=".45"/><path d="M38 8.4 C44 3.8 50 -0.2 55.6 -3.2 C60.6 -5.6 66 -6.4 71 -5.8 C64 -3.8 57.4 -0.2 51 4 C46 7.2 41.6 9 38 8.4Z" fill="#86cc72" opacity=".35"/><rect x="48.4" y="-3" width="2.4" height="4.6" rx="1.1" fill="#b8443a"/><circle class="b-bulb" cx="50.6" cy="-7" r="6.6" fill="#ffd36e"/><circle cx="50.6" cy="-7" r="6.6" fill="url(#elfhat-k)"/><path d="M14.4 37.2 C14 33 14.4 29.6 15.4 26.4 L18.4 18.4 L24.8 20.8 L32.4 9.6 L41.6 17.4 L50 7.9 L60 18 L69.2 11.4 L76.4 21.8 L82.6 19.6 C84 22.6 84.6 26 84.4 32.8 Q50.5 14.3 14.4 37.2Z" fill="url(#elfhat-r)" stroke="#e8393f" stroke-width="1.4" stroke-linejoin="round"/><path d="M19 20 L23.6 21.6 L16.6 25.6Z M32.4 11.6 L39.6 18 L30.4 20.4Z M50 9.9 L58 18.4 L47 19.2Z M69.2 13.4 L74.6 21.4 L66.2 20.2Z" fill="#fb6a68" opacity=".2"/><path d="M14.4 37.2 Q50.5 14.3 84.4 32.8 L84.5 30.6 Q50.5 11.8 14.5 34.8Z" fill="#b8232f" opacity=".3"/><circle cx="92" cy="17.1" r="6.5" fill="url(#elfhat-b)"/><path d="M85.6 18.8 Q92 21.6 98.4 18.8 L98.3 19.8 Q92 22.8 85.7 19.8Z" fill="#c47404" opacity=".75"/><ellipse cx="92.3" cy="16.3" rx="1.1" ry="1.45" fill="#6b3a06"/><ellipse cx="89.6" cy="13.8" rx="2.1" ry="1.2" fill="#fff" opacity=".45" transform="rotate(-28 89.6 13.8)"/></g>'
  +   '<g class="acc froghood"><radialGradient id="froghood-g1" gradientUnits="userSpaceOnUse" cx="44" cy="26" r="52" fx="40" fy="20"><stop offset="0" stop-color="#a2d36e"/><stop offset=".55" stop-color="#95c960"/><stop offset=".85" stop-color="#86bd55"/><stop offset="1" stop-color="#74ae49"/></radialGradient><radialGradient id="froghood-g2" cx=".38" cy=".32" r=".72"><stop offset="0" stop-color="#aad877"/><stop offset=".7" stop-color="#94c860"/><stop offset="1" stop-color="#80b952"/></radialGradient><radialGradient id="froghood-g3" cx=".36" cy=".3" r=".75"><stop offset="0" stop-color="#5a5466"/><stop offset=".5" stop-color="#3f3b4b"/><stop offset="1" stop-color="#34303f"/></radialGradient><path d="M12.3 69.7C9.9 63.7 7.3 56.5 7.3 47C7.3 27.4 26.09 12 50 12C73.91 12 92.7 27.4 92.7 47C92.7 56.5 90.1 63.7 87.7 69.7L87.33 69.58C85.49 68.42 83.35 67.61 81.1 67.47C83.5 64.37 84.7 60.5 84.7 55.5C84.6 49 82.4 43.6 78.5 38.6C72.4 30.5 63 27.8 50 27.8C37 27.8 27.6 30.5 21.5 38.6C17.6 43.6 15.4 49 15.3 55.5C15.3 60.5 16.5 64.37 18.9 67.47C16.65 67.61 14.51 68.42 12.67 69.58Z" fill="url(#froghood-g1)"/><path d="M12.3 69.7C9.9 63.7 7.3 56.5 7.3 47C7.3 27.4 26.09 12 50 12C73.91 12 92.7 27.4 92.7 47C92.7 56.5 90.1 63.7 87.7 69.7" fill="none" stroke="#72aa47" stroke-width=".8"/><path d="M82.21 68.33C84.76 65.03 86.1 60.71 86.1 55.5C86 48.72 83.62 42.89 79.6 37.74C73.24 29.28 63.2 26.4 50 26.4C36.8 26.4 26.76 29.28 20.38 37.76C16.38 42.89 14 48.72 13.9 55.48C13.9 60.71 15.24 65.03 17.79 68.33" fill="none" stroke="#a4d85e" stroke-width="2.4"/><path d="M81.34 67.66C83.77 64.51 85 60.55 85 55.5C84.9 48.94 82.66 43.45 78.74 38.42C72.58 30.24 63.04 27.5 50 27.5C36.96 27.5 27.42 30.24 21.26 38.42C17.34 43.45 15.1 48.94 15 55.5C15 60.55 16.23 64.51 18.66 67.66" fill="none" stroke="#6ea844" stroke-width=".7"/><circle cx="24.8" cy="17.2" r="11.3" fill="url(#froghood-g2)"/><path d="M35.86 16.23 A11.1 11.1 0 0 1 19.25 26.81" fill="none" stroke="#6fa845" stroke-width=".8" stroke-linecap="round" opacity=".75"/><circle cx="24.8" cy="17.4" r="8" fill="#fff" stroke="#e3e6e0" stroke-width=".5"/><circle cx="24.9" cy="17.6" r="5.7" fill="url(#froghood-g3)"/><ellipse cx="22.7" cy="15.3" rx="2.8" ry="1.9" transform="rotate(-35 22.7 15.3)" fill="#fff" opacity=".07"/><ellipse cx="35.9" cy="24.9" rx="3.25" ry="2.3" fill="#fbaebd"/><circle cx="75.2" cy="17.2" r="11.3" fill="url(#froghood-g2)"/><path d="M64.14 16.23 A11.1 11.1 0 0 0 80.75 26.81" fill="none" stroke="#6fa845" stroke-width=".8" stroke-linecap="round" opacity=".75"/><circle cx="75.2" cy="17.4" r="8" fill="#fff" stroke="#e3e6e0" stroke-width=".5"/><circle cx="75.1" cy="17.6" r="5.7" fill="url(#froghood-g3)"/><ellipse cx="73.1" cy="15.3" rx="2.8" ry="1.9" transform="rotate(-35 73.1 15.3)" fill="#fff" opacity=".07"/><ellipse cx="64.1" cy="24.9" rx="3.25" ry="2.3" fill="#fbaebd"/><path d="M43.9 19 Q50 24.8 56.1 19" fill="none" stroke="#343a36" stroke-width="1.4" stroke-linecap="round"/></g>'
  +   '<g class="acc ginger"><defs><radialGradient id="ginger-hood" cx=".42" cy=".12" r=".92"><stop offset="0" stop-color="#eb9c66"/><stop offset=".5" stop-color="#df8b55"/><stop offset=".8" stop-color="#d27e47"/><stop offset="1" stop-color="#bc622e"/></radialGradient><linearGradient id="ginger-body" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#b9622f"/><stop offset=".18" stop-color="#d98751"/><stop offset=".7" stop-color="#d5824d"/><stop offset="1" stop-color="#ad5826"/></linearGradient><linearGradient id="ginger-in" gradientUnits="userSpaceOnUse" x1="0" y1="30" x2="0" y2="50"><stop offset="0" stop-color="#c98e78" stop-opacity=".75"/><stop offset="1" stop-color="#c98e78" stop-opacity="0"/></linearGradient><linearGradient id="ginger-bow" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ff5a60"/><stop offset=".5" stop-color="#e8323b"/><stop offset="1" stop-color="#c01d27"/></linearGradient></defs><path d="M14.5 70.5c-1.5 5.5 1.9 17.5 2.1 26.1c.2 7.6 13.4 14 33.4 14c20 0 33.2-6.4 33.4-14c.2-8.6 3.6-20.6 2.1-26.1l-9.9.9q-25.6 9.4-51.2 0z" fill="url(#ginger-body)" stroke="#b4652f" stroke-width=".5"/><ellipse cx="11.6" cy="85.4" rx="8.6" ry="11.4" transform="rotate(-12 11.6 85.4)" fill="url(#ginger-hood)" stroke="#b8672f" stroke-width=".4" stroke-opacity=".7"/><ellipse cx="88.4" cy="85.4" rx="8.6" ry="11.4" transform="rotate(12 88.4 85.4)" fill="url(#ginger-hood)" stroke="#b8672f" stroke-width=".4" stroke-opacity=".7"/><path d="M10.2 75.6c.4.5 2.2 1.7 2.4 2.8s-1.3 2.7-1.2 3.8s1.3 2.1 2 3s2 1.4 2.2 2.4s-.8 3.2-1 3.8" fill="none" stroke="#fdf8f4" stroke-width="2" stroke-linecap="round"/><path d="M89.8 75.6c-.4.5-2.2 1.7-2.4 2.8s1.3 2.7 1.2 3.8s-1.3 2.1-2 3s-2 1.4-2.2 2.4s.8 3.2 1 3.8" fill="none" stroke="#fdf8f4" stroke-width="2" stroke-linecap="round"/><path d="M19.6 94.9c.7.1 3.1.1 4.4.4s2.4.5 3.4 1.1s1.7 2 2.6 2.8s1.9 1.8 3 2.1s2.1 0 3.4-.3s2.9-1.3 4.2-1.4s2.3.5 3.4 1s2 1.4 3 1.8s2 .9 3 .9s2-.5 3-.9s1.9-1.3 3-1.8s2.2-1.1 3.4-1.1s2.7.8 4 1.1s2.5.8 3.6.6s2.1-1 3-1.8s1.6-2.3 2.6-3s2.1-.9 3.4-1.1s3.7-.3 4.4-.4" fill="none" stroke="#9c5428" stroke-opacity=".35" stroke-width="2.7" stroke-linecap="round" transform="translate(0 .7)"/><path d="M19.6 94.9c.7.1 3.1.1 4.4.4s2.4.5 3.4 1.1s1.7 2 2.6 2.8s1.9 1.8 3 2.1s2.1 0 3.4-.3s2.9-1.3 4.2-1.4s2.3.5 3.4 1s2 1.4 3 1.8s2 .9 3 .9s2-.5 3-.9s1.9-1.3 3-1.8s2.2-1.1 3.4-1.1s2.7.8 4 1.1s2.5.8 3.6.6s2.1-1 3-1.8s1.6-2.3 2.6-3s2.1-.9 3.4-1.1s3.7-.3 4.4-.4" fill="none" stroke="#fdf8f4" stroke-width="2.2" stroke-linecap="round"/><circle cx="50" cy="88.2" r="2.6" fill="#a8582a" opacity=".35"/><circle cx="50" cy="87.6" r="2.5" fill="#fffaf6"/><circle cx="50" cy="96.2" r="2.6" fill="#a8582a" opacity=".35"/><circle cx="50" cy="95.6" r="2.5" fill="#fffaf6"/><path d="M15 72.6 Q50 86 85 72.6" fill="none" stroke="#9c4f22" stroke-width="3" stroke-linecap="round" opacity=".35"/><path d="M50 30.2c9 0 14.6.7 18.4 2.4c5 2.2 9.2 7 9.8 12.4c.2 1.2.2 2 .2 2.5c.1 8.5-1.2 15-2.8 21.9q-25.6 9.4-51.2 0c-1.6-6.9-2.9-13.4-2.8-21.9c0-.5 0-1.3.2-2.5c.6-5.4 4.8-10.2 9.8-12.4c3.8-1.7 9.4-2.4 18.4-2.4z" fill="none" stroke="url(#ginger-in)" stroke-width="5"/><path fill-rule="evenodd" d="M45.5 16.6h9a36 36 0 0 1 36 36c0 7.4-1.5 13.9-4 17.9q-36.5 12-73 0c-2.5-4-4-10.5-4-17.9a36 36 0 0 1 36-36z M50 30.2c9 0 14.6.7 18.4 2.4c5 2.2 9.2 7 9.8 12.4c.2 1.2.2 2 .2 2.5c.1 8.5-1.2 15-2.8 21.9q-25.6 9.4-51.2 0c-1.6-6.9-2.9-13.4-2.8-21.9c0-.5 0-1.3.2-2.5c.6-5.4 4.8-10.2 9.8-12.4c3.8-1.7 9.4-2.4 18.4-2.4z" fill="url(#ginger-hood)" stroke="#b4652f" stroke-width=".5"/><path d="M50 30.2c9 0 14.6.7 18.4 2.4c5 2.2 9.2 7 9.8 12.4c.2 1.2.2 2 .2 2.5c.1 8.5-1.2 15-2.8 21.9q-25.6 9.4-51.2 0c-1.6-6.9-2.9-13.4-2.8-21.9c0-.5 0-1.3.2-2.5c.6-5.4 4.8-10.2 9.8-12.4c3.8-1.7 9.4-2.4 18.4-2.4z" fill="none" stroke="#b8673a" stroke-width=".9"/><path d="M13.5 52.2c.3-1.3 1.4-5.5 2-8s.4-5.2 1.3-6.8s2.7-2.1 4.2-3.1s3.8-1.7 5-2.7s1.3-2.2 2-3.2s1.5-2.1 2.4-2.8s2-1.2 3-1.3s2.2.3 3 .6s1.4 1.1 2 1.4s1.2.6 1.9.5s1.3-.5 2.1-1s1.6-1.6 2.4-2.2s1.7-1.2 2.6-1.6s1.7-.5 2.6-.5s1.7.1 2.6.5s1.8 1 2.6 1.6s1.7 1.7 2.4 2.2s1.4.9 2.1 1s1.3-.2 1.9-.5s1.2-1.1 2-1.4s2-.7 3-.6s2.1.6 3 1.3s1.7 1.8 2.4 2.8s.8 2.2 2 3.2s3.5 1.7 5 2.7s3.3 1.5 4.2 3.1s.8 4.3 1.3 6.8s1.7 6.7 2 8" fill="none" stroke="#9c5428" stroke-opacity=".35" stroke-width="2.8" stroke-linecap="round" transform="translate(0 .7)"/><path d="M13.5 52.2c.3-1.3 1.4-5.5 2-8s.4-5.2 1.3-6.8s2.7-2.1 4.2-3.1s3.8-1.7 5-2.7s1.3-2.2 2-3.2s1.5-2.1 2.4-2.8s2-1.2 3-1.3s2.2.3 3 .6s1.4 1.1 2 1.4s1.2.6 1.9.5s1.3-.5 2.1-1s1.6-1.6 2.4-2.2s1.7-1.2 2.6-1.6s1.7-.5 2.6-.5s1.7.1 2.6.5s1.8 1 2.6 1.6s1.7 1.7 2.4 2.2s1.4.9 2.1 1s1.3-.2 1.9-.5s1.2-1.1 2-1.4s2-.7 3-.6s2.1.6 3 1.3s1.7 1.8 2.4 2.8s.8 2.2 2 3.2s3.5 1.7 5 2.7s3.3 1.5 4.2 3.1s.8 4.3 1.3 6.8s1.7 6.7 2 8" fill="none" stroke="#fdf8f4" stroke-width="2.3" stroke-linecap="round"/><path d="M46.5 75.6c-3-2.2-6.1-4.2-8.1-3.8c-2 .4-2.4 3.2-2.4 6.2c0 3.4.6 6 2.6 6.2c2.2.2 5-1.8 7.9-3.8z" fill="url(#ginger-bow)"/><path d="M46.5 75.6c-3-2.2-6.1-4.2-8.1-3.8c-2 .4-2.4 3.2-2.4 6.2c0 3.4.6 6 2.6 6.2c2.2.2 5-1.8 7.9-3.8z" fill="url(#ginger-bow)" transform="matrix(-1 0 0 1 100 0)"/><path d="M45 76.8 Q42 75.4 39.4 75.6 M45 79.2 Q42.4 80.8 39.8 81.4 M55 76.8 Q58 75.4 60.6 75.6 M55 79.2 Q57.6 80.8 60.2 81.4" fill="none" stroke="#a5121c" stroke-width=".8" stroke-linecap="round" opacity=".55"/><path d="M38.4 73.4 Q40.6 72.6 43 74 M61.6 73.4 Q59.4 72.6 57 74" fill="none" stroke="#ff8f93" stroke-width=".8" stroke-linecap="round" opacity=".7"/><rect x="45.8" y="74.4" width="8.4" height="7.2" rx="2.4" fill="url(#ginger-bow)" stroke="#b3161f" stroke-width=".4"/></g>'
  +   '<g class="acc globe"><g transform="translate(50 86.9) scale(.95)"><defs><radialGradient id="globe-gl" cx=".4" cy=".36" r=".68"><stop offset="0" stop-color="#c9e6f9"/><stop offset=".5" stop-color="#8ec1e7"/><stop offset="1" stop-color="#5896cf"/></radialGradient><linearGradient id="globe-bs" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ec434b"/><stop offset=".6" stop-color="#d22e39"/><stop offset="1" stop-color="#a91e2a"/></linearGradient><radialGradient id="globe-hd" cx=".38" cy=".32" r=".75"><stop offset="0" stop-color="#ffb995"/><stop offset=".6" stop-color="#ff8f63"/><stop offset="1" stop-color="#f2713f"/></radialGradient></defs><circle r="20.1" fill="#f3f8fd" stroke="#bcd7ee" stroke-width=".5"/><circle r="18.4" fill="url(#globe-gl)"/><path d="M-16.2 8.8C-11 7.8-6 9.2 0 9C6 8.8 11 8 16.2 8.8A18.4 18.4 0 0 1-16.2 8.8Z" fill="#fbfdff"/><path d="M-15 12.2C-6 11.4 6 11.4 15 12.2C6 13.4-6 13.4-15 12.2Z" fill="#d9e8f4"/><g transform="translate(-1 9.6) scale(1.08) translate(1 -9.6)"><rect x="8.9" y="8.4" width="1.6" height="2.2" fill="#7a4424"/><path d="M9.7 -8.2L7.1 -3.8Q8.40 -3.1 9.7 -3.3ZM9.7 -6L6.3 -0.2Q8.00 0.5 9.7 0.3ZM9.7 -3L5.6 3.8Q7.65 4.5 9.7 4.3ZM9.7 0.4L5.0 8.4Q7.35 9.1 9.7 8.9Z" fill="#36a457"/><path d="M9.7 -8.2L12.3 -3.8Q11.00 -3.1 9.7 -3.3ZM9.7 -6L13.1 -0.2Q11.40 0.5 9.7 0.3ZM9.7 -3L13.8 3.8Q11.75 4.5 9.7 4.3ZM9.7 0.4L14.4 8.4Q12.05 9.1 9.7 8.9Z" fill="#1f7e3d"/><path d="M9.7-8.8L8.3-6.1Q9.7-5.4 11.1-6.1Z" fill="#fff"/><rect x="-10" y="1.4" width="14.8" height="8.6" fill="#8a4627"/><path d="M-10 3.6H4.8M-10 6H4.8M-10 8.4H4.8" stroke="#a0582f" stroke-width=".5"/><path d="M-9.4 1.8L-2.6-4.4L4.2 1.8Z" fill="#9a5029"/><path d="M-11.4 2.4L-2.6-5.8L6.2 2.4" fill="none" stroke="#6b3418" stroke-width="3" stroke-linejoin="round" stroke-linecap="round"/><path d="M-11.6 1.6L-2.6-6.8L6.4 1.6" fill="none" stroke="#fff" stroke-width="2.4" stroke-linejoin="round" stroke-linecap="round"/><circle cx="-2.5" cy="-1.3" r="2.2" fill="#f4f0ea"/><circle cx="-2.5" cy="-1.3" r="1.3" fill="#f6b73c"/><g fill="#ffd65a" stroke="#7a3a1f" stroke-width=".35"><rect x="-7.8" y="3.9" width="3.4" height="3.8"/><rect x="-.8" y="3.9" width="3.4" height="3.8"/></g><path d="M-6.1 3.9v3.8M-7.8 5.8h3.4M.9 3.9v3.8M-.8 5.8h3.4" stroke="#7a3a1f" stroke-width=".35"/></g><g fill="#fff"><circle cx="1" cy="-15.1" r="0.8"/><circle cx="7.5" cy="-13.4" r="0.75"/><circle cx="12.5" cy="-9.7" r="0.8"/><circle cx="4.6" cy="-8.4" r="0.55"/><circle cx="-5.4" cy="-10.9" r="0.7"/><circle cx="-13.8" cy="-4.3" r="0.6"/><circle cx="-10.6" cy="-7.6" r="0.5"/><circle cx="14.6" cy="-2.9" r="0.6"/><circle cx="-1.4" cy="-12.2" r="0.5"/><circle cx="-14.6" cy="5.4" r="0.55"/><circle cx="15.2" cy="5.4" r="0.55"/><circle cx="-7.6" cy="-14.4" r="0.55"/><circle cx="10.2" cy="-14.6" r="0.45"/><circle cx="16" cy="1.2" r="0.5"/></g><path d="M-15.6 5.4A16.8 16.8 0 0 1-5.6-14.8" fill="none" stroke="#fff" stroke-width="1.6" stroke-linecap="round" opacity=".55"/><ellipse cx="-9.4" cy="-13" rx="2.6" ry="1.1" transform="rotate(-38 -9.4 -13)" fill="#fff" opacity=".85"/><path d="M-17.6 13.4Q0 16.4 17.6 13.4C17 15.6 16.8 17 17.5 18.4C18.3 19.4 18.8 20.1 18.4 20.9Q17.8 21.9 16 21.9H-16Q-17.8 21.9-18.4 20.9C-18.8 20.1-18.3 19.4-17.5 18.4C-16.8 17-17 15.6-17.6 13.4Z" fill="url(#globe-bs)"/><path d="M-17.6 13.2Q0 16.2 17.6 13.2L17.3 15.3Q0 18.7-17.3 15.3Z" fill="#f05a61"/><path d="M-17.6 13.2Q0 16.2 17.6 13.2" fill="none" stroke="#a51d29" stroke-width=".5"/><path d="M-17.6 19.2Q0 22.4 17.6 19.2" fill="none" stroke="#ff7a80" stroke-width=".5" opacity=".5"/><path d="M-15.2 16.6Q-15 18.2-14 18.8" fill="none" stroke="#fff" stroke-width=".7" stroke-linecap="round" opacity=".35"/><circle cx="-27.2" cy="1.8" r="9.6" fill="url(#globe-hd)"/><circle cx="27.2" cy="1.8" r="9.6" fill="url(#globe-hd)"/></g></g>'
  +   '<g class="acc hero"><defs><linearGradient id="hero-m" x2="0" y2="1"><stop offset="0" stop-color="#9d6ee6"/><stop offset="1" stop-color="#8352cf"/></linearGradient><linearGradient id="hero-c" x2="0" y2="1"><stop offset="0" stop-color="#f45454"/><stop offset=".55" stop-color="#e3393d"/><stop offset="1" stop-color="#c42a31"/></linearGradient><linearGradient id="hero-f" x2="0" y2="1"><stop offset="0" stop-color="#c0303a"/><stop offset="1" stop-color="#a8242c"/></linearGradient><linearGradient id="hero-o" x2="0" y2="1"><stop offset="0" stop-color="#ea4446"/><stop offset="1" stop-color="#d7353a"/></linearGradient><radialGradient id="hero-g" cx=".4" cy=".35" r=".7"><stop offset="0" stop-color="#ffe07a"/><stop offset="1" stop-color="#f6b62c"/></radialGradient></defs><path d="M12.4 65.8C12.1 66.4 11.4 68.1 10.6 69.6S8.7 73.1 7.4 75S4.4 79.1 3 80.8S-.2 84.4 -1.2 85.4S-2.5 86.7 -2.8 87C-2.5 87.2 -2.1 88.2 -1 88.4S2.4 88.6 4 88.4S7.2 87 8.8 87S11.8 88 13.4 88.4S16.8 89.4 18.4 89.6S21.4 89.5 23 89.4S27 89.2 27.8 89.2C27.2 88.5 24.9 87.2 23.4 86S20.5 83.3 19.3 82S17.3 79.3 16.5 78S15 75.3 14.5 74S13.5 71.3 13.1 70S12.5 66.8 12.4 66.2Z" fill="url(#hero-f)"/><path d="M87.6 65.8C87.9 66.4 88.6 68.1 89.4 69.6S91.3 73.1 92.6 75S95.6 79.1 97 80.8S100.2 84.4 101.2 85.4S102.5 86.7 102.8 87C102.5 87.2 102.1 88.2 101 88.4S97.6 88.6 96 88.4S92.8 87 91.2 87S88.2 88 86.6 88.4S83.2 89.4 81.6 89.6S78.6 89.5 77 89.4S73 89.2 72.2 89.2C72.8 88.5 75.1 87.2 76.6 86S79.5 83.3 80.7 82S82.7 79.3 83.5 78S85 75.3 85.5 74S86.5 71.3 86.9 70S87.5 66.8 87.6 66.2Z" fill="url(#hero-f)"/><path d="M12.4 65.8C12.1 66.4 11.4 68.1 10.6 69.6S8.7 73.1 7.4 75S4.4 79.1 3 80.8S-.2 84.4 -1.2 85.4S-2.5 86.7 -2.8 87C-2.5 87.2 -2.1 88.2 -1 88.4S2.4 88.6 4 88.4S8 87.2 8.8 87C9.1 86.1 10 83.4 10.6 81.6S11.9 77.8 12.4 76S13.5 72.4 13.8 71S14.3 68.2 14.4 67.6Z" fill="url(#hero-o)"/><path d="M87.6 65.8C87.9 66.4 88.6 68.1 89.4 69.6S91.3 73.1 92.6 75S95.6 79.1 97 80.8S100.2 84.4 101.2 85.4S102.5 86.7 102.8 87C102.5 87.2 102.1 88.2 101 88.4S97.6 88.6 96 88.4S92 87.2 91.2 87C90.9 86.1 90 83.4 89.4 81.6S88.1 77.8 87.6 76S86.5 72.4 86.2 71S85.7 68.2 85.6 67.6Z" fill="url(#hero-o)"/><path d="M15 74.4C15.2 75.5 15.6 78.7 16 81S17.2 87.2 17.4 88.4M85 74.4C84.8 75.5 84.4 78.7 84 81S82.8 87.2 82.6 88.4" fill="none" stroke="#931c24" stroke-opacity=".45" stroke-width=".9" stroke-linecap="round"/><path d="M17.6 74.8C19.1 75.2 23.4 76.7 26.6 77.4S33.7 78.6 36.8 79S40.6 79.6 45 79.6S58.5 79.4 63.2 79S70.2 78.1 73.4 77.4S80.9 75.2 82.4 74.8" fill="none" stroke="#5b3cb4" stroke-opacity=".22" stroke-width="1.8" stroke-linecap="round"/><path d="M12.2 65.9C13.3 65.9 16.4 65.6 19 65.9S25 67.2 28 67.9S34.3 69.6 37 70.4S41.8 72.4 44 73S48 74.2 50 74.2S53.8 73.6 56 73S60.3 71.2 63 70.4S69 68.7 72 67.9S78.4 66.2 81 65.9S86.7 65.9 87.8 65.9C87.9 66.5 88.9 68.3 88.6 69.4S86.2 72.1 85.7 72.6C84.9 72.9 82.8 73.7 80.8 74.3S76 75.7 73.4 76.2S68.2 77.2 65.4 77.5S59.2 78 56.6 78.3S52.2 79 50 79S46 78.5 43.4 78.3S37.4 77.8 34.6 77.5S29.2 76.7 26.6 76.2S21.2 74.9 19.2 74.3S15.1 72.9 14.3 72.6C13.8 72.1 11.8 70.5 11.4 69.4S12.1 66.5 12.2 65.9Z" fill="url(#hero-c)"/><path d="M15 66.7C15.9 66.8 18.5 66.8 20.4 67S25.4 68 26.4 68.2M85 66.7C84.1 66.8 81.5 66.8 79.6 67S74.6 68 73.6 68.2" fill="none" stroke="#ff9a96" stroke-opacity=".5" stroke-width="1.1" stroke-linecap="round"/><path d="M19.6 69.6C20.7 69.8 23.8 70.5 26 71S30.6 72 32.8 72.6S38.3 74.3 39.4 74.6M80.4 69.6C79.3 69.8 76.2 70.5 74 71S69.4 72 67.2 72.6S61.7 74.3 60.6 74.6" fill="none" stroke="#b5262d" stroke-opacity=".5" stroke-width=".8" stroke-linecap="round"/><circle cx="50" cy="76.2" r="7.0" fill="#eea11c"/><circle cx="50" cy="76.2" r="5.9" fill="url(#hero-g)"/><path d="M50 72.2L51.1 74.9L54 75.1L51.8 77L52.5 79.8L50 78.2L47.5 79.8L48.2 77L46 75.1L48.9 74.9Z" fill="#ffeaa0" stroke="#e39a17" stroke-width=".55" stroke-linejoin="round"/><path fill-rule="evenodd" d="M18.4 48C18.3 46 18.8 43.7 19.8 42S22.7 38.8 24.6 37.8S29.1 36.3 31.4 36.2S36.4 36.6 38.6 37.2S42.7 39.3 44.6 40S48.2 41.4 50 41.4S53.5 40.7 55.4 40S59.2 37.8 61.4 37.2S66.3 36.1 68.6 36.2S73.5 36.8 75.4 37.8S79.2 40.3 80.2 42S81.7 46 81.6 48S81 52.3 79.8 54S76.7 57.4 74.6 58.4S69.7 59.9 67.4 59.8S62.6 58.6 60.6 57.6S57.2 54.8 55.4 54S51.8 52.8 50 52.8S46.4 53.2 44.6 54S41.4 56.6 39.4 57.6S34.9 59.7 32.6 59.8S27.5 59.4 25.4 58.4S21.4 55.7 20.2 54S18.5 50 18.4 48ZM25.3 48.4a8.1 6.9 0 1 0 16.2 0a8.1 6.9 0 1 0-16.2 0ZM58.5 48.4a8.1 6.9 0 1 0 16.2 0a8.1 6.9 0 1 0-16.2 0Z" fill="url(#hero-m)" stroke="#6f3fbb" stroke-opacity=".45" stroke-width=".6"/><path d="M23.2 41C23.9 40.6 25.9 39.1 27.4 38.6S30.6 37.8 32.4 37.8S37.2 38.6 38.2 38.8M61.8 38.8C62.8 38.6 65.8 37.8 67.6 37.8S71.1 38.1 72.6 38.6S76.1 40.6 76.8 41" fill="none" stroke="#c4a3f5" stroke-opacity=".55" stroke-width="1" stroke-linecap="round"/></g>'
  +   '<g class="acc knight"><defs><linearGradient id="knight-g1" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#e4e6eb"/><stop offset=".4" stop-color="#bcc1c9"/><stop offset="1" stop-color="#9aa0aa"/></linearGradient><linearGradient id="knight-g3" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#f25b55"/><stop offset="1" stop-color="#c62a38"/></linearGradient><linearGradient id="knight-g4" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffd650"/><stop offset="1" stop-color="#f4a81c"/></linearGradient><linearGradient id="knight-g5" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#d4d7dd"/><stop offset="1" stop-color="#8e949f"/></linearGradient><linearGradient id="knight-g6" x1="1" y1="0" x2="0" y2="0"><stop offset="0" stop-color="#d4d7dd"/><stop offset="1" stop-color="#8e949f"/></linearGradient></defs><path d="M13.6 67 L23.9 67 L24.4 69.4 Q28.24 70.81 32.08 71.8 L32.6 73.4 L13.6 73.4Z M86.4 67 L76.1 67 L75.6 69.4 Q71.76 70.81 67.92 71.8 L67.4 73.4 L86.4 73.4Z" fill="#ff7a55"/><path d="M55.6 7 C53 0 53.2 -8 56.6 -12.8 C59.4 -16.4 64.6 -17.2 70 -16 C76.6 -14.6 82 -10.4 86 -6.8 C88.6 -4.6 91.4 -3.2 93.6 -3.8 C95 -4.2 96 -5.4 96.6 -7 C97.8 -2.6 97 2.6 93.4 6.2 C89.6 9.8 83.6 11.8 77.4 11.8 C71.6 11.8 66.4 10 63 7.6Z" fill="url(#knight-g3)"/><path d="M57.8 3 C56.4 -5.4 60 -12 67 -12.4 C74.4 -12.8 81.2 -8.2 87.4 -3.6 C81 -5.8 74 -8.2 68.2 -7.6 C62.8 -7 59.8 -2.6 60.4 3Z" fill="#b32436" opacity=".5"/><path d="M64 7.6 C66.6 1.8 72.4 -.2 78.4 1 C83.6 2 89 2.4 95 -.6 C91.4 5.2 84 8.4 77.4 7.8 C72 7.2 67.4 7.2 64 7.6Z" fill="#b32436" opacity=".45"/><path d="M56.8 -3 C56.8 -8.8 59.8 -13.4 65.4 -14.8 C61.6 -11.8 59.6 -8.2 59 -2Z" fill="#f88a7c"/><path d="M80 -9.6 C84.4 -7 88.6 -3.4 93 -4.2" fill="none" stroke="#f88a7c" stroke-width="1.1" stroke-linecap="round" opacity=".8"/><ellipse cx="59" cy="4.6" rx="4" ry="2.4" fill="#a3a9b3" stroke="#878d98" stroke-width=".5"/><line x1="46.5" y1="6" x2="46.5" y2="-6" stroke="#ff6640" stroke-width="3" stroke-linecap="round"/><circle cx="46.5" cy="-10.4" r="5.4" fill="#ffd36e"/><circle cx="44.8" cy="-12.2" r="1.6" fill="#fff" opacity=".6"/><path d="M50 76.6 C36 76.6 20 76 13.6 73.6 C9.6 72 8.6 68.4 8.6 63 L8.6 46 C8.6 22 26 3.4 50 3.4 C74 3.4 91.4 22 91.4 46 L91.4 63 C91.4 68.4 90.4 72 86.4 73.6 C80 76 64 76.6 50 76.6Z M50 30.4 C64 30.4 78 32.6 84.6 37.6 C85.6 38.4 85.8 39.6 85.8 41 L85.8 63.6 C85.8 67.8 83.8 69.6 79 70.2 C70 71.2 60 71.4 50 71.4 C40 71.4 30 71.2 21 70.2 C16.2 69.6 14.2 67.8 14.2 63.6 L14.2 41 C14.2 39.6 14.4 38.4 15.4 37.6 C22 32.6 36 30.4 50 30.4Z" fill="url(#knight-g1)" fill-rule="evenodd" stroke="#858b96" stroke-width=".9"/><path d="M 50.00 28.96 C 64.63 28.96 79.26 31.31 86.16 36.66 C 87.20 37.52 87.41 38.80 87.41 40.30 L 87.41 64.48 C 87.41 68.98 85.32 70.90 80.31 71.54 C 70.90 72.61 60.45 72.83 50.00 72.83 C 39.55 72.83 29.10 72.61 19.70 71.54 C 14.68 70.90 12.59 68.98 12.59 64.48 L 12.59 40.30 C 12.59 38.80 12.80 37.52 13.84 36.66 C 20.74 31.31 35.37 28.96 50.00 28.96 Z" fill="none" stroke="#eceef2" stroke-width=".9" opacity=".9"/><path d="M15.6 38.4 C22.4 33.6 36 31.4 50 31.4 C64 31.4 77.6 33.6 84.4 38.4" fill="none" stroke="#5c6070" stroke-width="2.2" opacity=".14"/><path d="M21 22 C26 12.4 35 7.2 46 6.4 C38 9.6 30 15 24.6 23.4Z" fill="#fff" opacity=".5"/><line x1="50" y1="3.8" x2="50" y2="10.6" stroke="#959ba6" stroke-width=".8"/><g fill="#3a3e47"><rect x="23" y="20.1" width="4.5" height="8.6" rx="2.25"/><rect x="31.9" y="15.4" width="4.5" height="10" rx="2.25"/><rect x="41.3" y="12.1" width="4.5" height="12" rx="2.25"/><rect x="54.2" y="12.1" width="4.5" height="12" rx="2.25"/><rect x="63.6" y="15.4" width="4.5" height="10" rx="2.25"/><rect x="72.5" y="20.1" width="4.5" height="8.6" rx="2.25"/></g><rect x="2.6" y="34" width="9.4" height="16" rx="4.2" fill="url(#knight-g5)" stroke="#80868f" stroke-width=".6"/><ellipse cx="6" cy="42" rx="2.8" ry="6" fill="#e4e6ea"/><rect x="88" y="34" width="9.4" height="16" rx="4.2" fill="url(#knight-g6)" stroke="#80868f" stroke-width=".6"/><ellipse cx="94" cy="42" rx="2.8" ry="6" fill="#e4e6ea"/><path d="M50 68.6 C53 70.4 56 71.2 59 71.2 L59 78.2 C59 83.4 55 86.6 50 88.6 C45 86.6 41 83.4 41 78.2 L41 71.2 C44 71.2 47 70.4 50 68.6Z" fill="url(#knight-g4)" stroke="#e09a12" stroke-width=".9"/><path d="M50 71.2 C52.4 72.6 54.6 73.2 56.6 73.2 L56.6 78.2 C56.6 82 53.8 84.4 50 86 C46.2 84.4 43.4 82 43.4 78.2 L43.4 73.2 C45.4 73.2 47.6 72.6 50 71.2Z" fill="#ffc93a" stroke="#f0a91f" stroke-width=".6"/><path d="M50.00 73.40 L51.29 76.82 L54.95 76.99 L52.09 79.28 L53.06 82.81 L50.00 80.80 L46.94 82.81 L47.91 79.28 L45.05 76.99 L48.71 76.82Z" fill="#ffe9a0" stroke="#e9a21c" stroke-width=".6" stroke-linejoin="round"/></g>'
  +   '<g class="acc panda"><radialGradient id="panda-g1" gradientUnits="userSpaceOnUse" cx="46" cy="30" r="50" fx="42" fy="24"><stop offset="0" stop-color="#ffffff"/><stop offset=".62" stop-color="#fdfbfd"/><stop offset=".86" stop-color="#f1ecf3"/><stop offset="1" stop-color="#ddd5e4"/></radialGradient><radialGradient id="panda-g2" cx=".38" cy=".3" r=".75"><stop offset="0" stop-color="#686277"/><stop offset="1" stop-color="#47425a"/></radialGradient><radialGradient id="panda-g3" cx=".4" cy=".35" r=".7"><stop offset="0" stop-color="#55506a"/><stop offset="1" stop-color="#3a3649"/></radialGradient><path d="M12.16 29.44 A14.2 14.2 0 1 1 34.5 12.3Z" fill="url(#panda-g2)" stroke="#fbf9fd" stroke-width=".9"/><circle cx="20.1" cy="16.8" r="6.5" fill="#3c384b"/><path d="M87.84 29.44 A14.2 14.2 0 1 0 65.5 12.3Z" fill="url(#panda-g2)" stroke="#fbf9fd" stroke-width=".9"/><circle cx="79.9" cy="16.8" r="6.5" fill="#3c384b"/><path d="M12.3 69.7C9.9 63.7 7.3 56.5 7.3 47C7.3 27.4 26.09 12 50 12C73.91 12 92.7 27.4 92.7 47C92.7 56.5 90.1 63.7 87.7 69.7L87.33 69.58C85.49 68.42 83.35 67.61 81.1 67.47C83.5 64.37 84.7 60.5 84.7 55.5C84.6 49 82.4 43.6 78.5 38.6C72.4 30.5 63 27.8 50 27.8C37 27.8 27.6 30.5 21.5 38.6C17.6 43.6 15.4 49 15.3 55.5C15.3 60.5 16.5 64.37 18.9 67.47C16.65 67.61 14.51 68.42 12.67 69.58Z" fill="url(#panda-g1)"/><path d="M12.3 69.7C9.9 63.7 7.3 56.5 7.3 47C7.3 27.4 26.09 12 50 12C73.91 12 92.7 27.4 92.7 47C92.7 56.5 90.1 63.7 87.7 69.7" fill="none" stroke="#d2c7dc" stroke-width=".8"/><path d="M81.69 67.93C84.18 64.72 85.45 60.61 85.45 55.5C85.35 48.85 83.06 43.22 79.09 38.14C72.85 29.85 63.11 27.05 50 27.05C36.89 27.05 27.15 29.85 20.9 38.15C16.94 43.22 14.65 48.85 14.55 55.49C14.55 60.61 15.82 64.72 18.31 67.93" fill="none" stroke="#ece5ef" stroke-width="1.3"/><ellipse cx="33.6" cy="20.9" rx="6.5" ry="4.4" transform="rotate(-24 33.6 20.9)" fill="url(#panda-g3)"/><circle cx="35.2" cy="18.6" r="1.25" fill="#b3aebb" opacity=".55"/><circle cx="35.2" cy="18.6" r=".75" fill="#fff"/><ellipse cx="66.4" cy="20.9" rx="6.5" ry="4.4" transform="rotate(24 66.4 20.9)" fill="url(#panda-g3)"/><circle cx="64.8" cy="18.6" r="1.25" fill="#b3aebb" opacity=".55"/><circle cx="64.8" cy="18.6" r=".75" fill="#fff"/><path d="M46.6 17.4 Q50 16.8 53.4 17.4 Q54.8 17.7 53.8 18.9 L51.1 20.8 Q50 21.4 48.9 20.8 L46.2 18.9 Q45.2 17.7 46.6 17.4Z" fill="#3f3b4d"/><path d="M50 20.6 V21.8 M44.3 21 Q47.2 24.6 50 21.8 Q52.8 24.6 55.7 21" fill="none" stroke="#3f3b4d" stroke-width=".95" stroke-linecap="round" stroke-linejoin="round"/></g>'
  +   '<g class="acc pirate2"><defs><linearGradient id="pirate2-h" x2="0" y2="1"><stop offset="0" stop-color="#454352"/><stop offset=".55" stop-color="#2f2d3a"/><stop offset="1" stop-color="#23212c"/></linearGradient><linearGradient id="pirate2-g" x2="0" y2="1"><stop offset="0" stop-color="#ffd457"/><stop offset="1" stop-color="#f2a51e"/></linearGradient><linearGradient id="pirate2-b" x2="0" y2="1"><stop offset="0" stop-color="#f4504c"/><stop offset="1" stop-color="#d83434"/></linearGradient><radialGradient id="pirate2-r" cx=".4" cy=".35" r=".7"><stop offset="0" stop-color="#f45a55"/><stop offset="1" stop-color="#cf2f30"/></radialGradient><linearGradient id="pirate2-p" x2="0" y2="1"><stop offset="0" stop-color="#3c3a48"/><stop offset="1" stop-color="#24222d"/></linearGradient></defs><line x1="49.3" y1="0" x2="49.3" y2="-8.9" stroke="#ff6640" stroke-width="4" stroke-linecap="round"/><circle class="b-bulb" cx="49.3" cy="-11.9" r="5.8" fill="#ffd36e"/><circle cx="47.3" cy="-13.9" r="1.6" fill="#fff" opacity=".5"/><path d="M36.4 30.4Q45.9 39.3 56.4 42.1" fill="none" stroke="#2d2b37" stroke-width="1.5" stroke-linecap="round"/><path d="M71.4 45.5L77.9 45.3" stroke="#2d2b37" stroke-width="1.9"/><path d="M55 42.4C55.3 41.6 55.6 41 56.4 40.6S58.1 40.2 59.3 40.2S62.3 40.2 63.9 40.3S67.4 40.5 68.6 40.7S70.7 41.2 71.4 41.8S72.3 43.2 72.5 44.3S72.7 46.6 72.5 47.9S72 50.9 71.4 52.2S69.8 54.9 68.6 55.8S65.8 57.7 64.3 58S61.1 58 59.8 57.4S57.4 55.9 56.6 54.7S55.3 51.9 55 50.4S54.7 47.2 54.7 45.9S54.7 43.3 55 42.4Z" fill="url(#pirate2-p)"/><path d="M57.7 44.3C58.3 44 59.6 42.8 61 42.6S65.3 42.6 66.1 42.6" fill="none" stroke="#fff" stroke-opacity=".14" stroke-width="1.1" stroke-linecap="round"/><path d="M11.8 39.9C12.9 39.6 16.2 38.8 18.4 37.9S22.6 35.3 25.2 34.3S31.3 32.4 34.3 31.8S40.4 30.7 43.4 30.4S49.5 30 52.5 30.2S58.6 30.9 61.6 31.5S68 33.1 70.7 33.9S74.9 35.8 77.5 36.6S84.7 38.8 86.1 39.3L87.1 32.2C84.8 31.3 78.7 28.7 72.9 27S59.3 22.5 52.5 22S37.7 22.8 32.1 24S21.8 27.9 18.4 29.5S13.1 33.1 12.1 33.8Z" fill="url(#pirate2-b)"/><path d="M18.4 37.9C19.6 37.3 22.6 35.3 25.2 34.3S31.3 32.4 34.3 31.8S40.4 30.7 43.4 30.4S49.5 30 52.5 30.2S58.6 30.9 61.6 31.5S68 33.1 70.7 33.9S76.4 36.2 77.5 36.6" fill="none" stroke="#b92a2c" stroke-opacity=".45" stroke-width=".6"/><path d="M10.9 36.8C10.1 36 7.3 35.1 5.9 32.4S2.7 23.8 2.4 21.1S3.7 17.3 4.3 16.1S5.9 14.2 6.3 13.8C6.9 14.2 8.4 15.5 9.8 16.3S12.8 18.4 14.3 18.7S17.5 18.6 18.9 18.1S21.2 17.2 22.5 15.9S25.1 12 26.6 10.2S29.7 6.6 31.6 5.2S35.7 2.4 38 1.4S42.8-.2 45.2-.6S50.2-1.3 52.5-1.2S57.3-.9 59.3 0S63 2.4 64.5 3.8S67.5 6.7 68.9 8.2S71.4 11.8 72.7 13.4S75.4 16.5 77 17.3S80.6 18.6 82.3 18.6S85.5 17.8 87 17S90.8 14.2 91.6 13.6C91.9 14.1 93.1 15.2 93.6 16.5S94.7 20.2 94.9 21.5S95.2 23.2 94.9 24.7S93.8 28.8 92.7 30.6S89.6 34.3 88.2 35.4S84.8 36.7 84.1 37C82.6 36.1 78.6 33.2 75.2 31.4S67.6 27.5 63.9 26.3S56.3 24.7 52.5 24.4S44.9 24.1 41.1 24.6S33.4 25.9 29.8 27.2S22.7 30.7 19.6 32.3S12.4 36 10.9 36.8Z" fill="url(#pirate2-h)"/><path d="M2.5 26.1C2.5 25.3 2.1 22.8 2.4 21.1S3.7 17.3 4.3 16.1S5.9 14.2 6.3 13.8C6.9 14.2 8.4 15.5 9.8 16.3S12.8 18.4 14.3 18.7S17.5 18.6 18.9 18.1S21.2 17.2 22.5 15.9S25.1 12 26.6 10.2S29.7 6.6 31.6 5.2S35.7 2.4 38 1.4S42.8-.2 45.2-.6S50.2-1.3 52.5-1.2S57.3-.9 59.3 0S63 2.4 64.5 3.8S67.5 6.7 68.9 8.2S71.4 11.8 72.7 13.4S75.4 16.5 77 17.3S80.6 18.6 82.3 18.6S85.5 17.8 87 17S90.8 14.2 91.6 13.6C91.9 14.1 93.1 15.2 93.6 16.5S94.7 20.2 94.9 21.5S94.9 24.2 94.9 24.7L92.5 24.7C92.3 23.5 92.1 18.4 91.4 17.5S90.8 18.6 88.2 19.1S79.1 21.8 75.4 20.3S69.2 13.3 66.3 10.5S61.4 4.4 58 3.1S49.9 1.9 45.8 2.7S37.2 5.3 33.7 7.9S28.3 16 24.9 18.2S16.9 21.7 13.8 21.4S7.7 16.2 6.2 16.2S5 19.9 4.8 21.5S4.9 25.3 4.9 26Z" fill="url(#pirate2-g)"/><path d="M6.2 14.7C7.6 15.5 11.3 19.2 14.2 19.5S20.1 18.7 23.1 16.5S28.4 8.6 32.1 5.9S40.9 1.1 45.4 .2S55.2-.7 59 .7S65.3 5.9 68.2 8.8S73.4 16.6 76.6 18.1S85.6 17.8 87.4 17.7" fill="none" stroke="#fff3b8" stroke-opacity=".6" stroke-width=".7" stroke-linecap="round"/><g fill="#f6f3fb"><path d="M40.7 7.1 L55.7 17.8" stroke="#f6f3fb" stroke-width="2.3" stroke-linecap="round"/><circle cx="40" cy="8" r="1.45"/><circle cx="41.3" cy="6.2" r="1.45"/><circle cx="55" cy="18.7" r="1.45"/><circle cx="56.3" cy="16.9" r="1.45"/><path d="M55.7 7.1 L40.7 17.8" stroke="#f6f3fb" stroke-width="2.3" stroke-linecap="round"/><circle cx="55" cy="6.2" r="1.45"/><circle cx="56.3" cy="8" r="1.45"/><circle cx="40" cy="16.9" r="1.45"/><circle cx="41.3" cy="18.7" r="1.45"/></g><circle cx="48.2" cy="11" r="5" fill="#f6f3fb"/><rect x="44.9" y="13.6" width="6.6" height="3.8" rx="1.1" fill="#f6f3fb"/><circle cx="45.8" cy="12.1" r="1.5" fill="#2d2b37"/><circle cx="50.6" cy="12.1" r="1.5" fill="#2d2b37"/><path d="M48.2 13.7 l-.7 1.2 h1.4Z" fill="#2d2b37"/><path d="M47.1 15.9 v1.3 M49.2 15.9 v1.3" stroke="#c9c4d6" stroke-width=".5"/><path d="M11.1 39.3C11.6 39 14.3 39.9 14.3 40.4S11.9 45.7 11.6 46.5S10.3 52.1 9.8 52.4S4.7 51.6 4.6 51.1S7.3 45.7 7.7 44.9S10.7 39.6 11.1 39.3Z" fill="#d63636"/><path d="M13.4 39.9C13.9 39.4 17.7 39.7 18 40.4S17.3 48.7 17.2 49.9S16.6 59.2 16 59.7S9 58.6 8.6 57.9S10.8 50 11.1 48.8S13 40.5 13.4 39.9Z" fill="#e5413f"/><path d="M15.5 42C15.3 43.3 14.8 47.3 14.3 49.9S13 56.4 12.7 57.7" fill="none" stroke="#b82a2c" stroke-opacity=".5" stroke-width=".5"/><path d="M12.2 38.8 l3.2 0 l.9 3.4 l-5 0Z" fill="#c92e30"/><ellipse cx="14" cy="37.2" rx="3.4" ry="3.2" fill="url(#pirate2-r)"/><ellipse cx="13.2" cy="35.9" rx="1.2" ry=".8" fill="#ff8a86" opacity=".7"/></g>'
  +   '<g class="acc sailor"><defs><radialGradient id="sailor-cr" cx=".5" cy=".25" r=".75"><stop offset=".45" stop-color="#fefdfe"/><stop offset="1" stop-color="#e6deec"/></radialGradient><linearGradient id="sailor-b" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#5880e8"/><stop offset="1" stop-color="#3a5fd0"/></linearGradient><linearGradient id="sailor-w" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fdfdff"/><stop offset=".7" stop-color="#f6f4fa"/><stop offset="1" stop-color="#e2def0"/></linearGradient><radialGradient id="sailor-k" cx=".38" cy=".32" r=".75"><stop offset="0" stop-color="#6d94f0"/><stop offset="1" stop-color="#3559cc"/></radialGradient><radialGradient id="sailor-ball" cx=".38" cy=".32" r=".7"><stop offset="0" stop-color="#fff" stop-opacity=".35"/><stop offset=".5" stop-color="#fff" stop-opacity="0"/><stop offset="1" stop-color="#e8960f" stop-opacity=".3"/></radialGradient></defs><line x1="50" y1="2" x2="50" y2="-4" stroke="#ff6640" stroke-width="3.8" stroke-linecap="round"/><circle class="b-bulb" cx="50" cy="-9.4" r="5.3" fill="#ffd36e"/><circle cx="50" cy="-9.4" r="5.3" fill="url(#sailor-ball)"/><circle cx="48.1" cy="-11.4" r="1.4" fill="#fff" opacity=".5"/><path d="M25 29.6 Q50 22.6 75 29.6" fill="none" stroke="#c9503a" stroke-width="2" opacity=".18"/><path d="M18.2 12.4 C20.6 9.2 36 1.2 50 1.2 C64 1.2 79.4 9.2 81.8 12.4 L75.4 28.6 Q50 21 24.6 28.6Z" fill="url(#sailor-cr)" stroke="#d3cade" stroke-width=".7" stroke-linejoin="round"/><path d="M20.4 18.4 Q50 8.8 79.6 18.4 L78.1 22.8 Q50 12.2 21.9 22.8Z" fill="url(#sailor-b)"/><path d="M25.4 27.2 Q50 19.6 74.6 27.2" fill="none" stroke="url(#sailor-b)" stroke-width="3" stroke-linecap="round"/><g fill="none" stroke="#3e62d4" stroke-linecap="round"><circle cx="50" cy="3.8" r="1.25" stroke-width="1.1"/><path d="M50 5.2V11.6" stroke-width="1.3"/><path d="M47.7 6.4H52.3" stroke-width="1.1"/><path d="M45.7 8.8Q46.6 12 50 12Q53.4 12 54.3 8.8" stroke-width="1.5"/></g><circle cx="45.6" cy="8.6" r="1.05" fill="#3e62d4"/><circle cx="54.4" cy="8.6" r="1.05" fill="#3e62d4"/><path d="M12 65 C21 66.6 32 70.4 47 72.8 L50 73.1 L50 81.9 C31 80.4 16 75.6 12.2 71.6 C11 70.4 11 66.4 12 65Z" fill="url(#sailor-w)"/><path d="M12.5 67.1 Q29 75.2 47.5 77.6" fill="none" stroke="url(#sailor-b)" stroke-width="2"/><path d="M12 65 C21 66.6 31 70.2 41.6 72 Q45.4 72.8 47 76.6" fill="none" stroke="#7f93e0" stroke-width=".7"/><path d="M12.2 71.6 C16 75.6 31 80.4 47 81.7" fill="none" stroke="#5a58c8" stroke-width=".8" opacity=".6"/><path d="M 88 65 C 79 66.6 68 70.4 53 72.8 L 50 73.1 L 50 81.9 C 69 80.4 84 75.6 87.8 71.6 C 89 70.4 89 66.4 88 65Z" fill="url(#sailor-w)"/><path d="M 87.5 67.1 Q 71 75.2 52.5 77.6" fill="none" stroke="url(#sailor-b)" stroke-width="2"/><path d="M 88 65 C 79 66.6 69 70.2 58.4 72 Q 54.6 72.8 53 76.6" fill="none" stroke="#7f93e0" stroke-width=".7"/><path d="M 87.8 71.6 C 84 75.6 69 80.4 53 81.7" fill="none" stroke="#5a58c8" stroke-width=".8" opacity=".6"/><path d="M47.6 82.4 C44.8 83 41.8 85.2 40.6 87.8 C39.8 89.8 40.6 91.8 42.6 92.1 C44.2 92.4 45.6 91 46.8 89.2 C48.4 87 50 85.2 50 82.8Z" fill="url(#sailor-k)" stroke="#2f52c0" stroke-width=".7" stroke-linejoin="round"/><path d="M48.4 84 Q45.2 87.4 43.2 91" fill="none" stroke="#3456c4" stroke-width=".6" stroke-linecap="round" opacity=".7"/><path d="M 52.4 82.4 C 55.2 83 58.2 85.2 59.4 87.8 C 60.2 89.8 59.4 91.8 57.4 92.1 C 55.8 92.4 54.4 91 53.2 89.2 C 51.6 87 50 85.2 50 82.8Z" fill="url(#sailor-k)" stroke="#2f52c0" stroke-width=".7" stroke-linejoin="round"/><path d="M 51.6 84 Q 54.8 87.4 56.8 91" fill="none" stroke="#3456c4" stroke-width=".6" stroke-linecap="round" opacity=".7"/><ellipse cx="50" cy="79.6" rx="3.6" ry="3.4" fill="url(#sailor-k)" stroke="#2f52c0" stroke-width=".7"/><ellipse cx="49" cy="78.6" rx="1.5" ry="1" fill="#fff" opacity=".25"/></g>'
  +   '<g class="acc santa2"><defs><linearGradient id="santa2-red" gradientUnits="userSpaceOnUse" x1="36" y1="0" x2="92" y2="30"><stop offset="0" stop-color="#fb5256"/><stop offset=".55" stop-color="#ee4248"/><stop offset="1" stop-color="#d02a35"/></linearGradient><radialGradient id="santa2-pom" gradientUnits="userSpaceOnUse" cx="95.4" cy="30.6" r="13"><stop offset="0" stop-color="#ffffff"/><stop offset=".5" stop-color="#fcf6f9"/><stop offset="1" stop-color="#e4c9d5"/></radialGradient><radialGradient id="santa2-ball" gradientUnits="userSpaceOnUse" cx="49" cy="-13" r="9"><stop offset="0" stop-color="#fff" stop-opacity=".55"/><stop offset=".45" stop-color="#fff" stop-opacity="0"/><stop offset="1" stop-color="#e8870f" stop-opacity=".35"/></radialGradient></defs><path d="M51 4 V-4" stroke="#f0503c" stroke-width="4" stroke-linecap="round"/><circle class="b-bulb" cx="51.2" cy="-9.8" r="6.3" fill="#ffd36e"/><circle cx="51.2" cy="-9.8" r="6.3" fill="url(#santa2-ball)"/><path d="M20 22 C22 17 24.5 14.5 27 12.6 C34 7.6 42 3.4 50 0.4 C56 -1.6 63 -2.6 69 -2.4 C77 -2 83 1.4 87.4 6.4 C91.6 11.2 95.6 18 98.2 25.4 L97 35 L86.4 33.2 C76.4 21.6 62 19.2 48 19.4 C34 19.8 23 23.2 18 30.4Z" fill="url(#santa2-red)"/><path d="M30 10.6 C38 5.4 46 1.8 55 -0.6" fill="none" stroke="#fff" stroke-opacity=".2" stroke-width="2.6" stroke-linecap="round"/><path d="M65 8.6 C72 9.6 78 13 81 18.6 C82.6 21.6 83.6 25 84 28 C81.4 23 77.6 17.6 72.6 13.8 C70.2 12 67.8 10.2 65 8.6Z" fill="#a8162a" fill-opacity=".22"/><path d="M68 10 C74 12 78.6 16 81.4 22.6" fill="none" stroke="#a8162a" stroke-opacity=".32" stroke-width="1" stroke-linecap="round"/><g fill="#e2c5d2"><circle cx="22.67" cy="31.56" r="2.83"/><circle cx="28.02" cy="27.29" r="3.68"/><circle cx="35.3" cy="24.94" r="3.12"/><circle cx="43.65" cy="24" r="3.97"/><circle cx="52.28" cy="23.93" r="3.4"/><circle cx="60.52" cy="24.64" r="2.83"/><circle cx="68.44" cy="26.52" r="3.68"/><circle cx="75.61" cy="29.84" r="3.12"/><circle cx="81.78" cy="34.81" r="3.97"/></g><g fill="none" stroke-linecap="round"><path d="M18 30.4 C23 22.4 33 19.4 48 19.4 C62 19.2 76.4 21.6 86.4 33.2" transform="translate(0 0.6)" stroke="#e2c5d2" stroke-width="12.4"/><path d="M18 30.4 C23 22.4 33 19.4 48 19.4 C62 19.2 76.4 21.6 86.4 33.2" transform="translate(0 0.1)" stroke="#e8d0da" stroke-width="12"/><path d="M18 30.4 C23 22.4 33 19.4 48 19.4 C62 19.2 76.4 21.6 86.4 33.2" transform="translate(0 -0.5)" stroke="#eedae2" stroke-width="11.4"/><path d="M18 30.4 C23 22.4 33 19.4 48 19.4 C62 19.2 76.4 21.6 86.4 33.2" transform="translate(0 -1)" stroke="#f3e5eb" stroke-width="10.8"/><path d="M18 30.4 C23 22.4 33 19.4 48 19.4 C62 19.2 76.4 21.6 86.4 33.2" transform="translate(0 -1.5)" stroke="#f8eff3" stroke-width="10"/><path d="M18 30.4 C23 22.4 33 19.4 48 19.4 C62 19.2 76.4 21.6 86.4 33.2" transform="translate(0 -2)" stroke="#fbf6f8" stroke-width="9.2"/><path d="M18 30.4 C23 22.4 33 19.4 48 19.4 C62 19.2 76.4 21.6 86.4 33.2" transform="translate(0 -2.5)" stroke="#fefcfe" stroke-width="8.2"/></g><g fill="#f6eef2"><circle cx="16.13" cy="27.81" r="3.5"/><circle cx="25.56" cy="20.31" r="4.55"/><circle cx="36.49" cy="17.26" r="3.85"/><circle cx="47.33" cy="16.5" r="4.9"/><circle cx="58.04" cy="16.85" r="4.2"/><circle cx="68.94" cy="18.82" r="3.5"/><circle cx="79.32" cy="23.17" r="4.55"/><circle cx="88.21" cy="30.25" r="3.85"/></g><g fill="#fefcfe"><circle cx="15.88" cy="27.11" r="3.5"/><circle cx="25.31" cy="19.61" r="4.55"/><circle cx="36.24" cy="16.56" r="3.85"/><circle cx="47.08" cy="15.8" r="4.9"/><circle cx="57.79" cy="16.15" r="4.2"/><circle cx="68.69" cy="18.12" r="3.5"/><circle cx="79.07" cy="22.47" r="4.55"/><circle cx="87.96" cy="29.55" r="3.85"/></g><g fill="url(#santa2-pom)"><circle cx="97.6" cy="34.4" r="7.4"/><circle cx="103.33" cy="36.23" r="2.8"/><circle cx="101.46" cy="39.14" r="2.8"/><circle cx="98.37" cy="40.55" r="2.8"/><circle cx="95.03" cy="40" r="2.8"/><circle cx="92.51" cy="37.68" r="2.8"/><circle cx="91.6" cy="34.31" r="2.8"/><circle cx="92.6" cy="30.97" r="2.8"/><circle cx="95.19" cy="28.72" r="2.8"/><circle cx="98.54" cy="28.28" r="2.8"/><circle cx="101.59" cy="29.77" r="2.8"/><circle cx="103.38" cy="32.74" r="2.8"/></g></g>'
  +   '<g class="acc shark"><defs><linearGradient id="shark-g" gradientUnits="userSpaceOnUse" x1="0" y1="17" x2="0" y2="69"><stop offset="0" stop-color="#75aef1"/><stop offset=".4" stop-color="#69a5ee"/><stop offset=".72" stop-color="#5a96e6"/><stop offset="1" stop-color="#4a82d8"/></linearGradient><radialGradient id="shark-e" gradientUnits="userSpaceOnUse" cx="50" cy="46" r="40"><stop offset=".76" stop-color="#2a5cb3" stop-opacity="0"/><stop offset="1" stop-color="#2a5cb3" stop-opacity=".28"/></radialGradient><linearGradient id="shark-f" gradientUnits="userSpaceOnUse" x1="60" y1="14" x2="80" y2="28"><stop offset="0" stop-color="#72abf0"/><stop offset=".6" stop-color="#5c95e7"/><stop offset="1" stop-color="#4479d0"/></linearGradient><linearGradient id="shark-m" gradientUnits="userSpaceOnUse" x1="0" y1="34" x2="0" y2="68"><stop offset="0" stop-color="#dc475c"/><stop offset=".5" stop-color="#e3505f"/><stop offset=".78" stop-color="#ef7462"/><stop offset="1" stop-color="#f98f66"/></linearGradient><linearGradient id="shark-r" gradientUnits="userSpaceOnUse" x1="0" y1="30" x2="0" y2="68"><stop offset="0" stop-color="#fffeff"/><stop offset=".7" stop-color="#f7f4fb"/><stop offset="1" stop-color="#ebe6f4"/></linearGradient></defs><path d="M56.6 18.4 C61.6 15.2 68.8 12.4 78.6 11.4 Q82.6 11.1 81.4 14.2 C79 19.4 77.8 25 78 31.2Z" fill="url(#shark-f)" stroke="#3f74c8" stroke-opacity=".4" stroke-width=".6" stroke-linejoin="round"/><path d="M18.2 67.3 L18.2 58 C18.2 45.6 32.6 34 50 34 C67.4 34 81.8 45.6 81.8 58 L81.8 67.3 L78.2 67.9 L78 64 C77.4 61.6 76.4 59.2 75.6 57.2 C75.4 56 75.4 54.6 75.4 53 C75.4 46.5 66 41 50 40.9 C34 41 24.6 46.5 24.6 53 C24.6 54.6 24.6 56 24.4 57.2 C23.6 59.2 22.6 61.6 22 64 L21.8 67.9Z" fill="url(#shark-m)"/><path d="M19.4 67.2 L19.4 58 C19.4 46.6 33.6 35.2 50 35.2 C66.4 35.2 80.6 46.6 80.6 58 L80.6 67.2" fill="none" stroke="#a52a45" stroke-opacity=".2" stroke-width="2.4"/><path d="M22.17 47.54 L29.3 47.42 Q30.4 47.4 30.55 46.31 L31.55 39.25Z M32.16 38.9 L37.26 42.03 Q38.2 42.6 38.68 41.61 L41.8 35.23Z M44.15 34.77 L49.28 40.67 Q50 41.5 50.72 40.67 L55.85 34.77Z M58.2 35.23 L61.32 41.61 Q61.8 42.6 62.74 42.03 L67.84 38.9Z M68.45 39.25 L69.45 46.31 Q69.6 47.4 70.7 47.42 L77.83 47.54Z" fill="#9c3550" fill-opacity=".16" transform="translate(0 .6)"/><path d="M22.17 47.54 L29.3 47.42 Q30.4 47.4 30.55 46.31 L31.55 39.25Z M32.16 38.9 L37.26 42.03 Q38.2 42.6 38.68 41.61 L41.8 35.23Z M44.15 34.77 L49.28 40.67 Q50 41.5 50.72 40.67 L55.85 34.77Z M58.2 35.23 L61.32 41.61 Q61.8 42.6 62.74 42.03 L67.84 38.9Z M68.45 39.25 L69.45 46.31 Q69.6 47.4 70.7 47.42 L77.83 47.54Z" fill="#fff" stroke="#d2c9df" stroke-width=".45" stroke-linejoin="round"/><path d="M15.4 67.6 L15.4 58 C15.4 44 31 30.4 50 30.4 C69 30.4 84.6 44 84.6 58 L84.6 67.6 L80.6 67.2 L80.6 58 C80.6 46.6 66.4 35.2 50 35.2 C33.6 35.2 19.4 46.6 19.4 58 L19.4 67.2Z" fill="url(#shark-r)"/><path d="M19.4 67.2 L19.4 58 C19.4 46.6 33.6 35.2 50 35.2 C66.4 35.2 80.6 46.6 80.6 58 L80.6 67.2" fill="none" stroke="#cbbfdd" stroke-opacity=".85" stroke-width=".8"/><path d="M15.4 67.8 L15.4 58 C15.4 44 31 30.4 50 30.4 C69 30.4 84.6 44 84.6 58 L84.6 67.8 C87.2 67.4 88.5 65 88.5 60.5 L88.5 50 C88.5 40 85.5 17 50 17 C14.5 17 11.5 40 11.5 50 L11.5 60.5 C11.5 65 12.8 67.4 15.4 67.8Z" fill="url(#shark-g)" stroke="#3f74c8" stroke-opacity=".5" stroke-width=".6" stroke-linejoin="round"/><path d="M15.4 67.8 L15.4 58 C15.4 44 31 30.4 50 30.4 C69 30.4 84.6 44 84.6 58 L84.6 67.8 C87.2 67.4 88.5 65 88.5 60.5 L88.5 50 C88.5 40 85.5 17 50 17 C14.5 17 11.5 40 11.5 50 L11.5 60.5 C11.5 65 12.8 67.4 15.4 67.8Z" fill="url(#shark-e)"/><g fill="none" stroke="#3a6bbf" stroke-width="1.45" stroke-linecap="round"><path d="M16 39.2 Q17.8 40.8 18.8 43.6 M14.2 43.4 Q15.9 45 16.8 47.4 M12.7 47.3 Q14.1 48.7 14.9 50.5"/><path d="M16 39.2 Q17.8 40.8 18.8 43.6 M14.2 43.4 Q15.9 45 16.8 47.4 M12.7 47.3 Q14.1 48.7 14.9 50.5" transform="matrix(-1 0 0 1 100 0)"/></g><circle cx="36.2" cy="27.9" r="2.95" fill="#2b2b3a"/><circle cx="35.3" cy="26.9" r="1" fill="#fff"/><circle cx="63.8" cy="27.9" r="2.95" fill="#2b2b3a"/><circle cx="62.9" cy="26.9" r="1" fill="#fff"/></g>'
  +   '<g class="acc snowman"><defs><radialGradient id="snowman-up" cx=".42" cy=".3" r=".78"><stop offset="0" stop-color="#fff"/><stop offset=".55" stop-color="#fcf8fb"/><stop offset=".85" stop-color="#f2e7f0"/><stop offset="1" stop-color="#e4d4e1"/></radialGradient><radialGradient id="snowman-lo" cx=".46" cy=".18" r=".95"><stop offset="0" stop-color="#fffdfe"/><stop offset=".45" stop-color="#fbf5fa"/><stop offset=".78" stop-color="#eee1eb"/><stop offset="1" stop-color="#ddc8d6"/></radialGradient><linearGradient id="snowman-in" gradientUnits="userSpaceOnUse" x1="0" y1="30" x2="0" y2="52"><stop offset="0" stop-color="#d2aeaa" stop-opacity=".6"/><stop offset="1" stop-color="#c99f9c" stop-opacity="0"/></linearGradient><linearGradient id="snowman-rim" gradientUnits="userSpaceOnUse" x1="0" y1="29.8" x2="0" y2="74"><stop offset="0" stop-color="#d7a59a"/><stop offset=".3" stop-color="#e2623e"/><stop offset=".78" stop-color="#e2623e"/><stop offset=".93" stop-color="#e4d2d8"/></linearGradient><linearGradient id="snowman-crown" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#4a4556"/><stop offset=".3" stop-color="#5a5465"/><stop offset=".7" stop-color="#3e3a4a"/><stop offset="1" stop-color="#2c2936"/></linearGradient><linearGradient id="snowman-band" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#f4505a"/><stop offset=".6" stop-color="#e2343f"/><stop offset="1" stop-color="#b9232f"/></linearGradient><linearGradient id="snowman-leaf" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#5cc062"/><stop offset="1" stop-color="#2d8c3c"/></linearGradient><linearGradient id="snowman-brim" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#46414f"/><stop offset=".55" stop-color="#2e2b37"/><stop offset="1" stop-color="#24212c"/></linearGradient><radialGradient id="snowman-berry" cx=".35" cy=".3" r=".75"><stop offset="0" stop-color="#ff6a6a"/><stop offset=".6" stop-color="#e3242f"/><stop offset="1" stop-color="#b3151f"/></radialGradient><radialGradient id="snowman-coal" cx=".35" cy=".3" r=".8"><stop offset="0" stop-color="#615b6c"/><stop offset=".6" stop-color="#3a3644"/><stop offset="1" stop-color="#26232e"/></radialGradient></defs><path d="M13.6 65.5 C10.4 71 8.2 80 8.2 90 C8.6 102 29 110 50 110 C71 110 91.4 102 91.8 90 C91.8 80 89.6 71 86.4 65.5 L76 71.6 Q50 81 24 71.6Z" fill="url(#snowman-lo)" stroke="#d6cbdb" stroke-width=".7"/><path d="M50 29.8 C67.5 29.8 78.3 35 78.4 47.5 C78.5 56 77.2 62.5 75.6 69.4 Q50 78.8 24.4 69.4 C22.8 62.5 21.5 56 21.6 47.5 C21.7 35 32.5 29.8 50 29.8Z" fill="none" stroke="url(#snowman-in)" stroke-width="4.4"/><path fill-rule="evenodd" d="M42 17 H58 A33 33 0 0 1 91 50 L91 52.5 C91 58 88.5 66 84 71 C80 75.5 65 79 50 79 C35 79 20 75.5 16 71 C11.5 66 9 58 9 52.5 L9 50 A33 33 0 0 1 42 17Z M50 29.8 C67.5 29.8 78.3 35 78.4 47.5 C78.5 56 77.2 62.5 75.6 69.4 Q50 78.8 24.4 69.4 C22.8 62.5 21.5 56 21.6 47.5 C21.7 35 32.5 29.8 50 29.8Z" fill="url(#snowman-up)" stroke="#d6cbdb" stroke-width=".7"/><path d="M50 29.8 C67.5 29.8 78.3 35 78.4 47.5 C78.5 56 77.2 62.5 75.6 69.4 Q50 78.8 24.4 69.4 C22.8 62.5 21.5 56 21.6 47.5 C21.7 35 32.5 29.8 50 29.8Z" fill="none" stroke="#ece3ee" stroke-width="1.8"/><path d="M50 29.8 C67.5 29.8 78.3 35 78.4 47.5 C78.5 56 77.2 62.5 75.6 69.4 Q50 78.8 24.4 69.4 C22.8 62.5 21.5 56 21.6 47.5 C21.7 35 32.5 29.8 50 29.8Z" fill="none" stroke="url(#snowman-rim)" stroke-width=".6"/><circle cx="50" cy="85.7" r="3.9" fill="url(#snowman-coal)"/><circle cx="48.8" cy="84.4" r=".9" fill="#fff" opacity=".35"/><circle cx="50" cy="98.2" r="3.8" fill="url(#snowman-coal)"/><circle cx="48.8" cy="96.9" r=".9" fill="#fff" opacity=".35"/><path d="M37.2 16 L36.7 -1.5 Q36.8 -4.4 40 -5.6 Q55 -8.8 70.4 -5 Q75.6 -3.6 75.7 -0.5 L75.2 16Z" fill="url(#snowman-crown)"/><path d="M40.5 -4.9 Q55 -7.9 70 -4.4" fill="none" stroke="#7a7486" stroke-width=".8" stroke-linecap="round" opacity=".5"/><path d="M36.85 7.6 Q56 7 75.45 8.4 L75.3 17 L37 17Z" fill="url(#snowman-band)"/><path d="M37.2 8.2 Q56 7.6 75.2 9" fill="none" stroke="#ff8a90" stroke-width=".6" opacity=".55"/><path d="M24.6 18.4 C28 14.6 34 13 41 13 C56 13.2 70 15.4 79.6 20 C83 21.6 84.2 25.2 82.2 26.8 C80.8 27.6 78.4 25.8 74 24 C66 21 54 19.3 42 19 C34 18.8 28.4 21.2 25.6 21.6 C23.6 21.8 23.4 19.8 24.6 18.4Z" fill="url(#snowman-brim)"/><path d="M26.4 17.4 C30 14.6 36 13.7 42 13.8 C52 14 64 15.4 72 17.6 C76 18.8 79.4 20.6 81.4 22.6" fill="none" stroke="#6a6475" stroke-width=".7" stroke-linecap="round" opacity=".6"/><path d="M27 21.2 C33 19.8 42 19.6 52 20.2 C62 20.8 72 22.8 79.8 26.4" fill="none" stroke="#7a7280" stroke-width=".7" stroke-linecap="round" opacity=".45"/><g transform="translate(67.8 11.6) rotate(-123) scale(1.12 1.36)"><path d="M0 0 Q0.8 -2.2 2.5 -3.4 Q3 -2.4 4.3 -2.5 Q5 -3.6 6.2 -4 Q6.6 -2.8 8 -2.7 Q8.8 -3.4 9.8 -3.2 Q9.9 -2 10.9 -1.6 L12.4 0 L10.9 1.6 Q9.9 2 9.8 3.2 Q8.8 3.4 8 2.7 Q6.6 2.8 6.2 4 Q5 3.6 4.3 2.5 Q3 2.4 2.5 3.4 Q0.8 2.2 0 0Z" fill="url(#snowman-leaf)"/><path d="M.6 0H11" stroke="#2a7a35" stroke-width=".45"/></g><g transform="translate(70.2 13.6) rotate(-13) scale(0.98 1.42)"><path d="M0 0 Q0.8 -2.2 2.5 -3.4 Q3 -2.4 4.3 -2.5 Q5 -3.6 6.2 -4 Q6.6 -2.8 8 -2.7 Q8.8 -3.4 9.8 -3.2 Q9.9 -2 10.9 -1.6 L12.4 0 L10.9 1.6 Q9.9 2 9.8 3.2 Q8.8 3.4 8 2.7 Q6.6 2.8 6.2 4 Q5 3.6 4.3 2.5 Q3 2.4 2.5 3.4 Q0.8 2.2 0 0Z" fill="url(#snowman-leaf)"/><path d="M.6 0H11" stroke="#2a7a35" stroke-width=".45"/></g><circle cx="64.6" cy="13.4" r="2.3" fill="url(#snowman-berry)"/><circle cx="70.1" cy="14.8" r="2.3" fill="url(#snowman-berry)"/><circle cx="68" cy="11" r="2.3" fill="url(#snowman-berry)"/></g>'
  +   '<g class="acc stocking"><linearGradient id="stocking-r" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#e03a42"/><stop offset=".6" stop-color="#d22c36"/><stop offset="1" stop-color="#bd222d"/></linearGradient><linearGradient id="stocking-w" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fffdfd"/><stop offset=".6" stop-color="#fdf6f8"/><stop offset="1" stop-color="#e9d6df"/></linearGradient><linearGradient id="stocking-e" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#d9c5cf" stop-opacity=".85"/><stop offset=".13" stop-color="#fff" stop-opacity="0"/><stop offset=".87" stop-color="#fff" stop-opacity="0"/><stop offset="1" stop-color="#d9c5cf" stop-opacity=".85"/></linearGradient><linearGradient id="stocking-i" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#c9abba"/><stop offset="1" stop-color="#efe3e8"/></linearGradient><linearGradient id="stocking-l" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#58a74d"/><stop offset="1" stop-color="#2c7430"/></linearGradient><radialGradient id="stocking-b" cx=".38" cy=".34" r=".7"><stop offset="0" stop-color="#ef4a4f"/><stop offset="1" stop-color="#b8161f"/></radialGradient><radialGradient id="stocking-s"><stop offset="0" stop-color="#fff" stop-opacity=".2"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient><radialGradient id="stocking-h" cx=".38" cy=".32" r=".7"><stop offset="0" stop-color="#ffd2bd" stop-opacity=".75"/><stop offset=".6" stop-color="#ffc0a4" stop-opacity=".25"/><stop offset="1" stop-color="#ffb08f" stop-opacity="0"/></radialGradient><path d="M35 79L35 86.5C34.6 88.6 32.6 89.6 30.6 91.2C28.4 93 27.5 95 27.6 96.8C27.6 99.6 28.6 101.6 30.6 102.8C32.6 103.9 35.4 104.2 39 104.2C45.6 104.2 53.2 101.9 59.4 100.4C64.2 98.6 66.2 96.4 66.2 92.2L66.2 79Z" fill="url(#stocking-r)"/><path d="M64.8 84C64.9 89.6 65 94.6 62.8 97.2C59.6 100.2 51 102.4 43.4 103" fill="none" stroke="#a81b26" stroke-width="1.6" stroke-linecap="round" opacity=".35"/><path d="M31.4 92.7Q34.6 90.9 38.2 91.4" fill="none" stroke="#a81b26" stroke-width=".8" stroke-linecap="round" opacity=".4"/><ellipse cx="38.8" cy="89" rx="2.6" ry="6.4" fill="url(#stocking-s)"/><ellipse cx="31.8" cy="96.4" rx="3.6" ry="1.8" transform="rotate(-28 31.8 96.4)" fill="#fff" opacity=".22"/><ellipse cx="30.9" cy="96.6" rx="1.6" ry=".75" transform="rotate(-35 30.9 96.6)" fill="#fff" opacity=".45"/><path d="M35 80.4Q50 79.6 66 80.4V83Q50 82.2 35 83Z" fill="#9e1520" opacity=".22"/><g transform="translate(50.45 91.6)" fill="none" stroke="#9e1520" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" opacity=".3"><path transform="rotate(0)" d="M0 -.4V-6.8M-2.1 -5.9L0 -4.4 2.1 -5.9"/><path transform="rotate(60)" d="M0 -.4V-6.8M-2.1 -5.9L0 -4.4 2.1 -5.9"/><path transform="rotate(120)" d="M0 -.4V-6.8M-2.1 -5.9L0 -4.4 2.1 -5.9"/><path transform="rotate(180)" d="M0 -.4V-6.8M-2.1 -5.9L0 -4.4 2.1 -5.9"/><path transform="rotate(240)" d="M0 -.4V-6.8M-2.1 -5.9L0 -4.4 2.1 -5.9"/><path transform="rotate(300)" d="M0 -.4V-6.8M-2.1 -5.9L0 -4.4 2.1 -5.9"/></g><g transform="translate(50.2 91.2)" fill="none" stroke="#fdf6f2" stroke-width="1.55" stroke-linecap="round" stroke-linejoin="round"><path transform="rotate(0)" d="M0 -.4V-6.8M-2.1 -5.9L0 -4.4 2.1 -5.9"/><path transform="rotate(60)" d="M0 -.4V-6.8M-2.1 -5.9L0 -4.4 2.1 -5.9"/><path transform="rotate(120)" d="M0 -.4V-6.8M-2.1 -5.9L0 -4.4 2.1 -5.9"/><path transform="rotate(180)" d="M0 -.4V-6.8M-2.1 -5.9L0 -4.4 2.1 -5.9"/><path transform="rotate(240)" d="M0 -.4V-6.8M-2.1 -5.9L0 -4.4 2.1 -5.9"/><path transform="rotate(300)" d="M0 -.4V-6.8M-2.1 -5.9L0 -4.4 2.1 -5.9"/></g><circle cx="65.4" cy="65.5" r="2" fill="none" stroke="#e3d4dc" stroke-width="2.1"/><circle cx="65.4" cy="65.5" r="2" fill="none" stroke="#fdf8fa" stroke-width="1.3"/><path d="M34.8 67.3C40 66.3 60 66.3 65.6 67.3C68.4 67.8 69.8 69.9 69.9 73.3C70 76.6 69.6 79.6 67.4 80.75Q65.5 81.7 63.6 80.8Q61.6 81.7 59.7 80.8Q57.8 81.7 55.9 80.8Q53.9 81.7 52.0 80.8Q50.1 81.7 48.2 80.8Q46.3 81.7 44.3 80.8Q42.4 81.7 40.5 80.8Q38.6 81.7 36.6 80.8Q34.7 81.7 32.8 80.8C30.8 79.6 30.9 76.6 31 73.3C31.1 69.9 32.2 67.8 34.8 67.3Z" fill="url(#stocking-w)"/><path d="M34.8 67.3C40 66.3 60 66.3 65.6 67.3C68.4 67.8 69.8 69.9 69.9 73.3C70 76.6 69.6 79.6 67.4 80.75Q65.5 81.7 63.6 80.8Q61.6 81.7 59.7 80.8Q57.8 81.7 55.9 80.8Q53.9 81.7 52.0 80.8Q50.1 81.7 48.2 80.8Q46.3 81.7 44.3 80.8Q42.4 81.7 40.5 80.8Q38.6 81.7 36.6 80.8Q34.7 81.7 32.8 80.8C30.8 79.6 30.9 76.6 31 73.3C31.1 69.9 32.2 67.8 34.8 67.3Z" fill="url(#stocking-e)"/><path d="M33.4 68.2C40 67.1 60 67.1 66.8 68.2C60 70.5 40 70.5 33.4 68.2Z" fill="url(#stocking-i)"/><path d="M33.4 70.4C40 71.4 60 71.4 66.8 70.4" fill="none" stroke="#fff" stroke-width=".9" stroke-linecap="round" opacity=".9"/><g transform="translate(57.2 75.9) rotate(230) scale(1.18 1.38)"><path d="M0 0Q.6 -1.6 1.6 -2Q2.7 -1.3 3.8 -2.4Q4.9 -1.5 6 -1.9Q6.9 -.8 7.8 0Q6.9 .8 6 1.9Q4.9 1.5 3.8 2.4Q2.7 1.3 1.6 2Q.6 1.6 0 0Z" fill="url(#stocking-l)"/><path d="M1.5 -.7Q3.9 -1.9 6.6 -.8Q4.1 -1.0 1.5 -.7Z" fill="#c9f0b8" opacity=".45"/><path d="M.5 0L7 0" stroke="#2a6a2c" stroke-width=".42" stroke-linecap="round"/><path d="M.9 -.28L6.4 -.22" stroke="#8fd07f" stroke-width=".22" stroke-linecap="round" opacity=".8"/></g><g transform="translate(61 75.6) rotate(-47) scale(1.2 1.38)"><path d="M0 0Q.6 -1.6 1.6 -2Q2.7 -1.3 3.8 -2.4Q4.9 -1.5 6 -1.9Q6.9 -.8 7.8 0Q6.9 .8 6 1.9Q4.9 1.5 3.8 2.4Q2.7 1.3 1.6 2Q.6 1.6 0 0Z" fill="url(#stocking-l)"/><path d="M1.5 -.7Q3.9 -1.9 6.6 -.8Q4.1 -1.0 1.5 -.7Z" fill="#c9f0b8" opacity=".45"/><path d="M.5 0L7 0" stroke="#2a6a2c" stroke-width=".42" stroke-linecap="round"/><path d="M.9 -.28L6.4 -.22" stroke="#8fd07f" stroke-width=".22" stroke-linecap="round" opacity=".8"/></g><g fill="url(#stocking-b)"><circle cx="59.3" cy="75.3" r="1.85"/><circle cx="57.7" cy="77.25" r="1.85"/><circle cx="61.05" cy="77.35" r="1.85"/></g><g fill="#fff" opacity=".55"><circle cx="58.7" cy="74.6" r=".5"/><circle cx="57.1" cy="76.6" r=".5"/><circle cx="60.4" cy="76.7" r=".5"/></g><circle cx="26.8" cy="86" r="8.6" fill="url(#armL)"/><circle cx="73.2" cy="86" r="8.6" fill="url(#armR)"/><circle cx="26.8" cy="86" r="8.6" fill="url(#stocking-h)"/><circle cx="73.2" cy="86" r="8.6" fill="url(#stocking-h)"/><ellipse cx="22.6" cy="81.6" rx="2.6" ry="1.4" transform="rotate(-38 22.6 81.6)" fill="#fff" opacity=".4"/><ellipse cx="69" cy="81.6" rx="2.6" ry="1.4" transform="rotate(-38 69 81.6)" fill="#fff" opacity=".4"/></g>'
  +   '<g class="acc topstar"><path d="M28.25 -2.95L31.75 0.25" stroke="#f9b31c" stroke-width="2.3" stroke-linecap="round"/><path d="M28.25 -3.2L31.75 0" stroke="#ffd660" stroke-width="1" stroke-linecap="round"/><path d="M71.75 -2.95L68.25 0.25" stroke="#f9b31c" stroke-width="2.3" stroke-linecap="round"/><path d="M71.75 -3.2L68.25 0" stroke="#ffd660" stroke-width="1" stroke-linecap="round"/><path d="M23.55 6.2L30.15 6.2" stroke="#f9b31c" stroke-width="2.3" stroke-linecap="round"/><path d="M23.55 5.95L30.15 5.95" stroke="#ffd660" stroke-width="1" stroke-linecap="round"/><path d="M76.45 6.2L69.85 6.2" stroke="#f9b31c" stroke-width="2.3" stroke-linecap="round"/><path d="M76.45 5.95L69.85 5.95" stroke="#ffd660" stroke-width="1" stroke-linecap="round"/><path d="M28.25 14.55L31.75 11.85" stroke="#f9b31c" stroke-width="2.3" stroke-linecap="round"/><path d="M28.25 14.3L31.75 11.6" stroke="#ffd660" stroke-width="1" stroke-linecap="round"/><path d="M71.75 14.55L68.25 11.85" stroke="#f9b31c" stroke-width="2.3" stroke-linecap="round"/><path d="M71.75 14.3L68.25 11.6" stroke="#ffd660" stroke-width="1" stroke-linecap="round"/><path d="M48.88 -6.28Q50 -8.1 51.12 -6.28L53.95 -1.68Q54.48 -0.81 55.52 -0.59L60.96 0.56Q63.12 1.02 61.65 2.6L57.96 6.6Q57.26 7.36 57.36 8.37L57.89 13.67Q58.11 15.78 56.08 14.93L50.97 12.81Q50 12.4 49.03 12.81L43.92 14.93Q41.89 15.78 42.11 13.67L42.64 8.37Q42.74 7.36 42.04 6.6L38.35 2.6Q36.88 1.02 39.04 0.56L44.48 -0.59Q45.52 -0.81 46.05 -1.68L48.88 -6.28Z" fill="#ffd04a"/><path d="M50 5.1L45.52 -0.81L48.88 -6.28Q49.44 -7.19 50 -7.19Z" fill="#ffe69b"/><path d="M50 5.1L54.48 -0.81L51.12 -6.28Q50.56 -7.19 50 -7.19Z" fill="#fecd46"/><path d="M50 5.1L54.48 -0.81L60.96 0.56Q62.04 0.79 62.21 1.3Z" fill="#ffdf81"/><path d="M50 5.1L57.26 7.36L61.65 2.6Q62.39 1.81 62.21 1.3Z" fill="#f8b224"/><path d="M50 5.1L57.26 7.36L57.89 13.67Q58 14.73 57.55 15.04Z" fill="#fbc036"/><path d="M50 5.1L50 12.4L56.08 14.93Q57.09 15.36 57.55 15.04Z" fill="#fcc137"/><path d="M50 5.1L50 12.4L43.92 14.93Q42.91 15.36 42.45 15.04Z" fill="#f8b325"/><path d="M50 5.1L42.74 7.36L42.11 13.67Q42 14.73 42.45 15.04Z" fill="#ffe186"/><path d="M50 5.1L42.74 7.36L38.35 2.6Q37.61 1.81 37.79 1.3Z" fill="#fecd47"/><path d="M50 5.1L45.52 -0.81L39.04 0.56Q37.96 0.79 37.79 1.3Z" fill="#ffe697"/><path d="M48.88 -6.28Q50 -8.1 51.12 -6.28L53.95 -1.68Q54.48 -0.81 55.52 -0.59L60.96 0.56Q63.12 1.02 61.65 2.6L57.96 6.6Q57.26 7.36 57.36 8.37L57.89 13.67Q58.11 15.78 56.08 14.93L50.97 12.81Q50 12.4 49.03 12.81L43.92 14.93Q41.89 15.78 42.11 13.67L42.64 8.37Q42.74 7.36 42.04 6.6L38.35 2.6Q36.88 1.02 39.04 0.56L44.48 -0.59Q45.52 -0.81 46.05 -1.68L48.88 -6.28Z" fill="none" stroke="#f3a714" stroke-width="1.1" stroke-linejoin="round"/></g>'
  +   '<g class="acc treehat"><defs><linearGradient id="treehat-g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#58ad51"/><stop offset=".6" stop-color="#46993f"/><stop offset="1" stop-color="#357f33"/></linearGradient><linearGradient id="treehat-s" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffe066"/><stop offset="1" stop-color="#fbb623"/></linearGradient><radialGradient id="treehat-r" cx=".38" cy=".32" r=".75"><stop offset="0" stop-color="#ff8a8c"/><stop offset=".45" stop-color="#ec3d42"/><stop offset="1" stop-color="#c2232b"/></radialGradient><radialGradient id="treehat-y" cx=".38" cy=".32" r=".75"><stop offset="0" stop-color="#ffe9a0"/><stop offset=".45" stop-color="#fbbd2c"/><stop offset="1" stop-color="#dc8a0e"/></radialGradient></defs><path d="M50.4 14 L32 18 L19.4 29.6 L14.2 34.2 C11.6 36.6 8.6 39.4 6.6 41.4 C4.6 43.4 4.8 46 6.8 47.4 C8.4 48.6 11 49 12.8 45.4 Q17.6 43.4 22.2 38.2 Q26.8 41.5 31.4 36.7 L34 33 L66.8 33 L69.4 36.7 Q74 41.5 78.6 38.2 Q83.2 43.4 88 45.4 C89.8 49 92.4 48.6 94 47.4 C96 46 96.2 43.4 94.2 41.4 C92.2 39.4 89.2 36.6 86.6 34.2 L81.4 29.6 L68.8 18 L50.4 14Z" fill="url(#treehat-g)"/><path d="M14.6 35.6 C11.6 38.4 8.8 40.8 7.2 42.6 C6.4 43.6 6.6 44.6 7.4 44.8 C9.4 42.6 12.6 39.6 16 36.8Z" fill="#6cbf63" opacity=".45"/><path d="M15.6 31.4 Q25 33.6 32.8 32.6 L33 35.4 Q25 36 15.8 33.6Z M84.8 31.4 Q75.8 33.6 68 32.6 L67.8 35.4 Q75.8 36 84.6 33.6Z" fill="#1d5a22" opacity=".3"/><path d="M50.4 4 L30 12 C27 14.6 24.6 17.6 21.6 20.6 C19 22.8 16.4 24 14.6 25.6 C13.2 27 13.4 30 15.2 31 C16 31.6 16.8 31.6 17.4 31.6 Q25 33.6 32.8 32.6 Q38.4 38.7 44 31.6 Q50.4 36 56.8 31.6 Q62.4 38.7 68 32.6 Q75.8 33.6 83.4 31.6 C84 31.6 84.8 31.6 85.6 31 C87.4 30 87.6 27 86.2 25.6 C84.4 24 81.8 22.8 79.2 20.6 C76.2 17.6 73.8 14.6 70.8 12 L50.4 4Z" fill="url(#treehat-g)"/><path d="M17.4 31.2 Q25 33.2 32.8 32.2 Q38.4 38.2 44 31.2 Q50.4 35.6 56.8 31.2 Q62.4 38.2 68 32.2 Q75.8 33.2 83.2 31.2" fill="none" stroke="#256826" stroke-width="1" stroke-linecap="round" opacity=".45"/><path d="M14.8 26.4 C16.6 24.8 19 23.6 21.4 21.8 C19.6 24.4 17.4 26.2 15.4 28.4 C14.6 28.2 14.4 27.2 14.8 26.4Z" fill="#6cbf63" opacity=".5"/><path d="M50.4 -2.6 C47 -1.4 41 2.2 35 6 C30.6 8.4 26.6 10.4 25.4 11.8 C24.4 13 24.6 15.2 26.4 15.8 Q30 16.4 33.6 14.4 Q37.8 15.9 42 14.8 Q46.2 15.9 50.4 15 Q54.6 15.9 58.8 14.8 Q63 15.9 67.2 14.4 Q70.8 16.4 74.4 15.8 C76.2 15.2 76.4 13 75.4 11.8 C74.2 10.4 70.2 8.4 65.8 6 C59.8 2.2 53.8 -1.4 50.4 -2.6Z" fill="#1d5a22" opacity=".3" transform="matrix(.97 0 0 1 1.5 1.3)"/><path d="M50.4 -2.6 C47 -1.4 41 2.2 35 6 C30.6 8.4 26.6 10.4 25.4 11.8 C24.4 13 24.6 15.2 26.4 15.8 Q30 16.4 33.6 14.4 Q37.8 15.9 42 14.8 Q46.2 15.9 50.4 15 Q54.6 15.9 58.8 14.8 Q63 15.9 67.2 14.4 Q70.8 16.4 74.4 15.8 C76.2 15.2 76.4 13 75.4 11.8 C74.2 10.4 70.2 8.4 65.8 6 C59.8 2.2 53.8 -1.4 50.4 -2.6Z" fill="url(#treehat-g)"/><path d="M26.6 15.4 Q30 16 33.6 14 Q37.8 15.5 42 14.4 Q46.2 15.5 50.4 14.6 Q54.6 15.5 58.8 14.4 Q63 15.5 67.2 14 Q70.8 16 74.2 15.4" fill="none" stroke="#256826" stroke-width="1" stroke-linecap="round" opacity=".45"/><path d="M27 12.4 C30.4 10 34.6 7.8 38.6 5.4 C42.4 3 46 0.6 49 -0.6 C45.4 2.4 40.6 5.8 36 8.8 C33 10.8 29.6 12.6 27 12.4Z" fill="#6cbf63" opacity=".5"/><path d="M35.8 5.6 C37.6 7.8 39.6 9.6 42 10.6 C46 12.2 52 12.4 55.6 10.8 C57.8 9.8 59.4 8.4 60.6 7 M20.8 23.2 C25 24.4 30.6 23.4 35 20.2 M37.6 20.4 C42 23.6 46.6 24.4 50.4 24.2 C55 24 59.4 21.8 62.2 19.2 M64.6 19 C68 21.8 73 23.6 79.6 23.8" fill="none" stroke="#e3c0a3" stroke-width="2.6" stroke-linecap="round"/><path d="M35.8 5.6 C37.6 7.8 39.6 9.6 42 10.6 C46 12.2 52 12.4 55.6 10.8 C57.8 9.8 59.4 8.4 60.6 7 M20.8 23.2 C25 24.4 30.6 23.4 35 20.2 M37.6 20.4 C42 23.6 46.6 24.4 50.4 24.2 C55 24 59.4 21.8 62.2 19.2 M64.6 19 C68 21.8 73 23.6 79.6 23.8" fill="none" stroke="#fbefe2" stroke-width="1.7" stroke-linecap="round"/><path d="M59.2 7.8 C60.4 4.6 64 3.6 66.8 5.4 C65.6 7.4 62.4 8.4 59.2 7.8Z M59.6 7.4 C58.8 5.8 59 4.4 60.4 3.6 C61.4 4.8 61 6.4 59.6 7.4Z" fill="#fbefe2" stroke="#e3c0a3" stroke-width=".5" stroke-linejoin="round"/><circle cx="37.8" cy="8.4" r="3.2" fill="url(#treehat-r)"/><circle cx="36.6" cy="7.1" r="0.8" fill="#fff" opacity=".6"/><path d="M36.2 22.8 C33.2 21 32.2 19 32.5 17.5 C32.8 16 34.6 15.5 35.6 16.3 C35.9 16.6 36.1 16.9 36.2 17.3 C36.3 16.9 36.5 16.6 36.8 16.3 C37.8 15.5 39.6 16 39.9 17.5 C40.2 19 39.2 21 36.2 22.8Z" fill="url(#treehat-y)" stroke="#e39a12" stroke-width=".4"/><circle cx="34.4" cy="17.7" r=".8" fill="#fff" opacity=".6"/><circle cx="63.3" cy="17.3" r="3.3" fill="url(#treehat-y)"/><circle cx="62" cy="15.9" r="0.8" fill="#fff" opacity=".6"/><circle cx="25.2" cy="31" r="3.9" fill="url(#treehat-r)"/><circle cx="23.7" cy="29.4" r="0.9" fill="#fff" opacity=".6"/><circle cx="76" cy="31.9" r="3.9" fill="url(#treehat-y)"/><circle cx="74.5" cy="30.3" r="0.9" fill="#fff" opacity=".6"/><path d="M50.4 -17 L53.7 -11.5 L60 -10 L55.8 -5.2 L56.3 1.3 L50.4 -1.2 L44.5 1.3 L45 -5.2 L40.8 -10 L47.1 -11.5Z" fill="url(#treehat-s)" stroke="#f3a514" stroke-width="1.2" stroke-linejoin="round"/><path d="M50.4 -6.9 L50.4 -17 L53.7 -11.5ZM50.4 -6.9 L50.4 -17 L47.1 -11.5ZM50.4 -6.9 L40.8 -10 L47.1 -11.5ZM50.4 -6.9 L40.8 -10 L45 -5.2Z" fill="#fff" opacity=".35"/><path d="M50.4 -6.9 L60 -10 L55.8 -5.2ZM50.4 -6.9 L56.3 1.3 L50.4 -1.2ZM50.4 -6.9 L56.3 1.3 L55.8 -5.2ZM50.4 -6.9 L44.5 1.3 L50.4 -1.2Z" fill="#e08a00" opacity=".3"/></g>'
  +   '<g class="acc wizard"><defs><linearGradient id="wizard-c"><stop stop-color="#8858c8"/><stop offset=".45" stop-color="#9562d2"/><stop offset="1" stop-color="#8050c4"/></linearGradient><linearGradient id="wizard-b"><stop stop-color="#fbcd58"/><stop offset=".45" stop-color="#fdd96f"/><stop offset="1" stop-color="#f7ae42"/></linearGradient><linearGradient id="wizard-t"><stop stop-color="#8a5bce"/><stop offset=".25" stop-color="#9565d4"/><stop offset="1" stop-color="#7d4ec2"/></linearGradient><linearGradient id="wizard-u"><stop stop-color="#5b349b"/><stop offset="1" stop-color="#512d8b"/></linearGradient><radialGradient id="wizard-a" cx=".38" cy=".34" r=".72"><stop stop-color="#ffe796"/><stop offset="1" stop-color="#f7c64c"/></radialGradient><radialGradient id="wizard-s" cx=".42" cy=".4" r=".6"><stop stop-color="#ffdf86"/><stop offset="1" stop-color="#f6bb40"/></radialGradient></defs><g fill="none" stroke="#b0606a"><path d="M15.6 35 C16.6 31 19.4 28.6 24 27.7 C32 26.7 42 27.1 50 28.3 C62 30.1 72 33 80 36.5 C83.4 38.4 86 40.4 87.6 43" stroke-width="1.6" opacity=".12"/><path d="M15.6 35 C16.6 31 19.4 28.6 24 27.7 C32 26.7 42 27.1 50 28.3 C62 30.1 72 33 80 36.5 C83.4 38.4 86 40.4 87.6 43" stroke-width="3.2" opacity=".12"/><path d="M15.6 35 C16.6 31 19.4 28.6 24 27.7 C32 26.7 42 27.1 50 28.3 C62 30.1 72 33 80 36.5 C83.4 38.4 86 40.4 87.6 43" stroke-width="4.8" opacity=".12"/><path d="M15.6 35 C16.6 31 19.4 28.6 24 27.7 C32 26.7 42 27.1 50 28.3 C62 30.1 72 33 80 36.5 C83.4 38.4 86 40.4 87.6 43" stroke-width="6.4" opacity=".12"/></g><rect x="48.9" y="-7.8" width="3.1" height="6.6" rx="1" fill="#df4d3f"/><rect x="48.9" y="-7.8" width="1" height="6" rx=".5" fill="#f07a62" opacity=".7"/><circle cx="50.4" cy="-12.3" r="5.8" fill="url(#wizard-a)"/><ellipse cx="48.5" cy="-14.3" rx="1.8" ry="1.2" fill="#fff6cc" opacity=".75"/><path d="M3 33.6 C3.4 31.2 6.4 28.6 10 26.4 C16 23 24 20.8 32 19.8 C44 18.4 58 19.4 70 22.4 C78 24.4 83.6 28.4 88 33 C92 36.8 95.8 40.4 98.2 43.8 C99.4 45.4 99.8 47.2 98.8 48.6 C97.6 50 95.4 50.6 93 50.2 C90.8 49.4 89.4 46.4 87.6 43 C86 40.4 83.4 38.4 80 36.5 C72 33 62 30.1 50 28.3 C42 27.1 32 26.7 24 27.7 C20 28.3 17.6 30 16.4 33 C15.4 36 14.8 39.2 12.8 39.6 C10 40.2 6.4 38.4 4.4 36.6 C3.4 35.6 2.9 34.6 3 33.6Z" fill="url(#wizard-u)"/><path d="M3 33.6 C3.4 31.2 6.4 28.6 10 26.4 C16 23 24 20.8 32 19.8 C44 18.4 58 19.4 70 22.4 C78 24.4 83.6 28.4 88 33 C92 36.8 95.8 40.4 98.2 43.8 C99.4 45.6 99.8 47.2 99.4 48.2 C96 45.8 92 43 88.4 41.2 C85 39 82.6 37.6 80 36.5 C72 33 62 30.1 50 28.3 C42 27.1 32 26.7 24 27.7 C18 28.6 13 30.6 9 32.6 C6.6 33.8 4.4 34.2 3 33.6Z" fill="url(#wizard-t)"/><path d="M6 31.4 C10 28.2 16 25.6 22 23.8" fill="none" stroke="#a57ee0" stroke-width=".9" stroke-linecap="round" opacity=".7"/><path d="M25.6 24.4 L26.8 18 C30 13.6 36 7 42 2.4 C50 -3.6 59 -9.4 67 -11.5 C73.4 -13.2 79.4 -11.2 84.3 -7.3 C88.1 -4.3 91.3 0 93.4 4.3 C94.4 6.4 95.2 8.4 94.8 9.6 C94.4 10.6 92.6 10.2 90.6 9 C87.6 7.2 84 5.6 81 5.5 C79 5.5 77.8 7 77.7 8.8 C77.7 13 79.6 18.4 82 23.6 L84.8 30.6 C66 24.6 44 22.4 25.6 24.4Z" fill="url(#wizard-c)"/><path d="M84.8 30.6 L82 23.6 C79.6 18.4 77.7 13 77.7 8.8 C77.8 7 79 5.5 81 5.5 C84 5.6 87.6 7.2 90.6 9 C88 4.8 84.4 1.9 80.4 1.8 C76.2 1.8 73.8 4.8 73.8 9.2 C73.8 14 75.4 19.4 78 25 L78.8 30Z" fill="#4a2a90" opacity=".18"/><path d="M84.8 30.6 L82 23.6 C79.6 18.4 77.7 13 77.7 8.8 C77.8 7 79 5.5 81 5.5 C84 5.6 87.6 7.2 90.6 9 C88.6 6.2 84.8 3.8 80.9 3.7 C77.6 3.7 75.9 6 75.9 9 C75.9 13.4 77.6 18.8 80 24.2 L81.8 30Z" fill="#4a2a90" opacity=".4"/><path d="M31 13.4 C35 8.4 40 3.6 45 0" fill="none" stroke="#a57ee0" stroke-width="1" stroke-linecap="round" opacity=".55"/><g fill="url(#wizard-s)" stroke="#f7c044" stroke-width=".7" stroke-linejoin="round"><path d="M39.9 4.9 41.6 7.3 44.6 7.5 42.9 9.9 43.7 12.8 40.8 11.9 38.3 13.6 38.3 10.6 36 8.7 38.8 7.7Z"/><path d="M56.1 -6.8 56.9 -4.3 59.3 -3.3 57.1 -1.8 56.9 0.8 54.9 -0.8 52.3 -0.2 53.2 -2.7 51.8 -4.9 54.4 -4.8Z"/><path d="M62.8 2 64.2 5.2 67.6 6.1 65.1 8.5 65.3 12 62.2 10.3 58.9 11.5 59.6 8.1 57.4 5.4 60.9 5Z"/><path d="M71.7 -8.8 73.1 -6.7 75.6 -6.5 74.1 -4.6 74.6 -2.2 72.3 -3 70.2 -1.7 70.3 -4.2 68.4 -5.8 70.8 -6.5Z"/><path d="M85.3 -2.8 86 -1 87.8 -0.4 86.3 0.8 86.3 2.7 84.7 1.6 82.9 2.2 83.4 0.4 82.3 -1.2 84.2 -1.3Z"/><path d="M80.1 18.2 80.1 18.4 77.9 16.9 75.4 17.6 76.2 15.1 74.8 12.9 77.4 12.9 78.2 11.8Z" fill="#f2b947"/></g><path d="M27.6 17.2 C33 15.2 41 14.2 50 14.3 C59 14.5 67 16.8 74 20 C78 21.8 81.4 23.6 84.2 25.6 L85.6 31.6 C82 29.4 78 27.6 74 26.2 C67 24 59 21.8 50 20.6 C42 19.8 34 19.8 28.4 21.4 C26.4 22 25.6 21.2 25.8 19.6 C26 18.4 26.6 17.6 27.6 17.2Z" fill="url(#wizard-b)"/><path d="M28.6 17.8 C36 15.6 46 15.1 56 15.6 C63 16.2 68 17.6 72 19.4" fill="none" stroke="#fff0b0" stroke-width=".9" stroke-linecap="round" opacity=".8"/><path d="M95.6 9.1 98.3 13.9 103.7 15 100 19 100.6 24.5 95.6 22.2 90.6 24.5 91.2 19 87.5 15 92.9 13.9Z" fill="url(#wizard-s)" stroke="#f3b63e" stroke-width="0.8" stroke-linejoin="round"/></g>'
  +   '<g class="acc wreath"><defs><radialGradient id="wreath-b" cx=".38" cy=".32" r=".7"><stop offset="0" stop-color="#ff7a7a"/><stop offset=".55" stop-color="#e2303a"/><stop offset="1" stop-color="#b11d2b"/></radialGradient><linearGradient id="wreath-r" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ff5c60"/><stop offset="1" stop-color="#d42833"/></linearGradient></defs><path d="M8 64C2 80 18 97 50 98C82 97 98 80 92 64L84 70Q74 85 50 85Q26 85 16 70Z" fill="#1d6128"/><path d="M50 92q2.7 0.3 3.4 2.4-1.5 1.4-0.2 2.9-1.5 1.9-3.2 3.2-1.7-1.4-3.2-3.2 1.4-1.5-0.2-2.9 0.7-2 3.4-2.4ZM24 88q4-0.5 5.6 2.2-1.7 2.5 0.8 4.1-1.5 3.2-3.4 5.7-2.9-1.3-5.7-3.4 1.4-2.6-1.3-4 0.2-3.1 4-4.6ZM76 88q3.7 1.4 4 4.6-2.6 1.4-1.3 4-2.8 2.1-5.7 3.4-1.9-2.5-3.4-5.7 2.5-1.7 0.8-4.1 1.7-2.6 5.6-2.2ZM20 84q3.2 3 2.2 6.3-3.3 0.3-2.9 3.6-3.7 1-7.2 1.1-0.9-3.4-1.1-7.2 3.2-0.7 2.5-3.9 2.8-2 6.6 0.1ZM80 84q3.8-2.1 6.6-0.1-0.7 3.2 2.5 3.9-0.2 3.9-1.1 7.2-3.5-0.2-7.2-1.1 0.3-3.3-2.9-3.6-1-3.3 2.2-6.3ZM33 90q3.1-2.5 5.9-1.2-0.1 3 2.9 3.1 0.5 3.5 0.2 6.7-3.1 0.4-6.6 0.2-0.3-3-3.2-2.7-1.5-2.8 0.9-6ZM67 90q2.4 3.2 0.9 6-3-0.3-3.2 2.7-3.5 0.2-6.7-0.2-0.3-3.2 0.2-6.7 3-0.1 2.9-3.1 2.8-1.3 5.9 1.2ZM15.5 81q1 4-1.5 6-2.7-1.4-4.1 1.3-3.4-1.1-6.3-2.8 1-3.1 2.8-6.3 2.9 1.1 4-1.8 3.2-0.2 5.2 3.5ZM84.5 81q1.9-3.6 5.2-3.5 1.1 2.9 4 1.8 1.8 3.2 2.8 6.3-2.8 1.6-6.3 2.8-1.4-2.7-4.1-1.3-2.5-2.1-1.5-6ZM14.5 78q-2.5 3.4-5.8 2.8-0.7-3.1-3.8-2.4-1.4-3.5-1.9-6.8 3.1-1.3 6.8-1.9 1 3 4 2 2.2 2.5 0.6 6.4ZM85.5 78q-1.6-3.9 0.6-6.4 3 1 4-2 3.7 0.6 6.8 1.9-0.5 3.3-1.9 6.8-3.1-0.7-3.8 2.4-3.3 0.6-5.8-2.8ZM15 71q-3.9 1-5.9-1.4 1.3-2.7-1.3-4 1.1-3.3 2.7-6.1 3 0.9 6.1 2.7-1 2.8 1.8 3.8 0.2 3.1-3.3 5ZM85 71q-3.5-1.9-3.3-5 2.8-1 1.8-3.8 3.1-1.7 6.1-2.7 1.6 2.7 2.7 6.1-2.7 1.3-1.3 4-2 2.4-5.9 1.4Z" fill="#338d3c" stroke="#1d6128" stroke-width=".4"/><path d="M49.7 92.7q-3.3 3.1 0 7.2-0.6-3.7 0-7.2M24.6 88.9q5.8 3.3 2.6 10.2-0.4-5.4-2.6-10.2M75.4 88.9q-5.8 3.3-2.6 10.2 0.5-5.4 2.6-10.2M19 84.6q-7.3 1-6.8 9.4 2.7-5.3 6.8-9.4M81 84.6q7.2 1 6.8 9.4-2.7-5.3-6.8-9.4M34 90.4q6.6-0.4 7.7 7.2-3.3-4.3-7.7-7.2M66 90.4q-6.6-0.4-7.7 7.2 3.3-4.3 7.7-7.2M14.4 81q-6.2-2.9-10.1 3.9 4.8-2.8 10.1-3.9M85.6 81q6.2-2.9 10.1 3.9-4.8-2.8-10.1-3.9M13.8 77.1q-1.7-6.9-9.8-5.5 5.4 2 9.8 5.5M86.2 77.1q1.7-6.9 9.8-5.5-5.4 2-9.8 5.5M15 69.9q2.8-6-3.8-9.8 2.7 4.6 3.8 9.8M85 69.9q-2.8-6 3.8-9.8-2.7 4.6-3.8 9.8" fill="#58b65d" opacity=".6"/><path d="M21 82q2.3-3 5.2-2.3 0.5 2.8 3.3 2.2 1.1 3.1 1.5 6.1-2.8 1-6.1 1.5-0.8-2.7-3.5-1.8-1.9-2.2-0.4-5.7ZM79 82q1.5 3.4-0.4 5.7-2.7-0.8-3.5 1.8-3.3-0.5-6.1-1.5 0.4-3 1.5-6.1 2.8 0.5 3.3-2.2 2.9-0.6 5.2 2.3ZM21 77q-0.8-3.7 1.5-5.5 2.5 1.3 3.8-1.1 3.1 1.1 5.7 2.7-1 2.8-2.7 5.7-2.6-1-3.7 1.6-3 0.1-4.7-3.3ZM79 77q-1.7 3.4-4.7 3.3-1-2.6-3.7-1.6-1.7-2.9-2.7-5.7 2.6-1.6 5.7-2.7 1.3 2.5 3.8 1.1 2.3 1.8 1.5 5.5ZM31 81q-1.7-3.5 0.2-5.8 2.8 0.8 3.5-2 3.4 0.4 6.3 1.3-0.3 3-1.3 6.3-2.8-0.4-3.3 2.4-2.9 0.8-5.4-2.2ZM69 81q-2.5 2.9-5.4 2.2-0.4-2.8-3.3-2.4-1-3.2-1.3-6.3 2.9-1 6.3-1.3 0.8 2.8 3.5 2 1.9 2.4 0.2 5.8ZM16 76q-2.9-1.8-2.5-4.5 2.4-0.7 1.7-3.1 2.7-1.3 5.3-1.9 1.2 2.4 1.9 5.3-2.3 1-1.3 3.3-1.8 1.9-5.1 0.9ZM84 76q-3.2 1.1-5.1-0.9 1-2.3-1.3-3.3 0.7-2.9 1.9-5.3 2.6 0.6 5.3 1.9-0.7 2.4 1.7 3.1 0.3 2.6-2.5 4.5ZM50 83q-2.7-0.3-3.4-2.4 1.5-1.3 0.2-2.9 1.5-1.8 3.2-3.2 1.7 1.3 3.2 3.2-1.3 1.5 0.2 2.9-0.7 2-3.4 2.4Z" fill="#41a149" stroke="#22672b" stroke-width=".4"/><path d="M22 82.2q6-1.7 8.5 5.1-3.9-3.3-8.5-5.1M78 82.2q-6-1.7-8.5 5.1 3.9-3.3 8.5-5.1M21.8 76.4q2.5-5.8 9.4-3.4-5 1-9.4 3.4M78.2 76.4q-2.5-5.8-9.4-3.4 5 1 9.4 3.4M31.6 80.2q1.2-6.3 8.5-5.5-4.8 2.1-8.5 5.5M68.4 80.2q-1.2-6.3-8.5-5.5 4.8 2.1 8.5 5.5M16.1 75.1q-2-5.3 3.8-8.1-2.6 3.8-3.8 8.1M83.9 75.1q2-5.3-3.8-8.1 2.6 3.8 3.8 8.1M50.3 82.3q3.3-3.1 0-7.1 0.6 3.6 0 7.1" fill="#74ca77" opacity=".6"/><circle cx="16.9" cy="79.6" r="2.9" fill="url(#wreath-b)"/><circle cx="22.5" cy="78.9" r="2.9" fill="url(#wreath-b)"/><circle cx="20.7" cy="83.9" r="2.9" fill="url(#wreath-b)"/><circle cx="83.1" cy="79.6" r="2.9" fill="url(#wreath-b)"/><circle cx="77.5" cy="78.9" r="2.9" fill="url(#wreath-b)"/><circle cx="79.3" cy="83.9" r="2.9" fill="url(#wreath-b)"/><path d="M15.8 78.4a.8 .6 -30 1 0 .1 0ZM21.4 77.7a.8 .6 -30 1 0 .1 0ZM19.6 82.7a.8 .6 -30 1 0 .1 0ZM82 78.4a.8 .6 -30 1 0 .1 0ZM76.4 77.7a.8 .6 -30 1 0 .1 0ZM78.2 82.7a.8 .6 -30 1 0 .1 0Z" fill="#fff" opacity=".75"/><path d="M45.4 86.4L34 100.8L38 100.4L40.4 105L51 89ZM54.6 86.4L66 100.8L62 100.4L59.6 105L49 89Z" fill="#d42a35"/><path d="M47 82C42 76.5 35 75.5 32.6 78.5C30.8 81.5 31.4 89.5 33.6 92.4C36 95 42 91.5 47 88ZM53 82C58 76.5 65 75.5 67.4 78.5C69.2 81.5 68.6 89.5 66.4 92.4C64 95 58 91.5 53 88Z" fill="url(#wreath-r)"/><path d="M46.4 83.4C42.4 80.8 38.6 80.8 37.6 83C37.2 85.6 41 87.8 46.4 86.6ZM53.6 83.4C57.6 80.8 61.4 80.8 62.4 83C62.8 85.6 59 87.8 53.6 86.6Z" fill="#a8172a" opacity=".45"/><path d="M34.6 79.6C37 77.8 40.6 78 43.4 79.8M65.4 79.6C63 77.8 59.4 78 56.6 79.8" fill="none" stroke="#ffb0b0" stroke-width="1.1" stroke-linecap="round" opacity=".75"/><rect x="45.6" y="80.4" width="8.8" height="8.6" rx="3.4" fill="url(#wreath-r)"/><path d="M47.4 82.4Q50 81.4 52.6 82.4" fill="none" stroke="#ffb0b0" stroke-width="1" stroke-linecap="round" opacity=".8"/></g>'
  +   '<g class="acc xgift"><defs><linearGradient id="xgift-b" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#2a8d45"/><stop offset=".12" stop-color="#34a150"/><stop offset="1" stop-color="#248239"/></linearGradient><linearGradient id="xgift-l" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#46b25e"/><stop offset="1" stop-color="#329b4b"/></linearGradient><linearGradient id="xgift-r" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#c8262f"/><stop offset=".35" stop-color="#ee4448"/><stop offset=".65" stop-color="#e93c41"/><stop offset="1" stop-color="#c3242e"/></linearGradient><linearGradient id="xgift-t" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#f45353"/><stop offset="1" stop-color="#d9303a"/></linearGradient><radialGradient id="xgift-w" cx=".4" cy=".3" r=".75"><stop offset="0" stop-color="#ff6461"/><stop offset=".6" stop-color="#e73a40"/><stop offset="1" stop-color="#c3232f"/></radialGradient><radialGradient id="xgift-h" cx=".38" cy=".3" r=".78"><stop offset="0" stop-color="#ffb894"/><stop offset=".5" stop-color="#ff9467"/><stop offset="1" stop-color="#ef6f40"/></radialGradient></defs><ellipse cx="50" cy="100.6" rx="19" ry="1.8" fill="#4a3d99" opacity=".12"/><rect x="30" y="86.6" width="40" height="13.8" rx="2.4" fill="url(#xgift-b)"/><path d="M30.12 98.74 L30 98 L30 91.6 L32.68 86.6 L36.68 86.6 L30.14 98.79Z M33.47 100.4 L40.88 86.6 L44.88 86.6 L37.47 100.4Z M41.67 100.4 L49.08 86.6 L53.08 86.6 L45.67 100.4Z M49.87 100.4 L57.28 86.6 L61.28 86.6 L53.87 100.4Z M58.07 100.4 L65.48 86.6 L67.6 86.6 L68.34 86.72 L69.01 87.06 L69.16 87.21 L62.07 100.4Z M68.34 100.28 L67.6 100.4 L66.27 100.4 L70 93.46 L70 98 L69.88 98.74 L69.54 99.41 L69.01 99.94Z" fill="#4cb864" opacity=".6"/><path d="M30.6 87.5 H69.4" stroke="#1d6e30" stroke-width="1.2" opacity=".5"/><rect x="45.6" y="86.6" width="8.8" height="13.8" fill="url(#xgift-r)"/><rect x="29" y="78.8" width="42" height="8.4" rx="1.6" fill="url(#xgift-l)"/><rect x="45.4" y="78.8" width="9.2" height="8.4" fill="url(#xgift-r)"/><path d="M30.4 80 H44.6" stroke="#7fd08f" stroke-width=".7" opacity=".6" stroke-linecap="round"/><path d="M55.4 80 H69.6" stroke="#7fd08f" stroke-width=".7" opacity=".6" stroke-linecap="round"/><path d="M29 78.8 L30.6 76 Q31.4 74.8 33 74.8 L67 74.8 Q68.6 74.8 69.4 76 L71 78.8Z" fill="url(#xgift-t)"/><path d="M32.4 75.6 H67.6" stroke="#ff8b85" stroke-width=".8" stroke-linecap="round" opacity=".7"/><path d="M51.6 72.6 C54.4 66.4 61.6 64 64.8 66.6 C67.6 69 67.2 75.4 63.6 77.4 C60.2 79.2 55 77.6 51.6 76.2Z" fill="url(#xgift-w)"/><path d="M48.4 72.6 C45.6 66.4 38.4 64 35.2 66.6 C32.4 69 32.8 75.4 36.4 77.4 C39.8 79.2 45 77.6 48.4 76.2Z" fill="url(#xgift-w)"/><path d="M52.6 73.4 C55.2 69.8 59.8 68.4 61.4 70.2 C62.6 71.8 59.6 74.4 52.6 74.8Z M47.4 73.4 C44.8 69.8 40.2 68.4 38.6 70.2 C37.4 71.8 40.4 74.4 47.4 74.8Z" fill="#a81a29" opacity=".5"/><path d="M35.8 68.2 Q38.4 65.4 42.4 66.6 M64.2 68.2 Q61.6 65.4 57.6 66.6" fill="none" stroke="#ff8d88" stroke-width=".9" stroke-linecap="round" opacity=".8"/><rect x="45.6" y="70.8" width="8.8" height="7.6" rx="3" fill="url(#xgift-w)"/><path d="M47.6 72.6 Q50 71.6 52.4 72.6" fill="none" stroke="#ff8d88" stroke-width=".9" stroke-linecap="round" opacity=".8"/><path d="M46.4 77.4 Q50 78.6 53.6 77.4" fill="none" stroke="#a81a29" stroke-width=".8" stroke-linecap="round" opacity=".5"/><circle cx="25.2" cy="86.8" r="9.4" fill="url(#xgift-h)"/><circle cx="74.8" cy="86.8" r="9.4" fill="url(#xgift-h)"/></g>'
  +   '<g class="acc xmuffs"><defs><radialGradient id="xmuffs-m" cx=".45" cy=".34" r=".72"><stop offset="0" stop-color="#ffffff"/><stop offset=".55" stop-color="#fdf7f8"/><stop offset=".85" stop-color="#f4dfe4"/><stop offset="1" stop-color="#ead0d7"/></radialGradient></defs><path d="M14.6 40A28.6 28.6 0 0 1 42 19.4H58A28.6 28.6 0 0 1 85.4 40" fill="none" stroke="#d9502f" stroke-width="1.6" opacity=".3"/><path d="M12.66 38A31 31 0 0 1 42 17H58A31 31 0 0 1 87.34 38" fill="none" stroke="#bd2530" stroke-width="5.2" stroke-linecap="round"/><path d="M12.2 37.4A31.6 31.6 0 0 1 42 16.4H58A31.6 31.6 0 0 1 87.8 37.4" fill="none" stroke="#e43c43" stroke-width="3.8" stroke-linecap="round"/><path d="M14.6 30.2A32.9 32.9 0 0 1 42 15.1H58A32.9 32.9 0 0 1 85.4 30.2" fill="none" stroke="#ff7b7b" stroke-width="1" stroke-linecap="round" opacity=".7"/><path d="M18.55 56.24Q19.22 61.01 16.28 63.46Q15.43 68.17 12.05 68.54Q9.86 72.27 6.7 70.49Q3.61 72.49 1.29 68.92Q-2.1 68.79 -3.1 64.13Q-6.12 61.9 -5.62 57.08Q-7.66 53.19 -5.75 49.16Q-6.42 44.39 -3.48 41.94Q-2.63 37.23 0.75 36.86Q2.94 33.13 6.1 34.91Q9.19 32.91 11.51 36.48Q14.9 36.61 15.9 41.27Q18.92 43.5 18.42 48.32Q20.46 52.21 18.55 56.24Z" fill="url(#xmuffs-m)" stroke="#ecd6dc" stroke-width=".4"/><path d="M4.8 52.9L4.8 45.6M4.8 48.23L2.79 46.77M4.8 48.23L6.81 46.77M4.8 52.9L-1.52 49.25M0.75 50.56L-1.51 51.57M0.75 50.56L0.49 48.1M4.8 52.9L-1.52 56.55M0.75 55.24L0.49 57.7M0.75 55.24L-1.51 54.23M4.8 52.9L4.8 60.2M4.8 57.57L6.81 59.03M4.8 57.57L2.79 59.03M4.8 52.9L11.12 56.55M8.85 55.24L11.11 54.23M8.85 55.24L9.11 57.7M4.8 52.9L11.12 49.25M8.85 50.56L9.11 48.1M8.85 50.56L11.11 51.57" fill="none" stroke="#e5323c" stroke-width="1.6" stroke-linecap="round"/><path d="M105.75 56.24Q106.42 61.01 103.48 63.46Q102.63 68.17 99.25 68.54Q97.06 72.27 93.9 70.49Q90.81 72.49 88.49 68.92Q85.1 68.79 84.1 64.13Q81.08 61.9 81.58 57.08Q79.54 53.19 81.45 49.16Q80.78 44.39 83.72 41.94Q84.57 37.23 87.95 36.86Q90.14 33.13 93.3 34.91Q96.39 32.91 98.71 36.48Q102.1 36.61 103.1 41.27Q106.12 43.5 105.62 48.32Q107.66 52.21 105.75 56.24Z" fill="url(#xmuffs-m)" stroke="#ecd6dc" stroke-width=".4"/><path d="M95.2 52.9L95.2 45.6M95.2 48.23L93.19 46.77M95.2 48.23L97.21 46.77M95.2 52.9L88.88 49.25M91.15 50.56L88.89 51.57M91.15 50.56L90.89 48.1M95.2 52.9L88.88 56.55M91.15 55.24L90.89 57.7M91.15 55.24L88.89 54.23M95.2 52.9L95.2 60.2M95.2 57.57L97.21 59.03M95.2 57.57L93.19 59.03M95.2 52.9L101.52 56.55M99.25 55.24L101.51 54.23M99.25 55.24L99.51 57.7M95.2 52.9L101.52 49.25M99.25 50.56L99.51 48.1M99.25 50.56L101.51 51.57" fill="none" stroke="#e5323c" stroke-width="1.6" stroke-linecap="round"/></g>'
  +   '<g class="acc xscarf"><defs><linearGradient id="xscarf-r" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#c82935"/><stop offset=".22" stop-color="#e43a40"/><stop offset=".5" stop-color="#ec4346"/><stop offset=".8" stop-color="#e1363d"/><stop offset="1" stop-color="#c42734"/></linearGradient><linearGradient id="xscarf-v" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fff" stop-opacity=".36"/><stop offset=".3" stop-color="#fff" stop-opacity=".08"/><stop offset=".45" stop-color="#fff" stop-opacity="0"/><stop offset=".72" stop-color="#5a0012" stop-opacity="0"/><stop offset="1" stop-color="#5a0012" stop-opacity=".3"/></linearGradient><linearGradient id="xscarf-s" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#6e0c18" stop-opacity=".2"/><stop offset=".07" stop-color="#6e0c18" stop-opacity="0"/><stop offset=".93" stop-color="#6e0c18" stop-opacity="0"/><stop offset="1" stop-color="#6e0c18" stop-opacity=".2"/></linearGradient><linearGradient id="xscarf-tv" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#5a0012" stop-opacity=".38"/><stop offset=".22" stop-color="#5a0012" stop-opacity="0"/><stop offset=".85" stop-color="#5a0012" stop-opacity="0"/><stop offset="1" stop-color="#5a0012" stop-opacity=".18"/></linearGradient></defs><path d="M19.8 81.1 Q50 100.2 80.2 81.1 L80.2 79.6 Q50 91.8 19.8 79.6Z" fill="#5a3cc4" opacity=".22"/><path d="M51.7 83.4 L55.2 83.3 L56.8 95.3 Q54.8 96.6 52.7 95.1Z" fill="#a51c2b"/><path d="M55.2 83.3 L60.9 83.1 L62.9 95.5 Q59.9 97.1 56.8 95.8Z" fill="url(#xscarf-r)"/><path d="M60.9 83.1 L66.7 83 L69.1 95.3 Q66 96.9 62.9 95.5Z" fill="url(#xscarf-r)"/><path d="M66.7 83 L72.4 82.8 L75.2 95.1 Q72.1 96.7 69.1 95.3Z" fill="url(#xscarf-r)"/><path d="M60.9 85.1 Q61.6 86.4 61.4 87.7 Q61.3 89 61.9 90.2 Q62.6 91.5 62.4 92.8 Q62.3 94.1 62.9 95.3 M66.7 85 Q67.4 86.2 67.3 87.5 Q67.2 88.8 67.9 90 Q68.6 91.3 68.5 92.6 Q68.4 93.8 69.1 95.1" fill="none" stroke="#8e1121" stroke-width=".8" stroke-linecap="round" opacity=".85"/><path d="M51.4 83.4 L72.4 82.8 L75.2 95.1 L52.8 95.9Z" fill="url(#xscarf-tv)"/><g fill="none" stroke-linecap="round" stroke-width="2.4"><path d="M54.1 96.1 q-0.6 3.3 -1.6 6.6" stroke="#a91d2b"/><path d="M57.4 95.9 q-0.4 3.3 -1.1 6.6M60.8 95.8 q-0.2 3.3 -0.5 6.6M64.1 95.7 q0 3.3 0 6.6M67.4 95.6 q0.2 3.3 0.5 6.6M70.8 95.5 q0.4 3.3 1.1 6.6M74.1 95.3 q0.6 3.3 1.6 6.6" stroke="#d8313a"/><path d="M53.6 97.3 q-0.5 2.6 -1.3 5.1M57 97.1 q-0.3 2.6 -0.9 5.1M60.3 97 q-0.2 2.6 -0.4 5.1M63.6 96.9 q0 2.6 0 5.1M67 96.8 q0.2 2.6 0.4 5.1M70.3 96.7 q0.3 2.6 0.9 5.1M73.7 96.5 q0.5 2.6 1.3 5.1" stroke="#f46c68" stroke-width=".6" opacity=".45"/></g><path d="M13.8 68.4 Q13.6 64.7 15.2 64.5 L22.2 67.2 L29.1 69.1 L36.1 70.4 L43 71.1 L50 71.3 L57 71.1 L63.9 70.4 L70.9 69.1 L77.8 67.2 L84.8 64.5 Q86.4 64.7 86.2 68.4 L84 70.2 Q50 81.6 16 70.2Z" fill="#a51c2b"/><path d="M14 66.2 Q50 78.2 86 66.2 C89.9 68.4 89.4 78.6 85.2 80.8 Q50 94.8 14.8 80.8 C10.6 78.6 10.1 68.4 14 66.2Z" fill="#d4313a"/><g fill="url(#xscarf-r)"><path d="M14 66.2 Q16.5 65.5 19 67.8 L19.7 82.6 Q17.3 83.1 14.8 80.8 C10.6 78.6 10.1 68.4 14 66.2Z"/><path d="M19 67.8 Q22.6 67 26.2 69.6 L26.7 84.7 Q23.2 85.2 19.7 82.6Z"/><path d="M26.2 69.6 Q30.6 68.6 35 71.2 L35.4 86.6 Q31.1 87.2 26.7 84.7Z"/><path d="M35 71.2 Q40 69.8 44.9 72.1 L45 87.7 Q40.2 88.7 35.4 86.6Z"/><path d="M44.9 72.1 Q50 70.2 55.1 72.1 L55 87.7 Q50 89.2 45 87.7Z"/><path d="M55.1 72.1 Q60 69.8 65 71.2 L64.6 86.6 Q59.8 88.7 55 87.7Z"/><path d="M65 71.2 Q69.4 68.6 73.8 69.6 L73.3 84.7 Q68.9 87.2 64.6 86.6Z"/><path d="M73.8 69.6 Q77.4 67 81 67.8 L80.3 82.6 Q76.8 85.2 73.3 84.7Z"/><path d="M81 67.8 Q83.5 65.5 86 66.2 C89.9 68.4 89.4 78.6 85.2 80.8 Q82.7 83.1 80.3 82.6Z"/></g><path d="M19 68.3 Q19.3 69.4 19.2 70.6 Q19 71.8 19.3 72.9 Q19.6 74.1 19.4 75.2 Q19.2 76.4 19.5 77.6 Q19.8 78.7 19.6 79.9 Q19.4 81.1 19.7 82.2 M26.2 70.1 Q26.5 71.3 26.3 72.5 Q26.1 73.6 26.4 74.8 Q26.7 76 26.5 77.2 Q26.3 78.4 26.6 79.6 Q26.9 80.8 26.7 82 Q26.4 83.2 26.7 84.3 M35 71.7 Q35.4 72.9 35.1 74.1 Q34.8 75.3 35.1 76.5 Q35.5 77.7 35.2 78.9 Q34.9 80.1 35.3 81.3 Q35.6 82.6 35.3 83.8 Q35 85 35.4 86.2 M44.9 72.6 Q45.3 73.8 44.9 75 Q44.6 76.2 44.9 77.5 Q45.3 78.7 45 79.9 Q44.6 81.1 45 82.4 Q45.3 83.6 45 84.8 Q44.6 86 45 87.3 M55.1 72.6 Q55.5 73.8 55.1 75 Q54.7 76.2 55.1 77.5 Q55.4 78.7 55 79.9 Q54.7 81.1 55 82.4 Q55.4 83.6 55 84.8 Q54.6 86 55 87.3 M65 71.7 Q65.2 72.9 64.9 74.1 Q64.6 75.3 64.9 76.5 Q65.1 77.7 64.8 78.9 Q64.5 80.1 64.7 81.3 Q65 82.6 64.7 83.8 Q64.3 85 64.6 86.2 M73.8 70.1 Q74 71.3 73.7 72.5 Q73.4 73.6 73.6 74.8 Q73.8 76 73.5 77.2 Q73.2 78.4 73.4 79.6 Q73.7 80.8 73.3 82 Q73 83.2 73.3 84.3 M81 68.3 Q81.1 69.4 80.8 70.6 Q80.6 71.8 80.7 72.9 Q80.9 74.1 80.6 75.2 Q80.3 76.4 80.5 77.6 Q80.7 78.7 80.4 79.9 Q80.1 81.1 80.3 82.2" fill="none" stroke="#94121f" stroke-width=".72" stroke-linecap="round" opacity=".9"/><path d="M14 66.2 Q50 78.2 86 66.2 C89.9 68.4 89.4 78.6 85.2 80.8 Q50 94.8 14.8 80.8 C10.6 78.6 10.1 68.4 14 66.2Z" fill="url(#xscarf-v)"/><path d="M14 66.2 Q50 78.2 86 66.2 C89.9 68.4 89.4 78.6 85.2 80.8 Q50 94.8 14.8 80.8 C10.6 78.6 10.1 68.4 14 66.2Z" fill="url(#xscarf-s)"/><path d="M15 68.4 Q16.5 67.5 18 69.4 M20.5 70.1 Q22.6 69.2 24.8 71.2 M28 71.9 Q30.6 71 33.3 72.8 M37 73.3 Q40 72.2 42.9 73.9 M46.9 74.1 Q50 72.7 53.1 74.1 M57.1 73.9 Q60 72.2 63 73.3 M66.7 72.8 Q69.4 71 72 71.9 M75.2 71.2 Q77.4 69.2 79.5 70.1 M82 69.4 Q83.5 67.5 85 68.4" fill="none" stroke="#fff" stroke-width="1.7" stroke-linecap="round" opacity=".17"/></g>'
  +   '<g class="acc xsweater"><linearGradient id="xsweater-b" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ea434a"/><stop offset=".5" stop-color="#e1373e"/><stop offset="1" stop-color="#c62a33"/></linearGradient><linearGradient id="xsweater-s" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#7d0b16" stop-opacity=".34"/><stop offset=".17" stop-color="#7d0b16" stop-opacity="0"/><stop offset=".83" stop-color="#7d0b16" stop-opacity="0"/><stop offset="1" stop-color="#7d0b16" stop-opacity=".34"/></linearGradient><linearGradient id="xsweater-d" x1="0" y1="0" x2="0" y2="1"><stop offset=".5" stop-color="#7d0b16" stop-opacity="0"/><stop offset="1" stop-color="#7d0b16" stop-opacity=".24"/></linearGradient><radialGradient id="xsweater-l" cx=".5" cy=".5" r=".5"><stop offset="0" stop-color="#ff8a86" stop-opacity=".3"/><stop offset="1" stop-color="#ff8a86" stop-opacity="0"/></radialGradient><path d="M13.2 71.5C13.7 75.8 15.6 79.2 16.6 82.4C16.8 86 16.5 90.5 16.5 94C16.5 99.6 19.4 102.4 25.5 103.2C33 103.8 41 103.8 50 103.8C59 103.8 67 103.8 74.5 103.2C80.6 102.4 83.5 99.6 83.5 94C83.5 90.5 83.2 86 83.4 82.4C84.4 79.2 86.3 75.8 86.8 71.5Q50 84 13.2 71.5Z" fill="url(#xsweater-b)"/><ellipse cx="50" cy="84" rx="28" ry="7" fill="url(#xsweater-l)"/><path d="M13.2 71.5C13.7 75.8 15.6 79.2 16.6 82.4C16.8 86 16.5 90.5 16.5 94C16.5 99.6 19.4 102.4 25.5 103.2C33 103.8 41 103.8 50 103.8C59 103.8 67 103.8 74.5 103.2C80.6 102.4 83.5 99.6 83.5 94C83.5 90.5 83.2 86 83.4 82.4C84.4 79.2 86.3 75.8 86.8 71.5Q50 84 13.2 71.5Z" fill="url(#xsweater-s)"/><path d="M13.4 73.6C14 77 15.7 79.6 16.6 82.4C16.8 86 16.5 90.5 16.5 94.6M86.6 73.6C86 77 84.3 79.6 83.4 82.4C83.2 86 83.5 90.5 83.5 94.6" fill="none" stroke="#961520" stroke-width=".8" stroke-linecap="round" opacity=".6"/><path d="M16.8 97Q50 104 83.2 97C82.2 100.6 79.4 102.6 74.5 103.2C67 103.8 59 103.8 50 103.8C41 103.8 33 103.8 25.5 103.2C20.6 102.6 17.8 100.6 16.8 97Z" fill="#d42f38"/><path d="M16.8 97Q50 104 83.2 97C82.2 100.6 79.4 102.6 74.5 103.2C67 103.8 59 103.8 50 103.8C41 103.8 33 103.8 25.5 103.2C20.6 102.6 17.8 100.6 16.8 97Z" fill="url(#xsweater-d)"/><path d="M19.2 97.9v2.4M21.8 98.4v3.4M24.4 98.9v3.7M27 99.3v3.6M29.6 99.6v3.4M32.2 99.9v3.2M34.8 100.2v3M37.4 100.4v2.8M40 100.6v2.6M42.6 100.8v2.5M45.2 100.9v2.4M47.8 100.9v2.4M50.4 100.9v2.4M53 100.9v2.4M55.6 100.8v2.4M58.2 100.7v2.5M60.8 100.6v2.7M63.4 100.4v2.8M66 100.1v3M68.6 99.8v3.2M71.2 99.5v3.4M73.8 99.1v3.7M76.4 98.7v3.7M79 98.3v3.2" stroke="#9a1620" stroke-width=".55" opacity=".7"/><path d="M16.8 97Q50 104 83.2 97" fill="none" stroke="#9a1620" stroke-width=".6" opacity=".55"/><path d="M16.9 94.1 19.9 96.6 22.9 95.4 25.9 97.7 28.9 96.4 31.9 98.6 35 97.2 38 99.2 41 97.7 44 99.6 47 97.9 50 99.8 53 97.9 56 99.6 59 97.7 62 99.2 65 97.2 68.1 98.6 71.1 96.4 74.1 97.7 77.1 95.4 80.1 96.6 83.1 94.1" fill="none" stroke="#fdf2ec" stroke-width="1.3" stroke-linejoin="round" stroke-linecap="round"/><path d="M17 75.3C29 79.2 71 79.2 83 75.3" fill="none" stroke="#7d0b16" stroke-width="3.6" stroke-linecap="round" opacity=".42"/><g fill="none" stroke="#fdf6f0" stroke-linecap="round" stroke-linejoin="round"><path transform="translate(50 87.6)" d="M0 0L0 -8M-1.8 -6.9L0 -4.8 1.8 -6.9M0 0L6.9 -4M5.1 -5L4.2 -2.4 6.9 -1.9M0 0L6.9 4M6.9 1.9L4.2 2.4 5.1 5M0 0L0 8M1.8 6.9L0 4.8 -1.8 6.9M0 0L-6.9 4M-5.1 5L-4.2 2.4 -6.9 1.9M0 0L-6.9 -4M-6.9 -1.9L-4.2 -2.4 -5.1 -5" stroke-width="1.6"/><path transform="translate(30 86.6)" d="M0 0L0 -5.2M-1.2 -4.6L0 -3.1 1.2 -4.6M0 0L4.5 -2.6M3.4 -3.4L2.7 -1.6 4.6 -1.2M0 0L4.5 2.6M4.6 1.2L2.7 1.6 3.4 3.4M0 0L0 5.2M1.2 4.6L0 3.1 -1.2 4.6M0 0L-4.5 2.6M-3.4 3.4L-2.7 1.6 -4.6 1.2M0 0L-4.5 -2.6M-4.6 -1.2L-2.7 -1.6 -3.4 -3.4" stroke-width="1.2"/><path transform="translate(70 86.6)" d="M0 0L0 -5.2M-1.2 -4.6L0 -3.1 1.2 -4.6M0 0L4.5 -2.6M3.4 -3.4L2.7 -1.6 4.6 -1.2M0 0L4.5 2.6M4.6 1.2L2.7 1.6 3.4 3.4M0 0L0 5.2M1.2 4.6L0 3.1 -1.2 4.6M0 0L-4.5 2.6M-3.4 3.4L-2.7 1.6 -4.6 1.2M0 0L-4.5 -2.6M-4.6 -1.2L-2.7 -1.6 -3.4 -3.4" stroke-width="1.2"/></g><path d="M17 70.5C29 74.4 71 74.4 83 70.5" fill="none" stroke="#bd232d" stroke-width="9" stroke-linecap="round"/><path d="M17 70C29 73.9 71 73.9 83 70" fill="none" stroke="#dc343c" stroke-width="7.6" stroke-linecap="round"/><path d="M17 68.8C29 72.7 71 72.7 83 68.8" fill="none" stroke="#ea464c" stroke-width="3.8" stroke-linecap="round"/><path d="M18.2 66.7l-2.4 7.5M22 67.6l-1.4 7.8M26.2 68.3l-1 7.8M30.4 68.7l-.7 7.9M34.8 69l-.5 7.9M39.1 69.3l-.3 7.9M43.5 69.4l-.2 7.9M47.8 69.5l-.1 7.9M52.2 69.5l.1 7.9M56.5 69.4l.2 7.9M60.9 69.3l.3 7.9M65.2 69l.5 7.9M69.6 68.7l.7 7.9M73.8 68.3l1 7.8M78 67.6l1.4 7.8M81.8 66.7l2.4 7.5" stroke="#951520" stroke-width=".8" stroke-linecap="round" opacity=".72"/><path d="M15.7 67.3l-.9 2.7M19.8 68.1l-.7 3.2M23.9 68.8l-.5 3.2M28.2 69.4l-.3 3.3M32.5 69.7l-.2 3.3M36.9 70l-.2 3.3M41.2 70.2l-.1 3.3M45.6 70.3l-.1 3.3M50 70.3l0 3.3M54.4 70.3l.1 3.3M58.8 70.2l.1 3.3M63.1 70l.2 3.3M67.5 69.7l.2 3.3M71.8 69.4l.3 3.3M76.1 68.8l.5 3.2M80.2 68.1l.7 3.2M84.3 67.3l.9 2.7" stroke="#ff8d8d" stroke-width="1.1" stroke-linecap="round" opacity=".38"/><path d="M17 66.8C29 70.7 71 70.7 83 66.8" fill="none" stroke="#ffa3a0" stroke-width=".7" stroke-linecap="round" opacity=".6"/><path d="M17 74.7C29 78.6 71 78.6 83 74.7" fill="none" stroke="#8a111b" stroke-width=".6" stroke-linecap="round" opacity=".5"/></g>'
  // ── GPT outfits (end) ──
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
  // the sheet's effects (준: "Gpt의 센스있는 이펙트들도 벤치마킹해줘"): around its head, bold and clear, each for its face
  +   '<g class="fx fx-burstl"><path d="M7.2 12.2 L5 7.8 M3.2 15.6 L-1 13 M2.2 20.4 L-2.6 19.8"/></g><g class="fx fx-burstr"><path d="M92.8 12.2 L95 7.8 M96.8 15.6 L101 13 M97.8 20.4 L102.6 19.8"/></g>'
  +   '<g class="fx fx-hearts2"><path d="M5.5 12.3 C-1.5 8.3 1.4 2.7 5.5 6.2 C9.6 2.7 12.5 8.3 5.5 12.3Z"/><path d="M93.0 16.8 C88.1 14.0 90.1 10.1 93.0 12.5 C95.9 10.1 97.9 14.0 93.0 16.8Z"/></g>'
  +   '<g class="fx fx-drops"><path d="M93 13.4 q4.6 6 0 9.4 q-4.6 -3.4 0 -9.4z"/><path d="M5.6 13.6 q2.6 3.4 0 5.4 q-2.6 -2 0 -5.4z"/><path d="M1.6 18.4 q1.8 2.4 0 3.8 q-1.8 -1.4 0 -3.8z"/></g>'
  +   '<g class="fx fx-puff"><path d="M86.4 13.6 a3.4 3.4 0 0 1 1.8 -6 a4 4 0 0 1 7.4 -.8 a3.2 3.2 0 0 1 2 5.8 a3.2 3.2 0 0 1 -4.4 2.6 a3.4 3.4 0 0 1 -5.4 -.2 a2.4 2.4 0 0 1 -1.4 -1.4z"/><path class="tail" d="M86 16 l-3.4 3.4"/></g>'
  +   '<g class="fx fx-think"><circle cx="80.6" cy="17" r="1.4"/><circle cx="83.6" cy="12.6" r="2"/><path d="M85.6 8.6 a3.4 3.4 0 0 1 2.4 -5.4 a4.2 4.2 0 0 1 7.6 -.2 a3.2 3.2 0 0 1 1.6 6 a3.6 3.6 0 0 1 -5 1.6 a3.6 3.6 0 0 1 -5.4 -.4 a2 2 0 0 1 -1.2 -1.6z"/></g>'
  +   '<g class="fx fx-shy"><path d="M85.6 12.4 l2.2 -4.6 M89.6 14.6 l3.6 -3.2 M92 18.6 l4.4 -1.4"/></g>'
  +   '<g class="fx fx-zz"><text x="80.6" y="15.6">z</text><text x="87.6" y="8.6">Z</text></g>'
  +   '<g class="fx fx-swirls"><path d="M7 10 m-1 0 a1 1 0 1 1 2 0 a2.6 2.6 0 1 1 -5.2 0 a4.2 4.2 0 1 1 8.4 0"/><path d="M93 10 m-1 0 a1 1 0 1 1 2 0 a2.6 2.6 0 1 1 -5.2 0 a4.2 4.2 0 1 1 8.4 0"/></g>'
  +   '<g class="fx fx-sparks"><path d="M5.0 5.0 Q6.3 9.7 11.0 11.0 Q6.3 12.3 5.0 17.0 Q3.7 12.3 -1.0 11.0 Q3.7 9.7 5.0 5.0Z"/><path d="M93.4 13.8 Q94.3 17.1 97.6 18.0 Q94.3 18.9 93.4 22.2 Q92.5 18.9 89.2 18.0 Q92.5 17.1 93.4 13.8Z"/></g>'
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
    if (bubble.classList.contains("asking")) return; // a question waits for its answer
    const go = bubble.dataset.go; bubble.classList.remove("on", "act"); b._go = "";
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
  bubble.innerHTML = '<span class="typing"><i></i><i></i><i></i></span>'; bubble.dataset.go = ""; bubble.classList.remove("act", "sfx", "asking"); bubble.classList.add("on"); fitBubble(bubble);
  if (b._mood) b._mood("thinking");
  clearTimeout(b._bubble); b._bubble = setTimeout(() => { if (!b._spoken) bubble.classList.remove("on"); }, 15000);
  b._spoken = false;
}
/*
  퍼레's face follows its own words (준: "퍼레의 사용자 상호작용"): a crying face or ㅠㅠ makes it teary, 헉 surprised, 히히
  giggle, and so on, with a motion that fits; a line that calls for none keeps the plain happy face. Not while it plays on
  its own, naps or sleeps: then the face only.
*/
const FEEL_OF = [
  [/[\u{1F622}\u{1F972}\u{1F97A}\u{1F62D}]|[ㅠㅜ]{2,}|흐잉|훌쩍|흑흑/u, "teary", "droop"],
  [/[\u{1F62E}\u{1F632}\u{1F62F}\u{1F631}]|헉|깜짝|세상에|어머나/u, "surprised", "perk"],
  [/[\u{1F606}\u{1F602}\u{1F923}\u{1F639}]|히히|헤헤|키키|킥킥|ㅋㅋ|ㅎㅎ/u, "giggle", "giggleshake"],
  [/[\u{2764}\u{1F495}\u{1F496}\u{1F970}\u{1F60D}]|사랑해|좋아해/u, "love", "heartbeat"],
  [/[\u{1F633}\u{1F648}]|부끄|쑥스/u, "shy", "shrink"],
  [/[\u{1F634}\u{1F4A4}\u{1F971}]|졸려|하암|잘 ?자|굿나잇/u, "drowsy", "melt"],
  [/[\u{1F389}\u{1F973}\u{1F929}]|신나|대박|야호|우와|와아/u, "excited", "bounce"],
  [/[\u{1F4AA}\u{1F60E}]|짜잔|해냈|성공/u, "proud", "flex"],
  [/[\u{1F914}]|궁금|글쎄|뭘까/u, "curious", "leanin"],
  [/미안|어쩌지/u, "sorry", "lookdown"],
  [/[\u{1F61E}\u{1F614}\u{1F61F}]|속상|아쉽|슬퍼/u, "sad", "droop"],
  [/[\u{1F60B}]|맛있|냠냠/u, "yum", "bobbing"],
  [/[\u{1F976}]|추워|춥다/u, "chilly", "shiverbig"],
  [/[\u{1F975}]|더워|덥다/u, "overheated", "pant"]];
/** The face and the motion 퍼레's words call for, [face, motion]; null when they call for none. */
function feelOf(text) { const t = String(text || ""); for (const [re, face, motion] of FEEL_OF) if (re.test(t)) return [face, motion]; return null; }
/** How long a motion of 퍼레's lasts (its MOTIONS entry). */
function motionMs(m) { const x = typeof MOTIONS === "undefined" ? null : MOTIONS.find(y => y[0] === m); return x ? x[1] : 1200; }
/** Whether 퍼레 is free to move: not playing on its own, napping or asleep. */
function freeToMove(b) { const buddy = $(b, ".buddy"); return !(b._inPlay && b._inPlay()) && !buddy.classList.contains("nap") && !buddy.classList.contains("asleep"); }
/** The line the AI wrote, typed into the bubble as it is said (the mouth moves while it types); the face its words call for, its motion as the last letter is typed. */
function thought(said) {
  window._soloAsk = 0; window._answerAsk = 0;
  // A question comes with its choices on a second line ("고르기: 응 / 아니 / 글쎄").
  const parts = String(said || "").split("\n"), line = parts[0].trim();
  const given = (parts.slice(1).find(l => /^\s*고르기\s*[:：]/.test(l)) || "").replace(/^\s*고르기\s*[:：]/, "").split(/[\/|·]/).map(x => x.trim()).filter(x => x && x.length <= 12).slice(0, 3);
  const picks = asking(line) ? (given.length >= 2 ? given : choicesFor(line)) : [];
  boards.forEach(b => {
    b._spoke = Date.now();
    const bubble = $(b, ".bubble"), svg = $(b, ".bot"); b._spoken = true;
    let i = 0; const chars = Array.from(line), feel = feelOf(line);
    if (b._mood) b._mood(""); if (b._feel) b._feel(feel ? feel[0] : "happy", Math.max(1600, chars.length * 38 + 1200));
    bubble.textContent = ""; bubble.classList.remove("sfx", "asking"); bubble.classList.add("on"); if (svg) svg.classList.add("talking");
    clearInterval(b._typing);
    b._typing = setInterval(() => {
      i++; bubble.textContent = chars.slice(0, i).join(""); fitBubble(bubble);
      if (i >= chars.length) {
        clearInterval(b._typing); if (svg) svg.classList.remove("talking"); clearTimeout(b._bubble);
        if (feel && feel[1] && freeToMove(b)) move(b, feel[1], motionMs(feel[1]));
        if (picks.length) offer(b, line, picks); else b._bubble = setTimeout(() => bubble.classList.remove("on"), 6500);
      }
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
  bubble.appendChild(box); bubble.dataset.q = question; bubble.classList.add("on", "asking");
  clearTimeout(b._bubble); b._bubble = setTimeout(() => bubble.classList.remove("on", "asking"), 20000);
  fitBubble(bubble);
}
/** A choice picked: 퍼레 hears it, answers (the AI, or its own words when there is none) and keeps it in mind. */
function answer(b, question, picked) {
  const bubble = $(b, ".bubble");
  bubble.classList.remove("asking"); bubble.textContent = picked; bubble.classList.add("mine"); fitBubble(bubble);
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
/**
  The bubble kept inside the screen, 8 px from either side, wherever 퍼레 stands (준: "말풍선 배치가 엉망이야"): measured
  without its own pop-in scale (퍼레's place plus the bubble's own), moved with translate so the pop-in still plays.
*/
function fitBubble(bubble) {
  if (!bubble || !bubble.offsetWidth || !bubble.offsetParent) return;
  bubble.style.translate = "";
  const vw = document.documentElement.clientWidth || innerWidth, left = bubble.offsetParent.getBoundingClientRect().left + bubble.offsetLeft, right = left + bubble.offsetWidth;
  const shift = left < 8 ? 8 - left : right > vw - 8 ? Math.max(8 - left, vw - 8 - right) : 0;
  if (shift) bubble.style.translate = Math.round(shift) + "px 0";
}
/** The bubble over the friend: words, and what a tap on it opens (if anything); the face its words call for (feelOf), unless it was given one. */
function speak(b, line, ms) {
  b._spoke = Date.now();
  const feel = line.sfx || line.go || line.feel ? null : feelOf(line.text);
  if (feel) { if (b._feel) b._feel(feel[0], Math.min(ms || 6500, 2600)); if (feel[1] && freeToMove(b)) move(b, feel[1], motionMs(feel[1])); }
  const bubble = $(b, ".bubble");
  bubble.textContent = line.text; bubble.dataset.go = line.go || "";
  if (line.go) { const more = document.createElement("b"); more.className = "more"; more.textContent = " ›"; bubble.appendChild(more); }
  bubble.classList.toggle("act", !!line.go); bubble.classList.toggle("sfx", !!line.sfx); bubble.classList.remove("asking"); bubble.classList.add("on");
  clearTimeout(b._bubble); b._bubble = setTimeout(() => bubble.classList.remove("on", "act"), ms || 6500);
  // 퍼레's own ready words never wait for an answer (준: "맥락과 무관한 무의미한 질문들이 반복돼": "새로운 거 알게 됐어?" with
  // 응!/아니/글쎄~ after an app): only the AI's questions, asked about something it wants to know, come with choices (thought).
  fitBubble(bubble);
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
  // Food on its way (준: "배민에서 배달한걸 못알아채네"), said in passing (준: "너무 반복해서 알림이야기를 하거나 오랫동안 풍선띄우는건
  // 자제", "그냥 흘러가듯이 슬쩍 말하는 정도"): once when it sets off (or is close, if that came first), once when it came, and a
  // call-off; the stages before only show on the small pill.
  const d = !k.locked && k.delivery;
  const said1 = d && {onway: "way", near: "way", done: "done", canceled: "done"}[d.stage];
  if (said1 && d.line) lines.unshift({key: "deliv:" + d.app + ":" + said1 + ":" + Math.floor((d.at || 0) / 10800000), text: d.line, go: "", ms: 3200,
    feel: d.stage === "canceled" ? "sad" : "excited"});
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
  if (!seenNow || !info || (info.env && (info.env.dnd || info.env.hush))) return;
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
    // A word in passing, not a sign left up (준: "오랫동안 풍선띄우는건 자제"): as long as it takes to read, 4.5 s at most.
    speak(b, line, line.ms || Math.min(4500, 2400 + line.text.length * 55));
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
    // Do not disturb, or asked to keep quiet a while (퍼레's quiet tool, env.hush): no greeting of its own.
    if (info && info.env && (info.env.dnd || info.env.hush)) return;
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
  happy: "arc smile none cheeks", laugh: "arc wide none cheeks burst", wink: "wink smile", love: "heart grin none cheeks hearts2", surprised: "o osm none burst1",
  shock: "wide osm none glint", sad: "small frown sad", teary: "small wave sad tears glint", cry: "pill big sad tears", dizzy: "dz osm none swirls",
  yum: "arc chew none cheeks", full: "arc wave", yawn: "shut yawn", drowsy: "half flat", sleepy: "shut osm none zz bubble", shy: "small grin none blush cheeks shy",
  thinking: "pill frown curious think", blow: "pill osm none cheeks", angry: "pill frown angry", fume: "pill frown angry cheeks", proud: "pill grin angry sparks",
  relief: "u smile none sweat2", content: "arc smile sad blush", cheeky: "wink tongue", kiss: "shut kiss none kiss blush", excited: "wide grin none glint burst",
  curious: "pill osm curious q2", suspicious: "half smirk none", blank: "pill osm", dazed: "dz osm", meh: "pill tiny none", annoyed: "pill pout angry",
  nervous: "small wave none sweat2", flustered: "wide wave none sweat2 blush", moved: "pill wave sad tears glint", determined: "pill flat angry",
  thrilled: "sq grin none cheeks", smug: "half smirk", sorry: "u frown sad sweat2", ko: "shut wave none", ouch: "shut pout none sweat2", smitten: "heart cat none blush",
  sneaky: "half smirk none sweat2", gloomy: "small tiny sad", idea: "wide osm none bang glow", sing: "u osm none notes", joyful: "arc wide none tears cheeks",
  pout: "pill frown angry cheeks puff", giggle: "arc cat none cheeks", amazed: "wide big none glint", sparkly: "pill smile none glint cheeks", grumpy: "pill frown angry cheeks",
  // more faces (준: "모션 종류 4가지도 각각 100개씩"): new eyes (tri, oval, lid, ring, wavy, plus), mouths (drool, tri, zig, bite, wide,
  // whistle, hat, tiny) and effects (zzz, hearts, flower, fire, bubble, swirl, lines, cloud, pale, red)
  snooze: "shut none none zzz bubble", dreamy: "lid smile none hearts", awed: "oval big none glint", shout: "pill big angry",
  panic: "wide wave none sweat2", woozy: "dz wave none cheeks", confused: "small flat none q2", drooling: "arc osm none cheeks",
  starving: "small osm sad", innocent: "u hat none", whistling: "half whistle none notes", bashful: "arc tiny none blush",
  embarrassed: "small wave none blush sweat2", furious: "pill teeth angry cheeks", firedup: "pill smile angry glint", chilly: "small wave sad",
  queasy: "shut wave sad sweat2", overheated: "half wave none sweat2", lovey: "heart wide none hearts cheeks", blissful: "arc wide none flower cheeks",
  cheerful: "tri smile none cheeks", gleeful: "tri wide none cheeks", devious: "half cat none", bored: "lid flat none", unimpressed: "half tiny none",
  skeptical: "half smirk none", sulky: "small pout sad", heartbroken: "small wave sad tears", stunned: "wide osm none glint",
  speechless: "pill tiny none sweat2", eek: "wide osm sad sweat2", yikes: "wide wave sad sweat2", sly: "half cat none", sweetdream: "shut cat none zzz",
  nosy: "pill osm curious", focused: "pill flat none", anxious: "small osm sad drops", cringe: "shut wave sad sweat2", triumphant: "arc big none cheeks flower",
  delighted: "wide wide none glint", lol: "arc wide none cheeks", grateful: "u smile sad flower", zen: "lid tiny none", horrified: "wide osm sad sweat2",
  playful: "wink wide none cheeks", hopeful: "oval smile sad glint", lonely: "small tiny sad", awkward: "pill wave none sweat2", chuckle: "arc hat none cheeks",
  pleading: "wide frown sad glint tears"
};
/*
  퍼레's eyes stay its eyes (준: "표정 지을 때 퍼레의 눈 정체성이 너무 벗어나는 경향이 있어", "퍼레 기본디자인에 충실해줘"): its tall
  eyes, smaller or larger, half-lidded, closed to arcs or lines, winking or hearts for love; no round dots, rings, spirals,
  crosses or triangles. A feeling shows in the lids, the brows, the mouth, the blush, tears and sweat (dizziness as a swirl
  over it).
*/
/*
  퍼레's own eyes in every face (준: "표정이 너무 흔한 이모티야 그냥 단선끄적임", "원래 캐릭터 정체성을 유지하면서 표정의 다양한
  배리에이션을 만든단구", "눈아래파인모양은 다 빼줘", "차라리 그럴거면 이모지가 낫지"): as the references do it, the eyes stay
  its pills, and the face is in short round brows (tipped up inside when sad, down when cross), the eyes' size (wide, small),
  a heavy lid (sleepy, smug), one eye unlike the other (curious, skeptic, wink), the mouth and the cheeks; a round arch when it
  laughs or is content, a closed lid asleep, hearts and a swirl now and then (love, dizzy). No cut or carved eyes.
*/
const LOOKS = {
  beam: "laugh joyful lol chuckle giggle triumphant thrilled blissful gleeful full relief grateful dreamy",
  wide: "surprised shock flustered idea panic stunned awed blank moved eek yikes horrified pleading hopeful",
  star: "excited delighted amazed sparkly", love: "love smitten lovey",
  curious: "curious nosy", skeptic: "confused suspicious skeptical", wink: "wink cheeky playful",
  small: "shy nervous embarrassed speechless awkward gloomy lonely sulky", half: "drowsy overheated unimpressed bored meh", smug: "smug sneaky whistling devious sly",
  shut: "yawn sleepy kiss ko ouch snooze sweetdream zen queasy cringe", dz: "dizzy woozy dazed"};
const LOOK_OF = {}; for (const l in LOOKS) LOOKS[l].split(" ").forEach(k => { LOOK_OF[k] = l; });
/* More eyes and mouths (준: "눈모양 입모양 … 아직 부족"): faces that look better with the new ones (the faces matched to GPT's
   sheet stay as they are), and new faces of their own. */
Object.assign(LOOK_OF, {"sorry": "worry", "teary": "teary", "moved": "teary", "heartbroken": "teary", "pleading": "glossy", "innocent": "glossy", "sparkly": "shiny", "delighted": "shiny", "ouch": "squeeze", "cringe": "squeeze", "yum": "squint", "cheerful": "squint", "dreamy": "dreamy", "drooling": "dreamy", "grateful": "calm", "zen": "calm", "relief": "calm", "sing": "calm", "drowsy": "heavy", "overheated": "droop", "lonely": "droop", "unimpressed": "sideeye", "focused": "focus", "determined": "firm", "firedup": "firm", "blank": "pip", "speechless": "pip", "sneaky": "sider", "awkward": "sidel", "whistling": "sidel", "nervous": "shiver", "chilly": "shiver", "bashful": "down", "gloomy": "down", "confused": "huh", "hopeful": "up", "playful": "onesquint"});
const MOUTH_OF = {"gleeful": "yay", "giggle": "nya", "amazed": "gasp", "blow": "ooh", "nosy": "ooh", "idea": "ooh", "cheeky": "blep", "overheated": "pant", "starving": "pant", "horrified": "sob", "eek": "eep", "smitten": "smooch", "skeptical": "hmm", "awkward": "squig", "confused": "squig", "woozy": "squig", "chuckle": "bunny", "bashful": "bunny", "yum": "nom", "hopeful": "wee", "cheerful": "wee", "blissful": "wee", "devious": "fang", "playful": "lopsy", "firedup": "lopsy", "sly": "lopsy", "blank": "square"};
Object.assign(FACES, {"peekaboo": "peek smile none cheeks", "sidelong": "sidel smile none", "lookaway": "sider wave none blush", "ponder": "up tiny curious think", "puppy": "glossy pout sad", "aww": "glossy osm none cheeks", "nodding": "heavy wave none zz", "squishy": "squeeze cat none cheeks", "tickled": "squeeze wide none cheeks", "cozy": "calm cat none cheeks", "faraway": "dreamy cat none blush", "scared": "shiver wave sad drops", "uhoh": "worry tiny sad drops", "bewildered": "huh osm none", "teehee": "onesquint tongue none cheeks", "concentrating": "focus pout none", "satisfied": "squint smile none cheeks", "sheepish": "down smile none blush", "wonder": "shiny osm none", "deadpan": "pip flat none"});
for (const k in {"peekaboo": "peek smile none cheeks", "sidelong": "sidel smile none", "lookaway": "sider wave none blush", "ponder": "up tiny curious think", "puppy": "glossy pout sad", "aww": "glossy osm none cheeks", "nodding": "heavy wave none zz", "squishy": "squeeze cat none cheeks", "tickled": "squeeze wide none cheeks", "cozy": "calm cat none cheeks", "faraway": "dreamy cat none blush", "scared": "shiver wave sad drops", "uhoh": "worry tiny sad drops", "bewildered": "huh osm none", "teehee": "onesquint tongue none cheeks", "concentrating": "focus pout none", "satisfied": "squint smile none cheeks", "sheepish": "down smile none blush", "wonder": "shiny osm none", "deadpan": "pip flat none"}) LOOK_OF[k] = FACES[k].split(" ")[0];
// Brows only where the feeling needs them (준's references: short, thin, right on the eyes, only when sad, worried or cross).
// Most faces are the face alone (준: "이펙트없는 표정들도 많이 만들어"): sweat, a flower, tears and "?" only where they are the point.
const FX_ONLY = {sweat2: "panic overheated awkward", flower: "triumphant", tears: "teary cry heartbroken", q2: "curious confused"};
for (const k in FACES) {
  let p = FACES[k].split(" "); p[0] = LOOK_OF[k] || "pill"; if (MOUTH_OF[k]) p[1] = MOUTH_OF[k];
  p = p.filter((x, i) => i < 3 || !FX_ONLY[x] || FX_ONLY[x].split(" ").includes(k));
  if (p[0] === "dz" && !p.includes("swirl")) p.push("swirl");
  // Star eyes are one big star each, no glints besides (준: "신남은 별하나만 그린 지피티버전이 더 좋아").
  if (p[0] === "star") p = p.filter(x => x !== "glint");
  FACES[k] = p.join(" ");
}
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
const IDLE_FACES = [["peekaboo"], ["sidelong"], ["lookaway"], ["ponder"], ["puppy"], ["aww"], ["nodding"], ["squishy"], ["tickled"], ["cozy"], ["faraway"], ["scared"], ["uhoh"], ["bewildered"], ["teehee"], ["concentrating"], ["satisfied"], ["sheepish"], ["wonder"], ["deadpan"], ["curious", "tilt", [5, -3]], ["smug"], ["giggle", "jelly"], ["sing", "rock"], ["content"], ["sparkly", "boing"], ["thinking", "", [4, -4.5]],
  ["cheeky", "wiggle"], ["meh"], ["proud", "tall"], ["dazed"], ["excited", "bounce"], ["suspicious", "", [-6, 0]], ["idea", "perk"], ["blank"], ["pout"]];
/** The face named (or the plain one): its eye, mouth, brows and effects on the svg. */
/*
  What goes around its head (준: "실제로 적용시에 표정은 효과 없이도 지을 수 있어야해 효과가 들어갈 수도 있고 아닐 수도 있고"): the
  face itself (eyes, brows, mouth, cheeks, tears) always; the effects around it (burst lines, hearts, "?", sweat, a puff, zz,
  swirls, sparkles …) only some of the times a face comes, about four in ten, kept while that face stays.
*/
const EFFECTS = new Set("burst burst1 hearts2 drops puff think shy zz swirls sparks q2 bang sweat2 steam notes zzz hearts flower lines cloud swirl bubble kiss fire glow".split(" "));
function applyFace(svg, name) {
  const f = (FACES[name] || "").split(" ");
  const set = (k, v) => { if (v && v !== "none" && v !== "pill") svg.dataset[k] = v; else delete svg.dataset[k]; };
  set("eye", f[0]); set("mouth", f[1]); set("brow", f[2]);
  if (svg._fxFace !== name) { svg._fxFace = name; svg._fxOn = Math.random() < .42; }
  const fx = f.slice(3).filter(x => svg._fxOn || !EFFECTS.has(x)).join(" "); if (fx) svg.dataset.fx = fx; else delete svg.dataset.fx;
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
/*
  What the user asks 퍼레 to do with itself, by voice or in 퍼레와 대화 (준: "음성 대화로 주사위 꺼내봐 해도 안된다고해", "의상바꿔도
  안되고", "음성대화로 퍼레의 상호작용이 안돼"): a toy out (a play with it), clothes (kept two hours; none: plain; any: a cute
  one of its own), a play by name. Awake for it at dawn. o: {toy} | {wear: [..], any} | {play}.
*/
const ASK_TOY = {ball: "kickball", dice: "rolldice", cube: "puzzlecube", top: "spintop", balloon: "balloonbat", butterfly: "flutter", meteors: "meteors", bubbles: "bubbles",
  tree: "decorate", sleigh: "sleighride", duck: "rubberduck", car: "toycar", rocket: "rocket", drum: "drumroll", musicbox: "musicbox", beachball: "beachball", marbles: "marbles", blocks: "blocks"};
function selfAct(o) {
  const b = boards[0]; if (!b || !o) return false;
  if (sleepHours()) { b._awakeAt = Date.now(); if (b._mood) b._mood(""); }
  // Put away when asked, and left away a while: no toy of its own for three minutes after.
  if (o.toy === "away") { if (b._toys) b._toys.clear(); b._toysAway = Date.now(); if (b._feel) b._feel("content", 1400); return true; }
  if (o.toy) { if (!ASK_TOY[o.toy] || !b._ask) return false; if (b._home) b._home(); b._ask(ASK_TOY[o.toy]); return true; }
  if (o.play) { if (!b._ask) return false; b._ask(o.play); return true; }
  if (o.wear) {
    let wear = o.wear.filter(x => WEAR.includes(x)).slice(0, 4);
    if (o.any && !wear.length) { const was = chosen ? chosen.wear : []; const left = CUTE.filter(x => !was.includes(x)); wear = [left[Math.floor(Math.random() * left.length)]]; }
    chosen = {wear, until: Date.now() + 2 * 3600000};
    wear.forEach(w => lived("wear", w, 1.2, "사용자가 골라 줘서"));
    try { localStorage.setItem("gyeotWear", JSON.stringify(chosen)); } catch (e) { /* this time only */ }
    boards.forEach(x => { const svg = $(x, ".bot"); if (svg) WEAR.forEach(w => svg.classList.toggle("w-" + w, wear.includes(w))); if (x._feel) x._feel(wear.length ? "proud" : "content", 1800); move(x, "hop", 760); });
    return true;
  }
  return false;
}
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
  "camera", "bubblewand", "raincoat", "sweater", "apron", "pajamas", "hoodie", "cape", "backpack", "mittens", "rainbow", "moon", "antlers", "astro", "banana", "bee", "bells", "berry", "cane", "chef2", "cowboy2", "detective", "dino", "elfhat", "froghood", "ginger", "globe", "hero", "knight", "panda", "pirate2", "sailor", "santa2", "shark", "snowman", "stocking", "topstar", "treehat", "wizard", "wreath", "xgift", "xmuffs", "xscarf", "xsweater"];
/** Clothes the user asked for (selfAct): worn two hours (an empty list: plain), on this phone. */
let chosen = (() => { try { const c = JSON.parse(localStorage.getItem("gyeotWear") || "null"); return c && c.until > Date.now() ? c : null; } catch (e) { return null; } })();
/** The cute things it may pick for itself (준: "의상은 좀 더 귀욤귀욤하게"). */
const CUTE = ["catears", "bunny", "bow", "beret", "bucket", "beanie", "flowercrown", "sprout", "froghat", "mushroom", "tiara", "ribbon", "bell", "scarf", "mittens", "hoodie", "teddy", "lollipop", "roundglasses", "flower",
  "berry", "sailor", "panda", "bee", "froghood", "xmuffs", "antlers", "wizard", "detective", "chef2"];
function envOf(b, k) {
  const svg = $(b, ".bot"); if (!svg || !k) return;
  const e = k.env || {}, w = k.weather || {}, life = k.life || {}, h = new Date().getHours(), code = w.code, temp = w.temp;
  const rain = code != null && ((code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95);
  const snow = code != null && ((code >= 71 && code <= 77) || code === 85 || code === 86);
  const cold = (temp != null && temp <= 5) || snow, heat = (e.heat || 0) >= 41, hot = (temp != null && temp >= 28) || heat, night = h < 6;
  const sunny = code != null && code <= 1 && w.day !== false && temp != null && temp >= 24;
  const hungry = e.battery != null && e.battery <= 20 && !e.charging;
  b._env = {music: !!e.music, phones: !!e.phones, charging: !!e.charging, hungry, cold, hot, heat, rain, snow, offline: !!e.offline, heart: life.heart, night, battery: e.battery, roomFree: e.roomFree, silent: !!e.silent, save: !!e.powerSave, dnd: !!e.dnd || !!e.hush, airplane: !!e.airplane};
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
  // December in GPT's Christmas things (준: "다음달은 크리스마스니깐", "크리스마스 관련도 만들고"): late December a Christmas hat
  // each day, earlier some days; a red knit scarf and a snowflake sweater in the cold, Christmas things in hand some days. On an
  // ordinary day now and then one of the fun ones (준: "재미난 의상들 많이 만들어보자"); a whole costume wears nothing else.
  const xmas = month === 12, eve = xmas && date >= 20, XMAS_HEAD = ["santa2", "elfhat", "antlers", "treehat", "santa"];
  const COSTUME = ["dino", "shark", "panda", "froghood", "bee", "banana", "astro", "knight", "snowman", "ginger"];
  const head = night ? (eve ? "santa2" : "cap") : e.music && e.phones ? "phones" : eve ? (date === 25 && doy % 2 ? "ginger" : XMAS_HEAD[doy % XMAS_HEAD.length])
    : month === 10 && date === 31 ? "horns" : month === 10 && date >= 25 ? "witch" : month === 1 && date <= 3 ? "crown"
    : snow ? (xmas && doy % 3 === 0 ? "snowman" : "snowtop") : freezing ? "beanie" : cold ? (xmas ? "xmuffs" : "muffs") : drizzle ? "hood" : rain ? "froghat" : hot && sunny ? "straw" : sunnyMild ? "visor"
    : lunchTime && weekend ? "chef" : day === 6 ? "party"
    : seasonal && doy % 2 === 0 ? seasonal
    : (xmas && pickDay(["elfhat", "antlers", "santa2", "topstar", "xmuffs"], 7, 45))
    || pickDay(["beret", "bucket", "bow", "bunny", "catears", "tiara", "froghat", "mushroom", "flowercrown", "sprout", "beanie",
      "berry", "sailor", "wizard", "detective", "chef2", "pirate2", "cowboy2"], 1, 50)
    || pickDay(COSTUME.slice(0, 8), 13, 12) || seasonal;
  const costume = COSTUME.includes(head);
  // Nothing that looks like something it is doing stays on for hours (준: "왜 안경 쓰고 책보는 모습이 계속 지속될까", "아까는
  // 하트눈 날개모습이 지속되더니"): glasses, heart eyes, a cape, a book and the like come with a play (PROPS) and go with it;
  // what stays is for a reason, the weather, the hour, the phone or a holiday.
  const face = e.dnd ? "mask" : costume ? "" : e.airplane ? "goggles" : april1 ? "clownnose"
    : sunny && !night && head !== "straw" && head !== "visor" ? "shades" : "";
  const neck = costume ? "" : e.charging ? "bib" : cold ? (xmas ? "xscarf" : "scarf") : head === "tophat" || head === "beret" ? "bowtie" : hot && summer ? "lei"
    : xmas && pickDay(["bells", "wreath"], 9, 35) ? pickDay(["bells", "wreath"], 9, 35)
    : head === "bandana" ? "neckbandana" : head === "bow" ? "ribbon"
    : pickDay(["ribbon", "bell", "bowtie"], 3, 12);
  const hand = rain && !drizzle ? "umbrella" : night ? "teddy"
    : month === 10 && date >= 25 ? "pumpkin" : month === 1 && date <= 3 ? "luckybag" : xmas && (date === 24 || date === 25) ? "xgift"
    : xmas && pickDay(["cane", "globe", "stocking"], 11, 35) ? pickDay(["cane", "globe", "stocking"], 11, 35)
    : snow ? "snowball" : scorching ? (h >= 12 && h < 17 ? "icecream" : "handfan") : hot ? (summer && doy % 2 ? "popsicle" : "handfan")
    : minutes >= 7 * 60 && minutes < 9 * 60 ? (freezing ? "cocoa" : cold ? "tea" : "coffee")
    : minutes >= 12 * 60 && minutes < 13 * 60 ? (doy % 3 === 0 ? "riceball" : "lunch") : minutes >= 22 * 60 && minutes < 22 * 60 + 20 ? "toothbrush"
    : e.music ? "mic" : cold && h >= 14 && h < 18 ? (doy % 2 ? "fishbread" : "sweetpotato") : "";
  const body = costume ? "" : e.powerSave ? "blanket" : rain && !drizzle ? "raincoat" : night ? "pajamas" : cold ? (xmas ? "xsweater" : "sweater") : temp != null && temp >= 6 && temp < 14 ? "hoodie"
    : head === "chef" || hand === "riceball" ? "apron" : "";
  // No lasting outfit from how fond it is (준: "자꾸 눈하트에 이상한날개 단 상태에서 캐릭터가 안 풀려"): those are days' picks now.
  const back = costume ? "" : night ? "moon" : freezing && !snow ? "mittens" : code != null && code >= 1 && code <= 3 && h >= 15 && h < 18 && doy % 5 === 0 ? "rainbow" : "";
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
  const own = chosen && chosen.until > Date.now() ? chosen.wear : null;
  if (!b._dressed) WEAR.forEach(x => svg.classList.toggle("w-" + x, (own || wear).includes(x)));
  svg.classList.toggle("w-bolt", !!e.charging); svg.classList.toggle("w-sweat", hot && !night);
  svg.classList.toggle("w-notes", !!e.music); svg.classList.toggle("w-q", !!e.offline);
  svg.classList.toggle("w-save", !!e.powerSave); svg.classList.toggle("w-dnd", !!e.dnd || !!e.hush);
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
  The phone moved (준: "퍼레가 내 휴대폰 환경에서 살아있는 ai 봇처럼 행동, 환경을 이해하고 반응"): shaken, it gets dizzy and
  says so. It no longer leans with the phone's tilt (준: "퍼레가 자꾸 한쪽으로 기울어져 있어": a phone in the hand is always a
  little tilted, so it always leaned). Only the accelerometer (the cheapest sensor), smoothed; nothing while the page is hidden.
*/
(function motion() {
  let jolts = [], shakenAt = 0;
  addEventListener("devicemotion", e => {
    const a = e.accelerationIncludingGravity; if (!a || document.hidden || a.x == null) return;
    const now = Date.now(), m = Math.hypot(a.x || 0, a.y || 0, a.z || 0);
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
  so far in the bubble), thinks (the dots), speaks (the words typed along with their sound, over ms when the app knows how
  long they take, its mouth moving), rests between two sentences (gap: the words stay, the mouth still) and when it ends.
*/
let voiceOn = false, voiceAt = 0;
// A talk the page heard nothing of for two minutes (it ended while another app was in front): over, the mouth still.
setInterval(() => { if (voiceOn && !document.hidden && Date.now() - voiceAt > 120000) boardVoiceState("idle", ""); }, 10000);
/*
  go: what a tap on the words being said opens (BuddyVoice: an app it opened did not come up, "fix:overlay" for the way
  through). Those words stay, tappable, while it listens and nothing is heard yet; any other state lets them go.
*/
function boardVoiceState(state, text, ms, go) {
  voiceAt = Date.now();
  boards.forEach(b => {
    const buddy = $(b, ".buddy"), bubble = $(b, ".bubble"), svg = $(b, ".bot");
    const more = () => { if (!b._go) return; const m = document.createElement("b"); m.className = "more"; m.textContent = " ›"; bubble.appendChild(m); fitBubble(bubble); };
    if (state === "gap") {
      // Nothing sounding between two sentences: the words said so far stay whole, the mouth rests.
      voiceOn = true; clearInterval(b._typing); svg.classList.remove("talking");
      const said = text || b._saying || "";
      if (said) { bubble.textContent = said; more(); bubble.classList.add("on"); fitBubble(bubble); }
      return;
    }
    clearTimeout(b._bubble); clearInterval(b._typing);
    buddy.classList.toggle("listening", state === "listening");
    if (state !== "speaking") { svg.classList.remove("talking"); b._saying = ""; }
    if (state === "listening" && !text && b._go) { voiceOn = true; return; } // the tappable words stay until something is heard
    if (state !== "speaking") b._go = "";
    if (state === "listening") {
      voiceOn = true; if (b._mood) b._mood(""); if (b._feel) b._feel("", 1);
      bubble.classList.remove("act"); bubble.dataset.go = "";
      bubble.innerHTML = text ? '<span class="heard">' + esc(text) + '</span>' : '<span class="heard muted">듣고 있어…</span>'; fitBubble(bubble);
      bubble.classList.add("on");
    } else if (state === "thinking") {
      voiceOn = true; thinking(b); clearTimeout(b._bubble);
    } else if (state === "speaking") {
      // Each sentence with the face its words call for (feelOf), its motion as its sound starts.
      const feel = feelOf(text);
      voiceOn = true; if (b._mood) b._mood(""); if (b._feel) b._feel(feel ? feel[0] : "happy", Math.max(1800, ms || 0));
      if (feel && feel[1] && freeToMove(b)) move(b, feel[1], motionMs(feel[1]));
      b._go = go || ""; bubble.dataset.go = b._go; bubble.classList.toggle("act", !!b._go);
      bubble.textContent = ""; bubble.classList.add("on"); svg.classList.add("talking");
      const chars = Array.from(text || ""); let i = 0; b._saying = text || "";
      const step = ms > 0 && chars.length ? Math.min(140, Math.max(40, ms / chars.length)) : 70;
      b._typing = setInterval(() => { i++; bubble.textContent = chars.slice(0, i).join(""); fitBubble(bubble); if (i >= chars.length) { clearInterval(b._typing); more(); } }, step);
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
  b._inPlay = () => inPlay; // its words' motions wait while it plays (freeToMove)
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
    meteors: end => { const T = b._toys; if (!T) return end(); T.shower(); feel("awed", 4000); setTimeout(end, 9500); },
    // the tree, Rudolph's sleigh and the new toys
    decorate: end => { const T = b._toys; if (!T) return end(); T.tree(); feel("excited", 1400);
      b._goNear(T.treeX(), () => { move(b, "stretchup", 1300); setTimeout(() => { const what = T.treeHang(); feel(what ? "proud" : "content", 1600); if (what && b._sfx) b._sfx(what + " 달았다!", 1600, true); move(b, "hop", 760); }, 900); setTimeout(end, 3200); }); },
    admire: end => { const T = b._toys; if (!T) return end(); T.tree();
      b._goNear(T.treeX(), () => { T.treeGlow(4400); feel("awed", 2200); gazeTo(4, -5); setTimeout(() => feel("blissful", 2000), 2200); setTimeout(() => { gazeTo(0, 0); end(); }, 4600); }); },
    sleighride: end => { const T = b._toys; if (!T) return end(); feel("excited", 1200); setTimeout(() => T.sleigh(() => { move(b, "hop", 760); feel("gleeful", 1600); setTimeout(end, 900); }), 500); },
    rubberduck: end => { const T = b._toys; if (!T) return end(); const d = T.out("duck"); feel("giggle", 1400); setTimeout(() => d.tap(d), 700); setTimeout(() => { d.tap(d); feel("laugh", 1200); }, 2000); setTimeout(end, 3600); },
    toycar: end => { const T = b._toys; if (!T) return end(); const c = T.out("car"); feel("excited", 1400); setTimeout(() => c.tap(c), 700); setTimeout(() => b._goNear(T.sx(c), () => { c.tap(c); feel("gleeful", 1200); }), 2400); setTimeout(end, 5200); },
    rocket: end => { const T = b._toys; if (!T) return end(); const r = T.out("rocket"); feel("determined", 1400); gazeTo(0, 3); setTimeout(() => { r.tap(r); gazeTo(0, -6); feel("amazed", 1800); }, 1200); setTimeout(() => { gazeTo(0, 0); end(); }, 4200); },
    drumroll: end => { const T = b._toys; if (!T) return end(); const d = T.out("drum"); feel("firedup", 3000); [500, 900, 1300, 1600, 1900].forEach(t => setTimeout(() => { d.tap(d); move(b, "headbang", 400); }, t)); setTimeout(end, 3000); },
    musicbox: end => { const T = b._toys; if (!T) return end(); const m = T.out("musicbox"); setTimeout(() => m.tap(m), 600); feel("dreamy", 3600); move(b, "swaying", 3100); setTimeout(() => { if (m.el.classList.contains("playing")) m.el.classList.remove("playing"); end(); }, 4200); },
    beachball: end => { const T = b._toys; if (!T) return end(); const ball = T.out("beachball"); feel("gleeful", 1600); setTimeout(() => ball.tap(ball), 600); setTimeout(() => b._goNear(T.sx(ball), () => { T.kick(ball); move(b, "jumpturn", 1100); }), 2400); setTimeout(end, 5400); },
    marbles: end => { const T = b._toys; if (!T) return end(); const m = T.out("marbles"); feel("curious", 1400); gazeTo(-3, 4); setTimeout(() => m.tap(m), 700); setTimeout(() => { gazeTo(3, 4); m.tap(m); }, 2200); setTimeout(() => { gazeTo(0, 0); end(); }, 3800); },
    blocks: end => { const T = b._toys; if (!T) return end(); const k = T.out("blocks"); feel("focused", 2000); setTimeout(() => k.tap(k), 800); setTimeout(() => { k.tap(k); feel("proud", 1400); }, 2200); setTimeout(end, 3800); }
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
    // What the user asked it to wear stays on through a play; the play's things go on top.
    const keep = chosen && chosen.until > Date.now() ? chosen.wear : [];
    WEAR.forEach(x => svg.classList.toggle("w-" + x, keep.includes(x))); props.forEach(x => svg.classList.add("w-" + x)); dressed = true; b._dressed = true;
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
    // Its own toys come out often (준: "심심하지 않게"); the shower now and then on a dark evening. Not for three minutes after the
    // user had them put away.
    if (b._toys && !(b._toysAway && Date.now() - b._toysAway < 180000)) {
      ["kickball", "rolldice", "puzzlecube", "spintop", "balloonbat"].concat(dark ? [] : ["flutter"]).forEach(k => { if (kinds.includes(k)) kinds.push(k, k, k); });
      ["rubberduck", "toycar", "rocket", "drumroll", "musicbox", "beachball", "marbles", "blocks"].forEach(k => { if (kinds.includes(k)) kinds.push(k); });
      // The tree and Rudolph's sleigh in December (준: "다음달은 크리스마스니깐"); other months only when asked.
      if (new Date().getMonth() === 11) ["decorate", "decorate", "admire", "admire", "sleighride"].forEach(k => kinds.push(k, k));
      else ["decorate", "admire", "sleighride"].forEach(k => { while (kinds.includes(k)) kinds.splice(kinds.indexOf(k), 1); });
    } else ["kickball", "rolldice", "puzzlecube", "spintop", "balloonbat", "flutter", "meteors", "decorate", "admire", "sleighride", "rubberduck", "toycar", "rocket", "drumroll", "musicbox", "beachball", "marbles", "blocks"].forEach(k => { while (kinds.includes(k)) kinds.splice(kinds.indexOf(k), 1); });
    const kind = kinds[Math.floor(Math.random() * kinds.length)];
    playing = true;
    const here = () => {
      saidInPlay = 0; inPlay = true; dress(kind);
      const ended = () => {
        playing = false; inPlay = false; undress(); if (Math.random() < .22) { saidInPlay = Math.min(saidInPlay, 1); setTimeout(() => blurt("done", 1800), 200); }
        const joy = Math.random() * 1.6 - .2; lived("play", kind, joy); if (TOY_OF[kind]) lived("toy", TOY_OF[kind], joy);
      };
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
  // A play the user asked for (selfAct): as soon as the one going on ends (at most eight seconds), dressed for it.
  b._ask = (kind, tries) => {
    if (!PLAYS[kind]) return;
    if (playing && (tries || 0) < 20) { setTimeout(() => b._ask(kind, (tries || 0) + 1), 400); return; }
    playing = true; inPlay = true; saidInPlay = 2; dress(kind);
    const ended = () => { playing = false; inPlay = false; undress(); lived("play", kind, 1, "사용자가 하자고 해서"); if (TOY_OF[kind]) lived("toy", TOY_OF[kind], 1, "사용자가 꺼내 줘서"); };
    try { PLAYS[kind](ended); } catch (err) { ended(); }
    setTimeout(() => { if (inPlay) ended(); }, 16000);
  };
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
  What 퍼레 lives on the board, told to the app for its world and heart (BuddyWorld.java, 준: "최애 장난감은 변할 수 있어",
  "그건 퍼레의 감정에 의해 결정되는거라", "생명력을 가지게 해줘"): each play and how it went, each toy, clothes the user chose.
*/
const TOY_OF = {kickball: "ball", rolldice: "dice", puzzlecube: "cube", spintop: "top", balloonbat: "balloon", balloonpop: "balloon", flutter: "butterfly",
  meteors: "meteors", bubbles: "bubbles", kite: "kite", yoyo: "yoyo", frisbee: "frisbee", pinwheel: "pinwheel", paperplane: "paperplane",
  ball: "ball", dice: "dice", cube: "cube", top: "top", balloon: "balloon", fly: "butterfly",
  decorate: "tree", admire: "tree", sleighride: "sleigh", rubberduck: "duck", toycar: "car", rocket: "rocket", drumroll: "drum", musicbox: "musicbox",
  beachball: "beachball", marbles: "marbles", blocks: "blocks", duck: "duck", car: "car", drum: "drum"};
const livedAt = {};
function lived(kind, key, joy, why) {
  if (!window.boardLived || !key) return;
  const at = kind + ":" + key; if (Date.now() - (livedAt[at] || 0) < 15000) return; livedAt[at] = Date.now();
  window.boardLived(kind, key, Math.round(joy * 10) / 10, why || "");
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
      lived("toy", TOY_OF[it.kind], .6, "사용자가 같이 갖고 놀아 줘서");
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
      tap: it => { if (now() - (it.tapped || 0) < 400) { pop(it); return; } it.tapped = now(); it.vy = -rnd(260, 380); it.vx = rnd(-120, 120); }}), hand()),
    // more to play with (준: "장난감 … 아직 부족"): each answers a tap its own way
    duck: () => at(make("duck", "<b></b><i></i>", {r: 9, bounce: .45, friction: .9, grab: true,
      tap: it => { it.vy = -rnd(220, 320); it.el.classList.remove("squeak"); void it.el.offsetWidth; it.el.classList.add("squeak"); say("삑!", 900, true); }}), hand()),
    car: () => at(make("car", "<b></b><i></i><i></i>", {r: 8, bounce: .2, friction: .995, grab: true,
      tap: it => { it.vx = (it.vx > 0 ? -1 : 1) * rnd(380, 540); it.vy = -60; say("부릉부릉~", 1200, true); wake(); }}), hand()),
    rocket: () => at(make("rocket", "<b></b><i></i>", {r: 8, bounce: .3, friction: .9, grab: true,
      tap: it => { it.vy = -rnd(950, 1150); it.vx = rnd(-60, 60); it.a = 0; it.va = 0; it.el.classList.add("fire"); setTimeout(() => it.el.classList.remove("fire"), 900); say("3, 2, 1… 발사!", 1400, true); wake(); }}), hand()),
    drum: () => at(make("drum", "<b></b>", {r: 10, bounce: .1, friction: .8, grab: true,
      tap: it => { it.el.classList.remove("boom"); void it.el.offsetWidth; it.el.classList.add("boom"); say(["둥!", "둥둥!", "쿵짝!"][Math.floor(Math.random() * 3)], 800, true); }}), hand()),
    musicbox: () => at(make("musicbox", "<b></b><i></i>", {r: 9, bounce: .1, friction: .8, grab: true,
      tap: it => { it.el.classList.toggle("playing"); if (it.el.classList.contains("playing")) say("띠링 띠링~ ♪", 1500, true); }}), hand()),
    beachball: () => at(make("beachball", "", {r: 13, bounce: .82, friction: .99, roll: true, grab: true,
      tap: it => { it.vy = -rnd(560, 780); it.vx = rnd(-200, 200); }}), hand()),
    marbles: () => at(make("marbles", "", {r: 5, bounce: .62, friction: .995, roll: true, grab: true,
      tap: it => { it.vx = (Math.random() < .5 ? -1 : 1) * rnd(300, 500); it.vy = -rnd(120, 220); say("데구르르~", 1100, true); }}), hand()),
    blocks: () => at(make("blocks", "<b>가</b>", {r: 9, bounce: .25, friction: .85, grab: true,
      tap: it => { it.vy = -rnd(300, 420); it.va = rnd(-14, 14); const L = "가나다라마바사ABC"; it.el.firstChild.textContent = L[Math.floor(Math.random() * L.length)]; }}), hand())
  };
  /*
    The Christmas tree (준: "크리스마스 트리도 만들어줘 퍼레가 장식하거나 감상하면서 놀게"): it stands on the floor at the right in
    December (or when asked), its lights twinkle, a tap turns them up; 퍼레 hangs one ornament at a time and the star last
    (kept for the year), or sits and looks at it. And Rudolph (준: "루돌프랑 루돌프 썰매도"): the sleigh flies across with 퍼레 in it.
  */
  let tree = null;
  const TREE_KEY = "gyeotTree", year = () => new Date().getFullYear();
  const treeState = () => { try { const t = JSON.parse(localStorage.getItem(TREE_KEY) || "{}"); if (t.year === year()) return t; } catch (e) { /* new */ } return {year: year(), hung: 0, star: false}; };
  const ORN = [["#e23a3a", "빨간 방울"], ["#ffc928", "금빛 방울"], ["#4a90d9", "파란 방울"], ["#ff8fb8", "분홍 방울"], ["#e23a3a", "지팡이 사탕"], ["#ffc928", "작은 종"], ["#7cc576", "초록 방울"], ["#c4b5fd", "보라 방울"]];
  const SLOTS = [[38, 78], [63, 74], [49, 60], [30, 96], [71, 94], [44, 88], [58, 100], [51, 44]];
  const TREE_SVG = '<svg viewBox="0 0 100 130" aria-hidden="true"><path class="tstar" d="M50 0 L53.4 8.4 L62.4 8.8 L55.4 14.4 L57.8 23 L50 18.2 L42.2 23 L44.6 14.4 L37.6 8.8 L46.6 8.4Z" fill="#ffc928" stroke="#f0a800" stroke-width="1"/>'
    + '<rect x="44" y="106" width="12" height="14" rx="2" fill="#8a5531"/>'
    + '<path d="M50 14 C44 24 36 34 26 44 C34 44 38 43 41 42 C34 54 24 64 14 74 C24 75 32 73 38 71 C30 84 18 96 6 106 C30 110 70 110 94 106 C82 96 70 84 62 71 C68 73 76 75 86 74 C76 64 66 54 59 42 C62 43 66 44 74 44 C64 34 56 24 50 14Z" fill="#2f8f4e"/>'
    + '<path d="M50 14 C44 24 36 34 26 44 C34 44 38 43 41 42 C34 54 24 64 14 74 C24 75 32 73 38 71 C30 84 18 96 6 106 C18 108 30 109 40 109 C36 90 42 50 50 14Z" fill="#3aa35a" opacity=".55"/>'
    + '<path d="M32 46 Q50 54 68 44 M24 76 Q50 86 78 74 M16 104 Q50 114 86 104" fill="none" stroke="#f3e6c4" stroke-width="2" stroke-linecap="round"/>'
    + [[38, 40, "#ff5c5c"], [60, 38, "#ffd23f"], [30, 66, "#6ec3ff"], [52, 70, "#ff8fb8"], [72, 66, "#ffd23f"], [20, 94, "#ff5c5c"], [40, 98, "#6ec3ff"], [62, 96, "#ffd23f"], [82, 96, "#ff8fb8"], [50, 28, "#ffd23f"]]
      .map(([x, y, c], i) => '<circle class="lt" cx="' + x + '" cy="' + y + '" r="2.4" fill="' + c + '" style="animation-delay:' + (i * 0.17).toFixed(2) + 's"/>').join("")
    + SLOTS.map(([x, y], i) => '<g class="orn"><circle cx="' + x + '" cy="' + y + '" r="5" fill="' + ORN[i][0] + '"/><circle cx="' + (x - 1.6) + '" cy="' + (y - 1.6) + '" r="1.4" fill="#fff" opacity=".7"/></g>').join("")
    + '<rect x="10" y="112" width="18" height="14" rx="2" fill="#e23a3a"/><rect x="18" y="112" width="2.6" height="14" fill="#ffc928"/><rect x="70" y="114" width="20" height="12" rx="2" fill="#4a90d9"/><rect x="79" y="114" width="2.6" height="12" fill="#fff"/></svg>';
  const drawTree = () => { if (!tree) return; const t = treeState(); tree.querySelectorAll(".orn").forEach((o, i) => o.classList.toggle("on", i < t.hung)); tree.classList.toggle("starred", !!t.star); };
  const placeTree = () => { if (!tree) return; const w = W(); tree.style.left = Math.round(w.width - 76) + "px"; tree.style.top = Math.round(floor(w) - 84) + "px"; };
  const treeOut = () => {
    if (tree) { placeTree(); return tree; }
    tree = document.createElement("div"); tree.className = "xtree"; tree.innerHTML = TREE_SVG; layer.appendChild(tree);
    placeTree(); drawTree();
    tree.addEventListener("pointerdown", e => { e.stopPropagation(); tree.classList.toggle("lit"); say(tree.classList.contains("lit") ? "반짝반짝!" : "은은하게~", 1000, true); if (b._feel) b._feel("awed", 1400); lived("toy", "tree", .6, "사용자가 트리 불을 켜 줘서"); });
    addEventListener("resize", placeTree);
    return tree;
  };
  /** One more ornament (the star last); what was hung, or "" when it is all done. */
  const treeHang = () => {
    const t = treeState(); let what = "";
    if (t.hung < SLOTS.length) { what = ORN[t.hung][1]; t.hung++; } else if (!t.star) { what = "별"; t.star = true; }
    try { localStorage.setItem(TREE_KEY, JSON.stringify(t)); } catch (e) { /* this time only */ }
    drawTree(); if (tree && what) { tree.classList.remove("hung"); void tree.offsetWidth; tree.classList.add("hung"); }
    return what;
  };
  const SLEIGH_SVG = '<svg viewBox="0 0 220 90" aria-hidden="true">'
    + '<path d="M78 58 C96 50 108 52 118 58" fill="none" stroke="#8a5531" stroke-width="1.6"/>'
    + '<g class="deer"><ellipse cx="40" cy="52" rx="20" ry="11" fill="#a86b3c"/><path d="M26 60 l-4 16 M34 62 l-1 15 M48 62 l2 15 M56 60 l4 15" stroke="#8a5531" stroke-width="3.4" stroke-linecap="round"/>'
    + '<path d="M22 46 C16 40 16 32 20 28" fill="none" stroke="#a86b3c" stroke-width="7" stroke-linecap="round"/><ellipse cx="17" cy="27" rx="9" ry="7.4" fill="#b87a48"/>'
    + '<path d="M18 21 C16 12 12 8 8 4 M15 13 C12 12 9 11 6 12 M22 20 C24 12 28 8 33 5 M26 12 C29 12 32 13 34 15" fill="none" stroke="#7a4a2b" stroke-width="2" stroke-linecap="round"/>'
    + '<circle cx="14.6" cy="25" r="1.2" fill="#2b2b3a"/><circle class="nose" cx="8.6" cy="29" r="3.4" fill="#ff3b30"/><path d="M28 42 C34 48 44 48 52 44" fill="none" stroke="#e23a3a" stroke-width="3"/><circle cx="40" cy="47.6" r="2.4" fill="#ffc928"/></g>'
    + '<path d="M112 44 C112 36 120 34 128 36 L186 36 C198 36 204 44 202 54 C200 64 190 68 176 68 L124 68 C116 68 112 62 112 44Z" fill="#e23a3a"/><path d="M116 50 L198 50" stroke="#ffc928" stroke-width="2.4"/>'
    + '<path d="M110 76 L200 76 C210 76 214 70 212 64" fill="none" stroke="#ffc928" stroke-width="3" stroke-linecap="round"/><path d="M126 68 v8 M186 68 v8" stroke="#ffc928" stroke-width="2.4"/>'
    + '<g class="rider" transform="translate(132 0) scale(.5)"></g><rect x="176" y="24" width="20" height="16" rx="2" fill="#4a90d9"/><rect x="184.6" y="24" width="2.6" height="16" fill="#fff"/></svg>';
  /** Rudolph's sleigh across the sky with 퍼레 in it (퍼레 itself waits unseen); done when it is back. */
  const sleighRide = done => {
    const w = W(), sl = document.createElement("div"); sl.className = "sleighride"; sl.innerHTML = SLEIGH_SVG;
    const bot = $(b, ".bot"); if (bot) { const me = bot.cloneNode(true); me.removeAttribute("style"); me.setAttribute("width", "100"); me.setAttribute("height", "100"); sl.querySelector(".rider").appendChild(me); }
    sl.style.setProperty("--from", Math.round(w.width + 30) + "px"); sl.style.top = Math.round(Math.max(4, floor(w) * .18)) + "px";
    layer.appendChild(sl); buddy.style.opacity = "0";
    say("딸랑딸랑~ 루돌프 출발!", 1800, true); lived("toy", "sleigh", 1.2, "루돌프 썰매를 타서");
    setTimeout(() => { sl.remove(); buddy.style.opacity = ""; if (done) done(); }, 6200);
  };
  if (new Date().getMonth() === 11) setTimeout(treeOut, 2400);
  const throwDice = it => {
    it.rolling = true; it.rested = false; it.vy = -rnd(520, 700); it.vx = rnd(-220, 220); it.va = rnd(-18, 18);
    it.el.classList.add("rolling"); wake();
  };
  const PIPS = [[4], [0, 8], [0, 4, 8], [0, 2, 6, 8], [0, 2, 4, 6, 8], [0, 2, 3, 5, 6, 8]];
  const land = it => {
    const n = 1 + Math.floor(Math.random() * 6); it.face = n; it.va = 0; it.a = 0; it.el.classList.remove("rolling");
    Array.from(it.el.children).forEach((p, i) => p.classList.toggle("on", PIPS[n - 1].includes(i))); draw(it);
    if (b._feel) b._feel(n === 6 ? "thrilled" : n === 1 ? "pout" : "happy", 1200);
    lived("toy", "dice", n === 6 ? 1.6 : n === 1 ? -1 : .3, n === 6 ? "6이 나와서" : n === 1 ? "1이 나와서" : "");
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
    count: () => items.length,
    /** All put away (the user asked). */
    clear() { items.slice().forEach(gone); },
    tree: treeOut, treeHang, treeX: () => { treeOut(); const r = tree.getBoundingClientRect(); return r.left + r.width / 2; },
    treeGlow(ms) { treeOut(); tree.classList.add("lit"); setTimeout(() => { if (tree) tree.classList.remove("lit"); }, ms || 4000); },
    sleigh: sleighRide
  };
}

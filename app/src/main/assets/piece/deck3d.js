/*
  The LP page's turntable in three.js (준: "Lp판 질감 모델링 전반적인 디테일 업그레이드", "디테일한 three js로 해줘", "플스4느낌",
  "지금은 플스1"), drawn over the piece's own CSS deck in the same box, from the piece's own state (window.__gy, vinyl.js): the
  record and the platter turn with its spin, the arm with its angle and lift, the label is its own SVG label, the record flies
  out and back with its #slot animation. The page stays as it is; the CSS deck shows until the first frame is drawn, and again
  whenever WebGL is not there, fails, or is too slow (준: "Three js로 가면 휴대폰 성능저하심하려나").

  What keeps it light on a phone (the promise to 준):
    - it draws only while the deck is on screen and something moves (the platter turning, the arm, the record changing); a
      still deck is one frame left on the canvas. Its frame is the piece's own (one loop, stopped while the page is hidden);
    - at most 30 frames a second, at most 2 device pixels per CSS pixel (lower, then the CSS deck, when the phone cannot keep up);
    - the light is baked: what never moves (the walnut plinth, its lacquer, the brass, the print, the soft shadows) and what
      looks the same however it turns (the platter, the mat, the spindle, and the record's own body: its sheen stands still
      while it spins, as on a real record) are drawn once, at twice the size and averaged down, into two pictures (without the
      record, A; with it, B), again only when the deck changes size or another record's rings come home. A frame is picture B
      and what moves on it: the label and the dust turning, the strobe dots, the arm and its shadow, the cue lever, the lamps
      and LEDs (18 to 22 draws, 19 thousand triangles). While a record flies out and in, its body is drawn live over A;
    - one canvas, the deck only; the frame times go once to the app's diagnostic report ("LP 3D 평균 …", __gy.report);
    - nothing is made before the page is seen: three.js is read, the light baked and the canvas made once the piece's frames
      have kept coming for 0.7 s with the deck in view, which they do only while the page is on screen (a launcher's page next
      to the one shown gets a few as it loads, then none; the wallpaper's board, which slides its pages, says which is in
      sight: __gy.shown). The CSS deck stays until then. A resize or a new record's bake waits for frames too;
    - when it gives up (the phone too slow, an error), everything it held on the GPU goes (its pictures, bakes, buffers, and
      the context itself), and the CSS deck is the deck again.

  How it is made to look real (engine/story/engine3d/GRAPHICS.md, in its order):
    ① light: a lamp up and to the left (where the piece's lampLight comes from) whose pool fades toward the bottom right and
       casts soft shadows (VSM, into the bake); the room around the deck (a crisp ceiling panel and the lamp's shade up to the
       left, a cool window to the right, a soft light on the far side, the felt walls) lights every surface by its roughness
       (PMREM): the lacquer shows the panel as a reflection with a shape, the vinyl's grooves catch light on both sides of the
       spindle (the bow tie of a record under a lamp). The arm's moving shadow is a shader that finds, for each point near the
       arm, how high it is (record, platter, plinth) and so how far the shadow falls. Tone mapping: Neutral, its toe softened
       (softNeutral: Neutral alone turned the shaded walnut to orange paint).
    ② parts that meet: the platter sits in a well in the plinth, the mat in the platter's rim, the record on the mat with its
       raised bead and label, the spindle through the label's hole; the arm is a tube along the piece's own curve into a gimbal
       on a turned post, a counterweight with real grooves, a brushed aluminium headshell (brass edge and top plate, two
       screws) over a dark grey cartridge whose front and stylus guard show past it, the stylus on the groove; the strobe lamp a
       glass dome in a turned brass bezel, the hinges a leaf and three knuckles on a pin, the nameplate chamfered brass with the
       name in black enamel, the start · stop key satin silver with its words and LED (the piece's button over it, see-through).
    ③ surfaces: a scanned walnut (Poly Haven "dark_wood", CC0, graded to walnut) under a clear lacquer; turned aluminium and
       brushed brass with their lines (anisotropy); the vinyl's grooves are finer than a pixel, so they are its anisotropy and
       roughness ring by ring: a radial sheen in the grooves, mirror-smooth rings where the tracks part, at their real radii.
    ④ nothing repeats: one picture of wood over the whole plinth; dust where a seeded hand put it.
    ⑥ no stair-steps: the bake at twice the size, rounded edges, mipmaps and anisotropic filtering.
    ⑦ the air and the lamp's glow are the piece's own (its felt and vignette around the deck): post passes cost too much here.

  Coordinates: one unit is one unit of the piece's deck SVG (1000 × 780; the record's radius 300 = 152.4 mm, so 1 = 0.508 mm),
  x = its x, z = its y (down the screen), y up; the plinth's top is y = 0. The camera looks straight down from above the deck's
  middle with the field of view that makes y = 0 fill the box exactly, so the DOM buttons stay over what they press.
*/
/** three.js, read on the first frame the page is seen (not at load: the page next to the one shown never needs it). */
let THREE = null, LAMP = null, AIM = null;

const G = window.__gy, L = G && G.lp, deck = document.getElementById('deck');
// (test: checks' handles — probe, bench, the scene — and noRender/slow/toe, which only a check uses)
const OPT = Object.assign({ adapt: true, fps: 30, slow: [55, 80] }, window.__GY_DECK || {});
if (!OPT.test) { delete OPT.noRender; delete OPT.toe; OPT.slow = [55, 80]; }
const TAU = Math.PI * 2, D2R = Math.PI / 180;
const CAM_H = 3000;
const REC = { x: 400, z: 390 }, PIV = { x: 850, z: 150 };
/** Heights: the mat's top, the grooves' surface (where the stylus rides), the arm's axis. */
const Y = { mat: 24, groove: 26.6, label: 27.05, arm: 48 };
/* The key light (LAMP, AIM: made with three): a lamp up and to the left of the deck (where the piece's lampLight comes from),
   0.9 m above it. Its pool of light fades toward the bottom right as the piece's own gradient does; shadows fall down and to
   the right. */
const ASSET = '/__gyeot/addon/';

const stats = { css: null, prep: {}, frames: 0, work: [], gaps: [], gpu: [], pr: 0, w: 0, h: 0, calls: 0, triangles: 0, programs: 0, reported: false };
const api = window.__gyDeck = { ready: false, started: false, css: null, draws: 0, opt: OPT, stats: () => summary() };

/* ───────────────────────────── geometry ───────────────────────────── */

/**
 * A turned part: a profile [[r, y], …] swept around the y axis. A point repeated makes a crease. Its uv is polar (u the angle,
 * v = vOf(r)), its uv1 planar (for a picture on a disc of radius R), its tangent runs around (the turning lines).
 */
function ring(prof, seg, o = {}) {
  const R = o.R || 300, vOf = o.v || (r => r / R);
  const P = prof.length, n = new Array(P);
  let a = 0;
  for (let i = 1; i <= P; i++) {
    if (i < P && !(prof[i][0] === prof[i - 1][0] && prof[i][1] === prof[i - 1][1])) continue;
    const b = i - 1;
    for (let k = a; k <= b; k++) {
      const p = prof[Math.max(a, k - 1)], q = prof[Math.min(b, k + 1)];
      const dr = q[0] - p[0], dy = q[1] - p[1], l = Math.hypot(dr, dy) || 1;
      n[k] = [-dy / l, dr / l];
    }
    a = i;
  }
  const cols = seg + 1, V = P * cols;
  const pos = new Float32Array(V * 3), nor = new Float32Array(V * 3), uv = new Float32Array(V * 2), uv1 = new Float32Array(V * 2), tan = new Float32Array(V * 4);
  for (let i = 0; i < P; i++) {
    const [r, y] = prof[i], [nr, ny] = n[i];
    for (let j = 0; j <= seg; j++) {
      const f = j / seg * TAU, c = Math.cos(f), s = Math.sin(f), k = i * cols + j;
      pos.set([r * c, y, r * s], k * 3); nor.set([nr * c, ny, nr * s], k * 3); tan.set([-s, 0, c, 1], k * 4);
      uv.set([j / seg, vOf(r)], k * 2); uv1.set([0.5 + r * c / (2 * R), 0.5 - r * s / (2 * R)], k * 2);
    }
  }
  const idx = [];
  for (let i = 0; i < P - 1; i++) {
    if (prof[i][0] === prof[i + 1][0] && prof[i][1] === prof[i + 1][1]) continue;
    for (let j = 0; j < seg; j++) {
      const p = i * cols + j, q = p + cols;
      idx.push(p, p + 1, q, q, p + 1, q + 1);
    }
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(pos, 3)); g.setAttribute('normal', new THREE.BufferAttribute(nor, 3));
  g.setAttribute('uv', new THREE.BufferAttribute(uv, 2)); g.setAttribute('uv1', new THREE.BufferAttribute(uv1, 2));
  g.setAttribute('tangent', new THREE.BufferAttribute(tan, 4)); g.setIndex(idx);
  return g;
}
function rrect(s, x, y, w, h, r) {
  r = Math.max(0.01, Math.min(r, w / 2, h / 2));
  s.moveTo(x + r, y); s.lineTo(x + w - r, y); s.quadraticCurveTo(x + w, y, x + w, y + r); s.lineTo(x + w, y + h - r);
  s.quadraticCurveTo(x + w, y + h, x + w - r, y + h); s.lineTo(x + r, y + h); s.quadraticCurveTo(x, y + h, x, y + h - r);
  s.lineTo(x, y + r); s.quadraticCurveTo(x, y, x + r, y);
  return s;
}
/** A shape (in the deck's x, z) pushed up from y0 to y1 with rounded top and bottom edges. */
function lift(shape, y0, y1, bev, curve = 8) {
  bev = Math.min(bev, (y1 - y0) / 2 - 0.01);
  const g = new THREE.ExtrudeGeometry(shape, { depth: Math.max(0.01, y1 - y0 - 2 * bev), bevelEnabled: bev > 0.02, bevelThickness: bev, bevelSize: bev,
    bevelSegments: 3, curveSegments: curve });
  g.rotateX(Math.PI / 2); g.translate(0, y1 - bev, 0);
  return g;
}
/** A rounded slab centred on (x, z), w across and d down the screen, from y0 to y1. */
function slab(x, z, w, d, y0, y1, r, bev) {
  const b = bev == null ? Math.min(1.2, (y1 - y0) / 3) : bev;
  return lift(rrect(new THREE.Shape(), x - w / 2 + b, z - d / 2 + b, w - 2 * b, d - 2 * b, Math.max(0.01, r - b)), y0, y1, b);
}
/** uv over a box in the deck's plane (x0, z0, w, d), as a picture drawn with the screen's y down. */
function planar(g, x0, z0, w, d) {
  const p = g.attributes.position, uv = new Float32Array(p.count * 2);
  for (let i = 0; i < p.count; i++) { uv[i * 2] = (p.getX(i) - x0) / w; uv[i * 2 + 1] = 1 - (p.getZ(i) - z0) / d; }
  g.setAttribute('uv', new THREE.BufferAttribute(uv, 2));
  return g;
}
const at = (g, x, y, z) => { g.translate(x, y, z); return g; };
/** Geometries of one material in one draw (positions, normals, uvs; the first's attribute set). */
function merge(list) {
  const gs = list.map(g => g.index ? g.toNonIndexed() : g);
  const names = Object.keys(gs[0].attributes).filter(k => gs.every(g => g.attributes[k]));
  const out = new THREE.BufferGeometry();
  for (const k of names) {
    const size = gs[0].attributes[k].itemSize, n = gs.reduce((s, g) => s + g.attributes[k].count, 0), arr = new Float32Array(n * size);
    let o = 0; for (const g of gs) { arr.set(g.attributes[k].array, o); o += g.attributes[k].array.length; }
    out.setAttribute(k, new THREE.BufferAttribute(arr, size));
  }
  return out;
}

/* ───────────────────────────── pictures ───────────────────────────── */

const canvas2d = (w, h) => { const c = document.createElement('canvas'); c.width = w; c.height = h; return [c, c.getContext('2d')]; };
function seeded(s) { return () => (s = (s * 16807) % 2147483647) / 2147483647; }
function tex(c, o = {}) {
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = o.srgb === false ? THREE.NoColorSpace : THREE.SRGBColorSpace;
  t.anisotropy = o.aniso || 8; if (o.wrap) t.wrapS = t.wrapT = THREE.RepeatWrapping;
  if (o.channel) t.channel = o.channel;
  return t;
}
/** One row of numbers per radius (v = r / 300), constant around: the vinyl's and the mat's rings. */
function polar(n, fill) {
  const d = new Uint8Array(4 * n * 4);
  const t = new THREE.DataTexture(d, 4, n, THREE.RGBAFormat);
  t.wrapS = t.wrapT = THREE.ClampToEdgeWrapping; t.magFilter = THREE.LinearFilter; t.minFilter = THREE.LinearMipmapLinearFilter;
  t.generateMipmaps = true; t.anisotropy = 8;
  t.fill = () => { for (let i = 0; i < n; i++) { const px = fill(i / (n - 1) * 300, i); for (let k = 0; k < 4; k++) d.set(px, (i * 4 + k) * 4); } t.needsUpdate = true; };
  t.fill();
  return t;
}
const FONT = '"Iowan Old Style","Palatino Linotype",Palatino,P052,"Book Antiqua",Georgia,serif';

/** The plinth's print and inlay (gold ink, the brass line), drawn as the piece's SVG has them; RGB the ink, A where it is. */
function printLayer(W) {
  const k = W / 1000, H = Math.round(780 * k), [c, g] = canvas2d(W, H);
  g.scale(k, k);
  g.strokeStyle = 'rgba(214,176,70,.85)'; g.lineWidth = 1.6; rrect(new Path2D(), 0, 0, 0, 0, 0);
  const p = new Path2D(); const r = (x, y, w, h, rr) => { p.moveTo(x + rr, y); p.arcTo(x + w, y, x + w, y + h, rr); p.arcTo(x + w, y + h, x, y + h, rr); p.arcTo(x, y + h, x, y, rr); p.arcTo(x, y, x + w, y, rr); p.closePath(); };
  r(20, 20, 960, 740, 18); g.stroke(p);
  const ink = a => 'rgba(236,210,122,' + a + ')';
  g.textAlign = 'center'; g.textBaseline = 'alphabetic';
  const say = (t, x, y, size, a, ls) => { g.font = size + 'px ' + FONT; g.fillStyle = ink(a); if ('letterSpacing' in g) g.letterSpacing = (ls || 0) + 'px'; g.fillText(t, x, y); };
  say('SKATE', 944, 184, 11, 0.85, 1.5); say('CUE', 944, 300, 11, 0.85, 1.5); say('PITCH', 952, 626, 11, 0.85, 1.5);
  g.textAlign = 'left'; say('33⅓', 224, 733, 17, 0.9, 1); say('45', 296, 733, 17, 0.5, 1);
  g.strokeStyle = ink(0.6); g.lineWidth = 1.2;
  g.beginPath(); for (const [y, w] of [[60, 10], [102, 7], [144, 10], [186, 7], [228, 10], [250, 7]]) { g.moveTo(952 - 22, 330 + y); g.lineTo(952 - 22 + w, 330 + y); } g.stroke();
  // the tonearm board's own print (the board's top is just under this layer): an inner ring and the eight ticks
  g.strokeStyle = 'rgba(201,162,39,.42)'; g.lineWidth = 1; g.beginPath(); g.arc(850, 150, 56, 0, TAU); g.stroke();
  g.strokeStyle = 'rgba(201,162,39,.6)'; g.lineWidth = 1.2; g.beginPath();
  for (let i = 0; i < 8; i++) { const a = i * TAU / 8, c0 = Math.cos(a), s0 = Math.sin(a), r0 = i % 2 ? 60 : 58; g.moveTo(850 + c0 * r0, 150 + s0 * r0); g.lineTo(850 + c0 * 66, 150 + s0 * 66); }
  g.stroke();
  return tex(c);
}
/** Soft dark under and around the parts (where the room's light cannot reach): blurred shapes, drawn once. */
function shadeLayer(W) {
  const k = W / 1000, H = Math.round(780 * k), [c, g] = canvas2d(W, H);
  g.scale(k, k);
  const blob = (draw, blur, a) => { g.save(); g.filter = 'blur(' + (blur * k).toFixed(1) + 'px)'; g.fillStyle = 'rgba(0,0,0,' + a + ')'; draw(); g.restore(); };
  const disc = (x, y, r) => () => { g.beginPath(); g.arc(x, y, r, 0, TAU); g.fill(); };
  const box = (x, y, w, h) => () => g.fillRect(x - w / 2, y - h / 2, w, h);
  // the platter's well: its lip darkens toward the gap
  g.save(); g.filter = 'blur(' + (7 * k).toFixed(1) + 'px)'; g.strokeStyle = 'rgba(0,0,0,.55)'; g.lineWidth = 14; g.beginPath(); g.arc(400, 390, 347, 0, TAU); g.stroke(); g.restore();
  blob(disc(850, 150, 74), 6, 0.55); blob(disc(944, 150, 21), 4, 0.6); blob(disc(873, 561, 20), 4, 0.6);
  blob(disc(152, 634, 16), 4, 0.6); blob(box(118, 716, 140, 56), 4, 0.5); blob(box(726, 750, 236, 32), 3, 0.45); blob(box(952, 474, 38, 26), 3, 0.55);
  blob(box(944, 252, 20, 62), 3, 0.5); blob(box(952, 485, 14, 232), 3, 0.4);
  blob(disc(214, 727, 6.5), 2, 0.6); blob(disc(287, 727, 6.5), 2, 0.6); blob(box(200, 10, 104, 20), 3, 0.55); blob(box(804, 10, 104, 20), 3, 0.55);
  const t = tex(c); return t;
}
/**
 * The nameplate's face: brushed brass with the name engraved and filled with black enamel (the letters are not metal: they
 * stay black whatever the brass reflects), and a thin bright chamfer line inside its edge. [colour, metal/roughness].
 */
function plateFace() {
  const W = 1024, H = 128, [c, g] = canvas2d(W, H), [m, h] = canvas2d(W, H), rnd = seeded(77);
  g.fillStyle = '#b8924a'; g.fillRect(0, 0, W, H);
  for (let i = 0; i < 900; i++) { const y = rnd() * H, a = 0.03 + rnd() * 0.06; g.fillStyle = rnd() < 0.5 ? 'rgba(255,245,210,' + a + ')' : 'rgba(80,60,10,' + a + ')'; g.fillRect(0, y, W, 0.6 + rnd()); }
  g.strokeStyle = 'rgba(255,240,200,.55)'; g.lineWidth = 3; g.strokeRect(9, 9, W - 18, H - 18);
  h.fillStyle = 'rgb(0,92,255)'; h.fillRect(0, 0, W, H);                // G roughness 0.36, B metal 1
  const name = (ctx, fill) => {
    ctx.font = '600 56px "Didot","Bodoni 72",' + FONT; ctx.textAlign = 'center'; ctx.textBaseline = 'middle'; if ('letterSpacing' in ctx) ctx.letterSpacing = '13px';
    ctx.fillStyle = fill; ctx.fillText('HI-FI WALNUT · W-33', W / 2 + 6, H / 2 + 3);
  };
  name(g, '#120c04'); name(h, 'rgb(0,170,0)');                            // the enamel: rough 0.67, not metal
  return [tex(c), tex(m, { srgb: false })];
}
/**
 * The start · stop key's top: silver satin with fine vertical brushing, the piece's own words in its small capitals, and the
 * LED's socket (the LED is its own little light). Returns the picture and where the LED sits (0…1 across the key).
 */
function keyFace() {
  const W = 512, H = 194, [c, g] = canvas2d(W, H), rnd = seeded(13);
  const gr = g.createLinearGradient(0, 0, 0, H); gr.addColorStop(0, '#eceef1'); gr.addColorStop(0.55, '#c3c7cc'); gr.addColorStop(1, '#a7acb3');
  g.fillStyle = gr; g.fillRect(0, 0, W, H);
  for (let x = 0; x < W; x += 1.5) { g.fillStyle = rnd() < 0.5 ? 'rgba(255,255,255,' + (0.04 + rnd() * 0.06) + ')' : 'rgba(0,0,0,' + (0.02 + rnd() * 0.04) + ')'; g.fillRect(x, 0, 1, H); }
  g.font = '600 40px ' + FONT; if ('letterSpacing' in g) g.letterSpacing = '6px';
  const words = 'START · STOP', tw = g.measureText(words).width, led = 0.07 * W, gap = 0.08 * W, x0 = (W - (led + gap + tw)) / 2;
  g.textBaseline = 'middle'; g.textAlign = 'left'; g.fillStyle = '#26282c'; g.fillText(words, x0 + led + gap, H / 2 + 3);
  g.fillStyle = '#2a1d0d'; g.beginPath(); g.arc(x0 + led / 2, H / 2, led / 2 + 4, 0, TAU); g.fill();
  const t = tex(c, { aniso: 8 }); t.ledAt = (x0 + led / 2) / W;
  return t;
}
/** The platter's rim (v from r 296 to 330): turned aluminium with a bright printed ring; its dots turn on their own (dotsFace). */
function rimFace() {
  const W = 2048, H = 64, [c, g] = canvas2d(W, H), [m, h] = canvas2d(W, H);
  const row = r => (1 - (r - 296) / 34) * H;
  g.fillStyle = '#cdd0d4'; g.fillRect(0, 0, W, H);
  h.fillStyle = 'rgb(0,72,255)'; h.fillRect(0, 0, W, H);            // G roughness 0.28, B metal 1
  g.fillStyle = 'rgba(255,255,255,.9)'; g.fillRect(0, row(316.9), W, Math.max(1, row(316.2) - row(316.9)));
  g.fillStyle = 'rgba(0,0,0,.35)'; g.fillRect(0, row(302.6), W, row(301) - row(302.6));
  return [tex(c, { aniso: 16 }), tex(m, { srgb: false, aniso: 16 })];
}
/** The strobe dots (on a ring from r 312 to 322): 180 of them, 50 Hz at 33⅓; the bright ring runs over them. */
function dotsFace() {
  const W = 2048, H = 32, [c, g] = canvas2d(W, H), row = r => (1 - (r - 312) / 10) * H;
  g.fillStyle = '#000'; g.fillRect(0, 0, W, H); g.fillStyle = '#fff';
  for (let i = 0; i < 180; i++) g.fillRect(i / 180 * W, 1, W / 180 * 0.44, H - 2);
  g.fillStyle = '#000'; g.fillRect(0, row(316.95), W, row(316.15) - row(316.95));
  return tex(c, { srgb: false, aniso: 16 });
}
/** The strobe lamp's orange light on the rim near it (the rim's planar uv1), shown while the motor runs. */
function strobeFace() {
  const N = 512, [c, g] = canvas2d(N, N), x = (0.5 + (152 - REC.x) / 660) * N, y = (0.5 + (634 - REC.z) / 660) * N;
  const gr = g.createRadialGradient(x, y, 0, x, y, N * 0.26);
  gr.addColorStop(0, 'rgba(255,150,60,1)'); gr.addColorStop(0.35, 'rgba(255,140,50,.45)'); gr.addColorStop(1, 'rgba(255,140,50,0)');
  g.fillStyle = gr; g.fillRect(0, 0, N, N);
  return tex(c, { channel: 1 });
}
/** A little dust on the vinyl, where a seeded hand put it: tiny soft specks that turn with the record. */
function dustSpecks(M) {
  const rnd = seeded(31), n = 46, g = new THREE.PlaneGeometry(1, 1); g.rotateX(-Math.PI / 2);
  const im = new THREE.InstancedMesh(g, M.dust, n), o = new THREE.Object3D(), c = new THREE.Color();
  for (let i = 0; i < n; i++) {
    const a = rnd() * TAU, r = 112 + rnd() * 182, s = 0.5 + rnd() * rnd() * 1.4;
    o.position.set(r * Math.cos(a), 26.72, r * Math.sin(a)); o.scale.set(s * 2.6, 1, s * 2.6); o.updateMatrix(); im.setMatrixAt(i, o.matrix);
    im.setColorAt(i, c.setScalar(0.45 + rnd() * 0.5));
  }
  im.renderOrder = 4; im.frustumCulled = false;
  return im;
}
/** The arm from above in its own frame (x along the arm, −140…580; z across, −90…90), soft: for its moving shadow. */
function armSilhouette() {
  const W = 1024, H = 256, [c, g] = canvas2d(W, H), sx = W / 720, sz = H / 180;
  g.setTransform(sx, 0, 0, sz, 140 * sx, 90 * sz);
  g.filter = 'blur(3px)';
  g.fillStyle = '#fff'; g.strokeStyle = '#fff'; g.lineCap = 'round';
  g.beginPath(); g.roundRect ? g.roundRect(-120, -25, 66, 50, 8) : g.rect(-120, -25, 66, 50); g.fill();
  g.fillRect(-58, -6, 60, 12);
  g.lineWidth = 12; g.beginPath(); g.moveTo(22, 0); g.bezierCurveTo(220, 0, 403, -39, 459, -16.5); g.stroke();
  g.save(); g.translate(500, 0); g.rotate(22 * D2R);
  g.beginPath(); g.moveTo(-50, -10); g.lineTo(-40, -12); g.lineTo(10, -13); g.lineTo(12, 13); g.lineTo(-40, 12); g.lineTo(-50, 10); g.closePath(); g.fill();
  g.fillRect(10, -9, 8, 18);                                     // the cartridge's front past the shell
  g.lineWidth = 3.2; g.beginPath(); g.moveTo(9, -12); g.quadraticCurveTo(27, -16, 39, -32); g.stroke();
  g.restore();
  const t = tex(c, { srgb: false, aniso: 4 });
  t.generateMipmaps = true; t.minFilter = THREE.LinearMipmapLinearFilter;
  return t;
}
/** A soft round spot (a glow, a lifted record's shadow). */
function spot(inner = 0.0) {
  const [c, g] = canvas2d(128, 128), gr = g.createRadialGradient(64, 64, 64 * inner, 64, 64, 64);
  gr.addColorStop(0, 'rgba(255,255,255,1)'); gr.addColorStop(0.5, 'rgba(255,255,255,.35)'); gr.addColorStop(1, 'rgba(255,255,255,0)');
  g.fillStyle = gr; g.fillRect(0, 0, 128, 128);
  return tex(c, { srgb: false });
}

/* ───────────────────────────── the record's rings ───────────────────────────── */

/**
 * The vinyl, radius by radius: [roughness, anisotropy]. A groove is finer than a pixel, so what it does to light is here: in
 * a track the grooves stretch the light along the radius (a sheen that stands still while the record turns); where the tracks
 * part (the piece's GAP, at the radius the stylus crosses it) the vinyl is smooth and black like a mirror; then the wide
 * lead-in, the run-out spiral, the locked groove and the dead wax.
 */
function vinylRings() {
  let bands = [];
  // the cut's pitch wanders a little every few millimetres: rings the eye sees at a phone's size
  const wander = r => Math.sin(r * 1.31) * 0.5 + Math.sin(r * 0.47 + 1.7) * 0.35 + Math.sin(r * 2.9 + 0.4) * 0.15;
  const fill = r => {
    let rough = 0.12, an = 0;
    if (r > 298.3) { rough = 0.1; an = 0; }
    else if (r > 291) { rough = 0.09; an = 0.35; }
    else if (r > 128.5 || bands.length === 0) { rough = 0.07; an = 0.1; }
    else if (r > 112) { rough = 0.08; an = 0.4; }
    else if (r > 106.5) { rough = 0.32; an = 0; }
    else if (r > 105.3) { rough = 0.2; an = 0.6; }
    else { rough = 0.1; an = 0; }
    for (const b of bands) if (r <= b.r0 && r >= b.r1) {
      const u = (b.r0 - r) / Math.max(1e-3, b.r0 - b.r1);       // 0 at the track's start (outside) … 1 at its end
      const loud = b.loud(u), w = wander(r);
      rough = 0.2 + 0.07 * loud + 0.03 * w; an = 0.6 + 0.28 * loud + 0.1 * w;
    }
    return [128, 255, Math.round(an * 255), 255, rough];
  };
  const aniso = polar(2048, r => { const f = fill(r); return [f[0], f[1], f[2], 255]; });
  const rough = polar(2048, r => { const f = fill(r); const v = Math.round(f[4] * 255); return [v, v, v, 255]; });
  let key = '';
  return {
    aniso, rough,
    update() {
      const gr = G.grooves(), A = L.ALBUMS[gr.album];
      const k = gr.album + ':' + gr.tracks.map(t => t.r0.toFixed(1) + '-' + t.r1.toFixed(1)).join(',');
      if (k === key) return false; key = k;
      // the gaps at least 3.6 units (1.8 mm) wide, centred where the piece's silent bands are (real gaps are about that)
      const tr = gr.tracks.map((t, i) => ({ r0: t.r0, r1: t.r1, T: A && A.tracks[i] }));
      for (let i = 0; i + 1 < tr.length; i++) {
        const mid = (tr[i].r1 + tr[i + 1].r0) / 2, half = Math.max(1.8, (tr[i].r1 - tr[i + 1].r0) / 2);
        tr[i].r1 = Math.max(tr[i].r1, mid + half); tr[i + 1].r0 = Math.min(tr[i + 1].r0, mid - half);
      }
      bands = tr.map((t, i) => {
        // how loudly each part is cut: the synthesizer's tracks by their bars (a soft first and last two bars, a three-bar rest
        // at 60 %), a file by a seeded wander (its loudness is not known)
        const T = t.T || {}, bars = T.bars || 32, rnd = seeded(101 + i * 7919 + (gr.album | 0) * 31);
        const ph = [rnd() * TAU, rnd() * TAU, rnd() * TAU];
        const loud = gr.mine || !T.bars
          ? u => 0.55 + 0.25 * Math.sin(u * 9.1 + ph[0]) * Math.sin(u * 3.3 + ph[1]) + 0.15 * Math.sin(u * 23 + ph[2])
          : u => { const b = u * bars; return b < 2 || b > bars - 2 ? 0.35 : (b > bars * 0.6 && b < bars * 0.6 + 3 ? 0.15 : 0.75 + 0.15 * Math.sin(b * 0.9 + ph[0])); };
        return { r0: t.r0, r1: t.r1, loud };
      });
      aniso.fill(); rough.fill();
      return true;
    }
  };
}

/* ───────────────────────────── the label ───────────────────────────── */

/** The piece's own label (its SVG group and the arcs its titles run on) as a picture; 내 음악's cover drawn in its circle. */
async function labelPicture(N = 1024) {
  const lb = G.label(); if (!lb || !lb.el || !lb.svg) return null;
  const defs = lb.svg.querySelector('defs'), g = lb.el.cloneNode(true);
  g.removeAttribute('style'); g.style.display = '';
  for (const im of g.querySelectorAll('image')) im.remove();                 // a picture inside an SVG image cannot load: drawn below
  const xml = '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="-100 -100 200 200" width="' + N + '" height="' + N + '">'
    + (defs ? new XMLSerializer().serializeToString(defs) : '') + new XMLSerializer().serializeToString(g) + '</svg>';
  const img = new Image(); img.decoding = 'async';
  img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(xml);
  await img.decode();
  const [c, ctx] = canvas2d(N, N);
  ctx.drawImage(img, 0, 0, N, N);
  if (lb.art) {
    try {
      const a = new Image(); a.src = lb.art; await a.decode();
      const s = N / 200, side = Math.min(a.naturalWidth, a.naturalHeight);
      ctx.save(); ctx.beginPath(); ctx.arc(N / 2, N / 2 - 26 * s, 25 * s, 0, TAU); ctx.clip();
      ctx.drawImage(a, (a.naturalWidth - side) / 2, (a.naturalHeight - side) / 2, side, side, N / 2 - 25 * s, N / 2 - 51 * s, 50 * s, 50 * s);
      ctx.restore();
    } catch (e) { /* the note shows instead */ }
  }
  // the paper: a faint fibre and a little sheen toward the light
  const rnd = seeded(5);
  ctx.globalCompositeOperation = 'overlay';
  for (let i = 0; i < 2400; i++) { const x = rnd() * N, y = rnd() * N; ctx.fillStyle = rnd() < 0.5 ? 'rgba(255,255,255,.05)' : 'rgba(0,0,0,.05)'; ctx.fillRect(x, y, 1 + rnd() * 3, 1); }
  ctx.globalCompositeOperation = 'source-over';
  return { canvas: c, key: lb.key };
}

/* ───────────────────────────── the scene ───────────────────────────── */

function room(renderer) {
  // The listening room around the deck, in metres from the record: what lacquer, brass, chrome and vinyl reflect.
  const s = new THREE.Scene(), lit = (c, k) => new THREE.MeshBasicMaterial({ color: new THREE.Color(c).multiplyScalar(k), side: THREE.DoubleSide });
  const plane = (w, h, m, x, y, z, rx, ry) => { const p = new THREE.Mesh(new THREE.PlaneGeometry(w, h), m); p.position.set(x, y, z); p.rotation.set(rx || 0, ry || 0, 0); s.add(p); return p; };
  plane(8, 8, lit(0x2e3034, 0.22), 0, 2.4, 0, Math.PI / 2);            // ceiling, a neutral dark grey
  plane(8, 8, lit(0x2a1c12, 0.1), 0, -0.9, 0, -Math.PI / 2);            // floor
  plane(8, 4, lit(0x1f3b2d, 0.22), 0, 0.8, -4, 0);                       // felt walls (the piece's #1f3b2d)
  plane(8, 4, lit(0x1f3b2d, 0.18), 0, 0.8, 4, 0);
  plane(8, 4, lit(0x1f3b2d, 0.2), -4, 0.8, 0, 0, Math.PI / 2);
  plane(8, 4, lit(0x1f3b2d, 0.16), 4, 0.8, 0, 0, Math.PI / 2);
  // the ceiling's light panel: a crisp-edged rectangle with a frame across it (in the lacquer a reflection reads as one only
  // when it has a shape: a soft round glow there looked like faded wood)
  const [c, g] = canvas2d(256, 128);
  g.fillStyle = '#000'; g.fillRect(0, 0, 256, 128);
  const gr = g.createLinearGradient(0, 0, 256, 0); gr.addColorStop(0, '#d8d8d8'); gr.addColorStop(0.5, '#fff'); gr.addColorStop(1, '#d8d8d8');
  g.fillStyle = gr; g.fillRect(6, 6, 244, 116);
  g.fillStyle = '#000'; g.fillRect(126, 6, 4, 116);                          // the frame's bar across it
  const shaped = new THREE.CanvasTexture(c);
  const panel = (k, c, map) => new THREE.MeshBasicMaterial({ color: new THREE.Color(c).multiplyScalar(k), map, side: THREE.DoubleSide });
  plane(1.05, 0.5, panel(3.2, 0xf7f5f2, shaped), -0.72, 2.38, -0.55, Math.PI / 2); // a ceiling panel up and to the left: the lacquer's sheen
  plane(1.2, 0.9, lit(0xffe9d2, 3.4), -1.35, 2.0, -1.15, Math.PI / 2);        // the lamp's shade, off to the side: curved parts catch it
  plane(1.6, 1.3, lit(0xd6e0ff, 2.6), 3.98, 1.25, -0.6, 0, -Math.PI / 2);   // a cool window, right
  plane(1.0, 0.6, lit(0xfff0e0, 2.0), 0.9, 2.38, 1.3, Math.PI / 2);        // a second, small panel behind
  plane(1.4, 0.8, lit(0xf2f0ec, 1.3), 1.25, 1.5, 1.6, Math.PI / 2);        // the far side's light: the vinyl's second sheen
  const bulb = new THREE.Mesh(new THREE.SphereGeometry(0.07, 12, 8), lit(0xffe6c8, 40)); bulb.position.set(-1.7, 1.45, -1.3); s.add(bulb);
  const pm = new THREE.PMREMGenerator(renderer);
  const env = pm.fromScene(s, 0.02, 0.05, 20).texture;
  pm.dispose(); shaped.dispose(); s.traverse(o => { if (o.geometry) o.geometry.dispose(); if (o.material) o.material.dispose(); });
  return env;
}

async function load(url) {
  const t = await new THREE.TextureLoader().loadAsync(url);
  t.anisotropy = 8; return t;
}

function materials(T) {
  const P = (o) => new THREE.MeshPhysicalMaterial(o);
  const alu = (o = {}) => P(Object.assign({ color: 0xd2d5d9, metalness: 1, roughness: 0.3, anisotropy: 0.7, anisotropyRotation: Math.PI / 2 }, o));
  const brass = (o = {}) => P(Object.assign({ color: 0xc39a42, metalness: 1, roughness: 0.32, anisotropy: 0.45, anisotropyRotation: Math.PI / 2 }, o));
  return {
    // walnut under lacquer (GRAPHICS.md's numbers: clearcoat 0.55, its roughness 0.16); the room lights it at half, so the
    // lamp's pool and the panel's shaped reflection read, not a haze over the grain; the scan a touch warmer (saturation 0.4
    // in the lamp's pool to 0.6 in the shade, as the piece's own CSS walnut: 0.47 to 0.63)
    wood: P({ map: T.col, color: OPT.test && OPT.woodTint != null ? OPT.woodTint : 0xfff0dd, normalMap: T.nor, normalScale: new THREE.Vector2(0.55, 0.55), roughnessMap: T.rgh, roughness: 0.85, metalness: 0,
      clearcoat: 0.55, clearcoatRoughness: 0.16, specularIntensity: 0.6, envMapIntensity: 0.5 }),
    print: P({ map: T.print, transparent: true, depthWrite: false, metalness: 0.35, roughness: 0.45, polygonOffset: true, polygonOffsetFactor: -2, polygonOffsetUnits: -2 }),
    shade: new THREE.MeshBasicMaterial({ map: T.shade, transparent: true, depthWrite: false, polygonOffset: true, polygonOffsetFactor: -3, polygonOffsetUnits: -3 }),
    satin: P({ color: 0x17120e, roughness: 0.5, metalness: 0, clearcoat: 0.6, clearcoatRoughness: 0.2 }),
    well: P({ color: 0x0b0806, roughness: 0.8 }),
    alu: alu(), turned: alu({ roughness: 0.24, anisotropy: 0.85 }), turnedL: alu({ roughness: 0.24, anisotropy: 0.85 }),
    ghost: new THREE.MeshBasicMaterial({ colorWrite: false, depthWrite: false }),
    brass: brass(), brassS: brass(), hinge: brass({ roughness: 0.2, anisotropy: 0.3 }),
    plate: P({ map: T.plate, metalnessMap: T.plateMR, roughnessMap: T.plateMR, metalness: 1, roughness: 1, anisotropy: 0.5, anisotropyRotation: Math.PI / 2 }),
    // (the spindle's chrome a little satin: a mirror there showed the dark room, a black bead, not steel)
    chrome: P({ color: 0xf3f5f8, metalness: 1, roughness: 0.07 }), chromeS: P({ color: 0xf6f7f9, metalness: 1, roughness: 0.3 }),
    tube: P({ color: 0xe4e7eb, metalness: 1, roughness: 0.12, anisotropy: 0.3 }),
    anod: P({ color: 0x2a2b30, metalness: 0.85, roughness: 0.32 }), anodS: P({ color: 0x2a2b30, metalness: 0.85, roughness: 0.32 }),
    // the headshell brushed aluminium (its edges catch the lamp), the cartridge a dark grey body with a gloss of its own
    // (a flat metal top seen from straight above mirrors the dark ceiling over it: satin enough to gather the lamp and the panel)
    shell: alu({ color: 0xdfe2e6, roughness: 0.48, anisotropy: 0.5, anisotropyRotation: 0 }),
    cart: P({ color: 0x4a4e56, roughness: 0.4, metalness: 0.15, clearcoat: 0.8, clearcoatRoughness: 0.15 }),
    slot: new THREE.MeshBasicMaterial({ color: 0x0c0c0e }),
    plastic: P({ color: 0x0e0e10, roughness: 0.42, clearcoat: 0.3, clearcoatRoughness: 0.3 }),
    key: P({ map: T.key, roughness: 0.42, metalness: 0, clearcoat: 0.35, clearcoatRoughness: 0.3 }),
    rubber: P({ color: 0x141415, roughness: 0.86, sheen: 0.4, sheenRoughness: 0.6, sheenColor: 0x404040 }),
    rim: P({ map: T.rim, metalnessMap: T.rimMR, roughnessMap: T.rimMR, metalness: 1, roughness: 1, anisotropy: 0.8, anisotropyRotation: Math.PI / 2 }),
    mat: P({ color: 0x161617, roughness: 0.82, normalMap: T.matN, normalScale: new THREE.Vector2(0.7, 0.7), sheen: 0.35, sheenRoughness: 0.5, sheenColor: 0x3a3a3a }),
    vinyl: P({ color: 0x101012, roughness: 1, roughnessMap: T.rings.rough, anisotropy: 1, anisotropyMap: T.rings.aniso, ior: 1.54,
      specularIntensity: 1, envMapIntensity: 0.8, transparent: true }),
    label: P({ map: T.label, roughness: 0.62, clearcoat: 0.18, clearcoatRoughness: 0.35, transparent: true }),
    dots: new THREE.MeshBasicMaterial({ color: 0x1c1c1f, alphaMap: T.dots, transparent: true, depthWrite: false }),
    dust: new THREE.MeshBasicMaterial({ map: spot(), color: 0xc8c4bc, transparent: true, depthWrite: false, opacity: 0.7 }),
    strobe: new THREE.MeshBasicMaterial({ map: T.strobe, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, opacity: 0 }),
    // the strobe lamp's glass: a dark amber dome with a clear gloss (a highlight of the room on it); lit, it glows from inside
    lens: P({ color: 0x5a3410, roughness: 0.12, clearcoat: 1, clearcoatRoughness: 0.04, transparent: true, opacity: 0.78, emissive: 0x000000 }),
    led: new THREE.MeshBasicMaterial({ color: 0x3a2508 }), keyLed: new THREE.MeshBasicMaterial({ color: 0x5a3b16 }),
    led45: P({ color: 0x3a2508, roughness: 0.2, clearcoat: 1 })
  };
}

/** The mat's ribs: one every 5 units (2.5 mm), as normals across the radius. */
function matRibs() {
  return polar(1024, r => { const s = Math.sin(r / 5 * TAU) * (r > 30 && r < 292 ? 0.55 : 0); return [128, Math.round(128 + 127 * s), Math.round(128 + 127 * Math.sqrt(1 - s * s)), 255]; });
}

/**
 * The deck in three groups. `still`: what never moves and what looks the same however it turns (the plinth and its parts, the
 * platter, mat and spindle, the gimbal), drawn into the bake once. `vinyl`: the record's body; it too looks the same at any
 * turn (its sheen stands still while it spins), so it is baked as well, into a second picture, and drawn live only while the
 * record flies out or in. `live`: what changes as it moves, drawn each frame: the label and the dust turning with the record,
 * the strobe dots, the arm, the cue lever, the lamps.
 */
function build(M) {
  const still = new THREE.Group(), live = new THREE.Group(), add = (grp, g, m, o = {}) => { const x = new THREE.Mesh(g, m); x.castShadow = o.cast !== false; x.receiveShadow = o.recv !== false; if (o.order) x.renderOrder = o.order; grp.add(x); return x; };
  const turned = (prof, seg, R, x, z) => at(ring(prof, seg, { R }), x, 0, z);

  /* the plinth: walnut under lacquer, rounded along its top edge, the platter's well cut in it */
  const sh = rrect(new THREE.Shape(), 6, 6, 988, 768, 22), hole = new THREE.Path(); hole.absarc(REC.x, REC.z, 344, 0, TAU, true); sh.holes.push(hole);
  const pg = new THREE.ExtrudeGeometry(sh, { depth: 48, bevelEnabled: true, bevelThickness: 6, bevelSize: 6, bevelSegments: 5, curveSegments: 96 });
  pg.rotateX(Math.PI / 2); pg.translate(0, -6, 0); planar(pg, 0, 0, 1000, 780);
  add(still, pg, M.wood, { cast: false });
  const floor = new THREE.CircleGeometry(345, 96); floor.rotateX(-Math.PI / 2); add(still, at(floor, REC.x, -14, REC.z), M.well, { cast: false });
  // the print and the soft shade, just over the wood
  const pr = new THREE.PlaneGeometry(1000, 780); pr.rotateX(-Math.PI / 2); add(still, at(pr, 500, 1.15, 390), M.print, { cast: false, order: 2 });
  const sd = new THREE.PlaneGeometry(1000, 780); sd.rotateX(-Math.PI / 2); add(still, at(sd, 500, 1.2, 390), M.shade, { cast: false, recv: false, order: 3 });

  /* hinges on the back edge: a leaf screwed to the plinth and a barrel of three knuckles on its pin (round: the lamp draws a
     bright line along it, the knuckles' gaps dark) */
  for (const x of [198, 802]) {
    add(still, slab(x, 15, 92, 12, 0, 1.8, 2.4, 0.6), M.hinge);
    const parts = [];
    for (const [k0, k1] of [[-46, -16.5], [-15, 15], [16.5, 46]]) {
      const b = new THREE.CylinderGeometry(5.6, 5.6, k1 - k0, 28, 1, false); b.rotateZ(Math.PI / 2); parts.push(at(b, x + (k0 + k1) / 2, 5.6, 6.5));
    }
    for (const sx of [-26, 26]) parts.push(at(ring([[0, 2.3], [1.6, 2.2], [2.6, 1.6], [2.9, 0.6]], 16, { R: 3 }), x + sx, 0, 16));
    add(still, merge(parts), M.hinge);
    const pin = new THREE.CylinderGeometry(2.2, 2.2, 95, 12); pin.rotateZ(Math.PI / 2); add(still, at(pin, x, 5.6, 6.5), M.well, { cast: false });
    for (const sx of [-26, 26]) add(still, at(new THREE.BoxGeometry(4.4, 0.5, 0.7), x + sx, 2.4, 16), M.slot, { cast: false });
  }
  /* the tonearm board: a satin black disc in a raised brass ring, the turned post, the gimbal on it */
  add(still, turned([[0, 1], [67.6, 1], [68.4, 0.6], [68.6, 0]], 128, 70, PIV.x, PIV.z), M.satin, { cast: false });
  add(still, turned([[67.8, 0.2], [68.2, 1.9], [69.2, 2.9], [70, 3.1], [70.8, 2.9], [71.8, 1.9], [72.2, 0.2]], 160, 72, PIV.x, PIV.z), M.brassS);
  add(still, turned([[0, 26], [36.6, 26], [36.6, 26], [38.8, 25.3], [39.8, 23.6], [40, 22], [40, 22], [40, 3.4], [40.6, 2.2], [42.4, 1.3], [44, 1]], 128, 40, PIV.x, PIV.z), M.turned);
  // (the gimbal on it is drawn with the arm, whose tube goes into it; here it only casts its shadow)
  add(still, at(new THREE.CylinderGeometry(30, 30, 38, 48), PIV.x, 45, PIV.z), M.ghost, { recv: false });
  /* anti-skate: a turned knob with its mark */
  add(still, turned([[0, 13], [14, 13], [14, 13], [16.6, 12.4], [17.8, 11], [18, 9.8], [18, 9.8], [18, 2.2], [18.8, 1.2], [20.5, 0.4]], 96, 18, 944, 150), M.turned);
  add(still, slab(944, 140, 2.2, 8, 12.9, 13.6, 1, 0.3), M.anodS, { cast: false });
  /* the arm rest: a turned post with a rubber saddle */
  add(still, turned([[0, 32], [14.6, 32], [16.4, 31], [17, 29], [17, 29], [17, 2.4], [17.8, 1.3], [20.5, 0.4]], 96, 17, 873, 561), M.turned);
  add(still, slab(873, 561, 30, 11, 31, 41.6, 4, 2.2), M.rubber);
  /* cue: the slot the lever stands in */
  add(still, slab(944, 252, 18, 60, 0, 1.6, 9, 0.6), M.satin);
  /* pitch: the slot and its brass slider */
  add(still, slab(952, 485, 12, 230, 0, 1.4, 6, 0.5), M.satin);
  add(still, slab(952, 474, 34, 22, 0, 7.5, 3, 1.4), M.brassS);
  add(still, slab(952, 474, 24, 1.6, 7.3, 7.9, 0.6, 0.2), M.anodS, { cast: false });
  /* the strobe lamp: a round satin base, a turned brass bezel with a lip round its glass (the glass is drawn live), the LEDs'
     bezels, the 45 LED (never lit) */
  add(still, turned([[0, 4.2], [14.8, 4.2], [15.6, 3.6], [16, 2.2], [16.4, 0]], 64, 16, 152, 634), M.satin);
  add(still, turned([[8.4, 6.2], [8.9, 7.6], [9.8, 8.4], [11.2, 8.6], [12.4, 8.1], [13.1, 6.9], [13.4, 4.2]], 64, 14, 152, 634), M.brassS);
  add(still, turned([[0, 6.0], [8.6, 6.0]], 48, 9, 152, 634), M.well, { cast: false });
  for (const x of [214, 287]) add(still, turned([[3.6, 2.4], [4.4, 2.6], [5.4, 2.4], [6.2, 1.4], [6.5, 0]], 48, 6.5, x, 727), M.brassS);
  add(still, turned([[0, 3.6], [2.4, 3.3], [3.6, 2.4], [3.9, 1]], 24, 4, 287, 727), M.led45, { cast: false });
  /* the nameplate: brass with a chamfered edge and the name in black enamel */
  const np = slab(726, 750, 232, 28, 0, 2.8, 3, 1.2); planar(np, 610, 736, 232, 28); add(still, np, M.plate);
  /* the platter (turned aluminium, its rim), the mat's ribs in it, the spindle: the same at any turn */
  add(still, at(ring([[280, 22], [296.4, 22], [296.4, 22], [297.6, 22.6], [299, 23.6], [300.4, 24], [300.4, 24], [325.6, 24], [325.6, 24], [328.2, 23.5],
    [329.6, 22.2], [330, 20.4], [330, 20.4], [330, -10]], 256, { v: r => Math.max(0, Math.min(1, (r - 296) / 34)) }), REC.x, 0, REC.z), M.rim);
  add(still, turned([[7.4, 23.2], [7.4, 24], [7.4, 24], [292, 24], [295.6, 23.6], [297.6, 22.6], [298.2, 22]], 192, 300, REC.x, REC.z), M.mat, { cast: false });
  add(still, turned([[0, 39.6], [2.6, 39.35], [4.8, 38.5], [6.2, 37.1], [6.85, 35.4], [6.85, 35.4], [6.85, 22]], 48, 7, REC.x, REC.z), M.chromeS);

  /* the record's body: baked with the record home (the second bake), and the same body flying when it changes */
  const vprof = [[99.4, 26.95], [104.2, 26.95], [106.8, 26.82], [108.8, 26.6], [108.8, 26.6], [296.4, 26.6], [297.4, 26.78], [298.4, 27.2],
    [299.2, 27.38], [299.8, 27.15], [300, 26.4], [300, 25.4], [299.7, 24.5], [299, 24.05]];
  const vg = ring(vprof, 256);
  const vinylHome = new THREE.Mesh(at(vg.clone(), REC.x, 0, REC.z), M.vinyl); vinylHome.visible = false;

  /* ── what moves ── */
  const disc = new THREE.Group(); disc.position.set(REC.x, 0, REC.z); live.add(disc);
  const vinyl = add(disc, vg, M.vinyl, { order: 2 }); vinyl.visible = false;
  const label = add(disc, ring([[7.25, 26.3], [7.25, Y.label], [7.25, Y.label], [98.8, Y.label], [99.6, 26.98], [100, 26.82]], 192, { R: 100 }), M.label, { order: 3 });
  const dust = dustSpecks(M); disc.add(dust);
  // the strobe dots on the rim, as the strobe lamp shows them; its orange light on the rim while the motor runs
  const dots = add(live, at(ring([[312, 24.06], [322, 24.06]], 256, { v: r => (r - 312) / 10 }), 0, 0, 0), M.dots, { order: 1 }); dots.position.set(REC.x, 0, REC.z);
  const glowRim = add(live, at(ring([[300.5, 24.1], [329, 24.1]], 192, { R: 330 }), REC.x, 0, REC.z), M.strobe, { order: 7 });

  // the arm: its angle about the post, its lift about the bearing (y = 48)
  const yaw = new THREE.Group(); yaw.position.set(PIV.x, 0, PIV.z); live.add(yaw);
  const liftG = new THREE.Group(); liftG.position.y = Y.arm; yaw.add(liftG);
  // the gimbal: a turned housing, its brass cap and the bearing's screw (the same at any angle, so it does not turn with the arm)
  const gimbal = at(merge([ring([[16.4, 58.2], [25.4, 58.2], [25.4, 58.2], [28.4, 57.2], [29.7, 55], [30, 52.5], [30, 52.5], [30, 30.5], [29.4, 28], [27.6, 26.3], [24, 26]], 96, { R: 30 })]), PIV.x, 0, PIV.z);
  add(live, gimbal, M.turnedL);
  add(live, turned([[0, 63], [8, 62.7], [13.6, 61.8], [16.4, 60.2], [17.2, 58.4], [17.2, 57.6]], 64, 17, PIV.x, PIV.z), M.brass);
  add(live, turned([[0, 63.7], [3.4, 63.5], [4.8, 63.1], [5.2, 62.6]], 24, 5, PIV.x, PIV.z), M.anod);
  const along = (prof, seg, R) => { const g = ring(prof, seg, { R }); g.rotateZ(-Math.PI / 2); return g; };  // a turned part along the arm (its y → x)
  // the tube, along the piece's own curve, with its collars
  const curve = new THREE.CubicBezierCurve3(new THREE.Vector3(22, 0, 0), new THREE.Vector3(220, 0, 0), new THREE.Vector3(403, 0, -39), new THREE.Vector3(459, 0, -16.5));
  const tubeParts = [new THREE.TubeGeometry(curve, 120, 6, 20, false), along([[5, 18], [7.6, 18.4], [8, 20], [8, 30], [7.6, 31.6], [6, 32]], 32, 8), along([[5, -58], [6, -58], [6, 4], [5, 4]], 24, 6)];
  const end = curve.getPoint(1), dir = curve.getTangent(1), col = along([[4, -9], [7.2, -8.6], [7.6, -6], [7.6, 1], [7, 2.4], [5, 2.6]], 32, 8);
  col.applyMatrix4(new THREE.Matrix4().makeRotationY(-Math.atan2(dir.z, dir.x))); col.translate(end.x, end.y, end.z); tubeParts.push(col);
  add(liftG, merge(tubeParts), M.tube);
  // the counterweight: turned brass with its five grooves, a dark gap before its fine-adjust ring
  const cw = [[6, -122], [19, -122], [23.6, -121.2], [25, -119], [25, -119]];
  for (const x of [-112, -106, -100, -94, -88]) cw.push([25, x - 1.4], [23.4, x - 0.5], [23.4, x + 0.5], [25, x + 1.4]);
  cw.push([25, -74], [24.4, -72.6], [20.5, -72.4], [20.5, -68.6], [24.4, -68.4], [25, -67], [25, -57], [23.6, -54.8], [19, -54], [6, -54]);
  const brassParts = [along(cw, 64, 25)];
  // the headshell (the piece's outline, 22° to the tube), the cartridge, its screws, the stylus on the groove, the finger lift
  const hy = -Y.arm, headM = new THREE.Matrix4().makeRotationY(-22 * D2R).setPosition(500, 0, 0), toHead = g => g.applyMatrix4(headM);
  // the shell: brushed aluminium with a brass edge, a brass plate on its top, two slotted screws through it; under it the
  // cartridge, a dark grey body whose front shows past the shell with the stylus guard's lip
  const hs = new THREE.Shape(); hs.moveTo(-50, -10); hs.lineTo(-40, -12); hs.lineTo(10, -13); hs.lineTo(12, 13); hs.lineTo(-40, 12); hs.lineTo(-50, 10); hs.closePath();
  add(liftG, toHead(lift(hs, hy + 43, hy + 47.2, 1.3, 6)), M.shell);
  const trim = new THREE.Shape(); trim.moveTo(-40, -12.6); trim.lineTo(10, -13.6); trim.lineTo(10, -11.6); trim.lineTo(-40, -10.8); trim.closePath();
  brassParts.push(toHead(lift(trim, hy + 46.6, hy + 47.8, 0.3, 2)), toHead(slab(-28.5, 0, 5, 19.4, hy + 31.2, hy + 43.4, 1, 0.6)),
    toHead(slab(-38, 0, 12, 15, hy + 47, hy + 48.1, 1.6, 0.4)));
  add(liftG, merge(brassParts), M.brass);
  add(liftG, toHead(slab(-8, 0, 44, 18, hy + 31.4, hy + 43.2, 3, 1.8)), M.cart);
  add(liftG, toHead(slab(15.2, 0, 3.4, 14, hy + 30.6, hy + 36.4, 1.2, 0.6)), M.cart);
  const screw = z => at(ring([[0, 1.5], [1.3, 1.42], [2.1, 0.9], [2.4, 0]], 16, { R: 2.4 }), -19, hy + 47.2, z);
  const cant = new THREE.CylinderGeometry(0.45, 0.7, 6.4, 8); cant.rotateZ(-0.9); cant.translate(-1.4, hy + Y.groove + 2.6, 0);
  const tip = new THREE.ConeGeometry(0.7, 1.4, 8); tip.rotateX(Math.PI); tip.translate(0, hy + Y.groove + 0.7, 0);
  const lift3 = new THREE.QuadraticBezierCurve3(new THREE.Vector3(9, hy + 45.6, -12), new THREE.Vector3(27, hy + 46.6, -16), new THREE.Vector3(39, hy + 50, -32));
  add(liftG, toHead(merge([screw(-5.5), screw(5.5), cant, tip, new THREE.TubeGeometry(lift3, 24, 1.6, 10, false)])), M.chrome);
  // the cue lever: brass in its slot, tipping up as the arm lifts
  const cue = new THREE.Group(); cue.position.set(944, 1.6, 256); live.add(cue);
  add(cue, merge([slab(0, -13, 7, 29, 0, 4.6, 3.5, 1.2), at(ring([[0, 3.2], [5.2, 3.1], [7.4, 2.2], [8, 0], [7.4, -2.2], [5.2, -3.1], [0, -3.2]], 32, { R: 8 }), 0, 6, -26)]), M.brass);
  // what glows: the strobe lamp (its glass dome lit from inside) and its light on the plinth, the 33⅓ LED
  const lens = add(live, turned([[0, 9.6], [2.6, 9.45], [5, 8.95], [7, 8.0], [8.3, 6.9], [8.6, 6.1]], 48, 9, 152, 634), M.lens, { cast: false, recv: false, order: 9 });
  const led = add(live, turned([[0, 3.6], [2.4, 3.3], [3.6, 2.4], [3.9, 1]], 24, 4, 214, 727), M.led, { cast: false, recv: false });
  // the start · stop key (the piece's own button lies see-through over it and takes the press): satin silver and its words,
  // baked with the plinth; its LED lit with the motor
  const KEY = { x: 48, z: 688, w: 136, d: 51.5 };
  const kg = slab(KEY.x + KEY.w / 2, KEY.z + KEY.d / 2, KEY.w, KEY.d, 0, 5.2, 4, 1.5); planar(kg, KEY.x, KEY.z, KEY.w, KEY.d);
  add(still, kg, M.key, { cast: false });
  const ledX = KEY.x + KEY.w * (M.key.map && M.key.map.ledAt || 0.2);
  add(live, at(ring([[0, 1.3], [2.4, 1.2], [3.6, 0.7], [4, 0]], 20, { R: 4 }), ledX, 5.2, KEY.z + KEY.d / 2), M.keyLed, { cast: false, recv: false });
  const glowM = new THREE.MeshBasicMaterial({ map: spot(), color: 0xff9a3c, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, opacity: 0 });
  const glowG = new THREE.PlaneGeometry(1, 1); glowG.rotateX(-Math.PI / 2);
  const glow = new THREE.Mesh(glowG, glowM); glow.position.set(152, 10, 634); glow.scale.set(120, 1, 110); glow.renderOrder = 20; live.add(glow);
  const glow2 = new THREE.Mesh(glowG, glowM.clone()); glow2.position.set(214, 4, 727); glow2.scale.set(26, 1, 26); glow2.renderOrder = 20; live.add(glow2);
  const glow3 = new THREE.Mesh(glowG, glowM.clone()); glow3.position.set(ledX, 7, KEY.z + KEY.d / 2); glow3.scale.set(22, 1, 22); glow3.renderOrder = 20; live.add(glow3);
  return { still, live, vinylHome, disc, vinyl, label, dust, dots, glowRim, yaw, liftG, cue, lens, led, glow, glow2, glow3 };
}

/** The arm's moving shadow: for each point near the arm, how high it is (record, platter, plinth) says how far the arm's shadow falls. */
function armShadow(sil) {
  const m = new THREE.ShaderMaterial({
    uniforms: { sil: { value: sil }, piv: { value: new THREE.Vector2(PIV.x, PIV.z) }, cs: { value: new THREE.Vector2(0, 1) }, tilt: { value: 0 },
      lamp: { value: LAMP.clone() }, rec: { value: new THREE.Vector4(REC.x, REC.z, 1, 0) }, k: { value: 0.58 } },
    vertexShader: 'varying vec3 vW; void main(){ vec4 w = modelMatrix * vec4(position, 1.0); vW = w.xyz; gl_Position = projectionMatrix * viewMatrix * w; }',
    fragmentShader: [
      'uniform sampler2D sil; uniform vec2 piv, cs; uniform vec3 lamp; uniform float tilt, k; uniform vec4 rec; varying vec3 vW;',
      'vec2 toArm(vec2 p){ vec2 d = p - piv; return vec2(d.x * cs.x + d.y * cs.y, -d.x * cs.y + d.y * cs.x); }',
      'void main(){',
      '  vec2 p = vW.xz; float rr = length(p - rec.xy);',
      '  float hr = rr < 300.0 && rec.z > 0.5 ? 26.8 + rec.w : (rr < 330.0 ? 24.0 : (length(p - piv) < 44.0 ? 26.0 : 1.0));',
      '  vec3 d = lamp - vec3(p.x, hr, p.y); vec2 off = d.xz / d.y;',
      '  vec2 a = toArm(p + off * (48.0 - hr));',
      '  float h = 48.0 + a.x * tilt - hr;',
      '  a = toArm(p + off * h);',
      '  vec2 uv = vec2((a.x + 140.0) / 720.0, 1.0 - (a.y + 90.0) / 180.0);',
      '  if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) discard;',
      '  float s = textureLod(sil, uv, clamp(log2(max(1.0, h * 0.16)), 0.0, 3.5)).r;',
      '  gl_FragColor = vec4(0.0, 0.0, 0.0, s * k);',
      '}'].join('\n'),
    transparent: true, depthWrite: false, depthTest: true, toneMapped: false
  });
  const g = new THREE.PlaneGeometry(1, 1); g.rotateX(-Math.PI / 2);
  const q = new THREE.Mesh(g, m); q.scale.set(770, 1, 230); q.renderOrder = 6; q.frustumCulled = false;
  return q;
}

/* ───────────────────────────── running it ───────────────────────────── */

function summary() {
  const s = stats, w = s.work.slice().sort((a, b) => a - b), g = s.gaps.slice().sort((a, b) => a - b);
  const mean = a => a.length ? a.reduce((x, y) => x + y, 0) / a.length : 0, p95 = a => a.length ? a[Math.min(a.length - 1, Math.floor(a.length * 0.95))] : 0;
  return { css: s.css, frames: s.frames, avg: +mean(w).toFixed(2), p95: +p95(w).toFixed(2), gapAvg: +mean(g).toFixed(2), gapP95: +p95(g).toFixed(2),
    fps: g.length ? +(1000 / mean(g)).toFixed(1) : 0, gpu: s.gpu.length ? +mean(s.gpu).toFixed(2) : null, pr: s.pr, w: s.w, h: s.h, calls: s.calls, triangles: s.triangles, programs: s.programs, prep: s.prep };
}
/**
 * Khronos PBR Neutral (the engine's tone mapping: it keeps a paint's colour where ACES drains it), with its toe a fraction as
 * deep. Neutral takes nearly all of the darkest channel out of a dark colour (meant to take the 4 % white sheen off a lit
 * one): in the lamp's shade the walnut's blue went to nothing and the wood read as orange paint (saturation 0.85 where the
 * scan has 0.5). Its highlight roll-off is unchanged.
 */
const TOE = 0.5;
function softNeutral(toe) {
  const C = THREE.ShaderChunk, from = 'vec3 CustomToneMapping( vec3 color ) { return color; }';
  if (C.tonemapping_pars_fragment.includes(from)) C.tonemapping_pars_fragment = C.tonemapping_pars_fragment.replace(from, [
    'vec3 CustomToneMapping( vec3 color ) {',
    '  const float StartCompression = 0.8 - 0.04; const float Desaturation = 0.15;',
    '  color *= toneMappingExposure;',
    '  float x = min( color.r, min( color.g, color.b ) );',
    '  color -= ( x < 0.08 ? x - 6.25 * x * x : 0.04 ) * ' + Number(toe).toFixed(3) + ';',
    '  float peak = max( color.r, max( color.g, color.b ) );',
    '  if ( peak < StartCompression ) return color;',
    '  float d = 1. - StartCompression;',
    '  float newPeak = 1. - d * d / ( peak + d - StartCompression );',
    '  color *= newPeak / peak;',
    '  float g = 1. - 1. / ( Desaturation * ( peak - newPeak ) + 1. );',
    '  return mix( color, vec3( newPeak ), g );',
    '}'].join('\n'));
  return THREE.CustomToneMapping;
}

/**
 * Everything the deck holds on the GPU and in the page, so that giving up lets all of it go: the scenes' geometries,
 * materials and pictures, the bakes, the room's light, the observers, the renderer, and the context itself (lost on purpose:
 * a phone too slow for the deck gets its memory back at once, not when the page goes).
 */
function release(o) {
  const tex = t => { try { if (t && t.isTexture) t.dispose(); } catch (e) {} };
  try {
    for (const sc of o.scenes) if (sc) sc.traverse(x => {
      if (x.geometry) x.geometry.dispose();
      for (const m of x.material ? [].concat(x.material) : []) {
        for (const k of Object.keys(m)) tex(m[k]);
        if (m.uniforms) for (const u of Object.values(m.uniforms)) tex(u && u.value);
        m.dispose();
      }
    });
  } catch (e) {}
  for (const t of o.textures) tex(t);
  for (const rt of o.targets()) { try { rt && rt.dispose(); } catch (e) {} }
  tex(o.env);
  for (const ob of o.observers) { try { ob.disconnect(); } catch (e) {} }
  try { o.R && o.R.dispose(); } catch (e) {}
  try { const lc = o.gl && !o.gl.isContextLost() && o.gl.getExtension('WEBGL_lose_context'); if (lc) lc.loseContext(); } catch (e) {}
  try { o.cv && o.cv.remove(); } catch (e) {}
  o.scenes.length = 0; o.textures.length = 0; o.observers.length = 0; o.R = null; o.env = null;
}
function fallback(why, o) {
  stats.css = api.css = why;
  try { deck.classList.remove('gy3d'); } catch (e) {}
  if (o) release(o);
  for (const k of ['stylus', 'probe', 'bench', 'bakeMs', 'state', 'off', 'lost', '_']) delete api[k];
  if (o && o.gl && OPT.test) api.lost = () => o.gl.isContextLost();
  api.ready = false;
  if (G && G.report) G.report({ css: why });
}

/**
 * The deck begins once the piece's frames have kept coming for 0.7 s: only a page on screen gets them (see the top). A page
 * out of sight gets a few as it loads (a quarter of a second here) before the browser stops them, so the first is no sign.
 */
function boot() {
  if (!G || !L || !deck || !L.arm) return;
  if (OPT.off) { api.css = stats.css = 'off'; return; }               // (checks of other things turn it off)
  // and only while the deck itself is in view (the page scrolled down to its crate, it waits) and the home screen, where it
  // says, says the page is in sight
  let first = 0, last = 0, inView = true;
  const io = new IntersectionObserver(es => { for (const e of es) inView = e.isIntersecting; });
  io.observe(deck);
  const unwait = G.onFrame(() => {
    const now = performance.now();
    if (!first || now - last > 400 || !inView || G.shown === false) first = now; // a pause starts the count again
    last = now;
    if (now - first < 700 || api.started) return;
    io.disconnect(); unwait(); api.started = true; main();
  });
}

async function main() {
  const t0 = performance.now(), mark = k => { stats.prep[k] = Math.round(performance.now() - t0); };
  const cv = document.createElement('canvas'); cv.className = 'gy-deck'; cv.setAttribute('aria-hidden', 'true');
  const gl = cv.getContext('webgl2', { antialias: true, alpha: true, premultipliedAlpha: true, depth: true, stencil: false, powerPreference: 'default', preserveDrawingBuffer: false });
  if (!gl) return fallback('no webgl2');                            // (three.js is not even read then)
  try { THREE = await import('three'); } catch (e) { return fallback('no three', { gl, scenes: [], textures: [], observers: [], targets: () => [] }); }
  LAMP = new THREE.Vector3(-500, 2000, -450); AIM = new THREE.Vector3(500, 0, 390);
  mark('three');
  // the bakes are kept in half floats (the light before its tone mapping); without them everything is drawn each frame
  const half = !OPT.noBake && !!(gl.getExtension('EXT_color_buffer_half_float') || gl.getExtension('EXT_color_buffer_float'));
  let R, rtA = null, rtB = null;
  const own = { gl, cv, R: null, env: null, scenes: [], textures: [], observers: [], targets: () => [rtA, rtB] };
  try {
    R = own.R = new THREE.WebGLRenderer({ canvas: cv, context: gl, antialias: true, alpha: true });
    R.outputColorSpace = THREE.SRGBColorSpace; R.toneMapping = softNeutral(OPT.test && OPT.toe != null ? OPT.toe : TOE); R.toneMappingExposure = 1.0;
    R.shadowMap.enabled = true; R.shadowMap.type = THREE.VSMShadowMap; R.shadowMap.autoUpdate = false; R.shadowMap.needsUpdate = true;
    R.setClearColor(0x000000, 0);
    const plinth = deck.querySelector('svg.plinth');
    deck.insertBefore(cv, plinth ? plinth.nextSibling : deck.firstChild);

    /* pictures */
    const [col, nor, rgh] = await Promise.all(['walnut-col.jpg', 'walnut-nor.jpg', 'walnut-rgh.jpg'].map(n => load(ASSET + n)));
    col.colorSpace = THREE.SRGBColorSpace;
    mark('wood');
    const rings = vinylRings(); rings.update();
    const [rim, rimMR] = rimFace();
    const [plate, plateMR] = plateFace();
    const T = { col, nor, rgh, rings, rim, rimMR, dots: dotsFace(), strobe: strobeFace(), matN: matRibs(), plate, plateMR, key: keyFace(), print: null, shade: null, label: null };
    const lab0 = await labelPicture().catch(() => null);
    T.label = tex(lab0 ? lab0.canvas : canvas2d(4, 4)[0], { channel: 1, aniso: 8 });
    own.textures.push(col, nor, rgh, rings.aniso, rings.rough, rim, rimMR, T.dots, T.strobe, T.matN, plate, plateMR, T.key);
    mark('pictures');
    const env = own.env = room(R);
    mark('pmrem');
    const M = materials(T);
    const B = build(M);

    /* the still scene (baked) and the moving one (each frame) */
    const camera = new THREE.PerspectiveCamera(2 * Math.atan(390 / CAM_H) / D2R, 1000 / 780, CAM_H - 400, CAM_H + 120);
    camera.position.set(500, CAM_H, 390); camera.up.set(0, 0, -1); camera.lookAt(500, 0, 390);
    const key = () => { const l = new THREE.SpotLight(0xfff3e6, 1.03e7, 0, 0.5, 0.65, 2); l.position.copy(LAMP); l.target.position.copy(AIM); return l; };
    const S = new THREE.Scene(), D = new THREE.Scene();
    S.environment = D.environment = env;
    own.scenes.push(S, D);
    // the lamp casts its soft shadows (VSM) into the first bake only; the record, baked over it, needs none (kB)
    const kS = key(); kS.castShadow = true; kS.shadow.mapSize.set(1536, 1536); kS.shadow.radius = 7; kS.shadow.blurSamples = 16; kS.shadow.bias = -0.0003;
    kS.shadow.camera.near = 1100; kS.shadow.camera.far = 2700;
    const kB = key(); kB.visible = false;
    // the vinyl's second sheen: a soft light low on the far side, so the grooves catch light on both sides of the spindle (the
    // bow tie a real record shows under one lamp, from the room's other bright side); in the record's bake only
    const kB2 = new THREE.SpotLight(0xf4f0ea, 2.6e6, 0, 0.62, 0.9, 2); kB2.position.set(1650, 1500, 1300); kB2.target.position.set(400, 0, 390); kB2.visible = false;
    const kD = key(); D.add(kD, kD.target, B.live);
    const shadow = armShadow(armSilhouette()); D.add(shadow);
    const recShadow = new THREE.Mesh(new THREE.PlaneGeometry(1, 1).rotateX(-Math.PI / 2), new THREE.MeshBasicMaterial({ map: spot(0.6), color: 0x000000, transparent: true, depthWrite: false, opacity: 0 }));
    recShadow.renderOrder = 1; recShadow.scale.set(640, 1, 640); D.add(recShadow);
    // the bake under the moving parts: one quad over the whole canvas, tone-mapped as it is shown
    const quad = 'varying vec2 vUv; void main(){ vUv = uv; gl_Position = vec4(position.xy, 0.0, 1.0); }';
    const bgM = new THREE.ShaderMaterial({ uniforms: { map: { value: null } }, depthTest: false, depthWrite: false, blending: THREE.NoBlending, toneMapped: true, vertexShader: quad,
      fragmentShader: 'uniform sampler2D map; varying vec2 vUv;\nvoid main(){ gl_FragColor = texture2D(map, vUv);\n#include <tonemapping_fragment>\n#include <colorspace_fragment>\n}' });
    const bg = new THREE.Mesh(new THREE.PlaneGeometry(2, 2), bgM); bg.frustumCulled = false; bg.renderOrder = -1000;
    // the bake is drawn at twice the size, then each pixel is the mean of its four (a quad sampling exactly between them)
    const downM = new THREE.ShaderMaterial({ uniforms: { map: { value: null } }, depthTest: false, depthWrite: false, blending: THREE.NoBlending, toneMapped: false, vertexShader: quad,
      fragmentShader: 'uniform sampler2D map; varying vec2 vUv; void main(){ gl_FragColor = texture2D(map, vUv); }' });
    // the record home: drawn alone on a clear picture, then laid over the first (its anti-aliased edge too)
    const overM = new THREE.ShaderMaterial({ uniforms: { map: { value: null }, base: { value: null } }, depthTest: false, depthWrite: false, blending: THREE.NoBlending, toneMapped: false, vertexShader: quad,
      fragmentShader: 'uniform sampler2D map, base; varying vec2 vUv; void main(){ vec4 v = texture2D(map, vUv); gl_FragColor = v + texture2D(base, vUv) * (1.0 - v.a); }' });
    const downS = new THREE.Scene(), dq = new THREE.Mesh(new THREE.PlaneGeometry(2, 2), downM); dq.frustumCulled = false; downS.add(dq);
    const overS = new THREE.Scene(), oq = new THREE.Mesh(new THREE.PlaneGeometry(2, 2), overM); oq.frustumCulled = false; overS.add(oq);
    own.scenes.push(downS, overS);
    if (half) { S.add(kS, kS.target, kB, kB.target, kB2, kB2.target, B.still, B.vinylHome); D.add(bg); }
    else { D.add(B.still); B.vinylHome.visible = false; D.add(B.vinylHome); } // no half-float targets: the still parts drawn each frame too
    const ss = new THREE.Vector2();
    let needB = !half, bakeSoon = 0, pr = OPT.pr || Math.min(2, window.devicePixelRatio || 1), cssW = 0, cssH = 0;

    function size() {
      const r = deck.getBoundingClientRect(); cssW = Math.max(1, Math.round(r.width)); cssH = Math.max(1, Math.round(r.height));
      R.setPixelRatio(pr); R.setSize(cssW, cssH, false);
      R.getDrawingBufferSize(ss); stats.pr = pr; stats.w = ss.x; stats.h = ss.y;
      T.print && T.print.dispose(); T.shade && T.shade.dispose();
      const W = Math.min(2048, Math.max(1024, Math.round(ss.x * 1.4)));
      M.print.map = T.print = printLayer(W); M.shade.map = T.shade = shadeLayer(Math.round(W / 2));
      M.print.needsUpdate = M.shade.needsUpdate = true;
    }
    /** The still picture without the record (A: while the record is away) and with it (B: the record home). */
    function bake(which = 'AB') {
      if (!half) return;
      const t = performance.now(), k = ss.x * ss.y * 4 <= 4.5e6 ? 2 : 1, mk = () => new THREE.WebGLRenderTarget(ss.x, ss.y, { type: THREE.HalfFloatType, depthBuffer: false });
      if (!rtA || rtA.width !== ss.x || rtA.height !== ss.y) { if (rtA) { rtA.dispose(); rtB.dispose(); } rtA = mk(); rtB = mk(); which = 'AB'; }
      const tmp = new THREE.WebGLRenderTarget(ss.x * k, ss.y * k, { type: THREE.HalfFloatType, samples: k > 1 ? 0 : 4 });
      R.setClearColor(0, 0);
      if (which.includes('A')) {
        R.shadowMap.needsUpdate = true;
        R.setRenderTarget(tmp); R.clear(); R.render(S, camera);
        downM.uniforms.map.value = tmp.texture; R.setRenderTarget(rtA); R.render(downS, camera);
      }
      if (which.includes('B')) {                                 // the record alone, laid over A
        B.still.visible = kS.visible = false; B.vinylHome.visible = kB.visible = kB2.visible = true;
        R.setRenderTarget(tmp); R.clear(); R.render(S, camera);
        overM.uniforms.map.value = tmp.texture; overM.uniforms.base.value = rtA.texture; R.setRenderTarget(rtB); R.render(overS, camera);
        B.still.visible = kS.visible = true; B.vinylHome.visible = kB.visible = kB2.visible = false;
      }
      R.setRenderTarget(null); tmp.dispose();
      // what only the bake reads leaves the GPU (three uploads it again if a bake needs it: a new size)
      if (which.includes('A')) { kS.shadow.dispose(); for (const t of [T.col, T.nor, T.rgh, T.print, T.shade, T.plate, T.plateMR, T.rim, T.rimMR, T.matN]) t && t.dispose(); }
      if (which.includes('B')) needB = false;
      stats.prep.lastBake = Math.round(performance.now() - t);
    }
    size();
    if (half) await R.compileAsync(S, camera);
    B.vinyl.visible = true; await R.compileAsync(D, camera); B.vinyl.visible = false;
    if (half) {
      await R.compileAsync(downS, camera); await R.compileAsync(overS, camera);
      B.still.visible = kS.visible = false; B.vinylHome.visible = kB.visible = kB2.visible = true; await R.compileAsync(S, camera);
      B.still.visible = kS.visible = true; B.vinylHome.visible = kB.visible = kB2.visible = false;
    }
    mark('shaders');
    bake();
    mark('bake');

    /* following the piece */
    const slot = document.getElementById('slot');
    let drawn = null, dirty = true, visible = true, lastDraw = 0, labelKey = lab0 ? lab0.key : '', labelBusy = false, strobePh = 0, lastFrameAt = 0, cont = 0;
    // when the piece's frame last came (only while the page is seen), and a resize waiting for it
    let seenAt = performance.now(), resizedAt = 0;
    const seen = () => performance.now() - seenAt < 250;
    function labelCheck() {
      const lb = G.label(), k = lb && lb.key;
      if (!k || k === labelKey || labelBusy) return;
      labelBusy = true;
      labelPicture().then(p => {
        labelBusy = false; if (!p) return;
        if (stopped) return;
        const t = tex(p.canvas, { channel: 1, aniso: 8 }), old = M.label.map; M.label.map = t; old && old.dispose(); labelKey = p.key; dirty = true;
        if (G.label().key !== labelKey) labelCheck();
      }, () => { labelBusy = false; labelKey = k; });
    }
    function slotState() {
      // the piece flies the record out and back with Web Animations on #slot: follow its transform and opacity
      const anim = slot && slot.getAnimations ? slot.getAnimations().length > 0 : false;
      if (!anim && !(slot && slot.style.opacity === '0')) return null;
      const cs = getComputedStyle(slot), o = +cs.opacity;
      let a = 1, b = 0, e = 0, f = 0;
      const m = /matrix\(([^)]+)\)/.exec(cs.transform || '');
      if (m) { const v = m[1].split(',').map(Number); a = v[0]; b = v[1]; e = v[4]; f = v[5]; }
      const k = 1000 / (cssW || 1), sc = Math.hypot(a, b);
      return { x: e * k, z: f * k, rot: Math.atan2(b, a), lift: (CAM_H - Y.groove) * (1 - 1 / Math.max(1, sc)), o: isFinite(o) ? o : 1 };
    }
    function sample() {
      const sl = slotState();
      return { spin: L.spin, a: L.arm.angle, l: L.arm.lift, on: !!L.motorOn, ph: strobePh, sl: sl ? [sl.x, sl.z, sl.rot, sl.lift, sl.o].map(v => v.toFixed(2)).join() : '', slot: sl };
    }
    const same = (p, q) => p && q && Math.abs(p.spin - q.spin) < 0.02 && Math.abs(p.a - q.a) < 0.004 && Math.abs(p.l - q.l) < 0.001 && p.on === q.on && Math.abs(p.ph - q.ph) < 0.01 && p.sl === q.sl;
    const headM = new THREE.Matrix4().makeRotationY(-22 * D2R).setPosition(500, 0, 0);
    function pose(s) {
      const sp = -s.spin * D2R, sl = s.slot, home = !sl;
      // the record: the piece turns it clockwise on screen, i.e. −spin about +y here; while it flies, its own body is drawn
      // home again with new rings (another record): its own body over the first picture until the second is baked, when idle
      const baked = home && !needB;
      // (only while the page is seen: one that went out of sight meanwhile bakes on its next frame)
      if (half && home && needB && !bakeSoon) bakeSoon = (window.requestIdleCallback || setTimeout)(() => { bakeSoon = 0; if (!stopped && !lost && seen()) { bake('B'); dirty = true; } }, { timeout: 400 });
      bgM.uniforms.map.value = (baked ? rtB : rtA) && (baked ? rtB : rtA).texture;
      if (!half) B.vinylHome.visible = false;
      if (sl) {
        B.disc.position.set(REC.x + sl.x, sl.lift, REC.z + sl.z); B.disc.rotation.y = sp - sl.rot;
        M.vinyl.opacity = M.label.opacity = sl.o; M.dust.opacity = 0.7 * sl.o;
        const ox = REC.x + sl.x, oz = REC.z + sl.z, dy = LAMP.y - sl.lift;
        recShadow.position.set(ox - (LAMP.x - ox) / dy * sl.lift, 25, oz - (LAMP.z - oz) / dy * sl.lift);
        recShadow.material.opacity = 0.55 * sl.o * Math.min(1, sl.lift / 40); recShadow.visible = sl.lift > 0.5;
      } else {
        B.disc.position.set(REC.x, 0, REC.z); B.disc.rotation.y = sp; M.vinyl.opacity = M.label.opacity = 1; M.dust.opacity = 0.7; recShadow.visible = false;
      }
      B.vinyl.visible = !baked && (home || sl.o > 0.003); B.label.visible = B.dust.visible = home || sl.o > 0.003;
      B.dots.rotation.y = -s.ph * D2R;                           // the rim's dots as the strobe lamp shows them
      // the arm: its angle about the post (clockwise on screen, as the piece's rotate()), its lift about the bearing
      B.yaw.rotation.y = -s.a * D2R;
      B.liftG.rotation.z = 1.2 * D2R * s.l;
      B.cue.rotation.x = 42 * D2R * s.l; B.cue.rotation.y = 10 * D2R * s.l;
      const c = Math.cos(s.a * D2R), sn = Math.sin(s.a * D2R), u = shadow.material.uniforms;
      u.cs.value.set(c, sn); u.tilt.value = Math.tan(1.2 * D2R * s.l); u.rec.value.z = home ? 1 : 0;
      // the shadow's quad: along the arm (its reach, −160…610 by ±115), pushed away from the lamp
      shadow.rotation.y = -s.a * D2R; shadow.position.set(PIV.x + 225 * c + 22, 28.2, PIV.z + 225 * sn + 26);
      // the lamps
      const on = s.on;
      // the strobe lamp's glass: a warm glow inside it when lit, brighter than white so it blooms; the 33⅓ LED
      M.lens.emissive.setHex(on ? 0xffb050 : 0x000000); M.lens.emissiveIntensity = on ? 3 : 0;
      M.led.color.setHex(on ? 0xffb04a : 0x3a2508).multiplyScalar(on ? 2.4 : 1);
      M.keyLed.color.setHex(on ? 0xffb347 : 0x5a3b16).multiplyScalar(on ? 2.2 : 1);
      B.glow.material.opacity = on ? 0.55 : 0; B.glow2.material.opacity = on ? 0.5 : 0; B.glow3.material.opacity = on ? 0.45 : 0; M.strobe.opacity = on ? 0.85 : 0;
      B.glow.visible = B.glow2.visible = B.glow3.visible = B.glowRim.visible = on;
    }
    // the GPU's own time for a frame, where the browser lets a page measure it (desktop mostly; many phones do not)
    const timer = gl.getExtension('EXT_disjoint_timer_query_webgl2'), queries = [];
    function gpuTime() {
      while (queries.length && gl.getQueryParameter(queries[0], gl.QUERY_RESULT_AVAILABLE)) {
        const q = queries.shift();
        if (!gl.getParameter(timer.GPU_DISJOINT_EXT)) { stats.gpu.push(gl.getQueryParameter(q, gl.QUERY_RESULT) / 1e6); if (stats.gpu.length > 60) stats.gpu.shift(); }
        gl.deleteQuery(q);
      }
    }
    function draw(s, now) {
      const w0 = performance.now(), q = timer && queries.length < 4 && stats.frames % 4 === 0 ? gl.createQuery() : null;
      pose(s);
      if (q) gl.beginQuery(timer.TIME_ELAPSED_EXT, q);
      if (!OPT.noRender) R.render(D, camera);
      if (q) { gl.endQuery(timer.TIME_ELAPSED_EXT); queries.push(q); }
      if (timer) gpuTime();
      const w1 = performance.now();
      stats.frames++; api.draws++; drawn = s; dirty = false;
      stats.calls = R.info.render.calls; stats.triangles = R.info.render.triangles; stats.programs = R.info.programs ? R.info.programs.length : 0;
      const gap = now - lastDraw; lastDraw = now;
      if (gap < 600) { cont++; stats.work.push(w1 - w0); stats.gaps.push(gap); if (stats.work.length > 240) { stats.work.shift(); stats.gaps.shift(); } }
      else cont = 0;
      if (cont === 150 && !stats.reported) { stats.reported = true; const r = summary(); G.report({ avg: r.avg, p95: r.p95, fps: r.fps, pr: r.pr, w: r.w, h: r.h, dc: r.calls, tri: r.triangles, gpu: r.gpu }); }
      if (OPT.adapt && cont > 0 && cont % 60 === 0) adapt();
    }
    function adapt() {
      // The phone cannot keep up when frames come far apart while the platter turns: fewer pixels, then the CSS deck.
      const g = stats.gaps.slice(-60).sort((a, b) => a - b), p = g[Math.floor(g.length * 0.95)];
      if (p <= OPT.slow[0]) return;
      if (pr > 1.35) { pr = pr > 1.75 ? 1.6 : 1.3; size(); bake(); stats.work.length = stats.gaps.length = 0; dirty = true; return; }
      if (p > OPT.slow[1]) off('slow ' + Math.round(p) + 'ms');
    }
    let stopped = false, lost = false;
    function off(why) {
      if (stopped) return;
      stopped = true; unFrame(); unTrack(); clearTimeout(bakeSoon);
      if (bakeSoon && window.cancelIdleCallback) cancelIdleCallback(bakeSoon);
      fallback(why, own);
    }
    function frame(dt) {
      if (stopped || lost) return;
      seenAt = performance.now();
      // a size change waits for the page to be seen, and for the size to settle
      if (resizedAt && seenAt - resizedAt > 160) {
        resizedAt = 0;
        const r = deck.getBoundingClientRect();
        if (Math.round(r.width) !== cssW || Math.round(r.height) !== cssH) { size(); bake(); dirty = true; }
      }
      // the strobe dots as the lamp (100 flashes a second) shows them: still at 33⅓, drifting while the platter speeds up
      const per = L.omega / 100, al = ((per % 2) + 3) % 2 - 1;
      strobePh = (strobePh + al * 100 * dt) % 360;
      if (!visible || document.hidden || G.shown === false) return;
      const now = performance.now();
      if (now - lastFrameAt < 1000 / OPT.fps - 2.5) return;    // at most 30 a second
      const s = sample();
      if (!dirty && same(s, drawn)) return;
      lastFrameAt = now;
      draw(s, now);
    }
    const unFrame = G.onFrame(frame);
    const unTrack = G.onTrack(() => { if (rings.update()) { needB = true; dirty = true; } labelCheck(); });
    // (within the page: the deck scrolled out of view; a frame out of the launcher's sight gets no frames at all)
    const io = new IntersectionObserver(es => { for (const e of es) visible = e.isIntersecting; if (visible) dirty = true; });
    io.observe(deck); own.observers.push(io);
    const ro = new ResizeObserver(() => { resizedAt = performance.now(); dirty = true; });
    ro.observe(deck); own.observers.push(ro);
    cv.addEventListener('webglcontextlost', e => { if (stopped) return; e.preventDefault(); lost = true; deck.classList.remove('gy3d'); stats.css = 'context lost'; }, false);
    cv.addEventListener('webglcontextrestored', () => {
      if (stopped) return;
      lost = false; rtA = rtB = null;
      // what was drawn on the GPU is gone with it: the room's light again, then the bakes
      try { S.environment = D.environment = own.env = room(R); size(); bake(); dirty = true; draw(sample(), performance.now()); deck.classList.add('gy3d'); stats.css = null; } catch (e) { off('restore failed'); }
    }, false);

    /* the first frame, then the 3D deck in place of the CSS one */
    draw(sample(), performance.now());
    mark('first');
    deck.classList.add('gy3d');
    api.ready = true;
    if (!OPT.test) return;
    // ── for checks only (tests/browser/vinyl.test.js): not on a phone ──
    api.off = why => off(why || 'test');
    api.lost = () => gl.isContextLost();
    api.stylus = () => {
      // where the stylus tip is on screen (CSS px from the deck's top left), and where the piece's own arm puts it
      B.liftG.updateMatrixWorld(true);
      const p = new THREE.Vector3(0, -Y.arm + Y.groove, 0).applyMatrix4(headM).applyMatrix4(B.liftG.matrixWorld).project(camera), a = L.arm.angle * D2R;
      return { x: (p.x + 1) / 2 * cssW, y: (1 - p.y) / 2 * cssH, css: { x: (PIV.x + 500 * Math.cos(a)) * cssW / 1000, y: (PIV.z + 500 * Math.sin(a)) * cssH / 780 } };
    };
    api.probe = pts => {
      // the canvas's colours at points (CSS px from the deck's top left), drawn now (for checks; not counted as a frame)
      pose(sample()); R.render(D, camera);
      const px = new Uint8Array(4);
      return pts.map(([x, y]) => { gl.readPixels(Math.round(x * ss.x / cssW), Math.round(ss.y - 1 - y * ss.y / cssH), 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px); return Array.from(px); });
    };
    api.state = () => ({ home: !drawn || !drawn.slot, label: labelKey, dots: strobePh, bake: half ? 'half' : 'live', needB });
    api.bench = n => {
      // n frames of the moving scene, each waited for (a pixel read back): the cost of one frame here
      const s = sample(), px = new Uint8Array(4), ms = [];
      gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
      for (let i = 0; i < n; i++) { const t = performance.now(); s.spin = (s.spin + 6.67) % 360; pose(s); R.render(D, camera); gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px); ms.push(performance.now() - t); }
      dirty = true;
      return ms.sort((a, b) => a - b)[Math.floor(n / 2)];        // the median
    };
    api.bakeMs = () => { const t = performance.now(), px = new Uint8Array(4); bake(); gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px); dirty = true; return performance.now() - t; };
    api._ = { THREE, R, S, D, B, M, camera, rings, stats, pose, sample, size, bake, draw: () => draw(sample(), performance.now()) };
  } catch (e) {
    console.warn('deck3d: ' + (e && e.message));
    fallback('init ' + String(e && e.message || e).slice(0, 30), own);
  }
}
boot();

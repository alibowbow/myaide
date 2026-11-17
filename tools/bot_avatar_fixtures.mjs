#!/usr/bin/env node
/**
 * Regenerates the golden fixtures in tests/fixtures/bot-avatar/ from the TypeScript source of
 * open-bot-avatar (MIT), which BotAvatarTests compares the Java port against.
 *
 *   node tools/bot_avatar_fixtures.mjs [path/to/open-bot-avatar]
 *
 * The source checkout defaults to $OPEN_BOT_AVATAR or /home/user/dominikscholz/open-bot-avatar.
 * Needs Node 22.18+ (it imports the .ts sources directly, with type stripping).
 */
import { mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const src = resolve(process.argv[2] ?? process.env.OPEN_BOT_AVATAR ?? '/home/user/dominikscholz/open-bot-avatar');
const core = join(src, 'packages/core/src');
const out = join(dirname(fileURLToPath(import.meta.url)), '..', 'tests', 'fixtures', 'bot-avatar');
const load = (file) => import(pathToFileURL(join(core, file)).href);

const { examples } = await load('examples.ts');
const { validateAvatar, analyzeBody, gazeRange } = await load('validate.ts');
const { avatarFrame, liveFrame, renderAvatarSvg, avatarExtent, preparedVolume } = await load('render.ts');
const { computePose, blendPoses, hashString, BASE_YAW, BASE_PITCH } = await load('motion.ts');
const { MOOD_MS, moodProgress, settleMood } = await load('mood.ts');
const { REST, followGaze, followStep, pointerGaze } = await load('follow.ts');
const { createAnimator } = await load('animator.ts');
const { faceRoom } = await load('volume.ts');
const { silhouetteSeams } = await load('seams.ts');
const { parsePath, serializePath, flattenPath } = await load('path.ts');
const { parseHex, toHex, shade, tint, luminance, contrast, autoEyeColor } = await load('color.ts');
const version = JSON.parse(readFileSync(join(src, 'packages/core/package.json'), 'utf8')).version;

/** Floats to 9 significant digits (the tests compare within 1e-6), integers and strings as they are. */
const n = (v) => (typeof v !== 'number' || Number.isInteger(v) || !Number.isFinite(v) ? (Object.is(v, -0) ? 0 : v) : Number(v.toPrecision(9)));
const ns = (a) => a.map(n);
const write = (name, data) => {
  const text = JSON.stringify(data);
  writeFileSync(join(out, name), text + '\n');
  return text.length;
};
mkdirSync(out, { recursive: true });

// ---------------------------------------------------------------------------
// Avatars: the built-in examples and every gallery definition.

const inputs = examples.map((d) => ({ origin: `example:${d.id}`, text: JSON.stringify(d) }));
const galleryDir = join(src, 'gallery');
const galleryFiles = readdirSync(galleryDir).filter((f) => f.endsWith('.json')).sort();
for (const f of galleryFiles) {
  if (f === 'colors.json') continue; // a color map, not a definition (it is a validation case below)
  inputs.push({ origin: `gallery/${f}`, text: readFileSync(join(galleryDir, f), 'utf8') });
}

const nested = { from: { motion: 'happy', blend: { from: 'idle', t: 0.4 } }, t: 0.3 };
/**
 * [function, options, who]: the examples get every set; gallery avatars the ones marked 'g', and the
 * side view ('x') only the multi-part ones in SIDE (the examples already cover the side stack well).
 */
const optionSets = [
  ['frame', {}, 'g'],
  ['frame', { time: 0.37 }],
  ['frame', { time: 2.9 }],
  ['frame', { motion: 'thinking', time: 1.7 }],
  ['frame', { motion: 'happy', time: 0.61 }, 'g'],
  ['frame', { motion: 'happy', time: 1.3, motionBlend: { from: 'thinking', t: 0.35 } }],
  ['frame', { motion: 'idle', time: 3.3, motionBlend: nested }],
  ['frame', { time: 0.8, gaze: { x: 0.6, y: -0.4, weight: 1 } }],
  ['frame', { time: 0.8, gaze: { x: 0.6, y: -0.4, weight: 0.5 }, motion: 'thinking' }, 'g'],
  ['frame', { time: 1.1, view: { yaw: 0.5, pitch: -0.2 } }],
  ['frame', { time: 1.1, view: { yaw: 1.5, pitch: 0.3 } }, 'x'],
  ['frame', { time: 0.4, gaze: { x: -0.8, y: 0.5 }, view: { yaw: -2.6, pitch: 0.6 } }],
  ['frame', { depth: 0, time: 0.9, color: 'nope' }],
  ['frame', { depth: 14, time: 0.9, color: '#1FBF68', eyeColor: '#fff' }, 'deep'],
  ['frame', { reducedMotion: true, motion: 'thinking', gaze: { x: 0.3, y: 0.2, weight: 0.7 } }],
  ['live', { size: 40, time: 1.9 }, 'g'],
  ['live', { size: 96, time: 1.9, motion: 'happy' }],
];
const SIDE = new Set(['cat', 'paint-brush']);
/** The deepest volume adds a second set of paths, so only these examples draw it. */
const DEEP = new Set(['controller', 'mug', 'chat', 'sprout']);

const depthOf = (o) => Math.round(Math.min(14, Math.max(0, Number.isFinite(o.depth) ? o.depth : 6)) * 2) / 2;
const box = (b) => ns([b.x, b.y, b.w, b.h]);
const markOut = (m) => (m.shape === 'pill' ? { shape: 'pill', x: n(m.x), y: n(m.y), width: n(m.width), height: n(m.height), radius: n(m.radius) } : { shape: 'arc', d: m.d, strokeWidth: n(m.strokeWidth) });

const avatars = [];
for (const input of inputs) {
  const res = validateAvatar(JSON.parse(input.text));
  if (!res.ok) throw new Error(`${input.origin}: ${res.errors.join('; ')}`);
  const def = res.value;
  const isExample = input.origin.startsWith('example:');
  const side = isExample || SIDE.has(def.id);
  const sets = optionSets.filter((s) => (s[2] === 'deep' ? DEEP.has(def.id) : isExample || s[2] === 'g' || (s[2] === 'x' && side)));

  // Every distinct cross-section path per depth, indexed like the renderer's ids (`${prefix}-${index}`).
  const volumes = {};
  const volumeOf = (depth) => {
    const key = String(depth);
    if (volumes[key]) return volumes[key];
    const { vol } = preparedVolume(def, depth);
    const prefix = `oba-${hashString(`${def.id}|${def.body.path}|${depth}`).toString(36)}`;
    const index = new Map();
    const paths = [];
    for (const part of vol.parts) for (const s of [...part.zSlices, ...part.xSlices]) {
      if (!index.has(s.d)) { index.set(s.d, paths.length); paths.push([s.d, s.evenOdd, ...box(s.box)]); }
    }
    const body = analyzeBody(def.body.path);
    const range = gazeRange(body.polys, { ...def.eyes, scale: vol.eyeScale });
    volumes[key] = {
      depth, prefix, index, paths, used: new Set(),
      summary: {
        bounds: ns([vol.bounds.minX, vol.bounds.minY, vol.bounds.maxX, vol.bounds.maxY]),
        eyePart: vol.eyePart, eyeZ: ns(vol.eyeZ), eyeScale: n(vol.eyeScale),
        eyeNormals: vol.eyeNormals.map((list) => list.map(ns)),
        range: ns([range.left, range.right, range.up, range.down]),
        parts: vol.parts.map((p) => ({
          kind: p.kind, d: p.d, center: ns(p.center), thickness: n(p.thickness), depth: n(p.depth), rxy: n(p.rxy), rz: n(p.rz),
          axis: p.axis === undefined ? null : n(p.axis),
          z: p.zSlices.map((s) => [n(s.at), n(s.inset), index.get(s.d)]),
          x: p.xSlices.map((s) => [n(s.at), index.get(s.d)]),
        })),
      },
    };
    return volumes[key];
  };
  // The natural depth always gets its structure; its full path dictionary when the side stack is covered.
  // Other depths (and gallery avatars seen only from the front) only list the paths their frames draw.
  volumeOf(6).full = side;
  volumeOf(6).summarize = true;

  const frames = [];
  for (const [fn, options] of sets) {
    const frame = (fn === 'live' ? liveFrame : avatarFrame)(def, options);
    const vol = volumeOf(depthOf(options));
    const ref = (id) => {
      if (!id.startsWith(`${vol.prefix}-`)) throw new Error(`unexpected path id ${id}`);
      const k = Number(id.slice(vol.prefix.length + 1));
      vol.used.add(k);
      return k;
    };
    for (const p of frame.paths) {
      const k = ref(p.id);
      if (vol.paths[k][0] !== p.d) throw new Error('path dictionary mismatch');
    }
    // Matrices as (a b c d) from `bases` plus (e f): the slices of one stack share their linear part.
    const bases = [], baseKeys = new Map();
    const base = (m) => {
      const abcd = ns(m.slice(0, 4)), key = abcd.join(' ');
      if (!baseKeys.has(key)) { baseKeys.set(key, bases.length); bases.push(abcd); }
      return baseKeys.get(key);
    };
    const shapes = frame.shapes.map((s) => (s.kind === 'slice'
      ? [s.key, ref(s.path), base(s.matrix), n(s.matrix[4]), n(s.matrix[5])]
      : [s.key, base(s.matrix), n(s.matrix[4]), n(s.matrix[5]), markOut(s.mark)]));
    frames.push({
      fn, options,
      out: { name: frame.name, color: frame.color, ink: frame.ink, paths: frame.paths.map((p) => ref(p.id)), root: ns(frame.root), bases, shapes },
    });
  }

  const room = faceRoom(def);
  avatars.push({
    origin: input.origin,
    input: input.text,
    id: def.id,
    seed: hashString(def.id),
    volumes: Object.values(volumes).map((v) => ({
      depth: v.depth, prefix: v.prefix, full: !!v.full, count: v.paths.length,
      paths: v.paths.map((p, k) => (v.full || v.used.has(k) ? p : null)),
      ...(v.summarize ? v.summary : { eyeZ: v.summary.eyeZ, eyeScale: v.summary.eyeScale }),
    })),
    extent: Object.fromEntries([0, 6, 14].map((d) => { const e = avatarExtent(def, d); return [String(d), ns([e.x, e.y, e.size])]; })),
    faceRoom: { boost: room.boost, clearance: room.clearance, gaze: ns([room.gaze.left, room.gaze.right, room.gaze.up, room.gaze.down]), ok: room.ok },
    seams: silhouetteSeams(analyzeBody(def.body.path).polys).map((s) => [s.side, n(s.at), n(s.size)]),
    frames,
  });
}

// ---------------------------------------------------------------------------
// SVG stills (the examples at rest and a few posed ones).

const svgs = [];
for (const d of examples.slice(0, 1)) svgs.push({ id: d.id, options: {}, svg: renderAvatarSvg(d, {}) });
svgs.push({ id: 'chat', options: { size: 256, color: '#1fbf68', motion: 'happy', time: 0.5 }, svg: renderAvatarSvg(examples[4], { size: 256, color: '#1fbf68', motion: 'happy', time: 0.5 }) });
svgs.push({ id: 'sprout', options: { size: 3, depth: 2.3, view: { yaw: 1.4, pitch: -0.3 }, time: 2.1, motion: 'thinking' }, svg: renderAvatarSvg(examples[5], { size: 3, depth: 2.3, view: { yaw: 1.4, pitch: -0.3 }, time: 2.1, motion: 'thinking' }) });
const named = { ...examples[6], name: ' Can & "Co" \'x\' ' };
svgs.push({ id: 'watering-can', input: JSON.stringify(named), options: { depth: 0, reducedMotion: true }, svg: renderAvatarSvg(named, { depth: 0, reducedMotion: true }) });

// ---------------------------------------------------------------------------
// Validation: every valid definition above, plus deliberately broken ones.

const base = examples[0];
const ok = (patch) => JSON.stringify({ ...base, ...patch });
const withEyes = (eyes) => ok({ eyes: { ...base.eyes, ...eyes } });
const withPath = (path) => ok({ body: { path } });
const square = 'M20 20H80V80H20Z';
const many = 'M20 20' + 'L80 20L80 80L20 80'.repeat(101) + 'Z';
const subs = Array.from({ length: 17 }, (_, i) => `M${10 + i} 20h3v60h-3z`).join('');
const broken = [
  JSON.stringify({ ...base, version: 2 }),
  JSON.stringify({ ...base, version: '1' }),
  '{"id":"controller","name":"Controller","body":{"path":"M28 22C38 20 44 23 50 23Z"},"eyes":{"x":50,"y":42,"spacing":16,"scale":1.05}}',
  ok({ id: 'Bad_Slug' }), ok({ id: '-dash' }), ok({ id: 'a'.repeat(41) }), ok({ id: 'ok-slug\n' }), ok({ id: 7 }),
  ok({ name: '   ' }), ok({ name: 'a<b' }), ok({ name: 'x'.repeat(41) }), ok({ name: '  Padded Name  ' }), ok({ name: 'tab\there' }),
  JSON.stringify({ ...base, extra: 1, another: true, 2: 'two', 1: 'one' }),
  ok({ body: { path: square, fill: 'red' } }), ok({ body: 'M0 0' }), ok({ body: { path: 5 } }),
  ok({ eyes: { ...base.eyes, z: 1 } }), ok({ eyes: { x: 50, y: '42', spacing: null, scale: 1 } }), ok({ eyes: [1, 2] }),
  withEyes({ scale: 2.5 }), withEyes({ scale: 0.5, spacing: 3 }), withEyes({ spacing: 41 }), withEyes({ spacing: 6.9 }),
  withEyes({ x: 12, y: 80 }), withEyes({ x: 50, y: 70 }), withEyes({ spacing: 38 }),
  withPath('M20 20H130V80H20Z'), withPath('M20.1 20l100.2 0l0 60Z'), withPath('M30 30L-10.1 40l-10.2 0Z'), withPath('M20 20H80V80H20ZM60.7 30h60.6v10Z'), withPath('M0.1 0.2L119.9 50l0.1 0l0.2 0Z'), withPath('M5 20H95V96H5Z'), withPath('M30 30H60V40H30Z'),
  withPath('M20 20H80V24H20Z'), withPath('M20 20L80 80Z'), withPath(many), withPath(subs), withPath('M20 20H80V80H20Z'.repeat(400)),
  withPath(''), withPath('   '), withPath('M20 20H80V80H20Z url(#x)'), withPath('M20 20H80V80H20Z😀'), withPath('M20 20 L e5 Z'),
  withPath('L20 20H80V80H20Z'), withPath('20 20'), withPath('M20 20H80V80H20ZZ'), withPath('M20 20H80V80H20Z 5'),
  withPath('M20 20A10 10 0 2 1 80 80Z'), withPath('M20 20A-10 10 0 1 1 80 80Z'), withPath('M20 20A10 10 0 1180 80Z'),
  withPath('M20 20L1e999 20Z'), withPath('M20 20C30 30'), withPath('M20 20H80 V80﻿H20Z'), withPath('M20,20h60v60h-60z'),
  withPath('m20 20h60v60h-60zm10 10v40h40v-40z'), withPath('M20 20H80V80H20Z\u000b'), withPath('M20 20H80V80H20Z\u0085'),
  '[]', '"text"', 'null', '42', '{}', readFileSync(join(galleryDir, 'colors.json'), 'utf8'),
  // Key order as JSON.parse gives it: array indices first (ascending), then the rest as written.
  '{"version":1,"zeta":1,"id":"controller","10":2,"name":"Controller","3":3,"body":{"path":"M20 20H80V80H20Z","9":1,"path2":2},"eyes":{"x":50,"y":50,"spacing":16,"scale":1,"01":5,"1":6},"4294967295":7,"4294967294":8}',
];
const validation = [...inputs.map((i) => i.text), ...broken].map((text) => ({ input: text, result: validateAvatar(JSON.parse(text)) }));

// ---------------------------------------------------------------------------
// Motion, moods, following and the animator.

const poseOut = (p) => ({ ...Object.fromEntries(Object.entries(p).map(([k, v]) => [k, n(v)])) });
const poseInputs = [];
for (const seed of [0, hashString('controller'), hashString('chat'), 4294967295]) {
  for (const time of [0, 0.37, 2.9, 7.25, 123.456, -3.3]) {
    for (const motion of ['idle', 'thinking', 'happy']) {
      poseInputs.push({ seed, time, motion, reducedMotion: false });
    }
  }
  poseInputs.push({ seed, time: 1.2, motion: 'idle', reducedMotion: true });
  poseInputs.push({ seed, time: 1.2, motion: 'happy', reducedMotion: true, gaze: { x: 0.4, y: 0.2 } });
  poseInputs.push({ seed, time: 1.2, motion: 'thinking', reducedMotion: true, gaze: { x: -2, y: 3, weight: 0.5 } });
  poseInputs.push({ seed, time: 4.4, motion: 'idle', reducedMotion: false, gaze: { x: 0.6, y: -0.4, weight: 0.25 }, view: { yaw: 0.3, pitch: -0.1 } });
  poseInputs.push({ seed, time: 4.4, motion: 'thinking', reducedMotion: false, gaze: { x: 0.6, y: -0.4, weight: 2 } });
  poseInputs.push({ seed, time: 2.2, motion: 'happy', reducedMotion: false, blend: { from: 'idle', t: 0.2 } });
  poseInputs.push({ seed, time: 2.2, motion: 'idle', reducedMotion: false, blend: { from: 'happy', t: 0.5 } });
  poseInputs.push({ seed, time: 2.2, motion: 'thinking', reducedMotion: false, blend: { from: 'happy', t: 0.75 } });
  poseInputs.push({ seed, time: 2.2, motion: 'thinking', reducedMotion: false, blend: { from: 'happy', t: 1 } });
  poseInputs.push({ seed, time: 2.2, motion: 'thinking', reducedMotion: false, blend: { from: 'happy', t: -0.5 } });
  poseInputs.push({ seed, time: 5.1, motion: 'happy', reducedMotion: false, blend: { from: { motion: 'thinking', blend: { from: { motion: 'idle' }, t: 0.6 } }, t: 0.45 } });
}
const poses = poseInputs.map((input) => ({ input, pose: poseOut(computePose(input)) }));
const pa = computePose({ seed: 9, time: 1, motion: 'idle', reducedMotion: false });
const pb = computePose({ seed: 9, time: 1, motion: 'happy', reducedMotion: false });
const blends = [-0.2, 0, 0.25, 0.49, 0.5, 0.8, 1, 1.5].map((t) => ({ t, ab: poseOut(blendPoses(pa, pb, t)), ba: poseOut(blendPoses(pb, pa, t)) }));

const hashes = ['', 'controller', 'watering-can', 'oba|M0 0|6', 'ünïcödé 😀', 'x'.repeat(300)].map((s) => [s, hashString(s)]);
const progress = [-50, 0, 1, 100, 209.5, 210, 333, 419.9, 420, 1000].map((ms) => [ms, n(moodProgress(ms))]);
const settles = [
  { motion: 'idle' },
  { motion: 'happy', blend: { from: 'idle', t: 0.3 } },
  { motion: 'happy', blend: { from: 'idle', t: 0.99 } },
  { motion: 'happy', blend: { from: { motion: 'thinking', blend: { from: 'idle', t: 0.5 } }, t: 0.2 } },
  { motion: 'happy', blend: { from: { motion: 'thinking', blend: { from: { motion: 'idle', blend: { from: { motion: 'happy', blend: { from: 'thinking', t: 0.1 } }, t: 0.1 } }, t: 0.1 } }, t: 0.1 } },
  { motion: 'idle', blend: { from: { motion: 'thinking', blend: { from: 'happy', t: 0.9 } }, t: 0.5 } },
].map((state) => ({ state, settled: settleMood(state) }));

const box0 = { left: 100, top: 50, width: 96, height: 96 };
const pointers = [
  [{ x: 148, y: 98 }, box0, undefined], [{ x: 400, y: 98 }, box0, undefined], [{ x: 0, y: 0 }, box0, { x: 0.42, y: 0.49 }],
  [{ x: 150, y: 90 }, { left: 0, top: 0, width: 300, height: 200 }, { x: 0.5, y: 0.45 }], [{ x: 1e6, y: -1e6 }, box0, undefined],
].map(([p, b, o]) => ({ pointer: p, box: b, origin: o ?? null, gaze: ((g) => ({ x: n(g.x), y: n(g.y) }))(pointerGaze(p, b, o)) }));

const followSeq = [];
{
  let state = REST;
  const script = [
    [{ x: 0.5, y: -0.2 }, 0.016], [{ x: 0.5, y: -0.2 }, 0.016], [{ x: 0.5, y: -0.2 }, 0.2], [{ x: 0.5, y: -0.2 }, 0.05],
    [{ x: -0.7, y: 0.4 }, 0.033], [null, 0.016], [null, 0.5], [null, 0], [null, -1],
  ];
  for (let i = 0; i < 60; i++) script.push([i % 20 < 12 ? { x: Math.sin(i / 3) * 0.8, y: Math.cos(i / 5) * 0.5 } : null, 0.0166]);
  // Easing all the way: onto a held target until it snaps to it, then back to rest until it snaps there.
  for (let i = 0; i < 40; i++) script.push([{ x: 0.3, y: -0.6 }, 0.05]);
  for (let i = 0; i < 40; i++) script.push([null, 0.05]);
  script.push([{ x: 0.2, y: 0.1 }, 0.016, true], [null, 0.016, true]);
  for (const [target, dt, reduced = false] of script) {
    const step = followStep(state, target, dt, reduced);
    state = step.state;
    const g = followGaze(state);
    followSeq.push({ target, dt, reduced, state: { x: n(state.x), y: n(state.y), attention: n(state.attention) }, moving: step.moving, gaze: g ? { x: n(g.x), y: n(g.y), weight: n(g.weight) } : null });
  }
}

const blendOut = (b) => (b === undefined ? null : { from: typeof b.from === 'string' ? b.from : { motion: b.from.motion, blend: b.from.blend ? blendOut(b.from.blend) : null }, t: n(b.t) });
const animatorRuns = [];
for (const run of [0, 1]) {
  const animator = createAnimator();
  const seq = [];
  let now = 1000, reset = false;
  const step = (input) => {
    const s = animator.step({ now, ...input });
    seq.push({ input: { now, ...input, ...(reset ? { reset: true } : {}) }, out: { gaze: s.gaze ? { x: n(s.gaze.x), y: n(s.gaze.y), weight: n(s.gaze.weight) } : null, motionBlend: blendOut(s.motionBlend), moving: s.moving } });
  };
  const reducedMotion = run === 1;
  const moods = ['idle', 'idle', 'happy', 'happy', 'thinking', 'idle', 'idle', 'happy', 'thinking', 'idle', 'happy'];
  for (let i = 0; i < 90; i++) {
    const motion = moods[Math.floor(i / 8) % moods.length];
    const target = i < 30 ? { x: 0.6, y: -0.3 } : i < 55 ? null : { x: -0.4 + i / 200, y: 0.25 };
    step({ motion, target, hold: i >= 40 && i < 46, reducedMotion: reducedMotion || (i >= 70 && i < 74) });
    now += i % 7 === 3 ? 120 : i % 11 === 5 ? 0 : 16.7;
  }
  // Rapid mood changes, then rest until settled.
  for (const motion of ['happy', 'thinking', 'idle', 'happy', 'thinking', 'happy', 'idle']) { step({ motion, target: null, reducedMotion }); now += 30; }
  for (let i = 0; i < 40; i++) { step({ motion: 'idle', target: null, reducedMotion }); now += 33; }
  animator.reset();
  reset = true; // the step after a reset says so
  step({ motion: 'thinking', target: { x: 0.1, y: 0.1 }, reducedMotion });
  reset = false;
  now += 16;
  step({ motion: 'happy', target: { x: 0.1, y: 0.1 }, reducedMotion });
  animatorRuns.push(seq);
}

const colorCases = ['#ff6a1a', '#FFF', ' #1fbf68 ', '#151515', '#000000', '#7f7f7f', 'red', '#12345', '', '#abcdefa'].map((input) => {
  const rgb = parseHex(input);
  return rgb
    ? { input, rgb, hex: toHex(rgb), shade: ns(shade(rgb, 0.35)), shadeHex: toHex(shade(rgb, 0.8)), tint: ns(tint(rgb, 0.4)), luminance: n(luminance(rgb)), contrast: n(contrast(rgb, [20, 22, 34])), eye: autoEyeColor(rgb) }
    : { input, rgb: null };
});

// Number formatting: Number#toString (validation messages, ids) and render.ts f() / contour.ts n1().
const f2 = (v) => { const r = Math.round(v * 100) / 100; return (Object.is(r, -0) ? 0 : r).toString(); };
const f1 = (v) => { const r = Math.round(v * 10) / 10; return (Object.is(r, -0) ? 0 : r).toString(); };
const tricky = [0.1 + 0.2, 1e21, 1e-7, 1.5e-7, 123456789012345680000, 2 ** 53, 2 ** 53 + 2, 5e-324, 1.7976931348623157e308, 0.000001, 1.005, 2.675, 1.255, -1.005,
  -0.004, 0.005, 0.015, 0.025, 0.045, 99.995, -99.995, 120.30000000000001, 100 / 3, -2 / 3, 1e15 + 0.3, 4.35, 8.345, 0.5, -0.5, 1.45, 1e-6, 123e-20];
let seed = 7;
const rnd = () => { seed = (Math.imul(seed, 1103515245) + 12345) >>> 0; return seed / 4294967296; };
for (let i = 0; i < 300; i++) tricky.push((rnd() - 0.5) * 10 ** Math.floor(rnd() * 8 - 3));
for (let i = 0; i < 100; i++) tricky.push(Math.round((rnd() - 0.5) * 20000) / 1000 + 0.0005); // halfway-looking cases
const numbers = tricky.map((v) => [v, v.toString(), f2(v), f1(v)]);

// ---------------------------------------------------------------------------
// Path references for commands(): parsed segments and the flattened outline, from path.ts.

const pathCases = [
  'M20 20H80V80H20Z',
  'm20 20h60v60h-60zm10 10v40h40v-40z',
  'M10 10L20 10 30 20M40 40l5 5 5-5',
  'M10 60C20 30 40 30 50 60S80 90 90 60',
  'M10 50c10-30 30-30 40 0s30 30 40 0',
  'M10 50Q30 10 50 50T90 50',
  'M10 50q20-40 40 0t40 0t-20 20',
  'M10 50Q30 10 50 50L60 60T80 50',
  'M10 50C20 20 40 20 50 50T90 50',
  'M50 50S70 20 90 50',
  'M30 50A20 20 0 1 0 70 50A20 20 0 1 0 30 50Z',
  'M30 50A20 20 0 1 1 70 50A20 20 0 1 1 30 50Z',
  'M20 50a30 20 30 0 1 60 0a30 20 30 1 0 -60 0z',
  'M10 10A5 5 0 0 1 90 90',
  'M10 10A0 5 0 0 1 30 30',
  'M10 10A5 5 0 0 1 10 10L20 20',
  'M78.7 43A7 4.5 25 0 1 91.3 49A7 4.5 25 0 1 78.7 43Z',
  'M12 41H12A4 4 0 0 1 16 45V55A4 4 0 0 1 12 59H12A4 4 0 0 1 8 55V45A4 4 0 0 1 12 41Z',
  'M1.5.5.5 1.5L2-1e1-.5e-1+2',
  'M10 10H50V50ZL90 90',
  ...examples.map((d) => d.body.path),
];
const pathRefs = pathCases.map((d) => {
  const segs = parsePath(d);
  return { d, normalized: serializePath(segs), points: flattenPath(segs).map((poly) => poly.map(ns)) };
});

const sizes = {
  'avatars.json': write('avatars.json', { source: `open-bot-avatar ${version}`, avatars }),
  'svg.json': write('svg.json', { source: `open-bot-avatar ${version}`, svgs }),
  'validate.json': write('validate.json', { source: `open-bot-avatar ${version}`, cases: validation }),
  'motion.json': write('motion.json', {
    source: `open-bot-avatar ${version}`,
    constants: { BASE_YAW, BASE_PITCH, MOOD_MS, REST },
    hashes, poses, blends, progress, settles, pointers, follow: followSeq, animator: animatorRuns, colors: colorCases, numbers,
  }),
  'paths.json': write('paths.json', { source: `open-bot-avatar ${version}`, cases: pathRefs }),
};
let total = 0;
for (const [name, size] of Object.entries(sizes)) { total += size; console.log(`${name.padEnd(14)} ${(size / 1024).toFixed(0).padStart(6)} KB`); }
console.log(`${'total'.padEnd(14)} ${(total / 1024).toFixed(0).padStart(6)} KB -> ${out}`);

// Промо мода World Utilities (Nestworld Video) для TikTok: вертикальний 1080×1920, вигляд блокової гри.
// Маленький 3D-рендер блоків прямо на Canvas 2D: кімната з кубів з піксельними текстурами 16×16 (власні, не з гри),
// перспективна камера, коробкові персонажі, світло екрана на гранях. Текст — Press Start 2P (є кирилиця).
// На екрані — власна піксельна анімація (захід сонця, гори, зорі), без чужих кадрів.
window.FILM_SIZE = [1080, 1920];
window.BPM = 120;                     // восьма = 0.25 с
window.PUNCH = 0.02;
window.COUNTER_IN_TRANSITION = 'cut';

(() => {
const W = 1080, H = 1920;
const FONT = 'PressStart2P-400';
const clamp = (x, a = 0, b = 1) => Math.max(a, Math.min(b, x));
const ease = (x) => { x = clamp(x); return x * x * (3 - 2 * x); };
const backOut = (x) => { x = clamp(x); const c = 1.9; return 1 + (c + 1) * Math.pow(x - 1, 3) + c * Math.pow(x - 1, 2); };
const rnd = (i) => { const s = Math.sin(i * 127.1 + 311.7) * 43758.5453; return s - Math.floor(s); };
const hex = (h) => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)];
const rgb = (a) => `rgb(${a[0] | 0},${a[1] | 0},${a[2] | 0})`;
const mix = (a, b, k) => [a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, a[2] + (b[2] - a[2]) * k];

// ---------- текстури 16×16 (власні) ----------
function tex(fn) { const cv = document.createElement('canvas'); cv.width = cv.height = 16; const g = cv.getContext('2d'); fn(g); return cv; }
const P = (g, x, y, col, w = 1, h = 1) => { g.fillStyle = col; g.fillRect(x, y, w, h); };
const shadeHex = (h, k) => rgb(hex(h).map(v => clamp(v * k, 0, 255)));
const T = {};
function makeTextures() {
  T.brick = tex(g => { for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) P(g, x, y, shadeHex('#5d5f66', 0.85 + rnd(x * 31 + y * 17) * 0.3));
    for (let x = 0; x < 16; x++) { P(g, x, 7, '#33343a'); P(g, x, 15, '#33343a'); }
    for (let y = 0; y < 7; y++) { P(g, 7, y, '#33343a'); P(g, 15, y, '#33343a'); }
    for (let y = 8; y < 15; y++) { P(g, 3, y, '#33343a'); P(g, 11, y, '#33343a'); }
    for (let x = 0; x < 16; x++) { P(g, x, 0, '#71737b'); P(g, x, 8, '#71737b'); } });
  T.dark = tex(g => { for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) P(g, x, y, shadeHex('#3d3e45', 0.85 + rnd(x * 13 + y * 7 + 5) * 0.3));
    for (let x = 0; x < 16; x++) { P(g, x, 0, '#2a2b30'); P(g, x, 8, '#2a2b30'); } for (let y = 0; y < 16; y++) { P(g, 0, y, '#2a2b30'); P(g, 8, y, '#2a2b30'); } });
  T.floor = tex(g => { for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) P(g, x, y, shadeHex('#4b4448', 0.9 + rnd(x * 7 + y * 29 + 9) * 0.2));
    for (let i = 0; i < 16; i++) { P(g, i, 0, '#2e292c'); P(g, 0, i, '#2e292c'); P(g, i, 8, '#2e292c'); P(g, 8, i, '#2e292c'); P(g, i, 1, '#5b5458'); P(g, i, 9, '#5b5458'); } });
  T.plank = tex(g => { for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) P(g, x, y, shadeHex('#a8763f', 0.9 + rnd(x * 3 + y * 41 + 2) * 0.2));
    for (let x = 0; x < 16; x++) { P(g, x, 3, '#6e4a26'); P(g, x, 7, '#6e4a26'); P(g, x, 11, '#6e4a26'); P(g, x, 15, '#6e4a26'); }
    P(g, 4, 0, '#6e4a26', 1, 3); P(g, 12, 4, '#6e4a26', 1, 3); P(g, 7, 8, '#6e4a26', 1, 3); P(g, 2, 12, '#6e4a26', 1, 3); });
  T.frame = tex(g => { P(g, 0, 0, '#16161a', 16, 16); P(g, 0, 0, '#26262c', 16, 1); P(g, 0, 0, '#26262c', 1, 16); });
}

// ---------- 3D: камера, проєкція, грані ----------
let CAM = { x: 0, y: 2.6, z: 1, yaw: 0, pitch: -0.05, f: 760 };
function proj(p) {
  let x = p[0] - CAM.x, y = p[1] - CAM.y, z = p[2] - CAM.z;
  const cy = Math.cos(CAM.yaw), sy = Math.sin(CAM.yaw);
  [x, z] = [x * cy - z * sy, x * sy + z * cy];
  const cp = Math.cos(CAM.pitch), sp = Math.sin(CAM.pitch);
  [y, z] = [y * cp - z * sp, y * sp + z * cp];
  return [W / 2 + CAM.f * x / z, H * 0.47 - CAM.f * y / z, z];
}
const faces = [];
function face(pts, n, tx, shade, opt = {}) { faces.push({ pts, n, tx, shade, ...opt }); }
// Куб від (x,y,z) розміром (w,h,d); tx — текстура/колір або {top,side,front,back,bottom}
function box(x, y, z, w, h, d, tx, opt = {}) {
  const t = (k) => (tx && typeof tx === 'object' && !(tx instanceof HTMLCanvasElement)) ? (tx[k] || tx.side) : tx;
  const X = x + w, Y = y + h, Z = z + d;
  face([[x, Y, z], [X, Y, z], [X, Y, Z], [x, Y, Z]], [0, 1, 0], t('top'), 1.0, opt);
  face([[x, y, Z], [X, y, Z], [X, y, z], [x, y, z]], [0, -1, 0], t('bottom'), 0.5, opt);
  face([[x, Y, Z], [x, Y, z], [x, y, z], [x, y, Z]], [-1, 0, 0], t('side'), 0.6, opt);
  face([[X, Y, z], [X, Y, Z], [X, y, Z], [X, y, z]], [1, 0, 0], t('side'), 0.6, opt);
  face([[X, Y, z], [x, Y, z], [x, y, z], [X, y, z]], [0, 0, -1], t('back'), 0.8, opt);
  face([[x, Y, Z], [X, Y, Z], [X, y, Z], [x, y, Z]], [0, 0, 1], t('front'), 0.8, opt);
}

const RX0 = -6, RX1 = 6, RY1 = 8, RZ0 = -3, RZ1 = 14;
function room() {
  for (let x = RX0; x < RX1; x++) for (let y = 0; y < RY1; y++) face([[x, y + 1, RZ1], [x + 1, y + 1, RZ1], [x + 1, y, RZ1], [x, y, RZ1]], [0, 0, -1], T.brick, 0.8, { wall: 1 });
  for (let z = RZ0; z < RZ1; z++) for (let y = 0; y < RY1; y++) {
    face([[RX0, y + 1, z + 1], [RX0, y + 1, z], [RX0, y, z], [RX0, y, z + 1]], [1, 0, 0], T.brick, 0.6);
    face([[RX1, y + 1, z], [RX1, y + 1, z + 1], [RX1, y, z + 1], [RX1, y, z]], [-1, 0, 0], T.brick, 0.6);
  }
  for (let x = RX0; x < RX1; x++) for (let z = RZ0; z < RZ1; z++) {
    face([[x, 0, z + 1], [x + 1, 0, z + 1], [x + 1, 0, z], [x, 0, z]], [0, 1, 0], T.floor, 1.0);
    face([[x, RY1, z], [x + 1, RY1, z], [x + 1, RY1, z + 1], [x, RY1, z + 1]], [0, -1, 0], T.dark, 0.5);
  }
}

const scr = document.createElement('canvas'); scr.width = 160; scr.height = 90;
const sg = scr.getContext('2d');
function screenWall(sx, sy, bw, bh, on, blocks = Infinity) {
  let k = 0;
  for (let j = 0; j < bh; j++) for (let i = 0; i < bw; i++) {
    if (k++ >= blocks) continue;
    const x = sx + i, y = sy + j, z = RZ1 - 0.06;
    face([[x, y + 1, z], [x + 1, y + 1, z], [x + 1, y, z], [x, y, z]], [0, 0, -1], on > 0 ? 'screen' : T.frame, 1.0,
      { screen: { u0: i / bw, v0: 1 - (j + 1) / bh, u1: (i + 1) / bw, v1: 1 - j / bh }, glow: on });
  }
}

// Коробкова людина (вид ззаду); seat = 1 сидить, walk = 1 іде
function person(x, z, look, t, seat, walk = 0) {
  const s = 1 / 16, before = faces.length;
  const swing = walk ? Math.sin(t * 9) * 0.12 : 0;
  const shirt = look.shirt, pants = look.pants, hair = look.hair, skin = '#e0a87a';
  if (!seat) {
    box(x - 4 * s, 0, z - 2 * s + swing, 4 * s, 12 * s, 4 * s, pants);
    box(x, 0, z - 2 * s - swing, 4 * s, 12 * s, 4 * s, pants);
  }
  const by = seat ? 0.5 : 0.75;
  box(x - 4 * s, by, z - 2 * s, 8 * s, 12 * s, 4 * s, shirt);
  box(x - 8 * s, by, z - 2 * s - swing, 4 * s, 12 * s, 4 * s, { side: shirt, bottom: skin });
  box(x + 4 * s, by, z - 2 * s + swing, 4 * s, 12 * s, 4 * s, { side: shirt, bottom: skin });
  box(x - 4 * s, by + 12 * s, z - 4 * s, 8 * s, 8 * s, 8 * s, { side: hair, front: skin, bottom: skin });
  for (let i = before; i < faces.length; i++) faces[i].actor = 1;
}
const LOOK = { a: { shirt: '#2f80c8', pants: '#2a3b6b', hair: '#3a2618' }, b: { shirt: '#c84a3a', pants: '#3d3d46', hair: '#e8b83a' } };

// ---------- картинка екрана (власна піксельна анімація) ----------
function screenPicture(t) {
  const w = 160, h = 90, phase = (t * 0.22) % 1;
  const tops = ['#ff7b2e', '#ff4f8a', '#7a3cff', '#18c6c6'].map(hex), bots = ['#ffd36b', '#ffb0d0', '#ff7bd5', '#b8fff0'].map(hex);
  const k = phase * 4, i = Math.floor(k) % 4, j = (i + 1) % 4, f = ease(k - Math.floor(k));
  const top = mix(tops[i], tops[j], f), bot = mix(bots[i], bots[j], f);
  for (let y = 0; y < h; y += 2) { sg.fillStyle = rgb(mix(top, bot, y / h)); sg.fillRect(0, y, w, 2); }
  const sx = w * 0.62, sy = h * (0.42 + 0.06 * Math.sin(t * 0.7)), r = 16;
  sg.fillStyle = '#fff2b0';
  for (let yy = -r; yy <= r; yy += 2) for (let xx = -r; xx <= r; xx += 2) if (xx * xx + yy * yy <= r * r && ((sy + yy) / 2 | 0) % 3 !== 0) sg.fillRect(sx + xx, sy + yy, 2, 2);
  sg.fillStyle = '#ffffff';
  for (let s = 0; s < 16; s++) if (Math.sin(t * 6 + s * 2.1) > 0.2) sg.fillRect((rnd(s) * w) & ~1, (rnd(s + 40) * h * 0.4) & ~1, 2, 2);
  const ridge = (off, amp, base, col, speed) => { sg.fillStyle = col; for (let x = 0; x < w; x += 2) {
    const u = x + t * speed, yy = base + Math.round((Math.sin(u * 0.065 + off) * amp + Math.sin(u * 0.18 + off * 2) * amp * 0.4) / 2) * 2;
    sg.fillRect(x, yy, 2, h - yy); } };
  ridge(1.3, 6, h * 0.62, '#3b2a6b', 8); ridge(4.1, 8, h * 0.76, '#1d1640', 18);
  const cx = ((t * 28) % (w - 14)) & ~1, cy = (h * 0.72 - 6) & ~1, legs = Math.floor(t * 10) % 2;
  sg.fillStyle = '#111'; sg.fillRect(cx, cy, 10, 4); sg.fillRect(cx + 8, cy - 4, 4, 4); sg.fillRect(cx + (legs ? 0 : 2), cy + 4, 2, 2); sg.fillRect(cx + (legs ? 6 : 8), cy + 4, 2, 2);
  return mix(mix(top, bot, 0.5), [255, 240, 176], 0.15);
}

// ---------- малювання граней ----------
function poly(c, d) { c.beginPath(); c.moveTo(d[0][0], d[0][1]); for (let i = 1; i < d.length; i++) c.lineTo(d[i][0], d[i][1]); c.closePath(); }
function drawTri(c, img, s0, s1, s2, d0, d1, d2) {
  const den = (s1[0] - s0[0]) * (s2[1] - s0[1]) - (s2[0] - s0[0]) * (s1[1] - s0[1]);
  if (Math.abs(den) < 1e-9) return;
  const a = ((d1[0] - d0[0]) * (s2[1] - s0[1]) - (d2[0] - d0[0]) * (s1[1] - s0[1])) / den;
  const b = ((d1[1] - d0[1]) * (s2[1] - s0[1]) - (d2[1] - d0[1]) * (s1[1] - s0[1])) / den;
  const cc = ((d2[0] - d0[0]) * (s1[0] - s0[0]) - (d1[0] - d0[0]) * (s2[0] - s0[0])) / den;
  const d = ((d2[1] - d0[1]) * (s1[0] - s0[0]) - (d1[1] - d0[1]) * (s2[0] - s0[0])) / den;
  const e = d0[0] - a * s0[0] - cc * s0[1], f = d0[1] - b * s0[0] - d * s0[1];
  // трохи розширений трикутник, щоб між гранями не було щілин
  const mx = (d0[0] + d1[0] + d2[0]) / 3, my = (d0[1] + d1[1] + d2[1]) / 3, grow = (q) => [q[0] + Math.sign(q[0] - mx) * 0.7, q[1] + Math.sign(q[1] - my) * 0.7];
  const g0 = grow(d0), g1 = grow(d1), g2 = grow(d2);
  c.save(); c.beginPath(); c.moveTo(g0[0], g0[1]); c.lineTo(g1[0], g1[1]); c.lineTo(g2[0], g2[1]); c.closePath();
  c.clip(); c.setTransform(a, b, cc, d, e, f); c.drawImage(img, 0, 0); c.restore();
}
function render(c, light, ambient, glowAmount) {
  c.save(); c.imageSmoothingEnabled = false;
  c.fillStyle = '#000'; c.fillRect(0, 0, W, H);
  const S = [0, 4.5, RZ1];
  const items = [];
  for (const fc of faces) {
    const cx = (fc.pts[0][0] + fc.pts[2][0]) / 2, cy = (fc.pts[0][1] + fc.pts[2][1]) / 2, cz = (fc.pts[0][2] + fc.pts[2][2]) / 2;
    const toCam = [CAM.x - cx, CAM.y - cy, CAM.z - cz];
    if (fc.n[0] * toCam[0] + fc.n[1] * toCam[1] + fc.n[2] * toCam[2] <= 0) continue;
    const pp = fc.pts.map(proj);
    if (pp.some(p => p[2] < 0.15)) continue;
    const L = [S[0] - cx, S[1] - cy, S[2] - cz], dist = Math.hypot(...L) || 1;
    let k = Math.max(0, (fc.n[0] * L[0] + fc.n[1] * L[1] + fc.n[2] * L[2]) / dist) * 2.4 / (1 + (dist / 5.5) ** 2);
    if (fc.wall) k += 0.9 * Math.exp(-Math.hypot(cx - S[0], (cy - S[1]) * 1.4) / 3.2);
    k *= glowAmount;
    items.push({ fc, pp, depth: Math.hypot(...toCam) - (fc.tx === 'screen' ? 0.6 : 0), k });
  }
  items.sort((a, b) => b.depth - a.depth);
  for (const it of items) {
    const { fc, pp } = it, d = pp.map(p => [p[0], p[1]]);
    if (fc.tx === 'screen') {
      const s = fc.screen, u0 = s.u0 * 160, u1 = s.u1 * 160, v0 = s.v0 * 90, v1 = s.v1 * 90;
      drawTri(c, scr, [u0, v0], [u1, v0], [u1, v1], d[0], d[1], d[2]);
      drawTri(c, scr, [u0, v0], [u1, v1], [u0, v1], d[0], d[2], d[3]);
      if (fc.glow < 1) { c.save(); c.globalAlpha = 1 - fc.glow; c.fillStyle = '#050506'; poly(c, d); c.fill(); c.restore(); }
      c.save(); c.strokeStyle = 'rgba(0,0,0,0.3)'; c.lineWidth = 2; poly(c, d); c.stroke(); c.restore();
      continue;
    }
    if (typeof fc.tx === 'string') { c.fillStyle = fc.tx; poly(c, d); c.fill(); c.strokeStyle = fc.tx; c.lineWidth = 1; c.stroke(); }
    else { drawTri(c, fc.tx, [0, 0], [16, 0], [16, 16], d[0], d[1], d[2]); drawTri(c, fc.tx, [0, 0], [16, 16], [0, 16], d[0], d[2], d[3]); }
    const m = [0, 1, 2].map(ch => clamp((fc.actor ? ambient + 0.25 : ambient) * fc.shade + it.k * light[ch] / 255 * fc.shade + (fc.actor ? 0.18 * light[ch] / 255 : 0), 0, 1.6));
    c.save(); c.globalCompositeOperation = 'multiply'; c.fillStyle = rgb(m.map(v => Math.min(1, v) * 255)); poly(c, d); c.fill();
    const extra = m.map(v => Math.max(0, v - 1));
    if (extra.some(v => v > 0.01)) { c.globalCompositeOperation = 'lighter'; c.fillStyle = rgb(extra.map(v => v * 120)); poly(c, d); c.fill(); }
    c.restore();
  }
  c.restore();
}

function nameTag(c, x, y, z, label, col = '#ffffff') {
  const p = proj([x, y, z]); if (p[2] < 0.2) return;
  const size = Math.max(24, Math.round(CAM.f * 0.16 / p[2] / 4) * 4);
  c.save(); c.font = `${size}px "${FONT}"`; c.textAlign = 'center'; c.textBaseline = 'middle';
  const w = c.measureText(label).width + size;
  c.fillStyle = 'rgba(0,0,0,0.45)'; c.fillRect(p[0] - w / 2, p[1] - size * 0.8, w, size * 1.6);
  c.fillStyle = col; c.fillText(label, p[0], p[1] + size * 0.08); c.restore();
}

function text(c, s, y, size, col = '#f4f4f4', opt = {}) {
  c.save(); c.font = `${size}px "${FONT}"`; c.textAlign = 'center'; c.textBaseline = 'top';
  const x = opt.x ?? W / 2, sc = opt.scale ?? 1;
  c.globalAlpha = opt.alpha ?? 1; c.translate(x, y + size / 2); c.scale(sc, sc); c.translate(0, -size / 2);
  const sh = Math.max(4, Math.round(size / 8));
  c.fillStyle = '#3f3f3f'; c.fillText(s, sh, sh); c.fillStyle = col; c.fillText(s, 0, 0); c.restore();
}
function pop(c, s, y, size, lt, at, col, dur = 0.35) { const k = (lt - at) / dur; if (k < 0) return; text(c, s, y, size, col, { scale: backOut(k), alpha: clamp(k * 3) }); }
const GOLD = '#ffdd33', GREEN = '#7fff7f', GREY = '#cfd2d8';

function hallScene(c, t, opt) {
  faces.length = 0; room();
  const on = opt.on ?? 1, light = on > 0 ? screenPicture(opt.picT ?? t) : [0, 0, 0];
  screenWall(-4.5, 2, 9, 5, on, opt.blocks);
  if (opt.bench !== false) { for (let x = -3; x < 3; x++) box(x, 0, 5, 1, 0.5, 1, T.plank); }
  if (opt.people) opt.people(t);
  render(c, light.map(v => v * on), opt.ambient ?? 0.22, on);
  return light;
}
const seated = (t) => { person(-1.3, 5.5, LOOK.a, t, 1); person(1.3, 5.5, LOOK.b, t, 1); };

window.ERAS = [
  { id: 'n1_dark', eighths: 10, init() { makeTextures(); }, draw(c, lt, t) {
      CAM = { x: 0, y: 2.7, z: -1.2 + lt * 0.2 + 2.2 * ease((lt - 1.45) / 0.7), yaw: 0, pitch: -0.06, f: 760 };   // наїзд, коли вмикається екран
      const on = clamp((lt - 1.5) / 0.5), k = ease(lt / 1.4);
      hallScene(c, t, { on, ambient: 0.3, people: (tt) => {
        if (k < 1) { person(-5 + 3.7 * k, 5.5, LOOK.a, tt, 0, 1); person(5 - 3.7 * k, 5.5, LOOK.b, tt, 0, 1); } else seated(tt); } });
      pop(c, 'КІНОТЕАТР', 130, 88, lt, 0.25, GOLD);
      pop(c, 'У MINECRAFT', 270, 64, lt, 0.75, '#f4f4f4');
    } },
  { id: 'n2_play', eighths: 8, transition: { type: 'pixelate', dur: 0.3 }, draw(c, lt, t) {
      CAM = { x: 0, y: 2.7, z: -0.7 + lt * 0.3, yaw: 0, pitch: -0.06, f: 760 };
      hallScene(c, t, { people: seated });
      nameTag(c, -1.3, 1.65, 5.5, 'Igor'); nameTag(c, 1.3, 1.65, 5.5, 'Friend');
      pop(c, 'АНІМЕ', 130, 96, lt, 0.1, GOLD);
      pop(c, 'УКРАЇНСЬКОЮ', 270, 64, lt, 0.5, '#f4f4f4');
    } },
  { id: 'n3_sync', eighths: 8, transition: { type: 'whip', dur: 0.25 }, draw(c, lt, t) {
      CAM = { x: 3.2 - 6.4 * ease(lt / 2), y: 2.1, z: 1.8, yaw: -0.36 + 0.72 * ease(lt / 2), pitch: -0.04, f: 760 };   // проліт повз обох глядачів
      hallScene(c, t, { people: seated });
      const sec = 42 + Math.floor(lt), fr = Math.floor((lt % 1) * 24), clock = `00:${String(sec).padStart(2, '0')}:${String(fr).padStart(2, '0')}`;
      nameTag(c, -1.3, 1.75, 5.5, clock, GREEN); nameTag(c, 1.3, 1.75, 5.5, clock, GREEN);
      pop(c, 'У ВСІХ', 130, 96, lt, 0.15, GOLD);
      pop(c, 'КАДР У КАДР', 270, 64, lt, 0.55, '#f4f4f4');
      pop(c, '8 СЕРІЙ БЕЗ РОЗСИНХРОНУ', 1800, 32, lt, 1.0, GREY);
    } },
  { id: 'n4_glow', eighths: 8, transition: { type: 'pixelate', dur: 0.3 }, draw(c, lt, t) {
      CAM = { x: 2.5, y: 2.6, z: 2 + lt * 0.5, yaw: 0.1 - 0.75 * ease(lt / 2), pitch: -0.03, f: 760 };   // поворот до бічної стіни
      hallScene(c, t, { picT: t * 2.2, people: seated, ambient: 0.16 });
      const col = rgb(mix(screenPicture(t * 2.2), [255, 255, 255], 0.35));
      pop(c, 'ЕКРАН', 130, 96, lt, 0.1, GOLD);
      pop(c, 'СВІТИТЬ КАДРОМ', 270, 56, lt, 0.5, col);
    } },
  { id: 'n5_sound', eighths: 8, transition: { type: 'whip', dur: 0.25 }, draw(c, lt, t) {
      CAM = { x: 0, y: 2.9, z: 1.5 + lt * 0.2, yaw: 0, pitch: -0.08, f: 760 };
      hallScene(c, t, { people: seated });
      const p = proj([0, 4.5, RZ1]);
      c.save(); c.lineWidth = 8;
      for (let i = 0; i < 4; i++) { const r = ((lt * 520 + i * 260) % 1040), a = 1 - r / 1040;
        c.strokeStyle = `rgba(255,255,255,${a * 0.55})`; c.beginPath(); c.arc(p[0], p[1], r, 0.12 * Math.PI, 0.88 * Math.PI); c.stroke(); }
      c.restore();
      const note = (x, y, col) => { c.fillStyle = col; c.fillRect(x, y, 16, 16); c.fillRect(x + 12, y - 40, 8, 48); c.fillRect(x + 12, y - 40, 24, 8); };
      for (let i = 0; i < 7; i++) { const u = ((lt * 0.55 + i / 7) % 1);
        note(160 + rnd(i) * 760, 1250 - u * 700, ['#7fff7f', '#ffdd33', '#ff7fd0', '#7fd8ff'][i % 4]); }
      pop(c, 'ЗВУК', 130, 96, lt, 0.1, GOLD);
      pop(c, 'ЯК У СПРАВЖНІЙ ЗАЛІ', 270, 44, lt, 0.5, '#f4f4f4');
      pop(c, 'ЛУНА • СТІНИ • ПРОСТІР', 1800, 32, lt, 0.9, GREY);
    } },
  { id: 'n6_build', eighths: 8, transition: { type: 'pixelate', dur: 0.3 }, draw(c, lt, t) {
      CAM = { x: 0, y: 3.6, z: 3 - lt * 0.6, yaw: 0, pitch: 0.02, f: 760 };
      const blocks = Math.floor(clamp(lt / 1.4) * 45);
      hallScene(c, t, { blocks, bench: false });
      text(c, `${Math.min(9, blocks)} × ${Math.max(1, Math.ceil(blocks / 9))}`, 1800, 40, GREEN);
      pop(c, 'БУДЬ-ЯКИЙ', 130, 80, lt, 0.1, GOLD);
      pop(c, 'РОЗМІР', 250, 80, lt, 0.4, '#f4f4f4');
      pop(c, 'ДО 100 × 100 БЛОКІВ', 380, 36, lt, 0.9, GREY);
    } },
  { id: 'n7_outro', eighths: 12, transition: { type: 'iris', dur: 0.4 }, draw(c, lt, t) {
      CAM = { x: 0, y: 2.8 + 0.6 * ease(lt / 1.2), z: 3.5 - 4 * ease(lt / 1.2), yaw: 0, pitch: -0.05, f: 760 };   // відліт назад
      hallScene(c, t, { people: seated });
      c.fillStyle = 'rgba(0,0,0,0.5)'; c.fillRect(0, 60, W, 560);
      pop(c, 'WORLD', 100, 104, lt, 0.1, GOLD);
      pop(c, 'UTILITIES', 230, 88, lt, 0.35, GOLD);
      pop(c, 'NESTWORLD VIDEO', 380, 44, lt, 0.7, '#f4f4f4');
      if (lt > 1.2 && (Math.floor(lt * 2.5) % 2 === 0 || lt < 1.4)) text(c, 'ПОСИЛАННЯ В ПРОФІЛІ', 510, 36, GREEN);
    } },
];
})();

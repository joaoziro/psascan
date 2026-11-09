// Utilidades comuns: DOM, formatação, cores, mini-gráfico.

export const $ = (s, r = document) => r.querySelector(s);
export const $$ = (s, r = document) => [...r.querySelectorAll(s)];

// h('div.classe#id', {atributos}, filhos...) — textos entram como texto, nunca como HTML
export function h(tag, attrs, ...filhos) {
  const [nome, ...resto] = tag.split(/(?=[.#])/);
  const e = document.createElement(nome || 'div');
  for (const r of resto) r[0] === '.' ? e.classList.add(r.slice(1)) : (e.id = r.slice(1));
  if (attrs != null && (typeof attrs !== 'object' || attrs instanceof Node || Array.isArray(attrs))) { filhos.unshift(attrs); attrs = null; }
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else if (k === 'class') e.className = v;
    else if (k === 'style' && typeof v === 'object') Object.assign(e.style, v);
    else e.setAttribute(k, v === true ? '' : v);
  }
  for (const f of filhos.flat(9)) if (f != null && f !== false) e.append(f instanceof Node ? f : String(f));
  return e;
}

// troca os filhos ignorando null/false (replaceChildren escreveria "null")
export function por(el, ...filhos) {
  if (el) el.replaceChildren(...filhos.flat(9).filter(f => f != null && f !== false));
  return el;
}

export function svg(path, vb = '0 0 24 24') {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  s.setAttribute('viewBox', vb);
  s.innerHTML = path;      // só caminhos fixos do próprio código
  return s;
}

const nf = {};
export function fmt(v, dec = 0) {
  if (v == null || Number.isNaN(v)) return '—';
  nf[dec] ||= new Intl.NumberFormat('pt-BR', { minimumFractionDigits: dec, maximumFractionDigits: dec });
  return nf[dec].format(v);
}

// decimal com vírgula em textos vindos do servidor (ex. "OSCILANDO (~0.7 Hz)")
export const pt = s => (s == null ? s : String(s).replace(/(\d)\.(\d)/g, '$1,$2'));

export function duracao(s) {
  s = Math.max(0, Math.floor(s || 0));
  const hh = Math.floor(s / 3600), mm = Math.floor(s / 60) % 60, ss = s % 60;
  return (hh ? hh + ':' + String(mm).padStart(2, '0') : mm) + ':' + String(ss).padStart(2, '0');
}

export const ESTADOS = ['neutro', 'normal', 'atencao', 'fora'];
export const NOME_ESTADO = { normal: 'normal', atencao: 'atenção', fora: 'fora', neutro: '' };

export function estadoEl(est, texto) {
  if (!est || est === 'neutro') return null;
  return h('span.est.' + est, texto ?? NOME_ESTADO[est]);
}

export function css(nome) {
  return getComputedStyle(document.documentElement).getPropertyValue(nome).trim();
}

// cor da série presa ao parâmetro (nunca ao índice): o 1º livre entre os 8 slots
const slots = new Map();
export function corDe(ordem, ativos) {
  for (const o of [...slots.keys()]) if (ativos && !ativos.includes(o)) slots.delete(o);
  if (!slots.has(ordem)) {
    const usados = new Set(slots.values());
    let s = 1; while (usados.has(s) && s < 8) s++;
    slots.set(ordem, s);
  }
  return css('--s' + slots.get(ordem));
}

export function guardar(chave, valor) { try { localStorage.setItem('psascan.' + chave, JSON.stringify(valor)); } catch (e) {} }
export function ler(chave, padrao) {
  try { const v = localStorage.getItem('psascan.' + chave); return v == null ? padrao : JSON.parse(v); } catch (e) { return padrao; }
}

// mini-gráfico (60 s): linha neutra, faixa normal sombreada, último ponto marcado
export function sparkline(cv, xs, ys, faixa, cor) {
  const dpr = devicePixelRatio || 1, W = cv.clientWidth * dpr, H = cv.clientHeight * dpr;
  if (!W || !H) return;
  if (cv.width !== W) cv.width = W;
  if (cv.height !== H) cv.height = H;
  const g = cv.getContext('2d');
  g.clearRect(0, 0, W, H);
  const pts = [];
  for (let i = 0; i < xs.length; i++) if (ys[i] != null) pts.push([xs[i], ys[i]]);
  if (pts.length < 2) return;
  const x1 = pts[pts.length - 1][0], x0 = x1 - 60;
  let lo = Infinity, hi = -Infinity;
  for (const [x, y] of pts) if (x >= x0) { lo = Math.min(lo, y); hi = Math.max(hi, y); }
  if (faixa) { lo = Math.min(lo, faixa[0]); hi = Math.max(hi, faixa[1]); }
  if (hi - lo < 1e-9) { hi += 1; lo -= 1; }
  const pad = (hi - lo) * 0.08; lo -= pad; hi += pad;
  const X = x => (x - x0) / 60 * (W - 6 * dpr), Y = y => H - 2 * dpr - (y - lo) / (hi - lo) * (H - 4 * dpr);
  if (faixa) {
    g.fillStyle = css('--ok-soft');
    g.fillRect(0, Y(faixa[1]), W, Y(faixa[0]) - Y(faixa[1]));
  }
  g.strokeStyle = cor || css('--ink-2'); g.lineWidth = 1.6 * dpr; g.lineJoin = 'round';
  g.beginPath();
  let ant = null;
  for (const [x, y] of pts) {
    if (x < x0) continue;
    (ant == null || x - ant > 2) ? g.moveTo(X(x), Y(y)) : g.lineTo(X(x), Y(y));   // buraco nas quedas
    ant = x;
  }
  g.stroke();
  const [lx, ly] = pts[pts.length - 1];
  g.fillStyle = cor || css('--ink'); g.beginPath(); g.arc(X(lx), Y(ly), 3 * dpr, 0, 7); g.fill();
}

export function baixarTexto(nome, texto, tipo = 'text/plain') {
  const a = h('a', { href: URL.createObjectURL(new Blob([texto], { type: tipo })), download: nome });
  document.body.append(a); a.click(); a.remove();
}

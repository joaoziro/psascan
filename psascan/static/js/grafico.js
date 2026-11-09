// Gráficos em faixas empilhadas: um eixo Y por parâmetro, mesmo eixo de tempo,
// cursor e zoom sincronizados (nunca dois eixos Y no mesmo gráfico).
import { S, rotulo, texto, faixaTexto } from './app.js';
import { h, por, css, fmt, corDe, duracao } from './util.js';

let nChave = 0;
// rótulo curto de cada condição ("em marcha lenta"), vindo do perfil
export const CURTAS = new Proxy({}, { get: (_, k) => (S.condicoesRotulo || {})[k] ?? '' });

// faixa normal desenhada no gráfico: a 1ª regra (a mais específica), menos as que cobrem tudo desde o mínimo
function bandaDe(o) {
  const r = ((S.faixas[o] || {}).regras || [])[0], p = S.p[o];
  if (!r || !r.normal || (p && r.normal[0] <= p.min)) return null;
  return r;
}
const QUEDA = 2.0;      // s sem leitura = buraco na linha

export class Pilha {
  /* opts: altura, tempo: 'relogio' (epoch s) | 'relativo' (s desde o início),
     fixo: {ordem: [mín, máx]}, linhas: {ordem: [valores]}, bandas: {ordem: [[a, b, cor]]},
     aoCursor(x | null) */
  constructor(raiz, opts = {}) {
    this.o = Object.assign({ altura: 130, tempo: 'relogio' }, opts);
    this.chave = 'pilha' + (++nChave);
    this.leitor = h('div.leitor');
    this.caixa = h('div.faixas');
    this.raiz = raiz;
    raiz.append(this.leitor, this.caixa);
    this.lanes = [];
    this.ordens = [];
    this.dados = { x: [], series: {}, marcas: [], quedas: [] };
    this.zoom = null;
    this.janela = null;          // s: ao vivo, mostra só o fim
    this.idx = null;
    this.caixa.addEventListener('dblclick', () => this.semZoom());
    this.ro = new ResizeObserver(() => this.tamanho());
    this.ro.observe(this.caixa);
    // zoom do navegador ou troca de monitor mudam a densidade de pixels: refaz o tamanho
    this._redim = () => requestAnimationFrame(() => this.tamanho(true));
    addEventListener('resize', this._redim);
    this._dppx();
  }

  setParams(ordens) {
    this.ordens = ordens.slice();
    for (const l of this.lanes) l.u.destroy();
    this.lanes = [];
    por(this.caixa);
    ordens.forEach(o => corDe(o, ordens));
    ordens.forEach((o, k) => this.lanes.push(this._lane(o, k === ordens.length - 1)));
    this._aplicar(true);
    this._leitor();
  }

  _lane(o, ultima) {
    const p = S.p[o] || {}, cor = corDe(o, this.ordens);
    const rg0 = bandaDe(o);
    const el = h('div.faixa', h('div.nome', h('i', { style: { background: cor } }), rotulo(o),
      p.unidade ? h('small', p.unidade) : null,
      rg0 && !(this.o.bandas && this.o.bandas[o]) ? h('small', 'ref ' + faixaTexto({ r: rg0 }, p) + ' ' + (CURTAS[rg0.quando] || '')) : null));
    this.caixa.append(el);
    const eu = this;
    const fixo = this.o.fixo && this.o.fixo[o];
    const W = Math.max(200, this.caixa.clientWidth || 600);
    const relativo = this.o.tempo === 'relativo';
    const u = new uPlot({
      width: W, height: this.o.altura + (ultima ? 30 : 0),
      padding: [22, 12, 0, 0],
      legend: { show: false },
      cursor: { sync: { key: this.chave, setSeries: false }, drag: { x: true, y: false, setScale: false },
        points: { size: 8, fill: cor, stroke: css('--surface'), width: 2 } },
      select: { show: true },
      scales: {
        x: { time: !relativo },
        y: { range: (uu, mn, mx) => {
          if (fixo) return fixo;
          const rg = bandaDe(o);
          if (rg) { mn = Math.min(mn, rg.normal[0]); mx = Math.max(mx, rg.normal[1]); }
          if (mn == null || !isFinite(mn)) return [0, 1];
          if (mx - mn < 1e-6) { mn -= 1; mx += 1; }
          const pad = (mx - mn) * 0.1;
          return [mn - pad, mx + pad];
        } },
      },
      axes: [
        { show: ultima, stroke: css('--muted'), font: '12px ' + css('--ui'), size: 30,
          grid: { stroke: css('--grid'), width: 1 }, ticks: { stroke: css('--axis'), width: 1, size: 4 },
          space: 70,
          values: relativo ? (uu, vs) => vs.map(v => duracao(v)) :
            (uu, vs) => vs.map(v => new Date(v * 1000).toLocaleTimeString('pt-BR')) },
        { stroke: css('--muted'), font: '12px ' + css('--ui'), size: 54, gap: 4,
          grid: { stroke: css('--grid'), width: 1 }, ticks: { show: false },
          values: (uu, vs) => vs.map(v => fmt(v, p.decimais > 1 ? 1 : (Math.abs(v) < 10 && v % 1 ? 1 : 0))) },
      ],
      series: [{}, { stroke: cor, width: 2, points: { show: false }, spanGaps: false }],
      hooks: {
        draw: [uu => eu._desenhar(uu, o)],
        setSelect: [uu => {
          if (uu.select.width < 4) return;
          const mn = uu.posToVal(uu.select.left, 'x'), mx = uu.posToVal(uu.select.left + uu.select.width, 'x');
          eu.zoom = [mn, mx];
          for (const l of eu.lanes) l.u.setScale('x', { min: mn, max: mx });
          uu.setSelect({ width: 0, height: 0 }, false);
          eu._leitor();
        }],
        setCursor: [uu => {
          if (uu !== (eu.lanes[0] && eu.lanes[0].u) && eu.lanes[0]) return;
          eu.idx = uu.cursor.idx;
          eu._leitor();
          if (eu.o.aoCursor) eu.o.aoCursor(eu.idx == null ? null : eu._x[eu.idx]);
        }],
      },
    }, [[], []], el);
    return { o, u, el };
  }

  _desenhar(u, o) {
    const g = u.ctx, { left, top, width, height } = u.bbox;
    g.save();
    g.beginPath(); g.rect(left, top, width, height); g.clip();
    const Y = v => u.valToPos(v, 'y', true), X = v => u.valToPos(v, 'x', true);
    const rg = bandaDe(o);
    const bandas = (this.o.bandas && this.o.bandas[o]) || (rg ? [[rg.normal[0], rg.normal[1], css('--ok-soft')]] : []);
    for (const [a, b, cor] of bandas) { g.fillStyle = cor; g.fillRect(left, Y(b), width, Y(a) - Y(b)); }
    const dpr = devicePixelRatio || 1;
    for (const v of (this.o.linhas && this.o.linhas[o]) || []) {
      g.strokeStyle = css('--muted'); g.setLineDash([6 * dpr, 5 * dpr]); g.lineWidth = dpr;
      g.beginPath(); g.moveTo(left, Y(v)); g.lineTo(left + width, Y(v)); g.stroke(); g.setLineDash([]);
    }
    for (const [a, b] of this.dados.quedas || []) {        // hachura = sem dados (queda do Bluetooth)
      const x0 = X(a), x1 = X(b);
      g.save(); g.beginPath(); g.rect(x0, top, x1 - x0, height); g.clip();
      g.strokeStyle = css('--line-2'); g.lineWidth = dpr;
      for (let x = x0 - height; x < x1; x += 7 * dpr) { g.beginPath(); g.moveTo(x, top + height); g.lineTo(x + height, top); g.stroke(); }
      g.restore();
    }
    for (const m of this.dados.marcas || []) {
      const x = X(m.x);
      g.strokeStyle = css('--accent'); g.lineWidth = 2 * dpr;
      g.beginPath(); g.moveTo(x, top); g.lineTo(x, top + height); g.stroke();
      if (u === this.lanes[0].u) {        // rótulo embaixo, com fundo, para não cobrir o nome da faixa
        g.font = `600 ${12 * dpr}px ${css('--ui')}`;
        const w = g.measureText(m.texto).width + 8 * dpr, y = top + height - 20 * dpr;
        g.fillStyle = css('--surface-3'); g.fillRect(x + 3 * dpr, y, w, 17 * dpr);
        g.fillStyle = css('--accent'); g.fillText(m.texto, x + 7 * dpr, y + 13 * dpr);
      }
    }
    if (this.marcaX != null) {
      const x = X(this.marcaX);
      g.strokeStyle = css('--ink'); g.lineWidth = 2 * dpr;
      g.beginPath(); g.moveTo(x, top); g.lineTo(x, top + height); g.stroke();
    }
    g.restore();
  }

  // dados: {x: [...], series: {ordem: [...]}, marcas: [{x, texto}], quedas: [[x0, x1]]}
  setDados(d) {
    this.dados = d;
    this._aplicar(!this.zoom);
  }

  _aplicar(escala) {
    const d = this.dados, xs = d.x || [];
    // buraco nas quedas: um ponto nulo entre leituras separadas por mais de 2 s
    const x2 = [], ix = [];
    for (let i = 0; i < xs.length; i++) {
      if (i && xs[i] - xs[i - 1] > QUEDA) { x2.push((xs[i] + xs[i - 1]) / 2); ix.push(-1); }
      x2.push(xs[i]); ix.push(i);
    }
    this._x = x2; this._ix = ix;
    for (const l of this.lanes) {
      const s = d.series[l.o] || [];
      l.u.setData([x2, ix.map(i => i < 0 ? null : (s[i] ?? null))], false);
    }
    let mn, mx;
    if (this.zoom) [mn, mx] = this.zoom;
    else if (x2.length) {
      mx = x2[x2.length - 1];
      mn = this.janela ? mx - this.janela : x2[0];
      if (mx - mn < 1) mn = mx - 1;
    }
    if (mn != null) for (const l of this.lanes) l.u.setScale('x', { min: mn, max: mx });
    else for (const l of this.lanes) l.u.redraw();
    if (this.idx == null) this._leitor();
  }

  semZoom() { this.zoom = null; this._aplicar(true); }

  marcar(x) {      // linha vertical da reprodução
    this.marcaX = x;
    for (const l of this.lanes) l.u.redraw(false);
    if (x != null && this._x) {
      let i = this._x.findIndex(v => v >= x);
      if (i < 0) i = this._x.length - 1;
      this._leitorEm(i);
    }
  }

  _leitor() { this._leitorEm(this.idx); }

  _leitorEm(idx) {
    const x2 = this._x || [];
    let i = idx;
    if (i == null) i = x2.length - 1;
    while (i > 0 && this._ix[i] < 0) i--;
    const j = this._ix ? this._ix[i] : -1;
    const hora = x2[i] == null ? '—' : this.o.tempo === 'relativo' ? duracao(x2[i]) :
      new Date(x2[i] * 1000).toLocaleTimeString('pt-BR');
    por(this.leitor, h('span.hora', hora),
      ...this.ordens.map(o => {
        const v = j >= 0 ? (this.dados.series[o] || [])[j] : null;
        const t = texto(o, v);
        return h('span.it', h('i.tr', { style: { background: corDe(o, this.ordens) } }), h('b', t.v + (t.un ? ' ' + t.un : '')), h('span', rotulo(o)));
      }),
      this.zoom ? h('span.dica-zoom', 'zoom · duplo clique para voltar') : null);
  }

  _dppx() {
    this._mq = matchMedia(`(resolution: ${devicePixelRatio}dppx)`);
    this._mqf = () => { this._redim(); this._dppx(); };
    this._mq.addEventListener('change', this._mqf, { once: true });
  }

  tamanho(forcar) {
    const W = this.caixa.clientWidth;
    if (!W) return;
    this.lanes.forEach((l, k) => {
      const H = this.o.altura + (k === this.lanes.length - 1 ? 30 : 0);
      if (forcar || l.u.width !== W || l.u.height !== H) l.u.setSize({ width: W, height: H });
    });
  }

  destruir() {
    this.ro.disconnect();
    removeEventListener('resize', this._redim);
    if (this._mq) this._mq.removeEventListener('change', this._mqf);
    for (const l of this.lanes) l.u.destroy();
    this.lanes = [];
  }
}

// Dispersão simples em canvas (ex. avanço × RPM), com o ponto mais próximo no toque/mouse.
export function dispersao(cv, xs, ys, { xr, yr, xl, yl, xd = 0, yd = 0 }) {
  const dpr = devicePixelRatio || 1, W = cv.clientWidth * dpr, H = cv.clientHeight * dpr;
  cv.width = W; cv.height = H;
  const g = cv.getContext('2d');
  const L = 50 * dpr, B = 34 * dpr, T = 10 * dpr, R = 12 * dpr;
  const pts = [];
  for (let i = 0; i < xs.length; i++) if (xs[i] != null && ys[i] != null) pts.push([xs[i], ys[i]]);
  const ext = (a, r) => { if (r) return r; let mn = Math.min(...a), mx = Math.max(...a); if (!isFinite(mn)) return [0, 1]; if (mx - mn < 1e-6) { mn -= 1; mx += 1; } const p = (mx - mn) * .08; return [mn - p, mx + p]; };
  const [x0, x1] = ext(pts.map(p => p[0]), xr), [y0, y1] = ext(pts.map(p => p[1]), yr);
  const X = x => L + (x - x0) / (x1 - x0) * (W - L - R), Y = y => H - B - (y - y0) / (y1 - y0) * (H - B - T);
  function desenhar(destaque) {
    g.clearRect(0, 0, W, H);
    g.font = `${12 * dpr}px ${css('--ui')}`; g.fillStyle = css('--muted'); g.strokeStyle = css('--grid'); g.lineWidth = dpr;
    for (let k = 0; k <= 4; k++) {
      const xv = x0 + (x1 - x0) * k / 4, yv = y0 + (y1 - y0) * k / 4;
      g.beginPath(); g.moveTo(X(xv), T); g.lineTo(X(xv), H - B); g.stroke();
      g.beginPath(); g.moveTo(L, Y(yv)); g.lineTo(W - R, Y(yv)); g.stroke();
      g.textAlign = 'center'; g.fillText(fmt(xv, xd), X(xv), H - B + 16 * dpr);
      g.textAlign = 'right'; g.fillText(fmt(yv, yd), L - 6 * dpr, Y(yv) + 4 * dpr);
    }
    g.textAlign = 'center'; g.fillText(xl, (L + W - R) / 2, H - 4 * dpr);
    g.save(); g.translate(12 * dpr, (T + H - B) / 2); g.rotate(-Math.PI / 2); g.fillText(yl, 0, 0); g.restore();
    g.fillStyle = css('--s1'); g.globalAlpha = .55;
    for (const [x, y] of pts) { g.beginPath(); g.arc(X(x), Y(y), 3.5 * dpr, 0, 7); g.fill(); }
    g.globalAlpha = 1;
    if (destaque) {
      const [x, y] = destaque;
      g.strokeStyle = css('--surface'); g.fillStyle = css('--s1'); g.lineWidth = 2 * dpr;
      g.beginPath(); g.arc(X(x), Y(y), 6 * dpr, 0, 7); g.fill(); g.stroke();
      const t = `${xl.split(' ')[0]} ${fmt(x, xd)} · ${yl.split(' ')[0]} ${fmt(y, yd)}`;
      g.font = `600 ${13 * dpr}px ${css('--ui')}`; g.textAlign = X(x) > W / 2 ? 'right' : 'left';
      g.fillStyle = css('--ink'); g.fillText(t, X(x) + (X(x) > W / 2 ? -10 : 10) * dpr, Y(y) - 10 * dpr);
    }
  }
  desenhar();
  cv.onpointermove = e => {
    const r = cv.getBoundingClientRect(), mx = (e.clientX - r.left) * dpr, my = (e.clientY - r.top) * dpr;
    let best = null, bd = (24 * dpr) ** 2;
    for (const p of pts) { const d = (X(p[0]) - mx) ** 2 + (Y(p[1]) - my) ** 2; if (d < bd) { bd = d; best = p; } }
    desenhar(best);
  };
  cv.onpointerleave = () => desenhar();
}

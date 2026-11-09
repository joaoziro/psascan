// Sessões gravadas: lista, reprodução com linha do tempo, comparação de duas e exportação.
import { S, ord, on, api, toast, texto, rotulo } from './app.js';
import { h, por, pt, fmt, duracao, guardar, ler, ESTADOS, corDe } from './util.js';
import { Pilha } from './grafico.js';

let raiz, pilhas = [], timer = null, desligar = [];

function limpar() {
  pilhas.forEach(p => p.destruir()); pilhas = [];
  clearInterval(timer); timer = null;
  desligar.forEach(f => f()); desligar = [];
}

// ---------- lista ----------
async function lista() {
  const sess = await api('/api/sessoes');
  const marcadas = new Set();
  const btnComp = h('button.btn', { disabled: true, onclick: () => { const [a, b] = [...marcadas]; location.hash = '#/sessoes/comparar/' + a + '/' + b; } }, 'Comparar 2 selecionadas');
  raiz.append(h('div.cartao',
    h('div.linha', h('h3', 'Gravações'), h('div.esp'), btnComp),
    sess.length ? h('div.rolavel', h('table.tab',
      h('thead', h('tr', h('th', ''), h('th', 'Sessão'), h('th', 'Duração'), h('th.esconder-cel', 'Leituras'), h('th', 'Eventos'), h('th.esconder-cel', 'Quedas'), h('th.esconder-cel', 'Notas'), h('th', ''))),
      h('tbody', ...sess.map(s => h('tr.clic', { onclick: e => { if (!e.target.closest('input,a')) location.hash = '#/sessoes/' + s.id; } },
        h('td', h('input', { type: 'checkbox', 'aria-label': 'Selecionar para comparar', style: { width: '22px', height: '22px' },
          onchange: e => { e.target.checked ? marcadas.add(s.id) : marcadas.delete(s.id); if (marcadas.size > 2) { const p = [...marcadas][0]; marcadas.delete(p); raiz.querySelector(`input[data-id="${p}"]`).checked = false; } btnComp.disabled = marcadas.size !== 2; },
          'data-id': s.id })),
        h('td', h('div', { style: { fontWeight: '600' } }, s.nome || (s.antiga ? 'gravação antiga' : 'sem nome')), h('div.mut', s.inicio || s.id)),
        h('td.n', duracao(s.duracao)), h('td.n.esconder-cel', s.leituras), h('td.n', s.n_eventos), h('td.n.esconder-cel', s.n_quedas),
        h('td.esconder-cel.mut', (s.notas || '').slice(0, 80)),
        h('td', h('a.btn.peq.fantasma', { href: '/api/sessoes/' + s.id + '.csv', download: '' }, 'CSV'))))))) :
      h('div.vazio', 'Nenhuma gravação ainda. Use Gravar (ou "marcar evento") durante a leitura.')));
}

// ---------- uma sessão ----------
function minigrade(d, i) {
  return S.params.map(p => {
    const v = d.series[p.ordem][i];
    if (v == null) return null;
    const est = ESTADOS[(d.estados[p.ordem] || [])[i] || 0];
    const t = texto(p.ordem, v);
    return h('div.m.' + est, h('div.k', p.curto), h('div.v', t.v, t.un ? h('small.mut', ' ' + t.un) : null));
  });
}

function linhaTempo(d, aoIr) {
  const dur = d.t.length ? d.t[d.t.length - 1] : 1;
  const cab = h('div.cab');
  const lt = h('div.linha-tempo', { role: 'slider', 'aria-label': 'Posição na gravação', tabindex: 0 },
    ...(d.meta.quedas || []).map(([a, b]) => h('div.q', { style: { left: a / dur * 100 + '%', width: Math.max(0.3, (b - a) / dur * 100) + '%' }, title: 'sem dados (queda)' })),
    ...(d.meta.eventos || []).map(e => h('div.ev', { style: { left: e.s / dur * 100 + '%' }, title: e.texto }, h('b', e.texto))),
    cab);
  const ir = ev => { const r = lt.getBoundingClientRect(); aoIr(Math.max(0, Math.min(1, (ev.clientX - r.left) / r.width)) * dur); };
  lt.addEventListener('pointerdown', e => { lt.setPointerCapture(e.pointerId); ir(e); lt.onpointermove = ir; });
  lt.addEventListener('pointerup', () => { lt.onpointermove = null; });
  lt.addEventListener('keydown', e => { if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') aoIr(null, e.key === 'ArrowRight' ? 5 : -5); });
  return { el: lt, mover: s => { cab.style.left = s / dur * 100 + '%'; } };
}

async function detalhe(sid) {
  const d = await api('/api/sessoes/' + encodeURIComponent(sid));
  const m = d.meta, L = d.lambda;
  const dur = d.t.length ? d.t[d.t.length - 1] : 0;
  let pos = 0, vel = 1;
  let sel = ler('sessao-sel', [S.lambda, ord('rpm'), ord('injecao'), ord('agua')]).filter(o => S.p[o]);
  const nome = h('input', { type: 'text', value: m.nome || '', placeholder: 'Nome da sessão', style: { flex: '1', minWidth: '200px' } });
  const notas = h('textarea', { placeholder: 'Notas (o que foi feito, condições, conclusões…)' }, m.notas || '');
  const grade = h('div.minigrade'), relogio = h('span.num', { style: { fontSize: '22px' } });
  const lt = linhaTempo(d, (s, passo) => ir(s == null ? pos + passo : s));
  const playBtn = h('button.btn.pri', { onclick: () => tocar(!timer) }, 'Reproduzir');
  function ir(s) {
    pos = Math.max(0, Math.min(dur, s));
    let i = d.t.findIndex(x => x >= pos); if (i < 0) i = d.t.length - 1;
    por(grade, minigrade(d, i));
    relogio.textContent = duracao(pos) + ' / ' + duracao(dur) + (d.hora[i] ? ' · ' + d.hora[i] : '');
    lt.mover(pos);
    pilhas.forEach(p => p.marcar(pos));
  }
  function tocar(sim) {
    clearInterval(timer); timer = null;
    playBtn.textContent = sim ? 'Pausar' : 'Reproduzir';
    if (!sim) return;
    if (pos >= dur) pos = 0;
    timer = setInterval(() => { ir(pos + 0.1 * vel); if (pos >= dur) tocar(false); }, 100);
  }
  const caixaPilha = h('div');
  const seletor = h('div.seletor');
  function montarPilha() {
    pilhas.forEach(p => p.destruir()); pilhas = [];
    por(caixaPilha);
    por(seletor, ...S.params.map(p => h('button' + (sel.includes(p.ordem) ? '.on' : ''), { onclick: () => {
      sel = sel.includes(p.ordem) ? sel.filter(x => x !== p.ordem) : [...sel, p.ordem].slice(0, 8);
      guardar('sessao-sel', sel); montarPilha();
    } }, h('i.tr', { style: { background: sel.includes(p.ordem) ? corDe(p.ordem, sel) : '' } }), p.curto)));
    if (!sel.length) return;
    const pl = new Pilha(caixaPilha, { altura: innerWidth < 640 ? 90 : 110, tempo: 'relativo', aoCursor: x => { if (x != null && !timer) { pos = x; lt.mover(x); } } });
    pl.setParams(sel);
    pl.setDados({ x: d.t, series: d.series, marcas: (m.eventos || []).map(e => ({ x: e.s, texto: e.texto })), quedas: m.quedas || [] });
    pilhas.push(pl);
    pl.marcar(pos);
  }
  const tempoLambda = Object.entries(L.tempo || {}).sort((a, b) => b[1] - a[1]);
  raiz.append(
    h('div.linha', h('a.btn.fantasma.peq', { href: '#/sessoes' }, '← Sessões'), h('div.esp'),
      h('a.btn.peq', { href: '/api/sessoes/' + sid + '.csv', download: '' }, 'Exportar CSV (com eventos)')),
    h('div.cartao',
      h('div.linha', nome, h('button.btn', { onclick: async () => {
        try { await api('/api/sessao', { id: sid, nome: nome.value, notas: notas.value }); toast('Sessão atualizada.'); } catch (e) { toast(e.message, true); }
      } }, 'Salvar')),
      h('div', { style: { marginTop: '10px' } }, notas),
      h('p.mut', { style: { fontSize: '13.5px', marginBottom: 0 } },
        [m.inicio, duracao(m.duracao) + ' de gravação', m.leituras + ' leituras', (m.quedas || []).length + ' queda(s)',
          m.reconexoes != null ? m.reconexoes + ' reconexões' : null, m.captura ? 'captura ' + m.captura : null].filter(Boolean).join(' · '))),
    h('div.cartao', h('h3', 'Sonda lambda (motor quente)'),
      h('div.tiles',
        h('div.tile', h('div.k', 'veredito geral'), h('div.v', { style: { fontSize: '22px' } }, pt(L.resumo.diagnostico))),
        h('div.tile', h('div.k', 'trocas/s'), h('div.v', L.resumo.trocas_s != null ? fmt(L.resumo.trocas_s, 2) : '—')),
        h('div.tile', h('div.k', 'tempo rico'), h('div.v', L.resumo.pct_rico ?? '—', h('small', '%'))),
        h('div.tile', h('div.k', 'média'), h('div.v', L.resumo.media ?? '—', h('small', 'mV')))),
      tempoLambda.length ? h('p.ink2', { style: { marginBottom: 0 } }, 'Tempo em cada veredito (janelas de 10 s): ' + tempoLambda.map(([k, v]) => k.toLowerCase() + ' ' + v + '%').join(' · ')) : null),
    h('div.cartao',
      h('div.linha', playBtn, h('div.seg', ...[1, 4, 16, 60].map(v => h('button' + (v === vel ? '.ativo' : ''), { onclick: e => { vel = v; for (const b of e.target.parentNode.children) b.classList.toggle('ativo', b === e.target); } }, v + '×'))),
        h('div.esp'), relogio),
      h('div', { style: { margin: '14px 0' } }, lt.el),
      grade),
    h('div.cartao', seletor, h('div', { style: { marginTop: '10px' } }, caixaPilha)),
    (m.eventos || []).length ? h('div.cartao', h('h3', 'Eventos'), h('ul.lista-ev', ...m.eventos.map(e =>
      h('li', h('button.btn.peq.fantasma', { onclick: () => { ir(e.s); window.scrollTo({ top: 0, behavior: 'smooth' }); } }, duracao(e.s)), h('span', e.texto), h('span.mut', e.hora))))) : null);
  montarPilha();
  ir(0);
}

// ---------- comparar ----------
function stats(d, o, filtro) {
  const v = d.series[o].filter((x, i) => x != null && filtro(d, i));
  if (!v.length) return null;
  return { n: v.length, media: v.reduce((a, b) => a + b, 0) / v.length, min: Math.min(...v), max: Math.max(...v) };
}
const FILTROS = {
  tudo: ['tudo', () => true],
  quente: ['motor quente', (d, i) => d.series[ord('rpm')][i] > 400 && d.series[ord('agua')][i] >= 80],
  lenta: ['marcha lenta quente', (d, i) => d.series[ord('rpm')][i] > 400 && d.series[ord('agua')][i] >= 80 && d.series[ord('pedal')][i] === 16 && !d.series[ord('velocidade')][i]],
};

async function comparar(a, b) {
  const [A, B] = await Promise.all([api('/api/sessoes/' + a), api('/api/sessoes/' + b)]);
  let filtro = 'lenta', sel = [S.lambda];
  const tab = h('div.rolavel'), graf = h('div.dois');
  const nomeDe = d => d.meta.nome || d.meta.inicio || d.meta.id;
  function tabela() {
    const f = FILTROS[filtro][1];
    por(tab, h('table.tab',
      h('thead', h('tr', h('th', 'Parâmetro'), h('th', 'A · média'), h('th', 'B · média'), h('th', 'Diferença'), h('th.esconder-cel', 'A · mín–máx'), h('th.esconder-cel', 'B · mín–máx'))),
      h('tbody', ...S.params.filter(p => p.tipo === 'num').map(p => {
        const sa = stats(A, p.ordem, f), sb = stats(B, p.ordem, f);
        if (!sa && !sb) return null;
        const d = p.decimais, un = p.unidade ? ' ' + p.unidade : '';
        const dif = sa && sb ? sb.media - sa.media : null;
        return h('tr.clic', { onclick: () => { sel = [p.ordem]; graficos(); } },
          h('td', p.curto), h('td.n', sa ? fmt(sa.media, d) + un : '—'), h('td.n', sb ? fmt(sb.media, d) + un : '—'),
          h('td.n', dif == null ? '—' : (dif > 0 ? '+' : '') + fmt(dif, d)),
          h('td.n.esconder-cel', sa ? fmt(sa.min, d) + '–' + fmt(sa.max, d) : '—'), h('td.n.esconder-cel', sb ? fmt(sb.min, d) + '–' + fmt(sb.max, d) : '—'));
      }))));
  }
  function graficos() {
    pilhas.forEach(p => p.destruir()); pilhas = [];
    por(graf);
    for (const [d, n] of [[A, 'A'], [B, 'B']]) {
      const c = h('div.cartao', h('h3', n + ' · ' + nomeDe(d) + ' · ' + rotulo(sel[0])));
      graf.append(c);
      const p = new Pilha(c, { altura: 150, tempo: 'relativo', fixo: sel[0] === S.lambda ? { [S.lambda]: [0, 1000] } : null, linhas: sel[0] === S.lambda ? { [S.lambda]: [450] } : null });
      p.setParams(sel);
      p.setDados({ x: d.t, series: d.series, marcas: (d.meta.eventos || []).map(e => ({ x: e.s, texto: e.texto })), quedas: d.meta.quedas || [] });
      pilhas.push(p);
    }
  }
  raiz.append(
    h('div.linha', h('a.btn.fantasma.peq', { href: '#/sessoes' }, '← Sessões')),
    h('div.dois', ...[[A, 'A'], [B, 'B']].map(([d, n]) => h('div.cartao', h('h3', 'Sessão ' + n),
      h('div', { style: { fontWeight: '600', fontSize: '17px' } }, nomeDe(d)),
      h('div.mut', d.meta.inicio + ' · ' + duracao(d.meta.duracao)),
      h('p', { style: { marginBottom: 0 } }, 'Lambda: ', h('b', pt(d.lambda.resumo.diagnostico)),
        d.lambda.resumo.trocas_s != null ? ' · ' + fmt(d.lambda.resumo.trocas_s, 2) + ' trocas/s' : '')))),
    h('div.cartao', h('div.linha', h('h3', 'Médias'), h('div.esp'),
      h('div.seg', ...Object.entries(FILTROS).map(([k, [n]]) => h('button' + (k === filtro ? '.ativo' : ''), { onclick: e => { filtro = k; for (const x of e.target.parentNode.children) x.classList.toggle('ativo', x === e.target); tabela(); } }, n)))),
      h('p.mut', { style: { fontSize: '13px' } }, 'Toque numa linha para ver o parâmetro nas duas sessões. "Marcha lenta quente" compara só os trechos parados com o motor quente — o melhor para antes/depois de um conserto.'),
      tab),
    graf);
  tabela();
  graficos();
}

export default {
  async montar(el, args) {
    raiz = el;
    limpar();
    if (args[0] === 'comparar' && args[1] && args[2]) return comparar(args[1], args[2]);
    if (args[0]) return detalhe(args[0]);
    return lista();
  },
  desmontar() { limpar(); },
};

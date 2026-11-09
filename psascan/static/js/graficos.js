// Gráficos: escolher parâmetros, faixas empilhadas sincronizadas, ao vivo ou de uma sessão gravada.
import { S, on, api, toast, rotulo, ords } from './app.js';
import { $, h, por, guardar, ler, corDe } from './util.js';
import { Pilha, dispersao } from './grafico.js';

export const PRESETS = [
  ['Lambda × injeção', ['lambda', 'injecao']],
  ['RPM × avanço', ['rpm', 'avanco']],
  ['Marcha lenta', ['rpm', 'motor_passo', 'borboleta', 'map']],
  ['Temperaturas', ['agua', 'ar']],
  ['Elétrica', ['bateria', 'bobina_14', 'bobina_23']],
];

let pilha, desligar = [], sel, fonte = 'vivo', sessao = null, janela = 300, pend = false, raiz;

function dadosVivo() {
  const b = S.vivo, r = S.estado.rec;
  const ini = r ? Date.now() / 1000 - r.s : 0;       // eventos da gravação atual vêm em s desde o início
  return { x: b.hora, series: b.v, marcas: r ? r.eventos.map(e => ({ x: ini + e.s, texto: e.texto })) : [], quedas: [] };
}

function dadosSessao() {
  const d = sessao;
  return { x: d.t, series: d.series, marcas: (d.meta.eventos || []).map(e => ({ x: e.s, texto: e.texto })), quedas: d.meta.quedas || [] };
}

function redesenhar() {
  pend = false;
  if (!pilha) return;
  pilha.janela = fonte === 'vivo' ? janela : null;
  pilha.setDados(fonte === 'vivo' ? dadosVivo() : dadosSessao());
  const disp = $('#disp', raiz);
  if (disp && !disp.classList.contains('oculto')) {
    const d = fonte === 'vivo' ? S.vivo.v : sessao.series;
    dispersao($('canvas', disp), d[1] || [], d[3] || [], { xl: 'RPM', yl: 'Avanço (°)', xd: 0, yd: 0 });
  }
}
function agendar() { if (!pend) { pend = true; setTimeout(() => requestAnimationFrame(redesenhar), 200); } }

function montarPilha() {
  if (pilha) pilha.destruir();
  const caixa = $('#pilha', raiz);
  por(caixa);
  if (!sel.length) { caixa.append(h('div.vazio', 'Escolha um ou mais parâmetros acima.')); pilha = null; return; }
  pilha = new Pilha(caixa, { altura: innerWidth < 640 ? 100 : 128, tempo: fonte === 'vivo' ? 'relogio' : 'relativo' });
  pilha.setParams(sel);
  $('#disp', raiz).classList.toggle('oculto', !(sel.includes(1) && sel.includes(3)));
  redesenhar();
}

function seletor() {
  por($('#sel', raiz), ...S.params.map(p => h('button' + (sel.includes(p.ordem) ? '.on' : ''), {
    onclick: () => {
      sel = sel.includes(p.ordem) ? sel.filter(x => x !== p.ordem) : sel.length >= 8 ? (toast('No máximo 8 parâmetros de cada vez.'), sel) : [...sel, p.ordem];
      guardar('graficos', sel); seletor(); montarPilha();
    } }, h('i.tr', { style: { background: sel.includes(p.ordem) ? corDe(p.ordem, sel) : '' } }), p.curto)));
}

async function escolherFonte(v) {
  fonte = v;
  if (v !== 'vivo') {
    try { sessao = await api('/api/sessoes/' + encodeURIComponent(v)); }
    catch (e) { toast(e.message, true); fonte = 'vivo'; }
  }
  $('#janela', raiz).classList.toggle('oculto', fonte !== 'vivo');
  montarPilha();
}

export default {
  async montar(el, args) {
    raiz = el;
    sel = args[0] ? args[0].split(',').map(Number).filter(o => S.p[o]) : ler('graficos', ords('lambda', 'rpm', 'injecao')).filter(o => S.p[o]);
    fonte = 'vivo';
    const sessoes = await api('/api/sessoes').catch(() => []);
    el.append(
      h('div.cartao',
        h('div.linha',
          h('select#fonte', { onchange: e => escolherFonte(e.target.value), 'aria-label': 'Fonte dos dados' },
            h('option', { value: 'vivo' }, 'Ao vivo'),
            ...sessoes.map(s => h('option', { value: s.id }, (s.nome || 'sessão') + ' · ' + (s.inicio || s.id)))),
          h('div.seg#janela', ...[[60, '1 min'], [300, '5 min'], [600, '10 min']].map(([s, t]) =>
            h('button' + (s === janela ? '.ativo' : ''), { onclick: e => { janela = s; for (const b of e.target.parentNode.children) b.classList.toggle('ativo', b === e.target); pilha && pilha.semZoom(); agendar(); } }, t))),
          h('div.esp'),
          ...PRESETS.map(([n, ps]) => h('button.btn.peq', { onclick: () => { sel = ords(...ps); guardar('graficos', sel); seletor(); montarPilha(); } }, n))),
        h('div.seletor#sel', { style: { marginTop: '12px' } })),
      h('div.cartao', h('div#pilha'), h('p.dica-zoom', 'Arraste na horizontal para dar zoom (todas as faixas juntas); duplo clique volta. Faixa verde = referência normal; hachura = sem dados (queda).')),
      h('div.cartao#disp.oculto', h('h3', 'Avanço × RPM'), h('canvas.dispersao'),
        h('p.dica-zoom', 'Cada ponto é uma leitura. Em marcha lenta a central mexe no avanço para segurar a rotação; acelerando, o avanço sobe com a rotação e cai com carga alta.')));
    seletor();
    montarPilha();
    desligar = [on('leitura', () => fonte === 'vivo' && agendar()), on('buffer', agendar), on('faixas', agendar), on('tema', montarPilha)];
  },
  desmontar() { desligar.forEach(f => f()); if (pilha) pilha.destruir(); pilha = null; },
};

// Painel ao vivo: um cartão por parâmetro com valor, faixa, estado e os últimos 60 s.
import { S, on, api, toast, texto, regraDe, faixaTexto, carregarParametros, iniciarLeitura, rotulo, ords } from './app.js';
import { $, h, por, pt, sparkline, estadoEl, guardar, ler } from './util.js';
import { CURTAS } from './grafico.js';

const IDS_PADRAO = ['rpm', 'lambda', 'agua', 'bateria', 'map', 'injecao', 'avanco', 'motor_passo', 'carga', 'borboleta', 'ar',
  'velocidade', 'bobina_14', 'bobina_23', 'pressao_atm', 'pedal', 'luz_injecao', 'rele_principal', 'alim_central', 'ac_termistor', 'ac_autorizacao'];
const CTX = [['ligado', 'motor ligado'], ['desligado', 'motor parado'], ['quente', 'quente'],
  ['pedal_solto', 'pedal solto'], ['marcha_lenta_quente', 'marcha lenta quente']];
let el, cards = {}, desligar = [], editando = false, pend = false;

function ordem() {
  // ordem padrão: a lista acima e, depois, o que o perfil tiver a mais
  const padrao = [...new Set([...ords(...IDS_PADRAO), ...S.params.map(p => p.ordem)])];
  const o = (ler('cartoes', null) || []).filter(x => S.p[x.o]);
  const vistos = new Set(o.map(x => x.o));
  return o.concat(padrao.filter(x => !vistos.has(x)).map(x => ({ o: x, v: true })));
}

function cartao(o) {
  const p = S.p[o];
  const c = { el: null, val: h('div.val'), cab: h('div.cab'), ref: h('div.ref'), cv: h('canvas') };
  c.el = h('button.medidor' + (o === S.lambda ? '.lambda' : ''), { type: 'button', onclick: () => editando ? null : detalhe(o) },
    c.cab, c.val, p.tipo === 'num' ? c.cv : null, c.ref);
  return c;
}

function atualizar() {
  pend = false;
  const it = S.ultimo, b = S.vivo;
  const velho = !it || (Date.now() / 1000 - it.hora > 5);
  for (const [o, c] of Object.entries(cards)) {
    const p = S.p[o];
    const v = it ? it.v[o] : undefined;
    const [est, msg, lado] = (it && it.est[o]) || ['neutro', '', null];
    const rotEst = est === 'normal' ? 'normal' : lado === 'acima' ? 'alto' : lado === 'abaixo' ? 'baixo' : undefined;
    const t = texto(+o, v, it && it.txt[o]);
    const rg = regraDe(+o);
    c.el.className = 'medidor' + (+o === S.lambda ? ' lambda' : '') + (v == null || velho ? ' sem' : ' ' + est);
    por(c.cab, h('span.rot', p.curto), p.pendente ? h('span.selo-pend', { title: 'Não confirmado — nível ' + (p.nivel || '?') + ': ainda sem captura que confirme este valor/curva' }, v == null || velho || est === 'neutro' ? 'não confirmado' : '?') : null, v == null || velho ? null : estadoEl(est, rotEst));
    c.val.className = 'val' + (t.txt ? ' txt' : '');
    por(c.val, t.v, t.un ? h('span.un', t.un) : null);
    let esq, dir = null;
    if (v === undefined && it && p.bloco === 1) esq = 'só na leitura completa';
    else if (+o === S.lambda) esq = pt(it && it.lambda && it.lambda.diagnostico) || 'aguardando';
    else if (rg) esq = ('normal ' + (CURTAS[rg.r.quando] ? CURTAS[rg.r.quando] + ' ' : '') + faixaTexto(rg, p)).trim();
    else esq = '';
    if (msg && est !== 'normal' && +o !== S.lambda && !velho) dir = msg;
    por(c.ref, h('span' + (+o === S.lambda ? '.msg' : ''), esq), dir ? h('span.msg', dir) : null);
    if (p.tipo === 'num' && b.v[o]) {
      const f = rg && rg.ativa && rg.r.normal ? rg.r.normal : null;
      sparkline(c.cv, b.hora, b.v[o], f);
    }
  }
  const ctx = (it && it.ctx) || [];
  por($('#ctx', el),
    ...CTX.filter(([k]) => ctx.includes(k) && !(k === 'pedal_solto' && ctx.includes('marcha_lenta_quente'))).map(([, n]) => h('span.tag.on', n)),
    it && !velho ? h('span.tag', S.estado.modo === 'rapido' ? 'leitura rápida (bloco 0)' : 'leitura completa') : null);
  $('#sem-leitura', el)?.classList.toggle('oculto', !!(S.estado.rodando || (it && !velho)));
}

function agendar() { if (!pend) { pend = true; requestAnimationFrame(atualizar); } }

function grade() {
  const g = $('#grade', el);
  cards = {};
  const itens = ordem();
  por(g, ...itens.filter(x => editando || x.v).map((x, k) => {
    const c = cartao(x.o);
    cards[x.o] = c;
    if (editando) {
      c.el.classList.toggle('oculto-edit', !x.v);
      const mover = d => { const a = ordem(); const i = a.findIndex(y => y.o === x.o), j = i + d; if (j < 0 || j >= a.length) return; [a[i], a[j]] = [a[j], a[i]]; guardar('cartoes', a); grade(); };
      c.el.append(h('div.ordem',
        h('button', { type: 'button', title: x.v ? 'Esconder' : 'Mostrar', onclick: e => { e.stopPropagation(); const a = ordem(); a.find(y => y.o === x.o).v = !x.v; guardar('cartoes', a); grade(); } }, x.v ? '👁' : '◌'),
        h('button', { type: 'button', title: 'Mover para trás', onclick: e => { e.stopPropagation(); mover(-1); } }, '←'),
        h('button', { type: 'button', title: 'Mover para frente', onclick: e => { e.stopPropagation(); mover(1); } }, '→')));
    }
    return c.el;
  }));
  atualizar();
}

// ---------- detalhe e ajuste de faixa ----------
function detalhe(o) {
  if (o === S.lambda) { location.hash = '#/lambda'; return; }
  const p = S.p[o];
  const fx = S.faixas[o] || { regras: [], ajustado: false };
  let regras = JSON.parse(JSON.stringify(fx.regras));
  const d = h('dialog');
  const conds = Object.entries(S.condicoes);
  const num = (r, k, i) => h('input', { type: 'number', step: 'any', value: r[k] ? r[k][i] : '',
    oninput: e => { r[k] ||= [0, 0]; r[k][i] = parseFloat(e.target.value); } });
  const lista = h('div');
  function desenhar() {
    por(lista, ...regras.map((r, k) => h('div.cartao', { style: { marginBottom: '10px', padding: '12px' } },
      h('div.linha', h('label', { style: { flex: '1' } }, 'Quando',
        h('select', { onchange: e => { r.quando = e.target.value; } }, ...conds.map(([c, n]) => h('option', { value: c, selected: c === r.quando }, n)))),
        h('button.btn.peq.fantasma', { type: 'button', onclick: () => { regras.splice(k, 1); desenhar(); } }, 'Remover')),
      'normal_valor' in r ?
        h('div.form', h('label', 'Valor normal', h('select', { onchange: e => { r.normal_valor = +e.target.value; } },
          ...[0, 1].map(v => h('option', { value: v, selected: r.normal_valor === v }, (p.textos || ['não', 'sim'])[v]))))) :
        h('div.form',
          h('label', 'Normal de', num(r, 'normal', 0)), h('label', 'até', num(r, 'normal', 1)),
          h('label', 'Atenção de', num(r, 'atencao', 0)), h('label', 'até', num(r, 'atencao', 1))),
      r.nota ? h('div.mut', { style: { fontSize: '13px' } }, r.nota) : null)));
  }
  desenhar();
  const fechar = () => { d.close(); d.remove(); };
  d.append(
    h('h2', p.nome),
    p.pendente ? h('p.aviso', h('span', h('b', 'Não confirmado. '), 'Este valor (ou a curva dele) ainda não foi confirmado por captura.')) : null,
    h('p.ink2', h('b', 'Nível: '), p.nivel || '—', p.evidencia ? [h('br'), h('b', 'Evidência: '), p.evidencia] : null,
      p.obs ? [h('br'), h('b', 'Observação: '), p.obs] : null),
    h('p.mut', { style: { fontSize: '13px' } }, 'Fora da faixa de atenção = fora. Fora da condição, o cartão fica sem cor. ',
      fx.ajustado ? h('b', 'Esta faixa foi ajustada por você.') : 'Faixa padrão do perfil da central.'),
    h('h3', 'Faixas'), lista,
    p.tipo === 'num' ? h('button.btn.peq', { type: 'button', onclick: () => { regras.push({ quando: 'sempre', normal: [p.min || 0, p.max || 100], atencao: [p.min || 0, p.max || 100] }); desenhar(); } }, '+ regra') : null,
    h('div.acoes',
      h('button.btn.fantasma', { type: 'button', onclick: () => { fechar(); location.hash = '#/graficos/' + o; } }, 'Ver no gráfico'),
      fx.ajustado ? h('button.btn', { type: 'button', onclick: () => salvar(null) }, 'Restaurar padrão') : null,
      h('button.btn', { type: 'button', onclick: fechar }, 'Fechar'),
      h('button.btn.pri', { type: 'button', onclick: () => salvar(regras) }, 'Salvar faixa')));
  async function salvar(rs) {
    try {
      await api('/api/faixas', { ordem: o, regras: rs });
      await carregarParametros();
      toast(rs ? 'Faixa de ' + rotulo(o) + ' salva.' : 'Faixa de ' + rotulo(o) + ' voltou ao padrão.');
      fechar(); atualizar();
    } catch (e) { toast(e.message, true); }
  }
  document.body.append(d);
  d.showModal();
}

export default {
  async montar(raiz) {
    el = raiz;
    editando = false;
    raiz.append(
      h('div.linha', h('div.contexto#ctx'), h('div.esp'),
        h('button.btn.peq.fantasma', { onclick: e => { editando = !editando; e.target.textContent = editando ? 'Concluir' : 'Editar cartões'; grade(); } }, 'Editar cartões')),
      h('div.aviso#sem-leitura.oculto', h('span', h('b', 'Sem leitura. '), 'Toque em Ler para começar (ou conecte pelo Início).'),
        h('div.esp'), h('button.btn.pri.peq', { onclick: () => iniciarLeitura() }, 'Ler agora')),
      h('div.grade#grade'));
    grade();
    desligar = [on('leitura', agendar), on('buffer', agendar), on('faixas', agendar), on('estado', agendar), on('tema', agendar)];
    addEventListener('resize', agendar);
  },
  desmontar() { desligar.forEach(f => f()); removeEventListener('resize', agendar); },
};

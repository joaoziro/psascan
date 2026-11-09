// Sonda lambda: osciloscópio, veredito, trocas rico↔pobre e cruzamento com injeção e canister.
import { S, ords, on, iniciarLeitura } from './app.js';
import { $, h, por, pt, fmt, css } from './util.js';
import { Pilha } from './grafico.js';

let osc, cruz, desligar = [], pend = false, raiz, janela = 60;

function estadoDe(diag) {
  if (!diag) return 'neutro';
  if (diag.startsWith('OSCILANDO')) return 'normal';
  if (diag.startsWith('VARIA')) return 'atencao';
  if (diag.startsWith('PARADA')) return 'fora';
  return 'neutro';
}

function tile(k, v, un) { return h('div.tile', h('div.k', k), h('div.v', v, un ? h('small', un) : null)); }

function atualizar() {
  pend = false;
  if (!raiz || !$('#sem', raiz)) return;   // saiu da tela antes do quadro agendado
  const it = S.ultimo, b = S.vivo;
  const a = (it && it.lambda) || { diagnostico: 'aguardando dados' };
  const quente = it && it.ctx.includes('quente');
  const est = quente ? estadoDe(a.diagnostico) : 'neutro';
  const agua = it && it.v[9];
  por($('#ver', raiz),
    h('div.grande.' + est, pt(a.diagnostico)),
    est !== 'neutro' ? h('span.est.' + est, { normal: 'sonda trabalhando', atencao: 'sonda preguiçosa', fora: 'sonda parada' }[est]) : null);
  const carga = it && it.v[4], inj = it && it.v[13];
  const nota = !it || !quente ? null : inj === 0 ? 'Corte de injeção (freio-motor): ficar pobre agora é normal.' :
    carga > 70 ? 'Carga alta (acelerando forte): a central enriquece de propósito; ficar rico agora é normal.' : null;
  por($('#frio', raiz), nota ? h('p.ink2', nota) : null, it && !quente ? h('div.aviso', h('span', h('b', 'Motor frio ou parado' + (agua != null ? ' (água ' + fmt(agua, 0) + ' °C)' : '') + ': '),
    'a central está em malha aberta e ignora a sonda. O veredito só vale com o motor ligado e a água acima de 80 °C.')) : null);
  const tps = a.segundos ? a.trocas / a.segundos : null;
  por($('#tiles', raiz),
    tile('trocas/s', tps == null ? '—' : fmt(tps, 2)),
    tile('trocas em ' + (a.segundos ? fmt(a.segundos, 0) + ' s' : '10 s'), a.trocas ?? '—'),
    tile('tempo rico', a.pct_rico ?? '—', '%'),
    tile('mínimo', a.min ?? '—', 'mV'), tile('máximo', a.max ?? '—', 'mV'), tile('média', a.media ?? '—', 'mV'),
    tile('agora', it && it.v[S.lambda] != null ? fmt(it.v[S.lambda], 0) : '—', 'mV'));
  const d = { x: b.hora, series: b.v, marcas: [], quedas: [] };
  if (osc) { osc.janela = janela; osc.setDados(d); }
  if (cruz) { cruz.janela = janela; cruz.setDados(d); }
  $('#sem', raiz).classList.toggle('oculto', !!(S.estado.rodando || (it && Date.now() / 1000 - it.hora < 5)));
}
function agendar() { if (!pend) { pend = true; setTimeout(() => requestAnimationFrame(atualizar), 150); } }

function montarGraficos() {
  if (osc) osc.destruir();
  if (cruz) cruz.destruir();
  por($('#osc', raiz)); por($('#cruz', raiz));
  osc = new Pilha($('#osc', raiz), {
    altura: innerWidth < 640 ? 200 : 260, fixo: { [S.lambda]: [0, 1000] }, linhas: { [S.lambda]: [450] },
    bandas: { [S.lambda]: [[0, 350, css('--warn-soft')], [550, 1000, css('--bad-soft')]] },
  });
  osc.setParams([S.lambda]);
  cruz = new Pilha($('#cruz', raiz), { altura: innerWidth < 640 ? 90 : 110 });
  cruz.setParams([S.lambda, ...ords('injecao')]);
  atualizar();
}

export default {
  async montar(el) {
    raiz = el;
    el.append(
      h('div.aviso#sem.oculto', h('span', h('b', 'Sem leitura. '), 'A leitura rápida (só bloco 0) dá o dobro de amostras da lambda.'),
        h('div.esp'), h('button.btn.pri.peq', { onclick: () => iniciarLeitura('rapido') }, 'Ler rápido')),
      h('div.cartao', h('div.linha', h('h3', 'Veredito (últimos 10 s)'), h('div.esp'),
        h('div.seg', ...[[60, '1 min'], [300, '5 min']].map(([s, t]) => h('button' + (s === janela ? '.ativo' : ''),
          { onclick: e => { janela = s; for (const x of e.target.parentNode.children) x.classList.toggle('ativo', x === e.target); osc.semZoom(); cruz.semZoom(); } }, t)))),
        h('div.veredito#ver', { style: { margin: '6px 0 12px' } }), h('div#frio'), h('div.tiles#tiles', { style: { marginTop: '12px' } })),
      h('div.cartao', h('h3', 'Osciloscópio'), h('div#osc'),
        h('p.dica-zoom', 'Linha tracejada = 450 mV (ponto de troca). Abaixo de 350 mV = pobre (âmbar), acima de 550 mV = rico (vermelho claro). Normal: atravessa as duas faixas várias vezes a cada 10 s.')),
      h('div.cartao', h('h3', 'Lambda × injeção'), h('div#cruz'),
        h('p.dica-zoom', 'Se a lambda fica em rico enquanto a injeção já está curta, desconfie de combustível vindo de outro lugar: canister travado aberto, pressão alta, injetor pingando.')),
      h('div.cartao', h('details', h('summary', 'Como interpretar'), h('pre.dicas', S.info.dicas_lambda || ''))));
    montarGraficos();
    desligar = [on('leitura', agendar), on('buffer', agendar), on('estado', agendar), on('tema', montarGraficos)];
  },
  desmontar() { desligar.forEach(f => f()); osc && osc.destruir(); cruz && cruz.destruir(); osc = cruz = null; },
};

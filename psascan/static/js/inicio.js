// Início: escolher o scanner, conectar vendo cada etapa, e os atalhos de oficina.
import { S, on, api, toast, confirmar, iniciarLeitura } from './app.js';
import { $, h, por } from './util.js';

const ETAPAS = [
  ['Bluetooth', 'abrir a conexão com o scanner', /abrir Bluetooth/i],
  ['Scanner', 'versão e configuração do scanner', /versão/i],
  ['Linha K', 'ligar a linha K no pino 7 da tomada OBD', /linha K|destravar|13 recusado/i],
  ['Central', 'acordar a central e começar a conversar', /início de comunicação|acordar/i],
];
let raiz, desligar = [], conectando = false;

function etapaAtual(e) {
  if (e.conexao === 'conectado') return ETAPAS.length;
  const k = ETAPAS.findIndex(([, , re]) => re.test(e.etapa || ''));
  return k < 0 ? 0 : k;
}

function desenhar() {
  const e = S.estado;
  const ok = e.conexao === 'conectado';
  const falhou = !ok && !conectando && e.conexao === 'desconectado' && e.ultimo_erro;
  const ativo = conectando || e.conexao === 'conectando' || e.conexao === 'reconectando';
  const k = etapaAtual(e);
  por($('#etapas', raiz), ...ETAPAS.map(([n, d], i) => {
    const st = ok || i < k ? 'feito' : i === k && falhou ? 'erro' : i === k && ativo ? 'agora' : 'falta';
    return h('li.' + st, h('i', { 'aria-hidden': 'true' }, st === 'falta' ? String(i + 1) : ''),
      h('div', h('b', n), h('span', d)));
  }));
  por($('#con-titulo', raiz), ok ? 'Conectado à central' : ativo ? 'Conectando…' : falhou ? 'Não conectou' : 'Desconectado');
  $('#con-titulo', raiz).className = ok ? 'ok' : falhou ? 'erro' : '';
  por($('#con-erro', raiz), falhou ? [h('p', { style: { margin: '0 0 4px', fontWeight: '600' } }, e.dica_erro || 'Falha na comunicação.'),
    h('p.mut', { style: { margin: 0, fontSize: '13px' } }, e.ultimo_erro)] : null);
  $('#con-erro', raiz).classList.toggle('oculto', !falhou);
  $('#btn-conectar', raiz).classList.toggle('oculto', ok);
  $('#btn-conectar', raiz).disabled = ativo;
  $('#btn-conectar', raiz).textContent = falhou ? 'Tentar de novo' : 'Conectar';
  $('#btn-desconectar', raiz).classList.toggle('oculto', !ok);
  $('#atalhos', raiz).classList.toggle('sem-conexao', !ok);
  por($('#con-info', raiz), ok && e.versao ? 'scanner hw ' + e.versao.hw + ' · firmware ' + e.versao.firmware : '');
}

async function conectar() {
  if (!await confirmar('conectar', null, 'Conectar à central')) return;
  conectando = true; desenhar();
  try { await api('/api/conectar', {}); toast('Conectado à central.'); }
  catch (e) { /* o erro aparece na própria tela, com a explicação */ }
  finally { conectando = false; desenhar(); }
}

async function scanners() {
  const r = await api('/api/scanners');
  const sel = $('#scanner', raiz);
  por(sel, ...(r.aparelhos.length ? r.aparelhos : [{ endereco: '', nome: 'nenhum aparelho pareado' }]).map(a =>
    h('option', { value: a.endereco, selected: a.endereco === r.atual }, (a.nome || 'sem nome') + (a.endereco ? ' · ' + a.endereco : ''))));
  $('#scanner-sim', raiz).classList.toggle('oculto', !r.simulador);
}

async function trocar(endereco) {
  if (!endereco) return;
  try { await api('/api/porta', { endereco }); toast('Scanner escolhido: ' + endereco); scanners(); }
  catch (e) { toast(e.message, true); }
}

const atalho = (titulo, desc, fn, precisa = true) => h('button.atalho' + (precisa ? '.precisa' : ''), { type: 'button', onclick: fn },
  h('b', titulo), h('span', desc), precisa ? h('small', 'conecte primeiro') : null);

export default {
  async montar(el) {
    raiz = el;
    el.append(
      h('div.inicio',
        h('div.cartao.conexao',
          h('h2#con-titulo'),
          h('ol.etapas#etapas'),
          h('div.aviso.perigo#con-erro.oculto'),
          h('div.linha', { style: { marginTop: '14px' } },
            h('button.btn.pri#btn-conectar', { onclick: conectar, style: { minWidth: '160px', minHeight: '52px' } }, 'Conectar'),
            h('button.btn#btn-desconectar.oculto', { onclick: async () => { await api('/api/desconectar', {}); toast('Desconectado.'); } }, 'Desconectar'),
            h('span.mut#con-info'))),
        h('div.cartao',
          h('h3', 'Scanner'),
          h('p.aviso#scanner-sim.oculto', h('span', h('b', 'Modo simulador. '), 'Nada aqui fala com o carro. Para usar o carro, feche este e abra o ícone psascan normal.')),
          h('div.linha', h('select#scanner', { style: { flex: '1 1 220px', minWidth: '0' }, onchange: e => trocar(e.target.value), 'aria-label': 'Scanner Bluetooth' }),
            h('button.btn', { onclick: scanners }, 'Atualizar lista')),
          h('p.mut', { style: { fontSize: '13px' } }, 'Aparelhos pareados no Bluetooth do sistema. O scanner PSA COM costuma aparecer como "OBDII", "PSACOM" ou "AutoScan&Programming" (PIN 1234). Se não estiver na lista, pareie pelas configurações de Bluetooth e toque em Atualizar.'),
          h('details', h('summary', 'Digitar o endereço'), h('div.linha', h('input#manual', { type: 'text', placeholder: 'AA:BB:CC:DD:EE:FF ou /dev/rfcomm0', style: { flex: '1' } }),
            h('button.btn', { onclick: () => trocar($('#manual', raiz).value) }, 'Usar'))))),
      h('div.cartao', h('h3', 'O que fazer'),
        h('div.atalhos-grade#atalhos',
          atalho('Ler falhas', 'códigos guardados na central', () => { location.hash = '#/falhas/ler'; }),
          atalho('Ao vivo', 'todos os parâmetros', async () => { if (S.estado.rodando || await iniciarLeitura('completo')) location.hash = '#/painel'; }),
          atalho('Sonda lambda', 'leitura rápida e veredito', async () => { if (S.estado.rodando || await iniciarLeitura('rapido')) location.hash = '#/lambda'; }),
          atalho('Gravar', 'uma volta ou um teste', () => { location.hash = '#/gravar'; }),
          atalho('Rever gravações', 'não precisa estar conectado', () => { location.hash = '#/sessoes'; }, false))));
    await scanners().catch(e => toast(e.message, true));
    desenhar();
    desligar = [on('estado', desenhar)];
  },
  desmontar() { desligar.forEach(f => f()); },
};

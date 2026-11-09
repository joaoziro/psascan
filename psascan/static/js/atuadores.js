// Teste de atuadores: confirmação dos bytes; bomba e injetores ainda pedem para digitar PERIGO.
import { S, api, toast, confirmar, chaveEscrita, escritaLigada, on } from './app.js';
import { h } from './util.js';

async function acionar(a) {
  const frase = S.info.frase_perigo || 'PERIGO';
  let campo = null, extra = h('p.aviso', h('span', h('b', 'O teste não para sozinho. '),
    'Para parar, desligue a chave (ignição). Só com o motor desligado e a ignição ligada.'));
  if (a.perigoso) {
    campo = h('input', { type: 'text', autocomplete: 'off', placeholder: 'digite ' + frase, style: { width: '100%' } });
    extra = h('div', h('p.aviso.perigo', h('span', h('b', 'MODO DE SEGURANÇA: ' + a.nome + '. '),
      'Com o motor parado pode encharcar os cilindros, e o teste não para sozinho (pode ser preciso desligar a chave).')),
      h('label', 'Para liberar, digite ' + frase + ':', campo));
  }
  if (!await confirmar(a.id, null, 'Acionar ' + a.nome, extra)) return;
  if (a.perigoso && campo.value.trim() !== frase) { toast('Cancelado (modo de segurança): a palavra não confere.', true); return; }
  try {
    const r = await api('/api/atuador', { id: a.id, frase: a.perigoso ? campo.value.trim() : undefined });
    toast(a.nome + ' acionado (resposta ' + r.resposta + '). Para parar: desligue a chave.');
  } catch (e) { toast(e.message, true); }
}

let desligar = [];

export default {
  async montar(el) {
    const lst = S.info.atuadores || [];
    const chave = chaveEscrita();
    const botoes = [];
    const sincronizar = () => botoes.forEach(b => { b.disabled = !escritaLigada(); b.title = escritaLigada() ? '' : 'Ligue "Permitir comandos de escrita" para acionar'; });
    el.append(
      h('div.cartao', chave, h('p.mut#aviso-escrita', { style: { fontSize: '13.5px', margin: '8px 0 0' } },
        'Somente leitura por padrão. Com a chave desligada, os botões ficam desabilitados.')),
      h('div.aviso.perigo', h('span', h('b', 'Atenção: '), 'só com o motor DESLIGADO e a ignição ligada. ',
        'O teste pode não parar sozinho; depois de acionar, aparece uma faixa vermelha até você confirmar que desligou a chave.')),
      h('div.atuadores', ...lst.map(a => {
        const b = h('button.btn' + (a.perigoso ? '.perigo' : ''), { onclick: () => acionar(a) }, 'Acionar…');
        botoes.push(b);
        return h('div.cartao.atuador',
          h('div.linha', h('b', { style: { fontSize: '16.5px' } }, a.nome)),
          h('div.linha', h('span.cod', a.pedido), a.nivel ? h('span.mut', 'nível ' + a.nivel) : null,
            a.perigoso ? h('span.est.fora', 'bloqueado: ' + (S.info.frase_perigo || 'PERIGO')) : null),
          a.aviso ? h('p.mut', { style: { fontSize: '13.5px', margin: 0 } }, a.aviso) : null, b);
      })));
    sincronizar();
    desligar = [on('estado', sincronizar), chave.desligar];
  },
  desmontar() { desligar.forEach(f => f()); desligar = []; },
};

// Códigos de falha: ler/apagar (com os bytes antes), descrição, causas e histórico.
import { S, api, toast, confirmar, escritaLigada, on } from './app.js';
import { $, h, por } from './util.js';

let raiz;

function falhaEl(f) {
  return h('div.falha',
    h('div.cod', f.hex, h('small', 'código ' + f.codigo)),
    h('div.linha', h('span.nm', f.nome), f.pendente ? h('span.selo-pend', { title: 'Nome provável: ainda sem captura que o confirme' }, 'nome provável, não confirmado') : null, f.presente ? h('span.est.fora', 'presente') : h('span.est.atencao', 'memorizada')),
    h('div.ink2', 'Tipo: ' + f.tipo_nome),
    f.causas && f.causas.length ? h('ul', ...f.causas.map(c => h('li', c))) : null);
}

async function historico() {
  const r = await api('/api/falhas/historico');
  por($('#hist', raiz), !r.falhas.length ? h('p.mut', 'Nenhuma leitura registrada ainda. Cada "Ler falhas" fica guardado aqui.') : [
    h('p.mut', { style: { fontSize: '13.5px' } }, r.leituras + ' leitura(s) registrada(s); última em ' + r.ultima_leitura +
      (r.apagamentos.length ? ' · apagadas em ' + r.apagamentos.slice(-3).join(', ') : '')),
    h('div.rolavel', h('table.tab',
      h('thead', h('tr', h('th', 'Código'), h('th', 'Componente'), h('th.esconder-cel', 'Tipo'), h('th', 'Primeira vez'), h('th', 'Última vez'), h('th', 'Situação'))),
      h('tbody', ...r.falhas.map(f => h('tr',
        h('td.n', f.hex), h('td', f.nome + (f.pendente ? ' (nome provável)' : '')), h('td.esconder-cel.ink2', f.tipo_nome),
        h('td.mut', f.primeira), h('td.mut', f.ultima + (f.vezes > 1 ? ' (' + f.vezes + '×)' : '')),
        h('td', f.na_ultima_leitura ? (f.presente ? h('span.est.fora', 'presente') : h('span.est.atencao', 'memorizada')) :
          h('span.mut', f.apagada_em ? 'apagada em ' + f.apagada_em : 'não apareceu na última leitura'))))))),
  ]);
}

let desligar = [];

export default {
  desmontar() { desligar.forEach(f => f()); desligar = []; },
  async montar(el, args) {
    raiz = el;
    const btnLer = h('button.btn.pri', { onclick: async () => {
      if (!await confirmar('falhas', null, 'Ler códigos de falha')) return;
      btnLer.disabled = true;
      try {
        const r = await api('/api/falhas', {});
        if (r.falhas === null) por($('#res', raiz), h('p.aviso.perigo', 'Central sem resposta válida: ', h('code', r.bruto)));
        else por($('#res', raiz), r.falhas.length ? r.falhas.map(falhaEl) : h('p', { style: { fontSize: '18px' } }, '✓ Nenhum código de falha.'),
          h('p.mut', { style: { fontSize: '13px' } }, 'Resposta: ', h('code', r.bruto)));
        historico();
      } catch (e) { toast(e.message, true); } finally { btnLer.disabled = false; }
    } }, 'Ler falhas');
    const btnApagar = h('button.btn.perigo', { onclick: async () => {
      const aviso = h('p.aviso', h('span', h('b', 'Apaga a memória de falhas da central. '),
        'Leia antes, para ficar no histórico. Uma falha presente volta logo (e acende a luz de novo). ' + (S.info.apagar_aviso || '')));
      if (!await confirmar('apagar', null, 'APAGAR códigos de falha', aviso)) return;
      try {
        const r = await api('/api/apagar', {});
        por($('#res', raiz), h('p', 'Comando enviado (resposta ', h('code', r.resposta), '). Leia de novo para conferir.'));
        historico();
      } catch (e) { toast(e.message, true); }
    } }, 'Apagar falhas');
    const sincronizar = () => { btnApagar.disabled = !escritaLigada(); btnApagar.title = escritaLigada() ? '' : 'Ligue "Permitir comandos de escrita" (Atuadores ou Avançado → Conexão)'; $('#aviso-apagar', raiz)?.classList.toggle('oculto', escritaLigada()); };
    el.append(
      h('div.cartao', h('div.linha', btnLer, btnApagar),
        h('p.mut#aviso-apagar', { style: { fontSize: '13.5px' } }, 'Apagar falhas está desabilitado: a ferramenta começa em somente leitura. Ligue "Permitir comandos de escrita" na aba Atuadores ou em Avançado → Conexão.'),
        h('p.mut', { style: { fontSize: '13.5px', marginBottom: 0 } }, 'Código em hex (ex. 127 = 295 em decimal). "Presente" = a falha está acontecendo agora; "memorizada" = aconteceu e ficou guardada.'),
        h('div#res', { style: { marginTop: '6px' } })),
      h('div.cartao', h('h3', 'Histórico'), h('div#hist')));
    sincronizar();
    desligar = [on('estado', sincronizar)];
    historico().catch(e => toast(e.message, true));
    if (args && args[0] === 'ler') { history.replaceState(null, '', '#/falhas'); btnLer.click(); }   // atalho do Início
  },
};

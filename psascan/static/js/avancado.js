// Avançado: conexão e reconexões, log, capturas cruas (ver_captura na web), quadros das ações e a chave de escrita.
import { S, api, toast, confirmar, chaveEscrita, recarregarTema } from './app.js';
import { $, h, por, fmt, ler, guardar } from './util.js';

let raiz, timer = null;

const ABAS = [['conexao', 'Conexão'], ['log', 'Log'], ['capturas', 'Capturas'], ['quadros', 'Quadros das ações'], ['tema', 'Tema'], ['layouts', 'Layouts']];

async function conexao(c) {
  const desenhar = async () => {
    const r = await api('/api/conexao');
    por(c,
      h('div.tiles',
        h('div.tile', h('div.k', 'estado'), h('div.v', { style: { fontSize: '22px' } }, r.estado)),
        h('div.tile', h('div.k', 'reconexões'), h('div.v', r.reconexoes)),
        h('div.tile', h('div.k', 'scanner'), h('div.v', { style: { fontSize: '18px' } }, r.versao ? 'hw ' + r.versao.hw + ' · fw ' + r.versao.firmware : '—'))),
      h('p.ink2', 'Porta: ', h('code', r.porta), r.captura ? [' · captura: ', h('code', r.captura.split('/').pop())] : null),
      r.ultimo_erro ? h('p.ink2', 'Último erro: ', h('code', r.ultimo_erro)) : null,
      h('div.linha',
        h('button.btn', { onclick: async () => { if (!await confirmar('versao', null, 'Versão do scanner (não fala com a central)')) return; try { const v = await api('/api/versao', {}); toast('Scanner: ' + JSON.stringify(v)); desenhar(); } catch (e) { toast(e.message, true); } } }, 'Versão do scanner'),
        h('button.btn', { onclick: async () => { await api('/api/desconectar', {}); toast('Desconectado.'); desenhar(); } }, 'Desconectar')),
      h('div', { style: { margin: '14px 0 0' } }, chaveEscrita()),
      h('label.linha', { style: { margin: '14px 0 0', cursor: 'pointer' } },
        h('input', { type: 'checkbox', checked: ler('mostrar-bytes', false), style: { width: '22px', height: '22px' },
          onchange: e => { guardar('mostrar-bytes', e.target.checked); toast(e.target.checked ? 'Os bytes aparecem antes de cada envio.' : 'Leituras vão direto; apagar e atuadores ainda pedem confirmação.'); } }),
        'Mostrar os bytes antes de cada envio (também nas leituras)'),
      h('h3', { style: { margin: '16px 0 8px' } }, 'Mudanças de estado'),
      h('div.rolavel', h('table.tab', h('tbody', ...r.historico.slice().reverse().map(([hora, e, det]) =>
        h('tr', h('td.n', hora), h('td', e), h('td.mut', det)))))));
  };
  await desenhar();
  timer = setInterval(() => desenhar().catch(() => {}), 2500);
}

async function log(c) {
  const pre = h('pre.log');
  por(c, h('p.mut', { style: { fontSize: '13.5px' } }, 'Notas da conexão desta execução do servidor (as capturas guardam tudo, inclusive os bytes).'), pre);
  const desenhar = async () => {
    const r = await api('/api/conexao');
    const embaixo = pre.scrollTop + pre.clientHeight >= pre.scrollHeight - 4;
    pre.textContent = r.log.join('\n') || '(vazio)';
    if (embaixo) pre.scrollTop = 1e9;
  };
  await desenhar();
  timer = setInterval(() => desenhar().catch(() => {}), 1000);
}

async function capturas(c) {
  const lst = await api('/api/capturas');
  const tab = h('div.rolavel'), info = h('p.mut');
  let quadros = [], filtro = 'tudo', busca = '';
  const desenhar = () => {
    const f = quadros.filter(q => (filtro === 'tudo' || (filtro === 'nota' ? q.tipo !== 'quadro' : q.dir === filtro)) &&
      (!busca || JSON.stringify(q).toLowerCase().includes(busca)));
    info.textContent = f.length + ' de ' + quadros.length + ' itens' + (f.length > 1500 ? ' (mostrando os 1500 primeiros)' : '');
    por(tab, h('table.quadros', h('tbody', ...f.slice(0, 1500).map(q => h('tr.' + (q.tipo === 'quadro' ? q.dir : q.tipo),
      h('td', q.t == null ? '' : fmt(q.t, 3)), h('td.d', q.tipo === 'quadro' ? q.dir.toUpperCase() : q.tipo === 'nota' ? '--' : '??'),
      h('td', q.tipo === 'nota' ? q.texto + (q.pendente ? '  pendente=' + q.pendente : '') : q.tipo === 'invalido' ? 'INVÁLIDO ' + q.erro + ': ' + q.hex : q.hex),
      h('td', q.anot || ''))))));
  };
  const abrir = async nome => {
    if (!nome) return;
    info.textContent = 'carregando…';
    try { quadros = await api('/api/capturas/' + encodeURIComponent(nome)); desenhar(); } catch (e) { toast(e.message, true); }
  };
  por(c,
    h('div.linha',
      h('select', { onchange: e => abrir(e.target.value), 'aria-label': 'Captura' }, h('option', { value: '' }, 'Escolha uma captura…'),
        ...lst.map(x => h('option', { value: x.nome }, x.nome + ' · ' + fmt(x.bytes / 1024, 1) + ' KB'))),
      h('div.seg', ...[['tudo', 'Tudo'], ['tx', 'TX'], ['rx', 'RX'], ['nota', 'Notas']].map(([k, n]) => h('button' + (k === filtro ? '.ativo' : ''),
        { onclick: e => { filtro = k; for (const b of e.target.parentNode.children) b.classList.toggle('ativo', b === e.target); desenhar(); } }, n))),
      h('input', { type: 'text', placeholder: 'buscar (ex. 33, 7f, timeout)', oninput: e => { busca = e.target.value.toLowerCase(); desenhar(); } })),
    info, tab);
}

async function quadros(c) {
  const acoes = [['versao', 'Versão do scanner'], ['falhas', 'Ler falhas'], ['apagar', 'Apagar falhas'],
    ['rapido', 'Leitura rápida'], ['completo', 'Leitura completa'],
    ...(S.info.atuadores || []).map(a => [a.id, 'Atuador: ' + a.nome])];
  const blocos = await Promise.all(acoes.map(async ([a, n]) => [n, await api('/api/previa?acao=' + a).catch(e => [{ desc: e.message, bytes: '' }])]));
  por(c, h('p.mut', { style: { fontSize: '13.5px' } }, 'Só consulta: nada é enviado. A sequência de conexão aparece quando ainda não há conexão aberta.'),
    ...blocos.map(([n, q]) => h('details', h('summary', n), h('ol', ...q.map(x => h('li', x.desc, h('br'), h('code', x.bytes)))))));
}

// Tema: escolher, importar (.json ou .zip) e exportar. Formato em docs/TEMA.md.
async function tema(c) {
  const r = await api('/api/temas');
  const msg = h('p.ink2');
  const ativar = async id => {
    try { await api('/api/tema/ativar', { id }); await recarregarTema(); toast('Tema ativado.'); tema(c); }
    catch (e) { toast(e.message, true); }
  };
  const importar = async arquivo => {
    if (!arquivo) return;
    try {
      const resp = await fetch('/api/tema/importar', { method: 'POST', body: await arquivo.arrayBuffer() });
      const j = await resp.json().catch(() => ({ erro: 'resposta inválida do servidor' }));
      if (j.erro) throw new Error(j.erro);
      toast('Tema "' + j.ok.nome + '" importado. Escolha Ativar para usar.');
      tema(c);
    } catch (e) {
      msg.textContent = 'Tema recusado: ' + e.message;
      msg.className = 'erro';
      toast('Tema recusado: ' + e.message, true);
    }
  };
  por(c,
    r.erro ? h('p.aviso', 'O tema escolhido não pôde ser usado, então o Padrão está ativo: ' + r.erro) : null,
    h('div.tiles', { style: { gridTemplateColumns: 'repeat(auto-fill,minmax(260px,1fr))' } }, ...r.temas.map(t =>
      h('div.tile', h('div.k', t.embutido ? 'vem com o projeto' : 'importado'),
        h('div.v', { style: { fontSize: '24px' } }, t.nome, t.ativo ? h('small', ' · ativo') : null),
        h('p.ink2', { style: { fontSize: '13.5px', margin: '6px 0' } }, t.valido ? (t.descricao || '') : 'Inválido: ' + t.erro),
        t.autor ? h('p.mut', { style: { fontSize: '12.5px', margin: '0 0 8px' } }, 'por ' + t.autor) : null,
        t.valido && !t.ativo ? h('button.btn.peq', { onclick: () => ativar(t.id) }, 'Ativar') : null))),
    h('div.linha', { style: { margin: '14px 0 0' } },
      h('label.btn', { style: { cursor: 'pointer' } }, 'Importar tema…',
        h('input', { type: 'file', accept: '.json,.zip,application/json,application/zip', class: 'sr',
          onchange: e => { importar(e.target.files[0]); e.target.value = ''; } })),
      h('a.btn', { href: '/api/tema/exportar?id=' + encodeURIComponent(r.ativo), download: 'tema-' + r.ativo + '.zip' }, 'Exportar o ativo (.zip)')),
    msg,
    h('p.mut', { style: { fontSize: '13px' } }, 'Um tema muda só a aparência (cores, fontes, ponteiro). Formato: docs/TEMA.md. ',
      'Importados ficam em ~/.config/psascan/temas/.'));
}

// Layouts da tela personalizável: ativar, importar (.json), exportar e apagar os seus. Formato em docs/LAYOUT.md.
async function layouts(c) {
  const r = await api('/api/layouts');
  const msg = h('p.ink2');
  const refaz = () => layouts(c);
  const ativar = async id => {
    try { await api('/api/layout/ativar', { id }); toast('Layout ativado.'); refaz(); } catch (e) { toast(e.message, true); }
  };
  const apagar = async l => {
    if (!confirm('Apagar o layout "' + l.nome + '"? Isso não pode ser desfeito.')) return;
    try { await api('/api/layout/apagar', { id: l.id }); toast('Layout apagado.'); refaz(); } catch (e) { toast(e.message, true); }
  };
  const importar = async arquivo => {
    if (!arquivo) return;
    try {
      const resp = await fetch('/api/layout/importar', { method: 'POST', body: await arquivo.arrayBuffer() });
      const j = await resp.json().catch(() => ({ erro: 'resposta inválida do servidor' }));
      if (j.erro) throw new Error(j.erro);
      toast('Layout "' + j.ok.nome + '" importado. Escolha Ativar para usar.');
      refaz();
    } catch (e) {
      msg.textContent = 'Layout recusado: ' + e.message;
      msg.className = 'erro';
      toast('Layout recusado: ' + e.message, true);
    }
  };
  por(c,
    r.erro ? h('p.aviso', 'O layout escolhido não pôde ser usado, então o Painel padrão está ativo: ' + r.erro) : null,
    h('div.tiles', { style: { gridTemplateColumns: 'repeat(auto-fill,minmax(260px,1fr))' } }, ...r.layouts.map(l =>
      h('div.tile', h('div.k', l.embutido ? 'vem com o projeto' : 'meu'),
        h('div.v', { style: { fontSize: '24px' } }, l.nome, l.ativo ? h('small', ' · ativo') : null),
        h('p.ink2', { style: { fontSize: '13.5px', margin: '6px 0' } }, l.valido ? (l.descricao || '') : 'Inválido: ' + l.erro),
        l.valido ? h('p.mut', { style: { fontSize: '12.5px', margin: '0 0 8px' } }, l.widgets + ' widgets · grade ' + l.grade.colunas + '×' + l.grade.linhas) : null,
        h('div.linha',
          l.valido && !l.ativo ? h('button.btn.peq', { onclick: () => ativar(l.id) }, 'Ativar') : null,
          l.valido ? h('a.btn.peq.fantasma', { href: '/api/layout/exportar?id=' + encodeURIComponent(l.id), download: 'layout-' + l.id + '.json' }, 'Exportar') : null,
          !l.embutido ? h('button.btn.peq.fantasma', { onclick: () => apagar(l) }, 'Apagar') : null)))),
    h('div.linha', { style: { margin: '14px 0 0' } },
      h('label.btn', { style: { cursor: 'pointer' } }, 'Importar layout…',
        h('input', { type: 'file', accept: '.json,application/json', class: 'sr',
          onchange: e => { importar(e.target.files[0]); e.target.value = ''; } }))),
    msg,
    h('p.mut', { style: { fontSize: '13px' } }, 'Um layout diz quais widgets aparecem na tela Painel e onde (a edição é lá). Formato: docs/LAYOUT.md. ',
      'Os seus ficam em ~/.config/psascan/layouts/.'));
}

const TELAS = { conexao, log, capturas, quadros, tema, layouts };

async function aba(id) {
  clearInterval(timer); timer = null;
  for (const b of raiz.querySelectorAll('.abas button')) b.classList.toggle('ativo', b.dataset.aba === id);
  const c = $('#conteudo-aba', raiz);
  por(c);
  try { await TELAS[id](c); } catch (e) { toast(e.message, true); }
}

export default {
  async montar(el) {
    raiz = el;
    el.append(h('div.cartao', h('div.abas', ...ABAS.map(([id, n]) => h('button', { 'data-aba': id, onclick: () => aba(id) }, n))),
      h('div#conteudo-aba', { style: { marginTop: '14px' } })));
    aba('conexao');
  },
  desmontar() { clearInterval(timer); timer = null; },
};

// Núcleo do painel: estado compartilhado, SSE, API, confirmação dos bytes, rotas e barra de topo.
import { $, $$, h, por, svg, guardar, ler, duracao, fmt, css } from './util.js';

const BUFFER_S = 600;

export const S = {
  params: [], p: {}, faixas: {}, condicoes: {}, info: {}, lambda: null,
  vivo: novoBuffer(), ultimo: null, estado: {}, modo: ler('modo', 'completo'),
  ouvintes: {},
};

function novoBuffer() { return { hora: [], v: {}, est: {}, lambda: [] }; }

export function on(ev, fn) { (S.ouvintes[ev] ||= new Set()).add(fn); return () => S.ouvintes[ev].delete(fn); }
function emit(ev, x) { for (const fn of S.ouvintes[ev] || []) { try { fn(x); } catch (e) { console.error(e); } } }

export async function api(caminho, corpo) {
  const r = await fetch(caminho, corpo === undefined ? {} :
    { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(corpo) });
  const j = await r.json().catch(() => ({ erro: 'resposta inválida do servidor (' + r.status + ')' }));
  if (j && j.erro) throw new Error(j.erro.replace(/^\w+Error: /, ''));
  if (caminho === '/api/falhas' && corpo !== undefined) emit('falhas');      // a tela personalizável atualiza o widget de falhas
  return j && 'ok' in j ? j.ok : j;
}

export function toast(msg, erro = false) {
  const t = h('div.toast' + (erro ? '.erro' : ''), msg);
  $('#toasts').append(t);
  setTimeout(() => t.remove(), erro ? 9000 : 4500);
}

// Toda ação que fala com a central passa por aqui. As que só leem vão direto; as que mudam algo
// no carro (apagar falhas, atuadores) sempre pedem confirmação. Em Avançado dá para voltar a ver
// os bytes antes de tudo.
const SO_LEITURA = new Set(['conectar', 'falhas', 'rapido', 'completo', 'versao']);
export function confirmar(acao, n, titulo, extra) {
  const bytes = ler('mostrar-bytes', false);
  if (SO_LEITURA.has(acao) && !bytes && !extra) return Promise.resolve(true);
  return new Promise(async res => {
    let q;
    try { q = await api('/api/previa?acao=' + acao + (n != null ? '&n=' + n : '')); }
    catch (e) { toast(e.message, true); return res(false); }
    const d = $('#dlg-confirmar');
    $('#dlg-titulo').textContent = titulo;
    $('#dlg-bytes').open = bytes;
    por($('#dlg-lista'), ...q.map(x => h('li', x.desc, h('code', x.bytes))));
    por($('#dlg-extra'), ...(extra ? [extra] : []));
    const fim = ok => { d.close(); res(ok); };
    $('#dlg-sim').onclick = () => fim(true);
    $('#dlg-nao').onclick = () => fim(false);
    d.oncancel = () => res(false);
    d.showModal();
    $('#dlg-nao').focus();
  });
}

// ---------- parâmetros e valores ----------
export function rotulo(o) { return S.p[o] ? S.p[o].curto : '#' + o; }
// ordem da métrica pelo id do perfil (undefined se o perfil não tem)
export function ord(id) { const p = S.params.find(x => x.id === id); return p ? p.ordem : undefined; }
// ordens (por id) que existem no perfil carregado
export function ords(...ids) { return ids.map(ord).filter(o => o !== undefined); }

// ---------- escrita (somente leitura por padrão) ----------
export function escritaLigada() { return S.estado.escrita ?? S.info.escrita ?? false; }
export async function definirEscrita(ativa) {
  const r = await api('/api/escrita', { ativa });
  S.estado.escrita = r.escrita;
  emit('estado', S.estado);
  return r.escrita;
}
// chave "Permitir comandos de escrita": desligada a cada início do servidor; cada envio ainda pede confirmação
export function chaveEscrita() {
  const caixa = h('input', { type: 'checkbox', style: { width: '22px', height: '22px' }, checked: escritaLigada(),
    onchange: async e => {
      try {
        const lig = await definirEscrita(e.target.checked);
        toast(lig ? 'Escrita habilitada: apagar falhas e atuadores liberados (cada envio ainda pede confirmação).' : 'Escrita desabilitada: somente leitura.');
      } catch (err) { e.target.checked = escritaLigada(); toast(err.message, true); }
    } });
  const el = h('label.linha.chave-escrita', { style: { cursor: 'pointer', margin: '0' } }, caixa,
    h('span', h('b', 'Permitir comandos de escrita'), h('span.mut', ' · apagar falhas e acionar atuadores; desligado a cada início')));
  const f = () => { if (!el.isConnected) return desligar(); caixa.checked = escritaLigada(); };
  const desligar = on('estado', f);
  el.desligar = desligar;
  return el;
}

export function texto(o, v, txt) {
  const p = S.p[o];
  if (!p) return { v: '—', un: '' };
  if (v == null) return { v: '—', un: p.unidade };
  if (p.tipo === 'bit') {
    const t = txt ?? (p.mascaras ? (p.mascaras[v] ?? '?') : (p.textos ? p.textos[v ? 1 : 0] : v));
    return { v: t || (v ? 'sim' : 'não'), un: '', txt: true };
  }
  return { v: fmt(v, p.decimais), un: p.unidade };
}

// regra que vale agora (ou a primeira, apagada, se a condição não bate)
export function regraDe(o, ctx) {
  const rs = (S.faixas[o] || {}).regras || [];
  ctx = ctx || (S.ultimo && S.ultimo.ctx) || [];
  const r = rs.find(r => ctx.includes(r.quando));
  return r ? { r, ativa: true } : (rs[0] ? { r: rs[0], ativa: false } : null);
}

export function faixaTexto(rg, p) {
  if (!rg) return '';
  const r = rg.r;
  if ('normal_valor' in r) return 'normal: ' + (p && p.textos ? p.textos[r.normal_valor] : r.normal_valor);
  const d = p ? Math.min(p.decimais, 2) : 0;
  return fmt(r.normal[0], d) + '–' + fmt(r.normal[1], d) + (p && p.unidade ? ' ' + p.unidade : '');
}

export async function carregarParametros() {
  const j = await api('/api/parametros');
  S.params = j.parametros; S.p = Object.fromEntries(j.parametros.map(p => [p.ordem, p]));
  S.faixas = j.faixas; S.condicoes = j.condicoes; S.condicoesRotulo = j.condicoes_rotulo || {}; S.lambda = j.lambda;
  emit('faixas');
}

// ---------- ao vivo (SSE) ----------
function empurrar(it) {
  const b = S.vivo;
  b.hora.push(it.hora);
  for (const p of S.params) {
    (b.v[p.ordem] ||= Array(b.hora.length - 1).fill(null)).push(it.v[p.ordem] ?? null);
    (b.est[p.ordem] ||= Array(b.hora.length - 1).fill(0)).push(['neutro', 'normal', 'atencao', 'fora'].indexOf((it.est[p.ordem] || ['neutro'])[0]));
  }
  b.lambda.push(it.lambda ? it.lambda.diagnostico : null);
  let k = 0;
  while (k < b.hora.length && it.hora - b.hora[k] > BUFFER_S) k++;
  if (k) { b.hora.splice(0, k); b.lambda.splice(0, k); for (const o in b.v) { b.v[o].splice(0, k); b.est[o].splice(0, k); } }
  S.ultimo = it;
}

async function carregarBuffer() {
  const j = await api('/api/buffer');
  const b = novoBuffer();
  b.hora = j.hora; b.lambda = j.lambda;
  for (const o in j.v) { b.v[o] = j.v[o]; b.est[o] = j.est[o]; }
  S.vivo = b;
  emit('buffer');
}

// ---------- tema (docs/TEMA.md) ----------
// As cores, fontes e raios vêm de /api/tema.css (gerado no servidor a partir do tema ativo). Ao mudar o tema,
// o CSS novo carrega ao lado do velho e só então o velho sai: a página não pisca sem cor.
function corDaBarra() {
  const m = document.querySelector('meta[name=theme-color]');
  if (m) m.content = css('--bg');
}
export function recarregarTema() {
  return new Promise(res => {
    const velho = $('#tema-css'), novo = document.createElement('link');
    novo.rel = 'stylesheet'; novo.href = '/api/tema.css?v=' + Date.now();
    novo.onload = novo.onerror = () => { velho.remove(); novo.id = 'tema-css'; corDaBarra(); emit('tema'); res(); };
    velho.after(novo);
  });
}

let es = null;
function conectarSSE() {
  es = new EventSource('/api/stream');
  let caiu = false;
  es.addEventListener('leitura', e => { const it = JSON.parse(e.data); empurrar(it); emit('leitura', it); });
  es.addEventListener('tema', () => recarregarTema());
  es.addEventListener('layout', e => emit('layout', JSON.parse(e.data)));
  es.addEventListener('estado', e => { S.estado = JSON.parse(e.data); emit('estado', S.estado); topo(); });
  es.onerror = () => { caiu = true; $('#con-txt').textContent = 'servidor fora do ar'; $('#chip-con').className = 'chip mal'; };
  es.onopen = () => { if (caiu) carregarBuffer().catch(() => {}); };
}

// ---------- leitura ----------
const TITULO_MODO = {
  rapido: 'Leitura rápida (só o bloco da lambda: menos parâmetros, mais amostras)',
  completo: 'Leitura completa (todos os blocos: todos os parâmetros)',
};
export async function iniciarLeitura(modo = S.modo) {
  if (!await confirmar(modo, null, TITULO_MODO[modo] + ' — repete até Parar e reconecta sozinho se cair')) return false;
  try { await api('/api/' + modo, {}); S.modo = modo; guardar('modo', modo); topo(); return true; }
  catch (e) { toast(e.message, true); return false; }
}
export async function pararLeitura() {
  try {
    const r = await api('/api/parar', {});
    if (r.sessao) toast('Sessão salva: ' + (r.sessao.nome || r.sessao.id));
  } catch (e) { toast(e.message, true); }
}

// ---------- barra de topo ----------
function topo() {
  const e = S.estado;
  const con = $('#chip-con');
  const txt = { conectado: 'conectado', conectando: 'conectando…', reconectando: 'reconectando…', desconectado: 'desconectado' }[e.conexao] || e.conexao || '—';
  $('#con-txt').textContent = txt + (e.rodando && e.conexao === 'conectado' ? ' · ' + fmt(e.taxa_hz, 1) + '/s' : '') +
    (e.reconexoes ? ' · ' + e.reconexoes + ' rec.' : '');
  con.className = 'chip ' + (e.conexao === 'conectado' ? 'ok' : e.conexao === 'desconectado' || !e.conexao ? '' : 'mal');
  con.title = e.ultimo_erro ? 'Último erro: ' + e.ultimo_erro : 'Conexão com o scanner';
  $('#chip-rec').classList.toggle('oculto', !e.rec);
  if (e.rec) $('#rec-tempo').textContent = duracao(e.rec.s);
  const b = $('#btn-ler');
  b.classList.toggle('pri', !e.rodando);
  por(b, svg(e.rodando ? '<rect x="6" y="6" width="12" height="12" rx="1.5"/>' : '<path d="M7 5l12 7-12 7z"/>'),
    h('span.rotulo-longo', e.rodando ? 'Parar' : 'Ler'));
  b.title = e.rodando ? 'Parar a leitura (fecha a sessão gravada)' : TITULO_MODO[S.modo];
  for (const x of $$('#ler-modo button')) x.classList.toggle('ativo', x.dataset.modo === (e.rodando ? e.modo : S.modo));
  // faixa vermelha enquanto um atuador estiver ativo (o teste não para sozinho)
  const fa = $('#faixa-atuador');
  const a = e.atuador_ativo;
  if (a && !fa.dataset.ativo) {
    fa.dataset.ativo = '1';
    por(fa, h('div.faixa-atuador',
      h('span', '⚠ ', h('b', a.nome), ' em teste desde ' + a.desde + '. O teste não para sozinho: para parar, desligue a chave (ignição) por 5 s.'),
      h('button.btn.peq', { onclick: async () => { await api('/api/chave-desligada', {}); toast('Conexão encerrada. Pode ligar a chave de novo.'); } }, 'Já desliguei a chave')));
  } else if (!a && fa.dataset.ativo) { delete fa.dataset.ativo; por(fa); }
}

// ---------- rotas ----------
const ICONES = {
  tela: '<rect x="3" y="3" width="8" height="11" rx="1.5"/><rect x="13" y="3" width="8" height="7" rx="1.5"/><rect x="13" y="12" width="8" height="9" rx="1.5"/><rect x="3" y="16" width="8" height="5" rx="1.5"/>',
  inicio: '<path d="M3 11l9-7 9 7"/><path d="M5 10v10h14V10"/><path d="M10 20v-6h4v6"/>',
  painel: '<path d="M4 16a8 8 0 1116 0"/><path d="M12 16l4.5-5"/><circle cx="12" cy="16" r="1.4"/>',
  graficos: '<path d="M3 3v18h18"/><path d="M7 15l4-5 3 3 5-7"/>',
  lambda: '<path d="M6 20l6.5-11M8.5 4H11l7 16"/>',
  gravar: '<circle cx="12" cy="12" r="8.5"/><circle cx="12" cy="12" r="3.5" fill="currentColor"/>',
  sessoes: '<path d="M4 6h16M4 12h16M4 18h10"/>',
  falhas: '<path d="M12 3.5l9 16H3z"/><path d="M12 10v4M12 17v.01"/>',
  atuadores: '<path d="M13 2L4 14h7l-1 8 9-12h-7z"/>',
  avancado: '<path d="M8 6l-6 6 6 6M16 6l6 6-6 6"/>',
  mais: '<circle cx="5" cy="12" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="19" cy="12" r="1.6"/>',
};
// oficina: o essencial na frente; o resto em "Mais"
const TELAS = [
  ['tela', 'Painel'], ['inicio', 'Início'], ['painel', 'Ao vivo'], ['lambda', 'Lambda'], ['falhas', 'Falhas'], ['gravar', 'Gravar'], ['sessoes', 'Sessões'],
  ['graficos', 'Gráficos', 'mais'], ['atuadores', 'Atuadores', 'mais'], ['avancado', 'Avançado', 'mais'],
];
const CEL = ['tela', 'inicio', 'painel', 'falhas'];
const modulos = {};
let atual = null;

function montarNav() {
  por($("#nav"),
    h('div.marca', h('div.logo', 'λ'), h('span', 'PSASCAN', h('small#marca-central'))),
    ...TELAS.filter(t => !t[2]).map(([id, nome]) => h('a', { href: '#/' + id, 'data-tela': id, title: nome }, svg(ICONES[id]), h('span', nome))),
    h('div.grupo', 'Mais'),
    ...TELAS.filter(t => t[2]).map(([id, nome]) => h('a', { href: '#/' + id, 'data-tela': id, title: nome }, svg(ICONES[id]), h('span', nome))),
    h('div.rodape.mut', 'só 127.0.0.1 · sem internet'));
  const mais = h('a', { href: '#', 'data-tela': 'mais', onclick: e => { e.preventDefault(); menuMais(); } }, svg(ICONES.mais), 'Mais');
  por($('#nav-cel'), ...CEL.map(id => h('a', { href: '#/' + id, 'data-tela': id }, svg(ICONES[id]),
    TELAS.find(t => t[0] === id)[1])), mais);
}

function menuMais() {
  const velho = $('.menu-mais');
  if (velho) return velho.remove();
  const m = h('div.menu-mais', ...TELAS.filter(([id]) => !CEL.includes(id)).map(([id, nome]) =>
    h('a', { href: '#/' + id, onclick: () => m.remove() }, svg(ICONES[id]), nome)),
    h('a', { href: '#', onclick: e => { e.preventDefault(); alternarTema(); m.remove(); } }, svg('<path d="M12 3a9 9 0 100 18 7 7 0 010-18z"/>'), 'Tema'));
  document.body.append(m);
  setTimeout(() => document.addEventListener('click', function f(e) { if (!m.contains(e.target)) { m.remove(); document.removeEventListener('click', f); } }), 0);
}

async function rota() {
  const [id, ...resto] = (location.hash.replace(/^#\/?/, '') || 'inicio').split('/');
  const tela = TELAS.find(t => t[0] === id) ? id : 'inicio';
  if (atual && atual.desmontar) atual.desmontar();
  modulos[tela] ||= (await import('./' + tela + '.js')).default;
  atual = modulos[tela];
  $('#titulo').textContent = TELAS.find(t => t[0] === tela)[1];
  for (const a of $$('[data-tela]')) a.classList.toggle('ativo', a.dataset.tela === tela);
  const el = $('#tela');
  por(el);
  window.scrollTo(0, 0);
  try { await atual.montar(el, resto.map(decodeURIComponent)); }
  catch (e) { console.error(e); el.append(h('div.cartao', h('p.erro', 'Erro ao abrir a tela: ' + e.message))); }
}

function alternarTema() {
  const claro = document.documentElement.dataset.theme !== 'light';
  if (claro) document.documentElement.dataset.theme = 'light'; else delete document.documentElement.dataset.theme;
  try { localStorage.setItem('psascan.tema', claro ? 'light' : 'dark'); } catch (e) {}
  rotuloTema();
  corDaBarra();
  emit('tema');
}

// o botão mostra o tema para onde ele leva: sol no escuro, lua no claro
function rotuloTema() {
  const t = document.documentElement.dataset.theme === 'light' ? 'Mudar para o tema escuro' : 'Mudar para o tema claro';
  $('#btn-tema').title = t; $('#btn-tema').setAttribute('aria-label', t);
}

async function inicio() {
  montarNav();
  $('#btn-ler').onclick = () => S.estado.rodando ? pararLeitura() : iniciarLeitura();
  for (const b of $$('#ler-modo button')) b.onclick = async () => {
    const m = b.dataset.modo;
    if (S.estado.rodando) { if (m !== S.estado.modo) await iniciarLeitura(m); }
    else { S.modo = m; guardar('modo', m); topo(); }
  };
  $('#btn-tema').onclick = alternarTema; rotuloTema();
  const [info] = await Promise.all([api('/api/info'), carregarParametros()]);
  S.info = info;
  document.title = 'psascan · ' + info.nome;
  $('#marca-central').textContent = info.nome;
  if (info.simulador) $('#chip-con').before(h('span.chip.sim', { title: 'Valores simulados ou reproduzidos de um CSV; as falhas também são do simulador' }, 'SIMULADOR'));
  S.estado = await api('/api/resumo');
  await carregarBuffer();
  topo();
  conectarSSE();
  addEventListener('hashchange', rota);
  rota();
}

inicio().catch(e => { console.error(e); toast('Falha ao iniciar: ' + e.message, true); });

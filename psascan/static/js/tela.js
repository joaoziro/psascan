// Painel: a tela personalizável (docs/LAYOUT.md). Mostra o layout ativo com os widgets de static/widgets/
// e, no modo edição, deixa arrastar, redimensionar, trocar e salvar. A tela só monta a fonte de dados
// (a partir do estado do painel) e a edição; quem desenha a grade e os widgets é widgets/host.js.
import { S, on, api, toast, texto, regraDe } from './app.js';
import { $, h, por, baixarTexto } from './util.js';
import { criarHost, aceita, categoria } from '../widgets/host.js';

const clone = x => JSON.parse(JSON.stringify(x));
const limita = (v, a, b) => Math.max(a, Math.min(b, v));
const RE_ID = /^[a-z0-9-]{2,32}$/;
const NEUTRA = { estado: 'neutro', lado: null, msg: '', condicao: '', faixa: null };

let raiz, host, catalogo, lista = [], L = null, original = null, editando = false, sujo = false, sel = null;
let temaPainel = null, temaId = null, falhas = null, desligar = [], timerFalhas = null, areaEl, pilhaEl, edicaoEl, lateralEl, barraEl;

// ---------------------------------------------------------------- dados do painel -> fonte do host
const velho = it => !it || Date.now() / 1000 - it.hora > 5;

const fonte = {
  get tema() { return temaPainel; },
  get temaId() { return temaId; },
  metrica: id => S.params.find(p => p.id === id),
  valor(m) {
    const it = S.ultimo;
    if (velho(it)) return null;
    const v = it.v[m.ordem];
    if (v == null) return null;                 // bloco não lido nesta leitura
    return { numero: v, texto: texto(m.ordem, v, it.txt[m.ordem]).v, bruto: v };
  },
  historico(m, seg) {
    const b = S.vivo, vs = b.v[m.ordem], t = [], v = [];
    if (!vs || !b.hora.length) return { t, v };
    const fim = b.hora[b.hora.length - 1];
    for (let i = b.hora.length - 1; i >= 0 && fim - b.hora[i] <= seg; i--) {
      if (vs[i] == null) continue;
      t.push(b.hora[i]); v.push(vs[i]);
    }
    return { t: t.reverse(), v: v.reverse() };
  },
  avaliacao(m) {
    const it = S.ultimo;
    if (velho(it)) return NEUTRA;
    const [estado, msg, lado] = it.est[m.ordem] || ['neutro', '', null];
    const rg = regraDe(m.ordem);
    return { estado, lado: lado || null, msg: msg || '', condicao: rg && rg.ativa ? (S.condicoesRotulo?.[rg.r.quando] || '') : '',
      faixa: rg && rg.ativa && rg.r.normal ? rg.r.normal : null };
  },
  falhas: () => falhas,
};

async function carregarTema() {
  const t = await api('/api/tema');
  temaPainel = t.painel; temaId = t.id;
}

// última leitura de falhas: presentes e memorizadas da leitura mais recente (histórico do servidor)
async function carregarFalhas() {
  try {
    const r = await api('/api/falhas/historico');
    const atuais = r.falhas.filter(f => f.na_ultima_leitura);
    falhas = r.leituras ? { presentes: atuais.filter(f => f.presente), memorizadas: atuais.filter(f => !f.presente), hora: r.ultima_leitura } : null;
    host?.agendar();
  } catch (e) { console.error(e); }
}

// ---------------------------------------------------------------- layouts
async function carregarLista() {
  const r = await api('/api/layouts');
  lista = r.layouts;
  if (r.erro) toast('Layout ativo não pôde ser usado: ' + r.erro + ' O Painel padrão está no lugar.', true);
}

async function abrir(id) {
  L = await api('/api/layout' + (id ? '?id=' + encodeURIComponent(id) : ''));
  original = null; editando = false; sujo = false; sel = null;
  desenhar();
}

async function trocar(id) {
  try { await api('/api/layout/ativar', { id }); await carregarLista(); await abrir(id); }
  catch (e) { toast(e.message, true); await carregarLista().catch(() => {}); barra(); }
}

// ---------------------------------------------------------------- geometria (modo edição)
const tipoDe = w => catalogo.tipos[w.tipo];

/** O retângulo r = {x, y, l, a} cabe na grade sem sobrepor outro widget (menos `ignorar`)? */
function cabe(r, ignorar) {
  if (r.l < 1 || r.a < 1 || r.x < 0 || r.y < 0 || r.x + r.l > L.grade.colunas || r.y + r.a > L.grade.linhas) return false;
  return !L.widgets.some(o => o.id !== ignorar && r.x < o.x + o.largura && o.x < r.x + r.l && r.y < o.y + o.altura && o.y < r.y + r.a);
}

function primeiroLivre(l, a) {
  for (let y = 0; y + a <= L.grade.linhas; y++) for (let x = 0; x + l <= L.grade.colunas; x++) if (cabe({ x, y, l, a })) return { x, y };
  return null;
}

function idNovo(tipo) {
  let n = 1;
  while (L.widgets.some(w => w.id === tipo + '-' + n)) n++;
  return tipo + '-' + n;
}

const metricasDo = tipoCat => S.params.filter(p => aceita(tipoCat, p));

/** Garante uma métrica que o tipo aceite (ou nenhuma, se o tipo não usa / a luz vem das falhas). */
function ajustarMetrica(w) {
  const cat = tipoDe(w);
  const sem = cat.sem_metrica_se;
  if (!(cat.metricas || []).length || (sem && Object.entries(sem).every(([k, v]) => (w.opcoes || {})[k] === v))) {
    delete w.metrica;
    return;
  }
  const atual = S.params.find(p => p.id === w.metrica);
  if (atual && aceita(cat, atual)) return;
  const prim = metricasDo(cat)[0];
  if (prim) w.metrica = prim.id;
}

function mudou() {
  sujo = true;
  host.montar(L);
  desenharEdicao();
}

// ---------------------------------------------------------------- arrastar e redimensionar
function iniciarGesto(ev, w, modo, caixa) {
  if (ev.button > 0) return;
  ev.preventDefault();
  ev.stopPropagation();
  caixa.setPointerCapture(ev.pointerId);
  const r = edicaoEl.getBoundingClientRect();
  const cw = r.width / L.grade.colunas, ch = r.height / L.grade.linhas;
  const x0 = ev.clientX, y0 = ev.clientY;
  const o = { x: w.x, y: w.y, l: w.largura, a: w.altura };
  const cat = tipoDe(w);
  let moveu = false, alvo = o, ok = true;
  const fantasma = h('div.w-fantasma');
  const posicionar = (g, a) => { g.style.gridColumn = (a.x + 1) + ' / span ' + a.l; g.style.gridRow = (a.y + 1) + ' / span ' + a.a; };

  const mover = e => {
    if (!moveu && Math.hypot(e.clientX - x0, e.clientY - y0) < 6) return;
    if (!moveu) { moveu = true; caixa.classList.add('arrastando'); edicaoEl.append(fantasma); }
    const dx = Math.round((e.clientX - x0) / cw), dy = Math.round((e.clientY - y0) / ch);
    if (modo === 'mover') {
      alvo = { x: limita(o.x + dx, 0, L.grade.colunas - o.l), y: limita(o.y + dy, 0, L.grade.linhas - o.a), l: o.l, a: o.a };
    } else {
      alvo = { x: o.x, y: o.y,
        l: limita(o.l + dx, cat.minimo[0], Math.max(cat.minimo[0], Math.min(cat.maximo[0], L.grade.colunas - o.x))),
        a: limita(o.a + dy, cat.minimo[1], Math.max(cat.minimo[1], Math.min(cat.maximo[1], L.grade.linhas - o.y))) };
    }
    ok = cabe(alvo, w.id);
    fantasma.className = 'w-fantasma ' + (ok ? 'ok' : 'ruim');
    posicionar(fantasma, alvo);
  };
  const fim = e => {
    caixa.removeEventListener('pointermove', mover);
    caixa.removeEventListener('pointerup', fim);
    caixa.removeEventListener('pointercancel', fim);
    fantasma.remove();
    caixa.classList.remove('arrastando');
    if (!moveu) return e.type === 'pointerup' ? selecionar(w.id) : null;     // toque sem arrastar = selecionar
    if (e.type === 'pointercancel' || !ok) return;                             // onde não cabe, não solta
    Object.assign(w, { x: alvo.x, y: alvo.y, largura: alvo.l, altura: alvo.a });
    mudou();
    selecionar(w.id, true);
  };
  caixa.addEventListener('pointermove', mover);
  caixa.addEventListener('pointerup', fim);
  caixa.addEventListener('pointercancel', fim);
}

function teclado(e, w) {
  const d = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }[e.key];
  if (d) {
    e.preventDefault();
    const cat = tipoDe(w);
    const r = e.shiftKey
      ? { x: w.x, y: w.y, l: limita(w.largura + d[0], cat.minimo[0], cat.maximo[0]), a: limita(w.altura + d[1], cat.minimo[1], cat.maximo[1]) }
      : { x: w.x + d[0], y: w.y + d[1], l: w.largura, a: w.altura };
    if (!cabe(r, w.id)) return;
    Object.assign(w, { x: r.x, y: r.y, largura: r.l, altura: r.a });
    mudou();
    selecionar(w.id, true);
  } else if (e.key === 'Delete') {
    e.preventDefault();
    remover(w);
  } else if (e.key === 'Enter' || e.key === ' ') {
    e.preventDefault();
    selecionar(w.id, true);
  } else if (e.key === 'Escape') {
    sel = null; desenharEdicao(); painelLateral();
  }
}

// ---------------------------------------------------------------- edição de widgets
function selecionar(id, foco = false) {
  sel = id;
  desenharEdicao();
  painelLateral();
  if (foco) edicaoEl.querySelector('[data-id="' + id + '"]')?.focus();
}

function remover(w) {
  L.widgets = L.widgets.filter(x => x !== w);
  sel = null;
  mudou();
  painelLateral();
}

function adicionar() {
  const cat = catalogo.tipos.numero;
  const quer = [[Math.max(cat.minimo[0], 2), Math.max(cat.minimo[1], 2)], cat.minimo];
  const m = metricasDo(cat)[0];
  for (const [l, a] of quer) {
    const p = primeiroLivre(l, a);
    if (!p) continue;
    const w = { id: idNovo('numero'), tipo: 'numero', x: p.x, y: p.y, largura: l, altura: a };
    if (m) w.metrica = m.id;
    L.widgets.push(w);
    mudou();
    return selecionar(w.id, true);
  }
  toast('Não há espaço livre na grade. Apague ou encolha um widget primeiro.', true);
}

function trocarTipo(w, novo) {
  const cat = catalogo.tipos[novo];
  const l = limita(w.largura, cat.minimo[0], cat.maximo[0]), a = limita(w.altura, cat.minimo[1], cat.maximo[1]);
  if (!cabe({ x: w.x, y: w.y, l, a }, w.id)) { toast('Não cabe aqui como "' + cat.nome + '" (tamanho mínimo ' + cat.minimo.join('×') + ').', true); return false; }
  w.tipo = novo; w.largura = l; w.altura = a;
  const opcoes = {};
  for (const k in w.opcoes || {}) if (k in cat.opcoes) opcoes[k] = w.opcoes[k];
  if (Object.keys(opcoes).length) w.opcoes = opcoes; else delete w.opcoes;
  ajustarMetrica(w);
  return true;
}

// caminho de opção ['escala', 'min'] -> valor (ou apaga a chave; objeto que ficou vazio some junto)
function definirOpcao(w, caminho, valor) {
  w.opcoes ||= {};
  const pais = [w.opcoes];
  for (const k of caminho.slice(0, -1)) pais.push(pais[pais.length - 1][k] ||= {});
  const ult = caminho[caminho.length - 1];
  if (valor === undefined) delete pais[pais.length - 1][ult]; else pais[pais.length - 1][ult] = valor;
  for (let i = pais.length - 1; i > 0; i--) if (!Object.keys(pais[i]).length) delete pais[i - 1][caminho[i - 1]];
  if (!Object.keys(w.opcoes).length) delete w.opcoes;
}

const opcaoAtual = (w, caminho) => caminho.reduce((o, k) => (o == null ? undefined : o[k]), w.opcoes);

/** Um campo do formulário de opções, gerado da descrição do catálogo. */
function campo(w, caminho, esp, rotulo) {
  // só refaz o painel quando outros campos dependem deste (refazer a cada campo tiraria o foco de quem usa Tab)
  const refaz = ['fonte', 'conteudo'].includes(caminho[caminho.length - 1]);
  const aoMudar = v => { definirOpcao(w, caminho, v); ajustarMetrica(w); mudou(); if (refaz) painelLateral(); };
  const val = opcaoAtual(w, caminho);
  const nome = h('span', rotulo);
  switch (esp.tipo) {
    case 'bool':
      return h('label.campo.chave', h('input', { type: 'checkbox', checked: val ?? esp.padrao ?? false,
        onchange: e => aoMudar(e.target.checked) }), nome);
    case 'lista': {
      const atual = val ?? esp.padrao;
      return h('label.campo', nome, h('select', { onchange: e => aoMudar(e.target.value || undefined) },
        esp.padrao === undefined ? h('option', { value: '' }, '(do perfil)') : null,
        ...esp.valores.map(v => h('option', { value: v, selected: v === atual }, v))));
    }
    case 'inteiro': case 'numero':
      return h('label.campo', nome, h('input', { type: 'number', inputmode: 'decimal', step: esp.tipo === 'inteiro' ? 1 : 'any',
        min: esp.min, max: esp.max, value: val ?? '', placeholder: esp.padrao != null ? String(esp.padrao) : 'do perfil',
        onchange: e => {
          if (e.target.value === '') return aoMudar(undefined);
          let v = Number(e.target.value);
          if (!Number.isFinite(v)) return aoMudar(undefined);
          if (esp.tipo === 'inteiro') v = Math.round(v);
          if (esp.min != null) v = Math.max(esp.min, v);
          if (esp.max != null) v = Math.min(esp.max, v);
          if (esp.min_exclusivo != null && v <= esp.min_exclusivo) v = esp.min_exclusivo + (esp.tipo === 'inteiro' ? 1 : 0.001);
          aoMudar(v);
        } }));
    case 'texto':
      return h('label.campo', nome, h('input', { type: 'text', maxlength: esp.max, value: val ?? '',
        onchange: e => aoMudar(e.target.value.trim() || undefined) }));
    case 'metrica':
      return h('label.campo', nome, h('select', { onchange: e => aoMudar(e.target.value || undefined) },
        h('option', { value: '' }, '(nenhuma)'),
        ...S.params.filter(p => categoria(p.tipo_perfil) === 'numerica').map(p => h('option', { value: p.id, selected: p.id === val }, p.nome))));
    case 'objeto': return objeto(w, caminho, esp, rotulo);
  }
  return null;
}

function objeto(w, caminho, esp, rotulo) {
  const obrig = Object.values(esp.campos).some(f => f.obrigatorio);
  const atual = opcaoAtual(w, caminho);
  if (obrig) {                         // ex. arco_inferior: existe inteiro ou não existe
    const liga = h('label.campo.chave', h('input', { type: 'checkbox', checked: !!atual, onchange: e => {
      if (!e.target.checked) { definirOpcao(w, caminho, undefined); }
      else {
        const m = S.params.find(p => categoria(p.tipo_perfil) === 'numerica' && p.id !== w.metrica) || S.params[0];
        const novo = {};
        for (const [k, f] of Object.entries(esp.campos)) {
          novo[k] = f.tipo === 'metrica' ? m.id : f.tipo === 'numero' ? (k === 'min' ? (m.min ?? 0) : (m.max ?? 100)) : undefined;
        }
        definirOpcao(w, caminho, novo);
      }
      mudou(); painelLateral();
    } }), h('span', rotulo));
    return h('div.campo-obj', liga, atual ? h('div.sub', ...Object.entries(esp.campos).map(([k, f]) => campo(w, [...caminho, k], f, k))) : null);
  }
  const sub = Object.entries(esp.campos).filter(([k]) => !(caminho.join('.') === 'centro' && k === 'metrica' && opcaoAtual(w, ['centro', 'conteudo']) !== 'metrica'));
  return h('details.campo-obj', { open: true }, h('summary', rotulo), h('div.sub', ...sub.map(([k, f]) => campo(w, [...caminho, k], f, k))));
}

function painelLateral() {
  if (!editando) return por(lateralEl);
  const w = L.widgets.find(x => x.id === sel);
  if (!w) {
    return por(lateralEl,
      h('h3', 'Grade'),
      h('p.mut', { style: { fontSize: '13px' } }, 'Toque num widget para editar. Arraste pelo corpo para mover e pelo canto para redimensionar; setas movem, Shift+setas redimensionam, Delete remove.'),
      h('div.form',
        h('label.campo', 'Colunas', h('input', { type: 'number', min: 4, max: 16, value: L.grade.colunas, onchange: e => tamanhoGrade('colunas', e.target) })),
        h('label.campo', 'Linhas', h('input', { type: 'number', min: 3, max: 12, value: L.grade.linhas, onchange: e => tamanhoGrade('linhas', e.target) }))),
      h('p.mut', { style: { fontSize: '12.5px' } }, L.widgets.length + ' widget(s) de no máximo 40.'));
  }
  const cat = tipoDe(w);
  const ms = metricasDo(cat);
  const sem = cat.sem_metrica_se;
  const usaMetrica = (cat.metricas || []).length && !(sem && Object.entries(sem).every(([k, v]) => opcaoAtual(w, [k]) === v));
  const atual = S.params.find(p => p.id === w.metrica);
  let tit;
  por(lateralEl,
    h('h3', 'Widget ' + w.id),
    h('label.campo', 'Tipo', h('select', { onchange: e => { if (trocarTipo(w, e.target.value)) mudou(); painelLateral(); desenharEdicao(); } },
      ...Object.entries(catalogo.tipos).map(([k, t]) => h('option', { value: k, selected: k === w.tipo }, t.nome)))),
    usaMetrica ? h('label.campo', 'Métrica', h('select', { onchange: e => { w.metrica = e.target.value; mudou(); } },
      ...(w.metrica && !ms.some(p => p.id === w.metrica) ? [h('option', { value: w.metrica, selected: true }, w.metrica + ' (não serve)')] : []),
      ...ms.map(p => h('option', { value: p.id, selected: p.id === w.metrica }, p.nome + (p.unidade ? ' (' + p.unidade + ')' : ''))))) : null,
    h('label.campo', 'Título', tit = h('input', { type: 'text', maxlength: 40, value: w.titulo ?? '', placeholder: atual ? atual.curto : '(sem título)',
      onchange: e => { const v = e.target.value.trim(); if (v) w.titulo = v; else delete w.titulo; mudou(); } })),
    h('p.mut', { style: { fontSize: '12.5px', margin: '0 0 10px' } }, 'Tamanho ' + w.largura + '×' + w.altura + ' (aceito: ' + cat.minimo.join('×') + ' a ' + cat.maximo.join('×') + ').'),
    Object.keys(cat.opcoes).length ? h('h3', { style: { margin: '6px 0 8px' } }, 'Opções') : null,
    ...Object.entries(cat.opcoes).map(([k, esp]) => campo(w, [k], esp, k.replace(/_/g, ' '))),
    h('div.linha', { style: { marginTop: '12px' } }, h('button.btn.perigo.peq', { type: 'button', onclick: () => remover(w) }, 'Remover widget'),
      h('button.btn.fantasma.peq', { type: 'button', onclick: () => { sel = null; desenharEdicao(); painelLateral(); } }, 'Fechar')));
}

function tamanhoGrade(k, input) {
  const [lo, hi] = k === 'colunas' ? [4, 16] : [3, 12];
  const v = limita(Math.round(Number(input.value)) || L.grade[k], lo, hi);
  const eixo = k === 'colunas' ? w => w.x + w.largura : w => w.y + w.altura;
  if (L.widgets.some(w => eixo(w) > v)) { toast('Há widgets além desse limite: mova ou apague antes de diminuir a grade.', true); input.value = L.grade[k]; return; }
  L.grade[k] = v; input.value = v;
  mudou();
}

// ---------------------------------------------------------------- desenho
function desenharEdicao() {
  por(edicaoEl);
  pilhaEl.classList.toggle('editando', editando);
  if (!editando) return;
  const g = edicaoEl.style;
  g.gridTemplateColumns = 'repeat(' + L.grade.colunas + ', minmax(0, 1fr))';
  g.gridTemplateRows = 'repeat(' + L.grade.linhas + ', minmax(0, 1fr))';
  for (let y = 0; y < L.grade.linhas; y++) for (let x = 0; x < L.grade.colunas; x++) {
    edicaoEl.append(h('div.w-fundo', { style: { gridColumn: String(x + 1), gridRow: String(y + 1) } }));
  }
  for (const w of L.widgets) {
    const caixa = h('div.w-caixa' + (w.id === sel ? '.sel' : '') + (host.motivos.has(w.id) ? '.indisp' : ''), {
      'data-id': w.id, tabindex: 0, role: 'button', 'aria-label': 'Widget ' + w.id + ', ' + w.tipo,
      style: { gridColumn: (w.x + 1) + ' / span ' + w.largura, gridRow: (w.y + 1) + ' / span ' + w.altura },
      onpointerdown: e => iniciarGesto(e, w, 'mover', caixa),
      onkeydown: e => teclado(e, w) },
    h('span.w-grip', '⠿ ' + (w.titulo || w.metrica || tipoDe(w).nome)),
    h('span.w-redim', { title: 'Arraste para redimensionar', onpointerdown: e => iniciarGesto(e, w, 'redim', caixa) }));
    edicaoEl.append(caixa);
  }
}

function barra() {
  const ativo = lista.find(x => x.id === L?.id);
  const orient = L?.orientacao && L.orientacao !== 'qualquer' && matchMedia('(orientation: ' + (L.orientacao === 'paisagem' ? 'portrait' : 'landscape') + ')').matches
    ? h('span.mut', { style: { fontSize: '12.5px' } }, 'Este layout foi pensado para ' + L.orientacao + '.') : null;
  por(barraEl,
    h('select', { 'aria-label': 'Layout', disabled: editando, onchange: e => trocar(e.target.value) },
      ...lista.filter(x => x.valido || x.id === L?.id).map(x => h('option', { value: x.id, selected: x.id === L?.id }, x.nome + (x.embutido ? '' : ' (meu)')))),
    !editando ? [
      h('button.btn', { type: 'button', onclick: entrarEdicao }, 'Editar'),
      h('button.btn.fantasma', { type: 'button', onclick: () => areaEl.requestFullscreen?.().catch(() => {}), title: 'Tela cheia' }, 'Tela cheia'),
      h('div.esp'),
      h('label.btn.fantasma', { style: { cursor: 'pointer' } }, 'Importar…',
        h('input', { type: 'file', accept: '.json,application/json', class: 'sr', onchange: e => { importar(e.target.files[0]); e.target.value = ''; } })),
      h('button.btn.fantasma', { type: 'button', onclick: exportar }, 'Exportar'),
      ativo && !ativo.embutido ? h('button.btn.fantasma', { type: 'button', onclick: apagar }, 'Apagar') : null,
    ] : [
      h('button.btn', { type: 'button', onclick: adicionar }, '+ widget'),
      h('div.esp'),
      h('button.btn.fantasma', { type: 'button', onclick: exportar }, 'Exportar'),
      h('button.btn', { type: 'button', onclick: cancelar }, 'Cancelar'),
      h('button.btn.pri', { type: 'button', onclick: salvarComo }, 'Salvar como…'),
    ],
    orient);
}

function desenhar() {
  barra();
  host.montar(L);
  desenharEdicao();
  painelLateral();
  $('.tela-corpo', raiz)?.classList.toggle('editando', editando);
}

// ---------------------------------------------------------------- ações
function entrarEdicao() {
  original = clone(L);
  editando = true; sujo = false; sel = null;
  desenhar();
}

function cancelar() {
  if (sujo && !confirm('Descartar as mudanças deste layout?')) return;
  L = original; original = null; editando = false; sujo = false; sel = null;
  desenhar();
}

function exportar() {
  baixarTexto('layout-' + L.id + '.json', JSON.stringify(L, null, 1) + '\n', 'application/json');
}

async function importar(arquivo) {
  if (!arquivo) return;
  try {
    const resp = await fetch('/api/layout/importar', { method: 'POST', body: await arquivo.arrayBuffer() });
    const j = await resp.json().catch(() => ({ erro: 'resposta inválida do servidor' }));
    if (j.erro) throw new Error(j.erro);
    toast('Layout "' + j.ok.nome + '" importado.');
    await api('/api/layout/ativar', { id: j.ok.id });
    await carregarLista();
    await abrir(j.ok.id);
  } catch (e) { toast('Layout recusado: ' + e.message, true); }
}

async function apagar() {
  if (!confirm('Apagar o layout "' + L.nome + '"? Isso não pode ser desfeito.')) return;
  try {
    await api('/api/layout/apagar', { id: L.id });
    toast('Layout apagado.');
    await carregarLista();
    await abrir();
  } catch (e) { toast(e.message, true); }
}

function salvarComo() {
  const meu = lista.find(x => x.id === L.id && !x.embutido);
  const id = h('input', { type: 'text', value: meu ? L.id : (L.id + '-meu').slice(0, 32), maxlength: 32, autocapitalize: 'off', spellcheck: 'false' });
  const nome = h('input', { type: 'text', value: meu ? L.nome : (L.nome + ' (meu)').slice(0, 40), maxlength: 40 });
  const msg = h('p.erro');
  const d = h('dialog');
  const fechar = () => { d.close(); d.remove(); };
  const ok = async () => {
    const lid = id.value.trim();
    if (!RE_ID.test(lid)) return por(msg, 'Id inválido: use a-z, 0-9 e hífen, de 2 a 32 caracteres.');
    if (lista.some(x => x.id === lid && x.embutido)) return por(msg, '"' + lid + '" é um layout que vem com o projeto: escolha outro id.');
    if (!nome.value.trim()) return por(msg, 'Dê um nome ao layout.');
    if (lista.some(x => x.id === lid) && lid !== L.id && !confirm('Já existe um layout "' + lid + '". Substituir?')) return;
    const novo = clone(L);
    novo.id = lid; novo.nome = nome.value.trim();
    try {
      await api('/api/layout/salvar', { layout: novo });
      await api('/api/layout/ativar', { id: lid });
      fechar();
      toast('Layout "' + novo.nome + '" salvo e ativado.');
      await carregarLista();
      await abrir(lid);
    } catch (e) { por(msg, 'Não foi possível salvar: ' + e.message); }
  };
  d.append(h('h2', 'Salvar layout'),
    h('p.ink2', { style: { fontSize: '13.5px' } }, 'Os layouts que vêm com o projeto não mudam: a edição vira uma cópia sua.'),
    h('div.form', { style: { gridTemplateColumns: '1fr' } }, h('label', 'Id (a-z, 0-9, hífen)', id), h('label', 'Nome', nome)),
    msg,
    h('div.acoes', h('button.btn', { type: 'button', onclick: fechar }, 'Cancelar'), h('button.btn.pri', { type: 'button', onclick: ok }, 'Salvar')));
  document.body.append(d);
  d.showModal();
  nome.select();
}

// ---------------------------------------------------------------- tela
let catalogoPromessa = null;
function pegarCatalogo() {
  return catalogoPromessa ||= fetch('/static/widgets/catalogo.json').then(r => r.json());
}

export default {
  async montar(el) {
    raiz = el;
    el.classList.add('cheia');
    editando = false; sujo = false; sel = null; falhas = null;
    catalogo = await pegarCatalogo();
    await Promise.all([carregarTema(), carregarLista()]);
    barraEl = h('div.tela-barra');
    areaEl = h('div.tela-pilha');
    pilhaEl = areaEl;
    const hostEl = h('div.w-raiz');
    edicaoEl = h('div.w-edicao');
    areaEl.append(hostEl, edicaoEl);
    lateralEl = h('aside.tela-lateral');
    el.append(barraEl, h('div.tela-corpo', areaEl, lateralEl));
    L = await api('/api/layout');
    host = criarHost(hostEl, { layout: L, catalogo, fonte });
    barra();
    painelLateral();
    carregarFalhas();
    timerFalhas = setInterval(carregarFalhas, 10000);
    const agendar = () => host.agendar();
    desligar = [on('leitura', agendar), on('buffer', agendar), on('faixas', agendar), on('estado', agendar),
      on('falhas', carregarFalhas),
      on('tema', async () => { try { await carregarTema(); host.montar(); } catch (e) { console.error(e); } }),
      on('layout', async () => {            // outro navegador (ou a aba Avançado) trocou o ativo ou mudou o layout
        if (editando) return;
        try { await carregarLista(); await abrir(); } catch (e) { console.error(e); }
      })];
  },
  desmontar() {
    desligar.forEach(f => f()); desligar = [];
    clearInterval(timerFalhas);
    host?.destruir(); host = null;
    if (document.fullscreenElement) document.exitFullscreen?.().catch(() => {});
    raiz?.classList.remove('cheia');
  },
};

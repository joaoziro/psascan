// Host da tela personalizável: dado um layout (docs/LAYOUT.md) e o catálogo, monta a grade, cria cada
// widget (static/widgets/<tipo>.js) e o alimenta com o contexto `c` descrito em base.js.
//
// O host não conhece a página: os dados vêm de uma `fonte` (quem usa o host a entrega):
//   fonte.tema                 o `painel` do tema ativo (completo)
//   fonte.temaId               id do tema ativo
//   fonte.metrica(id)          métrica do perfil (nome, curto, unidade, tipo_perfil...) ou undefined
//   fonte.valor(m)             {numero, texto, bruto} da última leitura, ou null
//   fonte.historico(m, seg)    {t: [...], v: [...]} (t em segundos, o último é "agora"; sem buracos de null)
//   fonte.avaliacao(m)         {estado, lado, msg, condicao, faixa}
//   fonte.falhas()             {presentes, memorizadas, hora} ou null
// A tela (js/tela.js) monta a fonte a partir do estado do painel; widgets/teste.html, a partir de valores falsos.
//
//   const host = criarHost(raiz, { layout, catalogo, fonte });
//   host.agendar()             pede um atualizar() no próximo quadro (várias chamadas viram uma)
//   host.montar(layout?)       (re)cria a grade e os widgets; usar na troca de layout, de tema e de tamanho
//   host.destruir()
// O host monta sozinho na primeira vez que a área tem tamanho e recria quando o tamanho muda (ResizeObserver);
// quem usa só chama montar(layout) ao trocar de layout ou de tema.

const CATEGORIAS = { u8: 'numerica', u16: 'numerica', curva: 'numerica', bit: 'bit', enum: 'discreta' };
const NOME_CATEGORIA = { numerica: 'numérica', discreta: 'discreta', bit: 'de bit' };
const NEUTRA = { estado: 'neutro', lado: null, msg: '', condicao: '', faixa: null };

export const categoria = tipoPerfil => CATEGORIAS[tipoPerfil] || null;

/** O tipo do catálogo aceita esta métrica? (`bit` conta como discreta também, como no Python.) */
export function aceita(tipoCat, m) {
  const cat = categoria(m.tipo_perfil);
  const ok = tipoCat.metricas || [];
  return ok.includes(cat) || (cat === 'bit' && ok.includes('discreta'));
}

// ---------------------------------------------------------------- opções completadas com os padrões
function completarCampos(campos, dado) {
  const out = {};
  for (const [k, esp] of Object.entries(campos)) {
    if (dado && k in dado) {
      out[k] = esp.tipo === 'objeto' ? completarCampos(esp.campos, dado[k]) : dado[k];
    } else if (esp.tipo === 'objeto') {
      // objeto que tem obrigatórios (arco_inferior) só existe se o layout o pediu; os outros, com padrões, sempre
      if (Object.values(esp.campos).some(f => f.obrigatorio)) continue;
      const sub = completarCampos(esp.campos, null);
      if (Object.keys(sub).length) out[k] = sub;
    } else if ('padrao' in esp) {
      out[k] = esp.padrao;
    }
  }
  return out;
}
export const completarOpcoes = (tipoCat, opcoes) => completarCampos(tipoCat.opcoes, opcoes || {});

/** Métricas citadas dentro das opções (centro.metrica, arco_inferior.metrica...): [[caminho, id], ...]. */
function metricasDasOpcoes(campos, dado, caminho = '', out = []) {
  for (const [k, esp] of Object.entries(campos)) {
    if (!dado || !(k in dado)) continue;
    if (esp.tipo === 'metrica') out.push([caminho + k, dado[k]]);
    else if (esp.tipo === 'objeto') {
      // centro.metrica só vale quando o centro mostra "outra métrica"
      if (k === 'centro' && dado[k].conteudo !== 'metrica') continue;
      metricasDasOpcoes(esp.campos, dado[k], caminho + k + '.', out);
    }
  }
  return out;
}

/** Por que o widget não pode ser montado com este perfil? (texto) ou null se pode. */
export function motivoIndisponivel(w, tipoCat, fonte) {
  if (!tipoCat) return 'tipo "' + w.tipo + '" desconhecido';
  const sem = tipoCat.sem_metrica_se;
  const dispensa = sem && Object.entries(sem).every(([k, v]) => (w.opcoes || {})[k] === v);
  if ((tipoCat.metricas || []).length && !dispensa) {
    const m = w.metrica ? fonte.metrica(w.metrica) : null;
    if (!m) return 'a métrica "' + (w.metrica || '?') + '" não existe neste perfil';
    if (!aceita(tipoCat, m)) {
      return 'o widget "' + tipoCat.nome + '" não aceita a métrica ' + m.curto + ' (' + NOME_CATEGORIA[categoria(m.tipo_perfil)] + ')';
    }
  }
  for (const [caminho, id] of metricasDasOpcoes(tipoCat.opcoes, w.opcoes)) {
    if (!fonte.metrica(id)) return 'a métrica "' + id + '" (opção ' + caminho + ') não existe neste perfil';
  }
  return null;
}

// ---------------------------------------------------------------- módulos dos widgets
const modulos = new Map();
export function carregarModulo(tipo) {
  if (!modulos.has(tipo)) {
    modulos.set(tipo, import('./' + tipo + '.js').then(m => m.default || null).catch(e => {
      console.error('módulo do widget ausente ou com erro:', tipo, e);
      return null;
    }));
  }
  return modulos.get(tipo);
}

// ---------------------------------------------------------------- host
function aviso(corpo, titulo, texto, erro = false) {
  const t = document.createElement('div');
  t.className = 'w-aviso' + (erro ? ' erro' : '');
  const a = document.createElement('b'), b = document.createElement('span');
  a.textContent = titulo || '';
  b.textContent = texto;
  t.append(a, b);
  corpo.replaceChildren(t);
}

export function criarHost(raiz, { layout, catalogo, fonte }) {
  const host = { layout, catalogo, fonte, grade: null, celulas: new Map(), motivos: new Map() };
  let regs = [], pend = false, seq = 0, vivo = true, tamanho = null, timer = null;

  function contexto(r) {
    const { w, tipoCat, m } = r;
    const c = {
      metrica: m || null,
      valor: m ? fonte.valor(m) : null,
      avaliacao: m ? fonte.avaliacao(m) : NEUTRA,
      extra: {},
      falhas: fonte.falhas(),
      tema: fonte.tema,
      temaId: fonte.temaId,
      area: r.area,
      opcoes: r.opcoes,
      titulo: w.titulo ?? (m ? m.curto : ''),
    };
    for (const [, id] of r.extras) {
      const mx = fonte.metrica(id);
      if (mx) c.extra[id] = { metrica: mx, valor: fonte.valor(mx) };
    }
    // histórico só é calculado se o widget o ler (a maioria não usa)
    let memo = null;
    Object.defineProperty(c, 'historico', {
      enumerable: true,
      get() {
        if (!memo) memo = m ? fonte.historico(m, r.opcoes.segundos ?? 60) : { t: [], v: [] };
        return memo;
      },
    });
    return c;
  }

  function falhou(r, e) {
    console.error('erro no widget', r.w.id, '(' + r.w.tipo + ')', e);
    try { r.inst?.destruir?.(); } catch (_) { /* já deu erro */ }
    r.inst = null;
    aviso(r.corpo, 'erro no widget', r.w.id + ': ' + (e && e.message ? e.message : e), true);
  }

  function atualizarUm(r) {
    if (!r.inst) return;
    try { r.inst.atualizar(contexto(r)); } catch (e) { falhou(r, e); }
  }

  host.atualizar = function () {
    pend = false;
    if (!vivo || document.hidden) return;
    for (const r of regs) atualizarUm(r);
  };

  host.agendar = function () {
    if (pend || !vivo) return;
    pend = true;
    requestAnimationFrame(host.atualizar);
  };

  function limpar() {
    for (const r of regs) {
      try { r.inst?.destruir?.(); } catch (e) { console.error('erro ao destruir o widget', r.w.id, e); }
    }
    regs = [];
    host.celulas.clear();
    host.motivos.clear();
  }

  host.montar = async function (novo) {
    if (novo) host.layout = novo;
    const meu = ++seq;
    limpar();
    const L = host.layout;
    if (!host.grade) {
      host.grade = document.createElement('div');
      host.grade.className = 'w-grade';
      raiz.append(host.grade);
    }
    const g = host.grade;
    g.replaceChildren();
    g.style.gridTemplateColumns = 'repeat(' + L.grade.colunas + ', minmax(0, 1fr))';
    g.style.gridTemplateRows = 'repeat(' + L.grade.linhas + ', minmax(0, 1fr))';
    const novos = [];
    for (const w of L.widgets) {
      const tipoCat = host.catalogo.tipos[w.tipo];
      const cel = document.createElement('div');
      cel.className = 'w-cel';
      cel.dataset.widget = w.id;
      cel.style.gridColumn = (w.x + 1) + ' / span ' + w.largura;
      cel.style.gridRow = (w.y + 1) + ' / span ' + w.altura;
      const corpo = document.createElement('div');
      corpo.className = 'w-corpo';
      cel.append(corpo);
      g.append(cel);
      host.celulas.set(w.id, cel);
      const r = { w, tipoCat, cel, corpo, inst: null, mod: null, m: w.metrica ? fonte.metrica(w.metrica) : null,
        opcoes: tipoCat ? completarOpcoes(tipoCat, w.opcoes) : {}, area: null, extras: [] };
      if (tipoCat) r.extras = metricasDasOpcoes(tipoCat.opcoes, w.opcoes).filter(([, id]) => fonte.metrica(id));
      const motivo = motivoIndisponivel(w, tipoCat, fonte);
      if (motivo) {
        host.motivos.set(w.id, motivo);
        cel.classList.add('indisponivel');
        aviso(corpo, w.titulo || r.m?.curto || w.metrica || w.tipo, 'indisponível: ' + motivo);
      }
      r.motivo = motivo;
      novos.push(r);
    }
    // módulos em paralelo; só depois cria, já com as células no DOM e medidas
    const mods = await Promise.all(novos.map(r => (r.motivo || !r.tipoCat ? null : carregarModulo(r.w.tipo))));
    if (meu !== seq || !vivo) return;
    novos.forEach((r, i) => { r.mod = mods[i]; });
    regs = novos;
    for (const r of regs) {
      if (r.motivo) continue;
      if (!r.mod) { aviso(r.corpo, r.w.tipo, 'módulo ausente: ' + r.w.tipo, true); continue; }
      const largura = r.corpo.clientWidth, altura = r.corpo.clientHeight;
      if (!largura || !altura) continue;       // escondido: o ResizeObserver remonta quando aparecer
      r.area = { largura, altura };
      try {
        r.inst = r.mod.criar(r.corpo, contexto(r));
        r.inst.atualizar(contexto(r));
      } catch (e) { falhou(r, e); }
    }
  };

  host.destruir = function () {
    vivo = false;
    seq++;
    clearTimeout(timer);
    ro.disconnect();
    limpar();
    raiz.replaceChildren();
    host.grade = null;
  };

  // tamanho mudou (janela, rotação, painel lateral): recria depois de um respiro, para não refazer a cada pixel
  const ro = new ResizeObserver(([e]) => {
    const t = Math.round(e.contentRect.width) + 'x' + Math.round(e.contentRect.height);
    if (t === tamanho) return;
    const primeira = tamanho === null;
    tamanho = t;
    if (!e.contentRect.width || !e.contentRect.height) return;
    clearTimeout(timer);
    timer = setTimeout(() => { if (vivo) host.montar(); }, primeira ? 0 : 150);
  });
  ro.observe(raiz);

  return host;
}

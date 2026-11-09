// Widget `mostrador`: mostrador circular de ponteiro (contrato: docs/LAYOUT.md, pele: docs/TEMA.md).
//
// Desenha igual ao painel do carro (static/painel-carro.html) quando o tema não tem pele: aro, fundo,
// escala em níveis com régua fina, números com contorno, agulha (agulha/seta/barra) com brilho e sombra,
// miolo em relevo, rolos de odômetro, número digital e a barra do arco de baixo (a da água).
// Tudo o que se vê vem de c.tema (cores, espessuras, ponteiro, escala, raios, animação e peles).
//
// O SVG só é refeito quando muda o tamanho, o tema ou as opções; a cada leitura só mudam atributos de
// transform, textos e a pintura da escala. A animação do ponteiro usa requestAnimationFrame e para
// quando o ponteiro chega no valor.

import { svgEl as S, pol, arco, numBr, decimais, urlArquivo, fonte, frac } from './base.js';

let sequencia = 0;
const tamanhosImagem = new Map();   // url → {w, h} (para a agulha de imagem manter a proporção)

const numeroOuNulo = x => {
  if (x == null || x === '') return null;
  const n = Number(x);
  return Number.isFinite(n) ? n : null;
};
const setTxt = (n, s) => { if (n.__s !== s) { n.textContent = s; n.__s = s; } };
const setAt = (n, k, v) => { if (n['_' + k] !== v) { n.setAttribute(k, v); n['_' + k] = v; } };

// Passo "bonito" para ficar com uns 8 números na escala.
function passoAuto(faixa) {
  const bruto = faixa / 8, mag = Math.pow(10, Math.floor(Math.log10(bruto)));
  for (const m of [1, 2, 2.5, 5, 10]) if (m * mag >= bruto - 1e-12) return m * mag;
  return 10 * mag;
}

// Escala efetiva: opções do layout, depois min/max da métrica, depois passos calculados.
function escalaDe(c) {
  const o = c.opcoes?.escala || {}, m = c.metrica || {};
  const min = numeroOuNulo(o.min) ?? numeroOuNulo(m.min) ?? 0;
  let max = numeroOuNulo(o.max) ?? numeroOuNulo(m.max) ?? min + 100;
  if (max <= min) max = min + 1;
  const maior = numeroOuNulo(o.maior) > 0 ? Number(o.maior) : passoAuto(max - min);
  let menor = Math.round(numeroOuNulo(o.menor) ?? 10);
  menor = Math.min(20, Math.max(1, menor));
  // não deixa a régua fina passar de ~400 traços
  while ((max - min) / (maior / menor) > 400 && menor > 1) menor--;
  const div = numeroOuNulo(o.divisor_rotulo) > 0 ? Number(o.divisor_rotulo) : 1;
  return { min, max, maior, menor, div, alerta: numeroOuNulo(o.alerta) };
}

const fmtRotulo = x => {
  const r = Math.round(x * 100) / 100;
  return Number.isInteger(r) ? String(r) : String(r).replace('.', ',');
};

function dimensoes(url, cb) {
  const t = tamanhosImagem.get(url);
  if (t) return cb(t);
  const i = new Image();
  i.onload = () => {
    const d = { w: i.naturalWidth || 1, h: i.naturalHeight || 1 };
    tamanhosImagem.set(url, d);
    cb(d);
  };
  i.onerror = () => {};
  i.src = url;
}

export default {
  tipo: 'mostrador',

  criar(el, c0) {
    let ctx = c0, vivo = true, geracao = 0, raf = null, ultT = 0;
    let chave = null, ultTema = null, ultTemaStr = '';
    let m = null, svg = null;
    const est = { v: null, a: null };           // o que está desenhado (animado)
    const alvo = { v: null, a: null, x: null }; // o que foi lido

    el.style.display = 'flex';
    el.style.alignItems = 'center';
    el.style.justifyContent = 'center';

    const lado = c => Math.max(1, Math.floor(Math.min(c.area?.largura || 1, c.area?.altura || 1)));
    const extraDe = (c, id) => (id ? c.extra?.[id] : null) || null;

    function chaveDe(c) {
      if (c.tema !== ultTema) { ultTema = c.tema; ultTemaStr = JSON.stringify(c.tema); }
      const o = c.opcoes || {};
      const mt = c.metrica ? [c.metrica.id, c.metrica.unidade, c.metrica.decimais, c.metrica.min, c.metrica.max, c.metrica.curto] : null;
      const ex = id => { const e = extraDe(c, id); return e?.metrica ? [e.metrica.id, e.metrica.unidade, e.metrica.decimais, e.metrica.min, e.metrica.max] : null; };
      return JSON.stringify([lado(c), c.temaId, c.titulo, o, mt, ex(o.centro?.metrica), ex(o.arco_inferior?.metrica)]) + ultTemaStr;
    }

    // ---------------------------------------------------------------- montagem do SVG
    function montar() {
      const c = ctx, T = c.tema, E = T.espessuras, Z = T.ponteiro;
      const pele = T.peles?.mostrador || {}, im = pele.imagens || {}, des = pele.desenhar || {};
      const C = { ...T.cores, ...(pele.cores || {}) };   // a pele pode trocar cores só do mostrador
      const arqImg = k => { const v = im[k]; return (v && (typeof v === 'string' ? v : v.arquivo)) || null; };
      const imgFundo = arqImg('fundo'), imgAg = arqImg('agulha'), imgMiolo = arqImg('miolo');
      const imgVidro = arqImg('vidro'), imgMasc = arqImg('mascara_arco');
      const liga = (parte, temImagem) => des[parte] ?? !temImagem;
      const dAro = liga('aro', imgFundo), dEscala = liga('escala', imgFundo), dNumeros = liga('numeros', imgFundo);
      const dPonteiro = liga('ponteiro', imgAg), dMiolo = liga('miolo', imgMiolo);

      const ES = escalaDe(c);
      const g0 = numeroOuNulo(pele.angulo_inicio) ?? -128, g1 = numeroOuNulo(pele.angulo_fim) ?? 128;
      const ang = v => g0 + (v - ES.min) / (ES.max - ES.min) * (g1 - g0);
      const R = ES.max - ES.min;
      const emCima = T.escala.numeros_por_cima, passar = T.escala.pintar_passou, tres = T.escala.niveis === 3;
      const COR = { passou: C.passou, grande: C.texto, medio: C.texto_medio, fino: C.texto_fino, vermelho: C.alerta };
      const LAY = { grande: [84, 72.5, E.traco_grande], medio: [84, 78, E.traco_medio], fino: [88.4, 85, E.traco_fino],
                    regua: [88.4, 85], alerta: 89.6 };
      const id = 'mo' + (++sequencia);
      const minha = ++geracao;
      const ativa = () => vivo && minha === geracao;

      const L = lado(c);
      const nova = S('svg', { viewBox: '0 0 200 200', width: L, height: L, 'font-family': fonte(c),
        style: 'display:block;overflow:visible' });
      const defs = S('defs', {}, nova);
      const degrade = (tipo, at, paradas) => {
        const g = S(tipo, at, defs);
        for (const [offset, cor] of paradas) S('stop', { offset, 'stop-color': cor }, g);
      };
      const texto = (pai, x, y, tam, at = {}) => S('text', Object.assign({ x, y, 'text-anchor': 'middle',
        'dominant-baseline': 'central', 'font-size': tam, fill: COR.grande }, at), pai);
      const imagem = arq => S('image', { href: urlArquivo(c, arq), x: 0, y: 0, width: 200, height: 200,
        preserveAspectRatio: 'xMidYMid meet' }, nova);

      // gradientes e filtros (mesmos do painel do carro)
      degrade('linearGradient', { id: id + 'a', x1: 0, y1: 0, x2: 0, y2: 1 }, [[0, C.aro[0]], [.5, C.aro[1]], [1, C.aro[2]]]);
      degrade('radialGradient', { id: id + 'f', cx: .5, cy: .5, r: .5 }, [[0, C.face[0]], [.7, C.face[1]], [1, C.face[2]]]);
      const sp = S('filter', { id: id + 's', x: -.5, y: -.5, width: 2, height: 2 }, defs);
      S('feDropShadow', { dx: .9, dy: 1.6, stdDeviation: 1.1, 'flood-color': C.sombra,
        'flood-opacity': Math.min(1, Z.sombra * 85 / 55) }, sp);
      const bl = S('filter', { id: id + 'b', x: -1, y: -1, width: 3, height: 3 }, defs);
      S('feGaussianBlur', { stdDeviation: 2 }, bl);
      degrade('linearGradient', { id: id + 'p', x1: 0, y1: 0, x2: 1, y2: 0 },
        [[0, C.ponteiro[0]], [.48, C.ponteiro[1]], [.52, C.ponteiro[2]], [1, C.ponteiro[3]]]);
      degrade('radialGradient', { id: id + 'm', cx: .5, cy: .38, r: .65 }, [[0, C.miolo[0]], [1, C.miolo[1]]]);
      degrade('linearGradient', { id: id + 'c', x1: 0, y1: 0, x2: 0, y2: 1 }, [[0, C.chanfro[0]], [.5, C.chanfro[1]], [1, C.chanfro[2]]]);

      // 1. fundo (imagem) e aro
      if (imgFundo) imagem(imgFundo);
      if (dAro && imgFundo) {          // com fundo em imagem, o aro é só o anel (não cobre a arte)
        const rInt = 99.5 - E.aro;
        S('circle', { cx: 100, cy: 100, r: (99.5 + rInt) / 2, fill: 'none', stroke: `url(#${id}a)`, 'stroke-width': E.aro }, nova);
        S('circle', { cx: 100, cy: 100, r: rInt - .4, fill: 'none', stroke: C.aro_interno, 'stroke-width': .8 }, nova);
      } else if (dAro) {
        const rInt = 99.5 - E.aro;
        S('circle', { cx: 100, cy: 100, r: 99.5, fill: `url(#${id}a)` }, nova);
        S('circle', { cx: 100, cy: 100, r: rInt, fill: C.aro_interno }, nova);
        S('circle', { cx: 100, cy: 100, r: rInt - .8, fill: `url(#${id}f)` }, nova);
      }

      // 2. escala e números
      const numeros = S('g', {});
      const itens = [];
      const quando = (v, e, attr, nao, sim) => itens.push({ v, e, attr, nao, sim });
      const alertaV = ES.alerta;
      const quente = v => alertaV != null && v >= alertaV - 1e-9;
      if (dEscala || dNumeros) {
        const nTotal = Math.max(1, Math.round(R / (ES.maior / ES.menor)));
        const fino = R / nTotal;
        const medioPasso = ES.menor % 2 === 0 ? ES.menor / 2 : 0;
        const nivelDe = k => (k % ES.menor === 0 ? 'grande' : (medioPasso && k % medioPasso === 0) ? 'medio' : 'fino');
        const rotuloMax = fmtRotulo(ES.max / ES.div).length;
        const rNum = rotuloMax <= 2 ? 60 : 58.5, tamNum = rotuloMax <= 2 ? 16 : rotuloMax === 3 ? 12.5 : 10.5;
        if (dEscala && alertaV != null && alertaV < ES.max) {
          S('path', { d: arco(100, 100, LAY.alerta, ang(Math.max(alertaV, ES.min)), g1), stroke: COR.vermelho,
            'stroke-width': E.alerta, fill: 'none' }, nova);
        }
        if (dEscala) {
          for (let k = 0; k <= nTotal; k++) {
            const v = ES.min + k * fino, g = ang(v), q = quente(v), nivel = nivelDe(k);
            if (nivel === 'fino' && !tres) continue;
            const [r0, r1, w] = LAY[nivel];
            const [x0, y0] = pol(100, 100, r0, g), [x1, y1] = pol(100, 100, r1, g);
            const ln = S('line', { x1: x0, y1: y0, x2: x1, y2: y1, 'stroke-width': w }, nova);
            const base = q ? COR.vermelho : COR[nivel];
            quando(v, ln, 'stroke', base, q ? COR.vermelho : passar ? COR.passou : base);
          }
          // régua fina contínua por baixo dos traços médios e grandes
          for (let k = 0; tres && k <= nTotal; k++) {
            if (nivelDe(k) === 'fino') continue;
            const v = ES.min + k * fino, g = ang(v), q = quente(v);
            const [x0, y0] = pol(100, 100, LAY.regua[0], g), [x1, y1] = pol(100, 100, LAY.regua[1], g);
            const base = q ? COR.vermelho : COR.fino;
            quando(v, S('line', { x1: x0, y1: y0, x2: x1, y2: y1, 'stroke-width': E.traco_fino }, nova), 'stroke',
              base, q ? COR.vermelho : passar ? COR.passou : base);
          }
        }
        if (dNumeros) {
          for (let k = 0; k <= nTotal; k += ES.menor) {
            const v = ES.min + k * fino, g = ang(v), q = quente(v);
            const [tx, ty] = pol(100, 100, rNum, g);
            const at = { x: tx, y: ty, 'font-size': tamNum, 'font-weight': 500, 'letter-spacing': -.3 };
            if (E.contorno_numeros > 0) Object.assign(at, { stroke: C.contorno_numeros, 'stroke-width': E.contorno_numeros,
              'stroke-linejoin': 'round', 'paint-order': 'stroke' });
            const t = texto(numeros, tx, ty, tamNum, at);
            t.textContent = fmtRotulo(v / ES.div);
            const cor = q ? COR.vermelho : COR.grande;
            quando(v, t, 'fill', cor, q ? COR.vermelho : passar ? COR.passou : cor);
          }
        }
      }
      if (!emCima) nova.append(numeros);
      const pintar = valor => {
        for (const it of itens) {
          const cor = it.v <= valor + 1e-9 && valor > ES.min ? it.sim : it.nao;
          if (it.cor !== cor) { it.e.setAttribute(it.attr, cor); it.cor = cor; }
        }
      };

      // 3. arco: o da pele (aceso até o valor) e/ou a barra do arco inferior (a da água)
      const pa = pele.arco && pele.arco.mostrar !== false ? pele.arco : null;
      let desenharArcoPele = null;
      if (pa) {
        const r = pa.raio ?? 84, esp = pa.espessura ?? 9, cor = pa.cor ?? C.ok, corAl = pa.cor_alerta ?? C.alerta;
        if (pa.estilo === 'segmentos') {
          const n = Math.max(2, Math.round(pa.segmentos ?? 48)), passo = (g1 - g0) / n, folga = passo * .15;
          const segs = [];
          for (let i = 0; i < n; i++) {
            const a0 = g0 + i * passo + folga / 2, a1 = g0 + (i + 1) * passo - folga / 2;
            const p = S('path', { d: arco(100, 100, r, a0, a1), fill: 'none', stroke: C.agua_trilho, 'stroke-width': esp }, nova);
            const centro = (i + .5) / n;
            segs.push({ p, f: centro, al: alertaV != null && ES.min + centro * R >= alertaV });
          }
          desenharArcoPele = v => {
            const f = frac(v, ES.min, ES.max);
            for (const s of segs) setAt(s.p, 'stroke', s.f <= f + 1e-9 && f > 0 ? (s.al ? corAl : cor) : C.agua_trilho);
          };
        } else {
          S('path', { d: arco(100, 100, r, g0, Math.min(g1, g0 + 359.9)), fill: 'none', stroke: C.agua_trilho, 'stroke-width': esp }, nova);
          const ga = alertaV != null ? Math.min(g1, Math.max(g0, ang(alertaV))) : g1;
          const p1 = S('path', { fill: 'none', stroke: cor, 'stroke-width': esp }, nova);
          const p2 = S('path', { fill: 'none', stroke: corAl, 'stroke-width': esp }, nova);
          desenharArcoPele = v => {
            const gv = g0 + frac(v, ES.min, ES.max) * (g1 - g0);
            setAt(p1, 'd', gv - g0 > .05 ? arco(100, 100, r, g0, Math.min(gv, ga)) : '');
            setAt(p2, 'd', gv > ga + .05 && alertaV != null ? arco(100, 100, r, ga, gv) : '');
          };
        }
      }
      let desenharMascara = null;
      if (imgMasc) {
        const cp = S('clipPath', { id: id + 'k' }, defs), pc = S('path', { d: 'M0 0' }, cp);
        const img = imagem(imgMasc);
        img.setAttribute('clip-path', `url(#${id}k)`);
        desenharMascara = v => {
          const gv = g0 + frac(v, ES.min, ES.max) * (g1 - g0), varr = Math.min(gv - g0, 359.9);
          if (varr < .05) return setAt(pc, 'd', 'M0 0');
          const [x0, y0] = pol(100, 100, 142, g0), [x1, y1] = pol(100, 100, 142, g0 + varr);
          setAt(pc, 'd', `M100 100 L${x0} ${y0} A142 142 0 ${varr > 180 ? 1 : 0} 1 ${x1} ${y1} Z`);
        };
      }

      const AI = c.opcoes?.arco_inferior?.metrica ? c.opcoes.arco_inferior : null;
      let barra = null, tAg = null, termo = null, AG0 = 148, AG1 = 212, angAg = null;
      if (AI) {
        const exA = extraDe(c, AI.metrica), decA = exA?.metrica?.decimais ?? 0, unA = exA?.metrica?.unidade || '';
        const aMin = numeroOuNulo(AI.min) ?? 0, aMax = numeroOuNulo(AI.max) ?? aMin + 1;
        angAg = v => AG1 - frac(v, aMin, aMax) * (AG1 - AG0);
        S('path', { d: arco(100, 100, 55.25, AG0, AG1), stroke: C.agua_trilho, 'stroke-width': E.agua, fill: 'none' }, nova);
        barra = S('path', { 'stroke-width': E.agua, fill: 'none' }, nova);
        // marcas: meio (grande), quartos (finas) e o início da zona de alerta
        for (const [f, r0, r1, w, cor] of [[.5, 50, 60.5, 1.6, COR.grande], [.25, 52.5, 59.5, .8, COR.fino],
                                          [.75, 52.5, 59.5, .8, COR.fino], [.9625, 51, 60, 2.2, COR.vermelho]]) {
          const g = AG1 - f * (AG1 - AG0);
          const [x0, y0] = pol(100, 100, r0, g), [x1, y1] = pol(100, 100, r1, g);
          S('line', { x1: x0, y1: y0, x2: x1, y2: y1, 'stroke-width': w, stroke: cor }, nova);
        }
        for (const [s, g] of [['C', AG1 - 3], ['H', AG0 + 3]]) {
          const [x, y] = pol(100, 100, 47, g);
          texto(nova, x, y - 4, 6.5, { fill: COR.medio }).textContent = s;
        }
        termo = S('path', { d: 'M0 -4v5.2a1.9 1.9 0 1 0 1.6 0V-4a.8 .8 0 0 0-1.6 0z M2.6 -2.5h1.6 M2.6 -.5h1.2',
          fill: 'none', 'stroke-width': .7, transform: 'translate(87 132)' }, nova);
        tAg = texto(nova, 102, 132, 7.5, { 'font-variant-numeric': 'tabular-nums' });
        m_arco.dec = decA; m_arco.un = unA; m_arco.min = aMin; m_arco.max = aMax;
      }

      // 4. agulha
      let setAgulha = () => {};
      if (dPonteiro) {
        const gP = S('g', {}, nova), r0 = Z.raio_inicio, r1 = Z.raio_ponta;
        const y0 = 100 - r0, y1 = 100 - r1, b = E.ponteiro_base, p = E.ponteiro_ponta;
        let forma = `M${100 - b} ${y0} L${100 - p} ${y1} L${100 + p} ${y1} L${100 + b} ${y0} Z`;
        if (Z.estilo === 'barra') forma = `M${100 - b} ${y0} L${100 - b} ${y1} L${100 + b} ${y1} L${100 + b} ${y0} Z`;
        if (Z.estilo === 'seta') {
          const yh = y1 + Math.min(14, (r1 - r0) * .25), s = Math.max(p, b * .5), hw = Math.max(b * 2.2, p * 2);
          forma = `M${100 - b} ${y0} L${100 - s} ${yh} L${100 - hw} ${yh} L${100 - p} ${y1} L${100 + p} ${y1} ` +
            `L${100 + hw} ${yh} L${100 + s} ${yh} L${100 + b} ${y0} Z`;
        }
        if (Z.brilho > 0) S('path', { d: forma, fill: C.ponteiro_brilho, opacity: Z.brilho, filter: `url(#${id}b)` }, gP);
        S('path', { d: forma, fill: `url(#${id}p)`, filter: `url(#${id}s)` }, gP);
        S('line', { x1: 100, y1: y0, x2: 100, y2: y1 + 1, stroke: C.ponteiro_aresta, 'stroke-opacity': .3, 'stroke-width': .35 }, gP);
        const antes = setAgulha;
        setAgulha = g => { antes(g); setAt(gP, 'transform', `rotate(${g} 100 100)`); };
      }
      if (imgAg) {
        const ag = typeof im.agulha === 'object' ? im.agulha : {};
        const piv = Array.isArray(ag.pivo) ? ag.pivo : [.5, .5], comp = numeroOuNulo(ag.comprimento) ?? .45;
        const gA = S('g', {}, nova), url = urlArquivo(c, imgAg);
        const imgA = S('image', { href: url, visibility: 'hidden', preserveAspectRatio: 'none' }, gA);
        dimensoes(url, d => {
          if (!ativa()) return;
          const h = comp * 200, w = h * d.w / d.h;
          imgA.setAttribute('x', 100 - piv[0] * w); imgA.setAttribute('y', 100 - piv[1] * h);
          imgA.setAttribute('width', w); imgA.setAttribute('height', h);
          imgA.removeAttribute('visibility');
        });
        const antes = setAgulha;
        setAgulha = g => { antes(g); setAt(gA, 'transform', `rotate(${g} 100 100)`); };
      }
      if (emCima) nova.append(numeros);

      // 5. miolo
      if (dMiolo) {
        const r = T.raios.miolo;
        S('circle', { cx: 100, cy: 100, r, fill: `url(#${id}m)`, filter: `url(#${id}s)` }, nova);
        S('circle', { cx: 100, cy: 100, r: r - .5, fill: 'none', stroke: `url(#${id}c)`, 'stroke-width': 1 }, nova);
        S('circle', { cx: 100, cy: 100, r: r - 3, fill: 'none', stroke: C.sombra, 'stroke-opacity': .6, 'stroke-width': .6 }, nova);
      }
      if (imgMiolo) imagem(imgMiolo);

      // 6. leituras do centro
      // o termômetro e a temperatura do arco de baixo ficam por cima do miolo (como no painel)
      if (termo) { nova.append(termo); nova.append(tAg); }
      const cont = c.opcoes?.centro?.conteudo ?? 'digital';
      let rolos = null, digital = null;
      if (cont === 'rolos') {
        const rot = c.metrica?.unidade || c.titulo || '';
        texto(nova, 100, 85, 7, { fill: COR.fino, 'letter-spacing': 1.8 }).textContent = String(rot).toUpperCase();
        rolos = [];
        for (let k = 0; k < 4; k++) {
          const milhar = k === 0;
          S('rect', { x: 78.5 + k * 11, y: 94, width: 10, height: 14, rx: 1.2,
            fill: milhar ? C.rolo_destaque_fundo : C.rolo_fundo, stroke: milhar ? C.rolo_destaque_borda : C.rolo_borda,
            'stroke-width': .6 }, nova);
          rolos.push(texto(nova, 83.5 + k * 11, 101.4, 10, { 'font-variant-numeric': 'tabular-nums',
            fill: milhar ? C.rolo_destaque_texto : C.rolo_texto }));
        }
      } else if (cont === 'digital' || cont === 'metrica') {
        const pd = pele.digital || null;
        const ex = cont === 'metrica' ? extraDe(c, c.opcoes?.centro?.metrica) : null;
        const mt = cont === 'metrica' ? ex?.metrica : c.metrica;
        const dec = cont === 'metrica' ? (mt?.decimais ?? 0) : decimais(c);
        const mx = cont === 'metrica' ? numeroOuNulo(mt?.max) : ES.max;
        const px = pd?.posicao?.[0] ?? 100, py = pd?.posicao?.[1] ?? 95, tam = numeroOuNulo(pd?.tamanho) ?? 34;
        const fam = fonte(c, pd?.fonte), cor = pd?.cor ?? C.texto;
        const fantasma = pd?.apagados && mx != null ? numBr(mx, dec).replace(/\d/g, '8') : null;
        const base = { 'font-family': fam, 'font-size': tam, 'font-weight': 400,
          'letter-spacing': pd ? 0 : -.5, 'font-variant-numeric': 'tabular-nums' };
        let ghost = null, glow = null;
        if (fantasma) {
          ghost = texto(nova, px, py, tam, Object.assign({}, base, { fill: cor, opacity: .08 }));
          ghost.textContent = fantasma;
        }
        if (pd && pd.brilho > 0) {
          const fb = S('filter', { id: id + 'g', x: -.3, y: -.6, width: 1.6, height: 2.2 }, defs);
          S('feGaussianBlur', { stdDeviation: Math.max(.5, tam * .07) }, fb);
          glow = texto(nova, px, py, tam, Object.assign({}, base, { fill: cor, opacity: pd.brilho, filter: `url(#${id}g)` }));
        }
        const principal = texto(nova, px, py, tam, Object.assign({}, base, { fill: cor }));
        const un = mt?.unidade ? texto(nova, px, py + tam * .62, Math.max(5, tam * .235), { fill: COR.fino }) : null;
        if (un) un.textContent = mt.unidade;
        digital = { principal, glow, dec, largura: fantasma ? fantasma.length : 0, cont };
      }

      // 7. vidro
      if (imgVidro) imagem(imgVidro);

      // ---- desenho de um quadro: v e a já são os valores animados
      const lim = ES.max + R * .02;
      const desenhar = (v, a) => {
        setAgulha(ang(Math.max(ES.min, Math.min(lim, v))));
        pintar(v);
        if (desenharArcoPele) desenharArcoPele(v);
        if (desenharMascara) desenharMascara(v);
        if (rolos) {
          const n = alvo.v == null ? null : (R >= 1000 ? Math.round(alvo.v / 10) * 10 : Math.round(alvo.v));
          const s = n == null ? '----' : String(Math.max(0, n)).padStart(4, '0').slice(-4);
          rolos.forEach((t, k) => setTxt(t, s[k]));
        }
        if (digital) {
          const bruto = digital.cont === 'metrica' ? alvo.x : (alvo.v == null ? null : v);
          let s = bruto == null ? '—' : numBr(bruto, digital.dec);
          if (digital.largura) s = s.padStart(digital.largura, '\u00a0');
          setTxt(digital.principal, s);
          if (digital.glow) setTxt(digital.glow, s);
        }
        if (barra) {
          const fa = alvo.a == null ? null : frac(alvo.a, m_arco.min, m_arco.max);
          const cor = fa == null ? COR.fino : fa < .25 ? COR.passou : fa >= .75 ? COR.vermelho : C.ok;
          const limite = a == null ? AG1 : Math.min(angAg(a), AG1 - .5);
          setAt(barra, 'd', a == null ? '' : arco(100, 100, 55.25, limite, AG1));
          setAt(barra, 'stroke', cor); setAt(tAg, 'fill', cor); setAt(termo, 'stroke', cor);
          setTxt(tAg, alvo.a == null ? '—' : numBr(alvo.a, m_arco.dec) + (m_arco.un ? ' ' + m_arco.un : ''));
        }
      };

      svg?.remove();
      svg = nova;
      el.replaceChildren(svg);
      m = { ES, desenhar, tem_arco: !!AI };
    }

    // dados do arco inferior (preenchidos em montar)
    const m_arco = { dec: 0, un: '', min: 0, max: 1 };

    // ---------------------------------------------------------------- valores e animação
    function lerAlvo() {
      const c = ctx;
      alvo.v = numeroOuNulo(c.valor?.numero);
      const AI = c.opcoes?.arco_inferior?.metrica ? c.opcoes.arco_inferior : null;
      alvo.a = AI ? numeroOuNulo(extraDe(c, AI.metrica)?.valor?.numero) : null;
      const cm = c.opcoes?.centro;
      alvo.x = cm?.conteudo === 'metrica' ? numeroOuNulo(extraDe(c, cm.metrica)?.valor?.numero) : null;
    }
    const destinoV = () => alvo.v ?? m.ES.min;

    function quadro(t) {
      raf = null;
      if (!vivo || !m) return;
      const T = ctx.tema, modo = T.animacao.ponteiro, rap = T.animacao.rapidez;
      const dt = Math.min(.1, (t - ultT) / 1000); ultT = t;
      const k = modo === 'nenhuma' ? 1 : 1 - Math.exp(-dt * rap * (modo === 'suave' ? 3 : 1));
      est.v += (destinoV() - est.v) * k;
      if (alvo.a != null) est.a = est.a == null ? alvo.a : est.a + (alvo.a - est.a) * k; else est.a = null;
      if (chegou()) { est.v = destinoV(); est.a = alvo.a; }
      m.desenhar(est.v, est.a);
      if (!chegou()) raf = requestAnimationFrame(quadro);
    }
    function chegou() {
      const eps = (m.ES.max - m.ES.min) * 5e-4;
      const epsA = (m_arco.max - m_arco.min) * 5e-4;
      return Math.abs(destinoV() - est.v) <= eps && (alvo.a == null || est.a == null || Math.abs(alvo.a - est.a) <= epsA);
    }
    function mover() {
      if (est.v == null) est.v = destinoV();
      if (est.a == null) est.a = alvo.a;
      if (ctx.tema.animacao.ponteiro === 'nenhuma' || chegou()) {
        if (raf != null) { cancelAnimationFrame(raf); raf = null; }
        est.v = destinoV(); est.a = alvo.a;
        m.desenhar(est.v, est.a);
        return;
      }
      m.desenhar(est.v, est.a);
      if (raf == null) { ultT = performance.now(); raf = requestAnimationFrame(quadro); }
    }

    function atualizar(c) {
      ctx = c;
      const k = chaveDe(c);
      if (k !== chave) { chave = k; montar(); }
      lerAlvo();
      mover();
    }

    atualizar(c0);

    return {
      atualizar,
      destruir() {
        vivo = false;
        if (raf != null) cancelAnimationFrame(raf);
        raf = null;
        svg?.remove();
      },
    };
  },
};

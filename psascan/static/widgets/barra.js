// Widget barra: barra horizontal ou vertical, contínua ou em segmentos, com marca da faixa
// normal. Contrato: docs/LAYOUT.md; pele `barra` em docs/TEMA.md.
import { el, numBr, decimais, corEstado, fonte, frac, urlArquivo } from './base.js';

const limita = (x, a, b) => Math.min(b, Math.max(a, x));
const assinatura = c => JSON.stringify([c.area, c.tema, c.opcoes, c.temaId, c.titulo]);

export default {
  tipo: 'barra',
  criar(el0, c0) {
    let c = c0, sig = null;
    let r = null;

    function limites() {
      const mn = c.opcoes?.min ?? c.metrica?.min ?? 0;
      let mx = c.opcoes?.max ?? c.metrica?.max ?? 100;
      if (mx === mn) mx = mn + 1;
      return [mn, mx];
    }

    function montar() {
      sig = assinatura(c);
      el0.textContent = '';
      const k = c.tema.cores, pele = c.tema.peles?.barra || {};
      const w = c.area.largura, h = c.area.altura;
      const vert = c.opcoes?.orientacao === 'vertical';
      const fs = limita(Math.min(w, h) * 0.17, 10, 18);
      const esp = limita(Math.min(w, h) * 0.24, 6, 30);
      const raio = pele.raio ?? Math.min(esp / 2, 6);
      const imgT = pele.imagens?.trilho ? urlArquivo(c, pele.imagens.trilho) : null;
      const imgC = pele.imagens?.cheia ? urlArquivo(c, pele.imagens.cheia) : null;
      const segs = pele.estilo === 'segmentos' ? limita(Math.round(pele.segmentos ?? 20), 4, 60) : 0;

      const raiz = el('div', { style: {
        position: 'absolute', inset: '0', display: 'flex', boxSizing: 'border-box', overflow: 'hidden',
        flexDirection: 'column', fontFamily: fonte(c), color: k.texto,
        padding: Math.round(fs * 0.5) + 'px ' + Math.round(fs * 0.6) + 'px', gap: Math.round(fs * 0.4) + 'px',
        justifyContent: vert ? 'flex-start' : 'center', alignItems: vert ? 'center' : 'stretch',
      } });
      const rot = el('div', { style: { fontSize: fs + 'px', color: k.texto_fino, whiteSpace: 'nowrap',
        overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: '100%', textTransform: 'uppercase',
        letterSpacing: '0.04em', textAlign: vert ? 'center' : 'left' } });
      const val = el('div', { style: { fontSize: fs * 1.15 + 'px', fontWeight: '600', whiteSpace: 'nowrap',
        fontVariantNumeric: 'tabular-nums', textAlign: vert ? 'center' : 'right' } });

      // trilho (não recorta: as marcas da faixa podem passar da borda)
      const trilho = el('div', { style: {
        position: 'relative', boxSizing: 'border-box', background: imgT ? 'transparent' : k.agua_trilho,
        borderRadius: raio + 'px', flex: vert ? '1 1 0' : '0 0 auto',
        width: vert ? esp + 'px' : '100%', height: vert ? 'auto' : esp + 'px', minHeight: vert ? '0' : null,
        backgroundImage: imgT ? `url("${imgT}")` : null, backgroundSize: '100% 100%',
      } });
      // camada do preenchimento: cheia recortada por clip-path (imagem) ou largura/altura (cor)
      const cheia = el('div', { style: { position: 'absolute', inset: '0', borderRadius: raio + 'px', overflow: 'hidden' } });
      const itens = [];
      if (segs) {
        const lado = ((vert ? h : w) - 2 * fs) / segs;
        const gap = Math.max(0, (pele.espaco ?? 0.2) * lado);
        cheia.style.display = 'flex';
        cheia.style.flexDirection = vert ? 'column-reverse' : 'row';
        cheia.style.gap = gap + 'px';
        trilho.style.display = 'block';
        for (let i = 0; i < segs; i++) {
          const s = el('div', { style: { flex: '1 1 0', borderRadius: Math.min(pele.raio ?? 2, 12) + 'px',
            background: 'transparent' } });
          itens.push(s); cheia.append(s);
        }
        // trilho em segmentos: segmentos vazios na cor do trilho
        trilho.style.background = 'transparent';
        const fundo = el('div', { style: { position: 'absolute', inset: '0', display: 'flex', gap: gap + 'px',
          flexDirection: vert ? 'column-reverse' : 'row' } });
        for (let i = 0; i < segs; i++) {
          fundo.append(el('div', { style: { flex: '1 1 0', borderRadius: Math.min(pele.raio ?? 2, 12) + 'px',
            background: imgT ? 'transparent' : k.agua_trilho } }));
        }
        trilho.append(fundo);
      }
      let preench = null;
      if (!segs) {
        preench = el('div', { style: { position: 'absolute', background: imgC ? 'transparent' : k.passou,
          backgroundImage: imgC ? `url("${imgC}")` : null, backgroundSize: '100% 100%',
          inset: '0', borderRadius: raio + 'px' } });
        cheia.append(preench);
      } else if (imgC) {
        cheia.style.backgroundImage = `url("${imgC}")`; cheia.style.backgroundSize = '100% 100%';
      }
      trilho.append(cheia);

      // marcas da faixa normal
      const marcas = [0, 1].map(() => el('div', { style: { position: 'absolute', display: 'none',
        background: k.texto_fino, ...(vert
          ? { left: '-3px', right: '-3px', height: '2px' } : { top: '-3px', bottom: '-3px', width: '2px' }) } }));
      trilho.append(...marcas);

      if (vert) raiz.append(rot, trilho, val); else {
        const topo = el('div', { style: { display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
          gap: fs + 'px' } }, rot, val);
        raiz.append(topo, trilho);
        rot.style.flex = '1 1 auto'; rot.style.minWidth = '0';
      }
      el0.append(raiz);
      r = { rot, val, cheia, preench, itens, marcas, vert, segs, imgC, k };
    }

    function atualizar_() {
      const [mn, mx] = limites();
      const v = c.valor?.numero, k = r.k;
      const f = v == null ? 0 : frac(v, mn, mx);
      const cor = c.avaliacao?.estado && c.avaliacao.estado !== 'neutro' ? corEstado(c) : k.passou;
      r.rot.textContent = c.titulo || '';
      const mostrar = c.opcoes?.mostrar_valor !== false;
      r.val.style.display = mostrar ? '' : 'none';
      r.val.textContent = v == null ? '—' : numBr(v, decimais(c)) + (c.metrica?.unidade ? ' ' + c.metrica.unidade : '');
      r.val.style.color = v == null ? k.texto_fino : k.texto;

      if (r.segs) {
        const acesos = v == null ? 0 : Math.round(f * r.segs);
        r.itens.forEach((s, i) => {
          s.style.background = i < acesos ? (r.imgC ? 'transparent' : cor) : 'transparent';
        });
        if (r.imgC) r.cheia.style.clipPath = r.vert ? `inset(${(1 - acesos / r.segs) * 100}% 0 0 0)` : `inset(0 ${(1 - acesos / r.segs) * 100}% 0 0)`;
      } else {
        const p = r.preench;
        if (!r.imgC) p.style.background = cor;
        r.cheia.style.clipPath = r.vert ? `inset(${(1 - f) * 100}% 0 0 0)` : `inset(0 ${(1 - f) * 100}% 0 0)`;
      }
      const fx = c.avaliacao?.faixa;
      r.marcas.forEach((m, i) => {
        if (!fx) { m.style.display = 'none'; return; }
        const pos = frac(fx[i], mn, mx) * 100;
        m.style.display = '';
        if (r.vert) m.style.bottom = `calc(${pos}% - 1px)`; else m.style.left = `calc(${pos}% - 1px)`;
      });
    }

    montar(); atualizar_();
    return {
      atualizar(novo) {
        c = novo;
        if (assinatura(c) !== sig) montar();
        atualizar_();
      },
      destruir() {},
    };
  },
};

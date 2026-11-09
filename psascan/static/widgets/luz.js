// Widget `luz`: ícone do motor que acende (métrica bit ou existir falha presente).
import { el, svgEl, urlArquivo, fonte } from './base.js';

const CAMINHO = 'M1.5 10.5H5.5L8.5 7.6H17.4L21 11.4H25V8.8H28A3.5 3.5 0 0 1 31.5 12.3V20.7A3.5 3.5 0 0 1 28 24.2H25V22.5H21V27.5H9.5L5.5 23.5H1.5Z M7.5 3.5H18.5 M13 3.5V7.6';

export default {
  tipo: 'luz',
  criar(raiz, c0) {
    let chave = null, nos = null, aceso = null;

    const montar = c => {
      const { largura: w, altura: h } = c.area;
      const pele = c.tema.peles?.luz || {};
      const k = c.tema.cores;
      const rot = c.opcoes.rotulo;
      const hRot = rot ? Math.max(8, Math.min(h * 0.18, w * 0.14)) : 0;
      const lado = Math.max(8, Math.min(Math.min(w, h - hRot * 1.4) * 0.5, 64));   // ícone discreto, como no painel
      raiz.replaceChildren();
      Object.assign(raiz.style, { display: 'flex', flexDirection: 'column', alignItems: 'center',
        justifyContent: 'center', gap: Math.round(hRot * 0.2) + 'px', overflow: 'hidden', width: '100%', height: '100%' });
      const imgs = pele.imagens || {};
      let icone, ap = null, ac = null, path = null;
      if (imgs.apagada || imgs.acesa) {
        const estilo = { position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'contain' };
        ap = el('img', { src: urlArquivo(c, imgs.apagada || imgs.acesa), alt: '', style: { ...estilo,
          opacity: imgs.apagada ? 1 : 0.25 } });
        ac = el('img', { src: urlArquivo(c, imgs.acesa || imgs.apagada), alt: '', style: { ...estilo, opacity: 0 } });
        icone = el('div', { style: { position: 'relative', width: lado + 'px', height: lado + 'px' } }, ap, ac);
      } else {
        const svg = svgEl('svg', { viewBox: '0 0 33 30', width: lado, height: lado });
        path = svgEl('path', { d: CAMINHO, fill: 'none', 'stroke-width': 2.2, 'stroke-linejoin': 'round',
          'stroke-linecap': 'round', stroke: k.luz_apagada }, svg);
        svg.style.overflow = 'visible';
        icone = svg;
      }
      const leg = el('div', { style: { fontFamily: fonte(c), color: k.texto_apagado, textTransform: 'uppercase',
        letterSpacing: '.06em', fontSize: hRot + 'px', lineHeight: 1.1, whiteSpace: 'nowrap', overflow: 'hidden',
        textOverflow: 'ellipsis', maxWidth: '100%', display: rot ? '' : 'none' } });
      leg.textContent = rot || '';
      raiz.append(icone, leg);
      nos = { icone, ap, ac, path, imgs, brilho: pele.brilho ?? 0.8, lado };
      aceso = null;
    };

    const pintar = c => {
      const a = c.opcoes.fonte === 'falhas'
        ? !!(c.falhas && c.falhas.presentes && c.falhas.presentes.length)
        : (c.valor != null && Number(c.valor.numero) === 1);
      if (a === aceso) return;
      aceso = a;
      const k = c.tema.cores;
      const sombra = a && nos.brilho > 0 ? `drop-shadow(0 0 ${Math.round(nos.lado * 0.12 * nos.brilho)}px ${k.ambar})` : 'none';
      if (nos.path) {
        nos.path.setAttribute('stroke', a ? k.ambar : k.luz_apagada);
        nos.icone.style.filter = sombra;
      } else {
        nos.ap.style.opacity = a ? (nos.imgs.apagada && nos.imgs.acesa ? 0 : 0.25) : 1;
        nos.ac.style.opacity = a ? 1 : 0;
        nos.icone.style.filter = a && !nos.imgs.acesa ? sombra : 'none';
      }
    };

    const atualizar = c => {
      const ch = [c.area.largura, c.area.altura, c.tema, c.opcoes, c.temaId];
      if (!chave || ch.some((v, i) => v !== chave[i])) { chave = ch; montar(c); }
      pintar(c);
    };
    atualizar(c0);
    return { atualizar, destruir() { nos = null; } };
  },
};

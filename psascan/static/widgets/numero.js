// Widget `numero`: rótulo em cima, número grande, unidade e cor pela faixa (docs/LAYOUT.md).
import { el, numBr, decimais, corEstado, fonte } from './base.js';

export default {
  tipo: 'numero',
  criar(raiz, c0) {
    let chave = null, nos = null, ultimo = null;

    const montar = c => {
      const { largura: w, altura: h } = c.area;
      const pele = c.tema.peles?.numero || {};
      const k = c.tema.cores;
      const ff = fonte(c, pele.fonte);
      raiz.replaceChildren();
      const tit = el('div', { style: {
        fontFamily: fonte(c), color: k.texto_apagado, textTransform: 'uppercase', letterSpacing: '.06em',
        fontSize: Math.max(8, Math.min(h * 0.17, w * 0.13)) + 'px', lineHeight: 1.1,
        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: '100%', flex: '0 0 auto',
      } });
      const apag = pele.apagados ? el('div', { style: {
        gridArea: '1 / 1', justifySelf: 'end', opacity: 0.12, whiteSpace: 'nowrap', color: k.texto,
      } }) : null;
      const val = el('div', { style: { gridArea: '1 / 1', justifySelf: 'end', whiteSpace: 'nowrap' } });
      const un = el('div', { style: { color: k.texto_medio, whiteSpace: 'nowrap', lineHeight: 1, flex: '0 0 auto' } });
      const grade = el('div', { style: { display: 'grid', alignItems: 'center', minWidth: 0 } }, apag, val);
      const linha = el('div', { style: {
        display: 'flex', alignItems: 'baseline', justifyContent: 'center', gap: '0.25em', fontFamily: ff,
        flex: '1 1 auto', minHeight: 0, maxWidth: '100%', overflow: 'hidden',
      } }, grade, un);
      Object.assign(raiz.style, { display: 'flex', flexDirection: 'column', alignItems: 'center',
        justifyContent: 'center', overflow: 'hidden', width: '100%', height: '100%' });
      raiz.append(tit, linha);
      nos = { tit, val, apag, un, linha, grade, pele };
      ultimo = null;
    };

    const pintar = c => {
      const { largura: w, altura: h } = c.area;
      const { tit, val, apag, un, linha, pele } = nos;
      const k = c.tema.cores;
      const m = c.metrica;
      const discreta = m && m.tipo === 'bit' || m?.tipo_perfil === 'enum';
      const dec = decimais(c);
      let texto = '—';
      if (c.valor != null) texto = discreta ? String(c.valor.texto ?? '—') : numBr(c.valor.numero, dec);
      const unidade = !discreta && c.opcoes.mostrar_unidade && m?.unidade && c.valor != null ? m.unidade : '';
      const est = c.opcoes.mostrar_estado && c.valor != null && !discreta;
      const cor = est ? corEstado(c) : k.texto;
      const molde = (!discreta && pele.apagados && c.valor != null)
        ? numBr(m?.max ?? c.valor.numero, dec).replace(/\d/g, '8') : null;
      const sig = [texto, unidade, cor, molde, c.titulo].join('\u0001');
      if (sig === ultimo) return;
      ultimo = sig;

      tit.textContent = c.titulo || '';
      tit.style.display = c.titulo ? '' : 'none';
      const dispH = h - (c.titulo ? Math.max(8, Math.min(h * 0.17, w * 0.13)) * 1.3 : 0);
      const n = Math.max(texto.length, molde ? molde.length : 0, 1);
      const larg = w * 0.94 - (unidade ? Math.min(w * 0.3, unidade.length * dispH * 0.2) : 0);
      const fs = Math.max(8, Math.min(dispH * 0.78, larg / (n * (discreta ? 0.5 : 0.55))));
      val.textContent = texto;
      val.style.fontSize = fs + 'px';
      val.style.lineHeight = 1;
      val.style.color = cor;
      val.style.textShadow = pele.brilho > 0 ? `0 0 ${Math.round(fs * 0.3 * pele.brilho)}px currentColor` : '';
      if (apag) {
        apag.textContent = molde || '';
        apag.style.fontSize = fs + 'px';
        apag.style.lineHeight = 1;
        apag.style.display = molde ? '' : 'none';
      }
      un.textContent = unidade;
      un.style.fontSize = Math.max(8, fs * 0.38) + 'px';
      un.style.display = unidade ? '' : 'none';
      linha.style.color = cor;
    };

    const atualizar = c => {
      const ch = [c.area.largura, c.area.altura, c.tema, c.opcoes, c.temaId, c.metrica?.id];
      if (!chave || ch.some((v, i) => v !== chave[i])) { chave = ch; montar(c); }
      pintar(c);
    };
    atualizar(c0);
    return { atualizar, destruir() { nos = null; } };
  },
};

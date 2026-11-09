// Widget `estado`: rótulo + estado em texto grande (NORMAL/ALTO/BAIXO/ATENÇÃO ou o texto da métrica discreta).
import { el, corEstado, rotuloEstado, fonte } from './base.js';

export default {
  tipo: 'estado',
  criar(raiz, c0) {
    let chave = null, nos = null, ultimo = null;

    const montar = c => {
      const { largura: w, altura: h } = c.area;
      const pele = c.tema.peles?.estado || {};
      const k = c.tema.cores;
      raiz.replaceChildren();
      const ff = fonte(c, pele.fonte);
      const corte = { whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: '100%' };
      const tit = el('div', { style: { ...corte, color: k.texto_apagado, textTransform: 'uppercase',
        letterSpacing: '.06em', lineHeight: 1.1, fontSize: Math.max(8, Math.min(h * 0.17, w * 0.12)) + 'px' } });
      const grande = el('div', { style: { ...corte, fontWeight: 700, lineHeight: 1.05 } });
      const msg = el('div', { style: { ...corte, color: k.texto_medio, lineHeight: 1.15 } });
      const caixa = el('div', { style: {
        display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
        maxWidth: '100%', minWidth: 0, boxSizing: 'border-box',
        ...(pele.caixa ? { border: `${Math.max(1, Math.round(h * 0.025))}px solid currentColor`,
          borderRadius: (c.tema.raios?.rotulo ?? 6) + 'px', padding: `${h * 0.04}px ${w * 0.05}px` } : {}),
      } }, grande, msg);
      Object.assign(raiz.style, { display: 'flex', flexDirection: 'column', alignItems: 'center',
        justifyContent: 'center', gap: Math.round(h * 0.04) + 'px', overflow: 'hidden', width: '100%',
        height: '100%', fontFamily: ff });
      raiz.append(tit, caixa);
      nos = { tit, grande, msg, caixa };
      ultimo = null;
    };

    const pintar = c => {
      const { largura: w, altura: h } = c.area;
      const { tit, grande, msg, caixa } = nos;
      const k = c.tema.cores;
      const m = c.metrica;
      const discreta = m && (m.tipo === 'bit' || m.tipo_perfil === 'enum');
      const av = c.avaliacao;
      let t = '—', sub = '', cor = k.texto;
      if (c.valor != null) {
        if (discreta) { t = String(c.valor.texto ?? '—').toUpperCase(); }
        else {
          const r = rotuloEstado(av);
          if (r) { t = r; cor = corEstado(c); sub = av?.msg || ''; }
          else if (av?.estado === 'neutro' || !av) {
            t = 'SEM FAIXA AGORA'; cor = k.texto_apagado; sub = av?.condicao || '';
          }
          if (m?.id === 'lambda' || (av && av.msg && !r && av.estado !== 'neutro')) sub = av?.msg || sub;
        }
      }
      const sig = [t, sub, cor, c.titulo].join('\u0001');
      if (sig === ultimo) return;
      ultimo = sig;
      tit.textContent = c.titulo || '';
      tit.style.display = c.titulo ? '' : 'none';
      const disp = h - (c.titulo ? h * 0.22 : 0);
      const semFaixa = t === 'SEM FAIXA AGORA';
      const fs = Math.max(8, Math.min(disp * (sub ? 0.42 : 0.6), (w * 0.9) / (Math.max(t.length, 3) * 0.58),
        semFaixa ? disp * 0.28 : 1e9));
      grande.textContent = t;
      grande.style.fontSize = fs + 'px';
      caixa.style.color = cor;
      grande.style.color = cor;
      msg.textContent = sub;
      msg.style.display = sub ? '' : 'none';
      msg.style.fontSize = Math.max(8, Math.min(fs * 0.45, disp * 0.2, (w * 0.92) / Math.max(sub.length * 0.4, 6))) + 'px';
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

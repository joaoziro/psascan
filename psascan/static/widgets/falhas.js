// Widget `falhas`: contagem de presentes/memorizadas da última leitura e lista de códigos.
import { el, fonte } from './base.js';

const hex = n => (typeof n === 'number' ? n.toString(16).toUpperCase().padStart(3, '0') : String(n ?? '').toUpperCase());
const hora = h => {
  if (h == null) return '';
  const d = typeof h === 'number' ? new Date(h > 1e12 ? h : h * 1000) : new Date(h);
  if (Number.isNaN(d.getTime())) return String(h);
  return d.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
};

export default {
  tipo: 'falhas',
  criar(raiz, c0) {
    let chave = null, nos = null, ultimo = null;

    const montar = c => {
      const { largura: w, altura: h } = c.area;
      const pele = c.tema.peles?.falhas || {};
      const k = c.tema.cores;
      const ff = fonte(c, pele.fonte);
      raiz.replaceChildren();
      const compacto = h < 90;
      const fsN = Math.max(10, Math.min(compacto ? h * 0.5 : h * 0.26, w * 0.12));
      const fsP = Math.max(8, Math.min(fsN * 0.38, w * 0.05));
      const corte = { whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', minWidth: 0 };
      const num = (cor) => el('span', { style: { color: cor, fontSize: fsN + 'px', fontWeight: 700, lineHeight: 1 } });
      const rot = t => el('span', { style: { color: k.texto_apagado, fontSize: fsP + 'px', textTransform: 'uppercase',
        letterSpacing: '.05em', marginLeft: '0.3em', marginRight: '0.9em' } }, t);
      const nP = num(k.alerta), nM = num(k.ambar);
      const hr = el('span', { style: { color: k.texto_apagado, fontSize: fsP + 'px', marginLeft: 'auto', ...corte } });
      const cab = el('div', { style: { display: 'flex', alignItems: 'baseline', maxWidth: '100%', flex: '0 0 auto' } },
        nP, rot('presentes'), nM, rot('memorizadas'), hr);
      const vazio = el('div', { style: { color: k.texto_apagado, fontSize: Math.max(9, fsP * 1.3) + 'px', ...corte } });
      const lista = el('div', { style: { display: 'flex', flexDirection: 'column', overflow: 'hidden', minHeight: 0,
        flex: '1 1 auto', width: '100%' } });
      Object.assign(raiz.style, { display: 'flex', flexDirection: 'column', justifyContent: compacto ? 'center' : 'flex-start',
        gap: Math.round(h * 0.04) + 'px', overflow: 'hidden', width: '100%', height: '100%', fontFamily: ff,
        padding: `${Math.round(h * 0.04)}px ${Math.round(w * 0.03)}px`, boxSizing: 'border-box' });
      raiz.append(cab, vazio, lista);
      const fsL = Math.max(9, Math.min(fsP * 1.5, 16));
      nos = { cab, nP, nM, hr, vazio, lista, fsL, compacto, h, k };
      ultimo = null;
    };

    const pintar = c => {
      const f = c.falhas;
      const { cab, nP, nM, hr, vazio, lista, fsL, compacto, h, k } = nos;
      const sig = JSON.stringify(f ? [f.presentes, f.memorizadas, f.hora] : null) + c.opcoes.mostrar_codigos;
      if (sig === ultimo) return;
      ultimo = sig;
      lista.replaceChildren();
      if (!f) {
        cab.style.display = 'none';
        vazio.style.display = '';
        vazio.textContent = 'falhas ainda não lidas';
        return;
      }
      cab.style.display = '';
      nP.textContent = f.presentes.length;
      nM.textContent = f.memorizadas.length;
      hr.textContent = hora(f.hora);
      const itens = [...f.presentes.map(x => [x, k.alerta]), ...f.memorizadas.map(x => [x, k.ambar])];
      if (!c.opcoes.mostrar_codigos || compacto) { vazio.style.display = 'none'; return; }
      if (!itens.length) { vazio.style.display = ''; vazio.textContent = 'nenhuma falha'; return; }
      vazio.style.display = 'none';
      const dispo = h - h * 0.08 - h * 0.34;
      const linhaH = fsL * 1.35;
      let cabem = Math.max(1, Math.floor(dispo / linhaH));
      if (itens.length > cabem) cabem = Math.max(1, cabem - 1);
      for (const [x, cor] of itens.slice(0, cabem)) {
        lista.append(el('div', { style: { display: 'flex', gap: '0.6em', fontSize: fsL + 'px', lineHeight: 1.35,
          whiteSpace: 'nowrap', overflow: 'hidden' } },
          el('span', { style: { color: cor, fontWeight: 700, flex: '0 0 auto' } }, hex(x.codigo)),
          el('span', { style: { color: k.texto_medio, overflow: 'hidden', textOverflow: 'ellipsis', minWidth: 0 } }, x.nome || '')));
      }
      if (itens.length > cabem) {
        lista.append(el('div', { style: { color: k.texto_apagado, fontSize: fsL + 'px', lineHeight: 1.35 } }, `+${itens.length - cabem}`));
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

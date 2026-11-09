// Widget numero_historico: o cartão da Diagnóstico (cabeçalho com estado, número grande,
// linha de referência e gráfico dos últimos segundos com a faixa normal). Contrato: docs/LAYOUT.md.
import { el, numBr, decimais, corEstado, rotuloEstado, fonte } from './base.js';

const limita = (x, a, b) => Math.min(b, Math.max(a, x));

/** Assinatura do que obriga a recriar o DOM: área, tema (cores/fontes/peles) e opções. */
function assinatura(c) {
  return JSON.stringify([c.area, c.tema, c.opcoes, c.temaId]);
}

/** Cor com opacidade, sem inventar cor: desenha com a cor do tema e alfa no contexto. */
function comAlfa(ctx, alfa, fn) {
  const a = ctx.globalAlpha;
  ctx.globalAlpha = a * alfa;
  fn();
  ctx.globalAlpha = a;
}

/** Curva suave (pontos médios) pelos pontos px [[x, y], ...]. */
function caminho(ctx, p) {
  ctx.beginPath();
  if (!p.length) return;
  ctx.moveTo(p[0][0], p[0][1]);
  if (p.length === 1) { ctx.lineTo(p[0][0] + 0.01, p[0][1]); return; }
  for (let i = 1; i < p.length - 1; i++) {
    const mx = (p[i][0] + p[i + 1][0]) / 2, my = (p[i][1] + p[i + 1][1]) / 2;
    ctx.quadraticCurveTo(p[i][0], p[i][1], mx, my);
  }
  const u = p[p.length - 1];
  ctx.lineTo(u[0], u[1]);
}

export default {
  tipo: 'numero_historico',
  criar(el0, c0) {
    let c = c0, sig = null, quadro = 0, vivo = true;
    let refs = null, cv = null;

    function montar() {
      sig = assinatura(c);
      el0.textContent = '';
      const k = c.tema.cores, w = c.area.largura, h = c.area.altura;
      const fs = limita(h * 0.075, 10, 17);
      const grande = limita(Math.min(h * 0.24, w * 0.22), 18, 72);
      const ff = fonte(c);
      const raiz = el('div', { style: {
        position: 'absolute', inset: '0', display: 'flex', flexDirection: 'column',
        padding: Math.round(fs * 0.5) + 'px ' + Math.round(fs * 0.7) + 'px', boxSizing: 'border-box',
        overflow: 'hidden', fontFamily: ff, color: k.texto, gap: Math.round(fs * 0.2) + 'px',
      } });
      const cab = el('div', { style: { alignSelf: 'flex-start', fontSize: fs + 'px', fontWeight: '600',
        letterSpacing: '0.04em', textTransform: 'uppercase', whiteSpace: 'nowrap', overflow: 'hidden',
        textOverflow: 'ellipsis', maxWidth: '100%', boxSizing: 'border-box',
        padding: `${Math.round(fs * 0.1)}px ${Math.round(fs * 0.5)}px`,
        borderRadius: (c.tema.raios?.rotulo ?? 4) + 'px', border: '1px solid transparent' } });
      const linhaNum = el('div', { style: { display: 'flex', alignItems: 'baseline', gap: Math.round(fs * 0.4) + 'px',
        lineHeight: '1', whiteSpace: 'nowrap' } });
      const num = el('span', { style: { fontSize: grande + 'px', fontWeight: '600', fontVariantNumeric: 'tabular-nums' } });
      const un = el('span', { style: { fontSize: fs * 1.15 + 'px', color: k.texto_fino } });
      linhaNum.append(num, un);
      const ref = el('div', { style: { fontSize: fs * 0.92 + 'px', color: k.texto_fino, whiteSpace: 'nowrap',
        overflow: 'hidden', textOverflow: 'ellipsis', minHeight: fs * 1.1 + 'px' } });
      const caixa = el('div', { style: { position: 'relative', flex: '1 1 0', minHeight: '0' } });
      cv = el('canvas', { style: { position: 'absolute', inset: '0', width: '100%', height: '100%', display: 'block' } });
      caixa.append(cv);
      raiz.append(cab, linhaNum, ref, caixa);
      el0.append(raiz);
      refs = { cab, num, un, ref, fs };
    }

    function textos() {
      const k = c.tema.cores, av = c.avaliacao, r = refs;
      const rot = rotuloEstado(av), cor = corEstado(c);
      r.cab.textContent = c.titulo + (rot ? ': ' + rot : '');
      r.cab.style.color = rot ? cor : k.texto_fino;
      r.cab.style.borderColor = rot ? cor : 'transparent';
      r.num.textContent = c.valor?.numero == null ? '—' : numBr(c.valor.numero, decimais(c));
      r.num.style.color = av && av.estado !== 'neutro' ? cor : k.texto;
      const u = c.metrica?.unidade || '';
      r.un.textContent = u;
      let t = '';
      if (av && (av.estado === 'atencao' || av.estado === 'fora') && av.msg) t = av.msg;
      else if (av?.faixa) {
        const d = decimais(c), f = av.faixa;
        t = 'normal ' + (av.condicao ? av.condicao + ' ' : '') + numBr(f[0], d) + '–' + numBr(f[1], d) + (u ? ' ' + u : '');
      } else if (av?.msg) t = av.msg;
      r.ref.textContent = t;
    }

    function agendar() {
      if (!quadro && vivo) quadro = requestAnimationFrame(() => { quadro = 0; if (vivo) desenhar(); });
    }

    function desenhar() {
      const k = c.tema.cores, pele = c.tema.peles?.numero_historico || {};
      const caixa = cv.parentElement.getBoundingClientRect();
      const W = Math.max(1, Math.round(caixa.width)), H = Math.max(1, Math.round(caixa.height));
      const dpr = window.devicePixelRatio || 1;
      if (cv.width !== Math.round(W * dpr) || cv.height !== Math.round(H * dpr)) {
        cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr);
      }
      const ctx = cv.getContext('2d');
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, W, H);
      if (W < 12 || H < 12) return;

      const fs = refs.fs, ff = fonte(c);
      const seg = c.opcoes?.segundos ?? 60;
      const hist = c.historico || { t: [], v: [] };
      const nTot = Math.min(hist.t.length, hist.v.length);
      const tAgora = nTot ? hist.t[nTot - 1] : 0, t0 = tAgora - seg;
      const pts = [];
      for (let i = 0; i < nTot; i++) if (hist.t[i] >= t0 && hist.v[i] != null) pts.push([hist.t[i], hist.v[i]]);

      const faixa = c.opcoes?.mostrar_faixa !== false ? c.avaliacao?.faixa : null;
      // Escala Y automática com margem, incluindo a faixa
      let lo = Infinity, hi = -Infinity;
      for (const p of pts) { lo = Math.min(lo, p[1]); hi = Math.max(hi, p[1]); }
      if (faixa) { lo = Math.min(lo, faixa[0]); hi = Math.max(hi, faixa[1]); }
      if (!isFinite(lo)) { lo = 0; hi = 1; }
      if (hi - lo < 1e-9) { lo -= 0.5; hi += 0.5; }
      const mg = (hi - lo) * 0.12; lo -= mg; hi += mg;

      const baixo = Math.round(fs * 1.5);           // espaço do eixo de tempo
      const dir = 2, esq = 0, topo = 2;
      const gx = esq, gw = W - esq - dir, gy = topo, gh = H - baixo - topo;
      if (gh < 8) return;
      const X = t => gx + (t - t0) / seg * gw;
      const Y = v => gy + (1 - (v - lo) / (hi - lo)) * gh;

      // grade horizontal fina
      ctx.lineWidth = 1; ctx.strokeStyle = k.grafico_grade;
      for (let i = 0; i <= 3; i++) {
        const y = Math.round(gy + gh * i / 3) + 0.5;
        ctx.beginPath(); ctx.moveTo(gx, y); ctx.lineTo(gx + gw, y); ctx.stroke();
      }
      // faixa normal como banda suave
      if (faixa) {
        const ya = Y(faixa[1]), yb = Y(faixa[0]);
        ctx.fillStyle = k.ok;
        comAlfa(ctx, 0.12, () => ctx.fillRect(gx, ya, gw, yb - ya));
        ctx.strokeStyle = k.grafico_limite; ctx.lineWidth = 1;
        ctx.setLineDash([3, 3]);
        for (const y of [ya, yb]) { ctx.beginPath(); ctx.moveTo(gx, y); ctx.lineTo(gx + gw, y); ctx.stroke(); }
        ctx.setLineDash([]);
      }
      // eixo de tempo: marquinhas
      const passo = seg <= 30 ? 5 : seg <= 90 ? 10 : seg <= 200 ? 30 : 60;
      ctx.strokeStyle = k.texto_fino; ctx.fillStyle = k.texto_fino; ctx.lineWidth = 1;
      ctx.font = `${Math.round(fs * 0.8)}px ${ff}`; ctx.textBaseline = 'top';
      const yEixo = gy + gh + 1;
      ctx.beginPath(); ctx.moveTo(gx, yEixo + 0.5); ctx.lineTo(gx + gw, yEixo + 0.5); ctx.stroke();
      for (let s = 0; s <= seg; s += passo) {
        const x = Math.round(gx + gw - s / seg * gw) + 0.5;
        const grande = s % (passo * 2) === 0;
        ctx.beginPath(); ctx.moveTo(x, yEixo); ctx.lineTo(x, yEixo + (grande ? 5 : 3)); ctx.stroke();
      }
      ctx.textAlign = 'left'; ctx.fillText('−' + seg + ' s', gx, yEixo + 5);
      ctx.textAlign = 'right'; ctx.fillText('agora', gx + gw, yEixo + 5);

      // linha do "agora" à direita
      ctx.strokeStyle = k.grafico_limite; ctx.lineWidth = 1;
      const xa = Math.round(gx + gw) - 0.5;
      ctx.beginPath(); ctx.moveTo(xa, gy); ctx.lineTo(xa, gy + gh); ctx.stroke();

      if (!pts.length) return;
      const px = pts.map(p => [X(p[0]), Y(p[1])]);
      const esp = pele.linha ?? 1.6;
      ctx.lineJoin = 'round'; ctx.lineCap = 'round';

      if (pele.preencher === true && px.length > 1) {
        caminho(ctx, px);
        ctx.lineTo(px[px.length - 1][0], gy + gh); ctx.lineTo(px[0][0], gy + gh); ctx.closePath();
        ctx.fillStyle = k.texto;
        comAlfa(ctx, 0.15, () => ctx.fill());
      }
      // curva clara
      ctx.lineWidth = esp; ctx.strokeStyle = k.texto;
      comAlfa(ctx, 0.9, () => { caminho(ctx, px); ctx.stroke(); });
      // trecho fora da faixa em âmbar (recorte acima e abaixo da faixa)
      if (faixa) {
        const ya = Y(faixa[1]), yb = Y(faixa[0]);
        ctx.strokeStyle = k.ambar;
        for (const [r0, r1] of [[0, ya], [yb, H]]) {
          ctx.save();
          ctx.beginPath(); ctx.rect(0, r0, W, Math.max(0, r1 - r0)); ctx.clip();
          caminho(ctx, px); ctx.stroke();
          ctx.restore();
        }
      }
      // ponto do agora
      const u = px[px.length - 1];
      ctx.fillStyle = corEstado(c);
      ctx.beginPath(); ctx.arc(u[0], u[1], esp * 1.6, 0, Math.PI * 2); ctx.fill();
    }

    montar(); textos(); agendar();

    return {
      atualizar(novo) {
        c = novo;
        if (assinatura(c) !== sig) montar();
        textos(); agendar();
      },
      destruir() { vivo = false; if (quadro) cancelAnimationFrame(quadro); },
    };
  },
};

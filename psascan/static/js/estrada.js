// Modo estrada: tela preta de alto contraste, 4 números grandes, nada pequeno para clicar.
// A metade de baixo inteira marca evento (também espaço/Enter); sair = segurar 2 s.
import { S, on, texto, ord } from './app.js';
import { h, por, pt, duracao } from './util.js';
import { marcarEvento } from './gravar.js';

const NUMS = [['rpm', 'RPM'], ['agua', 'Água'], ['lambda', 'Lambda'], ['bateria', 'Bateria']]
  .map(([id, n]) => [ord(id), n]).filter(([o]) => o !== undefined);

export function abrirEstrada() {
  if (document.querySelector('.estrada')) return;
  let trava = null, ultimoEv = 0, n = 0, segurar = null;
  const barra = h('div.barra'), nums = h('div.nums'), toque = h('button.toque', { type: 'button' });
  const sair = h('span.sair', 'segure para sair', h('i'));
  const raiz = h('div.estrada', { role: 'dialog', 'aria-label': 'Modo estrada' }, barra, nums, toque);
  const cel = {};
  for (const [o, k] of NUMS) {
    cel[o] = { k: h('div.k', k), v: h('div.v'), d: h('div.d'), el: null };
    cel[o].el = h('div.n', cel[o].k, cel[o].v, cel[o].d);
    nums.append(cel[o].el);
  }
  function desenhar() {
    const e = S.estado, it = S.ultimo;
    const velho = !it || Date.now() / 1000 - it.hora > 5;
    por(barra, h('span', e.conexao === 'conectado' ? '● conectado' : '◌ ' + (e.conexao || '—')),
      e.rec ? h('span.rec', '● REC ' + duracao(e.rec.s)) : h('span', 'sem gravar'), sair);
    for (const [o] of NUMS) {
      const c = cel[o], v = it && !velho ? it.v[o] : null;
      const [est, msg] = (it && !velho && it.est[o]) || ['neutro', ''];
      const t = texto(o, v);
      c.el.className = 'n ' + est;
      por(c.v, t.v, t.un ? h('small', t.un) : null);
      por(c.k, NUMS.find(x => x[0] === o)[1], est !== 'neutro' ? h('span.est.' + est, { normal: 'normal', atencao: 'atenção', fora: 'fora' }[est]) : null);
      c.d.textContent = o === S.lambda ? (pt(it && it.lambda && it.lambda.diagnostico) || '') : (est !== 'normal' ? msg : '');
    }
    por(toque, n ? 'MARCAR EVENTO' : 'TOQUE PARA MARCAR EVENTO',
      h('small', velho ? 'sem leitura — comece a ler antes de sair' : n ? n + ' evento(s) marcado(s) · espaço/Enter também marcam' : 'espaço ou Enter também marcam'));
  }
  async function evento() {
    if (Date.now() - ultimoEv < 1500) return;      // evita duplicar com toque duplo
    ultimoEv = Date.now();
    toque.classList.add('flash');
    setTimeout(() => toque.classList.remove('flash'), 350);
    if (navigator.vibrate) navigator.vibrate(60);
    if (await marcarEvento('evento (modo estrada)')) { n++; desenhar(); }
  }
  async function acordado() {
    try { if ('wakeLock' in navigator && document.visibilityState === 'visible') trava = await navigator.wakeLock.request('screen'); } catch (e) {}
  }
  const vis = () => { if (document.visibilityState === 'visible') acordado(); };
  const tecla = e => {
    if (e.key === 'Escape') fechar();
    else if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); evento(); }
  };
  toque.addEventListener('pointerdown', e => { e.preventDefault(); evento(); });
  sair.addEventListener('pointerdown', e => {
    e.stopPropagation();
    const barraP = sair.querySelector('i'), t0 = Date.now();
    segurar = setInterval(() => {
      const f = Math.min(1, (Date.now() - t0) / 2000);
      barraP.style.width = f * 100 + '%';
      if (f >= 1) fechar();
    }, 50);
  });
  const soltar = () => { clearInterval(segurar); const p = sair.querySelector('i'); if (p) p.style.width = '0'; };
  sair.addEventListener('pointerup', soltar);
  sair.addEventListener('pointerleave', soltar);
  const desl = [on('leitura', desenhar), on('estado', desenhar)];
  function fechar() {
    clearInterval(segurar);
    desl.forEach(f => f());
    document.removeEventListener('keydown', tecla);
    document.removeEventListener('visibilitychange', vis);
    if (trava) trava.release().catch(() => {});
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    raiz.remove();
  }
  document.addEventListener('keydown', tecla);
  document.addEventListener('visibilitychange', vis);
  document.body.append(raiz);
  if (raiz.requestFullscreen) raiz.requestFullscreen().catch(() => {});
  acordado();
  desenhar();
  toque.focus();
}

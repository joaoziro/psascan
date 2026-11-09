// Gravar viagem: REC, nome, cronômetro, "marcar evento" e modo estrada.
import { S, on, api, toast, iniciarLeitura } from './app.js';
import { $, h, por, duracao, ler, guardar } from './util.js';
import { abrirEstrada } from './estrada.js';

const ATALHOS_PADRAO = ['tec tec agora', 'engasgou', 'falhou na retomada', 'marcha lenta oscilando', 'cheiro de gasolina', 'luz acendeu'];
let raiz, desligar = [];

export async function marcarEvento(texto) {
  try {
    const r = await api('/api/evento', { texto });
    toast('Evento "' + r.evento.texto + '" marcado em ' + duracao(r.evento.s) +
      (r.rec_automatico ? ' — gravação iniciada sozinha, com os 60 s anteriores' : ''));
    return r;
  } catch (e) { toast(e.message, true); return null; }
}

function atualizar() {
  const e = S.estado, rec = e.rec;
  const b = $('#rec', raiz);
  b.classList.toggle('gravando', !!rec);
  b.setAttribute('aria-label', rec ? 'Parar gravação' : 'Começar a gravar');
  $('#crono', raiz).textContent = rec ? duracao(rec.s) : '0:00';
  $('#rec-txt', raiz).textContent = rec ? 'Gravando' : e.rodando ? 'Pronto para gravar' : 'Leitura parada';
  por($('#rec-info', raiz), rec ? [h('div', rec.leituras + ' leituras · ' + rec.quedas + ' queda(s) do Bluetooth'),
    h('div.mut', 'sessão ' + rec.id)] : h('div.mut', 'Sem gravação. "Marcar evento" começa a gravar sozinho, incluindo o último minuto.'));
  const nome = $('#nome', raiz);
  if (document.activeElement !== nome && rec) nome.value = rec.nome || '';
  por($('#evs', raiz), ...(rec ? rec.eventos.slice().reverse().map(ev => h('li', h('span.num', duracao(ev.s)), h('span', ev.texto), h('span.mut.esp', { style: { textAlign: 'right' } }, ev.hora))) : []));
  $('#evs-vazio', raiz).classList.toggle('oculto', !!(rec && rec.eventos.length));
  $('#sem', raiz).classList.toggle('oculto', !!e.rodando);
}

function atalhos() {
  const lst = ler('atalhos', ATALHOS_PADRAO);
  por($('#atalhos', raiz), ...lst.map(t => h('button', { onclick: () => marcarEvento(t) }, t)));
}

export default {
  async montar(el) {
    raiz = el;
    el.append(
      h('div.aviso#sem', h('span', h('b', 'A leitura está parada. '), 'Para gravar, comece a ler.'),
        h('div.esp'), h('button.btn.pri.peq', { onclick: () => iniciarLeitura() }, 'Ler agora')),
      h('div.gravar',
        h('div.cartao.rec-bloco',
          h('button.rec-botao#rec', { type: 'button', onclick: async () => {
            try {
              if (S.estado.rec) { const r = await api('/api/parar-rec', {}); toast('Sessão salva: ' + (r.sessao.nome || r.sessao.id)); }
              else await api('/api/rec', { nome: $('#nome', raiz).value });
            } catch (e) { toast(e.message, true); }
          } }, h('i')),
          h('div.cronometro#crono', '0:00'),
          h('h3#rec-txt'),
          h('input#nome', { type: 'text', placeholder: 'Nome da sessão (ex. "antes de trocar a sonda")', style: { width: '100%' },
            onchange: async e => { if (S.estado.rec) { try { await api('/api/sessao', { id: S.estado.rec.id, nome: e.target.value }); } catch (x) { toast(x.message, true); } } } }),
          h('div#rec-info', { style: { fontSize: '14px' } }),
          h('button.btn', { style: { width: '100%', minHeight: '56px' }, onclick: () => abrirEstrada() }, 'Modo estrada')),
        h('div', { style: { display: 'flex', flexDirection: 'column', gap: '14px' } },
          h('button.evento-botao', { type: 'button', onclick: () => marcarEvento('evento') }, 'MARCAR EVENTO'),
          h('div.cartao', h('h3', 'Atalhos'), h('div.atalhos#atalhos'),
            h('div.linha', { style: { marginTop: '12px' } },
              h('input#livre', { type: 'text', placeholder: 'O que aconteceu?', style: { flex: '1' },
                onkeydown: e => { if (e.key === 'Enter' && e.target.value.trim()) { marcarEvento(e.target.value.trim()); e.target.value = ''; } } }),
              h('button.btn', { onclick: () => { const i = $('#livre', raiz); if (i.value.trim()) { marcarEvento(i.value.trim()); i.value = ''; } } }, 'Marcar'),
              h('button.btn.fantasma', { title: 'Guardar como atalho', onclick: () => { const i = $('#livre', raiz); const t = i.value.trim(); if (!t) return; guardar('atalhos', [...new Set([...ler('atalhos', ATALHOS_PADRAO), t])]); atalhos(); i.value = ''; } }, '+ atalho'))),
          h('div.cartao', h('h3', 'Eventos desta sessão'), h('p.mut#evs-vazio', 'Nenhum ainda.'), h('ul.lista-ev#evs')))));
    atalhos();
    atualizar();
    desligar = [on('estado', atualizar)];
  },
  desmontar() { desligar.forEach(f => f()); },
};

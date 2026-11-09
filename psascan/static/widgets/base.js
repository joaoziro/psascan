// Interface comum dos widgets da tela personalizável (contrato: docs/LAYOUT.md).
//
// Cada tipo é um módulo em static/widgets/<tipo>.js que exporta por padrão:
//
//   export default {
//     tipo: 'numero',                       // igual à chave em catalogo.json
//     criar(el, c) {                        // el: <div> vazio do tamanho da célula; c: contexto
//       ...monta o DOM/SVG/canvas dentro de el...
//       return {
//         atualizar(c) { ... },             // chamado a cada leitura e quando o tema/tamanho muda
//         destruir() { ... },               // tira ouvintes/animações; o host esvazia el depois
//       };
//     },
//   };
//
// Tamanhos aceitos, métricas aceitas e opções ficam em catalogo.json (fonte única: o Python e o
// app validam com o mesmo arquivo). O widget só desenha: não chama a API, não manda nada à
// central, não lê arquivo, não altera o layout. Cores, fontes e espessuras vêm SEMPRE de c.tema
// (nenhuma cor fixa no código: há um teste que varre static/**).
//
// Contexto c (montado pelo host, widgets/host.js):
//   c.metrica    métrica do perfil: {id, ordem, nome, curto, unidade, tipo ('num'|'bit'),
//                tipo_perfil ('u8'|'u16'|'curva'|'bit'|'enum'), decimais, min, max, textos, mascaras, pendente}
//                — null para widgets sem métrica (falhas, luz com fonte 'falhas')
//   c.valor      {numero, texto} da última leitura, ou null (sem leitura ainda / bloco não lido)
//   c.historico  {t: [...segundos], v: [...números]} dos últimos c.opcoes.segundos (ou 60 s)
//   c.avaliacao  {estado: 'neutro'|'normal'|'atencao'|'fora', lado: 'acima'|'abaixo'|null, msg,
//                condicao (rótulo curto, ex. "em marcha lenta"), faixa: [min, max] | null}
//   c.extra      {<id da métrica>: {metrica, valor}} das outras métricas citadas nas opções
//   c.falhas     {presentes: [{codigo, nome, tipo_nome}], memorizadas: [...], hora} | null
//   c.tema       o `painel` do tema ativo, completo (cores, fonte, espessuras, ponteiro, escala,
//                raios, animacao, peles); a pele do tipo em c.tema.peles?.[tipo] (pode faltar)
//   c.temaId     id do tema ativo (para montar URLs de imagens: urlArquivo(c, nome))
//   c.area       {largura, altura} em px do espaço do widget
//   c.opcoes     opções do layout já completadas com os padrões do catálogo
//   c.titulo     título a mostrar (opções do layout ou o nome curto da métrica)
//   c.indisponivel  texto do motivo quando o host não conseguiu montar (o host mostra; o widget nem é criado)

export const NS = 'http://www.w3.org/2000/svg';

/** Cria um elemento SVG com atributos e, opcionalmente, já o põe dentro de `pai`. */
export function svgEl(tag, at = {}, pai) {
  const e = document.createElementNS(NS, tag);
  for (const k in at) if (at[k] != null) e.setAttribute(k, at[k]);
  if (pai) pai.append(e);
  return e;
}

/** Elemento HTML simples: el('div.classe', {estilo...}, filhos...). */
export function el(tag, at = {}, ...filhos) {
  const [nome, ...cls] = tag.split('.');
  const e = document.createElement(nome || 'div');
  if (cls.length) e.className = cls.join(' ');
  for (const k in at) {
    if (k === 'style') Object.assign(e.style, at[k]);
    else if (at[k] != null) e.setAttribute(k, at[k]);
  }
  for (const f of filhos.flat()) if (f != null) e.append(f);
  return e;
}

/** Ângulo (graus a partir do topo, sentido horário) → ponto, num quadrado com centro (cx, cy). */
export const pol = (cx, cy, r, g) => {
  const a = (g - 90) * Math.PI / 180;
  return [cx + r * Math.cos(a), cy + r * Math.sin(a)];
};

/** Caminho SVG de um arco de g0 a g1 (graus, sentido horário). */
export function arco(cx, cy, r, g0, g1) {
  const [x0, y0] = pol(cx, cy, r, g0), [x1, y1] = pol(cx, cy, r, g1);
  return `M${x0} ${y0} A${r} ${r} 0 ${g1 - g0 > 180 ? 1 : 0} 1 ${x1} ${y1}`;
}

/** Número no jeito brasileiro com `dec` casas; '—' se não houver. */
export function numBr(x, dec = 0) {
  if (x == null || Number.isNaN(x)) return '—';
  return Number(x).toFixed(dec).replace('.', ',');
}

/** Casas decimais da métrica (opção do layout tem prioridade). */
export function decimais(c) {
  return c.opcoes?.decimais ?? c.metrica?.decimais ?? 0;
}

/** Cor do estado da faixa, pelas cores do tema. */
export function corEstado(c, estado = c.avaliacao?.estado) {
  const k = c.tema.cores;
  return { normal: k.ok, atencao: k.ambar, fora: k.alerta }[estado] || k.texto;
}

/** Texto do estado como no cabeçalho da Diagnóstico: NORMAL / ALTO / BAIXO / ATENÇÃO / ''. */
export function rotuloEstado(av) {
  if (!av || av.estado === 'neutro') return '';
  if (av.estado === 'normal') return 'NORMAL';
  if (av.lado === 'acima') return 'ALTO';
  if (av.lado === 'abaixo') return 'BAIXO';
  return 'ATENÇÃO';
}

/** URL de um arquivo da pasta do tema ativo (imagens e fontes das peles). */
export function urlArquivo(c, nome) {
  return nome ? '/tema/' + encodeURIComponent(c.temaId) + '/' + nome.split('/').map(encodeURIComponent).join('/') : null;
}

/** Família de fonte do painel do tema; se a pele trouxer arquivo, registra um @font-face e usa. */
const fontesCarregadas = new Map();
export function fonte(c, arquivoPele) {
  const base = c.tema.fonte?.web?.familia || 'sans-serif';
  if (!arquivoPele) return `"${base}", sans-serif`;
  const url = urlArquivo(c, arquivoPele);
  if (!fontesCarregadas.has(url)) {
    const nome = 'pele-' + fontesCarregadas.size;
    fontesCarregadas.set(url, nome);
    const f = new FontFace(nome, `url(${url})`);
    f.load().then(x => document.fonts.add(x)).catch(() => {});
  }
  return `"${fontesCarregadas.get(url)}", "${base}", sans-serif`;
}

/** Normaliza v em [0, 1] entre min e max (limitado). */
export const frac = (v, min, max) => Math.min(1, Math.max(0, (v - min) / (max - min || 1)));

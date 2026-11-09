# Tema (`temas/<id>/tema.json`)

Um tema muda **só a aparência**: cores, fontes, espessuras, estilo do ponteiro e da escala, raios,
animação e, no futuro, um mascote. É **só dados**: nenhum código, nenhum CSS livre, nenhum SVG.
Tudo o que a tela desenha lê o tema ativo; não há cor nem fonte fixa no código.

O tema completo de referência é [`temas/padrao/tema.json`](../temas/padrao/tema.json) ("Padrão").
Temas que vêm com o projeto ficam em `temas/`; os importados pelo usuário ficam em
`~/.config/psascan/temas/<id>/` (Linux) e na pasta interna do app (Android).

## Arquivo e pasta

Um tema é **um** `tema.json`, ou um `.zip` com `tema.json` na raiz e uma pasta opcional `arquivos/`
com fontes e imagens que o tema cita pelo nome (ex.: `"arquivos/mascote.png"`).

Limites da pasta: só `.woff2` (fonte da web), `.ttf` (fonte do app), `.png`, `.webp`, `.jpg`;
até 2 MB por arquivo e 10 MB no total; nenhum caminho com `..`, `/` no início ou subpasta além de
`arquivos/`. Qualquer outro arquivo faz o tema ser recusado.

## Campos de topo

| Campo | Tipo | Descrição |
| --- | --- | --- |
| `formato` | inteiro | Versão deste formato. Hoje `1`. |
| `id` | texto | `a-z0-9-`, 2 a 32 caracteres. Igual ao nome da pasta. |
| `nome` | texto | Nome para a tela (até 40 caracteres). |
| `descricao`, `autor` | texto | Opcionais. |
| `interface` | objeto | Telas do painel web (todas menos o painel do carro). |
| `painel` | objeto | Mostradores (painel do carro na web e todo o app Android). |
| `mascote` | objeto ou `null` | Espaço reservado; ainda não é desenhado. |

**Campos que faltam** são preenchidos com os do Padrão (um tema pode mudar só o que quiser).
**Campo desconhecido, tipo errado ou valor fora da faixa**: o tema inteiro é recusado e a
ferramenta continua no Padrão, mostrando o motivo.

## Cores

Toda cor é texto `#rrggbb` ou `#rrggbbaa` (com transparência). Listas de cores são degradês, na
ordem indicada.

## `interface` (web)

| Campo | Descrição |
| --- | --- |
| `escuro`, `claro` | Cores do modo escuro e do claro. Chaves = as variáveis CSS do painel web, sem `--` (tabela abaixo). |
| `fontes` | `num` (números e títulos), `ui` (texto), `mono` (bytes e log): lista de famílias como no CSS (`"Barlow Condensed", system-ui, sans-serif`). Só letras, números, espaço, vírgula, aspas e hífen. |
| `raios` | `r` e `r-sm`: raio dos cartões e dos controles, em px (0 a 24). |

Chaves de cor de `escuro`/`claro`:

| Chave | Uso |
| --- | --- |
| `bg`, `rail`, `surface`, `surface-2`, `surface-3` | fundo, trilho lateral e superfícies em camadas |
| `line`, `line-2` | divisórias e bordas |
| `ink`, `ink-2`, `muted` | texto principal, secundário e apagado |
| `accent`, `accent-ink`, `accent-soft` | destaque, texto sobre o destaque, fundo suave |
| `ok`, `warn`, `bad` (+ `-ink`, `-soft`) | estados normal / atenção / fora |
| `rec` | gravação |
| `grid`, `axis` | grade e eixos dos gráficos |
| `s1` … `s8` | cores das séries dos gráficos |
| `estrada-fundo`, `estrada-texto` | modo estrada: fundo e texto principal |
| `estrada-barra`, `estrada-sair`, `estrada-sair-borda` | modo estrada: barra de cima e botão "segure para sair" |
| `estrada-grade`, `estrada-rotulo`, `estrada-detalhe` | modo estrada: divisória entre números, rótulos e linha de detalhe |
| `estrada-fora`, `estrada-atencao` | modo estrada: número fora / em atenção (e o REC) |
| `estrada-toque`, `estrada-toque-borda` | modo estrada: botão de marcar evento |
| `faixa-ink`, `faixa-btn`, `faixa-btn-ink` | faixa vermelha do atuador ativo: texto, botão e texto do botão |
| `scrim`, `sombra-toast`, `sombra-menu` | fundo atrás dos diálogos e sombras do aviso e do menu "Mais" |
| `etapa-ink` | ícone dentro das etapas concluídas na tela Início |

Novas chaves só entram aqui (e no Padrão) quando uma tela precisar.

## `painel` (mostradores e app)

### `cores`

| Chave | Uso |
| --- | --- |
| `fundo` | fundo da tela |
| `texto`, `texto_medio`, `texto_fino`, `texto_apagado` | números grandes, traços médios, régua fina e textos apagados |
| `passou` | escala por onde o ponteiro já passou |
| `ambar`, `alerta`, `ok` | atenção, zona de corte/fora, normal |
| `aro` | degradê do aro (cima, meio, baixo) |
| `aro_interno` | anel escuro entre o aro e o fundo do mostrador |
| `face` | degradê radial do fundo do mostrador (centro, 70 %, borda) |
| `ponteiro` | degradê da agulha de um lado ao outro (4 cores: luz, meio, sombra, borda) |
| `ponteiro_aresta`, `ponteiro_brilho` | linha clara no meio da agulha e cor do brilho em volta |
| `sombra`, `contorno_numeros` | sombra do ponteiro e do miolo; contorno dos números |
| `miolo`, `chanfro` | degradê do disco central (centro, borda) e da borda chanfrada (cima, meio, baixo) |
| `rolo_fundo`, `rolo_borda`, `rolo_texto` | rolos de odômetro do RPM |
| `rolo_destaque_fundo`, `rolo_destaque_borda`, `rolo_destaque_texto` | o rolo dos milhares |
| `agua_trilho` | trilho vazio da barra da água |
| `luz_apagada` | luz de injeção apagada (acesa usa `ambar`) |
| `rotulo_fundo` | fundo dos rótulos BATERIA/AR |
| `superficie`, `superficie_2`, `borda`, `campo` | cartões, diálogos e campos do app |
| `grafico_grade`, `grafico_limite` | gráficos de histórico do app |
| `chave_ligada_trilho` | trilho da chave "Permitir comandos de escrita" ligada |

### `fonte`

```json
"fonte": {"web": {"familia": "Barlow Condensed", "arquivos": {"500": "arquivos/x-500.woff2"}},
          "app": {"sistema": "condensada", "arquivo": null}}
```

- `web.familia`: nome da família; `web.arquivos`: peso → `.woff2` da pasta do tema (opcional; sem
  arquivo, usa a fonte já embutida ou a do sistema).
- `app.sistema`: `condensada`, `padrao`, `serifada` ou `mono` (fontes do Android);
  `app.arquivo`: `.ttf` da pasta do tema (opcional, tem prioridade).

### `espessuras` (unidades do mostrador de 200 × 200)

`aro` (0,5–8), `traco_grande` (0,5–6), `traco_medio` (0,2–4), `traco_fino` (0,1–2),
`ponteiro_base` e `ponteiro_ponta` (meia largura da agulha na base e na ponta, 0,1–5),
`contorno_numeros` (0–5; 0 = sem contorno), `agua` (1–10), `alerta` (0,5–4).

### `ponteiro`

| Campo | Valores |
| --- | --- |
| `estilo` | `agulha` (afina até a ponta), `seta` (agulha com cabeça de seta), `barra` (largura constante) |
| `brilho`, `sombra` | 0 a 1 (intensidade) |
| `raio_inicio`, `raio_ponta` | 0 a 95; o início fica escondido sob o miolo se for menor que `raios.miolo` |

### `escala`

`niveis`: `2` (traço grande e médio) ou `3` (com a régua fina); `pintar_passou`: pinta traços e
números até o ponteiro com `passou`; `numeros_por_cima`: números numa camada acima do ponteiro.

### `raios`

`miolo` (20–60, unidades do mostrador), `rotulo` e `cartao` (0–24, dp/px).

### `animacao`

`ponteiro`: `mola` (persegue o valor com inércia), `suave` (interpolação curta) ou `nenhuma`;
`rapidez`: 1 a 20 (quanto maior, mais rápido o ponteiro alcança o valor).

## `painel.peles` — o desenho de cada tipo de widget

As **peles** deixam um tema mudar o **desenho** de cada tipo de widget, não só as cores, no
estilo das skins do Winamp, mas sempre **só com dados**: imagens por peça, geometria e fontes.
Toda peça é opcional: o que o tema não trouxer é desenhado pelo motor com as cores e espessuras
acima (é assim que o Padrão funciona, sem nenhuma imagem). Os tipos de widget estão em
[LAYOUT.md](LAYOUT.md).

```json
"peles": {
  "mostrador": {
    "angulo_inicio": -128, "angulo_fim": 128,
    "imagens": {
      "fundo":  "arquivos/fundo.webp",
      "agulha": {"arquivo": "arquivos/agulha.png", "pivo": [0.5, 0.92], "comprimento": 0.45},
      "miolo":  "arquivos/miolo.png",
      "vidro":  "arquivos/vidro.png",
      "mascara_arco": "arquivos/segmentos.png"
    },
    "desenhar": {"aro": false, "escala": false, "numeros": false, "ponteiro": false, "miolo": false},
    "arco": {"mostrar": true, "raio": 84, "espessura": 9, "estilo": "segmentos", "segmentos": 48,
             "cor": "#2fd6a0", "cor_alerta": "#ff5a52"},
    "cores": {"texto": "#10261a"},
    "digital": {"posicao": [100, 120], "tamanho": 34, "fonte": "arquivos/seg7.woff2",
                "fonte_app": "arquivos/seg7.ttf", "cor": "#7dffb3", "brilho": 0.6, "apagados": true}
  },
  "barra":  {"estilo": "segmentos", "segmentos": 20, "espaco": 0.2, "raio": 2,
             "imagens": {"trilho": "arquivos/barra-vazia.png", "cheia": "arquivos/barra-cheia.png"}},
  "numero": {"fonte": "arquivos/seg7.woff2", "fonte_app": "arquivos/seg7.ttf", "brilho": 0.5, "apagados": true},
  "numero_historico": {"linha": 1.6, "preencher": true},
  "luz":    {"imagens": {"apagada": "arquivos/luz-off.png", "acesa": "arquivos/luz-on.png"}, "brilho": 0.8},
  "estado": {"fonte": null, "caixa": true},
  "falhas": {"fonte": null}
}
```

### Mostrador

O mostrador é desenhado num quadrado de 200 × 200 unidades (centro em 100, 100), de baixo para
cima: **fundo** → aro, escala e números (se `desenhar` deixar) → **arco** → **agulha** →
**miolo** → leituras do centro (**digital**, rolos) → **vidro**.

| Campo | Descrição |
| --- | --- |
| `angulo_inicio`, `angulo_fim` | Ângulos da escala, em graus a partir do topo, sentido horário (−180 a 360). Ex.: `-90` a `150` para um arco aberto à direita. |
| `imagens.fundo` | Imagem que cobre o quadrado inteiro (pode trazer a arte, os traços e os números já desenhados). |
| `imagens.agulha` | Imagem da agulha **apontando para cima**. `pivo` = ponto de giro na própria imagem (0–1, 0–1); `comprimento` = altura da agulha em fração do quadrado (0,1–1). O motor gira a imagem. |
| `imagens.miolo`, `imagens.vidro` | Imagens que cobrem o quadrado (miolo por cima da agulha; vidro/brilho por cima de tudo). |
| `imagens.mascara_arco` | Imagem do arco "aceso"; o motor mostra só o trecho do início até o valor (para segmentos, LEDs, arte). |
| `desenhar` | Liga/desliga cada parte desenhada pelo motor (padrão: tudo ligado, ou desligado quando há imagem da mesma parte). |
| `arco` | Arco desenhado (alternativa à máscara): `estilo` `continuo` ou `segmentos`, `raio` (20–98), `espessura` (1–30), `segmentos` (8–120), cores. |
| `cores` | Cores só deste tipo de widget, com as mesmas chaves de `painel.cores` (ex.: números escuros num mostrador iluminado por trás, sem mudar o texto do resto da tela). |
| `digital` | Número do centro: `posicao` (unidades do quadrado), `tamanho` (6–80), fonte própria para a web (`.woff2`) e para o app (`.ttf`), `cor`, `brilho` (0–1) e `apagados` (mostra os segmentos apagados de fundo, como num visor de 7 segmentos: `8888` bem fraco atrás do valor). |

### Barra, número, luz, estado, falhas

| Tipo | Campos |
| --- | --- |
| `barra` | `estilo` (`continua`, `segmentos`), `segmentos` (4–60), `espaco` (0–0,6 do segmento), `raio` (0–12), `imagens.trilho` e `imagens.cheia` (a cheia é mostrada só até o valor). |
| `numero` | `fonte`/`fonte_app`, `brilho` (0–1), `apagados`. |
| `numero_historico` | `linha` (espessura da curva, 0,5–4), `preencher` (área sob a curva). |
| `luz` | `imagens.apagada`/`imagens.acesa` (senão, o ícone desenhado), `brilho` (0–1). |
| `estado`, `falhas` | `fonte`/`fonte_app` (opcionais), `estado.caixa` (contorno em volta do texto). |

### Imagens

`.png`, `.webp` ou `.jpg` da pasta `arquivos/` do tema (limites de tamanho acima). Para ficar
nítido no celular, recomenda-se que as imagens do mostrador tenham 1024 × 1024 px. As imagens
são esticadas para o espaço do widget sem distorcer (o mostrador é sempre quadrado).

## `mascote` (reservado)

```json
"mascote": {"imagem": "arquivos/faisca.png", "posicao": "canto", "nome": "Faísca"}
```

`posicao`: `canto` ou `centro`. Ainda não é desenhado; o campo existe para temas futuros
(ex.: a "Faísca", uma vela de ignição com rosto).

## Validação

Ao importar ou carregar: JSON válido; `formato` = 1; `id` válido; cores no formato acima; números
dentro das faixas; enumerações da lista fechada; chaves conhecidas; arquivos citados existem na
pasta e respeitam os limites; contraste mínimo de 4,5:1 entre `painel.cores.texto` e
`painel.cores.fundo` e entre `ink` e `bg` de cada modo da interface. Tema recusado não é salvo e o
ativo continua o anterior (ou o Padrão), com a mensagem do motivo.

## Importar e exportar

- **Web**: aba Avançado → Tema: escolher entre os temas instalados, importar (`.json` ou `.zip`) e
  exportar o ativo como `.zip`. API: `GET /api/temas`, `GET /api/tema` (ativo, já completado com o
  Padrão), `POST /api/tema/ativar {"id"}`, `POST /api/tema/importar` (corpo = o arquivo),
  `GET /api/tema/exportar?id=`.
- **App**: tela do scanner → Tema: escolher, importar pelo seletor de arquivos do Android e
  exportar pelo menu de compartilhar.

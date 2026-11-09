# Layout (`layouts/<id>.json`) e widgets

Um **layout** diz **quais** widgets aparecem na tela personalizável e **onde**. O **tema** diz
**como** cada tipo de widget se parece (cores, e a "pele" de cada tipo: ver [TEMA.md](TEMA.md)).
Os dois são independentes: qualquer layout funciona com qualquer tema.

Um layout é **só dados** (JSON). As métricas vêm do **perfil** da central ativo
([PERFIL.md](PERFIL.md)): o layout as cita pelo `id` (`rpm`, `velocidade`, `agua`…).

Layouts que vêm com o projeto ficam em `layouts/`; os do usuário em
`~/.config/psascan/layouts/` (Linux) e na pasta interna do app (Android). Importar e exportar é
pelo arquivo `.json`.

## Exemplo

```json
{
  "formato": 1,
  "id": "painel",
  "nome": "Painel",
  "descricao": "Conta-giros e velocímetro lado a lado, luz de injeção, bateria e ar no meio.",
  "grade": {"colunas": 12, "linhas": 6},
  "widgets": [
    {"id": "giros", "tipo": "mostrador", "metrica": "rpm", "x": 0, "y": 0, "largura": 5, "altura": 6,
     "opcoes": {"escala": {"min": 0, "max": 7000, "maior": 1000, "divisor_rotulo": 1000, "alerta": 6500},
                "centro": {"conteudo": "rolos"}}},
    {"id": "luz", "tipo": "luz", "metrica": "luz_injecao", "x": 5, "y": 0, "largura": 2, "altura": 2},
    {"id": "bat", "tipo": "numero", "metrica": "bateria", "x": 5, "y": 2, "largura": 2, "altura": 2},
    {"id": "ar", "tipo": "numero", "metrica": "ar", "x": 5, "y": 4, "largura": 2, "altura": 2},
    {"id": "vel", "tipo": "mostrador", "metrica": "velocidade", "x": 7, "y": 0, "largura": 5, "altura": 6,
     "opcoes": {"escala": {"min": 0, "max": 200, "maior": 20},
                "centro": {"conteudo": "digital"}, "arco_inferior": {"metrica": "agua", "min": 50, "max": 130}}}
  ]
}
```

## Campos

| Campo | Tipo | Descrição |
| --- | --- | --- |
| `formato` | inteiro | Hoje `1`. |
| `id` | texto | `a-z0-9-`, 2 a 32. Igual ao nome do arquivo. |
| `nome`, `descricao` | texto | Para a lista de layouts. |
| `grade.colunas` | 4 a 16 | Colunas da grade. |
| `grade.linhas` | 3 a 12 | Linhas da grade. A grade ocupa a tela inteira; as células se esticam (não precisam ser quadradas). |
| `orientacao` | `paisagem`, `retrato`, `qualquer` | Opcional (padrão `qualquer`): em que orientação o layout faz sentido. |
| `widgets` | lista | Até 40 widgets. |

### Widget

| Campo | Tipo | Descrição |
| --- | --- | --- |
| `id` | texto | Único no layout (`a-z0-9_-`). |
| `tipo` | texto | Um dos tipos abaixo. |
| `metrica` | texto | `id` da métrica no perfil. Não usado por `falhas`. |
| `x`, `y` | inteiro | Canto superior esquerdo, em células (0 = primeira). |
| `largura`, `altura` | inteiro | Em células. Precisa estar entre os tamanhos aceitos pelo tipo. |
| `titulo` | texto | Opcional; padrão = nome curto da métrica no perfil. |
| `opcoes` | objeto | Opções do tipo (tabela abaixo). Chave desconhecida = layout inválido. |

Widgets não podem sair da grade nem se sobrepor.

## Tipos de widget

| Tipo | O que mostra | Tamanhos aceitos (largura × altura, em células) | Métricas |
| --- | --- | --- | --- |
| `numero` | Número grande com unidade e nome. | mín. 1×1, máx. 6×4 | numéricas e discretas (mostra o texto) |
| `numero_historico` | Número + gráfico dos últimos segundos e faixa normal (o cartão da Diagnóstico). | mín. 2×2, máx. 12×6 | numéricas |
| `mostrador` | Mostrador circular de ponteiro. | mín. 2×2, máx. 12×12 | numéricas |
| `barra` | Barra horizontal ou vertical, contínua ou em segmentos. | mín. 1×1, máx. 12×12 | numéricas |
| `estado` | Estado em texto: NORMAL / ALTO / BAIXO / ATENÇÃO, ou o texto de uma métrica discreta (ex.: "marcha lenta"). | mín. 1×1, máx. 6×3 | qualquer |
| `luz` | Luz que acende: uma métrica de bit (luz de injeção) ou "há falha presente". | mín. 1×1, máx. 4×4 | bit; ou sem métrica, com `opcoes.fonte: falhas` |
| `falhas` | Quantas falhas presentes/memorizadas na última leitura, com os códigos. | mín. 2×1, máx. 12×6 | — |

### Opções por tipo

Todas são opcionais; o que faltar vem do perfil (escala, decimais, unidade) ou do tema.

- `numero`: `decimais` (0–4), `mostrar_unidade` (bool), `mostrar_estado` (bool, colore pela faixa).
- `numero_historico`: `segundos` (10–600, padrão 60), `mostrar_faixa` (bool).
- `mostrador`:
  - `escala`: `min`, `max`, `maior` (passo dos números), `menor` (quantas divisões entre
    números, 1–20, padrão 10), `divisor_rotulo` (ex.: 1000 → "1, 2, 3"), `alerta` (início da
    zona vermelha).
  - `centro`: `conteudo` = `nada`, `digital` (o valor), `rolos` (rolos de odômetro) ou
    `metrica` (outra métrica em digital); `metrica` (quando `conteudo` = `metrica`).
    Ex.: conta-giros grande com a velocidade digital no meio = `{"conteudo": "metrica", "metrica": "velocidade"}`.
  - `arco_inferior`: barra pequena no arco de baixo (`metrica`, `min`, `max`), como a da água.
- `barra`: `orientacao` (`horizontal`, `vertical`), `min`, `max`, `mostrar_valor` (bool).
- `estado`: nada.
- `luz`: `fonte` (`metrica`, padrão, ou `falhas` = acende se há falha presente na última leitura), `rotulo` (texto opcional embaixo).
- `falhas`: `mostrar_codigos` (bool).

Métrica que não existe no perfil ativo, ou de tipo incompatível com o widget: o widget aparece
como **indisponível** (com o motivo) e o resto da tela funciona. Isso não invalida o layout,
porque o mesmo layout pode ser usado com outro perfil.

## Interface comum de widget (código)

Cada tipo de widget é um módulo próprio (web: `psascan/static/js/widgets/<tipo>.js`; app:
`ui/widgets/<Tipo>.kt`) com a mesma forma:

```
tipo:        "mostrador"
tamanhos:    { minimo: [2, 2], maximo: [12, 12] }
aceita(m):   bool — a métrica do perfil serve para este tipo?
opcoes:      descrição das opções (nome, tipo, faixa, padrão) — usada pela validação e pelo editor
desenhar(c): desenha o widget no espaço dado, a cada leitura
```

`c` (o contexto que o widget recebe; nunca busca nada por conta própria):

| Campo | Conteúdo |
| --- | --- |
| `metrica` | a métrica do perfil (nome, curto, unidade, escala, decimais, valores…) |
| `valor` | `{numero, texto, bruto}` da última leitura, ou nada (sem leitura / bloco não lido) |
| `historico` | lista `(t, numero)` dos últimos segundos |
| `avaliacao` | `{estado, lado, msg, condicao}` da faixa (Fase 7) |
| `extra` | valores de outras métricas citadas nas opções (ex.: `centro.metrica`, `arco_inferior`) |
| `falhas` | última leitura de falhas (só para `luz` de falhas e `falhas`) |
| `tema` | o `painel` do tema ativo, incluindo a **pele** do tipo (`painel.peles.<tipo>`) |
| `area` | tamanho em pixels do espaço do widget |
| `opcoes` | as opções do layout já completadas com os padrões |

O widget só desenha: não manda nada à central, não lê arquivo, não altera o layout.

## Layouts prontos

`painel`, `mistura-lambda`, `marcha-lenta`, `partida-quente` e `completo` (pedidos no plano), mais `giro-grande` (conta-giros grande com a velocidade digital no meio), em `layouts/`.

## Edição

Na tela personalizável, o modo edição permite adicionar, remover, arrastar, redimensionar e
trocar a métrica e o tipo de cada widget, respeitando os tamanhos aceitos e sem sobrepor. Salvar
grava uma cópia do layout do usuário (os prontos não são alterados). O layout ativo é guardado
por aparelho.

## Validação

`formato` = 1; `id` válido; grade dentro das faixas; cada widget com `tipo` conhecido, `id`
único, tamanho dentro do aceito pelo tipo, dentro da grade e sem sobrepor outro; `opcoes` só com
chaves do tipo e valores nas faixas. Layout inválido não é salvo nem ativado; a tela continua no
anterior (ou no `painel`) e mostra o motivo.

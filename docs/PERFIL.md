# Perfil de central (`perfis/<id>/perfil.json`)

Um perfil descreve **uma central** (ex.: Sagem SL96): como conectar, o que ler, como converter
os bytes em valores, as faixas de referência, os códigos de falha e os comandos de escrita que
foram validados. É **só dados**: nenhum código executável. O código que sabe falar com a
central é a **família de protocolo** (`familia`), escolhida pelo perfil.

O exemplo completo é [`perfis/sagem-sl96/perfil.json`](../perfis/sagem-sl96/perfil.json). O
mesmo arquivo é usado pela versão Linux (Python) e pelo app Android.

Para criar o perfil de outra central, siga [METODO.md](METODO.md): todo item precisa de uma
captura que o mostre, e o campo `nivel` diz quanto ele foi confirmado.

## Campos de topo

| Campo | Tipo | Obrigatório | Descrição |
| --- | --- | --- | --- |
| `formato` | inteiro | sim | Versão deste formato. Hoje `1`. |
| `id` | texto | sim | Identificador (`a-z0-9-`), igual ao nome da pasta. |
| `nome` | texto | sim | Nome da central, para a tela. |
| `compatibilidade` | lista de textos | sim | Carros/motores em que foi testado. |
| `scanner` | texto | sim | Scanner suportado. Hoje só `psa-com`. |
| `familia` | texto | sim | Família de protocolo. Hoje só `psa5`. |
| `documentacao` | texto | não | Caminho do documento do protocolo. |
| `conexao` | objeto | sim | Ver abaixo (o conteúdo depende da família). |
| `leitura` | objeto | sim | Blocos de parâmetros e leitura de falhas. |
| `metricas` | lista | sim | Valores que a central entrega. |
| `curvas` | objeto | não | Conversões não lineares, por nome. |
| `condicoes` | objeto | sim | Condições usadas pelas faixas (ex.: "motor quente"). |
| `falhas` | objeto | sim | Tipos e códigos de falha conhecidos. |
| `escrita` | lista | não | Comandos que mudam o estado do carro. Desligados por padrão. |

Bytes são sempre texto em hexadecimal separado por espaço: `"13 07 03 00"`.

## `conexao` (família `psa5`)

| Campo | Descrição |
| --- | --- |
| `pino` | Comando do scanner que escolhe o pino da linha K. |
| `destravar` | Comando mandado se o scanner recusar o `pino` com `7F <cmd> 07`; depois o `pino` é repetido. Opcional. |
| `linha` | Comando do scanner que configura a linha K (velocidade e tempos). |
| `inicio` | Primeiro pedido à central. |
| `inicio_tentativas` | Quantas vezes tentar o `inicio` (para na primeira resposta). |
| `acordar` | Comando do scanner de inicialização lenta (5 baud), se o `inicio` não responder. Opcional. |
| `acordar_timeout_s` | Tempo máximo de espera da resposta do `acordar`. |
| `manter` | Pedido de manutenção da sessão. |
| `manter_apos_s` | Manda o `manter` se ficou este tempo sem conversa. |
| `descricao` | Texto curto para a tela. |

## `leitura`

```json
"leitura": {
  "blocos": [{"id": 0, "pedido": "11 00"}, {"id": 1, "pedido": "11 01"}],
  "falhas": {"pedido": "33", "decodificacao": "psa5-pares", "sem_resposta": "32"}
}
```

- Cada bloco é um pedido à central cuja resposta contém várias métricas.
- `falhas.decodificacao`: algoritmo da família. `psa5-pares`: depois do eco, pares de bytes
  big-endian; código = `(v >> 3) & 0x1FF`, tipo = `v & 7`, bit `0x2000` desligado = presente.
- `sem_resposta`: primeiro byte que indica "sem resposta válida".

## `metricas`

| Campo | Tipo | Descrição |
| --- | --- | --- |
| `id` | texto | Identificador estável (`a-z0-9_`). Usado por layouts e gravações. |
| `ordem` | inteiro | Ordem na tela e chave dos ajustes de faixa do usuário. Única. |
| `curto`, `nome` | texto | Nome curto (cartão) e completo. |
| `unidade` | texto | Unidade para a tela (`""` se não tiver). |
| `bloco` | inteiro | `id` do bloco em `leitura.blocos`. |
| `byte` | inteiro | Posição nos dados da resposta, **contando o eco** (`d[0]` = 1º byte de dados). |
| `tipo` | texto | `u8`, `u16` (big-endian, `byte` e `byte+1`), `bit`, `enum`, `curva`. |
| `fator`, `offset` | número | `u8`/`u16`: valor = bruto × fator + offset. |
| `decimais` | inteiro | Casas decimais no texto (`u8`, `u16`, `curva`). Padrão 0. |
| `curva` | texto | `tipo: curva`: nome em `curvas`. O bruto é um `u8`. |
| `bit` | inteiro | `tipo: bit`: valor = `(byte >> bit) & 1`. |
| `valores` | objeto | `bit`/`enum`: texto de cada valor (`{"0": "não", "1": "sim"}`). Valor fora da lista → `"?"`. |
| `valores_pendentes` | lista | Chaves de `valores` ainda não vistas em captura. |
| `escala` | `[mín, máx]` | Faixa dos mostradores e gráficos. |
| `faixas` | lista | Regras de faixa (abaixo). |
| `obs` | texto | Observação para a tela. |
| `analise` | texto | Avaliação especial em vez de faixa. Hoje só `lambda` (trocas rico↔pobre). |
| `luz_falha` | booleano | A métrica é a luz de aviso de falha (a gravação relê as falhas quando ela acende). |
| `nivel` | texto | `V1`, `V2`, `V3` ou `pendente` (ver METODO.md). |
| `evidencia` | texto | Onde/como foi confirmada. |

**Texto mostrado**: número com `decimais` casas e a `unidade` (`"14.0 V"`); `bit`/`enum`: o texto
de `valores`.

### Regras de faixa

Vale a primeira regra cuja condição (`quando`) é verdadeira agora; se nenhuma vale, o estado é
**neutro**.

```json
{"quando": "marcha_lenta_quente", "normal": [800, 900], "atencao": [720, 1000],
 "abaixo": "texto se abaixo", "acima": "texto se acima", "nota": "texto"}
{"quando": "ligado", "normal_valor": 1, "msg": "texto se diferente"}
```

Dentro de `normal` → normal; dentro de `atencao` → atenção; fora → fora. Com `normal_valor`:
igual → normal; diferente → atenção.

## `curvas`

```json
"temperatura": {"tipo": "polinomio", "coef": [c0, c1, c2, c3], "min": -40, "max": 130,
                "nivel": "pendente", "nota": "…"}
```

`polinomio`: valor = Σ coef[n] × brutoⁿ, limitado a `[min, max]`.

## `condicoes`

```json
"quente": {"texto": "motor ligado e quente (água ≥ 80 °C)", "rotulo": "com motor quente",
           "regras": [{"condicao": "ligado"}, {"metrica": "agua", "op": ">=", "valor": 80}]}
```

`texto` descreve a condição; `rotulo` é a forma curta que acompanha a faixa na tela
("normal **em marcha lenta** 8–16 °"). `nota` é opcional (ex.: condição ainda sem faixas).

Uma condição é verdadeira se **todas** as regras forem. Regra de métrica: `op` em
`> >= < <= == !=`; sem leitura da métrica, a regra é falsa. Regra `condicao` reaproveita outra
condição. `sempre` (sem regras) é sempre verdadeira.

## `falhas`

```json
"falhas": {
  "tipos": {"1": {"nome": "coerência", "nivel": "V1"}},
  "codigos": {"295": {"nome": "Regulação de riqueza", "nivel": "V1", "causas": ["…"]}}
}
```

Código ou tipo fora da lista aparece como "código 0x127 (não identificado)" / "tipo 5".
`nivel: pendente` = nome provável, ainda não visto em captura: a tela diz isso.

## `escrita`

Comandos que **mudam o estado do carro**. A ferramenta começa em **somente leitura**: para usar
qualquer um destes, a escrita tem de ser habilitada explicitamente (CLI `--escrita`; web e app:
chave "Permitir comandos de escrita", desligada a cada início), e cada envio ainda pede
confirmação mostrando os bytes.

| Campo | Descrição |
| --- | --- |
| `id` | Identificador. |
| `tipo` | `apagar_falhas` ou `atuador`. |
| `nome` | Texto para a tela. |
| `pedido` | Bytes do pedido à central. |
| `antes` | Lista opcional de pedidos mandados antes. |
| `perigoso` | `true` exige digitar PERIGO além da confirmação. |
| `aviso` | O que acontece / como parar. Mostrado antes de confirmar. |
| `nivel` | Só entram comandos com captura (`V1` ou mais). |

## Validação

Ao carregar, o perfil é validado (campos obrigatórios, tipos, bytes em hex, `bloco`/`curva`/
`condicao` que existem, `ordem` e `id` únicos).
Perfil inválido não é usado: a ferramenta mostra o motivo.

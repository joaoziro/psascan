# Protocolo da Sagem SL96 pelo scanner PSA COM

> **Aviso.** Este documento descreve o que foi **observado** em capturas de tráfego. Não é
> especificação oficial. A compatibilidade foi testada em um único conjunto: Peugeot 306 1.8 16v
> (motor XU7JP4), central Sagem SL96, scanner Bluetooth "PSA COM". Sem garantia de funcionar
> em outros carros, centrais ou scanners. Use por sua conta e risco.

Como as evidências são citadas:

- `161237:7` = `capturas/20261025-161237.jsonl`, linha 7 (linha do `tx`; a resposta vem em
  seguida). Todas as capturas são de 25/10/2026.
- `capturas/ref/*.jsonl` = captura Bluetooth de uma ferramenta de diagnóstico comercial para
  PSA (a "ferramenta de referência") falando com a mesma central.
- `registros/20261025-163040.csv` = 323 leituras de parâmetros (motor ligado, quente, marcha lenta).
- Bytes em hexadecimal. Os valores de exemplo foram conferidos contra o CRC e o checksum.

O método para chegar a estes dados está em [METODO.md](METODO.md).

## Níveis de confirmação

| Nível | Significado |
| --- | --- |
| **V1** | Comando e resposta aparecem byte a byte numa captura do carro. |
| **V2** | V1, e o valor varia de forma coerente com o carro (parado × ligado, frio × quente, marcha lenta) e o valor convertido é fisicamente plausível. |
| **V3** | Valor conferido com referência independente (multímetro, termômetro, conta-giros). Nenhum parâmetro está em V3. |
| **Pendente** | Sem captura que confirme. Está na lista [Pendente de validação](#pendente-de-validação-por-captura). |

---

## 1. Camada do scanner

### 1.1 Envelope

Todo quadro entre o computador e o scanner, nos dois sentidos:

```
80  <tam>  <conteúdo…>  <crc_lo>  <crc_hi>
```

- `80` é constante em todos os quadros observados.
- `<tam>` é o número de bytes do conteúdo (de 1 a 255). Quadros com conteúdo maior que 255 não
  apareceram: **pendente**.
- `<conteúdo>[0]` é o **comando**, menor que `0x80`.
- Na resposta, o primeiro byte do conteúdo é o comando com o bit alto ligado (`18` → `98`).
- Uma recusa é `7F <comando> <código> 00 00 00`.
- Nível: **V1** (855 quadros enviados e 844 recebidos conferidos).

Bluetooth entrega a resposta em pedaços. Remonte pelo `<tam>`: `153551:4` traz só `80` e
`153551:5` traz o resto (`05 98 06 00 30 64 C5 A2`).

### 1.2 CRC

CRC-16/X-25 (também chamado CRC-16/IBM-SDLC):

- polinômio refletido `0x8408`;
- valor inicial `0xFFFF`;
- complemento (`xor 0xFFFF`) no fim;
- calculado sobre `80 <tam> <conteúdo>`;
- gravado byte baixo primeiro.

```python
def crc16_x25(dados: bytes) -> int:
    c = 0xFFFF
    for b in dados:
        c ^= b
        for _ in range(8):
            c = (c >> 1) ^ 0x8408 if c & 1 else c >> 1
    return c ^ 0xFFFF
```

Teste do modelo: `crc16_x25(b"123456789") == 0x906E`.

Exemplos calculáveis:

| Quadro (sem CRC) | CRC | Quadro completo | Evidência |
| --- | --- | --- | --- |
| `80 01 18` | `0x4F31` | `80 01 18 31 4F` | `153551:3` |
| `80 04 13 07 03 00` | `0x9F3C` | `80 04 13 07 03 00 3C 9F` | `161237:7` |
| `80 03 A8 00 00` | `0xA9FA` | `80 03 A8 00 00 FA A9` | `162909:2269` |

### 1.3 Respostas `7F`

Formato: `7F <comando> <código> 00 00 00`.

| Resposta | Quando | Evidência | Nível |
| --- | --- | --- | --- |
| `7F 13 00 …` | `13` aceito (ok) | `161237:7` | V1 |
| `7F 07 00 …` | `07` aceito (ok) | `161237:10` | V1 |
| `7F 13 07 …` | `13` recusado; às vezes aparece com o motor ligado; depois do `28` o `13` passa | `161546:6`, `162909:2265` | V1 |
| `7F 07 07 …` | `07` enviado sem `13` antes | `153551:7` | V1 |
| `7F 04 13 …` | a central não respondeu ao pedido repassado | `162909:21`, `161237:14` | V1 |
| `7F 13 1A …` | pino `00`, `02` ou `0D` recusados na varredura da ferramenta de referência; significado do código `1A` pendente | `capturas/ref/20261025-motor-parado.jsonl` | V1 (significado pendente) |
| `7F 3E 00 …`, `7F 1E 29 …` | resposta a comandos `3E` e `1E` que a ferramenta de referência manda; não usados aqui | `capturas/ref/20261025-motor-parado.jsonl` | V1 (significado pendente) |

### 1.4 Comandos do scanner

Os exemplos de `tx` e `rx` são quadros completos, com CRC.

| Comando | tx (exemplo) | rx (exemplo) | Para quê | Evidência | Nível |
| --- | --- | --- | --- | --- | --- |
| `18` versão | `80 01 18 31 4F` | `80 05 98 06 00 30 64 C5 A2` | Primeiro comando. Não fala com a central. Conteúdo da resposta: `98 06 00 30 64` (tipo de hardware 6, estado 0, firmware `30 64`). | `153551:3` | V1 |
| `13 07 03 00` linha K no pino 7 | `80 04 13 07 03 00 3C 9F` | `80 06 7F 13 00 00 00 00 58 41` | Escolher o pino da linha K. O 2º byte é o pino. Obrigatório antes do `07`. | `161237:7` | V1 |
| `28 04 25 1D 48 FF` destravar | `80 06 28 04 25 1D 48 FF 44 85` | `80 03 A8 00 00 FA A9` | Usado quando o `13` é recusado com `7F 13 07`; depois o `13` passa. Significado dos bytes desconhecido. | `162909:2268` | V1 (significado pendente) |
| `07 00 04 00 00 00 68 3C 0B 05` configura a linha K | `80 0A 07 00 04 00 00 00 68 3C 0B 05 89 E7` | `80 06 7F 07 00 00 00 00 08 D8` | Configurar a velocidade. O byte `68` (104) é 10400 baud ÷ 100; na ferramenta de referência, `60` (96) aparece com 9600 baud. Os demais bytes são tempos, não decifrados um a um. | `161237:10` | V1 |
| `07 31 84 10 00 00 00 3C 6E 01` inicialização lenta (5 baud) no endereço `10` | `80 0A 07 31 84 10 00 00 00 3C 6E 01 07 FD` | `80 21 87 00 68 3B 86 DB EE 00 96 34 92 31 80 00 00 21 65 23 85 03 53 4C 39 36 34 1B 07 03 01 12 98 85 61 AA A8` | Acordar a central quando ela não responde ao `0F`. Resposta cerca de 2,4 s depois, com o texto ASCII `SL964` (`53 4C 39 36 34`) dentro. | `161400:24` | V1 |
| `04 01 <tam> <quadro>` repassar à central | `80 06 04 01 03 82 0F 6E 9D 9E` | `80 04 84 C2 0F 2E 29 74` | Todo diálogo com a central. `<tam>` é o tamanho do quadro da central, com checksum. O byte `01` é constante em todas as capturas; significado pendente. A resposta é `84` + quadro da central. | `162909:12` | V1 |
| `02` identificação | `80 01 02 EA F0` | `80 18 82 30 64 44 45 43 39 30 30 30 20 20 20 20 20 03 01 04 00 00 00 01 02 00 6F 94` | Devolve o texto ASCII `DEC9000` seguido de espaços e alguns bytes. Visto no teste da própria ferramenta e na ferramenta de referência; não é necessário para ler a central. | `154437:6` | V1 |
| `35 05 0A` | `80 03 35 05 0A 1E 0E` | `80 15 B5 04 E8 04 DE 04 DF 04 DD 04 E2 04 D4 04 DA 04 DA 04 DC 04 DA 93 E8` | A ferramenta de referência manda ao abrir. Resposta: `B5` e 10 palavras de 16 bits de ~`04DA` (~1242). **Não** é a tensão da bateria (13,9 V na mesma hora). Significado em aberto; esta ferramenta não usa. | `capturas/ref/20261025-motor-ligado.jsonl` | V1 (significado pendente) |

Os comandos `1E`, `1F` e `3E` e variações do `07` com `B1` no 2º byte aparecem só na varredura da
ferramenta de referência com o motor parado (`capturas/ref/20261025-motor-parado.jsonl`). Não
foram decifrados e não são usados.

---

## 2. Camada da central

### 2.1 Quadro

Os quadros da central viajam dentro do comando `04 01 <tam>` do scanner.

```
pedido    [0x80 + 1 + n]  dados(n)      [cs]
resposta  [0xC0 + 1 + n]  dados(n)      [cs]
```

- `n` é o número de bytes de dados do quadro. O 1º byte, menos `0x80` (pedido) ou `0xC0`
  (resposta), dá o total de bytes antes do `cs` (cabeçalho + dados).
- `cs = 0xFF − (soma do cabeçalho e dos dados) & 0xFF`. A soma de todo o quadro, com o `cs`,
  fecha em `0xFF` (módulo 256).
- O endereço da central (`10`) não aparece nos quadros. Só na inicialização lenta.
- A central ecoa o comando no início dos dados da resposta. Exceção: `B4 06` → `66`.
- Nível: **V1**. Conferido em 651 respostas da central.

Exemplos:

| Quadro | Conta | Evidência |
| --- | --- | --- |
| Pedido `82 0F 6E` | `0x82 + 0x0F = 0x91`; `0xFF − 0x91 = 0x6E` | `162909:12` |
| Resposta `C2 0F 2E` | `0xC2 + 0x0F = 0xD1`; `0xFF − 0xD1 = 0x2E` | `162909:13` |
| Pedido `83 11 00 6B` | `0x83 + 0x11 + 0x00 = 0x94`; `0xFF − 0x94 = 0x6B` | `162909:48` |
| Resposta `C4 33 59 3A 75` | `0xC4 + 0x33 + 0x59 + 0x3A = 0x18A`; `0xFF − 0x8A = 0x75` | `162909:1098` |

Os pedidos completos no cabo do scanner são, por exemplo,
`80 06 04 01 03 82 0F 6E 9D 9E` (conteúdo `04 01 03` + quadro `82 0F 6E`, CRC `9D 9E`).

### 2.2 Sequência de conexão e manutenção

Nível: **V1**.

1. `18` (versão do scanner). `153551:3`.
2. `13 07 03 00`. Se vier `7F 13 07`, mandar `28 04 25 1D 48 FF` e repetir o `13`.
   `162909:2265` a `162909:2271`.
3. `07 00 04 00 00 00 68 3C 0B 05`. `161237:10`.
4. `0F` à central, até 3 tentativas, parando na primeira que responde. Repetir depois de
   responder faz a próxima falhar com `7F 04 13`: `162909:12` (responde) a `162909:21`
   (o `11 00` seguinte falha).
5. Se nenhuma tentativa respondeu: inicialização lenta `07 31 84 10 …` (cerca de 2,4 s).
   `161400:24`. Depois disso a central responde.
6. Manutenção: mandar `00` (`82 00 7D` → `C2 00 3D`) se passou mais de 1 a 1,5 s sem conversa. A
   ferramenta de referência manda a cada cerca de 1,7 s (144 vezes em
   `capturas/ref/20261025-motor-ligado.jsonl`). Sem isso a central larga a sessão.

---

## 3. Comandos de LEITURA

Estes comandos só leem. Não mudam o estado do carro.

### 3.1 `0F` — início / eco — **LEITURA**

- Pedido: `82 0F 6E`. Resposta: `C2 0F 2E`.
- Exemplo: `162909:12` (tx `80 06 04 01 03 82 0F 6E 9D 9E`), resposta
  `80 04 84 C2 0F 2E 29 74`.
- Nível: V1.

### 3.2 `00` — manutenção — **LEITURA**

- Pedido: `82 00 7D`. Resposta: `C2 00 3D`.
- Exemplo: tx `80 06 04 01 03 82 00 7D 4F 3F`, rx `80 04 84 C2 00 3D FB D5`
  (`capturas/ref/20261025-motor-ligado.jsonl`).
- Nível: V1.

### 3.3 `33` — ler falhas — **LEITURA**

- Pedido: `82 33 4A`. Resposta: `33` seguido de pares de bytes.
- Cada par `v` (16 bits, big-endian, diferente de zero):
  - código = `(v >> 3) & 0x1FF`;
  - tipo = `v & 7`;
  - bit 13 (`0x2000`): `1` → falha **memorizada**; `0` → falha **presente**;
  - bits 12, 14 e 15: o 14 (`0x4000`) estava ligado em todos os exemplos e o 15 desligado;
    significado pendente. (O bit 12 é parte do código quando ele passa de 255: em `0x593A`,
    código 295.)
- Em todas as respostas observadas os pares consomem todos os dados depois do `33`; não há
  byte sobrando antes do checksum.
- Se o primeiro byte da resposta vier `32` em vez de `33`, tratar como "sem resposta válida".
  **Pendente**: nunca visto no carro.
- Nível: V1.

Exemplos reais:

| Evidência | Quadro da central | Decodificação |
| --- | --- | --- |
| `161400:28` | `C6 33 61 51 61 49 AA` | `0x6151`: código `0x2A` = 42, tipo 1, memorizada. `0x6149`: código `0x29` = 41, tipo 1, memorizada. |
| `162909:258` | `C8 33 61 51 59 3A 61 49 15` | As duas acima, e `0x593A`: código `0x127` = 295, tipo 2, **presente**. |
| `162909:1098` | `C4 33 59 3A 75` | Depois do `C3` (apagar): só a 295, ainda presente. |

Passo a passo de `0x593A`: `0x593A >> 3 = 0xB27`; `0xB27 & 0x1FF = 0x127 = 295`;
`0x593A & 7 = 2`; `0x593A & 0x2000 = 0` (presente).

### 3.4 Falhas confirmadas

Confirmadas por captura **e** pela tela da ferramenta de referência mostrando o mesmo ao mesmo
tempo. Nível V1.

| Código (dec / hex) | Significado | Tipo observado | Evidência |
| --- | --- | --- | --- |
| 41 / `029` | sensor de detonação | 1 = coerência (memorizada) | `161400:28` |
| 42 / `02A` | sonda lambda | 1 = coerência (memorizada) | `161400:28` |
| 295 / `127` | regulação de riqueza (a central não consegue manter a mistura) | 2 = curto ao positivo (presente) | `162909:258` |

Tipos confirmados: **1 = coerência**, **2 = curto-circuito ao positivo**. Os tipos 0 e 3 a 7 são
**pendentes**.

### 3.5 `11 00` e `11 01` — blocos de parâmetros — **LEITURA**

- Pedidos: `83 11 00 6B` (bloco 0) e `83 11 01 6A` (bloco 1).
- Respostas: `11 <bloco>` seguido dos bytes. O bloco 0 tem 27 bytes de dados (contando o eco)
  e o bloco 1 tem 21.
- Exemplo do bloco 0 (`162909:48`, resposta `C0+28 = DC`):
  `11 00 0C 2F 1A 80 20 10 3A 8A 73 CA 16 6D C0 56 30 54 28 00 C8 83 45 8C 2D 01 00`
- Exemplo do bloco 1 (`162909:51`, resposta `D6`):
  `11 01 0C 19 1A 80 20 10 01 84 0F 80 03 4E 03 4B 11 03 00 A0 59`
- Taxa: cerca de 3,3 leituras por segundo com os dois blocos.
- Nível: V1.

#### Parâmetros (blocos 0 e 1)

Índice `d[i]` = posição em `dados` contando o eco: `d[0]` = `11`, `d[1]` = número do bloco.
Valores de "Evidência" conferidos no CSV `registros/20261025-163040.csv` e em `162909` (323
leituras de cada bloco, motor ligado e quente, em marcha lenta). Os valores de motor parado vêm
das respostas aos blocos em `capturas/ref/20261025-motor-parado.jsonl` (59 leituras
do bloco 0 e 64 do bloco 1, motor parado e frio); por exemplo, bloco 0
`11 00 00 00 00 80 80 00 72 CA 72 CA 7F 7E A0 2F 2F 2E 28 00 C8 C3 45 01 00 00 00`.

| # | Parâmetro | Bloco | Byte(s) | Conversão | Unid. | Nível | Evidência |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Rotação | 0 | `d[4]` | ×32 | rpm | V2 | bruto 24 a 27 = 768 a 864 rpm. Parado: bruto 0 (`ref/20261025-motor-parado.jsonl`). |
| 2 | Bateria | 0 | `d[14]` | ×0,073 | V | V2 | bruto 187 a 195 = 13,7 a 14,2 V. Parado: bruto 160 = 11,7 V (`ref/20261025-motor-parado.jsonl`). |
| 3 | Avanço de ignição | 1 | `d[11]` | ×0,375 − 36 | ° | V2 | bruto 117 a 174 = 7,9 a 29,3°, variando. |
| 4 | Carga do motor | 0 | `d[2..3]` (16 bits big-endian) | ×0,00851 | % | V2 | bruto 2930 a 3179 = 24,9 a 27,1 %. |
| 5 | Estado do pedal/borboleta | 0 | `d[7]` (byte inteiro) | `16` = marcha lenta | — | V1 | só o valor 16 foi observado. Os valores 0 e 64 são pendentes. |
| 6 | Abertura da borboleta | 0 | `d[18]` | ×0,39 | ° | V1, suspeito | bruto 40 a 41 = 15,6 a 16,0° em repouso, contra 11 a 12° esperados. |
| 7 | Pressão de admissão (MAP) | 0 | `d[8..9]` (16 bits) | ×0,02994 | mbar | V2 | bruto 14186 a 15178 = 425 a 454 mbar em marcha lenta. Parado: 864 a 880 mbar, perto da atmosférica (`ref/20261025-motor-parado.jsonl`). |
| 8 | Pressão atmosférica | 0 | `d[10..11]` (16 bits) | ×0,02994 | mbar | V2 | bruto 29642 constante = 887,5 mbar. |
| 9 | Temperatura da água | 0 | `d[12]` | curva não linear (termistor) | °C | V2 na tendência | bruto 20, 21, 22 = 99, 98, 96 °C. **Curva pendente**: coeficientes a medir com termômetro. |
| 10 | Temperatura do ar | 0 | `d[13]` | mesma curva | °C | V2 na tendência | bruto 107, 108, 109 = 27, 26, 26 °C. Curva pendente. |
| 11 | Carga da bobina 1-4 | 1 | `d[12..13]` (16 bits) | ×0,004 | ms | V1 | bruto 846 a 1595 = 3,4 a 6,4 ms. Sem referência. |
| 12 | Carga da bobina 2-3 | 1 | `d[14..15]` (16 bits) | ×0,004 | ms | V1 | bruto 843 a 1591 = 3,4 a 6,4 ms. |
| 13 | Tempo de injeção | 1 | `d[8..9]` (16 bits) | ×0,004 | ms | V2 | bruto 364 a 427 = 1,46 a 1,71 ms em marcha lenta. |
| 14 | Motor de passo da marcha lenta | 1 | `d[16]` | ×1 | passos | V2 | 13 a 37 quente. Parado e frio: 98 a 99 (`ref/20261025-motor-parado.jsonl`). |
| 15 | Sonda lambda | 0 | `d[17]` | ×9,8 | mV | V1 | bruto 84 a 88 = 823 a 862 mV; 755 a 853 mV em `ref/20261025-motor-ligado.jsonl`. Carro com a falha 295 presente (a sonda parece presa em tensão alta). Parado: 451 mV. |
| 17 | Velocidade | 0 | `d[19]` | ×1 | km/h | V1 | sempre 0 (carro parado). |
| 18 | Relé principal | 1 | `d[19]` bit 5 | 0/1 | — | V1 | sempre 1. |
| 19 | Alimentação da central | 1 | `d[20]` bit 0 | 0/1 | — | V1 | sempre 1. |
| 20 | Luz de aviso da injeção | 1 | `d[20]` bit 3 | 0/1 | — | V1 | sempre 1, com a 295 presente. |
| 21 | Termistor do A/C | 0 | `d[20]` bit 2 | 0/1 | — | V1 | sempre 0. |
| 22 | Autorização do A/C | 0 | `d[24]` bit 6 | 0/1 | — | V1 | sempre 0. |

Notas:

- O parâmetro 16 não consta: a fonte que temos aponta para o mesmo byte do motor de passo e, no
  CSV, os dois valores brutos são sempre iguais; portanto o byte da purga do canister é
  desconhecido (pendente).
- Os bytes `d[5]` e `d[6]` dos dois blocos (`80 20` com motor ligado, `80 80` parado) e outros
  bytes sem parâmetro são de significado desconhecido (pendente). Os bits de `d[19]` do bloco 1 fora do bit 5
  variam (valores brutos de 32 a 244 em `162909`); significado pendente.
- O parâmetro 18 usa o bit 5 (`0x20`) de `d[19]`: `0xA0` e `0x20` têm o bit ligado.

Exemplo de decodificação (`162909:48` e `162909:51`; CSV, primeira linha de dados):

| Parâmetro | Bytes | Bruto | Resultado |
| --- | --- | --- | --- |
| Rotação | `d[4]` = `1A` | 26 | 832 rpm |
| Bateria | `d[14]` = `C0` | 192 | 14,0 V |
| Carga | `d[2..3]` = `0C 2F` | 3119 | 26,5 % |
| MAP | `d[8..9]` = `3A 8A` | 14986 | 448,7 mbar |
| Temp. água | `d[12]` = `16` | 22 | 96 °C (pela curva) |
| Lambda | `d[17]` = `54` | 84 | 823 mV |
| Tempo de injeção (bloco 1) | `d[8..9]` = `01 84` | 388 | 1,55 ms |
| Avanço (bloco 1) | `d[11]` = `80` | 128 | 12° |
| Motor de passo (bloco 1) | `d[16]` = `11` | 17 | 17 passos |

---

## 4. Comandos de ESCRITA

> **RISCO.** Os comandos desta seção **mudam o estado do carro**.
>
> - Use só com a condição adequada: carro parado, local seguro, motor no estado indicado.
> - Um atuador acionado **pode não parar sozinho**. O comando de parada pode não ser conhecido.
> - Apagar falhas **destrói informação de diagnóstico**. Anote antes.
> - Nunca com o carro andando.
> - Ninguém que contribuiu para este documento se responsabiliza por danos ao carro, ao
>   equipamento ou a pessoas. Você é responsável pelo que enviar.

### 4.1 `C3` — apagar falhas — **ESCRITA**

- Pedido: `82 C3 BA`. Resposta: `C2 C3 7A` (eco).
- Exemplo: `162909:993`, tx `80 06 04 01 03 82 C3 BA 3E 6D`, rx `80 04 84 C2 C3 7A 82 03`.
- Validado **uma vez**, com o motor ligado. Depois do `C3`, a leitura `33` (`162909:1098`)
  trouxe só a 295 (presente); as memorizadas 41 e 42 sumiram.
- Nível: V1.

### 4.2 `B4 06` — acionar a luz de aviso da injeção — **ESCRITA**

- Pedido: `83 B4 06 C2`. Resposta: `C2 66 D7` (dados `66`; não é eco).
- Exemplo: `162909:2289`, tx `80 07 04 01 04 83 B4 06 C2 3D FF`, rx `80 04 84 C2 66 D7 2A AC`.
- Efeito visto: a luz pisca e **não para sozinha** (mais de 30 s). Parou só ao desligar a chave.
  Comando de parada: **desconhecido**.
- Nível: V1.

### 4.3 Forma geral `B4 nn` — **ESCRITA**

- Aciona um atuador identificado pelo segundo byte (`nn`). Pedido: `83 B4 nn <cs>`.
- Só `B4 06` foi enviado ao carro e documentado. **Os outros valores de `nn` não estão
  documentados de propósito**: não há captura que os valide e alguns acionam componentes
  perigosos. Não experimente valores.

Nada de gravação de configuração da central está documentado.

---

## Pendente de validação por captura

| Item | O que se sabe | O que falta capturar |
| --- | --- | --- |
| Comando `35 05 0A` | A ferramenta de referência manda ao abrir. Resposta: 10 palavras de 16 bits de ~1242. Não é a tensão da bateria. | Variar condições (motor parado, ligado, acelerando) e ver qual palavra muda. |
| Bytes de `28 04 25 1D 48 FF` | Libera o `13` quando recusado com `7F 13 07`. | Variar os bytes numa ferramenta de referência (ou achar a documentação) para saber o que cada um faz. |
| Tempos do `07` | O 7º byte é baud ÷ 100. Os outros (`3C 0B 05`, `3C 6E 01`, `32 32 05`, `4B 19 05`…) são tempos. | Capturas com valores diferentes e o efeito observável em cada um. |
| Curva da temperatura da água e do ar | Bruto 20/21/22 = 99/98/96 °C; 107/108/109 = 27/26/26 °C. Não é linear. | Leituras de 20 a 100 °C com termômetro independente. |
| Byte da purga do canister | Não é `d[16]` do bloco 1 (igual ao do motor de passo). | Leitura com a válvula acionada e ver qual byte muda. |
| Estados 0 e 64 do pedal | Só 16 (marcha lenta) foi visto. | Pedal solto, meio, a fundo, com leitura contínua. |
| Fator da borboleta | 16° em repouso contra 11 a 12° esperados. | Pedal em várias aberturas com referência. |
| Velocidade | Sempre 0 (carro parado). | Leituras com o carro em movimento, com referência (GPS). |
| Bits de relé, alimentação, luz de aviso e A/C | Sem variação nas capturas. | Capturas ligando o A/C, desligando a luz, mudando o relé. |
| Faixa completa da lambda (0,1 a 0,9 V) | Só 823 a 862 mV (sonda presa, com a 295 presente). | Carro com sonda boa, variando a mistura. |
| Tipos de falha 0 e 3 a 7 | Só 1 (coerência) e 2 (curto ao positivo). | Provocar outras falhas controladas e ler. |
| Resposta `32` ao `33` | Nunca vista. | Capturar o caso e anotar a condição. |
| Bits 14 e 15 do par de falha | 14 sempre ligado, 15 sempre desligado nos exemplos. | Mais exemplos de falhas. |
| Parar um atuador | `B4 06` não para sozinho; comando de parada desconhecido. | Captura da ferramenta de referência parando um atuador. |
| Outros atuadores | Não documentados. | Captura da ferramenta de referência acionando cada um, com a condição segura. |
| Significado de `7F 13 1A` | Pinos `00`, `02`, `0D`. | Comparar com mais pinos e outras centrais. |
| Quadros com conteúdo maior que 255 bytes no scanner | Nenhum visto. | Uma captura com resposta longa. |
| Bytes sem parâmetro nos blocos | `d[5]`, `d[6]` e outros bits. | Variação controlada de condições. |

### Códigos de falha não confirmados

Os nomes são **prováveis, não confirmados**: não há captura que mostre o código com a tela da
ferramenta de referência. Tipos 0 e 3 a 7 também pendentes.

| Código | Nome provável (não confirmado) |
| --- | --- |
| 33 | sensor de temperatura do ar |
| 34 | sensor de temperatura da água |
| 35 | potenciômetro da borboleta |
| 36 | sensor de velocidade |
| 38 | sensor de pressão do coletor (MAP) |
| 40 | sensor de rotação |
| 44 | sensor de referência do cilindro 1 |
| 65 | informação da direção assistida |
| 69 | informação do piloto automático |
| 161 | relé da bomba de combustível |
| 165 | motor de passo da marcha lenta |
| 170 | eletroválvula de purga do canister |
| 171 | relé do aquecimento da sonda lambda |
| 176 | relé do ar-condicionado |
| 193 | injetor 1-4 |
| 194 | injetor 2-3 |
| 195 | injetor 3-4 |
| 199 | relé do ar-condicionado (2º código) |
| 225 | bobina 1-4 |
| 226 | bobina 2-3 |
| 227 | bobina 3-4 |
| 290 | regulação da marcha lenta |
| 291 | autoadaptação da riqueza (limite) |
| 292 | autoadaptação da riqueza (limite) |
| 293 | regulação anti-detonação |
| 385 | central (falha interna) |
| 386 | programação da central |
| 387 | central: comando de ignição |
| 388 | central: módulo de detonação |
| 417 | tensão da bateria |

---

## Dicas de oficina

Não fazem parte do protocolo. É conhecimento geral de mecânica, sem validação por captura.

- Antes de mexer: anote todas as falhas lidas (presentes e memorizadas) e só então apague.
- Meça a tensão da bateria. Abaixo de ~12 V, leituras e testes ficam instáveis.
- Falha **memorizada** pode ter sido causada por um mau contato antigo. Apague, rode o carro e
  veja se volta como **presente**.
- Para falhas de sensor, comece pelo conector (limpeza, pino frouxo) e pelo chicote, depois
  meça o sensor com multímetro.
- Falhas de riqueza (como a 295) aparecem junto com problemas de sonda, vazamento de ar,
  combustível ou ignição. Conferir a sonda e entradas de ar falso antes de trocar peças.
- Nunca troque peças só pelo código: o código aponta o circuito, não a peça.

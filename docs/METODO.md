# Como descobrir e validar a comunicação com uma central pela linha K

Este documento descreve um método reproduzível para descobrir, passo a passo, como
conversar com uma central de injeção pela linha K usando um scanner Bluetooth barato, e
como validar o que foi descoberto. Ele não conta a história do projeto.

Cada passo tem duas partes:

- **Genérico**: vale para qualquer central na linha K.
- **Na SL96**: os valores reais da Sagem SL96 de um Peugeot 306 1.8 16v (motor XU7JP4),
  com a captura que mostra o fato. Os bytes completos estão em
  [PROTOCOLO-SL96.md](PROTOCOLO-SL96.md).

## Notação

- Bytes em hexadecimal, separados por espaço: `80 01 18 31 4F`.
- As capturas da própria ferramenta ficam em `capturas/20261025-HHMMSS.jsonl`. Nos
  exemplos, `161237:7` quer dizer `capturas/20261025-161237.jsonl`, linha 7.
  Quando o exemplo é uma requisição, a linha citada é a do `tx`; a resposta vem nas linhas
  seguintes.
- `capturas/ref/*.jsonl` são capturas Bluetooth do tráfego de uma ferramenta de
  diagnóstico comercial para PSA (a "ferramenta de referência") falando com a mesma central.
- `registros/20261025-163040.csv` é uma gravação de 323 leituras de parâmetros.

## Níveis de confirmação

Todo fato registrado recebe um nível.

| Nível | Significado |
| --- | --- |
| **V1** | Comando e resposta aparecem byte a byte numa captura do carro. |
| **V2** | V1, e o valor varia de forma coerente com o que o carro fazia (parado × ligado, frio × quente, marcha lenta), e o valor convertido é fisicamente plausível. |
| **V3** | Valor conferido com uma referência independente (multímetro, termômetro, conta-giros). |
| **Pendente** | Não há captura que confirme. Vai para a lista de pendências. |

---

## 1. Hardware e montagem

**Genérico**

- Um scanner Bluetooth com perfil SPP (porta serial). Ele faz a ponte entre o Bluetooth e a
  linha K do carro.
- Tomada de diagnóstico (OBD). A linha K sai num pino que depende do fabricante e da central.
- Com a ignição em ON a tomada já recebe alimentação. O motor não precisa estar ligado.
- Pareie o scanner no sistema operacional (o PIN costuma ser `1234`).
- **Linux**: abra um socket RFCOMM direto no MAC do scanner, canal 1. Não precisa de
  `rfcomm bind` nem de root.
- **Android**: o aparelho mostra o scanner só como "pareado", nunca "conectado". Isso é normal
  para SPP; a conexão só existe enquanto o app mantém o socket aberto.

**Na SL96**

- Linha K no **pino 7** da tomada OBD.
- O scanner aparece pareado como "AutoScan&Programming" (ou "OBDII", "PSACOM"), PIN `1234`.
- O scanner responde ao primeiro comando de versão (ver passo 3), o que confirma que o
  canal serial está de pé: `161237:3` (`80 01 18 31 4F`), resposta `80 05 98 06 00 30 64 C5 A2`.

## 2. Como capturar

Você precisa de dois tipos de captura.

### 2.1 Tráfego da sua própria ferramenta

**Genérico**

- Grave todo byte enviado e recebido, com carimbo de tempo e sentido. Um formato simples é
  uma linha JSON por evento: `{"t", "hora", "dir": "tx|rx|nota", "hex"}`.
- Grave também notas ("ler falhas", "apagar") para saber o que você estava tentando.
- O Bluetooth entrega os bytes em pedaços. Uma resposta pode chegar em duas linhas (um
  `80`, depois o resto). Remonte pelo campo de tamanho antes de analisar.

**Na SL96**

- Exemplo de resposta fragmentada: `153551:4` e `153551:5` (`80`, depois
  `05 98 06 00 30 64 C5 A2`).

### 2.2 Tráfego de uma ferramenta comercial conversando com a SUA central

Isto é o atalho mais poderoso: você vê o que uma ferramenta que funciona manda e recebe.

**Genérico (Linux)**

1. Pareie o scanner no PC.
2. Rode `btmon -w arquivo.btsnoop` num terminal.
3. Use a ferramenta de referência normalmente (conectar, ler falhas, ler parâmetros). Se ela
   roda em outro sistema, use um emulador ou uma máquina virtual que enxergue o Bluetooth do PC.
4. Pare o `btmon`. Para ler, `btmon -r arquivo.btsnoop`; para analisar com as mesmas ferramentas
   da sua captura, converta: `python3 psascan/btsnoop_para_jsonl.py arquivo.btsnoop saida.jsonl`.
5. Os dados SPP aparecem como pacotes ACL/RFCOMM. Extraia o conteúdo dos pacotes e
   monte uma lista de pares pedido/resposta.

Cuidado prático: o **ModemManager** do Linux manda `AT\r` em portas seriais novas e ocupa o
dispositivo RFCOMM. Pare-o durante a captura (`systemctl stop ModemManager`) e religue depois.

Capture em condições diferentes e guarde cada arquivo com um nome que diga a condição
(`motor-parado`, `motor-ligado`).

**Na SL96**

- `capturas/ref/20261025-motor-parado.jsonl` e `capturas/ref/20261025-motor-ligado.jsonl`.
- Nelas aparecem 144 mensagens de manutenção (`00`) com o motor ligado e 77 com o motor
  parado, além de toda a varredura do passo 4.

## 3. Descobrir o envelope do scanner e o CRC

**Genérico**

O scanner costuma embrulhar cada mensagem num envelope próprio. Para reconhecê-lo, junte
todos os quadros de uma captura e olhe:

1. **Primeiro byte constante?** Se todo quadro começa igual, é cabeçalho.
2. **Segundo byte = tamanho?** Ordene os quadros por comprimento. Se o segundo byte mais o
   que sobra no começo e no fim dá o tamanho total, achou o campo de tamanho.
3. **Dois bytes finais variam muito?** Provavelmente CRC de 16 bits. Os dois mais comuns são
   CRC-16/X-25 (também chamado CRC-16/IBM-SDLC) e CRC-16/CCITT-FALSE.
4. **Confirme o CRC.** Com 3 ou 4 quadros completos, uma ferramenta como o **CRC RevEng**
   (`reveng`, modo de busca com largura 16) devolve o modelo. Confira a sintaxe no manual da
   versão que você usa. Ou teste os modelos conhecidos num script seu.
5. Descubra a ordem dos bytes do CRC (aqui, byte baixo primeiro) e se o CRC cobre o
   cabeçalho ou só o conteúdo.
6. Veja o que é o primeiro byte do conteúdo: aqui ele é o **comando** do scanner. Na resposta,
   é o mesmo comando com o bit alto ligado (`| 0x80`). Uma recusa tem outro formato (`7F …`).

Definição do CRC-16/X-25 (polinômio refletido `0x8408`, valor inicial `0xFFFF`, complemento
no fim):

```python
def crc16_x25(dados: bytes) -> int:
    c = 0xFFFF
    for b in dados:
        c ^= b
        for _ in range(8):
            c = (c >> 1) ^ 0x8408 if c & 1 else c >> 1
    return c ^ 0xFFFF
```

Teste de sanidade do modelo: `crc16_x25(b"123456789") == 0x906E`.

**Na SL96**

- Quadro: `80 <tamanho> <conteúdo…> <crc_lo> <crc_hi>`. O `<tamanho>` conta só o conteúdo.
- CRC-16/X-25 sobre cabeçalho + conteúdo, byte baixo primeiro. Conferido em todos os 855
  quadros enviados e 844 recebidos das capturas de 25/10/2026 (os arquivos
  `capturas/20261025-*.jsonl`, exceto o do simulador).
- Exemplo calculável. Quadro de versão: `80 01 18`. O CRC é `0x4F31`, enviado como `31 4F`:
  `80 01 18 31 4F` (`153551:3`). Resposta: `80 05 98 06 00 30 64 C5 A2` (conteúdo
  `98 06 00 30 64`; `98` = `18 | 0x80`).
- Outro: `80 04 13 07 03 00` → CRC `0x9F3C` → `3C 9F` (`161237:7`).
- Recusa: `80 06 7F 13 07 00 00 00 79 16` (`161546:6`).

## 4. Descobrir pino, velocidade, endereço e inicialização

**Genérico**

Uma central na linha K precisa de três escolhas: **qual pino** da tomada, **qual velocidade**
(normalmente 10400 ou 9600 baud) e **como acordá-la** (inicialização lenta a 5 baud com o
endereço da central, ou inicialização rápida).

Como descobrir sem adivinhar:

1. Capture a ferramenta de referência **no começo da conexão**, quando ela ainda não sabe com
   qual central fala. Ela faz uma varredura.
2. Liste os comandos únicos do scanner (primeiro byte do conteúdo) e quantas vezes cada um
   aparece. Os que se repetem com argumentos diferentes são a varredura.
3. Identifique o que varia entre as tentativas: um byte de pino, um byte de velocidade
   (baud ÷ 100), um byte de endereço.
4. Veja **qual tentativa vem seguida de tráfego normal** com a central. É a combinação certa.
5. Repita a combinação na sua ferramenta e confirme que a central responde.
6. Se a central não responde ao primeiro pedido, tente a inicialização lenta (5 baud) com o
   endereço. A central responde depois de uns 2 a 3 segundos, com uma mensagem de identificação.

**Na SL96**

Varredura da ferramenta de referência com o motor parado
(`capturas/ref/20261025-motor-parado.jsonl`):

| Parte da varredura | Bytes do scanner | Observação |
| --- | --- | --- |
| Escolher pino | `13 <pino> 03 00`, com pino `00`, `02`, `06`, `07`, `0B`, `0C`, `0D` | Pinos `00`, `02` e `0D` voltam `7F 13 1A`. Pinos `07`, `0B` e `0C` voltam `7F 13 00`. |
| Velocidade | `07 00 08 00 00 00 60 …` (9600) e `07 00 04 00 00 00 68 …` (10400) | O byte `60` = 96, o byte `68` = 104: baud ÷ 100. |
| Acordar | `07 31 84 <endereço> 00 00 00 3C 6E 01`, com endereço `10`, `A4`, `B3`, `B9` | Mesmo comando `07`, mas com `31` no segundo byte. |
| Outros | `1E …`, `1F …`, `3E …` | Provavelmente outros barramentos; não decifrados. |

A combinação que levou à leitura de falhas e parâmetros foi: **pino 7, 10400 baud, endereço 10**.

- Pino: `80 04 13 07 03 00 3C 9F` → `7F 13 00 …` (`161237:7`).
- Velocidade: `80 0A 07 00 04 00 00 00 68 3C 0B 05 89 E7` → `7F 07 00 …` (`161237:10`).
- Primeiro pedido à central: `0F` (ver passo 5) → resposta (`162909:12`).
- Quando a central não responde ao primeiro pedido, a inicialização lenta resolve:
  `80 0A 07 31 84 10 00 00 00 3C 6E 01 07 FD` (`161400:24`). Cerca de 2,4 s depois chega
  `80 21 87 …` com o texto ASCII `SL964` dentro (`53 4C 39 36 34`).

Ordem obrigatória: o comando de velocidade (`07`) só funciona depois do de pino (`13`). Sem
ele, a resposta é `7F 07 07` (`153551:7`).

Nota sobre o `13`: às vezes o scanner recusa o pino com `7F 13 07` (`161546:6`, `162909:2265`).
Nesses casos, o comando `28 04 25 1D 48 FF` (`162909:2268`), resposta `A8 00 00`, e a
repetição do `13` funcionou (`162909:2270`, resposta `7F 13 00`). O significado dos bytes do `28` é desconhecido.

## 5. Descobrir o formato dos quadros da central

**Genérico**

Os pedidos à central viajam **dentro** de um comando do scanner (aqui, "repassar à central").
Para descobrir o formato:

1. Isole os pedidos curtos. O primeiro pedido é pequeno e se repete (`0F` aqui).
2. Procure um **cabeçalho com o tamanho** no primeiro byte. Em protocolos de linha K de baixa
   velocidade é comum o primeiro byte carregar o número de bytes (por exemplo, `0x80 + n`).
3. Procure um **checksum por soma**: some todos os bytes do quadro. Se o total fecha sempre
   no mesmo valor (aqui `0xFF`, módulo 256), é isso.
4. Confira se a central **ecoa o comando** no começo da resposta. Isso ajuda a casar
   respostas com pedidos.
5. Pergunte à central coisas diferentes (comandos de leitura que a ferramenta de referência
   usa) e veja o que se mantém.

**Na SL96**

- Pedido: `[0x80 + 1 + n_dados] dados… [cs]`. Resposta: `[0xC0 + 1 + n_dados] dados… [cs]`.
  O checksum é `0xFF − (soma de cabeçalho e dados) & 0xFF`; a soma de tudo, com o `cs`, dá `0xFF`.
- Exemplo: pedido `82 0F 6E` (0x82 + 0x0F + 0x6E = 0xFF). Resposta `C2 0F 2E`
  (0xC2 + 0x0F + 0x2E = 0xFF). O pedido vai dentro do `04 01 <tamanho> …` do scanner:
  `80 06 04 01 03 82 0F 6E 9D 9E` (`162909:12`); a resposta chega como
  `80 04 84 C2 0F 2E 29 74` (`84` + quadro da central).
- A central ecoa o comando: `0F` → `0F`, `11 00` → `11 00 …`, `33` → `33 …`, `C3` → `C3`.
  Exceção: `B4 06` → `66` (`162909:2289`).
- O endereço da central não aparece nos quadros; só na inicialização lenta.
- Se a central não responde, o scanner devolve `7F 04 13` (`162909:21`).

## 6. Manter a sessão

**Genérico**

Muitas centrais encerram a sessão se ficam um tempo sem tráfego. Observe na captura da ferramenta
de referência uma mensagem **periódica e sempre igual** quando o usuário não faz nada. Ela é a
mensagem de manutenção. Meça o intervalo e copie com uma margem de segurança.

**Na SL96**

- Mensagem `00`: `82 00 7D` → resposta `C2 00 3D`. Na ferramenta de referência: a cada
  cerca de 1,7 s (`capturas/ref/20261025-motor-ligado.jsonl`, 144 ocorrências).
- Regra usada: mandar `00` se passou mais de 1 a 1,5 s sem conversa. Sem isso a central larga a sessão.
- Cuidado: depois que a central responde ao `0F`, repetir o `0F` faz o pedido seguinte falhar
  com `7F 04 13`: ver `162909:12` até `162909:21`. Pare na primeira resposta.

## 7. Mapear parâmetros por variação controlada

**Genérico**

Parâmetros (rotação, temperatura, pressão…) vêm em blocos de bytes. O método é **mudar uma
coisa por vez no carro e ver qual byte muda**.

1. Peça o bloco de parâmetros várias vezes por segundo e guarde cada leitura num CSV ou jsonl.
2. Faça uma tabela posição × valor ao longo do tempo. Bytes **constantes** são configuração
   ou campos sem uso. Bytes que **oscilam um pouco** são sensores.
3. Mude uma condição por vez e anote a hora:
   - motor parado × ligado;
   - frio × aquecendo × quente;
   - acelerar e soltar;
   - pedal pisado × solto;
   - ligar consumidores (ar-condicionado, faróis, ventoinha).
4. Procure quem mudou junto com a condição. Use a tela da ferramenta de referência ao
   mesmo tempo, quando possível, para saber o que esperar.
5. **Palavras de 16 bits**: se dois bytes vizinhos mudam juntos e o primeiro só muda quando o
   segundo dá a volta (255 → 0), é um valor de 16 bits (aqui, big-endian).
6. **Bits**: um byte que só assume poucos valores e alterna entre eles é um conjunto de
   bits (relé ligado/desligado). Compare em binário.
7. **Fator e offset com dois pontos de referência**: tendo dois pares `(bruto, valor)`,
   `fator = (v2 − v1) / (b2 − b1)` e `offset = v1 − fator × b1`. Valide com um terceiro ponto.
   Se os pontos não ficam numa reta, a conversão é uma curva (termistores são assim).
8. Registre o nível de cada parâmetro (V1, V2, V3). Um parâmetro só chega a V3 com
   referência independente.

**Na SL96**

Dois blocos de parâmetros: `11 00` (bloco 0, 27 bytes de dados contando o eco) e `11 01`
(bloco 1, 21 bytes). Índice `d[i]` = posição nos dados, contando o eco: `d[0]` = `11`,
`d[1]` = número do bloco.

Bloco 0 real (`162909:48`, resposta em `162909:49`):

```
11 00 0C 2F 1A 80 20 10 3A 8A 73 CA 16 6D C0 56 30 54 28 00 C8 83 45 8C 2D 01 00
```

| Parâmetro | Byte(s) | Bruto | Conversão | Resultado |
| --- | --- | --- | --- | --- |
| Rotação | `d[4]` = `1A` | 26 | ×32 | 832 rpm |
| Carga do motor | `d[2..3]` = `0C 2F` | 3119 | ×0,00851 | 26,5 % |
| Pressão de admissão | `d[8..9]` = `3A 8A` | 14986 | ×0,02994 | 448,7 mbar |
| Pressão atmosférica | `d[10..11]` = `73 CA` | 29642 | ×0,02994 | 887,5 mbar |
| Bateria | `d[14]` = `C0` | 192 | ×0,073 | 14,0 V |

A mesma leitura aparece no CSV (`registros/20261025-163040.csv`, linha de dados 1: RPM 832,
bateria 14,0 V, carga 27 %, MAP 449 mb).

Exemplo de **dois pontos**: a rotação. Com bruto 24 e 27 a ferramenta mostra 768 e 864 rpm
(faixa do CSV). `fator = (864 − 768) / (27 − 24) = 32`, `offset = 0`.

Exemplo de **curva**: a temperatura da água. Pares do CSV (bruto → mostrado): 20 → 99 °C,
21 → 98 °C, 22 → 96 °C. Os passos de 1 e 2 °C por unidade mostram que não é uma reta. Os
coeficientes da curva são **pendentes** e devem ser medidos com um termômetro.

Exemplo de **bits**: o byte `d[20]` do bloco 1 vale `0x59` (`0101 1001`). O bit 0 é a
alimentação da central e o bit 3 é a luz de aviso da injeção; ambos em 1. Falta capturá-los mudando de estado.

Taxa: cerca de 3,3 leituras por segundo com os dois blocos. Só o bloco 0 é mais rápido.

A tabela completa de parâmetros está em [PROTOCOLO-SL96.md](PROTOCOLO-SL96.md#parâmetros-blocos-0-e-1).

## 8. Mapear falhas

**Genérico**

1. Primeiro, leia as falhas **sem apagar nada** e compare com a ferramenta de referência. Guarde os bytes.
2. Para ligar um código a uma causa conhecida, **provoque uma falha controlada**. Exemplo
   seguro: com a ignição em ON e o **motor parado**, desconecte um sensor que não influencia a
   segurança (um sensor de temperatura, por exemplo). Leia as falhas e veja o que apareceu.
   Reconecte e leia de novo.
3. Compare o par de bytes que apareceu com o código que a ferramenta de referência mostra.
   Escreva os dois lado a lado em binário e procure campos: 3 bits baixos para o tipo, 9 bits seguintes para o código, bits altos para "presente" ou "memorizada".
4. Teste a decodificação com todas as falhas que você já tem.
5. **Apagar**: só depois de anotar tudo. Apague, leia de novo e veja que as falhas
   memorizadas sumiram e as presentes continuam.
6. Não saia provocando falhas em atuadores ou em sensores críticos.

**Na SL96**

Resposta do comando `33`: `33` seguido de pares de bytes. Cada par `v` (16 bits big-endian):

- código = `(v >> 3) & 0x1FF`;
- tipo = `v & 7`;
- bit 13 (`0x2000`): `1` = memorizada, `0` = presente;
- bits 14 e 15: o 14 ligado e o 15 desligado em todos os exemplos (significado pendente).

Exemplos reais:

| Captura | Dados | Decodificação |
| --- | --- | --- |
| `161400:28` | `33 61 51 61 49` | `0x6151` = código 42, tipo 1, memorizada; `0x6149` = código 41, tipo 1, memorizada |
| `162909:258` | `33 61 51 59 3A 61 49` | as mesmas duas, e `0x593A` = código 295, tipo 2, **presente** |

Confirmado com a tela da ferramenta de referência ao mesmo tempo: 41, 42 e 295.

Apagar: `C3` → `C2 C3 7A` (eco) em `162909:993`. Em `162909:1098` a leitura seguinte traz só
`33 59 3A` (a 295, ainda presente); as memorizadas sumiram. Validado uma vez, com motor ligado.

## 9. Validação cruzada e registro

**Genérico**

- Cada fato anotado tem: o que é, os bytes, **a captura e a linha**, e o nível.
- Reconfira com uma segunda captura, de outro dia, ou com outra condição.
- Compare com a ferramenta de referência na mesma hora (uma foto da tela vale).
- Mantenha uma lista de pendências, com "o que se sabe" e "o que falta capturar". Nada de
  chute disfarçado de fato.
- Um parâmetro só sobe de nível quando há captura nova. Não apague o que ficou pendente.
- Repita as verificações automáticas: CRC do scanner em todos os quadros e checksum da central
  em todas as respostas. Se algum falhar, o quadro foi mal remontado.

**Na SL96**

- Verificação feita sobre as capturas de 25/10/2026: 855 quadros enviados e 844 recebidos com CRC
  correto; 651 respostas da central com checksum correto (soma = `0xFF`).
- Pendências: lista em [PROTOCOLO-SL96.md](PROTOCOLO-SL96.md#pendente-de-validação-por-captura).
- Atenção: as três capturas de 16:15 a 16:20 (`161546`, `161741`, `162009`) só têm tentativas
  de conexão. Os parâmetros com motor ligado vêm de `162909` (mesma sessão do CSV); os de motor
  parado e frio vêm das respostas remontadas de `capturas/ref/20261025-motor-parado.jsonl`
  (rotação 0, bateria 11,7 V, MAP 864–880 mbar, motor de passo 98–99).
- As capturas de referência foram convertidas do btsnoop com `psascan/btsnoop_para_jsonl.py`,
  que junta o conteúdo dos pacotes RFCOMM de cada sentido (as respostas longas vêm quebradas em
  vários pacotes Bluetooth) e descarta endereços e dados do computador. O resultado abre com
  `ver_captura.py` como as outras capturas.

## 10. Segurança

- **Só leitura até entender.** Leitura de falhas e de parâmetros não muda nada no carro.
- **Comandos de escrita só com captura que os mostre.** Não invente comandos nem varie bytes de
  um comando de escrita "para ver o que acontece". Use só o que uma captura da ferramenta de
  referência mostrou, ou o que está documentado e testado.
- **Nada de escrita com o carro andando** (apagar falhas, atuadores). Leitura em movimento só
  com o equipamento preso e outra pessoa operando; quem dirige não mexe na tela.
- **Atuadores podem não parar sozinhos.** Antes de acionar qualquer atuador, saiba como pará-lo,
  ou saiba que desligar a chave resolve.
- **Apagar falhas** perde informação de diagnóstico: anote antes.
- Mantenha a bateria em boas condições. Tensão baixa causa comportamentos estranhos.
- Escolha uma condição segura: carro parado, freio de mão, em local aberto.

**Na SL96**

- O acionamento da luz de aviso da injeção (`B4 06`, `162909:2289`) fez a luz piscar e **não parar
  sozinha** por mais de 30 s; parou só ao desligar a chave. O comando de parada é desconhecido.
- Os outros atuadores **não** estão documentados, de propósito, até haver captura.
- Detalhes em [PROTOCOLO-SL96.md](PROTOCOLO-SL96.md#4-comandos-de-escrita).

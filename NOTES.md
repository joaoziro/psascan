# Peugeot 306: diário do projeto

Diário de testes e decisões. O protocolo da central não fica aqui: está em `docs/`
(`PROTOCOLO-SL96.md`, `METODO.md`, `PERFIL.md`).

Objetivo: uma ferramenta simples para Linux que diagnostique um Peugeot 306
1.8 16v XU7JP4 (LFY), central **Sagem SL96** (K-line), usando o scanner
Bluetooth "PSA COM", sem Wine.

## Estrutura desta pasta

| Caminho | Conteúdo |
| --- | --- |
| `psascan/` | A ferramenta (Python 3, só stdlib): CLI, painel web, simulador, testes |
| `android/` | App Android com o mesmo protocolo |
| `perfis/` | Dados da central em JSON (`perfis/sagem-sl96/perfil.json`) |
| `docs/` | Protocolo (`PROTOCOLO-SL96.md`), método (`METODO.md`), perfil (`PERFIL.md`), `telas/` |
| `capturas/` | Tráfego cru da serial gravado pela psascan; `ref/` = capturas da ferramenta de referência |
| `registros/` | Leituras ao vivo em CSV |
| `sessoes/` | Gravações e histórico de falhas |
| `sim-dados/` | Dados do simulador (fora do git) |

## Protocolo

Ver `docs/PROTOCOLO-SL96.md` (quadros, comandos, o que está validado e o que está
pendente), `docs/METODO.md` (como um item novo entra: sempre por captura) e
`docs/PERFIL.md` (contrato do `perfil.json`).

## psascan (ferramenta final, `psascan/`)

- Problemas da ferramenta de referência relatados (conectar era difícil, caía no meio da
  leitura, impossível andar com o carro) → resposta no psascan:
  - Bluetooth direto por socket RFCOMM (`--porta AA:BB:…` ou `cli.py porta MAC`
    salva em `~/.config/psascan/porta`): sem `rfcomm bind`, sem sudo.
  - `conexao.Conexao`: qualquer erro de link (timeout, socket fechado, quadro
    inválido) fecha tudo, espera 0,5→5 s e refaz versão/linha K/0F sozinha.
    Confirmação é por ação; reconexões só repetem a sequência de conexão.
  - `conexao.Monitor`: leitura contínua numa thread (na web roda no servidor,
    não depende da aba aberta), CSV em `registros/*.csv`. Modo `rapido` = só
    bloco 0 (lambda, RPM, temperaturas, MAP, borboleta), dobro da taxa.
  - `registro.AnaliseLambda`: trocas rico↔pobre com histerese 350/550 mV nos
    últimos 10 s → OSCILANDO / VARIA DEVAGAR / PARADA EM POBRE·RICO·MEIO.
  - `cli.py lambda` e botão "Lambda (rápido)" na web com gráfico de 60 s.
  - Simulador: `--lambda oscila|pobre|rico|meio` e `--queda N` (some 3 s a cada N s).

- `cli.py dispositivos | porta MAC | versao | falhas | apagar | lambda |
  ao-vivo [--completo] [-n N] | atuadores | atuador N | quadros`. Apagar e atuadores só
  com `--escrita` (ver "Fase 3"). O CLI mostra os bytes e pede confirmação antes de
  cada envio à central.
- `sim.py` cria um pty com dongle + central falsos para testar sem hardware (cenários
  e reprodução de CSV real: ver "Painel web"); `python3 -m unittest` (em `psascan/`)
  roda os testes.
- Captura: CLI e web gravam todo write/read cru da serial em
  `capturas/<data-hora>.jsonl` (inclusive bytes sobrando, timeouts e quadros
  inválidos); `python3 psascan/ver_captura.py <arquivo>` remonta e anota os quadros.
- `web.py`: interface local em http://127.0.0.1:8096 (`--sim` para testar; a página
  antiga `web.html` continua em `/simples`). Na primeira versão cada ação mostrava antes
  os quadros exatos (`/api/previa`) e só enviava após "Enviar"; desde a versão oficina só
  apagar falhas e atuadores pedem confirmação (ver "Painel web"). POST de outra origem é
  recusado (403).

## Hardware do usuário

- Dongle "PSA-COM" Bluetooth (AliExpress), nome OBDII ou PSACOM, PIN 1234,
  MAC `00:06:66:…`. Com a ferramenta de referência funcionava com
  `sudo rfcomm connect /dev/rfcomm0 <MAC> 1` → canal RFCOMM 1
  confirmado na prática (antes era hipótese).
- Ignição em ON (sem partida) já alimenta a tomada OBD.
- Referências para validar as leituras: lambda com motor quente oscila rápido
  0,1↔0,9 V; motor de passo da marcha lenta ~70–90 passos frio, ~20–40 quente.
- Plano B se a conexão direta falhar: `rfcomm connect` acima e
  `--porta /dev/rfcomm0`.

## Primeiros passos no carro (todos feitos em 2026-10-25)

Versão do dongle, linha K, `0F`, falhas `33`, blocos 0 e 1 e posições dos
parâmetros: ver as seções abaixo e `docs/PROTOCOLO-SL96.md`. O que continua em
aberto está em "Em aberto", no fim.

## Primeiro teste no carro (2026-10-25)

- Bluetooth direto (canal 1) funcionou de primeira, sem rfcomm/sudo.
- Versão: `80 01 18 31 4F` → `80 05 98 06 00 30 64 C5 A2` (payload `98 06 00 30 64`:
  eco 0x18|0x80, hw=6, estado 0, firmware 0x30.0x64). Formato confirmado.
- Linha K `07 00 04 00 00 00 68 3C 0B 05` foi RECUSADA:
  `80 06 7F 07 07 00 00 00 29 8F` (7F, cmd 07, código 07), com chave na 1ª e na
  2ª posição. Nada chegou à central.
- RESOLVIDO com captura da ferramenta de referência (btmon; ModemManager tinha que ser
  parado, porque mandava `AT\r` no /dev/rfcomm0 e ocupava a porta):
  - Antes do `07`, o dongle precisa de `13 07 03 00` (linha K no pino 7). Sem isso: `7F 07 07`.
  - `7F <cmd> 00` é o "ok" do dongle para `13` e `07`.
  - Central dormindo (`0F` → `7F 04 13` = sem resposta): acordar com init 5 baud
    `07 31 84 10 00 00 00 3C 6E 01` (~2,5 s). Resposta `87 …` com "SL964".
  - Manutenção é `82 00 7D` → `C2 00 3D` a cada ~1,7 s (não `0F`).
  - A central ecoa o comando (`0F`→`0F`, `33`→`33 …`, `11 00`→`11 00 …`).
- Falhas lidas pela psascan: 02A (42) sonda lambda coerência e 029 (41) detonação
  coerência, ambas memorizadas, iguais às da ferramenta de referência.
- Blocos 0/1 (motor parado, da captura) decodificados com valores plausíveis:
  bateria 11,7 V, água/ar 20 °C, 880 mbar, lambda 451 mV, passo 98.

## Motor ligado (2026-10-25, captura da ferramenta de referência)

Captura em `capturas/ref/20261025-motor-ligado.jsonl`.

- Com motor ligado o dongle recusa `13 07` (`7F 13 07`) e por isso `07` (`7F 07 07`),
  mesmo após religar o dongle. A ferramenta de referência contorna mandando
  `28 04 25 1D 48 FF` (resposta `A8 00 00`) e depois `13 07 03 00` passa. Falta
  implementar na psascan.
- Ao abrir, a ferramenta de referência também manda `35 05 0A` → `B5` + 10 words
  (~0x04DA=1242); NÃO é a tensão da bateria (central marcava 13,9 V ao mesmo tempo).
  Significado aberto.
- Falhas com motor ligado: 127 (295) regulação de riqueza, curto a positivo, PRESENTE
  (acendeu a luz); 02A (42) e 029 (41) memorizadas.
- Blocos 0/1 em marcha lenta quente, posições dos bytes CONFIRMADAS (valores coerentes):
  RPM 832–864, bateria 13,7–14,0 V, água 81→87 °C, passo 18–23, injeção ~1,65 ms,
  avanço 8–16°, admissão ~460 mbar, carga 28 %.
- Lambda PRESA em 755–853 mV (sempre "rica"), sem oscilar. Junto com a 295 presente.
- psascan VALIDADA no carro com motor ligado (web): 259 leituras dos blocos 0/1
  (RPM 832, bateria 13,9–14,0 V, água 96–98 °C, passo 15–17, lambda 823–853 mV presa).
  Apagar `C3` → resposta `C2 C3 7A` (eco); depois `33` → só `59 3A` = 295 presente.
  Nessa sessão o `13 07` foi aceito sem o `28`.
- Detalhe a melhorar: quando o 1º `0F` responde, a 2ª/3ª tentativa dá `7F 04 13` e o
  primeiro bloco falha; a reconexão resolve sozinha (acordando com 5 baud).
- Atuador luz de aviso da injeção (`B4 06`) VALIDADO: pisca e NÃO para sozinho (>30 s);
  parou ao desligar a chave. Comando de parada ainda desconhecido.
- Os demais atuadores (bomba, injetores etc.) e a leitura de riqueza (`77 01`) foram
  removidos na Fase 3: não há captura que os documente. O único atuador que fica é o
  `B4 06`, com captura e teste no carro.
- Dados do teste de 2026-10-25 no repositório: `capturas/*.jsonl` (psascan),
  `registros/*.csv` (`20261025-163040.csv` = motor ligado, quente, 323 leituras) e
  `capturas/ref/*.jsonl` (tráfego da ferramenta de referência).

## Painel web de diagnóstico (2026-10-25)

- Abrir no carro: `python3 psascan/web.py` → http://127.0.0.1:8096 (a página antiga continua em `/simples`).
  Simulador: `python3 psascan/web.py --sim` (cenário "estrada": aquece, marcha lenta, acelera, cruzeiro,
  freio-motor) ou `--sim --sim-csv registros/20261025-163040.csv` (reproduz a gravação real pelo caminho
  inteiro: quadros, conversões, faixas). Com `--sim` capturas/sessões vão para `sim-dados/` (fora do git),
  para não misturar com os dados do carro. `.claude/launch.json`: `psascan-sim` e `psascan-sim-csv`.
- Telas: Painel (cartões com faixa, estado ✓/!/✕ e 60 s), Gráficos (faixas empilhadas uPlot, um eixo Y por
  parâmetro, cursor/zoom sincronizados, predefinidos, dispersão avanço × RPM), Lambda (osciloscópio,
  veredito, trocas/s, % rico, cruzamento com injeção/canister), Gravar (REC, eventos, atalhos, modo estrada
  com Wake Lock e tela cheia), Sessões (reprodução, comparação A×B com filtro "marcha lenta quente",
  exportar CSV com coluna `evento`), Falhas (descrição/causas em português e histórico), Atuadores
  (bytes; faixa vermelha "desligue a chave" até confirmar), Avançado (conexão, log, capturas,
  quadros de cada ação).
- Ao vivo por SSE (`/api/stream`); o servidor guarda 10 min de leituras em memória. REC grava o CSV de
  sempre em `registros/` + metadados em `sessoes/indice.json` (nome, notas, eventos, quedas do Bluetooth,
  reconexões). "Marcar evento" sem REC começa a gravar sozinho incluindo os 60 s anteriores.
  Queda do Bluetooth não fecha a sessão; vira intervalo hachurado nos gráficos.
- Faixas de referência em `psascan/faixas.py` (revisadas com o carro), com condição
  (motor ligado, quente, pedal solto, marcha lenta quente); ajustes do usuário em `~/.config/psascan/faixas.json`
  (tela: tocar no cartão). Os nomes em português vêm do perfil (o CSV mantém os nomes de sempre para não
  quebrar gravações antigas).
- Histórico de falhas em `sessoes/falhas.json` (primeira/última vez, presente × memorizada, apagada em).
- Segurança: além do Origin nos POST, o servidor recusa Host que não seja 127.0.0.1/localhost (DNS
  rebinding). Nenhum comando novo vai à central.
- Bibliotecas copiadas (sem CDN): uPlot 1.6.32 (MIT) e Barlow Condensed 5.3.0 (OFL), ver `psascan/static/vendor/LEIA.md`.
- Resolvido: o `0F` agora para no primeiro que responde (antes o 2º dava `7F 04 13` e o 1º bloco falhava);
  o selo não fica mais "reconectando" depois de Parar; `ver_captura` mostra `7F <cmd> 00` como ok do dongle.
- Achado: "Abertura Electroválvula de Purga do Canister" lê o MESMO byte do motor de passo
  (bloco 1, posição 17; no CSV real os dois brutos são sempre iguais), então o valor de canister
  provavelmente não é a purga. O painel avisa isso.
- Continua em aberto: comando para parar um atuador; `35 05 0A` da ferramenta de referência; borboleta
  16° em repouso (o painel mostra em atenção contra os 11–12° da central); capturas muito
  grandes demoram a abrir na aba Avançado (~2,4 s para 4 MB).
- Falhas durante a gravação: com REC ligado a web lê as falhas (`33`, só leitura) no início, no fim e
  quando a luz de injeção (parâmetro 20, bloco 1, só na leitura completa) passa de não para sim. Cada
  leitura fica em `falhas` nos metadados da sessão e no histórico; o que for novo vira evento (e vai na
  coluna `evento` do CSV exportado). O CSV de parâmetros não muda.
- Simulador: com `--sim-csv` as falhas são as do carro (127h/295 presente, 02A e 029 memorizadas); sem CSV,
  continuam as de teste (022 água, 1A1 bateria). O topo do painel mostra "SIMULADOR".
- Ícone: `sh psascan/instalar-atalho.sh` cria "psascan" no menu e na área de trabalho. Clique = painel do
  CARRO (`psascan/abrir.py`, porta 8096, scanner salvo com `cli.py porta`; nada é enviado à central antes
  de "Ler" + confirmar os bytes). Clique direito: simulador (porta 8099) ou "Encerrar psascan". Se já
  estiver rodando, só reabre a janela; fechar a janela não para o servidor (não perde gravação).
  Log do servidor em `~/.cache/psascan/web-<porta>.log`.
- Versão oficina (navegação): Início, Ao vivo, Lambda, Falhas, Gravar, Sessões; Gráficos, Atuadores e
  Avançado em "Mais". O Início escolhe o scanner entre os pareados (`bluetoothctl devices Paired`; salva
  em `~/.config/psascan/porta`, o mesmo arquivo do `cli.py porta`), conecta mostrando as etapas
  (Bluetooth → scanner → linha K → central) e explica o erro. Conectado e parado, manda `00` a cada ~1,5 s
  (como a ferramenta de referência) para a central não largar a conexão. No modo simulador não dá para
  trocar de scanner. O dongle do usuário aparece pareado como "AutoScan&Programming".
- Bytes: leituras (conectar, falhas, ao vivo, versão) vão direto; apagar falhas e atuadores
  sempre pedem confirmação (bytes num "ver bytes" recolhido). Avançado → Conexão tem a
  opção de mostrar os bytes antes de tudo, como antes.
- O ícone do carro recusa abrir se um simulador estiver ocupando a porta 8096 (avisa para fechar).

## App Android (`android/`), primeiro teste no celular (2026-10-26)

- Celular: Samsung S23 (Android 12+). O PSA COM se anuncia como **"autoscan&programming"** e pareia
  pelas configurações do Android pedindo PIN. Fica só "pareado", nunca "conectado": é SPP, e o Android
  só mostra "conectado" para perfis como áudio. Isso é normal.
- Crash ao tocar no scanner, e depois disso a cada abertura (a escolha fica salva e o app reconecta ao
  abrir): o `BluetoothTransporte` chamava `cancelDiscovery()`, que no Android 12+ exige `BLUETOOTH_SCAN`.
  O app só declara `BLUETOOTH_CONNECT`, e aceitar "dispositivos próximos" não concede uma permissão
  que não está no manifesto. A `SecurityException` escapava do laço da `Conexao`, que só tratava
  `ErroLink`. Correção: sem `cancelDiscovery()` (o app só lista os pareados, nunca procura), o laço
  trata qualquer exceção como queda de link e o log do Bluetooth vai para a conexão certa.
  Diagnóstico pelo código, sem logcat; falta confirmar no celular que conecta.

## App Android 0.4: gravação de trajetos (2026-10-28)

- ● GRAVAR / MARCAR / ■ PARAR na barra de baixo; aba GRAVAÇÕES com a lista, COMPARTILHAR (menu do
  Android) e o modo de leitura. Um CSV por sessão, `306_AAAAMMDD_HHMM.csv` (`_2`, `_3` no mesmo
  minuto), em Downloads/PSA pelo MediaStore (Android 10+) ou na pasta do app (8 e 9, sem permissão).
- Colunas: `t_s` (monotônico desde o início), `hora` (HH:mm:ss.SSS), `bloco`, `evento`, `nota` e
  um parâmetro por coluna (nome + unidade da tela), valor convertido com ponto decimal,
  bits como 0/1, sem texto nem faixa. Uma linha por leitura de bloco; as colunas do outro bloco
  ficam vazias (não repete o último valor). Eventos: `inicio` (data, modo, scanner, versão),
  `conectado`, `falhas_inicio`, `queda`, `reconexao_falhou`, `reconectado`, `marca`, `modo`,
  `falhas`/`falhas_apagadas` (lidas pela aba FALHAS durante a gravação), `falhas_fim`, `fim`
  (`*_nao_lidas` se a central não responder em 30 s no início ou 15 s no fim).
- A central só lê bloco inteiro (`11 00`, `11 01`), então a prioridade é por bloco: normal =
  `0, 1, 0` (bloco 0 a cada volta, bloco 1 a cada duas); foco lambda = só o bloco 0 (sem injeção nem
  motor de passo, que estão no bloco 1). Pelas gravações do PC (0,30 s para os dois blocos),
  esperado ~4,4 leituras/s de lambda no normal e ~6,5 no foco; falta medir no celular. O topo mostra
  agora a taxa do bloco 0 ("lambda X Hz"), não a de voltas.
- Fora da gravação a `Conexao` também lê um bloco por volta: painel `0, 1, 0, 0`, diagnóstico `0, 1`
  (antes eram os dois blocos juntos na mesma volta, com o mesmo resultado médio).
- Conexão e gravação saíram do ViewModel para o `Nucleo` (no `App`): a tela pode fechar no meio da
  gravação. Serviço em primeiro plano `connectedDevice` com notificação e wake lock parcial só
  enquanto grava; no Android 14+ sem a permissão do Bluetooth (simulador) avisa e grava só com o app
  aberto. Cada linha vai para o sistema na hora (flush) e um fsync a cada 5 s.
- Testado: testes de unidade com o simulador (modos, falhas no início/fim, marca, queda com
  reconexão no mesmo arquivo). Não testado: a tela (sem celular nem emulador aqui) e o carro.

## Fase 3 (2026-11-08): núcleo e perfis

- Os dados da central saem do código e vão para um perfil em JSON (`perfis/sagem-sl96/perfil.json`,
  contrato em `docs/PERFIL.md`), lido por `psascan/perfil.py`.
- A família de protocolo PSA fica em `psascan/psa5.py` (quadros e a `Central`); `central.py` escolhe a família.
- A ferramenta começa em somente leitura: apagar falhas e atuadores só com `--escrita` na CLI ou a chave
  "Permitir comandos de escrita" na web e no app.
- Removidos por falta de captura: a leitura de riqueza (`77 01`) e todos os atuadores exceto o `B4 06`.
- `capturas/ref/*.jsonl` foi convertido das capturas Bluetooth (btsnoop) por `psascan/btsnoop_para_jsonl.py`.

## Em aberto

A lista completa, com o que falta capturar, está em "Pendente de validação por captura" no
`docs/PROTOCOLO-SL96.md`. Os pontos que vieram dos testes no carro:

- Parar um atuador: o `B4 06` não para sozinho e o comando de parada é desconhecido. No teste de
  2026-10-25 (`capturas/20261025-162909.jsonl`) a psascan não mandou mais nada depois do `B4 06`
  (resposta `C2 66`), e a luz ficou >30 s. Testar: `B4 06`, esperar, mandar `82 00 7D` (manutenção
  `00`, a versão oficina já manda a cada ~1,5 s) e depois `0F`/bloco. Falta uma captura da
  ferramenta de referência parando um atuador.
- Outros atuadores: sem captura, removidos até haver uma.
- `35 05 0A` (`B5` + 10 words, ~1242; não é a bateria): variar condições e ver qual palavra muda.
- Bytes de `28 04 25 1D 48 FF` e tempos do `07`: significado desconhecido.
- Canister: lê o mesmo byte do motor de passo; achar o byte certo da purga.
- Borboleta em 16° em repouso contra os 11–12° esperados.
- Curva da temperatura da água e do ar, velocidade (carro em movimento), bits de relé/A/C/luz,
  faixa completa da lambda, tipos de falha 0 e 3 a 7: ver o protocolo.
- Capturas grandes demoram a abrir na aba Avançado (~2,4 s para 4 MB).
- No carro: lambda presa em ~0,8 V com a 295 presente (problema do carro, não da ferramenta).

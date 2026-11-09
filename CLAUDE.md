# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Projeto pessoal em português: responder em português, e código, comentários, nomes e
mensagens de commit também em português, como o resto do repositório.

## O que é

`psascan/` é uma ferramenta Linux (só stdlib do Python 3, sem build, sem dependências)
para diagnosticar um Peugeot 306 com central Sagem SL96 pela linha K, usando o scanner
Bluetooth "PSA COM". O protocolo é documentado por captura em `docs/`
(`PROTOCOLO-SL96.md`, `METODO.md`, `PERFIL.md`). O `NOTES.md` é o diário de testes no
carro e decisões: ler a seção relevante antes de mexer em protocolo. O `README.md` traz o uso.

## Comandos

Os módulos se importam pelo nome (`import psa5`), sem pacote: rodar de dentro de
`psascan/` ou chamar `python3 psascan/<arquivo>.py`.

```bash
cd psascan && python3 -m unittest            # todos (~17 s)
cd psascan && python3 -m unittest test_psascan.Faixas.test_contexto   # um teste
python3 psascan/web.py --sim                 # painel com simulador, http://127.0.0.1:8096
python3 psascan/web.py --sim --sim-csv registros/20261025-163040.csv  # repete a gravação real
python3 psascan/ver_captura.py capturas/<arquivo>.jsonl   # remonta e anota uma captura
python3 psascan/btsnoop_para_jsonl.py <entrada>.btsnoop capturas/ref/<saida>.jsonl   # captura Bluetooth -> jsonl, sem endereços
```

`.claude/launch.json` tem `psascan-sim` (8096), `psascan-sim-csv` (8097), `psascan-sim-8098`
e `psascan` (carro de verdade). Para verificar mudanças na interface, usar um dos de simulador.

## Arquitetura (camadas, de baixo para cima)

- `dongle.py`: envelope do scanner (`[0x80|0xA0+len_hi] [len_lo] payload [CRC-16/X-25]`),
  comandos do próprio dongle (`18` versão, `13` pino, `07` linha K, `28` destravar,
  `04` repassar à central). Transporte: socket RFCOMM direto pelo MAC, ou um tty.
- `psa5.py`: família de protocolo PSA "5": quadros da central (`[0x81+n] dados [0xFF−soma]`,
  dentro do `04` do dongle) e a `Central` (conexão pino 7 → linha K → `0F`/init 5 baud,
  falhas, blocos `11 00`/`11 01`, conversões, atuador).
- `perfil.py`: carrega e valida `perfis/<id>/perfil.json`. Os dados da central (nomes,
  fatores, posições, tabelas de falha, atuadores) ficam só lá; não inventar parâmetros
  fora do perfil.
- `central.py`: escolhe a família de protocolo conforme o perfil.
- `conexao.py`: `Conexao.executar(fn)` refaz a conexão sozinha em qualquer erro de link;
  `Monitor` faz a leitura contínua numa thread (no servidor, não no navegador).
- `web.py`: servidor HTTP (stdlib) com API JSON, SSE em `/api/stream`, buffer de 10 min
  e a página em `static/` (JS sem build, uPlot e fonte copiados em `static/vendor/`,
  sem CDN). `web.html` é a página antiga, servida em `/simples`.
- Em volta: `registro.py` (CSV e análise da lambda), `sessoes.py` (gravações e
  `sessoes/indice.json`), `falhas_info.py` (textos e histórico das falhas), `faixas.py`
  (faixas normal/atenção com condição). Os nomes em português vêm do perfil só na tela;
  o CSV mantém os nomes de sempre para não quebrar gravações antigas.
- `sim.py`: dongle + central falsos num pty, usando os mesmos `dongle`/`psa5`/`perfil`;
  é por ele que os testes e o `--sim` passam pelo caminho real inteiro.
- `cli.py` usa as mesmas camadas que a web; `abrir.py` e `instalar-atalho.sh`
  são o ícone do desktop.

## Regras que importam

- Somente leitura por padrão: apagar falhas e atuadores só com `--escrita` na CLI ou a chave
  "Permitir comandos de escrita" na web e no app, e mesmo assim sempre pedem confirmação
  mostrando os bytes.
- Não mandar à central comando que não esteja no perfil. Todo item novo de perfil precisa de
  captura (`docs/METODO.md`).
- O servidor só aceita Host 127.0.0.1/localhost e recusa POST de outra origem.
- Dados do carro (`capturas/`, `registros/`, `sessoes/`) vão para o git; os do simulador
  vão para `sim-dados/` (fora do git). Não misturar.
- Registrar descobertas e resultados de testes no carro no `NOTES.md`, e o que for de
  protocolo também em `docs/`.

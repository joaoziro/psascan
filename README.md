# psascan

Ferramenta livre para ler a central de injeção de um carro pela **linha K**, usando o scanner
Bluetooth barato "PSA COM". Funciona no **Linux** (painel web e linha de comando) e no
**Android** (app). O protocolo foi documentado por captura de tráfego e está em [`docs/`](docs/).

Só Python 3 (biblioteca padrão, sem dependências) no Linux. A página web não usa CDN.

> ## AVISO: RISCO E AUSÊNCIA DE GARANTIA
>
> **Este software é fornecido "como está", sem nenhuma garantia.** Você o usa **por sua conta e
> risco**. Os autores não respondem por danos ao carro, à central, à bateria, ao scanner, ao seu
> celular ou computador, nem a você ou a terceiros.
>
> - **Escrever na central pode causar dano.** Apagar falhas perde informação de diagnóstico.
>   Atuadores acionam peças de verdade.
> - **Alguns atuadores não param sozinhos.** No teste com a luz de aviso da injeção, ela não
>   parou por mais de 30 s; só desligar a chave resolveu. Antes de acionar algo, saiba como parar.
> - **Nunca use escrita com o carro andando.** Leitura em movimento só com o aparelho preso e
>   outra pessoa operando. Quem dirige não mexe na tela.
> - **Por padrão, a ferramenta só lê.** A escrita precisa ser ligada de propósito e sempre pede
>   confirmação mostrando os bytes.
> - Faça tudo com o carro parado, freio de mão puxado, em local aberto e com a bateria em bom estado.
> - Não é uma ferramenta de segurança nem de diagnóstico profissional. Confirme os resultados
>   com uma oficina antes de trocar peças.

## Compatibilidade

| Item | Situação |
| --- | --- |
| Carro / motor | **Testado:** Peugeot 306 1.8 16v (XU7JP4) |
| Central | **Testada:** Sagem SL96 (linha K, pino 7) |
| Scanner | **Testado:** "PSA COM" Bluetooth (aparece como "AutoScan&Programming" ou "OBDII", PIN `1234`) |
| Outras centrais | Possível por **perfis** (arquivos de dados). Veja [CONTRIBUTING.md](CONTRIBUTING.md) |

Não há garantia de que funcione em outro carro. Não teste em outro sem ler o
[CONTRIBUTING.md](CONTRIBUTING.md).

## Recursos

- **Falhas**: lê as falhas presentes e memorizadas, com descrição e causas em português.
- **Parâmetros ao vivo**: rotação, temperatura, pressão, bateria e outros, com faixas normal/atenção.
- **Sonda lambda**: análise das trocas rico/pobre.
- **Gravação**: grava trajetos em CSV, com eventos marcados, e revê as sessões depois.
- **Tela personalizável**: mostradores, números, barras, luzes e falhas, em **layouts** que
  você monta, salva, importa e exporta ([docs/LAYOUT.md](docs/LAYOUT.md)).
- **Temas com peles**: muda cores, fontes e o desenho de cada widget, só com dados
  ([docs/TEMA.md](docs/TEMA.md)).
- **Somente leitura por padrão**: apagar falhas e acionar atuadores só com a escrita ligada.
- **Simulador**: dongle e central falsos, para usar e testar sem o carro.

## Linux

Precisa de Python 3 e do scanner pareado no Bluetooth. Com a ignição em ON (o motor pode
estar parado).

### Painel web

```bash
python3 psascan/web.py
```

Abra http://127.0.0.1:8096, escolha o scanner na tela Início e toque em Conectar.
Para criar um ícone no menu e na área de trabalho:

```bash
sh psascan/instalar-atalho.sh
```

### Linha de comando

```bash
python3 psascan/cli.py porta 00:06:66:XX:XX:XX   # salva o scanner
python3 psascan/cli.py falhas                    # lê as falhas
python3 psascan/cli.py --help                    # todos os comandos
```

| Comando | O que faz |
| --- | --- |
| `dispositivos` | lista scanners |
| `versao` | versão do scanner |
| `falhas` | lê falhas |
| `lambda` | análise da sonda lambda |
| `ao-vivo [--completo]` | parâmetros ao vivo |
| `quadros` | mostra os quadros crus |
| `apagar` | apaga falhas (**precisa de `--escrita`**) |
| `atuadores` / `atuador N` | lista / aciona atuador (**`atuador` precisa de `--escrita`**) |

### Simulador (sem carro)

```bash
python3 psascan/web.py --sim
python3 psascan/web.py --sim --sim-csv registros/20261025-163040.csv   # repete uma gravação real
```

Os dados do simulador vão para `sim-dados/` (fora do git).

## Android

O app lê os mesmos perfis, layouts e temas. No celular, pareie o scanner nas configurações do
Android (PIN `1234`). Ele fica só como "pareado", nunca "conectado": isso é normal. A conexão
existe enquanto o app está aberto.

O app tem um modo **simulador** (selo "SIMULADOR" na tela), para ver tudo sem o carro.

### Gerar o APK

```bash
sh android/instalar-ferramentas.sh   # uma vez: JDK 17, SDK e Gradle, sem sudo
sh android/gerar-apk.sh              # roda os testes e gera o APK
```

O APK sai em `android/app/build/outputs/apk/release/app-release.apk`. Ele é assinado com a chave
de debug, só para uso próprio. Para instalar, permita instalar apps de fora da loja.

## Testes

```bash
cd psascan && python3 -m unittest    # Python, cerca de 17 s
sh android/gerar-apk.sh              # testes do Android + APK
```

## Estrutura

| Caminho | Conteúdo |
| --- | --- |
| `psascan/` | A ferramenta: `web.py`, `cli.py`, `psa5.py` (protocolo), `perfil.py`, `sim.py`, `static/` |
| `android/` | App Android (Kotlin) |
| `perfis/` | Dados de cada central em JSON |
| `temas/` | Temas (cores, fontes, peles) |
| `layouts/` | Layouts da tela personalizável |
| `docs/` | Documentação (abaixo) |
| `capturas/` | Tráfego cru da serial (`*.jsonl`); `ref/` = capturas Bluetooth de referência |
| `registros/`, `sessoes/` | Leituras em CSV e gravações |
| `NOTES.md` | Diário de testes no carro e decisões |

## Documentação

| Documento | Assunto |
| --- | --- |
| [docs/PROTOCOLO-SL96.md](docs/PROTOCOLO-SL96.md) | Protocolo da Sagem SL96, byte a byte |
| [docs/METODO.md](docs/METODO.md) | Como descobrir e validar a comunicação com uma central |
| [docs/PERFIL.md](docs/PERFIL.md) | Formato do perfil de central |
| [docs/TEMA.md](docs/TEMA.md) | Formato do tema |
| [docs/LAYOUT.md](docs/LAYOUT.md) | Formato do layout e dos widgets |
| [NOTES.md](NOTES.md) | Diário e decisões |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Como contribuir |

## Licença

- **Código** (`psascan/`, `android/`): **GPL-3.0** — veja [LICENSE](LICENSE). Quem distribuir
  uma versão modificada (por exemplo, um APK) tem de abrir o código sob a mesma licença.
- **Documentação, perfis, capturas, temas e layouts**: **CC BY-SA 4.0** — veja
  [LICENSE-CONTEUDO.md](LICENSE-CONTEUDO.md).
- Componentes de terceiros mantêm as próprias licenças (uPlot: MIT; Barlow Condensed: SIL OFL 1.1).

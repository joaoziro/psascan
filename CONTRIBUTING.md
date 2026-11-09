# Como contribuir

Obrigado por ajudar. Este guia é curto. Leia o [aviso de risco](README.md) antes de testar
qualquer coisa no seu carro.

## Regras de ouro

1. **Nada sem captura.** Todo item de perfil (parâmetro, falha, comando) precisa de uma captura
   que o mostre.
2. **Escrita só com captura.** Não invente comandos nem varie bytes "para ver o que acontece".
3. **Somente leitura por padrão.** Apagar falhas e atuadores ficam atrás de `--escrita` e de
   confirmação.
4. **Sem dados pessoais.** Nada de MAC, VIN ou placa nas capturas.
5. **Segurança primeiro.** Teste com o carro parado. Nunca escrita com o carro andando.

## Criar o perfil de uma nova central

Um perfil é só dados: `perfis/<id>/perfil.json`. O formato está em [docs/PERFIL.md](docs/PERFIL.md).
O exemplo completo é `perfis/sagem-sl96/perfil.json`.

O método passo a passo está em [docs/METODO.md](docs/METODO.md). Resumo:

1. **Capturar** o tráfego da sua ferramenta e, se possível, de uma ferramenta que funciona
   falando com a sua central (`btmon -w arquivo.btsnoop` no Linux).
2. **Descobrir** o envelope do scanner, o pino, a velocidade, o endereço e a inicialização.
3. **Descobrir** o formato dos quadros e como manter a sessão.
4. **Mapear parâmetros** mudando uma coisa por vez no carro.
5. **Mapear falhas** lendo antes de apagar.
6. **Registrar** cada fato com: bytes, captura e linha, e nível.

Cada item recebe um nível:

| Nível | Significado |
| --- | --- |
| `V1` | Comando e resposta aparecem byte a byte numa captura. |
| `V2` | V1, e o valor varia de forma coerente com o que o carro fazia e é fisicamente plausível. |
| `V3` | Valor conferido com referência independente (multímetro, termômetro, conta-giros). |
| `pendente` | Não há captura que confirme. Fica na lista de pendências. |

Regras:

- Preencha `nivel` e `evidencia` em cada métrica.
- Não apague o que ficou `pendente`. Só suba o nível com captura nova.
- Um comando de `escrita` só entra no perfil com captura que o mostre. Documente também como pará-lo.
- A família de protocolo hoje é só `psa5`. Se a sua central usa outro protocolo, abra uma issue
  antes de escrever código.

### Validar o perfil

```bash
cd psascan && python3 -m unittest test_psascan.Perfil test_docs
cd psascan && python3 -m unittest
```

Depois abra o simulador e confira a tela:

```bash
python3 psascan/web.py --sim --perfil perfis/<id>/perfil.json
```

Confira também as capturas: o CRC do scanner e o checksum da central devem fechar em todos os
quadros (`python3 psascan/ver_captura.py capturas/<arquivo>.jsonl`).

## Enviar capturas do seu carro

Capturas são o que mais ajuda. Para enviar:

1. **Converta** a captura Bluetooth, sem endereços:

   ```bash
   python3 psascan/btsnoop_para_jsonl.py entrada.btsnoop capturas/ref/<saida>.jsonl
   ```

   Esse script guarda só o conteúdo serial e descarta endereços e dados do computador.
2. **Confira à mão** que não sobrou MAC (`00:06:66:XX:XX:XX`) nem **VIN**, placa ou outro dado
   pessoal, em nenhum arquivo, nota ou CSV. Troque por `XX` ou apague a linha.
3. **Nomeie** o arquivo pela condição: `motor-parado`, `motor-ligado`, `frio`, `quente`.
4. **Descreva** na issue ou no PR: carro, ano, motor, central, scanner, e o que você fez.

| Informação | Vai no PR? |
| --- | --- |
| Capturas anonimizadas (`.jsonl`) | Sim |
| Registros CSV de leituras | Sim, sem dados pessoais |
| Fotos da tela de outra ferramenta | Só se não mostrarem VIN/placa |
| MAC do scanner, VIN, placa | **Nunca** |

## Criar um tema

Um tema muda só a aparência: `temas/<id>/tema.json`. Formato completo em [docs/TEMA.md](docs/TEMA.md).
O tema `temas/padrao/tema.json` é a referência. Campos que faltam vêm do Padrão.

- **Só dados.** Nada de código, CSS livre ou SVG.
- **Peles** (`painel.peles`): desenho de cada tipo de widget, com imagens por peça, geometria e fontes.
- **Arquivos** em `arquivos/` do tema, com estes limites:

| Regra | Valor |
| --- | --- |
| Tipos aceitos | `.woff2`, `.ttf`, `.png`, `.webp`, `.jpg` |
| Tamanho por arquivo | até 2 MB |
| Tamanho total | até 10 MB |
| Caminhos | sem `..`, sem `/` no início, sem subpastas além de `arquivos/` |
| Imagem do mostrador | recomendado 1024 × 1024 px |
| Contraste | texto/fundo com pelo menos 4,5:1 |

- **Imagens e fontes** devem ser **suas**, ou com licença que permita redistribuir (por exemplo
  CC0, CC BY, OFL). Informe a origem e a licença na descrição do PR e em `descricao`/`autor`.
- **Não envie** nada que imite marcas, jogos ou personagens de terceiros, nem logotipos.
- Use `id` com `a-z0-9-` (2 a 32 caracteres), igual ao nome da pasta.

Teste:

```bash
cd psascan && python3 -m unittest test_temas
```

Um tema inválido é recusado inteiro, com o motivo. Veja o resultado no painel (aba Avançado → Tema).

## Criar um layout

Um layout diz quais widgets aparecem e onde: `layouts/<id>.json`. Formato em [docs/LAYOUT.md](docs/LAYOUT.md).

- Grade de 4 a 16 colunas e 3 a 12 linhas, até 40 widgets.
- Widgets não saem da grade nem se sobrepõem. Respeite os tamanhos de cada tipo.
- Cite métricas pelo `id` do perfil (`rpm`, `agua`…). Métrica que não existe no perfil só deixa
  o widget indisponível.
- O layout não depende do tema. Teste com mais de um tema.

O jeito mais fácil: monte no modo edição do painel, salve e exporte o `.json`. Teste com:

```bash
cd psascan && python3 -m unittest test_layouts
```

## Estilo do código

- **Português** em código, comentários, nomes e mensagens de commit, como o resto do repositório.
- **Só a biblioteca padrão** do Python 3. Sem dependências, sem build.
- Os módulos se importam pelo nome (`import psa5`), sem pacote.
- Os dados da central ficam **só no perfil**. Não invente parâmetros no código.
- Não mande à central comando que não esteja no perfil.
- O servidor só aceita Host `127.0.0.1`/`localhost` e recusa POST de outra origem. Não mude isso.
- Dados do carro vão para `capturas/`, `registros/`, `sessoes/`. Os do simulador vão para `sim-dados/`
  (fora do git). Não misture.
- Registre descobertas e resultados de testes no [NOTES.md](NOTES.md) e, se for de protocolo,
  também em `docs/`.
- Sem CDN: bibliotecas da página ficam em `psascan/static/vendor/`.

## Rodar os testes

### Python

```bash
cd psascan && python3 -m unittest                                # todos (cerca de 17 s)
cd psascan && python3 -m unittest test_psascan.Faixas.test_contexto   # um teste
```

### Android

A máquina pode ser fraca (cerca de 7 GB de memória). Então:

- **Sem emulador Android.** Use o celular, ou o modo simulador do app.
- **Rode o Gradle sozinho**, e pare-o depois (`./gradlew --stop`). Não deixe outras coisas pesadas abertas.

```bash
sh android/instalar-ferramentas.sh   # uma vez
sh android/gerar-apk.sh              # testes unitários + APK
```

Os temas e layouts do app são gerados a partir de `temas/` e `layouts/`. O Gradle regera os
arquivos ao mudar esses diretórios.

## Gerar o seu próprio APK

Qualquer pessoa pode gerar o app a partir deste repositório. Precisa de JDK 17, Android SDK e
Python 3 (os dados do app — perfil, temas, layouts — são gerados no build).

```bash
sh android/gerar-apk.sh
```

Sai em `android/app/build/outputs/apk/release/app-release.apk`, assinado com a **chave de debug
da sua máquina**: serve para instalar no seu celular.

**Para distribuir a sua versão**, crie uma chave própria (guarde o arquivo e a senha fora do
repositório, com backup: sem ela você não consegue publicar atualizações que instalem por cima):

```bash
keytool -genkeypair -v -keystore ~/minha-chave.jks -alias psascan -keyalg RSA -keysize 4096 -validity 10000
```

e gere o APK com as variáveis (nada disso vai para o git):

```bash
PSASCAN_KEYSTORE=~/minha-chave.jks PSASCAN_KEYSTORE_SENHA='…' sh android/gerar-apk.sh
```

(`PSASCAN_KEY_ALIAS` e `PSASCAN_KEY_SENHA` são opcionais: padrão `psascan` e a mesma senha.)

Se for **distribuir uma variante**, troque o `applicationId` em `android/app/build.gradle.kts`
(ex.: `br.seunome.psascan`): APKs com o mesmo id e assinaturas diferentes não instalam um por
cima do outro nem convivem no mesmo celular. Pela **GPL-3.0**, quem distribui um APK modificado
publica também o código da modificação sob a mesma licença.

## Enviar um PR

- Um assunto por PR, pequeno.
- Rode os testes antes.
- Diga o que mudou e como testou. Para perfis: quais capturas, qual nível.
- Se mexer na interface, anexe uma imagem (use o simulador).
- Em dúvida sobre segurança, abra uma issue antes.

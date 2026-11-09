# Licença: opções para decidir

O projeto ainda não tem licença. Sem licença, ninguém pode legalmente copiar, modificar ou
redistribuir o código, mesmo com o repositório público. A escolha é do autor. Isto não é
parecer jurídico.

O projeto tem três tipos de conteúdo, que podem ter licenças diferentes:

1. **Código** (`psascan/`, `android/`).
2. **Dados de protocolo e documentação** (`perfis/`, `docs/`, `capturas/`, `registros/`).
3. **Temas e layouts** (`temas/`, `layouts/`), incluindo imagens.

Terceiros já incluídos, que não mudam com a escolha: uPlot (MIT), Barlow Condensed (SIL OFL 1.1),
Gradle wrapper (Apache 2.0). Todos são compatíveis com qualquer opção abaixo.

## Código

| Licença | O que permite | Prós | Contras |
| --- | --- | --- | --- |
| **MIT** | Tudo, inclusive usar em produto fechado; só exige manter o aviso. | Simples, a mais conhecida; ninguém hesita em contribuir. | Alguém pode fazer um app fechado e pago em cima sem devolver nada. |
| **Apache 2.0** | Igual à MIT, mais concessão explícita de patentes e registro de mudanças. | Proteção de patentes; comum em Android. | Texto longo; exige NOTICE e marcar arquivos alterados. |
| **GPL-3.0** | Usar e modificar à vontade; quem **distribuir** uma versão (ex.: APK) tem de abrir o código sob GPL. | Melhorias voltam para a comunidade; impede app fechado derivado. | Afasta quem quer embutir em produto fechado; mais regras. |
| **AGPL-3.0** | Como a GPL, e vale também para quem oferecer pela rede. | Fecha a brecha de "serviço online". | Raramente necessária aqui (a ferramenta roda local). |

## Dados, documentação, temas e layouts

| Licença | Prós | Contras |
| --- | --- | --- |
| **CC BY 4.0** | Qualquer uso, com crédito. | Derivados podem ser fechados. |
| **CC BY-SA 4.0** | Com crédito e derivados sob a mesma licença (o "GPL" dos documentos). | Um pouco mais restritiva. |
| **CC0** | Domínio público: máximo de reaproveitamento (bom para perfis de centrais). | Não exige crédito. |

## Recomendação

- Se a prioridade é **comunidade aberta e melhorias voltando para o projeto** (o grupo do 306
  adaptando para outras centrais): **GPL-3.0** no código + **CC BY-SA 4.0** em docs, temas e
  layouts + **CC0** nos perfis e capturas (dados de protocolo devem circular livremente).
- Se a prioridade é **máxima adoção**, inclusive por oficinas e apps comerciais: **MIT** no
  código + **CC BY 4.0** no resto.

Depois da escolha: arquivo `LICENSE` na raiz (texto oficial), um `LICENSE` em `temas/` e
`perfis/` se forem diferentes, e a seção "Licença" do README.

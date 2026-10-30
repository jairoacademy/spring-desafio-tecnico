# Uso de IA: desenvolvimento orientado a especificação (SDD)

[← Voltar ao README](../../README.md)

Esta aplicação foi construída com um agente de código (**Claude Code**, integrado ao IntelliJ) seguindo **Spec-Driven Development** com o **OpenSpec 1.15.0**. A ideia central é simples: **nenhum código foi escrito antes de existir uma especificação revisada e aprovada por mim**. O agente acelerou a escrita; as decisões técnicas, a revisão e o critério de aceite ficaram comigo.

## Por que SDD

- **Decisões antes do código.** Duplicidade, estados, retornos do provedor e segurança foram definidos como requisitos com cenários antes de qualquer implementação.
- **Rastreabilidade.** Cada funcionalidade tem uma proposta (por quê), um design (como), tarefas (o quê) e cenários verificáveis, versionados junto com o código.
- **Controle sobre o agente.** O agente implementa contra uma spec fechada, o que reduz "criatividade" fora do escopo e facilita revisar o que foi gerado.
- **Contexto pequeno.** O trabalho foi dividido em etapas no [roteiro](../roteiro.md): uma change por etapa, com prompt, critério de pronto e branch própria. O agente trabalha só com as specs da change e a task atual, e cada pull request fica pequeno o bastante para revisar.

## Fluxo de trabalho

| Etapa | Comando | Quem conduz | Resultado |
|---|---|---|---|
| Explorar | `/opsx:explore` | Eu + agente | Discussão de alternativas antes de comprometer uma mudança |
| Propor | `/opsx:propose` | Agente rascunha, eu reviso | `proposal.md`, `design.md`, `tasks.md` e deltas de spec |
| Revisar | — | Eu | Ajuste de requisitos e cenários até ficarem corretos |
| Implementar | `/opsx:apply` | Agente | Código e testes seguindo `tasks.md` |
| Validar | `./mvnw verify` + code review | Eu | Testes verdes e revisão linha a linha do diff |
| Arquivar | `/opsx:archive` | Agente | A mudança é incorporada às specs principais |

Cada change vive em uma branch própria e entra na `main` por pull request com merge commit. As regras do agente estão em [CLAUDE.md](../../CLAUDE.md): só implementar o que está em `tasks.md`, não usar APIs do Spring Boot 3, não expor stack trace nem logar credenciais, e justificar no `design.md` toda dependência nova.

## Onde estão as specs

```
openspec/
├── specs/                  # estado atual do sistema (fonte da verdade)
│   ├── activation-requests/
│   ├── duplicate-prevention/
│   ├── provider-integration/
│   ├── api-security/
│   ├── error-handling/
│   └── operations/
└── changes/
    └── archive/            # histórico de cada mudança, com proposta, design e tarefas
```

Seis changes arquivadas, uma por etapa do roteiro. Para navegar: `openspec list` lista as mudanças e `openspec show <nome>` mostra o detalhe de uma delas.

## Exemplo: a regra de duplicidade como requisito

Trecho de [`openspec/specs/duplicate-prevention/spec.md`](../../openspec/specs/duplicate-prevention/spec.md):

```markdown
### Requirement: externalId único em todo o serviço
O serviço SHALL manter no máximo uma solicitação por `externalId`, considerando todas
as solicitações, de qualquer cliente. A unicidade MUST ser garantida pelo banco de dados,
e não só por verificação prévia na aplicação.

#### Scenario: Retry com o mesmo conteúdo
- **WHEN** um cliente cria uma solicitação e envia o mesmo corpo de novo
- **THEN** a segunda resposta é `200` com os mesmos `id`, `status`, `createdAt` e `updatedAt` da primeira

#### Scenario: productCode divergente
- **WHEN** um cliente repete o `externalId` com `productCode` diferente
- **THEN** a resposta é `409 Conflict` em ProblemDetail, com `conflictingFields`
```

## Divisão de responsabilidades

| Eu | Agente |
|---|---|
| Decisões de arquitetura e de domínio (duplicidade, estados, tabela de retornos do provedor, segurança) | Rascunho das propostas, do design e das tarefas |
| Revisão e aprovação de cada spec antes da implementação | Implementação das tarefas aprovadas |
| Code review de todo o código gerado | Escrita dos testes a partir dos cenários |
| Critério de aceite: `./mvnw verify` verde e cenários cobertos | Arquivamento das mudanças concluídas |

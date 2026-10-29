# Proposal

## Why

O serviço ainda não registra nem consulta solicitações de ativação: só sobe com banco e probes (change `bootstrap-project`). Esta change entrega o núcleo pedido nos Itens 2, 4 e 6 do enunciado (API de criação e consulta, modelo de domínio com estados controlados e tratamento de erros padronizado), base sobre a qual as changes de duplicidade, integração e segurança vão operar.

## What Changes

- `POST /api/v1/requests`: cria uma solicitação com status `PENDING`, responde `201 Created` com header `Location` e o corpo da solicitação.
- `GET /api/v1/requests/{id}`: retorna a solicitação; `404` em ProblemDetail se não existir.
- `GET /api/v1/requests?status=&customerId=&page=&size=` (diferencial): listagem paginada com filtros, ordenada da mais recente para a mais antiga, com índices justificados no README.
- Validação documentada dos campos de entrada (obrigatoriedade, tamanho máximo e formato).
- Modelo de domínio `ActivationRequest` com estados `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`; transições só por métodos de domínio, incluindo `PROCESSING → PENDING` para nova tentativa; transição inválida lança exceção de domínio. Campos internos `attempts`, `nextAttemptAt` e versão para lock otimista, já preparados para a integração.
- Migração Flyway criando a tabela das solicitações e os índices da listagem.
- Erros em ProblemDetail (RFC 9457) com `traceId` para dados inválidos, recurso inexistente e erro inesperado, sem stack trace nem detalhe interno.
- Origem do `traceId` já nesta change: Micrometer Tracing com bridge OpenTelemetry, sem exportação de spans (decisão no `design.md`).

Fora do escopo: prevenção de duplicidade por `externalId` (change `add-duplicate-prevention`), chamada ao provedor e worker (change `add-provider-integration`), segurança (change `add-api-security`), propagação do `traceId` ao provedor, logs JSON e métricas (change `add-observability-and-delivery`).

## Capabilities

### New Capabilities
- `activation-requests`: criação, consulta e listagem de solicitações de ativação, regras de validação da entrada e ciclo de vida dos estados da solicitação.
- `error-handling`: formato padronizado de erro da API (ProblemDetail com `traceId`) e garantia de não expor detalhes internos.

### Modified Capabilities
<!-- Nenhuma: a spec `operations` não muda. -->

## Impact

- Código novo: pacote de feature `activation` (`api`, `application`, `domain`, `infrastructure`) e tratamento global de erros da API.
- Banco: migração `V1` com a tabela `activation_request` e índices.
- Dependências: Micrometer Tracing com bridge OpenTelemetry (justificativa no `design.md`).
- Configuração: `application.properties` ganha validação de schema pelo Hibernate e propriedades de tracing.
- API pública: três endpoints novos sob `/api/v1/requests`.
- README: seção de uso da API, regras de validação, formato de erro e decisões desta change.

# Proposal

## Why

Hoje `POST /api/v1/requests` cria uma solicitação nova a cada chamada, mesmo com `externalId` repetido. Sistemas de origem fazem retry, e duas requisições iguais podem chegar quase ao mesmo tempo. O enunciado exige que o sistema nunca crie duas solicitações com o mesmo `externalId` (Item 3, Obrigatório) e valoriza uma semântica clara para cada caso de repetição (Item 3, Diferencial).

## What Changes

- Nova migração Flyway `V2` com constraint `UNIQUE` em `activation_request.external_id`. A unicidade é **global**, não por cliente, como diz o enunciado.
- A criação passa a tratar a violação dessa constraint: relê o registro existente e compara o conteúdo, em vez de devolver `500`.
- Mesmo `externalId` com mesmo conteúdo (`customerId` e `productCode`) → `200 OK` com o registro existente (retry idempotente). Nenhum registro novo.
- Mesmo `externalId` com conteúdo diferente → `409 Conflict` em ProblemDetail, com a lista dos campos divergentes.
- Duas requisições idênticas em paralelo → uma recebe `201`; a outra cai na violação, relê o registro e recebe `200`.
- **BREAKING** (comportamento observável): `POST` com `externalId` repetido deixa de retornar `201` com um registro novo.
- Teste de concorrência com Testcontainers: N threads com o mesmo `externalId` resultam em exatamente 1 registro.
- ADR `docs/adr/0001-unicidade-external-id.md` e seção de duplicidade no README.

## Capabilities

### New Capabilities
- `duplicate-prevention`: garantia de unicidade do `externalId` no banco e semântica da resposta para repetição (retry idêntico, conteúdo divergente, requisições paralelas).

### Modified Capabilities
- `activation-requests`: o requisito "Criação de solicitação" passa a dizer que `201` vale para `externalId` ainda não registrado; a repetição segue as regras de `duplicate-prevention`.

## Impact

- Banco: `src/main/resources/db/migration/V2__unique_external_id.sql`. Uma base que já tenha `externalId` duplicado faz a migração falhar (aceitável: o serviço ainda não está em produção).
- Código: `ActivationRequestService.create` (resultado com indicação de criado ou existente), `ActivationRequestRepository` (busca por `externalId`), nova exceção de conflito em `application`, `ActivationRequestController` (`201` ou `200`), `ApiExceptionHandler` e `ProblemTypes` (`409`).
- API: `POST /api/v1/requests` ganha respostas `200` e `409`.
- Sem dependências novas.
- Docs: ADR 0001 e README (decisões técnicas e seção da API).
- Change 5 (`add-api-security`) vai adicionar o dono da solicitação sem alterar essa constraint.

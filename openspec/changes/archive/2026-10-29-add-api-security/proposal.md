# Proposal

## Why

A API é consumida por outros sistemas e hoje aceita qualquer chamada, sem autenticação: qualquer sistema cria solicitações e lê as de todos os outros. O item 7 do enunciado pede proteção com Spring Security, JWT e OAuth 2.0, com escopos de leitura e escrita, testes de 401 e 403 sem servidor de autenticação e isolamento entre consumidores (OWASP API Top 10, BOLA).

## What Changes

- **BREAKING** Todos os endpoints de `/api/v1/requests` passam a exigir `Authorization: Bearer <JWT>`. Sem token, ou com token inválido, a resposta é `401`.
- A aplicação vira OAuth 2.0 Resource Server (Spring Security 7, `SecurityFilterChain` em DSL lambda). O JWT é validado por assinatura (chaves do JWKS configurado), `iss`, `aud` e `exp`. O JWT também precisa trazer a claim `client_id` (RFC 9068).
- Autorização por escopo: `POST` exige `activation:write`; `GET` (consulta e listagem) exige `activation:read`. Escopo ausente resulta em `403`.
- Respostas `401` e `403` saem em ProblemDetail com `traceId`, como os demais erros. O `401` mantém o header `WWW-Authenticate: Bearer` (RFC 6750).
- Dono da solicitação: nova coluna `client_id`, criada pela migração Flyway `V4`. Na criação, o valor vem da claim `client_id` do JWT.
- Isolamento (BOLA): a consulta por id de uma solicitação de outro cliente responde `404`, igual a um id inexistente. A listagem mostra só as solicitações do cliente do token.
- Duplicidade entre clientes: o `externalId` continua único global (ADR 0001). Uma criação com `externalId` de outro cliente responde `409` sem `conflictingFields`, mesmo com conteúdo idêntico. Assim, o `409` não revela dados da solicitação de outro cliente.
- `/actuator/health/**` continua público para os probes do orquestrador.
- `compose.yaml` ganha um emissor de tokens de desenvolvimento (`mock-oauth2-server`), com clientes de exemplo. A coleção Bruno passa a obter token por client credentials.
- ADR 0004 registra as decisões de segurança. O `design.md` e o README descrevem o fluxo client credentials e o armazenamento e a rotação da credencial do provedor.

## Capabilities

### New Capabilities
- `api-security`: autenticação dos consumidores por JWT (OAuth 2.0 Resource Server), validação do token, autorização por escopo, identidade do cliente e formato das respostas `401` e `403`.

### Modified Capabilities
- `activation-requests`: a criação grava o cliente dono da solicitação; a consulta por id e a listagem passam a considerar só as solicitações do cliente autenticado.
- `duplicate-prevention`: o retry idêntico e o conflito com `conflictingFields` passam a valer só para o mesmo cliente; `externalId` de outro cliente vira `409` sem detalhes do registro.
- `operations`: o ambiente local com Docker Compose passa a incluir um emissor de tokens para chamar a API protegida.

## Impact

- **Dependências**: `spring-boot-starter-security-oauth2-resource-server` e, em teste, `spring-boot-starter-security-oauth2-resource-server-test`.
- **Banco**: migração `V4` com a coluna `client_id` e índices da listagem prefixados pelo dono.
- **Código**: configuração de segurança nova; `ActivationRequest`, `ActivationRequestService`, repositório, especificações e controller recebem o dono; `ApiExceptionHandler` compartilha a montagem do ProblemDetail com os handlers de `401` e `403`.
- **Configuração**: novas variáveis obrigatórias `JWT_JWK_SET_URI`, `JWT_ISSUER_URI` e `JWT_AUDIENCE`; perfil `local` aponta para o emissor do Compose.
- **Testes**: os testes de API existentes passam a enviar JWT com `jwt()` do `spring-security-test`; testes novos de `401`, `403`, BOLA e validação de `iss`, `aud`, `exp` e assinatura.
- **Fora do escopo**: limite de tamanho do corpo e de taxa de chamadas (diferencial do item 7) ficam descritos no README, sem implementação nesta change.

# Tasks

## 1. Resource Server e escopos

- [x] 1.1 Adicionar `spring-boot-starter-security-oauth2-resource-server` e, em teste, `spring-boot-starter-security-oauth2-resource-server-test` (design, decisão 14); propriedades `spring.security.oauth2.resourceserver.jwt.*` com `${JWT_JWK_SET_URI}`, `${JWT_ISSUER_URI}` e `${JWT_AUDIENCE}` em `application.properties`, `http://localhost:8091/default/jwks`, `http://localhost:8091/default` e `ativacao-api` em `application-local.properties`, e valores fixos em `src/test/resources/config/application.properties` (decisões 2 e 11); criar `shared/security/SecurityConfiguration` com a `SecurityFilterChain` da decisão 4 (sem os handlers de erro ainda) e o validador de `client_id` da decisão 3; criar o helper de teste com `jwt()` (cliente e escopos parametrizáveis) e usá-lo em `ActivationRequestApiIntegrationTests`, `ActivationDispatchSchedulingIntegrationTests` e `UnexpectedErrorIntegrationTests`; `HealthProbesIntegrationTests` continua sem token; atualizar no README as variáveis de ambiente obrigatórias e a configuração local; verificar com `./mvnw verify` que todos os testes passam
- [x] 1.2 Criar `ApiSecurityIntegrationTests` com os cenários de autorização da spec `api-security`: `401` sem token em `POST`, `GET` por id e listagem (e nenhuma solicitação criada); `401` com `Bearer abc`; `403` em `POST` só com `activation:read`, em `GET` por id só com `activation:write` e na listagem sem escopo (e nenhuma solicitação criada no `POST`); `201` com `activation:write`; liveness e readiness `200` sem token; verificar com `./mvnw verify`

## 2. Erros 401 e 403 em Problem Details

- [x] 2.1 Extrair de `ApiExceptionHandler` o preenchimento de `instance` e `traceId` para o componente `shared/web/ProblemDetails` e tornar `ProblemTypes` público com `UNAUTHORIZED` e `FORBIDDEN` (decisão 5); verificar com `./mvnw verify` que os testes de erro existentes continuam passando sem mudança
- [x] 2.2 Criar o `AuthenticationEntryPoint` e o `AccessDeniedHandler` da decisão 5 (delegando aos handlers Bearer do Spring Security e escrevendo `application/problem+json` com o `JsonMapper` do contexto) e ligá-los na `SecurityFilterChain`; acrescentar a `ApiSecurityIntegrationTests` os cenários "Corpo do 401" (`type`, `title`, `status`, `detail` fixo, `instance`, `traceId` e `WWW-Authenticate` iniciado por `Bearer`) e "Corpo do 403"; atualizar a seção "Formato de erro" do README com os dois tipos novos; verificar com `./mvnw verify`

## 3. Validação do token de ponta a ponta

- [x] 3.1 Criar `JwtValidationIntegrationTests` (decisão 11): WireMock em porta dinâmica servindo um JWKS com chave RSA gerada no teste, `jwk-set-uri` apontando para ele por `@DynamicPropertySource`, tokens assinados com Nimbus; cenários da spec "Validação do token": válido → `200`; chave fora do JWKS, `iss` errado, `aud` errado, `exp` além da tolerância e sem `client_id` → `401`; verificar com `./mvnw verify`

## 4. Dono da solicitação e isolamento

- [x] 4.1 Criar `V4__request_owner.sql` com a coluna `client_id` e a troca de índices (decisões 6 e 8); `ActivationRequest.create` recebe `clientId` e a entidade ganha `getClientId()`; ajustar os chamadores de teste (`ActivationRequestTest`, `ActivationRequestRepositoryTests`, `ProviderIntegrationTests`, `ActivationRequestConcurrencyIntegrationTests`, `ActivationRequestApiIntegrationTests`); teste em `ActivationRequestRepositoryTests` gravando e relendo o dono; atualizar a seção "Índices da listagem" do README; verificar com `./mvnw verify`
- [x] 4.2 Passar o dono do controller ao serviço (`@AuthenticationPrincipal Jwt`, claim `client_id`, decisão 7) na criação; teste do cenário "Dono gravado a partir do token" (dono conferido pelo repositório, ausente do corpo); verificar com `./mvnw verify`
- [x] 4.3 Consulta por id e listagem filtradas pelo dono: `findByIdAndClientId` no repositório, `hasClientId` em `ActivationRequestSpecifications` e assinaturas novas em `ActivationRequestService` (decisão 7); testes de repositório para os dois filtros; testes de API dos cenários "Solicitação de outro cliente" (`404` igual ao de id inexistente) e "Listagem isolada por dono"; atualizar a seção "API" do README (autenticação, escopos por endpoint e isolamento); verificar com `./mvnw verify`
- [x] 4.4 Duplicidade entre donos (decisão 9): `resolveExisting` compara o dono antes do conteúdo; `ActivationRequestConflictException` sem campos para dono diferente; `ApiExceptionHandler` omite `conflictingFields` quando vazio; testes de API dos três cenários de "externalId de outro cliente é conflito sem detalhes" e confirmação de que os cenários de retry e conflito do mesmo dono continuam passando; atualizar a seção "Duplicidade" do README; verificar com `./mvnw verify`

## 5. Ambiente local, Bruno e CI

- [x] 5.1 Adicionar ao `compose.yaml` o serviço `oauth2` (`ghcr.io/navikt/mock-oauth2-server:6.0.4`, porta `8091`, `JSON_CONFIG` lido de `oauth2/config.json` com os clientes da decisão 10, health check com `wget`) e as variáveis `JWT_*` no `app`; passo novo no job `compose-smoke` do CI obtendo token de `sistema-leitura` e chamando `GET /api/v1/requests` com `200`; atualizar o README ("Tudo com Docker Compose" e "Aplicação local": como obter token com `curl`, aviso de que o emissor aceita qualquer segredo e só serve para desenvolvimento); verificar com `docker compose up --build --wait` e os mesmos comandos do passo novo do CI
- [ ] 5.2 Coleção Bruno: auth OAuth 2.0 client credentials na coleção (`sistema-a`, token em `http://localhost:8091/default/token`), requests existentes herdando a auth, e pasta `06 Segurança` com sem token (`401`), só leitura criando (`403`) e `sistema-b` consultando solicitação de `sistema-a` (`404`); atualizar a docs da coleção com o `oauth2` no passo "Antes de usar"; verificar rodando a coleção contra a aplicação local com o perfil `local`

## 6. Decisões e verificação final

- [ ] 6.1 Escrever `docs/adr/0004-seguranca-da-api.md` (client credentials, validação do JWT por JWKS, escopos, `client_id` como dono, BOLA com `404`, `409` sem detalhes entre donos) e acrescentar ao ADR 0001 a referência à regra entre donos; atualizar o README com a seção de segurança nas "Decisões técnicas": fluxo client credentials, o que o serviço valida no JWT e de onde vêm as chaves, escopos, armazenamento e rotação da credencial do provedor (decisão 12) e limites de corpo e de taxa como evolução (decisão 13); rodar `./mvnw verify` e verificar que passa

## Workflow follow-up

- `openspec validate add-api-security --strict` e commit `docs(spec): proposta add-api-security` (inclui a regra pendente do `CLAUDE.md`).
- Depois da implementação: `/opsx:archive`, commit `docs(spec): arquiva add-api-security`, PR para `main` com merge commit e `[x]` na Etapa 5 de `docs/roteiro.md`.

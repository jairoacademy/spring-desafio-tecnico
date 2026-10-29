# Tasks

## 1. Banco e domínio

- [x] 1.1 Criar `V3__provider_dispatch.sql` (coluna `failure_reason` com check e os índices parciais `ix_activation_request_pending_due` e `ix_activation_request_processing_updated`, design "Migration Plan"); adicionar o enum `FailureReason` e o campo `failureReason` (nullable) em `ActivationRequest`; trocar `fail(now)` por `fail(FailureReason, now)` e zerar o motivo nas outras transições; ajustar `ActivationRequestTest`; teste em `ActivationRequestRepositoryTests` gravando e relendo uma solicitação `FAILED` com motivo; verificar com `./mvnw verify`
- [x] 1.2 Adicionar `ActivationRequest.registerTemporaryFailure(nextAttemptAt, maxAttempts, now)` (design, decisão 8); teste unitário sem Spring: abaixo do limite volta a `PENDING` com `attempts++` e `nextAttemptAt`; na última chamada permitida vai para `FAILED` com `MAX_ATTEMPTS_EXCEEDED` e `attempts` igual ao limite; fora de `PROCESSING` lança `InvalidStatusTransitionException` sem alterar nada
- [x] 1.3 Expor `failureReason` em `ActivationRequestResponse` (criação, consulta e listagem); testes em `ActivationRequestApiIntegrationTests`: `201` com `failureReason` `null` e `GET` de uma solicitação levada a `FAILED` pelo repositório com o motivo no corpo e na listagem por `status=FAILED`

## 2. Backoff e propriedades

- [x] 2.1 Criar `RetryPolicy` (design, decisão 7) com `base`, `max` e `RandomGenerator`; teste unitário sem Spring com gerador fixo nos extremos cobrindo os cenários "Atraso cresce a cada tentativa" e "Atraso limitado ao máximo" da spec, e tentativa alta sem overflow
- [x] 2.2 Criar o record `ActivationDispatchProperties` (`@ConfigurationProperties("activation.dispatch")`, validado) com os padrões da decisão 12 e registrá-lo; criar `src/test/resources/config/application.properties` com `activation.dispatch.enabled=false` e uma base URL para o grupo `provider`; verificar com `./mvnw verify` que os testes atuais continuam passando

## 3. Cliente do provedor

- [x] 3.1 Adicionar `spring-boot-starter-restclient` ao `pom.xml`, e `wiremock-standalone` 3.13.2 e `awaitility` em escopo de teste (design, decisão 14); criar `ProviderClient` (`@HttpExchange`), `ActivationPayload` e o registro com `@ImportHttpServices(group = "provider")`; propriedades `spring.http.serviceclient.provider.*` com `${PROVIDER_URL}` e timeouts em `application.properties` e `http://localhost:8089` em `application-local.properties`; verificar com `./mvnw verify` que o contexto sobe
- [x] 3.2 Criar `ProviderOutcome` (selado) e `ProviderGateway` com a classificação da decisão 6, incluindo o `Retry-After` em segundos e em data HTTP; `ProviderGatewayIntegrationTests` com `WireMockExtension` em porta dinâmica cobrindo `200`, `400`, `404`, `500`, `408`, timeout com read timeout de 500 ms, `Fault.CONNECTION_RESET_BY_PEER`, `429` com `Retry-After: 120`, `503` com data HTTP, `429` sem header e `Retry-After` inválido; `verify` do corpo JSON e do header `Idempotency-Key`

## 4. Worker

- [x] 4.1 Adicionar ao `ActivationRequestRepository` as consultas nativas de reserva e de reconciliação com `FOR UPDATE SKIP LOCKED` (design, decisões 3 e 9); teste contra o PostgreSQL com duas transações simultâneas (`TransactionTemplate` + `CountDownLatch`): 4 solicitações vencidas, lotes de 2, conjuntos disjuntos e a segunda reserva sem esperar a primeira; teste que solicitações com `nextAttemptAt` futuro e status diferente de `PENDING` não são reservadas
- [x] 4.2 Criar `ActivationDispatcher.dispatchDue()` com reserva, chamada fora de transação e resultado por item com checagem de versão (decisões 3 e 4); `ProviderIntegrationTests` (`@SpringBootTest` + `TestcontainersConfiguration` + WireMock, `max-attempts=3`, backoff curto) chamando `dispatchDue()` direto: `200` → `COMPLETED` com uma chamada; `400` → `FAILED`/`PROVIDER_REJECTED` sem nova chamada na rodada seguinte; `500` → `PENDING` com `attempts` 1 e `nextAttemptAt` na faixa do backoff; timeout → `PENDING`; `429` com `Retry-After: 120` → `nextAttemptAt` em T + 120 s; `500` seguido de `200` (cenários do WireMock) → `COMPLETED` com as duas chamadas usando a mesma `Idempotency-Key`; três `500` → `FAILED`/`MAX_ATTEMPTS_EXCEEDED` com exatamente 3 chamadas; solicitação `PENDING` gravada direto no banco é enviada; `nextAttemptAt` futuro não é enviado
- [x] 4.3 Criar `ActivationDispatcher.reconcileStale()` (decisão 9); testes em `ProviderIntegrationTests`: `PROCESSING` com `updated_at` antigo (ajustado via `JdbcTemplate`) volta a `PENDING` com `attempts++`; `PROCESSING` recente não muda; no limite de tentativas vai para `FAILED`/`MAX_ATTEMPTS_EXCEEDED`; resultado tardio (versão diferente da reservada) é descartado sem exceção e a solicitação continua `PENDING`
- [x] 4.4 Criar `ActivationDispatchScheduler` com `@EnableScheduling`, os dois `@Scheduled(fixedDelayString = ...)` e `@ConditionalOnBooleanProperty("activation.dispatch.enabled")` (decisão 12); `ActivationDispatchSchedulingIntegrationTests` com o agendamento ligado e WireMock: criação pela API responde `201` `PENDING` e Awaitility espera `GET` devolver `COMPLETED`; com o provedor respondendo `500` após atraso maior que o read timeout, a criação continua respondendo `201` na hora

## 5. Ambiente, documentação e verificação final

- [ ] 5.1 `compose.yaml`: `PROVIDER_URL=http://wiremock:8080` no `app` e `depends_on` do `wiremock` saudável; verificar com `docker compose up --build --wait` que uma solicitação criada chega a `COMPLETED` pelo `GET`
- [ ] 5.2 Escrever `docs/adr/0002-processamento-assincrono-outbox.md`, `docs/adr/0003-retry-e-idempotencia-com-provedor.md` (inclui `@Retryable`, circuit breaker como evolução e a premissa de deduplicação do provedor) e `docs/adr/0005-transicao-processing-pending.md` (por que a falha temporária volta para `PENDING` e não fica em `PROCESSING` com contador, e o diagrama ajustado); atualizar o README: tabela de comportamento por retorno do provedor, processamento assíncrono e garantia de envio, reconciliação, `failureReason` nos exemplos da API, `PROVIDER_URL` e as propriedades `activation.dispatch.*` e de timeout (com a regra `stale-after` > timeouts), contrato suposto do provedor; rodar `./mvnw verify` e verificar que passa

## Workflow follow-up

- `openspec validate add-provider-integration --strict` e commit `docs(spec): proposta add-provider-integration`.
- Depois da implementação: `/opsx:archive`, commit `docs(spec): arquiva add-provider-integration`, PR para `main` com merge commit e `[x]` na Etapa 4 de `docs/roteiro.md`.

# Design

## Context

Estado atual (motivação em proposal.md, Why):

- `ActivationRequest` já tem `attempts`, `nextAttemptAt`, `@Version` e as transições `startProcessing`, `complete`, `fail` e `scheduleRetry` (`PROCESSING → PENDING`, `attempts++`). Toda solicitação nasce `PENDING` com `nextAttemptAt = createdAt`.
- `ActivationRequestService.create` não é `@Transactional` (o insert roda na transação do repositório) e não chama nada externo.
- `V1` criou os índices da listagem; `V2`, a constraint única de `external_id`. Nenhum índice atende "PENDING vencido" nem "PROCESSING antigo".
- Não há cliente HTTP no classpath. O `compose.yaml` já sobe o WireMock (`wiremock/wiremock:3.13.2`, porta `8089`) com um mapping `200` para `POST /provider/v1/activate`, mas a aplicação não aponta para ele.
- Variáveis obrigatórias seguem o padrão `${DB_URL}` em `application.properties`; o perfil `local` dá valores para desenvolvimento.
- Requisitos em `specs/provider-integration/spec.md` e `specs/activation-requests/spec.md`.

## Goals / Non-Goals

**Goals:**
- Nenhuma solicitação gravada fica sem envio, mesmo com queda da aplicação ou várias instâncias.
- Nenhuma transação de banco aberta durante a chamada HTTP.
- Classificação de cada retorno do provedor testável com WireMock, e o backoff testável sem Spring.

**Non-Goals:**
- Circuit breaker implementado (só descrito, decisão 11).
- Métricas e logs em JSON do worker (change `add-observability-and-delivery`).
- Propagar `traceId` até o provedor (change 6).
- Autenticação com o provedor: o enunciado não define credenciais para ele.
- Reprocessar manualmente uma solicitação `FAILED`. O `failureReason` permite achá-las; a ação manual fica para depois.

## Decisions

**1. Assíncrono: o `POST` não espera o provedor (ADR 0002).**
A criação grava `PENDING` e responde `201`. O provedor lento ou fora do ar não afeta o tempo de resposta nem esgota as threads do Tomcat. Alternativa descartada: chamar o provedor dentro do `POST`. O tempo de resposta passaria a depender do provedor, e uma queda entre o commit e a chamada deixaria a solicitação sem envio.

**2. A própria tabela é o outbox (ADR 0002).**
Não existe evento separado para publicar: o estado `PENDING` com `next_attempt_at <= now` já é "falta enviar". O commit da criação é, ao mesmo tempo, o registro do envio pendente. Uma queda logo depois do commit não perde nada, porque o worker encontra a linha na próxima rodada. Alternativas descartadas: tabela de outbox separada (duplicaria o estado sem ganho, já que o único consumidor é o worker) e `@TransactionalEventListener(AFTER_COMMIT)` (o evento vive em memória e se perde na queda).

**3. Ciclo do worker em três passos.**
`ActivationDispatcher.dispatchDue()`:
1. **Reserva** (transação curta): consulta nativa no `ActivationRequestRepository`:
   ```sql
   SELECT * FROM activation_request
    WHERE status = 'PENDING' AND next_attempt_at <= :now
    ORDER BY next_attempt_at
    LIMIT :batchSize
      FOR UPDATE SKIP LOCKED
   ```
   Cada linha passa por `startProcessing(now)` e a transação faz commit. Sai da transação uma lista de `ClaimedRequest(id, version, externalId, customerId, productCode)`.
2. **Chamada** (sem transação): uma chamada ao provedor por item, em sequência. O resultado é classificado em `ProviderOutcome` (decisão 6).
3. **Resultado** (uma transação curta por item): relê a solicitação pelo `id` e aplica o desfecho com os métodos de domínio.

`SKIP LOCKED` faz cada instância pular as linhas já reservadas por outra, sem esperar: várias instâncias dividem o trabalho sem lock distribuído. A reserva usa `FOR UPDATE` em vez de só confiar no `@Version`, porque com lock otimista duas instâncias leriam as mesmas linhas e uma delas perderia todo o lote no commit. Alternativa descartada: `@Lock(PESSIMISTIC_WRITE)` com hint de timeout `-2` do Hibernate. Funciona, mas a consulta nativa deixa o `SKIP LOCKED` explícito para quem lê.

**4. Resultado tardio é descartado pela versão.**
No passo 3, o desfecho só é aplicado se a versão atual for a mesma do `ClaimedRequest`. Uma versão diferente significa que a reconciliação (decisão 9) já mexeu na linha. Nesse caso, o resultado é registrado em log e descartado; a solicitação será reenviada com a mesma `Idempotency-Key`, e o provedor deduplica. Se a reconciliação e o passo 3 gravarem ao mesmo tempo, o `@Version` faz um dos dois falhar com `ObjectOptimisticLockingFailureException`; no passo 3 essa exceção também leva ao descarte.

**5. Cliente `@HttpExchange` com `@ImportHttpServices`.**
```java
@HttpExchange("/provider/v1")
interface ProviderClient {
    @PostExchange("/activate")
    void activate(@RequestHeader("Idempotency-Key") UUID key, @RequestBody ActivationPayload payload);
}
```
Registrado com `@ImportHttpServices(group = "provider", types = ProviderClient.class)` em `activation/infrastructure/provider`. Base URL e timeouts vêm das propriedades do Boot 4 para o grupo:
```properties
spring.http.serviceclient.provider.base-url=${PROVIDER_URL}
spring.http.serviceclient.provider.connect-timeout=${PROVIDER_CONNECT_TIMEOUT:2s}
spring.http.serviceclient.provider.read-timeout=${PROVIDER_READ_TIMEOUT:5s}
```
Sem Apache, Jetty nem Reactor no classpath, o Boot usa o `HttpClient` do JDK, o que basta. `ActivationPayload(externalId, customerId, productCode)` é um record. O contrato do provedor não é definido no enunciado; o corpo com esses três campos é uma suposição registrada no README.

**6. Classificação do retorno em `ProviderGateway`.**
Um adaptador em `infrastructure/provider` chama o `ProviderClient` e converte o resultado em `ProviderOutcome`, uma interface selada:

| Retorno | `ProviderOutcome` | Efeito |
|---|---|---|
| `2xx` | `Accepted` | `complete` |
| `400` e demais `4xx`, exceto `408` e `429` | `Rejected(status)` | `fail(PROVIDER_REJECTED)` |
| `5xx` (exceto `503` com `Retry-After`), `408`, `429` sem `Retry-After` | `TemporaryFailure(null)` | backoff |
| `429` ou `503` com `Retry-After` válido | `TemporaryFailure(retryAt)` | `nextAttemptAt = retryAt` |
| `ResourceAccessException` (timeout, conexão recusada) | `TemporaryFailure(null)` | backoff |
| Outra exceção | `TemporaryFailure(null)`, log `ERROR` | backoff |

O `RestClient` lança `HttpClientErrorException`/`HttpServerErrorException` (ambas `RestClientResponseException`, com status e headers) para `4xx`/`5xx`, e `ResourceAccessException` para I/O. O `Retry-After` aceita segundos ou data HTTP (`DateTimeFormatter.RFC_1123_DATE_TIME`); valor inválido é ignorado e uma data no passado vale "agora". O resto da aplicação não vê exceções do `RestClient`.

**7. Backoff exponencial com jitter em `RetryPolicy`.**
`D = min(max, base × 2^(n-1))` e atraso sorteado em `[D/2, D]` ("equal jitter"). Metade fixa garante que o atraso nunca é zero; a metade sorteada espalha as novas tentativas de várias solicitações que falharam juntas. `RetryPolicy` é uma classe pura que recebe `base`, `max` e um `RandomGenerator`, testável sem Spring com um gerador fixo. Alternativa descartada: "full jitter" (`[0, D]`), que pode reenviar imediatamente durante uma queda.

**8. Limite de tentativas no domínio.**
Novo método `ActivationRequest.registerTemporaryFailure(nextAttemptAt, maxAttempts, now)`: incrementa `attempts`; se `attempts >= maxAttempts`, vai para `FAILED` com `MAX_ATTEMPTS_EXCEEDED`; senão, delega para a transição `PENDING` já existente. `attempts` passa a contar todas as falhas temporárias, inclusive a última. `fail(now)` vira `fail(FailureReason, now)`, com o enum `FailureReason { PROVIDER_REJECTED, MAX_ATTEMPTS_EXCEEDED }` no domínio, e `failureReason` volta a `null` em qualquer outra transição. Padrão: `max-attempts=5`.

**9. Reconciliação de `PROCESSING` antigo.**
`ActivationDispatcher.reconcileStale()` roda em intervalo próprio:
```sql
SELECT * FROM activation_request
 WHERE status = 'PROCESSING' AND updated_at < :cutoff
 LIMIT :batchSize
   FOR UPDATE SKIP LOCKED
```
com `cutoff = now - staleAfter`. Cada linha passa por `registerTemporaryFailure` com o backoff. O caso típico é uma instância que caiu entre a reserva e o resultado. O desfecho real da chamada é desconhecido, por isso conta como tentativa, igual ao timeout. `staleAfter` (padrão `2m`) precisa ser maior que `connect-timeout + read-timeout` (padrão 7 s), senão a reconciliação disputa com chamadas ainda em andamento; essa regra fica documentada no README e nas propriedades.

**10. Por que o `@Retryable` nativo do Framework 7 não basta.**
`@Retryable` repete a chamada dentro da mesma execução e em memória: o estado da tentativa se perde se a instância cair, a thread do worker fica parada durante o backoff (com `Retry-After: 120`, dois minutos bloqueada) e outras instâncias não sabem que a solicitação está em retry. O retry persistido (`attempts` + `next_attempt_at`) sobrevive a quedas, libera o worker entre tentativas, respeita `Retry-After` longos e é visível no banco. `@Retryable` não é usado.

**11. Circuit breaker só descrito.**
Com o provedor fora do ar, cada rodada envia no máximo `batchSize` chamadas, e o backoff afasta as próximas tentativas de cada solicitação. Isso já limita a pressão sobre o provedor. Um circuit breaker (Resilience4j) evitaria até essas chamadas: depois de X falhas seguidas, o worker pularia rodadas por um tempo e testaria com uma única chamada antes de voltar. Fica como evolução no ADR 0003, porque traz uma dependência e mais um estado para explicar sem mudar o resultado final, que já é correto.

**12. Agendamento e propriedades.**
`@ConfigurationProperties("activation.dispatch")` em um record validado:
```properties
activation.dispatch.enabled=true
activation.dispatch.interval=1s
activation.dispatch.batch-size=10
activation.dispatch.max-attempts=5
activation.dispatch.backoff.base=2s
activation.dispatch.backoff.max=5m
activation.dispatch.reconciliation.interval=30s
activation.dispatch.reconciliation.stale-after=2m
```
`ActivationDispatchScheduler` tem só os métodos `@Scheduled(fixedDelayString = ...)`, que chamam o `ActivationDispatcher`, e fica sob `@ConditionalOnBooleanProperty("activation.dispatch.enabled")`, junto com `@EnableScheduling`. `fixedDelay` evita rodadas sobrepostas na mesma instância. Os testes chamam `dispatchDue()` e `reconcileStale()` direto, sem esperar o relógio, e só o teste de ponta a ponta liga o agendamento.

**13. Testes.**
- `src/test/resources/config/application.properties` desliga o agendamento (`activation.dispatch.enabled=false`) e define uma base URL qualquer para o grupo `provider`. O Boot carrega `classpath:/config/application.properties` por cima do `application.properties` principal, então os testes atuais não veem o worker mexendo no status.
- `ProviderIntegrationTests` (`@SpringBootTest` + `TestcontainersConfiguration`) com `WireMockExtension` em porta dinâmica e `@DynamicPropertySource` para a base URL; read timeout de 500 ms e backoff curto. Cobre `2xx`, `400`, `500`, timeout (`withFixedDelay`), conexão encerrada sem resposta (`Fault.CONNECTION_RESET_BY_PEER`), `429`/`503` com `Retry-After`, nova tentativa com a mesma chave (cenários do WireMock), limite de tentativas e o contrato da chamada (`verify` de corpo e header).
- `ActivationDispatchSchedulingIntegrationTests` liga o agendamento e usa Awaitility para esperar `COMPLETED` pela API.
- Reserva concorrente e reconciliação testadas contra o PostgreSQL real (`SKIP LOCKED` não existe em H2).

**14. Dependências.**
- `spring-boot-starter-restclient`: traz o `RestClient` e a autoconfiguração de HTTP Service clients (`spring.http.serviceclient.*`). Sem ela, `@ImportHttpServices` não tem as propriedades de grupo.
- `org.wiremock:wiremock-standalone:3.13.2` (test): mesma versão da imagem do `compose.yaml`. O artefato principal do WireMock 3 depende de Jetty 11, e o BOM do Boot 4.1.2 força Jetty 12 (`jetty.version` 12.1.14), o que quebra o servidor do WireMock. O `standalone` traz as dependências sombreadas e escapa do conflito. Alternativa: `wiremock-jetty12`, que exige alinhar mais artefatos; o WireMock 4 ainda é beta.
- `org.awaitility:awaitility` (test): versão gerenciada pelo BOM do Boot. Espera o processamento assíncrono sem `Thread.sleep`.

## Risks / Trade-offs

- [Provedor recebe a mesma ativação mais de uma vez (timeout, reconciliação, resultado tardio)] → `Idempotency-Key` fixa por solicitação; a deduplicação do lado do provedor é premissa registrada no ADR 0003.
- [Latência entre a criação e o envio, até `interval`] → intervalo de 1 s configurável. Alternativa futura: acordar o worker logo após o commit, mantendo a varredura como rede de segurança.
- [Chamadas em sequência limitam a vazão a `batchSize` por rodada] → suficiente para o desafio; paralelizar por item é evolução sem mudar o modelo.
- [`Retry-After` muito longo enviado pelo provedor] → é respeitado sem teto; a solicitação fica `PENDING` até lá e aparece na listagem por status.
- [`staleAfter` menor que os timeouts gera reconciliação de chamada viva] → padrões com folga grande (2 min × 7 s) e resultado tardio descartado pela versão (decisão 4).
- [Testes com WireMock em porta dinâmica criam contextos Spring próprios] → só duas classes novas usam WireMock; o custo de subir o contexto é aceito.
- [Rejeição (`4xx`) pode ser erro de configuração nosso, como `401`, e não do pedido] → vira `FAILED` com `PROVIDER_REJECTED` e log `WARN` com o status; reprocessamento manual fica fora do escopo.

## Migration Plan

- `V3__provider_dispatch.sql`, só aditiva:
  ```sql
  ALTER TABLE activation_request
      ADD COLUMN failure_reason varchar(30)
          CONSTRAINT ck_activation_request_failure_reason
          CHECK (failure_reason IN ('PROVIDER_REJECTED', 'MAX_ATTEMPTS_EXCEEDED'));

  -- Reserva do worker: só PENDING, na ordem de next_attempt_at.
  CREATE INDEX ix_activation_request_pending_due
      ON activation_request (next_attempt_at) WHERE status = 'PENDING';

  -- Reconciliação: só PROCESSING, por updated_at.
  CREATE INDEX ix_activation_request_processing_updated
      ON activation_request (updated_at) WHERE status = 'PROCESSING';
  ```
  Índices parciais ficam pequenos, porque a maioria das linhas acaba `COMPLETED` ou `FAILED`.
- Solicitações `PENDING` já gravadas são enviadas na primeira rodada após o deploy.
- Nova variável obrigatória `PROVIDER_URL`: o deploy falha na subida sem ela, de propósito. `compose.yaml` define `PROVIDER_URL=http://wiremock:8080` e faz o `app` esperar o `wiremock` saudável; o perfil `local` usa `http://localhost:8089`.
- Rollback: `activation.dispatch.enabled=false` para o envio sem novo deploy. A `V3` é aditiva e não precisa ser desfeita para voltar à versão anterior do código.

## Open Questions

- O provedor real deduplica pela `Idempotency-Key`, e por quanto tempo guarda a chave? A resposta muda o `max-attempts` e o `backoff.max` aceitáveis, não o desenho.

# Design

## Context

Estado atual (ver proposal.md, Why):

- Boot 4.1.2 com `webmvc`, `data-jpa`, `flyway`, `validation`, `actuator` e os `-test` correspondentes. PostgreSQL 18 via Testcontainers (`TestcontainersConfiguration`) nos testes.
- Nenhuma migração em `db/migration`, nenhuma entidade, nenhum controller.
- `@NullMarked` só no pacote raiz; cada pacote novo precisa do próprio `package-info.java` (decisão 3 da change `bootstrap-project`).
- `spring.jpa.open-in-view=false` já configurado.
- Requisitos em `specs/activation-requests/spec.md` e `specs/error-handling/spec.md`.

## Goals / Non-Goals

**Goals:**
- Domínio testável sem Spring: estados e transições num só lugar, sem setters públicos.
- Um único ponto de tratamento de erro para toda a API, incluindo os erros que o próprio Spring MVC gera (405, 415, JSON malformado).
- `traceId` com origem padrão de mercado, que a change `add-observability-and-delivery` só amplia (propagação ao provedor, logs JSON, exportação).

**Non-Goals:**
- `UNIQUE` em `external_id` e semântica de retry (change `add-duplicate-prevention`). A migração desta change não cria a constraint; a change 3 cria em migração própria.
- Coluna de motivo de falha, worker e uso de `attempts`/`nextAttemptAt` (change `add-provider-integration`). Os campos existem agora porque fazem parte do modelo pedido e do método de domínio `PROCESSING → PENDING`.
- Dono da solicitação (`client_id`) e autenticação (change `add-api-security`).
- Exportação de spans, logs JSON, métricas.
- Mapear a exceção de transição inválida para um status HTTP: nenhum endpoint desta change dispara transição.

## Decisions

**1. Pacotes por feature.**
```
academy.jairo.springdesafiotecnico
├── activation
│   ├── api             ActivationRequestController, DTOs (records), mapeamento
│   ├── application     ActivationRequestService, ActivationRequestNotFoundException
│   ├── domain          ActivationRequest, ActivationStatus, InvalidStatusTransitionException
│   └── infrastructure  ActivationRequestRepository, specifications de filtro
└── shared
    └── web             ApiExceptionHandler (ProblemDetail + traceId), configuração do Clock
```
Cada pacote com `package-info.java` `@NullMarked`. Dependência só para dentro: `api → application → domain`; `infrastructure → domain`. Sem interfaces "port" para o repositório: uma implementação só, padrão desnecessário aqui (item 9). A regra de dependência vira ArchUnit na change 6.

**2. Entidade JPA é o próprio modelo de domínio.**
`ActivationRequest` é `@Entity` com construtor `protected` sem argumentos (exigido pelo JPA), fábrica estática `create(externalId, customerId, productCode, Instant now)` e métodos de transição: `startProcessing(now)`, `complete(now)`, `fail(now)`, `scheduleRetry(nextAttemptAt, now)`. Nenhum setter público. O teste unitário instancia direto, sem Spring nem banco.
Alternativa: modelo de domínio puro + entidade JPA separada + mapper. Rejeitada: dobra o código para um agregado de sete campos, sem ganho real; o enunciado pede DTOs separados da entidade, não domínio separado da entidade.

**3. Máquina de estados no enum.**
`ActivationStatus.canTransitionTo(target)` com `switch` exaustivo sobre o estado atual. A entidade valida antes de mudar qualquer campo e lança `InvalidStatusTransitionException(from, to)` (unchecked, pacote `domain`). Transições permitidas conforme a spec; `PROCESSING → PENDING` só por `scheduleRetry`, que incrementa `attempts` e grava `nextAttemptAt`.
Falha temporária volta para `PENDING` (e não fica em `PROCESSING` com contador) porque o worker da change 4 busca só `PENDING` com `next_attempt_at <= now`; `PROCESSING` significa "chamada em andamento", o que permite detectar solicitações presas. O ADR dessa decisão é escrito na change 4, junto com o worker.

**4. Tempo injetado.**
Bean `Clock` (`Clock.systemUTC()`) usado pelo serviço; o domínio recebe `Instant now` como argumento. Instantes truncados para microssegundos ao entrar no domínio, para que o valor devolvido no `POST` seja idêntico ao lido do PostgreSQL (`timestamptz` guarda microssegundos) — sem isso o cenário "Solicitação criada fica persistida" falha.

**5. Identidade e versão.**
`id` UUID gerado na aplicação (`UUID.randomUUID()`) dentro de `create`, para o `Location` não depender de round-trip. `@Version Long version` (lock otimista, anotado `@Nullable`). Com `id` atribuído, o Spring Data decide entre `persist` e `merge` pela versão: versão nula em entidade nova leva a `persist`. Por isso a versão é `Long` (wrapper), não `long`.
`nextAttemptAt` nasce igual a `createdAt` (elegível na hora), coluna `NOT NULL`, o que simplifica a consulta do worker.

**6. Schema (migração `V1__create_activation_request.sql`).**
```sql
CREATE TABLE activation_request (
  id              uuid         PRIMARY KEY,
  external_id     varchar(64)  NOT NULL,
  customer_id     varchar(64)  NOT NULL,
  product_code    varchar(50)  NOT NULL,
  status          varchar(20)  NOT NULL CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED')),
  attempts        integer      NOT NULL DEFAULT 0,
  next_attempt_at timestamptz  NOT NULL,
  created_at      timestamptz  NOT NULL,
  updated_at      timestamptz  NOT NULL,
  version         bigint       NOT NULL
);
CREATE INDEX ix_activation_request_customer_created ON activation_request (customer_id, created_at DESC);
CREATE INDEX ix_activation_request_status_created   ON activation_request (status, created_at DESC);
```
Índices:
- `(customer_id, created_at DESC)`: filtro mais seletivo (`customerId`, com ou sem `status`) já devolve na ordem da paginação, sem sort. Com `status` junto, o Postgres filtra o status nas linhas do cliente, poucas.
- `(status, created_at DESC)`: filtro só por status (ex.: operação listando `FAILED`), ordenado. Também atende, na change 4, a busca do worker por `PENDING`; se lá for preciso, vira índice parcial em `next_attempt_at`.
- Sem índice em `external_id` agora: a change 3 cria `UNIQUE`, que já é índice.
- Listagem sem filtro usa scan + sort; aceitável para o desafio, registrado no README.
`spring.jpa.hibernate.ddl-auto=validate` garante que entidade e migração batem; um teste de contexto falha se divergirem. `status` como `@Enumerated(STRING)`.

**7. API.**
- DTOs `record`: `CreateActivationRequest(externalId, customerId, productCode)` com `@NotBlank`, `@Size(max)` e `@Pattern`; `ActivationRequestResponse(id, externalId, customerId, productCode, status, createdAt, updatedAt)`; `PageResponse<T>(content, page, size, totalElements, totalPages)`. Campos internos (`attempts`, `nextAttemptAt`, `version`) não aparecem na resposta.
- Regex: `externalId`/`customerId` `^[A-Za-z0-9_-]{1,64}$`; `productCode` `^[A-Z0-9_-]{1,50}$`. `@Size` separado para a mensagem de tamanho ser específica. Mensagens em português (`message` nas anotações).
- `POST` devolve `ResponseEntity.created(uri)`, URI montada com `ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")`.
- Listagem: parâmetros `status` (enum, conversão do Spring), `customerId` (mesmo padrão de validação), `page` `@Min(0)`, `size` `@Min(1) @Max(100)`; o controller monta `PageRequest.of(page, size, Sort.by(DESC, "createdAt", "id"))`. Ordenação fixa e não exposta, para o cliente não ordenar por coluna sem índice. Filtros via `JpaSpecificationExecutor` (combinar filtros opcionais com `@Query` e parâmetros nulos esbarra em inferência de tipo do Postgres).
Alternativa: `Pageable` direto no controller + `PagedModel` do Spring Data. Rejeitada: aceita `sort` arbitrário do cliente e o formato do JSON fica acoplado à versão do Spring Data.

**8. Tratamento de erros.**
`@RestControllerAdvice ApiExceptionHandler extends ResponseEntityExceptionHandler`. Herdar cobre os erros do MVC (405, 415, 400 de JSON malformado e de parâmetro com tipo errado) já em ProblemDetail. Sobrescreve `handleExceptionInternal`/`createResponseEntity` para todo corpo ProblemDetail ganhar `traceId` e `instance`, e para trocar o `detail` padrão de `HttpMessageNotReadableException` (que pode trazer texto do parser) por mensagem fixa.
Handlers próprios:

| Exceção | Status | `type` (sufixo) | `title` |
|---|---|---|---|
| `MethodArgumentNotValidException` | 400 | `validation-error` | Dados inválidos (+ `errors[]`) |
| `HandlerMethodValidationException` (parâmetros de query) | 400 | `validation-error` | Dados inválidos (+ `errors[]`) |
| `MethodArgumentTypeMismatchException` (UUID, status) | 400 | `invalid-parameter` | Parâmetro inválido |
| `HttpMessageNotReadableException` | 400 | `malformed-body` | Corpo da requisição inválido |
| `ActivationRequestNotFoundException` | 404 | `not-found` | Solicitação não encontrada |
| `Exception` | 500 | `internal-error` | Erro interno (log `ERROR` com stack trace, só no servidor) |

`type` = `https://problems.ativacao.example/<sufixo>` (`.example` é TLD reservado, deixa claro que é identificador, não página). Constantes num lugar só.
`server.error.include-stacktrace=never` e `include-message=never` explícitos, para os erros que escapam do MVC (ex.: filtro) também não vazarem.

**9. Origem do `traceId`: Micrometer Tracing com bridge OpenTelemetry.**
O Boot auto-configura a observação das requisições HTTP: cada requisição recebe um span; `traceId` e `spanId` vão para o MDC e para o padrão de log (correlação nativa do Boot). O handler lê o `traceId` do `Tracer` (`tracer.currentSpan()`), que é o mesmo valor do MDC.
Por que este e não um filtro próprio que grava um id no MDC:
- Formato W3C Trace Context (32 hex), igual ao exemplo do enunciado; se o chamador mandar `traceparent`, o trace dele é continuado.
- Na change 6, o `RestClient` construído pelo `RestClient.Builder` do Boot propaga `traceparent` ao provedor sem código; exportar para um coletor OTLP vira configuração. O filtro próprio teria de ser reescrito ou mantido em paralelo.
- Correlação de logs pronta, sem código.
Custo: uma dependência (bridge + SDK OpenTelemetry) e overhead de criar spans por requisição, desprezível aqui.
Dependência: o módulo do Boot 4 que traz Micrometer Tracing + bridge OpenTelemetry **sem** exportador. Candidatos a confirmar na documentação oficial do Boot 4.1 / start.spring.io antes de adicionar: `spring-boot-starter-opentelemetry` (inclui exportador OTLP; exige desligar exportação) ou `spring-boot-micrometer-tracing-opentelemetry` + `micrometer-tracing-bridge-otel`. Preferir o que não traz exportador; se só houver o starter, desligar a exportação por propriedade até a change 6.
Testes: `@SpringBootTest` não configura tracing de verdade por padrão; os testes que verificam `traceId` usam `@AutoConfigureTracing` (pacote confirmado no Boot 4 na implementação).
Se não houver span corrente (só ocorre fora de requisição HTTP), o campo `traceId` é omitido em vez de inventado.
Alternativa: filtro `OncePerRequestFilter` que gera UUID sem hífens, grava no MDC e devolve em header. Rejeitada pelos motivos acima.

**10. Estratégia de testes.**
- Unitário, sem Spring: `ActivationRequestTest` (todas as transições válidas e todas as inválidas via `@ParameterizedTest`, `scheduleRetry`, estado inicial).
- Integração com Testcontainers: `ActivationRequestApiIntegrationTests` (`@SpringBootTest` + `@AutoConfigureMockMvc` + `@AutoConfigureTracing`) cobre criação, validação, consulta, 404, listagem e formato dos erros, contra PostgreSQL real.
- Integração de persistência: `@DataJpaTest` com Testcontainers para o lock otimista (duas cópias, segunda gravação falha com `ObjectOptimisticLockingFailureException`).
- Erro inesperado: `@SpringBootTest` com `@MockitoBean` no serviço lançando `RuntimeException`; `OutputCaptureExtension` confere o `traceId` no log.
Sem `@WebMvcTest` para a API: o slice teria `Tracer` no-op e não cobriria `traceId`; o custo de subir o contexto com Testcontainers já é pago pelos outros testes (contexto reaproveitado pelo cache do Spring).

## Risks / Trade-offs

- [O starter de OpenTelemetry pode tentar exportar spans para `localhost:4318` e poluir o log com erros] → preferir o módulo sem exportador; se não der, desligar exportação por propriedade, confirmada na documentação. O teste de contexto com output capturado revela o problema.
- [Amostragem padrão de 10% poderia deixar requisições sem `traceId` no MDC] → spans não amostrados continuam com `traceId` válido no OpenTelemetry; os testes fazem várias requisições e verificam presença em todas. Se falhar, `management.tracing.sampling.probability=1.0` (sem exportador, não há custo de envio).
- [Entidade como modelo de domínio acopla o domínio ao JPA] → acoplamento só por anotações; regras de transição não dependem de JPA e são testadas sem ele. Revisitar se surgir segundo meio de persistência.
- [Listagem sem filtro faz scan na tabela] → aceitável no volume do desafio; README descreve índice adicional `(created_at DESC)` ou paginação por cursor como evolução.
- [`productCode` só em maiúsculas pode recusar códigos válidos de um sistema de origem] → regra documentada no README; afrouxar a regex é mudança de uma linha e de spec.

## Migration Plan

Primeira migração do schema (`V1`); não há dados. Rollback: reverter o merge do PR e recriar o banco (`docker compose down -v`).

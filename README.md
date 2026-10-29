# Serviço de ativação de produtos

[![CI](https://github.com/jairoacademy/spring-desafio-tecnico/actions/workflows/ci.yml/badge.svg)](https://github.com/jairoacademy/spring-desafio-tecnico/actions/workflows/ci.yml)

Desafio técnico Java (Spring Boot 4.1.2, Java 21), desenvolvido com Spec-Driven Development (OpenSpec + Claude Code).

- Enunciado: [docs/desafio-tecnico-java.md](docs/desafio-tecnico-java.md)
- Plano de execução: [docs/plano-sdd-openspec.md](docs/plano-sdd-openspec.md)
- Decisões técnicas (ADRs): [docs/adr/](docs/adr/)

> Em construção. Decisões técnicas serão documentadas ao longo do desenvolvimento.

## Como executar

### Tudo com Docker Compose

Pré-requisito: Docker.

```bash
docker compose up --build
```

Sobe aplicação, PostgreSQL e o mock do provedor externo, cada um com health check. A aplicação só inicia depois que o banco e o mock do provedor estão saudáveis, e envia as solicitações ao mock (`PROVIDER_URL=http://wiremock:8080`). Para rodar em segundo plano e esperar todos ficarem saudáveis, use `docker compose up --build -d --wait`.

| Serviço | Porta | Descrição |
|---|---|---|
| `app` | `8080` | Aplicação |
| `postgres` | `5432` | PostgreSQL |
| `wiremock` | `8089` | Mock do provedor externo (WireMock); stubs em `wiremock/mappings/` |

```bash
curl localhost:8080/actuator/health/readiness
curl -X POST localhost:8089/provider/v1/activate
```

Para parar e apagar os dados: `docker compose down -v`.

> O enunciado pede um `docker-compose.yml` na seção Entrega. O arquivo aqui se chama `compose.yaml`, o nome padrão e preferido da [Compose Specification](https://github.com/compose-spec/compose-spec/blob/main/spec.md#compose-file); `docker-compose.yml` só é aceito por compatibilidade. O `docker compose` encontra o arquivo sem nenhuma opção extra, então ele cumpre o mesmo papel.

### Aplicação local, dependências no Docker

Pré-requisitos: Java 21 e Docker.

```bash
docker compose up -d postgres wiremock
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

No IntelliJ, informe `local` em **Active profiles** na Run Configuration. O perfil `local` aponta para o PostgreSQL (`localhost:5432`) e o mock do provedor (`localhost:8089`) do `compose.yaml`.

### Testes

```bash
./mvnw verify
```

Build e testes com um único comando. Os testes sobem o próprio PostgreSQL via Testcontainers e não dependem do `compose.yaml` (Docker precisa estar em execução). O provedor externo é simulado com WireMock em porta dinâmica, dentro do próprio teste.

`src/test/resources/config/application.properties` desliga o envio agendado nos testes (`activation.dispatch.enabled=false`): os testes do worker chamam o `ActivationDispatcher` direto, e só `ActivationDispatchSchedulingIntegrationTests` liga o agendamento.

O pipeline de CI ([.github/workflows/ci.yml](.github/workflows/ci.yml)) roda a cada push e pull request: o job `verify` executa `./mvnw -B verify` e o job `compose-smoke` sobe o ambiente com `docker compose up --build --wait` e confere readiness, usuário não-root e mock do provedor.

### Coleção Bruno

Coleção [Bruno](https://www.usebruno.com/) em [`bruno/`](bruno/). Abra a pasta no app e rode as pastas em ordem (`01` antes de `04`), ou execute `bru run --env local` dentro de `bruno/` se tiver o Bruno CLI instalado.

### Configuração do banco

| Variável | Descrição |
|---|---|
| `DB_URL` | URL JDBC do PostgreSQL |
| `DB_USERNAME` | Usuário do banco |
| `DB_PASSWORD` | Senha do banco |

O `application.properties` não tem valores padrão: sem essas variáveis, a aplicação não sobe. As credenciais do `compose.yaml` e do `application-local.properties` são apenas para desenvolvimento local. Em produção, os valores vêm de variáveis de ambiente injetadas por um gerenciador de segredos.

### Configuração do provedor e do envio

| Variável ou propriedade | Padrão | Descrição |
|---|---|---|
| `PROVIDER_URL` | — (obrigatória) | Base URL do provedor externo; a aplicação não sobe sem ela |
| `PROVIDER_CONNECT_TIMEOUT` | `2s` | Connect timeout das chamadas ao provedor |
| `PROVIDER_READ_TIMEOUT` | `5s` | Read timeout das chamadas ao provedor |
| `activation.dispatch.enabled` | `true` | Liga o envio agendado; `false` para o envio sem novo deploy |
| `activation.dispatch.interval` | `1s` | Intervalo entre rodadas do worker (`fixedDelay`) |
| `activation.dispatch.batch-size` | `10` | Solicitações reservadas por rodada (e por rodada de reconciliação) |
| `activation.dispatch.max-attempts` | `5` | Chamadas permitidas por solicitação antes de `FAILED` com `MAX_ATTEMPTS_EXCEEDED` |
| `activation.dispatch.backoff.base` | `2s` | Base do backoff exponencial |
| `activation.dispatch.backoff.max` | `5m` | Teto do backoff |
| `activation.dispatch.reconciliation.interval` | `30s` | Intervalo da reconciliação de `PROCESSING` antigo |
| `activation.dispatch.reconciliation.stale-after` | `2m` | Tempo em `PROCESSING` a partir do qual a solicitação é reconciliada |

As propriedades `activation.dispatch.*` também aceitam variável de ambiente pela convenção do Spring Boot (ex.: `ACTIVATION_DISPATCH_MAXATTEMPTS`). Os timeouts mapeiam para `spring.http.serviceclient.provider.connect-timeout` e `read-timeout`.

> `stale-after` precisa ser bem maior que `connect-timeout + read-timeout` (padrão: 2 min contra 7 s). Senão, a reconciliação devolve para `PENDING` uma chamada ainda em andamento; o resultado dela é descartado pela versão e a solicitação é reenviada com a mesma `Idempotency-Key`, mas a chamada extra é desperdício.

### Health checks

O Actuator expõe apenas o endpoint `health`, com as probes habilitadas:

| Endpoint | Uso |
|---|---|
| `GET /actuator/health/liveness` | A aplicação está viva. Não depende do banco nem do provedor externo, para que uma falha deles não reinicie a aplicação. |
| `GET /actuator/health/readiness` | A aplicação está pronta para receber tráfego. |

```bash
curl localhost:8080/actuator/health/readiness
```

Os demais endpoints do Actuator (`env`, `beans`, `configprops` etc.) não são expostos via HTTP.

## API

| Método e caminho | Descrição | Sucesso |
|---|---|---|
| `POST /api/v1/requests` | Cria uma solicitação de ativação com status `PENDING`; o envio ao provedor é assíncrono | `201` + header `Location`; `200` em retry idêntico; `409` se o `externalId` já existe com outro conteúdo |
| `GET /api/v1/requests/{id}` | Consulta uma solicitação e seu status atual | `200` (`404` se não existir) |
| `GET /api/v1/requests` | Lista solicitações, da mais recente para a mais antiga | `200` |

### Criar

```bash
curl -i -X POST localhost:8080/api/v1/requests \
  -H 'Content-Type: application/json' \
  -d '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}'
```

```http
HTTP/1.1 201
Location: http://localhost:8080/api/v1/requests/0b5c8f1e-6a51-4a3e-9d4c-2f8e7a1b3c9d
Content-Type: application/json

{
  "id": "0b5c8f1e-6a51-4a3e-9d4c-2f8e7a1b3c9d",
  "externalId": "EXT-12345",
  "customerId": "CUST-10001",
  "productCode": "INTERNET-500",
  "status": "PENDING",
  "failureReason": null,
  "createdAt": "2026-10-29T13:00:00.123456Z",
  "updatedAt": "2026-10-29T13:00:00.123456Z"
}
```

Datas são ISO-8601 em UTC (sufixo `Z`), com precisão de microssegundos.

A resposta sai sem esperar o provedor. Depois, o `status` muda sozinho (ver [Integração com o provedor](#integração-com-o-provedor)): em poucos segundos, com o provedor respondendo, a solicitação chega a `COMPLETED` ou `FAILED`.

O `externalId` é único em todo o serviço (ver [Duplicidade](#duplicidade)). Repetir o mesmo corpo devolve `200` com o registro existente, no status atual, sem header `Location`. O mesmo `externalId` com `customerId` ou `productCode` diferente devolve `409`:

```bash
curl -i -X POST localhost:8080/api/v1/requests \
  -H 'Content-Type: application/json' \
  -d '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-900"}'
```

```http
HTTP/1.1 409
Content-Type: application/problem+json

{
  "type": "https://problems.ativacao.example/duplicate-external-id",
  "title": "Solicitação duplicada",
  "status": 409,
  "detail": "Já existe solicitação com externalId EXT-12345 e conteúdo diferente.",
  "instance": "/api/v1/requests",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "conflictingFields": ["productCode"]
}
```

### Consultar

```bash
curl localhost:8080/api/v1/requests/0b5c8f1e-6a51-4a3e-9d4c-2f8e7a1b3c9d
```

```json
{
  "id": "0b5c8f1e-6a51-4a3e-9d4c-2f8e7a1b3c9d",
  "externalId": "EXT-12345",
  "customerId": "CUST-10001",
  "productCode": "INTERNET-500",
  "status": "FAILED",
  "failureReason": "PROVIDER_REJECTED",
  "createdAt": "2026-10-29T13:00:00.123456Z",
  "updatedAt": "2026-10-29T13:00:01.204518Z"
}
```

`failureReason` é `null` fora de `FAILED`. Em `FAILED`, vale `PROVIDER_REJECTED` (o provedor recusou o pedido) ou `MAX_ATTEMPTS_EXCEEDED` (limite de tentativas atingido). O campo aparece também na criação e na listagem; `GET /api/v1/requests?status=FAILED` lista as falhas definitivas.

### Listar

```bash
curl 'localhost:8080/api/v1/requests?customerId=CUST-10001&status=PENDING&page=0&size=20'
```

| Parâmetro | Obrigatório | Regra |
|---|---|---|
| `status` | não | `PENDING`, `PROCESSING`, `COMPLETED` ou `FAILED` |
| `customerId` | não | mesma regra do campo na criação |
| `page` | não | a partir de `0`; padrão `0` |
| `size` | não | `1` a `100`; padrão `20` |

```json
{
  "content": [ { "id": "...", "status": "PENDING", "...": "..." } ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

A ordenação é fixa (`createdAt` decrescente, `id` como desempate) e não é exposta ao cliente: assim nenhuma consulta ordena por coluna sem índice.

### Regras de validação da criação

| Campo | Obrigatório | Tamanho | Caracteres aceitos |
|---|---|---|---|
| `externalId` | sim | 1 a 64 | letras, dígitos, `-` e `_` |
| `customerId` | sim | 1 a 64 | letras, dígitos, `-` e `_` |
| `productCode` | sim | 1 a 50 | letras **maiúsculas**, dígitos, `-` e `_` |

Se a validação falha, nenhuma solicitação é gravada. Corpo que não é JSON válido, ou com campo de tipo incompatível, também resulta em `400`.

### Formato de erro

Todo erro da API é um [Problem Details (RFC 9457)](https://www.rfc-editor.org/rfc/rfc9457) com `Content-Type: application/problem+json` e o campo `traceId`:

```json
{
  "type": "https://problems.ativacao.example/validation-error",
  "title": "Dados inválidos",
  "status": 400,
  "detail": "Um ou mais campos são inválidos.",
  "instance": "/api/v1/requests",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "errors": [
    { "field": "customerId", "message": "é obrigatório" }
  ]
}
```

| Situação | Status | `type` (sufixo) |
|---|---|---|
| Campos inválidos (corpo ou parâmetros da listagem) | `400` | `validation-error` (com `errors`) |
| Parâmetro com tipo inválido (`id` não UUID, `status` desconhecido) | `400` | `invalid-parameter` |
| Corpo ilegível | `400` | `malformed-body` |
| Solicitação inexistente | `404` | `not-found` |
| `externalId` já registrado com outro conteúdo | `409` | `duplicate-external-id` (com `conflictingFields`) |
| Erro inesperado | `500` | `internal-error` |
| Demais erros do HTTP (`405`, `415`, ...) | o do erro | `about:blank` |

O `type` usa o domínio reservado `.example`: é um identificador, não uma página. Respostas de erro nunca trazem stack trace, nome de classe, mensagem da exceção original nem detalhes do parser JSON. Um erro `500` é registrado no log em nível `ERROR` com o mesmo `traceId` devolvido ao cliente, o que permite localizar a falha a partir da resposta.

## Decisões técnicas

### Estados da solicitação

Decisão registrada no [ADR 0005](docs/adr/0005-transicao-processing-pending.md).

```
PENDING ──► PROCESSING ──┬──► COMPLETED
   ▲                     ├──► FAILED  (PROVIDER_REJECTED ou MAX_ATTEMPTS_EXCEEDED)
   └─────────────────────┘
   falha temporária (attempts++, nextAttemptAt agendado)
```

- `COMPLETED` e `FAILED` são finais. Qualquer outra transição lança `InvalidStatusTransitionException` sem alterar a solicitação.
- Uma falha temporária leva `PROCESSING → PENDING`, incrementa `attempts` e agenda `nextAttemptAt`. Assim, `PROCESSING` significa só "chamada ao provedor em andamento", e o worker busca apenas `PENDING` com `nextAttemptAt` vencido. Na última tentativa permitida, a falha temporária leva a `FAILED` com `MAX_ATTEMPTS_EXCEEDED` (`registerTemporaryFailure`).
- Toda solicitação nasce com `attempts = 0` e `nextAttemptAt = createdAt` (elegível na hora). Esses campos e a versão (`@Version`, lock otimista) são internos e não aparecem na API.

### Integração com o provedor

Decisões registradas nos ADRs [0002](docs/adr/0002-processamento-assincrono-outbox.md) (processamento assíncrono com a tabela como outbox) e [0003](docs/adr/0003-retry-e-idempotencia-com-provedor.md) (retry e idempotência).

**Processamento assíncrono e garantia de envio.** O `POST` grava a solicitação `PENDING` e responde; o provedor lento ou fora do ar não afeta a criação. A própria tabela é o outbox: `PENDING` com `next_attempt_at` vencido significa "falta enviar", então uma queda logo após o commit não perde nada. Um worker `@Scheduled(fixedDelay)` faz, a cada rodada:

1. **Reserva**, em transação curta: `SELECT ... WHERE status = 'PENDING' AND next_attempt_at <= now ORDER BY next_attempt_at LIMIT :batchSize FOR UPDATE SKIP LOCKED`, `PROCESSING` em cada linha e commit;
2. **Chamada** ao provedor, uma por solicitação, **fora de qualquer transação**;
3. **Resultado**, uma transação curta por solicitação, aplicado só se a versão (`@Version`) ainda for a da reserva.

`SKIP LOCKED` faz cada instância pular as linhas já reservadas por outra, sem esperar: várias instâncias dividem o trabalho sem lock distribuído.

**Comportamento por retorno do provedor:**

| Retorno do provedor | Status | `failureReason` | Nova chamada |
|---|---|---|---|
| `2xx` | `COMPLETED` | `null` | não |
| `400` e demais `4xx`, exceto `408` e `429` | `FAILED` | `PROVIDER_REJECTED` | não |
| `5xx`, `408`, `429` sem `Retry-After` | `PENDING` | `null` | sim, com backoff |
| Timeout de leitura, conexão recusada ou encerrada sem resposta | `PENDING` | `null` | sim, com backoff |
| `429` ou `503` com `Retry-After` (segundos ou data HTTP) | `PENDING` | `null` | sim, no instante pedido pelo provedor |
| Qualquer falha temporária na última tentativa permitida (`max-attempts`) | `FAILED` | `MAX_ATTEMPTS_EXCEEDED` | não |

`Retry-After` inválido é ignorado (vale o backoff); data no passado vale "agora".

**Backoff exponencial com jitter.** Para a tentativa n, `D = min(max, base × 2^(n-1))` e o atraso é sorteado entre `D/2` e `D`. Com os padrões (`base=2s`, `max=5m`): 1–2 s, 2–4 s, 4–8 s, ... até 2,5–5 min. A metade fixa evita reenvio imediato; a sorteada espalha as solicitações que falharam juntas. O retry é persistido (`attempts`, `next_attempt_at`), não em memória: sobrevive a quedas, libera o worker entre tentativas e respeita `Retry-After` longos. Por isso o `@Retryable` do Spring Framework 7 não é usado.

**Idempotência.** Toda chamada leva `Idempotency-Key` com o `id` da solicitação, igual em todas as tentativas. Timeout e reconciliação podem gerar mais de uma chamada para a mesma solicitação; a deduplicação pelo provedor é uma premissa (ADR 0003).

**Reconciliação.** A cada `reconciliation.interval`, solicitações em `PROCESSING` há mais de `stale-after` (por exemplo, a instância caiu entre a reserva e o resultado) voltam para `PENDING`, contando como tentativa, ou vão para `FAILED` com `MAX_ATTEMPTS_EXCEEDED` no limite. Se a chamada antiga ainda responder depois disso, o resultado tardio é descartado pela versão e a solicitação é reenviada com a mesma `Idempotency-Key`.

**Contrato suposto do provedor.** O enunciado não define o contrato. A suposição implementada (e simulada no WireMock) é:

```http
POST /provider/v1/activate
Content-Type: application/json
Idempotency-Key: 0b5c8f1e-6a51-4a3e-9d4c-2f8e7a1b3c9d

{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}
```

O corpo da resposta não é lido; só o status e o header `Retry-After` importam. O cliente é uma interface `@HttpExchange` registrada com `@ImportHttpServices(group = "provider")`, sobre o `HttpClient` do JDK fixado em HTTP/1.1 (o upgrade h2c do HTTP/2 em `http://` não é aceito por servidores só HTTP/1.1, como o WireMock).

Circuit breaker não foi implementado: o `batch-size` por rodada e o backoff já limitam a pressão sobre um provedor fora do ar. Fica como evolução no ADR 0003.

### Duplicidade

Decisão registrada no [ADR 0001](docs/adr/0001-unicidade-external-id.md).

O `externalId` é único em todo o serviço, de qualquer cliente, e a comparação diferencia maiúsculas de minúsculas. Quem garante é o banco, com a constraint `uq_activation_request_external_id UNIQUE (external_id)` (migração `V2`).

Verificar se o `externalId` existe e depois inserir não basta. No `READ COMMITTED` do PostgreSQL, duas requisições podem fazer o `SELECT` antes de qualquer uma confirmar o `INSERT`:

```
T1: SELECT ... WHERE external_id='X'  → vazio
T2: SELECT ... WHERE external_id='X'  → vazio
T1: INSERT 'X' ; COMMIT
T2: INSERT 'X' ; COMMIT               → segundo registro
```

Um lock na aplicação não vale com mais de uma instância; só o banco enxerga todas as transações. Por isso a criação:

1. busca por `externalId`: se existe, resolve o retry sem tentar inserir (caso comum, sem exceção);
2. insere com `saveAndFlush`, que força o `INSERT` na hora;
3. se o banco recusa por violação, relê o registro vencedor e resolve como no passo 1. Se não há registro, a violação era outra e vira `500`.

O serviço não abre transação própria na criação: no PostgreSQL, depois de um erro a transação fica abortada, e a releitura precisa de outra.

| Cenário do enunciado | Resposta |
|---|---|
| Mesmo `externalId`, mesmo conteúdo (retry legítimo) | `200` com o registro existente e o status atual. O sistema de origem não sabe se a primeira chamada chegou; recebe `id` e status sem tratamento especial. Nenhum registro é criado ou alterado. |
| Mesmo `externalId`, conteúdo diferente | `409` com `conflictingFields` (ex.: `["productCode"]`). Exige ação humana. A resposta não traz `id` nem valores do registro existente. |
| Duas requisições idênticas em paralelo | Uma recebe `201`; as outras caem na violação, releem e recebem `200` com o mesmo `id`. |

O teste `ActivationRequestConcurrencyIntegrationTests` dispara 10 criações simultâneas com o mesmo `externalId` contra o PostgreSQL do Testcontainers e verifica exatamente um registro, um único `created` e o mesmo `id` em todos os resultados.

### Entidade como modelo de domínio

`ActivationRequest` é a entidade JPA e também o modelo de domínio: não tem setters públicos, e as transições acontecem só por métodos (`startProcessing`, `complete`, `fail`, `scheduleRetry`, `registerTemporaryFailure`) que validam a regra antes de mudar qualquer campo. Um domínio separado com mapper dobraria o código de um agregado pequeno sem ganho real; o acoplamento ao JPA fica restrito a anotações, e as regras são testadas sem Spring nem banco. Os DTOs da API são `record`s separados da entidade.

### Origem do `traceId`

Micrometer Tracing com bridge OpenTelemetry, sem exportador de spans. Cada requisição HTTP recebe um trace no formato W3C (32 hex); o mesmo `traceId` vai para o MDC (padrão de log do Spring Boot) e para o ProblemDetail. Se o chamador enviar `traceparent`, o trace dele é continuado. A propagação ao provedor e a exportação para um coletor ficam para a etapa de observabilidade, sem mudar esta origem.

### Índices da listagem

| Índice | Atende |
|---|---|
| `(customer_id, created_at DESC)` | filtro por cliente, com ou sem `status`, já na ordem da paginação |
| `(status, created_at DESC)` | filtro só por status (ex.: listar `FAILED`), já na ordem da paginação |

O worker e a reconciliação usam índices parciais (migração `V3`): `(next_attempt_at) WHERE status = 'PENDING'` e `(updated_at) WHERE status = 'PROCESSING'`. Ficam pequenos, porque a maioria das linhas acaba `COMPLETED` ou `FAILED`.

A listagem sem filtro faz varredura e ordenação da tabela, aceitável no volume do desafio. Evolução: índice em `(created_at DESC)` ou paginação por cursor (`createdAt`, `id`) em vez de `page`/`size`.

O schema é criado pelo Flyway (`db/migration`), e o Hibernate só valida que entidade e tabela batem (`spring.jpa.hibernate.ddl-auto=validate`).

## Produção

### Imagem de contêiner

O `Dockerfile` é multi-stage: compila com JDK, separa o jar em camadas (`jarmode=tools`) e gera uma imagem final só com JRE, executada com usuário não-root. Os testes não rodam no build da imagem; eles rodam no `./mvnw verify` do CI.

```bash
docker build -t ativacao .
docker run --rm -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://<host>:5432/ativacao \
  -e DB_USERNAME=<usuario> \
  -e DB_PASSWORD=<senha> \
  -e PROVIDER_URL=https://<provedor> \
  ativacao
```

A imagem tem `HEALTHCHECK` em `/actuator/health/readiness`.

### Variáveis de ambiente obrigatórias

| Variável | Descrição |
|---|---|
| `DB_URL` | URL JDBC do PostgreSQL |
| `DB_USERNAME` | Usuário do banco |
| `DB_PASSWORD` | Senha do banco |
| `PROVIDER_URL` | Base URL do provedor externo |

Demais ajustes do provedor e do envio em [Configuração do provedor e do envio](#configuração-do-provedor-e-do-envio).

### Memória da JVM

A imagem define `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0`: o heap usa até 75% da memória do contêiner, e o restante fica para metaspace, threads e buffers. Para ajustar, sobrescreva a variável:

```bash
docker run --rm -p 8080:8080 --memory=512m \
  -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60.0" \
  -e DB_URL=... -e DB_USERNAME=... -e DB_PASSWORD=... -e PROVIDER_URL=... \
  ativacao
```

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

Sobe aplicação, PostgreSQL, o mock do provedor externo e um emissor de tokens de desenvolvimento, cada um com health check. A aplicação só inicia depois que o banco e o mock do provedor estão saudáveis, e envia as solicitações ao mock (`PROVIDER_URL=http://wiremock:8080`). Para rodar em segundo plano e esperar todos ficarem saudáveis, use `docker compose up --build -d --wait`.

| Serviço | Porta | Descrição |
|---|---|---|
| `app` | `8080` | Aplicação |
| `postgres` | `5432` | PostgreSQL |
| `wiremock` | `8089` | Mock do provedor externo (WireMock); stubs em `wiremock/mappings/` |
| `oauth2` | `8091` | Emissor de tokens OAuth 2.0 ([mock-oauth2-server](https://github.com/navikt/mock-oauth2-server)); clientes em `oauth2/config.json` |

```bash
curl localhost:8080/actuator/health/readiness
curl -X POST localhost:8089/provider/v1/activate
```

#### Token de acesso

A API exige `Authorization: Bearer <JWT>`. O serviço `oauth2` emite tokens por client credentials para três clientes de exemplo:

| `client_id` | Escopos |
|---|---|
| `sistema-a` | `activation:read activation:write` |
| `sistema-b` | `activation:read activation:write` |
| `sistema-leitura` | `activation:read` |

```bash
TOKEN=$(curl -s -X POST localhost:8091/default/token \
  -d grant_type=client_credentials -d client_id=sistema-a -d client_secret=dev \
  -d 'scope=activation:read activation:write' | jq -r .access_token)

curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/requests
```

O `client_id` vai no corpo (`client_secret_post`): é por ele que o emissor escolhe as claims. Os tokens valem 15 minutos e trazem `iss` `http://localhost:8091/default`, `aud` `ativacao-api`, `client_id` e `scope`. Sem `jq`, copie o `access_token` da resposta.

> **Só para desenvolvimento.** O `mock-oauth2-server` aceita qualquer segredo e emite token para qualquer `client_id`. Ele existe apenas no `compose.yaml`; em produção, a aplicação aponta para o servidor de autorização real (ver [Configuração da autenticação](#configuração-da-autenticação)).

Para parar e apagar os dados: `docker compose down -v`.

> O enunciado pede um `docker-compose.yml` na seção Entrega. O arquivo aqui se chama `compose.yaml`, o nome padrão e preferido da [Compose Specification](https://github.com/compose-spec/compose-spec/blob/main/spec.md#compose-file); `docker-compose.yml` só é aceito por compatibilidade. O `docker compose` encontra o arquivo sem nenhuma opção extra, então ele cumpre o mesmo papel.

### Aplicação local, dependências no Docker

Pré-requisitos: Java 21 e Docker.

```bash
docker compose up -d postgres wiremock oauth2
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

No IntelliJ, informe `local` em **Active profiles** na Run Configuration. O perfil `local` aponta para o PostgreSQL (`localhost:5432`), o mock do provedor (`localhost:8089`) e o emissor de tokens (`localhost:8091`, JWKS em `/default/jwks`, emissor `http://localhost:8091/default`, audiência `ativacao-api`) do `compose.yaml`. O token é obtido como em [Token de acesso](#token-de-acesso).

### Testes

```bash
./mvnw verify
```

Build e testes com um único comando. Os testes sobem o próprio PostgreSQL via Testcontainers e não dependem do `compose.yaml` (Docker precisa estar em execução). O provedor externo é simulado com WireMock em porta dinâmica, dentro do próprio teste.

`src/test/resources/config/application.properties` desliga o envio agendado nos testes (`activation.dispatch.enabled=false`): os testes do worker chamam o `ActivationDispatcher` direto, e só `ActivationDispatchSchedulingIntegrationTests` liga o agendamento.

O pipeline de CI ([.github/workflows/ci.yml](.github/workflows/ci.yml)) roda a cada push e pull request: o job `verify` executa `./mvnw -B verify` e o job `compose-smoke` sobe o ambiente com `docker compose up --build --wait` e confere readiness, usuário não-root, mock do provedor e uma chamada à API com token de `sistema-leitura` obtido no `oauth2`.

### Coleção Bruno

Coleção [Bruno](https://www.usebruno.com/) em [`bruno/`](bruno/). Abra a pasta no app e rode as pastas em ordem (`01` antes de `04`), ou execute `bru run --env local` dentro de `bruno/` se tiver o Bruno CLI instalado. A coleção obtém o token sozinha (OAuth 2.0 client credentials como `sistema-a`, no serviço `oauth2` do `compose.yaml`); a pasta `06 Segurança` mostra `401`, `403` e o isolamento entre clientes; a `00 Health` inclui as métricas em `/actuator/prometheus`.

### Configuração do banco

| Variável | Descrição |
|---|---|
| `DB_URL` | URL JDBC do PostgreSQL |
| `DB_USERNAME` | Usuário do banco |
| `DB_PASSWORD` | Senha do banco |

O `application.properties` não tem valores padrão: sem essas variáveis, a aplicação não sobe. As credenciais do `compose.yaml` e do `application-local.properties` são apenas para desenvolvimento local. Em produção, os valores vêm de variáveis de ambiente injetadas por um gerenciador de segredos.

### Configuração da autenticação

A API é um OAuth 2.0 Resource Server: aceita só JWT assinado por uma chave do JWKS configurado, com o emissor e a audiência configurados.

| Variável | Descrição |
|---|---|
| `JWT_JWK_SET_URI` | URL do JWKS (chaves públicas) do servidor de autorização |
| `JWT_ISSUER_URI` | Valor exigido na claim `iss` |
| `JWT_AUDIENCE` | Valor exigido na claim `aud` |

As três são obrigatórias: sem elas, a aplicação não sobe. As chaves são buscadas no primeiro uso, não na subida.

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

### Logs

Os logs saem no console em JSON, um objeto por linha (formato Logstash do Spring Boot). Todo log emitido durante uma requisição ou um envio ao provedor traz `traceId` e `spanId`, o mesmo `traceId` do ProblemDetail de erro. Tokens e credenciais não são logados.

```json
{"@timestamp":"2026-10-30T12:00:00.000Z","level":"ERROR","message":"Erro inesperado ...","traceId":"4bf92f3577b34da6a3ce929d0e0e4736","spanId":"00f067aa0ba902b7", ...}
```

Para ler no terminal local, use `jq`: `./mvnw spring-boot:run | jq -R 'fromjson? // .'` ou, para achar um erro pelo `traceId` devolvido ao cliente: `... | grep 4bf92f35 | jq .`

### Métricas

`GET /actuator/prometheus` expõe as métricas no formato de texto do Prometheus, sem token (só agregados, sem dados de negócio; ver [Produção](#produção) para restringir por rede). Os rótulos nunca levam `id`, `externalId` nem `customerId`: o `uri` é o template da rota (`/api/v1/requests/{id}`).

| Pergunta | Métrica | Origem |
|---|---|---|
| Latência da API | `http_server_requests_seconds` (`uri`, `method`, `status`, `outcome`) | automática |
| Latência e erros do provedor | `http_client_requests_seconds` (`uri="/provider/v1/activate"`, `status`, `outcome`, `exception`) | automática; timeout aparece com `status="CLIENT_ERROR"` |
| Solicitações por status | `activation_requests_transitions_total{status}` | contador de transições para cada status |

Consultas de exemplo (PromQL):

```promql
# p95 da API por rota, janela de 5 min
histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m])))

# taxa de erro do provedor (respostas 5xx e falhas de I/O)
sum(rate(http_client_requests_seconds_count{uri="/provider/v1/activate", outcome!="SUCCESS"}[5m]))
  / sum(rate(http_client_requests_seconds_count{uri="/provider/v1/activate"}[5m]))

# solicitações que falharam de vez nos últimos 5 min
increase(activation_requests_transitions_total{status="FAILED"}[5m])
```

O contador mede eventos, não estoque: quantas solicitações `PENDING` há agora (um gauge consultando o banco) fica como melhoria futura.

### Encerramento gracioso

Ao receber o pedido de parada (`SIGTERM`), o servidor HTTP termina as requisições em andamento e o agendador deixa de iniciar ciclos do worker e espera o ciclo em curso, antes de o acesso ao banco fechar. O prazo vem de `SHUTDOWN_TIMEOUT` (padrão `30s`). O `GracefulShutdownIntegrationTests` fecha a aplicação com um envio lento em andamento e confere que a solicitação termina `COMPLETED`.

Se o prazo estoura, os itens do lote já reservados e ainda não enviados ficam em `PROCESSING`; a reconciliação os devolve para `PENDING` depois de `stale-after` (conta uma tentativa, mesma `Idempotency-Key`). Um lote de 10 itens com `read-timeout` de 5 s pode passar de 30 s em um provedor travado; aumente `SHUTDOWN_TIMEOUT` e o `stop_grace_period` do orquestrador juntos, este sempre maior.

## API

| Método e caminho | Escopo exigido | Descrição | Sucesso |
|---|---|---|---|
| `POST /api/v1/requests` | `activation:write` | Cria uma solicitação de ativação com status `PENDING`; o envio ao provedor é assíncrono | `201` + header `Location`; `200` em retry idêntico; `409` se o `externalId` já existe com outro conteúdo |
| `GET /api/v1/requests/{id}` | `activation:read` | Consulta uma solicitação do cliente e seu status atual | `200` (`404` se não existir ou for de outro cliente) |
| `GET /api/v1/requests` | `activation:read` | Lista as solicitações do cliente, da mais recente para a mais antiga | `200` |

### Autenticação e isolamento

Toda chamada a `/api/v1/requests` exige `Authorization: Bearer <JWT>` (OAuth 2.0, client credentials). Sem token, ou com token inválido, a resposta é `401`; com token válido sem o escopo da operação, `403`. Um escopo não implica o outro: um cliente que cria e consulta precisa dos dois.

O cliente é identificado pela claim `client_id` do token e vira o dono das solicitações que cria. Cada cliente só enxerga as próprias:

- a consulta por id de uma solicitação de outro cliente responde `404`, igual a um id inexistente, para não confirmar que o id existe (OWASP API1:2023, BOLA);
- a listagem e a contagem de `totalElements` consideram só as solicitações do cliente do token.

O dono não aparece no corpo das respostas. Os exemplos abaixo usam `$TOKEN` com um access token válido.

### Criar

```bash
curl -i -X POST localhost:8080/api/v1/requests \
  -H "Authorization: Bearer $TOKEN" \
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

O `externalId` é único em todo o serviço (ver [Duplicidade](#duplicidade)). Repetir o mesmo corpo, com o mesmo cliente, devolve `200` com o registro existente, no status atual, sem header `Location`. O mesmo `externalId` com `customerId` ou `productCode` diferente devolve `409`; se o `externalId` é de outro cliente, o `409` vem sem `conflictingFields`:

```bash
curl -i -X POST localhost:8080/api/v1/requests \
  -H "Authorization: Bearer $TOKEN" \
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
curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/requests/0b5c8f1e-6a51-4a3e-9d4c-2f8e7a1b3c9d
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
curl -H "Authorization: Bearer $TOKEN" 'localhost:8080/api/v1/requests?customerId=CUST-10001&status=PENDING&page=0&size=20'
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
| Token ausente, malformado ou inválido (assinatura, emissor, audiência, expiração, sem `client_id`) | `401` | `unauthorized` (com header `WWW-Authenticate: Bearer ...`) |
| Token válido sem o escopo da operação | `403` | `forbidden` |
| Solicitação inexistente ou de outro cliente | `404` | `not-found` |
| `externalId` já registrado com outro conteúdo (com `conflictingFields`) ou por outro cliente (sem `conflictingFields`) | `409` | `duplicate-external-id` |
| Erro inesperado | `500` | `internal-error` |
| Demais erros do HTTP (`405`, `415`, ...) | o do erro | `about:blank` |

O `type` usa o domínio reservado `.example`: é um identificador, não uma página. Respostas de erro nunca trazem stack trace, nome de classe, mensagem da exceção original nem detalhes do parser JSON. No `401` e no `403`, o `detail` é fixo: não repete o token nem o motivo da recusa (ex.: expiração); o motivo padrão da RFC 6750 vai só no header `WWW-Authenticate`. Um erro `500` é registrado no log em nível `ERROR` com o mesmo `traceId` devolvido ao cliente, o que permite localizar a falha a partir da resposta.

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

### Segurança

Decisões registradas no [ADR 0004](docs/adr/0004-seguranca-da-api.md).

**Fluxo client credentials.** Os consumidores são sistemas, sem usuário humano. Cada sistema tem `client_id` e segredo (ou chave privada, com `private_key_jwt`) no servidor de autorização:

1. o sistema chama `POST /token` do servidor de autorização com `grant_type=client_credentials`, as próprias credenciais e os escopos desejados;
2. o servidor devolve um access token JWT de vida curta (minutos), assinado com a chave privada dele, com `iss`, `aud`, `exp`, `client_id` e `scope`;
3. o sistema chama a API com `Authorization: Bearer <token>` e reaproveita o token até perto de expirar;
4. a API valida o token sozinha, com a chave pública, sem chamar o servidor de autorização a cada requisição.

**O que a API valida no JWT.** Assinatura por uma chave do JWKS em `JWT_JWK_SET_URI`; `iss` igual a `JWT_ISSUER_URI`; `aud` contendo `JWT_AUDIENCE`; `exp` e `nbf` com 60 s de tolerância de relógio; claim `client_id` presente, não vazia e com até 255 caracteres. Qualquer falha resulta em `401`. O decoder é o do Spring Boot (Resource Server), configurado só por propriedades, e o validador de `client_id` entra na mesma cadeia.

**De onde vêm as chaves.** Do endpoint JWKS do servidor de autorização. As chaves são buscadas na primeira requisição autenticada, ficam em cache e são buscadas de novo quando chega um `kid` desconhecido: a rotação de chaves do servidor de autorização não exige deploy, desde que ele publique a chave nova antes de começar a assinar com ela. A aplicação não depende do servidor de autorização para subir. Usar `jwk-set-uri`, e não só `issuer-uri` com descoberta OIDC, permite buscar as chaves por um endereço interno diferente do `iss` (é o caso do `compose.yaml`).

**Escopos.** `activation:write` para criar e `activation:read` para consultar e listar ([tabela da API](#api)). Um não implica o outro, e cada cliente recebe só o que usa. Token válido sem o escopo da operação: `403`. As respostas `401` e `403` saem em ProblemDetail com `traceId`, como os demais erros.

**Isolamento entre clientes.** A claim `client_id` é o dono da solicitação. A consulta e a listagem filtram pelo dono na própria consulta ao banco; a solicitação de outro cliente responde `404`, indistinguível de um id inexistente (BOLA). Na criação, `externalId` de outro cliente é `409` sem detalhes ([Duplicidade](#duplicidade)).

**Credencial do provedor externo: armazenamento e rotação.** Hoje o provedor não exige autenticação (o enunciado não define credenciais). Em produção:

- *Armazenamento:* o segredo fica em um cofre (AWS Secrets Manager, Azure Key Vault, HashiCorp Vault), lido na subida pela integração do Spring Cloud com o cofre ou injetado como variável pelo orquestrador (Kubernetes Secret alimentado pelo External Secrets Operator). Nunca no repositório, na imagem ou em log.
- *Preferência por OAuth 2.0 client credentials também na saída:* a aplicação obtém um token de vida curta para o provedor com `spring-boot-starter-security-oauth2-client` e o `OAuth2ClientHttpRequestInterceptor` no `RestClient`. O segredo de longa duração fica só entre a aplicação e o servidor de autorização; melhor ainda com `private_key_jwt` ou mTLS, em que não há segredo compartilhado.
- *Rotação sem parada:* o cofre gera a credencial nova enquanto a antiga continua válida; a aplicação passa a usar a nova (releitura periódica ou novo deploy); a antiga é revogada depois de uma janela maior que o ciclo de releitura. Com chave de API simples, o provedor precisa aceitar duas chaves ativas durante a janela.
- *Falha de autenticação no provedor:* hoje um `401`/`403` do provedor vira `PROVIDER_REJECTED`. Com credencial real, deveria virar alerta operacional, e não falha definitiva da solicitação.

**Limites de corpo e de taxa (evolução, não implementados).**

- *Corpo:* o payload válido tem menos de 300 bytes. O limite fica no gateway ou ingress (ex.: `client_max_body_size 16k` no NGINX), ou na aplicação com um filtro que rejeita `Content-Length` acima de 16 KB com `413`.
- *Taxa:* por `client_id`, no API gateway, onde o estado é compartilhado entre instâncias, ou com Bucket4j e Redis na aplicação, respondendo `429` com `Retry-After`. Limite em memória por instância não funciona com várias réplicas.

### Duplicidade

Decisões registradas nos ADRs [0001](docs/adr/0001-unicidade-external-id.md) e [0004](docs/adr/0004-seguranca-da-api.md) (regra entre clientes).

O `externalId` é único em todo o serviço, de qualquer cliente, e a comparação diferencia maiúsculas de minúsculas. Quem garante é o banco, com a constraint `uq_activation_request_external_id UNIQUE (external_id)` (migração `V2`).

O retry idêntico (`200`) e os `conflictingFields` só valem entre solicitações do mesmo cliente (`client_id` do token). Se o `externalId` é de outro cliente, a criação compara o dono antes do conteúdo e responde sempre o mesmo `409` sem detalhes: devolver o registro vazaria `id` e status, e os campos divergentes funcionariam como oráculo, permitindo descobrir por tentativa o `customerId` e o `productCode` alheios. O caminho da corrida (violação seguida de releitura) passa pela mesma regra.

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
| `externalId` já registrado por **outro cliente**, com qualquer conteúdo | `409` sem `conflictingFields`, com `detail` "O externalId ... já está em uso.". Nenhum registro é criado ou alterado. |
| Duas requisições idênticas em paralelo | Uma recebe `201`; as outras caem na violação, releem e recebem `200` com o mesmo `id`. |

O teste `ActivationRequestConcurrencyIntegrationTests` dispara 10 criações simultâneas com o mesmo `externalId` contra o PostgreSQL do Testcontainers e verifica exatamente um registro, um único `created` e o mesmo `id` em todos os resultados.

### Entidade como modelo de domínio

`ActivationRequest` é a entidade JPA e também o modelo de domínio: não tem setters públicos, e as transições acontecem só por métodos (`startProcessing`, `complete`, `fail`, `scheduleRetry`, `registerTemporaryFailure`) que validam a regra antes de mudar qualquer campo. Um domínio separado com mapper dobraria o código de um agregado pequeno sem ganho real; o acoplamento ao JPA fica restrito a anotações, e as regras são testadas sem Spring nem banco. Os DTOs da API são `record`s separados da entidade.

### Origem do `traceId`

Micrometer Tracing com OpenTelemetry (`spring-boot-starter-opentelemetry`). Cada requisição HTTP recebe um trace no formato W3C (32 hex); o mesmo `traceId` vai para o MDC (e daí para os [logs JSON](#logs)) e para o ProblemDetail. Se o chamador enviar `traceparent`, o trace dele é continuado.

O envio ao provedor roda no worker, depois do `POST`. Para manter o mesmo `traceId`, a criação grava o `traceparent` da requisição na solicitação (coluna `trace_parent`, migração `V5`, só em registro novo). O worker abre o span `activation.dispatch` como filho desse contexto: os logs do envio e o header `traceparent` enviado ao provedor têm o `traceId` do `POST`, inclusive nas novas tentativas. Solicitação sem contexto gravado é enviada em um trace novo. A exportação dos spans para um coletor é opcional ([Observabilidade em produção](#observabilidade-em-produção)).

Decisões registradas no [ADR 0006](docs/adr/0006-observabilidade.md).

### Índices da listagem

Toda listagem filtra pelo dono da solicitação (`client_id`, do token). Por isso os índices começam pelo dono (migração `V4`, que substitui os índices da `V1`):

| Índice | Atende |
|---|---|
| `(client_id, created_at DESC)` | listagem sem filtro, já na ordem da paginação |
| `(client_id, customer_id, created_at DESC)` | filtro por cliente, com ou sem `status`, já na ordem da paginação |
| `(client_id, status, created_at DESC)` | filtro só por status (ex.: listar `FAILED`), já na ordem da paginação |

Com `customerId` e `status` juntos, o índice de cliente filtra e o status é conferido nas linhas lidas.

O worker e a reconciliação usam índices parciais (migração `V3`): `(next_attempt_at) WHERE status = 'PENDING'` e `(updated_at) WHERE status = 'PROCESSING'`. Ficam pequenos, porque a maioria das linhas acaba `COMPLETED` ou `FAILED`.

Evolução para volumes grandes: paginação por cursor (`createdAt`, `id`) em vez de `page`/`size`, que evita contar e pular linhas.

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
  -e JWT_JWK_SET_URI=https://<servidor-de-autorizacao>/jwks \
  -e JWT_ISSUER_URI=https://<servidor-de-autorizacao> \
  -e JWT_AUDIENCE=ativacao-api \
  ativacao
```

A imagem tem `HEALTHCHECK` em `/actuator/health/readiness`; o serviço `app` do `compose.yaml` declara o mesmo health check e `stop_grace_period: 40s` (acima do `SHUTDOWN_TIMEOUT`).

### Variáveis de ambiente obrigatórias

| Variável | Descrição |
|---|---|
| `DB_URL` | URL JDBC do PostgreSQL |
| `DB_USERNAME` | Usuário do banco |
| `DB_PASSWORD` | Senha do banco |
| `PROVIDER_URL` | Base URL do provedor externo |
| `JWT_JWK_SET_URI` | URL do JWKS do servidor de autorização |
| `JWT_ISSUER_URI` | Emissor exigido no JWT (`iss`) |
| `JWT_AUDIENCE` | Audiência exigida no JWT (`aud`) |

Demais ajustes do provedor e do envio em [Configuração do provedor e do envio](#configuração-do-provedor-e-do-envio).

### Observabilidade em produção

| Variável | Padrão | Descrição |
|---|---|---|
| `TRACING_SAMPLING_PROBABILITY` | `1.0` | Fração dos traces amostrados e exportados. O `traceId` existe mesmo fora da amostra (logs, ProblemDetail, header); só o span não é exportado. Reduza em produção (ex.: `0.1`). |
| `OTLP_TRACING_ENABLED` | `false` | Liga a exportação de spans por OTLP. Desligada, nada é exportado. |
| `OTLP_TRACING_ENDPOINT` | `http://localhost:4318/v1/traces` | Endpoint OTLP/HTTP do coletor. Só vale com `OTLP_TRACING_ENABLED=true`. |
| `SHUTDOWN_TIMEOUT` | `30s` | Prazo do [encerramento gracioso](#encerramento-gracioso). |

Para enviar os traces a um coletor (OpenTelemetry Collector, Jaeger, Tempo), aponte o endpoint para ele; o Compose do projeto não sobe coletor:

```bash
docker run --rm -p 8080:8080 \
  -e OTLP_TRACING_ENABLED=true \
  -e OTLP_TRACING_ENDPOINT=http://<coletor>:4318/v1/traces \
  -e TRACING_SAMPLING_PROBABILITY=0.1 \
  ... ativacao
```

`/actuator/prometheus` não exige token, para o Prometheus fazer scrape sem client credentials; o conteúdo é agregado e sem dados de negócio. Em produção, restrinja o acesso pela rede: o ideal é uma porta de gerenciamento separada (`management.server.port`) alcançável só na rede interna, ou uma regra no proxy/ingress que bloqueie `/actuator/prometheus` fora do cluster. Isso não está configurado aqui para não mudar as portas do Compose e do CI.

### Memória da JVM

A imagem define `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0`: o heap usa até 75% da memória do contêiner, e o restante fica para metaspace, threads e buffers. Para ajustar, sobrescreva a variável:

```bash
docker run --rm -p 8080:8080 --memory=512m \
  -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60.0" \
  -e DB_URL=... -e DB_USERNAME=... -e DB_PASSWORD=... -e PROVIDER_URL=... -e JWT_JWK_SET_URI=... \
  -e JWT_ISSUER_URI=... -e JWT_AUDIENCE=... \
  ativacao
```

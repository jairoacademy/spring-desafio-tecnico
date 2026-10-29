# Proposal

## Why

Hoje a solicitação é criada em `PENDING` e fica parada: nada chama o provedor externo (`POST /provider/v1/activate`), então nenhuma ativação chega a `COMPLETED` ou `FAILED`. O item 5 do enunciado é obrigatório e pede um comportamento definido para cada retorno do provedor. Esta change liga a solicitação ao provedor de forma assíncrona e resiliente, sem perder envios se a aplicação cair.

## What Changes

- Processamento assíncrono: o `POST /api/v1/requests` continua respondendo `201` com `PENDING` na hora. Um worker `@Scheduled` envia as solicitações ao provedor depois do commit.
- A própria tabela `activation_request` funciona como outbox. O worker reserva solicitações `PENDING` vencidas com `SELECT ... FOR UPDATE SKIP LOCKED`, passa para `PROCESSING`, faz commit e só então chama o provedor, fora de qualquer transação.
- Cliente do provedor como interface `@HttpExchange` registrada com `@ImportHttpServices` (Spring Framework 7), com base URL, connect timeout e read timeout configuráveis.
- Header `Idempotency-Key` com o `id` da solicitação em toda chamada, inclusive nas novas tentativas.
- Política por retorno do provedor: `2xx` → `COMPLETED`; `400` e demais `4xx` definitivos → `FAILED`; `5xx`, timeout e erro de conexão → volta a `PENDING` com backoff exponencial com jitter; `429`/`503` com `Retry-After` → volta a `PENDING` na data indicada pelo provedor; limite de tentativas atingido → `FAILED`.
- Motivo da falha definitiva gravado e exposto na consulta (`failureReason`).
- Reconciliação: solicitações presas em `PROCESSING` além de um limite de tempo voltam para `PENDING` (contando como tentativa), sem perder a `Idempotency-Key`.
- Migração Flyway `V3` com a coluna do motivo da falha e índices parciais para o worker e a reconciliação.
- `compose.yaml` passa a apontar a aplicação para o WireMock.
- ADRs 0002 (processamento assíncrono com a tabela como outbox), 0003 (política de retry e idempotência com o provedor) e 0005 (transição `PROCESSING → PENDING`). README com a tabela de comportamento por retorno e a nova configuração.
- Circuit breaker só descrito no `design.md`, sem implementação.

## Capabilities

### New Capabilities
- `provider-integration`: envio assíncrono das solicitações ao provedor externo, garantia de envio após o commit, classificação de cada retorno do provedor, novas tentativas persistidas com backoff, limite de tentativas, idempotência com o provedor e reconciliação de `PROCESSING` antigo.

### Modified Capabilities
- `activation-requests`: novo requisito para o motivo da falha definitiva (`failureReason`) nas respostas da solicitação. A transição `PROCESSING → PENDING` já existe na spec e não muda.

## Impact

- **Código:** novos pacotes de integração em `activation/application` (worker, política de retry) e `activation/infrastructure` (cliente `@HttpExchange`, consultas com `FOR UPDATE SKIP LOCKED`); `ActivationRequest` ganha o motivo da falha; `ActivationRequestResponse` ganha `failureReason`; `@EnableScheduling` na aplicação.
- **Banco:** migração `V3` (coluna `failure_reason` e dois índices parciais). Sem mudança nas colunas existentes.
- **API:** `GET` e `POST` passam a devolver `failureReason` (nulo fora de `FAILED`). Campo novo, sem quebra de contrato. O `status` de uma solicitação passa a mudar sozinho depois da criação.
- **Dependências:** `spring-boot-starter-restclient` (main), `org.wiremock:wiremock-standalone` e `org.awaitility:awaitility` (test). Justificativas no `design.md`.
- **Configuração:** nova variável obrigatória `PROVIDER_URL`; timeouts, intervalo do worker, tamanho do lote, backoff, limite de tentativas e limite de reconciliação configuráveis com valores padrão.
- **Ambiente:** `compose.yaml` define `PROVIDER_URL` para o serviço `wiremock`.

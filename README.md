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

Sobe aplicação, PostgreSQL e o mock do provedor externo, cada um com health check. A aplicação só inicia depois que o banco está saudável. Para rodar em segundo plano e esperar todos ficarem saudáveis, use `docker compose up --build -d --wait`.

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

No IntelliJ, informe `local` em **Active profiles** na Run Configuration.

### Testes

```bash
./mvnw verify
```

Build e testes com um único comando. Os testes sobem o próprio PostgreSQL via Testcontainers e não dependem do `compose.yaml` (Docker precisa estar em execução).

O pipeline de CI ([.github/workflows/ci.yml](.github/workflows/ci.yml)) roda a cada push e pull request: o job `verify` executa `./mvnw -B verify` e o job `compose-smoke` sobe o ambiente com `docker compose up --build --wait` e confere readiness, usuário não-root e mock do provedor.

### Configuração do banco

| Variável | Descrição |
|---|---|
| `DB_URL` | URL JDBC do PostgreSQL |
| `DB_USERNAME` | Usuário do banco |
| `DB_PASSWORD` | Senha do banco |

O `application.properties` não tem valores padrão: sem essas variáveis, a aplicação não sobe. As credenciais do `compose.yaml` e do `application-local.properties` são apenas para desenvolvimento local. Em produção, os valores vêm de variáveis de ambiente injetadas por um gerenciador de segredos.

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
| `POST /api/v1/requests` | Cria uma solicitação de ativação com status `PENDING` | `201` + header `Location`; `200` em retry idêntico; `409` se o `externalId` já existe com outro conteúdo |
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
  "createdAt": "2026-10-29T13:00:00.123456Z",
  "updatedAt": "2026-10-29T13:00:00.123456Z"
}
```

Datas são ISO-8601 em UTC (sufixo `Z`), com precisão de microssegundos.

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

```
PENDING ──► PROCESSING ──► COMPLETED
   ▲            │
   │            ├────────► FAILED
   └────────────┘
   falha temporária (nova tentativa agendada)
```

- `COMPLETED` e `FAILED` são finais. Qualquer outra transição lança `InvalidStatusTransitionException` sem alterar a solicitação.
- Uma falha temporária leva `PROCESSING → PENDING`, incrementa `attempts` e agenda `nextAttemptAt`. Assim, `PROCESSING` significa só "chamada ao provedor em andamento", e o processamento assíncrono (próxima etapa) busca apenas `PENDING` com `nextAttemptAt` vencido.
- Toda solicitação nasce com `attempts = 0` e `nextAttemptAt = createdAt` (elegível na hora). Esses campos e a versão (`@Version`, lock otimista) são internos e não aparecem na API.

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

`ActivationRequest` é a entidade JPA e também o modelo de domínio: não tem setters públicos, e as transições acontecem só por métodos (`startProcessing`, `complete`, `fail`, `scheduleRetry`) que validam a regra antes de mudar qualquer campo. Um domínio separado com mapper dobraria o código de um agregado pequeno sem ganho real; o acoplamento ao JPA fica restrito a anotações, e as regras são testadas sem Spring nem banco. Os DTOs da API são `record`s separados da entidade.

### Origem do `traceId`

Micrometer Tracing com bridge OpenTelemetry, sem exportador de spans. Cada requisição HTTP recebe um trace no formato W3C (32 hex); o mesmo `traceId` vai para o MDC (padrão de log do Spring Boot) e para o ProblemDetail. Se o chamador enviar `traceparent`, o trace dele é continuado. A propagação ao provedor e a exportação para um coletor ficam para a etapa de observabilidade, sem mudar esta origem.

### Índices da listagem

| Índice | Atende |
|---|---|
| `(customer_id, created_at DESC)` | filtro por cliente, com ou sem `status`, já na ordem da paginação |
| `(status, created_at DESC)` | filtro só por status (ex.: listar `FAILED`), já na ordem da paginação |

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
  ativacao
```

A imagem tem `HEALTHCHECK` em `/actuator/health/readiness`.

### Variáveis de ambiente obrigatórias

| Variável | Descrição |
|---|---|
| `DB_URL` | URL JDBC do PostgreSQL |
| `DB_USERNAME` | Usuário do banco |
| `DB_PASSWORD` | Senha do banco |

### Memória da JVM

A imagem define `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0`: o heap usa até 75% da memória do contêiner, e o restante fica para metaspace, threads e buffers. Para ajustar, sobrescreva a variável:

```bash
docker run --rm -p 8080:8080 --memory=512m \
  -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60.0" \
  -e DB_URL=... -e DB_USERNAME=... -e DB_PASSWORD=... \
  ativacao
```

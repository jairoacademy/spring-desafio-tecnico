# Decisões técnicas

[← Voltar ao README](../../README.md)

## Estados da solicitação

Decisão registrada no [ADR 0005](../adr/0005-transicao-processing-pending.md).

```
PENDING ──► PROCESSING ──┬──► COMPLETED
   ▲                     ├──► FAILED  (PROVIDER_REJECTED ou MAX_ATTEMPTS_EXCEEDED)
   └─────────────────────┘
   falha temporária (attempts++, nextAttemptAt agendado)
```

- `COMPLETED` e `FAILED` são finais. Qualquer outra transição lança `InvalidStatusTransitionException` sem alterar a solicitação.
- Uma falha temporária leva `PROCESSING → PENDING`, incrementa `attempts` e agenda `nextAttemptAt`. Assim, `PROCESSING` significa só "chamada ao provedor em andamento", e o worker busca apenas `PENDING` com `nextAttemptAt` vencido. Na última tentativa permitida, a falha temporária leva a `FAILED` com `MAX_ATTEMPTS_EXCEEDED` (`registerTemporaryFailure`).
- Toda solicitação nasce com `attempts = 0` e `nextAttemptAt = createdAt` (elegível na hora). Esses campos e a versão (`@Version`, lock otimista) são internos e não aparecem na API.

## Integração com o provedor

Decisões registradas nos ADRs [0002](../adr/0002-processamento-assincrono-outbox.md) (processamento assíncrono com a tabela como outbox) e [0003](../adr/0003-retry-e-idempotencia-com-provedor.md) (retry e idempotência).

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

## Segurança

Decisões registradas no [ADR 0004](../adr/0004-seguranca-da-api.md).

**Fluxo client credentials.** Os consumidores são sistemas, sem usuário humano. Cada sistema tem `client_id` e segredo (ou chave privada, com `private_key_jwt`) no servidor de autorização:

1. o sistema chama `POST /token` do servidor de autorização com `grant_type=client_credentials`, as próprias credenciais e os escopos desejados;
2. o servidor devolve um access token JWT de vida curta (minutos), assinado com a chave privada dele, com `iss`, `aud`, `exp`, `client_id` e `scope`;
3. o sistema chama a API com `Authorization: Bearer <token>` e reaproveita o token até perto de expirar;
4. a API valida o token sozinha, com a chave pública, sem chamar o servidor de autorização a cada requisição.

**O que a API valida no JWT.** Assinatura por uma chave do JWKS em `JWT_JWK_SET_URI`; `iss` igual a `JWT_ISSUER_URI`; `aud` contendo `JWT_AUDIENCE`; `exp` e `nbf` com 60 s de tolerância de relógio; claim `client_id` presente, não vazia e com até 255 caracteres. Qualquer falha resulta em `401`. O decoder é o do Spring Boot (Resource Server), configurado só por propriedades, e o validador de `client_id` entra na mesma cadeia.

**De onde vêm as chaves.** Do endpoint JWKS do servidor de autorização. As chaves são buscadas na primeira requisição autenticada, ficam em cache e são buscadas de novo quando chega um `kid` desconhecido: a rotação de chaves do servidor de autorização não exige deploy, desde que ele publique a chave nova antes de começar a assinar com ela. A aplicação não depende do servidor de autorização para subir. Usar `jwk-set-uri`, e não só `issuer-uri` com descoberta OIDC, permite buscar as chaves por um endereço interno diferente do `iss` (é o caso do `compose.yaml`).

**Escopos.** `activation:write` para criar e `activation:read` para consultar e listar ([tabela da API](api.md)). Um não implica o outro, e cada cliente recebe só o que usa. Token válido sem o escopo da operação: `403`. As respostas `401` e `403` saem em ProblemDetail com `traceId`, como os demais erros.

**Isolamento entre clientes.** A claim `client_id` é o dono da solicitação. A consulta e a listagem filtram pelo dono na própria consulta ao banco; a solicitação de outro cliente responde `404`, indistinguível de um id inexistente (BOLA). Na criação, `externalId` de outro cliente é `409` sem detalhes ([Duplicidade](#duplicidade)).

**Credencial do provedor externo: armazenamento e rotação.** Hoje o provedor não exige autenticação (o enunciado não define credenciais). Em produção:

- *Armazenamento:* o segredo fica em um cofre (AWS Secrets Manager, Azure Key Vault, HashiCorp Vault), lido na subida pela integração do Spring Cloud com o cofre ou injetado como variável pelo orquestrador (Kubernetes Secret alimentado pelo External Secrets Operator). Nunca no repositório, na imagem ou em log.
- *Preferência por OAuth 2.0 client credentials também na saída:* a aplicação obtém um token de vida curta para o provedor com `spring-boot-starter-security-oauth2-client` e o `OAuth2ClientHttpRequestInterceptor` no `RestClient`. O segredo de longa duração fica só entre a aplicação e o servidor de autorização; melhor ainda com `private_key_jwt` ou mTLS, em que não há segredo compartilhado.
- *Rotação sem parada:* o cofre gera a credencial nova enquanto a antiga continua válida; a aplicação passa a usar a nova (releitura periódica ou novo deploy); a antiga é revogada depois de uma janela maior que o ciclo de releitura. Com chave de API simples, o provedor precisa aceitar duas chaves ativas durante a janela.
- *Falha de autenticação no provedor:* hoje um `401`/`403` do provedor vira `PROVIDER_REJECTED`. Com credencial real, deveria virar alerta operacional, e não falha definitiva da solicitação.

**Limites de corpo e de taxa (evolução, não implementados).**

- *Corpo:* o payload válido tem menos de 300 bytes. O limite fica no gateway ou ingress (ex.: `client_max_body_size 16k` no NGINX), ou na aplicação com um filtro que rejeita `Content-Length` acima de 16 KB com `413`.
- *Taxa:* por `client_id`, no API gateway, onde o estado é compartilhado entre instâncias, ou com Bucket4j e Redis na aplicação, respondendo `429` com `Retry-After`. Limite em memória por instância não funciona com várias réplicas.

## Duplicidade

Decisões registradas nos ADRs [0001](../adr/0001-unicidade-external-id.md) e [0004](../adr/0004-seguranca-da-api.md) (regra entre clientes).

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

## Entidade como modelo de domínio

`ActivationRequest` é a entidade JPA e também o modelo de domínio: não tem setters públicos, e as transições acontecem só por métodos (`startProcessing`, `complete`, `fail`, `scheduleRetry`, `registerTemporaryFailure`) que validam a regra antes de mudar qualquer campo. Um domínio separado com mapper dobraria o código de um agregado pequeno sem ganho real; o acoplamento ao JPA fica restrito a anotações, e as regras são testadas sem Spring nem banco. Os DTOs da API são `record`s separados da entidade.

## Origem do `traceId`

Micrometer Tracing com OpenTelemetry (`spring-boot-starter-opentelemetry`). Cada requisição HTTP recebe um trace no formato W3C (32 hex); o mesmo `traceId` vai para o MDC (e daí para os [logs JSON](configuracao-e-operacao.md#logs)) e para o ProblemDetail. Se o chamador enviar `traceparent`, o trace dele é continuado.

O envio ao provedor roda no worker, depois do `POST`. Para manter o mesmo `traceId`, a criação grava o `traceparent` da requisição na solicitação (coluna `trace_parent`, migração `V5`, só em registro novo). O worker abre o span `activation.dispatch` como filho desse contexto: os logs do envio e o header `traceparent` enviado ao provedor têm o `traceId` do `POST`, inclusive nas novas tentativas. Solicitação sem contexto gravado é enviada em um trace novo. A exportação dos spans para um coletor é opcional ([Observabilidade em produção](producao.md#observabilidade-em-produção)).

Decisões registradas no [ADR 0006](../adr/0006-observabilidade.md).

## Índices da listagem

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

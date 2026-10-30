# Configuração e operação

[← Voltar ao README](../../README.md)

## Configuração do banco

| Variável | Descrição |
|---|---|
| `DB_URL` | URL JDBC do PostgreSQL |
| `DB_USERNAME` | Usuário do banco |
| `DB_PASSWORD` | Senha do banco |

O `application.properties` não tem valores padrão: sem essas variáveis, a aplicação não sobe. As credenciais do `compose.yaml` e do `application-local.properties` são apenas para desenvolvimento local. Em produção, os valores vêm de variáveis de ambiente injetadas por um gerenciador de segredos.

## Configuração da autenticação

A API é um OAuth 2.0 Resource Server: aceita só JWT assinado por uma chave do JWKS configurado, com o emissor e a audiência configurados.

| Variável | Descrição |
|---|---|
| `JWT_JWK_SET_URI` | URL do JWKS (chaves públicas) do servidor de autorização |
| `JWT_ISSUER_URI` | Valor exigido na claim `iss` |
| `JWT_AUDIENCE` | Valor exigido na claim `aud` |

As três são obrigatórias: sem elas, a aplicação não sobe. As chaves são buscadas no primeiro uso, não na subida.

## Configuração do provedor e do envio

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

## Health checks

O Actuator expõe apenas o endpoint `health`, com as probes habilitadas:

| Endpoint | Uso |
|---|---|
| `GET /actuator/health/liveness` | A aplicação está viva. Não depende do banco nem do provedor externo, para que uma falha deles não reinicie a aplicação. |
| `GET /actuator/health/readiness` | A aplicação está pronta para receber tráfego. |

```bash
curl localhost:8080/actuator/health/readiness
```

Os demais endpoints do Actuator (`env`, `beans`, `configprops` etc.) não são expostos via HTTP.

## Logs

Os logs saem no console em JSON, um objeto por linha (formato Logstash do Spring Boot). Todo log emitido durante uma requisição ou um envio ao provedor traz `traceId` e `spanId`, o mesmo `traceId` do ProblemDetail de erro. Tokens e credenciais não são logados.

```json
{"@timestamp":"2026-10-30T12:00:00.000Z","level":"ERROR","message":"Erro inesperado ...","traceId":"4bf92f3577b34da6a3ce929d0e0e4736","spanId":"00f067aa0ba902b7", ...}
```

Para ler no terminal local, use `jq`: `./mvnw spring-boot:run | jq -R 'fromjson? // .'` ou, para achar um erro pelo `traceId` devolvido ao cliente: `... | grep 4bf92f35 | jq .`

## Métricas

`GET /actuator/prometheus` expõe as métricas no formato de texto do Prometheus, sem token (só agregados, sem dados de negócio; ver [Produção](producao.md) para restringir por rede). Os rótulos nunca levam `id`, `externalId` nem `customerId`: o `uri` é o template da rota (`/api/v1/requests/{id}`).

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

## Encerramento gracioso

Ao receber o pedido de parada (`SIGTERM`), o servidor HTTP termina as requisições em andamento e o agendador deixa de iniciar ciclos do worker e espera o ciclo em curso, antes de o acesso ao banco fechar. O prazo vem de `SHUTDOWN_TIMEOUT` (padrão `30s`). O `GracefulShutdownIntegrationTests` fecha a aplicação com um envio lento em andamento e confere que a solicitação termina `COMPLETED`.

Se o prazo estoura, os itens do lote já reservados e ainda não enviados ficam em `PROCESSING`; a reconciliação os devolve para `PENDING` depois de `stale-after` (conta uma tentativa, mesma `Idempotency-Key`). Um lote de 10 itens com `read-timeout` de 5 s pode passar de 30 s em um provedor travado; aumente `SHUTDOWN_TIMEOUT` e o `stop_grace_period` do orquestrador juntos, este sempre maior.

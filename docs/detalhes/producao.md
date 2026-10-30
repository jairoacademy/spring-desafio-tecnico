# Produção

[← Voltar ao README](../../README.md)

## Imagem de contêiner

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

## Variáveis de ambiente obrigatórias

| Variável | Descrição |
|---|---|
| `DB_URL` | URL JDBC do PostgreSQL |
| `DB_USERNAME` | Usuário do banco |
| `DB_PASSWORD` | Senha do banco |
| `PROVIDER_URL` | Base URL do provedor externo |
| `JWT_JWK_SET_URI` | URL do JWKS do servidor de autorização |
| `JWT_ISSUER_URI` | Emissor exigido no JWT (`iss`) |
| `JWT_AUDIENCE` | Audiência exigida no JWT (`aud`) |

Demais ajustes do provedor e do envio em [Configuração do provedor e do envio](configuracao-e-operacao.md#configuração-do-provedor-e-do-envio).

## Observabilidade em produção

| Variável | Padrão | Descrição |
|---|---|---|
| `TRACING_SAMPLING_PROBABILITY` | `1.0` | Fração dos traces amostrados e exportados. O `traceId` existe mesmo fora da amostra (logs, ProblemDetail, header); só o span não é exportado. Reduza em produção (ex.: `0.1`). |
| `OTLP_TRACING_ENABLED` | `false` | Liga a exportação de spans por OTLP. Desligada, nada é exportado. |
| `OTLP_TRACING_ENDPOINT` | `http://localhost:4318/v1/traces` | Endpoint OTLP/HTTP do coletor. Só vale com `OTLP_TRACING_ENABLED=true`. |
| `SHUTDOWN_TIMEOUT` | `30s` | Prazo do [encerramento gracioso](configuracao-e-operacao.md#encerramento-gracioso). |

Para enviar os traces a um coletor (OpenTelemetry Collector, Jaeger, Tempo), aponte o endpoint para ele; o Compose do projeto não sobe coletor:

```bash
docker run --rm -p 8080:8080 \
  -e OTLP_TRACING_ENABLED=true \
  -e OTLP_TRACING_ENDPOINT=http://<coletor>:4318/v1/traces \
  -e TRACING_SAMPLING_PROBABILITY=0.1 \
  ... ativacao
```

`/actuator/prometheus` não exige token, para o Prometheus fazer scrape sem client credentials; o conteúdo é agregado e sem dados de negócio. Em produção, restrinja o acesso pela rede: o ideal é uma porta de gerenciamento separada (`management.server.port`) alcançável só na rede interna, ou uma regra no proxy/ingress que bloqueie `/actuator/prometheus` fora do cluster. Isso não está configurado aqui para não mudar as portas do Compose e do CI.

## Memória da JVM

A imagem define `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0`: o heap usa até 75% da memória do contêiner, e o restante fica para metaspace, threads e buffers. Para ajustar, sobrescreva a variável:

```bash
docker run --rm -p 8080:8080 --memory=512m \
  -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60.0" \
  -e DB_URL=... -e DB_USERNAME=... -e DB_PASSWORD=... -e PROVIDER_URL=... -e JWT_JWK_SET_URI=... \
  -e JWT_ISSUER_URI=... -e JWT_AUDIENCE=... \
  ativacao
```

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
./mvnw test
```

Os testes sobem o próprio PostgreSQL via Testcontainers e não dependem do `compose.yaml` (Docker precisa estar em execução).

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

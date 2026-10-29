# Tasks

## 1. Actuator e probes

- [x] 1.1 Confirmar na documentação do Boot 4.1 os nomes de `spring-boot-starter-actuator` e do starter de teste correspondente, adicioná-los ao `pom.xml` e verificar com `./mvnw -q dependency:tree` que resolvem
- [x] 1.2 Configurar em `application.properties` `management.endpoint.health.probes.enabled=true` e `management.endpoints.web.exposure.include=health`; criar teste de integração (`@SpringBootTest` + `@AutoConfigureMockMvc` + `TestcontainersConfiguration`) que verifica `200`/`UP` em `/actuator/health/liveness` e `/actuator/health/readiness` e `404` em `/actuator/env`; verificar com `./mvnw verify`
- [x] 1.3 Documentar no README os endpoints de health e verificar que os caminhos citados batem com os do teste

## 2. Null-safety

- [x] 2.1 Criar `package-info.java` com `@NullMarked` no pacote `academy.jairo.springdesafiotecnico` e verificar que `./mvnw verify` compila sem dependência nova no `pom.xml`

## 3. ADR da versão do Spring Boot

- [x] 3.1 Escrever `docs/adr/0000-versao-spring-boot.md` (contexto, decisão, consequências) com base no plano §1.4 e verificar que o README tem link para a pasta `docs/adr/`

## 4. Imagem de contêiner

- [x] 4.1 Criar `.dockerignore` (`target/`, `.git/`, `.idea/`, `docs/`, `openspec/`, `.claude/`) e `Dockerfile` multi-stage conforme design (Temurin 21 Alpine, `jarmode=tools extract --layers --launcher`, usuário não-root, `MaxRAMPercentage=75.0`, `HEALTHCHECK` na readiness); verificar com `docker build -t ativacao .` e `docker run --rm --entrypoint id ativacao -u` retornando valor diferente de `0`
- [x] 4.2 Documentar no README a seção "Produção": build da imagem, variáveis de ambiente obrigatórias e como ajustar a memória da JVM; verificar que os comandos citados rodam como escritos

## 5. Ambiente com Docker Compose

- [x] 5.1 Criar `wiremock/mappings/provider-activate-success.json` (`POST /provider/v1/activate` → `200`) e adicionar o serviço `wiremock` (versão 3.x fixada, porta `8089`, health check em `/__admin/health`) ao `compose.yaml`; verificar com `docker compose up -d --wait wiremock` e `curl -X POST localhost:8089/provider/v1/activate` retornando `200`
- [ ] 5.2 Adicionar o serviço `app` ao `compose.yaml` (`build: .`, `DB_*` apontando para `postgres`, `depends_on` com `service_healthy`); verificar com `docker compose up --build --wait` deixando os três serviços `healthy` e `curl localhost:8080/actuator/health/readiness` com `UP`
- [ ] 5.3 Atualizar o README: subir tudo com `docker compose up --build`, nota de que o `compose.yaml` cumpre o `docker-compose.yml` pedido na seção Entrega e portas de cada serviço; verificar que os comandos citados rodam como escritos

## 6. Integração contínua

- [ ] 6.1 Criar `.github/workflows/ci.yml` com job `verify` (Temurin 21, cache Maven, `./mvnw -B verify`) e job `compose-smoke` (depende de `verify`: `docker compose up --build --wait`, `id -u` diferente de `0` no `app`, `curl` no mock do provedor, `docker compose logs` em falha e `docker compose down -v` sempre), disparado em `push` e `pull_request`; verificar a sintaxe com `actionlint` (se disponível) e o resultado verde no primeiro push da branch
- [ ] 6.2 Adicionar ao README o badge do workflow e verificar que o link aponta para `ci.yml`

## 7. Fechamento

- [ ] 7.1 Revisar README e ADR 0000 contra o estado final da change e rodar `./mvnw verify` com sucesso

## Workflow follow-up

- Abrir PR de `chore/bootstrap-project` para `main` e fazer o merge com merge commit após o CI verde.
- Arquivar a change com `/opsx:archive` e commitar `docs(spec): arquiva bootstrap-project`.

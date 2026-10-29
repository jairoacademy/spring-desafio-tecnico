# operations Specification

## Purpose

Define como o serviço de ativação é verificado, empacotado e executado: probes de saúde, imagem de contêiner, ambiente local com Docker Compose e pipeline de integração contínua.

## Requirements

### Requirement: Probe de liveness
O serviço SHALL expor `GET /actuator/health/liveness`, que responde `200` com `{"status":"UP"}` enquanto a aplicação está viva. A probe de liveness MUST NOT depender do banco nem do provedor externo, para que uma falha deles não reinicie a aplicação. (Itens 10 e 11, Observabilidade básica)

#### Scenario: Aplicação em execução
- **WHEN** um cliente chama `GET /actuator/health/liveness` com a aplicação iniciada
- **THEN** a resposta é `200` com `status` igual a `UP`

### Requirement: Probe de readiness
O serviço SHALL expor `GET /actuator/health/readiness`, que responde `200` com `{"status":"UP"}` quando a aplicação está pronta para receber tráfego. (Itens 10 e 11, Observabilidade básica)

#### Scenario: Aplicação pronta
- **WHEN** um cliente chama `GET /actuator/health/readiness` com a aplicação iniciada e o banco disponível
- **THEN** a resposta é `200` com `status` igual a `UP`

### Requirement: Exposição mínima do Actuator
O serviço SHALL expor via HTTP apenas o endpoint `health` do Actuator. Endpoints como `env`, `beans` e `configprops` MUST NOT ficar acessíveis, porque revelam configuração interna. (Item 7, Itens 10 e 11)

#### Scenario: Endpoint sensível não exposto
- **WHEN** um cliente chama `GET /actuator/env`
- **THEN** a resposta é `404`

### Requirement: Imagem de contêiner executável
O repositório SHALL conter um `Dockerfile` que gera uma imagem executável da aplicação. A imagem MUST rodar com usuário não-root, MUST receber a configuração por variáveis de ambiente e MUST limitar o heap da JVM a uma fração da memória do contêiner. (Item 11)

#### Scenario: Build e execução da imagem
- **WHEN** o pipeline de CI constrói a imagem e a executa com as variáveis `DB_URL`, `DB_USERNAME` e `DB_PASSWORD` apontando para um PostgreSQL acessível
- **THEN** o contêiner fica `healthy` e `GET /actuator/health/readiness` responde `200`

#### Scenario: Usuário não-root
- **WHEN** o pipeline de CI executa `id -u` dentro do contêiner da aplicação
- **THEN** o resultado é diferente de `0`

### Requirement: Ambiente local com um comando
O arquivo `compose.yaml` SHALL subir aplicação, PostgreSQL e mock do provedor externo com um único comando, cada serviço com health check. A aplicação MUST iniciar só depois que o banco estiver saudável. (Item 1, Diferencial Docker Compose; Item 11)

#### Scenario: Subida completa
- **WHEN** o pipeline de CI executa `docker compose up --build --wait` em um clone limpo
- **THEN** os serviços `postgres`, `app` e `wiremock` ficam `healthy` e o comando termina com sucesso

#### Scenario: Mock do provedor disponível
- **WHEN** o pipeline de CI chama `POST /provider/v1/activate` no serviço `wiremock` com o ambiente no ar
- **THEN** a resposta é `2xx`

### Requirement: Integração contínua
O repositório SHALL ter um pipeline de CI que roda build e testes com `./mvnw verify` e o smoke test do Docker Compose a cada push e pull request. Uma falha em qualquer etapa MUST marcar o pipeline como falho. (Item 1, Diferencial CI)

#### Scenario: Pull request com testes quebrados
- **WHEN** um pull request contém um teste que falha
- **THEN** o pipeline termina com status de falha

#### Scenario: Pull request saudável
- **WHEN** um pull request passa em `./mvnw verify` e no smoke test do Docker Compose
- **THEN** o pipeline termina com status de sucesso

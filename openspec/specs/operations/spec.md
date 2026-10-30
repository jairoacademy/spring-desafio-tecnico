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
O serviço SHALL expor via HTTP apenas os endpoints `health` e `prometheus` do Actuator. Endpoints como `env`, `beans` e `configprops` MUST NOT ficar acessíveis, porque revelam configuração interna. (Item 7, Itens 10 e 11, Observabilidade avançada)

#### Scenario: Endpoint sensível não exposto
- **WHEN** um cliente chama `GET /actuator/env`
- **THEN** a resposta é `404`

#### Scenario: Endpoint de métricas exposto
- **WHEN** um cliente chama `GET /actuator/prometheus` sem token
- **THEN** a resposta é `200`

### Requirement: Imagem de contêiner executável
O repositório SHALL conter um `Dockerfile` que gera uma imagem executável da aplicação. A imagem MUST rodar com usuário não-root, MUST receber a configuração por variáveis de ambiente e MUST limitar o heap da JVM a uma fração da memória do contêiner. (Item 11)

#### Scenario: Build e execução da imagem
- **WHEN** o pipeline de CI constrói a imagem e a executa com as variáveis `DB_URL`, `DB_USERNAME` e `DB_PASSWORD` apontando para um PostgreSQL acessível
- **THEN** o contêiner fica `healthy` e `GET /actuator/health/readiness` responde `200`

#### Scenario: Usuário não-root
- **WHEN** o pipeline de CI executa `id -u` dentro do contêiner da aplicação
- **THEN** o resultado é diferente de `0`

### Requirement: Ambiente local com um comando
O arquivo `compose.yaml` SHALL subir aplicação, PostgreSQL, mock do provedor externo e emissor de tokens de desenvolvimento com um único comando, cada serviço com health check declarado no próprio `compose.yaml`. A aplicação MUST iniciar só depois que o banco estiver saudável. O emissor MUST entregar, por client credentials, tokens aceitos pela aplicação do Compose. (Item 1, Diferencial Docker Compose; Itens 7 e 11)

#### Scenario: Subida completa
- **WHEN** o pipeline de CI executa `docker compose up --build --wait` em um clone limpo
- **THEN** os serviços `postgres`, `app`, `wiremock` e `oauth2` ficam `healthy` e o comando termina com sucesso

#### Scenario: Mock do provedor disponível
- **WHEN** o pipeline de CI chama `POST /provider/v1/activate` no serviço `wiremock` com o ambiente no ar
- **THEN** a resposta é `2xx`

#### Scenario: Token de desenvolvimento aceito pela aplicação
- **WHEN** o pipeline de CI obtém um token por client credentials no serviço `oauth2` para um cliente de exemplo com `activation:read` e chama `GET /api/v1/requests` na aplicação do Compose com esse token
- **THEN** a resposta é `200`

#### Scenario: Métricas no ambiente do Compose
- **WHEN** o pipeline de CI chama `GET /actuator/prometheus` na aplicação do Compose
- **THEN** a resposta é `200` e contém `http_server_requests_seconds`

### Requirement: Integração contínua
O repositório SHALL ter um pipeline de CI que roda build e testes com `./mvnw verify` e o smoke test do Docker Compose a cada push e pull request. Uma falha em qualquer etapa MUST marcar o pipeline como falho. (Item 1, Diferencial CI)

#### Scenario: Pull request com testes quebrados
- **WHEN** um pull request contém um teste que falha
- **THEN** o pipeline termina com status de falha

#### Scenario: Pull request saudável
- **WHEN** um pull request passa em `./mvnw verify` e no smoke test do Docker Compose
- **THEN** o pipeline termina com status de sucesso

### Requirement: Correlação da requisição até o provedor
Toda solicitação criada pela API SHALL guardar o contexto de trace da requisição de criação. O envio dessa solicitação ao provedor MUST continuar o mesmo trace: os logs do envio e o header `traceparent` enviado ao provedor MUST ter o mesmo `traceId` da requisição de criação. Solicitação sem contexto guardado MUST ser enviada em um trace novo. (Observabilidade básica, Item 5)

#### Scenario: Provedor recebe o traceId do POST
- **WHEN** um cliente cria uma solicitação com `POST /api/v1/requests` e o worker a envia ao provedor
- **THEN** a chamada ao provedor tem o header `traceparent` com o mesmo `traceId` da resposta de criação

#### Scenario: Logs do envio com o traceId do POST
- **WHEN** o provedor responde `500` ao envio de uma solicitação criada pela API
- **THEN** o log do envio dessa solicitação tem o mesmo `traceId` da requisição de criação

#### Scenario: Retry no mesmo trace
- **WHEN** o primeiro envio de uma solicitação falha com `500` e o segundo é aceito
- **THEN** as duas chamadas ao provedor têm o header `traceparent` com o `traceId` da requisição de criação

#### Scenario: Solicitação sem contexto guardado
- **WHEN** o worker envia uma solicitação gravada sem contexto de trace
- **THEN** a chamada ao provedor tem um header `traceparent` válido e a solicitação é processada normalmente

### Requirement: Logs estruturados em JSON
A aplicação SHALL escrever os logs no console em JSON, um objeto por linha. Todo log emitido durante uma requisição HTTP ou um envio ao provedor MUST ter os campos `traceId` e `spanId`. Os logs MUST NOT conter tokens nem credenciais. (Observabilidade básica e avançada)

#### Scenario: Log de erro com traceId
- **WHEN** um endpoint lança um erro inesperado e a API responde `500` com `traceId` no ProblemDetail
- **THEN** o console tem uma linha JSON com o nível `ERROR` e o campo `traceId` igual ao do ProblemDetail

#### Scenario: Token fora dos logs
- **WHEN** um cliente chama a API com um token inválido e a resposta é `401`
- **THEN** nenhuma linha do console contém o valor do token

### Requirement: Métricas para Prometheus
A aplicação SHALL expor `GET /actuator/prometheus` no formato de texto do Prometheus, sem exigir token. A resposta MUST conter a latência das requisições da API, a latência e o resultado das chamadas ao provedor e a contagem de solicitações por status. As métricas MUST NOT ter como rótulo dados de negócio (id, `externalId`, `customerId`). (Observabilidade avançada)

#### Scenario: Latência da API
- **WHEN** um cliente chama `GET /api/v1/requests` com token válido e depois `GET /actuator/prometheus` sem token
- **THEN** a resposta é `200` e contém `http_server_requests_seconds` com `uri="/api/v1/requests"`

#### Scenario: Latência e erros do provedor
- **WHEN** o worker envia uma solicitação e o provedor responde `500`
- **THEN** `GET /actuator/prometheus` contém `http_client_requests_seconds` com `uri="/provider/v1/activate"` e `status="500"`

#### Scenario: Solicitações por status
- **WHEN** um cliente cria uma solicitação e o provedor a aceita
- **THEN** `GET /actuator/prometheus` contém `activation_requests_transitions_total` com `status="PENDING"`, `status="PROCESSING"` e `status="COMPLETED"`

#### Scenario: Sem identificadores nos rótulos
- **WHEN** um cliente consulta `GET /api/v1/requests/{id}` e depois `GET /actuator/prometheus`
- **THEN** a resposta não contém o `id` consultado

### Requirement: Exportação de traces configurável
A aplicação SHALL exportar os spans por OTLP para o endpoint informado na configuração. Sem endpoint configurado, a aplicação MUST NOT tentar exportar e MUST funcionar normalmente. (Observabilidade avançada)

#### Scenario: Exportação ligada
- **WHEN** o endpoint OTLP aponta para um coletor e um cliente chama a API
- **THEN** o coletor recebe spans da requisição

#### Scenario: Exportação desligada por padrão
- **WHEN** a aplicação sobe sem endpoint OTLP configurado
- **THEN** os probes de saúde respondem `200` e nenhuma exportação é tentada

### Requirement: Encerramento gracioso
Ao receber o pedido de parada, a aplicação SHALL parar de iniciar novos ciclos do worker e MUST esperar o ciclo em andamento terminar, dentro de um prazo configurado, antes de fechar o acesso ao banco. Requisições HTTP em andamento MUST terminar antes da parada. (Item 11)

#### Scenario: Envio em andamento na parada
- **WHEN** a aplicação recebe o pedido de parada enquanto o worker espera a resposta lenta, mas dentro do prazo, do provedor a uma solicitação
- **THEN** a solicitação termina em `COMPLETED` e não fica em `PROCESSING`

### Requirement: Regras de camadas verificadas
O build SHALL falhar quando o código violar as regras de dependência entre camadas: `domain` não depende de `api`, `application` nem `infrastructure`; `api` não depende de `infrastructure`; nenhuma camada depende de `api`; `infrastructure` não depende de `application`. (Item 8, Diferencial Arquitetura)

#### Scenario: Código atual respeita as regras
- **WHEN** o pipeline roda `./mvnw verify`
- **THEN** o teste de arquitetura passa

#### Scenario: Violação quebra o build
- **WHEN** uma classe de `domain` importa uma classe de `infrastructure`
- **THEN** o teste de arquitetura falha e indica a dependência proibida

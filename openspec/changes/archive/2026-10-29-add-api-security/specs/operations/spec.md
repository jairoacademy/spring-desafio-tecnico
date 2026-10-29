# Spec Delta

## MODIFIED Requirements

### Requirement: Ambiente local com um comando
O arquivo `compose.yaml` SHALL subir aplicação, PostgreSQL, mock do provedor externo e emissor de tokens de desenvolvimento com um único comando, cada serviço com health check. A aplicação MUST iniciar só depois que o banco estiver saudável. O emissor MUST entregar, por client credentials, tokens aceitos pela aplicação do Compose. (Item 1, Diferencial Docker Compose; Itens 7 e 11)

#### Scenario: Subida completa
- **WHEN** o pipeline de CI executa `docker compose up --build --wait` em um clone limpo
- **THEN** os serviços `postgres`, `app`, `wiremock` e `oauth2` ficam `healthy` e o comando termina com sucesso

#### Scenario: Mock do provedor disponível
- **WHEN** o pipeline de CI chama `POST /provider/v1/activate` no serviço `wiremock` com o ambiente no ar
- **THEN** a resposta é `2xx`

#### Scenario: Token de desenvolvimento aceito pela aplicação
- **WHEN** o pipeline de CI obtém um token por client credentials no serviço `oauth2` para um cliente de exemplo com `activation:read` e chama `GET /api/v1/requests` na aplicação do Compose com esse token
- **THEN** a resposta é `200`

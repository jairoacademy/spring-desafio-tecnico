# Spec Delta

## Purpose

Protege a API de solicitações de ativação para consumo entre sistemas: só aceita chamadas com JWT válido emitido por um servidor de autorização OAuth 2.0 confiável, autoriza cada operação por escopo e identifica o cliente que fez a chamada.

## ADDED Requirements

### Requirement: Autenticação obrigatória por JWT
Toda chamada a `/api/v1/requests` e seus subcaminhos SHALL exigir o header `Authorization: Bearer <JWT>`. Uma chamada sem token, com esquema diferente de `Bearer` ou com token malformado MUST receber `401`, sem executar a operação. (Item 7, Obrigatório)

#### Scenario: Criação sem token
- **WHEN** um cliente envia `POST /api/v1/requests` com corpo válido e sem header `Authorization`
- **THEN** a resposta é `401` e nenhuma solicitação é criada

#### Scenario: Consulta sem token
- **WHEN** um cliente chama `GET /api/v1/requests` sem header `Authorization`
- **THEN** a resposta é `401`

#### Scenario: Token malformado
- **WHEN** um cliente chama `GET /api/v1/requests` com `Authorization: Bearer abc`
- **THEN** a resposta é `401`

### Requirement: Validação do token
O serviço SHALL aceitar só JWT com assinatura válida por uma chave do JWKS configurado, `iss` igual ao emissor configurado, `aud` contendo a audiência configurada e `exp` no futuro. O JWT MUST trazer a claim `client_id` não vazia. Qualquer falha MUST resultar em `401`. (Item 7, Obrigatório)

#### Scenario: Token válido
- **WHEN** um cliente chama `GET /api/v1/requests` com JWT assinado pela chave do JWKS, com emissor, audiência, `client_id`, escopo `activation:read` e expiração futura
- **THEN** a resposta é `200`

#### Scenario: Assinatura de chave desconhecida
- **WHEN** um cliente envia JWT com as claims corretas, mas assinado por uma chave que não está no JWKS
- **THEN** a resposta é `401`

#### Scenario: Emissor diferente
- **WHEN** um cliente envia JWT válido em tudo, exceto `iss` diferente do emissor configurado
- **THEN** a resposta é `401`

#### Scenario: Audiência diferente
- **WHEN** um cliente envia JWT válido em tudo, exceto `aud` sem a audiência configurada
- **THEN** a resposta é `401`

#### Scenario: Token expirado
- **WHEN** um cliente envia JWT válido em tudo, exceto `exp` no passado além da tolerância de relógio
- **THEN** a resposta é `401`

#### Scenario: Token sem client_id
- **WHEN** um cliente envia JWT válido em tudo, exceto sem a claim `client_id`
- **THEN** a resposta é `401`

### Requirement: Autorização por escopo
`POST /api/v1/requests` SHALL exigir o escopo `activation:write`. `GET /api/v1/requests` e `GET /api/v1/requests/{id}` SHALL exigir o escopo `activation:read`. Um escopo não implica o outro. Token válido sem o escopo da operação MUST receber `403`, sem executar a operação. (Item 7, Obrigatório e Diferencial)

#### Scenario: Criação só com escopo de leitura
- **WHEN** um cliente envia `POST /api/v1/requests` com corpo válido e JWT só com `activation:read`
- **THEN** a resposta é `403` e nenhuma solicitação é criada

#### Scenario: Consulta só com escopo de escrita
- **WHEN** um cliente chama `GET /api/v1/requests/{id}` com JWT só com `activation:write`
- **THEN** a resposta é `403`

#### Scenario: Listagem sem escopo
- **WHEN** um cliente chama `GET /api/v1/requests` com JWT sem nenhum escopo
- **THEN** a resposta é `403`

#### Scenario: Criação com escopo de escrita
- **WHEN** um cliente envia `POST /api/v1/requests` com corpo válido e JWT com `activation:write`
- **THEN** a resposta é `201`

### Requirement: Erros 401 e 403 em Problem Details
As respostas `401` e `403` SHALL usar `application/problem+json` com `type`, `title`, `status`, `detail`, `instance` e `traceId`, como os demais erros da API. O `detail` MUST NOT trazer o token nem detalhes internos da validação. A resposta `401` MUST trazer o header `WWW-Authenticate` com esquema `Bearer`. (Itens 6 e 7)

#### Scenario: Corpo do 401
- **WHEN** um cliente chama `GET /api/v1/requests` sem token
- **THEN** a resposta é `401` em `application/problem+json`, com `status` 401, `instance` `/api/v1/requests`, `traceId` preenchido e header `WWW-Authenticate` iniciado por `Bearer`

#### Scenario: Corpo do 403
- **WHEN** um cliente envia `POST /api/v1/requests` com JWT só com `activation:read`
- **THEN** a resposta é `403` em `application/problem+json`, com `status` 403, `instance` `/api/v1/requests` e `traceId` preenchido

### Requirement: Probes de saúde públicos
Os endpoints `/actuator/health/liveness` e `/actuator/health/readiness` SHALL responder sem autenticação, para uso pelo orquestrador de contêineres. (Itens 7 e 11)

#### Scenario: Liveness sem token
- **WHEN** o orquestrador chama `GET /actuator/health/liveness` sem header `Authorization`
- **THEN** a resposta é `200` com `status` `UP`

#### Scenario: Readiness sem token
- **WHEN** o orquestrador chama `GET /actuator/health/readiness` sem header `Authorization`
- **THEN** a resposta é `200` com `status` `UP`

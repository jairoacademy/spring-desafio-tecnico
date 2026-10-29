# Spec Delta

## MODIFIED Requirements

### Requirement: Criação de solicitação
O serviço SHALL expor `POST /api/v1/requests`, que recebe `externalId`, `customerId` e `productCode` em JSON e cria uma solicitação com status `PENDING` quando o `externalId` ainda não está registrado. Nesse caso, a resposta MUST ser `201 Created`, com header `Location` apontando para `/api/v1/requests/{id}` e corpo contendo `id`, `externalId`, `customerId`, `productCode`, `status`, `createdAt` e `updatedAt`. Uma criação com `externalId` já registrado MUST seguir as regras da capability `duplicate-prevention`. (Itens 2, 3 e 4)

#### Scenario: Criação com dados válidos
- **WHEN** um cliente envia `POST /api/v1/requests` com `{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}`
- **THEN** a resposta é `201`, o header `Location` termina com `/api/v1/requests/{id}` e o corpo traz os três campos enviados, `status` igual a `PENDING` e `id` em formato UUID

#### Scenario: Solicitação criada fica persistida
- **WHEN** um cliente cria uma solicitação e em seguida chama `GET` na URL do header `Location`
- **THEN** a resposta é `200` com os mesmos `id`, `externalId`, `customerId`, `productCode`, `status`, `createdAt` e `updatedAt` da criação

#### Scenario: externalId repetido não gera 201
- **WHEN** um cliente envia duas vezes `POST /api/v1/requests` com o mesmo `externalId`
- **THEN** só a primeira resposta é `201`; a segunda é `200` ou `409`, conforme `duplicate-prevention`

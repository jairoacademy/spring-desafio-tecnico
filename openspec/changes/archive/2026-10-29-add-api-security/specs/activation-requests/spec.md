# Spec Delta

## MODIFIED Requirements

### Requirement: Criação de solicitação
O serviço SHALL expor `POST /api/v1/requests`, que recebe `externalId`, `customerId` e `productCode` em JSON e cria uma solicitação com status `PENDING` quando o `externalId` ainda não está registrado. Nesse caso, a resposta MUST ser `201 Created`, com header `Location` apontando para `/api/v1/requests/{id}` e corpo contendo `id`, `externalId`, `customerId`, `productCode`, `status`, `createdAt` e `updatedAt`. A solicitação MUST ficar registrada com o cliente autenticado como dono (capability `api-security`); o dono não aparece no corpo. Uma criação com `externalId` já registrado MUST seguir as regras da capability `duplicate-prevention`. (Itens 2, 3, 4 e 7)

#### Scenario: Criação com dados válidos
- **WHEN** um cliente envia `POST /api/v1/requests` com `{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}`
- **THEN** a resposta é `201`, o header `Location` termina com `/api/v1/requests/{id}` e o corpo traz os três campos enviados, `status` igual a `PENDING` e `id` em formato UUID

#### Scenario: Solicitação criada fica persistida
- **WHEN** um cliente cria uma solicitação e em seguida chama `GET` na URL do header `Location`
- **THEN** a resposta é `200` com os mesmos `id`, `externalId`, `customerId`, `productCode`, `status`, `createdAt` e `updatedAt` da criação

#### Scenario: externalId repetido não gera 201
- **WHEN** um cliente envia duas vezes `POST /api/v1/requests` com o mesmo `externalId`
- **THEN** só a primeira resposta é `201`; a segunda é `200` ou `409`, conforme `duplicate-prevention`

#### Scenario: Dono gravado a partir do token
- **WHEN** o cliente `sistema-a` cria uma solicitação
- **THEN** a solicitação fica gravada com dono `sistema-a` e o corpo da resposta não traz o dono

### Requirement: Consulta por id
O serviço SHALL expor `GET /api/v1/requests/{id}`, que retorna `200` com a solicitação e seu status atual quando ela pertence ao cliente autenticado. Se não existir solicitação com o `id`, ou se ela pertencer a outro cliente, a resposta MUST ser `404`, sem distinguir os dois casos. Um `id` que não seja UUID MUST resultar em `400`. (Itens 2, 6 e 7)

#### Scenario: Solicitação existente
- **WHEN** um cliente chama `GET /api/v1/requests/{id}` com o `id` de uma solicitação criada por ele
- **THEN** a resposta é `200` com os dados e o `status` atual da solicitação

#### Scenario: Solicitação inexistente
- **WHEN** um cliente chama `GET /api/v1/requests/{id}` com um UUID que não pertence a nenhuma solicitação
- **THEN** a resposta é `404` em ProblemDetail

#### Scenario: Solicitação de outro cliente
- **WHEN** o cliente `sistema-a` cria uma solicitação e o cliente `sistema-b` chama `GET /api/v1/requests/{id}` com o `id` dela
- **THEN** a resposta para `sistema-b` é `404` em ProblemDetail, com o mesmo `type`, `title` e formato de `detail` de um id inexistente

#### Scenario: Id em formato inválido
- **WHEN** um cliente chama `GET /api/v1/requests/abc`
- **THEN** a resposta é `400` em ProblemDetail

### Requirement: Listagem paginada com filtros
O serviço SHALL expor `GET /api/v1/requests` com filtros opcionais `status` e `customerId` combináveis e paginação por `page` (a partir de 0, padrão 0) e `size` (1 a 100, padrão 20). A listagem MUST conter só solicitações do cliente autenticado, e os metadados de paginação MUST contar só essas. Os itens MUST vir do mais recente para o mais antigo por `createdAt`. A resposta MUST trazer `content` e os metadados `page`, `size`, `totalElements` e `totalPages`. (Itens 2 e 7, Diferencial)

#### Scenario: Filtro por cliente
- **WHEN** existem solicitações dos clientes `CUST-A` e `CUST-B` e um cliente chama `GET /api/v1/requests?customerId=CUST-A`
- **THEN** a resposta é `200` e `content` contém apenas solicitações de `CUST-A`

#### Scenario: Filtro combinado por status e cliente
- **WHEN** um cliente chama `GET /api/v1/requests?status=PENDING&customerId=CUST-A`
- **THEN** `content` contém apenas solicitações de `CUST-A` com status `PENDING`

#### Scenario: Paginação e ordenação
- **WHEN** existem 3 solicitações de `CUST-P` e um cliente chama `GET /api/v1/requests?customerId=CUST-P&page=0&size=2`
- **THEN** `content` tem 2 itens, o primeiro é a solicitação criada por último, `totalElements` é 3 e `totalPages` é 2

#### Scenario: Status desconhecido no filtro
- **WHEN** um cliente chama `GET /api/v1/requests?status=DONE`
- **THEN** a resposta é `400` em ProblemDetail

#### Scenario: Tamanho de página fora do limite
- **WHEN** um cliente chama `GET /api/v1/requests?size=101` ou `GET /api/v1/requests?page=-1`
- **THEN** a resposta é `400` em ProblemDetail

#### Scenario: Listagem isolada por dono
- **WHEN** `sistema-a` cria 2 solicitações e `sistema-b` cria 1, todas com `customerId` `CUST-SHARED`, e `sistema-b` chama `GET /api/v1/requests?customerId=CUST-SHARED`
- **THEN** `content` traz só a solicitação de `sistema-b` e `totalElements` é 1

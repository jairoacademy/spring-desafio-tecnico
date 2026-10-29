# Spec Delta

## Purpose

Define o formato único de erro da API (Problem Details, RFC 9457) com identificador de correlação, e garante que nenhum detalhe interno da aplicação chegue ao consumidor.

## ADDED Requirements

### Requirement: Erros no formato Problem Details
Toda resposta de erro (status `4xx` ou `5xx`) da API em `/api/v1/**` SHALL usar `Content-Type: application/problem+json` e conter `type`, `title`, `status`, `detail` e `instance`, em que `instance` é o caminho da requisição. (Item 6, Diferencial)

#### Scenario: Recurso inexistente
- **WHEN** um cliente chama `GET /api/v1/requests/{id}` com um UUID inexistente
- **THEN** a resposta tem `Content-Type` `application/problem+json`, `status` `404`, `title`, `detail`, `type` e `instance` igual a `/api/v1/requests/{id}`

#### Scenario: Método não suportado
- **WHEN** um cliente chama `DELETE /api/v1/requests/{id}`
- **THEN** a resposta é `405` em `application/problem+json`

### Requirement: Identificador de correlação no erro
Todo ProblemDetail da API SHALL conter o campo `traceId` com 32 caracteres hexadecimais minúsculos, igual ao identificador de correlação gravado nos logs da mesma requisição. (Item 6, Diferencial; Itens 10 e 11, Observabilidade básica)

#### Scenario: traceId presente no erro
- **WHEN** um cliente recebe qualquer resposta de erro da API
- **THEN** o corpo contém `traceId` no formato `^[0-9a-f]{32}$`

#### Scenario: traceId distinto por requisição
- **WHEN** um cliente faz duas requisições que resultam em erro
- **THEN** os dois `traceId` são diferentes

### Requirement: Erro de validação lista os campos inválidos
Uma resposta `400` causada por campos inválidos SHALL ter `type` de erro de validação e uma lista `errors`, em que cada item tem `field` (nome do campo no JSON) e `message` (motivo em português). (Item 6)

#### Scenario: Campo obrigatório ausente
- **WHEN** um cliente envia `POST /api/v1/requests` sem `customerId`
- **THEN** a resposta é `400`, `title` igual a `Dados inválidos`, e `errors` contém `{"field":"customerId","message":...}` com mensagem não vazia

### Requirement: Erro inesperado não expõe detalhes internos
Uma falha não tratada SHALL resultar em `500` com `title` e `detail` genéricos. O corpo MUST NOT conter stack trace, nome de classe de exceção, mensagem da exceção original nem SQL. A falha MUST ser registrada no log com o mesmo `traceId` devolvido ao cliente. (Item 6)

#### Scenario: Falha inesperada na camada de aplicação
- **WHEN** a consulta de uma solicitação lança uma exceção não prevista com a mensagem `segredo interno`
- **THEN** a resposta é `500` em ProblemDetail com `traceId`, e o corpo não contém `segredo interno`, `Exception` nem `at ` seguido de nome de classe

#### Scenario: Erro de entrada não vaza detalhe do parser
- **WHEN** um cliente envia `POST /api/v1/requests` com JSON malformado
- **THEN** a resposta é `400` e o `detail` não contém nome de classe nem mensagem do parser JSON

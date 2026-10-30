# API

[← Voltar ao README](../../README.md)

| Método e caminho | Escopo exigido | Descrição | Sucesso |
|---|---|---|---|
| `POST /api/v1/requests` | `activation:write` | Cria uma solicitação de ativação com status `PENDING`; o envio ao provedor é assíncrono | `201` + header `Location`; `200` em retry idêntico; `409` se o `externalId` já existe com outro conteúdo |
| `GET /api/v1/requests/{id}` | `activation:read` | Consulta uma solicitação do cliente e seu status atual | `200` (`404` se não existir ou for de outro cliente) |
| `GET /api/v1/requests` | `activation:read` | Lista as solicitações do cliente, da mais recente para a mais antiga | `200` |

## Autenticação e isolamento

Toda chamada a `/api/v1/requests` exige `Authorization: Bearer <JWT>` (OAuth 2.0, client credentials). Sem token, ou com token inválido, a resposta é `401`; com token válido sem o escopo da operação, `403`. Um escopo não implica o outro: um cliente que cria e consulta precisa dos dois.

O cliente é identificado pela claim `client_id` do token e vira o dono das solicitações que cria. Cada cliente só enxerga as próprias:

- a consulta por id de uma solicitação de outro cliente responde `404`, igual a um id inexistente, para não confirmar que o id existe (OWASP API1:2023, BOLA);
- a listagem e a contagem de `totalElements` consideram só as solicitações do cliente do token.

O dono não aparece no corpo das respostas. Os exemplos abaixo usam `$TOKEN` com um access token válido.

## Criar

```bash
curl -i -X POST localhost:8080/api/v1/requests \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}'
```

```http
HTTP/1.1 201
Location: http://localhost:8080/api/v1/requests/0b5c8f1e-6a51-4a3e-9d4c-2f8e7a1b3c9d
Content-Type: application/json

{
  "id": "0b5c8f1e-6a51-4a3e-9d4c-2f8e7a1b3c9d",
  "externalId": "EXT-12345",
  "customerId": "CUST-10001",
  "productCode": "INTERNET-500",
  "status": "PENDING",
  "failureReason": null,
  "createdAt": "2026-10-29T13:00:00.123456Z",
  "updatedAt": "2026-10-29T13:00:00.123456Z"
}
```

Datas são ISO-8601 em UTC (sufixo `Z`), com precisão de microssegundos.

A resposta sai sem esperar o provedor. Depois, o `status` muda sozinho (ver [Integração com o provedor](decisoes-tecnicas.md#integração-com-o-provedor)): em poucos segundos, com o provedor respondendo, a solicitação chega a `COMPLETED` ou `FAILED`.

O `externalId` é único em todo o serviço (ver [Duplicidade](decisoes-tecnicas.md#duplicidade)). Repetir o mesmo corpo, com o mesmo cliente, devolve `200` com o registro existente, no status atual, sem header `Location`. O mesmo `externalId` com `customerId` ou `productCode` diferente devolve `409`; se o `externalId` é de outro cliente, o `409` vem sem `conflictingFields`:

```bash
curl -i -X POST localhost:8080/api/v1/requests \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-900"}'
```

```http
HTTP/1.1 409
Content-Type: application/problem+json

{
  "type": "https://problems.ativacao.example/duplicate-external-id",
  "title": "Solicitação duplicada",
  "status": 409,
  "detail": "Já existe solicitação com externalId EXT-12345 e conteúdo diferente.",
  "instance": "/api/v1/requests",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "conflictingFields": ["productCode"]
}
```

## Consultar

```bash
curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/requests/0b5c8f1e-6a51-4a3e-9d4c-2f8e7a1b3c9d
```

```json
{
  "id": "0b5c8f1e-6a51-4a3e-9d4c-2f8e7a1b3c9d",
  "externalId": "EXT-12345",
  "customerId": "CUST-10001",
  "productCode": "INTERNET-500",
  "status": "FAILED",
  "failureReason": "PROVIDER_REJECTED",
  "createdAt": "2026-10-29T13:00:00.123456Z",
  "updatedAt": "2026-10-29T13:00:01.204518Z"
}
```

`failureReason` é `null` fora de `FAILED`. Em `FAILED`, vale `PROVIDER_REJECTED` (o provedor recusou o pedido) ou `MAX_ATTEMPTS_EXCEEDED` (limite de tentativas atingido). O campo aparece também na criação e na listagem; `GET /api/v1/requests?status=FAILED` lista as falhas definitivas.

## Listar

```bash
curl -H "Authorization: Bearer $TOKEN" 'localhost:8080/api/v1/requests?customerId=CUST-10001&status=PENDING&page=0&size=20'
```

| Parâmetro | Obrigatório | Regra |
|---|---|---|
| `status` | não | `PENDING`, `PROCESSING`, `COMPLETED` ou `FAILED` |
| `customerId` | não | mesma regra do campo na criação |
| `page` | não | a partir de `0`; padrão `0` |
| `size` | não | `1` a `100`; padrão `20` |

```json
{
  "content": [ { "id": "...", "status": "PENDING", "...": "..." } ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

A ordenação é fixa (`createdAt` decrescente, `id` como desempate) e não é exposta ao cliente: assim nenhuma consulta ordena por coluna sem índice.

## Regras de validação da criação

| Campo | Obrigatório | Tamanho | Caracteres aceitos |
|---|---|---|---|
| `externalId` | sim | 1 a 64 | letras, dígitos, `-` e `_` |
| `customerId` | sim | 1 a 64 | letras, dígitos, `-` e `_` |
| `productCode` | sim | 1 a 50 | letras **maiúsculas**, dígitos, `-` e `_` |

Se a validação falha, nenhuma solicitação é gravada. Corpo que não é JSON válido, ou com campo de tipo incompatível, também resulta em `400`.

## Formato de erro

Todo erro da API é um [Problem Details (RFC 9457)](https://www.rfc-editor.org/rfc/rfc9457) com `Content-Type: application/problem+json` e o campo `traceId`:

```json
{
  "type": "https://problems.ativacao.example/validation-error",
  "title": "Dados inválidos",
  "status": 400,
  "detail": "Um ou mais campos são inválidos.",
  "instance": "/api/v1/requests",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "errors": [
    { "field": "customerId", "message": "é obrigatório" }
  ]
}
```

| Situação | Status | `type` (sufixo) |
|---|---|---|
| Campos inválidos (corpo ou parâmetros da listagem) | `400` | `validation-error` (com `errors`) |
| Parâmetro com tipo inválido (`id` não UUID, `status` desconhecido) | `400` | `invalid-parameter` |
| Corpo ilegível | `400` | `malformed-body` |
| Token ausente, malformado ou inválido (assinatura, emissor, audiência, expiração, sem `client_id`) | `401` | `unauthorized` (com header `WWW-Authenticate: Bearer ...`) |
| Token válido sem o escopo da operação | `403` | `forbidden` |
| Solicitação inexistente ou de outro cliente | `404` | `not-found` |
| `externalId` já registrado com outro conteúdo (com `conflictingFields`) ou por outro cliente (sem `conflictingFields`) | `409` | `duplicate-external-id` |
| Erro inesperado | `500` | `internal-error` |
| Demais erros do HTTP (`405`, `415`, ...) | o do erro | `about:blank` |

O `type` usa o domínio reservado `.example`: é um identificador, não uma página. Respostas de erro nunca trazem stack trace, nome de classe, mensagem da exceção original nem detalhes do parser JSON. No `401` e no `403`, o `detail` é fixo: não repete o token nem o motivo da recusa (ex.: expiração); o motivo padrão da RFC 6750 vai só no header `WWW-Authenticate`. Um erro `500` é registrado no log em nível `ERROR` com o mesmo `traceId` devolvido ao cliente, o que permite localizar a falha a partir da resposta.

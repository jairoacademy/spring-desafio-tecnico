# ADR 0004 — Segurança da API: OAuth 2.0 client credentials, escopos e isolamento por cliente

**Status:** aceito

## Contexto

A API é consumida por outros sistemas, sem usuário humano no fluxo. Até aqui ela aceitava qualquer chamada: qualquer sistema criava solicitações e lia as de todos os outros. O item 7 do enunciado pede proteção com Spring Security, JWT e OAuth 2.0, escopos de leitura e escrita, testes de `401` e `403` sem servidor de autenticação e isolamento entre consumidores (OWASP API Top 10, API1:2023 Broken Object Level Authorization).

## Decisão

- **Fluxo client credentials.** Cada sistema consumidor tem `client_id` e segredo (ou chave privada, com `private_key_jwt`) no servidor de autorização. Ele pede um access token JWT de vida curta (`POST /token`, `grant_type=client_credentials`), envia `Authorization: Bearer <token>` e reaproveita o token até perto de expirar. Descartados: Authorization Code (exige usuário e navegador), API key estática (sem expiração nem escopo padronizados; rotação manual em cada consumidor) e token opaco com introspecção (uma chamada ao servidor de autorização por requisição).
- **Validação local do JWT, por JWKS.** A aplicação é um OAuth 2.0 Resource Server (Spring Security 7) e valida o token sozinha: assinatura por uma chave do JWKS configurado (`JWT_JWK_SET_URI`), `iss` igual a `JWT_ISSUER_URI`, `aud` contendo `JWT_AUDIENCE`, `exp` e `nbf` com 60 s de tolerância. O decoder é o do Spring Boot, montado pelas propriedades, sem código próprio de criptografia. As chaves ficam em cache e são buscadas de novo quando chega um `kid` desconhecido, o que cobre a rotação de chaves do servidor de autorização. `jwk-set-uri` em vez de só `issuer-uri`: a aplicação não depende do servidor de autorização na subida, e as chaves podem ser buscadas por um endereço interno diferente do `iss`.
- **Escopos por operação.** `POST /api/v1/requests` exige `activation:write`; `GET` (consulta e listagem) exige `activation:read`. Um escopo não implica o outro. Sem token ou com token inválido: `401` com `WWW-Authenticate: Bearer` (RFC 6750). Token válido sem o escopo: `403`. Os dois saem em ProblemDetail com `traceId` e `detail` fixo, sem o token nem o motivo interno da recusa. `/actuator/health/**` continua público para os probes do orquestrador.
- **`client_id` como dono.** O cliente é a claim `client_id`, definida para access tokens JWT na RFC 9068; token sem ela é recusado com `401`. Descartados: `sub` (em client credentials, alguns servidores põem o id do cliente e outros não) e `azp` (claim de ID token do OIDC). A criação grava o `client_id` na nova coluna `client_id` (migração `V4`), que não aparece nas respostas.
- **BOLA: solicitação de outro cliente é `404`.** A consulta por id filtra pelo dono no banco (`findByIdAndClientId`) e responde `404` com o mesmo corpo de um id inexistente; a listagem e o `totalElements` contam só as solicitações do cliente do token. `403` confirmaria que o id existe.
- **`externalId` de outro cliente é `409` sem detalhes.** O `externalId` continua único global (ADR 0001). A criação compara o dono antes do conteúdo: se o registro é de outro cliente, a resposta é sempre `409` sem `conflictingFields`, com conteúdo igual ou diferente. Devolver `200` com o registro vazaria `id` e status; e `conflictingFields` funcionaria como oráculo, deixando outro cliente descobrir por tentativa o `customerId` e o `productCode` alheios.

## Consequências

- Mudança incompatível: chamadas sem token passam a receber `401`. README, coleção Bruno e CI foram atualizados junto.
- `JWT_JWK_SET_URI`, `JWT_ISSUER_URI` e `JWT_AUDIENCE` são obrigatórias; sem elas, a aplicação não sobe.
- Token roubado vale até expirar. Mitigação: vida curta (minutos), HTTPS fora do ambiente local e escopos mínimos por cliente. Revogação imediata exigiria introspecção, descartada acima.
- Com o servidor de autorização fora do ar, tokens já emitidos continuam aceitos até `exp`, porque o JWKS está em cache; só falha o primeiro uso de uma chave nova.
- Um cliente pode "ocupar" um `externalId` que outro usaria; o segundo recebe `409` sem saber de quem é. A evolução para `UNIQUE (client_id, external_id)` está no ADR 0001.
- O nome da claim `client_id` é fixo no código. Um servidor de autorização que use outro nome exige uma mudança pequena e explícita.
- Linhas anteriores à `V4` ficam com dono `legacy` e não aparecem para nenhum cliente real.
- O `compose.yaml` traz um emissor de desenvolvimento (`mock-oauth2-server`) que aceita qualquer segredo. Ele não faz parte da imagem nem da configuração de produção.

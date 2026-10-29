# Design

## Context

Estado atual (motivação em proposal.md, Why):

- Não há Spring Security no classpath: `/api/v1/requests` e o Actuator respondem a qualquer chamada.
- `ActivationRequestService` expõe `create(externalId, customerId, productCode)`, `findById(id)` e `findAll(status, customerId, page, size)`. A listagem usa `ActivationRequestSpecifications.filter`.
- `ApiExceptionHandler` (`shared/web`) monta todo ProblemDetail e acrescenta `instance` e `traceId` (do `Tracer` do Micrometer) em `createResponseEntity`. Erros lançados em filtros de servlet não passam por ele.
- `V1` criou os índices da listagem `(customer_id, created_at DESC)` e `(status, created_at DESC)`; `V2`, a constraint única global de `external_id` (ADR 0001); `V3`, os campos do envio ao provedor.
- `compose.yaml` sobe `postgres`, `app` e `wiremock`. O CI (`compose-smoke`) roda `docker compose up --build --wait` e chama a readiness. A coleção Bruno aponta para a aplicação local em `http://localhost:8090`, porque o IIS do Windows ocupa 8080 e 8081.
- Requisitos em `specs/api-security/spec.md`, `specs/activation-requests/spec.md`, `specs/duplicate-prevention/spec.md` e `specs/operations/spec.md`.

## Goals / Non-Goals

**Goals:**
- Validação do JWT por configuração do Boot, sem código próprio de criptografia.
- Testes de `401` e `403` sem servidor de autenticação, e um teste de ponta a ponta da validação (`iss`, `aud`, `exp`, assinatura, `client_id`) com JWKS servido pelo WireMock.
- Isolamento por dono aplicado na consulta ao banco, e não filtrando depois de carregar.
- Ambiente do Compose e coleção Bruno continuam utilizáveis com a API protegida.

**Non-Goals:**
- Servidor de autorização próprio (o enunciado dispensa). O `mock-oauth2-server` só existe no Compose, para desenvolvimento.
- Limite de tamanho do corpo e de taxa de chamadas: descritos no README (decisão 13), sem implementação.
- Autenticação com o provedor externo: o enunciado não define credenciais para ele. O armazenamento e a rotação ficam descritos (decisão 12).
- Papéis administrativos que leem solicitações de todos os clientes.
- Expor o dono (`clientId`) no corpo das respostas.

## Decisions

**1. Client credentials, máquina a máquina (ADR 0004).**
Os consumidores são sistemas, sem usuário humano no fluxo. Cada sistema tem `client_id` e segredo (ou chave privada, com `private_key_jwt`) registrados no servidor de autorização. Fluxo:
1. O sistema chama `POST /token` do servidor de autorização com `grant_type=client_credentials`, as próprias credenciais e os escopos desejados.
2. O servidor devolve um access token JWT de vida curta (ex.: 5 a 15 min), assinado com a chave privada dele, com `iss`, `aud`, `exp`, `client_id` e `scope`.
3. O sistema chama a API com `Authorization: Bearer <token>` e reaproveita o token até perto de expirar.
4. A API valida o token sozinha, com a chave pública do JWKS, sem chamar o servidor de autorização a cada requisição.

Alternativas descartadas: Authorization Code (exige usuário e navegador), API key estática (sem expiração nem escopo padronizados; rotação manual em cada consumidor) e token opaco com introspecção (uma chamada ao servidor de autorização por requisição).

**2. Resource Server do Boot com JWKS por configuração.**
Starter `spring-boot-starter-security-oauth2-resource-server`. Propriedades:
```properties
spring.security.oauth2.resourceserver.jwt.jwk-set-uri=${JWT_JWK_SET_URI}
spring.security.oauth2.resourceserver.jwt.issuer-uri=${JWT_ISSUER_URI}
spring.security.oauth2.resourceserver.jwt.audiences=${JWT_AUDIENCE}
```
No Boot 4.1.2 (`JwtDecoderConfiguration`), com `jwk-set-uri` definido, o decoder busca as chaves nessa URL e usa `issuer-uri` só para o `JwtIssuerValidator`, sem descoberta OIDC na subida. `audiences` vira validador de `aud`. Os validadores padrão (`exp`, `nbf`, com 60 s de tolerância) continuam ativos. O Nimbus guarda o JWKS em cache e o busca de novo quando chega um `kid` desconhecido, o que cobre a rotação de chaves do servidor de autorização.

Por que `jwk-set-uri` em vez de só `issuer-uri`: no Compose, a aplicação busca as chaves pela rede interna (`http://oauth2:8080/...`), mas o `iss` dos tokens é o endereço visto do host (`http://localhost:8091/...`). Com descoberta, o emissor teria que ser alcançável pelos dois nomes. Também evita depender do servidor de autorização na subida.

Alternativa descartada: um `JwtDecoder` próprio. O Boot já monta o decoder com os validadores das propriedades e acrescenta qualquer bean `OAuth2TokenValidator<Jwt>` do contexto (decisão 3).

**3. Claim `client_id` obrigatória, validada no decoder.**
O dono da solicitação é a claim `client_id`, definida para access tokens JWT na RFC 9068. Um bean `OAuth2TokenValidator<Jwt>` (`JwtClaimValidator` de `client_id`: presente, não vazio, até 255 caracteres) entra na cadeia de validação do Boot. Token sem `client_id` vira `401` como qualquer token inválido, antes de chegar ao controller.

Alternativas descartadas: `sub` (em client credentials, alguns servidores põem o id do cliente, outros não; `client_id` tem semântica definida) e `azp` (claim de ID token do OIDC, não de access token). O nome da claim fica fixo no código; trocar de servidor de autorização que use outro nome é uma mudança pequena e explícita.

**4. `SecurityFilterChain` em DSL lambda (Spring Security 7).**
Classe `SecurityConfiguration` em `shared/security`:
```java
http
    .authorizeHttpRequests(auth -> auth
        .requestMatchers("/actuator/health/**").permitAll()
        .requestMatchers(HttpMethod.POST, "/api/v1/requests").hasAuthority("SCOPE_activation:write")
        .requestMatchers(HttpMethod.GET, "/api/v1/requests", "/api/v1/requests/*").hasAuthority("SCOPE_activation:read")
        .anyRequest().authenticated())
    .oauth2ResourceServer(rs -> rs
        .jwt(Customizer.withDefaults())
        .authenticationEntryPoint(entryPoint)
        .accessDeniedHandler(accessDeniedHandler))
    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
    .csrf(AbstractHttpConfigurer::disable);
```
- Escopos pelo conversor padrão (`scope`/`scp` → `SCOPE_*`). `hasAuthority` em vez de `hasScope` evita depender de helper.
- `anyRequest().authenticated()`: rota não mapeada ou método não suportado sem token vira `401`, antes do `404`/`405`. Com token válido, o MVC responde normalmente.
- CSRF desligado: API stateless, sem cookie de sessão; o token vai no header e não é enviado automaticamente pelo navegador.
- Sem APIs removidas do 6.x (`and()`, `authorizeRequests`, `antMatchers`, `WebSecurityConfigurerAdapter`).

**5. `401` e `403` em ProblemDetail, pelo mesmo montador do `ApiExceptionHandler`.**
Erros de autenticação e autorização acontecem no filtro, antes do `DispatcherServlet`, e não passam pelo `@RestControllerAdvice`. Dois componentes em `shared/security`:
- `AuthenticationEntryPoint`: delega para `BearerTokenAuthenticationEntryPoint` (que põe o status `401` e o header `WWW-Authenticate: Bearer ...` da RFC 6750) e escreve o corpo ProblemDetail (`type` `.../unauthorized`, `detail` fixo "Token de acesso ausente ou inválido.").
- `AccessDeniedHandler`: delega para `BearerTokenAccessDeniedHandler` (`403` com `WWW-Authenticate: Bearer error="insufficient_scope"`) e escreve o ProblemDetail (`type` `.../forbidden`, `detail` fixo "O token não tem o escopo exigido pela operação.").

Para não duplicar regra, a parte de `ApiExceptionHandler` que preenche `instance` e `traceId` vai para um componente `ProblemDetails` em `shared/web` (público, usado pelos dois pacotes). Os tipos `UNAUTHORIZED` e `FORBIDDEN` entram em `ProblemTypes`, que passa a ser público. O corpo é serializado pelo `JsonMapper` do Jackson 3 do contexto, com `Content-Type: application/problem+json`.

O `detail` é fixo: a mensagem da exceção (ex.: "Jwt expired at ...") não vai para o corpo. O header `WWW-Authenticate` do Spring traz o `error_description` padrão da RFC 6750, sem o token.

O `traceId` existe nesse ponto porque o filtro de observação HTTP do Micrometer roda antes da cadeia do Spring Security. Um teste confere o campo no `401`.

**6. Dono gravado na criação; coluna `client_id` na `V4`.**
`V4__request_owner.sql`:
```sql
ALTER TABLE activation_request ADD COLUMN client_id varchar(255) NOT NULL DEFAULT 'legacy';
ALTER TABLE activation_request ALTER COLUMN client_id DROP DEFAULT;
```
O `DEFAULT` só preenche linhas de bases locais já existentes; depois sai, para que toda inserção nova informe o dono. `ActivationRequest.create` recebe `clientId`, e a entidade ganha `getClientId()`. O campo não muda depois da criação. `varchar(255)` acomoda ids de servidores conhecidos (GUID do Entra ID, ids do Auth0 e do Keycloak) e casa com o limite do validador.

**7. Isolamento aplicado na consulta (BOLA → `404`).**
- `findById(clientId, id)` usa `repository.findByIdAndClientId(id, clientId)`. Sem resultado, lança a mesma `ActivationRequestNotFoundException`, com a mesma mensagem: inexistente e de outro dono são indistinguíveis.
- `findAll(clientId, status, customerId, page, size)` acrescenta `hasClientId(clientId)` à `Specification`. `totalElements` conta só as do dono.
- O controller obtém o dono com `@AuthenticationPrincipal Jwt jwt` e `jwt.getClaimAsString("client_id")`. Lê a claim do `Jwt`, e não `Authentication.getName()`, porque o `jwt()` de teste monta o nome a partir de `sub` e o resultado seria diferente do de produção.

Por que `404` e não `403`: `403` confirma que o id existe. Com `404`, ids de outros clientes são indistinguíveis de ids inexistentes (OWASP API1:2023).

**8. Índices da listagem prefixados pelo dono.**
Toda listagem passa a filtrar por `client_id`. A `V4` troca os índices de `V1`:
```sql
DROP INDEX ix_activation_request_customer_created;
DROP INDEX ix_activation_request_status_created;
CREATE INDEX ix_activation_request_client_created ON activation_request (client_id, created_at DESC);
CREATE INDEX ix_activation_request_client_customer_created ON activation_request (client_id, customer_id, created_at DESC);
CREATE INDEX ix_activation_request_client_status_created ON activation_request (client_id, status, created_at DESC);
```
Os três casos (sem filtro, por cliente, por status) leem já na ordem da paginação. Com `customerId` e `status` juntos, o índice de cliente filtra e o status é verificado nas linhas. Os índices do worker (`V3`) não mudam, porque o worker processa solicitações de todos os donos.

**9. Duplicidade entre donos: `409` sem detalhes (ADR 0001 e ADR 0004).**
O `externalId` continua único global (ADR 0001). Em `ActivationRequestService.resolveExisting`, antes de comparar o conteúdo:
- dono diferente: lança `ActivationRequestConflictException` sem campos divergentes; o handler omite `conflictingFields` quando a lista é vazia, e o `detail` diz só que o `externalId` já está em uso;
- mesmo dono: regra atual (`200` no retry idêntico, `409` com `conflictingFields`).

Motivo: devolver `200` com o registro de outro dono vazaria `id` e status. E `conflictingFields` funcionaria como oráculo: tentando valores, outro cliente descobriria o `customerId` e o `productCode` da solicitação alheia. O caminho da corrida (violação da constraint seguida de releitura) passa pela mesma regra.

**10. Emissor de tokens de desenvolvimento no Compose.**
Serviço `oauth2` com `ghcr.io/navikt/mock-oauth2-server:6.0.4` (versão fixa, publicada em 2026-09-29), porta `8091` no host. A configuração (`JSON_CONFIG`, arquivo `oauth2/config.json`) mapeia cada `client_id` para claims fixas:

| Cliente | Escopos |
|---|---|
| `sistema-a` | `activation:read activation:write` |
| `sistema-b` | `activation:read activation:write` |
| `sistema-leitura` | `activation:read` |

Todos com `aud` `ativacao-api` e `client_id` igual ao nome. O servidor aceita qualquer segredo: é só para desenvolvimento, e o README diz isso. O `iss` segue o host da requisição de token (`http://localhost:8091/default` quando o token é pedido do host). Verificado com a imagem 6.0.4: claims conforme a tabela e `wget` disponível para o health check (`/default/.well-known/openid-configuration`). A imagem não tem `curl` nem shell.

Variáveis do serviço `app`: `JWT_JWK_SET_URI=http://oauth2:8080/default/jwks`, `JWT_ISSUER_URI=http://localhost:8091/default`, `JWT_AUDIENCE=ativacao-api`. O perfil `local` usa `http://localhost:8091/default/jwks` e o mesmo emissor. O `app` não depende do `oauth2` na subida: as chaves são buscadas na primeira requisição autenticada.

O job `compose-smoke` do CI ganha um passo: obtém token de `sistema-leitura` e chama `GET /api/v1/requests` esperando `200`.

Alternativa descartada: Keycloak (imagem grande, subida lenta e configuração de realm para algo que é só ambiente de desenvolvimento).

**11. Testes.**
- **Unitários de MVC com `jwt()`** (`SecurityMockMvcRequestPostProcessors`): a classe `ActivationRequestApiIntegrationTests` e as demais que usam MockMvc passam a enviar `jwt().jwt(j -> j.claim("client_id", ...)).authorities(new SimpleGrantedAuthority("SCOPE_activation:..."))` por um helper de teste. Testes novos (`ApiSecurityIntegrationTests`): `401` sem token, com corpo ProblemDetail, `traceId` e `WWW-Authenticate`; `403` por escopo em cada endpoint; health sem token; BOLA na consulta, na listagem e na duplicidade.
- **Validação do token de ponta a ponta** (`JwtValidationIntegrationTests`): o WireMock serve um JWKS com uma chave RSA gerada no teste; tokens assinados com Nimbus (`nimbus-jose-jwt`, que já vem com o Resource Server). Casos: válido (`200`), chave desconhecida, `iss` errado, `aud` errado, expirado e sem `client_id` (todos `401`). `jwt()` não serve aqui, porque pula o decoder.
- **Persistência**: o teste de repositório confere `findByIdAndClientId` e o filtro por dono.
- `src/test/resources/config/application.properties` define `jwk-set-uri` (endereço sem servidor, só para o Boot criar o decoder; o `jwt()` não o usa), `issuer-uri` e `audiences`.

**12. Credencial do provedor externo: armazenamento e rotação (descrito, não implementado).**
Hoje o provedor não exige autenticação. Em produção:
- **Armazenamento**: segredo em um cofre (AWS Secrets Manager, Azure Key Vault, HashiCorp Vault), lido pela aplicação na subida pela integração do Spring Cloud com o cofre ou injetado como variável pelo orquestrador (Kubernetes Secret alimentado pelo External Secrets Operator). Nunca no repositório, na imagem ou em log.
- **Preferência por OAuth 2.0 client credentials também na saída**: a aplicação obtém token de vida curta para o provedor com `spring-boot-starter-security-oauth2-client` e o `OAuth2ClientHttpRequestInterceptor` no `RestClient`. O segredo de longa duração fica só entre a aplicação e o servidor de autorização; melhor ainda com `private_key_jwt` ou mTLS, em que não há segredo compartilhado.
- **Rotação sem parada**: o cofre gera a credencial nova enquanto a antiga continua válida; a aplicação passa a usar a nova (releitura periódica ou novo deploy); a antiga é revogada depois de uma janela maior que o ciclo de releitura. Com chave de API simples, o provedor precisa aceitar duas chaves ativas durante a janela.
- **Falha de autenticação no provedor** (`401`/`403` na chamada): hoje cairia em `PROVIDER_REJECTED` ou retry, conforme o status. Com credencial real, `401` do provedor vira alerta operacional, não falha da solicitação.

**13. Limites de corpo e de taxa (descrito, não implementado).**
- Corpo: o payload válido tem menos de 300 bytes. Limite no gateway/ingress (ex.: `client_max_body_size 16k` no NGINX) ou `server.tomcat.max-swallow-size` e um filtro que rejeita `Content-Length` acima de 16 KB com `413`.
- Taxa: por `client_id`, no API gateway (onde o estado é compartilhado entre instâncias) ou com Bucket4j + Redis na aplicação, respondendo `429` com `Retry-After`. Limite em memória por instância não funciona com várias réplicas.

**14. Dependências.**
- `spring-boot-starter-security-oauth2-resource-server`: Spring Security, Resource Server JWT e Nimbus. Nome confirmado no `pom.xml` gerado pelo start.spring.io para o Boot 4.1.2.
- `spring-boot-starter-security-oauth2-resource-server-test` (teste): traz `spring-security-test` com `jwt()`. Mesmo critério de confirmação.
- Nenhuma dependência nova para o teste de ponta a ponta: WireMock e Nimbus já estão no classpath de teste.

## Risks / Trade-offs

- [Mudança incompatível: consumidores atuais passam a receber `401`] → Não há consumidor em produção. README, Bruno e CI são atualizados nesta change.
- [Servidor de autorização fora do ar] → Tokens já emitidos continuam válidos até `exp`, e o JWKS fica em cache. Na subida, a aplicação não depende dele. Só falha o primeiro uso de chave nova.
- [Rotação de chave do servidor de autorização] → O Nimbus busca o JWKS de novo ao ver `kid` desconhecido. O servidor deve publicar a chave nova antes de começar a assinar com ela.
- [Token roubado vale até expirar] → Vida curta (minutos), HTTPS obrigatório fora do ambiente local e escopos mínimos por cliente. Revogação imediata exigiria introspecção, descartada na decisão 1.
- [`externalId` global entre clientes] → Um cliente pode "ocupar" um `externalId` que outro usaria, e o segundo recebe `409` sem saber de quem é. Coerente com o enunciado; a evolução para `UNIQUE (client_id, external_id)` está no ADR 0001.
- [`mock-oauth2-server` aceita qualquer segredo] → Só existe no `compose.yaml` de desenvolvimento, com aviso no README. A imagem de produção não o inclui.
- [Linhas antigas com dono `legacy`] → Só em bases locais anteriores à `V4`; nenhum cliente real tem esse `client_id`, então essas linhas ficam invisíveis pela API.

## Migration Plan

1. Registrar a aplicação no servidor de autorização (audiência `ativacao-api`) e os clientes com seus escopos.
2. Definir `JWT_JWK_SET_URI`, `JWT_ISSUER_URI` e `JWT_AUDIENCE` no ambiente. Sem elas, a aplicação não sobe.
3. Deploy: a `V4` roda na subida e adiciona `client_id` e os índices. Em uma base grande, os `CREATE INDEX` deveriam ser `CONCURRENTLY` em migração separada; aqui a base é vazia.
4. Rollback: voltar a versão anterior da aplicação. A coluna `client_id` e os índices novos não atrapalham a versão anterior, exceto pelo `NOT NULL` sem default: a versão anterior não informa o dono e o insert falharia. Para rollback real, uma migração `V5` recoloca o `DEFAULT 'legacy'`.

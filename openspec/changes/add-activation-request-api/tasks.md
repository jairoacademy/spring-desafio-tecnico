# Tasks

## 1. Domínio

- [x] 1.1 Criar pacotes `activation.{api,application,domain,infrastructure}` e `shared.web`, cada um com `package-info.java` `@NullMarked`; verificar com `./mvnw compile`
- [x] 1.2 Criar `ActivationStatus` com `canTransitionTo` e `InvalidStatusTransitionException`; teste unitário `ActivationStatusTest` (sem Spring) cobrindo as 4 transições permitidas e todas as proibidas via `@ParameterizedTest`
- [x] 1.3 Criar `ActivationRequest` (`@Entity`, construtor `protected`, fábrica `create`, `startProcessing`, `complete`, `fail`, `scheduleRetry`, `@Version Long`, instantes truncados em microssegundos); teste unitário `ActivationRequestTest` cobrindo estado inicial (`attempts` 0, `nextAttemptAt` = `createdAt`), caminhos de sucesso e falha, `scheduleRetry` e transição rejeitada sem alterar status nem `updatedAt`

## 2. Persistência

- [x] 2.1 Criar migração `V1__create_activation_request.sql` (tabela, `CHECK` de status, índices `(customer_id, created_at DESC)` e `(status, created_at DESC)`) e configurar `spring.jpa.hibernate.ddl-auto=validate`; verificar com o teste de contexto existente passando
- [x] 2.2 Criar `ActivationRequestRepository` (`JpaRepository` + `JpaSpecificationExecutor`) e as specifications de filtro por `status` e `customerId`; teste `@DataJpaTest` com Testcontainers cobrindo gravação/leitura e o lock otimista (segunda gravação de cópia desatualizada lança `ObjectOptimisticLockingFailureException`)

## 3. Origem do traceId

- [x] 3.1 Confirmar na documentação oficial do Boot 4.1 o artefato de Micrometer Tracing + bridge OpenTelemetry sem exportador (design, decisão 9), adicionar ao `pom.xml` e, se necessário, desligar exportação por propriedade; verificar com `./mvnw verify` sem erro de exportação no log dos testes

## 4. API de criação e consulta

- [x] 4.1 Criar bean `Clock` UTC e `ActivationRequestService` (`create`, `findById` lançando `ActivationRequestNotFoundException`); coberto pelos testes de integração da 4.3
- [x] 4.2 Criar `ApiExceptionHandler` (`ResponseEntityExceptionHandler`, `traceId` e `instance` em todo ProblemDetail, handlers da tabela do design, `detail` fixo para corpo ilegível) e `server.error.include-stacktrace=never`/`include-message=never`; coberto pelos testes da 4.3 e 4.4
- [x] 4.3 Criar DTOs `record` com Bean Validation e `ActivationRequestController` com `POST` (201 + `Location`) e `GET /{id}`; teste `ActivationRequestApiIntegrationTests` (`@SpringBootTest`, `@AutoConfigureMockMvc`, `@AutoConfigureTracing`, Testcontainers) cobrindo: criação válida, datas ISO-8601 em UTC iguais, GET do `Location` com mesmos dados, cada caso de validação da spec (ausente, tamanho, formato, `{}`), JSON malformado sem detalhe do parser, consulta existente, inexistente (404), id não UUID (400), `DELETE` (405), `Content-Type` `application/problem+json` e `traceId` `^[0-9a-f]{32}$` distinto por requisição
- [x] 4.4 Teste `UnexpectedErrorIntegrationTests` com `@MockitoBean` no serviço lançando `RuntimeException("segredo interno")`: verificar `500` em ProblemDetail sem a mensagem, sem nome de exceção e sem stack trace, e o mesmo `traceId` presente no log capturado (`OutputCaptureExtension`)

## 5. Listagem paginada

- [x] 5.1 Adicionar `findAll(status, customerId, page, size)` no serviço, `PageResponse` e `GET /api/v1/requests` com `@Min`/`@Max` e ordenação fixa `createdAt DESC, id DESC`; testes de integração cobrindo filtro por cliente, filtro combinado, paginação e ordenação (3 itens, `size=2`), `status=DONE` (400), `size=101` e `page=-1` (400) e criação inválida não gravada

## 6. Documentação e verificação final

- [x] 6.1 Atualizar README: uso da API com `curl` (criar, consultar, listar), tabela de regras de validação, formato de erro com `traceId`, decisões desta change (estados e `PROCESSING → PENDING`, entidade como modelo de domínio, origem do `traceId`, índices da listagem e evolução sem filtro); rodar `./mvnw verify` verde

## Workflow follow-up

- `/opsx:archive` e commit `docs(spec): arquiva add-activation-request-api`.
- Push da branch, PR para `main` com o "Pronto quando" da Etapa 2 marcado; merge com merge commit após CI verde; marcar a Etapa 2 em `docs/roteiro.md`.

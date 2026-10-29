# Design

## Context

Estado atual (ver proposal.md, Why):

- Spring Boot 4.1.2 gerado pelo Spring Initializr, Java 21, starters modulares (`webmvc`, `data-jpa`, `flyway`, `validation`) e seus `-test`.
- `application.properties` sem valores padrão para o banco (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`); o perfil `local` aponta para o PostgreSQL do `compose.yaml`.
- `compose.yaml` com apenas o serviço `postgres` (imagem `postgres:18`, health check com `pg_isready`).
- Teste de contexto `SpringdesafiotecnicoApplicationTests` com `TestcontainersConfiguration` (`@ServiceConnection`, `postgres:18`).
- Pasta `db/migration` vazia; nenhuma entidade ainda.
- O JSpecify (`org.jspecify:jspecify:1.0.1`) já está no classpath de compilação, de forma transitiva via `spring-core`.

## Goals / Non-Goals

**Goals:**
- Cada requisito da spec `operations` verificado de forma automática: as probes por teste de integração no `./mvnw verify`; imagem, Compose e mock pelo smoke test no CI.
- Imagem pequena e segura, sem ferramentas de build na camada final.

**Non-Goals:**
- Publicar a imagem em registry ou fazer deploy.
- Configurar a aplicação para chamar o WireMock (a propriedade da URL do provedor nasce na change `add-provider-integration`).
- Porta de management separada, métricas ou logs estruturados.

## Decisions

**1. Probes com `management.endpoint.health.probes.enabled=true`.**
Fora do Kubernetes o Boot não garante os grupos `liveness` e `readiness`; a propriedade explícita torna o comportamento igual no Compose, no CI e em produção. Os grupos ficam com os indicadores padrão (estado de liveness e de readiness), sem incluir o banco: banco fora não deve reiniciar a aplicação. Exposição HTTP mantida no padrão do Boot (`health` apenas), declarada explicitamente em `management.endpoints.web.exposure.include=health` para documentar a intenção.
Alternativa: incluir `db` na readiness. Rejeitada por ora: o pool do Hikari já falha rápido e a decisão pode ser revista na change de observabilidade.

**2. Teste das probes com `@SpringBootTest` + `@AutoConfigureMockMvc` + Testcontainers.**
Reaproveita a `TestcontainersConfiguration` existente. No Boot 4 o `MockMvc` não é configurado automaticamente com `@SpringBootTest`, por isso a anotação explícita. Starter de teste do Actuator conforme o padrão modular do Boot 4 (`spring-boot-starter-actuator-test`), confirmado na documentação antes de adicionar.

**3. `@NullMarked` só no pacote raiz agora.**
`@NullMarked` vale por pacote, não para subpacotes. Hoje só existe o pacote raiz. Cada change que criar pacote novo cria o `package-info.java` dele; a regra pode virar teste ArchUnit na change `add-observability-and-delivery`. Sem NullAway/Error Prone: custo de build alto para o tamanho do projeto.

**4. Dockerfile multi-stage com Temurin Alpine.**
- Estágio `build`: `eclipse-temurin:21-jdk-alpine`, copia `pom.xml` e `.mvn` primeiro e baixa dependências (`dependency:go-offline`) para cachear a camada; depois copia `src` e roda `./mvnw -B package -DskipTests`. Os testes já rodam no `./mvnw verify` do CI; repeti-los no build da imagem exigiria Docker dentro do Docker por causa do Testcontainers.
- Estágio `extract`: `java -Djarmode=tools -jar app.jar extract --layers --launcher`, forma documentada no Boot 4.1 para camadas eficientes.
- Estágio final: `eclipse-temurin:21-jre-alpine`, usuário `app` criado com `adduser -S` (não-root), copia as camadas na ordem de menor para maior frequência de mudança.
- `JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"` como padrão, sobrescrevível por variável de ambiente.
- `HEALTHCHECK` com o `wget` do BusyBox (já presente no Alpine) em `/actuator/health/readiness`, sem instalar pacote extra.
Alternativas: imagem distroless (sem shell nem `wget` para o health check) e Buildpacks via `spring-boot:build-image` (menos controle e menos didático para o item 11, que pede um `Dockerfile`).

**5. `compose.yaml` mantém o nome.**
É o nome padrão da Compose Specification; `docker-compose.yml` só é aceito por compatibilidade. O README explica a equivalência com a seção Entrega do enunciado.
Serviços:
- `postgres`: sem mudança.
- `app`: `build: .`, porta `8080`, `DB_URL=jdbc:postgresql://postgres:5432/ativacao` e credenciais de desenvolvimento, `depends_on` com `condition: service_healthy` no `postgres`. Health check herdado do `HEALTHCHECK` da imagem.
- `wiremock`: imagem oficial `wiremock/wiremock` (versão 3.x fixada), porta host `8089`, volume `./wiremock:/home/wiremock` com o stub `mappings/provider-activate-success.json` (`POST /provider/v1/activate` → `200`). Health check em `/__admin/health`.

**6. GitHub Actions com dois jobs.**
- `verify`: `actions/setup-java` (Temurin 21, cache Maven) e `./mvnw -B verify`. Docker já existe no runner `ubuntu-latest`, o que basta para o Testcontainers.
- `compose-smoke` (depende de `verify`): `docker compose up --build --wait`, depois `docker compose exec app id -u` (diferente de `0`) e `curl` em `POST localhost:8089/provider/v1/activate`; `docker compose logs` em caso de falha e `docker compose down -v` sempre.
Gatilhos: `push` e `pull_request`.

**7. ADR 0000 no formato curto** (contexto, decisão, consequências), com base na seção 1.4 de `docs/plano-sdd-openspec.md`: fim do suporte open source do Boot 3.5 em 30/06/2026, diferenças relevantes do Boot 4 e o que isso exige do código (starters modulares, Jackson 3, `@MockitoBean`, Testcontainers 2).

## Risks / Trade-offs

- [Imagens `wiremock/wiremock` podem não ter `curl`/`wget` para o health check] → verificar na implementação; se faltar, usar a variante de health check suportada pela imagem ou a imagem com shell que tenha a ferramenta. O `docker compose up --wait` no CI pega o problema.
- [`-DskipTests` no build da imagem permite imagem com testes quebrados] → o job `compose-smoke` só roda depois de `verify` verde.
- [Alpine usa musl; alguma lib nativa futura pode não funcionar] → nenhuma dependência nativa hoje; trocar para a variante Ubuntu do Temurin é uma linha.
- [Credenciais de desenvolvimento no `compose.yaml`] → valem só para ambiente local; em produção vêm de variáveis injetadas por gerenciador de segredos (já descrito no README).

## Migration Plan

Não há dados nem usuários. Rollback: reverter o merge do PR.

# Serviço de ativação de produtos

[![CI](https://github.com/jairoacademy/spring-desafio-tecnico/actions/workflows/ci.yml/badge.svg)](https://github.com/jairoacademy/spring-desafio-tecnico/actions/workflows/ci.yml)

API REST que recebe solicitações de ativação de produtos, evita duplicidade e as envia de forma assíncrona a um provedor externo, com retry. Desafio técnico Java (Spring Boot 4.1.2, Java 21).

## Construído com IA, orientado por especificação

Esta aplicação foi construída com o agente **Claude Code** seguindo **Spec-Driven Development** com o **OpenSpec**: **nenhum código foi escrito antes de existir uma especificação revisada e aprovada por mim**. O agente acelerou a escrita; as decisões, a revisão e o critério de aceite ficaram comigo.

- **Specs como fonte da verdade:** requisitos e cenários em [`openspec/specs/`](openspec/specs/).
- **Uma change por etapa:** proposta, design e tarefas versionados, com branch e pull request próprios. Seis changes arquivadas em [`openspec/changes/archive/`](openspec/changes/archive/).
- **Eu decido e reviso, o agente implementa:** decisões de arquitetura, aprovação das specs e code review de todo o diff são meus.
- **Etapas e regras do agente:** [roteiro](docs/roteiro.md) e [CLAUDE.md](CLAUDE.md).

Fluxo completo, exemplo de spec e divisão de responsabilidades: [docs/detalhes/uso-de-ia.md](docs/detalhes/uso-de-ia.md).

## Executar

Pré-requisito: Docker.

```bash
docker compose up --build
```

Sobe a aplicação (`8080`), PostgreSQL, o mock do provedor (WireMock, `8089`) e um emissor de tokens de desenvolvimento (`8091`), todos com health check.

```bash
curl localhost:8080/actuator/health/readiness

TOKEN=$(curl -s -X POST localhost:8091/default/token \
  -d grant_type=client_credentials -d client_id=sistema-a -d client_secret=dev \
  -d 'scope=activation:read activation:write' | jq -r .access_token)

curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/requests
```

Para parar e apagar os dados: `docker compose down -v`. A aplicação local (sem Docker para o app) e os detalhes de configuração estão em [Configuração e operação](docs/detalhes/configuracao-e-operacao.md).

## Testar

```bash
./mvnw verify
```

Testes unitários e de integração com PostgreSQL via Testcontainers e provedor simulado com WireMock; o Docker precisa estar em execução. O CI roda o mesmo comando e um smoke test do Compose. A coleção [Bruno](bruno/) exercita a API de ponta a ponta.

## Documentação

| Assunto | Onde |
|---|---|
| Endpoints, autenticação, exemplos e formato de erro | [docs/detalhes/api.md](docs/detalhes/api.md) |
| Decisões técnicas: estados, provedor, segurança, duplicidade, índices | [docs/detalhes/decisoes-tecnicas.md](docs/detalhes/decisoes-tecnicas.md) |
| ADRs (uma decisão por arquivo) | [docs/adr/](docs/adr/) |
| Specs do comportamento | [openspec/specs/](openspec/specs/) |
| Configuração, health checks, logs, métricas, encerramento | [docs/detalhes/configuracao-e-operacao.md](docs/detalhes/configuracao-e-operacao.md) |
| Produção: imagem, variáveis, observabilidade, memória da JVM | [docs/detalhes/producao.md](docs/detalhes/producao.md) |
| Uso de IA: fluxo SDD, exemplo de spec, divisão de responsabilidades | [docs/detalhes/uso-de-ia.md](docs/detalhes/uso-de-ia.md) |
| Cenário de análise (lentidão em produção) | [docs/detalhes/cenario-de-analise.md](docs/detalhes/cenario-de-analise.md) |
| Enunciado e plano de execução | [desafio](docs/desafio-tecnico-java.md), [plano](docs/plano-sdd-openspec.md), [roteiro](docs/roteiro.md) |

## Decisões em uma olhada

- **Duplicidade:** `externalId` único no banco (constraint). Retry idêntico devolve `200`; conteúdo diferente, `409`; criação concorrente resulta em um único registro.
- **Assíncrono:** `POST` grava `PENDING` e responde `201`; um worker envia ao provedor (a tabela é o outbox). Retry com backoff para 5xx, timeout e 429.
- **Segurança:** JWT (OAuth 2.0) com escopos `activation:read` e `activation:write`; cada cliente só vê as próprias solicitações.
- **Observabilidade:** logs JSON com `traceId`, métricas Prometheus e traces OpenTelemetry.

## Cenário de análise

Lentidão em produção (API de 300 ms para 2,5 s, consultas de 50 ms para 1,2 s, provedor de 400 ms para 3 s) com CPU e memória normais.

1. CPU e memória normais com tudo lento indicam que o sistema está **esperando** alguma coisa, não trabalhando.
2. Os suspeitos são a aplicação, o banco e o provedor externo. Medir o tempo gasto em cada um mostra quem está lento.
3. Hipótese de causa comum: o provedor lento segura conexões do banco, o pool de conexões esgota e as consultas parecem lentas só porque esperam na fila.
4. Depois da correção, comparo o tempo de resposta e a taxa de erro antes e depois, com carga parecida.

Resposta completa, com os 6 itens do enunciado: [docs/detalhes/cenario-de-analise.md](docs/detalhes/cenario-de-analise.md).

## Pontos pendentes e melhorias futuras

- O provedor é um mock; contrato, autenticação e limites reais não foram validados.
- O emissor de tokens do Compose é só para desenvolvimento. `/actuator/prometheus` não exige token: restrinja pela rede em produção.
- Sem teste de carga; pool e threads usam valores padrão.
- Melhorias: gauge de `PENDING`, paginação por cursor, porta de gerenciamento separada para o Actuator, circuit breaker e dead-letter, alertas e dashboards.

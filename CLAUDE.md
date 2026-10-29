# Regras do projeto
- Leia openspec/ e docs/desafio-tecnico-java.md antes de implementar.
- Só implemente o que estiver em tasks.md da change ativa. Não antecipe changes futuras.
- Cada change OpenSpec vive na sua branch `<tipo>/<nome-da-change>` e entra na main por PR com merge commit. Nunca commite direto na main durante uma change; se estiver na main, pare e avise.
- Ao concluir cada task: marque [x], rode ./mvnw verify e sugira a mensagem de commit (Conventional Commits, em português).
- O README.md evolui junto com o código: toda task que muda como executar, configurar, testar ou usar a API atualiza o README no mesmo commit. Ao fechar uma etapa, marque [x] em docs/roteiro.md.
- Nunca exponha stack trace na API. Nunca logue tokens/credenciais.
- Não adicione dependência sem justificar no design.md.
- O projeto usa Spring Boot 4.1.2. Não use APIs, starters ou anotações do Boot 3 (ver docs/plano-sdd-openspec.md, seção 1.4).
  Na dúvida sobre nome de starter ou pacote, consulte a documentação oficial do Boot 4 em vez de supor.

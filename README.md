# Wallet API

API REST de transferências entre carteiras, desenvolvida como projeto de estudo e portfólio a partir do [desafio backend da PicPay](https://github.com/PicPay/picpay-desafio-backend). Não é um produto oficial da empresa.

O foco é uma implementação pequena, fácil de executar e explicar: regras de negócio explícitas, transações no PostgreSQL e testes dos cenários em que uma transferência pode falhar.

**Stack:** Java 21, Spring Boot 4.1, Spring Web MVC, Spring Security (JWT), Spring AI 2.0 com Claude (Anthropic), Resilience4j, Micrometer, Spring Data JPA, PostgreSQL 17, Flyway, Maven, JUnit 6, Mockito, Testcontainers e Docker Compose. A CI executa os testes no GitHub Actions.

## Executar a demonstração

Pré-requisito: Docker com Docker Compose. Não é necessário instalar Java ou PostgreSQL no host.

```bash
docker compose -f compose.yml -f compose.mock.yml up --build -d
```

Esse comando inicia a API, o PostgreSQL e um simulador HTTP local para autorização e notificação. Na primeira execução, o build baixa as dependências. A API fica em `http://localhost:8081`.

```bash
curl http://localhost:8081/actuator/health
```

Após a inicialização, a resposta deve trazer `"status":"UP"`. Para acompanhar a inicialização:

```bash
docker compose logs -f api
```

Para encerrar, preservando os dados:

```bash
docker compose -f compose.yml -f compose.mock.yml down
```

O volume mantém os cadastros entre execuções. Os próximos exemplos assumem um banco vazio; use os IDs devolvidos pela API quando já houver dados.

### 1. Cadastrar um usuário comum e um lojista

```bash
curl -i -X POST http://localhost:8081/users \
  -H 'Content-Type: application/json' \
  -d '{"fullName":"Ana Silva","document":"11111111111","email":"ana@example.com","password":"senha-de-exemplo","type":"COMMON"}'

curl -i -X POST http://localhost:8081/users \
  -H 'Content-Type: application/json' \
  -d '{"fullName":"Loja Exemplo","document":"22222222222222","email":"loja@example.com","password":"senha-de-exemplo","type":"MERCHANT"}'
```

Cada cadastro retorna `201 Created`, um cabeçalho `Location` e saldo inicial zero. Senha e documento não aparecem na resposta.

### 2. Entrar e consultar a própria conta

```bash
curl -X POST http://localhost:8081/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ana@example.com","password":"senha-de-exemplo"}'
```

A resposta traz um token de acesso válido por 15 minutos:

```json
{ "accessToken": "eyJhbGciOiJIUzI1NiJ9...", "tokenType": "Bearer", "expiresIn": 900 }
```

```bash
TOKEN='cole-o-accessToken-aqui'
curl http://localhost:8081/users/me -H "Authorization: Bearer $TOKEN"
```

Todas as rotas de carteira e transferência exigem o cabeçalho `Authorization: Bearer`. Cada usuário só consulta e movimenta a própria carteira.

### 3. Adicionar saldo de demonstração

```bash
curl -X POST http://localhost:8081/users/1/deposits \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"value":100.00}'
```

Esse endpoint existe apenas com o perfil `demo`, ativado pelo Compose, e só aceita depósito na carteira do próprio token. Ele simula a entrada de dinheiro; não representa integração com um banco ou processador de pagamentos.

### 4. Transferir

```bash
curl -i -X POST http://localhost:8081/transfer \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-transfer-001' \
  -d '{"value":25.00,"payee":2}'
```

O pagador é sempre o usuário do token. O corpo não aceita o campo `payer` do contrato original do desafio: se a API confiasse nele, qualquer pessoa autenticada poderia gastar o saldo de outra informando o ID dela.

Resposta `201 Created`:

```json
{
  "id": "a3f793b8-7e60-40c4-8a12-bb7e0c68f870",
  "value": 25.00,
  "payer": 1,
  "payee": 2,
  "createdAt": "2026-09-09T18:00:00Z",
  "notificationStatus": "PENDING"
}
```

O ID e a data são gerados pela aplicação. A transferência já está confirmada quando a API responde; `PENDING` descreve apenas a notificação. Consulte o endereço do cabeçalho `Location` para acompanhar a mudança para `SENT` ou `FAILED`. Reenvie a mesma operação com a mesma chave em caso de timeout; use uma chave nova para cada transferência diferente.

```bash
curl http://localhost:8081/users/me -H "Authorization: Bearer $TOKEN"
```

O saldo da Ana deve ser `75.00`. Para ver o da loja (`25.00`), entre com `loja@example.com` e consulte `/users/me` com o token dela. Há mais exemplos em [docs/requests.http](docs/requests.http), compatível com IntelliJ HTTP Client e a extensão REST Client do VS Code.

## Endpoints

| Método | Caminho | Comportamento |
| --- | --- | --- |
| POST | `/users` | Cadastra usuário comum (`COMMON`) ou lojista (`MERCHANT`) |
| POST | `/auth/login` | Troca e-mail e senha por um token JWT com expiração |
| GET | `/users/me` | Consulta o usuário do token e seu saldo |
| GET | `/users/me/transfers?limit=20` | Extrato: transferências enviadas e recebidas, das mais recentes para as mais antigas (limite de 1 a 50) |
| GET | `/users/{id}` | Consulta usuário e saldo; só o próprio ID |
| POST | `/users/{id}/deposits` | Adiciona saldo fictício na própria carteira; somente no perfil `demo` |
| POST | `/transfer` | Transfere do usuário do token para `payee`, com `description` opcional; exige `Idempotency-Key` |
| GET | `/transfer/{id}` | Consulta comprovante e situação da notificação; só pagador ou recebedor |

Exceto cadastro, login e health check, todas as rotas exigem `Authorization: Bearer <token>`. Consultas, o assistente e a categorização exigem o escopo `wallet:read`; transferências e depósitos exigem `transfer:write`. O login emite tokens com os dois.
| POST | `/assistant/chat` | Assistente de IA: responde sobre saldo, extrato e transferências, e prepara propostas de transferência |
| POST | `/transfer/{id}/category` | Categoriza uma transferência com IA (só participantes); a categoria é gravada uma vez |
| GET | `/actuator/health` | Verifica a saúde da aplicação e do banco |
| GET | `/actuator/metrics/{nome}` | Métricas, incluindo as do assistente; exige token |

Valores monetários devem ser positivos, com até 17 dígitos inteiros e duas casas decimais. A `description` da transferência é opcional, com até 140 caracteres, sem quebras de linha nem outros caracteres de controle. O documento deve ter 11 ou 14 dígitos, sem pontuação. O e-mail é normalizado para minúsculas. Senhas têm de 8 a 64 caracteres e no máximo 72 bytes em UTF-8, limite do BCrypt utilizado.

### Erros

As respostas de erro seguem `application/problem+json`, com `status`, `title` e `detail`. Erros de validação também incluem `errors` com os campos inválidos.

| Status | Situação |
| --- | --- |
| 400 | JSON inválido, campos ausentes, valores fora do formato ou `Idempotency-Key` ausente/inválida |
| 401 | E-mail ou senha inválidos no login; token ausente, expirado, adulterado ou de outro emissor |
| 403 | Token sem o escopo necessário, ou autorizador externo recusou a transferência |
| 404 | Usuário ou transferência não encontrado, ou pertencente a outra pessoa |
| 409 | CPF/CNPJ ou e-mail já cadastrado, conflito com restrição do banco ou reutilização de chave com outro payload |
| 422 | Saldo insuficiente, lojista como pagador, mesma origem/destino ou limite de saldo |
| 503 | Autorizador indisponível, tempo de espera pelo bloqueio da carteira excedido, ou assistente indisponível ou sem chave configurada |

## Como funciona

```mermaid
flowchart LR
    HTTP[Controller] --> Service[Service]
    Service --> Repository[Repository]
    Repository --> DB[(PostgreSQL)]
    Service --> Auth[Autorizador HTTP]
    Worker[Agendador de notificações] --> DB
    Worker --> Notify[Notificador HTTP]
```

Os pacotes são organizados por funcionalidade: `auth`, `user`, `transfer`, `integration`, `notification`, `shared` e `demo`. Controllers cuidam do contrato HTTP; services coordenam regras e transações; repositories acessam o banco.

- **Dinheiro:** `BigDecimal` em Java e `NUMERIC(19,2)` no banco. Não há arredondamento silencioso de entradas com mais de duas casas decimais.
- **Carteira:** um objeto `@Embeddable` dentro de `User`, armazenado na tabela `users`. Como cada usuário tem exatamente uma carteira, não há necessidade de uma tabela separada nesta versão.
- **Atomicidade:** débito, crédito, comprovante e chave de idempotência são gravados na mesma transação. Recusa ou falha do autorizador desfaz tudo. Restrições SQL também impedem saldos negativos, documentos/e-mails duplicados e uso repetido da chave pelo mesmo pagador.
- **Idempotência:** `POST /transfer` exige `Idempotency-Key` com até 128 caracteres, persistida por pagador junto com uma impressão digital do payload. Repetir a chave com o mesmo conteúdo devolve o recibo original; usar a mesma chave com outro conteúdo retorna `409`.
- **Concorrência:** as duas carteiras são bloqueadas com `PESSIMISTIC_WRITE`, sempre pelo menor ID primeiro. Isso impede gasto duplo e evita deadlocks entre transferências em sentidos opostos.
- **Serviços externos:** `RestClient` com timeout de conexão de 2 segundos e leitura de 3 segundos. Autorização exige resposta explícita de sucesso; respostas inesperadas resultam em `503`.
- **Notificação:** o comprovante nasce com notificação pendente na mesma transação do pagamento. Um agendador consulta até dez pendências a cada ciclo, com intervalo de dez segundos após o ciclo anterior. Falhas são reagendadas para depois de 60 segundos, até cinco tentativas por padrão, e sobrevivem a reinícios. O estado `FAILED` encerra novas tentativas automáticas; `NOTIFICATION_MAX_ATTEMPTS` altera o limite. `FOR UPDATE SKIP LOCKED` impede dois workers de processarem simultaneamente o mesmo registro.
- **Autenticação:** Spring Security como resource server. `POST /auth/login` emite um JWT HS256 com `sub` (ID do usuário), `iss`, `iat`, `exp` (15 minutos) e `scope`. O segredo vem de `JWT_SECRET`, tem no mínimo 32 bytes e a aplicação não sobe sem ele. Tokens sem `exp`, sem assinatura (`alg: none`), assinados com outra chave ou de outro emissor são recusados. A API não usa sessão nem cookies, por isso o CSRF fica desligado.
- **Posse da carteira:** a identidade vem só do `sub` do token. O pagador da transferência não vem do corpo, e consultas ou depósitos em IDs de outra pessoa devolvem `404`, a mesma resposta de um ID inexistente, para não revelar quais usuários ou comprovantes existem. Comprovantes são visíveis ao pagador e ao recebedor. A chave de idempotência continua separada por pagador, então reutilizar a chave de outra pessoa cria uma transferência nova em vez de devolver o recibo dela.
- **Escopos:** o token leva `scope`, e as rotas exigem `wallet:read` ou `transfer:write`. Um token só de leitura (por exemplo, para um agente de IA) consulta saldos, mas recebe `403` ao tentar mover dinheiro.
- **Login sem enumeração:** e-mail inexistente e senha errada devolvem a mesma resposta `401`. Quando o e-mail não existe, o BCrypt roda contra um hash fictício para que o tempo de resposta também não revele o cadastro.
- **Segurança básica:** senha armazenada como hash BCrypt, DTOs sem exposição de entidades e contêiner da aplicação executado sem root.

## Assistente de IA

Com `ANTHROPIC_API_KEY` definida, `POST /assistant/chat` responde em português usando o Claude (`claude-opus-5-5` por padrão) por meio do Spring AI 2.0, que usa o SDK oficial da Anthropic. Sem a chave, a aplicação sobe normalmente e só o assistente responde `503`.

```bash
curl -X POST http://localhost:8081/assistant/chat \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"message":"Manda R$ 25 para loja@example.com"}'
```

```json
{
  "reply": "Preparei uma transferência de R$ 25,00 para Loja Exemplo. Confirme para enviar.",
  "proposals": [{
    "idempotencyKey": "assistant-5f0c…",
    "payeeId": 2, "payeeName": "Loja Exemplo", "value": 25.00, "warnings": [],
    "confirmation": {
      "method": "POST", "path": "/transfer", "idempotencyKey": "assistant-5f0c…",
      "body": { "value": 25.00, "payee": 2, "description": null }
    }
  }]
}
```

**Ferramentas.** O modelo tem quatro ferramentas: `getBalance`, `getStatement`, `getTransfer` (para explicar uma transação) e `proposeTransfer`. Elas são criadas a cada pedido e presas ao usuário do token. Nenhum parâmetro escolhe de quem é a carteira, então o modelo não alcança dados de outra pessoa, mesmo que peça um ID alheio.

**O LLM só propõe.** Nenhuma ferramenta move dinheiro. `proposeTransfer` valida destinatário e valor e devolve uma proposta; quem executa é o próprio cliente, com `confirmation.body` em `POST /transfer`, com o token do usuário e a chave `idempotencyKey`. Confirmar duas vezes devolve o mesmo recibo. A rota do assistente exige só `wallet:read`, e um token só de leitura recebe `403` ao tentar confirmar.

**Prompt injection.** A `description` de uma transferência é texto de outra pessoa. Ela chega ao modelo como o campo `untrustedNote` de um JSON, e o prompt de sistema manda tratá-la como dado. Se o assistente propõe uma transferência depois de ler a descrição de uma transferência recebida, a proposta vem com um aviso em `warnings`. Os testes rodam um modelo "comprometido", que obedece à injeção: ele chega a criar a proposta, mas nenhum dinheiro sai da carteira sem a confirmação.

**Categorização.** `POST /transfer/{id}/category` classifica a transferência em uma de onze categorias (`FOOD`, `GROCERIES`, `TRANSPORT`, `HOUSING`, `BILLS`, `HEALTH`, `EDUCATION`, `SHOPPING`, `LEISURE`, `PERSONAL_TRANSFER`, `OTHER`). A requisição usa saída estruturada da API (`output_config.format` com esquema JSON e enum), então o modelo só consegue responder uma categoria válida. Transferências entre pessoas sem descrição são categorizadas por regra, sem chamar o modelo. O campo `source` indica a origem: `MODEL`, `RULE`, `STORED` ou `FALLBACK`.

**Resiliência.** Toda chamada ao LLM passa pelo `LlmGateway`: timeout HTTP de 30 segundos por tentativa (uma nova tentativa), circuit breaker do Resilience4j (abre com 50% de falhas em 10 chamadas e recusa sem chamar o provedor por 30 segundos) e `503` em qualquer falha, sem afetar as rotas de carteira. Uma recusa do modelo vira uma resposta educada.

**Métricas.** O Micrometer publica `wallet.assistant.requests` (latência, por `operation` e `outcome`: `success`, `error`, `circuit_open`, `not_configured`), `wallet.assistant.tokens` (por `type`: `input` e `output`, somando todo o laço de ferramentas) e `wallet.assistant.cost` (custo estimado em dólares, com os preços em `wallet.assistant.pricing`). O Spring AI também publica as métricas `gen_ai.client.*`.

```bash
curl http://localhost:8081/actuator/metrics/wallet.assistant.cost -H "Authorization: Bearer $TOKEN"
```

### Avaliação com o modelo real

Os testes normais nunca chamam a API da Anthropic: o `AssistantIT` roda o Spring AI de verdade contra um servidor local que imita a API e confere o que foi enviado, como modelo, esforço, ausência de `temperature`, ferramentas e esquema de saída. A qualidade das respostas do modelo é medida à parte:

```bash
ANTHROPIC_API_KEY=... ./mvnw -Peval verify
```

- `CategorizationEval`: 40 transferências rotuladas em `src/test/resources/eval/categorization.jsonl`, com gírias, erros de digitação e três tentativas de injeção. Exige 90% de acurácia e acerto em todos os casos de injeção.
- `AssistantInjectionEval`: descrições com instruções escondidas (transferir, falsa autorização do sistema, saldo falso) não podem gerar propostas, e um pedido legítimo ainda precisa gerar a proposta certa.

Os relatórios, com tokens e custo estimado, ficam em `target/eval/`. Sem a chave, as avaliações são puladas. No GitHub Actions, o workflow **LLM eval** roda manualmente e toda segunda-feira, nunca a cada push; ele precisa do secret `ANTHROPIC_API_KEY`.

## Testes

Pré-requisitos para executar fora de contêiner: JDK 21 e Docker acessível pelo usuário. O Maven Wrapper baixa o Maven na primeira execução.

```bash
# Testes unitários: não precisam de Docker
./mvnw test

# Suíte completa, incluindo PostgreSQL criado pelo Testcontainers
./mvnw verify
```

No Windows, use `mvnw.cmd`. Os testes de integração são executados pelo Maven Failsafe (`*IT`) e falham se o Docker não estiver disponível; não são pulados silenciosamente.

Os testes cobrem regras de saldo, precisão decimal, validação HTTP, unicidade e hashing, login e validação de tokens (expirado, adulterado, sem assinatura, sem expiração e de outro emissor), isolamento entre carteiras (tentativa de gastar saldo alheio, leitura de usuário e comprovante de terceiros, depósito em outra carteira, reutilização da chave de idempotência de outra pessoa) e escopos, transferência entre usuários e para lojistas, rollback, gasto duplo, transferências em sentidos opostos, replay idempotente, chaves conflitantes, concorrência com a mesma chave, limite de retentativas, disponibilidade do depósito demo por perfil, descrição e extrato, e o assistente de IA (ferramentas presas ao usuário, propostas sem movimentação, confirmação idempotente, prompt injection pela descrição, saída estruturada da categorização, timeout, circuit breaker e métricas de tokens e custo). Os clientes HTTP são exercitados contra um servidor local, incluindo timeout, recusa, resposta inválida e indisponibilidade. Os testes da aplicação usam PostgreSQL real e Mockito para controlar os serviços externos, sem depender da disponibilidade dos mocks públicos.

## Desenvolvimento local e serviços oficiais

```bash
docker compose up -d db
export JWT_SECRET="$(openssl rand -base64 48)"
SERVER_ADDRESS=127.0.0.1 ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo -Dspring-boot.run.arguments=--server.port=8081
```

O comando limita o servidor ao computador local. Sem o perfil `demo`, o endpoint de depósito não é registrado. O restante da API continua disponível.

Para usar os serviços oficiais com a aplicação em Docker, execute somente o Compose principal:

```bash
docker compose up --build -d
```

As URLs padrão são `https://util.devi.tools/api/v2/authorize` (`GET`) e `https://util.devi.tools/api/v1/notify` (`POST`). Em 09/09/2026, a consulta ao autorizador nesta implementação encontrou certificado TLS expirado; por isso a demonstração reproduzível usa simuladores locais. A aplicação mantém a validação TLS habilitada e trata essa falha como indisponibilidade.

| Variável | Padrão |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5433/wallet` |
| `DB_PORT` | `5433` — porta do PostgreSQL publicada pelo Compose |
| `API_PORT` | `8081` — porta da API publicada pelo Compose |
| `DB_USERNAME` | `wallet` |
| `DB_PASSWORD` | `wallet` — credencial apenas para desenvolvimento |
| `AUTHORIZATION_URL` | Autorizador oficial |
| `NOTIFICATION_URL` | Notificador oficial |
| `NOTIFICATION_MAX_ATTEMPTS` | `5` — máximo de tentativas automáticas de notificação |
| `JWT_SECRET` | Sem padrão; obrigatório, mínimo de 32 bytes. O Compose usa um segredo de demonstração |
| `JWT_TTL` | `15m` — validade do token de acesso |
| `ANTHROPIC_API_KEY` | Sem padrão; opcional. Sem ela, o assistente responde `503` |
| `ASSISTANT_MODEL` | `claude-opus-5-5` |
| `ASSISTANT_TIMEOUT` | `30s` — timeout HTTP de cada tentativa ao LLM |
| `SPRING_PROFILES_ACTIVE` | Sem perfil; Compose usa `demo` |

O arquivo `.env.example` serve de referência para o Compose. Para executar pela IDE/Maven, configure variáveis no ambiente; o Spring não carrega `.env` automaticamente.

O banco usa a porta `5433` no host para coexistir com instalações locais na porta `5432`. Dentro da rede Docker, a porta continua sendo `5432`. Se alterar `DB_PORT`, ajuste também `DB_URL` ao executar a aplicação fora do Docker.

## Limites e próximos passos

Este projeto prioriza o fluxo pedido no desafio. A autenticação cobre o essencial para uma API de estudo, mas não há refresh token, revogação de tokens, limite de tentativas de login nem verificação de e-mail. A API não deve ser publicada como serviço financeiro. Frontend está fora do escopo. A validação de documento confere formato e unicidade, sem cálculo de dígitos verificadores.

A autorização externa ocorre enquanto as carteiras estão bloqueadas. Os timeouts limitam a espera, mas esse desenho reduz o throughput de carteiras muito movimentadas. É uma escolha simples para este porte.

As notificações têm entrega **pelo menos uma vez**: se o provedor receber a mensagem e a aplicação cair antes de registrar o sucesso, pode haver reenvio. O payload inclui `transferId` para permitir deduplicação pelo provedor; não há garantia de que o mock público a implemente. Após cinco falhas por padrão, o recibo fica `FAILED` e o agendador para de tentar.

O assistente atende um pedido por vez, sem memória de conversa. As propostas não ficam gravadas: o cliente guarda e confirma. As avaliações medem o modelo, mas ele é probabilístico; a proteção que não depende dele é a confirmação explícita. O parâmetro `fallbacks` da Anthropic, que troca de modelo quando há recusa, não é exposto pelo Spring AI 2.0.2, então uma recusa vira a resposta padrão do assistente.

`Idempotency-Key` protege contra repetição acidental; quem autentica o pagador é o token. Kafka, Redis e microsserviços não são necessários para demonstrar os fundamentos deste desafio.

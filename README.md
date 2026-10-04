# WODZ

Sistema para campeonatos de CrossFit. O **organizador** cadastra o campeonato,
as categorias e as provas, abre o formulário de inscrição, acompanha as equipes,
monta e controla as baterias e lança os resultados. O **atleta** busca
campeonatos, inscreve a equipe, paga online (Pix ou cartão de crédito/débito), vê o horário
e o status das suas baterias e acompanha o leaderboard ao vivo.

| Camada | Tecnologia |
|---|---|
| Backend | Java 21, Spring Boot 3.5 (Web, Data JPA, Security/JWT, Validation), Flyway, springdoc (Swagger) |
| Banco | PostgreSQL 16, com tabelas, functions, procedures, triggers e views versionadas no Flyway |
| Frontend | React 19, TypeScript, Vite, React Router 7, TanStack Query |
| Tempo real | Server-Sent Events (baterias e leaderboard atualizam sozinhos) |
| Pagamentos | Mercado Pago (Checkout Transparente: Pix e cartão de crédito/débito tokenizado, com 3DS) ou sandbox simulado |

## Como rodar

### Com Docker (recomendado)

```bash
cp .env.example .env        # opcional
docker compose up --build
```

- Site: http://localhost:8081
- API e Swagger: http://localhost:8080/swagger-ui

Com `SEED_DEMO=true` (padrão no compose) o sistema sobe com dois campeonatos do
organizador de demonstração, cada um com 3 categorias, 3 provas por categoria e
16 duplas por categoria (ninguém aparece em duas equipes):

- **Vitória Games - Ano 6**: encerrado e 100% concluído, com todas as baterias
  concluídas e todos os resultados lançados, cada um com o judge da raia.
- **Arena 911 - 2026**: acontecendo hoje. Prova 1 concluída com resultados,
  a 1ª bateria da Prova 2 concluída aguardando resultados (para lançar pela
  interface) e a 2ª em andamento. A atleta de demonstração é capitã do
  "Time da Ana", na Scaled.

| Perfil | E-mail | Senha |
|---|---|---|
| Organizador | organizador@wodz.dev | wodz2026 |
| Atleta | atleta@wodz.dev | wodz2026 |

### Desenvolvimento local

```bash
# 1. Banco
docker compose up db

# 2. API (porta 8080)
cd backend
SEED_DEMO=true mvn spring-boot:run

# 3. Frontend (porta 5173; o Vite repassa /api para a 8080)
cd frontend
npm install
npm run dev
```

Testes do backend: `mvn test`. Checagem de tipos do frontend: `npm run typecheck`.

## Login com Google

Login e cadastro têm o botão oficial "Entrar com Google" (Google Identity
Services). O navegador recebe um ID token, e a API (`POST /api/auth/google`)
confere assinatura, emissor, destinatário (o Client ID), validade e e-mail
verificado com as chaves públicas do Google.

- **Conta já vinculada:** entra direto.
- **E-mail já cadastrado com senha:** a conta é vinculada ao Google.
- **Primeiro acesso:** a tela pede o perfil (atleta ou organizador) antes de criar a conta. No cadastro, vale o perfil já marcado na página.
- **Conta criada pelo Google:** não tem senha. Em "Minha conta" a pessoa pode criar uma; a exclusão da conta é confirmada com um novo login no Google.

Configuração (`GOOGLE_CLIENT_ID`, já com o Client ID do projeto como padrão):

```env
GOOGLE_CLIENT_ID=84072245662-m7cc1brb2fb7lkv92i9dqm5o1phnagr9.apps.googleusercontent.com
```

No Google Cloud, em "Origens JavaScript autorizadas" do Client ID, cadastre
`http://localhost:5173`, `http://localhost:8081` e, depois, o domínio de
produção (com `https`). O frontend recebe o Client ID por `GET /api/public/config`.
Deixe a variável vazia para desligar o login com Google.

## Pagamentos

As formas de pagamento são **Pix** e **cartão**. No cartão, a pessoa escolhe
**crédito** (à vista ou parcelado) ou **débito** (sempre à vista) antes de o
formulário do cartão aparecer. Boleto não é mais aceito: cobranças antigas
continuam no histórico, mas a API e o banco recusam boletos novos.

Por padrão o provedor é o **sandbox**: cartão é aprovado na hora (cartões com
final 0002 são recusados) e Pix fica pendente até você clicar em
"Simular pagamento aprovado".

Para cobrar de verdade com o **Mercado Pago**:

```env
PAYMENT_PROVIDER=mercadopago
MP_ACCESS_TOKEN=APP_USR-...          # credencial de produção ou de teste
MP_PUBLIC_KEY=APP_USR-...            # usada pelo Card Brick no navegador
MP_WEBHOOK_SECRET=...                # "Assinatura secreta" do painel de webhooks
MP_NOTIFICATION_URL=https://seu-dominio.com/api/webhooks/mercadopago
MP_THREE_D_SECURE=true               # 3DS 2.0 nos cartões (padrão: ligado)
```

- O cartão é digitado no **Card Payment Brick** do Mercado Pago (iframe) e
  tokenizado no navegador; o número do cartão nunca passa pela API.
- O Brick só aceita cartões do tipo escolhido (crédito ou débito) e a API
  confere de novo no catálogo de meios de pagamento do Mercado Pago antes de
  cobrar. Débito é sempre em 1 parcela (validado na API e por constraint no banco).
- Com `MP_THREE_D_SECURE=true` o pagamento vai com `three_d_secure_mode=optional`:
  se o emissor pedir autenticação, a página abre o desafio do banco num iframe
  isolado e acompanha o resultado pelo polling.
- O webhook tem a assinatura `x-signature` validada (HMAC-SHA256) e o status é
  sempre **reconsultado na API do Mercado Pago**, nunca lido do corpo da notificação.
- Enquanto a página de pagamento está aberta, ela também consulta o status a
  cada 5 s. Isso funciona mesmo em ambiente local, sem URL pública para o webhook.
- Pagamento aprovado confirma a equipe **por trigger no banco**; Pix (e boletos
  antigos) vencidos expiram por um job que chama uma procedure.

## Banco de dados

As migrations ficam em `backend/src/main/resources/db/migration`. As regras que
precisam valer mesmo com acessos simultâneos (vagas, duplicidade de atleta,
ranking) estão no próprio PostgreSQL.

**Tabelas**: `app_user`, `championship`, `category`, `workout`, `team`,
`team_member`, `payment`, `heat`, `heat_lane`, `score`, `score_audit`.

**Functions**

| Nome | O que faz |
|---|---|
| `fn_rank_workout` | Colocação e pontos de uma prova. For Time: menor tempo e, abaixo de quem terminou, CAP por repetições. AMRAP e máx. reps: mais repetições. PR: maior carga. Desempate por tiebreak; DNS/DQ empatados na lanterna. |
| `fn_category_leaderboard` | Leaderboard geral por categoria: pontos por colocação ou soma de colocações, com desempate por número de vitórias. |
| `fn_search_championships` | Busca sem acento e tolerante a erro de digitação (`unaccent` + `pg_trgm`). |
| `fn_format_result` / `fn_format_duration` | Exibição do resultado: `05:42.3`, `5 rds + 12`, `120 kg`, `CAP + 87 reps`. |
| `fn_registration_is_open`, `fn_category_slots_left` | Janela de inscrição (sempre fecha à 0h do dia de início, horário de Brasília) e vagas restantes. |
| `fn_cpf_is_valid` | Dígitos verificadores do CPF. |

**Procedures**

| Nome | O que faz |
|---|---|
| `sp_start_heat` | Marca a bateria como em andamento e conclui a que estava rodando. |
| `sp_generate_heats` | Distribui as equipes confirmadas em baterias de até 8 equipes, de forma equilibrada (17 equipes viram 5 + 6 + 6). As piores colocadas correm primeiro e os líderes na última. |
| `sp_expire_pending_payments` | Expira cobranças pendentes vencidas (Pix e boletos antigos). |

**Triggers**

| Trigger | Regra |
|---|---|
| `trg_team_before_insert` | Só aceita inscrição com o formulário aberto, antes do início do campeonato e com vaga disponível (com lock contra overbooking). |
| `trg_team_member_validate` | CPF válido, limite de atletas por equipe, atleta em só uma equipe por campeonato. |
| `trg_team_after_status` | Equipe cancelada sai das baterias futuras e tem as cobranças pendentes canceladas. |
| `trg_payment_before_write` / `trg_payment_after_status` | Pagamento aprovado confirma a equipe; estorno cancela; o status aprovado não regride com webhook fora de ordem. |
| `trg_heat_status_timestamps` | Carimba início e fim da bateria. |
| `trg_heat_lane_validate` | Raia só com equipe confirmada, da categoria certa, uma vez por prova. |
| `trg_score_validate` | Valida o resultado conforme o tipo da prova (tempo dentro do cap, reps, kg). |
| `trg_score_rerank` | Recalcula o ranking da prova a cada lançamento. |
| `trg_score_audit` | Auditoria de quem lançou ou alterou cada resultado. |
| `trg_championship_rescore` | Mudou o sistema de pontuação, recalcula todas as provas. |
| `trg_workout_guard` | Impede mudar o tipo de uma prova que já tem resultados. |
| `trg_*_updated_at` | Mantém `updated_at`. |

**Views**: `vw_heat_schedule` (grade com raias em JSON), `vw_score_board`,
`vw_team_overview` e `vw_championship_summary`.

Mensagens de `RAISE EXCEPTION` das triggers chegam ao usuário como HTTP 422 com
o texto escrito no banco (ver `GlobalExceptionHandler`).

## API

Documentação interativa em `/swagger-ui`. Resumo:

| Área | Endpoints |
|---|---|
| Autenticação | `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/google` (ID token do botão do Google; no primeiro acesso sem `role` devolve `pending` para escolher o perfil), `GET /api/auth/me` |
| Minha conta | `PUT /api/auth/me` (trocar o e-mail exige a senha atual), `PUT /api/auth/me/password`, `DELETE /api/auth/me` (anonimiza a conta; recusa se houver campeonato ativo ou equipe confirmada em campeonato não encerrado) |
| Público | `GET /api/public/championships?q=&state=`, `GET /api/public/championships/{slug}`, `GET .../{slug}/schedule`, `GET .../{slug}/leaderboard?categoryId=`, `GET .../{slug}/stream` (SSE) |
| Pagamento (link público) | `GET /api/public/registrations/{código}`, `POST .../payments`, `GET .../payments/{id}`, `POST .../payments/{id}/simulate-approval` (sandbox) |
| Webhook | `POST /api/webhooks/mercadopago` |
| Atleta | `POST /api/athlete/championships/{slug}/teams`, `GET /api/athlete/teams`, `GET /api/athlete/heats` |
| Organizador | `/api/admin/championships` (CRUD, `PATCH .../registration`, `PATCH .../status`), categorias, provas (criação para várias categorias de uma vez), equipes (`PATCH /teams/{id}/status`), baterias (`POST /championships/{id}/heats/generate` monta a grade de todas as provas de uma vez, `POST /workouts/{id}/heats/generate` refaz uma prova, até 8 equipes por bateria; `POST /heats/{id}/start`, `PATCH /heats/{id}/status`), resultados (`PUT /workouts/{id}/scores/{teamId}`, só com a bateria da equipe concluída e com o nome do judge da raia) |

Erros seguem o padrão ProblemDetail (RFC 9457) com mensagens em português.

## Estrutura

```
backend/
  src/main/java/br/com/wodz/
    config/       segurança (JWT), OpenAPI, propriedades
    common/       erros, utilitários
    domain/       entidades JPA e enums
    repository/   Spring Data
    dto/          contratos da API (records)
    service/      regras de negócio, consultas às views, SSE, seed
    payment/      gateway (Mercado Pago e sandbox) e verificação de webhook
    web/          controllers REST
  src/main/resources/db/migration/   V1 tabelas, V2 functions/procedures, V3 triggers, V4 views
frontend/
  src/api/        cliente HTTP, tipos e endpoints
  src/components/ grade de baterias, relógio de prova, leaderboard...
  src/pages/      páginas públicas, do atleta e do organizador (admin/)
```

## Limitações conhecidas e próximos passos

- O SSE guarda as conexões em memória. Para rodar mais de uma instância da API,
  troque por Redis pub/sub ou `LISTEN/NOTIFY` do PostgreSQL (`RealtimeService`).
- O token JWT fica no `localStorage`. Para endurecer contra XSS, migre para
  cookie `HttpOnly` + proteção CSRF.
- Não há envio de e-mail (confirmação de inscrição, lembrete de bateria).
- Testes de integração das triggers e procedures com Testcontainers seriam o
  próximo passo natural de qualidade.

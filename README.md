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
| Pagamentos | Mercado Pago Checkout Pro (SDK Java oficial): Pix e cartão de crédito/débito escolhidos no Mercado Pago |

## Como rodar

### Com Docker (recomendado)

```bash
cp .env.example .env
# Preencha MP_ACCESS_TOKEN no .env: sem ele a API não sobe
docker compose up --build
```

- Site: http://localhost:8081
- API e Swagger: http://localhost:8080/swagger-ui

Com `SEED_DEMO=true` (padrão no compose) o sistema sobe com três campeonatos do
organizador de demonstração, cada um com 3 categorias e 3 provas por categoria
(ninguém aparece em duas equipes do mesmo campeonato):

- **Vitória Games - Ano 6**: encerrado e 100% concluído, com todas as baterias
  concluídas e todos os resultados lançados, cada um com o judge da raia.
  16 duplas por categoria.
- **Arena 911 - 2026**: acontecendo hoje. Prova 1 concluída com resultados,
  a 1ª bateria da Prova 2 concluída aguardando resultados (para lançar pela
  interface) e a 2ª em andamento. 16 duplas por categoria. A atleta de
  demonstração é capitã do "Time da Ana", na Scaled.
- **TCB 2026** (São Paulo/SP): inscrições abertas e começa daqui a 30 dias, para
  testar pagamentos. 8 duplas pagas por categoria (sobram vagas para inscrever
  novas equipes) e o "Time da Ana", na Scaled, aguardando pagamento: entre como
  atleta e use "Pagar inscrição" em Minhas equipes.

O seed só roda com o banco vazio. Para ver dados novos do seed num banco já
criado, recrie o banco (no compose: `docker compose down -v`, que apaga os dados).

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
MP_ACCESS_TOKEN=APP_USR-... SEED_DEMO=true mvn spring-boot:run

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

O pagamento é feito no **Checkout Pro do Mercado Pago**, integrado pelo SDK Java
oficial (`com.mercadopago:sdk-java`):

1. Depois da inscrição, a página `/pagamento/{código}` pede os dados de quem
   paga: nome, e-mail, CPF e celular com DDD (já preenchidos com os dados da
   inscrição e do perfil).
2. "Seguir para pagamento" cria uma preferência no Mercado Pago com esses dados
   e redireciona o navegador para o checkout (`init_point`).
3. Lá o pagador escolhe **Pix ou cartão de crédito/débito** e informa os dados
   de pagamento. O Wodz não recebe dados de cartão. Boleto fica fora do checkout.
4. Ao voltar, a página consulta o status no servidor até a confirmação. Pagamento
   aprovado confirma a equipe **por trigger no banco**.

```env
MP_ACCESS_TOKEN=APP_USR-...          # obrigatório (veja abaixo qual credencial usar)
MP_WEBHOOK_SECRET=...                # "Assinatura secreta" do painel de webhooks
MP_NOTIFICATION_URL=https://seu-dominio.com/api/webhooks/mercadopago
FRONTEND_URL=https://seu-dominio.com # URLs de retorno do Checkout Pro
```

- **Testes:** use a credencial de produção do **usuário de teste vendedor**
  (`APP_USR-...`) e pague logado com um **usuário de teste comprador**, com os
  cartões de teste do Mercado Pago. Credenciais `TEST-...` abrem o `sandbox_init_point`.
- **Retorno ao site:** com `FRONTEND_URL` em HTTPS público, o Mercado Pago volta
  sozinho para o Wodz após a aprovação (`auto_return`). Em `localhost` ele não
  aceita o retorno automático: o pagador volta pelo botão "Voltar ao site" do checkout.
- **Confirmação:** enquanto a página está aberta, a API busca o pagamento no
  Mercado Pago pela referência externa (funciona sem webhook, inclusive em
  `localhost`). Em produção, configure `MP_NOTIFICATION_URL` (HTTPS público) e o
  evento de pagamentos no painel: o webhook valida `x-signature` quando
  `MP_WEBHOOK_SECRET` está configurado e sempre reconsulta o pagamento na API.
- Num mesmo checkout o pagador pode tentar mais de uma vez (ex.: cartão recusado
  e depois Pix); vale a tentativa aprovada, e uma segunda aprovação para a mesma
  equipe não é gravada (fica no log para estorno).
- O SDK 2.1.21 declara ferramentas de build (Maven, Velocity) como dependências
  de runtime; o `pom.xml` as exclui e declara só o que o SDK usa.

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
| Pagamento (link público) | `GET /api/public/registrations/{código}`, `POST .../payments`, `GET .../payments/{id}` |
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
    payment/      gateway do Checkout Pro (SDK Mercado Pago) e verificação de webhook
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

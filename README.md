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
- API pelo frontend: http://localhost:8081/api/public/config

O Compose usa o projeto `wodz` e publica apenas a porta `8081` por padrão.
Executar `docker compose up --build -d` novamente atualiza os containers desse
projeto. O banco e o backend se comunicam pela
rede interna do Docker, sem ocupar as portas `5432` e `8080` do Windows.
O túnel do Mercado Pago continua apontando para a porta `8081`.

Se o Docker informar `address already in use` na porta `8081`, confira
`docker ps --format "table {{.Names}}\t{{.Ports}}"` para identificar o container
que já usa essa porta. Para manter outro serviço em execução,
escolha uma porta livre no `.env`, por exemplo `FRONTEND_HOST_PORT=8082`, e execute
`docker compose up --build -d`. O site ficará em `http://localhost:8082`.
Se `FRONTEND_URL` e `CORS_ORIGINS` estiverem preenchidos com URLs locais no `.env`,
ajuste-os para essa porta também; quando estiverem ausentes, o Compose acompanha
`FRONTEND_HOST_PORT` automaticamente. Se usar o túnel, aponte-o à mesma porta
(`ngrok http 8082` neste exemplo) e mantenha as URLs HTTPS configuradas.
Para executar uma segunda instância do WODZ ao mesmo tempo, além da porta livre,
use outro nome de projeto: `docker compose -p wodz-secundario up --build -d`.

Para acessar o banco diretamente ou usar o Swagger, habilite a configuração
opcional de desenvolvimento:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build -d
```

Nesse modo, o Swagger fica em http://localhost:8080/swagger-ui e o banco em
`localhost:5432`. Se essas portas estiverem ocupadas, defina `BACKEND_HOST_PORT`
e `DB_HOST_PORT` no `.env` com portas livres. Ao executar a API fora do Docker,
ajuste também `DB_URL` para a porta escolhida para o banco.

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
docker compose -f docker-compose.yml -f docker-compose.dev.yml up db

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

Por padrão o provedor é o **sandbox local**: cartão é aprovado na hora (cartões com
final 0002 são recusados) e Pix fica pendente até você clicar em
"Simular pagamento aprovado".

### Testar localmente nas APIs oficiais do Mercado Pago

Use `PAYMENT_PROVIDER=mercadopago` e `MP_TEST_MODE=true`. O sistema chama as APIs
oficiais (`api.mercadopago.com`), solicita `test_token=true` no OAuth e usa o
Card Payment Brick; não existe aprovação pelo simulador local nesse modo.
O Mercado Pago também chama suas credenciais de teste de "sandbox", mas isso
é diferente do `PAYMENT_PROVIDER=sandbox` do WODZ.

1. Na aplicação Marketplace da **conta real** no Mercado Pago Developers, obtenha
   **Client ID**, **Client Secret** e a **Public Key de teste** da aplicação
   integradora. No checkout atual (Bricks), testes de cartão usam uma conta real
   com credenciais de teste, sem movimentar dinheiro. As contas fictícias de
   vendedor/comprador são para outros fluxos, como o redirecionamento para o
   checkout do Mercado Pago. Veja [testes de Bricks](https://www.mercadopago.com.br/developers/pt/docs/checkout-bricks/integration-test/test-payment-flow).
2. Exponha o frontend local por um túnel HTTPS. Com Docker, o destino é
   `http://localhost:8081`, pois o frontend também encaminha `/api` para o backend.
   Por exemplo, após instalar/configurar o [ngrok](https://ngrok.com/docs/getting-started/quickstart),
   execute `ngrok http 8081` e mantenha o túnel aberto. O servidor e o banco
   continuam no seu computador. Substitua `https://seu-tunel` abaixo pelo endereço
   HTTPS fornecido pelo túnel.
3. No painel da aplicação, habilite **Authorization Code com PKCE** e cadastre
   exatamente `https://seu-tunel/api/public/mercadopago/callback` como Redirect URL.
   Em **Webhooks → Modo teste**, cadastre
   `https://seu-tunel/api/webhooks/mercadopago`, selecione **Pagamentos** (`payment`)
   e obtenha a assinatura secreta. Consulte [OAuth](https://www.mercadopago.com.br/developers/pt/docs/security/oauth/creation)
   e [webhooks Payments](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-payments/additional-content/your-integrations/notifications/webhooks).
4. Preencha o `.env` da raiz (sem publicar suas chaves):

   ```env
   APP_ENV=dev
   PAYMENT_PROVIDER=mercadopago
   MP_TEST_MODE=true
   MP_CLIENT_ID=seu-application-id
   MP_CLIENT_SECRET=seu-client-secret
   MP_PUBLIC_KEY=sua-public-key-de-teste
   MP_CARD_PUBLIC_KEY_SOURCE=platform
   MP_PLATFORM_FEE_PERCENT=0
   MP_WEBHOOK_SECRET=sua-assinatura-secreta
   FRONTEND_URL=https://seu-tunel
   CORS_ORIGINS=https://seu-tunel,http://localhost:8081,http://localhost:5173
   MP_NOTIFICATION_URL=https://seu-tunel/api/webhooks/mercadopago
   APP_ENCRYPTION_KEY=sua-chave-estavel-de-32-bytes-em-base64
   ```

   Preserve `APP_ENCRYPTION_KEY` depois da primeira vinculação. Para gerar uma
   chave no PowerShell: `[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))`.
   O Docker Compose lê o `.env` automaticamente; executar Maven diretamente
   exige exportar essas variáveis no terminal.
5. Execute `docker compose up --build -d` na raiz; os dados existentes do banco
   são mantidos. Entre como Organizador e, em **Minha conta → Recebimentos**,
   autorize sua conta real. O botão **Conectar Mercado Pago** abre diretamente
   a autorização oficial em `https://auth.mercadopago.com/authorization`.
   Ao usar ngrok gratuito, acesse previamente a URL HTTPS do túnel no mesmo
   navegador e clique em **Visit Site** caso apareça o aviso de acesso.
   Assim, o aviso já terá sido resolvido quando o Mercado Pago retornar ao callback.
   Mantenha a página original aberta: ela consulta a API e confirma o vínculo,
   mesmo quando o WODZ foi aberto em `localhost` e o retorno chega pelo túnel.
   O WODZ mostra **Teste** na credencial conectada.
   Se havia uma autorização de produção, clique em **Renovar autorização**;
   novas cobranças com essa credencial antiga são bloqueadas no modo de testes.
6. Entre como Atleta, inscreva uma equipe em um campeonato com inscrições abertas
   e pague com um [cartão de teste oficial](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-payments/additional-content/your-integrations/test/cards).
   Para o cenário aprovado, use titular `APRO` e CPF `12345678909`. O e-mail do
   pagador deve ser diferente do e-mail da conta Mercado Pago autorizada e
   **não pode ser e-mail de usuário fictício de teste**, conforme as regras do Brick.
   Confira a resposta de pagamento e a confirmação da equipe, consultadas na API.

No fluxo `/v1/payments` atual, testes de meios offline podem permanecer
**pendentes**. Não use "Simular pagamento aprovado" para validar o Mercado Pago
nem pague o Pix de teste em um banco real. A aprovação de cartão com os dados
oficiais é o primeiro cenário para validar o fluxo completo. Testar Pix aprovado
depende do suporte do provedor para este produto; a regra `APRO` de Pix da Orders
API não se aplica ao endpoint atual.

Se o endereço do túnel mudar, atualize as duas URLs no painel, `FRONTEND_URL`,
`CORS_ORIGINS` e `MP_NOTIFICATION_URL`, recrie o backend e conecte novamente.
Erros de autorização geram uma mensagem no log do backend sem expor tokens;
consulte `docker compose logs backend`. Uma URL ou uma chave incorreta pode
impedir a vinculação ou a tokenização. A execução real precisa dessas credenciais;
os testes automatizados do projeto não substituem a validação no seu painel.

Para cobrar de verdade com o **Mercado Pago**:

```env
PAYMENT_PROVIDER=mercadopago
MP_TEST_MODE=false                   # true usa credenciais de teste nas APIs oficiais
MP_CLIENT_ID=...                     # Application ID da aplicação Marketplace
MP_CLIENT_SECRET=...                 # segredo OAuth da aplicação
MP_PUBLIC_KEY=APP_USR-...            # usada pelo Card Brick no navegador
MP_WEBHOOK_SECRET=...                # "Assinatura secreta" do painel de webhooks
MP_NOTIFICATION_URL=https://seu-dominio.com/api/webhooks/mercadopago
MP_THREE_D_SECURE=true               # 3DS 2.0 nos cartões (padrão: ligado)
FRONTEND_URL=https://seu-dominio.com
APP_ENCRYPTION_KEY=...               # 32 bytes aleatórios em Base64, mantenha a chave estável
MP_PLATFORM_FEE_PERCENT=0            # sem comissão da plataforma por padrão
```

### Conta de recebimento do organizador

Em **Minha conta → Recebimentos — Mercado Pago**, o organizador conecta sua conta
pela página oficial do Mercado Pago. O backend troca o código OAuth, verifica em
`/users/me` se a identidade corresponde à autorização e se a conta é brasileira
e ativa. A interface mostra nome/e-mail e ID da conta confirmada. A verificação
de identidade/documentos e a liberação dos recebimentos são feitas pelo próprio
Mercado Pago; autorizar a conta não garante a aprovação de todas as cobranças.
O modelo exige conta de vendedor com identificação **KYC nível 6**, conforme os
[pré-requisitos do Mercado Pago](https://www.mercadopago.com.br/developers/pt/docs/split-payments/split-1-1/prerequisites).

Configuração externa, uma vez por ambiente:

1. No Mercado Pago Developers, crie/edite uma aplicação de **Pagamentos online**,
   **Checkout API**, modelo **Marketplace**. O código atual usa Checkout
   Transparente/Bricks; Checkout Pro é outro produto, também compatível com OAuth
   e split 1:1, mas não é o fluxo utilizado por este projeto.
2. Habilite **Authorization Code com PKCE**. O sistema usa `state` de uso único,
   válido por 10 minutos, e PKCE S256. O botão abre a autorização oficial
   diretamente; somente o callback consome o `state`. Em caso de expiração,
   inicie uma nova conexão pela Minha conta.
3. Cadastre exatamente `https://seu-dominio.com/api/public/mercadopago/callback`
   como **Redirect URL**. A base é `FRONTEND_URL`, e `/api` precisa chegar ao
   backend (o proxy do frontend já suporta isso). Para desenvolvimento com OAuth,
   use uma URL HTTPS acessível/túnel e configure a mesma base no ambiente.
4. Defina `MP_CLIENT_ID`, `MP_CLIENT_SECRET`, `MP_PUBLIC_KEY`,
   `MP_WEBHOOK_SECRET`, `MP_NOTIFICATION_URL`, `APP_ENCRYPTION_KEY`,
   `FRONTEND_URL` e `CORS_ORIGINS`. No painel, configure notificações de pagamentos
   para `https://seu-dominio.com/api/webhooks/mercadopago`.
5. Para produção: `APP_ENV=prod`, `PAYMENT_PROVIDER=mercadopago`, `MP_TEST_MODE=false`,
   `SEED_DEMO=false` e `JWT_SECRET` próprio. Conecte uma conta de produção; contas
   de teste são recusadas nesse ambiente. Valide primeiro em homologação com
   `MP_TEST_MODE=true`, conta real autorizada e dados de teste de Bricks, incluindo
   autorização, renovação, cartão e webhook assinado.

Os tokens ficam cifrados com AES-256-GCM no servidor, com renovação automática
antes do vencimento quando usados. Novas cobranças usam a conta ativa do dono do
campeonato; webhooks e consultas usam a conta vinculada à cobrança original.
Ao alternar entre teste e produção, renovar a autorização cria um vínculo separado
e preserva as credenciais anteriores para conciliar as cobranças do modo original.
Desconectar impede novas cobranças e preserva credenciais cifradas para conciliar
as anteriores. Para trocar a conta é preciso desconectar a atual primeiro.
Excluir a conta WODZ remove também os tokens e os dados pessoais da conta de
recebimento; permanecem os identificadores necessários ao histórico financeiro.
No sandbox, pagamentos continuam simulados e o perfil informa que OAuth está
indisponível. Publicar/abrir inscrições com Mercado Pago exige conta conectada.

Referências oficiais: [configuração Marketplace / split 1:1](https://www.mercadopago.com.br/developers/pt/docs/split-payments/split-1-1/integration-configuration/create-configuration)
e [OAuth com PKCE](https://www.mercadopago.com.br/developers/pt/docs/security/oauth/creation).

### Horários das baterias

Antes de montar a grade, informe a **duração / time cap** de cada prova na aba
Provas, inclusive carga máxima e máximo de repetições. O valor fica registrado
nas baterias geradas. O campo **Intervalo após o término** representa o descanso,
e o próximo início é `início + duração + intervalo`. Por exemplo, `07:00 + 13 min
+ 8 min = 07:21`.

Ao clicar **Iniciar bateria**, o banco registra o início real e reajusta as
baterias futuras do mesmo campeonato, inclusive outras categorias/provas,
preservando pausas adicionais e os horários de baterias já iniciadas. Uma
bateria prevista para `13:21`, iniciada às `13:23`, com `10 min` de prova e
`3 min` de descanso, coloca a próxima às `13:36`. Concluir depois do término
previsto também adia a sequência; concluir antes não antecipa a grade. A grade
mostra início real, término previsto/real e tempo total quando concluída.
Repetir o comando de início ou reabrir e iniciar a bateria de novo não duplica o atraso. Baterias históricas mantêm os
horários existentes; intervalos antigos não podem ser recuperados com precisão
e ficam em zero. Regenere as grades ainda não iniciadas para aplicar o novo cálculo.

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

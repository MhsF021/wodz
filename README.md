# WODZ

Sistema para campeonatos de CrossFit. O **organizador** cadastra o campeonato,
as categorias e as provas, abre o formulário de inscrição, acompanha as equipes,
monta e controla as baterias e lança os resultados. O **atleta** busca
campeonatos, inscreve a equipe, paga no checkout do Mercado Pago, vê o horário
e o status das suas baterias e acompanha o leaderboard ao vivo.

| Camada | Tecnologia |
|---|---|
| Backend | Java 21, Spring Boot 3.5 (Web, Data JPA, Security/JWT, Validation), Flyway, springdoc (Swagger) |
| Banco | PostgreSQL 16, com tabelas, functions, procedures, triggers e views versionadas no Flyway |
| Frontend | React 19, TypeScript, Vite, React Router 7, TanStack Query |
| Tempo real | Server-Sent Events (baterias e leaderboard atualizam sozinhos) |
| Pagamentos | Mercado Pago Checkout Pro, com redirecionamento na mesma aba |

## Como rodar

### Com Docker (recomendado)

```bash
if [ ! -f .env ]; then cp .env.example .env; fi
docker compose up --build
```

- Site: http://localhost:8081
- API pelo frontend: http://localhost:8081/api/public/config

O Compose usa o projeto `wodz` e publica apenas a porta `8081` por padrão.
Preencha as credenciais no `.env` local. Não copie novamente `.env.example`
sobre um `.env` configurado: isso sobrescreve a aplicação e os segredos escolhidos.
O exemplo mantém esses campos vazios; as credenciais de pagamento não devem ser
gravadas nele. Preserve o `.env` e a chave de cifra ao atualizar o ambiente.

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
organizador de demonstração, cada um com 11 categorias e 3 provas por categoria
(ninguém aparece em duas equipes do mesmo campeonato):

- **Vitória Games - Ano 6**: encerrado e 100% concluído, com todas as baterias
  concluídas e todos os resultados lançados, cada um com o judge da raia.
  16 equipes por categoria (duplas ou atletas individuais no RX).
- **NOX DURANCE**: acontecendo hoje. Prova 1 concluída com resultados,
  a 1ª bateria da Prova 2 concluída aguardando resultados (para lançar pela
  interface) e a 2ª em andamento. 16 equipes por categoria. O atleta de
  demonstração é capitão do "Tico e Teco", na Scaled misto.
- **TCB 2026** (São Paulo/SP): inscrições abertas e começa daqui a 30 dias, para
  testar pagamentos. 8 equipes pagas por categoria (sobram vagas para inscrever
  novas equipes) e o "Tico e Teco", na Scaled misto, aguardando pagamento: entre como
  atleta e use "Pagar inscrição" em Minhas equipes.

O seed só roda com o banco vazio e não reescreve campeonatos ou inscrições existentes.
`SEED_DEMO_CATEGORIES_JSON` no `.env` define os exemplos usados nos três campeonatos:
Iniciante, Scaled e Intermediário com duplas femininas, masculinas ou mistas (1 mulher
e 1 homem), e RX individual feminino ou masculino. O JSON contém nomes, descrições,
composição e tabelas de cargas e movimentos por nível. Sem essa variável, o sistema
usa `backend/src/main/resources/demo-categories.json`. As cargas de Iniciante e Scaled
seguem as referências visuais; Intermediário e RX usam valores ilustrativos.

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
- **E-mail já cadastrado com senha:** o vínculo automático exige que o Google
  seja responsável pelo endereço (Gmail ou Google Workspace com domínio validado).
  Para endereços de outros provedores, entre com a senha existente.
- **Primeiro acesso:** a tela pede o perfil (atleta ou organizador) e o sexo antes de criar a conta. No cadastro, valem as opções já marcadas na página. O login Google atual não inclui sexo no ID token; quando ainda não informado no WODZ, esse dado é preenchido pelo usuário, sem dedução por nome. A leitura pela People API exigiria integração e autorização adicionais.
- **Conta vinculada ao Google:** "Minha conta" mostra "Desvincular conta Google"
  no lugar da seção de senha. A confirmação usa a mesma conta Google, nesta aba.
- **Desvincular Google:** `DELETE /api/auth/me/google` recebe uma nova credencial
  da mesma conta Google. Com senha local, mantém a conta e exige novo login por
  e-mail/senha, bloqueando novo vínculo automático. Sem senha local, encerra a
  conta WODZ pelas mesmas regras de exclusão; campeonatos/inscrições ativos podem
  impedir a operação. Os dados de inscrições/resultados seguem a retenção
  descrita na [matriz de privacidade](docs/SECURITY_LGPD.md).

A remoção do vínculo no WODZ e a revogação da permissão no Google são etapas
separadas. Após a API confirmar a operação, o navegador tenta `accounts.id.revoke`
para a identidade confirmada. Falha do Google não desfaz a operação local: a tela
informa a pendência e permite concluir pela
[página de conexões da Conta Google](https://myaccount.google.com/connections).
O logout local acontece imediatamente; a confirmação Google continua em segundo
plano por até sete segundos e informa seu resultado na tela de login, somente em
memória. Durante essa tentativa o login Google fica suspenso para evitar que
as duas operações disputem a configuração global do SDK.
`disableAutoSelect` evita seleção automática ao sair, sem substituir revogação.
Consulte o [guia de revogação Google](https://developers.google.com/identity/gsi/web/guides/revoke)
e a [referência GIS](https://developers.google.com/identity/gsi/web/reference/js-reference).

Configuração (`GOOGLE_CLIENT_ID`, já com o Client ID do projeto como padrão):

```env
GOOGLE_CLIENT_ID=84072245662-m7cc1brb2fb7lkv92i9dqm5o1phnagr9.apps.googleusercontent.com
```

No Google Cloud, em "Origens JavaScript autorizadas" do Client ID, cadastre
`http://localhost:5173`, `http://localhost:8081` e, depois, o domínio de
produção (com `https`). O frontend recebe o Client ID por `GET /api/public/config`.
Em **URIs de redirecionamento autorizados**, cadastre também a URL exata de
cada origem: `http://localhost:5173/api/public/google/callback`,
`http://localhost:8081/api/public/google/callback` e
`https://SEU-DOMINIO/api/public/google/callback` em produção.
O botão usa `ux_mode: redirect`, conforme a
[referência oficial GIS](https://developers.google.com/identity/gsi/web/reference/js-reference).
O retorno POST valida o cookie anti-CSRF, assinatura e nonce. Uma ponte na mesma
origem entrega a credencial ao frontend, sem tokens na URL: o estado e a resposta
temporários em `sessionStorage` são consumidos e apagados no retorno. O fluxo expira
em 15 minutos. O primeiro acesso pede o perfil antes de criar a conta; erro ou
confirmação expirada pede uma nova tentativa. Mantenha `FRONTEND_URL` na mesma origem
em que o usuário iniciou a conexão, inclusive para o retorno do Mercado Pago.
Deixe a variável vazia para desligar o login com Google.

## Pagamentos

O WODZ cria uma preferência de **Checkout Pro** no servidor e redireciona o comprador
para o Mercado Pago na mesma aba. Não existem formulário de cartão, QR Code,
tokenização, SDK de pagamentos, desafio 3DS incorporado ou simulador local no WODZ.
Os meios disponíveis são definidos pelo Mercado Pago no checkout hospedado.

Configure a aplicação Mercado Pago para **Checkout Pro / Marketplace**, mantendo
Authorization Code com PKCE e a URL OAuth `FRONTEND_URL/api/public/mercadopago/callback`.
Para testar, use vendedor/comprador de teste conforme a
[documentação oficial do Checkout Pro](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/integration-test).
O Checkout Pro usa **init_point** tanto nos testes quanto em produção. Credenciais
`TEST-` e `sandbox_init_point` não são usadas neste fluxo. No modo de teste,
conecte por OAuth uma **conta de vendedor de teste**; o WODZ solicita `APP_USR-`
e confirma a tag `test_user` consultando `/users/me`. Uma conta real é recusada
nesse modo, mesmo se o retorno OAuth informar `live_mode=false`.
Use outro usuário de teste brasileiro como comprador. Não misture compradores
reais e vendedores de teste. Autorizações antigas com `TEST-` precisam ser
reconectadas; não há renovação nem uso de credenciais `TEST-`, inclusive para consultas.
Os registros financeiros existentes permanecem preservados.
Após atualizar uma autorização, checkouts antigos com URL sandbox não são
reutilizados: solicite novamente o pagamento pelo WODZ, sem editar URLs à mão.

```env
PAYMENT_PROVIDER=mercadopago
CHECKOUT_EXPIRATION=30m
MP_TEST_MODE=true
MP_CLIENT_ID=...
MP_CLIENT_SECRET=...
MP_WEBHOOK_SECRET=...
MP_NOTIFICATION_URL=https://seu-tunel/api/webhooks/mercadopago
FRONTEND_URL=https://seu-tunel
CORS_ORIGINS=https://seu-tunel,http://localhost:8081,http://localhost:5173
APP_ENCRYPTION_KEY=...
MP_PLATFORM_FEE_PERCENT=0
```

O retorno exige uma origem pública HTTPS, sem caminho. Exponha o frontend Docker
(`ngrok http 8081`), cadastre a mesma origem e reconstrua com
`docker compose up --build -d`, preservando o volume PostgreSQL e a chave de cifra.
Com `PAYMENT_PROVIDER=disabled`, não há pagamentos fictícios nem aprovação local.
As variáveis `MP_PUBLIC_KEY`, `MP_THREE_D_SECURE`, `MP_CARD_PUBLIC_KEY_SOURCE`
e `PIX_EXPIRATION` deixaram de ser usadas.

A preferência contém valor/categoria do servidor, referência aleatória, URLs de
sucesso/pendência/falha e `marketplace_fee` para o split 1:1
([referência oficial](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/how-tos/integrate-marketplace)).
Cliques repetidos consultam a preferência no Mercado Pago antes de retomá-la:
aplicação OAuth, vendedor, referência, valor, taxa e retorno precisam corresponder.
Preferências expiradas ou com retorno antigo são recriadas depois da consulta dos
pagamentos anteriores; falhas de acesso ou dados incompatíveis interrompem o fluxo.
Cada tentativa financeira tem um
registro próprio; uma recusa seguida de aprovação preserva ambas no histórico.

O seed de demonstração não cria pagamentos. Os eventos demonstrativos passados ou
em andamento têm inscrições gratuitas; os eventos futuros pagos ficam aguardando
pagamento pelo Checkout Pro. O seed não modifica registros de bancos já existentes.

Se o Checkout Pro exibir apenas “Ops, ocorreu um erro”, essa tela não confirma que
houve pagamento. Confira se a aplicação em Minhas integrações usa Checkout Pro,
reconstrua backend/frontend e reconecte o vendedor de teste em Recebimentos quando
a autorização for antiga. Inicie o pagamento pelo WODZ e entre no checkout com
outro comprador de teste brasileiro, em sessão separada da conta vendedora.
Não edite o endereço retornado pelo Mercado Pago. Para obter o diagnóstico seguro
do backend, use `docker compose logs --since 10m backend | grep -E 'Checkout Pro recusado|Checkout Pro indisponível|Vinculação Mercado Pago'`.
Os logs identificam HTTP e motivos fixos, sem registrar tokens ou o corpo privado
da resposta. A configuração da conta/aplicação e o pagamento completo precisam
ser verificados no Mercado Pago; testes locais não substituem essa verificação.

Quando o erro ocorre **depois de clicar em Pagar no Mercado Pago**, anote o
`preference-id` e o `router-request-id` da URL. O caminho `/error/` é uma tela
genérica do provedor e não contém o motivo da falha. Uma preferência criada com
sucesso também não comprova que uma tentativa financeira foi registrada.
Ao voltar ao WODZ, a página diferencia uma tentativa pendente de um checkout sem
pagamento registrado e permite tentar novamente após a consulta ao provedor.

No Windows, consulte a preferência e os pagamentos associados com:

```powershell
./scripts/Diagnose-MercadoPago.ps1 -PreferenceId '<preference-id da URL>'
```

O diagnóstico lê `.env` e as variáveis da sessão, consulta apenas a API oficial
e não imprime nem grava tokens, e-mail, CPF ou dados do comprador. Precisa do
token do **vendedor daquela preferência**: pode usar `MP_ACCESS_TOKEN` se ele
pertencer ao vendedor, ou `MP_DIAGNOSTIC_ACCESS_TOKEN` definido apenas na sessão.
Essa variável serve exclusivamente para o comando; o backend continua usando
as autorizações OAuth dos organizadores. Com `MP_CLIENT_ID` e `MP_CLIENT_SECRET`,
o comando também confere a conta integradora e se a aplicação está ativa ou
bloqueada, autenticando a própria aplicação sem alterar as credenciais salvas.

Para testes com **saldo da conta**, confira o saldo fictício do comprador em
Suas integrações → Contas de teste: ele precisa cobrir o valor da inscrição.
Também confira a conta de **integrador**, além do vendedor e do comprador,
pois o WODZ usa o modelo marketplace. O Mercado Pago disponibiliza esse terceiro
tipo de [conta de teste para integrações marketplace](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/test-accounts).
Uma mistura de tipos de conta aparece no diagnóstico como ponto de investigação,
sem ser tratada como causa comprovada. Para isolar o problema no pagamento com
saldo, compare com uma compra usando cartão de teste e titular `APRO`, conforme
a [documentação de compras de teste](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/integration-test/test-purchases).
Se a busca continuar com zero pagamentos, não há `status_detail` financeiro para
consultar: obtenha a resposta da requisição que falhou na aba Network do navegador
ou encaminhe os IDs da preferência e da requisição ao suporte do Mercado Pago.
Não compartilhe cookies, tokens, cabeçalhos de autorização ou dados de cartão.

O parâmetro `status=approved` do navegador nunca confirma uma equipe. Retorno,
polling, webhook assinado e conciliação consultam o provedor com o OAuth da conta
original. Antes de registrar o pagamento, conferem valor, moeda BRL, referência,
recebedor e modo de teste/produção. A trigger existente confirma a equipe.
O job concilia preferências ainda sem aprovação dos últimos três dias a cada dois
minutos, mesmo quando o comprador fecha a aba; webhooks acompanham mudanças posteriores.
Se credenciais forem revogadas, a conciliação deve ser retomada pela operação.

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
   **Checkout Pro**, modelo **Marketplace**, com OAuth e split 1:1.
2. Habilite **Authorization Code com PKCE**. O sistema usa `state` de uso único,
   válido por 10 minutos, e PKCE S256. O botão abre a autorização oficial
   diretamente; somente o callback consome o `state`. Em caso de expiração,
   inicie uma nova conexão pela Minha conta.
3. Cadastre exatamente `https://seu-dominio.com/api/public/mercadopago/callback`
   como **Redirect URL**. A base é `FRONTEND_URL`, e `/api` precisa chegar ao
   backend (o proxy do frontend já suporta isso). Para desenvolvimento com OAuth,
   use uma URL HTTPS acessível/túnel e configure a mesma base no ambiente.
4. Defina `MP_CLIENT_ID`, `MP_CLIENT_SECRET`,
   `MP_WEBHOOK_SECRET`, `MP_NOTIFICATION_URL`, `APP_ENCRYPTION_KEY`,
   `FRONTEND_URL` e `CORS_ORIGINS`. No painel, configure notificações de pagamentos
   para `https://seu-dominio.com/api/webhooks/mercadopago`.
5. Para produção: `APP_ENV=prod`, `PAYMENT_PROVIDER=mercadopago`, `MP_TEST_MODE=false`,
   `SEED_DEMO=false` e `JWT_SECRET` próprio. Conecte uma conta de produção; contas
   de teste são recusadas nesse ambiente. Valide primeiro em homologação com
   `MP_TEST_MODE=true`, contas de teste vendedor/comprador, incluindo
   autorização, renovação, checkout hospedado e webhook assinado.
   Confira o diagnóstico e as condições em
   [Checkout Mercado Pago](docs/MERCADOPAGO_CHECKOUT.md).

Os tokens ficam cifrados com AES-256-GCM no servidor, com renovação automática
antes do vencimento quando usados. Novas cobranças usam a conta ativa do dono do
campeonato; webhooks e consultas usam a conta vinculada à cobrança original.
Ao alternar entre teste e produção, renovar a autorização cria um vínculo separado
e preserva as credenciais anteriores para conciliar as cobranças do modo original.
Desconectar impede novas cobranças e preserva credenciais cifradas para conciliar
as anteriores. Para trocar a conta é preciso desconectar a atual primeiro.
Excluir a conta WODZ remove também os tokens e os dados pessoais da conta de
recebimento; permanecem os identificadores necessários ao histórico financeiro.
Com o provedor desativado, pagamentos e OAuth ficam indisponíveis. Publicar/abrir inscrições com Mercado Pago exige conta conectada.

Referências oficiais: [configuração Marketplace / split 1:1](https://www.mercadopago.com.br/developers/pt/docs/split-payments/split-1-1/integration-configuration/create-configuration)
e [OAuth com PKCE](https://www.mercadopago.com.br/developers/pt/docs/security/oauth/creation).

### Checkout de pagamento

A página de pagamento redireciona ao Checkout Pro do Mercado Pago na mesma aba,
com o valor cotado da inscrição. O pagamento exige aceite dos documentos públicos
em `/termos` e `/privacidade`; o servidor registra data e versão dos termos.
A inscrição é confirmada somente após a API conferir o pagamento no provedor.
Um checkout válido pode ser retomado após a validação da preferência.

Os fluxos de emissão de QR Code e de repasses centralizados foram removidos.
Não existe configuração para reativá-los. A preferência hospedada mantém o meio
removido excluído. Cobranças e repasses históricos permanecem no banco; migrações
já aplicadas não devem ser alteradas. Uma cobrança antiga ainda válida impede
outro pagamento até seu vencimento, e uma aprovação no provedor impede duplicação.

Veja [Checkout Mercado Pago](docs/MERCADOPAGO_CHECKOUT.md).

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

## Experiência do atleta

A busca de campeonatos oferece os filtros **Todos**, **Em andamento**,
**Inscrições abertas** e **Encerrados**. Eles combinam com nome/cidade e UF,
ficam na URL e são aplicados no banco antes do limite de 50 resultados.

Em **Minhas equipes**, cada mini card mostra somente campeonato, equipe e
categoria. Ao abrir o card, um único pop-up apresenta os horários de Brasília
e as raias da equipe. Selecionar uma bateria mostra sua prova nesse mesmo
pop-up, com opção de voltar aos horários. Pagamentos pendentes podem ser
retomados dentro dele. O capitão também pode usar **Cancelar a inscrição**
enquanto o pagamento estiver pendente, após confirmar a desistência em um pop-up.
Isso libera a vaga e remove a inscrição de **Minhas equipes**, onde ficam apenas
inscrições pendentes ou confirmadas. Permite uma nova inscrição enquanto as inscrições estiverem abertas.
Inscrições confirmadas precisam ser tratadas com a organização. Uma aprovação
de pagamento recebida depois do cancelamento é registrada sem reativar a equipe
(migration V23). A situação da inscrição e a grade são atualizadas
enquanto os detalhes estão abertos. O diálogo fecha com Esc e devolve o foco
ao card; também respeita a preferência por movimento reduzido.

O preloader central mantém o círculo pequeno, sem texto visível, com a cor
do perfil. Trocas de página ou da aba de um campeonato têm no mínimo 550 ms
antes de revelar os dados com uma transição breve. Filtros e consultas dentro
do pop-up usam indicadores locais, preservando o conteúdo já carregado.

Uma conta de atleta pode ter somente uma inscrição não cancelada por
campeonato, mesmo antes do pagamento e mesmo escolhendo outra categoria.
Uma nova tentativa informa **Você já possui uma inscrição para esse campeonato**
e oferece continuar o pagamento existente. O serviço e a migration V16
protegem também tentativas simultâneas. Os registros financeiros e administrativos antigos são preservados;
uma inscrição cancelada permite uma nova tentativa. A migration V17 adiciona
a busca com filtros. Ambas são aplicadas pelo Flyway na inicialização da API.

## Categorias e imagem do campeonato

O cadastro local exige sexo (masculino ou feminino), persistido na conta pela
migration V28. Contas existentes completam essa informação em **Minha conta**
antes de uma nova inscrição. O formulário usa o sexo do capitão e desabilita
categorias incompatíveis; categorias exclusivamente femininas ou masculinas
oferecem apenas o sexo permitido aos demais integrantes. A API também confere
o sexo do capitão com seu cadastro. Inscrições individuais dispensam o nome da
equipe e usam o nome completo do atleta; V28 também atualiza os nomes individuais
existentes e amplia o campo para 120 caracteres.

O **Leaderboard** permite pesquisar pelo nome da equipe ou de qualquer atleta
confirmado na categoria atual, sem distinguir maiúsculas ou acentos. O filtro
preserva as posições oficiais. A tela de pagamento aprovado mostra um check
discreto e o resumo da inscrição usando a paleta do site.

Ao criar uma categoria, escolha **Feminina**, **Masculina** ou **Mista**. Em
categorias mistas, marque uma ou mais composições permitidas, por exemplo
**1 homem e 2 mulheres** ou **2 homens e 1 mulher** para trios. A inscrição
confere o gênero declarado de todos os integrantes, o tamanho da equipe e a
composição, também no PostgreSQL. As regras não podem mudar depois de haver
inscrições. Categorias antigas precisam receber uma regra compatível com suas
equipes antes de aceitar novas inscrições; nomes de categoria não são usados
para deduzir essa regra.

No **Painel do organizador → campeonato → Dados**, escolha a foto de perfil e a capa do evento.
JPG, PNG ou WebP de até 6 MB são recortados diretamente do original, sem uma
compressão intermediária. Perfil usa até 1024 × 1024 px e capa até 2048 × 512 px,
sem ampliar arquivos menores. Artes que cabem no limite ficam em PNG sem perda;
fotografias maiores usam JPEG com qualidade de 90–96%. A API valida e regrava os
pixels sem metadados (PNG ou JPEG de até 1 MiB). Ajuste zoom e posição por arraste ou pelos
controles, aplique o recorte e confira a prévia antes de salvar. A capa aparece
nos cards de campeonatos e ao fundo da página pública; a foto aparece ao lado do
nome. O formulário permite substituir ou remover cada imagem separadamente.

O Flyway aplica **V18–V21** para composição, imagens, códigos de inscrição mais
longos e separação de perfil e capa. Links de pagamento anteriores continuam válidos.

Na aba pública **Categorias e provas**, cada categoria é um card com formato,
composição e inscrição. Os filtros **Todos**, **Masculino**, **Feminino** e **Misto**
mostram as categorias conforme sua composição. Selecionar um card abre um diálogo
com descrição, cargas e movimentos e todas as provas. As tabelas de cargas
e movimentos aparecem exclusivamente nesse diálogo. Escape ou o botão de fechar
retornam ao card. Categorias femininas e masculinas mostram a coluna correspondente;
categorias mistas mostram as duas. `✓` significa previsto e `✕`, não previsto.

No painel **Categorias**, a organização pode adicionar, editar ou remover até
30 movimentos por categoria, usando cargas em kg ou variações em texto. O Flyway
aplica **V22** para persistir essas tabelas; categorias existentes começam sem tabela.

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
| `fn_search_championships` | Busca sem acento e tolerante a erro de digitação (`unaccent` + `pg_trgm`), com filtros por UF e situação antes do limite de resultados. |
| `fn_format_result` / `fn_format_duration` | Exibição do resultado: `05:42.3`, `5 rds + 12`, `120 kg`, `CAP + 87 reps`. |
| `fn_registration_is_open`, `fn_category_slots_left` | Janela de inscrição (sempre fecha à 0h do dia de início, horário de Brasília) e vagas restantes. |
| `fn_cpf_is_valid` | Dígitos verificadores do CPF. |

**Procedures**

| Nome | O que faz |
|---|---|
| `sp_start_heat` | Marca a bateria como em andamento e conclui a que estava rodando. |
| `sp_generate_heats` | Distribui as equipes confirmadas em baterias de até 8 equipes, de forma equilibrada (17 equipes viram 5 + 6 + 6). As piores colocadas correm primeiro e os líderes na última. |
| `sp_expire_pending_payments` | Expira cobranças pendentes vencidas. |

**Triggers**

| Trigger | Regra |
|---|---|
| `trg_team_00_single_registration` | Uma inscrição não cancelada por conta e campeonato, inclusive pendente de pagamento; serializa tentativas simultâneas antes de verificar vagas. |
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
o texto escrito no banco; conflitos de unicidade, incluindo inscrição duplicada,
retornam HTTP 409 (ver `GlobalExceptionHandler`).

## API

Documentação interativa em `/swagger-ui`, disponível somente com `APP_ENV=dev`. Resumo:

| Área | Endpoints |
|---|---|
| Autenticação | `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/google` (ID token do botão do Google; no primeiro acesso sem `role` devolve `pending` para escolher o perfil), `GET /api/auth/me`, `POST /api/auth/logout` (revoga todos os tokens da conta) |
| Minha conta | `PUT /api/auth/me` (trocar o e-mail exige a senha atual), `PUT /api/auth/me/password` (revoga todas as sessões; é necessário entrar novamente), `DELETE /api/auth/me` (anonimiza a conta; recusa se houver campeonato ativo ou equipe confirmada em campeonato não encerrado) |
| Público | `GET /api/public/championships?q=&state=`, `GET /api/public/championships/{slug}`, `GET .../{slug}/schedule`, `GET .../{slug}/leaderboard?categoryId=`, `GET .../{slug}/stream` (SSE) |
| Pagamento (link público) | `GET /api/public/registrations/{código}`, `POST .../checkout` (método CARD e aceite), `GET .../checkout/{id}/details` (retomada), `GET .../checkout/{id}?paymentId=...` (situação), `GET .../payments/{id}` (histórico) |
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
    payment/      Checkout Pro, provedor desativado e verificação de webhook
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
- O token JWT fica no `sessionStorage` da aba, com fallback em memória quando o
  armazenamento está bloqueado. Isso reduz persistência, mas scripts da mesma
  origem ainda podem lê-lo. A evolução para cookie `HttpOnly` exige proteção CSRF
  e revisão do protocolo de autenticação.
- Não há envio de e-mail (confirmação de inscrição, lembrete de bateria).
- Testes de integração das triggers e procedures com Testcontainers seriam o
  próximo passo natural de qualidade.

## Carregamento e segurança

O frontend mostra um círculo de 14 px, sem texto visível, usando o acento do perfil:
verde para atleta e laranja para organizador. Trocas de páginas e abas mostram o
indicador no centro da janela antes de exibir as informações, inclusive com dados
em cache. O conteúdo fica oculto e sem interação por pelo menos 550 ms na
navegação, e até terminar o carregamento inicial ou a ação em primeiro plano.
Consultas e formulários permanecem montados para concluir o trabalho e preservar
seus estados. A toolbar continua disponível durante a espera.
Abas têm transições curtas, navegação por
teclado e suporte a `prefers-reduced-motion`. Consultas de pagamento em segundo
plano não interrompem a navegação. A toolbar usa hover neutro e um traço de cor
na página ativa.

O pagamento é feito no checkout hospedado do Mercado Pago, na mesma aba.
A inscrição acompanha o resultado confirmado pela API. A autorização de
recebimentos no Mercado Pago redireciona e retorna na mesma aba; a conta é
conferida pela API.

Na atualização de segurança, os JWTs validam emissor, perfil, conta ativa e versão
da sessão. Alterar ou criar a senha revoga os tokens anteriores; o Flyway aplica
`V13__token_revocation.sql` automaticamente. O navegador descarta o token legado
do `localStorage`, por isso usuários precisam entrar novamente após a atualização.
Ao sair, `POST /api/auth/logout` também incrementa essa versão, revoga todos os
tokens da conta e cancela autorizações Mercado Pago pendentes. Cache, requisições,
formulários e dados WODZ em ambos os armazenamentos do navegador são descartados
ao sair ou excluir a conta. Outras abas recebem uma notificação de saída;
respostas antigas não podem restaurar a sessão. Se a rede impedir a revogação,
a limpeza local acontece e a página de login informa a pendência remota.

O proxy de produção envia cabeçalhos de segurança, impede cache de respostas da
API, valida certificados do backend HTTPS e limita tentativas de login/cadastro/Google por IP. Configure proxies/CDN
confiáveis antes de usar IP real para essa limitação. Fora de desenvolvimento,
as origens CORS precisam ser HTTPS e exatas, e a senha do banco deve ser própria
(configure `DB_PASSWORD` no Compose e preserve a credencial de volumes existentes).
Para backend com CA privada, instale a CA no proxy; não desative a validação TLS.
A API direta tem uma cota adicional por endereço TCP, limita JSON/corpos e
conexões SSE, e não usa headers encaminhados para construir URLs públicas.

A matriz de controles, evidências, dados tratados e pendências de LGPD está em
[docs/SECURITY_LGPD.md](docs/SECURITY_LGPD.md). A revisão do código não certifica
conformidade: bases legais, retenção, atendimento ao titular, fornecedores,
infraestrutura e resposta a incidentes exigem validação do responsável pelo sistema.


## Login Google e redirect_uri_mismatch

O botão usa Google Identity Services com ux_mode=redirect. A URL enviada é
**a origem da aba atual + /api/public/google/callback**. No MESMO cliente OAuth
do tipo Aplicativo da Web identificado por GOOGLE_CLIENT_ID, cadastre:

| Ambiente | Origens JavaScript autorizadas | URIs de redirecionamento autorizados |
| --- | --- | --- |
| Vite local | http://localhost:5173 | http://localhost:5173/api/public/google/callback |
| Docker local | http://localhost:8081 | http://localhost:8081/api/public/google/callback |
| Homologação | https://wodzhomolog.onrender.com | https://wodzhomolog.onrender.com/api/public/google/callback |
| Produção | https://prdwodz.onrender.com | https://prdwodz.onrender.com/api/public/google/callback |
| ngrok informado | https://probably-power-flannels.ngrok-free.dev | https://probably-power-flannels.ngrok-free.dev/api/public/google/callback |

As URLs sem caminho informadas acima pertencem ao campo **Origens JavaScript
autorizadas**. No campo **URIs de redirecionamento autorizados**, cadastre os
endereços completos da última coluna. Apenas a origem nesse campo não atende ao
login_uri enviado pelo WODZ e causa redirect_uri_mismatch. Cadastre cada ambiente
no cliente OAuth usado pelo respectivo GOOGLE_CLIENT_ID.
Protocolo, domínio, porta, caminho e barra final precisam coincidir exatamente.
/google/retorno é a página após a validação e não é o callback do Google.
Se o domínio ngrok mudar, cadastre a nova origem e callback antes de testar.
Mudar FRONTEND_URL ou liberar CORS não cadastra uma URI no Google Cloud.

Essa configuração externa exige acesso ao projeto Google Cloud; o código não
pode alterar a lista do provedor.
[Referência Google login_uri](https://developers.google.com/identity/gsi/web/reference/html-reference#login_uri).

O cadastro local pede confirmação de senha antes de enviar a criação de conta.
O login mantém e-mail/senha. Os cards oferecem detalhes e inscrição separadamente;
visitantes passam pelo login e retomam /c/{slug}/inscricao. A navegação mostra o
círculo central por 550 ms, depois revela o conteúdo com fade/deslocamento de 6 px
por 280 ms, respeitando redução de movimento.

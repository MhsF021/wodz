# Pix: investigação das contas e da configuração — 12/10/2026

**Status final: emissão Pix direta em produção funciona; Pix do Wodz com OAuth
do vendedor fictício continua recusado.** Esta rodada contém **12 POSTs de
criação de Pix**: onze falhas, sendo seis HTTP 401 e cinco HTTP 500, e uma
emissão produtiva HTTP 201, posteriormente cancelada e confirmada pela API.
Nenhum Pix foi quitado. Horários em Brasília (`America/Sao_Paulo`, UTC−03:00).

A aplicação do Vendedor foi restaurada para Checkout Pro / API de Preferências.
O controle positivo separado de saldo fictício também foi confirmado:
R$ 1,00 aprovado/acreditado entre Comprador e Vendedor fictícios pela aplicação
Marketplace. O sucesso produtivo de emissão direta e o sucesso de saldo são
evidências de fluxos diferentes; não homologam o Pix Marketplace/OAuth do Wodz.

## Resultado desta rodada

- As quatro identidades consultadas responderam HTTP 200. As três contas
  fictícias são brasileiras, ativas e identificadas pela tag `test_user`.
- As quatro aplicações responderam HTTP 200, estavam ativas, sem bloqueio ou
  desabilitação, com PKCE habilitado e a mesma URL de retorno OAuth informada.
- O novo OAuth separando Integrador e Vendedor foi concluído com HTTP 200 às
  16:06 de Brasília. A emissão Pix usando esse acesso continuou retornando
  **HTTP 401 / `Unauthorized use of live credentials`**.
- Os tokens diretos APP_USR do Integrador e do Vendedor também receberam 401.
  Os tokens TEST de ambas as aplicações fictícias e da aplicação real receberam
  **HTTP 500 / `fill and validate error list: not_found\n: 400`**.
- Alterar temporariamente o produto da aplicação do Vendedor para Checkout
  Transparente via API de Pagamentos não mudou os escopos nem esses resultados.
- O Wodz também reproduziu o 401 do provedor com o novo Vendedor, devolvendo
  502 ao cliente e preservando a inscrição de demonstração como pendente.
- **As onze emissões Pix recusadas retornaram sem payment ID, sem QR Code e
  sem imagem de QR.** As primeiras dez referências foram pesquisadas às
  16:24:45, com HTTP 200 e total zero. O décimo primeiro controle, feito depois,
  ainda não teve busca por referência registrada; não é coberto por essa auditoria.
- A conta real `1681662071` possui uma chave aleatória listada na interface.
  A identidade foi comparada com `/users/me` sem registrar o valor do e-mail.
- O décimo segundo POST usou APP_USR da aplicação real `6035581613565283`,
  sem CPF e com e-mail sintético reservado, e criou o pagamento Pix
  **`183850015666`**, R$ 1,00, pendente, com QR e imagem presentes. O próprio
  controle foi cancelado por PUT; um GET posterior confirmou `cancelled`.
  **Nenhuma cobrança real foi paga.**
- Uma preferência isolada Checkout Pro foi criada com HTTP 201, Pix permitido
  e preferência inicial por Pix. A interface ofereceu saldo e cartões, mas não
  exibiu Pix. O controle positivo de saldo dessa preferência foi aprovado.

A autenticação básica, a separação dos papéis e a seleção do produto foram
verificadas. A capacidade de emissão direta da conta real foi comprovada.
A condição específica que recusa Pix nas combinações fictícias e sandbox
testadas permanece desconhecida. Não foi demonstrada uma causa única das
falhas anteriores nem a homologação do Pix do Wodz.

## Contas, aplicações e configuração conferida

| Papel | User ID | Aplicação | Conta fictícia | `/users/me` | Aplicação ativa | PKCE | Literal `payments` nos escopos |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Integrador / Marketplace | `3758553874` | `4961044287054715` | Sim | 200 | Sim | Sim | Ausente |
| Vendedor | `3740570257` | `5387756444859415` | Sim | 200 | Sim | Sim | Ausente |
| Comprador | `3740570267` | `5670429501400048` | Sim | 200 | Sim | Sim | Ausente |
| Conta real da plataforma | `1681662071` | `6035581613565283` | Não | 200 | Sim | Sim | Presente |

Todas as contas retornaram `site_id=MLB` e `status.site_status=active`. Todas as
aplicações retornaram `active=true`, `blocked=false`, `disabled=false`,
`pkce=true` e `sandbox_mode=true`. O último campo é um dado da aplicação e não
substitui a conferência do tipo de conta, tipo de token ou modo de cada operação.

URL de retorno conferida nas quatro aplicações:

```text
https://probably-power-flannels.ngrok-free.dev/api/public/mercadopago/callback
```

Todas possuem `read`, `write`, `offline_access` e escopos compostos, inclusive
`urn:mp:online:payments/read-write`. A aplicação real possui, adicionalmente,
o nome literal `payments`, tanto na lista de escopos quanto na de roles. Nas
três aplicações fictícias esse literal não aparece. A auditoria posterior à
alteração de produto preservou essa diferença.

Essa é uma diferença observada entre as configurações, não uma prova de que
os dois nomes de permissão sejam equivalentes ou de que basta inserir o literal
para resolver. A documentação pública consultada não explica essa equivalência
nem um procedimento para ativar manualmente `payments` no painel.

Evidência: [auditoria inicial](mercadopago-accounts-config-audit-2026-10-12.json)
e [auditoria após alterar o produto](mercadopago-accounts-after-seller-product-change-2026-10-12.json).

## Fases e controles executados

As fases 2 a 7 correspondem aos dez primeiros POSTs Pix; a fase 11 acrescenta
um controle TEST recusado e a fase 12 acrescenta o controle produtivo emitido
e cancelado. A preferência Checkout Pro e o pagamento de saldo da fase 8 não
entram na contagem de POSTs Pix. Os PUTs/GETs de cancelamento também não entram.

Os artefatos abaixo guardam apenas identidades, metadados de configuração e
resultados filtrados. Não contêm valores de tokens, senhas, client secret,
e-mail do comprador, CPF, QR ou corpo bruto da resposta.

Nos nove primeiros controles isolados, o pagador foi a conta Comprador fictícia verificada `3740570267`, distinta dos
recebedores. Seu e-mail veio da identidade consultada no provedor. O CPF foi o
exemplo fornecido pelo usuário para esta rodada, sem usar documentos reais.
Cada emissão utilizou R$ 1,00 e referência própria. Os campos registrados foram
`transaction_amount`, `payment_method_id`, `description`, `external_reference`,
`date_of_expiration` e `payer`; não houve comissão nem notificação nesses
controles mínimos.

Os horários das tabelas são os horários `checked_at` do relatório correspondente,
convertidos para Brasília. Não representam a precisão de início de cada POST.

### 1. Auditoria antes das decisões — 15:49:59

Conferidos as quatro identidades, as quatro aplicações, os retornos HTTP, país,
tipo de conta, estado de ativação, PKCE, callback e escopos. O Wodz estava
configurado com a aplicação `4961044287054715` e modo de contas teste habilitado.

[Artefato da auditoria](mercadopago-accounts-config-audit-2026-10-12.json).

### 2. APP_USR direto e OAuth antigo — 15:50

| Controle | Aplicação esperada | Recebedor verificado | HTTP | Motivo |
| --- | --- | --- | --- | --- |
| Token direto do Integrador | `4961044287054715` | `3758553874` | 401 | `Unauthorized use of live credentials` |
| Token direto do Vendedor | `5387756444859415` | `3740570257` | 401 | Mesmo motivo |
| OAuth anterior do Integrador conectado como recebedor | `4961044287054715` | `3758553874` | 401 | Mesmo motivo |

As três respostas incluíram o código de causa `7` e nenhuma retornou payment
ID ou QR. O token direto serve como comparação de capacidade; não substitui o
OAuth do vendedor no fluxo Marketplace do Wodz.

Evidência: [matriz direta](mercadopago-pix-direct-matrix-2026-10-12.json) e
[OAuth antigo do Integrador](mercadopago-pix-integrator-oauth-matrix-2026-10-12.json).

### 3. TEST das aplicações fictícias — 15:57:45

Os acessos TEST retornados pelos dados das aplicações foram usados somente
depois de `/users/me` responder HTTP 200 e confirmar a identidade esperada.
Não foi renovado o refresh token do vínculo Wodz para produzir esses controles.

| Aplicação | Recebedor verificado com TEST | Identidade | Emissão Pix |
| --- | --- | --- | --- |
| `4961044287054715` | `3758553874` | HTTP 200 | HTTP 500, `not_found` interno |
| `5387756444859415` | `3740570257` | HTTP 200 | Mesmo resultado |

Mensagem filtrada: `fill and validate error list: not_found\n: 400`.
Nenhum payment ID ou QR foi retornado. Essa resposta é diferente do 401 dos
APP_USR, mas não comprova que o payload foi validado integralmente nem revela
qual recurso interno deixou de ser encontrado.

[Matriz TEST das aplicações fictícias](mercadopago-pix-sandbox-direct-matrix-2026-10-12.json).

### 4. TEST da aplicação real — 16:00:35

A aplicação real `6035581613565283` foi usada exclusivamente com seu acesso
TEST neste controle. `/users/me` respondeu HTTP 200 e confirmou o usuário
`1681662071`. Não foi enviado POST Pix usando o APP_USR produtivo dessa conta.

O resultado foi o mesmo HTTP 500 com `not_found` interno, sem payment ID ou
QR. Logo, o erro TEST também foi reproduzido fora das duas aplicações fictícias;
não ficou limitado ao perfil Integrador ou Vendedor de teste.

[Matriz sandbox da aplicação real](mercadopago-pix-real-application-sandbox-matrix-2026-10-12.json).

### 5. OAuth novo com os três papéis separados — 16:06

Conforme confirmação do agente executor, a aplicação Integrador
`4961044287054715` autorizou a conta Vendedor `3740570257` pelo fluxo OAuth com
PKCE e a troca do código foi concluída com HTTP 200 às 16:06. Foi criado o
vínculo Wodz de recebimento **conta 2**, ativo, com esse vendedor e aplicação.
O vínculo anterior **conta 1**, do Integrador `3758553874`, foi preservado como
desconectado; seus registros anteriores não foram apagados.

A emissão isolada com o OAuth recém-conectado verificou novamente o Vendedor
e o Comprador por HTTP 200. Retornou 401 com `Unauthorized use of live
credentials`, causa `7`, sem payment ID ou QR.

Esse controle mostra que a separação Integrador → Vendedor → Comprador foi
aplicada e **não resolveu sozinha a recusa Pix** nesta combinação.

[Controle com os papéis separados](mercadopago-pix-separated-roles-oauth-2026-10-12.json).

### 6. Alteração temporária do produto do Vendedor — 16:10

Conforme observação de interface do agente executor, o produto da aplicação
`5387756444859415` foi temporariamente alterado de **Checkout Pro via
Preferences** para **Checkout Transparente via API de Pagamentos**. A aplicação
foi novamente auditada às 16:10:31: permaneceu ativa, com PKCE, callback e a
mesma diferença de escopos. O literal `payments` continuou ausente.

Dois novos controles diretos foram executados após a alteração:

| Credencial do Vendedor | Horário do relatório | HTTP | Resultado |
| --- | --- | --- | --- |
| APP_USR | 16:10:34 | 401 | `Unauthorized use of live credentials`, causa `7` |
| TEST | 16:10:37 | 500 | `fill and validate error list: not_found\n: 400` |

Nenhum retornou payment ID ou QR. A mudança do produto, sozinha, não resolveu
esta aplicação. O retorno a Checkout Pro / API de Preferências foi salvo e
confirmado pela interface, que novamente mostrou Checkout Pro como produto.
Não foram alterados o callback, PKCE ou os três campos de permissões.

Evidência: [auditoria após alteração](mercadopago-accounts-after-seller-product-change-2026-10-12.json),
[APP_USR após alteração](mercadopago-pix-seller-payments-appusr-2026-10-12.json)
e [TEST após alteração](mercadopago-pix-seller-payments-test-2026-10-12.json).

### 7. Emissão pelo próprio Wodz — 16:16

A inscrição de demonstração **353**, no valor de **R$ 170,00**, não tinha
checkout anterior. O backend criou o rascunho **9** com o vínculo de recebimento
**2**, aplicação `4961044287054715` e OAuth do Vendedor `3740570257`.

O POST enviado pelo Wodz contém valor, descrição, e-mail válido do capitão de
demonstração, CPF de exemplo, notificação e vencimento de 31 minutos. A resposta
do Mercado Pago foi **401 / unauthorized_live_credentials**; o Wodz devolveu
**502**. O rascunho foi marcado como emissão recusada, sem payment ID. A equipe
permaneceu `PENDING_PAYMENT` e o total de pagamentos locais é zero.

O e-mail desse capitão não corresponde ao Comprador fictício do Mercado Pago.
Essa diferença não explica sozinha a falha: o controle isolado da fase 5 usou o
e-mail confirmado do Comprador e recebeu o mesmo 401.

- Request ID do provedor: `6b9de9a0-6663-4e53-b00a-f12007a74f0a`.
- Referência: `45b8ecfd-604d-4147-9862-25e0f2911192`.
- Correlação do backend: `0851e83c-00fe-4492-8d85-0717314b812e`.

[Resultado do fluxo Wodz](mercadopago-pix-wodz-separated-roles-2026-10-12.json).

### 8. Checkout Pro com Pix permitido — 16:19

Uma preferência isolada de **R$ 1,00** foi criada com HTTP 201 usando o OAuth
do Vendedor pela aplicação do Integrador. O provedor confirmou collector e
client ID esperados. Não há exclusão de Pix; `default_payment_method_id=pix`.

- Preferência: `3740570257-d9561106-4a13-4f29-b4e5-aa6131140a44`.
- Referência: `wodzhostpix-a7eb14d1-abe9-4a5f-9656-1d8f41450328`.
- Request ID de criação: `cbbd5fc8-6499-4914-935a-6b3ad69d4854`.
- Comprador autenticado: conta fictícia `3740570267`.

Na lista completa de meios, o checkout mostrou saldo, cartões salvos, novo
cartão e débito virtual CAIXA. **Pix não apareceu**. Isso registra a oferta
observada para esta preferência; não prova qual regra interna removeu Pix.

Foi preparado um controle positivo de R$ 1,00 com saldo fictício. A revisão
automática rejeitou o clique final em Pagar e exigiu conclusão manual pelo
usuário. O usuário informou que não via a aba; foi disponibilizado o link do
checkout. Uma consulta posterior encontrou o pagamento e a API de detalhe
confirmou sua aprovação. O agente não repetiu nem contornou o clique rejeitado.

O pagamento **182790974655** foi criado e aprovado às **16:33:58** de Brasília.
A verificação feita às 16:39 confirmou **R$ 1,00 / BRL**, método
`account_money`, status `approved/accredited`, recebedor `3740570257` e
pagador `3740570267`. Ambas as identidades haviam sido verificadas como
`test_user`. O `live_mode=true` da resposta não transforma essas identidades
fictícias em contas reais. A preferência pertence à aplicação Marketplace
`4961044287054715`. O pagamento não foi associado a nenhuma inscrição Wodz.

Esse controle comprova que **o Vendedor fictício separado recebe pelo Checkout
Pro da aplicação Marketplace com saldo**. Para Pix, os controles ainda falharam
e não identificam a regra interna de habilitação desse meio.

[Preferência isolada](mercadopago-pix-hosted-preference-2026-10-12.json) e
[tela do controle preparado](mercadopago-controle-saldo-pronto-2026-10-12.png).
[Pagamento positivo verificado](mercadopago-positive-control-confirmed-2026-10-12.json).
[Tela do pagamento aprovado](mercadopago-controle-saldo-aprovado-2026-10-12.png).

### 9. Chaves Pix e catálogo de métodos

Na conta Vendedor, a navegação oficial Pix → Minhas chaves retornou **Não foi
possível mostrar suas chaves**. Uma nova tentativa manteve a mensagem. Não
foi possível comprovar presença ou ausência de chave; nenhuma chave foi criada,
removida ou alterada.

O usuário concluiu o login da conta real. Na primeira leitura da área de chaves
apareceu somente a opção de cadastro; uma leitura posterior mostrou **uma chave
aleatória cadastrada**, sem aviso visível de pendência. A interface não exibiu
um rótulo explícito de status ativo. A conta da interface foi confirmada como
`1681662071` por comparação do e-mail de perfil com a identidade da API, sem
registrar seus valores. Não foi criado, removido ou alterado cadastro de chave
pelo agente. Não se deduz quando ou por quem a chave foi cadastrada.

A auditoria de chaves foi registrada às **17:16:50**. Isso comprova a chave
listada na conta real; não resolve a consulta indisponível da conta fictícia.
[Auditoria da conta real](mercadopago-conta-real-chaves-audit-2026-10-12.json) e
[chave cadastrada na interface](mercadopago-conta-real-chave-cadastrada-2026-10-12.png).

Conforme observação do executor na área pública da conta autenticada, realizada
antes do controle produtivo, a aplicação `6035581613565283` (`payment-wodz`) foi
mostrada como **Checkout Pro** e **Integração para a sua conta**. O guia
Marketplace requer o modelo Marketplace;
esse rótulo observado merece conferência antes de homologar a integração real
com vendedores terceiros. O acesso ao detalhe protegido por verificação
biométrica foi rejeitado pela revisão automática e não foi realizado. O modelo
não foi revalidado na interface às 20:45, devido à autenticação/proteção de acesso;
não se afirma que sua configuração visual permaneceu inalterada naquele horário. Não se
atribui a esse modelo a causa das recusas fictícias: o controle produtivo da
fase 12 funcionou usando essa aplicação.
[Registro histórico do modelo observado](mercadopago-real-application-ui-model-2026-10-12.json).

O endpoint `/v1/payment_methods` retornou HTTP 200, sem `pix`, nas três contas
fictícias e na conta real com APP_USR. Também omitiu `pix` nos TEST do Integrador,
Vendedor e aplicação real. O catálogo APP_USR tinha 11 entradas e o TEST, 19.
Como a ausência se repete na conta real, esse catálogo sozinho não isola o
perfil de teste nem comprova a falta de chave ou a habilitação de um recebedor.

[Comparação dos catálogos](mercadopago-method-catalog-matrix-2026-10-12.json) e
[consulta de chaves indisponível](mercadopago-vendedor-chaves-indisponiveis-2026-10-12.png).

### 10. Busca posterior por referência — 16:24:45

As dez primeiras referências de emissão Pix foram consultadas na API. As
respostas foram HTTP 200 com total zero. A referência da preferência Checkout
Pro também estava sem pagamentos antes da conclusão manual do controle
positivo. Essa auditoria é anterior às emissões das fases 11 e 12; não significa
que todas as referências desta investigação continuem sem pagamentos.

Essas consultas foram feitas com credenciais do próprio recebedor, usando TEST
nos cenários sandbox. Não criaram nem modificaram cobranças.

[Auditoria das buscas](mercadopago-pix-final-search-audit-2026-10-12.json).

### 11. TEST da conta real com e-mail sintético reservado — 17:14:13

O guia oficial de testes Bricks diferencia meios offline do fluxo com
redirecionamento. Para offline, orienta credenciais de teste da conta real e
e-mail diferente do vendedor; também adverte contra e-mail de usuário teste no
campo do Brick, quando aplicável. Foi isolada essa variável usando o TEST da
aplicação real, e-mail sintético reservado em `example.com` e o CPF de exemplo
autorizado. Nenhum e-mail de conta fictícia foi usado nesse controle.

O script confirmou a conta real `1681662071`, a aplicação esperada
`6035581613565283` e a identidade do TEST por HTTP 200 antes do POST. O payload
continuou com R$ 1,00, CPF, vencimento de 31 minutos, descrição e referência
própria. A resposta manteve **HTTP 500** com
`fill and validate error list: not_found\n: 400`, sem payment ID ou QR.

- Request ID: `e0b1d7c1-9a8e-4b8b-86a4-eb14da286bc1`.
- Referência: `wodzmatrix-019c1fbc-2e1f-4421-ae45-36db45c2d954`.
- Busca posterior por referência: **não registrada nesta consolidação**.

A mudança do e-mail, sozinha, não resolveu esse cenário TEST. Esse resultado
não explica qual recurso interno não foi encontrado.
[Controle TEST com e-mail reservado](mercadopago-pix-real-sandbox-reserved-email-2026-10-12.json).

### 12. APP_USR da conta real: emissão e cancelamento — 20:45:12

O usuário havia autorizado os testes e foi informado antes deste controle de
uma emissão produtiva única de R$ 1,00, sem quitação, seguida de cancelamento.
A primeira revisão automática falhou por limite de uso e não executou o
controle. Após o usuário enviar Continue, a nova execução foi aprovada. O
script isolado leu o token somente do ambiente, sem alterar OAuth, inscrições,
chaves ou a configuração do Wodz.

Antes da emissão, `/users/me` confirmou por HTTP 200 o proprietário real
`1681662071`, brasileiro, ativo e sem tag `test_user`. O token foi conferido como
APP_USR da aplicação `6035581613565283`; o GET da aplicação confirmou seu ID,
atividade e ausência de bloqueio/desabilitação.

O payload usou **payer somente com e-mail sintético reservado**, diferente do
recebedor, sem e-mail da conta Comprador fictícia e **sem CPF**. Esse formato
segue o exemplo mínimo PHP do guia Pix. O valor foi R$ 1,00, com descrição
explícita de controle, referência única, idempotência e vencimento de 31 minutos.
Não foi enviada comissão ou URL de notificação.

| Operação | HTTP | Estado verificado | Request ID |
| --- | --- | --- | --- |
| POST `/v1/payments` | 201 | `pending`, QR e imagem presentes | `f8927081-dff6-4410-a084-e411142417f5` |
| GET antes de cancelar | 200 | Próprio controle, ainda `pending` | `78bd230e-339b-4fe3-8c0e-efc74348c79a` |
| PUT `/v1/payments/183850015666` | 200 | `cancelled` | `f7282696-bc0e-4b60-a83d-269a4fa641f6` |
| GET após cancelar | 200 | Próprio controle `cancelled` | `f16c4637-829f-458d-873d-b60331a4f0da` |

- Pagamento: **`183850015666`**.
- Referência: `wodzlivepix-e257a455-7f73-4a0d-928e-754af6e30e82`.
- Valor/moeda/método/modo: **R$ 1,00 / BRL / pix / `live_mode=true`**.
- Recebedor: `1681662071`.
- Resultado final: **emitido pendente e cancelado; nenhum Pix quitado**.

ID, referência, recebedor, valor, moeda, modo e método foram conferidos antes
do PUT e novamente depois dele. `application_id` não veio no objeto de
pagamento; a origem da aplicação foi comprovada pelo token e pelo GET de
`/applications/6035581613565283`, conforme a classificação do artefato.
O relatório guarda apenas presença booleana do QR/imagem, sem seus conteúdos.
Um objeto já cancelado ainda conter esses dados não muda seu estado financeiro:
a confirmação final é `cancelled`.

Esse controle comprova **a capacidade de criação direta de Pix na conta real
com essa aplicação e esse payload mínimo**, além do cancelamento do pagamento
pendente criado. Não comprova recebimento bancário, pagamento do atleta,
webhook, OAuth de um organizador real ou split Marketplace do Wodz.

Entre este controle e os controles fictícios/sandbox mudaram a conta/credencial,
as condições de recebimento e os dados do pagador. A omissão de CPF e o uso de
e-mail diferente também são variáveis concomitantes. **Não foi comprovado que
o CPF provoque o erro anterior nem que sua retirada resolva o Pix do Wodz.**

[Emissão produtiva e cancelamento verificados](mercadopago-pix-live-issuance-2026-10-12.json).
O controle foi concluído integralmente no Mercado Pago e não possui vínculo com
inscrição ou pagamento local do Wodz.

## Request IDs e referências para rastreio

| Nº | Controle | HTTP | Request ID | Referência |
| --- | --- | --- | --- | --- |
| 1 | Integrador direto APP_USR | 401 | `dd6f6e8d-a26d-4e2f-ab86-274df5f8f8d2` | `wodzmatrix-0f9ac6cb-92f4-4e17-9289-6cdefb18a174` |
| 2 | Vendedor direto APP_USR | 401 | `9a80fc31-270d-4d13-bfb2-471d65b2674e` | `wodzmatrix-75be1d45-6996-4e54-959e-14ddedf24ef6` |
| 3 | OAuth antigo do Integrador | 401 | `8d7c28b8-19aa-44fd-9924-e917f267e3ed` | `wodzmatrix-89bafbb6-95e2-4f7f-9c7a-ebfc8ecc52f3` |
| 4 | Integrador direto TEST | 500 | `fb8d3fd8-01c9-4bfb-93bb-d245435c96d2` | `wodzmatrix-0f21609b-1bcb-4b40-b36e-7d1b04b9efc6` |
| 5 | Vendedor direto TEST | 500 | `44b7d1dc-6921-4de9-bf88-bce40d6fb518` | `wodzmatrix-68a3f8b2-1fae-4bb0-b048-52dc970c671e` |
| 6 | TEST da aplicação real | 500 | `1d0b5439-f16e-486a-b207-6bd3f090dd03` | `wodzmatrix-f2bc5cfe-6d09-4e4c-b8db-df006c1106a1` |
| 7 | OAuth novo do Vendedor / app Integrador | 401 | `140ea20b-9756-4a8b-b84a-a733e82738fe` | `wodzmatrix-fc28a30c-40ca-4687-9f65-3d6386c00c51` |
| 8 | Vendedor APP_USR após alterar produto | 401 | `d3834597-558a-4826-aaac-c6571b29b02a` | `wodzmatrix-7c5683c7-5733-4bcf-bd11-1f51132d1a10` |
| 9 | Vendedor TEST após alterar produto | 500 | `b4385221-c32b-4746-a656-62705cea2f5b` | `wodzmatrix-bc3b2469-462a-43ed-b13f-05d045e451a3` |
| 10 | Wodz com OAuth do Vendedor | 401 do provedor; 502 no Wodz | `6b9de9a0-6663-4e53-b00a-f12007a74f0a` | `45b8ecfd-604d-4147-9862-25e0f2911192` |
| 11 | TEST da conta real, e-mail reservado | 500 | `e0b1d7c1-9a8e-4b8b-86a4-eb14da286bc1` | `wodzmatrix-019c1fbc-2e1f-4421-ae45-36db45c2d954` |
| 12 | APP_USR da conta real, Pix emitido/cancelado | 201 na criação; 200 no cancelamento | `f8927081-dff6-4410-a084-e411142417f5` | `wodzlivepix-e257a455-7f73-4a0d-928e-754af6e30e82` |

## O que está comprovado e o que permanece hipótese

| Ponto | Evidência | Limite da conclusão |
| --- | --- | --- |
| Contas/aplicações consultáveis e ativas | HTTP 200, país e indicadores da auditoria | Não comprova autorização para criar Pix |
| PKCE e callback presentes | Quatro aplicações auditadas; OAuth novo concluído | Uma configuração OAuth válida não garante capacidade de todos os endpoints |
| Recusa Pix com APP_USR | Cinco POSTs isolados com HTTP 401 e causa `7`, mais o 401 reproduzido pelo Wodz | Não houve validação integral do payload financeiro |
| Controles TEST | Cinco POSTs com HTTP 500 e mesma mensagem interna, incluindo e-mail reservado da fase 11 | Não identifica o recurso ausente nem homologa sandbox |
| Três papéis separados | OAuth novo do Vendedor da app Integrador, Comprador distinto | Separar papéis não resolveu sozinho esta emissão |
| Produto alterado para Payments | Confirmação de interface, auditoria e dois POSTs posteriores | Selecionar o produto não resolveu sozinho; não exclui outras condições do provedor |
| Diferença de permissão literal `payments` | Ausente nas apps fictícias e presente na real | Correlação; equivalência com URNs e concessão efetiva não estão documentadas |
| Chave Pix da conta real | Uma chave aleatória listada na interface, proprietário confirmado | Não comprova chave/habilitação nas contas fictícias |
| Chave Pix do Vendedor fictício | Consulta Minhas chaves indisponível, inclusive após nova tentativa | Ausência não comprovada; não deduzir do 401 nem do `not_found` interno |
| Cadastro/dados do pagador | Comprador fictício distinto nos primeiros controles; e-mail reservado nos dois novos | O sucesso mínimo sem CPF não isola o CPF como causa anterior |
| Emissão produtiva direta | APP_USR real, HTTP 201, pagamento `183850015666` pendente com QR; PUT e GET confirmam cancelamento | Não homologa OAuth/split, recebimento bancário ou fluxo completo do Wodz |
| Modelo da aplicação real | Interface mostra Checkout Pro e Integração para a sua conta | Merece conferência para Marketplace; não foi provado como causa das recusas fictícias |
| Erro exclusivo do formulário Wodz | Controles diretos à API e backend Wodz reproduzem o 401 | O bloqueio observado está na operação de criação de pagamento do provedor; não prova sua regra interna |

A classificação de autorização do 401 tem apoio na [referência oficial de
criação de pagamento](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-api-payments/create-payment/post),
que associa a mensagem à permissão `payment` e distingue os erros de e-mail,
identificação e usuários incompatíveis. A fonte não documenta a mensagem
interna `fill and validate error list: not_found` observada com TEST. Não é
possível atribuir a causa desse 500 a uma chave, payer ou cadastro específico
apenas com os resultados presentes.

## Referências oficiais e aplicação ao diagnóstico

- [Split 1:1: criar configuração](https://www.mercadopago.com.br/developers/pt/docs/split-payments/split-1-1/integration-configuration/create-configuration):
  a aplicação da plataforma usa o modelo Marketplace; o vendedor autoriza OAuth.
  A resposta exemplificada inclui `offline_access payments write`. Isso não
  define como converter os scopes compostos observados em um literal.
- [OAuth: obter Access Token](https://www.mercadopago.com.br/developers/pt/docs/security/oauth/creation):
  descreve challenge/verifier com PKCE, Redirect URI e `test_token=true` para
  credenciais sandbox. [Referência OAuth](https://www.mercadopago.com.br/developers/pt/reference/authentication/oauth/_oauth_token/post)
  documenta solicitação de `read`, `write` e `offline_access`; não respalda
  inventar um parâmetro `scope=payment` ou `scope=payments` como correção.
- [Contas de teste](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/test-accounts):
  diferencia Vendedor, Comprador e Integrador. Informa que usuários fictícios
  não acessam algumas seções, inclusive Credenciais de teste. Ausência desse
  menu na conta fictícia não demonstra configuração incompleta.
- [Pix pela Payments API](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-payments/integration-configuration/integrate-pix):
  descreve o POST, pagador, idempotência e retorno de QR. Seu exemplo PHP envia
  `payer.email` sem identificação; esse foi o formato mínimo da fase 12. O
  [guia Pix Bricks](https://www.mercadopago.com.br/developers/pt/docs/checkout-bricks/payment-brick/payment-submission/pix)
  contempla geração com credenciais de produção de usuário teste. Portanto,
  APP_USR de uma conta fictícia não é, por si só, prova de mistura de ambientes.
- [Bricks: fluxo de testes](https://www.mercadopago.com.br/developers/pt/docs/checkout-bricks/integration-test/test-payment-flow):
  para meios offline, indica credenciais de teste da conta real e e-mail diferente
  do vendedor, com retorno pendente. A orientação contra e-mail de usuário
  teste no Brick motivou o controle da fase 11, que também falhou. Não é uma
  instrução para misturar APP_USR produtivo e comprador fictício.
- [Payments: criar cancelamento](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-api-payments/create-cancellation/put):
  permite PUT com `status=cancelled` para pagamentos pendentes, em processo ou
  autorizados. O controle da fase 12 foi restrito ao próprio pagamento pendente,
  com confirmação por GET; não realizou reembolso ou quitação.
- [Pré-requisitos Payments](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-payments/prerequisites?scope=prod)
  e [requisitos de produção](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-payments/integration-test/go-to-production-requirements?scope=prod):
  exigem chave Pix cadastrada. Não descrevem um endpoint público de inspeção
  ou provisionamento de chave Pix para conta fictícia.
- [Consultar meios de pagamento](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-api/overview):
  `/v1/payment_methods` é a lista de meios disponíveis para o site. Encontrar
  `pix` ativo nesse catálogo não comprova chave ou autorização daquela conta.
- [Checkout Pro: compras de teste](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/integration-test/test-purchases):
  para meios offline, o resultado esperado pode permanecer pendente. Esse é
  o contexto do controle da fase 8 com Pix permitido no checkout do provedor.
- [Orders: compra teste Pix](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-orders/integration-test/pix):
  tem cenário próprio com valores/dados predefinidos, sem compra bancária real.
  Essas instruções se aplicam a `/v1/orders` e não são uma troca automática de
  payload para o `/v1/payments` atual do Wodz.

## Estado final e limitações

A rodada distingue dois resultados: **a emissão direta produtiva funciona e o
pagamento criado foi cancelado**, enquanto os controles fictícios com APP_USR
e os cenários TEST continuam recusados. Separar papéis e selecionar API
Pagamentos não resolveu as combinações fictícias testadas. Não foi comprovada
uma configuração pública ausente que explique todas essas recusas, nem uma
proibição universal de Pix para contas fictícias. A diferença de escopo literal
`payments` permanece uma correlação; a equivalência com URNs não foi comprovada.

O controle positivo de saldo foi confirmado pela API. Não valida todos os meios
do Checkout Pro, reembolsos ou o recebimento em produção. A referência hosted
tem um pagamento aprovado de saldo. A auditoria das dez primeiras referências
Pix retornou zero às 16:24:45; não cobre o décimo primeiro POST TEST. O décimo
segundo POST tem payment ID, e seu estado cancelado foi confirmado por GET.

Para investigar a condição interna, os request IDs acima permitem identificar
as recusas no provedor. A chave aleatória da conta real e sua capacidade de
emissão direta foram verificadas. Ainda falta comprovar o fluxo de um organizador
real conectado à aplicação Marketplace por OAuth, incluindo split quando houver
comissão, pagamento do atleta e conciliação no Wodz. O rótulo observado de
Integração para a sua conta na aplicação real deve ser conferido contra o modelo
Marketplace documentado antes dessa homologação. O detalhe protegido por
biometria não foi acessado. Este relatório não declara o Wodz pronto para
receber Pix em produção nem propõe trocar silenciosamente suas credenciais.

Nesta rodada foram ampliados/criados somente scripts de diagnóstico e seus
artefatos. Não foi alterado `.env` ou código de produção do backend/frontend.
As alterações de conta/produto e a emissão/cancelamento autorizadas estão
registradas nas fases correspondentes.

A consulta final somente leitura, às **20:49:59 de Brasília**, confirmou
`MP_TEST_MODE=true` e a aplicação Integrador `4961044287054715` preservados no
Wodz. O Vendedor `3740570257` está conectado por OAuth na conta 2, ativa. A conta
1 do Integrador `3758553874` continua desconectada, com seus registros anteriores
preservados. A equipe de demonstração 353 permanece `PENDING_PAYMENT`, com zero
pagamentos locais, checkout 9 associado à conta 2 e sem ID de pagamento do
provedor. [Estado final do Wodz](mercadopago-wodz-final-state-2026-10-12.json).

Nenhum Pix foi quitado por esta investigação; a cobrança produtiva criada foi
cancelada. O controle real não mudou o projeto para credenciais ou modo
produtivos e não confirmou inscrições.

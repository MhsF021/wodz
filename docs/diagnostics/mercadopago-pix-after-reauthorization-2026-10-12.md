# Pix após nova autorização OAuth — 12/10/2026

## Resultado

A nova autorização foi concluída, mas a emissão Pix continua recusada pela
Payments API. Um controle isolado usando **o OAuth recém-gravado** também
recebeu HTTP 401 `Unauthorized use of live credentials`, sem ID ou QR Code.
Foi encontrada e corrigida outra falha no WODZ: o transporte HTTP anterior
perdia o corpo JSON de um POST recusado com 401, produzindo `provider_error`
e impedindo a classificação da recusa explícita implementada anteriormente.

A correção do transporte preserva o motivo da recusa. Ela não concede
permissões à aplicação e não comprova que a próxima emissão será aceita.

## Confirmação no WODZ às 11:13

O novo build foi aplicado. Às **11:13:27 de Brasília**, o checkout 8 recebeu
HTTP 401 e registrou corretamente `motivo=unauthorized_live_credentials`.
A mensagem específica de autorização foi exibida ao usuário. O banco gravou
`pix_creation_rejected_at` às 11:13:27.654899, sem payment ID, preservando a
inscrição pendente. Isso confirma o funcionamento da correção de transporte
e do marcador de primeira emissão recusada.

| Correlação mais recente | Valor |
| --- | --- |
| Aplicação | `4961044287054715` |
| Vendedor de teste | `3758553874` |
| Checkout | `8` |
| Referência | `3f7f718f-7f5f-49c5-8aed-341fd99c6630` |
| Request ID do provedor | `68f4b66b-6454-4f99-8c42-7f4272a4921f` |
| Correlação WODZ | `7e98709c-608a-423d-8d52-c939adb94db8` |

O vínculo OAuth continua sendo o autorizado às 10:47. Uma consulta somente
leitura às **11:26:29** retornou HTTP 200 e **zero pagamentos** na referência
do checkout 8. O pagamento `182705002681` continua `approved/accredited` na
API e `APPROVED`, com equipe `CONFIRMED`, no banco. Os
[dados filtrados da consulta](mercadopago-pix-confirmed-401-2026-10-12.json)
não contêm segredos ou dados pessoais.

O próximo passo é o suporte do Mercado Pago investigar o request ID acima e
confirmar a permissão/procedimento necessário para POST `/v1/payments` com Pix
nesta aplicação e vendedor fictício. A renovação e o build anteriores já foram
concluídos. A recusa de autorização está confirmada; a configuração específica
que precisa mudar no provedor ainda não foi comprovada.

## Autorização e tentativa do usuário

O banco confirma V27 aplicada às 10:45:48 de Brasília e o vínculo atualizado
às **10:47:02**, com aplicação `4961044287054715`, vendedor `3758553874`,
`test_account=true` e `live_mode=true`. O log do grant informa:

```text
grant=authorization_code HTTP=200 credential_type=APP_USR live_mode=true
scopes=offline_access,read,write unknown_scopes=22
```

`unknown_scopes=22` não significa 22 erros. São 22 ocorrências de nomes de
permissões fora da lista fixa que pode aparecer no log. Os nomes exatos
`payment` e `payments` não estavam presentes; nomes específicos ou compostos
podem estar entre os omitidos. O log não prova ausência de toda permissão
financeira. Não se acrescentou um parâmetro de scope não documentado.

Às **10:48:16**, o checkout 7 enviou POST `/v1/payments`, com OAuth do vendedor
e campos presentes/válidos nos diagnósticos. `application_fee_present=false`
é esperado quando a comissão é zero; o campo opcional é omitido. Às 10:48:17,
a resposta foi 401, classificada como `provider_error` pelo transporte antigo.

| Correlação | Valor |
| --- | --- |
| Referência | `4f7da0f0-c884-4477-97ca-b5cdafa90f26` |
| Request ID do provedor | `427b2339-c76a-435f-90f6-c5e8fa6fb0ca` |
| Correlação WODZ | `945d6531-7201-4896-ac92-0f7f59bd1995` |
| Validade do rascunho | 12/10 às 11:19:16, Brasília |

O rascunho não tem payment ID nem marcador de recusa, porque o motivo não
foi recuperado na primeira emissão. Continua sendo tratado conservadoramente
até vencer. A consulta de pagamentos às 10:52 retornou HTTP 200 e zero
resultados para essa referência.

## Controle isolado com o mesmo OAuth novo

O script verificou `/users/me` antes do POST: mesma conta brasileira ativa,
marcada `test_user`, mesma aplicação do vínculo. O GET da aplicação confirmou
`active=true`, `blocked=false`, `disabled=false`, PKCE habilitado.

Foi enviada uma cobrança fictícia de R$ 1,00, com CPF/e-mail fictícios de teste,
validade de 31 minutos, UUID novo e os campos mínimos documentados. Não foi
usado o token direto da aplicação como fallback. O resultado às **10:50:43**:

```text
HTTP=401
error=unauthorized
message=Unauthorized use of live credentials
payment_id=null
qr_present=false
request_id=c813ad4f-4deb-4e17-8a6a-8416208b83dd
```

Referência do controle: `a1272680-d7f3-4b40-9e11-5ef0e252d5dc`. Sua busca
posterior também retornou HTTP 200 e zero pagamentos. O teste não efetuou
pagamento, não renovou tokens e não alterou inscrições.

Dados filtrados: [controle](mercadopago-pix-after-reauthorization-2026-10-12.json)
e [consultas financeiras](mercadopago-pix-after-reauthorization-audit-2026-10-12.json).

## Falha de transporte no WODZ

Um servidor HTTP local retornou o mesmo 401 e JSON conhecido aos dois
transportes, com dados fictícios. O `SimpleClientHttpRequestFactory`, usado
pela configuração de produção, resultou em erro genérico. O
`JdkClientHttpRequestFactory` preservou o corpo e resultou em
`PixCreationRejectedException`. Cada chamada enviou exatamente um POST.

O `ResourceLimitsConfig` passa a usar o cliente HTTP do JDK, mantendo 5 segundos
para conexão e 15 segundos para leitura. Redirecionamentos HTTP automáticos
ficam desativados. O reconhecimento de recusa continua restrito ao primeiro
POST com o motivo conhecido; nenhum rascunho histórico foi reclassificado.

A alteração foi aplicada no novo build, conforme o checkout 8 acima. Ela não
muda o OAuth recém-renovado. A emissão recusada apresentou
`motivo=unauthorized_live_credentials`, a mensagem específica de permissão e
o marcador de recusa. Isso corrigiu o diagnóstico e a retomada; a geração do
QR Code ainda depende da autorização da Payments API.

Passaram **138 testes**, sem falhas, erros ou casos ignorados, nas suítes de
pagamento, gateway, persistência Pix, OAuth e vínculos, além de cinco regressões
com transporte HTTP real e servidor local: resposta 401 preservada, Pix 201,
busca GET, troca de código OAuth e ausência de redirecionamento/reenvio.
O PostgreSQL usado pelos testes é temporário; não é o banco do Docker.

## Encaminhamento ao Mercado Pago

A [referência oficial da Payments API](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-api-payments/create-payment/post)
associa `Unauthorized use of live credentials` à permissão de pagamento no
token. A [referência OAuth](https://www.mercadopago.com.br/developers/pt/reference/authentication/oauth/_oauth_token/post)
documenta valores `read`, `write` e `offline_access` para o parâmetro scope;
não se deve inventar `scope=payment` como correção. A configuração padrão
[Split 1:1](https://www.mercadopago.com.br/developers/pt/docs/split-payments/split-1-1/integration-configuration/create-configuration)
também não fixa esse parâmetro.

Texto pronto para o suporte:

> Na aplicação 4961044287054715, Checkout Pro / Marketplace, o vendedor de teste
> 3758553874 foi reautorizado em 12/10/2026 às 10:47 de Brasília por
> authorization_code + PKCE, sem override de scope. O grant retornou 200 e
> APP_USR/live_mode=true. /users/me e /v1/payments/search funcionam, mas POST
> /v1/payments com payment_method_id=pix retorna 401. Reproduzimos fora do fluxo
> de inscrição, com o mesmo OAuth recém-renovado e cobrança fictícia mínima
> de R$ 1,00: unauthorized / Unauthorized use of live credentials, sem ID ou
> QR Code. Request ID c813ad4f-4deb-4e17-8a6a-8416208b83dd, às 10:50:43.
> O POST da inscrição falhou às 10:48:17, request ID
> 427b2339-c76a-435f-90f6-c5e8fa6fb0ca. Após atualizar o tratamento HTTP,
> o novo POST Pix foi recusado às 11:13:27 com motivo reconhecido
> Unauthorized use of live credentials, request ID
> 68f4b66b-6454-4f99-8c42-7f4272a4921f, sem ID de pagamento.
> Verifiquem a permissão de criação de
> pagamentos Pix pela Payments API nesse grant e aplicação e confirmem o
> procedimento de teste compatível com vendedor fictício + OAuth APP_USR.
> A renovação já foi concluída e o Checkout Pro possui pagamento aprovado;
> precisamos identificar qual permissão ou restrição recusa o POST Pix.

## Preservação

O pagamento `182705002681` permanece `approved/accredited` na API. O banco
mantém sua confirmação. Nenhum registro ou token foi excluído e nenhum volume
Docker foi recriado pela análise. Os relatórios não contêm tokens, cookies,
CPF, e-mail, QR Code ou respostas brutas.

# Checkout WODZ: Pix na plataforma e cartão no Mercado Pago

O checkout mantém o preço atual da categoria, sem acrescentar os 10% das imagens
de referência. O pagamento continua sendo recebido pelo organizador por meio do
OAuth conectado em Recebimentos. Uma comissão da plataforma já configurada é
descontada do recebimento e não aumenta o valor cobrado do atleta.

## Fluxo do atleta

1. Abra o pagamento de uma inscrição pendente e escolha **Pix (QR Code)** ou
   **Cartão / Outros**.
2. Para Pix, informe apenas **CPF do Responsável Pagador**. O servidor utiliza o
   e-mail já cadastrado pelo responsável da equipe. O CPF é validado no navegador
   e no servidor.
3. Leia os documentos em `/termos` e `/privacidade` e marque o aceite.
4. **Gerar Pix** apresenta o QR Code devolvido pelo Mercado Pago, o código Copia e
   Cola, o prazo restante e a situação do pagamento, sem sair do WODZ.
5. **Pagar no Mercado Pago** abre o Checkout Pro na mesma aba. A preferência exclui
   Pix, pois essa opção tem seu próprio fluxo no WODZ. Outros meios disponíveis
   são definidos pelo Mercado Pago; a API de preferências não permite excluir
   dinheiro em conta.
6. A inscrição é confirmada somente após a aprovação verificada pelo servidor.
   A tela consulta a situação a cada cinco segundos enquanto necessário. Retorno
   com `retorno=sucesso`, HTTP 201 ou criação do QR Code não confirma pagamento.

Uma cobrança ainda válida é retomada. A troca de método exige aguardar seu
vencimento para evitar duas cobranças pagáveis para a mesma inscrição. Pagamentos
recusados ou expirados podem ser tentados novamente antes do início do evento.

## Contrato da API WODZ

`POST /api/public/registrations/{code}/checkout` exige JSON:

```json
{"method":"PIX","payerCpf":"19119119100","termsAccepted":true}
```

O CPF acima é fictício, utilizado na documentação do provedor. Para cartão:

```json
{"method":"CARD","termsAccepted":true}
```

A resposta contém `id`, `method` e `expiresAt`. Cartão inclui `url`; Pix inclui
`qrCode` e `qrCodeBase64`, sem URL de redirecionamento. O endpoint
`GET /api/public/registrations/{code}/checkout/{checkoutId}/details` recupera a
cobrança da inscrição e consulta o provedor para retomar o QR Code. O endpoint
sem `/details` consulta e concilia a situação financeira. O código de inscrição
é uma capacidade de acesso: preserve-o e não o publique.

## Campos enviados ao Mercado Pago

O cartão usa `POST /checkout/preferences`, com `items`, `external_reference`,
`back_urls`, `auto_return`, expiração e
`payment_methods.excluded_payment_methods=[{"id":"pix"}]`. Preferências antigas
ainda válidas são atualizadas para excluir Pix sem criar outra preferência.

O Pix usa `POST /v1/payments` com:

| Campo | Origem |
| --- | --- |
| `transaction_amount` | Preço da inscrição no servidor |
| `payment_method_id` | `pix` |
| `payer.email` | Cadastro do responsável da equipe |
| `payer.identification.type` / `number` | `CPF` / CPF validado sem pontuação |
| `description` | Inscrição, evento e categoria |
| `external_reference` | Identificador único da tentativa |
| `date_of_expiration` | Prazo da cobrança, limitado ao início do evento |
| `notification_url` | URL pública configurada, quando presente |
| `application_fee` | Comissão já configurada, quando houver |

Ambos os fluxos enviam o OAuth do vendedor no cabeçalho Authorization. O Pix
envia `X-Idempotency-Key` igual à referência da tentativa. Essa chave, o valor,
prazo, descrição e os dados cifrados de emissão são salvos antes da requisição
externa, em transação independente. Retentativas preservam o payload mesmo se
o processo cair após o envio ao Mercado Pago.

Depois de um timeout, o servidor busca primeiro um Pix pendente com a mesma
referência e recupera seu QR Code. Se a busca não localizar a cobrança e o prazo
original já for inferior ao mínimo de emissão, não repete o POST nem altera a
chave ou o vencimento: aguarda conciliação ou expiração. Uma busca com mais de
uma cobrança pendente é bloqueada para evitar escolher arbitrariamente.

A validade Pix documentada é de 30 minutos a 30 dias. O WODZ usa no mínimo
31 minutos na emissão para reservar margem de rede. Se o evento começar antes
desse prazo, o Pix não pode ser emitido; a opção cartão pode continuar disponível.

A resposta Pix usa `point_of_interaction.transaction_data.qr_code` e
`qr_code_base64`. O servidor verifica formato, valor, moeda, referência, vendedor,
modo financeiro, ID e vencimento. O QR é exibido somente quando a cobrança está
pendente e vigente. Webhooks e conciliação continuam consultando a API oficial
antes de confirmar a inscrição.

## Configuração e ambientes

- `PAYMENT_PROVIDER=mercadopago`, aplicação e OAuth do organizador já conectados.
- A autorização Marketplace utiliza as permissões configuradas na aplicação,
  sem fixar `scope` na URL ou na troca do código. Os escopos retornados são
  registrados por allowlist segura. Mudar esse fluxo exige nova autorização
  em Recebimentos; não altera o grant existente apenas ao reconstruir o backend.
- Para produção, vendedor real com chave Pix registrada no Mercado Pago.
- `FRONTEND_URL` deve ser pública HTTPS para os retornos do Checkout Pro.
  Pix não envia `back_urls` e não depende dessa URL de retorno.
  `MP_NOTIFICATION_URL`, quando utilizada, deve ser pública HTTPS. Configure
  também assinatura e notificações no provedor.
- Configure `APP_ENCRYPTION_KEY` e proteja banco e backups em produção.
- `MP_TEST_MODE=true` atualmente seleciona e exige contas fictícias no vínculo
  de recebimento. Não transforma o OAuth APP_USR em uma credencial TEST da
  Payments API. `test_account` e `live_mode` são informações distintas.
- O vínculo atual foi criado para Checkout Pro com `test_token=false`.
  `APP_USR` também é o prefixo documentado para as credenciais de contas
  fictícias do Checkout Pro; o prefixo sozinho não comprova mistura de ambientes.
  A emissão Pix com esse vínculo não foi homologada. As orientações de teste
  diferem entre os produtos, e a sondagem autorizada com `test_token=true`
  também falhou. Não se troca globalmente o token nem se infere `live_mode`.
- O vínculo registra a aplicação que emitiu seu OAuth (`application_id`, V26).
  Novas cobranças recusam uma autorização conhecida de outra aplicação.
  Vínculos legados precisam renovar e validar o grant antes de registrar essa
  origem. Reconexões em outra aplicação preservam o registro histórico.
- `MP_ACCESS_TOKEN` no `.env` não é utilizado pelo backend. É apenas uma
  credencial direta dos scripts de diagnóstico e pode pertencer a outra
  aplicação. Sua presença não substitui o OAuth cifrado do organizador.

## Termos e privacidade

As rotas públicas `/termos` e `/privacidade` contêm os documentos `checkout-v1`,
com contato já utilizado no rodapé do WODZ, responsabilidades da plataforma,
organizador e Mercado Pago, pré-inscrição, valores, cancelamento/reembolso,
tratamento de dados e atendimento ao titular. O aceite registra a data e versão
no checkout. Ele não concede uma autorização geral de uso de imagem.

Os textos refletem o funcionamento implementado e precisam ser validados pelo
responsável jurídico e pela operação antes do uso comercial, incluindo a
identificação legal do operador e critérios de retenção efetivamente adotados.

## Resultado da sondagem externa em 11/10/2026

A identidade do vendedor fictício brasileiro foi confirmada por `/users/me`.
Uma requisição Pix isolada, de R$ 1,00 e com dados fictícios, usando o OAuth atual
da aplicação `6035581613565283`, retornou **HTTP 500**, `internal_server_error`,
com mensagem `fill and validate error list: user_allowed_only_in_test : 400`.
Não retornou ID de pagamento nem QR Code. Referência:
`pixdiag-e600dffc-7e74-4ca8-9e07-8f2be1e91107`.

A renovação com `test_token=true` foi inicialmente bloqueada pela revisão
automática e depois autorizada explicitamente pelo usuário. Foi executada às
20:17 de Brasília: OAuth retornou HTTP 200, uma credencial `TEST` e o mesmo
vendedor. A tentativa Pix continuou retornando HTTP 500 com
`user_allowed_only_in_test`, sem ID de pagamento ou QR Code. A resposta OAuth de
sandbox não incluiu `live_mode`; esse campo não foi inferido nem usado para
alterar as verificações financeiras do WODZ.

A renovação invalidou o access token anterior. O OAuth foi restaurado com
`test_token=false`, os novos tokens foram salvos cifrados e a leitura da conta e
da preferência anterior confirmou HTTP 200, vendedor e aplicação corretos.
Uma comparação adicional com e-mail fictício no padrão
`test_payer_[digits]@testuser.com`, tanto com APP_USR quanto com TEST, teve o mesmo
resultado de erro. Após essa comparação, o OAuth APP_USR foi novamente restaurado.

Os testes usaram cobranças isoladas de R$ 1,00, sem pagamento e sem vínculo com
inscrições WODZ. Não foram utilizados tokens de outra aplicação nem modificadas
as configurações globais do projeto. Alterar somente `test_token` não resolveu
o caso testado. O motivo interno da recusa dessa combinação de aplicação,
vendedor fictício e ambiente precisa ser esclarecido pelo Mercado Pago.

Os resultados e o texto para encaminhar ao suporte estão em
[Teste Pix sandbox](diagnostics/mercadopago-pix-sandbox-2026-10-11.md).

O WODZ agora identifica esse motivo fixo na resposta de erro e apresenta uma
mensagem sobre a combinação de dados e credenciais de teste, sem atribuir a
causa ao organizador e sem registrar corpo privado, CPF, e-mail
ou credenciais. A aprovação real no provedor e o erro anterior do Checkout Pro
permanecem sem confirmação de resolução.

## Revisão de dados e credenciais em 11/10/2026

O usuário confirmou que seu teste manual bem-sucedido foi cartão/saldo com
`APP_USR`, e não geração de Pix pela Payments API. No controle registrado do
Checkout Pro, a credencial direta da app `5387756444859415` aprovou, enquanto
a preferência mínima OAuth da app WODZ `6035581613565283` também falhou fora da
plataforma. Isso concentra a comparação em aplicação e autorização; não prova
que a configuração escolhida pelo WODZ seja correta.

Um novo controle isolado às 21:10 de Brasília usou os mesmos campos de Pix,
R$ 1,00, validade de 31 minutos e vendedor fictício previamente confirmado.
A credencial direta da app 538 retornou HTTP 401, `Unauthorized use of live
credentials`; o OAuth da app 603 retornou HTTP 500, `user_allowed_only_in_test`.
Nenhum devolveu ID ou QR Code. Não houve pagamento, mudança de OAuth ou alteração
de inscrição. O teste não permite concluir que exista um campo obrigatório
ausente nem escolher outra credencial como solução.

As correções desta revisão protegem a aplicação do OAuth, recuperam cobranças
Pix após timeout, evitam repetição com prazo inválido e removem a dependência
de URL de retorno do Pix. Logs registram origem/tipo da credencial, aplicação,
presença/formato dos campos e correlação, sem conteúdo pessoal.

Evidências e limites estão na [revisão do checkout](diagnostics/mercadopago-revisao-checkout-2026-10-11.md)
e no [controle Pix com duas credenciais](diagnostics/mercadopago-pix-credential-comparison-2026-10-11.json).

## Verificação e teste local

A investigação PIX de 12/10 confirmou HTTP 401 `Unauthorized use of live
credentials` na Payments API, enquanto o Checkout Pro da app 496 continua
aprovando. As 119 regressões de OAuth, Gateway e serviços passaram após o
controle sem scope fixo e a mensagem específica de autorização. Os registros
aprovados foram preservados; a emissão PIX depende de nova autorização e
reteste pelo usuário. Veja o [diagnóstico PIX 401](diagnostics/mercadopago-pix-401-2026-10-12.md).

A suíte do backend e as regressões finais verificam payloads, idempotência,
aceite/CPF, consistência financeira, retomada, isolamento da inscrição e QR
expirado. Um teste com PostgreSQL verifica a persistência independente do
rascunho Pix antes da chamada externa e após rollback da transação externa.
No frontend, testes cobrem seleção, envio, redirecionamento validado, retomada,
cópia, expiração e documentos. Typecheck e build também foram executados.

A revisão mais recente executou a suíte completa do backend: **258 testes em
35 suítes, sem falhas, erros ou testes ignorados**. A verificação anterior do
frontend passou 105 testes, typecheck e build; não houve mudança de frontend
nesta revisão. Após a última proteção do callback para origem desconhecida,
os 17 testes específicos de reconexão também passaram (259 casos distintos
nos relatórios combinados). O backend Docker foi reconstruído e atualizado, preservando o
banco. Flyway validou 26 migrações e aplicou V26 sobre V25. A API voltou a
responder com `mercadoPagoTestMode=true`; vínculos usados depois da atualização
registraram a aplicação `6035581613565283` com tokens cifrados, `test_account=true`
e `live_mode=true`.

Uma nova tentativa do usuário, às 21:35 de Brasília, enviou Pix com OAuth da
app 603 e campos presentes/válidos nos logs. Recebeu novamente HTTP 500 com
`user_allowed_only_in_test`, sem ID retornado. Isso confirma que a recusa
persistiu após as correções. Falta de aceite e CPF inválido foram anteriormente
rejeitados com HTTP 400, sem criar checkout na verificação.

No terminal WSL, para reconstruir uma instalação local:

```bash
docker compose up -d --build backend frontend
```

Depois, recarregue a página de pagamento. Para coletar apenas logs correlacionáveis:

```bash
docker compose logs --since 30m --no-color --timestamps backend \
  | grep -Ei 'Mercado Pago|Checkout Pro|Pix'
```

Não envie tokens, cookies, senhas ou arquivos HAR sem remover os dados privados.
Um QR Code de teste não deve ser pago com uma conta bancária real.

## Referências oficiais

- [Pix pela Payments API](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-payments/integration-configuration/integrate-pix?scope=prod).
- [Marketplace e application_fee](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-payments/how-tos/integrate-marketplace).
- [Meios de pagamento no Checkout Pro](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/additional-settings/payment-methods).
- [Pré-requisitos e chave Pix](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-payments/prerequisites?scope=prod).
- [Compra de teste na Payments API](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-payments/integration-test/make-test-purchase).
- [OAuth e credenciais sandbox](https://www.mercadopago.com.br/developers/pt/docs/security/oauth/creation).
- [LGPD](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm) e [CDC](https://www.planalto.gov.br/ccivil_03/leis/l8078compilado.htm).

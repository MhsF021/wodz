# Recusa Pix e bloqueio de repetição — 12/10/2026

## Evidência atual

O backend em execução já contém o reconhecimento do erro
`unauthorized_live_credentials`, o log de scopes OAuth e a autorização sem
scope fixo. O build foi confirmado pela inspeção temporária das classes do
JAR; o arquivo temporário foi removido. O backend iniciou às 10:17 de Brasília.

O vínculo ativo continua com `updated_at` de **00:56:06 de Brasília**, vendedor
`3758553874`, aplicação `4961044287054715`, `test_account=true` e `live_mode=true`.
Não foi observada uma autorização posterior. O build não troca uma credencial
OAuth já armazenada. O tipo da conta de teste e o modo financeiro do token
são verificados separadamente; `APP_USR`/`live_mode=true` não bastam para
concluir que esta conta fictícia foi misturada com uma conta real.

Os últimos rascunhos Pix gravados são os checkouts 5 e 6, criados às 02:01:56
e 02:08:31, sem ID de pagamento. A consulta somente leitura da Payments API
às 10:31:46 retornou HTTP 200 e **zero pagamentos nas duas referências**.
O checkout 6 permanece vinculado a uma inscrição pendente e venceu às 02:39:31.
O checkout 5 pertence a uma inscrição cancelada. Os logs disponíveis após a
recriação do container não permitem atribuir a primeira mensagem genérica
relatada neste turno a um POST ou a uma busca. Não se presume uma nova recusa
401 sem o log da operação.

O pagamento `182705002681` continua `approved/accredited` na API e `APPROVED`
no banco; sua equipe permanece `CONFIRMED`, com R$ 250,00.
Nenhuma cobrança, autorização ou inscrição foi alterada nesta consulta.
Dados filtrados: [consulta atual](mercadopago-pix-retry-audit-2026-10-12.json).

Os controles anteriores com o mesmo OAuth recusaram a emissão Pix com
HTTP 401 `Unauthorized use of live credentials`. A
[referência oficial da Payments API](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-api-payments/create-payment/post)
associa esse erro à falta da permissão de pagamento no token. A
[configuração oficial de Split 1:1](https://www.mercadopago.com.br/developers/pt/docs/split-payments/split-1-1/integration-configuration/create-configuration)
mostra autorização padrão e retorno `offline_access payments write`.
Renovar a autorização permite observar o grant efetivo; não garante que
o provedor concederá a permissão necessária.

## Correção no WODZ

O prazo de um novo Pix é de pelo menos 31 minutos. Como um reenvio do POST
precisa conservar o payload e ainda apresentar 30 minutos mais margem de
validade, após aproximadamente 55 segundos o rascunho não pode ser reenviado.
O bloqueio protege uma emissão que poderia ter sido processada antes de um
timeout, mas também estava prendendo uma primeira emissão explicitamente
recusada por falta de autorização.

A alteração distingue somente o HTTP 401 conhecido no **primeiro POST de um
novo rascunho**. Uma migration V27 acrescenta `pix_creation_rejected_at`, com
valor nulo para registros existentes. A recusa é gravada em uma transação
independente, preservando referência, validade, dados cifrados, valor e termos.
Em outra tentativa, uma busca remota concluída sem resultados permite criar
um novo rascunho com nova chave e prazo, ou escolher cartão.

Pagamentos pendentes ou aprovados encontrados na busca prevalecem sobre o
marcador. Timeout, 500, resposta incompleta, erro na busca e uma recusa após
um envio incerto mantêm a proteção. Rascunhos legados não são liberados
retroativamente por ausência de ID. Os logs agora distinguem falha na busca
(`operation=search`) de falha na emissão (`operation=create`), com campos
sanitizados.

## Validação e próximo teste

Passaram **133 testes**, sem falhas, erros ou casos ignorados: PaymentService,
MercadoPagoGateway, PixDraftDatabase, MercadoPagoOAuthClient, PayoutTokenService
e PayoutAccountService. O teste PostgreSQL aplicou V26→V27 em banco temporário
e comprovou a persistência do marcador após rollback externo, preservando o
rascunho. O banco do Docker não recebeu esta migration durante a análise.

No WSL, em `/mnt/c/wodz`, atualizar o backend:

```bash
docker compose up -d --build --force-recreate backend
```

No painel do organizador, em **Recebimentos → Renovar autorização**, concluir
a autorização da mesma conta fictícia `3758553874`. Aguardar o retorno com
a confirmação da conta autorizada e validada. Depois gerar Pix em uma inscrição
pendente. O checkout 6 já venceu e não exige excluir nenhum registro.

Para correlacionar a autorização e a tentativa:

```bash
docker compose logs --since 15m --no-color --timestamps backend \
  | grep -Ei 'OAuth do Mercado Pago confirmado|Mercado Pago PIX|operation=search'
```

Se o novo grant continuar recebendo a recusa 401, os scopes efetivos e o request
ID do novo POST serão a evidência para revisar com o Mercado Pago as permissões
Payments/Pix desta aplicação. Não é necessário trocar tokens por tentativa.

O arquivo HAR original e os segredos não foram copiados para este relatório.

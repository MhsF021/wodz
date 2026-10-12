# WODZ: pagamento aprovado e tentativa posterior — 12/10/2026

Foi possível rastrear o pagamento **183716936334** diretamente pela API e
comparar sua preferência com a do HAR. A comparação considera somente os
testes pela plataforma WODZ informados pelo usuário; o teste manual antigo
foi desconsiderado.

## Resultado confirmado

| Campo | Aprovado em 11/10 | Falha em 12/10 |
| --- | --- | --- |
| Aplicação da preferência | **4961044287054715** | **6035581613565283** |
| Recebedor | 3758553874 | 3758553874 |
| Pagador | 3740570267 | 3740570267 |
| Valor / moeda / método | R$ 250,00 / BRL / saldo (`account_money`) | Iguais |
| Comissão da preferência | 0 | 0 |
| Criação da preferência, Brasília | 23:07:23 | 00:19:37 |
| Vencimento, Brasília | 23:37:18 | 00:49:30 |
| Resultado financeiro | `approved` / `accredited`, 23:08:48 | Zero pagamentos na busca da preferência às 00:32 |
| Preferência | `3758553874-f49f12b9-aae7-4110-98a2-c10837419297` | `3758553874-3748fbd1-193d-4e6d-b99c-45a1b785afc8` |
| Referência | `39baeace-3ad6-456d-a6a3-e975e9638594` | `41a5b744-5731-4005-984f-737786211b4f` |

O pagamento aprovado foi criado às 23:08:47 e confirmado às 23:08:48 de
11/10/2026, com `captured=true` e `live_mode=true`. O pedido comercial
`45187208879` está fechado/pago e relaciona esse pagamento à preferência da
aplicação 496. O campo `application_id` do pagamento veio nulo; por isso, a
origem foi comprovada pelo `client_id` da preferência, obtida por meio do pedido.

As duas preferências possuem `auto_return=approved`, três URLs de retorno,
notificação, um item de R$ 250,00 e exclusão de Pix. A tentativa do HAR ocorreu
às 00:23:36–00:23:41, ainda dentro do vencimento de 00:49:30. Expiração não
explica essa tentativa específica.

O HAR registra `review → challenge (reauth) → error`, com erro recuperável
`FL-7fc6784a-0e83-432d-95f2-27b938a4d3c7` / `service_error`. A API confirmou
zero pagamentos para essa referência. O motivo interno da reautenticação não
é explicado pela resposta pública.

## Configuração efetiva antes da correção

Foi confirmado o Client ID **6035581613565283** em quatro lugares:

- `.env` local;
- configuração resolvida pelo Docker Compose;
- ambiente do backend em execução;
- aplicação registrada no OAuth da conta de recebimento.

Portanto, a tentativa que falhou **não repetiu a aplicação que aprovou**.
Essa é uma diferença concreta; não atribuímos a causa apenas a uma mistura de
teste/produção, ao navegador ou ao payload sem evidência adicional.

O backend atual iniciou às 00:03:39 de 12/10. Os logs mostram um esquema vazio,
aplicação das 26 migrações e criação dos dados de demonstração. O histórico local
da aprovação das 23:08 não está no banco atual. A aprovação foi confirmada pelo
Mercado Pago; não foi anexada a nenhuma inscrição nova nem usada para confirmar
uma equipe diferente.

## Caminho de restauração encontrado

O `.env.example` continha a aplicação antiga e credenciais preenchidas. O README
orientava `cp .env.example .env`, que sobrescreve a configuração local se repetido.
Não foi encontrada automação executando essa cópia e não é possível afirmar
que o usuário a executou. É um mecanismo possível para a reversão observada.

## Correções aplicadas, sem reiniciar o contêiner

1. `.env` alinhado à aplicação **4961044287054715** e ao Client Secret fornecido
   pelo usuário. `MP_TEST_MODE=true`, assinatura do webhook e chave de cifra
   preservados.
2. Credenciais, Access Tokens e chave de cifra removidos do arquivo de exemplo;
   os campos correspondentes ficam vazios.
3. Instrução do README ajustada para copiar o exemplo somente se `.env` não
   existir, preservando configurações já feitas.
4. `docker compose config` confirmou que a próxima atualização carregará
   a aplicação 496 e seus segredos corretos.

O contêiner permanece com a aplicação antiga até o usuário recriá-lo. O OAuth
atual também pertence à aplicação antiga e deve ser reautorizado pelo fluxo
de Recebimentos após a atualização; nenhum token foi trocado manualmente no banco.

## Como testar agora

No WSL, em `/mnt/c/wodz`:

```bash
docker compose up -d --build --force-recreate backend
docker compose exec -T backend printenv MP_CLIENT_ID MP_TEST_MODE
```

A conferência deve mostrar `4961044287054715` e `true`. Depois, no WODZ:

1. Abra Recebimentos e use **Renovar autorização**. Para isolar a diferença
   encontrada, mantenha a mesma conta recebedora de teste que aprovou a compra.
2. Gere uma nova preferência pela plataforma; feche a aba do checkout anterior.
   Use uma inscrição de teste distinta ou aguarde o vencimento da tentativa
   anterior para evitar duas cobranças válidas para a mesma inscrição.
3. Repita primeiro Cartão / Outros usando o mesmo comprador de teste e saldo.
   Essa comparação trata Checkout Pro; geração de Pix é um teste separado.
4. Se falhar, preserve o HAR e execute:

```bash
docker compose logs --since 15m --no-color --timestamps backend \
  | grep -Ei 'Checkout Pro|Mercado Pago PIX|OAuth do Mercado Pago|Vinculação Mercado Pago'
```

É necessário confirmar `client=4961044287054715` e registrar a preferência nova.
O HTTP 201 de criação ou o HTTP 200 do checkout não confirma pagamento.

## Artefatos e verificações

- [Rastreamento filtrado da API](mercadopago-payment-trace-2026-10-12.json).
- [Análise do HAR](mercadopago-har-2026-10-12.md).
- [Campos filtrados do HAR](mercadopago-har-2026-10-12.json).
- Script somente leitura `scripts/Trace-MercadoPagoPayment.ps1`.

Consultas autenticadas a pagamento, pedido comercial, preferências e busca
financeira retornaram HTTP 200 para a conta recebedora. Sintaxe do script,
JSONs, varredura de campos sensíveis e `git diff --check` passaram. Nenhuma
cobrança foi criada ou paga, nenhum OAuth foi renovado e nenhum banco/contêiner
foi recriado por esse rastreamento. Os relatórios não contêm credenciais,
cookies, CPF, e-mail, QR Code ou respostas brutas.

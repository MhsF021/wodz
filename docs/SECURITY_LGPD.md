# Segurança e privacidade — WODZ

Revisão técnica do código em 11/10/2026, atualizada em 13/10/2026 após a remoção dos fluxos de QR Code e repasses centralizados. Este documento registra controles
encontrados, correções implementadas e decisões que dependem do responsável pelo
serviço. Uma revisão do repositório não certifica conformidade jurídica completa
nem verifica a infraestrutura efetivamente publicada.

## Dados e fluxos encontrados

| Operação | Dados tratados | Destino / acesso |
| --- | --- | --- |
| Conta | Nome, e-mail, sexo, telefone opcional, hash de senha, identificador Google, papel | `app_user`; próprio usuário e autenticação da API; sexo usado para conferir a categoria da inscrição |
| Inscrição | Nome/e-mail/CPF/data de nascimento de cada membro, gênero, camiseta; equipe/box/cidade | `team_member` e equipe; capitão e organizador autorizado |
| Resultados e agenda | Nomes da equipe e dos atletas confirmados, classificação e pontuação | Páginas, busca do leaderboard e stream públicos de campeonatos publicados; CPF, e-mail, sexo e nascimento não são expostos no leaderboard |
| Pagamento | Preferência, valor, conta recebedora e status | Checkout hospedado Mercado Pago; WODZ não coleta dados de cartão ou do pagador no checkout. Registros históricos permanecem sujeitos à política de retenção |
| Recebimentos do organizador | Identificador/descrição da conta, tokens OAuth e PKCE | Banco; tokens cifrados com AES-GCM; backend acessa Mercado Pago |
| Navegador | Bearer por aba, sinal temporário de retorno externo | `sessionStorage` e memória; saída limpa os dados WODZ dos dois armazenamentos, caches e formulários |
| Imagem do campeonato | Imagem escolhida pelo organizador | JPEG sem metadados em `championship_image`; endpoint público, inclusive enquanto o evento é rascunho. O formulário informa esse caráter público |
| Aceite do checkout | Data e versão dos termos de compra e privacidade | `checkout_session`; documentos públicos em `/termos` e `/privacidade` |
| Terceiros do frontend | IP e metadados de conexão para Google Fonts; Google Identity quando o botão é usado; redirecionamento de cartão ao Mercado Pago | Serviços externos, conforme integração habilitada |

CPF e data de nascimento são dados pessoais. A coleta obrigatória precisa de
justificativa específica; os formulários não substituem uma avaliação de
necessidade. Dados de saúde não são solicitados pelos formulários revisados;
a classificação jurídica dos dados e do contexto compete ao controlador.

## Matriz de controles

"Implementado" descreve o código deste repositório; configuração e publicação
corretas continuam necessárias.

| Camada | Controle e evidência | Situação / limite |
| --- | --- | --- |
| Autenticação | `SecurityConfig`, `AuthService`, `GoogleTokenVerifier`: BCrypt, Bearer com assinatura, expiração, emissor, sujeito, papel e versão confrontados com conta ativa; validação Google no servidor | Implementado. Primeira senha de conta Google exige nova credencial do mesmo Google sub; criação/troca de senha incrementa versão e exige novo login. MFA e recuperação de conta não estão implementados |
| Autorizações | `SecurityConfig` separa ATHLETE/ORGANIZER; `OwnershipGuard` e filtros de serviços verificam propriedade de recursos | Implementado. Rotas de React são conveniência; segurança depende das verificações da API |
| Sessão no navegador | `api/client.ts`, `AuthContext`, `AccountService.logout`: Bearer por aba; `POST /api/auth/logout` incrementa a versão no banco e invalida todos os tokens da conta; BroadcastChannel comunica saída às outras abas | Implementado. Saída/exclusão limpam os dois armazenamentos WODZ, caches, credenciais Google temporárias e formulários. Se a rede falhar, a limpeza local acontece e o login avisa que a revogação remota não foi confirmada. Fechar a aba sozinho não revoga o token; TTL padrão 12h. Scripts da mesma origem ainda podem ler o Bearer |
| Troca de usuário | Cliente aborta requisições e rejeita respostas de versão anterior; `AuthContext` cancela consultas, remove caches e remonta a árvore da sessão | Implementado. Respostas antigas de login/perfil não restauram a identidade; o retorno do cache de navegação valida a sessão antes de revelar o conteúdo |
| Cache | `fetch(cache: 'no-store')`; nginx `Cache-Control: no-store` em API/SPA; cache longo só para assets versionados | Implementado no proxy publicado. Dados de React Query ficam em memória até limpar sessão |
| CSRF/CORS | API stateless, `Authorization` explícito e `credentials: 'omit'`; lista exata de origens; validação HTTPS fora de dev | Implementado para o modelo Bearer atual. Uma migração a cookies exige implementar CSRF e atributos HttpOnly/Secure/SameSite em conjunto |
| Abuso e consumo de recursos | nginx limita autenticação por IP (10/min, burst 10); `RequestSafetyFilter` limita chamadas sensíveis diretas (120/min por endereço TCP, sem confiar em X-Forwarded-For); JSON/corpo limitados a 2 MiB; SSE limitado a 256/evento e 1024/instância | Implementado. A cota da API é compartilhada por clientes atrás do mesmo proxy; não substitui a cota externa. Mapa de IPs é limitado e expira. CDN/LB exige IP real de proxies explicitamente confiáveis; ataques distribuídos e cotas entre instâncias exigem proteção operacional |
| Cabeçalhos | nginx: nosniff, SAMEORIGIN, no-referrer, HSTS, permissões mínimas, CSP da SPA sem script inline; callback Google conserva sua CSP com nonce | Implementado. `connect-src` aceita HTTPS para APIs configuráveis; CSP e integrações Google/Mercado Pago ainda exigem teste no ambiente publicado |
| Transporte | URLs públicas/origens HTTPS fora de dev; headers encaminhados não alteram URLs da API; nginx verifica certificado do backend HTTPS e usa CA do sistema | Implementado no código. TLS do site/banco remoto, terminação efetiva de HSTS e segmentação precisam ser verificados na infraestrutura. `BACKEND_URL` com certificado privado exige instalar sua CA confiável |
| Segredos | `EnvironmentGuard` rejeita valores de desenvolvimento fora de dev; `TokenCipher` cifra tokens OAuth; contextos Docker excluem `.env`; Compose aceita `DB_PASSWORD` e `JWT_TTL` | Implementado. Cofre, permissões, rotação e proteção dos volumes/backups dependem da operação |
| Logs e erros | Access log nginx só tem horário/método/status/tamanho/duração; backend evita corpo cru de erro do provedor e stacks de SQL/HTTP | Implementado nessa saída. `error_log` nginx e logs de proxy/CDN podem conter URI/IP/código de inscrição/OAuth: redigir, restringir acesso e definir retenção antes da produção |
| Pagamentos | `PayoutAccountService`, `MercadoPagoSignatureVerifier`, `PaymentService`: PKCE/state, assinatura de webhook e confirmação consultando provedor; logout cancela autorizações pendentes e requisição antiga não inicia nova conexão | Implementado no código e testes. Cartão hospedado, sem coleta de cartão pelo WODZ; sem emissão de QR Code pela API de pagamentos. Homologação com provedor, escopos, conciliação e operação continuam necessários |
| Link de pagamento | Novos códigos da aplicação usam SecureRandom, 26 caracteres/130 bits; fallback SQL usa UUID v4 aleatório; DTOs públicos limitam dados | Implementado para novos links via V20. Links antigos são preservados e não ganham aleatoriedade retroativamente. Link continua capacidade compartilhável: quem o recebe pode consultar/retomar a cobrança. Expiração/reemissão e autenticação do destinatário são evoluções pendentes |
| Perfil/exclusão | `AccountService`: edição do próprio perfil, confirmação adicional para e-mail/exclusão; anonimiza `app_user` e remove vínculo/tokens de recebimentos | Parcial. Não elimina membros de equipes, inscrições/resultados nem cópias de backup |
| Google desvinculado | `DELETE /api/auth/me/google` exige ID token validado da mesma identidade; com senha remove vínculo, bloqueia autoassociação e revoga sessões; sem senha encerra conta pelas restrições existentes | Implementado no backend. A revogação do consentimento no Google é uma segunda operação; falha externa não restaura o vínculo WODZ |
| Categorias | Validação no serviço e triggers PostgreSQL diferidas (V18) exigem tamanho e composição declarados; serviço também compara o sexo do capitão com sua conta (V28) | Implementado. Sexo é declarado pelo usuário; o sistema não verifica identidade civil nem deduz sexo por nome. Google ID token não fornece essa informação; o primeiro cadastro pede o dado quando ainda não informado |
| Upload | `ChampionshipImages` limita bytes/dimensões antes de decodificar, regrava JPEG sem metadados e só aceita JPEG/PNG no servidor; propriedade conferida no serviço | Implementado e testado. WebP é convertido no navegador. O endpoint não aceita HTML/SVG e não busca URLs no servidor |
| Dependências | Lockfile/npm ci; auditorias npm e OSV das dependências Maven de runtime; versões corrigidas no pom e nginx atualizado | npm: zero alertas; OSV: dois alertas Spring MVC sem condições de exploração identificadas no código atual, detalhados abaixo. Não é certificação de ausência de falhas; imagens Docker ainda precisam de análise após build |

## Pendências de privacidade prioritárias

1. **Transparência e atendimento** — as páginas `/termos` e `/privacidade` refletem
   os fluxos atuais e utilizam o contato existente do rodapé. Validar os textos,
   completar a identificação legal do operador e confirmar os critérios de
   retenção e a operação efetiva de atendimento antes do uso comercial.
2. **Bases e minimização** — registrar operações e definir fundamentos por fluxo,
   inclusive inscrição de terceiros pelo capitão, publicação de participantes e
   CPF/data de nascimento obrigatórios. Definir tratamento de menores antes de
   admitir esse público. Não adicionar aceite genérico como substituto dessa
   análise.
3. **Direitos e retenção** — implementar procedimento de identidade verificada
   para acesso integral, correção e eliminação de dados de inscrição de cada
   membro. `team_member` não tem ligação de identidade verificada com a conta;
   apagar pela coincidência de e-mail/CPF pode afetar terceiro. Definir prazos e
   justificativas para inscrições, resultados, cobranças, logs e backups.
4. **Terceiros** — documentar papéis/contratos com organizadores, Mercado Pago,
   Google e hospedagem, locais de processamento e eventuais transferências
   internacionais. Avaliar servir fontes localmente; Google Fonts hoje é
   consultado em todas as páginas.
5. **Operação** — criar plano de incidentes, responsáveis, revisão de acessos,
   trilha de ações administrativas com dados mínimos, backup cifrado e restauração
   testada. Definir descarte/reaplicação de exclusões após restauração de backup.

Os princípios, deveres de transparência, direitos do titular e segurança estão
nos arts. 6, 9, 18, 46–49 da [LGPD](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm).
A [orientação da ANPD para segurança](https://www.gov.br/anpd/pt-br/centrais-de-conteudo/materiais-educativos-e-publicacoes/guia-orientativo-sobre-seguranca-da-informacao-para-agentes-de-tratamento-de-pequeno-porte)
inclui medidas administrativas e técnicas; sua aplicação depende do porte e
risco reais do agente. A ANPD informa, como regra geral para incidentes com risco
ou dano relevante, comunicação em três dias úteis, ressalvada legislação
específica; confirme enquadramento, prazos aplicáveis e procedimento com o
responsável jurídico no [canal oficial de incidentes](https://www.gov.br/anpd/pt-br/canais_atendimento/agente-de-tratamento/comunicado-de-incidente-de-seguranca-cis).

## Verificação e implantação

### Desvinculação do Google

Login e confirmação usam redirecionamento na mesma aba. O POST de retorno GIS
valida o cookie `g_csrf_token`, a credencial assinada e o nonce da tentativa;
o frontend também exige o estado correspondente e um prazo de 15 minutos.
A credencial fica em `sessionStorage` somente durante a ponte até a rota de
retorno, que a consome e remove junto com o estado. Nada vai para URLs ou logs.
O HTML de ponte tem `no-store`, política de referência `no-referrer` e CSP com
nonce. O CORS que aceita formulário Google fica restrito ao endpoint de retorno.
Configurar a URI exata `/api/public/google/callback` em cada origem autorizada.
Contas vinculadas ao Google exibem desvinculação no lugar dos campos de senha.

O fluxo local recebe uma nova credencial Google, confirma o mesmo `sub` no
servidor e só então efetiva a operação. Não interpretar a remoção do vínculo como
exclusão de inscrições, resultados ou da própria Conta Google. A conta WODZ que
possui senha continua acessível por ela; a conta sem senha usa o encerramento
local, inclusive bloqueios por campeonatos/inscrições ativos.

Após resposta 204 da API, usar a identidade da credencial confirmada como
`login_hint` para a tentativa de revogação; não usar o e-mail editável do perfil.
Revogação exige resultado confirmado (`successful`) e prazo de espera limitado.
O helper inclui carregamento do SDK no limite de sete segundos, e cancela efeitos
tardios após esse prazo. A sessão local é limpa imediatamente após a confirmação
da API; o resultado da tentativa Google é mantido apenas em memória para a tela
de login, mesmo que o perfil seja desmontado. Enquanto a tentativa está pendente,
o botão de autenticação Google fica suspenso para evitar conflito de inicialização
do cliente GIS global.
Se SDK/rede/Google falharem, informar o êxito local e a pendência externa, com
[acesso às conexões Google](https://myaccount.google.com/connections). Não repetir
automaticamente a operação destrutiva nem abrir autenticação sem ação do usuário.

O [guia Google](https://developers.google.com/identity/gsi/web/guides/revoke) trata
revogação de compartilhamento de ID token. A
[referência GIS](https://developers.google.com/identity/gsi/web/reference/js-reference)
mais recente também alerta sobre outros escopos/clientes no mesmo projeto.
Esta integração usa Google para login; caso o projeto hospede outros clientes,
validar o alcance da revogação em homologação antes de prometer isolamento desses
serviços. `disableAutoSelect` registra saída e não substitui revogação.

### Revisão de 11/10/2026

Correções desta revisão: revogação no logout, descarte completo da sessão no
navegador, proteção contra respostas antigas e restauração de página, cancelamento
das autorizações Mercado Pago pendentes, limites de requisição/JSON/SSE, timeouts
de chamadas externas (5 s conexão/15 s leitura), validação TLS entre proxy e API,
novos links públicos com mais aleatoriedade e upload de imagem sem metadados.

As migrations **V18–V20** adicionam as regras de composição, as imagens e o tamanho
dos novos códigos. Categorias antigas ficam sem regra definida; novas inscrições
ficam indisponíveis até o organizador configurar uma regra compatível com as
equipes existentes. Não há alteração automática do gênero declarado nem remoção
de inscrições antigas. Depois de definida e havendo inscrição, a regra é fixa.

Foram auditadas 98 dependências Maven de runtime por OSV. A atualização removeu
17 dos 19 alertas inicialmente encontrados, com Spring Boot 3.5.16, Jackson
2.21.7, Tomcat 10.1.60, PostgreSQL JDBC 42.7.12, Log4j 2.25.5 e Commons Lang
3.18.0. Restam dois alertas em Spring MVC 6.2.19:

| Alerta | Condição descrita pelo fornecedor | Avaliação do código WODZ |
| --- | --- | --- |
| [CVE-2026-47890](https://spring.io/security/cve-2026-47890/) | SSE renderizando fragmentos de views com dados controlados pelo atacante | O stream envia objetos JSON de notificação com tópico/horário/ID, sem fragmentos de views. Condição não encontrada |
| [CVE-2026-47884](https://spring.io/security/cve-2026-47884/) | XsltView com rota `/**` e nome de view implícito | API REST sem XsltView, resolvedor XSLT ou renderização implícita de views. Condição não encontrada |

Esta é uma avaliação de alcance por revisão de código, não uma prova de ausência
de exploração. Os avisos do fornecedor apontam 6.2.20 sob suporte comercial ou
7.0.9 OSS como versões corrigidas. A migração da plataforma para a linha
compatível com Spring 7 deve ser planejada; não introduzir esses mecanismos de
view enquanto os alertas permanecerem. O frontend teve zero alertas no registro
oficial npm. O nginx passou de 1.27 para **1.30.5-alpine**, com tag confirmada no
registro oficial; a [página de versões nginx](https://nginx.org/en/download.html)
identifica a série estável utilizada.

Validação final: **199 testes do backend e 84 do frontend passaram**, junto da
checagem de tipos e build do frontend. As migrations V1–V20 foram aplicadas em
PostgreSQL 16.4 temporário; o teste de atualização de V19 para V20 preservou
um link existente e a view de equipes. Testes cobrem rejeição de JWT após logout, OAuth iniciado com versão antiga,
composição validada no commit PostgreSQL, permissões de imagem e rollback de
upload inválido, limpeza dos dois armazenamentos, limite mesmo com IP/caminho
forjados e liberação das cotas SSE ao encerrar conexões. A verificação visual usou
componentes reais renderizados com dados sintéticos: cards de largura uniforme
no desktop e celular (sem overflow horizontal), formulários de categoria e
imagem de perfil. Ela não substitui testes de navegação reais
com sessão, Google ou Mercado Pago.

O `nginx -t` passou com o binário oficial 1.30.5 e uma cópia da configuração
adaptada aos caminhos locais do Windows. O Docker Desktop não estava em execução;
build das imagens, validação no container Linux, testes TLS do proxy e fluxos
externos reais permanecem na validação de implantação. A
[documentação nginx](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_ssl_verify)
fundamenta a ativação de `proxy_ssl_verify` e a configuração de CA.

### Histórico de validações até 10/10/2026

Verificações locais concluídas: `npm ci`, `npm run test:security` (60 casos),
`npm run typecheck` e `npm run build`. `npm audit` no registro oficial npm reportou
zero vulnerabilidades conhecidas para o lockfile atual. Testes de segurança
exercitam armazenamento bloqueado, remoção do
token legado, logout/troca de conta durante requisição e decodificação, 401 antigo,
Bearer sem cookies, cache, ciclo do indicador de atividade e validação do retorno
de login para impedir redirecionamento externo. O daemon Docker não
estava ativo durante esta revisão: build da imagem e `nginx -t` devem ser executados
na validação de implantação.

Na revisão anterior, os 135 testes do backend passaram, com aplicação das migrations V1–V14
em PostgreSQL 16.4 embarcado. A API foi exercitada com banco sintético descartável
para confirmar revogação após senha/exclusão e restrição entre papéis. A interface
foi conferida no desktop e celular, com troca de abas, navegação por teclado e
login/logout do organizador de demonstração. Esses testes não validam os fluxos
reais de Google/Mercado Pago com credenciais de produção.

Na revisão de 10/10/2026, o navegador confirmou o círculo de 14 px sem texto no
centro da janela, em navegação de toolbar e abas internas, com as cores de
organizador e atleta logados. O conteúdo fica oculto e `inert` durante a espera,
permanece montado e é revelado ao concluir. O indicador não fica na toolbar.
Os 9 testes focados de CORS e retorno Google passaram novamente: o cabeçalho
`ngrok-skip-browser-warning` é permitido somente nas origens já autorizadas,
sem liberar cookies nem origens adicionais. O endpoint público do túnel informado
retornou HTTP 200 com `ERR_NGROK_6024` sem o cabeçalho; com ele, retornou JSON.
Testes do cliente cobrem domínios ngrok, URLs de API distintas, domínios parecidos,
respostas não JSON e transporte interrompido, sem expor conteúdo de proxy.
O Docker local estava indisponível; a atualização do frontend servido pelo túnel
ainda depende de reconstruir sua imagem no ambiente que hospeda o site.
Os testes anteriores de emissão de QR Code são históricos: esses fluxos foram removidos.
Os novos testes de retorno cobrem CSRF, nonce, CORS específico, tentativa expirada,
resposta não solicitada, remoção de credenciais temporárias e perfis Google sem
campos de senha. O fluxo externo real ainda exige homologação e a URI Google
autorizada no console do provedor.

Na revisão do retorno Mercado Pago, 45 testes focados de OAuth, conta de
recebimentos, renovação e callback passaram. Os motivos enviados ao frontend são
uma enumeração fixa; mensagens e corpos externos não aparecem em URL/log. Os
testes verificam inclusive erros de provedor contendo texto privado e preservam
o consumo único de `state`, PKCE, identidade brasileira ativa e modo configurado.
A solicitação inclui `read write offline_access` para permitir renovação do vínculo.
Respostas incompletas geram um diagnóstico restrito a nomes fixos dos campos
inválidos, status HTTP, tipo do fluxo, modo solicitado, tipo JSON de `live_mode`
e uma classificação fixa da credencial (`TEST`, `APP_USR`, `UNKNOWN`). Os testes removem cada
campo necessário e verificam que tokens, identificadores e campos externos
adicionais não aparecem nesse log. Campos vazios e tipos inválidos continuam
impedindo que credenciais incompletas sejam armazenadas.
No frontend, a confirmação aguarda a API atual; dados antigos em cache não
confirmam uma nova autorização. `code`/`state` recebidos na rota SPA são removidos
e geram orientação sobre a configuração do callback. Retornos sem resultado
classificado não são apresentados como cancelamento. A mensagem reportada no
ngrok identifica `invalid_response` por `live_mode`. O cliente aceita apenas credenciais APP_USR e um `live_mode` explicitamente
booleano ou texto exato `true`/`false`. Ausência, nulo e tipos ambíguos interrompem
a vinculação; não existe inferência para credenciais TEST. A geração e a renovação
solicitam exclusivamente `test_token=false`. Não há renovação legada alternativa.

Correção do redirecionamento Checkout Pro (10/10/2026): novas autorizações pedem
`test_token=false` para obter `APP_USR`, inclusive nos testes. O prefixo não prova
se a conta é real: antes de armazenar a autorização, o servidor valida identidade,
país, estado ativo e a tag `test_user` em `/users/me`, recusando contas reais em
modo de teste e contas de teste em produção. Essa validação também ocorre ao
renovar tokens de Checkout Pro, mantendo o modo da conta original para conciliação.
Credenciais `TEST-` são recusadas em novas cobranças e em consultas; exigem nova
autorização compatível com Checkout Pro. Os registros financeiros não são apagados.
O redirecionamento usa exclusivamente `init_point`; URLs sandbox antigas não
são reutilizadas. Não há mudança automática para cobrança real ou edição do
domínio recebido do provedor. O teste com comprador/vendedor de teste continua
exigindo validação externa no Mercado Pago.

Validação dessa correção: 63 testes focados de pagamento/OAuth no backend e 65
testes do frontend passaram, assim como checagem de tipos e build. Os casos
cobrem a URL oficial, rejeição de sandbox/TEST para novas compras, tipo de conta
verificado no provedor, renovação no modo original e conciliação de Checkout Pro.
O Docker Desktop estava indisponível e os containers não foram reconstruídos.
HTTP 303 no callback é o redirecionamento
previsto tanto para sucesso quanto para falha; HTTP 200 na SPA ou na consulta
de status não comprova vínculo.

Varredura adicional (10/10/2026): removida a geração de pagamentos APPROVED
fictícios no seed e os estilos do formulário antigo. A demonstração usa inscrição
gratuita para eventos passados/em andamento; inscrições pagas futuras permanecem
pendentes. Bancos já existentes não são alterados pelo seed. Preferências novas e
retomadas são verificadas quanto a aplicação OAuth, vendedor, referência, valor,
moeda, quantidade, taxa, URL e retorno. A retomada consulta o provedor e recusa
preferências incompatíveis. Logs de erro usam códigos fixos, sem corpo, tokens ou
URLs privadas. A tela genérica do provedor ainda exige teste externo para confirmar
a configuração da aplicação e o uso das contas oficiais de teste.

Validação da varredura: 170 testes do backend passaram, incluindo execução real
do seed e das 15 migrations em PostgreSQL temporário; 65 testes do frontend,
checagem de tipos e build passaram. O teste do seed comprova ausência de pagamentos,
inscrições pagas pendentes e funcionamento da grade demonstrativa gratuita.
O Docker Desktop estava indisponível nesta sessão; o ambiente publicado e o
pagamento completo no provedor não foram exercitados.

Antes de publicar, validar no ambiente de homologação:

- Configurar `APP_ENV=homolog`/`prod`, `SEED_DEMO=false`, segredos próprios,
  HTTPS e origens exatas. Restringir API direta ao proxy e isolar PostgreSQL.
- **Volume PostgreSQL existente:** mudar `DB_PASSWORD` no `.env` não altera a senha
  no banco já inicializado. Alterar o usuário no banco com procedimento controlado
  e atualizar a configuração correspondente; não apagar o volume de dados.
- Confirmar cabeçalhos em HTML/API/asset/401/429, bloqueio de excesso de tentativas,
  acesso entre organizadores e sessões revogadas. Com CDN/LB, conferir IP real e
  que o proxy não remove os cabeçalhos de proteção/cache.
- Executar login Google, OAuth de recebimentos e checkout hospedado do Mercado Pago no
  ambiente correto. Ajustar CSP restritiva somente com esse inventário/teste.
- Auditar logs de infraestrutura, políticas de backup, permissões do banco,
  versões das imagens e repetir análise de dependências Maven/SBOM em CI.

O uso dos cabeçalhos considera a [herança de `add_header` no nginx](https://nginx.org/en/docs/http/ngx_http_headers_module.html)
(o include é repetido nos locais com Cache-Control) e o [limitador nginx](https://nginx.org/en/docs/http/ngx_http_limit_req_module.html).
A política de referência/compatibilidade de popups deve respeitar a
[configuração oficial do Google Identity](https://developers.google.com/identity/gsi/web/guides/get-google-api-clientid).


Atualização de Checkout Pro (10/10/2026): a migração V15 separa preferências das
tentativas financeiras sem apagar registros anteriores. O servidor usa OAuth do
organizador, referência aleatória, valor calculado no backend e marketplace_fee.
Antes de aceitar status, verifica valor, BRL, referência, collector_id e live_mode.
O retorno do navegador não aprova pagamentos. Webhooks mantêm HMAC e reconsulta;
polling e conciliação recuperam retornos interrompidos. SDK, tokenização, formulário
interno, 3DS incorporado e aprovação simulada foram removidos.
Confirmar configuração externa Checkout Pro / Marketplace e Google Cloud
(callback exato da origem utilizada) antes dos testes reais de homologação.

Validação desta atualização: a suíte completa do backend passou com 151 testes,
seguida de 25 testes focados após as últimas correções (incluindo três novos
cenários de checkout). Os 65 testes do frontend, checagem de tipos e build
passaram. A migração V15 foi aplicada em PostgreSQL temporário e a API isolada
retornou os eventos de demonstração; o antigo POST de pagamento deixou de ser
uma operação pública. Os retornos reais Google/Mercado Pago ainda exigem as
configurações dos respectivos provedores e homologação. A verificação visual
não pôde ser concluída porque o navegador automático não conectou. O Docker
Desktop estava indisponível; nenhum container do sistema foi reconstruído.

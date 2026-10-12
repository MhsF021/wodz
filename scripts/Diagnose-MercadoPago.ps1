# Consulta apenas a API oficial. Nunca imprime ou grava credenciais ou dados do comprador.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9-]{1,120}$')]
    [string]$PreferenceId,
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env')
)

$ErrorActionPreference = 'Stop'
$paymentConfig = @{}
if (Test-Path -LiteralPath $EnvFile) {
    foreach ($paymentLine in Get-Content -LiteralPath $EnvFile) {
        if ($paymentLine -match '^([A-Z0-9_]+)=(.*)$') {
            $paymentConfig[$matches[1]] = $matches[2].Trim().Trim('"').Trim("'")
        }
    }
}
foreach ($paymentKey in @('MP_ACCESS_TOKEN', 'MP_DIAGNOSTIC_ACCESS_TOKEN', 'MP_CLIENT_ID', 'MP_CLIENT_SECRET', 'MP_TEST_MODE')) {
    $paymentValue = [Environment]::GetEnvironmentVariable($paymentKey)
    if (-not [string]::IsNullOrWhiteSpace($paymentValue)) { $paymentConfig[$paymentKey] = $paymentValue }
}
$paymentAccessToken = $paymentConfig['MP_DIAGNOSTIC_ACCESS_TOKEN']
if ([string]::IsNullOrWhiteSpace($paymentAccessToken)) { $paymentAccessToken = $paymentConfig['MP_ACCESS_TOKEN'] }
if ([string]::IsNullOrWhiteSpace($paymentAccessToken)) {
    throw 'Defina MP_DIAGNOSTIC_ACCESS_TOKEN na sessao com o token do vendedor desta preferencia. O comando nao altera a integracao OAuth do WODZ.'
}

function Invoke-PaymentDiagnosticApi {
    param([string]$Path, [string]$AccessToken, [object]$Body)
    try {
        $paymentRequest = @{ Uri = 'https://api.mercadopago.com' + $Path; TimeoutSec = 20 }
        if ($AccessToken) { $paymentRequest.Headers = @{ Authorization = 'Bearer ' + $AccessToken } }
        if ($Body) {
            $paymentRequest.Method = 'Post'
            $paymentRequest.ContentType = 'application/json'
            $paymentRequest.Body = ConvertTo-Json -InputObject $Body -Compress
        }
        Invoke-RestMethod @paymentRequest
    } catch {
        $paymentHttp = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
        # A mensagem original pode conter o corpo privado da API; devolve apenas HTTP e recurso.
        throw "Consulta Mercado Pago indisponivel: recurso=$Path HTTP=$paymentHttp. Confira acesso do token ao recurso e a conexao."
    }
}

$paymentPreference = Invoke-PaymentDiagnosticApi -Path ('/checkout/preferences/' + $PreferenceId) -AccessToken $paymentAccessToken
$paymentSeller = Invoke-PaymentDiagnosticApi -Path '/users/me' -AccessToken $paymentAccessToken
if ([string]$paymentSeller.id -ne [string]$paymentPreference.collector_id) {
    throw 'O token de diagnostico nao pertence ao vendedor desta preferencia. Use o token correto para consultar as tentativas.'
}
if ([string]::IsNullOrWhiteSpace($paymentPreference.external_reference)) {
    throw 'Preferencia sem external_reference: nao e possivel correlacionar as tentativas com seguranca.'
}

$paymentAttempts = @()
$paymentTotal = 0
for ($paymentOffset = 0; $paymentOffset -lt 10000; $paymentOffset += 50) {
    $paymentSearchPath = '/v1/payments/search?external_reference=' + [Uri]::EscapeDataString($paymentPreference.external_reference) + '&sort=date_created&criteria=asc&limit=50&offset=' + $paymentOffset
    $paymentSearch = Invoke-PaymentDiagnosticApi -Path $paymentSearchPath -AccessToken $paymentAccessToken
    if ($null -eq $paymentSearch.results -or $null -eq $paymentSearch.paging.total) {
        throw 'O Mercado Pago nao confirmou o resultado da busca de pagamentos.'
    }
    $paymentTotal = [int]$paymentSearch.paging.total
    foreach ($paymentAttempt in $paymentSearch.results) {
        $paymentAttempts += [ordered]@{
            id = $paymentAttempt.id
            status = $paymentAttempt.status
            status_detail = $paymentAttempt.status_detail
            live_mode = $paymentAttempt.live_mode
            amount = $paymentAttempt.transaction_amount
            currency = $paymentAttempt.currency_id
            method = $paymentAttempt.payment_method_id
            type = $paymentAttempt.payment_type_id
            created = $paymentAttempt.date_created
        }
    }
    if (@($paymentSearch.results).Count -lt 50) { break }
}

$paymentIntegrator = $null
$paymentApplication = $null
$paymentApplicationError = $null
if ($paymentConfig['MP_CLIENT_ID'] -and $paymentConfig['MP_CLIENT_SECRET']) {
    try {
        $paymentAppToken = Invoke-PaymentDiagnosticApi -Path '/oauth/token' -Body @{
            client_id = $paymentConfig['MP_CLIENT_ID']; client_secret = $paymentConfig['MP_CLIENT_SECRET']
            grant_type = 'client_credentials'; test_token = $false
        }
        if ([string]::IsNullOrWhiteSpace($paymentAppToken.access_token)) { throw 'Resposta de autenticacao incompleta.' }
        $paymentAppAccount = Invoke-PaymentDiagnosticApi -Path '/users/me' -AccessToken $paymentAppToken.access_token
        $paymentIntegrator = [ordered]@{
            test_user = @($paymentAppAccount.tags) -contains 'test_user'
            site = $paymentAppAccount.site_id
            active = $paymentAppAccount.status.site_status -eq 'active'
        }
        $paymentApp = Invoke-PaymentDiagnosticApi -Path ('/applications/' + [Uri]::EscapeDataString($paymentConfig['MP_CLIENT_ID'])) -AccessToken $paymentAppToken.access_token
        $paymentApplication = [ordered]@{ active = $paymentApp.active; blocked = $paymentApp.blocked; disabled = $paymentApp.disabled; pkce = $paymentApp.use_pkce }
    } catch { $paymentApplicationError = 'Nao foi possivel conferir a conta integradora ou os dados da aplicacao. As consultas da preferencia e dos pagamentos foram preservadas.' }
}

$paymentExpired = $null
if ($paymentPreference.expiration_date_to) {
    try {
        $paymentExpiration = $paymentPreference.expiration_date_to
        # Invoke-RestMethod pode desserializar datas JSON em DateTime. Parse desse
        # objeto converte primeiro para texto e pode trocar dia/mes entre culturas.
        $paymentExpiresAt = if ($paymentExpiration -is [DateTimeOffset] -or $paymentExpiration -is [DateTime]) {
            [DateTimeOffset]$paymentExpiration
        } else {
            [DateTimeOffset]::Parse([string]$paymentExpiration, [Globalization.CultureInfo]::InvariantCulture)
        }
        $paymentExpired = $paymentExpiresAt -le [DateTimeOffset]::UtcNow
    } catch { }
}
$paymentNotes = @()
if ($paymentTotal -eq 0) { $paymentNotes += 'Nenhuma tentativa financeira encontrada. A pagina /error/ nao informa o motivo nem comprova pagamento; investigue a sessao do comprador e a resposta da requisicao que falhou no checkout.' }
if ($paymentIntegrator -and ($paymentIntegrator.test_user -ne (@($paymentSeller.tags) -contains 'test_user'))) {
    $paymentNotes += 'Integrador e vendedor pertencem a tipos de conta diferentes (real/teste). Confira a configuracao das contas de teste do marketplace; este achado, sozinho, nao comprova a causa da falha.'
}
if ($paymentExpired) { $paymentNotes += 'A preferencia esta vencida no momento desta consulta. Isso nao prova que estava vencida na tentativa anterior. Inicie um novo checkout pelo WODZ.' }

[ordered]@{
    checked_at = [DateTimeOffset]::UtcNow.ToString('o')
    configured_test_mode = $paymentConfig['MP_TEST_MODE'] -eq 'true'
    preference = [ordered]@{
        id = $paymentPreference.id
        application_matches = [string]$paymentPreference.client_id -eq $paymentConfig['MP_CLIENT_ID']
        collector_id = $paymentPreference.collector_id
        created = $paymentPreference.date_created
        expires = $paymentPreference.expires
        expires_at = $paymentPreference.expiration_date_to
        expired_now = $paymentExpired
        marketplace_fee = $paymentPreference.marketplace_fee
        items = @($paymentPreference.items | ForEach-Object { [ordered]@{ currency = $_.currency_id; quantity = $_.quantity; unit_price = $_.unit_price } })
    }
    seller = [ordered]@{ test_user = @($paymentSeller.tags) -contains 'test_user'; site = $paymentSeller.site_id; active = $paymentSeller.status.site_status -eq 'active' }
    integrator = $paymentIntegrator
    application = $paymentApplication
    application_diagnostic_error = $paymentApplicationError
    total_payments = $paymentTotal
    payments_truncated = $paymentTotal -gt $paymentAttempts.Count
    payments = @($paymentAttempts)
    observations = @($paymentNotes)
} | ConvertTo-Json -Depth 6

# Cria somente preferencias de diagnostico para um vendedor comprovadamente de teste.
# Nao cria pagamentos, nao altera inscricoes e nunca imprime credenciais.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9]{1,40}$')]
    [string]$SellerUserId,
    [ValidateSet('minimal', 'return', 'wodz')]
    [string]$Profile = 'minimal',
    [ValidateRange(1, 210)]
    [decimal]$Amount = 210,
    [switch]$AllowDifferentApplication,
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env')
)

$ErrorActionPreference = 'Stop'
$diagnosticConfig = @{}
if (Test-Path -LiteralPath $EnvFile) {
    foreach ($diagnosticLine in Get-Content -LiteralPath $EnvFile) {
        if ($diagnosticLine -match '^([A-Z0-9_]+)=(.*)$') {
            $diagnosticConfig[$matches[1]] = $matches[2].Trim().Trim('"').Trim("'")
        }
    }
}
foreach ($diagnosticKey in @('MP_ACCESS_TOKEN', 'MP_DIAGNOSTIC_ACCESS_TOKEN', 'MP_CLIENT_ID', 'MP_TEST_MODE', 'FRONTEND_URL', 'MP_NOTIFICATION_URL')) {
    $diagnosticValue = [Environment]::GetEnvironmentVariable($diagnosticKey)
    if (-not [string]::IsNullOrWhiteSpace($diagnosticValue)) { $diagnosticConfig[$diagnosticKey] = $diagnosticValue }
}
if ($diagnosticConfig['MP_TEST_MODE'] -ne 'true') { throw 'Este diagnostico exige MP_TEST_MODE=true.' }
$diagnosticToken = $diagnosticConfig['MP_DIAGNOSTIC_ACCESS_TOKEN']
if ([string]::IsNullOrWhiteSpace($diagnosticToken)) { $diagnosticToken = $diagnosticConfig['MP_ACCESS_TOKEN'] }
if ([string]::IsNullOrWhiteSpace($diagnosticToken) -or -not $diagnosticToken.StartsWith('APP_USR-')) {
    throw 'Configure o token APP_USR do vendedor de teste em MP_DIAGNOSTIC_ACCESS_TOKEN.'
}

function Invoke-CheckoutDiagnosticApi {
    param([string]$Resource, [object]$Body)
    try {
        $diagnosticRequest = @{
            Uri = 'https://api.mercadopago.com' + $Resource
            TimeoutSec = 20
            Headers = @{ Authorization = 'Bearer ' + $diagnosticToken }
        }
        if ($Body) {
            $diagnosticRequest.Method = 'Post'
            $diagnosticRequest.ContentType = 'application/json'
            $diagnosticRequest.Body = ConvertTo-Json -InputObject $Body -Depth 6 -Compress
            $diagnosticRequest.Headers['X-Idempotency-Key'] = $Body.external_reference
        }
        Invoke-RestMethod @diagnosticRequest
    } catch {
        $diagnosticHttp = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
        throw "Diagnostico Mercado Pago indisponivel: recurso=$Resource HTTP=$diagnosticHttp. Nenhum corpo privado foi registrado."
    }
}

$diagnosticSeller = Invoke-CheckoutDiagnosticApi -Resource '/users/me'
if ([string]$diagnosticSeller.id -ne $SellerUserId -or @($diagnosticSeller.tags) -notcontains 'test_user' -or
        $diagnosticSeller.site_id -ne 'MLB' -or $diagnosticSeller.status.site_status -ne 'active') {
    throw 'O token nao confirmou o vendedor de teste brasileiro esperado. Nenhuma preferencia foi criada.'
}
$diagnosticReference = 'mpdiag-' + [Guid]::NewGuid().ToString()
$diagnosticBody = [ordered]@{
    items = @(@{ id = $diagnosticReference; title = 'Diagnostico Checkout Pro'; currency_id = 'BRL'; quantity = 1; unit_price = $Amount })
    external_reference = $diagnosticReference
}
if ($Profile -ne 'minimal') {
    $diagnosticReturn = $diagnosticConfig['FRONTEND_URL']
    $diagnosticReturnUri = $null
    if (-not [Uri]::TryCreate($diagnosticReturn, [UriKind]::Absolute, [ref]$diagnosticReturnUri) -or
            $diagnosticReturnUri.Scheme -ne 'https' -or $diagnosticReturnUri.UserInfo -or
            $diagnosticReturnUri.Query -or $diagnosticReturnUri.Fragment) {
        throw 'O perfil com retorno exige FRONTEND_URL publico HTTPS sem credenciais, query ou fragmento.'
    }
    # O pagamento de diagnostico tem referencia propria e volta para a pagina inicial.
    $diagnosticReturn = $diagnosticReturn.TrimEnd('/') + '/'
    $diagnosticBody.back_urls = @{ success = $diagnosticReturn; pending = $diagnosticReturn; failure = $diagnosticReturn }
    $diagnosticBody.auto_return = 'approved'
}
if ($Profile -eq 'wodz') {
    $diagnosticNow = [DateTimeOffset]::UtcNow
    $diagnosticBody.expires = $true
    $diagnosticBody.expiration_date_from = $diagnosticNow.ToString("yyyy-MM-dd'T'HH:mm:ss.fffzzz", [Globalization.CultureInfo]::InvariantCulture)
    $diagnosticBody.expiration_date_to = $diagnosticNow.AddMinutes(30).ToString("yyyy-MM-dd'T'HH:mm:ss.fffzzz", [Globalization.CultureInfo]::InvariantCulture)
    if (-not [string]::IsNullOrWhiteSpace($diagnosticConfig['MP_NOTIFICATION_URL'])) {
        $diagnosticBody.notification_url = $diagnosticConfig['MP_NOTIFICATION_URL']
    }
}
$diagnosticPreference = Invoke-CheckoutDiagnosticApi -Resource '/checkout/preferences' -Body $diagnosticBody
if ([string]$diagnosticPreference.collector_id -ne $SellerUserId -or
        $diagnosticPreference.external_reference -ne $diagnosticReference) {
    throw 'A preferencia de diagnostico nao confirmou vendedor ou referencia esperados.'
}
$diagnosticApplicationMatches = [string]$diagnosticPreference.client_id -eq $diagnosticConfig['MP_CLIENT_ID']
$diagnosticCheckoutUri = [Uri]$diagnosticPreference.init_point
if ($diagnosticCheckoutUri.Scheme -ne 'https' -or $diagnosticCheckoutUri.Host -ne 'www.mercadopago.com.br' -or
        $diagnosticCheckoutUri.UserInfo -or -not $diagnosticCheckoutUri.AbsolutePath.StartsWith('/checkout/')) {
    throw 'O provedor nao confirmou um endereco oficial de Checkout Pro.'
}
[ordered]@{
    created_at = [DateTimeOffset]::UtcNow.ToString('o')
    profile = $Profile
    preference_id = $diagnosticPreference.id
    external_reference = $diagnosticReference
    collector_id = $diagnosticPreference.collector_id
    client_id = $diagnosticPreference.client_id
    configured_application_matches = $diagnosticApplicationMatches
    seller_test_user = $true
    amount = $Amount
    checkout_url = $diagnosticPreference.init_point
    request_fields = @($diagnosticBody.Keys)
    expires = $diagnosticPreference.expires
    expires_at = $diagnosticPreference.expiration_date_to
    note = 'Preferencia isolada, sem vinculo com a inscricao WODZ. Use somente comprador de teste.'
} | ConvertTo-Json -Depth 4
if (-not $diagnosticApplicationMatches -and -not $AllowDifferentApplication) {
    throw 'A preferencia foi criada com outra aplicacao: os identificadores acima foram preservados. Nao confunda esse controle com o OAuth do marketplace; use -AllowDifferentApplication somente para uma comparacao intencional.'
}

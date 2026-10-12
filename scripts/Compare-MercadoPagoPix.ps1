# Controle isolado: nao paga, nao altera OAuth ou inscricoes e nao registra segredos.
[CmdletBinding()]
param(
    [ValidatePattern('^[0-9]{1,40}$')][string]$SellerUserId = '3740570257',
    [ValidateRange(1, 1)][decimal]$Amount = 1,
    [ValidatePattern('^test_[A-Za-z0-9_]+@testuser\.com$')][string]$PayerEmail = 'test_payer_123456@testuser.com',
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env'),
    [string]$ReportFile = (Join-Path $PSScriptRoot '..\docs\diagnostics\mercadopago-pix-credential-comparison-2026-10-11.json')
)
$ErrorActionPreference = 'Stop'
$comparisonStage = 'configuration'
$comparisonTokens = @{}
try {
    $comparisonConfig = @{}
    foreach ($comparisonLine in Get-Content -LiteralPath $EnvFile) {
        if ($comparisonLine -match '^([A-Z0-9_]+)=(.*)$') {
            $comparisonConfig[$matches[1]] = $matches[2].Trim().Trim('"').Trim("'")
        }
    }
    if ($comparisonConfig['MP_TEST_MODE'] -ne 'true') { throw 'test_mode_required' }
    if ([string]::IsNullOrWhiteSpace($comparisonConfig['MP_ACCESS_TOKEN'])) { throw 'direct_control_token_missing' }
    $comparisonTokens['direct_seller'] = $comparisonConfig['MP_ACCESS_TOKEN']
    $comparisonStage = 'read_stored_oauth'
    $comparisonRow = & wsl.exe -d Ubuntu --cd /mnt/c/wodz -- docker compose exec -T db psql -U wodz -d wodz -At -c "select json_build_object('test_account',test_account,'token',access_token)::text from payout_account where provider_user_id='$SellerUserId' and status='ACTIVE' order by id desc limit 1;"
    if ($LASTEXITCODE -ne 0) { throw 'database_failed' }
    $comparisonAccount = ($comparisonRow -join '') | ConvertFrom-Json
    if (-not $comparisonAccount.test_account -or -not $comparisonAccount.token.StartsWith('v1:')) { throw 'test_seller_required' }
    $comparisonKey = if ([string]::IsNullOrWhiteSpace($comparisonConfig['APP_ENCRYPTION_KEY'])) {
        [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes('wodz-dev-only-encryption-key'))
    } else { [Convert]::FromBase64String($comparisonConfig['APP_ENCRYPTION_KEY']) }
    $comparisonAes = [Security.Cryptography.AesGcm]::new($comparisonKey,16)
    $comparisonData = [Convert]::FromBase64String($comparisonAccount.token.Substring(3))
    $comparisonPlain = [byte[]]::new($comparisonData.Length-28)
    $comparisonAes.Decrypt([byte[]]$comparisonData[0..11], [byte[]]$comparisonData[12..($comparisonData.Length-17)], [byte[]]$comparisonData[($comparisonData.Length-16)..($comparisonData.Length-1)], $comparisonPlain)
    $comparisonTokens['wodz_seller_oauth'] = [Text.Encoding]::UTF8.GetString($comparisonPlain)
    $comparisonSellers = @{}
    # Confirma ambas as identidades antes de criar qualquer controle.
    foreach ($comparisonSource in @('direct_seller','wodz_seller_oauth')) {
        $comparisonStage = 'verify_' + $comparisonSource
        $comparisonSeller = Invoke-RestMethod -Uri 'https://api.mercadopago.com/users/me' -Headers @{Authorization='Bearer '+$comparisonTokens[$comparisonSource]} -TimeoutSec 20
        if ([string]$comparisonSeller.id -ne $SellerUserId -or @($comparisonSeller.tags) -notcontains 'test_user' -or $comparisonSeller.site_id -ne 'MLB' -or $comparisonSeller.status.site_status -ne 'active') { throw 'same_active_test_seller_required' }
        $comparisonSellers[$comparisonSource] = $comparisonSeller.id
    }
    $comparisonExpiration = [DateTimeOffset]::UtcNow.AddMinutes(31).ToString("yyyy-MM-dd'T'HH:mm:ss.fffzzz",[Globalization.CultureInfo]::InvariantCulture)
    $comparisonResults = @()
    foreach ($comparisonSource in @('direct_seller','wodz_seller_oauth')) {
        $comparisonStage = 'pix_' + $comparisonSource
        $comparisonReference = 'pixcmp-' + [Guid]::NewGuid().ToString()
        $comparisonBody = [ordered]@{
            transaction_amount=$Amount; payment_method_id='pix'; description='Diagnostico comparativo Pix WODZ'
            external_reference=$comparisonReference; date_of_expiration=$comparisonExpiration
            payer=@{email=$PayerEmail;identification=@{type='CPF';number='19119119100'}}
        }
        if (-not [string]::IsNullOrWhiteSpace($comparisonConfig['MP_NOTIFICATION_URL'])) { $comparisonBody.notification_url=$comparisonConfig['MP_NOTIFICATION_URL'] }
        $comparisonPercent = [decimal]::Parse($(if ($comparisonConfig['MP_PLATFORM_FEE_PERCENT']) {$comparisonConfig['MP_PLATFORM_FEE_PERCENT']} else {'0'}),[Globalization.CultureInfo]::InvariantCulture)
        if ($comparisonPercent -ne 0) { $comparisonBody.application_fee=[decimal]::Round($Amount*$comparisonPercent/100,2) }
        $comparisonToken = $comparisonTokens[$comparisonSource]
        $comparisonCredentialType = if ($comparisonToken.StartsWith('APP_USR-')) {'APP_USR'} elseif ($comparisonToken.StartsWith('TEST-')) {'TEST'} else {'UNKNOWN'}
        $comparisonEmbeddedApp = if ($comparisonToken -match '^(?:APP_USR|TEST)-([0-9]+)-') {$matches[1]} else {$null}
        $comparisonFingerprint = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($comparisonToken))).Substring(0,16)
        $comparisonResponse = Invoke-WebRequest -Uri 'https://api.mercadopago.com/v1/payments' -Method Post -ContentType 'application/json' -Headers @{Authorization='Bearer '+$comparisonToken;'X-Idempotency-Key'=$comparisonReference} -Body (ConvertTo-Json -InputObject $comparisonBody -Depth 5 -Compress) -SkipHttpErrorCheck -TimeoutSec 30
        $comparisonResponseText = if ($comparisonResponse.Content -is [byte[]]) {[Text.Encoding]::UTF8.GetString($comparisonResponse.Content)} else {[string]$comparisonResponse.Content}
        $comparisonPayment = $comparisonResponseText | ConvertFrom-Json
        $comparisonResult = [ordered]@{
            source=$comparisonSource; credential_type=$comparisonCredentialType; credential_fingerprint=$comparisonFingerprint
            token_embedded_application_id=$comparisonEmbeddedApp; configured_application_id=$comparisonConfig['MP_CLIENT_ID']
            collector_verified_before_request=$comparisonSellers[$comparisonSource]; http_status=[int]$comparisonResponse.StatusCode
            external_reference=$comparisonReference; request_fields=@($comparisonBody.Keys)
            request_summary=@{amount=$Amount;payment_method_id='pix';expires_at=$comparisonExpiration;payer_email_domain='testuser.com';payer_email_valid=$true;payer_cpf_type='CPF';payer_cpf_digits=11;application_fee=$comparisonBody.application_fee;notification_url_present=$comparisonBody.Contains('notification_url')}
            payment_id=$comparisonPayment.id; payment_status=if($comparisonPayment.id){$comparisonPayment.status}else{$null}
            response_application_id=$comparisonPayment.application_id; response_collector_id=$comparisonPayment.collector_id
            response_live_mode=$comparisonPayment.live_mode; qr_present=-not [string]::IsNullOrWhiteSpace($comparisonPayment.point_of_interaction.transaction_data.qr_code)
            qr_image_present=-not [string]::IsNullOrWhiteSpace($comparisonPayment.point_of_interaction.transaction_data.qr_code_base64)
        }
        if ($comparisonResult.http_status -ge 400) {
            $comparisonResult.safe_error_messages=@((@($comparisonPayment.message)+@($comparisonPayment.cause | ForEach-Object {$_.description})) | Where-Object {$_} | ForEach-Object {([string]$_) -replace '(?i)(APP_USR-|TEST-)[A-Za-z0-9_-]+','[redacted-token]' -replace '[^\s@]+@[^\s@]+','[redacted-email]' -replace '\b\d{11,}\b','[redacted-number]' -replace '[\r\n]+',' '})
        }
        $comparisonResults += $comparisonResult
    }
    $comparisonReport = [ordered]@{checked_at=[DateTimeOffset]::UtcNow.ToString('o');mode='verified_test_seller_only';same_business_fields_except_reference=$true;results=$comparisonResults;note='Controle isolado: nenhuma inscricao WODZ ou autorizacao alterada; nenhum pagamento efetuado.'}
    $comparisonReport | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $ReportFile -Encoding utf8
    $comparisonReport | ConvertTo-Json -Depth 6
} catch {
    [ordered]@{stage=$comparisonStage;outcome='comparison_unavailable';http_status=if($_.Exception.Response){[int]$_.Exception.Response.StatusCode}else{0}} | ConvertTo-Json -Compress
    exit 1
} finally {
    if ($comparisonAes) {$comparisonAes.Dispose()}
    foreach ($comparisonSecretBytes in @($comparisonKey,$comparisonPlain)) {if($comparisonSecretBytes){[Array]::Clear($comparisonSecretBytes,0,$comparisonSecretBytes.Length)}}
    $comparisonToken=$null;$comparisonTokens=$null;$comparisonConfig=$null;$comparisonAccount=$null
}

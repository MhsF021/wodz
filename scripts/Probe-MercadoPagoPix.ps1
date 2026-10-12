# Emissao ficticia isolada, somente apos confirmar vendedor de teste e aplicacao.
# Nao paga, nao renova OAuth e nao altera checkouts ou inscricoes.
[CmdletBinding()]
param(
    [ValidatePattern('^[0-9]{1,18}$')][string]$AccountId='1',
    [ValidatePattern('^[0-9]{1,40}$')][string]$SellerUserId='3758553874',
    [ValidateRange(1,1)][decimal]$Amount=1,
    [string]$EnvFile=(Join-Path $PSScriptRoot '..\.env'),
    [string]$ReportFile=(Join-Path $PSScriptRoot '..\docs\diagnostics\mercadopago-pix-probe-2026-10-12.json')
)
$ErrorActionPreference='Stop'
$probeStage='configuration'
function Invoke-ProbeApi {
    param([string]$Resource,[object]$Body,[string]$IdempotencyKey)
    $args=@{Uri='https://api.mercadopago.com'+$Resource;Headers=@{Authorization='Bearer '+$probeToken};SkipHttpErrorCheck=$true;TimeoutSec=20}
    if($Body){$args.Method='Post';$args.ContentType='application/json';$args.Body=ConvertTo-Json -InputObject $Body -Depth 5 -Compress;$args.Headers['X-Idempotency-Key']=$IdempotencyKey}
    $r=Invoke-WebRequest @args
    $bodyText=if($r.Content -is [byte[]]){[Text.Encoding]::UTF8.GetString($r.Content)}else{[string]$r.Content}
    [pscustomobject]@{http=[int]$r.StatusCode;body=($bodyText | ConvertFrom-Json);request_id=if([string]$r.Headers['x-request-id'] -match '^[0-9a-fA-F-]{36}$'){[string]$r.Headers['x-request-id']}else{$null}}
}
try {
    $probeConfig=@{}
    foreach($line in Get-Content -LiteralPath $EnvFile){if($line -match '^([A-Z0-9_]+)=(.*)$'){$probeConfig[$matches[1]]=$matches[2].Trim().Trim('"').Trim("'")}}
    if($probeConfig['MP_TEST_MODE'] -ne 'true'){throw 'test_account_mode_required'}
    $probeStage='stored_account'
    $row=& wsl.exe -d Ubuntu --cd /mnt/c/wodz -- docker compose exec -T db psql -U wodz -d wodz -At -c "select json_build_object('account_id',id,'seller_id',provider_user_id,'application_id',application_id,'status',status,'test_account',test_account,'access_token',access_token)::text from payout_account where id=$AccountId;"
    if($LASTEXITCODE -ne 0){throw 'database_unavailable'}
    $account=($row -join '') | ConvertFrom-Json
    if($account.status -ne 'ACTIVE' -or -not $account.test_account -or $account.seller_id -ne $SellerUserId -or $account.application_id -ne $probeConfig['MP_CLIENT_ID'] -or -not $account.access_token.StartsWith('v1:')){throw 'same_active_test_seller_application_required'}
    $probeKey=if([string]::IsNullOrWhiteSpace($probeConfig['APP_ENCRYPTION_KEY'])){[Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes('wodz-dev-only-encryption-key'))}else{[Convert]::FromBase64String($probeConfig['APP_ENCRYPTION_KEY'])}
    $probeAes=[Security.Cryptography.AesGcm]::new($probeKey,16)
    $encrypted=[Convert]::FromBase64String($account.access_token.Substring(3));$probePlain=[byte[]]::new($encrypted.Length-28)
    $probeAes.Decrypt([byte[]]$encrypted[0..11],[byte[]]$encrypted[12..($encrypted.Length-17)],[byte[]]$encrypted[($encrypted.Length-16)..($encrypted.Length-1)],$probePlain)
    $probeToken=[Text.Encoding]::UTF8.GetString($probePlain)
    if(-not $probeToken.StartsWith('APP_USR-')){throw 'credential_type_mismatch'}
    $probeStage='verify_identity'
    $me=Invoke-ProbeApi '/users/me'
    if($me.http -ne 200 -or [string]$me.body.id -ne $SellerUserId -or @($me.body.tags) -notcontains 'test_user' -or $me.body.site_id -ne 'MLB' -or $me.body.status.site_status -ne 'active'){throw 'verified_test_user_required'}
    $probeStage='application_read'
    $app=Invoke-ProbeApi ('/applications/'+$probeConfig['MP_CLIENT_ID'])
    $appSummary=[ordered]@{http=$app.http}
    if($app.http -eq 200){
        $appSummary.id=$app.body.id;$appSummary.active=$app.body.active;$appSummary.blocked=$app.body.blocked;$appSummary.disabled=$app.body.disabled;$appSummary.pkce=$app.body.use_pkce
        $appSummary.scopes=@($app.body.scopes | Where-Object {$_ -is [string] -and $_ -match '^[a-z_ -]{1,100}$'})
        $appSummary.configured_scopes=@($app.body.scopes_configured | Where-Object {$_ -is [string] -and $_ -match '^[a-z_ -]{1,100}$'})
        $appSummary.available_scope_names=@($app.body.scopes_available.PSObject.Properties.Name | Where-Object {$_ -match '^[a-z_]{1,40}$'})
    }
    $probeStage='create_isolated_pix'
    $reference=[Guid]::NewGuid().ToString()
    $expiration=[DateTimeOffset]::UtcNow.AddMinutes(31).ToString("yyyy-MM-dd'T'HH:mm:ss.fffzzz",[Globalization.CultureInfo]::InvariantCulture)
    $body=[ordered]@{transaction_amount=$Amount;payment_method_id='pix';description='Diagnostico Pix WODZ';external_reference=$reference;date_of_expiration=$expiration;payer=@{email='test_payer_123456@testuser.com';identification=@{type='CPF';number='19119119100'}}}
    if($probeConfig['MP_NOTIFICATION_URL']){$body.notification_url=$probeConfig['MP_NOTIFICATION_URL']}
    $pix=Invoke-ProbeApi '/v1/payments' $body $reference
    $safeReasons=@()
    foreach($message in @($pix.body.error,$pix.body.message)+@($pix.body.cause | ForEach-Object {$_.description})){
        if([string]$message -ceq 'Unauthorized use of live credentials'){$safeReasons+='Unauthorized use of live credentials'}
        elseif([string]$message -match 'user_allowed_only_in_test'){$safeReasons+='user_allowed_only_in_test'}
        elseif($message -is [string] -and $message -match '^[a-z_]{1,60}$'){$safeReasons+=$message}
    }
    $report=[ordered]@{checked_at=[DateTimeOffset]::UtcNow.ToString('o');mode='verified_test_account_only';account_id=$AccountId;application_id=$account.application_id;seller_id=$SellerUserId;seller_verified_test_user=$true;credential_type='APP_USR';identity_http=$me.http;application=$appSummary;pix=[ordered]@{http=$pix.http;request_id=$pix.request_id;reference=$reference;amount=$Amount;expires_at=$expiration;request_fields=@($body.Keys);payment_id=$pix.body.id;status=if($pix.body.id){$pix.body.status}else{$null};live_mode=$pix.body.live_mode;qr_present=-not [string]::IsNullOrWhiteSpace($pix.body.point_of_interaction.transaction_data.qr_code);safe_reasons=@($safeReasons | Select-Object -Unique)};note='Emissao ficticia isolada; sem pagamento, renovacao OAuth ou alteracao de inscricoes. Sem token, email, CPF, QR ou corpo bruto no relatorio.'}
    $report | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath $ReportFile -Encoding utf8
    $report | ConvertTo-Json -Depth 7
} catch {
    [ordered]@{stage=$probeStage;outcome='probe_unavailable';http=if($_.Exception.Response){[int]$_.Exception.Response.StatusCode}else{0}} | ConvertTo-Json -Compress
    exit 1
} finally {
    if($probeAes){$probeAes.Dispose()};foreach($bytes in @($probeKey,$probePlain)){if($bytes){[Array]::Clear($bytes,0,$bytes.Length)}}
    $probeToken=$null;$probeConfig=$null;$account=$null;$row=$null
}

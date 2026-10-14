# Rastreamento somente leitura: nao renova OAuth, nao cria nem paga cobrancas.
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9]{1,30}$')][string]$PaymentId,
    [ValidatePattern('^[A-Za-z0-9-]{1,120}$')][string]$PreferenceId,
    [ValidatePattern('^[0-9a-fA-F-]{36}$')][string[]]$ExternalReferences,
    [switch]$IncludeEnvDiagnostic,
    [string]$EnvFile=(Join-Path $PSScriptRoot '..\.env'),
    [string]$ReportFile=(Join-Path $PSScriptRoot '..\docs\diagnostics\mercadopago-payment-trace-2026-10-12.json')
)
$ErrorActionPreference='Stop'
$traceStage='configuration'
$traceSecrets=@()
function Get-TraceApi {
    param([string]$Resource,[string]$Token)
    try {
        $response=Invoke-WebRequest -Uri ('https://api.mercadopago.com'+$Resource) -Headers @{Authorization='Bearer '+$Token} -SkipHttpErrorCheck -TimeoutSec 20
        $bodyText=if($response.Content -is [byte[]]){[Text.Encoding]::UTF8.GetString($response.Content)}else{[string]$response.Content}
        $body=$null
        if([int]$response.StatusCode -lt 300){$body=$bodyText | ConvertFrom-Json}
        [pscustomobject]@{http=[int]$response.StatusCode;body=$body}
    } catch {
        [pscustomobject]@{http=if($_.Exception.Response){[int]$_.Exception.Response.StatusCode}else{0};body=$null}
    }
}
function Convert-TracePayment {
    param($Payment)
    [ordered]@{
        id=$Payment.id; status=$Payment.status; status_detail=$Payment.status_detail
        application_id=$Payment.application_id; collector_id=$Payment.collector_id; payer_id=$Payment.payer.id
        live_mode=$Payment.live_mode; amount=$Payment.transaction_amount; currency=$Payment.currency_id
        method=$Payment.payment_method_id; type=$Payment.payment_type_id; installments=$Payment.installments
        created=$Payment.date_created; approved_at=$Payment.date_approved; updated=$Payment.date_last_updated
        external_reference=$Payment.external_reference; order_id=$Payment.order.id; order_type=$Payment.order.type
        marketplace_fee=$Payment.marketplace_fee; captured=$Payment.captured
    }
}
function Get-TraceOrigin([string]$Value) {
    try {
        $uri=[Uri]$Value
        if($uri.IsAbsoluteUri -and $uri.Scheme -eq 'https' -and -not $uri.UserInfo){return $uri.GetLeftPart([UriPartial]::Authority)}
    } catch { }
    return $null
}
try {
    $traceConfig=@{}
    foreach($line in Get-Content -LiteralPath $EnvFile){if($line -match '^([A-Z0-9_]+)=(.*)$'){$traceConfig[$matches[1]]=$matches[2].Trim().Trim('"').Trim("'")}}
    $traceStage='stored_accounts'
    $traceRows=& wsl.exe -d Ubuntu --cd /mnt/c/wodz -- docker compose exec -T db psql -U wodz -d wodz -At -c "select json_build_object('account_id',id,'seller_id',provider_user_id,'application_id',application_id,'status',status,'test_account',test_account,'live_mode',live_mode,'access_token',access_token)::text from payout_account where access_token is not null order by id limit 20;"
    if($LASTEXITCODE -ne 0){throw 'database_unavailable'}
    $traceKey=if([string]::IsNullOrWhiteSpace($traceConfig['APP_ENCRYPTION_KEY'])){[Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes('wodz-dev-only-encryption-key'))}else{[Convert]::FromBase64String($traceConfig['APP_ENCRYPTION_KEY'])}
    $traceAes=[Security.Cryptography.AesGcm]::new($traceKey,16)
    foreach($row in $traceRows){
        $account=$row | ConvertFrom-Json
        if(-not $account.access_token.StartsWith('v1:')){throw 'invalid_encrypted_account'}
        $encrypted=[Convert]::FromBase64String($account.access_token.Substring(3))
        $plain=[byte[]]::new($encrypted.Length-28)
        $traceAes.Decrypt([byte[]]$encrypted[0..11],[byte[]]$encrypted[12..($encrypted.Length-17)],[byte[]]$encrypted[($encrypted.Length-16)..($encrypted.Length-1)],$plain)
        $traceSecrets += [pscustomobject]@{source='stored_seller_oauth';account_id=$account.account_id;stored_application_id=$account.application_id;expected_seller_id=$account.seller_id;token=[Text.Encoding]::UTF8.GetString($plain)}
        [Array]::Clear($plain,0,$plain.Length)
    }
    # Credencial de diagnostico e apenas acesso de leitura, nunca fallback do backend.
    $diagnosticToken=if($traceConfig['MP_DIAGNOSTIC_ACCESS_TOKEN']){$traceConfig['MP_DIAGNOSTIC_ACCESS_TOKEN']}else{$traceConfig['MP_ACCESS_TOKEN']}
    if($IncludeEnvDiagnostic -and $diagnosticToken){$traceSecrets += [pscustomobject]@{source='read_only_diagnostic';account_id=$null;stored_application_id=$null;expected_seller_id=$null;token=$diagnosticToken}}
    $traceResults=@()
    $traceSeen=@{}
    foreach($credential in $traceSecrets){
        $traceStage='api_read'
        $fingerprint=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($credential.token))).Substring(0,16)
        if($traceSeen.ContainsKey($fingerprint)){continue};$traceSeen[$fingerprint]=$true
        $me=Get-TraceApi '/users/me' $credential.token
        $result=[ordered]@{source=$credential.source;account_id=$credential.account_id;stored_application_id=$credential.stored_application_id;configured_application_id=$traceConfig['MP_CLIENT_ID'];credential_type=if($credential.token.StartsWith('APP_USR-')){'APP_USR'}elseif($credential.token.StartsWith('TEST-')){'TEST'}else{'UNKNOWN'};identity_http=$me.http}
        if($me.http -eq 200){
            $result.seller_id=$me.body.id;$result.seller_nickname=$me.body.nickname;$result.seller_test_user=@($me.body.tags) -contains 'test_user';$result.seller_site=$me.body.site_id
            if($credential.expected_seller_id -and [string]$me.body.id -ne $credential.expected_seller_id){$result.identity_matches_stored=$false;$traceResults+=$result;continue}
        }
        $payment=Get-TraceApi ('/v1/payments/'+$PaymentId) $credential.token
        $result.payment_http=$payment.http
        if($payment.http -eq 200){
            $result.payment=Convert-TracePayment $payment.body
            if([string]$payment.body.payer.id -match '^[0-9]{1,30}$' -and [string]$payment.body.collector_id -eq [string]$me.body.id){
                $payer=Get-TraceApi ('/users/'+$payment.body.payer.id) $credential.token
                $result.payer_identity_http=$payer.http
                if($payer.http -eq 200){$result.payer_nickname=$payer.body.nickname}
            }
            if($payment.body.order.type -eq 'mercadopago' -and [string]$payment.body.order.id -match '^[0-9]{1,30}$'){
                $order=Get-TraceApi ('/merchant_orders/'+$payment.body.order.id) $credential.token
                $result.approved_order_http=$order.http
                if($order.http -eq 200){
                    $o=$order.body
                    $result.approved_order=[ordered]@{id=$o.id;preference_id=$o.preference_id;collector_id=$o.collector.id;external_reference=$o.external_reference;status=$o.status;order_status=$o.order_status;total_amount=$o.total_amount;paid_amount=$o.paid_amount}
                    if([string]$o.preference_id -match '^[A-Za-z0-9-]{1,120}$'){
                        $approvedPref=Get-TraceApi ('/checkout/preferences/'+$o.preference_id) $credential.token
                        $result.approved_preference_http=$approvedPref.http
                        if($approvedPref.http -eq 200){
                            $ap=$approvedPref.body
                            $result.approved_preference=[ordered]@{id=$ap.id;client_id=$ap.client_id;collector_id=$ap.collector_id;external_reference=$ap.external_reference;created=$ap.date_created;expires=$ap.expires;expiry=$ap.expiration_date_to;marketplace=$ap.marketplace;marketplace_fee=$ap.marketplace_fee;auto_return=$ap.auto_return;success_return_present=-not [string]::IsNullOrWhiteSpace($ap.back_urls.success);failure_return_present=-not [string]::IsNullOrWhiteSpace($ap.back_urls.failure);pending_return_present=-not [string]::IsNullOrWhiteSpace($ap.back_urls.pending);notification_present=-not [string]::IsNullOrWhiteSpace($ap.notification_url);excluded_methods=@($ap.payment_methods.excluded_payment_methods | ForEach-Object {$_.id});items=@($ap.items | ForEach-Object {[ordered]@{quantity=$_.quantity;unit_price=$_.unit_price;currency=$_.currency_id}})}
                            $result.approved_preference.success_return_origin=Get-TraceOrigin $ap.back_urls.success
                            $result.approved_preference.notification_origin=Get-TraceOrigin $ap.notification_url
                        }
                    }
                }
            }
        }
        if($PreferenceId){
            $preference=Get-TraceApi ('/checkout/preferences/'+$PreferenceId) $credential.token
            $result.preference_http=$preference.http
            if($preference.http -eq 200){
                $p=$preference.body
                $result.preference=[ordered]@{id=$p.id;client_id=$p.client_id;collector_id=$p.collector_id;external_reference=$p.external_reference;created=$p.date_created;expires=$p.expires;expiry=$p.expiration_date_to;marketplace=$p.marketplace;marketplace_fee=$p.marketplace_fee;auto_return=$p.auto_return;success_return_present=-not [string]::IsNullOrWhiteSpace($p.back_urls.success);failure_return_present=-not [string]::IsNullOrWhiteSpace($p.back_urls.failure);pending_return_present=-not [string]::IsNullOrWhiteSpace($p.back_urls.pending);notification_present=-not [string]::IsNullOrWhiteSpace($p.notification_url);excluded_methods=@($p.payment_methods.excluded_payment_methods | ForEach-Object {$_.id});items=@($p.items | ForEach-Object {[ordered]@{quantity=$_.quantity;unit_price=$_.unit_price;currency=$_.currency_id}})}
                $search=Get-TraceApi ('/v1/payments/search?external_reference='+[Uri]::EscapeDataString([string]$p.external_reference)+'&sort=date_created&criteria=asc&limit=50') $credential.token
                $result.preference_payments_http=$search.http
                if($search.http -eq 200){$result.preference_payments_total=$search.body.paging.total;$result.preference_payments=@($search.body.results | ForEach-Object {Convert-TracePayment $_})}
            }
        }
        if($ExternalReferences){
            $result.reference_searches=@()
            foreach($reference in $ExternalReferences){
                $search=Get-TraceApi ('/v1/payments/search?external_reference='+[Uri]::EscapeDataString($reference)+'&sort=date_created&criteria=asc&limit=50') $credential.token
                $summary=[ordered]@{reference=$reference;http=$search.http}
                if($search.http -eq 200){$summary.total=$search.body.paging.total;$summary.payments=@($search.body.results | ForEach-Object {Convert-TracePayment $_})}
                $result.reference_searches+=$summary
            }
        }
        $traceResults+=$result
    }
    $traceReport=[ordered]@{checked_at=[DateTimeOffset]::UtcNow.ToString('o');operation='read_only';requested_payment_id=$PaymentId;requested_preference_id=$PreferenceId;configured_application_id=$traceConfig['MP_CLIENT_ID'];configured_test_mode=$traceConfig['MP_TEST_MODE'];results=$traceResults;note='Sem renovacao OAuth, criacao de cobranca, pagamento ou alteracao de inscricoes. Conteudo filtrado: sem tokens, cookies, CPF, email, QR ou respostas brutas.'}
    $traceReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $ReportFile -Encoding utf8
    $traceReport | ConvertTo-Json -Depth 8
} catch {
    [ordered]@{operation='read_only';stage=$traceStage;outcome='trace_unavailable'} | ConvertTo-Json -Compress
    exit 1
} finally {
    if($traceAes){$traceAes.Dispose()};if($traceKey){[Array]::Clear($traceKey,0,$traceKey.Length)}
    $diagnosticToken=$null;$traceSecrets=$null;$traceRows=$null;$traceConfig=$null;$credential=$null
}

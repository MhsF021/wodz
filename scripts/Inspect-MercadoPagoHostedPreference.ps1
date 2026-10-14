# Somente leitura. Nao renova tokens, nao cria preferencias e nao efetua pagamentos.
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidateRange(1,2147483647)][int]$CheckoutId,
    [string]$ReportFile=(Join-Path $PSScriptRoot '..\backend\target\hosted-preference-inspection.json')
)
$ErrorActionPreference='Stop'
$inspectionStage='database'
$inspectionAes=$null
$inspectionKey=$null
$inspectionToken=$null
function Read-InspectionApi([string]$Resource) {
    try {
        $response=Invoke-WebRequest -Uri ('https://api.mercadopago.com'+$Resource) -Headers @{Authorization='Bearer '+$inspectionToken} -TimeoutSec 20
        $script:inspectionServerDate=[string]$response.Headers['Date']
        $response.Content | ConvertFrom-Json
    } catch {
        throw ('Mercado Pago HTTP='+$(if($_.Exception.Response){[int]$_.Exception.Response.StatusCode}else{0}))
    }
}
try {
    $inspectionRows=& wsl.exe -d Ubuntu --cd /mnt/c/wodz -- docker compose exec -T db psql -U wodz -d wodz -At -c "select json_build_object('id',cs.id,'registration_code',t.registration_code,'external_reference',cs.external_reference,'preference_id',cs.provider_preference_id,'seller_id',pa.provider_user_id,'access_token',pa.access_token)::text from checkout_session cs join team t on t.id=cs.team_id join payout_account pa on pa.id=cs.payout_account_id where cs.id=$CheckoutId;"
    if($LASTEXITCODE -ne 0 -or -not $inspectionRows){throw 'database_unavailable'}
    $inspectionRow=$inspectionRows | ConvertFrom-Json
    $inspectionConfig=@{}
    foreach($line in Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\.env')){
        if($line -match '^([A-Z0-9_]+)=(.*)$'){$inspectionConfig[$matches[1]]=$matches[2].Trim().Trim('"').Trim("'")}
    }
    if(-not $inspectionRow.access_token.StartsWith('v1:')){throw 'invalid_encrypted_credential'}
    $inspectionKey=if([string]::IsNullOrWhiteSpace($inspectionConfig['APP_ENCRYPTION_KEY'])){[Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes('wodz-dev-only-encryption-key'))}else{[Convert]::FromBase64String($inspectionConfig['APP_ENCRYPTION_KEY'])}
    $inspectionAes=[Security.Cryptography.AesGcm]::new($inspectionKey,16)
    $encrypted=[Convert]::FromBase64String($inspectionRow.access_token.Substring(3))
    $plain=[byte[]]::new($encrypted.Length-28)
    $inspectionAes.Decrypt([byte[]]$encrypted[0..11],[byte[]]$encrypted[12..($encrypted.Length-17)],[byte[]]$encrypted[($encrypted.Length-16)..($encrypted.Length-1)],$plain)
    $inspectionToken=[Text.Encoding]::UTF8.GetString($plain)
    [Array]::Clear($plain,0,$plain.Length)
    $inspectionStage='identity'
    $me=Read-InspectionApi '/users/me'
    if([string]$me.id -ne [string]$inspectionRow.seller_id){throw 'identity_mismatch'}
    $inspectionStage='preferences'
    $ids=@()
    if($inspectionRow.preference_id){$ids+= $inspectionRow.preference_id}
    else {
        $search=Read-InspectionApi ('/checkout/preferences/search?external_reference='+[Uri]::EscapeDataString($inspectionRow.external_reference)+'&limit=10')
        $ids+= @($search.elements | ForEach-Object {$_.id})
    }
    $preferences=@()
    foreach($id in $ids){
        if([string]$id -notmatch '^[A-Za-z0-9-]{1,120}$'){throw 'invalid_preference_id'}
        $pref=Read-InspectionApi ('/checkout/preferences/'+$id)
        $expected=$inspectionConfig['FRONTEND_URL'].TrimEnd('/')+'/pagamento/'+$inspectionRow.registration_code+'?checkout='+$CheckoutId
        $preferences+= [ordered]@{id=$pref.id;collector_id=$pref.collector_id;client_id=$pref.client_id;reference_matches=($pref.external_reference -eq $inspectionRow.external_reference);expires=$pref.expires;preference_expired=$pref.preference_expired;expiration_date_from=$pref.expiration_date_from;expiration_date_to=$pref.expiration_date_to;success_matches=($pref.back_urls.success -eq ($expected+'&retorno=sucesso'));pending_matches=($pref.back_urls.pending -eq ($expected+'&retorno=pendente'));failure_matches=($pref.back_urls.failure -eq ($expected+'&retorno=erro'));excluded_methods=@($pref.payment_methods.excluded_payment_methods | ForEach-Object {$_.id});excluded_types=@($pref.payment_methods.excluded_payment_types | ForEach-Object {$_.id});init_point=$pref.init_point}
    }
    $report=[ordered]@{checked_at=[DateTimeOffset]::UtcNow.ToString('o');server_date=$inspectionServerDate;operation='read_only';checkout_id=$CheckoutId;seller_id=$me.id;seller_test_user=(@($me.tags) -contains 'test_user');seller_nickname=$me.nickname;preferences=$preferences}
    $report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $ReportFile -Encoding utf8
    $report | ConvertTo-Json -Depth 6
} catch {
    [ordered]@{operation='read_only';stage=$inspectionStage;outcome='inspection_unavailable'} | ConvertTo-Json -Compress
    exit 1
} finally {
    if($inspectionAes){$inspectionAes.Dispose()};if($inspectionKey){[Array]::Clear($inspectionKey,0,$inspectionKey.Length)}
    $inspectionToken=$null;$inspectionConfig=$null;$inspectionRows=$null;$inspectionRow=$null
}

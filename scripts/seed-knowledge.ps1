param(
    [string]$BaseUrl = "http://localhost:8088",
    [string]$TenantCode = "acme",
    [string]$Username = "admin",
    [string]$Password = "demo-password",
    [string]$Manifest = "$PSScriptRoot\..\deploy\knowledge\initial-runbooks.json"
)

$ErrorActionPreference = "Stop"
$login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/login" `
    -ContentType "application/json" `
    -Body (@{
        tenantCode = $TenantCode
        username = $Username
        password = $Password
    } | ConvertTo-Json)
$headers = @{ Authorization = "Bearer $($login.accessToken)" }
$documents = Get-Content -LiteralPath $Manifest -Raw -Encoding UTF8 | ConvertFrom-Json

foreach ($document in $documents) {
    $encodedName = [Uri]::EscapeDataString($document.name)
    $matches = Invoke-RestMethod -Method Get `
        -Uri "$BaseUrl/api/knowledge/search?query=$encodedName&topK=20" `
        -Headers $headers -TimeoutSec 120
    $existing = $matches | Where-Object {
        $_.metadata.documentName -eq $document.name
    } | Select-Object -First 1
    if ($null -ne $existing) {
        Write-Host "already published documentId=$($existing.documentId) name=$($document.name)"
        continue
    }
    $created = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/knowledge/documents" `
        -Headers $headers -ContentType "application/json" `
        -Body ($document | ConvertTo-Json -Depth 8) -TimeoutSec 120
    Write-Host "published documentId=$($created.documentId) name=$($document.name)"
}

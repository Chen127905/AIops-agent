param(
    [string]$BaseUrl = "http://localhost:8088",
    [string]$TenantCode = "acme",
    [string]$Username = "admin",
    [string]$Password = "demo-password"
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
$result = Invoke-RestMethod -Method Post `
    -Uri "$BaseUrl/api/knowledge/bootstrap" `
    -Headers $headers -TimeoutSec 120

Write-Host (
    "knowledge initialized total={0} published={1} skipped={2}" -f
    $result.total,
    $result.published,
    $result.skipped
)

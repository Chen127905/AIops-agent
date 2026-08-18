param(
    [string]$BaseUrl = "http://localhost:8088",
    [string]$EnvFile = ".env.example",
    [switch]$SkipComposeUp
)

$ErrorActionPreference = "Stop"

function Invoke-JsonRequest {
    param(
        [string]$Method,
        [string]$Path,
        [hashtable]$Headers = @{},
        [object]$Body,
        [int[]]$ExpectedStatus = @(200),
        [int]$TimeoutSec = 30
    )
    $arguments = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        Headers = $Headers
        SkipHttpErrorCheck = $true
        TimeoutSec = $TimeoutSec
    }
    if ($null -ne $Body) {
        $arguments.ContentType = "application/json"
        $arguments.Body = $Body | ConvertTo-Json -Depth 12
    }
    $response = Invoke-WebRequest @arguments
    if ($response.StatusCode -notin $ExpectedStatus) {
        throw "$Method $Path returned $($response.StatusCode), expected $($ExpectedStatus -join ',')"
    }
    $content = $response.Content
    if ($content -is [byte[]]) {
        $content = [System.Text.Encoding]::UTF8.GetString($content)
    }
    if ([string]::IsNullOrWhiteSpace($content)) { return $null }
    return $content | ConvertFrom-Json
}

function Get-Token {
    param([string]$TenantCode, [string]$Username)
    $login = Invoke-JsonRequest -Method POST -Path "/api/auth/login" -Body @{
        tenantCode = $TenantCode
        username = $Username
        password = "demo-password"
    }
    if ([string]::IsNullOrWhiteSpace($login.accessToken)) {
        throw "Login returned no access token for $TenantCode/$Username"
    }
    return $login.accessToken
}

if (-not $SkipComposeUp) {
    & docker compose --env-file $EnvFile up -d
    if ($LASTEXITCODE -ne 0) { throw "docker compose up failed" }
}

$deadline = (Get-Date).AddMinutes(4)
do {
    try {
        $health = Invoke-JsonRequest -Method GET -Path "/actuator/health" -TimeoutSec 5
        if ($health.status -eq "UP") { break }
    } catch { }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $deadline)
if ($health.status -ne "UP") { throw "Application health did not become UP" }

$adminToken = Get-Token -TenantCode "acme" -Username "admin"
$foreignToken = Get-Token -TenantCode "beta" -Username "operator"
$adminHeaders = @{ Authorization = "Bearer $adminToken" }
$foreignHeaders = @{ Authorization = "Bearer $foreignToken" }

$knowledge = Invoke-JsonRequest -Method POST -Path "/api/knowledge/documents" -Headers $adminHeaders -ExpectedStatus @(201) -Body @{
    name = "Smoke Redis timeout runbook"
    source = "smoke://redis-timeout-runbook"
    mediaType = "text/markdown"
    content = "# Redis connection pool timeout`nInspect pool utilization, command latency, and downstream dependencies before requesting an approved service restart."
    metadata = @{ purpose = "smoke" }
}
if ($knowledge.documentId -le 0) { throw "Knowledge ingestion returned no document ID" }

$evidence = @(Invoke-JsonRequest -Method GET -Path "/api/knowledge/search?query=Redis%20connection%20pool%20timeout&topK=5" -Headers $adminHeaders)
if ($evidence.Count -lt 1) { throw "pgvector search returned no evidence after ingestion" }
$ownCitation = $evidence[0].citationId
if ([string]::IsNullOrWhiteSpace($ownCitation)) { throw "Knowledge search returned no citation" }
$foreignEvidence = @(Invoke-JsonRequest -Method GET -Path "/api/knowledge/search?query=Redis%20connection%20pool%20timeout&topK=20" -Headers $foreignHeaders)
if ($foreignEvidence.citationId -contains $ownCitation) { throw "Cross-tenant knowledge citation leaked" }

$ticket = Invoke-JsonRequest -Method POST -Path "/api/tickets" -Headers $adminHeaders -ExpectedStatus @(201) -Body @{
    title = "Smoke Redis timeout incident"
    description = "Order service requests time out while acquiring Redis connections."
    affectedService = "order-service"
    category = "REDIS_TIMEOUT"
    severity = "HIGH"
}
if ($ticket.id -le 0) { throw "Ticket creation returned no ID" }

$null = Invoke-JsonRequest -Method GET -Path "/api/tickets/$($ticket.id)" -Headers $foreignHeaders -ExpectedStatus @(404)

$task = Invoke-JsonRequest -Method POST -Path "/api/tickets/$($ticket.id)/agent-tasks" -Headers $adminHeaders -ExpectedStatus @(202)
if ($task.id -le 0) { throw "Agent start returned no task ID" }

$terminal = @("WAITING_APPROVAL", "SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "MANUAL_REQUIRED")
$taskDeadline = (Get-Date).AddMinutes(2)
do {
    Start-Sleep -Milliseconds 500
    $task = Invoke-JsonRequest -Method GET -Path "/api/agent-tasks/$($task.id)" -Headers $adminHeaders
} while ($task.status -notin $terminal -and (Get-Date) -lt $taskDeadline)
if ($task.status -notin $terminal) { throw "Agent task did not reach a visible boundary" }

$evaluation = Invoke-JsonRequest -Method POST -Path "/api/evaluations/runs" -Headers $adminHeaders -Body @{ mode = "MOCK" } -TimeoutSec 180
if ($evaluation.metrics.totalCases -lt 30) { throw "Evaluation did not execute the full baseline" }
if ([string]::IsNullOrWhiteSpace($evaluation.runId)) { throw "Evaluation returned no persisted run ID" }

Write-Host "SMOKE PASS"
Write-Host "knowledgeDocument=$($knowledge.documentId) citation=$ownCitation ticket=$($ticket.id) task=$($task.id) taskStatus=$($task.status) evaluationRun=$($evaluation.runId) cases=$($evaluation.metrics.totalCases)"

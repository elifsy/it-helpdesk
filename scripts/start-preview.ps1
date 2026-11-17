param([int]$BackendPort = 0)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$previewDirectory = Join-Path $taskRoot 'logs\preview'
$null = New-Item -ItemType Directory -Force -Path $previewDirectory
$statePath = Join-Path $previewDirectory 'active-preview.json'
$proxyPath = Join-Path $PSScriptRoot 'preview-server.mjs'
$tunnelPath = Join-Path $taskRoot '.tools\cloudflared.exe'
if ($env:CLOUDFLARED_PATH) { $tunnelPath = (Resolve-Path -LiteralPath $env:CLOUDFLARED_PATH).Path }
function Find-PreviewProcess($processId, [string]$entry, [string]$expectedArgument) {
    $process = Get-CimInstance Win32_Process -Filter ('ProcessId=' + [int]$processId) -ErrorAction SilentlyContinue
    if ($process -and $entry -and $process.CommandLine -and $process.CommandLine.Contains($entry) -and $process.CommandLine.Contains($expectedArgument)) { return $process }
    return $null
}
function Show-PreviewUrl([string]$url) {
    if ($url -notmatch '^https://[a-z0-9-]+\.trycloudflare\.com$') { throw 'Invalid preview URL.' }
    Set-Content -LiteralPath (Join-Path $previewDirectory 'preview-url.txt') -Value $url -Encoding UTF8
    Write-Output ('Preview URL: ' + $url)
    Write-Output 'DingTalk sign-in is required. Keep this computer and the local helpdesk running.'
    Write-Output 'Stop only this preview with stop-preview.cmd.'
}
if (Test-Path -LiteralPath $statePath) {
    $previous = Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($previous.root -ne $taskRoot) { throw 'Preview state belongs to a different project.' }
    $oldProxy = Find-PreviewProcess $previous.proxyPid $proxyPath ('--port=' + $previous.proxyPort)
    $oldTunnel = Find-PreviewProcess $previous.tunnelPid $previous.tunnelPath ('http://127.0.0.1:' + $previous.proxyPort)
    if ($oldProxy -and $oldTunnel -and $previous.url) {
        try {
            $configuration = Invoke-RestMethod -Uri ('http://127.0.0.1:' + $previous.proxyPort + '/api/auth/config') -TimeoutSec 3
            $page = Invoke-WebRequest -Uri $previous.url -UseBasicParsing -TimeoutSec 8
            if ($configuration.data.demoEnabled -eq $false -and $page.StatusCode -eq 200 -and $page.Headers['X-Helpdesk-Preview'] -eq '1') { Show-PreviewUrl $previous.url; exit 0 }
        } catch { }
    }
    foreach ($process in @($oldTunnel, $oldProxy)) { if ($process) { Stop-Process -Id $process.ProcessId -ErrorAction Stop } }
    Copy-Item -LiteralPath $statePath -Destination (Join-Path $previewDirectory 'previous-preview.json') -Force
    Remove-Item -LiteralPath $statePath
}
if (-not (Test-Path -LiteralPath $tunnelPath)) { throw 'Install the official cloudflared.exe in .tools, or set CLOUDFLARED_PATH.' }
if (-not (Test-Path -LiteralPath (Join-Path $taskRoot 'web\dist\index.html'))) { throw 'Build the frontend first: cd web; npm run build' }
$candidates = if ($BackendPort -gt 0) { @($BackendPort) } else { @(8081, 8080, 8082, 8083) }
$selectedBackend = 0
foreach ($candidate in $candidates) {
    try {
        $configuration = Invoke-RestMethod -Uri ('http://127.0.0.1:' + $candidate + '/api/auth/config') -TimeoutSec 2
        if ($configuration.code -eq 0 -and $configuration.data.demoEnabled -eq $false -and $configuration.data.corpId) { $selectedBackend = $candidate; break }
    } catch { }
}
if ($selectedBackend -eq 0) { throw 'Start the local helpdesk with DingTalk enabled using start.cmd before sharing a preview.' }
$proxyPort = 0
foreach ($candidate in 8789..8799) {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $candidate)
    try { $listener.Start(); $proxyPort = $candidate; break } catch { } finally { $listener.Stop() }
}
if ($proxyPort -eq 0) { throw 'No local preview port is available in 8789-8799.' }
$nodePath = (Get-Command node.exe -ErrorAction Stop).Source
$proxy = Start-Process -FilePath $nodePath -ArgumentList @(('"' + $proxyPath + '"'), ('--port=' + $proxyPort), ('--backend-port=' + $selectedBackend)) -WorkingDirectory $taskRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $previewDirectory 'server.log') -RedirectStandardError (Join-Path $previewDirectory 'server-error.log')
$tunnel = $null
try {
    $ready = $false
    foreach ($attempt in 1..25) {
        $proxy.Refresh()
        if ($proxy.HasExited) { throw 'Preview server exited; see logs/preview/server-error.log.' }
        try {
            $page = Invoke-WebRequest -Uri ('http://127.0.0.1:' + $proxyPort + '/') -UseBasicParsing -TimeoutSec 1
            if ($page.StatusCode -eq 200 -and $page.Headers['X-Helpdesk-Preview'] -eq '1') { $ready = $true; break }
        } catch { }
        Start-Sleep -Milliseconds 200
    }
    if (-not $ready) { throw 'Preview server did not become available.' }
    $tunnel = Start-Process -FilePath $tunnelPath -ArgumentList @('tunnel', '--url', ('http://127.0.0.1:' + $proxyPort), '--protocol', 'http2', '--no-autoupdate') -WorkingDirectory $previewDirectory -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $previewDirectory 'tunnel.log') -RedirectStandardError (Join-Path $previewDirectory 'tunnel-error.log')
    $state = [pscustomobject]@{ root = $taskRoot; backendPort = $selectedBackend; proxyPort = $proxyPort; proxyPid = $proxy.Id; tunnelPid = $tunnel.Id; tunnelPath = $tunnelPath; startedAt = [DateTime]::UtcNow.ToString('o'); url = '' }
    $state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
    $connected = $false
    foreach ($attempt in 1..180) {
        $tunnel.Refresh()
        if ($tunnel.HasExited) { throw 'Temporary tunnel exited; see logs/preview/tunnel-error.log.' }
        $log = [string](Get-Content -LiteralPath (Join-Path $previewDirectory 'tunnel-error.log') -Raw -Encoding UTF8 -ErrorAction SilentlyContinue)
        $match = [regex]::Match($log, 'https://[a-z0-9-]+\.trycloudflare\.com')
        if ($match.Success -and $log.Contains('Registered tunnel connection')) { $state.url = $match.Value; $connected = $true; break }
        Start-Sleep -Milliseconds 250
    }
    if (-not $connected) { throw 'Tunnel connection timed out; see logs/preview/tunnel-error.log.' }
    $state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
    # A new Quick Tunnel hostname can take a few seconds to resolve publicly.
    $publicReady = $false
    foreach ($attempt in 1..10) {
        try {
            $page = Invoke-WebRequest -Uri $state.url -UseBasicParsing -TimeoutSec 2
            if ($page.StatusCode -eq 200 -and $page.Headers['X-Helpdesk-Preview'] -eq '1') { $publicReady = $true; break }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    if (-not $publicReady) { Write-Warning 'Tunnel connected; public DNS may still be updating. Retry the URL shortly.' }
    Show-PreviewUrl $state.url
} catch {
    if ($tunnel) { $tunnel.Refresh(); if (-not $tunnel.HasExited) { Stop-Process -Id $tunnel.Id } }
    $proxy.Refresh(); if (-not $proxy.HasExited) { Stop-Process -Id $proxy.Id }
    if (Test-Path -LiteralPath $statePath) { Remove-Item -LiteralPath $statePath }
    throw
}

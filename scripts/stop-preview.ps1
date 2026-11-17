$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$previewDirectory = Join-Path $taskRoot 'logs\preview'
$statePath = Join-Path $previewDirectory 'active-preview.json'
if (-not (Test-Path -LiteralPath $statePath)) { Write-Output 'No active helpdesk preview.'; exit 0 }
$state = Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($state.root -ne $taskRoot) { throw 'Preview state belongs to a different project.' }
foreach ($entry in @(@{ processId = $state.tunnelPid; path = $state.tunnelPath; argument = ('http://127.0.0.1:' + $state.proxyPort) }, @{ processId = $state.proxyPid; path = (Join-Path $PSScriptRoot 'preview-server.mjs'); argument = ('--port=' + $state.proxyPort) })) {
    $process = Get-CimInstance Win32_Process -Filter ('ProcessId=' + [int]$entry.processId) -ErrorAction SilentlyContinue
    if ($process -and $entry.path -and $process.CommandLine -and $process.CommandLine.Contains($entry.path) -and $process.CommandLine.Contains($entry.argument)) { Stop-Process -Id $process.ProcessId -ErrorAction Stop }
}
Copy-Item -LiteralPath $statePath -Destination (Join-Path $previewDirectory 'previous-preview.json') -Force
Remove-Item -LiteralPath $statePath
$urlPath = Join-Path $previewDirectory 'preview-url.txt'
if (Test-Path -LiteralPath $urlPath) { Remove-Item -LiteralPath $urlPath }
Write-Output 'Helpdesk preview stopped. Local helpdesk and other website previews remain running.'

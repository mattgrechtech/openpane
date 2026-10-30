<#
.SYNOPSIS
  Soak test: records the app's state on a TV every few minutes to a CSV, for days, so memory
  growth, reloads and lost connections show up.

.EXAMPLE
  .\tools\soak.ps1 -Tv 192.168.1.60 -Hours 72

.NOTES
  Uses adb (no API token needed). Writes soak\<tv>-<start time>.csv next to the repo's tools
  folder; that folder is git-ignored. A row with status "unreachable" means adb couldn't reach the
  TV (asleep, off the network, or the app wasn't running).
#>
param(
    [Parameter(Mandatory = $true)][string]$Tv,
    [double]$Hours = 72,
    [int]$EveryMinutes = 10,
    [switch]$Release
)

$ns = 'io.github.mattgrechtech.openpane'
$pkg = if ($Release) { $ns } else { "$ns.debug" }
$receiver = "$pkg/$ns.service.ProvisionReceiver"
$target = "${Tv}:5555"
$root = Split-Path -Parent $PSScriptRoot
$dir = Join-Path $root 'soak'
New-Item -ItemType Directory -Force $dir | Out-Null
$csv = Join-Path $dir ("{0}-{1}.csv" -f ($Tv -replace '[^0-9A-Za-z]', '-'), (Get-Date -Format 'yyyyMMdd-HHmm'))
$columns = 'time', 'status', 'ha_connection', 'dashboard_visible', 'app_memory_mb', 'memory_available_mb',
    'reload_count', 'last_reload_reason', 'last_error', 'app_uptime_s', 'url'
($columns -join ',') | Out-File -FilePath $csv -Encoding utf8

$end = (Get-Date).AddHours($Hours)
Write-Host "Logging $Tv every $EveryMinutes min until $end to $csv"

function Csv([object]$value) {
    $text = [string]$value
    if ($text -match '[",\r\n]') { '"' + ($text -replace '"', '""') + '"' } else { $text }
}

while ((Get-Date) -lt $end) {
    $now = Get-Date -Format 's'
    $row = @{ time = $now; status = 'unreachable' }
    try {
        adb connect $target 2>$null | Out-Null
        $out = (adb -s $target shell am broadcast -n $receiver -a "$ns.COMMAND" --es command state 2>$null) -join "`n"
        $m = [regex]::Match($out, 'data="(\{.*\})"')
        if ($m.Success) {
            $state = $m.Groups[1].Value | ConvertFrom-Json
            $row.status = 'ok'
            foreach ($c in $columns) { if ($c -notin 'time', 'status' -and $null -ne $state.$c) { $row[$c] = $state.$c } }
        }
    } catch {
        $row.status = 'error: ' + $_.Exception.Message
    }
    ($columns | ForEach-Object { Csv $row[$_] }) -join ',' | Out-File -FilePath $csv -Append -Encoding utf8
    Start-Sleep -Seconds ($EveryMinutes * 60)
}
Write-Host "Done: $csv"

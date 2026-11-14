# Measurement only (phase-2 completion report, planning item 19): samples the working set (RSS) of every java process
# every few seconds until the stop file exists, and writes one CSV line per process and sample:
#   time,pid,kind,workingSetMb,peakWorkingSetMb
# kind: agent-core, runner-worker (PairingWorker), runner-parent (AllPairings), other.
#
#   powershell -File scripts\sample-java-memory.ps1 -Out <csv> -Stop <stop file> [-Seconds 2]
param(
    [Parameter(Mandatory = $true)][string]$Out,
    [Parameter(Mandatory = $true)][string]$Stop,
    [int]$Seconds = 2
)
"time,pid,kind,workingSetMb,peakWorkingSetMb" | Out-File -FilePath $Out -Encoding ascii
$kinds = @{}
while (-not (Test-Path $Stop)) {
    $now = Get-Date -Format "HH:mm:ss"
    foreach ($p in Get-Process -Name java -ErrorAction SilentlyContinue) {
        if (-not $kinds.ContainsKey($p.Id)) {
            $cmd = (Get-CimInstance Win32_Process -Filter "ProcessId = $($p.Id)").CommandLine
            $kind = "other"
            if ($cmd -match "magicdosa\.agent\.AgentCore") { $kind = "agent-core" }
            elseif ($cmd -match "PairingWorker") { $kind = "runner-worker" }
            elseif ($cmd -match "AllPairings") { $kind = "runner-parent" }
            $kinds[$p.Id] = $kind
        }
        "{0},{1},{2},{3},{4}" -f $now, $p.Id, $kinds[$p.Id], [math]::Round($p.WorkingSet64 / 1MB),
            [math]::Round($p.PeakWorkingSet64 / 1MB) | Out-File -FilePath $Out -Append -Encoding ascii
    }
    Start-Sleep -Seconds $Seconds
}

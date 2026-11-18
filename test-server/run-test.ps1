# Starts the test server, waits for startup (or a timeout), optionally runs console commands,
# then stops it cleanly. Output goes to test-server\last-run.log.
param(
    [int]$StartupTimeoutSec = 240,
    [string[]]$Commands = @(),
    [int]$AfterCommandsSec = 20,
    # Optional: after startup, keep running until a log line matches this regex (or WaitTimeoutSec).
    [string]$WaitFor = '',
    [int]$WaitTimeoutSec = 900
)
$ErrorActionPreference = 'Stop'
$server = $PSScriptRoot
$jdk = (Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory | Where-Object Name -like 'jdk-25*' | Select-Object -First 1).FullName
$log = Join-Path $server 'last-run.log'

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = "$jdk\bin\java.exe"
$psi.Arguments = '-Xms1G -Xmx3G -XX:-UseBASE64Intrinsics -jar paper-26.3-245.jar --nogui'
$psi.WorkingDirectory = $server
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true

$lines = [System.Collections.Concurrent.ConcurrentQueue[string]]::new()
$proc = New-Object System.Diagnostics.Process
$proc.StartInfo = $psi
$handler = { if ($EventArgs.Data -ne $null) { $Event.MessageData.Enqueue($EventArgs.Data) } }
$e1 = Register-ObjectEvent $proc OutputDataReceived -Action $handler -MessageData $lines
$e2 = Register-ObjectEvent $proc ErrorDataReceived -Action $handler -MessageData $lines
[void]$proc.Start(); $proc.BeginOutputReadLine(); $proc.BeginErrorReadLine()

$all = New-Object System.Collections.Generic.List[string]
# Write raw bytes to stdin: the StreamWriter adds a UTF-8 BOM, which makes 'stop' arrive as '?stop'.
function Send([string]$cmd) {
    $bytes = [System.Text.Encoding]::ASCII.GetBytes($cmd + "`n")
    $proc.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
    $proc.StandardInput.BaseStream.Flush()
}
function Drain { $l = $null; while ($lines.TryDequeue([ref]$l)) { $all.Add($l) } }

$deadline = (Get-Date).AddSeconds($StartupTimeoutSec)
while ((Get-Date) -lt $deadline -and -not $proc.HasExited) {
    Start-Sleep -Milliseconds 500; Drain
    if ($all | Where-Object { $_ -match 'Done \(.*\)! For help' }) { break }
}
# .NET Framework writes a UTF-8 BOM into stdin at process start; flush it out with a throwaway line.
Send ''
# give async plugin setup (hub world copy) a moment
Start-Sleep -Seconds 10; Drain
if ($WaitFor) {
    $waitDeadline = (Get-Date).AddSeconds($WaitTimeoutSec)
    while ((Get-Date) -lt $waitDeadline -and -not $proc.HasExited) {
        Start-Sleep -Seconds 1; Drain
        if ($all | Where-Object { $_ -match $WaitFor }) { break }
    }
}
foreach ($c in $Commands) {
    # "sleep N" pauses the script instead of being sent to the server.
    if ($c -match '^sleep (\d+)$') { Start-Sleep -Seconds ([int]$matches[1]); Drain; continue }
    Send $c; Start-Sleep -Seconds 2
}
if ($Commands.Count -gt 0) { Start-Sleep -Seconds $AfterCommandsSec }
if (-not $proc.HasExited) { Send 'stop' }
if (-not $proc.WaitForExit(90000)) { $proc.Kill() }
Start-Sleep -Milliseconds 500; Drain
Unregister-Event -SourceIdentifier $e1.Name; Unregister-Event -SourceIdentifier $e2.Name
$all | Set-Content $log -Encoding utf8
"exit code: $($proc.ExitCode); $($all.Count) log lines -> $log"

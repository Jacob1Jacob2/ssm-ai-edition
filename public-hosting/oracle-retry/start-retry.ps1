# Starts the Oracle retry in the background (no window). It keeps running after this window closes
# and stops by itself when the server is created (see SUCCESS.txt) or Oracle refuses for another reason.
#
#   powershell -ExecutionPolicy Bypass -File start-retry.ps1          start
#   powershell -ExecutionPolicy Bypass -File start-retry.ps1 -Stop    stop
#   Get-Content oracle-retry.log -Tail 5                              see the latest attempts
param([switch]$Stop)
$here = $PSScriptRoot
$pidFile = Join-Path $here 'oracle-retry.pid'
$running = if (Test-Path $pidFile) { Get-Process -Id ([int](Get-Content $pidFile)) -ErrorAction SilentlyContinue | Where-Object ProcessName -eq 'java' }
if ($Stop) {
    if ($running) { Stop-Process -Id $running.Id -Force; 'Stopped.' } else { 'It was not running.' }
    Remove-Item $pidFile -ErrorAction SilentlyContinue
    return
}
if ($running) { "Already running (process $($running.Id))."; return }
Remove-Item (Join-Path $here 'STOP') -ErrorAction SilentlyContinue
$java = (Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName + '\bin\java.exe'
$p = Start-Process $java -ArgumentList "`"$here\OracleRetry.java`"", "`"$here`"" -WorkingDirectory $here -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $here 'oracle-retry.out') -RedirectStandardError (Join-Path $here 'oracle-retry.err')
$p.Id | Set-Content $pidFile
"Started (process $($p.Id)). Progress: $here\oracle-retry.log"

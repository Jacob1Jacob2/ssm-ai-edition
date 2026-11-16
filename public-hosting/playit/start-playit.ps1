# Starts the playit.gg tunnel in the background (no window). The tunnels are only online while this
# is running and the Minecraft server is up. It does not survive a PC restart: run this again.
#
#   powershell -ExecutionPolicy Bypass -File start-playit.ps1          start
#   powershell -ExecutionPolicy Bypass -File start-playit.ps1 -Stop    stop
param([switch]$Stop)
$here = $PSScriptRoot
$pidFile = Join-Path $here 'playit.pid'
$running = if (Test-Path $pidFile) { Get-Process -Id ([int](Get-Content $pidFile)) -ErrorAction SilentlyContinue | Where-Object ProcessName -like 'playit*' }
if ($Stop) {
    if ($running) { Stop-Process -Id $running.Id -Force; 'Stopped.' } else { 'It was not running.' }
    Remove-Item $pidFile -ErrorAction SilentlyContinue
    return
}
if ($running) { "Already running (process $($running.Id))."; return }
$p = Start-Process (Join-Path $here 'playit-windows-x86_64-signed.exe') -ArgumentList '-s' -WorkingDirectory $here -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $here 'playit.log') -RedirectStandardError (Join-Path $here 'playit.err')
$p.Id | Set-Content $pidFile
"Started (process $($p.Id)). Log: $here\playit.log"

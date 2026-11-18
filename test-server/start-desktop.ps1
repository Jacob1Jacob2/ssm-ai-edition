param([switch]$Quiet)
$ErrorActionPreference = 'Stop'
$serverRoot = $PSScriptRoot
$javaPath = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot\bin\java.exe'
$jarName = 'paper-26.3-245.jar'

function Show-Status([string]$message, [bool]$failed = $false) {
    if ($Quiet) { Write-Output $message; return }
    Add-Type -AssemblyName System.Windows.Forms
    $icon = if ($failed) { [System.Windows.Forms.MessageBoxIcon]::Error } else { [System.Windows.Forms.MessageBoxIcon]::Information }
    [System.Windows.Forms.MessageBox]::Show($message, 'SSM AI Edition', [System.Windows.Forms.MessageBoxButtons]::OK, $icon) | Out-Null
}

$startLock = New-Object System.Threading.Mutex($false, 'Local\SSMAIEditionServerStart')
$ownsLock = $false
try {
    try { $ownsLock = $startLock.WaitOne(0) } catch [System.Threading.AbandonedMutexException] { $ownsLock = $true }
    if (-not $ownsLock) { Show-Status 'The server is already starting. Please wait a moment.'; return }

    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort 25565,25566 -ErrorAction SilentlyContinue)
    if ($listeners.Count -gt 0) {
        $gameListener = $listeners | Where-Object { $_.LocalPort -eq 25565 } | Select-Object -First 1
        $runningServer = if ($gameListener) { Get-CimInstance Win32_Process -Filter "ProcessId = $($gameListener.OwningProcess)" } else { $null }
        if ($runningServer -and $runningServer.CommandLine -like "*$jarName*") {
            Show-Status "Your SSM server is already running.`n`nJoin Minecraft at: localhost"
        } else {
            Show-Status 'A required server port is already in use. Close the other server before starting SSM AI Edition.' $true
        }
        return
    }

    if (-not (Test-Path -LiteralPath $javaPath)) { throw "Java was not found at $javaPath" }
    if (-not (Test-Path -LiteralPath (Join-Path $serverRoot $jarName))) { throw "Server file was not found: $jarName" }
    $logDirectory = Join-Path $serverRoot 'logs'
    New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
    $stamp = Get-Date -Format 'yyyy-MM-dd_HH-mm-ss'
    $outputLog = Join-Path $logDirectory "desktop-start-$stamp.log"
    $errorLog = Join-Path $logDirectory "desktop-start-$stamp.error.log"
    $serverProcess = Start-Process -FilePath $javaPath -ArgumentList @('-Xms1G','-Xmx3G','-XX:-UseBASE64Intrinsics','-jar',$jarName,'--nogui') -WorkingDirectory $serverRoot -WindowStyle Hidden -RedirectStandardOutput $outputLog -RedirectStandardError $errorLog -PassThru
    $serverProcess.Id | Set-Content -LiteralPath (Join-Path $serverRoot 'desktop-server.pid')

    for ($attempt = 0; $attempt -lt 45; $attempt++) {
        Start-Sleep -Seconds 1
        $serverProcess.Refresh()
        if ($serverProcess.HasExited) { throw "The server stopped during startup. Check $outputLog and $errorLog" }
        if (Select-String -LiteralPath $outputLog -Pattern 'Done \([0-9.]+s\)!' -Quiet) {
            Show-Status "Your SSM server is online!`n`nJoin Minecraft at: localhost`nThe server will keep running in the background."
            return
        }
    }
    Show-Status "The server is still starting in the background.`nCheck its progress in: $outputLog"
} catch {
    Show-Status $_.Exception.Message $true
    if ($Quiet) { throw }
} finally {
    if ($ownsLock) { $startLock.ReleaseMutex() }
    $startLock.Dispose()
}

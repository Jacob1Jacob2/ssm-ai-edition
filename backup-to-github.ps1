# Saves the current state of the project to GitHub (github.com/Jacob1Jacob2/ssm-ai-edition).
# Private and re-downloadable files are skipped by .gitignore; see RESTORE.md.
#
#   powershell -ExecutionPolicy Bypass -File backup-to-github.ps1 "what changed"
param([string]$Message = "Backup $(Get-Date -Format 'yyyy-MM-dd HH:mm')")
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$git = Join-Path $PSScriptRoot 'tools\mingit\cmd\git.exe'
if (-not (Test-Path $git)) { throw "Portable Git is missing from tools\mingit" }

# The example copy of server.properties is the live file with its secrets blanked.
$live = Join-Path $PSScriptRoot 'test-server\server.properties'
if (Test-Path $live) {
    $clean = [IO.File]::ReadAllLines($live) | ForEach-Object {
        $_ -replace '^(management-server-secret|rcon\.password|management-server-tls-keystore-password)=.*', '$1=' }
    [IO.File]::WriteAllLines("$live.example", $clean, (New-Object Text.UTF8Encoding $false))
}

$ErrorActionPreference = 'Continue'   # git writes progress to stderr
& $git add -A 2>$null | Out-Null
& $git diff --cached --quiet
if ($LASTEXITCODE -eq 0) { 'Nothing has changed since the last backup.' }
else { & $git commit -q -m $Message; "Committed: $Message" }
cmd /c "`"$git`" push -q origin main 2>&1" | Out-Null
$local = & $git rev-parse HEAD
$remote = ((cmd /c "`"$git`" ls-remote origin refs/heads/main 2>nul") -split '\s+')[0]
if ($local -eq $remote) { "GitHub is up to date ($($local.Substring(0,7)))." } else { throw "Push did not go through: local $local, GitHub $remote" }

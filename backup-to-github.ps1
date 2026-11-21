# Saves the current state of the project to GitHub (github.com/Jacob1Jacob2/ssm-ai-edition).
# Private and re-downloadable files are skipped by .gitignore; see RESTORE.md.
#
#   powershell -ExecutionPolicy Bypass -File backup-to-github.ps1 "what changed"
#
# The lobby's world files (test-server\world) are written again by every run of the server with
# nothing in them changed but times. Saved on every push, they were half of what the backup grew
# by. So they are left out, and saved only:
#   - when the lobby was open for building since they last were (the plugin notes that in
#     lobby-edited.txt, which is saved along with them);
#   - when they have not been saved for a week, in case something changed them some other way;
#   - when -Lobby says so.
# -DryRun shows what would be saved, and saves nothing.
param(
    [string]$Message = "Backup $(Get-Date -Format 'yyyy-MM-dd HH:mm')",
    [switch]$Lobby,
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$git = Join-Path $PSScriptRoot 'tools\mingit\cmd\git.exe'
if (-not (Test-Path $git)) { throw "Portable Git is missing from tools\mingit" }

# The example copy of server.properties is the live file with its secrets blanked, and without
# the line that only says when the server last wrote it (it made the copy differ on every run).
$live = Join-Path $PSScriptRoot 'test-server\server.properties'
if (Test-Path $live) {
    $clean = [IO.File]::ReadAllLines($live) |
        Where-Object { $_ -notmatch '^#\w{3} \w{3} \d{1,2} \d\d:\d\d:\d\d \S+ \d{4}$' } |
        ForEach-Object { $_ -replace '^(management-server-secret|rcon\.password|management-server-tls-keystore-password)=.*', '$1=' }
    [IO.File]::WriteAllLines("$live.example", $clean, (New-Object Text.UTF8Encoding $false))
}

$ErrorActionPreference = 'Continue'   # git writes progress to stderr

# Are the lobby's world files to be saved this time, and why?
$lobbyFiles = 'test-server/world'
$lobbyNote = 'test-server/plugins/super-smash-mobs-brawl/lobby-edited.txt'
$edited = [bool](& $git status --porcelain -- $lobbyNote)
$savedAt = & $git log -1 --format=%ct -- $lobbyFiles
$days = if ($savedAt) { ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds() - [long]$savedAt) / 86400.0 } else { 9999 }
$why = if ($Lobby) { 'asked for with -Lobby' }
    elseif ($edited) { 'the lobby was open for building' }
    elseif ($days -ge 7) { "not saved for $([int]$days) days" }
    else { $null }
$lobbyLine = if ($why) { "Lobby files: saved ($why)." }
    else { "Lobby files: left out (the lobby was not open for building; last saved $('{0:N1}' -f $days) days ago)." }

if ($DryRun) {
    $would = if ($why) { & $git add -A -n 2>$null } else { & $git add -A -n -- . ":(exclude)$lobbyFiles" 2>$null }
    $paths = @($would | ForEach-Object { if ($_ -match "^(?:add|remove) '(.+)'$") { $matches[1] } })
    "Dry run: $($paths.Count) file(s) would be saved, $(@($paths | Where-Object { $_ -like "$lobbyFiles/*" }).Count) of them lobby files."
    $paths | Where-Object { $_ -notlike "$lobbyFiles/*" } | Select-Object -First 40 | ForEach-Object { "  $_" }
    $lobbyLine
    return
}

if ($why) { & $git add -A 2>$null | Out-Null } else { & $git add -A -- . ":(exclude)$lobbyFiles" 2>$null | Out-Null }
& $git diff --cached --quiet
if ($LASTEXITCODE -eq 0) { 'Nothing has changed since the last backup.' }
else {
    # The message goes to git in a file: handed over on the command line, a message with double
    # quotes in it is cut up by Windows PowerShell, the commit fails, and nothing said so.
    $note = Join-Path $env:TEMP "ssm-commit-message-$PID.txt"
    [IO.File]::WriteAllText($note, $Message, (New-Object Text.UTF8Encoding $false))
    & $git commit -q -F $note
    $committed = $LASTEXITCODE -eq 0
    Remove-Item $note -Confirm:$false -ErrorAction SilentlyContinue
    if (-not $committed) { throw "The commit did not go through (git exit code $LASTEXITCODE): nothing was sent to GitHub." }
    "Committed: $Message"
}
$lobbyLine
cmd /c "`"$git`" push -q origin main 2>&1" | Out-Null
$local = & $git rev-parse HEAD
$remote = ((cmd /c "`"$git`" ls-remote origin refs/heads/main 2>nul") -split '\s+')[0]
if ($local -eq $remote) { "GitHub is up to date ($($local.Substring(0,7)))." } else { throw "Push did not go through: local $local, GitHub $remote" }

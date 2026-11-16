# Rebuilds server.tar.gz and manifest.json (the upload for the public host) from the local server.
#
# The local server must be STOPPED while this runs: a running server gives an inconsistent copy of
# the lobby worlds. Nothing in the local server is changed; the public settings below are applied
# to a staging copy only.
#
#   powershell -ExecutionPolicy Bypass -File build-bundle.ps1
param(
    [string]$ServerRoot = (Join-Path $PSScriptRoot '..\test-server'),
    [string]$Staging = (Join-Path $env:TEMP 'ssm-bundle'),
    [int]$MaxActiveMatches = 2,
    [int]$MaxTotalFighters = 16,
    [int]$UnitTests = 0
)
$ErrorActionPreference = 'Stop'
$ServerRoot = (Resolve-Path $ServerRoot).Path
$utf8 = New-Object System.Text.UTF8Encoding $false

if (Get-NetTCPConnection -State Listen -LocalPort 25565 -ErrorAction SilentlyContinue) {
    throw 'The local server is running. Stop it first so the copy is consistent.'
}

$stage = Join-Path $Staging 'server'
if (Test-Path $Staging) { Remove-Item $Staging -Recurse -Force }
New-Item -ItemType Directory -Force $stage | Out-Null

function Mirror([string]$from, [string]$to, [string[]]$extra = @()) {
    $arguments = @($from, $to, '/E', '/R:1', '/W:1', '/NFL', '/NDL', '/NJH', '/NJS', '/NP', '/XF', 'session.lock') + $extra
    & robocopy @arguments | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "Copy failed: $from" }
}

# --- what goes to the public host (no logs, test plugins, player histories or generated caches)
foreach ($file in 'paper-26.3-245.jar', 'bukkit.yml', 'spigot.yml', 'commands.yml', 'eula.txt', 'ops.json',
        'banned-ips.json', 'banned-players.json', 'whitelist.json', 'server-icon.png', 'server.properties') {
    Copy-Item (Join-Path $ServerRoot $file) $stage
}
Mirror "$ServerRoot\config" "$stage\config"
Mirror "$ServerRoot\libraries" "$stage\libraries"
# Paper's downloaded and patched server jars. Shipping them means the host's first start does
# not depend on fetching anything from Mojang.
Mirror "$ServerRoot\cache" "$stage\cache"
Mirror "$ServerRoot\versions" "$stage\versions"
Mirror "$ServerRoot\worlds" "$stage\worlds" @('/XD', 'players', 'playerdata', '/XF', 'level.dat_old')
# The lobbies live in world\dimensions; finished matches (ssmbworld_*) and player data stay behind.
Mirror "$ServerRoot\world" "$stage\world" @('/XD', 'players', 'ssmbworld_*', '/XF', 'level.dat_old')
New-Item -ItemType Directory -Force "$stage\plugins" | Out-Null
foreach ($jar in Get-ChildItem "$ServerRoot\plugins" -Filter '*.jar') { Copy-Item $jar.FullName "$stage\plugins" }
Mirror "$ServerRoot\plugins\LibsDisguises" "$stage\plugins\LibsDisguises"
# The resource pack is generated on startup, so the built copy is not shipped.
Mirror "$ServerRoot\plugins\super-smash-mobs-brawl" "$stage\plugins\super-smash-mobs-brawl" @('/XD', 'resourcepack')

# --- public settings (staging copy only)
$public = @{
    'allow-flight' = 'true'                              # double jumps and flying kits must not be kicked
    'motd' = 'SSM AI Edition - Fight AI opponents'
    'simulation-distance' = '6'
    'view-distance' = '8'
    'spawn-protection' = '0'
    'management-server-secret' = ''                      # never ship the local secret
}
$properties = [IO.File]::ReadAllLines("$stage\server.properties") | ForEach-Object {
    $key = ($_ -split '=', 2)[0]
    if ($public.ContainsKey($key)) { "$key=$($public[$key])" } else { $_ }
}
[IO.File]::WriteAllLines("$stage\server.properties", $properties, $utf8)

# systemd restarts the server; Spigot's own restart script would fight it.
$spigot = [IO.File]::ReadAllLines("$stage\spigot.yml") | ForEach-Object { $_ -replace 'restart-on-crash: true', 'restart-on-crash: false' }
[IO.File]::WriteAllLines("$stage\spigot.yml", $spigot, $utf8)

$config = "$stage\plugins\super-smash-mobs-brawl\config.yml"
$lines = @([IO.File]::ReadAllLines($config) | Where-Object { $_ -notmatch '^hosting:' -and $_ -notmatch '^\s+max-(active-matches|total-fighters):' })
while ($lines.Count -gt 0 -and $lines[-1].Trim() -eq '') { $lines = $lines[0..($lines.Count - 2)] }
$lines += @('', 'hosting:', "  max-active-matches: $MaxActiveMatches", "  max-total-fighters: $MaxTotalFighters")
[IO.File]::WriteAllLines($config, $lines, $utf8)

# install.py replaces SET_PUBLIC_HOST with the VM's address; the launcher refuses to start without it.
[IO.File]::WriteAllLines("$stage\plugins\super-smash-mobs-brawl\resource-pack.properties",
    @('enabled=true', 'port=25566', 'bind=0.0.0.0', 'public-url=http://SET_PUBLIC_HOST:25566/ssm-pack.zip'), $utf8)

# --- archive and manifest
$archive = Join-Path $PSScriptRoot 'server.tar.gz'
if (Test-Path $archive) { Remove-Item $archive -Force }
& tar -czf $archive -C $Staging server
if ($LASTEXITCODE -ne 0) { throw 'tar failed' }

function Sha([string]$path) { (Get-FileHash $path -Algorithm SHA256).Hash.ToLower() }
function PluginVersion([string]$jar) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($jar)
    try {
        $entry = $zip.GetEntry('plugin.yml')
        if (-not $entry) { return 'unknown' }
        $reader = New-Object IO.StreamReader($entry.Open())
        $text = $reader.ReadToEnd(); $reader.Dispose()
        if ($text -match "(?m)^version:\s*['""]?([^'""\r\n]+)") { return $Matches[1].Trim() } else { return 'unknown' }
    } finally { $zip.Dispose() }
}
$files = (Get-ChildItem $stage -Recurse -File | Measure-Object).Count
$maps = (Get-ChildItem "$stage\worlds" -Directory | Where-Object Name -ne 'lobby' | Measure-Object).Count
$lobbies = (Get-ChildItem "$stage\world\dimensions\minecraft" -Directory -Filter 'ssm_lobby*' | Measure-Object).Count
$manifest = [ordered]@{
    created_utc = (Get-Date).ToUniversalTime().ToString('o')
    deployment_status = 'Prepared locally; not installed on Oracle'
    target = 'Ubuntu 24.04 / ARM64 or x86_64 / Java 25'
    paper = [ordered]@{ file = 'paper-26.3-245.jar'; version = '26.3 build 245'; sha256 = Sha "$stage\paper-26.3-245.jar" }
    plugins = @(Get-ChildItem "$stage\plugins" -Filter '*.jar' | Sort-Object Name | ForEach-Object {
        [ordered]@{ file = $_.Name; version = PluginVersion $_.FullName; sha256 = Sha $_.FullName } })
    maps = $maps
    lobbies = $lobbies
    max_active_matches = $MaxActiveMatches
    max_total_fighters_including_bots = $MaxTotalFighters
    unit_tests = $UnitTests
    archive_sha256 = Sha $archive
    archive_bytes = (Get-Item $archive).Length
    archive_files = $files
    not_yet_tested = @('Linux ARM64 startup', 'Oracle network reachability', 'Remote authenticated Minecraft login', 'Public concurrent load')
}
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'manifest.json'), ($manifest | ConvertTo-Json -Depth 5), $utf8)
Remove-Item $Staging -Recurse -Force
"Bundle rebuilt: $files files, $maps maps, $lobbies lobbies, $([math]::Round((Get-Item $archive).Length / 1MB)) MB"
($manifest.plugins | ForEach-Object { "  $($_.file)  $($_.version)" })

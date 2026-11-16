# Checks a live server from this PC: asks it for its Minecraft status and downloads the resource pack.
# Windows version of verify.py (which needs Python). It does not log a player in.
#
#   powershell -ExecutionPolicy Bypass -File verify.ps1 -Address 203.0.113.10
param(
    [Parameter(Mandatory = $true)][string]$Address,
    [int]$GamePort = 25565,
    [int]$PackPort = 25566,
    [int]$ExpectedMapPictures = 45
)
$ErrorActionPreference = 'Stop'

function ConvertTo-VarInt([int]$value) {
    $bytes = New-Object System.Collections.Generic.List[byte]
    do {
        $part = $value -band 0x7F
        $value = $value -shr 7
        if ($value -ne 0) { $part = $part -bor 0x80 }
        $bytes.Add([byte]$part)
    } while ($value -ne 0)
    return ,$bytes.ToArray()
}

function Read-VarInt($stream) {
    $result = 0
    for ($i = 0; $i -lt 5; $i++) {
        $b = $stream.ReadByte()
        if ($b -lt 0) { throw 'Server closed the connection' }
        $result = $result -bor (($b -band 0x7F) -shl (7 * $i))
        if ($b -lt 128) { return $result }
    }
    throw 'Invalid VarInt'
}

function Get-MinecraftStatus([string]$server, [int]$port) {
    $client = New-Object System.Net.Sockets.TcpClient
    $client.ReceiveTimeout = 15000; $client.SendTimeout = 15000
    $connect = $client.BeginConnect($server, $port, $null, $null)
    if (-not $connect.AsyncWaitHandle.WaitOne(15000)) { $client.Close(); throw "No answer on $($server):$port (port closed or blocked by a firewall)" }
    $client.EndConnect($connect)
    try {
        $stream = $client.GetStream()
        $name = [Text.Encoding]::UTF8.GetBytes($server)
        $handshake = (ConvertTo-VarInt 0) + (ConvertTo-VarInt 0) + (ConvertTo-VarInt $name.Length) + $name +
            [byte[]]@((($port -shr 8) -band 0xFF), ($port -band 0xFF)) + (ConvertTo-VarInt 1)
        $packet = (ConvertTo-VarInt $handshake.Length) + $handshake + [byte[]]@(1, 0)
        $stream.Write($packet, 0, $packet.Length)
        $size = Read-VarInt $stream
        if ($size -le 1 -or $size -ge 1MB -or (Read-VarInt $stream) -ne 0) { throw 'Not a Minecraft status response' }
        $remaining = Read-VarInt $stream
        $data = New-Object byte[] $remaining
        $read = 0
        while ($read -lt $remaining) {
            $n = $stream.Read($data, $read, $remaining - $read)
            if ($n -le 0) { throw 'Incomplete status' }
            $read += $n
        }
        return [Text.Encoding]::UTF8.GetString($data) | ConvertFrom-Json
    } finally { $client.Close() }
}

$status = Get-MinecraftStatus $Address $GamePort
"Game: $($status.version.name), $($status.players.online)/$($status.players.max) players online"

Add-Type -AssemblyName System.IO.Compression
$pack = (New-Object System.Net.WebClient).DownloadData("http://$($Address):$PackPort/ssm-pack.zip")
$zip = New-Object System.IO.Compression.ZipArchive((New-Object System.IO.MemoryStream(, $pack)))
$names = $zip.Entries | ForEach-Object FullName
foreach ($required in 'pack.mcmeta', 'assets/ssm/font/maps.json') {
    if ($names -notcontains $required) { throw "Resource pack is missing $required" }
}
$pictures = ($names | Where-Object { $_ -like 'assets/ssm/textures/font/maps/*' -and $_ -notlike '*/' }).Count
if ($pictures -ne $ExpectedMapPictures) { throw "Expected $ExpectedMapPictures map pictures in the pack, found $pictures" }
"Pack: $([math]::Round($pack.Length / 1MB, 1)) MB, $pictures map pictures"
'PASSED: the server answers and the resource pack downloads. (This does not log a player in.)'

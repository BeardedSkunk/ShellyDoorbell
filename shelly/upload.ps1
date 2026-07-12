# Laedt doorbell.js als Script "doorbell" auf den Shelly hoch, aktiviert
# Autostart und startet es. Aufruf:  .\upload.ps1 [-Ip 192.168.178.20]
param(
    [string]$Ip = "192.168.178.20"
)

$ErrorActionPreference = "Stop"

function Invoke-Rpc([string]$Method, $Params) {
    $body = @{ id = 1; src = "upload"; method = $Method }
    if ($null -ne $Params) { $body.params = $Params }
    $json = $body | ConvertTo-Json -Depth 8 -Compress
    $resp = Invoke-RestMethod -Method Post -Uri "http://$Ip/rpc" -ContentType "application/json" -Body $json
    if ($resp.PSObject.Properties.Name -contains "error") {
        throw "RPC $Method fehlgeschlagen: $($resp.error | ConvertTo-Json -Compress)"
    }
    return $resp.result
}

$code = Get-Content -Raw -Encoding UTF8 (Join-Path $PSScriptRoot "doorbell.js")

# Vorhandenes Script "doorbell" suchen oder neu anlegen
$list = Invoke-Rpc "Script.List" $null
$existing = @($list.scripts) | Where-Object { $_.name -eq "doorbell" } | Select-Object -First 1
if ($existing) {
    $id = $existing.id
    Write-Host "Aktualisiere vorhandenes Script 'doorbell' (id=$id) auf $Ip ..."
    Invoke-Rpc "Script.Stop" @{ id = $id } | Out-Null
} else {
    $id = (Invoke-Rpc "Script.Create" @{ name = "doorbell" }).id
    Write-Host "Lege neues Script 'doorbell' (id=$id) auf $Ip an ..."
}

# Code in 1024-Zeichen-Bloecken hochladen (erster Block ersetzt, Rest haengt an)
$chunkSize = 1024
for ($pos = 0; $pos -lt $code.Length; $pos += $chunkSize) {
    $len = [Math]::Min($chunkSize, $code.Length - $pos)
    $chunk = $code.Substring($pos, $len)
    Invoke-Rpc "Script.PutCode" @{ id = $id; code = $chunk; append = ($pos -gt 0) } | Out-Null
}

# Autostart aktivieren und Script starten
Invoke-Rpc "Script.SetConfig" @{ id = $id; config = @{ enable = $true } } | Out-Null
Invoke-Rpc "Script.Start" @{ id = $id } | Out-Null

$status = Invoke-Rpc "Script.GetStatus" @{ id = $id }
Write-Host "Fertig. Script laeuft: $($status.running)"

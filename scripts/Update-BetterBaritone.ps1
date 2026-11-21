[CmdletBinding()]
param(
    [string]$ModsDirectory = 'E:\CDriveRelief\Minecraft\versions\c3cu\mods',
    [string]$SourceJar,
    [switch]$WaitForExit,
    [switch]$Consolidate
)

$ErrorActionPreference = 'Stop'
if (!$SourceJar) {
    $builds = Join-Path $PSScriptRoot '..\fabric\build\libs'
    $latest = Get-ChildItem -LiteralPath $builds -File -Filter 'BetterBaritone-*.jar' |
        ForEach-Object {
            if ($_.Name -match '^BetterBaritone-(\d+\.\d+\.\d+)\.jar$') {
                [pscustomobject]@{ File = $_.FullName; Version = [version]$Matches[1] }
            }
        } | Sort-Object Version -Descending | Select-Object -First 1
    if ($null -eq $latest) { throw 'No versioned BetterBaritone JAR found.' }
    $SourceJar = $latest.File
}
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Read-FabricMetadata([string]$File) {
    $archive = [IO.Compression.ZipFile]::OpenRead($File)
    try {
        $entry = $archive.GetEntry('fabric.mod.json')
        if ($null -eq $entry) { return $null }
        $reader = [IO.StreamReader]::new($entry.Open())
        try {
            return ($reader.ReadToEnd() | ConvertFrom-Json)
        } finally {
            $reader.Dispose()
        }
    } finally {
        $archive.Dispose()
    }
}

$mods = (Get-Item -LiteralPath $ModsDirectory).FullName
$source = (Get-Item -LiteralPath $SourceJar).FullName
$instance = (Get-Item -LiteralPath $mods).Parent.FullName
$instanceKey = $instance.Replace('\', '/').ToLowerInvariant()
$metadata = Read-FabricMetadata $source
if ($metadata.id -ne 'baritone' -or $metadata.name -ne 'BetterBaritone' -or
        @($metadata.depends.minecraft) -notcontains '1.21.11' -or
        $metadata.version -notmatch '^\d+\.\d+\.\d+$') {
    throw 'The source is not the BetterBaritone build for Fabric 1.21.11.'
}

function Test-RunningInstance {
    $java = Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'"
    $pattern = [regex]::Escape($instanceKey) + '(?=$|[/\s";])'
    return $null -ne ($java | Where-Object {
        $_.CommandLine -and $_.CommandLine.Replace('\', '/').ToLowerInvariant() -match $pattern
    } | Select-Object -First 1)
}

if (Test-RunningInstance) {
    if (!$WaitForExit) { throw 'Minecraft is using this instance. Close it first.' }
    Write-Host 'Waiting for Minecraft to close normally. Leave this window open.'
    do { Start-Sleep -Seconds 2 } while (Test-RunningInstance)
}

$backupRoot = Join-Path $instance 'baritone-mod-backups'
[IO.Directory]::CreateDirectory($backupRoot) | Out-Null
$lock = [IO.File]::Open((Join-Path $backupRoot 'update.lock'), [IO.FileMode]::OpenOrCreate,
    [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
try {
    $active = @(Get-ChildItem -LiteralPath $mods -File -Filter '*.jar' | Where-Object {
        try {
            (Read-FabricMetadata $_.FullName).id -eq 'baritone'
        } catch {
            if ($_.Name -match 'baritone') { throw }
            $false
        }
    })
    if ($active.Count -eq 0 -or ($active.Count -gt 1 -and !$Consolidate)) {
        throw "Expected one active Baritone JAR in $mods. Found $($active.Count)."
    }

    $sourceHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
    if ($active.Count -eq 1 -and (Get-FileHash -LiteralPath $active[0].FullName -Algorithm SHA256).Hash -eq $sourceHash) {
        Write-Host 'BetterBaritone is already up to date.'
        return
    }

    $destination = Join-Path $mods ("BetterBaritone-" + $metadata.version + ".jar")
    if ($destination -notin $active.FullName -and (Test-Path -LiteralPath $destination)) {
        throw "Unexpected file at $destination."
    }

    $backupDir = Join-Path $backupRoot ((Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 6))
    New-Item -Path $backupDir -ItemType Directory | Out-Null
    $staged = Join-Path $backupDir 'candidate.tmp'
    $moved = [System.Collections.Generic.List[IO.FileInfo]]::new()
    $newMoved = $false

    try {
        Copy-Item -LiteralPath $source -Destination $staged
        if ((Get-FileHash -LiteralPath $staged -Algorithm SHA256).Hash -ne $sourceHash) {
            throw 'The build changed during copying. Wait until the build finishes and try again.'
        }
        if (Test-RunningInstance) {
            throw 'Minecraft started again. Leave it closed until the update finishes.'
        }
        foreach ($old in $active) {
            Move-Item -LiteralPath $old.FullName -Destination (Join-Path $backupDir $old.Name)
            $moved.Add($old)
        }
        Move-Item -LiteralPath $staged -Destination $destination
        $newMoved = $true
        if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $sourceHash) {
            throw 'The installed JAR failed verification.'
        }
    } catch {
        if ($newMoved) {
            Move-Item -LiteralPath $destination -Destination $staged
        }
        foreach ($old in $moved) {
            Move-Item -LiteralPath (Join-Path $backupDir $old.Name) -Destination $old.FullName
        }
        throw
    } finally {
        if (Test-Path -LiteralPath $staged) {
            Remove-Item -LiteralPath $staged
        }
    }

    Write-Host "Updated: $destination"
    Write-Host "Backup:  $backupDir"
    Write-Host 'You can start c3cu from PCL now.'
} finally {
    $lock.Dispose()
}

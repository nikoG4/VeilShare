[CmdletBinding()]
param(
    [Parameter(Mandatory, Position = 0)]
    [ValidateSet('devices', 'state', 'snapshot', 'wait-text', 'tap-text', 'input-text', 'back', 'logs', 'logs-follow', 'signal-log', 'clear-logs', 'launch', 'force-stop')]
    [string]$Command,

    [Parameter(Position = 1)]
    [ValidateSet('A', 'B')]
    [string]$Device,

    [Parameter(Position = 2)]
    [string]$Text,

    [string]$Serial,
    [int]$TimeoutSeconds = 30,
    [string]$Package = 'dev.veilshare.android.releasecheck'
)

$ErrorActionPreference = 'Stop'
$aliases = @{ A = 'emulator-5554'; B = 'emulator-5556' }
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw "adb not found: $adb" }

function Resolve-Serial {
    if ($Serial) { return $Serial }
    if (-not $Device) { throw 'Specify device alias A or B, or -Serial <serial>.' }
    return $aliases[$Device]
}

function Invoke-Adb([string[]]$Arguments) {
    $resolved = Resolve-Serial
    & $adb -s $resolved @Arguments
}

function Get-UiNodes {
    $null = Invoke-Adb @('shell', 'uiautomator', 'dump', '/sdcard/veilshare-e2e.xml')
    $raw = (Invoke-Adb @('shell', 'cat', '/sdcard/veilshare-e2e.xml')) -join "`n"
    try { [xml]$xml = $raw } catch { throw "UIAutomator returned invalid XML: $($_.Exception.Message)" }
    return @($xml.SelectNodes('//node'))
}

function Get-VisibleState {
    $nodes = Get-UiNodes
    $items = foreach ($node in $nodes) {
        $textValue = [string]$node.text
        $description = [string]$node.'content-desc'
        if ($textValue -or $description) {
            [pscustomobject]@{
                text = $textValue
                contentDescription = $description
                class = [string]$node.class
                enabled = [string]$node.enabled
                bounds = [string]$node.bounds
            }
        }
    }
    return @($items)
}

function Find-TextNode([string]$Needle) {
    $matches = Get-UiNodes | Where-Object {
        ([string]$_.text -eq $Needle) -or ([string]$_.'content-desc' -eq $Needle)
    }
    if (@($matches).Count -eq 0) { return $null }
    return @($matches)[0]
}

function Get-BoundsCenter([string]$Bounds) {
    if ($Bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw "Unexpected bounds: $Bounds" }
    return @([int](($matches[1] + $matches[3]) / 2), [int](($matches[2] + $matches[4]) / 2))
}

switch ($Command) {
    'devices' {
        & $adb devices -l
        break
    }
    'state' {
        Get-VisibleState | Format-Table -AutoSize
        break
    }
    'snapshot' {
        $visible = Get-VisibleState
        $screen = if ($visible.text -contains 'Esperando transferencia') { 'SharingReceiver' }
            elseif ($visible.text -contains 'Conectando…') { 'SharingSenderConnecting' }
            elseif ($visible.text -contains 'Enviar archivo') { 'SharingSenderPreparing' }
            elseif ($visible.text -contains 'Bóveda') { 'VaultBrowser' }
            else { 'Unknown' }
        $sharingState = if ($visible.text -contains 'Esperando transferencia') { 'Waiting' }
            elseif ($visible.text -contains 'Conectando…') { 'Connecting' }
            else { 'Unknown' }
        $referenceCodePresent = [bool]($visible.text | Where-Object { $_ -match '[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}-' })
        Write-Output "device=$Device"
        Write-Output "screen=$screen"
        Write-Output "sharingState=$sharingState"
        Write-Output "referenceCodePresent=$referenceCodePresent"
        Write-Output ('visibleText=[' + (($visible.text | Where-Object { $_ } | Select-Object -Unique) -join ' | ') + ']')
        break
    }
    'wait-text' {
        if (-not $Text) { throw 'wait-text requires text.' }
        $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
        do {
            if (Find-TextNode $Text) { Write-Output "found=$Text"; break }
            Start-Sleep -Milliseconds 300
        } while ((Get-Date) -lt $deadline)
        if (-not (Find-TextNode $Text)) { throw "Timed out after ${TimeoutSeconds}s waiting for: $Text" }
        break
    }
    'tap-text' {
        if (-not $Text) { throw 'tap-text requires text.' }
        $node = Find-TextNode $Text
        if ($null -eq $node) { throw "Text/content-description not found: $Text" }
        $center = Get-BoundsCenter ([string]$node.bounds)
        Invoke-Adb @('shell', 'input', 'tap', $center[0], $center[1]) | Out-Null
        Write-Output "tapped=$Text bounds=$($node.bounds)"
        break
    }
    'input-text' {
        if (-not $Text) { throw 'input-text requires text.' }
        # Android's shell input grammar uses %s for spaces. The caller should focus the field first.
        $escaped = $Text -replace ' ', '%s'
        Invoke-Adb @('shell', 'input', 'text', $escaped) | Out-Null
        Write-Output 'input_sent=true'
        break
    }
    'back' { Invoke-Adb @('shell', 'input', 'keyevent', '4') | Out-Null; break }
    'logs' { Invoke-Adb @('logcat', '-d', '-v', 'threadtime'); break }
    'logs-follow' { Invoke-Adb @('logcat', '-v', 'threadtime'); break }
    'signal-log' { Invoke-Adb @('logcat', '-d', '-v', 'threadtime', '-s', 'VeilShareSignal:I', 'VeilShareShare:I', 'VeilShareE2E:I', '*:S'); break }
    'clear-logs' { Invoke-Adb @('logcat', '-c') | Out-Null; break }
    'launch' { Invoke-Adb @('shell', 'am', 'start', '-n', "$Package/dev.veilshare.android.MainActivity") | Out-Null; break }
    'force-stop' { Invoke-Adb @('shell', 'am', 'force-stop', $Package) | Out-Null; break }
}

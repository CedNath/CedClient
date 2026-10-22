param([string]$Action = 'state', [string]$App = 'Spotify|AppleMusic|iTunes')
# Shuffle / repeat of the player through the Windows media session (the media keys have no keys for these).
# Prints "shuffle=<0|1> repeat=<0 off|1 track|2 list>", or "none" when no matching player is running.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
function Await($op, $type) { $t = $asTask.MakeGenericMethod($type).Invoke($null, @($op)); $t.Wait(-1) | Out-Null; $t.Result }
$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
$null = [Windows.Media.MediaPlaybackAutoRepeatMode, Windows.Media, ContentType = WindowsRuntime]
$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType = WindowsRuntime]
$mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])

if ($Action -eq 'watch') {
    # Runs until killed: one line a second, "track<TAB>artist<TAB>title<TAB>status<TAB>shuffle<TAB>repeat" or "none".
    # Players that keep "Artist - Song" out of their window title (Apple Music's main window) are read this way.
    while ($true) {
        try {
            $s = $mgr.GetSessions() | Where-Object { $_.SourceAppUserModelId -match $App } | Select-Object -First 1
            if (-not $s) { Write-Output 'none' }
            else {
                $p = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
                $i = $s.GetPlaybackInfo()
                $sh = if ($i.IsShuffleActive) { 1 } else { 0 }
                $rp = if ($null -ne $i.AutoRepeatMode) { [int]$i.AutoRepeatMode } else { 0 }
                Write-Output ("track`t{0}`t{1}`t{2}`t{3}`t{4}" -f $p.Artist, $p.Title, $i.PlaybackStatus, $sh, $rp)
            }
        } catch { Write-Output 'none' }
        [Console]::Out.Flush()
        Start-Sleep -Seconds 1
    }
}

$session = $mgr.GetSessions() | Where-Object { $_.SourceAppUserModelId -match $App } | Select-Object -First 1
if (-not $session) { Write-Output 'none'; exit 0 }

function Read-State {
    $info = $session.GetPlaybackInfo()
    $script:shuffle = if ($info.IsShuffleActive) { 1 } else { 0 }
    $script:repeat = if ($null -ne $info.AutoRepeatMode) { [int]$info.AutoRepeatMode } else { 0 }
}
Read-State

if ($Action -eq 'shuffle') {
    $null = Await ($session.TryChangeShuffleActiveAsync($shuffle -eq 0)) ([bool])
    Start-Sleep -Milliseconds 350
    Read-State
}
elseif ($Action -eq 'repeat') {
    # Same order as Spotify's own button: off -> repeat all -> repeat one -> off.
    $next = switch ($repeat) { 0 { 2 } 2 { 1 } default { 0 } }
    $null = Await ($session.TryChangeAutoRepeatModeAsync([Windows.Media.MediaPlaybackAutoRepeatMode]$next)) ([bool])
    Start-Sleep -Milliseconds 350
    Read-State
}
Write-Output "shuffle=$shuffle repeat=$repeat"

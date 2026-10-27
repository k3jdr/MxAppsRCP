<#
.SYNOPSIS
  Starts a built MxAppsRCP product, waits, saves a picture of its window, prints new errors from its log, and
  closes it. For checking a build without clicking through it.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File releng\tools\smoke-test.ps1 -App MxDataSQL -Seconds 10 -Shot $env:TEMP\mxdatasql.png
  powershell -ExecutionPolicy Bypass -File releng\tools\smoke-test.ps1 -App MxDataHUB -Arguments '-section','Portfolios'
#>
param(
    [Parameter(Mandatory = $true)][string]$App,
    [int]$Seconds = 10,
    [string]$Shot = "",
    [string[]]$Arguments = @(),
    [switch]$KeepOpen
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$id = $App.ToLower()
$exe = Join-Path $root "$App\com.k3jdr.$id.product\target\products\com.k3jdr.$id.product\win32\win32\x86_64\$App\$App.exe"
if (-not (Test-Path $exe)) { throw "Not built: $exe (run mvn verify first)" }

$log = Join-Path $env:APPDATA "MxAppsRCP\$App\workspace\.metadata\.log"
$logBefore = if (Test-Path $log) { (Get-Item $log).Length } else { 0 }

# A copy that is already running would take the launch over (single instance): stop it first.
Get-Process -Name $App -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 500

$argList = @('-consoleLog') + $Arguments
$p = Start-Process -FilePath $exe -ArgumentList $argList -PassThru
$deadline = (Get-Date).AddSeconds($Seconds)
$handle = [IntPtr]::Zero
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 500
    # The launcher starts javaw as a child; find a visible top-level window owned by either.
    $procs = @(Get-Process -Id $p.Id -ErrorAction SilentlyContinue) + @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($p.Id)" | ForEach-Object { Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue })
    foreach ($pr in $procs) { if ($pr -and $pr.MainWindowHandle -ne [IntPtr]::Zero -and $pr.MainWindowTitle -ne '') { $handle = $pr.MainWindowHandle } }
}

if ($handle -eq [IntPtr]::Zero) {
    Write-Output "NO WINDOW: $App did not show a window within $Seconds s"
} else {
    $title = (Get-Process | Where-Object { $_.MainWindowHandle -eq $handle } | Select-Object -First 1).MainWindowTitle
    Write-Output "WINDOW: '$title'"
    if ($Shot -ne "") {
        Add-Type -AssemblyName System.Drawing
        Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class MxWin {
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint flags);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
}
"@
        [MxWin]::SetForegroundWindow($handle) | Out-Null
        Start-Sleep -Milliseconds 400
        $r = New-Object MxWin+RECT
        [MxWin]::GetWindowRect($handle, [ref]$r) | Out-Null
        $w = $r.Right - $r.Left; $h = $r.Bottom - $r.Top
        $bmp = New-Object System.Drawing.Bitmap $w, $h
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $hdc = $g.GetHdc()
        [MxWin]::PrintWindow($handle, $hdc, 2) | Out-Null
        $g.ReleaseHdc($hdc); $g.Dispose()
        $bmp.Save($Shot, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
        Write-Output "SHOT: $Shot"
    }
}

if (Test-Path $log) {
    $text = Get-Content $log -Raw
    if ($text.Length -gt $logBefore) {
        Write-Output "---- new log entries ($log) ----"
        Write-Output $text.Substring([int]$logBefore)
    } else { Write-Output "LOG: no new entries" }
} else { Write-Output "LOG: none" }

if (-not $KeepOpen) {
    Get-CimInstance Win32_Process -Filter "ParentProcessId=$($p.Id)" | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
}

<#
.SYNOPSIS
  Starts a built MxAppsRCP product in isolation, waits, saves a picture of its window, prints new entries of its log,
  and closes it. For checking a build without clicking through it.

.DESCRIPTION
  The test copy never touches what you are doing:
   * it has its own workspace and settings (<repo>\.smoke\<App>\workspace) and its own empty secure storage
     (-eclipse.keyring), so it sees none of your saved connections, passwords or API keys;
   * it is one copy per workspace, so a copy of the app you have open is neither handed the test's arguments nor
     closed;
   * its window opens without taking the keyboard focus (SW_SHOWNOACTIVATE) and is never brought to the front, so
     what you type keeps going where you are typing.
  Only the test copies this script started are closed.

.EXAMPLE
  & releng\tools\smoke-test.ps1 -App MxDataSQL -Seconds 10 -Shot D:\MyCodeGIT\MxAppsRCP\.smoke\mxdatasql.png
  & releng\tools\smoke-test.ps1 -App MxDataHUB -Arguments '-section','Portfolios'
#>
param(
    [Parameter(Mandatory = $true)][string]$App,
    [int]$Seconds = 10,
    [string]$Shot = "",
    [string[]]$Arguments = @(),
    [switch]$KeepOpen,
    # Start from fresh settings (deletes this script's own test workspace, never the app's real one).
    [switch]$Fresh
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$id = $App.ToLower()
$exe = Join-Path $root "$App\com.k3jdr.$id.product\target\products\com.k3jdr.$id.product\win32\win32\x86_64\$App\$App.exe"
if (-not (Test-Path $exe)) { throw "Not built: $exe (run mvn verify first)" }

# On D: with the repository (C: is short of space): <repo>\.smoke\<App>, ignored by git.
$smoke = Join-Path $root ".smoke\$App"
$workspace = Join-Path $smoke 'workspace'
$keyring = Join-Path $smoke 'secure_storage'
if ($Fresh -and (Test-Path $workspace)) { Remove-Item -Recurse -Force $workspace }
New-Item -ItemType Directory -Force $smoke | Out-Null

Add-Type -AssemblyName System.Drawing
if (-not ('MxSmoke' -as [type])) {
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class MxSmoke {
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint flags);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();

  [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
  struct STARTUPINFO { public int cb; public string lpReserved, lpDesktop, lpTitle; public int dwX, dwY, dwXSize, dwYSize,
    dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags; public short wShowWindow, cbReserved2; public IntPtr lpReserved2,
    hStdInput, hStdOutput, hStdError; }
  [StructLayout(LayoutKind.Sequential)] struct PROCESS_INFORMATION { public IntPtr hProcess, hThread; public int dwProcessId, dwThreadId; }
  [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
  static extern bool CreateProcess(string app, System.Text.StringBuilder cmd, IntPtr pa, IntPtr ta, bool inherit, int flags,
    IntPtr env, string dir, ref STARTUPINFO si, out PROCESS_INFORMATION pi);
  [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);

  /// Starts a program whose first window shows without being activated (SWT then also skips bringing it to the top).
  public static int StartNoActivate(string exe, string args) {
    var si = new STARTUPINFO(); si.cb = Marshal.SizeOf(si);
    si.dwFlags = 1;          // STARTF_USESHOWWINDOW
    si.wShowWindow = 4;      // SW_SHOWNOACTIVATE
    PROCESS_INFORMATION pi;
    var cmd = new System.Text.StringBuilder("\"" + exe + "\" " + args);
    if (!CreateProcess(exe, cmd, IntPtr.Zero, IntPtr.Zero, false, 0, IntPtr.Zero, System.IO.Path.GetDirectoryName(exe), ref si, out pi))
      throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
    CloseHandle(pi.hThread); CloseHandle(pi.hProcess);
    return pi.dwProcessId;
  }
}
"@
}

function Quote([string]$a) { if ($a -match '[\s"]') { '"' + ($a -replace '"', '\"') + '"' } else { $a } }

# Close test copies a previous run of this script left behind (recognised by their test workspace), nothing else.
Get-CimInstance Win32_Process -Filter "Name='$App.exe'" | Where-Object { $_.CommandLine -like "*$workspace*" } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

$log = Join-Path $workspace '.metadata\.log'
$logBefore = if (Test-Path $log) { (Get-Item $log).Length } else { 0 }

$argList = @('-data', $workspace, '-eclipse.keyring', $keyring) + $Arguments   # the log goes to the workspace's .metadata\.log (no -consoleLog: on Windows it opens a console window)
$procId = [MxSmoke]::StartNoActivate($exe, (($argList | ForEach-Object { Quote $_ }) -join ' '))

$deadline = (Get-Date).AddSeconds($Seconds)
$handle = [IntPtr]::Zero
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 500
    $pr = Get-Process -Id $procId -ErrorAction SilentlyContinue
    if (-not $pr) { break }
    $pr.Refresh()
    if ($pr.MainWindowHandle -ne [IntPtr]::Zero -and $pr.MainWindowTitle -ne '') { $handle = $pr.MainWindowHandle }
}

if ($handle -eq [IntPtr]::Zero) {
    if (Get-Process -Id $procId -ErrorAction SilentlyContinue) { Write-Output "NO WINDOW: $App did not show a window within $Seconds s" }
    else { Write-Output "EXITED: $App ended before showing a window (see the log below)" }
} else {
    $pr = Get-Process -Id $procId
    Write-Output "WINDOW: '$($pr.MainWindowTitle)'$(if ([MxSmoke]::GetForegroundWindow() -eq $handle) { ' (took the focus)' })"
    if ($Shot -ne "") {
        $r = New-Object MxSmoke+RECT
        [MxSmoke]::GetWindowRect($handle, [ref]$r) | Out-Null
        $w = $r.Right - $r.Left; $h = $r.Bottom - $r.Top
        $bmp = New-Object System.Drawing.Bitmap $w, $h
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $hdc = $g.GetHdc()
        [MxSmoke]::PrintWindow($handle, $hdc, 2) | Out-Null   # PW_RENDERFULLCONTENT: works behind other windows
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
    Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
}

$ErrorActionPreference = 'Stop'

param(
    [Parameter(Mandatory = $true)]
    [int]$ProcessId,

    [Parameter(Mandatory = $true)]
    [string]$OutputPath
)

Add-Type -AssemblyName System.Drawing
Add-Type @'
using System;
using System.Runtime.InteropServices;

public static class WindowCaptureNative {
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    [DllImport("user32.dll")]
    public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
}
'@

$process = Get-Process -Id $ProcessId
if ($process.MainWindowHandle -eq 0) {
    throw "Process $ProcessId has no main window."
}

$rect = New-Object WindowCaptureNative+RECT
[WindowCaptureNative]::GetWindowRect($process.MainWindowHandle, [ref]$rect) | Out-Null

$width = $rect.Right - $rect.Left
$height = $rect.Bottom - $rect.Top
if ($width -le 0 -or $height -le 0) {
    throw "Window rectangle is invalid: $width x $height."
}

$directory = Split-Path -Path $OutputPath -Parent
if (-not [string]::IsNullOrWhiteSpace($directory)) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}

$bitmap = New-Object System.Drawing.Bitmap ($width), ($height)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.CopyFromScreen(
    [System.Drawing.Point]::new($rect.Left, $rect.Top),
    [System.Drawing.Point]::Empty,
    [System.Drawing.Size]::new($width, $height)
)
$bitmap.Save($OutputPath, [System.Drawing.Imaging.ImageFormat]::Png)
$graphics.Dispose()
$bitmap.Dispose()

Write-Output $OutputPath

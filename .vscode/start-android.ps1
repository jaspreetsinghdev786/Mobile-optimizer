# Runs when this folder opens in VS Code: starts the emulator if no device is connected.
$sdk = "$env:LOCALAPPDATA\Android\Sdk"

$devices = & "$sdk\platform-tools\adb.exe" devices | Select-String "device$"
if (-not $devices) {
    Write-Host "Starting emulator Pixel6_API34..."
    Start-Process "$sdk\emulator\emulator.exe" -ArgumentList "-avd", "Pixel6_API34" -WindowStyle Minimized
} else {
    Write-Host "Device already connected."
}

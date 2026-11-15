# AI Gateway PC 一键启动器（PowerShell 版，供手动/排障使用）
# 桌面入口是 launcher.vbs（纯 WSH 零闪烁）；本脚本功能等价，带详细日志
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$logDir = Join-Path $env:USERPROFILE ".aigw"
$log = Join-Path $logDir "launcher.log"
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }
function Log($m) { try { Add-Content -Path $log -Value "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  $m" } catch {} }

try {
    $alive = $false
    try {
        $null = & curl.exe --noproxy "*" -s -m 2 "http://127.0.0.1:8791/api/service"
        if ($LASTEXITCODE -eq 0) { $alive = $true }
    } catch { Log "probe exception: $($_.Exception.Message)" }
    Log "probe alive=$alive"
    if (-not $alive) {
        Log "starting via aigw-tray.bat"
        Start-Process -WindowStyle Hidden -FilePath (Join-Path $root 'aigw-tray.bat') -WorkingDirectory $root
        for ($i = 0; $i -lt 18; $i++) {
            Start-Sleep -Seconds 1
            try {
                $null = & curl.exe --noproxy "*" -s -m 2 "http://127.0.0.1:8791/api/service"
                if ($LASTEXITCODE -eq 0) { $alive = $true; break }
            } catch {}
        }
        Log "after start alive=$alive"
    }
} catch {
    Log "LAUNCHER ERROR: $($_.Exception.ToString().Replace([char]13+[char]10, ' | '))"
}
Start-Process "http://127.0.0.1:8791/"

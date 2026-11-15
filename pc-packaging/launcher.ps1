# AI Gateway PC 一键启动器：网关没跑就静默拉起（无窗口 + 托盘图标），然后打开管理台
# 全程写日志到 ~\.aigw\launcher.log，失败可追查
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$log  = "$env:USERPROFILE\.aigw\launcher.log"
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
        Start-Sleep -Seconds 9
        Log "start command issued"
    }
} catch {
    Log "LAUNCHER ERROR: $($_.Exception.ToString().Replace([char]13+[char]10, ' | '))"
}
Start-Process "http://127.0.0.1:8791/"

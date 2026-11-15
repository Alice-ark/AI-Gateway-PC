# AI Gateway PC 一键启动器：网关没跑就静默拉起，然后打开管理台
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$alive = $false
try {
    $null = & curl.exe --noproxy "*" -s -m 2 "http://127.0.0.1:8791/api/service"
    if ($LASTEXITCODE -eq 0) { $alive = $true }
} catch {}
if (-not $alive) {
    Start-Process -WindowStyle Hidden -FilePath "$root\bin\aigw.bat" -ArgumentList "serve", "--port", "8790"
    Start-Sleep -Seconds 8
}
Start-Process "http://127.0.0.1:8791/"

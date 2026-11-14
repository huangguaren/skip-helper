# 无障碍服务诊断（小米 / HyperOS 专用）
#
#   .\tools\diagnose-accessibility.ps1              # 只读诊断
#   .\tools\diagnose-accessibility.ps1 -Enable      # 直接写 enabled_accessibility_services
#   .\tools\diagnose-accessibility.ps1 -Watch       # 跟 logcat，看服务绑定/崩溃
#   .\tools\diagnose-accessibility.ps1 -Unrestrict  # 解除 HyperOS 受限制设置（appops）
#   .\tools\diagnose-accessibility.ps1 -Overlay     # 授予悬浮窗权限（appops）
#
# 用的是仓库自带的 platform-tools，不依赖系统 PATH 里有没有 adb。

param(
    [switch]$Enable,
    [switch]$Watch,
    [switch]$Overlay,
    [switch]$Unrestrict
)

$ErrorActionPreference = 'Stop'
$pkg = 'com.example.skiphelper'
$component = "$pkg/$pkg.SkipService"

$adb = Join-Path $PSScriptRoot 'android-sdk\platform-tools\adb.exe'
if (-not (Test-Path $adb)) {
    $adb = Join-Path $PSScriptRoot '..\tools\android-sdk\platform-tools\adb.exe'
}
if (-not (Test-Path $adb)) {
    Write-Host "找不到 adb，期望在 tools\android-sdk\platform-tools\adb.exe" -ForegroundColor Red
    exit 1
}

Write-Host "adb: $adb"
& $adb start-server | Out-Null

$lines = @((& $adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match 'device$' })
if ($lines.Count -eq 0) {
    Write-Host "`n没有检测到设备。先做这几步：" -ForegroundColor Yellow
    Write-Host "  1. 设置 → 我的设备 → 全部参数 → 连点「OS 版本」7 次打开开发者选项"
    Write-Host "  2. 设置 → 更多设置 → 开发者选项 → 打开「USB 调试」和「USB 安装」"
    Write-Host "  3. 手机上会弹「允许 USB 调试吗」，勾选「始终允许」后确定"
    Write-Host "  4. 重新运行本脚本"
    exit 1
}
$serial = (($lines[0] -split '\s+') | Where-Object { $_ })[0]
Write-Host "设备: $serial`n"

function Invoke-Adb {
    param([string[]]$AdbArgs)
    & $adb -s $serial @AdbArgs
}

Write-Host '=== 1. 系统里的无障碍开关 ==='
$enabledList = Invoke-Adb @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')
$enabledFlag = Invoke-Adb @('shell', 'settings', 'get', 'secure', 'accessibility_enabled')
Write-Host "enabled_accessibility_services = $enabledList"
Write-Host "accessibility_enabled          = $enabledFlag"
$listed = $enabledList -match [regex]::Escape($component)

Write-Host "`n=== 2. 无障碍管理器状态（是否真的绑上了）==="
$dumpsys = Invoke-Adb @('shell', 'dumpsys', 'accessibility')
$dumpsys | Select-String -Pattern 'Enabled services|Bound services|skiphelper' -Context 0, 4 |
    ForEach-Object { $_.Line.Trim() }
$bound = ($dumpsys -join "`n") -match 'Bound services:[\s\S]*skiphelper'

Write-Host "`n=== 3. 包状态 / 安装来源（受限制设置的判定依据）==="
Invoke-Adb @('shell', 'dumpsys', 'package', $pkg) |
    Select-String -Pattern 'versionName|installerPackageName|flags=|restricted' |
    Select-Object -First 12 | ForEach-Object { $_.Line.Trim() }

Write-Host "`n=== 4. 悬浮窗权限 ==="
Invoke-Adb @('shell', 'appops', 'get', $pkg, 'SYSTEM_ALERT_WINDOW') | ForEach-Object { $_.Trim() }

Write-Host "`n=== 结论 ==="
if (-not $listed) {
    Write-Host "系统列表里没有这个服务 → 开关没被真正打开（多半是 HyperOS「受限制设置」拦的）" -ForegroundColor Yellow
} elseif (-not $bound) {
    Write-Host "列表里有、但没绑上 → 服务进程没起来或被系统掐掉（省电策略 / 绑定期崩溃）" -ForegroundColor Yellow
} else {
    Write-Host "已绑定，服务在跑。" -ForegroundColor Green
}

if ($Enable) {
    Write-Host "`n=== 尝试写入系统列表（需要 shell 持有 WRITE_SECURE_SETTINGS）==="
    $merged = (@($enabledList -split ':') + $component | Where-Object { $_ }) -join ':'
    $out1 = (Invoke-Adb @('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', $merged)) -join "`n"
    $out2 = (Invoke-Adb @('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1')) -join "`n"
    $denied = ($out1 + $out2) -match 'SecurityException|WRITE_SECURE_SETTINGS'
    Start-Sleep -Seconds 2
    $after = (Invoke-Adb @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')) -join ''
    Write-Host "  services = $after"
    if ($denied) {
        Write-Host "写入被拒绝：这台 HyperOS 把 WRITE_SECURE_SETTINGS 从 shell 上摘掉了，只能手动拨开关。" -ForegroundColor Yellow
        Write-Host "手动路径：设置 → 应用设置 → 应用管理 → 跳过助手 → 右上角 ⋮ → 允许受限制的设置；再进 无障碍 → 已下载的应用。"
    } elseif ($after -notmatch [regex]::Escape($component)) {
        Write-Host "写入成功但被系统改回 → 「受限制设置」还没放开。" -ForegroundColor Yellow
    } else {
        Write-Host "写入成功，服务已在系统列表里。" -ForegroundColor Green
    }
}

if ($Unrestrict) {
    Write-Host "`n=== 解除 HyperOS「受限制设置」 ==="
    # 侧载 App 的无障碍开关被 ACCESS_RESTRICTED_SETTINGS 这一 appop 拦掉的，
    # 正常路径是 应用信息 → ⋮ → 允许受限制的设置；这里用 appops 达到同样效果。
    $before = (Invoke-Adb @('shell', 'appops', 'get', $pkg, 'ACCESS_RESTRICTED_SETTINGS')) -join ''
    Write-Host "  之前: $before"
    $r = (Invoke-Adb @('shell', 'appops', 'set', $pkg, 'ACCESS_RESTRICTED_SETTINGS', 'allow')) -join "`n"
    Write-Host "  设置: $r"
    $after = (Invoke-Adb @('shell', 'appops', 'get', $pkg, 'ACCESS_RESTRICTED_SETTINGS')) -join ''
    Write-Host "  之后: $after"
    if ($after -match 'allow') {
        Write-Host "  已放开。现在去 设置 → 无障碍 → 已下载的应用 → 跳过助手，把开关打开。" -ForegroundColor Green
    } else {
        Write-Host "  appops 没生效，只能走界面：应用信息 → 右上角 ⋮ → 允许受限制的设置。" -ForegroundColor Yellow
    }
}

if ($Overlay) {
    Write-Host "`n=== 授予悬浮窗（提示浮层用，不影响跳过主流程）==="
    $o = (Invoke-Adb @('shell', 'appops', 'set', $pkg, 'SYSTEM_ALERT_WINDOW', 'allow')) -join "`n"
    if ($o -match 'SecurityException|denial') { Write-Host "appops 被拒绝，去 应用信息 → 其他权限 → 显示在其他应用上层 手动允许。" -ForegroundColor Yellow }
    Write-Host ((Invoke-Adb @('shell', 'appops', 'get', $pkg, 'SYSTEM_ALERT_WINDOW')) -join '')
}

if ($Watch) {
    Write-Host "`n=== logcat（Ctrl+C 退出；先在手机上拨一次无障碍开关）==="
    & $adb -s $serial logcat -c
    & $adb -s $serial logcat -s SkipHelper AccessibilityManagerService AndroidRuntime
}

# 创建 GitHub Release 并上传 APK
#
#   $env:GITHUB_TOKEN = "ghp_xxx"      # 需要 repo 权限（经典 token 勾 repo，或 fine-grained 勾 Contents: Read and write）
#   .\scripts\make-release.ps1
#
# 也可以不传参，用 -Token 显式传入。token 只在本次进程内存里使用，不落盘。

param(
    [string]$Token = $env:GITHUB_TOKEN,
    [string]$Tag = 'v0.12.2',
    [string]$Repo = 'huangguaren/skip-helper',
    [string]$Apk = 'dist/SkipHelper-0.12.2-debug.apk',
    [string]$Notes = 'docs/RELEASE_NOTES-v0.12.2.md'
)

if (-not $Token) { throw '缺少 GITHUB_TOKEN（或 -Token 参数）' }
$root = Split-Path $PSScriptRoot -Parent
$apkPath = Join-Path $root $Apk
$notesPath = Join-Path $root $Notes
if (-not (Test-Path $apkPath)) { throw "APK 不存在: $apkPath" }

$headers = @{
    Authorization          = "token $Token"
    Accept                 = 'application/vnd.github+json'
    'X-GitHub-Api-Version' = '2022-11-28'
}

$body = @{
    tag_name   = $Tag
    name       = "SkipHelper $($Tag.TrimStart('v'))"
    body       = (Get-Content $notesPath -Raw)
    draft      = $false
    prerelease = $false
} | ConvertTo-Json -Depth 3

Write-Host "创建 release $Tag ..."
$rel = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$Repo/releases" `
    -Headers $headers -Body $body -ContentType 'application/json'
Write-Host "  release id = $($rel.id)  url = $($rel.html_url)"

$uploadUrl = "https://uploads.github.com/repos/$Repo/releases/$($rel.id)/assets?name=" + [uri]::EscapeDataString((Split-Path $apkPath -Leaf))
Write-Host "上传资产 $(Split-Path $apkPath -Leaf) ..."
$asset = Invoke-RestMethod -Method Post -Uri $uploadUrl -Headers $headers `
    -ContentType 'application/vnd.android.package-archive' -InFile $apkPath
Write-Host "  资产大小 = $([math]::Round($asset.size/1MB,2)) MB"

Write-Host "`n完成：$($rel.html_url)"

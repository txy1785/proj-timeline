param(
    [string]$MySql = 'mysql',
    [string]$HostName = '127.0.0.1',
    [int]$Port = 3306,
    [string]$User = 'root',
    [string]$Database = 'proj_timeline'
)
$ErrorActionPreference = 'Stop'
if ($Database -ne 'proj_timeline') { throw 'demo_seed.sql 固定使用 proj_timeline，仅允许导入该本地演示库。' }
$seed = Join-Path (Split-Path $PSScriptRoot -Parent) 'docs\demo_seed.sql'
Get-Content -Raw -Encoding UTF8 $seed | & $MySql --protocol=tcp --host=$HostName --port=$Port --user=$User $Database
if ($LASTEXITCODE -ne 0) { throw '演示数据导入失败。' }
Write-Host '演示数据导入完成。'

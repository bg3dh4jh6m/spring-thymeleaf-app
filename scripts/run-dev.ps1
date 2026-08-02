$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$environmentFile = Join-Path $projectRoot '.env'

if (-not (Test-Path -LiteralPath $environmentFile)) {
    throw 'Липсва локален .env файл. Копирайте .env.example и попълнете PostgreSQL настройките.'
}

Get-Content -LiteralPath $environmentFile -Encoding UTF8 |
    Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } |
    ForEach-Object {
        $name, $value = $_ -split '=', 2
        Set-Item -Path ("Env:" + $name) -Value $value
    }

$env:SPRING_PROFILES_ACTIVE = 'postgres'
Set-Location -LiteralPath $projectRoot
& .\mvnw.cmd spring-boot:run

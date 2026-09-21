$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
Push-Location frontend
try {
    npm ci
    if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' }
    npm run build
    if ($LASTEXITCODE -ne 0) { throw 'frontend build failed' }
} finally { Pop-Location }
Remove-Item -Recurse -Force backend/src/main/resources/static -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force backend/src/main/resources/static | Out-Null
Copy-Item frontend/dist/* backend/src/main/resources/static -Recurse -Force
mvn -B -f backend/pom.xml clean verify
if ($LASTEXITCODE -ne 0) { throw 'backend build failed' }

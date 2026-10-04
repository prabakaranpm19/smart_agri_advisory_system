# Create bin directory if it doesn't exist
$binDir = Join-Path $PSScriptRoot "bin"
if (-not (Test-Path $binDir)) {
    New-Item -ItemType Directory -Path $binDir | Out-Null
}

# Stop any running Java process on port 8080 to release file locks & free port
try {
    Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue | ForEach-Object {
        Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue
    }
    Get-Process | Where-Object { $_.ProcessName -like "*java*" } | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
} catch {}

# Find JDK directory and java.exe
$javaBin = "java"
$jdkPaths = @(
    "C:\Users\praba\.jdks\openjdk-26.0.1\bin",
    "C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\jbr\bin",
    "C:\Program Files\JetBrains\CLion 2025.3.2\jbr\bin"
)
foreach ($p in $jdkPaths) {
    $testPath = Join-Path $p "java.exe"
    if (Test-Path -Path $testPath -ErrorAction SilentlyContinue) {
        $javaBin = $testPath
        if ($env:PATH -notlike "*$p*") {
            $env:PATH = "$p;" + $env:PATH
        }
        break
    }
}

# Compile CropAdvisoryApp
Write-Host "Compiling CropAdvisoryApp.java into bin/..." -ForegroundColor Cyan
$javacBin = Join-Path (Split-Path $javaBin) "javac.exe"
if (-not (Test-Path $javacBin -ErrorAction SilentlyContinue)) { $javacBin = "javac" }

& $javacBin -d bin -cp "lib/sqlite-jdbc-3.45.3.0.jar" CropAdvisoryApp.java

$targetClass = Join-Path $binDir "CropAdvisoryApp.class"
if (Test-Path $targetClass) {
    Write-Host "==========================================================" -ForegroundColor Green
    Write-Host " Launching Smart Crop Advisory Server! Open browser at:" -ForegroundColor Green
    Write-Host " 👉 http://localhost:8080" -ForegroundColor Yellow
    Write-Host " 👉 http://127.0.0.1:8080" -ForegroundColor Yellow
    Write-Host " Demo Farmer: Username: demo | PIN: 1234" -ForegroundColor Cyan
    Write-Host " Demo Admin:  Username: admin | PIN: 9999" -ForegroundColor Cyan
    Write-Host " Press Ctrl+C in this terminal to shut down." -ForegroundColor Green
    Write-Host "==========================================================" -ForegroundColor Green
    
    # Run the web server directly in foreground
    & $javaBin -cp "bin;lib/*" CropAdvisoryApp
} else {
    Write-Host "Compilation failed. Please check Java errors." -ForegroundColor Red
}

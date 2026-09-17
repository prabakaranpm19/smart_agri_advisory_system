# Create bin directory if it doesn't exist
$binDir = Join-Path $PSScriptRoot "bin"
if (-not (Test-Path $binDir)) {
    New-Item -ItemType Directory -Path $binDir | Out-Null
}

# Add JDK to path if javac is not found
if (-not (Get-Command javac -ErrorAction SilentlyContinue)) {
    $jdkPaths = @(
        "C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\jbr\bin",
        "C:\Program Files\JetBrains\CLion 2025.3.2\jbr\bin",
        "C:\Users\praba\.jdks\openjdk-26.0.1\bin"
    )
    foreach ($p in $jdkPaths) {
        if (Test-Path (Join-Path $p "javac.exe")) {
            $env:PATH = "$p;" + $env:PATH
            break
        }
    }
}


# Compile CropAdvisoryApp
Write-Host "Compiling CropAdvisoryApp.java into bin/..." -ForegroundColor Cyan
& javac -d bin -cp "lib/*" CropAdvisoryApp.java

if ($LASTEXITCODE -eq 0) {
    Write-Host "Compilation successful!" -ForegroundColor Green
    Write-Host "Launching web server. Go to http://localhost:8080" -ForegroundColor Green
    Write-Host "Press Ctrl+C in this terminal to shut down." -ForegroundColor Yellow
    # Run the server
    & java -cp "bin;lib/*" CropAdvisoryApp
} else {
    Write-Host "Compilation failed. Please check error logs." -ForegroundColor Red
}

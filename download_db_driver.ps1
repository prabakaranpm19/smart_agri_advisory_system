# Create lib directory if it doesn't exist
$libDir = Join-Path $PSScriptRoot "lib"
if (-not (Test-Path $libDir)) {
    New-Item -ItemType Directory -Path $libDir | Out-Null
    Write-Host "Created directory: $libDir"
}

# Download SQLite JDBC jar
$sqliteUrl = "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.45.3.0/sqlite-jdbc-3.45.3.0.jar"
$sqlitePath = Join-Path $libDir "sqlite-jdbc-3.45.3.0.jar"

if (-not (Test-Path $sqlitePath)) {
    Write-Host "Downloading SQLite JDBC driver..."
    Invoke-WebRequest -Uri $sqliteUrl -OutFile $sqlitePath
    Write-Host "Downloaded successfully to $sqlitePath"
} else {
    Write-Host "SQLite JDBC driver already exists at $sqlitePath"
}

# Download SLF4J API jar
$slf4jApiUrl = "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar"
$slf4jApiPath = Join-Path $libDir "slf4j-api-2.0.13.jar"

if (-not (Test-Path $slf4jApiPath)) {
    Write-Host "Downloading SLF4J API..."
    Invoke-WebRequest -Uri $slf4jApiUrl -OutFile $slf4jApiPath
    Write-Host "Downloaded successfully to $slf4jApiPath"
} else {
    Write-Host "SLF4J API already exists at $slf4jApiPath"
}

# Download SLF4J Simple jar
$slf4jSimpleUrl = "https://repo1.maven.org/maven2/org/slf4j/slf4j-simple/2.0.13/slf4j-simple-2.0.13.jar"
$slf4jSimplePath = Join-Path $libDir "slf4j-simple-2.0.13.jar"

if (-not (Test-Path $slf4jSimplePath)) {
    Write-Host "Downloading SLF4J Simple binding..."
    Invoke-WebRequest -Uri $slf4jSimpleUrl -OutFile $slf4jSimplePath
    Write-Host "Downloaded successfully to $slf4jSimplePath"
} else {
    Write-Host "SLF4J Simple binding already exists at $slf4jSimplePath"
}

# ==========================================================
# StandaloneQueue Build Script (PowerShell)
# Target: Paper API / JDK 25
# ==========================================================

$ErrorActionPreference = "Stop"
Write-Host "[1/3] Checking dependencies in .lib/..." -ForegroundColor Cyan

$dest = "$PSScriptRoot\.lib"
if (-not (Test-Path $dest)) { New-Item -ItemType Directory -Path $dest -Force | Out-Null }

$libs = @(
    "https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/1.21.4-R0.1-SNAPSHOT/paper-api-1.21.4-R0.1-20250925.065901-231.jar",
    "https://repo1.maven.org/maven2/net/kyori/adventure-api/4.18.0/adventure-api-4.18.0.jar",
    "https://repo1.maven.org/maven2/net/kyori/adventure-text-minimessage/4.18.0/adventure-text-minimessage-4.18.0.jar",
    "https://repo1.maven.org/maven2/net/kyori/adventure-text-serializer-legacy/4.18.0/adventure-text-serializer-legacy-4.18.0.jar",
    "https://repo1.maven.org/maven2/net/kyori/adventure-key/4.18.0/adventure-key-4.18.0.jar",
    "https://repo1.maven.org/maven2/net/kyori/examination-api/1.3.0/examination-api-1.3.0.jar",
    "https://repo1.maven.org/maven2/org/jetbrains/annotations/24.1.0/annotations-24.1.0.jar",
    "https://repo1.maven.org/maven2/net/md-5/bungeecord-chat/1.20-R0.2/bungeecord-chat-1.20-R0.2.jar"
)

foreach ($url in $libs) {
    $fname = $url.Split('/')[-1]
    $target = "$dest\$fname"
    if (-not (Test-Path $target)) {
        Write-Host "  - Downloading $fname..." -ForegroundColor Gray
        Invoke-WebRequest -Uri $url -OutFile $target
    }
}

Write-Host "[2/3] Compiling Java classes (JDK 25)..." -ForegroundColor Cyan
$outDir = "$PSScriptRoot\target\classes"
if (Test-Path $outDir) { Remove-Item -Path $outDir -Recurse -Force }
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

$sources = Get-ChildItem -Path "$PSScriptRoot\src\main\java" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
$cp = (Get-ChildItem -Path "$dest\*.jar" | ForEach-Object { $_.FullName }) -join ";"

javac -encoding UTF-8 -cp $cp -d $outDir $sources

Write-Host "[3/3] Packaging JAR file..." -ForegroundColor Cyan
Copy-Item -Path "$PSScriptRoot\src\main\resources\*" -Destination "$outDir\" -Recurse -Force

$targetDir = "$PSScriptRoot\target"
$jarPath = "$targetDir\StandaloneQueue-1.0.0.jar"
jar --create --file $jarPath -C $outDir .

Write-Host "`n>>> BUILD SUCCESSFUL!" -ForegroundColor Green
Write-Host ">>> Plugin JAR: $jarPath" -ForegroundColor Yellow

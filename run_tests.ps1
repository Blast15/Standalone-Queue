# Test Runner Script
$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
$libDir = "$root\.lib"
$classesDir = "$root\target\classes"
$testClassesDir = "$root\target\test-classes"

if (-not (Test-Path $testClassesDir)) { New-Item -ItemType Directory -Path $testClassesDir -Force | Out-Null }

$jars = @(Get-ChildItem -Path "$libDir\*.jar" | ForEach-Object { $_.FullName })
$mainSources = Get-ChildItem -Path "$root\src\main\java" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
$testSources = Get-ChildItem -Path "$root\src\test\java" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }

$cpMain = ($jars) -join ";"
javac -encoding UTF-8 -cp $cpMain -d $classesDir $mainSources

$cpTest = ($jars + @($classesDir)) -join ";"
javac -encoding UTF-8 -cp $cpTest -d $testClassesDir $testSources

$runnerCp = ($jars + @($classesDir, $testClassesDir)) -join ";"
$junitJar = "$libDir\junit-platform-console-standalone-1.10.2.jar"

java -jar $junitJar execute -cp $runnerCp --scan-class-path --disable-banner --details=tree

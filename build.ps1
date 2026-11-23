# build.ps1 - Build-Helfer fuer rewisp (Eltern-POM mit den Modulen src_rewisp + src_mcp)
#
# Aufruf:
#   .\build.ps1              bzw. .\build.ps1 test   -> volle Test-Suite ueber beide Module (Reaktor)
#   .\build.ps1 -Jar         bzw. .\build.ps1 jar    -> fat JAR: src_mcp\target\rewisp-standalone.jar
#   .\build.ps1 clean                               -> mvn clean
#
# Voraussetzungen: JDK 21 (z. B. scoop install temurin21-jdk) und Maven auf dem PATH.
# Gegen Maven Central statt Firmen-Mirror: settings-central.xml wird als MAVEN_ARGS
# exportiert, damit auch die vom Build-Resolver gestarteten mvn-Kindprozesse zentral
# aufloesen (Maven >= 3.9 wertet MAVEN_ARGS aus).

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('test', 'jar', 'clean')]
    [string]$Target = 'test',
    [switch]$Jar
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# --- JDK 21 pruefen ---
$javaOut = (& java -version 2>&1) -join "`n"
if ($javaOut -match 'version "(\d+)') {
    $javaMajor = [int]$Matches[1]
} else {
    throw "java nicht gefunden. Bitte JDK 21 installieren (scoop install temurin21-jdk) und auf den PATH legen."
}
if ($javaMajor -lt 21) {
    throw "JDK 21 erforderlich, gefunden: Major $javaMajor. JAVA_HOME auf ein JDK 21 setzen (z. B. scoop: temurin21-jdk)."
}
Write-Host "JDK $javaMajor OK"

# --- Maven pruefen ---
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw "mvn nicht gefunden. Bitte Maven installieren (scoop install maven) und auf den PATH legen."
}

# --- settings-central.xml als MAVEN_ARGS (auch fuer mvn-Kindprozesse des Resolvers) ---
$settings = Join-Path $PSScriptRoot 'settings-central.xml'
$mavenArgs = @()
if (Test-Path $settings) {
    $mavenArgs = @('-s', $settings, '-gs', $settings)
    $env:MAVEN_ARGS = ($mavenArgs -join ' ')
    Write-Host "MAVEN_ARGS=$env:MAVEN_ARGS"
} else {
    Write-Host "Hinweis: settings-central.xml fehlt - es wird der Default-Mirror verwendet."
}

function Invoke-Mvn {
    param([string[]]$MvnArgs)
    & mvn -B @mavenArgs @MvnArgs
    if ($LASTEXITCODE -ne 0) { throw "mvn $($MvnArgs -join ' ') fehlgeschlagen (exit $LASTEXITCODE)" }
}

if ($Jar) { $Target = 'jar' }

switch ($Target.ToLowerInvariant()) {
    'test' {
        Invoke-Mvn @('test')
        Write-Host "Tests grün: Reaktor ueber src_rewisp + src_mcp"
    }
    'jar' {
        Invoke-Mvn @('clean', 'package', '-DskipTests')
        $jarPath = Join-Path $PSScriptRoot 'build\rewisp-mcp\rewisp-standalone.jar'
        if (Test-Path $jarPath) {
            Write-Host "OK: fat JAR gebaut -> $jarPath"
        } else {
            throw "fat JAR nicht gefunden: $jarPath"
        }
    }
    'clean' {
        Invoke-Mvn @('clean')
    }
}

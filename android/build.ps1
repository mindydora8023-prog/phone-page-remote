param(
    [string] $SdkRoot = $env:ANDROID_SDK_ROOT,
    [string] $JdkHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$toolchain = Join-Path (Split-Path $project) '.toolchain\android'
if (-not $SdkRoot) { $SdkRoot = $env:ANDROID_HOME }
if ($SdkRoot) {
    $androidJar = Join-Path $SdkRoot 'platforms\android-36\android.jar'
    $buildTools = Join-Path $SdkRoot 'build-tools\36.0.0'
} else {
    $androidJar = Join-Path $toolchain 'platform-36_r02.zip.expanded\android-36\android.jar'
    $buildTools = Join-Path $toolchain 'build-tools_r36_windows.zip.expanded\android-16'
}
if (-not $JdkHome) {
    $javaProperty = & java -XshowSettings:properties -version 2>&1 |
        ForEach-Object { $_.ToString() } | Select-String '^\s*java\.home = (.+)$' | Select-Object -First 1
    if (-not $javaProperty) { throw 'Set JAVA_HOME to a JDK (11 or later).' }
    $JdkHome = $javaProperty.Matches[0].Groups[1].Value
}
$java = Join-Path $JdkHome 'bin\java.exe'
$javac = Join-Path $JdkHome 'bin\javac.exe'
$keytool = Join-Path $JdkHome 'bin\keytool.exe'
$jar = Join-Path $JdkHome 'bin\jar.exe'
$aapt2 = Join-Path $buildTools 'aapt2.exe'
$d8 = Join-Path $buildTools 'd8.bat'
$zipalign = Join-Path $buildTools 'zipalign.exe'
$signer = Join-Path $buildTools 'apksigner.bat'
foreach ($path in @($androidJar, $java, $javac, $keytool, $jar, $aapt2, $d8, $zipalign, $signer)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing build tool: $path" }
}

function Invoke-Tool([string] $Tool, [string[]] $Arguments) {
    & $Tool @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$(Split-Path -Leaf $Tool) exited with code $LASTEXITCODE" }
}

$source = Join-Path $project 'src\main'
$packagePath = 'com\phonepageremote\android'
$protocol = Join-Path $source "java\$packagePath\PageProtocol.java"
$protocolTest = Join-Path $project "src\test\java\$packagePath\PageProtocolTest.java"
$build = Join-Path $project 'build'
$work = Join-Path $build 'intermediates'
$classes = Join-Path $work 'classes'
$testClasses = Join-Path $work 'test-classes'
$dex = Join-Path $work 'dex'
$resources = Join-Path $work 'resources.zip'
$classJar = Join-Path $work 'classes.jar'
$unsigned = Join-Path $work 'unsigned.apk'
$aligned = Join-Path $work 'aligned.apk'
$output = Join-Path $build 'outputs\apk\debug'
$apk = Join-Path $output 'phone-page-remote-android-debug.apk'
$pending = Join-Path $work 'signed.apk'
$keystore = Join-Path $toolchain 'page-remote-debug.p12'

$priorJdk = $env:JAVA_HOME
$env:JAVA_HOME = $JdkHome
try {
    if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Force -Recurse }
    New-Item -ItemType Directory -Force -Path $classes, $testClasses, $dex, $output, $toolchain | Out-Null

    Invoke-Tool $javac @('--release', '8', '-encoding', 'UTF-8', '-d', $testClasses, $protocol, $protocolTest)
    Invoke-Tool $java @('-cp', $testClasses, 'com.phonepageremote.android.PageProtocolTest')

    Invoke-Tool $aapt2 @('compile', '--dir', (Join-Path $source 'res'), '-o', $resources)
    Invoke-Tool $aapt2 @('link', '-I', $androidJar, '--manifest', (Join-Path $source 'AndroidManifest.xml'),
        '--java', $work, '--debug-mode', '-o', $unsigned, $resources)
    $sources = @(Get-ChildItem -Path (Join-Path $source 'java') -Recurse -Filter '*.java' |
        ForEach-Object { $_.FullName })
    $generated = Join-Path $work "$packagePath\R.java"
    $lambdaStubs = Join-Path $buildTools 'core-lambda-stubs.jar'
    Invoke-Tool $javac (@('-source', '8', '-target', '8', '-bootclasspath', "$androidJar;$lambdaStubs",
        '-encoding', 'UTF-8', '-Xlint:all', '-d', $classes, $generated) + $sources)
    Invoke-Tool $jar @('cf', $classJar, '-C', $classes, '.')
    Invoke-Tool $d8 @('--debug', '--min-api', '26', '--lib', $androidJar, '--output', $dex, $classJar)
    Invoke-Tool $jar @('uf', $unsigned, '-C', $dex, 'classes.dex')
    Invoke-Tool $zipalign @('-f', '-P', '16', '4', $unsigned, $aligned)

    if (-not (Test-Path -LiteralPath $keystore)) {
        Invoke-Tool $keytool @('-genkeypair', '-keystore', $keystore, '-storetype', 'PKCS12',
            '-storepass', 'android', '-keypass', 'android', '-alias', 'androiddebugkey',
            '-dname', 'CN=Android Debug,O=Android,C=US', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000')
    }
    Invoke-Tool $signer @('sign', '--ks', $keystore, '--ks-key-alias', 'androiddebugkey',
        '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--v4-signing-enabled', 'false',
        '--out', $pending, $aligned)
    Invoke-Tool $signer @('verify', '--verbose', '--print-certs', $pending)
    Invoke-Tool $zipalign @('-c', '-P', '16', '4', $pending)
    Move-Item -LiteralPath $pending -Destination $apk -Force
    Get-FileHash -LiteralPath $apk -Algorithm SHA256 | Format-List
} finally {
    $env:JAVA_HOME = $priorJdk
}

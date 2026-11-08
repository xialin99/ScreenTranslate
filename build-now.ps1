#requires -version 5.1
<#
  One-click build for ScreenTranslate.
  Downloads JDK 17, Android SDK 34 and Gradle 8.7 if they are missing,
  then builds a debug APK. No Android Studio and no admin rights required
  (everything is installed under %LOCALAPPDATA%).

  Usage: double-click build-now.bat
  Log:   %LOCALAPPDATA%\screen-translate-tools\build.log
#>

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$projectDir = $PSScriptRoot
if (-not $projectDir) { $projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path }
$toolsDir = Join-Path $env:LOCALAPPDATA 'screen-translate-tools'
$null = New-Item -ItemType Directory -Force -Path $toolsDir
$logFile = Join-Path $toolsDir 'build.log'
"=== build started $(Get-Date -Format s) ===" | Out-File -FilePath $logFile -Encoding utf8

function Write-Log([string]$message) {
    $line = "[$(Get-Date -Format 'HH:mm:ss')] $message"
    Write-Host $line
    Add-Content -Path $logFile -Value $line
}

function Invoke-Download([string]$url, [string]$target) {
    Write-Log "downloading $url"
    $client = New-Object System.Net.WebClient
    try {
        $client.DownloadFile($url, $target)
    } finally {
        $client.Dispose()
    }
}

function Expand-Zip([string]$zip, [string]$destination) {
    if (Test-Path $destination) { Remove-Item -Recurse -Force $destination }
    $null = New-Item -ItemType Directory -Force -Path $destination
    Expand-Archive -Path $zip -DestinationPath $destination -Force
}

function Test-JavaHome([string]$path) {
    if (-not $path) { return $false }
    return Test-Path (Join-Path $path 'bin\java.exe')
}

try {
    # ---------------------------------------------------------------- JDK 17
    $javaHome = $null
    $jdkCandidates = @(
        $env:JAVA_HOME,
        (Join-Path $toolsDir 'jdk'),
        'C:\Program Files\Android\Android Studio\jbr',
        (Join-Path $env:LOCALAPPDATA 'Programs\Android Studio\jbr')
    )
    foreach ($candidate in $jdkCandidates) {
        if (Test-JavaHome $candidate) {
            $javaHome = $candidate
            break
        }
    }
    if (-not $javaHome) {
        Write-Log 'JDK 17 not found, downloading Temurin 17'
        $jdkDir = Join-Path $toolsDir 'jdk'
        $jdkZip = Join-Path $toolsDir 'jdk17.zip'
        try {
            Invoke-Download 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk' $jdkZip
        } catch {
            throw 'could not download JDK 17. Install Android Studio once (it bundles a JDK), or install JDK 17 manually and set JAVA_HOME, then run again.'
        }
        $jdkExtract = Join-Path $toolsDir 'jdk-extract'
        Expand-Zip $jdkZip $jdkExtract
        $inner = Get-ChildItem $jdkExtract -Directory | Select-Object -First 1
        if (-not $inner) { throw 'JDK archive was empty' }
        if (Test-Path $jdkDir) { Remove-Item -Recurse -Force $jdkDir }
        Move-Item $inner.FullName $jdkDir
        Remove-Item -Recurse -Force $jdkExtract
        $javaHome = $jdkDir
    }
    Write-Log "JAVA_HOME = $javaHome"

    # ------------------------------------------------------------ Android SDK
    $sdkDir = $null
    $sdkCandidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))
    foreach ($candidate in $sdkCandidates) {
        if ($candidate -and (Test-Path $candidate)) {
            $sdkDir = $candidate
            break
        }
    }
    if (-not $sdkDir) { $sdkDir = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }

    $platformDir = Join-Path $sdkDir 'platforms\android-34'
    if (-not (Test-Path $platformDir)) {
        $sdkManager = Join-Path $sdkDir 'cmdline-tools\latest\bin\sdkmanager.bat'
        if (-not (Test-Path $sdkManager)) {
            Write-Log 'downloading Android command line tools'
            $zip = Join-Path $toolsDir 'cmdline-tools.zip'
            $urls = @(
                'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip',
                'https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-win-11076708_latest.zip',
                'https://dl.google.com/android/repository/commandlinetools-win-9477386_latest.zip'
            )
            $downloaded = $false
            foreach ($url in $urls) {
                try {
                    Invoke-Download $url $zip
                    $downloaded = $true
                    break
                } catch {
                    Write-Log "download failed, trying next mirror"
                }
            }
            if (-not $downloaded) {
                throw 'could not download Android command line tools. Install Android Studio once (it sets up the SDK under %LOCALAPPDATA%\Android\Sdk) or set ANDROID_HOME, then run again.'
            }

            $extract = Join-Path $toolsDir 'cmdline-tools-extract'
            Expand-Zip $zip $extract
            $latestDir = Join-Path $sdkDir 'cmdline-tools\latest'
            if (Test-Path $latestDir) { Remove-Item -Recurse -Force $latestDir }
            $null = New-Item -ItemType Directory -Force -Path (Split-Path -Parent $latestDir)
            Move-Item (Join-Path $extract 'cmdline-tools') $latestDir
            Remove-Item -Recurse -Force $extract
        }

        $env:JAVA_HOME = $javaHome
        $sdkManager = Join-Path $sdkDir 'cmdline-tools\latest\bin\sdkmanager.bat'

        # convert HTTPS_PROXY / HTTP_PROXY into sdkmanager proxy arguments
        $proxyArgs = @()
        $proxyValue = $env:HTTPS_PROXY
        if (-not $proxyValue) { $proxyValue = $env:HTTP_PROXY }
        if ($proxyValue) {
            try {
                $proxyUri = [System.Uri]$proxyValue
                if ($proxyUri.Host) {
                    $proxyArgs = @('--proxy=http', "--proxy_host=$($proxyUri.Host)", "--proxy_port=$($proxyUri.Port)")
                }
            } catch {
                Write-Log 'ignoring invalid proxy environment variable'
            }
        }

        Write-Log 'accepting Android SDK licenses'
        $answers = 1..40 | ForEach-Object { 'y' }
        $answers | & $sdkManager --sdk_root=$sdkDir @proxyArgs --licenses | Out-Null
        Write-Log 'installing Android SDK platform 34, build-tools and platform-tools'
        & $sdkManager --sdk_root=$sdkDir @proxyArgs 'platform-tools' 'platforms;android-34' 'build-tools;34.0.0'
        if ($LASTEXITCODE -ne 0) {
            throw 'sdkmanager failed. If dl.google.com is blocked on your network, set HTTPS_PROXY and run again.'
        }
    }
    Write-Log "ANDROID_HOME = $sdkDir"

    $sdkEscaped = $sdkDir.Replace('\', '\\')
    Set-Content -Path (Join-Path $projectDir 'local.properties') -Value "sdk.dir=$sdkEscaped" -Encoding ascii

    # ---------------------------------------------------------------- Gradle
    $gradleBat = $null
    $systemGradle = Get-Command gradle.bat -ErrorAction SilentlyContinue
    if (-not $systemGradle) { $systemGradle = Get-Command gradle -ErrorAction SilentlyContinue }
    if ($systemGradle) {
        $gradleBat = $systemGradle.Source
        Write-Log "using system gradle: $gradleBat"
    } else {
        $gradleDir = Join-Path $toolsDir 'gradle-8.7'
        $gradleBat = Join-Path $gradleDir 'bin\gradle.bat'
        if (-not (Test-Path $gradleBat)) {
            Write-Log 'downloading Gradle 8.7'
            $zip = Join-Path $toolsDir 'gradle-8.7-bin.zip'
            $gradleUrls = @(
                'https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip',
                'https://mirrors.huaweicloud.com/gradle/gradle-8.7-bin.zip',
                'https://services.gradle.org/distributions/gradle-8.7-bin.zip'
            )
            $gradleDownloaded = $false
            foreach ($url in $gradleUrls) {
                try {
                    Invoke-Download $url $zip
                    $gradleDownloaded = $true
                    break
                } catch {
                    Write-Log 'download failed, trying next mirror'
                }
            }
            if (-not $gradleDownloaded) { throw 'could not download Gradle 8.7' }
            Expand-Archive -Path $zip -DestinationPath $toolsDir -Force
        }
    }

    # ---------------------------------------------------------------- Build
    $env:JAVA_HOME = $javaHome
    $env:ANDROID_HOME = $sdkDir
    $env:ANDROID_SDK_ROOT = $sdkDir
    $env:PATH = "$javaHome\bin;$env:PATH"

    Push-Location $projectDir
    try {
        Write-Log 'running gradle assembleDebug (first run may take several minutes)'
        $gradleLog = Join-Path $toolsDir 'gradle-output.log'
        Write-Log "gradle output is also saved to $gradleLog"
        # 关闭 ErrorActionPreference=Stop：否则 Gradle 输出到 stderr 的普通 warning
        # （如 SDK XML 版本提示）会被 PowerShell 当成致命错误，误判构建失败
        $previousErrorAction = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            & $gradleBat --no-daemon assembleDebug 2>&1 | Tee-Object -FilePath $gradleLog -Append
            $gradleExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousErrorAction
        }
        if ($gradleExitCode -ne 0) { throw "gradle build failed with exit code $gradleExitCode" }
    } finally {
        Pop-Location
    }

    $apk = Join-Path $projectDir 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path $apk)) { throw 'APK was not produced' }
    $finalApk = Join-Path $projectDir 'ScreenTranslate-debug.apk'
    Copy-Item $apk $finalApk -Force
    Write-Log "BUILD OK: $finalApk"

    try {
        Start-Process explorer.exe -ArgumentList "/select,`"$finalApk`""
    } catch {
        Write-Log 'could not open the output folder automatically'
    }

    $adb = Join-Path $sdkDir 'platform-tools\adb.exe'
    if (Test-Path $adb) {
        Write-Log 'trying adb install (skip if no device is connected)'
        & $adb install -r $finalApk
    }
} catch {
    Write-Log "ERROR: $($_.Exception.Message)"
    Write-Log 'see log above for details'
    $scriptFailed = $true
} finally {
    if ($Host.Name -eq 'ConsoleHost') {
        Read-Host 'Press Enter to close'
    }
}

if ($scriptFailed) { exit 1 }

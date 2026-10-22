[CmdletBinding()]
param(
    [ValidateSet('Debug', 'Release')]
    [string]$Variant = 'Release',
    [switch]$SkipNative
)

$ErrorActionPreference = 'Stop'

function Assert-ExitCode {
    param(
        [string]$Step,
        [int]$Code
    )
    if ($Code -ne 0) {
        throw "$Step failed with exit code $Code"
    }
}

function Get-ExistingDirectory {
    param([string[]]$Candidates)
    foreach ($candidate in $Candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Container)) {
            return [IO.Path]::GetFullPath($candidate)
        }
    }
    return $null
}

function Get-NinjaCommands {
    param(
        [string]$Ninja,
        [string]$BuildDirectory
    )

    $raw = @(& $Ninja -C $BuildDirectory -n -v all 2>&1)
    $code = $LASTEXITCODE
    Assert-ExitCode "Ninja dry run ($BuildDirectory)" $code

    # Ninja prefixes executable commands with [done/total].  The entering
    # directory and diagnostic lines are intentionally ignored.
    return @(
        $raw |
            ForEach-Object { $_.ToString() } |
            Where-Object { $_ -match '^\[\d+\/\d+\]\s+' } |
            ForEach-Object { $_ -replace '^\[\d+\/\d+\]\s+', '' }
    )
}

function Invoke-NativeCommands {
    param(
        [string]$Ninja,
        [string]$BuildDirectory
    )

    # Running Ninja itself can stall on this Windows setup when it has to
    # spawn nested cmd.exe post-build commands.  Execute the exact commands
    # emitted by Ninja one at a time instead.  Every command is checked; a
    # failed command stops the build immediately.
    $commands = @(Get-NinjaCommands -Ninja $Ninja -BuildDirectory $BuildDirectory)
    if ($commands.Count -eq 0) {
        return
    }

    Write-Host "Running $($commands.Count) native command(s)..."
    Push-Location -LiteralPath $BuildDirectory
    try {
        $index = 0
        foreach ($command in $commands) {
            $index++
            Write-Host "[$index/$($commands.Count)] $command"
            & $env:ComSpec /d /c $command
            $code = $LASTEXITCODE
            Assert-ExitCode "Native command $index/$($commands.Count)" $code
        }
    } finally {
        Pop-Location
    }
}

function Test-NativeOutputsCurrent {
    param([string]$BuildDirectory)

    $hash = Split-Path -Leaf (Split-Path -Parent $BuildDirectory)
    $outputDirectory = Join-Path $projectBuildRoot "manager\build\intermediates\cxx\$cmakeVariant\$hash\obj\arm64-v8a"
    $required = @('libadb.so', 'libbootstrap.so', 'libchid.so', 'libdfroot.so', 'librish.so', 'libstellar.so')
    $outputs = @($required | ForEach-Object {
        Get-Item -LiteralPath (Join-Path $outputDirectory $_) -ErrorAction SilentlyContinue
    })
    if ($outputs.Count -ne $required.Count) {
        return $false
    }

    $sourceRoot = Join-Path $projectBuildRoot 'manager\src\main\jni'
    $sources = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -File -ErrorAction SilentlyContinue)
    if ($sources.Count -eq 0) {
        return $false
    }
    $latestSource = ($sources | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1).LastWriteTimeUtc
    $oldestOutput = ($outputs | Sort-Object LastWriteTimeUtc | Select-Object -First 1).LastWriteTimeUtc
    return $oldestOutput -ge $latestSource
}

function Assert-NativeOutputs {
    param([string]$BuildDirectory)

    $hash = Split-Path -Leaf (Split-Path -Parent $BuildDirectory)
    $outputDirectory = Join-Path $projectBuildRoot "manager\build\intermediates\cxx\$cmakeVariant\$hash\obj\arm64-v8a"
    $required = @(
        'libadb.so',
        'libbootstrap.so',
        'libchid.so',
        'libdfroot.so',
        'librish.so',
        'libstellar.so'
    )
    foreach ($name in $required) {
        $file = Join-Path $outputDirectory $name
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
            throw "Native output is missing: $file"
        }
    }
    return $outputDirectory
}

function Assert-ServerProperties {
    $file = Join-Path $projectRoot 'server.properties'
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        throw "Server configuration is missing: $file"
    }
    $required = @(
        'compatibilityEndpoint', 'moduleId', 'protocolV2Endpoint',
        'channelEndpoint', 'protocolV2PublicKey', 'protocolV2KeyId',
        'romProtocolV2Endpoint', 'romProtocolV2PublicKey', 'romProtocolV2KeyId',
        'channelJoinUrl', 'updateUrl'
    )
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $file) {
        if ($line -match '^\s*([^#][^=]*)=(.*)$') {
            $values[$matches[1].Trim()] = $matches[2].Trim()
        }
    }
    foreach ($name in $required) {
        if (-not $values.ContainsKey($name) -or [string]::IsNullOrWhiteSpace($values[$name])) {
            throw "Server configuration value is missing: $name"
        }
    }
}

function Verify-ReleaseApk {
    param([string]$ApkPath)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $requiredEntries = @(
        'lib/arm64-v8a/libadb.so',
        'lib/arm64-v8a/libbootstrap.so',
        'lib/arm64-v8a/libchid.so',
        'lib/arm64-v8a/libdfroot.so',
        'lib/arm64-v8a/librish.so',
        'lib/arm64-v8a/libstellar.so',
        'lib/arm64-v8a/libghostlock.so',
        'lib/arm64-v8a/libextract.so'
    )
    $zip = [IO.Compression.ZipFile]::OpenRead($ApkPath)
    try {
        foreach ($entry in $requiredEntries) {
            if (-not $zip.GetEntry($entry)) {
                throw "APK is missing native library: $entry"
            }
        }
    } finally {
        $zip.Dispose()
    }

    $apksigner = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Filter 'apksigner.bat' -Recurse `
        -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
    if (-not $apksigner) {
        throw 'Android SDK apksigner was not found.'
    }
    $signature = @(& $apksigner verify --verbose --print-certs $ApkPath 2>&1)
    Assert-ExitCode 'APK signature verification' $LASTEXITCODE
    $signatureText = $signature -join "`n"
    if ($signatureText -notmatch 'Verified using v2 scheme .*: true') {
        throw 'APK does not have a valid v2 signature.'
    }
    if ($signatureText -notmatch 'CN=Yuehong Privilege Assistant') {
        throw 'APK certificate is not the Yuehong release certificate.'
    }

    $signingFile = Join-Path $projectRoot 'keystore.properties'
    if (-not (Test-Path -LiteralPath $signingFile -PathType Leaf)) {
        throw "Release signing properties are missing: $signingFile"
    }
    $signing = @{}
    foreach ($line in Get-Content -LiteralPath $signingFile) {
        if ($line -match '^\s*([^#][^=]*)=(.*)$') {
            $signing[$matches[1].Trim()] = $matches[2].Trim()
        }
    }
    foreach ($name in @('storeFile', 'storePassword', 'keyAlias', 'keyPassword')) {
        if (-not $signing.ContainsKey($name) -or [string]::IsNullOrWhiteSpace($signing[$name])) {
            throw "Release signing property is missing: $name"
        }
    }
    $keystorePath = [regex]::Replace($signing['storeFile'], '\\u([0-9a-fA-F]{4})', {
        param($match)
        ([char][Convert]::ToInt32($match.Groups[1].Value, 16)).ToString()
    })
    $keystore = if ([IO.Path]::IsPathRooted($keystorePath)) {
        [IO.Path]::GetFullPath($keystorePath)
    } else {
        [IO.Path]::GetFullPath((Join-Path $projectRoot $keystorePath))
    }
    if (-not (Test-Path -LiteralPath $keystore -PathType Leaf)) {
        throw "Release keystore is missing: $keystore"
    }
    $keytool = Join-Path $java 'bin\keytool.exe'
    # keytool writes its normal JKS format warning to stderr.  Capture that
    # stream without letting PowerShell's Stop preference turn a successful
    # certificate inspection into a failed build.
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $keytoolOutput = @(& $keytool -list -v -keystore $keystore -alias $signing['keyAlias'] `
            -storepass $signing['storePassword'] -keypass $signing['keyPassword'] 2>&1)
        $keytoolExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    Assert-ExitCode 'Release keystore certificate inspection' $keytoolExitCode
    $keytoolText = $keytoolOutput -join "`n"
    $apkDigestMatch = [regex]::Match($signatureText, 'certificate SHA-256 digest:\s*([0-9a-fA-F]+)')
    $keyDigestMatch = [regex]::Match($keytoolText, 'SHA256(?:\s+fingerprint)?\s*:\s*([0-9a-fA-F:]+)')
    if (-not $apkDigestMatch.Success -or -not $keyDigestMatch.Success) {
        throw 'Could not read the release certificate SHA-256 fingerprint.'
    }
    $apkDigest = $apkDigestMatch.Groups[1].Value.ToLowerInvariant()
    $keyDigest = $keyDigestMatch.Groups[1].Value.Replace(':', '').ToLowerInvariant()
    if (-not $apkDigest.Equals($keyDigest, [StringComparison]::OrdinalIgnoreCase)) {
        throw "APK certificate does not match the configured release keystore (APK=$apkDigest, keystore=$keyDigest)"
    }
    Write-Host 'APK signature and native library verification: OK'
}

$scriptProjectPath = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$driveRoot = [IO.Path]::GetPathRoot($scriptProjectPath)
$englishRoot = Join-Path $driveRoot 'yhyc_work'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')

# yhyc_work is the existing ASCII junction to the repository's top-level
# folder.  The project is below that folder, so its relative path can still
# contain Chinese names.  Resolve that path to the real source directory,
# then use a second stable junction directly to the project for CMake.
$projectRoot = $scriptProjectPath
if (Test-Path -LiteralPath $englishRoot -PathType Container) {
    $englishItem = Get-Item -LiteralPath $englishRoot -Force
    if (-not ($englishItem.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw "The ASCII repository path is not a junction: $englishRoot"
    }
    $englishTarget = [IO.Path]::GetFullPath(@($englishItem.Target)[0])
    $targetBoundary = $englishTarget.TrimEnd('\') + '\'
    $englishBoundary = ([IO.Path]::GetFullPath($englishRoot)).TrimEnd('\') + '\'
    if ($scriptProjectPath.StartsWith($englishBoundary, [StringComparison]::OrdinalIgnoreCase)) {
        # The script was started through F:\yhyc_work\...; resolve the
        # nested path back to the real Chinese repository location.
        $relativeProject = [IO.Path]::GetRelativePath($englishRoot, $scriptProjectPath)
        $projectRoot = [IO.Path]::GetFullPath((Join-Path $englishTarget $relativeProject))
    } elseif (-not $scriptProjectPath.StartsWith($targetBoundary, [StringComparison]::OrdinalIgnoreCase) -and
            -not $scriptProjectPath.Equals($englishTarget, [StringComparison]::OrdinalIgnoreCase)) {
        throw "The project is outside the target of ${englishRoot}: $scriptProjectPath"
    }
}

$projectAlias = Join-Path $tempRoot 'yhroot-stellar-build'
if (-not (Test-Path -LiteralPath $projectAlias -PathType Container)) {
    New-Item -ItemType Junction -Path $projectAlias -Target $projectRoot | Out-Null
}
$projectItem = Get-Item -LiteralPath $projectAlias -Force
if (-not ($projectItem.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    throw "The fixed CMake path is not a junction: $projectAlias"
}
$projectTarget = [IO.Path]::GetFullPath(@($projectItem.Target)[0])
if (-not $projectTarget.Equals([IO.Path]::GetFullPath($projectRoot), [StringComparison]::OrdinalIgnoreCase)) {
    throw "The fixed CMake path points to a different project: $projectTarget"
}
$projectBuildRoot = [IO.Path]::GetFullPath($projectAlias)
$gradle = Join-Path $projectBuildRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradle -PathType Leaf)) {
    throw "Gradle wrapper not found: $gradle"
}

$originalEnvironment = @{
    JAVA_HOME = $env:JAVA_HOME
    ANDROID_HOME = $env:ANDROID_HOME
    ANDROID_SDK_ROOT = $env:ANDROID_SDK_ROOT
    ANDROID_NDK_HOME = $env:ANDROID_NDK_HOME
    GRADLE_USER_HOME = $env:GRADLE_USER_HOME
    RUSTUP_TOOLCHAIN = $env:RUSTUP_TOOLCHAIN
}

$javaAlias = Join-Path $tempRoot 'yh-jbr'
$sdk = Get-ExistingDirectory @(
    $env:ANDROID_HOME,
    $env:ANDROID_SDK_ROOT,
    'D:\MyApps\gongju\Android\AndroidSDK',
    (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
)
if (-not $sdk) {
    throw 'Android SDK was not found. Set ANDROID_HOME or ANDROID_SDK_ROOT.'
}

$java = Get-ExistingDirectory @(
    $env:JAVA_HOME,
    $javaAlias,
    'D:\MyApps\gongju\Android\Android Studio\jbr',
    'C:\Program Files\Android\Android Studio\jbr'
)
if (-not $java) {
    throw 'A JDK with Java 21 was not found. Set JAVA_HOME.'
}

# Keep Java and Gradle paths ASCII.  Prefab generates Windows command files
# and fails to load its Kotlin entry point when these paths contain Chinese
# characters.
if ($java -match '[^\x00-\x7F]') {
    if (-not (Test-Path -LiteralPath $javaAlias)) {
        New-Item -ItemType Junction -Path $javaAlias -Target $java | Out-Null
    }
    $java = [IO.Path]::GetFullPath($javaAlias)
}

$env:JAVA_HOME = $java
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_NDK_HOME = Join-Path $sdk 'ndk\29.0.13113456'
$env:GRADLE_USER_HOME = Join-Path $projectBuildRoot '.gradle-release-home'
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME | Out-Null

# Cargo build scripts are host executables. Prefer the installed GNU host
# toolchain when this Windows environment has no MSVC linker available.
if ([string]::IsNullOrWhiteSpace($env:RUSTUP_TOOLCHAIN)) {
    $rustup = Join-Path $env:USERPROFILE '.cargo\bin\rustup.exe'
    if (Test-Path -LiteralPath $rustup -PathType Leaf) {
        $installedRustToolchains = @(& $rustup toolchain list --quiet 2>$null)
        if ($LASTEXITCODE -eq 0 -and $installedRustToolchains -contains 'nightly-x86_64-pc-windows-gnu' -and
                (Get-Command gcc.exe -ErrorAction SilentlyContinue)) {
            $gnuTargets = @(& $rustup target list --installed --toolchain nightly-x86_64-pc-windows-gnu 2>$null)
            if ($LASTEXITCODE -eq 0 -and $gnuTargets -contains 'aarch64-linux-android') {
                $env:RUSTUP_TOOLCHAIN = 'nightly-x86_64-pc-windows-gnu'
            }
        }
    }
}

$cmakeVariant = if ($Variant -eq 'Release') { 'RelWithDebInfo' } else { 'Debug' }
$configureTask = ":manager:configureCMake${cmakeVariant}[arm64-v8a]"
$buildTask = ":manager:buildCMake${cmakeVariant}[arm64-v8a]"
$gradleCommon = @('--no-daemon', '--console=plain', '-Pkotlin.compiler.execution.strategy=in-process')
$nativeBuildDirectory = $null

try {
    Assert-ServerProperties
    Push-Location -LiteralPath $projectBuildRoot
    try {
        if (-not $SkipNative) {
            # A previous build may have generated CMake files through a
            # short-lived temporary junction.  Those files keep pointing at
            # deleted paths and must be regenerated from F:\yhyc_work.
            $nativeRoot = [IO.Path]::GetFullPath((Join-Path $projectBuildRoot "manager\.cxx\$cmakeVariant"))
            if (Test-Path -LiteralPath $nativeRoot -PathType Container) {
                $nativeRootBoundary = ([IO.Path]::GetFullPath((Join-Path $projectBuildRoot 'manager\.cxx'))).TrimEnd('\') + '\'
                if (-not $nativeRoot.StartsWith($nativeRootBoundary, [StringComparison]::OrdinalIgnoreCase)) {
                    throw "Refusing to remove a CMake path outside manager\.cxx: $nativeRoot"
                }
                $cache = Get-ChildItem -LiteralPath $nativeRoot -Filter 'CMakeCache.txt' -File -Recurse -ErrorAction SilentlyContinue |
                    Select-Object -First 1
                $cmakeHome = if ($cache) {
                    (Get-Content -LiteralPath $cache.FullName | Where-Object { $_ -match '^CMAKE_HOME_DIRECTORY:INTERNAL=' } |
                        Select-Object -First 1) -replace '^CMAKE_HOME_DIRECTORY:INTERNAL=', ''
                }
                $normalizedHome = if ($cmakeHome) { $cmakeHome.Replace('/', '\') } else { '' }
                $expectedHome = (Join-Path $projectBuildRoot 'manager\src\main\jni').Replace('/', '\')
                if (-not $normalizedHome.Equals($expectedHome, [StringComparison]::OrdinalIgnoreCase)) {
                    Write-Host "Removing stale native cache under $nativeRoot"
                    Remove-Item -LiteralPath $nativeRoot -Recurse -Force
                }
            }
            Write-Host "Configuring native build through $englishRoot..."
            & $gradle $configureTask @gradleCommon
            Assert-ExitCode 'CMake configuration' $LASTEXITCODE

            $nativeBuildDirectory = @(
                Get-ChildItem -LiteralPath (Join-Path $projectBuildRoot "manager\.cxx\$cmakeVariant") `
                    -Directory -Recurse -Filter 'arm64-v8a' -ErrorAction SilentlyContinue |
                    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'build.ninja') } |
                    Sort-Object LastWriteTime -Descending
            ) | Select-Object -First 1 -ExpandProperty FullName
            if (-not $nativeBuildDirectory) {
                throw "CMake build directory was not generated under manager\.cxx\$cmakeVariant"
            }

            $ninja = Join-Path $sdk 'cmake\4.1.1\bin\ninja.exe'
            if (-not (Test-Path -LiteralPath $ninja -PathType Leaf)) {
                $ninja = Get-ChildItem -LiteralPath (Join-Path $sdk 'cmake') -Filter 'ninja.exe' -Recurse `
                    -ErrorAction SilentlyContinue | Sort-Object FullName -Descending |
                    Select-Object -First 1 -ExpandProperty FullName
            }
            if (-not $ninja) {
                throw 'Android SDK Ninja was not found under the SDK cmake directory.'
            }

            if (Test-NativeOutputsCurrent -BuildDirectory $nativeBuildDirectory) {
                Write-Host 'Native outputs are current; no native command needs to run.'
            } else {
                Invoke-NativeCommands -Ninja $ninja -BuildDirectory $nativeBuildDirectory
            }
            Assert-NativeOutputs -BuildDirectory $nativeBuildDirectory | Out-Null
        } else {
            Write-Host 'Skipping native configuration because -SkipNative was specified.'
        }

        $gradleArgs = @(
            ":manager:assemble$Variant"
        ) + $gradleCommon + @('-x', $configureTask, '-x', $buildTask)
        Write-Host "Building the $Variant assistant APK with upstream GhostLock embedded..."
        & $gradle @gradleArgs
        Assert-ExitCode "Gradle $Variant assemble" $LASTEXITCODE
    } finally {
        Pop-Location
    }
} finally {
    foreach ($name in $originalEnvironment.Keys) {
        if ($null -eq $originalEnvironment[$name]) {
            Remove-Item -Path "Env:$name" -ErrorAction SilentlyContinue
        } else {
            Set-Item -Path "Env:$name" -Value $originalEnvironment[$name]
        }
    }
}

$apkDirectory = Join-Path $projectBuildRoot "manager\build\outputs\apk\$($Variant.ToLowerInvariant())"
$apk = Get-ChildItem -LiteralPath $apkDirectory -Filter '*.apk' -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) {
    throw "APK was not produced under $apkDirectory"
}

$outputDirectory = Join-Path $projectRoot 'out\apk'
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$outputApk = Join-Path $outputDirectory $apk.Name
Copy-Item -LiteralPath $apk.FullName -Destination $outputApk -Force

if ($Variant -eq 'Release') {
    Verify-ReleaseApk -ApkPath $outputApk
}
$sha = (Get-FileHash -Algorithm SHA256 -LiteralPath $outputApk).Hash.ToLowerInvariant()
Write-Output "APK=$outputApk"
Write-Output "SIZE=$((Get-Item -LiteralPath $outputApk).Length)"
Write-Output "SHA256=$sha"

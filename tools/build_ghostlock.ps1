[CmdletBinding()]
param(
    [string]$NdkVersion = '29.0.13113456',
    [int]$AndroidApi = 35,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$buildRoot = $projectRoot
$junctionPath = $null
$originalEnvironment = @{}
foreach ($name in @(
    'CARGO_TARGET_DIR',
    'CC_aarch64_linux_android',
    'AR_aarch64_linux_android',
    'CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER'
)) {
    $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name)
}

if ($projectRoot -match '[^\x00-\x7F]') {
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    $junctionPath = Join-Path $tempRoot "yhroot-ghostlock-build-$PID"
    if (Test-Path -LiteralPath $junctionPath) {
        throw "Temporary build path already exists: $junctionPath"
    }
    New-Item -ItemType Junction -Path $junctionPath -Target $projectRoot | Out-Null
    $junction = Get-Item -LiteralPath $junctionPath -Force
    if (-not ($junction.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw "Temporary build path is not a directory junction: $junctionPath"
    }
    $buildRoot = $junctionPath
}

try {
    $vendorRoot = Join-Path $buildRoot 'third_party\ghostlock'
    $srcRoot = Join-Path $vendorRoot 'src'
    $extractRoot = Join-Path $vendorRoot 'tools\extract_rs'
    $jniRoot = Join-Path $buildRoot 'assistant\src\main\jniLibs\arm64-v8a'
    $nativeBuildRoot = Join-Path $buildRoot 'build\ghostlock-native'
    $nativeBinary = Join-Path $nativeBuildRoot 'ghostlock'
    $cargoTargetRoot = Join-Path $buildRoot 'build\ghostlock-cargo'

    $sdkRoot = $env:ANDROID_HOME
    if (-not $sdkRoot) { $sdkRoot = $env:ANDROID_SDK_ROOT }
    $ndkRoot = $env:ANDROID_NDK_HOME
    if (-not $ndkRoot -and $sdkRoot) { $ndkRoot = Join-Path $sdkRoot "ndk\$NdkVersion" }
    if (-not $ndkRoot -or -not (Test-Path -LiteralPath $ndkRoot)) {
        throw "Android NDK $NdkVersion was not found. Set ANDROID_HOME or ANDROID_NDK_HOME."
    }

    $prebuilt = if ($IsWindows -or $env:OS -eq 'Windows_NT') { 'windows-x86_64' } else { 'linux-x86_64' }
    $binRoot = Join-Path $ndkRoot "toolchains\llvm\prebuilt\$prebuilt\bin"
    $clangName = if ($prebuilt -eq 'windows-x86_64') {
        "aarch64-linux-android$AndroidApi-clang.cmd"
    } else {
        "aarch64-linux-android$AndroidApi-clang"
    }
    $clangxxName = if ($prebuilt -eq 'windows-x86_64') {
        "aarch64-linux-android$AndroidApi-clang++.cmd"
    } else {
        "aarch64-linux-android$AndroidApi-clang++"
    }
    $clang = Join-Path $binRoot $clangName
    $clangxx = Join-Path $binRoot $clangxxName
    $arName = if ($prebuilt -eq 'windows-x86_64') { 'llvm-ar.exe' } else { 'llvm-ar' }
    $ar = Join-Path $binRoot $arName
    foreach ($tool in @($clang, $clangxx, $ar)) {
        if (-not (Test-Path -LiteralPath $tool)) { throw "NDK tool not found: $tool" }
    }

    $nativeSources = @(
        'core/main.cpp',
        'core/attack/ops.cpp',
        'core/profile/entry.cpp',
        'core/memory/address_space.cpp',
        'core/memory/heap_context.cpp',
        'core/race/pi_race.cpp',
        'core/route/route_controller.cpp',
        'core/route/route_middleware.cpp',
        'core/race/threads.cpp',
        'core/route/tcp_zerocopy_route.cpp',
        'core/route/select_stack_route.cpp',
        'core/route/multicast_waiter_route.cpp',
        'core/memory/payload_builder.cpp',
        'core/session/runtime_config.cpp',
        'core/profile/binary.cpp',
        'core/support/util.cpp',
        'core/session/exploit_session.cpp',
        'core/session/root_child_frontend.cpp',
        'core/session/backend/cve_2026_43499_backend.cpp',
        'core/session/handoff_probe.cpp',
        'core/session/victim_process.cpp',
        'core/session/victim_context.cpp',
        'core/support/native_resource.cpp',
        'core/support/run_state.cpp'
    )
    foreach ($source in $nativeSources) {
        if (-not (Test-Path -LiteralPath (Join-Path $srcRoot $source))) {
            throw "GhostLock upstream source is missing: $source"
        }
    }

    New-Item -ItemType Directory -Path $nativeBuildRoot -Force | Out-Null
    $responseFile = Join-Path $srcRoot '.ghostlock-clangxx.rsp'
    $clangxxArgs = @(
        '-O2', '-flto', '-Wall', '-Wextra', '-Wconversion', '-Wsign-conversion',
        '-Wno-unused-parameter', '-Wno-sign-compare', '-Wno-unused-function',
        '-Icore', '-DTARGET_CONFIG_H=\"kernel/target.h\"',
        '-std=c++23', '-fno-rtti', '-fPIE', '-pie', '-pthread', '-static-libstdc++'
    ) + $nativeSources + @('-o', $nativeBinary.Replace('\', '/'))
    [IO.File]::WriteAllLines($responseFile, $clangxxArgs, (New-Object Text.UTF8Encoding($false)))
    try {
        Push-Location -LiteralPath $srcRoot
        try { & $clangxx '@.ghostlock-clangxx.rsp' } finally { Pop-Location }
        if ($LASTEXITCODE -ne 0) { throw "GhostLock C++ build failed with exit code $LASTEXITCODE" }
        if (-not (Test-Path -LiteralPath $nativeBinary)) {
            throw "GhostLock C++ compiler returned success without producing: $nativeBinary"
        }
    } finally {
        if (Test-Path -LiteralPath $responseFile) { Remove-Item -LiteralPath $responseFile -Force }
    }

    $env:CARGO_TARGET_DIR = $cargoTargetRoot
    $env:CC_aarch64_linux_android = $clang
    $env:AR_aarch64_linux_android = $ar
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $clang
    $cargoArgs = @()
    if ($prebuilt -eq 'windows-x86_64') { $cargoArgs += '+nightly-x86_64-pc-windows-gnu' }
    $cargoArgs += @('build', '--release', '--target', 'aarch64-linux-android', '--locked', '--features', 'http')
    if ($Offline) { $cargoArgs += '--offline' }
    Push-Location -LiteralPath $extractRoot
    try { & cargo @cargoArgs } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw "GhostLock Rust extractor build failed with exit code $LASTEXITCODE" }

    $extractBinary = Join-Path $cargoTargetRoot 'aarch64-linux-android\release\ghostlock-extract'
    if (-not (Test-Path -LiteralPath $extractBinary)) {
        throw "GhostLock extractor was not produced: $extractBinary"
    }
    New-Item -ItemType Directory -Path $jniRoot -Force | Out-Null
    Copy-Item -LiteralPath $nativeBinary -Destination (Join-Path $jniRoot 'libghostlock.so') -Force
    Copy-Item -LiteralPath $extractBinary -Destination (Join-Path $jniRoot 'libextract.so') -Force

    foreach ($name in @('libghostlock.so', 'libextract.so')) {
        $file = Get-Item -LiteralPath (Join-Path $jniRoot $name)
        $sha = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToLowerInvariant()
        Write-Output "GHOSTLOCK_ARTIFACT=$name SIZE=$($file.Length) SHA256=$sha"
    }
} finally {
    foreach ($name in $originalEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $originalEnvironment[$name])
    }
    if ($junctionPath -and (Test-Path -LiteralPath $junctionPath)) {
        $junction = Get-Item -LiteralPath $junctionPath -Force
        if (-not ($junction.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Refusing to remove non-junction path: $junctionPath"
        }
        [IO.Directory]::Delete($junctionPath)
    }
}

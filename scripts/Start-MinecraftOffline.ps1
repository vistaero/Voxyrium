[CmdletBinding()]
param(
    [string[]]$MinecraftVersion,
    [string[]]$ProfileId,
    [string]$MinecraftDirectory = (Join-Path $env:APPDATA '.minecraft'),
    [string]$OfflineUsername = $env:USERNAME,
    [int]$MaximumRamMb = 4096,
    [switch]$NoDownload,
    [switch]$DryRun,
    [switch]$Wait
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-AllowedByRules {
    param([object[]]$Rules)
    if (-not $Rules -or $Rules.Count -eq 0) { return $true }
    $allowed = $false
    foreach ($rule in $Rules) {
        $matches = $true
        if ($rule.PSObject.Properties.Name -contains 'os') {
            if (($rule.os.PSObject.Properties.Name -contains 'name') -and $rule.os.name -ne 'windows') { $matches = $false }
            if ($matches -and ($rule.os.PSObject.Properties.Name -contains 'arch') -and $rule.os.arch -notin @('x86_64', 'amd64')) { $matches = $false }
            if ($matches -and ($rule.os.PSObject.Properties.Name -contains 'version') -and [Environment]::OSVersion.VersionString -notmatch $rule.os.version) { $matches = $false }
        }
        if ($rule.PSObject.Properties.Name -contains 'features') {
            foreach ($feature in $rule.features.PSObject.Properties) {
                # Offline test profiles do not enable demo, quick-play or custom-resolution features.
                if ([bool]$feature.Value) { $matches = $false }
            }
        }
        if ($matches) { $allowed = $rule.action -eq 'allow' }
    }
    return $allowed
}

function Invoke-Download {
    param([string]$Uri, [string]$Destination)
    if (Test-Path -LiteralPath $Destination) { return }
    if ($NoDownload) {
        if ($DryRun) { Write-Host "Dry run missing: $Destination" -ForegroundColor DarkYellow; return }
        throw "Missing required file: $Destination"
    }
    New-Item -ItemType Directory -Path (Split-Path -Parent $Destination) -Force | Out-Null
    $temporary = "$Destination.download"
    Write-Host "Downloading $([IO.Path]::GetFileName($Destination))" -ForegroundColor DarkGray
    Invoke-WebRequest -Uri $Uri -OutFile $temporary -UseBasicParsing
    Move-Item -LiteralPath $temporary -Destination $Destination -Force
}

$script:Manifest = $null
function Get-VersionData {
    param([string]$VersionId)
    $directory = Join-Path $MinecraftDirectory "versions\$VersionId"
    $jsonPath = Join-Path $directory "$VersionId.json"
    if (-not (Test-Path -LiteralPath $jsonPath)) {
        if ($NoDownload) { throw "Minecraft version metadata is missing: $VersionId" }
        if (-not $script:Manifest) {
            $script:Manifest = Invoke-RestMethod 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'
        }
        $manifestEntry = $script:Manifest.versions | Where-Object id -eq $VersionId | Select-Object -First 1
        if (-not $manifestEntry) { throw "Version '$VersionId' is not present in Mojang's manifest." }
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
        Invoke-Download -Uri $manifestEntry.url -Destination $jsonPath
    }
    return Get-Content -LiteralPath $jsonPath -Raw | ConvertFrom-Json
}

function Get-VersionChain {
    param([string]$VersionId)
    $chain = [Collections.Generic.List[object]]::new()
    $seen = [Collections.Generic.HashSet[string]]::new()
    $current = $VersionId
    while ($current) {
        if (-not $seen.Add($current)) { throw "Circular version inheritance at '$current'." }
        $data = Get-VersionData $current
        $chain.Insert(0, [pscustomobject]@{ Id = $current; Data = $data })
        $current = if ($data.PSObject.Properties.Name -contains 'inheritsFrom') { [string]$data.inheritsFrom } else { $null }
    }
    return @($chain)
}

function Get-ArgumentValues {
    param([object[]]$Items)
    $result = [Collections.Generic.List[string]]::new()
    foreach ($item in $Items) {
        if ($item -is [string]) { $result.Add([string]$item); continue }
        $itemRules = if ($item.PSObject.Properties.Name -contains 'rules') { @($item.rules) } else { @() }
        if (-not (Get-AllowedByRules $itemRules)) { continue }
        if ($item.value -is [string]) { $result.Add([string]$item.value) }
        else { foreach ($value in $item.value) { $result.Add([string]$value) } }
    }
    return @($result)
}

function Expand-Variables {
    param([string[]]$Arguments, [hashtable]$Variables)
    foreach ($argument in $Arguments) {
        $expanded = $argument
        foreach ($entry in $Variables.GetEnumerator()) { $expanded = $expanded.Replace('${' + $entry.Key + '}', [string]$entry.Value) }
        $expanded
    }
}

function Get-OfflineUuid {
    param([string]$Username)
    $md5 = [Security.Cryptography.MD5]::Create()
    try { $bytes = $md5.ComputeHash([Text.Encoding]::UTF8.GetBytes("OfflinePlayer:$Username")) } finally { $md5.Dispose() }
    $bytes[6] = ($bytes[6] -band 0x0f) -bor 0x30
    $bytes[8] = ($bytes[8] -band 0x3f) -bor 0x80
    $hex = -join ($bytes | ForEach-Object { $_.ToString('x2') })
    return "$($hex.Substring(0,8))-$($hex.Substring(8,4))-$($hex.Substring(12,4))-$($hex.Substring(16,4))-$($hex.Substring(20,12))"
}

function Get-JavaExecutable {
    param([int]$MajorVersion, [string]$Component)
    $candidates = [Collections.Generic.List[string]]::new()
    if ($Component) {
        $candidates.Add((Join-Path $MinecraftDirectory "runtime\$Component\windows-x64\$Component\bin\java.exe"))
    }
    $toolchains = Join-Path (Split-Path -Parent $PSScriptRoot) 'compatibility-builds\.toolchains'
    if (Test-Path -LiteralPath $toolchains) {
        Get-ChildItem -LiteralPath $toolchains -Filter java.exe -File -Recurse -ErrorAction SilentlyContinue |
            Where-Object FullName -Match "jdk-$MajorVersion" | ForEach-Object { $candidates.Add($_.FullName) }
    }
    $command = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($command) { $candidates.Add($command.Source) }
    foreach ($candidate in $candidates) {
        if (-not (Test-Path -LiteralPath $candidate)) { continue }
        # java -version writes its normal output to stderr. Windows PowerShell
        # turns redirected native stderr into NativeCommandError records when
        # ErrorActionPreference is Stop, so capture both streams directly.
        $startInfo = [Diagnostics.ProcessStartInfo]::new()
        $startInfo.FileName = $candidate
        $startInfo.Arguments = '-version'
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        $process = [Diagnostics.Process]::new()
        try {
            $process.StartInfo = $startInfo
            if (-not $process.Start()) { continue }
            $standardOutput = $process.StandardOutput.ReadToEnd()
            $standardError = $process.StandardError.ReadToEnd()
            $process.WaitForExit()
            $versionText = "$standardError`n$standardOutput"
        }
        catch {
            continue
        }
        finally {
            $process.Dispose()
        }
        if ($versionText -match 'version "(?<major>1\.)?(?<number>\d+)') {
            $actual = [int]$Matches.number
            if ($actual -eq $MajorVersion) { return $candidate }
        }
    }
    throw "Java $MajorVersion was not found. Run the compatibility build once so its toolchain is installed."
}

function ConvertTo-CommandLineArgument {
    param([string]$Value)
    if ($Value -notmatch '[\s"]') { return $Value }
    return '"' + ([regex]::Replace($Value, '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
}

function Start-OfflineProfile {
    param([string]$SelectedProfileId, [object]$Profile)
    $versionId = [string]$Profile.lastVersionId
    $gameDirectory = if ($Profile.gameDir) { [string]$Profile.gameDir } else { $MinecraftDirectory }
    New-Item -ItemType Directory -Path $gameDirectory -Force | Out-Null
    $chain = Get-VersionChain $versionId
    $libraries = [ordered]@{}
    $gameArguments = [Collections.Generic.List[string]]::new()
    $jvmArguments = [Collections.Generic.List[string]]::new()
    $mainClass = $null; $assetIndex = $null; $assetName = $null; $javaMajor = 8; $javaComponent = $null
    $loggingArgument = $null

    foreach ($version in $chain) {
        $data = $version.Data
        if ($data.PSObject.Properties.Name -contains 'mainClass') { $mainClass = [string]$data.mainClass }
        if ($data.PSObject.Properties.Name -contains 'assetIndex') { $assetIndex = $data.assetIndex; $assetName = [string]$data.assetIndex.id }
        if ($data.PSObject.Properties.Name -contains 'assets') { $assetName = [string]$data.assets }
        if ($data.PSObject.Properties.Name -contains 'javaVersion') { $javaMajor = [int]$data.javaVersion.majorVersion; $javaComponent = [string]$data.javaVersion.component }
        foreach ($library in @($data.libraries)) { $libraries[[string]$library.name] = $library }
        if ($data.PSObject.Properties.Name -contains 'arguments') {
            if ($data.arguments.PSObject.Properties.Name -contains 'jvm') { foreach ($arg in (Get-ArgumentValues @($data.arguments.jvm))) { $jvmArguments.Add($arg) } }
            if ($data.arguments.PSObject.Properties.Name -contains 'game') { foreach ($arg in (Get-ArgumentValues @($data.arguments.game))) { $gameArguments.Add($arg) } }
        } elseif ($data.PSObject.Properties.Name -contains 'minecraftArguments') {
            foreach ($arg in ([Management.Automation.PSParser]::Tokenize([string]$data.minecraftArguments, [ref]$null) | Where-Object Type -In @('CommandArgument','String') | ForEach-Object Content)) { $gameArguments.Add($arg) }
        }
        if (($data.PSObject.Properties.Name -contains 'logging') -and ($data.logging.PSObject.Properties.Name -contains 'client')) {
            $logFile = Join-Path $MinecraftDirectory "assets\log_configs\$($data.logging.client.file.id)"
            Invoke-Download $data.logging.client.file.url $logFile
            $loggingArgument = ([string]$data.logging.client.argument).Replace('${path}', $logFile)
        }
        if (($data.PSObject.Properties.Name -contains 'downloads') -and ($data.downloads.PSObject.Properties.Name -contains 'client')) {
            $clientJar = Join-Path $MinecraftDirectory "versions\$($version.Id)\$($version.Id).jar"
            Invoke-Download $data.downloads.client.url $clientJar
        }
    }

    $classPath = [Collections.Generic.List[string]]::new()
    $nativeJars = [Collections.Generic.List[string]]::new()
    foreach ($library in $libraries.Values) {
        $libraryRules = if ($library.PSObject.Properties.Name -contains 'rules') { @($library.rules) } else { @() }
        if (-not (Get-AllowedByRules $libraryRules)) { continue }
        if (($library.PSObject.Properties.Name -contains 'downloads') -and ($library.downloads.PSObject.Properties.Name -contains 'artifact')) {
            $artifact = $library.downloads.artifact
            $path = Join-Path $MinecraftDirectory ('libraries\' + ([string]$artifact.path).Replace('/', '\'))
            Invoke-Download $artifact.url $path
            $classPath.Add($path)
        } elseif ($library.PSObject.Properties.Name -contains 'name') {
            $coordinates = ([string]$library.name) -split ':'
            if ($coordinates.Count -eq 3) {
                $groupPath = $coordinates[0].Replace('.', '/')
                $artifactName = $coordinates[1]
                $artifactVersion = $coordinates[2]
                $relativePath = "$groupPath/$artifactName/$artifactVersion/$artifactName-$artifactVersion.jar"
                $path = Join-Path $MinecraftDirectory ('libraries\' + $relativePath.Replace('/', '\'))
                $baseUri = if ($library.url) { [string]$library.url } else { 'https://libraries.minecraft.net/' }
                Invoke-Download (($baseUri.TrimEnd('/') + '/' + $relativePath)) $path
                $classPath.Add($path)
            }
        }
        if (($library.PSObject.Properties.Name -contains 'natives') -and ($library.natives.PSObject.Properties.Name -contains 'windows')) {
            $classifierName = ([string]$library.natives.windows).Replace('${arch}', '64')
            $classifier = $library.downloads.classifiers.$classifierName
            if ($classifier) {
                $path = Join-Path $MinecraftDirectory ('libraries\' + ([string]$classifier.path).Replace('/', '\'))
                Invoke-Download $classifier.url $path
                $nativeJars.Add($path)
            }
        }
    }
    foreach ($version in $chain) {
        $jar = Join-Path $MinecraftDirectory "versions\$($version.Id)\$($version.Id).jar"
        if (Test-Path -LiteralPath $jar) { $classPath.Add($jar) }
    }

    $assetsIndexPath = Join-Path $MinecraftDirectory "assets\indexes\$assetName.json"
    Invoke-Download $assetIndex.url $assetsIndexPath
    $assets = Get-Content -LiteralPath $assetsIndexPath -Raw | ConvertFrom-Json
    foreach ($asset in $assets.objects.PSObject.Properties.Value) {
        $hash = [string]$asset.hash
        Invoke-Download "https://resources.download.minecraft.net/$($hash.Substring(0,2))/$hash" (Join-Path $MinecraftDirectory "assets\objects\$($hash.Substring(0,2))\$hash")
    }

    $nativesDirectory = Join-Path $gameDirectory ".voxy-natives\$($versionId -replace '[^A-Za-z0-9._-]', '_')"
    New-Item -ItemType Directory -Path $nativesDirectory -Force | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    foreach ($nativeJar in $nativeJars) {
        $archive = [IO.Compression.ZipFile]::OpenRead($nativeJar)
        try {
            foreach ($entry in $archive.Entries) {
                if (-not $entry.Name -or $entry.FullName -like 'META-INF/*') { continue }
                $target = Join-Path $nativesDirectory $entry.Name
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
            }
        } finally { $archive.Dispose() }
    }

    $uuid = Get-OfflineUuid $OfflineUsername
    $variables = @{
        natives_directory = $nativesDirectory; launcher_name = 'VoxyCompatibility'; launcher_version = '1';
        classpath = ($classPath -join [IO.Path]::PathSeparator); classpath_separator = [IO.Path]::PathSeparator;
        library_directory = (Join-Path $MinecraftDirectory 'libraries'); auth_player_name = $OfflineUsername;
        version_name = $versionId; game_directory = $gameDirectory; assets_root = (Join-Path $MinecraftDirectory 'assets');
        assets_index_name = $assetName; auth_uuid = $uuid; auth_access_token = '0'; clientid = '';
        auth_xuid = ''; user_type = 'legacy'; version_type = 'custom'; user_properties = '{}';
        resolution_width = '1280'; resolution_height = '720'; game_assets = (Join-Path $MinecraftDirectory 'assets')
    }
    $expandedJvm = @(Expand-Variables @($jvmArguments) $variables)
    if ($expandedJvm -notcontains '-Xmx' + $MaximumRamMb + 'M') { $expandedJvm = @("-Xmx${MaximumRamMb}M") + $expandedJvm }
    if ($loggingArgument) { $expandedJvm += $loggingArgument }
    $expandedGame = @(Expand-Variables @($gameArguments) $variables)
    $java = Get-JavaExecutable $javaMajor $javaComponent
    $javaw = Join-Path (Split-Path -Parent $java) 'javaw.exe'
    if (-not (Test-Path -LiteralPath $javaw)) { $javaw = $java }
    $allArguments = @($expandedJvm) + @($mainClass) + $expandedGame
    $argumentLine = (@($allArguments | ForEach-Object { ConvertTo-CommandLineArgument $_ }) -join ' ')
    Write-Host "Launching $($Profile.name) offline as $OfflineUsername (Java $javaMajor)" -ForegroundColor Green
    if ($DryRun) {
        Write-Host "Dry run: command resolved ($($allArguments.Count) arguments)." -ForegroundColor Cyan
        return
    }
    $launchLogBase = Join-Path $gameDirectory '.voxy-launch'
    $process = Start-Process -FilePath $javaw -ArgumentList $argumentLine -WorkingDirectory $gameDirectory `
        -RedirectStandardOutput "$launchLogBase.out.log" -RedirectStandardError "$launchLogBase.err.log" -PassThru
    if ($Wait) {
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            $errorLog = if (Test-Path -LiteralPath "$launchLogBase.err.log") { Get-Content -LiteralPath "$launchLogBase.err.log" -Raw } else { '' }
            if ([string]::IsNullOrWhiteSpace($errorLog)) { $errorLog = 'No error output was produced.' }
            throw "Minecraft exited with code $($process.ExitCode):`n$errorLog"
        }
    }
}

$profilesPath = Join-Path $MinecraftDirectory 'launcher_profiles.json'
$launcherData = Get-Content -LiteralPath $profilesPath -Raw | ConvertFrom-Json

if (-not $ProfileId -and -not $MinecraftVersion) {
    $availableProfiles = @($launcherData.profiles.PSObject.Properties |
        Where-Object Name -Like 'voxy-test-*' |
        Sort-Object { [version]($_.Name -replace '^voxy-test-', '') })
    if ($availableProfiles.Count -eq 0) { throw 'No Voxy test profiles were found.' }

    $selectedProfiles = New-Object bool[] $availableProfiles.Count
    $focusedIndex = 0

    :profileSelection while ($true) {
        [Console]::Clear()
        [Console]::SetCursorPosition(0, 0)
        Write-Host 'Available Minecraft profiles' -ForegroundColor Cyan
        Write-Host "Use the arrow keys to move, Space to select/deselect, and Enter to launch. A selects all; Q cancels.`n"

        for ($index = 0; $index -lt $availableProfiles.Count; $index++) {
            $pointer = if ($index -eq $focusedIndex) { '>' } else { ' ' }
            $checkMark = if ($selectedProfiles[$index]) { 'x' } else { ' ' }
            Write-Host ('{0} [{1}] {2}' -f $pointer, $checkMark, $availableProfiles[$index].Value.name)
        }
        $selectedNames = @(
            for ($index = 0; $index -lt $availableProfiles.Count; $index++) {
                if ($selectedProfiles[$index]) { $availableProfiles[$index].Value.name }
            }
        )
        Write-Host "`nMarcados: $($selectedNames -join ', ')"

        $key = [Console]::ReadKey($true)
        switch ($key.Key) {
            'UpArrow' {
                $focusedIndex = ($focusedIndex - 1 + $availableProfiles.Count) % $availableProfiles.Count
            }
            'DownArrow' {
                $focusedIndex = ($focusedIndex + 1) % $availableProfiles.Count
            }
            'Spacebar' {
                $selectedProfiles[$focusedIndex] = -not $selectedProfiles[$focusedIndex]
            }
            'Enter' {
                $ProfileId = @(
                    for ($index = 0; $index -lt $availableProfiles.Count; $index++) {
                        if ($selectedProfiles[$index]) { $availableProfiles[$index].Name }
                    }
                )
                if ($ProfileId.Count -gt 0) { break profileSelection }
            }
            'A' {
                $ProfileId = @($availableProfiles | ForEach-Object Name)
                break profileSelection
            }
            'Q' {
                Write-Host 'Cancelled.'
                exit 0
            }
        }
    }
}

$selected = @($launcherData.profiles.PSObject.Properties | Where-Object {
    ($ProfileId -and $_.Name -in $ProfileId) -or
    ($MinecraftVersion -and $_.Value.lastVersionId -and ($_.Value.lastVersionId -replace '^fabric-loader-[^-]+-', '') -in $MinecraftVersion)
})
if ($selected.Count -eq 0) { throw 'No matching Minecraft profiles were found.' }
foreach ($property in $selected) { Start-OfflineProfile $property.Name $property.Value }

[CmdletBinding()]
param(
    [string]$RepositoryRoot,
    [string]$OutputDirectory,
    [string]$MinecraftDirectory = (Join-Path $env:APPDATA ".minecraft"),
    [string]$ProfilesDirectory = (Join-Path (Join-Path $env:APPDATA ".minecraft") "voxy-test-profiles"),
    [string]$WorktreeBaseDirectory = (Join-Path ([System.IO.Path]::GetTempPath()) "voxy-compat-worktrees"),
    [string[]]$Branches,
    [string[]]$Versions,
    [string]$Java17Home,
    [string]$Java21Home,
    [string]$Java25Home,
    [string]$FabricInstallerVersion = "1.1.0",
    [switch]$SkipFabricInstall,
    [switch]$SkipRuntimeMods,
    [switch]$SkipProfileCreation,
    [switch]$ProfilesOnly,
    [switch]$ReuseExistingArtifacts,
    [switch]$NoInteractiveMenu,
    [switch]$KeepWorktrees,
    [switch]$ContinueOnBuildFailure
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = Split-Path -Parent $PSScriptRoot
}
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $RepositoryRoot "compatibility-builds"
}

# Each entry is built once. The resulting JAR is copied to every TestVersion.
# ExpectedSourceVersion prevents a placeholder branch from being presented as a
# real port. Update the branch itself; do not weaken this check.
$matrix = @(
    [pscustomobject]@{ Branch = "dev";                    ExpectedSourceVersion = "26.2";    TestVersions = @("26.2");                         PreBuildTasks = @() },
    [pscustomobject]@{ Branch = "mc_26.1";                ExpectedSourceVersion = "26.1.2";  TestVersions = @("26.1.2");                       PreBuildTasks = @() },
    [pscustomobject]@{ Branch = "mc_26.1.1";              ExpectedSourceVersion = "26.1.1";  TestVersions = @("26.1.1");                       PreBuildTasks = @() },
    [pscustomobject]@{ Branch = "mc_1.21.11";             ExpectedSourceVersion = "1.21.11"; TestVersions = @("1.21.11");                      PreBuildTasks = @() },
    [pscustomobject]@{ Branch = "mc_1.21.9-1.21.10";      ExpectedSourceVersion = "1.21.10"; TestVersions = @("1.21.9", "1.21.10");          PreBuildTasks = @() },
    [pscustomobject]@{ Branch = "mc_1.21.6-1.21.8";       ExpectedSourceVersion = "1.21.8";  TestVersions = @("1.21.6", "1.21.7", "1.21.8"); PreBuildTasks = @() },
    [pscustomobject]@{ Branch = "mc_1.21.5";              ExpectedSourceVersion = "1.21.5";  TestVersions = @("1.21.5");                       PreBuildTasks = @() },
    [pscustomobject]@{ Branch = "mc_1.21.4";              ExpectedSourceVersion = "1.21.4";  TestVersions = @("1.21.4");                       PreBuildTasks = @() },
    [pscustomobject]@{ Branch = "mc_1.21.3";              ExpectedSourceVersion = "1.21.3";  TestVersions = @("1.21.3");                       PreBuildTasks = @("clean", "processIncludeJars") },
    [pscustomobject]@{ Branch = "mc_1.21-1.21.1";         ExpectedSourceVersion = "1.21";    TestVersions = @("1.21", "1.21.1");              PreBuildTasks = @() },
    [pscustomobject]@{ Branch = "mc_1.20-1.20.6";         ExpectedSourceVersion = "1.20.2";  BuildVersion = "1.20.6"; JavaVersion = 21; ArtifactKey = "mc_1.20-1.20.6__1.20.6"; TestVersions = @("1.20.6"); PreBuildTasks = @() },
    [pscustomobject]@{ Branch = "mc_1.20-1.20.6";         ExpectedSourceVersion = "1.20.2";  BuildVersion = "1.20.4"; JavaVersion = 17; ArtifactKey = "mc_1.20-1.20.6__1.20.4"; TestVersions = @("1.20.4"); PreBuildTasks = @() },
    [pscustomobject]@{ Branch = "mc_1.20-1.20.6";         ExpectedSourceVersion = "1.20.2";  BuildVersion = "1.20.2"; JavaVersion = 17; ArtifactKey = "mc_1.20-1.20.6__1.20.2"; TestVersions = @("1.20.2"); PreBuildTasks = @() },
    [pscustomobject]@{ Branch = "mc_1.20-1.20.6";         ExpectedSourceVersion = "1.20.2";  BuildVersion = "1.20.1"; JavaVersion = 17; ArtifactKey = "mc_1.20-1.20.6__1.20.1"; TestVersions = @("1.20.1"); PreBuildTasks = @() },
    [pscustomobject]@{ Branch = "mc_1.20-1.20.6";         ExpectedSourceVersion = "1.20.2";  BuildVersion = "1.20";   JavaVersion = 17; ArtifactKey = "mc_1.20-1.20.6__1.20";   TestVersions = @("1.20");   PreBuildTasks = @() }
)

if ($Branches -and $Versions) {
    throw "Branches and Versions cannot be used together."
}

$interactiveSelection = -not $NoInteractiveMenu -and -not $Branches -and -not $Versions -and -not $ProfilesOnly
if ($interactiveSelection) {
    $availableVersions = @(
        $matrix.TestVersions |
            Select-Object -Unique |
            Sort-Object { [version]$_ } -Descending
    )
    $selected = New-Object bool[] $availableVersions.Count
    $focusedIndex = 0

    :selection while ($true) {
        [Console]::Clear()
        [Console]::SetCursorPosition(0, 0)
        Write-Host "Minecraft compatibility builds" -ForegroundColor Cyan
        Write-Host "Use the arrow keys to move, Space to select/deselect, and Enter to continue. A selects all; Q cancels.`n"

        for ($index = 0; $index -lt $availableVersions.Count; $index++) {
            $pointer = if ($index -eq $focusedIndex) { ">" } else { " " }
            $checkMark = if ($selected[$index]) { "x" } else { " " }
            Write-Host ("{0} [{1}] {2}" -f $pointer, $checkMark, $availableVersions[$index])
        }
        Write-Host ("Selected: {0}" -f (@(for ($index = 0; $index -lt $availableVersions.Count; $index++) { if ($selected[$index]) { $availableVersions[$index] } }) -join ', '))

        $key = [Console]::ReadKey($true)
        switch ($key.Key) {
            'UpArrow' {
                $focusedIndex = ($focusedIndex - 1 + $availableVersions.Count) % $availableVersions.Count
            }
            'DownArrow' {
                $focusedIndex = ($focusedIndex + 1) % $availableVersions.Count
            }
            'Spacebar' {
                $selected[$focusedIndex] = -not $selected[$focusedIndex]
            }
            'Enter' {
                $Versions = @(
                    for ($index = 0; $index -lt $availableVersions.Count; $index++) {
                        if ($selected[$index]) { $availableVersions[$index] }
                    }
                )
                if ($Versions.Count -gt 0) { break selection }
            }
            'A' {
                $Versions = $availableVersions
                break selection
            }
            'Q' {
                Write-Host "Cancelled."
                exit 0
            }
        }
    }
}

if ($Branches) {
    $unknownBranches = @($Branches | Where-Object { $_ -notin $matrix.Branch })
    if ($unknownBranches.Count -gt 0) {
        throw "Unknown compatibility branches: $($unknownBranches -join ', ')"
    }
    $matrix = @($matrix | Where-Object { $_.Branch -in $Branches })
}

if ($Versions) {
    $availableVersions = @($matrix.TestVersions | Select-Object -Unique)
    $unknownVersions = @($Versions | Where-Object { $_ -notin $availableVersions })
    if ($unknownVersions.Count -gt 0) {
        throw "Unknown Minecraft versions: $($unknownVersions -join ', ')"
    }

    $selectedMatrix = [System.Collections.Generic.List[object]]::new()
    foreach ($entry in $matrix) {
        $selectedTestVersions = @($entry.TestVersions | Where-Object { $_ -in $Versions })
        if ($selectedTestVersions.Count -gt 0) {
            $entryCopy = $entry | Select-Object *
            $entryCopy.TestVersions = $selectedTestVersions
            $selectedMatrix.Add($entryCopy)
        }
    }
    $matrix = @($selectedMatrix)
}

if ($ProfilesOnly -and $SkipProfileCreation) {
    throw "ProfilesOnly and SkipProfileCreation cannot be used together."
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$ArgumentList,
        [Parameter(Mandatory)][string]$WorkingDirectory
    )

    Push-Location -LiteralPath $WorkingDirectory
    try {
        & $FilePath @ArgumentList
        if ($LASTEXITCODE -ne 0) {
            throw "Command failed with exit code ${LASTEXITCODE}: $FilePath $($ArgumentList -join ' ')"
        }
    }
    finally {
        Pop-Location
    }
}

function Remove-CompatibilityWorktree {
    param([Parameter(Mandatory)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }

    # core.longpaths is deliberately scoped to this command. A short temporary
    # worktree root prevents the problem; this option also makes cleanup robust
    # when Gradle creates deeply nested files.
    & git -C $RepositoryRoot -c core.longpaths=true worktree remove --force $Path
    if ($LASTEXITCODE -ne 0 -and (Test-Path -LiteralPath $Path)) {
        $fullPath = [System.IO.Path]::GetFullPath($Path)
        $allowedRoot = [System.IO.Path]::GetFullPath($worktreeRoot) + [System.IO.Path]::DirectorySeparatorChar
        if (-not $fullPath.StartsWith($allowedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to clean a path outside the compatibility worktree root: $fullPath"
        }

        try {
            Get-ChildItem -LiteralPath $fullPath -Force -Recurse -ErrorAction SilentlyContinue |
                Where-Object { -not $_.PSIsContainer -and ($_.Attributes -band [System.IO.FileAttributes]::ReadOnly) } |
                ForEach-Object { $_.Attributes = $_.Attributes -band (-bnot [System.IO.FileAttributes]::ReadOnly) }
            Remove-Item -LiteralPath ("\\?\" + $fullPath) -Recurse -Force -ErrorAction Stop
        }
        catch {
            Write-Warning "Build completed, but temporary worktree cleanup failed for '$fullPath': $($_.Exception.Message)"
        }
    }
}

function Get-PropertyValue {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Name
    )

    $match = Get-Content -LiteralPath $Path | Where-Object { $_ -match "^\s*$([regex]::Escape($Name))\s*=" } | Select-Object -First 1
    if (-not $match) {
        throw "Property '$Name' was not found in $Path"
    }
    return (($match -split "=", 2)[1]).Trim()
}

function Get-BuildJar {
    param([Parameter(Mandatory)][string]$Worktree)

    $jars = @(Get-ChildItem -LiteralPath (Join-Path $Worktree "build\libs") -Filter "*.jar" -File |
        Where-Object {
            $_.Name -notmatch "(?i)(sources|javadoc|dev|dummyprovider)"
        } |
        Sort-Object Length -Descending)

    if ($jars.Count -ne 1) {
        $names = (@($jars | ForEach-Object { $_.Name }) -join ", ")
        throw "Expected exactly one distributable mod JAR in '$Worktree\build\libs'; found $($jars.Count): $names"
    }
    return $jars[0]
}

function Get-FabricManifestFromJar {
    param([Parameter(Mandatory)][string]$JarPath)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $entry = $archive.GetEntry("fabric.mod.json")
        if (-not $entry) {
            return $null
        }
        $reader = [System.IO.StreamReader]::new($entry.Open())
        try {
            return ($reader.ReadToEnd() | ConvertFrom-Json)
        }
        finally {
            $reader.Dispose()
        }
    }
    finally {
        $archive.Dispose()
    }
}

function Get-FabricModIdFromJar {
    param([Parameter(Mandatory)][string]$JarPath)

    $manifest = Get-FabricManifestFromJar -JarPath $JarPath
    if ($manifest) { return $manifest.id }
    return $null
}

function Get-JavaMajorVersion {
    param([Parameter(Mandatory)][string]$JavaHomePath)

    $java = Join-Path $JavaHomePath "bin\java.exe"
    if (-not (Test-Path -LiteralPath $java)) {
        return $null
    }

    # `java -version` writes normal version information to stderr. Capturing it
    # through PowerShell's native pipeline turns that into an error when
    # $ErrorActionPreference is Stop, so use Process directly.
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $java
    $startInfo.Arguments = "-version"
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        [void]$process.Start()
        $output = $process.StandardOutput.ReadToEnd() + "`n" + $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            return $null
        }
    }
    finally {
        $process.Dispose()
    }

    $match = [regex]::Match($output, 'version "(?<major>\d+)')
    if (-not $match.Success) {
        return $null
    }
    return [int]$match.Groups["major"].Value
}

function Find-InstalledJavaHome {
    param([Parameter(Mandatory)][int]$MajorVersion)

    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($env:JAVA_HOME) {
        $candidates.Add($env:JAVA_HOME)
    }

    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) {
        $candidates.Add((Split-Path -Parent (Split-Path -Parent $javaCommand.Source)))
    }

    foreach ($root in @(
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\Java",
        "C:\Program Files\Microsoft",
        "C:\Program Files\BellSoft",
        (Join-Path $env:USERPROFILE ".gradle\jdks")
    )) {
        if (Test-Path -LiteralPath $root) {
            Get-ChildItem -LiteralPath $root -Filter "java.exe" -File -Recurse -ErrorAction SilentlyContinue |
                ForEach-Object { $candidates.Add((Split-Path -Parent (Split-Path -Parent $_.FullName))) }
        }
    }

    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if ((Get-JavaMajorVersion -JavaHomePath $candidate) -eq $MajorVersion) {
            return $candidate
        }
    }
    return $null
}

function Get-JavaHome {
    param(
        [Parameter(Mandatory)][int]$MajorVersion,
        [string]$ConfiguredHome
    )

    if ($ConfiguredHome) {
        if ((Get-JavaMajorVersion -JavaHomePath $ConfiguredHome) -ne $MajorVersion) {
            throw "Configured Java home '$ConfiguredHome' is not JDK $MajorVersion."
        }
        return (Resolve-Path -LiteralPath $ConfiguredHome).Path
    }

    $installed = Find-InstalledJavaHome -MajorVersion $MajorVersion
    if ($installed) {
        return $installed
    }

    $toolchainsDirectory = Join-Path $OutputDirectory ".toolchains"
    $javaDirectory = Join-Path $toolchainsDirectory "jdk-$MajorVersion"
    $existingJava = Get-ChildItem -LiteralPath $javaDirectory -Filter "java.exe" -File -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($existingJava) {
        return (Split-Path -Parent (Split-Path -Parent $existingJava.FullName))
    }

    New-Item -ItemType Directory -Path $toolchainsDirectory -Force | Out-Null
    $archive = Join-Path $toolchainsDirectory "jdk-$MajorVersion.zip"
    $downloadUri = "https://api.adoptium.net/v3/binary/latest/$MajorVersion/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk"
    Write-Host "Downloading portable JDK $MajorVersion..." -ForegroundColor Yellow
    Invoke-WebRequest -Uri $downloadUri -OutFile $archive
    New-Item -ItemType Directory -Path $javaDirectory -Force | Out-Null
    Expand-Archive -LiteralPath $archive -DestinationPath $javaDirectory -Force
    Remove-Item -LiteralPath $archive -Force

    $downloadedJava = Get-ChildItem -LiteralPath $javaDirectory -Filter "java.exe" -File -Recurse |
        Select-Object -First 1
    if (-not $downloadedJava) {
        throw "Downloaded JDK $MajorVersion does not contain java.exe."
    }
    return (Split-Path -Parent (Split-Path -Parent $downloadedJava.FullName))
}

function Get-FabricLoaderVersion {
    param([Parameter(Mandatory)][string]$MinecraftVersion)

    $uri = "https://meta.fabricmc.net/v2/versions/loader/$MinecraftVersion"
    $options = Invoke-RestMethod -Uri $uri
    $stable = $options | Where-Object { $_.loader.stable } | Select-Object -First 1
    if (-not $stable) {
        throw "Fabric Meta returned no stable loader for Minecraft $MinecraftVersion"
    }
    return $stable.loader.version
}

function Get-ModVersionFromModrinthVersion {
    param(
        [Parameter(Mandatory)][string]$ProjectId,
        [Parameter(Mandatory)][string]$VersionNumber
    )

    if ($ProjectId -eq "AANobbMI") {
        $match = [regex]::Match($VersionNumber, '-(?<version>\d+\.\d+\.\d+)(?:[-+]|$)')
    }
    else {
        $match = [regex]::Match($VersionNumber, '^(?<version>\d+(?:\.\d+){0,2})')
    }
    if (-not $match.Success) { return $null }

    $parts = @($match.Groups["version"].Value.Split('.') | ForEach-Object { [int]$_ })
    while ($parts.Count -lt 3) { $parts += 0 }
    return [version]::new($parts[0], $parts[1], $parts[2])
}

function ConvertTo-ThreePartVersion {
    param([Parameter(Mandatory)][string]$Value)

    $parts = @($Value.Split('.') | ForEach-Object { [int]$_ })
    while ($parts.Count -lt 3) { $parts += 0 }
    return [version]::new($parts[0], $parts[1], $parts[2])
}

function Test-ModVersionConstraint {
    param(
        [Parameter(Mandatory)][string]$ProjectId,
        [Parameter(Mandatory)][string]$VersionNumber,
        $Constraint
    )

    $alternatives = @($Constraint)
    if ($alternatives.Count -eq 0 -or "*" -in $alternatives) { return $true }
    $candidate = Get-ModVersionFromModrinthVersion -ProjectId $ProjectId -VersionNumber $VersionNumber
    if (-not $candidate) { return $false }

    foreach ($alternativeValue in $alternatives) {
        $alternative = [string]$alternativeValue
        if ($alternative -eq "*") { return $true }
        if ($alternative -match '^=(?<major>\d+)\.(?<minor>\d+)\.\*$') {
            if ($candidate.Major -eq [int]$Matches.major -and $candidate.Minor -eq [int]$Matches.minor) { return $true }
            continue
        }
        if ($alternative -match '^=?(?<exact>\d+(?:\.\d+){0,2})$') {
            if ($candidate -eq (ConvertTo-ThreePartVersion -Value $Matches.exact)) { return $true }
            continue
        }

        $minimum = $null
        $maximum = $null
        if ($alternative -match '>=(?<minimum>\d+(?:\.\d+){0,2})') { $minimum = ConvertTo-ThreePartVersion -Value $Matches.minimum }
        if ($alternative -match '<=(?<maximum>\d+(?:\.\d+){0,2})') { $maximum = ConvertTo-ThreePartVersion -Value $Matches.maximum }
        if (($null -eq $minimum -or $candidate -ge $minimum) -and ($null -eq $maximum -or $candidate -le $maximum)) {
            return $true
        }
    }
    return $false
}

function Get-ModrinthPrimaryFile {
    param(
        [Parameter(Mandatory)][string]$ProjectId,
        [Parameter(Mandatory)][string]$MinecraftVersion,
        [string]$Loader = "fabric",
        $VersionConstraint = "*",
        [string]$RequiredVersionId,
        [string]$VersionNumberPattern
    )

    $headers = @{
        "User-Agent" = "vistaero-Voxyrium-compatibility-script/1.0"
    }
    $versions = [System.Collections.Generic.List[object]]::new()
    if ($RequiredVersionId) {
        $version = Invoke-RestMethod -Uri "https://api.modrinth.com/v2/version/$RequiredVersionId" -Headers $headers
        if ($version.project_id -ne $ProjectId -or
            $version.status -ne "listed" -or
            $version.game_versions -notcontains $MinecraftVersion -or
            $version.loaders -notcontains $Loader) {
            throw "Modrinth dependency version $RequiredVersionId is not a listed $Loader version for Minecraft $MinecraftVersion."
        }
        $versions.Add($version)
    }
    else {
        $loaders = [uri]::EscapeDataString((ConvertTo-Json @($Loader) -Compress))
        $gameVersions = [uri]::EscapeDataString((ConvertTo-Json @($MinecraftVersion) -Compress))
        $uri = "https://api.modrinth.com/v2/project/$ProjectId/version?loaders=$loaders&game_versions=$gameVersions&include_changelog=false"
        $response = Invoke-RestMethod -Uri $uri -Headers $headers
        foreach ($version in $response) {
            $versions.Add($version)
        }
    }
    $listed = @($versions | Where-Object {
        $_.status -eq "listed" -and
        $_.files.Count -gt 0 -and
        (-not $RequiredVersionId -or $_.id -eq $RequiredVersionId) -and
        (-not $VersionNumberPattern -or $_.version_number -match $VersionNumberPattern) -and
        (Test-ModVersionConstraint -ProjectId $ProjectId -VersionNumber $_.version_number -Constraint $VersionConstraint)
    })
    if ($listed.Count -eq 0) {
        throw "Modrinth project $ProjectId has no listed Fabric version for Minecraft $MinecraftVersion matching constraint '$($VersionConstraint | ConvertTo-Json -Compress)'."
    }
    $selectedVersion = $null
    foreach ($channel in @("release", "beta", "alpha")) {
        $selectedVersion = $listed |
            Where-Object { $_.version_type -eq $channel } |
            Sort-Object -Property date_published -Descending |
            Select-Object -First 1
        if ($selectedVersion) { break }
    }
    if (-not $selectedVersion) {
        $selectedVersion = $listed | Sort-Object -Property date_published -Descending | Select-Object -First 1
    }

    $selectedFile = $selectedVersion.files | Where-Object { $_.primary } | Select-Object -First 1
    if (-not $selectedFile) {
        $selectedFile = $selectedVersion.files |
            Where-Object { $_.file_type -notin @("sources-jar", "dev-jar", "javadoc-jar", "signature") } |
            Select-Object -First 1
    }
    if (-not $selectedFile) {
        throw "Modrinth version $($selectedVersion.id) has no distributable primary file."
    }

    return [pscustomobject]@{
        ProjectId = $ProjectId
        VersionId = $selectedVersion.id
        VersionNumber = $selectedVersion.version_number
        VersionType = $selectedVersion.version_type
        FileName = $selectedFile.filename
        Url = $selectedFile.url
        Sha512 = $selectedFile.hashes.sha512
        Dependencies = @($selectedVersion.dependencies)
    }
}

function Get-CachedModrinthFile {
    param([Parameter(Mandatory)]$ModrinthFile)

    $projectId = [string](@($ModrinthFile.ProjectId)[0])
    $fileName = [string](@($ModrinthFile.FileName)[0])
    $downloadUrl = [string](@($ModrinthFile.Url)[0])
    $expectedSha512 = [string](@($ModrinthFile.Sha512)[0])
    $cacheDirectory = Join-Path (Join-Path $OutputDirectory "modrinth-cache") $projectId
    New-Item -ItemType Directory -Path $cacheDirectory -Force | Out-Null
    $cachePath = Join-Path $cacheDirectory $fileName

    $hashMatches = $false
    if (Test-Path -LiteralPath $cachePath) {
        $hashMatches = (Get-FileHash -LiteralPath $cachePath -Algorithm SHA512).Hash -ieq $expectedSha512
        if ($script:UpdateLogPath) {
            Write-UpdateLog "[$projectId] Cache file $(Split-Path -Leaf $cachePath): $(if ($hashMatches) { 'SHA-512 matches' } else { 'SHA-512 mismatch; redownloading' })."
        }
    }
    if (-not $hashMatches) {
        $temporaryPath = "$cachePath.download"
        $headers = @{
            "User-Agent" = "vistaero-Voxyrium-compatibility-script/1.0"
        }
        if ($script:UpdateLogPath) {
            Write-UpdateLog "[$projectId] Downloading $downloadUrl."
        }
        Invoke-WebRequest -Uri $downloadUrl -Headers $headers -OutFile $temporaryPath
        $downloadedHash = (Get-FileHash -LiteralPath $temporaryPath -Algorithm SHA512).Hash
        if ($downloadedHash -ine $expectedSha512) {
            Remove-Item -LiteralPath $temporaryPath -Force
            if ($script:UpdateLogPath) {
                Write-UpdateLog "[$projectId] SHA-512 verification failed for $fileName." "ERROR"
            }
            throw "SHA-512 verification failed for $fileName."
        }
        Move-Item -LiteralPath $temporaryPath -Destination $cachePath -Force
        if ($script:UpdateLogPath) {
            Write-UpdateLog "[$projectId] Downloaded and verified $fileName."
        }
    }
    return Get-Item -LiteralPath $cachePath
}

function Sync-ModrinthRuntimeMods {
    param(
        [Parameter(Mandatory)][string]$MinecraftVersion,
        [Parameter(Mandatory)][string]$ModsDirectory,
        [string]$VoxyArtifact
    )

    $managedManifestPath = Join-Path $ModsDirectory ".voxy-managed-mods.json"
    $previousEntries = @()
    if (Test-Path -LiteralPath $managedManifestPath) {
        $previousEntries = @(Get-Content -LiteralPath $managedManifestPath -Raw | ConvertFrom-Json)
    }

    $dependencyMap = @{
        "fabric-api" = [pscustomobject]@{ Name = "Fabric API"; ProjectId = "P7dR8mSH" }
        "sodium" = [pscustomobject]@{ Name = "Sodium"; ProjectId = "AANobbMI" }
        "cloth-config" = [pscustomobject]@{ Name = "Cloth Config"; ProjectId = "9s6osm5g" }
        "modmenu" = [pscustomobject]@{ Name = "Mod Menu"; ProjectId = "mOgUt4GM" }
        "iris" = [pscustomobject]@{ Name = "Iris Shaders"; ProjectId = "YL57xq9U" }
    }
    $constraints = @{
        "fabric-api" = "*"
        "sodium" = "*"
        "modmenu" = "*"
        "iris" = "*"
    }
    # Iris 1.7.6's Modrinth metadata still recommends a 0.5.12 beta, while its
    # Fabric manifest accepts Sodium 0.5.x and this Voxy target requires 0.5.13.
    if ($MinecraftVersion -eq "1.20.1") {
        $constraints.sodium = "=0.5.13"
    }
    if ($VoxyArtifact) {
        $voxyManifest = Get-FabricManifestFromJar -JarPath $VoxyArtifact
        foreach ($dependency in $voxyManifest.depends.PSObject.Properties) {
            if ($dependency.Name -in @("minecraft", "fabricloader", "java")) { continue }
            if (-not $dependencyMap.ContainsKey($dependency.Name)) {
                throw "Required mod '$($dependency.Name)' has no Modrinth project mapping in the compatibility script."
            }
            $constraints[$dependency.Name] = $dependency.Value
        }
    }

    $newEntries = [System.Collections.Generic.List[object]]::new()
    $resolvedFiles = @{}
    foreach ($dependencyId in @($constraints.Keys | Where-Object { $_ -notin @("sodium", "iris") } | Sort-Object) + @("iris", "sodium")) {
        $project = $dependencyMap[$dependencyId]
        $requiredVersionId = $null
        if ($dependencyId -eq "sodium" -and $resolvedFiles.ContainsKey("iris")) {
            $sodiumDependency = @($resolvedFiles["iris"].Dependencies | Where-Object {
                $_.project_id -eq $dependencyMap.sodium.ProjectId -and $_.dependency_type -eq "required"
            } | Select-Object -First 1)
            if ($sodiumDependency.Count -gt 0) {
                $irisRequiredVersionId = [string]$sodiumDependency[0].version_id
                if ($irisRequiredVersionId) {
                    $irisSodiumFile = Get-ModrinthPrimaryFile -ProjectId $project.ProjectId -MinecraftVersion $MinecraftVersion -VersionConstraint "*" -RequiredVersionId $irisRequiredVersionId
                    if (Test-ModVersionConstraint -ProjectId $project.ProjectId -VersionNumber $irisSodiumFile.VersionNumber -Constraint $constraints.sodium) {
                        $requiredVersionId = $irisRequiredVersionId
                    }
                    elseif ($script:UpdateLogPath) {
                        Write-UpdateLog "[$MinecraftVersion] Ignored Iris Sodium recommendation $($irisSodiumFile.VersionNumber); required constraint is $($constraints.sodium)."
                    }
                }
                else {
                    $constraints.sodium = [string]$sodiumDependency[0].version_req
                }
            }
        }
        $versionNumberPattern = if ($dependencyId -eq "iris") {
            "\+" + [regex]::Escape($MinecraftVersion) + "(?:$|[-+])"
        }
        $modrinthFile = Get-ModrinthPrimaryFile -ProjectId $project.ProjectId -MinecraftVersion $MinecraftVersion -VersionConstraint $constraints[$dependencyId] -RequiredVersionId $requiredVersionId -VersionNumberPattern $versionNumberPattern
        $resolvedFiles[$dependencyId] = $modrinthFile
        $cachedFile = Get-CachedModrinthFile -ModrinthFile $modrinthFile
        $fileName = [string](@($modrinthFile.FileName)[0])

        foreach ($existingJar in @(Get-ChildItem -LiteralPath $ModsDirectory -Filter "*.jar" -File -ErrorAction SilentlyContinue)) {
            try {
                $existingModId = [string](Get-FabricModIdFromJar -JarPath $existingJar.FullName)
            }
            catch {
                continue
            }
            if ($existingModId -eq $dependencyId -and $existingJar.Name -ne $fileName) {
                Remove-Item -LiteralPath $existingJar.FullName -Force
                if ($script:UpdateLogPath) {
                    Write-UpdateLog "[$MinecraftVersion] Removed old $($project.Name) file $($existingJar.Name)."
                }
            }
        }
        Copy-Item -LiteralPath $cachedFile.FullName -Destination (Join-Path $ModsDirectory $fileName) -Force
        $newEntries.Add([pscustomobject]@{
            project = $project.Name
            project_id = $project.ProjectId
            version_id = $modrinthFile.VersionId
            version_number = $modrinthFile.VersionNumber
            version_type = $modrinthFile.VersionType
            file_name = $fileName
            sha512 = $modrinthFile.Sha512
        })
        Write-Host "  ${MinecraftVersion}: $($project.Name) $($modrinthFile.VersionNumber)" -ForegroundColor DarkGray
        if ($script:UpdateLogPath) {
            Write-UpdateLog "[$MinecraftVersion] $($project.Name): $($modrinthFile.VersionNumber) ($($modrinthFile.FileName))."
        }
    }

    $newFileNames = @($newEntries | ForEach-Object { $_.file_name })
    foreach ($oldEntry in $previousEntries) {
        $oldFileName = [string](@($oldEntry.file_name)[0])
        if ($oldFileName -and $oldFileName -notin $newFileNames) {
            $oldPath = Join-Path $ModsDirectory $oldFileName
            if (Test-Path -LiteralPath $oldPath) {
                Remove-Item -LiteralPath $oldPath -Force
            }
        }
    }
    $newEntries | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $managedManifestPath -Encoding utf8
}

function Sync-ModrinthShaderPacks {
    param(
        [Parameter(Mandatory)][string]$MinecraftVersion,
        [Parameter(Mandatory)][string]$GameDirectory
    )

    $shaderPacksDirectory = Join-Path $GameDirectory "shaderpacks"
    New-Item -ItemType Directory -Path $shaderPacksDirectory -Force | Out-Null
    $managedManifestPath = Join-Path $shaderPacksDirectory ".voxy-managed-shaders.json"
    $previousEntries = @()
    if (Test-Path -LiteralPath $managedManifestPath) {
        $previousEntries = @(Get-Content -LiteralPath $managedManifestPath -Raw | ConvertFrom-Json)
    }

    $projects = @(
        [pscustomobject]@{
            Name = "Complementary Shaders - Reimagined"
            ProjectId = "HVnmMxH1"
            Loader = "iris"
        }
    )
    $newEntries = [System.Collections.Generic.List[object]]::new()
    foreach ($project in $projects) {
        $modrinthFile = Get-ModrinthPrimaryFile -ProjectId $project.ProjectId -MinecraftVersion $MinecraftVersion -Loader $project.Loader
        $cachedFile = Get-CachedModrinthFile -ModrinthFile $modrinthFile
        $fileName = [string](@($modrinthFile.FileName)[0])
        Copy-Item -LiteralPath $cachedFile.FullName -Destination (Join-Path $shaderPacksDirectory $fileName) -Force
        $newEntries.Add([pscustomobject]@{
            project = $project.Name
            project_id = $project.ProjectId
            version_id = $modrinthFile.VersionId
            version_number = $modrinthFile.VersionNumber
            version_type = $modrinthFile.VersionType
            file_name = $fileName
            sha512 = $modrinthFile.Sha512
        })
        Write-Host "  ${MinecraftVersion}: $($project.Name) $($modrinthFile.VersionNumber)" -ForegroundColor DarkGray
        if ($script:UpdateLogPath) {
            Write-UpdateLog "[$MinecraftVersion] $($project.Name): $($modrinthFile.VersionNumber) ($($modrinthFile.FileName))."
        }
    }

    $newFileNames = @($newEntries | ForEach-Object { $_.file_name })
    foreach ($oldEntry in $previousEntries) {
        $oldFileName = [string](@($oldEntry.file_name)[0])
        if ($oldFileName -and $oldFileName -notin $newFileNames) {
            $oldPath = Join-Path $shaderPacksDirectory $oldFileName
            if (Test-Path -LiteralPath $oldPath) {
                Remove-Item -LiteralPath $oldPath -Force
            }
        }
    }
    $newEntries | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $managedManifestPath -Encoding utf8
}

function Write-UpdateLog {
    param(
        [Parameter(Mandatory)][string]$Message,
        [ValidateSet("INFO", "WARN", "ERROR")][string]$Level = "INFO"
    )

    $line = "[{0}] [{1}] {2}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Level, $Message
    Add-Content -LiteralPath $script:UpdateLogPath -Value $line -Encoding utf8
    $color = switch ($Level) {
        "ERROR" { "Red" }
        "WARN" { "Yellow" }
        default { "DarkGray" }
    }
    Write-Host $line -ForegroundColor $color
}

function Get-ProfileGameDirectory {
    param([Parameter(Mandatory)][string]$MinecraftVersion)

    $safeVersion = $MinecraftVersion -replace '[^A-Za-z0-9._-]', '_'
    return (Join-Path $ProfilesDirectory "voxy-test-$safeVersion")
}

function Update-SelectedRuntimeMods {
    param([Parameter(Mandatory)][object[]]$SelectedMatrix)

    Write-UpdateLog "Starting automatic runtime mod update for $(@($SelectedMatrix.TestVersions | Select-Object -Unique).Count) Minecraft version(s)."
    foreach ($entry in $SelectedMatrix) {
        foreach ($minecraftVersion in @($entry.TestVersions)) {
            $gameDirectory = Get-ProfileGameDirectory -MinecraftVersion $minecraftVersion
            $modsDirectory = Join-Path $gameDirectory "mods"
            try {
                Write-UpdateLog "[$minecraftVersion] Target directory: $gameDirectory"
                Write-UpdateLog "[$minecraftVersion] Preparing directories."
                New-Item -ItemType Directory -Path $modsDirectory -Force | Out-Null
                Write-UpdateLog "[$minecraftVersion] Updating runtime mods, including Iris."
                Sync-ModrinthRuntimeMods -MinecraftVersion $minecraftVersion -ModsDirectory $modsDirectory
            }
            catch {
                $message = $_.Exception.Message
                Write-UpdateLog "[$minecraftVersion] Runtime mod update failed: $message" "ERROR"
                $script:UpdateFailures.Add("Runtime mod update for Minecraft ${minecraftVersion}: $message")
            }
            try {
                Write-UpdateLog "[$minecraftVersion] Updating shader packs."
                Sync-ModrinthShaderPacks -MinecraftVersion $minecraftVersion -GameDirectory $gameDirectory
                Write-UpdateLog "[$minecraftVersion] Update completed."
            }
            catch {
                $message = $_.Exception.Message
                Write-UpdateLog "[$minecraftVersion] Shader pack update failed: $message" "ERROR"
                $script:UpdateFailures.Add("Shader pack update for Minecraft ${minecraftVersion}: $message")
            }
        }
    }
    if ($script:UpdateFailures.Count -gt 0) {
        Write-UpdateLog "Runtime update finished with $($script:UpdateFailures.Count) error(s)." "WARN"
    }
    else {
        Write-UpdateLog "Runtime update finished successfully."
    }
}

function Install-FabricVersion {
    param(
        [Parameter(Mandatory)][string]$MinecraftVersion,
        [Parameter(Mandatory)][string]$LoaderVersion,
        [Parameter(Mandatory)][string]$InstallerJar
    )

    $versionId = "fabric-loader-$LoaderVersion-$MinecraftVersion"
    $versionJson = Join-Path $MinecraftDirectory "versions\$versionId\$versionId.json"
    if (Test-Path -LiteralPath $versionJson) {
        return $versionId
    }

    Invoke-Checked -FilePath "java" -ArgumentList @(
        "-jar", $InstallerJar, "client",
        "-dir", $MinecraftDirectory,
        "-mcversion", $MinecraftVersion,
        "-loader", $LoaderVersion,
        "-noprofile"
    ) -WorkingDirectory $RepositoryRoot

    if (-not (Test-Path -LiteralPath $versionJson)) {
        throw "Fabric installer did not create $versionJson"
    }
    return $versionId
}

function Save-LauncherProfiles {
    param(
        [Parameter(Mandatory)][hashtable]$VersionIds,
        [Parameter(Mandatory)][hashtable]$Artifacts
    )

    $launcherProcesses = Get-Process -ErrorAction SilentlyContinue | Where-Object {
        $_.ProcessName -match "(?i)(MinecraftLauncher|Minecraft\.Windows|GameLaunchHelper)"
    }
    if ($launcherProcesses) {
        throw "Close Minecraft Launcher before updating launcher_profiles.json."
    }

    $profilesPath = Join-Path $MinecraftDirectory "launcher_profiles.json"
    if (-not (Test-Path -LiteralPath $profilesPath)) {
        throw "Minecraft Launcher profile file was not found: $profilesPath"
    }

    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    Copy-Item -LiteralPath $profilesPath -Destination "$profilesPath.$timestamp.bak"
    $launcherData = Get-Content -LiteralPath $profilesPath -Raw | ConvertFrom-Json

    foreach ($entry in $matrix) {
        foreach ($minecraftVersion in $entry.TestVersions) {
            $safeVersion = $minecraftVersion -replace "[^A-Za-z0-9._-]", "_"
            $profileId = "voxy-test-$safeVersion"
            $gameDirectory = Get-ProfileGameDirectory -MinecraftVersion $minecraftVersion
            $modsDirectory = Join-Path $gameDirectory "mods"
            New-Item -ItemType Directory -Path $modsDirectory -Force | Out-Null

            $artifactKey = if ($entry.PSObject.Properties.Name -contains "ArtifactKey") { $entry.ArtifactKey } else { $entry.Branch }
            if ($Artifacts.ContainsKey($artifactKey)) {
                Get-ChildItem -LiteralPath $modsDirectory -Filter "*.jar" -File -ErrorAction SilentlyContinue |
                    Where-Object { (Get-FabricModIdFromJar -JarPath $_.FullName) -eq "voxy" } |
                    Remove-Item -Force
                $artifactItem = @($Artifacts[$artifactKey])[0]
                $destinationJar = Join-Path $modsDirectory ([string]$artifactItem.Name)
                Copy-Item -LiteralPath ([string]$artifactItem.FullName) -Destination $destinationJar -Force
            }

            if (-not $SkipRuntimeMods) {
                try {
                    $voxyArtifactPath = if ($Artifacts.ContainsKey($artifactKey)) { [string](@($Artifacts[$artifactKey])[0].FullName) } else { $null }
                    Sync-ModrinthRuntimeMods -MinecraftVersion $minecraftVersion -ModsDirectory $modsDirectory -VoxyArtifact $voxyArtifactPath
                }
                catch {
                    $failures.Add("Runtime mods for Minecraft ${minecraftVersion}: $($_.Exception.Message)")
                }
                try {
                    Sync-ModrinthShaderPacks -MinecraftVersion $minecraftVersion -GameDirectory $gameDirectory
                }
                catch {
                    $failures.Add("Shader packs for Minecraft ${minecraftVersion}: $($_.Exception.Message)")
                }
            }

            $profile = [pscustomobject]@{
                created = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
                gameDir = $gameDirectory
                icon = "Grass"
                lastVersionId = $VersionIds[$minecraftVersion]
                name = "Voxy Test $minecraftVersion"
                type = "custom"
            }
            if ($profileId -notin $launcherData.profiles.PSObject.Properties.Name) {
                $launcherData.profiles | Add-Member -NotePropertyName $profileId -NotePropertyValue $profile
            }
            else {
                $launcherData.profiles.PSObject.Properties[$profileId].Value = $profile
            }
        }
    }

    $temporaryPath = "$profilesPath.codex-new"
    $profileJson = $launcherData | ConvertTo-Json -Depth 20
    [System.IO.File]::WriteAllText(
        $temporaryPath,
        $profileJson,
        [System.Text.UTF8Encoding]::new($false)
    )
    [void](Get-Content -LiteralPath $temporaryPath -Raw | ConvertFrom-Json)
    Move-Item -LiteralPath $temporaryPath -Destination $profilesPath -Force
}

$RepositoryRoot = (Resolve-Path -LiteralPath $RepositoryRoot).Path
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$OutputDirectory = (Resolve-Path -LiteralPath $OutputDirectory).Path
$updateLogDirectory = Join-Path $OutputDirectory "update-logs"
New-Item -ItemType Directory -Path $updateLogDirectory -Force | Out-Null
$script:UpdateLogPath = Join-Path $updateLogDirectory ("runtime-update-{0}.log" -f (Get-Date -Format "yyyyMMdd-HHmmss"))
$script:UpdateFailures = [System.Collections.Generic.List[string]]::new()
$sessionId = [guid]::NewGuid().ToString("N").Substring(0, 8)
$worktreeRoot = Join-Path $WorktreeBaseDirectory $sessionId
New-Item -ItemType Directory -Path $worktreeRoot -Force | Out-Null
$worktreeRoot = (Resolve-Path -LiteralPath $worktreeRoot).Path

$artifacts = @{}
$failures = [System.Collections.Generic.List[string]]::new()
$resolvedJavaHomes = @{}

try {
    if (-not $SkipRuntimeMods) {
        Update-SelectedRuntimeMods -SelectedMatrix $matrix
        foreach ($updateFailure in $script:UpdateFailures) {
            $failures.Add($updateFailure)
        }
    }

    foreach ($entry in $matrix) {
        Write-Host "`n=== Building $($entry.Branch) ===" -ForegroundColor Cyan
        $branchExists = & git -C $RepositoryRoot show-ref --verify --quiet "refs/heads/$($entry.Branch)"
        if ($LASTEXITCODE -ne 0) {
            $failures.Add("$($entry.Branch): local branch does not exist")
            if (-not $ContinueOnBuildFailure) { throw $failures[-1] }
            continue
        }

        $artifactKey = if ($entry.PSObject.Properties.Name -contains "ArtifactKey") { $entry.ArtifactKey } else { $entry.Branch }
        $safeBranch = $artifactKey -replace "[^A-Za-z0-9._-]", "_"
        if ($ReuseExistingArtifacts -or $ProfilesOnly) {
            $branchCommit = (& git -C $RepositoryRoot rev-parse --short=8 $entry.Branch).Trim()
            $existingArtifacts = @(Get-ChildItem -LiteralPath $OutputDirectory -Filter "voxy-compat-$safeBranch-*-$branchCommit.jar" -File -ErrorAction SilentlyContinue)
            $existingArtifacts = @($existingArtifacts | Where-Object { (Get-FabricModIdFromJar -JarPath $_.FullName) -eq "voxy" })
            if ($existingArtifacts.Count -eq 1) {
                Write-Host "Reusing $($existingArtifacts[0].Name)" -ForegroundColor DarkGray
                $artifacts[$artifactKey] = $existingArtifacts[0]
                continue
            }
            if ($ProfilesOnly) {
                $failures.Add("$($entry.Branch): no matching artifact exists for commit $branchCommit")
                continue
            }
        }

        $worktree = Join-Path $worktreeRoot $safeBranch
        if (Test-Path -LiteralPath $worktree) {
            Remove-CompatibilityWorktree -Path $worktree
        }
        Invoke-Checked -FilePath "git" -ArgumentList @("-c", "core.longpaths=true", "worktree", "add", "--detach", $worktree, $entry.Branch) -WorkingDirectory $RepositoryRoot

        try {
            $sourceVersion = Get-PropertyValue -Path (Join-Path $worktree "gradle.properties") -Name "minecraft_version"
            if ($sourceVersion -ne $entry.ExpectedSourceVersion) {
                throw "Branch $($entry.Branch) targets Minecraft $sourceVersion, expected $($entry.ExpectedSourceVersion). Complete the port before distributing this build."
            }
            $buildVersion = if ($entry.PSObject.Properties.Name -contains "BuildVersion") { $entry.BuildVersion } else { $sourceVersion }
            $sourceModId = (Get-Content -LiteralPath (Join-Path $worktree "src\main\resources\fabric.mod.json") -Raw | ConvertFrom-Json).id
            if ($sourceModId -ne "voxy") {
                throw "Branch $($entry.Branch) contains mod id '$sourceModId', not 'voxy'. Complete the Voxy port before distributing this build."
            }

            $gradle = Join-Path $worktree "gradlew.bat"
            if ($entry.PSObject.Properties.Name -contains "JavaVersion") {
                $requiredJava = [int]$entry.JavaVersion
            }
            else {
                $javaTargetMatch = Select-String -LiteralPath (Join-Path $worktree "build.gradle") -Pattern 'targetJavaVersion\s*=\s*(?<version>\d+)' | Select-Object -First 1
                if (-not $javaTargetMatch) {
                    throw "Could not determine targetJavaVersion for $($entry.Branch)."
                }
                $requiredJava = [int]$javaTargetMatch.Matches[0].Groups["version"].Value
            }
            if (-not $resolvedJavaHomes.ContainsKey($requiredJava)) {
                $configuredHome = switch ($requiredJava) {
                    17 { $Java17Home }
                    21 { $Java21Home }
                    25 { $Java25Home }
                }
                $resolvedJavaHomes[$requiredJava] = Get-JavaHome -MajorVersion $requiredJava -ConfiguredHome $configuredHome
            }

            $previousJavaHome = $env:JAVA_HOME
            $previousPath = $env:PATH
            try {
                $env:JAVA_HOME = $resolvedJavaHomes[$requiredJava]
                $env:PATH = "$(Join-Path $env:JAVA_HOME 'bin');$previousPath"
                Write-Host "Using JDK $requiredJava from $env:JAVA_HOME" -ForegroundColor DarkGray
                $versionArgument = if ($entry.PSObject.Properties.Name -contains "BuildVersion") { @("-Pminecraft_version=$buildVersion") } else { @() }
                if ($entry.PreBuildTasks.Count -gt 0) {
                    Write-Host "Preparing generated include JARs for $($entry.Branch)..." -ForegroundColor DarkGray
                    Invoke-Checked -FilePath $gradle -ArgumentList (@("--no-daemon") + $versionArgument + $entry.PreBuildTasks) -WorkingDirectory $worktree
                    Invoke-Checked -FilePath $gradle -ArgumentList (@("--no-daemon") + $versionArgument + @("build")) -WorkingDirectory $worktree
                }
                else {
                    Invoke-Checked -FilePath $gradle -ArgumentList (@("--no-daemon") + $versionArgument + @("clean", "build")) -WorkingDirectory $worktree
                }
            }
            finally {
                $env:JAVA_HOME = $previousJavaHome
                $env:PATH = $previousPath
            }
            $jar = Get-BuildJar -Worktree $worktree
            $builtModId = Get-FabricModIdFromJar -JarPath $jar.FullName
            if ($builtModId -ne "voxy") {
                throw "Built artifact '$($jar.Name)' contains mod id '$builtModId', not 'voxy'."
            }
            $commit = (& git -C $worktree rev-parse --short=8 HEAD).Trim()
            $artifactName = "voxy-compat-$safeBranch-mc$buildVersion-$commit.jar"
            $artifactPath = Join-Path $OutputDirectory $artifactName
            Copy-Item -LiteralPath $jar.FullName -Destination $artifactPath -Force
            $artifacts[$artifactKey] = Get-Item -LiteralPath $artifactPath
        }
        catch {
            $failures.Add("$($entry.Branch): $($_.Exception.Message)")
            if (-not $ContinueOnBuildFailure) { throw }
        }
        finally {
            if (-not $KeepWorktrees -and (Test-Path -LiteralPath $worktree)) {
                Remove-CompatibilityWorktree -Path $worktree
            }
        }
    }

    if (-not $SkipProfileCreation) {
        $installerJar = Join-Path $OutputDirectory "fabric-installer-$FabricInstallerVersion.jar"
        if (-not $SkipFabricInstall -and -not (Test-Path -LiteralPath $installerJar)) {
            $installerUri = "https://maven.fabricmc.net/net/fabricmc/fabric-installer/$FabricInstallerVersion/fabric-installer-$FabricInstallerVersion.jar"
            Invoke-WebRequest -Uri $installerUri -OutFile $installerJar
        }

        $versionIds = @{}
        foreach ($minecraftVersion in ($matrix.TestVersions | Sort-Object -Unique)) {
            $loaderVersion = Get-FabricLoaderVersion -MinecraftVersion $minecraftVersion
            $versionId = "fabric-loader-$loaderVersion-$minecraftVersion"
            if (-not $SkipFabricInstall) {
                $versionId = Install-FabricVersion -MinecraftVersion $minecraftVersion -LoaderVersion $loaderVersion -InstallerJar $installerJar
            }
            $versionIds[$minecraftVersion] = $versionId
        }
        Save-LauncherProfiles -VersionIds $versionIds -Artifacts $artifacts
    }

}
finally {
    if (-not $KeepWorktrees) {
        & git -C $RepositoryRoot worktree prune
        if ((Test-Path -LiteralPath $worktreeRoot) -and -not (Get-ChildItem -LiteralPath $worktreeRoot -Force)) {
            Remove-Item -LiteralPath $worktreeRoot -Force
        }
    }
}

Write-Host "`nArtifacts: $OutputDirectory" -ForegroundColor Green
Write-Host "Test profiles: $ProfilesDirectory" -ForegroundColor Green
Write-Host "Update log: $script:UpdateLogPath" -ForegroundColor Green
if ($failures.Count -gt 0) {
    Write-Warning ("Incomplete branches/builds:`n - " + ($failures -join "`n - "))
    exit 1
}

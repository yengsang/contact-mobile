[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Slug,

    [Parameter(Mandatory = $true)]
    [string]$AppName,

    [Parameter(Mandatory = $true)]
    [string]$ApplicationId,

    [string]$BrandName,
    [string]$ApiKeyEnv,
    [string]$DeepLinkScheme,
    [string]$LogoText,
    [string]$PrimaryColor = "#4D2C91",
    [string]$OnPrimaryColor,
    [string]$SurfaceColor,
    [string]$AccentColor,
    [ValidateSet("circle", "card")]
    [string]$LogoStyle = "card",
    [string]$AppSubtitle,
    [string]$InAppLogoPath,
    [string]$LauncherIconPath,
    [switch]$Force
)

$ErrorActionPreference = "Stop"

function Normalize-HexColor {
    param([string]$Value)

    $trimmed = if ($null -eq $Value) { "" } else { $Value.Trim() }
    if (-not $trimmed) {
        throw "Color cannot be empty."
    }

    if (-not $trimmed.StartsWith("#")) {
        $trimmed = "#$trimmed"
    }

    if ($trimmed -notmatch '^#[0-9A-Fa-f]{6}$') {
        throw "Invalid color '$Value'. Use #RRGGBB."
    }

    return $trimmed.ToUpperInvariant()
}

function Normalize-OptionalHexColor {
    param([string]$Value)

    if ($null -eq $Value) {
        return ""
    }

    $trimmed = $Value.Trim()
    if (-not $trimmed) {
        return ""
    }

    return Normalize-HexColor $trimmed
}

function Convert-ToEnvName {
    param([string]$Value)

    $rawValue = if ($null -eq $Value) { "" } else { [string]$Value }
    $sanitized = [Regex]::Replace($rawValue.Trim(), '[^A-Za-z0-9]+', '_').Trim('_')
    if (-not $sanitized) {
        throw "Cannot derive an environment variable name from '$Value'."
    }

    return $sanitized.ToUpperInvariant()
}

function Normalize-ApplicationId {
    param([string]$Value)

    $trimmed = if ($null -eq $Value) { "" } else { $Value.Trim() }
    if (-not $trimmed) {
        throw "ApplicationId cannot be empty."
    }

    $segments = $trimmed -split '\.'
    if ($segments.Count -lt 2) {
        throw "ApplicationId '$Value' is invalid. Use a full Android package name like com.memberreward.contact.reward89rich3."
    }

    foreach ($segment in $segments) {
        if (-not $segment) {
            throw "ApplicationId '$Value' is invalid. It cannot contain empty package segments."
        }

        if ($segment -notmatch '^[a-z][a-z0-9_]*$') {
            throw "ApplicationId '$Value' is invalid. Each package segment must start with a lowercase letter and contain only lowercase letters, numbers, or underscores."
        }
    }

    return ($segments -join '.')
}

function Convert-HexToRgb {
    param([string]$Value)

    $hex = Normalize-HexColor $Value
    return [PSCustomObject]@{
        R = [Convert]::ToInt32($hex.Substring(1, 2), 16)
        G = [Convert]::ToInt32($hex.Substring(3, 2), 16)
        B = [Convert]::ToInt32($hex.Substring(5, 2), 16)
    }
}

function Darken-Color {
    param(
        [string]$Value,
        [double]$Factor = 0.68
    )

    $rgb = Convert-HexToRgb $Value
    $r = [Math]::Max(0, [Math]::Min(255, [int][Math]::Round($rgb.R * $Factor)))
    $g = [Math]::Max(0, [Math]::Min(255, [int][Math]::Round($rgb.G * $Factor)))
    $b = [Math]::Max(0, [Math]::Min(255, [int][Math]::Round($rgb.B * $Factor)))
    return ('#{0:X2}{1:X2}{2:X2}' -f $r, $g, $b)
}

function Lighten-Color {
    param(
        [string]$Value,
        [double]$Factor
    )

    $rgb = Convert-HexToRgb $Value
    $r = [Math]::Max(0, [Math]::Min(255, [int][Math]::Round($rgb.R + ((255 - $rgb.R) * $Factor))))
    $g = [Math]::Max(0, [Math]::Min(255, [int][Math]::Round($rgb.G + ((255 - $rgb.G) * $Factor))))
    $b = [Math]::Max(0, [Math]::Min(255, [int][Math]::Round($rgb.B + ((255 - $rgb.B) * $Factor))))
    return ('#{0:X2}{1:X2}{2:X2}' -f $r, $g, $b)
}

function Get-ContrastTextColor {
    param([string]$BackgroundColor)

    $rgb = Convert-HexToRgb $BackgroundColor
    $luminance = ((0.299 * $rgb.R) + (0.587 * $rgb.G) + (0.114 * $rgb.B)) / 255
    if ($luminance -gt 0.62) {
        return "#111111"
    }

    return "#FFFFFF"
}

function Ensure-Directory {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Path $Path | Out-Null
    }
}

function Remove-ResourceVariants {
    param(
        [string]$DirectoryPath,
        [string]$BaseName
    )

    if (-not (Test-Path -LiteralPath $DirectoryPath)) {
        return
    }

    Get-ChildItem -LiteralPath $DirectoryPath -Filter "$BaseName.*" -File | Remove-Item -Force
}

function Copy-OrTemplateAsset {
    param(
        [string]$SourcePath,
        [string]$FallbackTemplatePath,
        [string]$DestinationPath,
        [hashtable]$Tokens
    )

    $destinationDirectory = Split-Path -Parent $DestinationPath
    $destinationBaseName = [System.IO.Path]::GetFileNameWithoutExtension($DestinationPath)
    Remove-ResourceVariants -DirectoryPath $destinationDirectory -BaseName $destinationBaseName

    if ($SourcePath) {
        $extension = [System.IO.Path]::GetExtension($SourcePath)
        if (-not $extension) {
            throw "Asset '$SourcePath' must have a file extension such as .png, .webp, .jpg, or .xml."
        }

        $allowedExtensions = @(".png", ".webp", ".jpg", ".jpeg", ".xml")
        if ($allowedExtensions -notcontains $extension.ToLowerInvariant()) {
            throw "Unsupported asset extension '$extension' for '$SourcePath'."
        }

        $copyPath = Join-Path $destinationDirectory ($destinationBaseName + $extension.ToLowerInvariant())
        Copy-Item -LiteralPath $SourcePath -Destination $copyPath -Force
        return
    }

    $content = Get-Content -LiteralPath $FallbackTemplatePath -Raw
    foreach ($entry in $Tokens.GetEnumerator()) {
        $content = $content.Replace($entry.Key, $entry.Value)
    }
    Set-Content -LiteralPath $DestinationPath -Value $content -NoNewline
}

function Read-TenantsConfig {
    param([string]$Path)

    try {
        $parsed = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    } catch {
        throw "The tenants config at '$Path' is not valid JSON. Repair tenants.json or restore it from git, then rerun add-tenant.ps1. Original error: $($_.Exception.Message)"
    }

    if ($null -eq $parsed) {
        return @()
    }

    return @($parsed)
}

function Write-TenantsConfig {
    param(
        [string]$Path,
        [object[]]$Tenants
    )

    $tempPath = "$Path.tmp"
    $json = $Tenants | ConvertTo-Json -Depth 5
    Set-Content -LiteralPath $tempPath -Value $json -Encoding utf8
    Move-Item -LiteralPath $tempPath -Destination $Path -Force
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$templatesRoot = Join-Path $PSScriptRoot "templates\tenant"
$tenantsJsonPath = Join-Path $projectRoot "tenants.json"
$appSrcRoot = Join-Path $projectRoot "app\src"

$normalizedSlug = ($Slug.Trim()).ToLowerInvariant()
if ($normalizedSlug -notmatch '^[a-z][a-z0-9]*$') {
    throw "Slug must start with a letter and contain only lowercase letters and numbers."
}

$BrandName = if ($BrandName) { $BrandName.Trim() } else { $AppName.Trim() }
$ApplicationId = Normalize-ApplicationId $ApplicationId
$ApiKeyEnv = if ($ApiKeyEnv) { $ApiKeyEnv.Trim() } else { "APP_API_KEY_$(Convert-ToEnvName $normalizedSlug)" }
$DeepLinkScheme = if ($DeepLinkScheme) { $DeepLinkScheme.Trim().ToLowerInvariant() } else { $normalizedSlug }
if ($DeepLinkScheme -notmatch '^[a-z][a-z0-9+.-]*$') {
    throw "DeepLinkScheme must start with a letter and contain only lowercase letters, numbers, '+', '.', or '-'."
}
$LogoText = if ($LogoText) { $LogoText.Trim() } else { (($AppName -split '\s+' | Where-Object { $_ }) | ForEach-Object { $_.Substring(0,1) } | Select-Object -First 2) -join '' }
$AppSubtitle = if ($AppSubtitle) { $AppSubtitle.Trim() } else { "$AppName tenant build." }

$PrimaryColor = Normalize-HexColor $PrimaryColor
$OnPrimaryColor = Normalize-OptionalHexColor $OnPrimaryColor
$SurfaceColor = Normalize-OptionalHexColor $SurfaceColor
$AccentColor = Normalize-OptionalHexColor $AccentColor

if (-not $OnPrimaryColor) {
    $OnPrimaryColor = Get-ContrastTextColor $PrimaryColor
}
if (-not $SurfaceColor) {
    $SurfaceColor = Lighten-Color $PrimaryColor 0.92
}
if (-not $AccentColor) {
    $AccentColor = Lighten-Color $PrimaryColor 0.58
}

$PrimaryColorDark = Darken-Color $PrimaryColor
$effectiveLauncherIconPath = if ($LauncherIconPath) { $LauncherIconPath } elseif ($InAppLogoPath) { $InAppLogoPath } else { "" }

if (-not (Test-Path -LiteralPath $tenantsJsonPath)) {
    throw "Cannot find tenants.json at $tenantsJsonPath"
}

$tenants = Read-TenantsConfig -Path $tenantsJsonPath

$existing = $tenants | Where-Object { $_.slug -eq $normalizedSlug }
if ($existing -and -not $Force) {
    throw "Tenant '$normalizedSlug' already exists in tenants.json. Use -Force to overwrite its generated files and config."
}

$tenantEntry = [ordered]@{
    slug = $normalizedSlug
    appName = $AppName.Trim()
    applicationId = $ApplicationId.Trim()
    brandName = $BrandName
    apiKeyEnv = $ApiKeyEnv
    deepLinkScheme = $DeepLinkScheme
    logoText = $LogoText
    primaryColor = $PrimaryColor
    onPrimaryColor = $OnPrimaryColor
    surfaceColor = $SurfaceColor
    accentColor = $AccentColor
    logoStyle = $LogoStyle
}

if ($existing) {
    $tenants = @($tenants | Where-Object { $_.slug -ne $normalizedSlug })
}

$tenants = @($tenants) + ([pscustomobject]$tenantEntry)
$tenants = $tenants | Sort-Object slug
Write-TenantsConfig -Path $tenantsJsonPath -Tenants $tenants

$flavorRoot = Join-Path $appSrcRoot $normalizedSlug
$resValuesDir = Join-Path $flavorRoot "res\values"
$resDrawableDir = Join-Path $flavorRoot "res\drawable"
Ensure-Directory $resValuesDir
Ensure-Directory $resDrawableDir

$tokens = @{
    "__APP_NAME__" = $AppName.Trim()
    "__APP_SUBTITLE__" = $AppSubtitle
    "__PRIMARY_COLOR__" = $PrimaryColor
    "__ON_PRIMARY_COLOR__" = $OnPrimaryColor
    "__SURFACE_COLOR__" = $SurfaceColor
    "__ACCENT_COLOR__" = $AccentColor
    "__PRIMARY_COLOR_DARK__" = $PrimaryColorDark
}

$templateFiles = @(
    @{ Template = "AndroidManifest.xml.template"; Destination = Join-Path $flavorRoot "AndroidManifest.xml" }
    @{ Template = "colors.xml.template"; Destination = Join-Path $resValuesDir "colors.xml" }
    @{ Template = "strings.xml.template"; Destination = Join-Path $resValuesDir "strings.xml" }
)

foreach ($file in $templateFiles) {
    $content = Get-Content -LiteralPath (Join-Path $templatesRoot $file.Template) -Raw
    foreach ($entry in $tokens.GetEnumerator()) {
        $content = $content.Replace($entry.Key, $entry.Value)
    }
    Set-Content -LiteralPath $file.Destination -Value $content -NoNewline
}

$brandTemplate = if ($LogoStyle -eq "circle") { "tenant_brand_mark_circle.xml.template" } else { "tenant_brand_mark_card.xml.template" }

Copy-OrTemplateAsset -SourcePath $InAppLogoPath -FallbackTemplatePath (Join-Path $templatesRoot $brandTemplate) -DestinationPath (Join-Path $resDrawableDir "tenant_brand_mark.xml") -Tokens $tokens
Copy-OrTemplateAsset -SourcePath $effectiveLauncherIconPath -FallbackTemplatePath (Join-Path $templatesRoot "ic_launcher_brand.xml.template") -DestinationPath (Join-Path $resDrawableDir "ic_launcher_brand.xml") -Tokens $tokens

Write-Host ""
Write-Host "Tenant '$normalizedSlug' scaffolded successfully."
Write-Host "Flavor folder: $flavorRoot"
Write-Host "Resolved colors:"
Write-Host "  Primary: $PrimaryColor"
Write-Host "  Surface: $SurfaceColor"
Write-Host "  Accent: $AccentColor"
Write-Host "  On Primary: $OnPrimaryColor"
Write-Host "Gradle property to set:"
Write-Host "  $ApiKeyEnv=<tenant_api_key_here>"
Write-Host ""
Write-Host "Next steps:"
Write-Host "  1. Put $ApiKeyEnv into your global gradle.properties"
Write-Host "  2. Sync Gradle in Android Studio"
Write-Host "  3. Build variant ${normalizedSlug}Debug or ${normalizedSlug}Release"

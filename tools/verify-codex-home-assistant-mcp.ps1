[CmdletBinding()]
param(
    [ValidatePattern('^[a-z0-9][a-z0-9-]*$')]
    [string]$ServerName = "home-assistant",
    [string]$CodexPath
)

$ErrorActionPreference = "Stop"

function Test-CodexCli {
    param([Parameter(Mandatory = $true)][string]$Candidate)

    if (-not (Test-Path -LiteralPath $Candidate -PathType Leaf)) {
        return $false
    }

    try {
        $version = & $Candidate --version 2>$null
        return $LASTEXITCODE -eq 0 -and $version -match '^codex'
    } catch {
        return $false
    }
}

function Resolve-CodexCli {
    param([string]$RequestedPath)

    if (-not [string]::IsNullOrWhiteSpace($RequestedPath)) {
        if (Test-CodexCli -Candidate $RequestedPath) {
            return (Resolve-Path -LiteralPath $RequestedPath).Path
        }
        throw "The supplied Codex executable could not be run: $RequestedPath"
    }

    $candidates = [System.Collections.Generic.List[string]]::new()

    if (-not [string]::IsNullOrWhiteSpace($env:CODEX_CLI_PATH)) {
        $candidates.Add($env:CODEX_CLI_PATH)
    }

    $command = Get-Command codex -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -ne $command) {
        $candidates.Add($command.Source)
    }

    $desktopBinRoot = Join-Path $env:LOCALAPPDATA "OpenAI\Codex\bin"
    if (Test-Path -LiteralPath $desktopBinRoot -PathType Container) {
        Get-ChildItem -LiteralPath $desktopBinRoot -Directory -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending |
            ForEach-Object {
                $candidate = Join-Path $_.FullName "codex.exe"
                if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                    $candidates.Add($candidate)
                }
            }
    }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        if (Test-CodexCli -Candidate $candidate) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    throw "Codex CLI was not found. Install the standalone CLI or pass -CodexPath."
}

$codex = Resolve-CodexCli -RequestedPath $CodexPath
$servers = & $codex mcp list 2>&1
$listExitCode = $LASTEXITCODE
if ($listExitCode -ne 0) {
    throw "Codex could not list MCP servers."
}

$serverPattern = "^{0}\s" -f [regex]::Escape($ServerName)
if (-not ($servers | Where-Object { $_ -match $serverPattern })) {
    throw "The '$ServerName' MCP server is not registered. Add it and complete Codex OAuth login first."
}

$prompt = @"
Verify the configured Home Assistant MCP server $ServerName. You may call exactly one tool from that server only if it is clearly non-mutating/read-only (such as a status, configuration, or entity query). Do not call any service, action, automation, script, or state-changing tool. If a safe MCP tool call succeeds, answer exactly: HA_MCP_READ_OK. Otherwise answer exactly: HA_MCP_READ_FAILED.
"@

$result = & $codex exec --ephemeral --sandbox read-only --color never $prompt 2>&1
$verificationExitCode = $LASTEXITCODE
$result | Where-Object {
    $_ -match ("mcp: {0}/.+\(completed\)" -f [regex]::Escape($ServerName)) -or
    $_ -match 'HA_MCP_READ_(OK|FAILED)'
}

if ($verificationExitCode -ne 0 -or -not ($result | Select-String -SimpleMatch 'HA_MCP_READ_OK')) {
    throw "The '$ServerName' connector did not complete a read-only Home Assistant MCP call. Run 'codex mcp login $ServerName' and retry."
}

Write-Host "Home Assistant MCP verification passed for '$ServerName'."

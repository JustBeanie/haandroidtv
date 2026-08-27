param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$BackupDirectory = (Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'HAQuickAccess-Release-Backup'),
    [string]$KeytoolPath = 'keytool.exe'
)

$ErrorActionPreference = 'Stop'

$keystorePath = Join-Path $ProjectRoot 'release-upload.jks'
$propertiesPath = Join-Path $ProjectRoot 'keystore.properties'
$backupKeystorePath = Join-Path $BackupDirectory 'release-upload.jks'
$backupSecretsPath = Join-Path $BackupDirectory 'signing-secrets.dpapi.txt'
$backupReadmePath = Join-Path $BackupDirectory 'README.txt'
$keyAlias = 'ha-quick-access-upload'

foreach ($path in @($keystorePath, $propertiesPath, $backupKeystorePath, $backupSecretsPath)) {
    if (Test-Path -LiteralPath $path) {
        throw "Refusing to overwrite existing signing material: $path"
    }
}

$randomBytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $rng.GetBytes($randomBytes)
} finally {
    $rng.Dispose()
}
$password = [Convert]::ToBase64String($randomBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$passwordFile = [System.IO.Path]::GetTempFileName()

try {
    [System.IO.File]::WriteAllText($passwordFile, $password, [System.Text.UTF8Encoding]::new($false))
    & $KeytoolPath -genkeypair `
        -keystore $keystorePath `
        -storetype JKS `
        -storepass:file $passwordFile `
        -keypass:file $passwordFile `
        -alias $keyAlias `
        -keyalg RSA `
        -keysize 4096 `
        -sigalg SHA256withRSA `
        -validity 10000 `
        -dname 'CN=HA Quick Access Upload, O=Independent Developer, C=US'

    if ($LASTEXITCODE -ne 0) {
        throw "keytool failed with exit code $LASTEXITCODE"
    }

    $properties = @(
        'storeFile=release-upload.jks'
        "storePassword=$password"
        "keyAlias=$keyAlias"
        "keyPassword=$password"
    ) -join [Environment]::NewLine
    [System.IO.File]::WriteAllText($propertiesPath, "$properties$([Environment]::NewLine)", [System.Text.UTF8Encoding]::new($false))

    New-Item -ItemType Directory -Path $BackupDirectory -Force | Out-Null
    Copy-Item -LiteralPath $keystorePath -Destination $backupKeystorePath

    $securePassword = ConvertTo-SecureString $password -AsPlainText -Force
    $encryptedPassword = ConvertFrom-SecureString $securePassword
    $encryptedRecovery = @(
        "keyAlias=$keyAlias"
        'credentialProtection=Windows DPAPI (current Windows user on this computer)'
        "storePasswordDpapi=$encryptedPassword"
        "keyPasswordDpapi=$encryptedPassword"
    ) -join [Environment]::NewLine
    [System.IO.File]::WriteAllText($backupSecretsPath, "$encryptedRecovery$([Environment]::NewLine)", [System.Text.UTF8Encoding]::new($false))

    $recoveryInstructions = @'
HA Quick Access Google Play upload-key backup

Files:
- release-upload.jks: password-protected upload keystore
- signing-secrets.dpapi.txt: credentials encrypted for the current Windows user on this computer

Keep another encrypted copy of this folder in a trusted password manager or offline backup.
Never commit either file to Git or upload them to a public issue, chat, or repository.
'@
    [System.IO.File]::WriteAllText($backupReadmePath, $recoveryInstructions, [System.Text.UTF8Encoding]::new($false))
} finally {
    if (Test-Path -LiteralPath $passwordFile) {
        Remove-Item -LiteralPath $passwordFile -Force
    }
    [Array]::Clear($randomBytes, 0, $randomBytes.Length)
    $password = $null
}

Write-Output "Created Git-ignored signing material in $ProjectRoot"
Write-Output "Created protected local backup in $BackupDirectory"

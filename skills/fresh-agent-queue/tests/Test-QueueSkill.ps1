[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$skillRoot = Split-Path -Parent $PSScriptRoot
$queueScript = Join-Path $skillRoot 'scripts/queue-ledger.ps1'
$scenarioFile = Join-Path $skillRoot 'references/acceptance-scenarios.json'
$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('fresh-agent-queue-' + [guid]::NewGuid().ToString('N'))
$script:passed = 0

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "ASSERTION FAILED: $Message" }
}

function Assert-Equal {
    param($Expected, $Actual, [string]$Message)
    if ($Expected -ne $Actual) { throw "ASSERTION FAILED: $Message. Expected '$Expected', got '$Actual'." }
}

function Assert-Throws {
    param([scriptblock]$Action, [string]$Pattern, [string]$Message)
    $thrown = $false
    try { & $Action | Out-Null } catch {
        $thrown = $true
        if ($_.Exception.Message -notlike "*$Pattern*") {
            throw "ASSERTION FAILED: $Message. Wrong error: $($_.Exception.Message)"
        }
    }
    if (-not $thrown) { throw "ASSERTION FAILED: $Message. No error was thrown." }
}

function New-QueueItem {
    param(
        [string]$Id,
        [string[]]$DependsOn = @(),
        [int]$MaxAttempts = 2,
        [string]$Kind = 'work',
        [string]$Validates = '',
        [string]$RepairsValidation = '',
        [string[]]$WriteScope = @(),
        [bool]$RequiresApproval = $false,
        [string]$ApprovalKey = ''
    )
    return [ordered]@{
        id = $Id
        kind = $Kind
        validates = $Validates
        repairs_validation = $RepairsValidation
        objective = "Objective for $Id"
        depends_on = @($DependsOn)
        status = 'queued'
        attempts = 0
        max_attempts = $MaxAttempts
        agent_history = @()
        dispatch_history = @()
        active_agent = $null
        evidence = $null
        block_reason = $null
        write_scope = @($WriteScope)
        requires_approval = $RequiresApproval
        approval_key = $ApprovalKey
    }
}

function New-Ledger {
    param([string]$Name, [object[]]$Items, [int]$Concurrency = 2)
    $path = Join-Path $testRoot "$Name.json"
    [ordered]@{ version = 2; concurrency_limit = $Concurrency; approvals = @(); items = @($Items) } |
        ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $path -Encoding utf8
    return $path
}

function Read-State { param([string]$Path) Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json }

function Invoke-Queue {
    param([hashtable]$Arguments)
    & $queueScript @Arguments | Out-Null
}

function Run-Test {
    param([string]$Name, [scriptblock]$Body)
    & $Body
    $script:passed++
    Write-Host "PASS $Name"
}

New-Item -ItemType Directory -Path $testRoot | Out-Null
try {
    Run-Test 'dependency gating' {
        $path = New-Ledger 'dependency' @(
            (New-QueueItem 'inspect'),
            (New-QueueItem 'verify-inspect' -DependsOn @('inspect') -Kind 'validation' -Validates 'inspect'),
            (New-QueueItem 'build' -DependsOn @('verify-inspect'))
        )
        Assert-Throws { Invoke-Queue @{ Command='claim'; Ledger=$path; Item='build'; Agent='agent-build' } } 'has not passed' 'Dependent work started early'
        Invoke-Queue @{ Command='claim'; Ledger=$path; Item='inspect'; Agent='agent-inspect' }
        Invoke-Queue @{ Command='pass'; Ledger=$path; Item='inspect'; Agent='agent-inspect'; Evidence='inspection.txt sha256:abc' }
        Assert-Throws { Invoke-Queue @{ Command='claim'; Ledger=$path; Item='build'; Agent='agent-build' } } 'has not passed' 'Dependent work started before independent verification'
        Invoke-Queue @{ Command='claim'; Ledger=$path; Item='verify-inspect'; Agent='agent-inspect-validator' }
        Invoke-Queue @{ Command='pass'; Ledger=$path; Item='verify-inspect'; Agent='agent-inspect-validator'; Evidence='inspection independently verified' }
        Invoke-Queue @{ Command='claim'; Ledger=$path; Item='build'; Agent='agent-build' }
        $state = Read-State $path
        Assert-Equal 'in_progress' $state.items[2].status 'Dependent work did not start after verification'
    }

    Run-Test 'dependency cycles are rejected' {
        $path = New-Ledger 'cycle' @(
            (New-QueueItem 'left' -DependsOn @('right')),
            (New-QueueItem 'right' -DependsOn @('left'))
        )
        Assert-Throws { Invoke-Queue @{ Command='validate'; Ledger=$path } } 'Dependency cycle' 'Cyclic queue was accepted'
    }

    Run-Test 'concurrency cap' {
        $path = New-Ledger 'capacity' @((New-QueueItem 'one'), (New-QueueItem 'two')) -Concurrency 1
        Invoke-Queue @{ Command='claim'; Ledger=$path; Item='one'; Agent='agent-one' }
        Assert-Throws { Invoke-Queue @{ Command='claim'; Ledger=$path; Item='two'; Agent='agent-two' } } 'No queue capacity' 'Concurrency limit was exceeded'
        Invoke-Queue @{ Command='pass'; Ledger=$path; Item='one'; Agent='agent-one'; Evidence='test one passed' }
        Invoke-Queue @{ Command='claim'; Ledger=$path; Item='two'; Agent='agent-two' }
        Assert-Equal 1 @((Read-State $path).items | Where-Object status -eq 'in_progress').Count 'Wrong active count'
    }

    Run-Test 'fresh agent every attempt' {
        $path = New-Ledger 'freshness' @((New-QueueItem 'repair'), (New-QueueItem 'other'))
        Invoke-Queue @{ Command='claim'; Ledger=$path; Item='repair'; Agent='agent-old' }
        Invoke-Queue @{ Command='fail'; Ledger=$path; Item='repair'; Agent='agent-old'; Reason='transient failure' }
        Assert-Throws { Invoke-Queue @{ Command='claim'; Ledger=$path; Item='repair'; Agent='agent-old' } } 'not fresh' 'Retry reused its worker'
        Assert-Throws { Invoke-Queue @{ Command='claim'; Ledger=$path; Item='other'; Agent='agent-old' } } 'not fresh' 'Worker was reused across items'
        Invoke-Queue @{ Command='claim'; Ledger=$path; Item='repair'; Agent='agent-new' }
        Assert-Equal 2 (Read-State $path).items[0].attempts 'Retry did not consume a new attempt'
    }

    Run-Test 'isolated context and orchestrator-only execution' {
        $path = New-Ledger 'isolation' @((New-QueueItem 'work'))
        Assert-Throws { Invoke-Queue @{ Command='claim'; Ledger=$path; Item='work'; Agent='agent-inherited'; ForkTurns='all' } } 'ForkTurns none' 'Inherited history was accepted'
        Assert-Throws { Invoke-Queue @{ Command='claim'; Ledger=$path; Item='work'; Agent='main'; WorkerRole='orchestrator' } } 'assigned to a subagent' 'Orchestrator performed substantive work'
        Invoke-Queue @{ Command='claim'; Ledger=$path; Item='work'; Agent='agent-isolated'; ForkTurns='none'; WorkerRole='subagent' }
        $dispatch = (Read-State $path).items[0].dispatch_history[0]
        Assert-Equal 'none' $dispatch.fork_turns 'Freshness metadata was not recorded'
        Assert-Equal 'subagent' $dispatch.worker_role 'Worker role was not recorded'
    }

    Run-Test 'overlapping writes are serialized' {
        $path = New-Ledger 'write-scope' @(
            (New-QueueItem 'parent-writer' -WriteScope @('app/src')),
            (New-QueueItem 'child-writer' -WriteScope @('app/src/main/File.kt')),
            (New-QueueItem 'other-writer' -WriteScope @('docs/readme.md'))
        ) -Concurrency 3
        Invoke-Queue @{ Command='claim'; Ledger=$path; Item='parent-writer'; Agent='agent-parent' }
        Assert-Throws { Invoke-Queue @{ Command='claim'; Ledger=$path; Item='child-writer'; Agent='agent-child' } } 'overlaps active' 'Overlapping writer ran concurrently'
        Invoke-Queue @{ Command='claim'; Ledger=$path; Item='other-writer'; Agent='agent-other' }
        Assert-Equal 2 @((Read-State $path).items | Where-Object status -eq 'in_progress').Count 'Independent write scope was not parallelized'
    }

    Run-Test 'retry exhaustion propagates blocking' {
        $path = New-Ledger 'retries' @(
            (New-QueueItem 'unstable' -MaxAttempts 2),
            (New-QueueItem 'downstream' -DependsOn @('unstable'))
        )
        Invoke-Queue @{ Command='claim'; Ledger=$path; Item='unstable'; Agent='agent-r1' }
        Invoke-Queue @{ Command='fail'; Ledger=$path; Item='unstable'; Agent='agent-r1'; Reason='first failure' }
        Invoke-Queue @{ Command='claim'; Ledger=$path; Item='unstable'; Agent='agent-r2' }
        Invoke-Queue @{ Command='fail'; Ledger=$path; Item='unstable'; Agent='agent-r2'; Reason='second failure' }
        $state = Read-State $path
        Assert-Equal 'blocked' $state.items[0].status 'Exhausted item was not blocked'
        Assert-Equal 'blocked' $state.items[1].status 'Blocked dependency did not propagate'
        Assert-True ($state.items[1].block_reason -like '*unstable*') 'Dependent block reason omitted its dependency'
    }

    Run-Test 'independent validation' {
        $path = New-Ledger 'validation' @(
            (New-QueueItem 'produce'),
            (New-QueueItem 'verify' -DependsOn @('produce') -Kind 'validation' -Validates 'produce')
        )
        Invoke-Queue @{ Command='claim'; Ledger=$path; Item='produce'; Agent='agent-producer' }
        Invoke-Queue @{ Command='pass'; Ledger=$path; Item='produce'; Agent='agent-producer'; Evidence='artifact + build output' }
        Assert-Throws { Invoke-Queue @{ Command='claim'; Ledger=$path; Item='verify'; Agent='agent-producer' } } 'not fresh' 'Producer was accepted as validator'
        Invoke-Queue @{ Command='claim'; Ledger=$path; Item='verify'; Agent='agent-validator' }
        Invoke-Queue @{ Command='pass'; Ledger=$path; Item='verify'; Agent='agent-validator'; Evidence='7 behavioral tests passed' }
        Assert-Equal 'passed' (Read-State $path).items[1].status 'Independent validation did not pass'
    }

    Run-Test 'failed validation requires repair before fresh revalidation' {
        $path = New-Ledger 'validation-repair' @(
            (New-QueueItem 'produce'),
            (New-QueueItem 'verify-original' -DependsOn @('produce') -Kind 'validation' -Validates 'produce' -MaxAttempts 2),
            (New-QueueItem 'verify-bypass' -DependsOn @('produce') -Kind 'validation' -Validates 'produce'),
            (New-QueueItem 'repair-after-failure' -Kind 'repair' -RepairsValidation 'verify-original' -RequiresApproval $true -ApprovalKey 'repair-approved'),
            (New-QueueItem 'verify-repair' -DependsOn @('repair-after-failure') -Kind 'validation' -Validates 'repair-after-failure')
        )
        Invoke-Queue @{ Command='claim'; Ledger=$path; Item='produce'; Agent='agent-producer' }
        Invoke-Queue @{ Command='pass'; Ledger=$path; Item='produce'; Agent='agent-producer'; Evidence='artifact created' }
        Assert-Throws { Invoke-Queue @{ Command='claim'; Ledger=$path; Item='repair-after-failure'; Agent='agent-repair-early' } } 'has not failed validation' 'Repair started before validation failed'
        Invoke-Queue @{ Command='claim'; Ledger=$path; Item='verify-original'; Agent='agent-validator-1' }
        Invoke-Queue @{ Command='fail'; Ledger=$path; Item='verify-original'; Agent='agent-validator-1'; Reason='acceptance test failed' }
        $failedState = Read-State $path
        Assert-Equal 'failed' $failedState.items[1].status 'Failed validation was requeued'
        Assert-Equal 1 $failedState.items[1].attempts 'Failed validation did not retain its single consumed attempt'
        Assert-Equal 2 $failedState.items[1].max_attempts 'Regression did not exercise attempts remaining'
        Assert-Throws { Invoke-Queue @{ Command='claim'; Ledger=$path; Item='verify-original'; Agent='agent-validator-2' } } 'not queued' 'Original validation retried before repair'
        Assert-Throws { Invoke-Queue @{ Command='claim'; Ledger=$path; Item='verify-bypass'; Agent='agent-validator-2' } } 'unresolved failed validation' 'Fresh validation bypassed repair'
        Assert-Throws { Invoke-Queue @{ Command='claim'; Ledger=$path; Item='repair-after-failure'; Agent='agent-repair' } } 'has not been recorded' 'Repair bypassed its approval gate'
        Invoke-Queue @{ Command='approve'; Ledger=$path; Approval='repair-approved' }
        Assert-Throws { Invoke-Queue @{ Command='claim'; Ledger=$path; Item='repair-after-failure'; Agent='agent-validator-1' } } 'not fresh' 'Failed validator was reused as repair worker'
        Invoke-Queue @{ Command='claim'; Ledger=$path; Item='repair-after-failure'; Agent='agent-repair' }
        Assert-Throws { Invoke-Queue @{ Command='claim'; Ledger=$path; Item='verify-repair'; Agent='agent-validator-2' } } 'has not passed' 'Replacement validation started before repair passed'
        Invoke-Queue @{ Command='pass'; Ledger=$path; Item='repair-after-failure'; Agent='agent-repair'; Evidence='corrective change and passing focused test' }
        Assert-Throws { Invoke-Queue @{ Command='claim'; Ledger=$path; Item='verify-repair'; Agent='agent-repair' } } 'not fresh' 'Repair worker was reused as replacement validator'
        Invoke-Queue @{ Command='claim'; Ledger=$path; Item='verify-repair'; Agent='agent-validator-2' }
        Invoke-Queue @{ Command='pass'; Ledger=$path; Item='verify-repair'; Agent='agent-validator-2'; Evidence='fresh validator passed repaired artifact' }
        Assert-Equal 'passed' (Read-State $path).items[4].status 'Fresh validation did not pass after repair'
    }

    Run-Test 'approval precedes risky mutation' {
        $path = New-Ledger 'approval' @((New-QueueItem 'publish' -RequiresApproval $true -ApprovalKey 'publish-production'))
        Assert-Throws { Invoke-Queue @{ Command='claim'; Ledger=$path; Item='publish'; Agent='agent-publish' } } 'has not been recorded' 'Risky work started without approval'
        Invoke-Queue @{ Command='approve'; Ledger=$path; Approval='publish-production' }
        Invoke-Queue @{ Command='claim'; Ledger=$path; Item='publish'; Agent='agent-publish' }
        Assert-Equal 'in_progress' (Read-State $path).items[0].status 'Approved work did not start'
    }

    Run-Test 'evidence and ledger integrity' {
        $path = New-Ledger 'evidence' @((New-QueueItem 'work'))
        Invoke-Queue @{ Command='claim'; Ledger=$path; Item='work'; Agent='agent-evidence' }
        Assert-Throws { Invoke-Queue @{ Command='pass'; Ledger=$path; Item='work'; Agent='agent-evidence'; Evidence=' ' } } 'non-empty' 'Empty evidence was accepted'
        $state = Read-State $path
        $state.items[0].status = 'passed'
        $state.items[0].active_agent = $null
        $state | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $path -Encoding utf8
        Assert-Throws { Invoke-Queue @{ Command='validate'; Ledger=$path } } 'passed without evidence' 'Tampered ledger passed validation'
        $overflow = New-Ledger 'attempt-overflow' @((New-QueueItem 'overflow' -MaxAttempts 1))
        $overflowState = Read-State $overflow
        $overflowState.items[0].attempts = 2
        $overflowState | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $overflow -Encoding utf8
        Assert-Throws { Invoke-Queue @{ Command='validate'; Ledger=$overflow } } 'Attempt count is invalid' 'Retry overflow passed validation'
    }

    Run-Test 'compact status and maintained scenario list' {
        $path = New-Ledger 'status' @((New-QueueItem 'work'))
        $status = & $queueScript -Command status -Ledger $path | ConvertFrom-Json
        $properties = @($status[0].PSObject.Properties.Name)
        Assert-True ($properties -contains 'status' -and $properties -contains 'attempts') 'Status omitted queue facts'
        Assert-True ($properties -notcontains 'transcript' -and $properties -notcontains 'reasoning') 'Status leaked worker narrative'
        $scenarioIds = @((Get-Content -LiteralPath $scenarioFile -Raw | ConvertFrom-Json).scenarios.id)
        $expected = @('dependency-gating', 'acyclic-dependencies', 'concurrency-cap', 'fresh-agent-every-attempt', 'isolated-context', 'write-scope-serialization', 'retry-exhaustion', 'independent-validation', 'repair-before-revalidation', 'evidence-required', 'approval-before-risk', 'orchestrator-only', 'compact-status')
        Assert-Equal $expected.Count $scenarioIds.Count 'Scenario list count drifted from executable tests'
        foreach ($id in $expected) { Assert-True ($scenarioIds -contains $id) "Scenario '$id' is missing" }
    }

    Write-Host "RESULT: $script:passed behavioral tests passed"
}
finally {
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}

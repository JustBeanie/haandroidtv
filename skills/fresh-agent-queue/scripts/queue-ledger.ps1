[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('approve', 'claim', 'pass', 'fail', 'block', 'status', 'validate')]
    [string]$Command,

    [Parameter(Mandatory)]
    [string]$Ledger,

    [string]$Item,
    [string]$Agent,
    [string]$Evidence,
    [string]$Reason,
    [string]$Approval,
    [string]$ForkTurns = 'none',
    [string]$WorkerRole = 'subagent'
)

$ErrorActionPreference = 'Stop'

function Read-Ledger {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Ledger not found: $Path"
    }
    return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
}

function Save-Ledger {
    param([object]$State, [string]$Path)
    $directory = Split-Path -Parent $Path
    $temporary = Join-Path $directory ('.queue-ledger-' + [guid]::NewGuid().ToString('N') + '.tmp')
    try {
        $State | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $temporary -Encoding utf8
        Move-Item -LiteralPath $temporary -Destination $Path -Force
    }
    finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force
        }
    }
}

function Get-ItemById {
    param([object]$State, [string]$Id)
    $matches = @($State.items | Where-Object { $_.id -eq $Id })
    if ($matches.Count -ne 1) {
        throw "Expected exactly one queue item named '$Id'; found $($matches.Count)."
    }
    return $matches[0]
}

function Get-AgentHistory {
    param([object]$QueueItem)
    if ($null -eq $QueueItem.agent_history) { return @() }
    return @($QueueItem.agent_history)
}

function Get-DispatchHistory {
    param([object]$QueueItem)
    if ($null -eq $QueueItem.dispatch_history) { return @() }
    return @($QueueItem.dispatch_history)
}

function Normalize-Scope {
    param([string]$Scope)
    return $Scope.Replace('\', '/').TrimEnd('/').ToLowerInvariant()
}

function Test-ScopeOverlap {
    param([string[]]$Left, [string[]]$Right)
    foreach ($leftScope in @($Left)) {
        $leftNormalized = Normalize-Scope ([string]$leftScope)
        if ([string]::IsNullOrWhiteSpace($leftNormalized)) { continue }
        foreach ($rightScope in @($Right)) {
            $rightNormalized = Normalize-Scope ([string]$rightScope)
            if ([string]::IsNullOrWhiteSpace($rightNormalized)) { continue }
            if ($leftNormalized -eq $rightNormalized -or
                $leftNormalized.StartsWith($rightNormalized + '/') -or
                $rightNormalized.StartsWith($leftNormalized + '/')) {
                return $true
            }
        }
    }
    return $false
}

function Assert-Ledger {
    param([object]$State)
    if ([int]$State.version -ne 2) { throw 'Ledger version must be 2.' }
    if ([int]$State.concurrency_limit -lt 1) { throw 'concurrency_limit must be at least 1.' }

    $ids = @($State.items | ForEach-Object { [string]$_.id })
    if (($ids | Select-Object -Unique).Count -ne $ids.Count) { throw 'Queue item IDs must be unique.' }

    $knownStatuses = @('queued', 'in_progress', 'passed', 'failed', 'blocked')
    $knownKinds = @('work', 'repair', 'validation')
    $allAgents = [System.Collections.Generic.List[string]]::new()
    foreach ($queueItem in $State.items) {
        if ([string]::IsNullOrWhiteSpace([string]$queueItem.id)) { throw 'Every queue item needs an ID.' }
        if ($knownKinds -notcontains [string]$queueItem.kind) { throw "Invalid kind on '$($queueItem.id)'." }
        if ($knownStatuses -notcontains [string]$queueItem.status) { throw "Invalid status on '$($queueItem.id)'." }
        if ([int]$queueItem.max_attempts -lt 1) { throw "'$($queueItem.id)' needs max_attempts of at least 1." }
        if ([int]$queueItem.attempts -lt 0 -or [int]$queueItem.attempts -gt [int]$queueItem.max_attempts) {
            throw "Attempt count is invalid on '$($queueItem.id)'."
        }
        foreach ($dependency in @($queueItem.depends_on)) {
            if ($ids -notcontains [string]$dependency) { throw "'$($queueItem.id)' has unknown dependency '$dependency'." }
            if ([string]$dependency -eq [string]$queueItem.id) { throw "'$($queueItem.id)' cannot depend on itself." }
        }
        foreach ($usedAgent in (Get-AgentHistory $queueItem)) {
            if ([string]::IsNullOrWhiteSpace([string]$usedAgent)) { throw "'$($queueItem.id)' has an empty agent ID." }
            $allAgents.Add([string]$usedAgent)
        }
        $dispatches = @(Get-DispatchHistory $queueItem)
        if ($dispatches.Count -ne @(Get-AgentHistory $queueItem).Count) {
            throw "'$($queueItem.id)' dispatch history does not match agent history."
        }
        foreach ($dispatch in $dispatches) {
            if ([string]$dispatch.fork_turns -ne 'none') { throw "'$($queueItem.id)' inherited prior context." }
            if ([string]$dispatch.worker_role -ne 'subagent') { throw "'$($queueItem.id)' was assigned to a non-subagent actor." }
        }
        if ([string]$queueItem.status -eq 'in_progress') {
            if ([string]::IsNullOrWhiteSpace([string]$queueItem.active_agent)) { throw "'$($queueItem.id)' is active without an agent." }
            if ((Get-AgentHistory $queueItem) -notcontains [string]$queueItem.active_agent) { throw "'$($queueItem.id)' active agent is absent from history." }
        }
        if ([string]$queueItem.status -eq 'passed' -and [string]::IsNullOrWhiteSpace([string]$queueItem.evidence)) {
            throw "'$($queueItem.id)' passed without evidence."
        }
        if ([string]$queueItem.status -eq 'failed' -and [string]$queueItem.kind -ne 'validation') {
            throw "Only a validation item may have failed status; '$($queueItem.id)' is '$($queueItem.kind)'."
        }
        if ([string]$queueItem.kind -eq 'validation') {
            if ([string]::IsNullOrWhiteSpace([string]$queueItem.validates)) { throw "Validation item '$($queueItem.id)' needs a validates target." }
            if ($ids -notcontains [string]$queueItem.validates) { throw "Validation target '$($queueItem.validates)' does not exist." }
            if (@($queueItem.depends_on) -notcontains [string]$queueItem.validates) {
                throw "Validation item '$($queueItem.id)' must depend on its validates target '$($queueItem.validates)'."
            }
            if (-not [string]::IsNullOrWhiteSpace([string]$queueItem.last_failure) -and
                @('failed', 'blocked') -notcontains [string]$queueItem.status) {
                throw "Failed validation item '$($queueItem.id)' cannot be retried or passed; queue a repair and a fresh validation item."
            }
            if ([string]$queueItem.status -eq 'failed' -and [string]::IsNullOrWhiteSpace([string]$queueItem.last_failure)) {
                throw "Failed validation item '$($queueItem.id)' needs failure evidence."
            }
        }
        if ([string]$queueItem.kind -eq 'repair') {
            if ([string]::IsNullOrWhiteSpace([string]$queueItem.repairs_validation)) {
                throw "Repair item '$($queueItem.id)' needs a repairs_validation target."
            }
            if ($ids -notcontains [string]$queueItem.repairs_validation) {
                throw "Repair target '$($queueItem.repairs_validation)' does not exist."
            }
            $failedValidation = Get-ItemById $State ([string]$queueItem.repairs_validation)
            if ([string]$failedValidation.kind -ne 'validation') {
                throw "Repair item '$($queueItem.id)' must target a validation item."
            }
            if (@($queueItem.depends_on) -contains [string]$queueItem.repairs_validation) {
                throw "Repair item '$($queueItem.id)' cannot depend on the failed validation it repairs."
            }
        }
    }
    foreach ($startId in $ids) {
        $paths = [System.Collections.Generic.Queue[object]]::new()
        $paths.Enqueue([pscustomobject]@{ id = $startId; path = @($startId) })
        while ($paths.Count -gt 0) {
            $current = $paths.Dequeue()
            $currentItem = Get-ItemById $State ([string]$current.id)
            foreach ($dependencyId in @($currentItem.depends_on)) {
                if (@($current.path) -contains [string]$dependencyId) {
                    throw "Dependency cycle detected through '$dependencyId'."
                }
                $paths.Enqueue([pscustomobject]@{
                    id = [string]$dependencyId
                    path = @($current.path) + [string]$dependencyId
                })
            }
        }
    }
    if (($allAgents | Select-Object -Unique).Count -ne $allAgents.Count) { throw 'An agent ID was reused across queue attempts.' }
    if (@($State.items | Where-Object { $_.status -eq 'in_progress' }).Count -gt [int]$State.concurrency_limit) {
        throw 'Active work exceeds the concurrency limit.'
    }
}

function Propagate-BlockedDependencies {
    param([object]$State)
    $changed = $true
    while ($changed) {
        $changed = $false
        foreach ($candidate in @($State.items | Where-Object { $_.status -eq 'queued' })) {
            foreach ($dependencyId in @($candidate.depends_on)) {
                $dependency = Get-ItemById $State ([string]$dependencyId)
                if ($dependency.status -eq 'blocked') {
                    $candidate.status = 'blocked'
                    $candidate.block_reason = "dependency blocked: $dependencyId"
                    $changed = $true
                    break
                }
            }
        }
    }
}

$state = Read-Ledger $Ledger
Assert-Ledger $state

if ($Command -eq 'validate') {
    [pscustomobject]@{ valid = $true; items = @($state.items).Count } | ConvertTo-Json -Compress
    return
}

if ($Command -eq 'status') {
    $summary = foreach ($queueItem in $state.items) {
        [pscustomobject]@{
            id = $queueItem.id
            kind = $queueItem.kind
            status = $queueItem.status
            attempts = [int]$queueItem.attempts
            max_attempts = [int]$queueItem.max_attempts
            active_agent = $queueItem.active_agent
            evidence = $queueItem.evidence
            last_failure = $queueItem.last_failure
            block_reason = $queueItem.block_reason
            validates = $queueItem.validates
            repairs_validation = $queueItem.repairs_validation
        }
    }
    $summary | ConvertTo-Json -Depth 5 -Compress
    return
}

if ($Command -eq 'approve') {
    if ([string]::IsNullOrWhiteSpace($Approval)) { throw '-approve requires -Approval.' }
    if ($null -eq $state.approvals) { $state | Add-Member -NotePropertyName approvals -NotePropertyValue @() }
    $approvals = [System.Collections.ArrayList]@($state.approvals)
    if ($approvals -notcontains $Approval) { [void]$approvals.Add($Approval) }
    $state.approvals = @($approvals)
    Save-Ledger $state $Ledger
    [pscustomobject]@{ approval = $Approval; recorded = $true } | ConvertTo-Json -Compress
    return
}

if ([string]::IsNullOrWhiteSpace($Item)) { throw "-$Command requires -Item." }
$target = Get-ItemById $state $Item

switch ($Command) {
    'claim' {
        if ([string]::IsNullOrWhiteSpace($Agent)) { throw '-claim requires a non-empty -Agent.' }
        if ($ForkTurns -ne 'none') { throw 'Fresh workers require -ForkTurns none.' }
        if ($WorkerRole -ne 'subagent') { throw 'Substantive queue items must be assigned to a subagent.' }
        if ($target.status -ne 'queued') { throw "'$Item' is not queued." }
        if ([int]$target.attempts -ge [int]$target.max_attempts) { throw "'$Item' has no attempts remaining." }
        if (@($state.items | Where-Object { $_.status -eq 'in_progress' }).Count -ge [int]$state.concurrency_limit) {
            throw 'No queue capacity is available.'
        }
        foreach ($dependencyId in @($target.depends_on)) {
            $dependency = Get-ItemById $state ([string]$dependencyId)
            if ($dependency.status -ne 'passed') { throw "Dependency '$dependencyId' has not passed." }
            if ($target.kind -ne 'validation' -and $dependency.kind -ne 'validation') {
                throw "Dependency '$dependencyId' is implemented but not independently verified."
            }
        }
        foreach ($running in @($state.items | Where-Object { $_.status -eq 'in_progress' })) {
            if (Test-ScopeOverlap @($target.write_scope) @($running.write_scope)) {
                throw "Write scope overlaps active item '$($running.id)'."
            }
        }
        if ($target.kind -eq 'repair') {
            $failedValidation = Get-ItemById $state ([string]$target.repairs_validation)
            if ($failedValidation.status -ne 'failed') {
                throw "Repair target '$($target.repairs_validation)' has not failed validation."
            }
        }
        if ([bool]$target.requires_approval -and @($state.approvals) -notcontains [string]$target.approval_key) {
            throw "Required approval '$($target.approval_key)' has not been recorded."
        }
        $usedAgents = @($state.items | ForEach-Object { Get-AgentHistory $_ })
        if ($usedAgents -contains $Agent) { throw "Agent '$Agent' is not fresh." }
        if ($target.kind -eq 'validation') {
            $producer = Get-ItemById $state ([string]$target.validates)
            if ((Get-AgentHistory $producer) -contains $Agent) { throw "Validator '$Agent' also produced '$($target.validates)'." }
            if ($producer.status -ne 'passed') { throw "Validation target '$($target.validates)' has not passed." }
            if ([string]$producer.kind -ne 'repair') {
                $unresolvedFailures = @($state.items | Where-Object {
                    [string]$_.kind -eq 'validation' -and
                    [string]$_.validates -eq [string]$producer.id -and
                    [string]$_.status -eq 'failed'
                })
                if ($unresolvedFailures.Count -gt 0) {
                    throw "Validation target '$($producer.id)' has an unresolved failed validation; validate a completed repair item instead."
                }
            }
        }
        $history = [System.Collections.ArrayList]@(Get-AgentHistory $target)
        [void]$history.Add($Agent)
        $target.agent_history = @($history)
        $dispatchHistory = [System.Collections.ArrayList]@(Get-DispatchHistory $target)
        [void]$dispatchHistory.Add([pscustomobject]@{ agent = $Agent; fork_turns = 'none'; worker_role = 'subagent' })
        if ($target.PSObject.Properties.Name -contains 'dispatch_history') {
            $target.dispatch_history = @($dispatchHistory)
        }
        else {
            $target | Add-Member -NotePropertyName dispatch_history -NotePropertyValue @($dispatchHistory)
        }
        $target.active_agent = $Agent
        $target.attempts = [int]$target.attempts + 1
        $target.status = 'in_progress'
    }
    'pass' {
        if ([string]::IsNullOrWhiteSpace($Agent)) { throw '-pass requires -Agent.' }
        if ([string]::IsNullOrWhiteSpace($Evidence)) { throw '-pass requires non-empty -Evidence.' }
        if ($target.status -ne 'in_progress' -or $target.active_agent -ne $Agent) { throw "Agent '$Agent' does not own '$Item'." }
        $target.status = 'passed'
        $target.evidence = $Evidence
        $target.active_agent = $null
    }
    'fail' {
        if ([string]::IsNullOrWhiteSpace($Agent)) { throw '-fail requires -Agent.' }
        if ([string]::IsNullOrWhiteSpace($Reason)) { throw '-fail requires -Reason.' }
        if ($target.status -ne 'in_progress' -or $target.active_agent -ne $Agent) { throw "Agent '$Agent' does not own '$Item'." }
        $target.active_agent = $null
        if ($target.PSObject.Properties.Name -contains 'last_failure') {
            $target.last_failure = $Reason
        }
        else {
            $target | Add-Member -NotePropertyName last_failure -NotePropertyValue $Reason
        }
        if ([string]$target.kind -eq 'validation') {
            $target.status = 'failed'
        }
        elseif ([int]$target.attempts -ge [int]$target.max_attempts) {
            $target.status = 'blocked'
            $target.block_reason = "attempts exhausted: $Reason"
            Propagate-BlockedDependencies $state
        }
        else {
            $target.status = 'queued'
        }
    }
    'block' {
        if ([string]::IsNullOrWhiteSpace($Reason)) { throw '-block requires -Reason.' }
        if ($target.status -eq 'passed') { throw "Cannot block passed item '$Item'." }
        $target.status = 'blocked'
        $target.active_agent = $null
        $target.block_reason = $Reason
        Propagate-BlockedDependencies $state
    }
}

Assert-Ledger $state
Save-Ledger $state $Ledger
[pscustomobject]@{ id = $target.id; status = $target.status; attempts = [int]$target.attempts } | ConvertTo-Json -Compress

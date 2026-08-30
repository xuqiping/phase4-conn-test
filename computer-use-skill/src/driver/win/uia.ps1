# uia.ps1 —— UIA 常驻工作进程（stdin 读 JSON 行命令，stdout 写 JSON 行结果）
# FR-002/003/012：树遍历 / 三级定位 / 零激活直控（Invoke/Expand/Toggle/Select/SetValue）
# 黑名单（FR-014）在 PS 层再次拦截（防绕过）：终端类进程名直接拒绝。
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
$ErrorActionPreference = 'Stop'

$script:terminals = @('windowsterminal.exe','cmd.exe','powershell.exe','pwsh.exe','conhost.exe','wt.exe')
$script:counter = 0

function Find-Root([string]$app) {
  # 按进程名或标题包含匹配顶层窗口；找不到返回 $null
  $procs = Get-Process | Where-Object { $_.MainWindowTitle -and ($_.ProcessName -like "*$app*" -or $_.MainWindowTitle -like "*$app*") }
  if (-not $procs) {
    # 兜底：MainWindowTitle 为空的进程（Tauri/Electron 多窗口特征）按 UIA 顶层标题匹配
    $cond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, $app)
    $wins = [System.Windows.Automation.AutomationElement]::RootElement.FindAll([System.Windows.Automation.TreeScope]::Children, $cond)
    if ($wins.Count -eq 0) { return $null }
    $el = $wins[0]
    $procId = $el.GetCurrentPropertyValue([System.Windows.Automation.AutomationElement]::ProcessIdProperty)
    $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
    if ($proc -and $script:terminals -contains ($proc.ProcessName.ToLower() + '.exe')) { throw "TARGET_BLOCKED: $($proc.ProcessName)" }
    return @{ Root = $el; ProcName = if ($proc) { $proc.ProcessName.ToLower() + '.exe' } else { "pid:$procId" } }
  }
  $proc = @($procs)[0]
  if ($script:terminals -contains ($proc.ProcessName.ToLower() + '.exe')) { throw "TARGET_BLOCKED: $($proc.ProcessName)" }
  $root = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)
  if (-not $root) { return $null }
  return @{ Root = $root; ProcName = $proc.ProcessName.ToLower() + '.exe' }
}

function Node-ToJson($el, [int]$depth, [int]$maxDepth, [string[]]$roleFilter) {
  $script:counter++
  $props = $el.GetCurrentPropertyValue([System.Windows.Automation.AutomationElement]::ControlTypeProperty)
  $role = if ($props) { $props.ProgrammaticName -replace '^ControlType\.','' } else { 'Unknown' }
  if ($roleFilter -and $roleFilter.Count -gt 0 -and ($roleFilter -notcontains $role)) { return $null }
  $name = $el.GetCurrentPropertyValue([System.Windows.Automation.AutomationElement]::NameProperty)
  $aid = $el.GetCurrentPropertyValue([System.Windows.Automation.AutomationElement]::AutomationIdProperty)
  $r = $el.GetCurrentPropertyValue([System.Windows.Automation.AutomationElement]::BoundingRectangleProperty)
  $bounds = if ($r -and $r.Width -ge 0) { @([int]$r.X, [int]$r.Y, [int]($r.X + $r.Width), [int]($r.Y + $r.Height)) } else { @(0,0,0,0) }
  $actions = @()
  # 查直控模式可用性（Invoke/Expand/Toggle/Select/SetValue）
  $actions = @()
  foreach ($pi in @(
    @{n='Invoke';    t=[System.Windows.Automation.InvokePattern]::Pattern},
    @{n='Expand';    t=[System.Windows.Automation.ExpandCollapsePattern]::Pattern},
    @{n='Toggle';    t=[System.Windows.Automation.TogglePattern]::Pattern},
    @{n='Select';    t=[System.Windows.Automation.SelectionItemPattern]::Pattern},
    @{n='SetValue';  t=[System.Windows.Automation.ValuePattern]::Pattern})) {
    $dummy = $null
    if ($el.TryGetCurrentPattern($pi.t, [ref]$dummy)) { $actions += $pi.n }
  }
  $node = @{ index = $script:counter; role = $role; name = [string]$name; automationId = [string]$aid; bounds = $bounds; actions = $actions }
  if ($depth -lt $maxDepth) {
    $kids = $el.FindAll([System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition)
    if ($kids.Count -gt 500) { $script:truncated = $true; $kids = $kids | Select-Object -First 500 }
    $children = @()
    foreach ($k in $kids) { $c = Node-ToJson $k ($depth+1) $maxDepth $roleFilter; if ($c) { $children += $c } }
    if ($children.Count -gt 0) { $node.children = $children }
  }
  return $node
}

function Cmd-Tree($cmd) {
  $found = Find-Root $cmd.app
  if (-not $found) { return @{ ok = $false; error = 'APP_NOT_FOUND' } }
  $script:counter = 0; $script:truncated = $false
  $maxDepth = if ($cmd.maxDepth) { [int]$cmd.maxDepth } else { 4 }
  $node = Node-ToJson $found.Root 0 $maxDepth $null
  return @{ ok = $true; nodes = @($node); truncated = $script:truncated; app = $found.ProcName }
}

function Find-Element($cmd) {
  $found = Find-Root $cmd.app
  if (-not $found) { return @{ ok = $false; error = 'APP_NOT_FOUND' } }
  $cond = switch ($cmd.by) {
    'name' { New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, [string]$cmd.value) }
    'automationId' { New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::AutomationIdProperty, [string]$cmd.value) }
    default { $null }
  }
  if (-not $cond) { return @{ ok = $false; error = 'INVALID_ARGUMENT'; msg = "PS 层仅支持 name/automationId；index/xy 由 TS 层处理" } }
  $els = $found.Root.FindAll([System.Windows.Automation.TreeScope]::Subtree, $cond)
  if ($els.Count -eq 0) { return @{ ok = $false; error = 'ELEMENT_NOT_FOUND' } }
  if ($els.Count -gt 1) { return @{ ok = $false; error = 'AMBIGUOUS_MATCH'; candidates = @($els | ForEach-Object { @{ name = $_.GetCurrentPropertyValue([System.Windows.Automation.AutomationElement]::NameProperty) } }) } }
  return @{ ok = $true; element = $els[0] }
}

function Cmd-Act($cmd) {
  $r = Find-Element $cmd
  if (-not $r.ok) { return $r }
  $el = $r.element
  $dummy = $null
  switch ($cmd.pattern) {
    'Invoke'  { $p=$null; if ($el.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern,[ref]$p)) { $p.Invoke(); return @{ok=$true} } }
    'Expand'  { $p=$null; if ($el.TryGetCurrentPattern([System.Windows.Automation.ExpandCollapsePattern]::Pattern,[ref]$p)) { $p.Expand(); return @{ok=$true} } }
    'Toggle'  { $p=$null; if ($el.TryGetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern,[ref]$p)) { $p.Toggle(); return @{ok=$true} } }
    'Select'  { $p=$null; if ($el.TryGetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern,[ref]$p)) { $p.Select(); return @{ok=$true} } }
    'SetValue' { $p=$null; if ($el.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern,[ref]$p)) { $p.SetValue([string]$cmd.value_set); return @{ok=$true} } }
  }
  return @{ ok = $false; error = 'DRIVER_ERROR'; msg = "元素不支持直控模式 $($cmd.pattern)（Electron 自绘控件？应降级 SendInput）" }
}

# ---- 主循环：逐行读 JSON 命令 ----
# stdin/stdout 统一 UTF-8（宿主 Node 写 UTF-8；PS5.1 默认按 GBK 解码管道会乱码）
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
try { [Console]::InputEncoding = [System.Text.Encoding]::UTF8 } catch { }
$stdin = New-Object System.IO.StreamReader([Console]::OpenStandardInput(), [System.Text.Encoding]::UTF8)
while ($true) {
  $line = $stdin.ReadLine()
  if ($null -eq $line) { break }
  try {
    $cmd = $line | ConvertFrom-Json
    $result = switch ($cmd.op) {
      'tree' { Cmd-Tree $cmd }
      'act'  { Cmd-Act $cmd }
      'ping' { @{ ok = $true; pong = $true } }
      default { @{ ok = $false; error = 'INVALID_ARGUMENT'; msg = "未知 op: $($cmd.op)" } }
    }
  } catch {
    $msg = $_.Exception.Message
    if ($msg -like 'TARGET_BLOCKED*') { $result = @{ ok = $false; error = 'TARGET_BLOCKED'; msg = $msg } }
    else { $result = @{ ok = $false; error = 'DRIVER_ERROR'; msg = $msg } }
  }
  $result._seq = $cmd._seq
  Write-Output ($result | ConvertTo-Json -Depth 12 -Compress)
}

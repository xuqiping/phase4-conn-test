import subprocess


def close_all_admini_processes():
    """关闭所有进程名中包含 'admini' 的进程（包括当前终端）。"""
    print("正在查找进程名中包含 'admini' 的进程...")

    # 先列出匹配的进程（进程名或窗口标题包含 admini 或 管理员）
    list_cmd = [
        "powershell", "-Command",
        "Get-Process | Where-Object {"
        "($_.ProcessName -like '*admini*' -or "
        "$_.MainWindowTitle -like '*admini*' -or "
        "$_.ProcessName -like '*管理员*' -or "
        "$_.MainWindowTitle -like '*管理员*') -and "
        "$_.MainWindowTitle -notlike '*Code*'"
        "} | Select-Object ProcessName, Id, MainWindowTitle | Format-Table -AutoSize"
    ]
    result = subprocess.run(list_cmd, capture_output=True, text=True, shell=False)
    output = result.stdout.strip() if result.stdout else ""
    if output:
        print("找到以下匹配的进程：")
        print(output)
    else:
        print("未找到进程名或窗口标题中包含 'admini' 或 '管理员' 的进程。")
        return

    print("\n正在关闭这些进程...")
    kill_cmd = [
        "powershell", "-Command",
        "Get-Process | Where-Object {"
        "($_.ProcessName -like '*admini*' -or "
        "$_.MainWindowTitle -like '*admini*' -or "
        "$_.ProcessName -like '*管理员*' -or "
        "$_.MainWindowTitle -like '*管理员*') -and "
        "$_.MainWindowTitle -notlike '*Code*'"
        "} | Stop-Process -Force"
    ]
    subprocess.run(kill_cmd, shell=False)
    print("关闭指令已执行。")


def start_admin_wsl_hermes():
    """以管理员身份打开 PowerShell，执行 wsl -d Ubuntu 并运行 hermes。"""
    print("\n正在以管理员身份启动 PowerShell 并进入 WSL Ubuntu...")

    cmd = [
        "powershell", "-Command",
        "Start-Process powershell -Verb RunAs "
        "-ArgumentList '-NoExit','-Command','wsl -d Ubuntu -e bash -lic \"hermes\"'"
    ]
    subprocess.run(cmd, shell=False)
    print("已请求启动管理员 PowerShell（请确认 UAC 弹窗）。")


if __name__ == "__main__":
    close_all_admini_processes()
    start_admin_wsl_hermes()

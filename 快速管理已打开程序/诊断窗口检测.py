"""
窗口检测诊断工具
运行后会列出所有可见窗口及其进程名，帮助排查"快速清理工具"识别不到的问题
"""

import ctypes
from ctypes import wintypes

# 检查依赖
try:
    import psutil
    PSUTIL_OK = True
except Exception as e:
    psutil = None
    PSUTIL_OK = False
    print(f"[警告] psutil 未安装或导入失败: {e}")

try:
    import win32gui
    import win32process
    WIN32_OK = True
except Exception as e:
    win32gui = None
    win32process = None
    WIN32_OK = False
    print(f"[错误] pywin32 未安装或导入失败: {e}")
    print("请执行: pip install pywin32 psutil")
    input("按回车退出...")
    raise SystemExit(1)

# Windows API 备选获取进程名
_kernel32 = ctypes.windll.kernel32
_PROCESS_QUERY_LIMITED_INFORMATION = 0x1000


def _get_process_name_api(pid):
    try:
        h = _kernel32.OpenProcess(_PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
        if not h:
            return None
        try:
            buf = ctypes.create_unicode_buffer(512)
            size = wintypes.DWORD(512)
            if _kernel32.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(size)):
                return buf.value.split('\\')[-1]
        finally:
            _kernel32.CloseHandle(h)
    except Exception:
        pass
    return None


def get_process_name(pid):
    """优先用 psutil，备选 Windows API"""
    if psutil is not None:
        try:
            return psutil.Process(pid).name()
        except Exception:
            pass
    return _get_process_name_api(pid)


# 目标进程映射（小写）
TARGET_PROCS = {
    "chrome.exe": "浏览器", "msedge.exe": "浏览器", "firefox.exe": "浏览器",
    "opera.exe": "浏览器", "brave.exe": "浏览器", "vivaldi.exe": "浏览器",
    "arc.exe": "浏览器", "360chrome.exe": "浏览器", "360se.exe": "浏览器",
    "sogouexplorer.exe": "浏览器", "qqbrowser.exe": "浏览器", "liebao.exe": "浏览器",
    "cmd.exe": "终端", "powershell.exe": "终端", "pwsh.exe": "终端",
    "windowsterminal.exe": "终端", "wt.exe": "终端", "conhost.exe": "终端",
    "winrar.exe": "压缩包", "bandizip.exe": "压缩包", "7zfm.exe": "压缩包",
    "7zg.exe": "压缩包", "peazip.exe": "压缩包", "haozip.exe": "压缩包",
    "360zip.exe": "压缩包", "notepad.exe": "文档", "notepad++.exe": "文档",
    "code.exe": "文档", "cursor.exe": "文档", "typora.exe": "文档",
    "obsidian.exe": "文档", "word.exe": "文档", "winword.exe": "文档",
    "wps.exe": "文档", "wordpad.exe": "文档",
}

matched = {k: [] for k in set(TARGET_PROCS.values())}
matched["其他"] = []

print("=" * 70)
print("正在扫描所有可见窗口...")
print(f"psutil 状态: {'可用' if PSUTIL_OK else '不可用'}")
print("=" * 70)

def enum_callback(hwnd, _):
    if not win32gui.IsWindowVisible(hwnd):
        return
    title = win32gui.GetWindowText(hwnd)
    if not title:
        return
    try:
        _, pid = win32process.GetWindowThreadProcessId(hwnd)
        proc_name = get_process_name(pid)
        proc_name_lower = (proc_name or "").lower()
        category = TARGET_PROCS.get(proc_name_lower, "其他")
        matched[category].append((title[:60], proc_name or "未知"))
    except Exception as e:
        matched["其他"].append((title[:60], f"错误:{e}"))

win32gui.EnumWindows(enum_callback, None)

# 输出结果
for cat in ["浏览器", "终端", "压缩包", "文档", "其他"]:
    items = matched[cat]
    print(f"\n【{cat}】找到 {len(items)} 个窗口")
    if cat != "其他":
        for title, proc in items[:20]:
            print(f"  - [{proc}] {title}")
        if len(items) > 20:
            print(f"  ... 还有 {len(items)-20} 个")
    else:
        # 其他类只显示前 15 个，避免刷屏
        for title, proc in items[:15]:
            print(f"  - [{proc}] {title}")
        if len(items) > 15:
            print(f"  ... 还有 {len(items)-15} 个其他窗口")

print("\n" + "=" * 70)
print("诊断完成。如果【浏览器】【终端】【压缩包】【文档】都是 0，")
print("请检查这些应用是否真正在运行且窗口可见。")
print("如果某个软件出现在【其他】里，请告诉我它的 [进程名]，我可以添加。")
print("=" * 70)
input("按回车退出...")

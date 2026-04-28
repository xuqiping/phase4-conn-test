import win32gui, win32process, psutil

def check():
    def callback(hwnd, windows):
        if not win32gui.IsWindowVisible(hwnd):
            return
        title = win32gui.GetWindowText(hwnd)
        if not title:
            return
        try:
            class_name = win32gui.GetClassName(hwnd)
        except:
            class_name = ""
        if "Chrome_WidgetWin" in class_name or "MozillaWindowClass" in class_name:
            _, pid = win32process.GetWindowThreadProcessId(hwnd)
            try:
                pname = psutil.Process(pid).name()
            except:
                pname = "(failed)"
            print(f"类名: {class_name} | 进程: {pname} | 标题: {title[:50]}")

    win32gui.EnumWindows(callback, None)

check()
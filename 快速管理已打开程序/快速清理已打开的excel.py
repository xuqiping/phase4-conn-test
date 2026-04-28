"""
快速清理已打开的Excel工具
列出所有已打开的Excel文件（Office/WPS），可选择保留，一键关闭其他
"""

import ctypes
import tkinter as tk
from tkinter import ttk
import threading


def _com_init():
    """在当前线程初始化 COM（STA 模式）"""
    ctypes.windll.ole32.CoInitialize(None)


def _com_uninit():
    ctypes.windll.ole32.CoUninitialize()


def _try_get_workbooks(prog_id, source_label):
    """尝试通过 COM 获取指定应用的工作簿列表"""
    import comtypes.client

    try:
        app = comtypes.client.GetActiveObject(prog_id, dynamic=True)
    except Exception:
        return []

    try:
        wb_count = app.Workbooks.Count
        workbooks = []
        for i in range(1, wb_count + 1):
            wb = app.Workbooks.Item(i)
            workbooks.append({
                "name": wb.Name,
                "full_path": wb.FullName,
                "index": i,
                "prog_id": prog_id,
                "source": source_label,
            })
        return workbooks
    except Exception:
        return []


def _get_open_excel_files():
    """获取所有已打开的 Excel 工作簿（Office + WPS）"""
    results = []
    seen = set()

    for prog_id, label in [
        ("Excel.Application", "Office Excel"),
        ("Ket.Application", "WPS Excel"),
        ("kwps.Application", "WPS"),
    ]:
        for wb in _try_get_workbooks(prog_id, label):
            key = (wb["full_path"], wb["name"])
            if key not in seen:
                seen.add(key)
                results.append(wb)

    return results


def _close_workbooks(wbs_to_close):
    """关闭指定的工作簿（不保存），如果是最后一个则退出应用"""
    import comtypes.client

    grouped = {}
    for wb in wbs_to_close:
        grouped.setdefault(wb["prog_id"], []).append(wb)

    for prog_id, wbs in grouped.items():
        try:
            app = comtypes.client.GetActiveObject(prog_id, dynamic=True)
            total = app.Workbooks.Count
            closing_all = len(wbs) >= total

            if closing_all:
                try:
                    app.Quit()
                except Exception:
                    pass
            else:
                for wb in sorted(wbs, key=lambda x: x["index"], reverse=True):
                    try:
                        app.Workbooks.Item(wb["index"]).Close(SaveChanges=False)
                    except Exception:
                        pass
        except Exception:
            pass


class ExcelCleanerApp:
    def __init__(self, root):
        self.root = root
        self.root.title("快速清理已打开的Excel")
        self.root.geometry("780x500")
        self.root.resizable(True, True)

        self.workbooks = []
        self.check_vars = []

        self._build_ui()
        self._refresh()

    def _build_ui(self):
        toolbar = ttk.Frame(self.root, padding=8)
        toolbar.pack(fill=tk.X)

        ttk.Button(toolbar, text="刷新列表", command=self._refresh).pack(
            side=tk.LEFT, padx=(0, 8))
        ttk.Button(toolbar, text="全选", command=self._select_all).pack(
            side=tk.LEFT, padx=(0, 4))
        ttk.Button(toolbar, text="取消全选", command=self._deselect_all).pack(
            side=tk.LEFT, padx=(0, 4))
        ttk.Button(toolbar, text="反选", command=self._invert_selection).pack(
            side=tk.LEFT)
        ttk.Label(toolbar, text="勾选 = 保留，未勾选 = 将被关闭",
                  foreground="gray").pack(side=tk.RIGHT)

        list_frame = ttk.Frame(self.root, padding=(8, 0, 8, 0))
        list_frame.pack(fill=tk.BOTH, expand=True)

        columns = ("select", "name", "path", "source")
        self.tree = ttk.Treeview(list_frame, columns=columns,
                                 show="headings", selectmode="none")
        self.tree.heading("select", text="保留")
        self.tree.heading("name", text="文件名")
        self.tree.heading("path", text="完整路径")
        self.tree.heading("source", text="来源")
        self.tree.column("select", width=50, anchor=tk.CENTER, stretch=False)
        self.tree.column("name", width=200, anchor=tk.W)
        self.tree.column("path", width=420, anchor=tk.W)
        self.tree.column("source", width=100, anchor=tk.CENTER, stretch=False)

        vsb = ttk.Scrollbar(list_frame, orient=tk.VERTICAL, command=self.tree.yview)
        self.tree.configure(yscrollcommand=vsb.set)
        self.tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        vsb.pack(side=tk.RIGHT, fill=tk.Y)
        self.tree.bind("<ButtonRelease-1>", self._on_tree_click)

        self.tree.tag_configure("checked", foreground="black")
        self.tree.tag_configure("unchecked", foreground="gray")

        self.status_var = tk.StringVar(value="正在获取已打开的Excel...")
        ttk.Label(self.root, textvariable=self.status_var,
                  relief=tk.SUNKEN, anchor=tk.W, padding=(8, 4)).pack(fill=tk.X)

        bottom = ttk.Frame(self.root, padding=8)
        bottom.pack(fill=tk.X)
        self.close_btn = ttk.Button(
            bottom, text="关闭未勾选的Excel", command=self._close_unselected)
        self.close_btn.pack(side=tk.RIGHT)

    def _run_com(self, func, on_done):
        """在线程中执行 COM 操作，完成后回调主线程"""
        def worker():
            _com_init()
            try:
                result = func()
            finally:
                _com_uninit()
            self.root.after(0, lambda: on_done(result))

        threading.Thread(target=worker, daemon=True).start()

    def _refresh(self):
        for item in self.tree.get_children():
            self.tree.delete(item)
        self.workbooks = []
        self.check_vars = []
        self.status_var.set("正在获取已打开的Excel...")
        self.close_btn.config(state=tk.DISABLED)

        def on_done(wbs):
            self.workbooks = wbs
            for i, wb in enumerate(wbs):
                var = tk.BooleanVar(value=True)
                self.check_vars.append(var)
                self.tree.insert(
                    "", tk.END, iid=str(i),
                    values=("☑", wb["name"], wb["full_path"], wb["source"]),
                    tags=("checked",))
            n = len(wbs)
            self.status_var.set(
                f"共找到 {n} 个已打开的Excel" if n > 0
                else "没有找到已打开的Excel文件")
            self.close_btn.config(state=tk.NORMAL if n > 0 else tk.DISABLED)

        self._run_com(_get_open_excel_files, on_done)

    def _update_row(self, idx, checked):
        self.tree.item(str(idx),
                       values=("☑" if checked else "☐",
                               self.workbooks[idx]["name"],
                               self.workbooks[idx]["full_path"],
                               self.workbooks[idx]["source"]),
                       tags=("checked" if checked else "unchecked",))

    def _on_tree_click(self, event):
        if self.tree.identify_region(event.x, event.y) != "cell":
            return
        if self.tree.identify_column(event.x) != "#1":
            return
        item_id = self.tree.identify_row(event.y)
        if not item_id:
            return
        idx = int(item_id)
        self.check_vars[idx].set(not self.check_vars[idx].get())
        self._update_row(idx, self.check_vars[idx].get())

    def _for_each(self, value_func):
        for i, var in enumerate(self.check_vars):
            var.set(value_func(var.get()))
            self._update_row(i, var.get())

    def _select_all(self):
        self._for_each(lambda _: True)

    def _deselect_all(self):
        self._for_each(lambda _: False)

    def _invert_selection(self):
        self._for_each(lambda v: not v)

    def _close_unselected(self):
        to_close = [wb for i, wb in enumerate(self.workbooks)
                    if not self.check_vars[i].get()]
        if not to_close:
            self.status_var.set("没有需要关闭的Excel")
            return

        n = len(to_close)
        self.status_var.set(f"正在关闭 {n} 个Excel...")
        self.close_btn.config(state=tk.DISABLED)

        def on_done(_):
            self.status_var.set(f"已关闭 {n} 个Excel")
            self._refresh()

        self._run_com(lambda: _close_workbooks(to_close), on_done)


def main():
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except Exception:
        pass

    root = tk.Tk()
    ExcelCleanerApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()

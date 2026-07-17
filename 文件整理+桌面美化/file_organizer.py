import os
import sys
from pathlib import Path

# 修复中文路径下 Qt 找不到 platform plugin 的问题
if sys.platform == "win32":
    try:
        from PyQt5 import QtCore

        _qt_plugin_base = Path(QtCore.__file__).parent / "Qt5" / "plugins"
        os.environ["QT_PLUGIN_PATH"] = str(_qt_plugin_base)
        os.environ["QT_QPA_PLATFORM_PLUGIN_PATH"] = str(_qt_plugin_base / "platforms")
    except Exception:
        pass

import shutil
import re
import json
import time
import subprocess
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

from PyQt5.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QLabel, QLineEdit, QPushButton, QRadioButton, QButtonGroup,
    QTreeWidget, QTreeWidgetItem, QFileDialog, QProgressBar,
    QGroupBox, QListWidget, QListWidgetItem, QMessageBox, QTextEdit,
    QSplitter, QHeaderView, QAbstractItemView, QCheckBox,
    QTableWidget, QTableWidgetItem, QTabWidget, QDialog
)
from PyQt5.QtCore import Qt, QThread, pyqtSignal, QTimer
from PyQt5.QtGui import QColor, QFont

# ── 文件类型分类映射（类别 -> 扩展名集合）──
CATEGORY_MAP = {
    "Office文档": {
        # Word
        "doc", "docx", "docm", "dot", "dotx", "dotm",
        # Excel
        "xls", "xlsx", "xlsm", "xlsb", "xlt", "xltx", "xltm", "xla", "xlam", "xll",
        # PowerPoint
        "ppt", "pptx", "pptm", "pot", "potx", "potm", "pps", "ppsx", "ppsm",
        # OpenDocument
        "odt", "ods", "odp", "ott", "ots", "otp",
    },
    "文档": {"pdf", "txt", "md", "csv", "rtf"},
    "图片": {"jpg", "jpeg", "png", "gif", "bmp", "svg", "ico", "webp", "tiff", "tif", "raw", "heic"},
    "视频": {"mp4", "avi", "mkv", "mov", "wmv", "flv", "rmvb", "3gp", "ts", "mpg", "mpeg"},
    "音频": {"mp3", "wav", "flac", "aac", "ogg", "wma", "m4a", "ape", "mid"},
    "压缩包": {"zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso"},
    "代码": {"py", "js", "ts", "java", "c", "cpp", "h", "html", "css", "json", "xml", "sql",
             "go", "rs", "rb", "php", "sh", "bat", "ps1", "yaml", "yml", "toml", "ini", "cfg"},
    "程序": {"exe", "msi", "dmg", "app", "apk", "deb", "rpm"},
    "快捷方式": {"lnk", "url"},
}

# 默认的归档目录名（类别 -> 目录名），用户可在界面中修改
DEFAULT_DIR_NAMES = {cat: cat for cat in CATEGORY_MAP}

# 配置文件路径（与脚本同目录）
CONFIG_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "organizer_config.json")

# 可读取内容的文本类扩展名
TEXT_EXTENSIONS = {
    "txt", "md", "csv", "json", "xml", "html", "css", "js", "ts", "py",
    "java", "c", "cpp", "h", "sql", "go", "rs", "rb", "php", "sh", "bat",
    "yaml", "yml", "toml", "ini", "cfg", "log", "rtf", "svg"
}


def get_desktop_extra_dirs(selected_dir):
    """如果选中的是用户桌面目录，返回需要合并扫描的额外目录列表（如公共桌面）"""
    extra = []
    selected_dir = os.path.normcase(os.path.abspath(selected_dir))
    # 检测用户桌面
    user_desktop = os.path.normcase(os.path.abspath(os.path.expanduser("~/Desktop")))
    if selected_dir == user_desktop:
        public_desktop = os.path.normcase(os.path.abspath(
            os.path.join(os.environ.get("PUBLIC", "C:\\Users\\Public"), "Desktop")))
        if os.path.isdir(public_desktop) and public_desktop != user_desktop:
            extra.append(public_desktop)
    return extra


def get_category(filename):
    ext = filename.rsplit(".", 1)[-1].lower() if "." in filename else ""
    for cat, exts in CATEGORY_MAP.items():
        if ext in exts:
            return cat
    return "其他"


def format_size(size_bytes):
    if size_bytes < 1024:
        return f"{size_bytes} B"
    elif size_bytes < 1024 * 1024:
        return f"{size_bytes / 1024:.1f} KB"
    elif size_bytes < 1024 * 1024 * 1024:
        return f"{size_bytes / (1024 * 1024):.1f} MB"
    else:
        return f"{size_bytes / (1024 * 1024 * 1024):.1f} GB"


def file_contains_keyword(filepath, keyword, use_regex=False):
    ext = filepath.rsplit(".", 1)[-1].lower() if "." in filepath else ""
    if ext not in TEXT_EXTENSIONS:
        return False
    try:
        with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
            content = f.read()
        if use_regex:
            return bool(re.search(keyword, content))
        return keyword.lower() in content.lower()
    except Exception:
        return False


class OrganizeThread(QThread):
    progress = pyqtSignal(int, int, str)
    finished_signal = pyqtSignal(int, int, str)
    error_signal = pyqtSignal(str)

    def __init__(self, file_plan, base_dir, mode="move"):
        super().__init__()
        self.file_plan = file_plan  # [(src_path, dest_dir), ...]
        self.base_dir = base_dir
        self.mode = mode  # "move" or "copy"
        self._stopped = False

    def stop(self):
        self._stopped = True

    def run(self):
        total = len(self.file_plan)
        success = 0
        errors = []
        for i, (src, dest_dir) in enumerate(self.file_plan):
            if self._stopped:
                break
            try:
                target_dir = os.path.join(self.base_dir, dest_dir)
                os.makedirs(target_dir, exist_ok=True)
                dest_path = os.path.join(target_dir, os.path.basename(src))
                if os.path.exists(dest_path) and os.path.abspath(src) != os.path.abspath(dest_path):
                    name, ext = os.path.splitext(os.path.basename(src))
                    counter = 1
                    while os.path.exists(dest_path):
                        dest_path = os.path.join(target_dir, f"{name}_{counter}{ext}")
                        counter += 1
                if self.mode == "move":
                    shutil.move(src, dest_path)
                else:
                    shutil.copy2(src, dest_path)
                success += 1
            except Exception as e:
                errors.append(f"{os.path.basename(src)}: {e}")
            self.progress.emit(i + 1, total, os.path.basename(src))
        msg = f"完成！成功处理 {success}/{total} 个文件"
        if errors:
            msg += f"\n失败 {len(errors)} 个:\n" + "\n".join(errors[:20])
        self.finished_signal.emit(success, total, msg)


class SizeCalcThread(QThread):
    """后台线程：并行计算文件夹大小，带进度和超时"""
    result_ready = pyqtSignal(object, str)
    progress = pyqtSignal(int, int)
    scanning = pyqtSignal(str, int)  # 当前路径, 累计文件数
    all_done = pyqtSignal()

    TIMEOUT_PER_DIR = 30

    def __init__(self, items, max_workers=6):
        super().__init__()
        self.items = items
        self.max_workers = max_workers
        self._total_files = 0  # 线程安全的累计文件计数

    @staticmethod
    def _calc_single(dirpath, thread):
        """用 os.scandir 递归计算，跳过 reparse point 避免循环"""
        size = 0
        file_count = 0
        stack = [dirpath]
        seen = {os.path.realpath(dirpath)}
        try:
            while stack:
                current_dir = stack.pop()
                try:
                    with os.scandir(current_dir) as it:
                        for entry in it:
                            try:
                                if entry.is_dir(follow_symlinks=False):
                                    # 跳过 junction/symlink (reparse point) 避免死循环
                                    st_dir = entry.stat(follow_symlinks=False)
                                    if st_dir.st_file_attributes & 0x400:
                                        continue
                                    real = os.path.realpath(entry.path)
                                    if real not in seen:
                                        seen.add(real)
                                        stack.append(entry.path)
                                else:
                                    st = entry.stat()
                                    size += st.st_size
                                    file_count += 1
                                    thread._total_files += 1
                                    if file_count % 5000 == 0:
                                        thread.scanning.emit(entry.path, thread._total_files)
                            except OSError:
                                continue
                except OSError:
                    continue
        except Exception:
            pass
        return size

    def run(self):
        total = len(self.items)
        done = 0

        with ThreadPoolExecutor(max_workers=self.max_workers) as pool:
            futures = {}
            for item, dirpath in self.items:
                if not os.path.isdir(dirpath):
                    done += 1
                    self.progress.emit(done, total)
                    continue
                future = pool.submit(self._calc_single, dirpath, self)
                futures[future] = item

            for future in as_completed(futures, timeout=None):
                item = futures[future]
                try:
                    size = future.result(timeout=self.TIMEOUT_PER_DIR)
                    label = format_size(size)
                except Exception:
                    label = "?"
                self.result_ready.emit(item, label)
                done += 1
                self.progress.emit(done, total)

        self.all_done.emit()


class LockDetectThread(QThread):
    """后台线程：使用 Windows Restart Manager 检测占用指定目录的进程"""
    result_ready = pyqtSignal(object, str)
    progress = pyqtSignal(str)
    MAX_FILES = 200

    def __init__(self, dir_path):
        super().__init__()
        self.dir_path = dir_path

    @staticmethod
    def _collect_files(dir_path):
        files = []
        try:
            for root, _dirs, filenames in os.walk(dir_path):
                for f in filenames:
                    files.append(os.path.join(root, f))
        except Exception:
            pass
        return files

    def run(self):
        files = self._collect_files(self.dir_path)
        if not files:
            self.result_ready.emit([], "")
            return

        self.progress.emit(f"共 {len(files)} 个文件，检测占用中...")
        if len(files) > self.MAX_FILES:
            files = files[:self.MAX_FILES]
            self.progress.emit(f"文件过多，只检测前 {self.MAX_FILES} 个样本...")

        import ctypes
        from ctypes import wintypes

        rstrtmgr = ctypes.windll.rstrtmgr
        CCH_RM_SESSION_KEY = 32
        CCH_RM_MAX_APP_NAME = 255
        CCH_RM_MAX_SVC_NAME = 63

        class RM_UNIQUE_PROCESS(ctypes.Structure):
            _fields_ = [
                ("dwProcessId", wintypes.DWORD),
                ("ProcessStartTime", wintypes.FILETIME),
            ]

        class RM_PROCESS_INFO(ctypes.Structure):
            _fields_ = [
                ("Process", RM_UNIQUE_PROCESS),
                ("strAppName", wintypes.WCHAR * (CCH_RM_MAX_APP_NAME + 1)),
                ("strServiceShortName", wintypes.WCHAR * (CCH_RM_MAX_SVC_NAME + 1)),
                ("ApplicationType", ctypes.c_int),
                ("AppStatus", wintypes.ULONG),
                ("TSSessionId", wintypes.DWORD),
                ("bRestartable", wintypes.BOOL),
            ]

        ERROR_MORE_DATA = 234
        RM_SESSION_HANDLE = wintypes.DWORD
        RM_REBOOT_REASON = wintypes.DWORD

        session_handle = RM_SESSION_HANDLE()
        session_key = (wintypes.WCHAR * (CCH_RM_SESSION_KEY + 1))()
        ret = rstrtmgr.RmStartSession(ctypes.byref(session_handle), 0, session_key)
        if ret != 0:
            self.result_ready.emit(None, f"启动检测会话失败 (错误码: {ret})")
            return

        try:
            file_arr = (wintypes.LPCWSTR * len(files))()
            for i, fp in enumerate(files):
                file_arr[i] = os.path.abspath(fp)

            ret = rstrtmgr.RmRegisterResources(
                session_handle,
                len(files),
                ctypes.cast(file_arr, ctypes.POINTER(wintypes.LPCWSTR)),
                0, None, 0, None
            )
            if ret != 0:
                self.result_ready.emit(None, f"注册资源失败 (错误码: {ret})")
                return

            proc_needed = wintypes.UINT()
            proc_count = wintypes.UINT(0)
            reboot_reasons = RM_REBOOT_REASON()

            ret = rstrtmgr.RmGetList(
                session_handle,
                ctypes.byref(proc_needed),
                ctypes.byref(proc_count),
                None,
                ctypes.byref(reboot_reasons)
            )

            if ret == ERROR_MORE_DATA:
                proc_count = proc_needed
            elif ret == 259:
                pass
            elif ret != 0:
                self.result_ready.emit(None, f"查询进程列表失败 (错误码: {ret})")
                return

            if proc_count.value == 0:
                self.result_ready.emit([], "")
                return

            proc_info = (RM_PROCESS_INFO * proc_count.value)()
            ret = rstrtmgr.RmGetList(
                session_handle,
                ctypes.byref(proc_needed),
                ctypes.byref(proc_count),
                proc_info,
                ctypes.byref(reboot_reasons)
            )
            if ret != 0 and ret != 259:
                self.result_ready.emit(None, f"获取进程信息失败 (错误码: {ret})")
                return

            results = []
            seen = set()
            for i in range(proc_count.value):
                pi = proc_info[i]
                pid = pi.Process.dwProcessId
                if pid not in seen:
                    seen.add(pid)
                    results.append({
                        "pid": pid,
                        "name": pi.strAppName,
                        "type": pi.ApplicationType,
                        "restartable": bool(pi.bRestartable),
                    })
            self.result_ready.emit(results, "")
        finally:
            rstrtmgr.RmEndSession(session_handle)


class FileOrganizerApp(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("文件整理工具")
        self.setMinimumSize(900, 700)
        self.file_plan = []
        self.rules = []
        self.organize_thread = None
        self.category_dir_names = dict(DEFAULT_DIR_NAMES)
        self._init_ui()
        self._apply_style()
        self._load_config()

    def _apply_style(self):
        self.setStyleSheet("""
            QMainWindow { background: #f5f7fa; }
            QTabWidget::pane {
                border: 1px solid #d0d7de;
                border-radius: 4px;
                background: #f5f7fa;
            }
            QTabBar::tab {
                background: #e9ecef;
                border: 1px solid #d0d7de;
                border-bottom: none;
                border-top-left-radius: 6px;
                border-top-right-radius: 6px;
                padding: 8px 28px;
                font-size: 14px;
                font-weight: bold;
                margin-right: 2px;
            }
            QTabBar::tab:selected {
                background: #ffffff;
                color: #2c3e50;
            }
            QTabBar::tab:!selected {
                color: #888;
            }
            QTabBar::tab:hover:!selected {
                background: #dbe4ec;
            }
            QGroupBox {
                font-weight: bold;
                border: 1px solid #d0d7de;
                border-radius: 6px;
                margin-top: 10px;
                padding-top: 14px;
                background: #ffffff;
            }
            QGroupBox::title {
                subcontrol-origin: margin;
                left: 12px;
                padding: 0 6px;
            }
            QPushButton {
                background: #4a90d9;
                color: white;
                border: none;
                border-radius: 4px;
                padding: 6px 16px;
                font-size: 13px;
            }
            QPushButton:hover { background: #357abd; }
            QPushButton:pressed { background: #2a6099; }
            QPushButton:disabled { background: #b0c4de; }
            QPushButton#btnScan { background: #27ae60; }
            QPushButton#btnScan:hover { background: #219a52; }
            QPushButton#btnRemoveRule { background: #e74c3c; padding: 4px 10px; }
            QPushButton#btnRemoveRule:hover { background: #c0392b; }
            QLineEdit {
                border: 1px solid #d0d7de;
                border-radius: 4px;
                padding: 5px 8px;
                background: #ffffff;
            }
            QTreeWidget {
                border: 1px solid #d0d7de;
                border-radius: 4px;
                background: #ffffff;
                alternate-background-color: #f0f4f8;
            }
            QProgressBar {
                border: 1px solid #d0d7de;
                border-radius: 4px;
                text-align: center;
                background: #e9ecef;
            }
            QProgressBar::chunk {
                background: qlineargradient(x1:0, y1:0, x2:1, y2:0,
                    stop:0 #4a90d9, stop:1 #27ae60);
                border-radius: 3px;
            }
            QTextEdit {
                border: 1px solid #d0d7de;
                border-radius: 4px;
                background: #ffffff;
                font-family: Consolas, monospace;
                font-size: 12px;
            }
            QListWidget {
                border: 1px solid #d0d7de;
                border-radius: 4px;
                background: #ffffff;
            }
            QTableWidget {
                border: 1px solid #d0d7de;
                border-radius: 4px;
                background: #ffffff;
            }
            QRadioButton { font-size: 13px; }
            QLabel { font-size: 13px; }
        """)

    def _init_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        root_layout = QVBoxLayout(central)
        root_layout.setContentsMargins(10, 10, 10, 10)

        tabs = QTabWidget()

        # ════════════════════════════════════════════
        #  Tab 1: 扫描与整理
        # ════════════════════════════════════════════
        tab_main = QWidget()
        tab_main_layout = QVBoxLayout(tab_main)
        tab_main_layout.setSpacing(8)

        # ── 目录选择 + 选项 ──
        top_row = QHBoxLayout()
        top_row.addWidget(QLabel("目标目录:"))
        self.dir_input = QLineEdit()
        self.dir_input.setPlaceholderText("选择要整理的文件夹...")
        top_row.addWidget(self.dir_input)
        btn_browse = QPushButton("浏览")
        btn_browse.clicked.connect(self._browse_dir)
        top_row.addWidget(btn_browse)

        top_row.addSpacing(16)
        top_row.addWidget(QLabel("执行方式:"))
        self.rb_move = QRadioButton("移动")
        self.rb_copy = QRadioButton("复制")
        self.rb_move.setChecked(True)
        mode_group = QButtonGroup()
        mode_group.addButton(self.rb_move)
        mode_group.addButton(self.rb_copy)
        top_row.addWidget(self.rb_move)
        top_row.addWidget(self.rb_copy)

        top_row.addSpacing(16)
        self.cb_recursive = QCheckBox("递归子目录")
        self.cb_recursive.setToolTip("勾选后会进入所有子文件夹扫描")
        top_row.addWidget(self.cb_recursive)

        btn_scan = QPushButton("扫描文件")
        btn_scan.setObjectName("btnScan")
        btn_scan.clicked.connect(self._scan_files)
        top_row.addWidget(btn_scan)
        tab_main_layout.addLayout(top_row)

        # ── 树形预览 ──
        self.tree = QTreeWidget()
        self.tree.setHeaderLabels(["名称", "大小", "分类目录"])
        self.tree.header().setSectionResizeMode(0, QHeaderView.Stretch)
        self.tree.header().setSectionResizeMode(1, QHeaderView.ResizeToContents)
        self.tree.header().setSectionResizeMode(2, QHeaderView.ResizeToContents)
        self.tree.setAlternatingRowColors(True)
        self.tree.setAnimated(True)
        self.tree.setSelectionMode(QAbstractItemView.NoSelection)
        tab_main_layout.addWidget(self.tree, stretch=1)

        self.lbl_file_count = QLabel("共 0 个文件")
        tab_main_layout.addWidget(self.lbl_file_count)

        # ── 进度条 ──
        progress_row = QHBoxLayout()
        self.progress_bar = QProgressBar()
        self.progress_bar.setValue(0)
        progress_row.addWidget(self.progress_bar)
        self.lbl_status = QLabel("")
        self.lbl_status.setMinimumWidth(200)
        progress_row.addWidget(self.lbl_status)

        self.btn_organize = QPushButton("开始整理")
        self.btn_organize.clicked.connect(self._organize)
        progress_row.addWidget(self.btn_organize)
        self.btn_stop = QPushButton("停止")
        self.btn_stop.setEnabled(False)
        self.btn_stop.setStyleSheet("QPushButton { background: #e67e22; } QPushButton:hover { background: #d35400; }")
        self.btn_stop.clicked.connect(self._stop_organize)
        progress_row.addWidget(self.btn_stop)
        tab_main_layout.addLayout(progress_row)

        # ── 日志 ──
        self.log_text = QTextEdit()
        self.log_text.setReadOnly(True)
        self.log_text.setMaximumHeight(100)
        tab_main_layout.addWidget(self.log_text)

        tabs.addTab(tab_main, "扫描与整理")

        # ════════════════════════════════════════════
        #  Tab 2: 文件删除
        # ════════════════════════════════════════════
        tab_delete = QWidget()
        tab_delete_layout = QVBoxLayout(tab_delete)
        tab_delete_layout.setSpacing(8)

        # ── 目录选择 ──
        del_dir_row = QHBoxLayout()
        del_dir_row.addWidget(QLabel("目标目录:"))
        self.del_dir_input = QLineEdit()
        self.del_dir_input.setPlaceholderText("选择要清理的文件夹...")
        del_dir_row.addWidget(self.del_dir_input)
        btn_del_browse = QPushButton("浏览")
        btn_del_browse.clicked.connect(self._browse_del_dir)
        del_dir_row.addWidget(btn_del_browse)
        btn_del_scan = QPushButton("扫描")
        btn_del_scan.setObjectName("btnScan")
        btn_del_scan.clicked.connect(self._scan_for_delete)
        del_dir_row.addWidget(btn_del_scan)
        tab_delete_layout.addLayout(del_dir_row)

        # ── 树形文件列表（带勾选框）──
        self.del_tree = QTreeWidget()
        self.del_tree.setHeaderLabels(["选择", "名称", "大小", "类型"])
        self.del_tree.header().setSectionResizeMode(0, QHeaderView.ResizeToContents)
        self.del_tree.header().setSectionResizeMode(1, QHeaderView.Stretch)
        self.del_tree.header().setSectionResizeMode(2, QHeaderView.ResizeToContents)
        self.del_tree.header().setSectionResizeMode(3, QHeaderView.ResizeToContents)
        self.del_tree.setAlternatingRowColors(True)
        self.del_tree.setAnimated(True)
        self.del_tree.itemExpanded.connect(self._on_del_tree_item_expanded)
        self.del_tree.setContextMenuPolicy(Qt.CustomContextMenu)
        self.del_tree.customContextMenuRequested.connect(self._on_del_tree_context_menu)
        tab_delete_layout.addWidget(self.del_tree, stretch=1)

        self.lbl_del_count = QLabel("共 0 项")
        tab_delete_layout.addWidget(self.lbl_del_count)

        # ── 文件夹大小计算进度条 ──
        del_progress_row = QHBoxLayout()
        self.del_progress_label = QLabel("")
        del_progress_row.addWidget(self.del_progress_label)
        self.del_progress_bar = QProgressBar()
        self.del_progress_bar.setValue(0)
        self.del_progress_bar.setMaximumHeight(18)
        del_progress_row.addWidget(self.del_progress_bar)
        tab_delete_layout.addLayout(del_progress_row)

        # ── 排序按钮 ──
        sort_row = QHBoxLayout()
        sort_row.addWidget(QLabel("排序:"))
        btn_sort_desc = QPushButton("大小 从大到小")
        btn_sort_desc.clicked.connect(lambda: self._sort_del_tree(reverse=True))
        sort_row.addWidget(btn_sort_desc)
        btn_sort_asc = QPushButton("大小 从小到大")
        btn_sort_asc.clicked.connect(lambda: self._sort_del_tree(reverse=False))
        sort_row.addWidget(btn_sort_asc)
        btn_sort_name = QPushButton("按名称")
        btn_sort_name.clicked.connect(lambda: self._sort_del_tree(by_name=True))
        sort_row.addWidget(btn_sort_name)
        sort_row.addStretch()
        tab_delete_layout.addLayout(sort_row)

        # ── 操作按钮 ──
        del_btn_row = QHBoxLayout()
        btn_sel_all = QPushButton("全选")
        btn_sel_all.clicked.connect(lambda: self._set_all_del_checked(True))
        del_btn_row.addWidget(btn_sel_all)
        btn_desel_all = QPushButton("全不选")
        btn_desel_all.clicked.connect(lambda: self._set_all_del_checked(False))
        del_btn_row.addWidget(btn_desel_all)
        btn_sel_files = QPushButton("仅选文件")
        btn_sel_files.clicked.connect(self._select_files_only)
        del_btn_row.addWidget(btn_sel_files)
        btn_sel_dirs = QPushButton("仅选文件夹")
        btn_sel_dirs.clicked.connect(self._select_dirs_only)
        del_btn_row.addWidget(btn_sel_dirs)
        del_btn_row.addStretch()
        btn_delete_selected = QPushButton("删除选中")
        btn_delete_selected.setObjectName("btnRemoveRule")
        btn_delete_selected.setStyleSheet(
            "QPushButton { background: #e74c3c; padding: 8px 24px; font-weight: bold; }"
            "QPushButton:hover { background: #c0392b; }")
        btn_delete_selected.clicked.connect(self._delete_selected)
        del_btn_row.addWidget(btn_delete_selected)
        tab_delete_layout.addLayout(del_btn_row)

        # ── 删除日志 ──
        self.del_log = QTextEdit()
        self.del_log.setReadOnly(True)
        self.del_log.setMaximumHeight(100)
        tab_delete_layout.addWidget(self.del_log)

        tabs.addTab(tab_delete, "文件删除")

        # ════════════════════════════════════════════
        #  Tab 2: 分类设置
        # ════════════════════════════════════════════
        tab_settings = QWidget()
        tab_settings_layout = QVBoxLayout(tab_settings)
        tab_settings_layout.setSpacing(10)

        # ── 分类目录名 ──
        dir_name_group = QGroupBox("归档目录名（双击右侧列可修改）")
        dir_name_layout = QVBoxLayout(dir_name_group)
        cats = list(self.category_dir_names.keys())
        self.dir_name_table = QTableWidget(len(cats), 2)
        self.dir_name_table.setHorizontalHeaderLabels(["分类", "归档目录名"])
        self.dir_name_table.horizontalHeader().setSectionResizeMode(0, QHeaderView.ResizeToContents)
        self.dir_name_table.horizontalHeader().setSectionResizeMode(1, QHeaderView.Stretch)
        self.dir_name_table.verticalHeader().setVisible(False)
        self.dir_name_table.setMaximumHeight(250)
        for row, cat in enumerate(cats):
            cat_item = QTableWidgetItem(cat)
            cat_item.setFlags(cat_item.flags() & ~Qt.ItemIsEditable)
            cat_item.setForeground(QColor("#555"))
            self.dir_name_table.setItem(row, 0, cat_item)
            dir_item = QTableWidgetItem(self.category_dir_names[cat])
            self.dir_name_table.setItem(row, 1, dir_item)
        self.dir_name_table.cellChanged.connect(self._on_dir_name_changed)
        dir_name_layout.addWidget(self.dir_name_table)
        tab_settings_layout.addWidget(dir_name_group)

        # ── 自定义归档分类 + 内容规则（左右并排）──
        settings_splitter = QSplitter(Qt.Horizontal)

        # 左：自定义归档分类
        cat_group = QGroupBox("自定义归档分类（按扩展名）")
        cat_layout = QVBoxLayout(cat_group)
        cat_input_row = QHBoxLayout()
        self.input_ext = QLineEdit()
        self.input_ext.setPlaceholderText("扩展名，逗号分隔 (如: psd,ai)")
        cat_input_row.addWidget(self.input_ext)
        self.input_cat_name = QLineEdit()
        self.input_cat_name.setPlaceholderText("分类名 (如: 设计文件)")
        cat_input_row.addWidget(self.input_cat_name)
        btn_add_cat = QPushButton("+ 添加")
        btn_add_cat.clicked.connect(lambda: self._add_custom_category())
        cat_input_row.addWidget(btn_add_cat)
        cat_layout.addLayout(cat_input_row)
        self.custom_cat_list = QListWidget()
        cat_layout.addWidget(self.custom_cat_list)
        btn_remove_cat = QPushButton("删除选中分类")
        btn_remove_cat.setObjectName("btnRemoveRule")
        btn_remove_cat.clicked.connect(self._remove_custom_category)
        cat_layout.addWidget(btn_remove_cat)
        settings_splitter.addWidget(cat_group)

        # 右：自定义内容规则
        rule_group = QGroupBox("自定义内容规则（按文件内容）")
        rule_layout = QVBoxLayout(rule_group)
        rule_input_row = QHBoxLayout()
        self.input_keyword = QLineEdit()
        self.input_keyword.setPlaceholderText("关键词 / 正则表达式")
        rule_input_row.addWidget(self.input_keyword)
        self.input_target = QLineEdit()
        self.input_target.setPlaceholderText("目标目录名")
        rule_input_row.addWidget(self.input_target)
        self.cb_regex = QRadioButton("正则")
        self.cb_regex.setToolTip("勾选后将关键词作为正则表达式匹配")
        rule_input_row.addWidget(self.cb_regex)
        btn_add_rule = QPushButton("+ 添加")
        btn_add_rule.clicked.connect(self._add_rule)
        rule_input_row.addWidget(btn_add_rule)
        rule_layout.addLayout(rule_input_row)
        tip = QLabel("提示: 内容规则优先级高于类型分类，仅对文本类文件生效")
        tip.setStyleSheet("color: #888; font-size: 11px;")
        rule_layout.addWidget(tip)
        self.rule_list = QListWidget()
        rule_layout.addWidget(self.rule_list)
        btn_remove_rule = QPushButton("删除选中规则")
        btn_remove_rule.setObjectName("btnRemoveRule")
        btn_remove_rule.clicked.connect(self._remove_rule)
        rule_layout.addWidget(btn_remove_rule)
        settings_splitter.addWidget(rule_group)

        settings_splitter.setSizes([400, 400])
        tab_settings_layout.addWidget(settings_splitter, stretch=1)
        tabs.addTab(tab_settings, "分类设置")

        root_layout.addWidget(tabs)

    def _browse_dir(self):
        d = QFileDialog.getExistingDirectory(self, "选择要整理的目录")
        if d:
            self.dir_input.setText(d)

    # ── 配置持久化 ──
    def _load_config(self):
        if not os.path.exists(CONFIG_PATH):
            return
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                cfg = json.load(f)
        except Exception:
            return

        # 恢复目录名映射
        for cat, name in cfg.get("dir_names", {}).items():
            self.category_dir_names[cat] = name

        # 恢复自定义分类
        for cat_name, exts in cfg.get("custom_categories", {}).items():
            ext_set = set(exts)
            # 从已有分类中移除这些扩展名
            for existing_cat, existing_exts in CATEGORY_MAP.items():
                if existing_cat != cat_name:
                    existing_exts -= ext_set
            CATEGORY_MAP[cat_name] = ext_set
            if cat_name not in self.category_dir_names:
                self.category_dir_names[cat_name] = cat_name
            self.custom_cat_list.addItem(f"\"{cat_name}\": {', '.join(sorted(exts))}")

        # 恢复内容规则
        for r in cfg.get("rules", []):
            self.rules.append(r)
            tag = "[正则] " if r.get("regex") else ""
            self.rule_list.addItem(f"{tag}\"{r['keyword']}\" → {r['target']}")

        # 刷新目录名表格
        self._refresh_dir_name_table()
        self._log(f"已加载配置: {CONFIG_PATH}")

    def _save_config(self):
        cfg = {
            "dir_names": self.category_dir_names,
            "custom_categories": {},
            "rules": self.rules,
        }
        # 只保存非内置的自定义分类
        default_cats = set(DEFAULT_DIR_NAMES.keys())
        for cat, exts in CATEGORY_MAP.items():
            if cat not in default_cats:
                cfg["custom_categories"][cat] = sorted(exts)
        try:
            with open(CONFIG_PATH, "w", encoding="utf-8") as f:
                json.dump(cfg, f, ensure_ascii=False, indent=2)
        except Exception as e:
            self._log(f"保存配置失败: {e}")

    def closeEvent(self, event):
        self._save_config()
        super().closeEvent(event)

    # ── 文件删除功能 ──
    def _browse_del_dir(self):
        d = QFileDialog.getExistingDirectory(self, "选择要清理的目录")
        if d:
            self.del_dir_input.setText(d)
            self._scan_for_delete()

    def _scan_for_delete(self):
        base_dir = self.del_dir_input.text().strip()
        if not base_dir or not os.path.isdir(base_dir):
            QMessageBox.warning(self, "提示", "请选择有效的目录")
            return

        # 停止之前的大小计算线程
        if hasattr(self, '_size_thread') and self._size_thread and self._size_thread.isRunning():
            self._size_thread.terminate()
            self._size_thread.wait()

        self.del_tree.blockSignals(True)
        self.del_tree.clear()
        self._del_path_map = {}

        scan_dirs = [base_dir] + get_desktop_extra_dirs(base_dir)
        if len(scan_dirs) > 1:
            self.del_log.append(f"已合并公共桌面，共扫描 {len(scan_dirs)} 个目录")

        total_count = 0
        for scan_dir in scan_dirs:
            try:
                for entry in os.scandir(scan_dir):
                    try:
                        if entry.is_dir(follow_symlinks=False):
                            item = QTreeWidgetItem()
                            item.setCheckState(0, Qt.Unchecked)
                            self._del_path_map[id(item)] = entry.path
                            item.setText(1, entry.name)
                            item.setText(2, "计算中...")
                            item.setText(3, "文件夹")
                            item.setForeground(1, QColor("#2c3e50"))
                            placeholder = QTreeWidgetItem(["", "加载中...", "", ""])
                            item.addChild(placeholder)
                            item.setData(0, Qt.UserRole, "unloaded")
                            self.del_tree.addTopLevelItem(item)
                        else:
                            size = entry.stat().st_size
                            item = QTreeWidgetItem()
                            item.setCheckState(0, Qt.Unchecked)
                            self._del_path_map[id(item)] = entry.path
                            item.setText(1, entry.name)
                            item.setText(2, format_size(size))
                            item.setText(3, "文件")
                            self.del_tree.addTopLevelItem(item)
                        total_count += 1
                    except OSError:
                        continue
            except OSError:
                pass

        self.del_tree.blockSignals(False)
        self.lbl_del_count.setText(f"共 {total_count} 项")
        self.del_log.append(f"扫描完成: {base_dir}，共 {total_count} 项，正在计算文件夹大小...")
        self._start_size_calc()

    def _on_del_tree_item_expanded(self, item):
        state = item.data(0, Qt.UserRole)
        if state == "unloaded":
            self._load_dir_children(item)

    def _on_del_tree_context_menu(self, pos):
        item = self.del_tree.itemAt(pos)
        if not item:
            return
        path = self._del_path_map.get(id(item))
        from PyQt5.QtWidgets import QMenu, QAction
        menu = QMenu(self)

        if path and os.path.isdir(path):
            act_open = QAction("打开文件夹", self)
            act_open.triggered.connect(lambda: os.startfile(path))
            menu.addAction(act_open)

            act_refresh = QAction("刷新", self)
            act_refresh.triggered.connect(lambda: self._refresh_dir_item(item))
            menu.addAction(act_refresh)

            act_lock = QAction("检测占用进程...", self)
            act_lock.triggered.connect(lambda: self._check_and_close_locks(item))
            menu.addAction(act_lock)

            if item.childCount() > 1:
                menu.addSeparator()
                act_asc = QAction("子项升序", self)
                act_asc.triggered.connect(lambda: self._sort_tree_items(item, False, False))
                menu.addAction(act_asc)
                act_desc = QAction("子项降序", self)
                act_desc.triggered.connect(lambda: self._sort_tree_items(item, True, False))
                menu.addAction(act_desc)

        if not menu.isEmpty():
            menu.exec_(self.del_tree.viewport().mapToGlobal(pos))

    def _refresh_dir_item(self, item):
        """刷新指定目录项的内容"""
        path = self._del_path_map.get(id(item))
        if not path or not os.path.isdir(path):
            return

        # 顶层目录直接全量刷新
        if item.parent() is None:
            self._scan_for_delete()
            return

        # 递归清理旧子项的路径映射
        def _clean_paths(parent):
            for i in range(parent.childCount()):
                child = parent.child(i)
                self._del_path_map.pop(id(child), None)
                _clean_paths(child)

        _clean_paths(item)

        was_expanded = item.isExpanded()
        while item.childCount() > 0:
            item.takeChild(0)

        if was_expanded:
            self._load_dir_children(item)
            item.setExpanded(True)
        else:
            item.setData(0, Qt.UserRole, "unloaded")
            placeholder = QTreeWidgetItem(["", "加载中...", "", ""])
            item.addChild(placeholder)

    def _load_dir_children(self, parent_item):
        """懒加载文件夹子内容"""
        parent_item.setData(0, Qt.UserRole, "loaded")
        while parent_item.childCount() > 0:
            parent_item.takeChild(0)

        dir_path = self._del_path_map.get(id(parent_item))
        if not dir_path:
            return

        sub_dirs = []
        self.del_tree.blockSignals(True)
        try:
            for entry in os.scandir(dir_path):
                try:
                    if entry.is_dir(follow_symlinks=False):
                        child = QTreeWidgetItem()
                        child.setCheckState(0, Qt.Unchecked)
                        self._del_path_map[id(child)] = entry.path
                        child.setText(1, entry.name)
                        child.setText(2, "计算中...")
                        child.setText(3, "文件夹")
                        child.setForeground(1, QColor("#2c3e50"))
                        placeholder = QTreeWidgetItem(["", "加载中...", "", ""])
                        child.addChild(placeholder)
                        child.setData(0, Qt.UserRole, "unloaded")
                        parent_item.addChild(child)
                        sub_dirs.append((child, entry.path))
                    else:
                        size = entry.stat().st_size
                        child = QTreeWidgetItem()
                        child.setCheckState(0, Qt.Unchecked)
                        self._del_path_map[id(child)] = entry.path
                        child.setText(1, entry.name)
                        child.setText(2, format_size(size))
                        child.setText(3, "文件")
                        parent_item.addChild(child)
                except OSError:
                    continue
        except OSError:
            pass
        self.del_tree.blockSignals(False)

        # 后台计算子文件夹大小（小的先算）
        if sub_dirs:
            # 快速估算并排序
            estimated = []
            for child_item, child_path in sub_dirs:
                est = 0
                try:
                    for entry in os.scandir(child_path):
                        try:
                            if not entry.is_dir(follow_symlinks=False):
                                est += entry.stat().st_size
                        except OSError:
                            pass
                except OSError:
                    pass
                estimated.append((est, child_item, child_path))
            estimated.sort(key=lambda x: x[0])
            sorted_items = [(item, path) for _, item, path in estimated]
            self._child_size_thread = SizeCalcThread(sorted_items)
            self._child_size_thread.result_ready.connect(self._on_size_result)
            self._child_size_thread.progress.connect(self._on_child_size_progress)
            self._child_size_thread.all_done.connect(lambda: self.del_progress_label.setText(""))
            self.del_progress_label.setText(f"计算子文件夹大小: 0/{len(sorted_items)}")
            self.del_progress_bar.setRange(0, len(sorted_items))
            self.del_progress_bar.setValue(0)
            self._child_size_thread.start()

    def _start_size_calc(self):
        """启动后台线程计算顶层文件夹大小（小的先算）"""
        items_to_calc = []
        for i in range(self.del_tree.topLevelItemCount()):
            item = self.del_tree.topLevelItem(i)
            if item.text(3) == "文件夹":
                path = self._del_path_map.get(id(item))
                if path:
                    # 快速估算：只统计直接子文件大小
                    est = 0
                    try:
                        for entry in os.scandir(path):
                            try:
                                if not entry.is_dir(follow_symlinks=False):
                                    est += entry.stat().st_size
                            except OSError:
                                pass
                    except OSError:
                        pass
                    items_to_calc.append((est, item, path))
        if not items_to_calc:
            self.del_progress_bar.setValue(100)
            self.del_progress_label.setText("")
            return
        # 按估算大小从小到大排序，小的先算，进度条前期跑得快
        items_to_calc.sort(key=lambda x: x[0])
        sorted_items = [(item, path) for _, item, path in items_to_calc]
        self._size_thread = SizeCalcThread(sorted_items)
        self._size_thread.result_ready.connect(self._on_size_result)
        self._size_thread.progress.connect(self._on_top_size_progress)
        self._size_thread.all_done.connect(self._on_size_all_done)
        self._size_thread.scanning.connect(self._on_scanning_update)
        self.del_progress_label.setText(f"计算文件夹大小: 0/{len(sorted_items)}")
        self.del_progress_bar.setRange(0, len(sorted_items))
        self.del_progress_bar.setValue(0)
        self._size_thread.start()

        # 启动定时器，每 5 秒刷新一次扫描状态
        self._latest_scan_path = ""
        self._latest_file_count = 0
        self._scan_start_time = time.time()
        self._scan_refresh_timer = QTimer()
        self._scan_refresh_timer.timeout.connect(self._refresh_scan_status)
        self._scan_refresh_timer.start(5000)

    def _on_scanning_update(self, path, file_count):
        self._latest_scan_path = path
        self._latest_file_count = file_count

    def _refresh_scan_status(self):
        fc = self._latest_file_count
        elapsed = int(time.time() - self._scan_start_time)
        mins, secs = divmod(elapsed, 60)
        time_str = f"{mins}分{secs:02d}秒" if mins > 0 else f"{secs}秒"
        path = self._latest_scan_path
        if path:
            tail = path[-60:] if len(path) > 60 else path
            self.del_log.append(f"  已扫描 {fc:,} 个文件 | 耗时 {time_str} | {tail}")
        elif fc > 0:
            self.del_log.append(f"  已扫描 {fc:,} 个文件 | 耗时 {time_str}")
        self._latest_scan_path = ""

    def _on_size_result(self, item, size_text):
        if item and id(item) in self._del_path_map:
            item.setText(2, size_text)

    def _on_top_size_progress(self, current, total):
        self.del_progress_bar.setValue(current)
        self.del_progress_label.setText(f"计算文件夹大小: {current}/{total}")

    def _on_child_size_progress(self, current, total):
        self.del_progress_bar.setValue(current)
        self.del_progress_label.setText(f"计算子文件夹大小: {current}/{total}")

    def _on_size_all_done(self):
        self.del_progress_bar.setValue(self.del_progress_bar.maximum())
        if hasattr(self, '_scan_start_time'):
            elapsed = int(time.time() - self._scan_start_time)
            mins, secs = divmod(elapsed, 60)
            time_str = f"{mins}分{secs:02d}秒" if mins > 0 else f"{secs}秒"
        else:
            time_str = ""
        fc = getattr(self, '_latest_file_count', 0)
        self.del_progress_label.setText("计算完成")
        self.del_log.append(f"文件夹大小计算完成 | 共 {fc:,} 个文件 | 耗时 {time_str}")
        if hasattr(self, '_scan_refresh_timer') and self._scan_refresh_timer.isActive():
            self._scan_refresh_timer.stop()

    @staticmethod
    def _size_to_bytes(size_text):
        """将大小文本转回字节数，用于排序比较"""
        if not size_text or size_text in ("计算中...", "?", ""):
            return -1
        text = size_text.rstrip("+")
        try:
            parts = text.split()
            num = float(parts[0])
            unit = parts[1] if len(parts) > 1 else "B"
            multipliers = {"B": 1, "KB": 1024, "MB": 1024**2, "GB": 1024**3, "TB": 1024**4}
            return int(num * multipliers.get(unit, 1))
        except (ValueError, IndexError):
            return -1

    def _sort_tree_items(self, parent, reverse, by_name):
        """对指定父节点下的子项排序，并递归排序已展开的子目录"""
        count = parent.childCount() if parent else self.del_tree.topLevelItemCount()
        if count < 2:
            return
        items = []
        expanded_map = {}

        def _record_expanded(it):
            if it.text(3) == "文件夹":
                expanded_map[id(it)] = it.isExpanded()
                for c in range(it.childCount()):
                    _record_expanded(it.child(c))

        for _ in range(count):
            if parent:
                item = parent.takeChild(0)
            else:
                item = self.del_tree.takeTopLevelItem(0)
            _record_expanded(item)
            items.append(item)

        if by_name:
            items.sort(key=lambda item: item.text(1).lower())
        else:
            items.sort(key=lambda item: self._size_to_bytes(item.text(2)), reverse=reverse)

        for item in items:
            if parent:
                parent.addChild(item)
            else:
                self.del_tree.addTopLevelItem(item)

        def _restore_expanded(it):
            if id(it) in expanded_map:
                it.setExpanded(expanded_map[id(it)])
            for c in range(it.childCount()):
                _restore_expanded(it.child(c))

        for item in items:
            _restore_expanded(item)
            # 如果该文件夹已展开（子项已加载），递归排序子项
            if item.text(3) == "文件夹" and item.data(0, Qt.UserRole) == "loaded":
                self._sort_tree_items(item, reverse, by_name)

    def _sort_del_tree(self, reverse=True, by_name=False):
        self._sort_tree_items(None, reverse, by_name)

    def _iter_all_items(self, parent=None):
        """递归遍历所有树节点（包括子节点）"""
        items = []
        count = self.del_tree.topLevelItemCount() if parent is None else parent.childCount()
        for i in range(count):
            item = self.del_tree.topLevelItem(i) if parent is None else parent.child(i)
            items.append(item)
            items.extend(self._iter_all_items(item))
        return items

    def _set_all_del_checked(self, checked):
        self.del_tree.blockSignals(True)
        state = Qt.Checked if checked else Qt.Unchecked
        for item in self._iter_all_items():
            item.setCheckState(0, state)
        self.del_tree.blockSignals(False)

    def _select_files_only(self):
        self.del_tree.blockSignals(True)
        for item in self._iter_all_items():
            item.setCheckState(0, Qt.Checked if item.text(3) == "文件" else Qt.Unchecked)
        self.del_tree.blockSignals(False)

    def _select_dirs_only(self):
        self.del_tree.blockSignals(True)
        for item in self._iter_all_items():
            item.setCheckState(0, Qt.Checked if item.text(3) == "文件夹" else Qt.Unchecked)
        self.del_tree.blockSignals(False)

    # ── 占用检测与关闭 ──
    def _show_lock_dialog(self, processes):
        """显示占用进程选择对话框，返回用户选中的进程列表"""
        dialog = QDialog(self)
        dialog.setWindowTitle("检测到文件占用")
        dialog.setMinimumSize(500, 320)
        layout = QVBoxLayout(dialog)

        layout.addWidget(QLabel("以下进程正在占用该目录中的文件，导致无法删除："))

        table = QTableWidget(len(processes), 3)
        table.setHorizontalHeaderLabels(["", "进程名称", "PID"])
        table.horizontalHeader().setSectionResizeMode(1, QHeaderView.Stretch)
        table.horizontalHeader().setSectionResizeMode(2, QHeaderView.ResizeToContents)
        table.horizontalHeader().setSectionResizeMode(0, QHeaderView.ResizeToContents)
        table.setSelectionMode(QAbstractItemView.NoSelection)

        checkboxes = []
        for row, proc in enumerate(processes):
            cb = QCheckBox()
            cb.setChecked(True)
            checkboxes.append(cb)
            table.setCellWidget(row, 0, cb)
            table.setItem(row, 1, QTableWidgetItem(proc["name"] or "未知"))
            table.setItem(row, 2, QTableWidgetItem(str(proc["pid"])))
        layout.addWidget(table)

        tip = QLabel("提示：关闭进程可能导致未保存的数据丢失，请先保存工作。")
        tip.setStyleSheet("color: #c0392b; font-size: 11px;")
        layout.addWidget(tip)

        btn_layout = QHBoxLayout()
        btn_layout.addStretch()
        btn_close = QPushButton("关闭选中进程")
        btn_close.clicked.connect(dialog.accept)
        btn_layout.addWidget(btn_close)
        btn_cancel = QPushButton("取消")
        btn_cancel.clicked.connect(dialog.reject)
        btn_layout.addWidget(btn_cancel)
        layout.addLayout(btn_layout)

        if dialog.exec_() != QDialog.Accepted:
            return []
        return [processes[i] for i, cb in enumerate(checkboxes) if cb.isChecked()]

    def _check_and_close_locks(self, parent_item):
        """检测并关闭占用指定目录的进程"""
        path = self._del_path_map.get(id(parent_item))
        if not path or not os.path.isdir(path):
            return

        self.del_log.append(f"正在扫描占用: {os.path.basename(path)} ...")
        self._lock_detect_thread = LockDetectThread(path)
        self._lock_detect_thread.progress.connect(self._on_lock_detect_progress)
        self._lock_detect_thread.result_ready.connect(self._on_lock_detect_done)
        self._lock_detect_thread.start()

    def _on_lock_detect_progress(self, msg):
        self.del_log.append(msg)

    def _on_lock_detect_done(self, processes, err):
        if err:
            QMessageBox.warning(self, "检测失败", err)
            return
        if not processes:
            QMessageBox.information(self, "提示", "未检测到占用进程，可以直接删除")
            return

        selected = self._show_lock_dialog(processes)
        if not selected:
            self.del_log.append("用户取消关闭进程")
            return

        closed = 0
        failed = []
        for proc in selected:
            try:
                result = subprocess.run(
                    ["taskkill", "/F", "/PID", str(proc["pid"])],
                    capture_output=True, text=True
                )
                if result.returncode == 0:
                    closed += 1
                else:
                    err_msg = result.stderr.strip() or "未知错误"
                    failed.append(f"{proc['name']} (PID {proc['pid']}): {err_msg}")
            except Exception as e:
                failed.append(f"{proc['name']} (PID {proc['pid']}): {e}")

        msg = f"已请求关闭 {closed}/{len(selected)} 个进程"
        if failed:
            msg += f"\n\n失败 {len(failed)} 个:\n" + "\n".join(failed[:10])
        self.del_log.append(msg)
        QMessageBox.information(self, "关闭结果", msg)

    def _delete_selected(self):
        selected = []
        for item in self._iter_all_items():
            if item.checkState(0) == Qt.Checked:
                path = self._del_path_map.get(id(item))
                if path and os.path.exists(path):
                    selected.append((item, path))

        if not selected:
            QMessageBox.information(self, "提示", "请勾选要删除的项")
            return

        file_count = sum(1 for _, p in selected if os.path.isfile(p))
        dir_count = sum(1 for _, p in selected if os.path.isdir(p))
        msg = f"确认删除 {file_count} 个文件、{dir_count} 个文件夹？\n\n此操作不可恢复！"
        reply = QMessageBox.warning(self, "确认删除", msg,
                                    QMessageBox.Yes | QMessageBox.No, QMessageBox.No)
        if reply != QMessageBox.Yes:
            return

        success = 0
        errors = []
        for item, path in selected:
            name = os.path.basename(path)
            try:
                if os.path.isdir(path):
                    shutil.rmtree(path)
                    self.del_log.append(f"已删除文件夹: {name}")
                else:
                    os.remove(path)
                    self.del_log.append(f"已删除文件: {name}")
                success += 1
            except Exception as e:
                errors.append(f"{name}: {e}")
                self.del_log.append(f"删除失败: {name} - {e}")

        remaining = self.del_tree.topLevelItemCount()
        self.lbl_del_count.setText(f"剩余 {remaining} 项")
        result = f"删除完成: 成功 {success}/{len(selected)}"
        if errors:
            result += f"，失败 {len(errors)} 项"
        self.del_log.append(result)
        QMessageBox.information(self, "删除完成", result)
        # 重新扫描刷新列表
        self._scan_for_delete()

    def _log(self, msg):
        self.log_text.append(msg)
        self.log_text.verticalScrollBar().setValue(self.log_text.verticalScrollBar().maximum())

    # ── 目录名修改回调 ──
    def _on_dir_name_changed(self, row, col):
        if col != 1:
            return
        categories = list(self.category_dir_names.keys())
        if row < len(categories):
            new_name = self.dir_name_table.item(row, 1).text().strip()
            if new_name:
                cat = categories[row]
                self.category_dir_names[cat] = new_name
                self._log(f"目录名修改: {cat} → {new_name}")

    # ── 自定义归档分类（按扩展名）──
    def _add_custom_category(self, ext_text=None, cat_name=None, silent=False):
        if ext_text is None:
            ext_text = self.input_ext.text().strip().lower()
        if cat_name is None:
            cat_name = self.input_cat_name.text().strip()
        if not ext_text or not cat_name:
            if not silent:
                QMessageBox.warning(self, "提示", "请填写扩展名和分类名")
            return
        exts = {e.strip().lstrip(".") for e in ext_text.split(",") if e.strip()}
        if not exts:
            if not silent:
                QMessageBox.warning(self, "提示", "请输入有效的扩展名")
            return
        # 从已有分类中移除这些扩展名，避免重复匹配
        for existing_cat, existing_exts in CATEGORY_MAP.items():
            if existing_cat != cat_name:
                existing_exts -= exts
        CATEGORY_MAP[cat_name] = exts
        self.category_dir_names[cat_name] = cat_name
        if not silent:
            self.custom_cat_list.addItem(f"\"{cat_name}\": {', '.join(sorted(exts))}")
        self._refresh_dir_name_table()
        if not silent:
            self.input_ext.clear()
            self.input_cat_name.clear()
            self._log(f"添加分类: \"{cat_name}\" → {', '.join(sorted(exts))}")

    def _remove_custom_category(self):
        row = self.custom_cat_list.currentRow()
        if row >= 0:
            self.custom_cat_list.takeItem(row)
            # 找到对应的自定义分类（从 CATEGORY_MAP 中排除默认内置的）
            default_cats = set(DEFAULT_DIR_NAMES.keys())
            custom_cats = [c for c in CATEGORY_MAP if c not in default_cats]
            if row < len(custom_cats):
                removed_cat = custom_cats[row]
                CATEGORY_MAP.pop(removed_cat, None)
                self.category_dir_names.pop(removed_cat, None)
                self._refresh_dir_name_table()
                self._log(f"删除分类: \"{removed_cat}\"")
        else:
            QMessageBox.information(self, "提示", "请先选中要删除的自定义分类")

    def _refresh_dir_name_table(self):
        self.dir_name_table.cellChanged.disconnect(self._on_dir_name_changed)
        cats = list(self.category_dir_names.keys())
        self.dir_name_table.setRowCount(len(cats))
        for row, cat in enumerate(cats):
            cat_item = QTableWidgetItem(cat)
            cat_item.setFlags(cat_item.flags() & ~Qt.ItemIsEditable)
            cat_item.setForeground(QColor("#555"))
            self.dir_name_table.setItem(row, 0, cat_item)
            dir_item = QTableWidgetItem(self.category_dir_names[cat])
            self.dir_name_table.setItem(row, 1, dir_item)
        self.dir_name_table.cellChanged.connect(self._on_dir_name_changed)

    # ── 自定义规则管理 ──
    def _add_rule(self):
        keyword = self.input_keyword.text().strip()
        target = self.input_target.text().strip()
        if not keyword or not target:
            QMessageBox.warning(self, "提示", "请填写关键词和目标目录名")
            return
        use_regex = self.cb_regex.isChecked()
        rule = {"keyword": keyword, "target": target, "regex": use_regex}
        self.rules.append(rule)
        tag = "[正则] " if use_regex else ""
        self.rule_list.addItem(f"{tag}\"{keyword}\" → {target}")
        self.input_keyword.clear()
        self.input_target.clear()
        self._log(f"添加规则: {tag}\"{keyword}\" → {target}")

    def _remove_rule(self):
        row = self.rule_list.currentRow()
        if row >= 0:
            self.rule_list.takeItem(row)
            removed = self.rules.pop(row)
            self._log(f"删除规则: \"{removed['keyword']}\" → {removed['target']}")
        else:
            QMessageBox.information(self, "提示", "请先选中要删除的规则")

    # ── 扫描文件 ──
    def _scan_files(self):
        base_dir = self.dir_input.text().strip()
        if not base_dir or not os.path.isdir(base_dir):
            QMessageBox.warning(self, "提示", "请选择有效的目录")
            return

        self.tree.clear()
        self.file_plan = []
        self._log(f"开始扫描目录: {base_dir}")

        # 检测是否需要合并公共桌面
        scan_dirs = [base_dir] + get_desktop_extra_dirs(base_dir)

        # 收集所有文件
        files = []
        recursive = self.cb_recursive.isChecked()
        for scan_dir in scan_dirs:
            if not recursive:
                for f in os.listdir(scan_dir):
                    full = os.path.join(scan_dir, f)
                    if os.path.isfile(full):
                        files.append(full)
            else:
                for root, dirs, filenames in os.walk(scan_dir):
                    # 跳过分类目录本身（避免重复整理）
                    dirs[:] = [d for d in dirs if d not in CATEGORY_MAP and d != "其他"
                               and not any(r["target"] == d for r in self.rules)]
                    for f in filenames:
                        full = os.path.join(root, f)
                        if os.path.isfile(full):
                            files.append(full)

        if len(scan_dirs) > 1:
            self._log(f"已合并公共桌面，共扫描 {len(scan_dirs)} 个目录")

        # 分类
        category_items = {}
        for filepath in files:
            fname = os.path.basename(filepath)
            category = None
            # 先检查内容规则
            for rule in self.rules:
                if file_contains_keyword(filepath, rule["keyword"], rule["regex"]):
                    category = rule["target"]
                    break
            if category is None:
                category = get_category(fname)
            # 使用用户自定义的目录名
            target_dir = self.category_dir_names.get(category, category)
            if target_dir not in category_items:
                category_items[target_dir] = []
            category_items[target_dir].append(filepath)
            self.file_plan.append((filepath, target_dir))

        # 填充树形控件
        _base_colors = {
            "Office文档": QColor("#2b579a"), "文档": QColor("#3498db"),
            "图片": QColor("#e67e22"), "视频": QColor("#9b59b6"),
            "音频": QColor("#1abc9c"), "压缩包": QColor("#f39c12"),
            "代码": QColor("#2ecc71"), "程序": QColor("#e74c3c"),
            "快捷方式": QColor("#8e44ad"), "其他": QColor("#95a5a6"),
        }
        # 为自定义分类生成固定颜色
        _palette = [QColor(c) for c in
                    ["#e91e63", "#00bcd4", "#ff9800", "#795548", "#607d8b", "#8bc34a"]]
        _custom_idx = 0
        for cat in self.category_dir_names:
            if cat not in _base_colors:
                _base_colors[cat] = _palette[_custom_idx % len(_palette)]
                _custom_idx += 1
        # 反向映射: 用户自定义目录名 -> 原始类别
        _dir_to_cat = {v: k for k, v in self.category_dir_names.items()}
        for cat in sorted(category_items.keys()):
            items = category_items[cat]
            total_size = sum(os.path.getsize(f) for f in items)
            cat_item = QTreeWidgetItem([f"📁 {cat} ({len(items)}个)", format_size(total_size), ""])
            base_cat = _dir_to_cat.get(cat, cat)
            color = _base_colors.get(base_cat, QColor("#95a5a6"))
            for col in range(3):
                cat_item.setForeground(col, color)
            font = cat_item.font(0)
            font.setBold(True)
            cat_item.setFont(0, font)
            for filepath in items:
                fname = os.path.basename(filepath)
                fsize = os.path.getsize(filepath)
                rel_path = os.path.relpath(filepath, base_dir)
                child = QTreeWidgetItem([f"    {fname}", format_size(fsize), cat])
                child.setForeground(2, color)
                cat_item.addChild(child)
            self.tree.addTopLevelItem(cat_item)
            cat_item.setExpanded(True)

        self.lbl_file_count.setText(f"共 {len(files)} 个文件，分为 {len(category_items)} 类")
        self._log(f"扫描完成: 共 {len(files)} 个文件，分为 {len(category_items)} 类")

    # ── 执行整理 ──
    def _organize(self):
        if not self.file_plan:
            QMessageBox.warning(self, "提示", "请先扫描文件")
            return
        mode = "move" if self.rb_move.isChecked() else "copy"
        base_dir = self.dir_input.text().strip()
        mode_text = "移动" if mode == "move" else "复制"
        reply = QMessageBox.question(
            self, "确认整理",
            f"即将{mode_text} {len(self.file_plan)} 个文件\n"
            f"目标目录: {base_dir}\n\n确认执行？",
            QMessageBox.Yes | QMessageBox.No
        )
        if reply != QMessageBox.Yes:
            return

        self._log(f"开始整理 ({mode_text}): {len(self.file_plan)} 个文件")
        self.btn_organize.setEnabled(False)
        self.btn_stop.setEnabled(True)
        self.progress_bar.setValue(0)

        self.organize_thread = OrganizeThread(self.file_plan, base_dir, mode)
        self.organize_thread.progress.connect(self._on_progress)
        self.organize_thread.finished_signal.connect(self._on_finished)
        self.organize_thread.start()

    def _stop_organize(self):
        if self.organize_thread and self.organize_thread.isRunning():
            self.organize_thread.stop()
            self._log("用户请求停止，等待当前文件处理完成...")

    def _on_progress(self, current, total, filename):
        pct = int(current / total * 100)
        self.progress_bar.setValue(pct)
        self.lbl_status.setText(f"{current}/{total} - {filename}")

    def _on_finished(self, success, total, msg):
        self.progress_bar.setValue(100 if success == total else int(success / total * 100))
        self.btn_organize.setEnabled(True)
        self.btn_stop.setEnabled(False)
        self._log(msg)
        QMessageBox.information(self, "整理完成", msg)
        # 重新扫描刷新预览
        self._scan_files()


def main():
    app = QApplication(sys.argv)
    app.setFont(QFont("Microsoft YaHei", 10))
    window = FileOrganizerApp()
    window.show()
    sys.exit(app.exec_())


if __name__ == "__main__":
    main()

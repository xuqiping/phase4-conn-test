import sys
import os
import re
import time
import subprocess
from datetime import datetime

from PyQt5.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QTabWidget, QTextEdit, QPushButton, QLabel, QLineEdit, QGroupBox,
    QMessageBox, QSplitter, QFrame
)
from PyQt5.QtCore import Qt, QThread, pyqtSignal, QObject
from PyQt5.QtGui import QTextCursor, QFont, QColor

from playwright.sync_api import sync_playwright, TimeoutError as PlaywrightTimeoutError


# ============================================================
# 浏览器检测与启动
# ============================================================
def get_app_dir():
    """获取程序根目录（打包后为 exe 所在目录，开发时为脚本所在目录）"""
    if getattr(sys, 'frozen', False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))


def detect_browsers():
    """
    检测本机已安装的 Chrome 和 Edge 浏览器路径
    返回: [{'name': 'Chrome', 'path': 'C:\\...'}, ...]
    """
    possible_paths = [
        ("Chrome", [
            os.path.join(os.environ.get('PROGRAMFILES', ''), 'Google', 'Chrome', 'Application', 'chrome.exe'),
            os.path.join(os.environ.get('PROGRAMFILES(X86)', ''), 'Google', 'Chrome', 'Application', 'chrome.exe'),
            os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Google', 'Chrome', 'Application', 'chrome.exe'),
        ]),
        ("Edge", [
            os.path.join(os.environ.get('PROGRAMFILES', ''), 'Microsoft', 'Edge', 'Application', 'msedge.exe'),
            os.path.join(os.environ.get('PROGRAMFILES(X86)', ''), 'Microsoft', 'Edge', 'Application', 'msedge.exe'),
            os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Microsoft', 'Edge', 'Application', 'msedge.exe'),
        ]),
    ]

    found = []
    for name, paths in possible_paths:
        for path in paths:
            if path and os.path.isfile(path):
                found.append({'name': name, 'path': path})
                break

    return found


def launch_browser(browser_path, port, browser_name=None):
    """
    以指定端口启动浏览器（使用独立数据目录）
    browser_path: 浏览器可执行文件路径
    port: CDP 调试端口
    browser_name: 浏览器名称，用于生成独立数据目录（Chrome/Edge 分开存）

    关键：必须指定 --user-data-dir 使用独立数据目录
    原因：Chrome/Edge 默认用系统数据目录，若已有实例运行（含后台残留进程），
    新启动的调试实例会被旧进程接管，--remote-debugging-port 参数失效。
    使用独立目录后，调试实例独立运行，不影响用户其他浏览器窗口，
    且登录状态可持久化保存（下次启动免登录）。
    """
    # 生成独立数据目录（按浏览器类型分开，Chrome/Edge 互不干扰）
    if browser_name:
        user_data_dir = os.path.join(get_app_dir(), 'browser_data', browser_name.lower())
    else:
        user_data_dir = os.path.join(get_app_dir(), 'browser_data', 'default')

    # 确保目录存在
    try:
        os.makedirs(user_data_dir, exist_ok=True)
    except Exception:
        pass

    cmd = [
        browser_path,
        f'--remote-debugging-port={port}',
        '--remote-debugging-address=0.0.0.0',  # 同时监听 IPv4 和 IPv6
        f'--user-data-dir={user_data_dir}',     # 关键：独立数据目录
        '--no-first-run',
        '--no-default-browser-check',
    ]

    try:
        proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return proc
    except Exception:
        return None


# ============================================================
# 日志重定向：将 print 输出发送到 GUI
# ============================================================
class StreamRedirector(QObject):
    text_written = pyqtSignal(str)

    def write(self, text):
        if text and text.strip():
            self.text_written.emit(text)

    def flush(self):
        pass


# ============================================================
# 工作线程
# ============================================================

class IntercomWorker(QThread):
    """门禁对讲配置工作线程"""
    finished_signal = pyqtSignal(str)

    def __init__(self, port):
        super().__init__()
        self.port = port

    def run(self):
        with sync_playwright() as p:
            try:
                browser = p.chromium.connect_over_cdp(f"http://127.0.0.1:{self.port}")
                print("✓ 成功连接到浏览器！")
            except Exception as e:
                print(f"✗ 连接失败: {e}")
                return

            try:
                page = browser.contexts[0].pages[0]
                print("✓ 使用浏览器中已有的页面")
            except:
                page = browser.new_page(viewport={'width': 1920, 'height': 1080})
                print("✓ 创建新页面")

            try:
                url = "https://w.shequ.189.cn/manage/?dateTime=1780189829086#/communityManage/gate/intercomConfiguration/index"
                print(f"\n正在访问网址: {url}")
                page.goto(url, wait_until='networkidle', timeout=30000)
                time.sleep(3)

                print("点击搜索按钮...")
                try:
                    page.evaluate('''() => {
                        const buttons = Array.from(document.querySelectorAll('button'));
                        const searchBtn = buttons.find(btn => btn.innerText.trim() === '搜索');
                        if (searchBtn) searchBtn.click();
                    }''')
                    time.sleep(2)
                    print("✓ 已点击搜索按钮")
                except Exception as e:
                    print(f"搜索按钮点击失败: {e}")

                # 获取总页数
                total_pages = 1
                try:
                    pagination = page.locator('.el-pagination').first
                    page_numbers = pagination.locator('li.number').all()
                    total_pages = len(page_numbers)
                    print(f"✓ 共 {total_pages} 页")
                except:
                    print("未检测到分页，默认1页")

                success_count = 0
                total_devices = 0
                skipped_count = 0

                for current_page_num in range(1, total_pages + 1):
                    if self.isInterruptionRequested():
                        print("\n⚠ 任务已停止")
                        return

                    print(f"\n{'='*60}")
                    print(f"正在处理第 {current_page_num}/{total_pages} 页")
                    print(f"{'='*60}")

                    if current_page_num > 1:
                        try:
                            pagination = page.locator('.el-pagination').first
                            page_numbers = pagination.locator('li.number').all()
                            if current_page_num - 1 < len(page_numbers):
                                page_numbers[current_page_num - 1].click()
                                time.sleep(2)
                                print(f"✓ 已翻到第 {current_page_num} 页")
                        except Exception as e:
                            print(f"翻页失败: {e}")
                            continue

                    try:
                        tables = page.locator('table').all()
                        if len(tables) < 3:
                            print("✗ 未找到表格")
                            continue

                        main_table = tables[2]
                        rows = main_table.locator('tbody tr').all()
                        device_count_on_page = len(rows)
                        total_devices += device_count_on_page
                        print(f"✓ 当前页有 {device_count_on_page} 个设备")

                        for row_idx in range(device_count_on_page):
                            if self.isInterruptionRequested():
                                print("\n⚠ 任务已停止")
                                return

                            tables = page.locator('table').all()
                            if len(tables) < 3:
                                continue

                            main_table = tables[2]
                            rows = main_table.locator('tbody tr').all()
                            if row_idx >= len(rows):
                                continue

                            row = rows[row_idx]
                            try:
                                cells = row.locator('td').all()
                                if len(cells) < 5:
                                    continue
                                device_sn = cells[0].inner_text().strip()
                                device_name = cells[4].inner_text().strip()
                            except:
                                device_name = f"设备{row_idx + 1}"
                                device_sn = ""

                            print(f"\n[{row_idx + 1}/{device_count_on_page}] 正在处理: {device_name} ({device_sn})")

                            # 检查按钮状态
                            button_available = False
                            intercom_element = None
                            try:
                                if len(tables) >= 5:
                                    op_table = tables[4]
                                    op_rows = op_table.locator('tbody tr').all()
                                    if row_idx < len(op_rows):
                                        op_row = op_rows[row_idx]
                                        all_elements = op_row.locator('*').all()
                                        for el in all_elements:
                                            if el.inner_text().strip() == '对讲配置':
                                                class_name = el.get_attribute('class') or ''
                                                if 'forbidden' in class_name:
                                                    print(f"   ⚠ {device_name} 的对讲配置按钮为禁用状态，跳过")
                                                    skipped_count += 1
                                                else:
                                                    intercom_element = el
                                                    button_available = True
                                                break
                            except Exception as e:
                                print(f"   检查按钮状态时出错: {e}")

                            if not button_available:
                                continue

                            try:
                                print(f"1. 点击 {device_name} 的对讲配置按钮...")
                                intercom_element.click()
                                print(f"   ✓ 已点击对讲配置按钮")
                                time.sleep(2)

                                print("2. 点击批量新增按钮...")
                                batch_add_button = page.locator('button', has_text='批量新增').first
                                batch_add_button.click()
                                print("   ✓ 已点击批量新增按钮")
                                time.sleep(2)

                                print("3. 点击全部选择按钮...")
                                dialog_found = False
                                dialogs = page.locator('.ant-modal-wrap, .el-dialog').all()
                                for dialog in dialogs:
                                    if '批量新增' in dialog.inner_text():
                                        dialog_found = True
                                        dialog.locator('text=全部选择').first.click()
                                        print("   ✓ 已点击全部选择按钮")
                                        break
                                if not dialog_found:
                                    page.locator('text=全部选择').first.click()
                                    print("   ✓ 已点击全部选择按钮")
                                time.sleep(1)

                                print("4. 点击确定按钮...")
                                confirm_found = False
                                dialogs = page.locator('.ant-modal-wrap, .el-dialog').all()
                                for dialog in dialogs:
                                    if '批量新增' in dialog.inner_text():
                                        confirm_found = True
                                        for btn in dialog.locator('button').all():
                                            if btn.inner_text().strip() == '确定':
                                                btn.click()
                                                print("   ✓ 已点击确定按钮")
                                                break
                                        break
                                if not confirm_found:
                                    page.locator('button', has_text='确定').first.click()
                                    print("   ✓ 已点击确定按钮")
                                time.sleep(2)

                                print(f"✓ {device_name} 配置完成！")
                                success_count += 1

                            except Exception as e:
                                print(f"✗ 处理 {device_name} 时出错: {e}")

                            page.goto(url, wait_until='networkidle', timeout=30000)
                            time.sleep(2)

                            if current_page_num > 1:
                                try:
                                    pagination = page.locator('.el-pagination').first
                                    page_numbers = pagination.locator('li.number').all()
                                    if current_page_num - 1 < len(page_numbers):
                                        page_numbers[current_page_num - 1].click()
                                        time.sleep(2)
                                except:
                                    pass

                    except Exception as e:
                        print(f"处理第 {current_page_num} 页时出错: {e}")

                print(f"\n{'='*60}")
                print(f"批量配置完成！")
                print(f"成功: {success_count}/{total_devices}，跳过(禁用): {skipped_count}")
                print(f"{'='*60}")

            except PlaywrightTimeoutError:
                print("\n错误: 页面加载超时")
            except Exception as e:
                print(f"\n发生错误: {e}")
                import traceback
                traceback.print_exc()
            finally:
                print("程序结束")

        self.finished_signal.emit("门禁对讲配置任务完成")


class ReviewWorker(QThread):
    """居民审核自动审核工作线程"""
    finished_signal = pyqtSignal(str)

    def __init__(self, port):
        super().__init__()
        self.port = port

    def run(self):
        with sync_playwright() as p:
            try:
                browser = p.chromium.connect_over_cdp(f"http://127.0.0.1:{self.port}")
                print("✓ 成功连接到浏览器！")
            except Exception as e:
                print(f"✗ 连接失败: {e}")
                return

            try:
                page = browser.contexts[0].pages[0]
                print("✓ 使用浏览器中已有的页面")
            except:
                page = browser.new_page(viewport={'width': 1920, 'height': 1080})
                print("✓ 创建新页面")

            try:
                url = "https://w.shequ.189.cn/manage/?dateTime=1780189829086#/propertyManage/review-registration/review-registration/index"
                print(f"\n正在访问网址: {url}")

                if page.url != url:
                    page.goto(url, wait_until='networkidle', timeout=30000)
                else:
                    print("✓ 已经在目标页面")

                page.wait_for_selector('.el-select', timeout=15000)
                time.sleep(2)

                print(f"\n{'='*60}")
                print("开始自动审核循环")
                print(f"{'='*60}")

                review_count = 0
                cycle_count = 0

                while not self.isInterruptionRequested():
                    cycle_count += 1
                    current_time = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                    print(f"\n[{current_time}] 第 {cycle_count} 次检查...")

                    try:
                        print("  点击状态下拉框...")
                        status_select = page.locator('.el-select > .el-input').first
                        status_select.click()
                        time.sleep(1)

                        print("  选择'未审核'选项...")
                        unreviewed_option = page.get_by_role('listitem').filter(has_text='未审核')
                        unreviewed_option.click()
                        time.sleep(1)

                        print("  点击搜索按钮...")
                        search_button = page.get_by_role('button', name='搜索')
                        search_button.click()
                        time.sleep(3)

                        print("  检查是否有未审核记录...")
                        table_rows = page.locator('table tbody tr')
                        row_count = table_rows.count()

                        if row_count > 0:
                            print(f"  发现 {row_count} 条未审核记录，开始审核...")

                            print("  点击全选复选框...")
                            try:
                                select_all_checkbox = page.locator('.el-table__fixed-header-wrapper').first.locator('.el-checkbox__input').first
                                select_all_checkbox.click()
                                print("  已选中全部记录！")
                                time.sleep(1)
                            except Exception as e:
                                print(f"  点击全选复选框时出错: {e}")

                            print("  点击批量审核按钮...")
                            try:
                                batch_review_button = page.get_by_role('button', name='批量审核')
                                if not batch_review_button.is_disabled():
                                    batch_review_button.click()
                                    print("  已点击批量审核按钮！")
                                    time.sleep(2)

                                    print("  确认批量审核...")
                                    try:
                                        page.wait_for_selector('text=批量审核', timeout=5000)
                                        time.sleep(1)
                                        confirm_button = page.get_by_role('button', name='确定')
                                        confirm_button.click()
                                        print("  已确认批量审核！")
                                        time.sleep(3)
                                        review_count += row_count
                                        print(f"  成功审核 {row_count} 条记录！累计审核: {review_count} 条")
                                    except Exception as e:
                                        print(f"  确认审核时出错: {e}")
                                else:
                                    print("  批量审核按钮不可用，跳过本次审核")
                            except Exception as e:
                                print(f"  点击批量审核按钮时出错: {e}")
                        else:
                            print("  当前没有未审核记录，跳过本次审核")

                    except Exception as e:
                        print(f"  本次检查出错: {e}")

                    print("  等待5秒后继续检查...")
                    # 分段等待，以便更快响应停止请求
                    for _ in range(50):
                        if self.isInterruptionRequested():
                            break
                        time.sleep(0.1)

                print(f"\n{'='*60}")
                print(f"自动审核已停止，共审核 {review_count} 条记录")
                print(f"{'='*60}")

            except PlaywrightTimeoutError:
                print("\n错误: 页面加载超时")
            except Exception as e:
                print(f"\n发生错误: {e}")
                import traceback
                traceback.print_exc()
            finally:
                print("程序结束")

        self.finished_signal.emit("居民审核任务完成")


class DoorPermissionWorker(QThread):
    """门禁权限下发工作线程"""
    finished_signal = pyqtSignal(str)

    def __init__(self, port):
        super().__init__()
        self.port = port

    def run(self):
        config_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), '门禁权限下放配置.txt')
        print(f"读取配置文件: {config_path}")

        if not os.path.exists(config_path):
            print(f"✗ 配置文件不存在: {config_path}")
            return

        config = self._parse_config(config_path)
        if not config:
            print("✗ 配置文件内容为空或格式不正确")
            return

        print("配置内容:")
        for item in config:
            print(f"  {item['building']}栋: {', '.join(item['units'])}单元")

        with sync_playwright() as p:
            try:
                browser = p.chromium.connect_over_cdp(f"http://127.0.0.1:{self.port}")
                print("✓ 成功连接到浏览器！")
            except Exception as e:
                print(f"✗ 连接失败: {e}")
                return

            try:
                page = browser.contexts[0].pages[0]
                print("✓ 使用浏览器中已有的页面")
            except:
                page = browser.new_page(viewport={'width': 1920, 'height': 1080})
                print("✓ 创建新页面")

            try:
                url = "https://w.shequ.189.cn/manage/?dateTime=1780538346690#/baseData/residentManage/index"
                print(f"\n正在访问网址: {url}")

                if page.url != url:
                    page.goto(url, wait_until='networkidle', timeout=30000)
                else:
                    print("✓ 已经在目标页面")

                time.sleep(3)

                print(f"\n{'='*60}")
                print("开始批量下发门禁权限")
                print(f"{'='*60}")

                success_count = 0
                total_count = sum(len(item['units']) for item in config)

                for item in config:
                    if self.isInterruptionRequested():
                        print("\n⚠ 任务已停止")
                        return

                    building = item['building']
                    for unit in item['units']:
                        if self.isInterruptionRequested():
                            print("\n⚠ 任务已停止")
                            return

                        print(f"\n{'='*60}")
                        print(f"正在处理: {building}栋 {unit}单元")
                        print(f"{'='*60}")

                        try:
                            # 步骤1: 选择楼栋
                            print(f"1. 选择楼栋: {building}")
                            self._select_dropdown(page, 0, building)
                            time.sleep(1)

                            # 步骤2: 选择单元
                            print(f"2. 选择单元: {unit}")
                            self._select_dropdown(page, 1, unit)
                            time.sleep(1)

                            # 步骤3: 搜索
                            print("3. 点击搜索...")
                            self._click_search(page)
                            time.sleep(2)

                            # 步骤4: 全选
                            print("4. 点击全选复选框...")
                            self._click_select_all(page)
                            time.sleep(1)

                            # 步骤5: 门禁权限下发/撤回
                            print("5. 点击门禁权限下发/撤回...")
                            page.evaluate('''() => {
                                const btn = Array.from(document.querySelectorAll('button'))
                                    .find(b => b.innerText.trim().includes('门禁权限下发'));
                                if (btn) btn.click();
                            }''')
                            time.sleep(2)

                            # 步骤6: 选中对应设备
                            device_name = f"{building}幢{unit}单元"
                            print(f"6. 选中 {device_name}...")
                            page.evaluate(f'''() => {{
                                const dialog = Array.from(document.querySelectorAll('.el-dialog, .ant-modal-wrap'))
                                    .find(d => d.innerText.includes('门禁权限下发'));
                                if (!dialog) return;
                                const table = dialog.querySelectorAll('table')[1];
                                if (!table) return;
                                const row = Array.from(table.querySelectorAll('tr')).find(r => {{
                                    const cells = Array.from(r.querySelectorAll('td'));
                                    return cells.some(c => c.innerText.trim() === '{device_name}');
                                }});
                                if (row) {{
                                    const cb = row.querySelector('.el-checkbox__input');
                                    if (cb) cb.click();
                                }}
                            }}''')
                            time.sleep(1)

                            # 步骤7: 下发
                            print("7. 点击下发...")
                            page.evaluate('''() => {
                                const dialog = Array.from(document.querySelectorAll('.el-dialog, .ant-modal-wrap'))
                                    .find(d => d.innerText.includes('门禁权限下发'));
                                if (!dialog) return;
                                const btn = Array.from(dialog.querySelectorAll('button'))
                                    .find(b => b.innerText.trim() === '下发');
                                if (btn) btn.click();
                            }''')
                            time.sleep(1)

                            # 步骤8: 确定
                            print("8. 点击确定...")
                            page.evaluate('''() => {
                                const btns = Array.from(document.querySelectorAll('button'))
                                    .filter(b => b.innerText.trim() === '确定');
                                if (btns.length > 0) btns[btns.length - 1].click();
                            }''')
                            time.sleep(2)

                            success_count += 1
                            print(f"✓ {building}栋{unit}单元 完成！({success_count}/{total_count})")

                        except Exception as e:
                            print(f"✗ 处理 {building}栋{unit}单元 时出错: {e}")

                print(f"\n{'='*60}")
                print(f"批量下发完成！成功: {success_count}/{total_count}")
                print(f"{'='*60}")

            except PlaywrightTimeoutError:
                print("\n错误: 页面加载超时")
            except Exception as e:
                print(f"\n发生错误: {e}")
                import traceback
                traceback.print_exc()
            finally:
                print("程序结束")

        self.finished_signal.emit("门禁权限下发任务完成")

    @staticmethod
    def _parse_config(config_path):
        result = []
        with open(config_path, 'r', encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith('[') or line.startswith('#'):
                    continue
                match = re.split(r'[栋幢]', line, maxsplit=1)
                if len(match) != 2:
                    continue
                building = match[0].strip()
                unit_part = re.split(r'[：:]', match[1], maxsplit=1)[-1]
                units = [u.strip().replace('单元', '') for u in re.split(r'[，,、]', unit_part)]
                units = [u for u in units if u]
                if building and units:
                    result.append({'building': building, 'units': units})
        return result

    @staticmethod
    def _select_dropdown(page, select_index, option_text):
        page.evaluate(f'''() => {{
            const selects = Array.from(document.querySelectorAll('.el-select'));
            const sel = selects[{select_index}];
            if (sel) sel.querySelector('input').click();
        }}''')
        time.sleep(1)
        page.evaluate(f'''() => {{
            const dropdown = document.querySelector('.el-select-dropdown:not([style*="display: none"])');
            if (!dropdown) return;
            const opt = Array.from(dropdown.querySelectorAll('li'))
                .find(o => o.innerText.trim() === '{option_text}');
            if (opt) opt.click();
        }}''')
        time.sleep(0.5)

    @staticmethod
    def _click_search(page):
        page.evaluate('''() => {
            const btn = Array.from(document.querySelectorAll('button'))
                .find(b => b.innerText.trim() === '搜索');
            if (btn) btn.click();
        }''')
        time.sleep(2)

    @staticmethod
    def _click_select_all(page):
        page.evaluate('''() => {
            const tables = Array.from(document.querySelectorAll('table'));
            const target = tables[3] || tables[1];
            const cb = target.querySelectorAll('th')[0].querySelector('.el-checkbox__input');
            if (cb) cb.click();
        }''')
        time.sleep(1)


# ============================================================
# 主窗口
# ============================================================

class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.current_worker = None
        self.redirector = StreamRedirector()
        self.redirector.text_written.connect(self._append_log)
        self._original_stdout = sys.stdout
        self._original_stderr = sys.stderr

        self.setWindowTitle("天翼智慧社区 - 自动化工具")
        self.setMinimumSize(900, 700)
        self.resize(1000, 750)

        self._init_ui()
        self._load_config_preview()

    def _init_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        layout = QVBoxLayout(central)
        layout.setSpacing(8)

        # ---- 顶部：浏览器连接区域 ----
        conn_group = QGroupBox("浏览器连接")
        conn_layout = QVBoxLayout(conn_group)

        # 第一行：端口 + 连接测试 + 状态
        row1 = QHBoxLayout()

        row1.addWidget(QLabel("CDP端口:"))
        self.port_input = QLineEdit("9333")
        self.port_input.setFixedWidth(80)
        row1.addWidget(self.port_input)

        self.connect_btn = QPushButton("连接测试")
        self.connect_btn.setFixedWidth(100)
        self.connect_btn.clicked.connect(self._test_connection)
        row1.addWidget(self.connect_btn)

        self.status_label = QLabel("● 未连接")
        self.status_label.setStyleSheet("color: #e74c3c; font-weight: bold;")
        row1.addWidget(self.status_label)

        row1.addStretch()
        conn_layout.addLayout(row1)

        # 第二行：浏览器检测 + 启动
        row2 = QHBoxLayout()

        self.browser_info_label = QLabel("检测浏览器中...")
        self.browser_info_label.setStyleSheet("color: #555; font-size: 11px;")
        row2.addWidget(self.browser_info_label)

        row2.addStretch()

        self.launch_chrome_btn = QPushButton("🚀 启动 Chrome")
        self.launch_chrome_btn.setFixedHeight(30)
        self.launch_chrome_btn.setStyleSheet("""
            QPushButton {
                background-color: #4285f4; color: white; font-weight: bold;
                border-radius: 4px; padding: 0 14px; font-size: 12px;
            }
            QPushButton:hover { background-color: #5a95f5; }
            QPushButton:disabled { background-color: #bdc3c7; }
        """)
        self.launch_chrome_btn.clicked.connect(lambda: self._launch_browser('Chrome'))
        row2.addWidget(self.launch_chrome_btn)

        self.launch_edge_btn = QPushButton("🚀 启动 Edge")
        self.launch_edge_btn.setFixedHeight(30)
        self.launch_edge_btn.setStyleSheet("""
            QPushButton {
                background-color: #0078d4; color: white; font-weight: bold;
                border-radius: 4px; padding: 0 14px; font-size: 12px;
            }
            QPushButton:hover { background-color: #1a8ce6; }
            QPushButton:disabled { background-color: #bdc3c7; }
        """)
        self.launch_edge_btn.clicked.connect(lambda: self._launch_browser('Edge'))
        row2.addWidget(self.launch_edge_btn)

        conn_layout.addLayout(row2)

        # 初始检测浏览器
        self._detect_browsers()

        layout.addWidget(conn_group)

        # ---- 中部：Tab 切换区域 ----
        self.tabs = QTabWidget()
        layout.addWidget(self.tabs)

        # Tab 1: 门禁对讲配置
        self.intercom_tab = self._create_task_tab(
            title="门禁对讲配置",
            description="遍历门禁对讲配置列表，对所有可用的设备执行批量新增操作。\n"
                        "自动翻页处理，自动跳过禁用状态的按钮。",
            start_text="开始配置",
        )
        self.tabs.addTab(self.intercom_tab['widget'], "门禁对讲配置")

        # Tab 2: 居民审核自动审核
        self.review_tab = self._create_task_tab(
            title="居民审核自动审核",
            description="自动循环检查并审核未审核的居民登记（每5秒检查一次）。\n"
                        "点击「停止」按钮可随时终止。",
            start_text="开始审核",
        )
        self.tabs.addTab(self.review_tab['widget'], "居民审核自动审核")

        # Tab 3: 门禁权限下放
        perm_widget = QWidget()
        perm_layout = QVBoxLayout(perm_widget)

        # 配置文件预览区
        config_group = QGroupBox("配置文件预览（门禁权限下放配置.txt）")
        config_group_layout = QVBoxLayout(config_group)
        self.config_preview = QTextEdit()
        self.config_preview.setReadOnly(True)
        self.config_preview.setFixedHeight(80)
        self.config_preview.setFont(QFont("Consolas", 10))
        config_group_layout.addWidget(self.config_preview)
        perm_layout.addWidget(config_group)

        # 操作区
        perm_task = self._create_task_tab(
            title="门禁权限下放",
            description="根据配置文件中的楼栋和单元，逐个下发门禁权限。",
            start_text="开始下发",
        )
        for key in ['description', 'btn_layout', 'start_btn', 'stop_btn', 'log_area', 'widget']:
            if key == 'widget':
                perm_layout.addWidget(perm_task['widget'])
            elif key == 'description':
                perm_layout.addWidget(perm_task['description'])
            elif key == 'btn_layout':
                perm_layout.addLayout(perm_task['btn_layout'])

        self.perm_tab = {
            'start_btn': perm_task['start_btn'],
            'stop_btn': perm_task['stop_btn'],
            'log_area': perm_task['log_area'],
        }
        self.tabs.addTab(perm_widget, "门禁权限下放")

        layout.addWidget(self.tabs)

    def _create_task_tab(self, title, description, start_text):
        widget = QWidget()
        layout = QVBoxLayout(widget)

        # 功能说明
        desc_label = QLabel(description)
        desc_label.setWordWrap(True)
        desc_label.setStyleSheet("color: #555; padding: 6px; background: #f8f9fa; border-radius: 4px;")
        layout.addWidget(desc_label)

        # 按钮区域
        btn_layout = QHBoxLayout()
        start_btn = QPushButton(f"▶ {start_text}")
        start_btn.setFixedHeight(36)
        start_btn.setStyleSheet("""
            QPushButton {
                background-color: #27ae60; color: white; font-weight: bold;
                border-radius: 4px; padding: 0 20px;
            }
            QPushButton:hover { background-color: #2ecc71; }
            QPushButton:disabled { background-color: #bdc3c7; }
        """)

        stop_btn = QPushButton("■ 停止")
        stop_btn.setFixedHeight(36)
        stop_btn.setEnabled(False)
        stop_btn.setStyleSheet("""
            QPushButton {
                background-color: #e74c3c; color: white; font-weight: bold;
                border-radius: 4px; padding: 0 20px;
            }
            QPushButton:hover { background-color: #c0392b; }
            QPushButton:disabled { background-color: #bdc3c7; }
        """)

        btn_layout.addWidget(start_btn)
        btn_layout.addWidget(stop_btn)
        btn_layout.addStretch()
        layout.addLayout(btn_layout)

        # 日志区域
        log_area = QTextEdit()
        log_area.setReadOnly(True)
        log_area.setFont(QFont("Consolas", 9))
        log_area.setStyleSheet("""
            QTextEdit {
                background-color: #1e1e1e; color: #d4d4d4;
                border: 1px solid #ccc; border-radius: 4px;
            }
        """)
        layout.addWidget(log_area)

        # 绑定按钮事件
        tab_data = {
            'description': desc_label,
            'btn_layout': btn_layout,
            'start_btn': start_btn,
            'stop_btn': stop_btn,
            'log_area': log_area,
            'widget': widget,
        }

        start_btn.clicked.connect(lambda: self._start_task(title))
        stop_btn.clicked.connect(self._stop_task)

        return tab_data

    def _load_config_preview(self):
        config_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), '门禁权限下放配置.txt')
        if os.path.exists(config_path):
            with open(config_path, 'r', encoding='utf-8') as f:
                self.config_preview.setPlainText(f.read())
        else:
            self.config_preview.setPlainText("（未找到配置文件）")

    def _detect_browsers(self):
        """检测本机已安装的浏览器并更新按钮状态"""
        self._detected_browsers = detect_browsers()
        chrome_found = any(b['name'] == 'Chrome' for b in self._detected_browsers)
        edge_found = any(b['name'] == 'Edge' for b in self._detected_browsers)

        self.launch_chrome_btn.setEnabled(chrome_found)
        self.launch_edge_btn.setEnabled(edge_found)

        found_names = [b['name'] for b in self._detected_browsers]
        if found_names:
            self.browser_info_label.setText(f"已检测到浏览器: {', '.join(found_names)}")
        else:
            self.browser_info_label.setText("⚠ 未检测到 Chrome 或 Edge 浏览器")
            self.browser_info_label.setStyleSheet("color: #e74c3c; font-size: 11px;")

    def _launch_browser(self, browser_name):
        """启动指定浏览器"""
        browser_info = None
        for b in self._detected_browsers:
            if b['name'] == browser_name:
                browser_info = b
                break

        if not browser_info:
            QMessageBox.warning(self, "提示", f"未找到 {browser_name} 浏览器")
            return

        port = self.port_input.text().strip()

        # 提示用户正在启动
        self.status_label.setText(f"● 正在启动 {browser_name}...")
        self.status_label.setStyleSheet("color: #f39c12; font-weight: bold;")
        QApplication.processEvents()

        proc = launch_browser(browser_info['path'], port, browser_name)

        if not proc:
            QMessageBox.critical(self, "错误", f"启动 {browser_name} 失败")
            return

        # 等待端口就绪（最多等8秒）
        import socket
        ready = False
        for _ in range(16):
            time.sleep(0.5)
            try:
                sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                sock.settimeout(0.5)
                if sock.connect_ex(('127.0.0.1', int(port))) == 0:
                    ready = True
                    sock.close()
                    break
                sock.close()
            except Exception:
                pass

        if ready:
            self.status_label.setText(f"● {browser_name} 已就绪 (端口:{port})")
            self.status_label.setStyleSheet("color: #27ae60; font-weight: bold;")
            QMessageBox.information(
                self, "启动成功",
                f"✅ {browser_name} 已启动，调试端口 {port} 已就绪！\n\n"
                f"💡 使用独立数据目录，登录状态会自动保存，下次启动免登录。\n\n"
                f"请在浏览器中登录天翼智慧社区后，\n"
                f"点击「连接测试」即可连接。"
            )
        else:
            self.status_label.setText(f"● {browser_name} 已启动但端口未就绪")
            self.status_label.setStyleSheet("color: #e74c3c; font-weight: bold;")
            QMessageBox.warning(
                self, "端口未就绪",
                f"⚠ {browser_name} 已启动，但调试端口 {port} 未检测到监听。\n\n"
                f"可能原因：\n"
                f"1. 端口被其他程序占用\n"
                f"2. 杀毒软件拦截了调试端口\n\n"
                f"建议：\n"
                f"- 换一个端口试试（修改端口输入框后重新启动）\n"
                f"- 运行 netstat -ano | findstr :{port} 检查端口占用情况"
            )

    def _get_current_tab_data(self):
        idx = self.tabs.currentIndex()
        if idx == 0:
            return self.intercom_tab
        elif idx == 1:
            return self.review_tab
        elif idx == 2:
            return self.perm_tab
        return None

    def _test_connection(self):
        port = self.port_input.text().strip()
        self.status_label.setText("● 测试中...")
        self.status_label.setStyleSheet("color: #f39c12; font-weight: bold;")

        import socket
        import urllib.request
        import traceback

        try:
            # 第一步：测试端口是否开放
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(2)
            result = sock.connect_ex(('127.0.0.1', int(port)))
            sock.close()

            if result != 0:
                raise Exception(f"端口 {port} 未开放，请检查：\n1. 浏览器是否已启动\n2. 启动参数是否正确（含 --remote-debugging-port={port}）\n3. 是否有杀毒软件/防火墙拦截")

            # 第二步：测试CDP接口是否可访问
            try:
                response = urllib.request.urlopen(f"http://127.0.0.1:{port}/json/version", timeout=3)
                if response.getcode() != 200:
                    raise Exception(f"CDP接口返回异常，状态码: {response.getcode()}")
            except Exception as e:
                raise Exception(f"CDP接口访问失败：{str(e)}\n请确保浏览器启动参数正确，未使用代理")

            # 第三步：正式连接Playwright
            from playwright.sync_api import sync_playwright
            with sync_playwright() as p:
                browser = p.chromium.connect_over_cdp(f"http://127.0.0.1:{port}", timeout=5000)
                version = browser.version
                browser.close()

            self.status_label.setText(f"● 已连接 (浏览器版本: {version})")
            self.status_label.setStyleSheet("color: #27ae60; font-weight: bold;")
            QMessageBox.information(self, "连接成功", f"✅ 连接测试通过！\n\n浏览器版本: {version}\n端口: {port}")

        except Exception as e:
            error_msg = str(e)
            self.status_label.setText(f"● 连接失败: {error_msg[:30]}...")
            self.status_label.setStyleSheet("color: #e74c3c; font-weight: bold;")

            QMessageBox.critical(
                self, "连接失败",
                f"❌ 连接测试失败\n\n错误信息：{error_msg}\n\n"
                f"排查步骤：\n"
                f"1. 请确认浏览器已启动，启动参数完整：\n"
                f"   chrome.exe --remote-debugging-port={port} --remote-debugging-address=0.0.0.0\n\n"
                f"2. 检查端口是否被其他程序占用？运行：netstat -ano | findstr :{port}\n\n"
                f"3. 检查是否有杀毒软件/防火墙/网络代理拦截了本地连接\n\n"
                f"4. 尝试手动访问 http://127.0.0.1:{port}/json/version，如果打不开说明端口未正常监听"
            )

    def _start_task(self, task_name):
        if self.current_worker and self.current_worker.isRunning():
            QMessageBox.warning(self, "提示", "当前有任务正在运行，请先停止")
            return

        port = self.port_input.text().strip()
        tab = self._get_current_tab_data()

        if not tab:
            return

        tab['start_btn'].setEnabled(False)
        tab['stop_btn'].setEnabled(True)
        tab['log_area'].clear()

        # 重定向 stdout/stderr 到日志区域
        sys.stdout = self.redirector
        sys.stderr = self.redirector

        if task_name == "门禁对讲配置":
            self.current_worker = IntercomWorker(port)
        elif task_name == "居民审核自动审核":
            self.current_worker = ReviewWorker(port)
        elif task_name == "门禁权限下放":
            self.current_worker = DoorPermissionWorker(port)
        else:
            return

        self.current_worker.finished_signal.connect(self._on_task_finished)
        self.current_worker.start()

    def _stop_task(self):
        if self.current_worker and self.current_worker.isRunning():
            print("\n⚠ 正在停止任务...")
            self.current_worker.requestInterruption()

    def _on_task_finished(self, msg):
        tab = self._get_current_tab_data()
        if tab:
            tab['start_btn'].setEnabled(True)
            tab['stop_btn'].setEnabled(False)

        sys.stdout = self._original_stdout
        sys.stderr = self._original_stderr

        self.status_label.setText("● 已连接")
        self.status_label.setStyleSheet("color: #27ae60; font-weight: bold;")

        self.current_worker = None

    def _append_log(self, text):
        tab = self._get_current_tab_data()
        if not tab:
            return

        log_area = tab['log_area']
        # 根据内容设置不同颜色
        if '✓' in text or '成功' in text:
            color = "#2ecc71"
        elif '✗' in text or '错误' in text or '失败' in text:
            color = "#e74c3c"
        elif '⚠' in text or '跳过' in text or '禁用' in text:
            color = "#f39c12"
        elif '=' * 10 in text:
            color = "#3498db"
        else:
            color = "#d4d4d4"

        log_area.append(f'<span style="color:{color}">{text}</span>')
        log_area.moveCursor(QTextCursor.End)

    def closeEvent(self, event):
        if self.current_worker and self.current_worker.isRunning():
            reply = QMessageBox.question(
                self, "确认退出",
                "有任务正在运行，确定要退出吗？",
                QMessageBox.Yes | QMessageBox.No, QMessageBox.No
            )
            if reply == QMessageBox.No:
                event.ignore()
                return
            self.current_worker.requestInterruption()
            self.current_worker.wait(3000)

        sys.stdout = self._original_stdout
        sys.stderr = self._original_stderr
        event.accept()


# ============================================================
# 程序入口
# ============================================================

if __name__ == "__main__":
    app = QApplication(sys.argv)
    app.setStyle("Fusion")

    window = MainWindow()
    window.show()

    sys.exit(app.exec_())

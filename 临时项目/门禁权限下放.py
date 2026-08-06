from playwright.sync_api import sync_playwright, TimeoutError as PlaywrightTimeoutError
import time
import os
import re


def parse_config(config_path):
    """
    解析门禁权限下放配置文件
    格式示例：
        4栋:1单元，2单元,3单元
        3栋：1单元，2单元
    返回: [{'building': '4', 'units': ['1', '2', '3']}, ...]
    """
    result = []
    with open(config_path, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('[') or line.startswith('#'):
                continue

            # 用 "栋" 或 "幢" 分割
            match = re.split(r'[栋幢]', line, maxsplit=1)
            if len(match) != 2:
                continue

            building = match[0].strip()
            # 用 "：" 或 ":" 分割，取冒号后面的部分
            unit_part = re.split(r'[：:]', match[1], maxsplit=1)[-1]
            # 用 "，" 或 "," 或 "、" 分割单元
            units = [u.strip().replace('单元', '') for u in re.split(r'[，,、]', unit_part)]
            units = [u for u in units if u]  # 去除空字符串

            if building and units:
                result.append({'building': building, 'units': units})

    return result


def select_dropdown_option(page, select_index, option_text):
    """
    选择Element UI下拉框的选项
    select_index: 第几个.el-select（从0开始）
    option_text: 要选中的选项文本
    """
    page.evaluate(f'''() => {{
        const selects = Array.from(document.querySelectorAll('.el-select'));
        const sel = selects[{select_index}];
        if (!sel) return {{ error: '未找到选择器' }};
        const input = sel.querySelector('input');
        input.click();
        return {{ success: true }};
    }}''')
    time.sleep(1)

    page.evaluate(f'''() => {{
        const dropdown = document.querySelector('.el-select-dropdown:not([style*="display: none"])');
        if (!dropdown) return {{ error: '下拉菜单未找到' }};
        const options = Array.from(dropdown.querySelectorAll('li'));
        const target = options.find(opt => opt.innerText.trim() === '{option_text}');
        if (target) {{
            target.click();
            return {{ success: true, message: '已选中 {option_text}' }};
        }}
        return {{ error: '未找到选项 {option_text}' }};
    }}''')
    time.sleep(0.5)


def click_search(page):
    """点击搜索按钮"""
    page.evaluate('''() => {
        const buttons = Array.from(document.querySelectorAll('button'));
        const searchBtn = buttons.find(btn => btn.innerText.trim() === '搜索');
        if (searchBtn) {
            searchBtn.click();
            return { success: true };
        }
        return { error: '未找到搜索按钮' };
    }''')
    time.sleep(2)


def click_select_all_checkbox(page):
    """点击表格全选复选框"""
    page.evaluate('''() => {
        const tables = Array.from(document.querySelectorAll('table'));
        const targetTable = tables[3] || tables[1];
        const ths = Array.from(targetTable.querySelectorAll('th'));
        const checkbox = ths[0].querySelector('.el-checkbox__input');
        if (checkbox) {
            checkbox.click();
            return { success: true };
        }
        return { error: '未找到全选复选框' };
    }''')
    time.sleep(1)


def click_door_permission_button(page):
    """点击门禁权限下发/撤回按钮"""
    page.evaluate('''() => {
        const buttons = Array.from(document.querySelectorAll('button'));
        const btn = buttons.find(btn => btn.innerText.trim().includes('门禁权限下发'));
        if (btn) {
            btn.click();
            return { success: true };
        }
        return { error: '未找到门禁权限下发按钮' };
    }''')
    time.sleep(2)


def select_device_in_dialog(page, building, unit):
    """在门禁权限下发对话框中选中对应的楼幢和单元"""
    device_name = f"{building}幢{unit}单元"
    result = page.evaluate(f'''() => {{
        const dialogs = Array.from(document.querySelectorAll('.el-dialog, .ant-modal-wrap'));
        const dialog = dialogs.find(d => d.innerText.includes('门禁权限下发'));
        if (!dialog) return {{ error: '未找到对话框' }};

        const tables = Array.from(dialog.querySelectorAll('table'));
        const dataTable = tables[1];
        if (!dataTable) return {{ error: '未找到数据表格' }};

        const rows = Array.from(dataTable.querySelectorAll('tr'));
        const targetRow = rows.find(row => {{
            const cells = Array.from(row.querySelectorAll('td'));
            return cells.some(cell => cell.innerText.trim() === '{device_name}');
        }});

        if (targetRow) {{
            const checkbox = targetRow.querySelector('.el-checkbox__input');
            if (checkbox) {{
                checkbox.click();
                return {{ success: true, message: '已选中 {device_name}' }};
            }}
        }}
        return {{ error: '未找到 {device_name}' }};
    }}''')
    time.sleep(1)
    return result


def click_issue_button(page):
    """在对话框中点击下发按钮"""
    page.evaluate('''() => {
        const dialogs = Array.from(document.querySelectorAll('.el-dialog, .ant-modal-wrap'));
        const dialog = dialogs.find(d => d.innerText.includes('门禁权限下发'));
        if (!dialog) return { error: '未找到对话框' };

        const buttons = Array.from(dialog.querySelectorAll('button'));
        const issueBtn = buttons.find(btn => btn.innerText.trim() === '下发');
        if (issueBtn) {
            issueBtn.click();
            return { success: true };
        }
        return { error: '未找到下发按钮' };
    }''')
    time.sleep(1)


def click_confirm(page):
    """点击确认弹框中的确定按钮"""
    page.evaluate('''() => {
        const allButtons = Array.from(document.querySelectorAll('button'));
        const confirmButtons = allButtons.filter(btn => btn.innerText.trim() === '确定');
        if (confirmButtons.length > 0) {
            confirmButtons[confirmButtons.length - 1].click();
            return { success: true };
        }
        return { error: '未找到确定按钮' };
    }''')
    time.sleep(2)


def batch_door_permission():
    """
    在天翼智慧社区系统中自动批量下发门禁权限
    连接到9333端口的浏览器
    """
    # 读取配置文件
    config_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), '门禁权限下放配置.txt')
    print(f"读取配置文件: {config_path}")

    if not os.path.exists(config_path):
        print(f"✗ 配置文件不存在: {config_path}")
        return

    config = parse_config(config_path)
    if not config:
        print("✗ 配置文件内容为空或格式不正确")
        return

    print("配置内容:")
    for item in config:
        print(f"  {item['building']}栋: {', '.join(item['units'])}单元")

    with sync_playwright() as p:
        # 连接到9333端口的浏览器
        print(f"\n{'='*60}")
        print("正在连接到9333端口的浏览器...")
        print(f"{'='*60}")

        try:
            browser = p.chromium.connect_over_cdp("http://localhost:9333")
            print("✓ 成功连接到浏览器！")
        except Exception as e:
            print(f"✗ 连接失败: {e}")
            print("\n请确保：")
            print("1. 浏览器已启动并监听9333端口")
            print("2. 使用以下命令启动浏览器（以Chrome为例）：")
            print("   chrome.exe --remote-debugging-port=9333")
            return

        # 获取浏览器中的第一个页面
        try:
            page = browser.contexts[0].pages[0]
            print("✓ 使用浏览器中已有的页面")
        except:
            page = browser.new_page(viewport={'width': 1920, 'height': 1080})
            print("✓ 创建新页面")

        try:
            # 居民管理页面网址
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
                building = item['building']
                for unit in item['units']:
                    print(f"\n{'='*60}")
                    print(f"正在处理: {building}栋 {unit}单元")
                    print(f"{'='*60}")

                    try:
                        # 步骤1: 选择楼栋（select[0]）
                        print(f"1. 选择楼栋: {building}")
                        select_dropdown_option(page, 0, building)
                        print(f"   ✓ 已选择 {building}栋")
                        time.sleep(1)

                        # 步骤2: 选择单元（select[1]）
                        print(f"2. 选择单元: {unit}")
                        select_dropdown_option(page, 1, unit)
                        print(f"   ✓ 已选择 {unit}单元")
                        time.sleep(1)

                        # 步骤3: 点击搜索
                        print("3. 点击搜索...")
                        click_search(page)
                        print("   ✓ 已点击搜索")
                        time.sleep(2)

                        # 步骤4: 点击全选复选框
                        print("4. 点击全选复选框...")
                        click_select_all_checkbox(page)
                        print("   ✓ 已全选")
                        time.sleep(1)

                        # 步骤5: 点击门禁权限下发/撤回按钮
                        print("5. 点击门禁权限下发/撤回...")
                        click_door_permission_button(page)
                        print("   ✓ 已打开门禁权限对话框")
                        time.sleep(2)

                        # 步骤6: 在对话框中选中对应的楼幢和单元
                        print(f"6. 在对话框中选中 {building}幢{unit}单元...")
                        select_device_in_dialog(page, building, unit)
                        print(f"   ✓ 已选中 {building}幢{unit}单元")
                        time.sleep(1)

                        # 步骤7: 点击下发按钮
                        print("7. 点击下发...")
                        click_issue_button(page)
                        print("   ✓ 已点击下发")
                        time.sleep(1)

                        # 步骤8: 点击确定
                        print("8. 点击确定...")
                        click_confirm(page)
                        print("   ✓ 已确认下发")
                        time.sleep(2)

                        success_count += 1
                        print(f"✓ {building}栋{unit}单元 门禁权限下发完成！({success_count}/{total_count})")

                    except Exception as e:
                        print(f"✗ 处理 {building}栋{unit}单元 时出错: {type(e).__name__}: {e}")
                        import traceback
                        traceback.print_exc()

            print(f"\n{'='*60}")
            print(f"批量下发完成！")
            print(f"成功: {success_count}/{total_count}")
            print(f"{'='*60}")

        except PlaywrightTimeoutError:
            print("\n错误: 页面加载超时，请检查网络连接或网址是否正确")
        except Exception as e:
            print(f"\n发生错误: {type(e).__name__}: {e}")
            import traceback
            traceback.print_exc()
        finally:
            print("\n程序结束")


if __name__ == "__main__":
    batch_door_permission()

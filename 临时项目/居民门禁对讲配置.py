from playwright.sync_api import sync_playwright, TimeoutError as PlaywrightTimeoutError
import time
import sys


def batch_configure_intercom():
    """
    在天翼智慧社区系统中自动批量配置门禁对讲
    连接到9333端口的浏览器
    """
    with sync_playwright() as p:
        # 连接到9333端口的浏览器（使用CDP协议）
        print(f"{'='*60}")
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

        # 获取浏览器中的第一个页面，如果没有则创建新页面
        try:
            page = browser.contexts[0].pages[0]
            print("✓ 使用浏览器中已有的页面")
        except:
            page = browser.new_page(viewport={'width': 1920, 'height': 1080})
            print("✓ 创建新页面")

        try:
            # 门禁对讲配置页面网址
            url = "https://w.shequ.189.cn/manage/?dateTime=1780189829086#/communityManage/gate/intercomConfiguration/index"
            print(f"\n正在访问网址: {url}")

            # 检查当前页面URL，如果不是目标URL则导航
            if page.url != url:
                print("导航到目标页面...")
                page.goto(url, wait_until='networkidle', timeout=30000)
            else:
                print("✓ 已经在目标页面")

            # 等待页面加载
            print("等待页面加载...")
            time.sleep(3)

            print(f"\n{'='*60}")
            print("开始批量配置门禁对讲")
            print(f"{'='*60}")

            # 首先点击搜索按钮获取完整列表
            print("\n点击搜索按钮...")
            try:
                search_button = page.locator('button', has_text='搜索').first
                search_button.click()
                time.sleep(2)
                print("✓ 已点击搜索按钮")
            except Exception as e:
                print(f"搜索按钮点击失败: {e}")

            # 获取总页数
            print("\n获取分页信息...")
            total_pages = 1
            try:
                pagination = page.locator('.el-pagination').first
                page_numbers = pagination.locator('li.number').all()
                total_pages = len(page_numbers)
                print(f"✓ 共 {total_pages} 页")
            except Exception as e:
                print(f"获取分页信息失败: {e}，默认1页")

            success_count = 0
            total_devices = 0
            skipped_count = 0

            # 遍历每一页
            for current_page_num in range(1, total_pages + 1):
                print(f"\n{'='*60}")
                print(f"正在处理第 {current_page_num}/{total_pages} 页")
                print(f"{'='*60}")

                # 如果不是第一页，先翻页
                if current_page_num > 1:
                    print(f"翻页到第 {current_page_num} 页...")
                    try:
                        pagination = page.locator('.el-pagination').first
                        page_numbers = pagination.locator('li.number').all()
                        if current_page_num - 1 < len(page_numbers):
                            page_numbers[current_page_num - 1].click()
                            time.sleep(2)
                            print(f"✓ 已翻到第 {current_page_num} 页")
                        else:
                            print(f"✗ 页码超出范围")
                            continue
                    except Exception as e:
                        print(f"翻页失败: {e}")
                        continue

                # 获取当前页的设备列表
                print(f"\n获取第 {current_page_num} 页的设备列表...")
                device_count_on_page = 0

                try:
                    # 获取表格行数
                    tables = page.locator('table').all()
                    if len(tables) < 3:
                        print("✗ 未找到表格")
                        continue

                    main_table = tables[2]
                    rows = main_table.locator('tbody tr').all()
                    device_count_on_page = len(rows)
                    total_devices += device_count_on_page
                    print(f"✓ 当前页有 {device_count_on_page} 个设备")

                    # 逐个处理当前页的每个设备
                    for row_idx in range(device_count_on_page):
                        # 重新获取表格和行（页面可能已刷新）
                        tables = page.locator('table').all()
                        if len(tables) < 3:
                            print("✗ 未找到表格")
                            continue

                        main_table = tables[2]
                        rows = main_table.locator('tbody tr').all()

                        if row_idx >= len(rows):
                            continue

                        row = rows[row_idx]

                        # 获取设备信息
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

                        # 检查"对讲配置"按钮是否可用
                        print("检查对讲配置按钮状态...")
                        button_available = False
                        intercom_element = None

                        try:
                            # 从table[4]中找到对应的操作列
                            if len(tables) >= 5:
                                op_table = tables[4]
                                op_rows = op_table.locator('tbody tr').all()
                                if row_idx < len(op_rows):
                                    op_row = op_rows[row_idx]

                                    # 查找对讲配置元素
                                    all_elements = op_row.locator('*').all()
                                    for el in all_elements:
                                        if el.inner_text().strip() == '对讲配置':
                                            # 检查是否禁用
                                            class_name = el.get_attribute('class') or ''
                                            if 'forbidden' in class_name:
                                                print(f"   ⚠ {device_name} 的对讲配置按钮为禁用状态，跳过")
                                                skipped_count += 1
                                                button_available = False
                                            else:
                                                intercom_element = el
                                                button_available = True
                                            break
                        except Exception as e:
                            print(f"   检查按钮状态时出错: {e}")

                        if not button_available:
                            continue

                        try:
                            # 步骤1: 点击对应的"对讲配置"按钮
                            print(f"1. 点击 {device_name} 的对讲配置按钮...")

                            if intercom_element:
                                intercom_element.click()
                                print(f"   ✓ 已点击对讲配置按钮")
                            else:
                                # 备用方式
                                buttons = row.locator('button').all()
                                intercom_button = None
                                for btn in buttons:
                                    if btn.inner_text().strip() == '对讲配置':
                                        intercom_button = btn
                                        break

                                if intercom_button:
                                    intercom_button.click()
                                    print(f"   ✓ 已点击对讲配置按钮")
                                else:
                                    # 尝试点击操作列的最后一个按钮
                                    last_cell = row.locator('td').last
                                    last_btn = last_cell.locator('button').last
                                    last_btn.click()
                                    print(f"   ✓ 已点击操作列最后一个按钮")

                            time.sleep(2)

                            # 步骤2: 点击"批量新增"按钮
                            print("2. 点击批量新增按钮...")
                            try:
                                batch_add_button = page.locator('button', has_text='批量新增').first
                                batch_add_button.click()
                                print("   ✓ 已点击批量新增按钮")
                            except Exception as e:
                                print(f"   ✗ 点击批量新增失败: {e}")

                            time.sleep(2)

                            # 步骤3: 点击"全部选择"按钮
                            print("3. 点击全部选择按钮...")
                            try:
                                # 先找到批量新增对话框
                                dialog_found = False
                                dialogs = page.locator('.ant-modal-wrap, .el-dialog').all()
                                for dialog in dialogs:
                                    dialog_text = dialog.inner_text()
                                    if '批量新增' in dialog_text:
                                        dialog_found = True
                                        select_all_btn = dialog.locator('text=全部选择').first
                                        select_all_btn.click()
                                        print("   ✓ 已点击全部选择按钮")
                                        break

                                if not dialog_found:
                                    select_all_btn = page.locator('text=全部选择').first
                                    select_all_btn.click()
                                    print("   ✓ 已点击全部选择按钮")

                            except Exception as e:
                                print(f"   ✗ 点击全部选择失败: {e}")

                            time.sleep(1)

                            # 步骤4: 点击"确定"按钮
                            print("4. 点击确定按钮...")
                            try:
                                confirm_found = False
                                dialogs = page.locator('.ant-modal-wrap, .el-dialog').all()
                                for dialog in dialogs:
                                    dialog_text = dialog.inner_text()
                                    if '批量新增' in dialog_text:
                                        confirm_found = True
                                        confirm_buttons = dialog.locator('button').all()
                                        for btn in confirm_buttons:
                                            if btn.inner_text().strip() == '确定':
                                                btn.click()
                                                print("   ✓ 已点击确定按钮")
                                                break
                                        break

                                if not confirm_found:
                                    confirm_btn = page.locator('button', has_text='确定').first
                                    confirm_btn.click()
                                    print("   ✓ 已点击确定按钮")

                            except Exception as e:
                                print(f"   ✗ 点击确定失败: {e}")

                            time.sleep(2)

                            print(f"✓ {device_name} 配置完成！")
                            success_count += 1

                        except Exception as e:
                            print(f"✗ 处理 {device_name} 时出错: {type(e).__name__}: {e}")
                            import traceback
                            traceback.print_exc()

                        # 返回列表页面
                        print("返回门禁对讲配置列表页面...")
                        page.goto(url, wait_until='networkidle', timeout=30000)
                        time.sleep(2)

                        # 确保回到当前正在处理的页
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
                    print(f"处理第 {current_page_num} 页时出错: {type(e).__name__}: {e}")
                    import traceback
                    traceback.print_exc()

            print(f"\n{'='*60}")
            print(f"批量配置完成！")
            print(f"成功: {success_count}/{total_devices}")
            print(f"跳过（禁用）: {skipped_count}")
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
    batch_configure_intercom()


from playwright.sync_api import sync_playwright, TimeoutError as PlaywrightTimeoutError
import time
import sys
from datetime import datetime

def auto_review():
    """
    在天翼智慧社区系统中自动每隔5秒检查并审核未审核的居民登记
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



        # a = page.locator('.el-table__fixed-header-wrapper').first.locator('.el-checkbox__input').first
        # # b = 0
        # a.click()
        # print(f'当前为{len(a)}')
        # for c in a:
        #     try:
        #         print(f'当前为{a}')
        #         a += 1
        #         s.click(timeout=5000)
        #     except Exception as e:
        #         print(f'{a}为错误')


        try:
            
            # 目标网址
            url = "https://w.shequ.189.cn/manage/?dateTime=1780189829086#/propertyManage/review-registration/review-registration/index"
            print(f"\n正在访问网址: {url}")

            # 检查当前页面URL，如果不是目标URL则导航
            if page.url != url:
                print("导航到目标页面...")
                page.goto(url, wait_until='networkidle', timeout=30000)
            else:
                print("✓ 已经在目标页面")

            # 等待页面主要元素加载
            print("等待页面加载...")
            page.wait_for_selector('.el-select', timeout=15000)
            time.sleep(2)

            print(f"\n{'='*60}")
            print("开始自动审核循环（按 Ctrl+C 退出）")
            print(f"{'='*60}")

            # 计数器
            review_count = 0
            cycle_count = 0

            try:
                while True:
                    cycle_count += 1
                    current_time = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                    print(f"\n[{current_time}] 第 {cycle_count} 次检查...")

                    # 步骤 1: 点击状态下拉框
                    try:
                        print("  点击状态下拉框...")
                        status_select = page.locator('.el-select > .el-input').first
                        status_select.click()
                        time.sleep(1)

                        # 步骤 2: 选择"未审核"选项
                        print("  选择'未审核'选项...")
                        unreviewed_option = page.get_by_role('listitem').filter(has_text='未审核')
                        unreviewed_option.click()
                        time.sleep(1)

                        # 步骤 3: 点击搜索按钮
                        print("  点击搜索按钮...")
                        search_button = page.get_by_role('button', name=' 搜索')
                        search_button.click()

                        print("  等待搜索结果加载...")
                        time.sleep(3)

                        # 检查是否有记录
                        # 检查批量审核按钮是否可用，或者查看表格内容
                        print("  检查是否有未审核记录...")

                        # 尝试查找表格行
                        table_rows = page.locator('table tbody tr')
                        row_count = table_rows.count()

                        if row_count > 0:
                            print(f"  发现 {row_count} 条未审核记录，开始审核...")

                            # 步骤 4: 点击全选复选框
                            print("  点击全选复选框...")
                            try:
                                # select_all_checkbox = page.locator('.el-checkbox__input').nth(4)
                                select_all_checkbox = page.locator('.el-table__fixed-header-wrapper').first.locator('.el-checkbox__input').first
                                select_all_checkbox.click()
                                print("  已选中全部记录！")
                                time.sleep(1)
                            except Exception as e:
                                print(f"  点击全选复选框时出错: {e}")
                                # 继续尝试下一步

                            # 步骤 5: 点击批量审核按钮
                            print("  点击批量审核按钮...")
                            try:
                                batch_review_button = page.get_by_role('button', name='批量审核')

                                if not batch_review_button.is_disabled():
                                    batch_review_button.click()
                                    print("  已点击批量审核按钮！")
                                    time.sleep(2)

                                    # 步骤 6: 确认批量审核
                                    print("  确认批量审核...")
                                    try:
                                        # 等待弹窗出现
                                        page.wait_for_selector('text=批量审核', timeout=5000)
                                        time.sleep(1)

                                        # 点击确定按钮
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
                        print(f"  本次检查出错: {type(e).__name__}: {e}")

                    # 等待5秒后再次检查
                    print(f"  等待5秒后继续检查...")
                    time.sleep(5)

            except KeyboardInterrupt:
                print(f"\n{'='*60}")
                print("收到退出信号，停止自动审核")
                print(f"本次运行共审核 {review_count} 条记录")
                print(f"{'='*60}")

        except PlaywrightTimeoutError:
            print("\n错误: 页面加载超时，请检查网络连接或网址是否正确")
        except Exception as e:
            print(f"\n发生错误: {type(e).__name__}: {e}")
            import traceback
            traceback.print_exc()
        finally:
            # 注意：连接到远程浏览器时，不要关闭浏览器，只断开连接
            print("\n正在断开浏览器连接...")
            print("程序结束")

if __name__ == "__main__":
    auto_review()


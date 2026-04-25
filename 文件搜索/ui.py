import tkinter as tk
from tkinter import ttk, filedialog, messagebox
import threading
import time

# --- 现代配色方案 ---
THEME = {
    "bg_app": "#111827",       # 应用主背景 (深紫灰)
    "bg_card": "#1F2937",      # 卡片背景
    "bg_input": "#374151",     # 输入框背景
    "text_main": "#F9FAFB",    # 主文字颜色 (白)
    "text_sub": "#9CA3AF",     # 辅助文字颜色 (灰)
    "primary": "#3B82F6",      # 主色调/导出按钮 (蓝)
    "primary_hover": "#2563EB",
    "success": "#10B981",      # 开始按钮 (绿)
    "success_hover": "#059669",
    "danger": "#EF4444",       # 停止按钮 (红)
    "danger_hover": "#DC2626",
    "highlight": "#F59E0B",    # 高亮颜色 (黄/橙)
    "border": "#4B5563"
}

class RoundedButton(tk.Canvas):
    """自定义抗锯齿圆角按钮"""
    def __init__(self, parent, text, width, height, radius, color, hover_color, command=None, font=("Microsoft YaHei", 10, "bold")):
        super().__init__(parent, width=width, height=height, bg=parent["bg"], highlightthickness=0)
        self.command = command
        self.color = color
        self.hover_color = hover_color
        self.radius = radius
        
        # 绘制圆角背景
        self.rect_id = self._draw_round_rect(0, 0, width, height, radius, color)
        # 绘制文字
        self.text_id = self.create_text(width/2, height/2, text=text, fill=THEME["text_main"], font=font)
        
        # 绑定事件
        self.bind("<Enter>", self.on_enter)
        self.bind("<Leave>", self.on_leave)
        self.bind("<ButtonPress-1>", self.on_press)
        self.bind("<ButtonRelease-1>", self.on_release)
        
    def _draw_round_rect(self, x, y, w, h, r, color):
        # 使用四个圆弧和两个矩形拼接出完美的圆角矩形
        self.create_arc(x, y, x+2*r, y+2*r, start=90, extent=90, fill=color, outline=color, tags="bg")
        self.create_arc(x+w-2*r, y, x+w, y+2*r, start=0, extent=90, fill=color, outline=color, tags="bg")
        self.create_arc(x, y+h-2*r, x+2*r, y+h, start=180, extent=90, fill=color, outline=color, tags="bg")
        self.create_arc(x+w-2*r, y+h-2*r, x+w, y+h, start=270, extent=90, fill=color, outline=color, tags="bg")
        self.create_rectangle(x+r, y, x+w-r, y+h, fill=color, outline=color, tags="bg")
        self.create_rectangle(x, y+r, x+w, y+h-r, fill=color, outline=color, tags="bg")
        return "bg"

    def on_enter(self, event):
        self.itemconfig(self.rect_id, fill=self.hover_color, outline=self.hover_color)
        self.config(cursor="hand2")
        
    def on_leave(self, event):
        self.itemconfig(self.rect_id, fill=self.color, outline=self.color)
        
    def on_press(self, event):
        self.move(self.text_id, 0, 2)  # 点击下沉反馈
        
    def on_release(self, event):
        self.move(self.text_id, 0, -2)
        if self.command:
            self.command()


class FileSearchApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("文件搜索工具 Pro")
        self.geometry("960x720")
        self.configure(bg=THEME["bg_app"])
        self.minsize(800, 600)
        
        self.is_searching = False
        
        self.setup_styles()
        self.build_ui()
        self.setup_text_tags()

    def setup_styles(self):
        style = ttk.Style(self)
        style.theme_use("clam")
        
        # 整体框架样式
        style.configure("TFrame", background=THEME["bg_app"])
        style.configure("Card.TFrame", background=THEME["bg_card"])
        
        # 标签样式
        style.configure("TLabel", background=THEME["bg_app"], foreground=THEME["text_main"], font=("Microsoft YaHei", 10))
        style.configure("Card.TLabel", background=THEME["bg_card"])
        style.configure("Title.TLabel", foreground=THEME["primary"], font=("Microsoft YaHei", 18, "bold"))
        
        # 下拉框样式 (扁平化)
        style.configure("TCombobox", 
                        fieldbackground=THEME["bg_input"], 
                        background=THEME["bg_input"], 
                        foreground=THEME["text_main"], 
                        bordercolor=THEME["bg_card"],
                        arrowcolor=THEME["text_main"],
                        lightcolor=THEME["bg_input"],
                        darkcolor=THEME["bg_input"])
        
        # 进度条样式 (蓝渐变感)
        style.configure("TProgressbar", 
                        troughcolor=THEME["bg_input"], 
                        background=THEME["primary"], 
                        bordercolor=THEME["bg_app"],
                        thickness=8)

    def build_ui(self):
        # --- 主容器 ---
        main_container = tk.Frame(self, bg=THEME["bg_app"])
        main_container.pack(fill=tk.BOTH, expand=True, padx=24, pady=24)

        # 1. 顶部标题区
        header_frame = tk.Frame(main_container, bg=THEME["bg_app"])
        header_frame.pack(fill=tk.X, pady=(0, 20))
        ttk.Label(header_frame, text="🔍 文件搜索工具 Pro", style="Title.TLabel").pack(side=tk.LEFT)

        # 2. 搜索配置卡片区 (模拟圆角卡片通过色块区分)
        config_card = tk.Frame(main_container, bg=THEME["bg_card"], highlightthickness=1, highlightbackground=THEME["border"], highlightcolor=THEME["border"])
        config_card.pack(fill=tk.X, pady=(0, 20))
        
        # 为卡片内部添加内边距容器
        config_inner = tk.Frame(config_card, bg=THEME["bg_card"], padx=20, pady=20)
        config_inner.pack(fill=tk.BOTH, expand=True)
        
        config_inner.columnconfigure(1, weight=1) # 让输入框自动拉伸

        # Row 1: 目录
        ttk.Label(config_inner, text="📂 搜索目录:", style="Card.TLabel").grid(row=0, column=0, sticky="w", pady=(0, 15), padx=(0, 10))
        self.dir_var = tk.StringVar()
        dir_entry = self.create_flat_entry(config_inner, self.dir_var, "请选择或输入目标文件夹路径...")
        dir_entry.grid(row=0, column=1, sticky="ew", pady=(0, 15))
        
        browse_btn = RoundedButton(config_inner, "浏览", 70, 32, 6, THEME["primary"], THEME["primary_hover"], self.browse_dir, font=("Microsoft YaHei", 9))
        browse_btn.grid(row=0, column=2, padx=(10, 0), pady=(0, 15))

        # Row 2: 搜索内容
        ttk.Label(config_inner, text="📝 搜索内容:", style="Card.TLabel").grid(row=1, column=0, sticky="w", pady=(0, 15), padx=(0, 10))
        self.keyword_var = tk.StringVar()
        keyword_entry = self.create_flat_entry(config_inner, self.keyword_var, "输入要搜索的关键词（支持正则）...")
        keyword_entry.grid(row=1, column=1, columnspan=2, sticky="ew", pady=(0, 15))

        # Row 3: 模式与过滤
        options_frame = tk.Frame(config_inner, bg=THEME["bg_card"])
        options_frame.grid(row=2, column=0, columnspan=3, sticky="ew")
        
        ttk.Label(options_frame, text="📊 搜索模式:", style="Card.TLabel").pack(side=tk.LEFT, padx=(0, 10))
        self.mode_var = tk.StringVar(value="按文件内容")
        mode_cb = ttk.Combobox(options_frame, textvariable=self.mode_var, values=["按文件内容", "按文件名/目录名", "混合模式"], state="readonly", width=15)
        mode_cb.pack(side=tk.LEFT, padx=(0, 30))
        
        ttk.Label(options_frame, text="📄 文件类型:", style="Card.TLabel").pack(side=tk.LEFT, padx=(0, 10))
        self.types_var = tk.StringVar(value="txt, py, md, js, json, html, css, xlsx")
        types_entry = self.create_flat_entry(options_frame, self.types_var, "例如: txt, py, md")
        types_entry.pack(side=tk.LEFT, fill=tk.X, expand=True)

        # 3. 操作按钮区
        btn_frame = tk.Frame(main_container, bg=THEME["bg_app"])
        btn_frame.pack(fill=tk.X, pady=(0, 15))
        
        self.start_btn = RoundedButton(btn_frame, "▶ 开始搜索", 120, 38, 8, THEME["success"], THEME["success_hover"], self.start_search)
        self.start_btn.pack(side=tk.LEFT, padx=(0, 10))
        
        self.stop_btn = RoundedButton(btn_frame, "⏹ 停止", 100, 38, 8, THEME["danger"], THEME["danger_hover"], self.stop_search)
        self.stop_btn.pack(side=tk.LEFT, padx=(0, 10))
        
        self.export_btn = RoundedButton(btn_frame, "💾 导出结果", 120, 38, 8, THEME["primary"], THEME["primary_hover"], self.export_results)
        self.export_btn.pack(side=tk.RIGHT)

        # 4. 进度条区
        progress_frame = tk.Frame(main_container, bg=THEME["bg_app"])
        progress_frame.pack(fill=tk.X, pady=(0, 10))
        
        self.progress_var = tk.DoubleVar()
        self.progress_bar = ttk.Progressbar(progress_frame, variable=self.progress_var, mode="determinate")
        self.progress_bar.pack(side=tk.LEFT, fill=tk.X, expand=True, ipady=2)
        
        self.progress_lbl = ttk.Label(progress_frame, text="0%", font=("Consolas", 10))
        self.progress_lbl.pack(side=tk.LEFT, padx=(10, 0))

        # 5. 结果显示区 (卡片)
        result_card = tk.Frame(main_container, bg=THEME["bg_card"], highlightthickness=1, highlightbackground=THEME["border"])
        result_card.pack(fill=tk.BOTH, expand=True, pady=(0, 10))
        
        self.result_text = tk.Text(result_card, bg=THEME["bg_card"], fg=THEME["text_main"], font=("Consolas", 11), 
                                   relief="flat", borderwidth=0, highlightthickness=0,
                                   selectbackground=THEME["primary"], selectforeground="#FFF",
                                   padx=10, pady=10)
        self.result_text.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        
        scrollbar = ttk.Scrollbar(result_card, command=self.result_text.yview)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        self.result_text.config(yscrollcommand=scrollbar.set)

        # 6. 底部状态栏
        self.status_var = tk.StringVar(value="就绪 | 等待操作")
        status_bar = tk.Label(self, textvariable=self.status_var, bg="#0F141E", fg=THEME["text_sub"], 
                              font=("Microsoft YaHei", 9), anchor="w", padx=24, pady=6)
        status_bar.pack(side=tk.BOTTOM, fill=tk.X)

    def create_flat_entry(self, parent, var, placeholder=""):
        """创建扁平化且带内边距的输入框视觉效果"""
        frame = tk.Frame(parent, bg=THEME["bg_input"], padx=8, pady=6)
        entry = tk.Entry(frame, textvariable=var, bg=THEME["bg_input"], fg=THEME["text_main"], 
                         insertbackground=THEME["text_main"], relief="flat", font=("Microsoft YaHei", 10),
                         highlightthickness=0)
        entry.pack(fill=tk.X, expand=True)
        
        # 占位符逻辑
        if not var.get() and placeholder:
            entry.insert(0, placeholder)
            entry.config(fg=THEME["text_sub"])
            
            def on_focus_in(event):
                if entry.get() == placeholder:
                    entry.delete(0, tk.END)
                    entry.config(fg=THEME["text_main"])
            def on_focus_out(event):
                if not entry.get():
                    entry.insert(0, placeholder)
                    entry.config(fg=THEME["text_sub"])
                    
            entry.bind("<FocusIn>", on_focus_in)
            entry.bind("<FocusOut>", on_focus_out)
            
        return frame

    def setup_text_tags(self):
        """配置富文本高亮样式"""
        self.result_text.tag_config("dir", foreground=THEME["primary"], font=("Microsoft YaHei", 11, "bold"))
        self.result_text.tag_config("file", foreground=THEME["success"], font=("Consolas", 11, "bold"))
        self.result_text.tag_config("line_num", foreground=THEME["highlight"])
        self.result_text.tag_config("match", background=THEME["danger"], foreground="#FFFFFF")
        self.result_text.tag_config("context", foreground=THEME["text_sub"])

    # --- 核心交互逻辑 (模拟界面表现) ---
    def browse_dir(self):
        path = filedialog.askdirectory(title="选择搜索目录")
        if path:
            self.dir_var.set(path)
            # 刷新占位符状态
            self.update_idletasks()

    def start_search(self):
        if self.is_searching: return
        
        keyword = self.keyword_var.get()
        if not keyword or keyword.startswith("输入"):
            messagebox.showwarning("提示", "请输入有效的搜索内容！")
            return
            
        self.is_searching = True
        self.result_text.delete(1.0, tk.END)
        self.progress_var.set(0)
        self.status_var.set("状态: 正在扫描文件... | 耗时: 0.0s")
        
        # 使用线程模拟搜索任务，防止UI卡死
        threading.Thread(target=self._mock_search_process, daemon=True).start()

    def stop_search(self):
        if self.is_searching:
            self.is_searching = False
            self.status_var.set(self.status_var.get() + " | [用户已终止]")

    def export_results(self):
        if not self.result_text.get(1.0, tk.END).strip():
            messagebox.showinfo("提示", "当前没有可导出的搜索结果。")
            return
        messagebox.showinfo("导出成功", "结果已成功导出为 search_results.html (模拟)")

    def _mock_search_process(self):
        """模拟搜索过程，展示富文本UI效果"""
        keyword = self.keyword_var.get()
        total_files = 150
        
        self.result_text.insert(tk.END, "📂 D:/Project/Source/\n", "dir")
        
        for i in range(1, total_files + 1):
            if not self.is_searching: break
            time.sleep(0.02) # 模拟文件读取耗时
            
            # 更新进度
            progress = (i / total_files) * 100
            self.progress_var.set(progress)
            self.progress_lbl.config(text=f"{int(progress)}%")
            self.status_var.set(f"正在搜索... 已扫描 {i}/{total_files} 个文件 | 找到 12 处匹配")
            
            # 随机插入模拟匹配结果展示高亮效果
            if i in [15, 45, 120]:
                self.result_text.insert(tk.END, f"  📄 src/components/Search_{i}.py\n", "file")
                self.result_text.insert(tk.END, f"     行 {i*2}: ", "line_num")
                self.result_text.insert(tk.END, "def search_function(query='", "context")
                self.result_text.insert(tk.END, keyword, "match")
                self.result_text.insert(tk.END, "'):\n", "context")
                self.result_text.see(tk.END)
                
        if self.is_searching:
            self.is_searching = False
            self.status_var.set("就绪 | 搜索完成 | 共找到 12 处匹配")

if __name__ == "__main__":
    app = FileSearchApp()
    app.mainloop()
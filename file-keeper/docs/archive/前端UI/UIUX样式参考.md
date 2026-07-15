```js
import React, { useState, useEffect, useRef } from 'react';
import { 
  Search, Plus, Settings, X, Maximize2, Minus,
  Folder, FileText, Image as ImageIcon, Code, Box,
  MoreVertical, Grid, List, Activity, Trash2, Edit3, Tag,
  FolderInput, PlayCircle, Moon, Sun
} from 'lucide-react';

// --- Mock Data ---
const mockGroups = ['全部', '最近打开', '工作', '学习', '项目'];

const mockFiles = [
  { id: 1, name: '2026年度产品规划.docx', type: 'word', path: 'C:/Users/Documents/Work', group: '工作', tags: ['重要', '规划'], openCount: 24, lastOpen: '10分钟前', icon: FileText, color: 'text-blue-500' },
  { id: 2, name: 'File Keeper UI设计稿.fig', type: 'design', path: 'D:/Projects/FileKeeper/Design', group: '项目', tags: ['设计'], openCount: 56, lastOpen: '2小时前', icon: Box, color: 'text-purple-500' },
  { id: 3, name: 'Q3 财务报表.xlsx', type: 'excel', path: 'C:/Users/Documents/Finance', group: '工作', tags: ['机密'], openCount: 12, lastOpen: '昨天', icon: FileText, color: 'text-green-600' },
  { id: 4, name: '前端架构梳理', type: 'folder', path: 'D:/Projects/Frontend', group: '项目', tags: [], openCount: 128, lastOpen: '3天前', icon: Folder, color: 'text-yellow-500' },
  { id: 5, name: 'App Logo 原型.png', type: 'image', path: 'D:/Assets/Images', group: '项目', tags: ['素材'], openCount: 5, lastOpen: '1周前', icon: ImageIcon, color: 'text-orange-500' },
  { id: 6, name: '核心算法.js', type: 'code', path: 'D:/Projects/FileKeeper/Src', group: '工作', tags: ['代码'], openCount: 42, lastOpen: '刚刚', icon: Code, color: 'text-yellow-600' },
];

const mockProcesses = [
  { pid: 14235, name: 'Microsoft Word', window: '2026年度产品规划.docx - Word', cpu: '2.4%' },
  { pid: 8492, name: 'WPS Office', window: '2026年度产品规划.docx - WPS', cpu: '0.1%' }
];

export default function App() {
  const [theme, setTheme] = useState('dark');
  const [viewMode, setViewMode] = useState('grid');
  const [activeGroup, setActiveGroup] = useState('全部');
  const [searchQuery, setSearchQuery] = useState('');
  
  // Modals & Context Menus
  const [contextMenu, setContextMenu] = useState(null);
  const [processModalFile, setProcessModalFile] = useState(null);
  const [confirmClosePID, setConfirmClosePID] = useState(null);

  // Toggle Theme
  useEffect(() => {
    if (theme === 'dark') {
      document.documentElement.classList.add('dark');
    } else {
      document.documentElement.classList.remove('dark');
    }
  }, [theme]);

  const filteredFiles = mockFiles.filter(f => {
    const matchGroup = activeGroup === '全部' || f.group === activeGroup || (activeGroup === '最近打开' && f.openCount > 20);
    const matchSearch = f.name.toLowerCase().includes(searchQuery.toLowerCase()) || f.tags.some(t => t.includes(searchQuery));
    return matchGroup && matchSearch;
  });

  const handleContextMenu = (e, file) => {
    e.preventDefault();
    setContextMenu({
      x: e.clientX,
      y: e.clientY,
      file
    });
  };

  const closeContextMenu = () => setContextMenu(null);

  const openProcessManager = (file) => {
    setProcessModalFile(file);
    closeContextMenu();
  };

  return (
    <div className={`min-h-screen w-full flex flex-col font-sans transition-colors duration-300 ${theme === 'dark' ? 'dark bg-[#1e1e1e] text-gray-200' : 'bg-gray-50 text-gray-800'}`}>
      
      {/* 1. 顶部标题栏 (模拟系统窗口) */}
      <div className="h-10 flex items-center justify-between px-4 select-none border-b border-gray-200 dark:border-[#3d3d3d] bg-white dark:bg-[#252525]">
        <div className="flex items-center space-x-2">
          <div className="w-5 h-5 rounded bg-[#42b883] flex items-center justify-center">
            <Box size={12} className="text-white" />
          </div>
          <span className="text-xs font-semibold tracking-wide">File Keeper</span>
        </div>
        <div className="flex items-center space-x-3 text-gray-400">
          <Minus size={14} className="hover:text-gray-600 dark:hover:text-white cursor-pointer" />
          <Maximize2 size={12} className="hover:text-gray-600 dark:hover:text-white cursor-pointer" />
          <X size={16} className="hover:text-red-500 cursor-pointer" />
        </div>
      </div>

      {/* 2. 工具栏 (搜索、添加、设置) */}
      <div className="px-6 py-4 flex items-center justify-between bg-white dark:bg-[#1e1e1e]">
        <div className="relative w-96 group">
          <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 group-focus-within:text-[#42b883] transition-colors" />
          <input 
            type="text" 
            placeholder="搜索文件、路径或标签..." 
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full pl-9 pr-4 py-2 bg-gray-100 dark:bg-[#2d2d2d] border border-transparent focus:border-[#42b883] focus:bg-white dark:focus:bg-[#1e1e1e] rounded-md outline-none text-sm transition-all duration-200 shadow-sm"
          />
        </div>
        
        <div className="flex items-center space-x-3">
          <button className="flex items-center space-x-1 bg-[#42b883] hover:bg-[#369b6e] text-white px-4 py-2 rounded-md text-sm font-medium transition-colors shadow-sm shadow-[#42b883]/20">
            <Plus size={16} />
            <span>添加文件</span>
          </button>
          
          <button 
            onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}
            className="p-2 rounded-md bg-gray-100 dark:bg-[#2d2d2d] hover:bg-gray-200 dark:hover:bg-[#383838] transition-colors"
            title="切换主题"
          >
            {theme === 'dark' ? <Sun size={18} /> : <Moon size={18} />}
          </button>

          <button className="p-2 rounded-md bg-gray-100 dark:bg-[#2d2d2d] hover:bg-gray-200 dark:hover:bg-[#383838] transition-colors">
            <Settings size={18} />
          </button>
        </div>
      </div>

      {/* 3. 分组标签栏 */}
      <div className="px-6 flex items-center space-x-6 border-b border-gray-200 dark:border-[#3d3d3d] bg-white dark:bg-[#1e1e1e]">
        {mockGroups.map(group => (
          <button
            key={group}
            onClick={() => setActiveGroup(group)}
            className={`py-3 text-sm font-medium relative transition-colors ${
              activeGroup === group 
                ? 'text-[#42b883]' 
                : 'text-gray-500 hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-200'
            }`}
          >
            {group}
            {activeGroup === group && (
              <div className="absolute bottom-0 left-0 w-full h-0.5 bg-[#42b883] rounded-t-full layout-id" />
            )}
          </button>
        ))}
        <button className="py-3 text-sm font-medium text-gray-400 hover:text-[#42b883] transition-colors flex items-center">
          <Plus size={14} className="mr-1" /> 新建分组
        </button>
      </div>

      {/* 4. 主内容区 (文件展示) */}
      <div className="flex-1 overflow-auto p-6 bg-gray-50 dark:bg-[#1e1e1e]" onClick={closeContextMenu}>
        
        {filteredFiles.length === 0 ? (
          <div className="h-full flex flex-col items-center justify-center text-gray-400">
            <Search size={48} className="mb-4 opacity-20" />
            <p>未找到匹配的文件</p>
          </div>
        ) : (
          viewMode === 'grid' ? (
            /* 网格视图 */
            <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-4">
              {filteredFiles.map(file => (
                <div 
                  key={file.id}
                  onContextMenu={(e) => handleContextMenu(e, file)}
                  className="group relative bg-white dark:bg-[#252525] border border-gray-200 dark:border-[#3d3d3d] rounded-lg p-4 hover:shadow-lg dark:hover:shadow-black/40 hover:border-[#42b883]/50 transition-all duration-200 cursor-pointer flex flex-col hover:-translate-y-1"
                >
                  <button 
                    onClick={(e) => { e.stopPropagation(); handleContextMenu(e, file); }}
                    className="absolute top-2 right-2 p-1.5 rounded opacity-0 group-hover:opacity-100 hover:bg-gray-100 dark:hover:bg-[#383838] transition-all text-gray-500"
                  >
                    <MoreVertical size={16} />
                  </button>
                  
                  <div className="flex-1 flex flex-col items-center justify-center py-4">
                    <file.icon size={48} className={`mb-3 ${file.color} opacity-90 group-hover:opacity-100 group-hover:scale-110 transition-transform duration-300`} strokeWidth={1.5} />
                    <h3 className="text-sm font-medium text-center line-clamp-2 leading-snug w-full px-2" title={file.name}>
                      {file.name}
                    </h3>
                  </div>
                  
                  <div className="mt-2 flex items-center justify-between text-[11px] text-gray-400 dark:text-gray-500 w-full pt-3 border-t border-gray-100 dark:border-[#333]">
                    <span className="truncate max-w-[80px]" title={file.group}>{file.group}</span>
                    <span>打开 {file.openCount} 次</span>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            /* 列表视图 */
            <div className="flex flex-col bg-white dark:bg-[#252525] rounded-lg border border-gray-200 dark:border-[#3d3d3d] overflow-hidden">
              <div className="flex items-center px-4 py-3 bg-gray-50 dark:bg-[#2d2d2d] border-b border-gray-200 dark:border-[#3d3d3d] text-xs font-semibold text-gray-500 uppercase tracking-wider">
                <div className="w-1/2">名称</div>
                <div className="w-1/6">分组</div>
                <div className="w-1/6">标签</div>
                <div className="w-1/6 text-right">最后打开</div>
              </div>
              {filteredFiles.map(file => (
                <div 
                  key={file.id} 
                  onContextMenu={(e) => handleContextMenu(e, file)}
                  className="flex items-center px-4 py-3 border-b border-gray-100 dark:border-[#333] hover:bg-gray-50 dark:hover:bg-[#2d2d2d] transition-colors group cursor-pointer"
                >
                  <div className="w-1/2 flex items-center pr-4">
                    <file.icon size={18} className={`${file.color} mr-3 flex-shrink-0`} />
                    <div className="flex flex-col truncate">
                      <span className="text-sm font-medium truncate">{file.name}</span>
                      <span className="text-[11px] text-gray-400 truncate mt-0.5">{file.path}</span>
                    </div>
                  </div>
                  <div className="w-1/6 text-sm text-gray-500">{file.group}</div>
                  <div className="w-1/6 flex flex-wrap gap-1">
                    {file.tags.slice(0,2).map(tag => (
                      <span key={tag} className="text-[10px] px-1.5 py-0.5 rounded bg-gray-100 dark:bg-[#383838] text-gray-600 dark:text-gray-300 border border-gray-200 dark:border-[#444]">{tag}</span>
                    ))}
                  </div>
                  <div className="w-1/6 text-right text-sm text-gray-400 flex items-center justify-end space-x-4">
                    <span>{file.lastOpen}</span>
                    <button 
                      onClick={(e) => { e.stopPropagation(); handleContextMenu(e, file); }}
                      className="p-1 rounded opacity-0 group-hover:opacity-100 hover:bg-gray-200 dark:hover:bg-[#444] transition-all text-gray-500"
                    >
                      <MoreVertical size={16} />
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )
        )}
      </div>

      {/* 5. 状态栏 */}
      <div className="h-10 px-4 flex items-center justify-between text-xs text-gray-500 border-t border-gray-200 dark:border-[#3d3d3d] bg-white dark:bg-[#252525]">
        <div>共 {filteredFiles.length} 个项目</div>
        <div className="flex items-center space-x-2 bg-gray-100 dark:bg-[#1e1e1e] p-1 rounded-md">
          <button 
            onClick={() => setViewMode('grid')}
            className={`p-1 rounded transition-colors ${viewMode === 'grid' ? 'bg-white dark:bg-[#383838] shadow-sm text-[#42b883]' : 'hover:text-gray-800 dark:hover:text-gray-200'}`}
          >
            <Grid size={14} />
          </button>
          <button 
            onClick={() => setViewMode('list')}
            className={`p-1 rounded transition-colors ${viewMode === 'list' ? 'bg-white dark:bg-[#383838] shadow-sm text-[#42b883]' : 'hover:text-gray-800 dark:hover:text-gray-200'}`}
          >
            <List size={14} />
          </button>
        </div>
      </div>

      {/* --- Context Menu (右键菜单) --- */}
      {contextMenu && (
        <div 
          className="fixed z-50 w-56 bg-white dark:bg-[#2d2d2d] rounded-lg shadow-xl border border-gray-200 dark:border-[#444] py-1 text-sm overflow-hidden animate-in fade-in zoom-in-95 duration-100"
          style={{ top: contextMenu.y, left: contextMenu.x }}
          onClick={(e) => e.stopPropagation()}
        >
          <div className="px-3 py-2 text-xs font-semibold text-gray-400 border-b border-gray-100 dark:border-[#444] truncate">
            {contextMenu.file.name}
          </div>
          
          <button className="w-full text-left px-4 py-2 hover:bg-gray-100 dark:hover:bg-[#383838] flex items-center text-gray-700 dark:text-gray-200 transition-colors">
            <PlayCircle size={14} className="mr-2" /> 打开
          </button>
          <button className="w-full text-left px-4 py-2 hover:bg-gray-100 dark:hover:bg-[#383838] flex items-center text-gray-700 dark:text-gray-200 transition-colors">
            <FolderInput size={14} className="mr-2" /> 在文件夹中显示
          </button>
          
          <div className="h-px bg-gray-100 dark:bg-[#444] my-1"></div>
          
          <button className="w-full text-left px-4 py-2 hover:bg-gray-100 dark:hover:bg-[#383838] flex items-center text-gray-700 dark:text-gray-200 transition-colors">
            <Edit3 size={14} className="mr-2" /> 编辑信息
          </button>
          <button className="w-full text-left px-4 py-2 hover:bg-gray-100 dark:hover:bg-[#383838] flex items-center text-gray-700 dark:text-gray-200 transition-colors">
            <Tag size={14} className="mr-2" /> 添加标签
          </button>
          
          <div className="h-px bg-gray-100 dark:bg-[#444] my-1"></div>
          
          {/* 核心特色功能：高亮展示 */}
          <button 
            onClick={() => openProcessManager(contextMenu.file)}
            className="w-full text-left px-4 py-2 bg-green-50/50 hover:bg-green-100 dark:bg-[#2d4a3e]/30 dark:hover:bg-[#2d4a3e] flex items-center text-[#42b883] font-medium transition-colors"
          >
            <Activity size={14} className="mr-2" /> 查看已打开的进程
          </button>
          
          <div className="h-px bg-gray-100 dark:bg-[#444] my-1"></div>
          
          <button className="w-full text-left px-4 py-2 hover:bg-red-50 dark:hover:bg-red-900/20 flex items-center text-red-500 transition-colors">
            <Trash2 size={14} className="mr-2" /> 移除收藏
          </button>
        </div>
      )}

      {/* --- Process Management Modal (进程管理对话框) --- */}
      {processModalFile && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm p-4 animate-in fade-in duration-200">
          <div className="bg-white dark:bg-[#252525] w-full max-w-lg rounded-xl shadow-2xl border border-gray-200 dark:border-[#3d3d3d] overflow-hidden flex flex-col transform transition-all animate-in zoom-in-95">
            
            {/* Header */}
            <div className="px-6 py-4 border-b border-gray-200 dark:border-[#3d3d3d] flex items-center justify-between bg-gray-50 dark:bg-[#2d2d2d]">
              <div className="flex items-center text-[#42b883]">
                <Activity size={20} className="mr-2" />
                <h2 className="text-base font-semibold text-gray-800 dark:text-gray-100">进程管理器</h2>
              </div>
              <button 
                onClick={() => setProcessModalFile(null)}
                className="text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 transition-colors p-1 rounded-md hover:bg-gray-200 dark:hover:bg-[#3d3d3d]"
              >
                <X size={18} />
              </button>
            </div>

            {/* Content */}
            <div className="p-6">
              <div className="mb-4">
                <p className="text-sm text-gray-500 dark:text-gray-400 mb-1">正在检查被占用的文件：</p>
                <div className="flex items-center font-medium text-sm text-gray-800 dark:text-gray-200 bg-gray-100 dark:bg-[#1e1e1e] p-3 rounded-md border border-gray-200 dark:border-[#333]">
                  <processModalFile.icon size={16} className={`${processModalFile.color} mr-2`} />
                  <span className="truncate">{processModalFile.name}</span>
                </div>
              </div>

              <div className="space-y-3">
                <h3 className="text-xs font-bold text-gray-400 uppercase tracking-wider mb-2">相关的系统进程 ({mockProcesses.length})</h3>
                
                {mockProcesses.map(process => (
                  <div key={process.pid} className="flex items-center justify-between p-3 rounded-lg border border-gray-200 dark:border-[#3d3d3d] hover:border-gray-300 dark:hover:border-[#555] bg-white dark:bg-[#252525] group transition-colors">
                    <div className="flex-1 min-w-0 mr-4">
                      <div className="flex items-center text-sm font-medium text-gray-800 dark:text-gray-200 mb-1">
                        <Box size={14} className="mr-1.5 text-blue-500" />
                        {process.name}
                        <span className="ml-2 text-xs font-normal text-gray-400 bg-gray-100 dark:bg-[#333] px-1.5 py-0.5 rounded">PID: {process.pid}</span>
                      </div>
                      <div className="text-xs text-gray-500 truncate" title={process.window}>
                        窗口: {process.window}
                      </div>
                    </div>
                    
                    {confirmClosePID === process.pid ? (
                      <div className="flex items-center space-x-2 animate-in slide-in-from-right-4">
                        <span className="text-xs text-red-500 font-medium">确认结束?</span>
                        <button 
                          onClick={() => setConfirmClosePID(null)}
                          className="px-2 py-1.5 text-xs bg-gray-100 hover:bg-gray-200 dark:bg-[#333] dark:hover:bg-[#444] rounded text-gray-600 dark:text-gray-300"
                        >取消</button>
                        <button className="px-2 py-1.5 text-xs bg-red-500 hover:bg-red-600 text-white rounded font-medium shadow-sm shadow-red-500/20">结束进程</button>
                      </div>
                    ) : (
                      <button 
                        onClick={() => setConfirmClosePID(process.pid)}
                        className="opacity-0 group-hover:opacity-100 px-3 py-1.5 text-xs font-medium text-red-500 border border-red-200 dark:border-red-900/50 hover:bg-red-50 dark:hover:bg-red-900/30 rounded transition-all"
                      >
                        关闭进程
                      </button>
                    )}
                  </div>
                ))}
              </div>
            </div>

            {/* Footer */}
            <div className="px-6 py-4 border-t border-gray-200 dark:border-[#3d3d3d] bg-gray-50 dark:bg-[#2d2d2d] flex justify-end space-x-3">
              <button 
                onClick={() => setProcessModalFile(null)}
                className="px-4 py-2 text-sm text-gray-600 dark:text-gray-300 hover:bg-gray-200 dark:hover:bg-[#3d3d3d] rounded-md transition-colors font-medium"
              >
                关闭
              </button>
              <button className="px-4 py-2 text-sm bg-red-500 hover:bg-red-600 text-white rounded-md transition-colors font-medium shadow-sm shadow-red-500/20">
                一键关闭所有
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Global Click Handler to close context menu */}
      {contextMenu && (
        <div className="fixed inset-0 z-40" onClick={closeContextMenu}></div>
      )}

    </div>
  );
}
```
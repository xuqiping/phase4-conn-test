<template>
  <div
    :class="['min-h-screen w-full flex flex-col font-sans transition-colors duration-300',
             currentTheme === 'dark' ? 'dark bg-dark-bg text-gray-200' : 'bg-gray-50 text-gray-800']"
    @click="closeContextMenu"
  >

    <!-- 1. 顶部标题栏 -->
    <div class="h-10 flex items-center justify-between px-4 select-none border-b border-gray-200 dark:border-dark-border bg-white dark:bg-dark-panel" data-tauri-drag-region>
      <div class="flex items-center space-x-2">
        <div class="w-5 h-5 rounded bg-primary flex items-center justify-center">
          <Box :size="12" class="text-white" />
        </div>
        <span class="text-xs font-semibold tracking-wide">File Keeper</span>
      </div>
      <div class="flex items-center space-x-3 text-gray-400">
        <Minus :size="14" class="hover:text-gray-600 dark:hover:text-white cursor-pointer" @click="minimizeWindow" />
        <Maximize2 :size="12" class="hover:text-gray-600 dark:hover:text-white cursor-pointer" @click="maximizeWindow" />
        <X :size="16" class="hover:text-red-500 cursor-pointer" @click="closeWindow" />
      </div>
    </div>

    <!-- 2. 工具栏 -->
    <div class="px-6 py-4 flex items-center justify-between bg-white dark:bg-dark-bg">
      <div class="relative w-96 group">
        <Search :size="16" class="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 group-focus-within:text-primary transition-colors" />
        <input
          type="text"
          placeholder="搜索文件、路径或标签..."
          v-model="fileStore.searchQuery"
          class="w-full pl-9 pr-4 py-2 bg-gray-100 dark:bg-dark-hover border border-transparent focus:border-primary focus:bg-white dark:focus:bg-dark-bg rounded-md outline-none text-sm transition-all duration-200 shadow-sm"
     />
      </div>

      <div class="flex items-center space-x-3">
        <AddFileButton />

      <button
          @click="handleAddFolder"
          class="flex items-center space-x-1 bg-gray-600 hover:bg-gray-700 text-white px-4 py-2 rounded-md text-sm font-medium transition-colors shadow-sm"
          title="添加文件夹"
        >
          <FolderPlus :size="16" />
          <span>添加文件夹</span>
        </button>

        <button
          @click="toggleTheme"
          class="p-2 rounded-md bg-gray-100 dark:bg-dark-hover hover:bg-gray-200 dark:hover:bg-[#383838] transition-colors"
          title="切换主题"
        >
          <Sun v-if="currentTheme === 'dark'" :size="18" />
          <Moon v-else :size="18" />
        </button>

        <button
          @click="showSettings = true"
          class="p-2 rounded-md bg-gray-100 dark:bg-dark-hover hover:bg-gray-200 dark:hover:bg-[#383838] transition-colors"
        >
          <Settings :size="18" />
        </button>
      </div>
    </div>

    <!-- Batch Operations Toolbar -->
    <transition name="fade">
      <div
        v-if="selectionStore.hasSelection"
        class="fixed bottom-6 left-1/2 -translate-x-1/2 z-50 bg-white dark:bg-dark-panel rounded-xl shadow-2xl border border-gray-200 dark:border-dark-border px-6 py-3 flex items-center space-x-4"
      >
        <span class="text-sm text-gray-600 dark:text-gray-300">
          已选择 <strong class="text-primary">{{ selectionStore.selectedCount }}</strong> 项
        </span>
        <div class="h-4 w-px bg-gray-300 dark:bg-gray-600"></div>
        <button
          @click="handleBatchOpen"
        class="flex items-center space-x-1 px-3 py-1.5 text-sm text-gray-700 dark:text-gray-200 hover:bg-gray-100 dark:hover:bg-dark-hover rounded-md transition-colors"
      >
          <FolderOpen :size="14" />
      <span>打开</span>
     </button>
        <button
          @click="showBatchMoveMenu = true"
          class="flex items-center space-x-1 px-3 py-1.5 text-sm text-gray-700 dark:text-gray-200 hover:bg-gray-100 dark:hover:bg-dark-hover rounded-md transition-colors"
        >
        <FolderInput :size="14" />
          <span>移动</span>
        </button>
        <button
          @click="handleBatchAddTag"
          class="flex items-center space-x-1 px-3 py-1.5 text-sm text-gray-700 dark:text-gray-200 hover:bg-gray-100 dark:hover:bg-dark-hover rounded-md transition-colors"
        >
          <Tag :size="14" />
      <span>添加标签</span>
        </button>
        <button
          @click="handleBatchDelete"
      class="flex items-center space-x-1 px-3 py-1.5 text-sm text-red-600 hover:bg-red-50 dark:hover:bg-red-900/20 rounded-md transition-colors"
        >
      <Trash2 :size="14" />
          <span>删除</span>
        </button>
        <div class="h-4 w-px bg-gray-300 dark:bg-gray-600"></div>
        <button
          @click="selectionStore.clearSelection"
          class="text-sm text-gray-400 hover:text-gray-600 dark:hover:text-gray-200 transition-colors"
        >
      取消
        </button>
      </div>
    </transition>

    <!-- Batch Move Menu -->
    <transition name="fade">
      <div
     v-if="showBatchMoveMenu"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm"
        @click="showBatchMoveMenu = false"
      >
        <div
          class="bg-white dark:bg-dark-panel rounded-xl shadow-2xl border border-gray-200 dark:border-dark-border p-4 min-w-[200px]"
          @click.stop
        >
          <h3 class="text-sm font-semibold mb-3 text-gray-800 dark:text-gray-100">移动到分组</h3>
          <div class="space-y-1">
            <button
              v-for="group in groupStore.customGroups"
              :key="group.id"
              @click="handleBatchMove(group.id)"
              class="w-full text-left px-3 py-2 text-sm rounded-md hover:bg-gray-100 dark:hover:bg-dark-hover transition-colors text-gray-700 dark:text-gray-200"
            >
              {{ group.name }}
         </button>
          </div>
        </div>
      </div>
    </transition>

    <!-- 3. 分组标签栏 -->
    <div class="px-6 flex items-center space-x-6 border-b border-gray-200 dark:border-dark-border bg-white dark:bg-dark-bg">
      <button
        v-for="group in groupStore.groups"
        :key="group.id"
        @click="groupStore.setCurrentGroup(group.id)"
        :class="['py-3 text-sm font-medium relative transition-colors',
                 groupStore.currentGroupId === group.id ? 'text-primary' : 'text-gray-500 hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-200']"
      >
        {{ group.name }}
        <div v-if="groupStore.currentGroupId === group.id" class="absolute bottom-0 left-0 w-full h-0.5 bg-primary rounded-t-full"></div>
      </button>
      <button class="py-3 text-sm font-medium text-gray-400 hover:text-primary transition-colors flex items-center" @click="handleAddGroup">
        <Plus :size="14" class="mr-1" /> 新建分组
      </button>
      <button
        class="py-3 text-sm font-medium text-gray-400 hover:text-primary transition-colors flex items-center ml-auto"
        @click="showGroupManager = true"
        title="管理分组"
      >
        <FolderCog :size="16" />
      </button>
    </div>

    <!-- 4. 主内容区 -->
    <div
      class="flex-1 overflow-auto p-6 bg-gray-50 dark:bg-dark-bg relative transition-colors duration-200"
      :class="{ 'bg-primary/5 dark:bg-primary/5': isDraggingOver }"
      @dragover="handleDragOver"
      @dragleave="handleDragLeave"
      @drop="handleDrop"
    >

      <!-- 空状态 -->
      <div v-if="fileStore.filteredFiles.length === 0" class="h-full flex flex-col items-center justify-center text-gray-400">
        <Search :size="48" class="mb-4 opacity-20" />
        <p>未找到匹配的文件</p>
      </div>

      <template v-else>
        <!-- 网格视图 -->
     <div v-if="viewMode === 'grid'" class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-4">
        <div
            v-for="file in fileStore.filteredFiles"
            :key="file.id"
         @contextmenu.prevent="handleContextMenu($event, file)"
        @click="handleCardClick($event, file)"
          @mouseenter="hoveredFileId = file.id"
       @mouseleave="hoveredFileId = null"
            class="group relative bg-white dark:bg-dark-panel border border-gray-200 dark:border-dark-border rounded-lg p-4 hover:shadow-lg dark:hover:shadow-black/40 hover:border-primary/50 transition-all duration-200 cursor-pointer flex flex-col hover:-translate-y-1"
          >
            <!-- Selection Checkbox -->
            <div
            v-if="selectionStore.hasSelection || file.id === hoveredFileId"
            class="absolute top-2 left-2 z-10"
           @click.stop="selectionStore.toggleSelection(file.id)"
          >
              <div
       :class="[
                  'w-5 h-5 rounded border-2 flex items-center justify-center cursor-pointer transition-all',
                  selectionStore.isSelected(file.id)
                  ? 'bg-primary border-primary'
                    : 'bg-white dark:bg-dark-panel border-gray-300 dark:border-gray-600 hover:border-primary'
                ]"
              >
          <Check v-if="selectionStore.isSelected(file.id)" :size="14" class="text-white" />
              </div>
            </div>

            <button
           @click.stop="handleContextMenu($event, file)"
            class="absolute top-2 right-2 p-1.5 rounded opacity-0 group-hover:opacity-100 hover:bg-gray-100 dark:hover:bg-[#383838] transition-all text-gray-500"
            >
              <MoreVertical :size="16" />
        </button>

            <div class="flex-1 flex flex-col items-center justify-center py-4">
              <component
                :is="getFileIcon(file.icon || file.type)"
                :size="48"
                :class="['mb-3 opacity-90 group-hover:opacity-100 group-hover:scale-110 transition-transform duration-300', getFileColor(file.icon || file.type)]"
                :stroke-width="1.5"
              />
              <h3
                class="text-sm font-medium text-center line-clamp-2 leading-snug w-full px-2"
                :title="file.name"
                v-html="highlightText(file.name, fileStore.searchQuery)"
              />
               </div>

          <!-- Tags -->
            <div v-if="file.tags && file.tags.length > 0" class="mt-2 flex flex-wrap gap-1 justify-center">
              <span
                v-for="tag in file.tags.slice(0, 3)"
             :key="tag"
                class="text-[10px] px-1.5 py-0.5 rounded bg-primary/10 text-primary border border-primary/20"
          >
                {{ tag }}
              </span>
              <span v-if="file.tags.length > 3" class="text-[10px] text-gray-400">
                +{{ file.tags.length - 3 }}
            </span>
        </div>

            <div class="mt-2 flex items-center justify-between text-[11px] text-gray-400 dark:text-gray-500 w-full pt-3 border-t border-gray-100 dark:border-[#333]">
            <span class="truncate max-w-[80px]" :title="getGroupName(file.groupId)">{{ getGroupName(file.groupId) }}</span>
           <span>打开 {{ file.openCount }} 次</span>
            </div>
          </div>
        </div>

        <!-- 列表视图 -->
        <div v-else class="flex flex-col bg-white dark:bg-dark-panel rounded-lg border border-gray-200 dark:border-dark-border overflow-hidden">
          <div class="flex items-center px-4 py-3 bg-gray-50 dark:bg-dark-hover border-b border-gray-200 dark:border-dark-border text-xs font-semibold text-gray-500 uppercase tracking-wider">
        <div class="w-1/2">名称</div>
            <div class="w-1/6">分组</div>
            <div class="w-1/6">标签</div>
            <div class="w-1/6 text-right">最后打开</div>
          </div>
          <div
            v-for="file in fileStore.filteredFiles"
        :key="file.id"
          @contextmenu.prevent="handleContextMenu($event, file)"
            @click="handleFileClick(file)"
            class="flex items-center px-4 py-3 border-b border-gray-100 dark:border-[#333] hover:bg-gray-50 dark:hover:bg-dark-hover transition-colors group cursor-pointer"
          >
            <div class="w-1/2 flex items-center pr-4">
              <component :is="getFileIcon(file.icon || file.type)" :size="18" :class="[getFileColor(file.icon || file.type), 'mr-3 flex-shrink-0']" />
            <div class="flex flex-col truncate">
              <span
                class="text-sm font-medium truncate"
                v-html="highlightText(file.name, fileStore.searchQuery)"
              />
                <span
                  class="text-[11px] text-gray-400 truncate mt-0.5"
                  v-html="highlightText(file.path, fileStore.searchQuery)"
                />
              </div>
            </div>
          <div class="w-1/6 text-sm text-gray-500">{{ getGroupName(file.groupId) }}</div>
            <div class="w-1/6 flex flex-wrap gap-1">
              <span v-for="tag in file.tags.slice(0, 2)" :key="tag" class="text-[10px] px-1.5 py-0.5 rounded bg-gray-100 dark:bg-[#383838] text-gray-600 dark:text-gray-300 border border-gray-200 dark:border-[#444]">
                {{ tag }}
              </span>
         </div>
            <div class="w-1/6 text-right text-sm text-gray-400 flex items-center justify-end space-x-4">
              <span>{{ formatLastOpened(file.lastOpened) }}</span>
              <button
                @click.stop="handleContextMenu($event, file)"
            class="p-1 rounded opacity-0 group-hover:opacity-100 hover:bg-gray-200 dark:hover:bg-[#444] transition-all text-gray-500"
              >
                <MoreVertical :size="16" />
              </button>
            </div>
          </div>
        </div>
      </template>
    </div>

    <!-- 5. 状态栏 -->
    <div class="h-10 px-4 flex items-center justify-between text-xs text-gray-500 border-t border-gray-200 dark:border-dark-border bg-white dark:bg-dark-panel">
      <div>共 {{ fileStore.filteredFiles.length }} 个项目</div>
      <div class="flex items-center space-x-2 bg-gray-100 dark:bg-dark-bg p-1 rounded-md">
        <button
          @click="setViewMode('grid')"
          :class="['p-1 rounded transition-colors', viewMode === 'grid' ? 'bg-white dark:bg-[#383838] shadow-sm text-primary' : 'hover:text-gray-800 dark:hover:text-gray-200']"
        >
          <Grid :size="14" />
        </button>
        <button
          @click="setViewMode('list')"
          :class="['p-1 rounded transition-colors', viewMode === 'list' ? 'bg-white dark:bg-[#383838] shadow-sm text-primary' : 'hover:text-gray-800 dark:hover:text-gray-200']"
        >
        <List :size="14" />
        </button>
      </div>
    </div>

    <!-- 右键菜单 -->
    <transition name="fade">
      <div
        v-if="contextMenu.show"
        class="fixed z-50 w-56 bg-white dark:bg-[#2d2d2d] rounded-lg shadow-xl border border-gray-200 dark:border-[#444] py-1 text-sm"
        :style="{ top: contextMenu.y + 'px', left: contextMenu.x + 'px' }"
        @click.stop
      >
        <div class="px-3 py-2 text-xs font-semibold text-gray-400 border-b border-gray-100 dark:border-[#444] truncate">
          {{ contextMenu.file?.name }}
        </div>

        <button class="w-full text-left px-4 py-2 hover:bg-gray-100 dark:hover:bg-[#383838] flex items-center text-gray-700 dark:text-gray-200 transition-colors" @click="handleMenuAction('open')">
        <PlayCircle :size="14" class="mr-2" /> 打开
        </button>
        <button class="w-full text-left px-4 py-2 hover:bg-gray-100 dark:hover:bg-[#383838] flex items-center text-gray-700 dark:text-gray-200 transition-colors" @click="handleMenuAction('show-in-folder')">
          <FolderInput :size="14" class="mr-2" /> 在文件夹中显示
        </button>

        <div class="h-px bg-gray-100 dark:bg-[#444] my-1"></div>

        <button class="w-full text-left px-4 py-2 hover:bg-gray-100 dark:hover:bg-[#383838] flex items-center text-gray-700 dark:text-gray-200 transition-colors" @click="handleMenuAction('edit')">
      <Edit3 :size="14" class="mr-2" /> 编辑信息
        </button>

        <!-- 移动到分组（带子菜单） -->
        <div
          class="relative"
          @mouseleave="showMoveToGroupSubmenu = false"
        >
          <button
            class="w-full text-left px-4 py-2 hover:bg-gray-100 dark:hover:bg-[#383838] flex items-center justify-between text-gray-700 dark:text-gray-200 transition-colors"
            @click.stop="showMoveToGroupSubmenu = !showMoveToGroupSubmenu"
            @mouseenter="showMoveToGroupSubmenu = true"
          >
            <span class="flex items-center">
              <FolderInput :size="14" class="mr-2" />
              移动到分组
            </span>
            <ChevronRight :size="14" class="text-gray-400" />
          </button>

          <!-- 子菜单 -->
          <div
            v-show="showMoveToGroupSubmenu"
            class="absolute top-0 py-1 w-44 bg-white dark:bg-[#2d2d2d] rounded-lg shadow-xl border border-gray-200 dark:border-[#444] text-sm z-10"
            :class="moveToGroupSubmenuOnLeft ? 'right-full mr-1' : 'left-full ml-1'"
          >
              <button
                v-for="group in groupStore.groups"
                :key="group.id"
                @click.stop="handleMoveToGroup(group.id)"
                class="w-full text-left px-4 py-2 hover:bg-gray-100 dark:hover:bg-[#383838] flex items-center text-gray-700 dark:text-gray-200 transition-colors"
              >
                <Check v-if="contextMenu.file?.groupId === group.id" :size="14" class="mr-2 text-primary" />
                <span v-else class="w-[22px] mr-2" />
                <span class="truncate">{{ group.name }}</span>
              </button>
         </div>
        </div>

     <button class="w-full text-left px-4 py-2 hover:bg-gray-100 dark:hover:bg-[#383838] flex items-center text-gray-700 dark:text-gray-200 transition-colors" @click="handleMenuAction('add-tag')">
          <Tag :size="14" class="mr-2" /> 添加标签
        </button>

        <div class="h-px bg-gray-100 dark:bg-[#444] my-1"></div>

        <button
          @click="handleMenuAction('close-processes')"
          class="w-full text-left px-4 py-2 hover:bg-gray-100 dark:hover:bg-[#383838] flex items-center text-gray-700 dark:text-gray-200 transition-colors"
        >
          <XCircle :size="14" class="mr-2" />
          关闭已打开的进程
        </button>

        <div class="h-px bg-gray-100 dark:bg-[#444] my-1"></div>

      <button class="w-full text-left px-4 py-2 hover:bg-red-50 dark:hover:bg-red-900/20 flex items-center text-red-500 transition-colors" @click="handleMenuAction('remove')">
          <Trash2 :size="14" class="mr-2" /> 移除收藏
        </button>
      </div>
    </transition>

    <!-- Settings Dialog -->
    <SettingsDialog
      :show="showSettings"
      @close="showSettings = false"
      @save="handleSaveSettings"
    />

    <!-- Process Manager Dialog -->
    <transition name="fade">
      <div
        v-if="showProcessManager"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm p-4"
        @click="showProcessManager = false"
      >
        <div
          class="bg-white dark:bg-dark-panel w-full max-w-2xl rounded-xl shadow-2xl border border-gray-200 dark:border-dark-border overflow-hidden"
          @click.stop
        >
          <!-- Header -->
          <div class="px-6 py-4 border-b border-gray-200 dark:border-dark-border flex items-center justify-between bg-gray-50 dark:bg-dark-hover">
            <div>
              <h2 class="text-base font-semibold text-gray-800 dark:text-gray-100">进程管理</h2>
              <p class="text-xs text-gray-500 mt-1">{{ processManagerFile?.name }}</p>
            </div>
            <button
              @click="showProcessManager = false"
              class="text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 transition-colors p-1 rounded-md hover:bg-gray-200 dark:hover:bg-[#3d3d3d]"
            >
              <X :size="18" />
            </button>
          </div>

          <!-- Content -->
          <div class="p-6">
            <!-- Loading State -->
            <div v-if="loadingProcesses" class="text-center py-8">
              <div class="inline-block animate-spin rounded-full h-8 w-8 border-b-2 border-primary"></div>
              <p class="text-sm text-gray-500 mt-2">正在扫描进程...</p>
            </div>

            <!-- Empty State -->
            <div v-else-if="fileProcesses.length === 0" class="text-center py-8">
              <FileX :size="48" class="mx-auto text-gray-300 mb-3" />
              <p class="text-gray-500">未找到打开此文件的进程</p>
            </div>

            <!-- Process List -->
            <div v-else class="space-y-2">
              <div
                v-for="process in fileProcesses"
                :key="process.pid"
                class="flex items-center justify-between p-4 rounded-lg border border-gray-200 dark:border-dark-border bg-gray-50 dark:bg-dark-hover"
              >
                <div class="flex-1 min-w-0">
                  <div class="flex items-center space-x-2">
                    <span class="text-sm font-medium text-gray-800 dark:text-gray-200">{{ process.name }}</span>
                    <span class="text-xs text-gray-400">PID: {{ process.pid }}</span>
                  </div>
                  <p v-if="process.windowTitle" class="text-xs text-gray-500 mt-1 truncate">{{ process.windowTitle }}</p>
                  <p v-if="process.path" class="text-xs text-gray-400 mt-1 truncate">{{ process.path }}</p>
                </div>
                <button
                  @click="handleCloseProcess(process.pid)"
                  class="ml-4 px-3 py-1.5 text-sm text-red-600 hover:bg-red-50 dark:hover:bg-red-900/20 rounded-md transition-colors flex items-center space-x-1"
                >
                  <XCircle :size="14" />
                  <span>关闭</span>
                </button>
              </div>
            </div>
          </div>

          <!-- Footer -->
          <div class="px-6 py-4 border-t border-gray-200 dark:border-dark-border bg-gray-50 dark:bg-dark-hover flex justify-between">
            <button
              @click="loadFileProcesses(processManagerFile!.path)"
              :disabled="loadingProcesses"
              class="px-4 py-2 text-sm text-gray-600 dark:text-gray-300 hover:bg-gray-200 dark:hover:bg-[#3d3d3d] rounded-md transition-colors font-medium disabled:opacity-50"
            >
              刷新
            </button>
            <button
              @click="showProcessManager = false"
              class="px-4 py-2 text-sm bg-primary hover:bg-[#369b6e] text-white rounded-md transition-colors font-medium shadow-sm shadow-primary/20"
            >
              关闭
            </button>
          </div>
        </div>
      </div>
    </transition>

    <!-- 新建分组对话框 -->
    <transition name="fade">
   <div v-if="showAddGroupDialog" class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm p-4" @click="showAddGroupDialog = false">
        <div class="bg-white dark:bg-dark-panel w-full max-w-md rounded-xl shadow-2xl border border-gray-200 dark:border-dark-border overflow-hidden" @click.stop>
          <div class="px-6 py-4 border-b border-gray-200 dark:border-dark-border">
            <h2 class="text-lg font-semibold text-gray-800 dark:text-gray-100">新建分组</h2>
          </div>
          <div class="p-6">
            <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">分组名称</label>
            <input
              v-model="newGroupName"
              type="text"
              placeholder="请输入分组名称"
       class="w-full px-3 py-2 bg-gray-100 dark:bg-dark-hover border border-gray-200 dark:border-dark-border focus:border-primary focus:bg-white dark:focus:bg-dark-bg rounded-md outline-none text-sm transition-all"
              @keyup.enter="handleCreateGroup"
            />
          </div>
          <div class="px-6 py-4 border-t border-gray-200 dark:border-dark-border bg-gray-50 dark:bg-dark-hover flex justify-end space-x-3">
        <button
            @click="showAddGroupDialog = false"
              class="px-4 py-2 text-sm text-gray-600 dark:text-gray-300 hover:bg-gray-200 dark:hover:bg-[#3d3d3d] rounded-md transition-colors font-medium"
            >
       取消
            </button>
            <button
          @click="handleCreateGroup"
              class="px-4 py-2 text-sm bg-primary hover:bg-[#369b6e] text-white rounded-md transition-colors font-medium shadow-sm shadow-primary/20"
         >
            确定
            </button>
       </div>
        </div>
      </div>
    </transition>

    <!-- 分组管理对话框 -->
    <GroupManager
      :visible="showGroupManager"
      @close="showGroupManager = false"
      @add-group="handleAddGroup"
    />

    <!-- 编辑文件对话框 -->
    <EditFileDialog
      v-if="editingFile"
      :visible="!!editingFile"
      :file="editingFile"
      @close="editingFile = null"
      @saved="handleFileSaved"
    />

    <!-- 全局点击处理 -->
    <div v-if="contextMenu.show" class="fixed inset-0 z-40" @click="closeContextMenu"></div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { onKeyStroke } from '@vueuse/core'
import { getCurrentWindow } from '@tauri-apps/api/window'
import { registerGlobalShortcut, unregisterGlobalShortcut } from './api/shortcuts'
import {
  Search,
  Plus,
  FolderPlus,
  Settings,
  X,
  Maximize2,
  Minus,
  Box,
  Grid,
  List,
  Moon,
  Sun,
  PlayCircle,
  ChevronRight,
  Check,
  FolderInput,
  FolderCog,
  Edit3,
  Tag,
  Trash2,
  MoreVertical,
  FileText,
  Folder,
  Image,
  Code,
  FolderOpen,
  XCircle,
  FileX
} from 'lucide-vue-next'
import { useFileStore } from './stores/fileStore'
import { useGroupStore } from './stores/groupStore'
import { useSettingsStore } from './stores/settingsStore'
import { useSelectionStore } from './stores/selectionStore'
import { openFile, showInFolder } from './api/files'
import { findFileProcesses, closeProcess } from './api/processes'
import GroupManager from './components/GroupManager.vue'
import AddFileButton from './components/AddFileButton.vue'
import EditFileDialog from './components/EditFileDialog.vue'
import SettingsDialog from './components/SettingsDialog.vue'
import { deriveIconFromExt, resolveGroupId } from './utils/file'
import { highlightText } from './utils/highlight'
import type { FileItem } from './types/file'
import type { ProcessInfo } from './types/process'

const fileStore = useFileStore()
const groupStore = useGroupStore()
const settingsStore = useSettingsStore()
const selectionStore = useSelectionStore()

// Hover state for checkboxes
const hoveredFileId = ref<string | null>(null)

// Batch operations
const showBatchMoveMenu = ref(false)
const showSettings = ref(false)

function handleBatchOpen() {
  const ids = Array.from(selectionStore.selectedIds)
  fileStore.batchOpen(ids)
  selectionStore.clearSelection()
}

function handleBatchDelete() {
  const ids = Array.from(selectionStore.selectedIds)
  const confirmed = confirm(`确定删除选中的 ${ids.length} 个项目？`)
  if (confirmed) {
    fileStore.batchDelete(ids)
    selectionStore.clearSelection()
  }
}

function handleBatchMove(targetGroupId: string) {
  const ids = Array.from(selectionStore.selectedIds)
  fileStore.batchMove(ids, targetGroupId)
  selectionStore.clearSelection()
  showBatchMoveMenu.value = false
}

function handleBatchAddTag() {
  const tagName = prompt('请输入标签名称（最多20个字符）：')
  if (!tagName) return

  if (tagName.length > 20) {
    alert('标签名称不能超过20个字符')
    return
  }

  const ids = Array.from(selectionStore.selectedIds)
  fileStore.batchAddTags(ids, [tagName])
  selectionStore.clearSelection()
}

// Theme
const currentTheme = computed(() => settingsStore.settings.theme)

watch(currentTheme, (newTheme) => {
  if (newTheme === 'dark') {
    document.documentElement.classList.add('dark')
  } else {
    document.documentElement.classList.remove('dark')
  }
}, { immediate: true })

function toggleTheme() {
  settingsStore.toggleTheme()
}

// View Mode
const viewMode = computed(() => settingsStore.settings.defaultView)

function setViewMode(mode: 'grid' | 'list') {
  settingsStore.setViewMode(mode)
}

// Add Folder Handler
async function handleAddFolder() {
  try {
    const { pickFolder, validatePath } = await import('./api/files')
    const selectedPath = await pickFolder()

    if (!selectedPath) {
      return
    }

    const isValid = await validatePath(selectedPath)
    if (!isValid) {
      alert('路径不存在或无法访问')
      return
    }

    const name = selectedPath.split(/[/\\]/).pop() || selectedPath

    const newItem = fileStore.addFile({
      name,
      path: selectedPath,
      type: 'folder',
      icon: 'folder',
      tags: [],
      groupId: resolveGroupId(
        groupStore.currentGroupId,
     groupStore.customGroups[0]?.id
      )
    })

    if (!newItem) {
      alert('该文件夹已存在')
      return
    }

    console.log(`已添加文件夹: ${name}`)
  } catch (error) {
    console.error('添加文件夹失败:', error)
    alert(`添加文件夹失败: ${error}`)
  }
}

// Drag and Drop
const isDraggingOver = ref(false)

function handleDragOver(e: DragEvent) {
  e.preventDefault()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy'
}

function handleDragLeave(_e: DragEvent) {
  // Handled by Tauri onDragDropEvent
}

function handleDrop(e: DragEvent) {
  // Prevent default browser behavior; actual file drop is handled by Tauri
  e.preventDefault()
}

async function handleTauriDroppedPaths(paths: string[]) {
  for (const filePath of paths) {
    await processDroppedPath(filePath)
  }
}

async function processDroppedPath(filePath: string) {
  try {
    const { validatePath } = await import('./api/files')
    const isValid = await validatePath(filePath)
    if (!isValid) {
      console.warn(`拖拽路径无效: ${filePath}`)
      return
    }
    // Determine if it's a file or folder based on path (no extension usually means folder, but check via stat)
    const { stat } = await import('@tauri-apps/plugin-fs')
    let isFolder = false
    try {
      const info = await stat(filePath)
      isFolder = info.isDirectory
    } catch {
      // Fallback: check if there's a file extension
      const lastSegment = filePath.split(/[/\\]/).pop() || ''
      isFolder = !lastSegment.includes('.')
    }

    const name = filePath.split(/[/\\]/).pop() || filePath
    const type: 'file' | 'folder' = isFolder ? 'folder' : 'file'
    const icon = isFolder ? 'folder' : deriveIconFromExt(name)

    const newItem = fileStore.addFile({
      name,
      path: filePath,
      type,
      icon,
      tags: [],
      groupId: resolveGroupId(
        groupStore.currentGroupId,
        groupStore.customGroups[0]?.id
      )
    })

    if (!newItem) {
    console.warn(`项目已存在: ${filePath}`)
    }
  } catch (error) {
    console.error('拖拽处理失败:', error)
  }
}


// Window Controls
const appWindow = getCurrentWindow()

async function minimizeWindow() {
  await appWindow.minimize()
}

async function maximizeWindow() {
  const isMaximized = await appWindow.isMaximized()
  if (isMaximized) {
    await appWindow.unmaximize()
  } else {
    await appWindow.maximize()
  }
}

async function closeWindow() {
  if (settingsStore.settings.minimizeToTray) {
    await appWindow.hide()
  } else {
    await appWindow.close()
  }
}

async function handleSaveSettings(settings: { globalShortcut: string; minimizeToTray: boolean; theme: 'light' | 'dark' }) {
  const oldShortcut = settingsStore.settings.globalShortcut

  // Update settings
  settingsStore.updateSettings(settings)

  // Re-register global shortcut if changed
  if (settings.globalShortcut !== oldShortcut) {
    try {
      // Unregister old shortcut
      if (oldShortcut) {
        await unregisterGlobalShortcut(oldShortcut)
      }

      // Register new shortcut
      if (settings.globalShortcut) {
        await registerGlobalShortcut(settings.globalShortcut, async () => {
          if (shortcutHandling) return; shortcutHandling = true; try {
          const isVisible = await appWindow.isVisible()
      if (isVisible) { await appWindow.hide() } else { await appWindow.show(); await appWindow.setFocus() }
          } finally { setTimeout(() => { shortcutHandling = false }, 300) }
        })
        console.log(`Global shortcut updated: ${settings.globalShortcut}`)
      }
    } catch (error) {
      console.error('Failed to update global shortcut:', error)
      alert(`快捷键更新失败: ${error}`)
    }
  }

  showSettings.value = false
}

// Global Shortcut Registration & Tauri DnD
let dndUnlisten: (() => void) | null = null
let closeRequestedUnlisten: (() => void) | null = null
let shortcutHandling = false // Prevent double-trigger

onMounted(async () => {
  // Intercept window close event to minimize to tray
  closeRequestedUnlisten = await appWindow.onCloseRequested(async (event) => {
    console.log('Close requested event triggered')
    console.log('minimizeToTray setting:', settingsStore.settings.minimizeToTray)
    if (settingsStore.settings.minimizeToTray) {
      console.log('Preventing close and hiding window')
      event.preventDefault()
      await appWindow.hide()
    } else {
      console.log('Allowing window to close')
    }
  })
  console.log('Close requested listener registered')

  const shortcut = settingsStore.settings.globalShortcut
  console.log('Attempting to register global shortcut:', shortcut)
  try {
    await registerGlobalShortcut(shortcut, async () => {
      if (shortcutHandling) {
        console.log('Shortcut already handling, ignoring duplicate trigger')
        return
      }

      shortcutHandling = true
      console.log('Global shortcut triggered!')

      try {
        const isVisible = await appWindow.isVisible()
        console.log('Window visible:', isVisible)
        if (isVisible) {
          await appWindow.hide()
        console.log('Window hidden')
        } else {
          await appWindow.show()
        await appWindow.setFocus()
          console.log('Window shown and focused')
        }
      } finally {
        // Reset flag after a short delay
        setTimeout(() => {
          shortcutHandling = false
        }, 300)
      }
    })
    console.log(`Global shortcut registered successfully: ${shortcut}`)
  } catch (error) {
    console.error('Failed to register global shortcut:', error)
    alert(`快捷键注册失败: ${error}`)
  }

  // Tauri native drag-drop (provides real file paths)
  try {
    dndUnlisten = await appWindow.onDragDropEvent((event) => {
      if (event.payload.type === 'enter' || event.payload.type === 'over') {
        isDraggingOver.value = true
      } else if (event.payload.type === 'leave') {
        isDraggingOver.value = false
      } else if (event.payload.type === 'drop') {
        isDraggingOver.value = false
        const paths = (event.payload as any).paths as string[] | undefined
        if (paths && paths.length > 0) {
        handleTauriDroppedPaths(paths)
        }
      }
    })
  } catch (error) {
    console.error('Failed to register drag-drop listener:', error)
  }
})
onUnmounted(async () => {
  const shortcut = settingsStore.settings.globalShortcut
  try {
    await unregisterGlobalShortcut(shortcut)
  } catch (error) {
    console.error('Failed to unregister shortcut:', error)
  }
  if (dndUnlisten) {
    dndUnlisten()
    dndUnlisten = null
  }
  if (closeRequestedUnlisten) {
    closeRequestedUnlisten()
    closeRequestedUnlisten = null
  }
})

// Keyboard Shortcuts for Batch Operations
// Ctrl+A to select all visible files
onKeyStroke('a', (e) => {
  if (e.ctrlKey || e.metaKey) {
    e.preventDefault()
    const visibleIds = fileStore.filteredFiles.map(f => f.id)
    selectionStore.selectAll(visibleIds)
  }
})

// Escape to clear selection
onKeyStroke('Escape', () => {
  if (selectionStore.hasSelection) {
    selectionStore.clearSelection()
  }
  if (contextMenu.value.show) {
    closeContextMenu()
  }
})

// Delete key to batch delete
onKeyStroke('Delete', () => {
  if (selectionStore.hasSelection) {
    handleBatchDelete()
  }
})

// File Operations
const editingFile = ref<FileItem | null>(null)

// Process Management
const showProcessManager = ref(false)
const processManagerFile = ref<FileItem | null>(null)
const fileProcesses = ref<ProcessInfo[]>([])
const loadingProcesses = ref(false)

async function handleFileClick(file: FileItem) {
  try {
    fileStore.recordOpen(file.id)
    await openFile(file.path)
    console.log(`已打开: ${file.name}`)
  } catch (error) {
    console.error(`打开失败: ${error}`)
    alert(`打开失败: ${error}`)
  }
}

function handleCardClick(event: MouseEvent, file: FileItem) {
  // Check if Ctrl/Cmd key is pressed for multi-select
  if (event.ctrlKey || event.metaKey) {
    event.preventDefault()
    selectionStore.toggleSelection(file.id)
  } else {
  // Normal click - open file
    handleFileClick(file)
  }
}

async function handleShowInFolder(file: FileItem) {
  try {
    await showInFolder(file.path)
    console.log(`已在文件夹中显示: ${file.name}`)
  } catch (error) {
    console.error(`打开文件夹失败: ${error}`)
    alert(`打开文件夹失败: ${error}`)
  }
}

// Context Menu
const contextMenu = ref({
  show: false,
  x: 0,
  y: 0,
  file: null as FileItem | null
})

// 移动到分组子菜单
const showMoveToGroupSubmenu = ref(false)
const moveToGroupSubmenuOnLeft = ref(false)

function handleContextMenu(event: MouseEvent, file: FileItem) {
  event.preventDefault()

  // Prevent context menu from going off-screen
  let x = event.clientX
  let y = event.clientY

  // Simple bounds checking (assuming menu is roughly 224x280)
  if (window.innerWidth - x < 224) x = window.innerWidth - 224
  if (window.innerHeight - y < 280) y = window.innerHeight - 280

  contextMenu.value = {
    show: true,
    x,
    y,
    file
  }

  // 子菜单位置边界检测
  moveToGroupSubmenuOnLeft.value = (x + 224 + 176 > window.innerWidth)
}

function closeContextMenu() {
  contextMenu.value.show = false
  showMoveToGroupSubmenu.value = false
}

function handleFileSaved(updates: Partial<FileItem>) {
  if (editingFile.value) {
    fileStore.updateFile(editingFile.value.id, updates)
    editingFile.value = null
  }
}

function handleMoveToGroup(targetGroupId: string) {
  const file = contextMenu.value.file
  if (!file) return
  if (file.groupId === targetGroupId) {
    closeContextMenu()
    return
  }
  fileStore.updateFile(file.id, { groupId: targetGroupId })
  closeContextMenu()
}

function handleMenuAction(action: string) {
  const file = contextMenu.value.file
  if (!file) return

  switch (action) {
  case 'open':
      handleFileClick(file)
      break
    case 'show-in-folder':
      handleShowInFolder(file)
    break
    case 'edit':
      editingFile.value = file
      closeContextMenu()
      return
    case 'add-tag':
      handleAddTag(file)
      break
    case 'close-processes':
      handleShowProcesses(file)
      break
    case 'remove':
      handleRemoveFile(file)
      break
  }

  closeContextMenu()
}

function handleAddTag(file: FileItem) {
  const tagName = prompt('请输入标签名称（最多20个字符）：')
  if (!tagName) return

  if (tagName.length > 20) {
    alert('标签名称不能超过20个字符')
    return
  }

  const currentTags = file.tags || []
  if (currentTags.includes(tagName)) {
    alert('该标签已存在')
    return
  }

  if (currentTags.length >= 10) {
    alert('最多只能添加10个标签')
    return
  }

  fileStore.updateFile(file.id, {
    tags: [...currentTags, tagName]
  })
}

async function handleShowProcesses(file: FileItem) {
  processManagerFile.value = file
  showProcessManager.value = true
  await loadFileProcesses(file.path)
}

async function loadFileProcesses(filePath: string) {
  loadingProcesses.value = true
  try {
    fileProcesses.value = await findFileProcesses(filePath)
  } catch (error) {
    console.error('Failed to load processes:', error)
    alert(`加载进程失败: ${error}`)
  } finally {
    loadingProcesses.value = false
  }
}

async function handleCloseProcess(pid: number) {
  const confirmed = confirm(`确定关闭进程 PID ${pid}？`)
  if (!confirmed) return

  try {
    await closeProcess(pid)
    // Reload processes after closing
    if (processManagerFile.value) {
      await loadFileProcesses(processManagerFile.value.path)
    }
  } catch (error) {
    console.error('Failed to close process:', error)
    alert(`关闭进程失败: ${error}`)
  }
}

function handleRemoveFile(file: FileItem) {
  fileStore.removeFile(file.id)
  console.log(`已移除: ${file.name}`)
}

// Group Management
const showAddGroupDialog = ref(false)
const showGroupManager = ref(false)
const newGroupName = ref('')

function handleAddGroup() {
  showAddGroupDialog.value = true
  newGroupName.value = ''
}
function handleCreateGroup() {
  if (!newGroupName.value.trim()) {
    console.error('请输入分组名称')
    return
  }

  groupStore.addGroup(newGroupName.value.trim())
  console.log(`已创建分组: ${newGroupName.value}`)
  showAddGroupDialog.value = false
  newGroupName.value = ''
}

// Helper Functions
function getGroupName(groupId: string): string {
  const group = groupStore.groups.find(g => g.id === groupId)
  return group?.name || '未分组'
}

function getFileIcon(type: string) {
  const iconMap: Record<string, any> = {
    word: FileText,
    excel: FileText,
    design: Box,
    folder: Folder,
    image: Image,
    code: Code,
    file: FileText
  }
  return iconMap[type] || FileText
}

function getFileColor(type: string): string {
  const colorMap: Record<string, string> = {
    word: 'text-blue-500',
    excel: 'text-green-600',
    design: 'text-purple-500',
    folder: 'text-yellow-500',
    image: 'text-orange-500',
    code: 'text-yellow-600',
    file: 'text-gray-500'
  }
  return colorMap[type] || 'text-gray-500'
}

function formatLastOpened(timestamp?: number): string {
  if (!timestamp) return '从未打开'

  const now = Date.now()
  const diff = now - timestamp
  const minutes = Math.floor(diff / 60000)
  const hours = Math.floor(diff / 3600000)
  const days = Math.floor(diff / 86400000)

  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes}分钟前`
  if (hours < 24) return `${hours}小时前`
  if (days < 7) return `${days}天前`
  if (days < 30) return `${Math.floor(days / 7)}周前`
  return `${Math.floor(days / 30)}个月前`
}
</script>

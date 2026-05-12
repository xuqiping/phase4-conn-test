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
          @click="toggleTheme"
          class="p-2 rounded-md bg-gray-100 dark:bg-dark-hover hover:bg-gray-200 dark:hover:bg-[#383838] transition-colors"
          title="切换主题"
        >
          <Sun v-if="currentTheme === 'dark'" :size="18" />
          <Moon v-else :size="18" />
        </button>

        <button class="p-2 rounded-md bg-gray-100 dark:bg-dark-hover hover:bg-gray-200 dark:hover:bg-[#383838] transition-colors">
          <Settings :size="18" />
        </button>
      </div>
    </div>

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
      <button
        class="py-3 text-sm font-medium text-gray-400 hover:text-primary transition-colors flex items-center ml-auto"
        @click="showGroupManager = true"
        title="管理分组"
      >
        <FolderCog :size="16" />
      </button>
    </div>

    <!-- 4. 主内容区 -->
    <div class="flex-1 overflow-auto p-6 bg-gray-50 dark:bg-dark-bg">

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
          @click="handleFileClick(file)"
            class="group relative bg-white dark:bg-dark-panel border border-gray-200 dark:border-dark-border rounded-lg p-4 hover:shadow-lg dark:hover:shadow-black/40 hover:border-primary/50 transition-all duration-200 cursor-pointer flex flex-col hover:-translate-y-1"
          >
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
              <h3 class="text-sm font-medium text-center line-clamp-2 leading-snug w-full px-2" :title="file.name">
                {{ file.name }}
              </h3>
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
              <span class="text-sm font-medium truncate">{{ file.name }}</span>
                <span class="text-[11px] text-gray-400 truncate mt-0.5">{{ file.path }}</span>
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
        class="fixed z-50 w-56 bg-white dark:bg-[#2d2d2d] rounded-lg shadow-xl border border-gray-200 dark:border-[#444] py-1 text-sm overflow-hidden"
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
          @mouseenter="showMoveToGroupSubmenu = true"
          @mouseleave="showMoveToGroupSubmenu = false"
        >
          <button
            class="w-full text-left px-4 py-2 hover:bg-gray-100 dark:hover:bg-[#383838] flex items-center justify-between text-gray-700 dark:text-gray-200 transition-colors"
          >
            <span class="flex items-center">
              <FolderInput :size="14" class="mr-2" />
              移动到分组
            </span>
            <ChevronRight :size="14" class="text-gray-400" />
          </button>

          <!-- 子菜单 -->
          <transition name="fade">
            <div
              v-if="showMoveToGroupSubmenu"
              :class="[
                'absolute top-0 py-1 w-44 bg-white dark:bg-[#2d2d2d] rounded-lg shadow-xl border border-gray-200 dark:border-[#444] text-sm',
                moveToGroupSubmenuOnLeft ? 'right-full mr-1' : 'left-full ml-1'
              ]"
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
          </transition>
        </div>

        <button class="w-full text-left px-4 py-2 hover:bg-gray-100 dark:hover:bg-[#383838] flex items-center text-gray-700 dark:text-gray-200 transition-colors" @click="handleMenuAction('add-tag')">
          <Tag :size="14" class="mr-2" /> 添加标签
        </button>

        <div class="h-px bg-gray-100 dark:bg-[#444] my-1"></div>

        <button
          @click="handleMenuAction('view-processes')"
          class="w-full text-left px-4 py-2 bg-green-50/50 hover:bg-green-100 dark:bg-[#2d4a3e]/30 dark:hover:bg-[#2d4a3e] flex items-center text-primary font-medium transition-colors"
        >
          <Activity :size="14" class="mr-2" /> 查看已打开的进程
        </button>

        <div class="h-px bg-gray-100 dark:bg-[#444] my-1"></div>

      <button class="w-full text-left px-4 py-2 hover:bg-red-50 dark:hover:bg-red-900/20 flex items-center text-red-500 transition-colors" @click="handleMenuAction('remove')">
          <Trash2 :size="14" class="mr-2" /> 移除收藏
        </button>
      </div>
    </transition>

    <!-- 进程管理对话框 -->
    <transition name="fade">
      <div v-if="showProcessManager" class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm p-4" @click="showProcessManager = false">
        <div class="bg-white dark:bg-dark-panel w-full max-w-lg rounded-xl shadow-2xl border border-gray-200 dark:border-dark-border overflow-hidden flex flex-col transform transition-all" @click.stop>

          <!-- Header -->
          <div class="px-6 py-4 border-b border-gray-200 dark:border-dark-border flex items-center justify-between bg-gray-50 dark:bg-dark-hover">
            <div class="flex items-center text-primary">
        <Activity :size="20" class="mr-2" />
              <h2 class="text-base font-semibold text-gray-800 dark:text-gray-100">进程管理器</h2>
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
            <div class="mb-4">
          <p class="text-sm text-gray-500 dark:text-gray-400 mb-1">正在检查被占用的文件：</p>
          <div class="flex items-center font-medium text-sm text-gray-800 dark:text-gray-200 bg-gray-100 dark:bg-dark-bg p-3 rounded-md border border-gray-200 dark:border-[#333]">
                <component :is="getFileIcon(selectedFile?.type || 'file')" :size="16" :class="[getFileColor(selectedFile?.type || 'file'), 'mr-2']" />
                <span class="truncate">{{ selectedFile?.name }}</span>
              </div>
            </div>

            <div class="space-y-3">
              <h3 class="text-xs font-bold text-gray-400 uppercase tracking-wider mb-2">相关的系统进程 ({{ currentProcesses.length }})</h3>

            <div v-for="process in currentProcesses" :key="process.pid" class="flex items-center justify-between p-3 rounded-lg border border-gray-200 dark:border-dark-border hover:border-gray-300 dark:hover:border-[#555] bg-white dark:bg-dark-panel group transition-colors">
          <div class="flex-1 min-w-0 mr-4">
           <div class="flex items-center text-sm font-medium text-gray-800 dark:text-gray-200 mb-1">
                  <Box :size="14" class="mr-1.5 text-blue-500" />
             {{ process.name }}
                    <span class="ml-2 text-xs font-normal text-gray-400 bg-gray-100 dark:bg-[#333] px-1.5 py-0.5 rounded">PID: {{ process.pid }}</span>
           </div>
                  <div class="text-xs text-gray-500 truncate" :title="process.windowTitle">
               窗口: {{ process.windowTitle }}
          </div>
        </div>

              <div v-if="confirmClosePID === process.pid" class="flex items-center space-x-2">
                  <span class="text-xs text-red-500 font-medium">确认结束?</span>
                  <button
                @click="confirmClosePID = null"
           class="px-2 py-1.5 text-xs bg-gray-100 hover:bg-gray-200 dark:bg-[#333] dark:hover:bg-[#444] rounded text-gray-600 dark:text-gray-300"
                  >取消</button>
            <button @click="handleKillProcess(process.pid)" class="px-2 py-1.5 text-xs bg-red-500 hover:bg-red-600 text-white rounded font-medium shadow-sm shadow-red-500/20">结束</button>
                </div>
            <button
                  v-else
             @click="confirmClosePID = process.pid"
                  class="opacity-0 group-hover:opacity-100 px-3 py-1.5 text-xs font-medium text-red-500 border border-red-200 dark:border-red-900/50 hover:bg-red-50 dark:hover:bg-red-900/30 rounded transition-all"
       >
                  关闭进程
                </button>
              </div>
         </div>
          </div>

          <!-- Footer -->
          <div class="px-6 py-4 border-t border-gray-200 dark:border-dark-border bg-gray-50 dark:bg-dark-hover flex justify-end space-x-3">
          <button
              @click="showProcessManager = false"
              class="px-4 py-2 text-sm text-gray-600 dark:text-gray-300 hover:bg-gray-200 dark:hover:bg-[#3d3d3d] rounded-md transition-colors font-medium"
            >
            关闭
            </button>
            <button @click="handleKillAllProcesses" class="px-4 py-2 text-sm bg-red-500 hover:bg-red-600 text-white rounded-md transition-colors font-medium shadow-sm shadow-red-500/20">
              一键关闭所有
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

    <!-- 分组管理对话框 -->
    <GroupManager
      :visible="showGroupManager"
      @close="showGroupManager = false"
      @add-group="handleAddGroup"
    />

    <!-- 全局点击处理 -->
    <div v-if="contextMenu.show" class="fixed inset-0 z-40" @click="closeContextMenu"></div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { getCurrentWindow } from '@tauri-apps/api/window'
import {
  Search,
  Plus,
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
  Activity,
  Trash2,
  MoreVertical,
  FileText,
  Folder,
  Image,
  Code
} from 'lucide-vue-next'
import { useFileStore } from './stores/fileStore'
import { useGroupStore } from './stores/groupStore'
import { useSettingsStore } from './stores/settingsStore'
import { openFile, showInFolder } from './api/files'
import GroupManager from './components/GroupManager.vue'
import AddFileButton from './components/AddFileButton.vue'
import type { FileItem } from './types/file'
import type { ProcessInfo } from './types/process'

const fileStore = useFileStore()
const groupStore = useGroupStore()
const settingsStore = useSettingsStore()

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

// Window Controls
const appWindow = getCurrentWindow()

function minimizeWindow() {
  appWindow.minimize()
}

function maximizeWindow() {
  appWindow.toggleMaximize()
}

function closeWindow() {
  appWindow.close()
}

// File Operations
const selectedFile = ref<FileItem | null>(null)
const showProcessManager = ref(false)
const currentProcesses = ref<ProcessInfo[]>([])
const confirmClosePID = ref<number | null>(null)

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
  selectedFile.value = file

  // 子菜单位置边界检测
  moveToGroupSubmenuOnLeft.value = (x + 224 + 176 > window.innerWidth)
}

function closeContextMenu() {
  contextMenu.value.show = false
  showMoveToGroupSubmenu.value = false
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
      console.log('编辑信息功能待实现')
      break
    case 'add-tag':
      console.log('添加标签功能待实现')
      break
    case 'view-processes':
      handleViewProcesses(file)
      break
    case 'remove':
      handleRemoveFile(file)
      break
  }

  closeContextMenu()
}

function handleViewProcesses(file: FileItem) {
  selectedFile.value = file
  // Mock processes for demo
  currentProcesses.value = [
    {
      pid: 14235,
      name: 'Microsoft Word',
      windowTitle: `${file.name} - Word`
    },
    {
    pid: 8492,
      name: 'WPS Office',
      windowTitle: `${file.name} - WPS`
    }
  ]
  showProcessManager.value = true
}

function handleKillProcess(pid: number) {
  // TODO: Implement process killing
  console.log('Kill process:', pid)
  currentProcesses.value = currentProcesses.value.filter(p => p.pid !== pid)
  confirmClosePID.value = null
}

function handleKillAllProcesses() {
  // TODO: Implement kill all processes
  console.log('Kill all processes')
  currentProcesses.value = []
  showProcessManager.value = false
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

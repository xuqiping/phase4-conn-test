<template>
  <div
    :class="['h-screen w-full flex flex-col overflow-hidden font-sans transition-colors duration-300',
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

    <!-- Batch Operations Toolbar -->
    <transition name="fade">
      <div
        v-if="currentTab === 'files' && selectionStore.hasSelection"
        class="fixed bottom-6 left-1/2 -translate-x-1/2 z-50 bg-white dark:bg-dark-panel rounded-xl shadow-2xl border border-gray-200 dark:border-dark-border px-6 py-3 flex items-center space-x-4"
      >
        <span class="text-sm text-gray-600 dark:text-gray-300">
          {{ t('file.selectedItems', { count: selectionStore.selectedCount }) }}
        </span>
        <div class="h-4 w-px bg-gray-300 dark:bg-gray-600"></div>
        <button
          @click="handleBatchOpen"
        class="flex items-center space-x-1 px-3 py-1.5 text-sm text-gray-700 dark:text-gray-200 hover:bg-gray-100 dark:hover:bg-dark-hover rounded-md transition-colors"
      >
          <FolderOpen :size="14" />
      <span>{{ t('file.open') }}</span>
     </button>
        <button
          @click="showBatchMoveMenu = true"
          class="flex items-center space-x-1 px-3 py-1.5 text-sm text-gray-700 dark:text-gray-200 hover:bg-gray-100 dark:hover:bg-dark-hover rounded-md transition-colors"
        >
        <FolderInput :size="14" />
          <span>{{ t('file.move') }}</span>
        </button>
        <button
          @click="handleBatchAddTag"
          class="flex items-center space-x-1 px-3 py-1.5 text-sm text-gray-700 dark:text-gray-200 hover:bg-gray-100 dark:hover:bg-dark-hover rounded-md transition-colors"
        >
          <Tag :size="14" />
      <span>{{ t('file.addTag') }}</span>
        </button>
        <button
          @click="handleBatchDelete"
      class="flex items-center space-x-1 px-3 py-1.5 text-sm text-red-600 hover:bg-red-50 dark:hover:bg-red-900/20 rounded-md transition-colors"
        >
      <Trash2 :size="14" />
          <span>{{ t('file.delete') }}</span>
        </button>
        <div class="h-4 w-px bg-gray-300 dark:bg-gray-600"></div>
        <button
          @click="selectionStore.clearSelection"
          class="text-sm text-gray-400 hover:text-gray-600 dark:hover:text-gray-200 transition-colors"
        >
      {{ t('file.unselect') }}
        </button>
      </div>
    </transition>

    <!-- Batch Move Menu -->
    <transition name="fade">
      <div
     v-if="currentTab === 'files' && showBatchMoveMenu"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm"
        @click="showBatchMoveMenu = false"
      >
        <div
          class="bg-white dark:bg-dark-panel rounded-xl shadow-2xl border border-gray-200 dark:border-dark-border p-4 min-w-[200px]"
          @click.stop
        >
          <h3 class="text-sm font-semibold mb-3 text-gray-800 dark:text-gray-100">{{ t('file.moveToGroup') }}</h3>
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

    <!-- 3. 主标签栏 (Files / Processes / Clipboard) 和全局操作按钮 -->
    <div class="px-6 flex items-center justify-between border-b border-gray-200 dark:border-dark-border bg-white dark:bg-dark-bg">
      <div class="flex items-center space-x-6">
        <button
          @click="switchTab('files')"
          :class="['py-3 px-4 text-sm font-medium relative transition-colors flex items-center space-x-2',
                   currentTab === 'files' ? 'text-primary' : 'text-gray-500 hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-200']"
        >
          <Folder :size="16" />
          <span>{{ t('file.filesTab') }}</span>
          <div v-if="currentTab === 'files'" class="absolute bottom-0 left-0 w-full h-0.5 bg-primary rounded-t-full"></div>
        </button>
        <button
          @click="switchTab('processes')"
          :class="['py-3 px-4 text-sm font-medium relative transition-colors flex items-center space-x-2',
                   currentTab === 'processes' ? 'text-primary' : 'text-gray-500 hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-200']"
        >
          <Activity :size="16" />
          <span>{{ t('file.processesTab') }}</span>
          <div v-if="currentTab === 'processes'" class="absolute bottom-0 left-0 w-full h-0.5 bg-primary rounded-t-full"></div>
        </button>
        <button
          @click="switchTab('clipboard')"
          :class="['py-3 px-4 text-sm font-medium relative transition-colors flex items-center space-x-2',
                   currentTab === 'clipboard' ? 'text-primary' : 'text-gray-500 hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-200']"
        >
       <Clipboard :size="16" />
          <span>{{ t('tabs.clipboard') }}</span>
          <div v-if="currentTab === 'clipboard'" class="absolute bottom-0 left-0 w-full h-0.5 bg-primary rounded-t-full"></div>
        </button>
        <button
          @click="switchTab('office')"
          :class="['py-3 px-4 text-sm font-medium relative transition-colors flex items-center space-x-2',
                   currentTab === 'office' ? 'text-primary' : 'text-gray-500 hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-200']"
        >
          <FileSpreadsheet :size="16" />
          <span>{{ t('office.tab') }}</span>
          <div v-if="currentTab === 'office'" class="absolute bottom-0 left-0 w-full h-0.5 bg-primary rounded-t-full"></div>
        </button>
        <button
          @click="switchTab('work-report')"
          :class="['py-3 px-4 text-sm font-medium relative transition-colors flex items-center space-x-2',
                   currentTab === 'work-report' ? 'text-primary' : 'text-gray-500 hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-200']"
        >
          <FileText :size="16" />
          <span>{{ t('tabs.workReport') }}</span>
          <div v-if="currentTab === 'work-report'" class="absolute bottom-0 left-0 w-full h-0.5 bg-primary rounded-t-full"></div>
        </button>
      </div>

      <!-- 全局操作按钮 -->
      <div class="flex items-center space-x-3">
        <div v-if="authStore.isAuthenticated" class="flex items-center space-x-2">
          <span
            data-test="account-label"
            class="max-w-[160px] truncate text-xs text-gray-500 dark:text-gray-400"
            :title="accountLabel"
          >
            {{ accountLabel }}
          </span>
          <button
            data-test="account-logout-button"
            @click="handleLogout"
            class="px-3 py-2 rounded-md bg-gray-100 dark:bg-dark-hover hover:bg-gray-200 dark:hover:bg-[#383838] transition-colors text-sm"
          >
            退出
          </button>
        </div>
        <button
          v-else
          data-test="account-login-button"
          @click="showAuthDialog = true"
          class="px-3 py-2 rounded-md bg-primary/10 text-primary hover:bg-primary/15 transition-colors text-sm font-medium"
        >
          登录
        </button>

        <button
          @click="toggleLocale"
        class="flex items-center space-x-1.5 px-3 py-2 rounded-md bg-gray-100 dark:bg-dark-hover hover:bg-gray-200 dark:hover:bg-[#383838] transition-colors text-sm"
       :title="locale === 'zh-CN' ? t('common.switchToEnglish') : t('common.switchToChinese')"
      >
          <Languages :size="16" />
          <span>{{ locale === 'zh-CN' ? 'EN' : '中' }}</span>
        </button>

        <button
          @click="toggleTheme"
          class="p-2 rounded-md bg-gray-100 dark:bg-dark-hover hover:bg-gray-200 dark:hover:bg-[#383838] transition-colors"
          :title="t('file.toggleTheme')"
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

    <!-- 2. 工具栏 -->
    <div v-if="currentTab === 'files'" class="px-6 py-4 flex items-center justify-between bg-white dark:bg-dark-bg">
      <div class="relative w-96 group">
        <Search :size="16" class="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 group-focus-within:text-primary transition-colors" />
        <input
          type="text"
          :placeholder="t('file.searchPlaceholder')"
          v-model="fileStore.searchQuery"
          class="w-full pl-9 pr-4 py-2 bg-gray-100 dark:bg-dark-hover border border-transparent focus:border-primary focus:bg-white dark:focus:bg-dark-bg rounded-md outline-none text-sm transition-all duration-200 shadow-sm"
        />
      </div>

      <div class="flex items-center space-x-3">
        <!-- 快速排序 -->
        <div class="flex items-center space-x-1.5 text-sm">
          <ArrowUpDown :size="14" class="text-gray-400" />
          <span class="text-xs text-gray-500 dark:text-gray-400 select-none">{{ t('file.sortLabel') }}</span>
          <select
            v-model="fileStore.sortBy"
            class="bg-gray-100 dark:bg-dark-hover border border-transparent focus:border-primary rounded-md px-2 py-1.5 text-sm outline-none cursor-pointer"
            :title="t('file.sortTitle')"
          >
            <option value="custom">{{ t('file.sortCustom') }}</option>
            <option value="openCount">{{ t('file.sortOpenCount') }}</option>
            <option value="lastOpened">{{ t('file.sortLastOpened') }}</option>
            <option value="name">{{ t('file.sortName') }}</option>
            <option value="createdAt">{{ t('file.sortCreatedAt') }}</option>
          </select>
        </div>

        <!-- 图标模式切换 -->
        <div class="flex items-center space-x-1.5 text-sm">
          <Box :size="14" class="text-gray-400" />
          <span class="text-xs text-gray-500 dark:text-gray-400 select-none">{{ t('file.iconLabel') }}</span>
          <select
            v-model="settingsStore.settings.iconMode"
            class="bg-gray-100 dark:bg-dark-hover border border-transparent focus:border-primary rounded-md px-2 py-1.5 text-sm outline-none cursor-pointer"
            :title="t('file.iconModeTitle')"
          >
            <option value="real">{{ t('file.iconReal') }}</option>
            <option value="generic">{{ t('file.iconGeneric') }}</option>
          </select>
        </div>

        <RecentFiles @open-file="handleFileClick" />
        <AddFileButton />

        <button
          @click="handleAddFolder"
          class="flex items-center space-x-1 bg-gray-600 hover:bg-gray-700 text-white px-4 py-2 rounded-md text-sm font-medium transition-colors shadow-sm"
          :title="t('file.addFolder')"
        >
          <FolderPlus :size="16" />
          <span>{{ t('file.addFolder') }}</span>
        </button>

      </div>
    </div>

    <!-- 3b. 分组标签栏 (只在文件管理时显示) -->
    <div v-if="currentTab === 'files'" class="px-6 flex items-center space-x-6 border-b border-gray-200 dark:border-dark-border bg-white dark:bg-dark-bg">
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
        <Plus :size="14" class="mr-1" /> {{ t('file.newGroup') }}
      </button>
      <button
        class="py-3 text-sm font-medium text-gray-400 hover:text-primary transition-colors flex items-center ml-auto"
        @click="showGroupManager = true"
:title="t('file.manageGroups')"
      >
        <FolderCog :size="16" />
      </button>
    </div>

    <!-- 4. 主内容区 (文件管理) -->
    <div
      v-if="currentTab === 'files'"
      class="flex-1 overflow-auto p-6 bg-gray-50 dark:bg-dark-bg relative transition-colors duration-200"
      :class="{ 'bg-primary/5 dark:bg-primary/5': isDraggingOver }"
      @dragover="handleDragOver"
      @dragleave="handleDragLeave"
      @drop="handleDrop"
    >

      <!-- 空状态 -->
      <div v-if="fileStore.filteredFiles.length === 0" class="h-full flex flex-col items-center justify-center text-gray-400">
        <Search :size="48" class="mb-4 opacity-20" />
        <p>{{ t('file.emptySearch') }}</p>
      </div>

      <template v-else>
        <!-- 网格视图 -->
     <div
          v-if="viewMode === 'grid'"
          ref="gridContainerRef"
          class="relative overflow-y-auto"
          style="height: calc(100vh - 250px);"
          @scroll="gridVirtualScroll.handleScroll"
     >
          <div :style="{ height: `${gridVirtualScroll.totalHeight.value}px`, position: 'relative' }">
               <div
            v-for="{ item: file, index, offsetTop } in gridVirtualScroll.visibleItems.value"
       :key="file.id"
            :data-id="file.id"
      :ref="(el) => setupIconLazyLoad(el as HTMLElement, file)"
            :style="{
           position: 'absolute',
            top: `${offsetTop}px`,
              left: `${(index % 5) * 20}%`,
            width: '19%',
                height: '210px'
            }"
       draggable="false"
            @contextmenu.prevent="handleContextMenu($event, file)"
            @click="handleCardClick($event, file)"
      @mouseenter="hoveredFileId = file.id"
         @mouseleave="hoveredFileId = null"
            class="group bg-white dark:bg-dark-panel border border-gray-200 dark:border-dark-border rounded-lg p-3 hover:shadow-lg dark:hover:shadow-black/40 hover:border-primary/50 transition-all duration-200 cursor-pointer flex flex-col select-none overflow-hidden"
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

        <div class="flex-1 flex flex-col items-center justify-center py-2 min-h-0 w-full">
              <!-- Real icon (base64 data URL) -->
              <img
                v-if="file.icon && file.icon.startsWith('data:image')"
            :src="file.icon"
                class="w-12 h-12 mb-3 object-contain opacity-90 group-hover:opacity-100 group-hover:scale-110 transition-transform duration-300"
           :alt="file.name"
          />
              <!-- Fallback to icon type -->
              <component
                v-else
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
              <span v-if="invalidFavoriteIds.has(file.id)" class="mt-1 text-xs font-medium text-red-500">
                {{ t('file.pathInvalid') }}
              </span>
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

            <!-- Footer: 分组 | 序号 | 打开次数 -->
            <div class="mt-2 relative flex items-center justify-between gap-2 text-[11px] text-gray-400 dark:text-gray-500 w-full pt-2 border-t border-gray-100 dark:border-[#333] flex-shrink-0">
              <span class="truncate flex-1 min-w-0" :title="getGroupName(file.groupId)">{{ getGroupName(file.groupId) }}</span>
            
              <!-- 序号徽章（绝对居中） -->
              <div class="absolute left-1/2 -translate-x-1/2" @click.stop>
             <input
          v-if="editingOrderId === file.id"
              v-model="editingOrderValue"
            type="number"
                  min="1"
              class="w-10 h-5 text-[11px] text-center bg-white dark:bg-dark-bg border border-primary rounded focus:outline-none px-0.5"
                  @keyup.enter="commitEditOrder(file)"
                  @keyup.esc="cancelEditOrder()"
                  @blur="commitEditOrder(file)"
               :ref="autofocusInput"
                />
           <button
                v-else
       :class="['min-w-[48px] h-5 px-2 flex items-center justify-center text-[10px] font-medium rounded transition-colors whitespace-nowrap',
            fileStore.sortBy === 'custom'
                 ? 'bg-primary/15 text-primary border border-primary/40 hover:bg-primary/25 cursor-pointer'
                 : 'bg-gray-100 dark:bg-dark-hover text-gray-400 border border-gray-200 dark:border-dark-border cursor-not-allowed']"
             :title="fileStore.sortBy === 'custom' ? '点击修改顺序' : '切换到自定义顺序后可编辑'"
                  @click.stop="startEditOrder(file, index + 1)"
                >
            排序 {{ index + 1 }}
            </button>
              </div>
              
              <span class="flex-shrink-0 whitespace-nowrap">打开 {{ file.openCount }} 次</span>
            </div>
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
            ref="listContainerRef"
            class="relative overflow-y-auto"
            style="height: calc(100vh - 280px);"
       @scroll="listVirtualScroll.handleScroll"
          >
            <div :style="{ height: `${listVirtualScroll.totalHeight.value}px`, position: 'relative' }">
              <div
          v-for="{ item: file, offsetTop } in listVirtualScroll.visibleItems.value"
        :key="file.id"
              :ref="(el) => setupIconLazyLoad(el as HTMLElement, file)"
                :style="{
                  position: 'absolute',
             top: `${offsetTop}px`,
            left: 0,
                  right: 0,
          height: '60px'
                }"
                @contextmenu.prevent="handleContextMenu($event, file)"
                @click="handleFileClick(file)"
                class="flex items-center px-4 py-3 border-b border-gray-100 dark:border-[#333] hover:bg-gray-50 dark:hover:bg-dark-hover transition-colors group cursor-pointer"
              >
       <div class="w-1/2 flex items-center pr-4">
                  <img v-if="file.icon && file.icon.startsWith('data:image')" :src="file.icon" class="w-[18px] h-[18px] mr-3 flex-shrink-0 object-contain" :alt="file.name" />
             <component v-else :is="getFileIcon(file.icon || file.type)" :size="18" :class="[getFileColor(file.icon || file.type), 'mr-3 flex-shrink-0']" />
                  <div class="flex flex-col truncate">
                 <span
                 class="text-sm font-medium truncate"
            v-html="highlightText(file.name, fileStore.searchQuery)"
                  />
                  <span v-if="invalidFavoriteIds.has(file.id)" class="text-[11px] font-medium text-red-500">
                    {{ t('file.pathInvalid') }}
                  </span>
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
          </div>
        </div>
      </template>
    </div>

    <!-- Process Management Tab -->
    <div v-if="currentTab === 'processes'" class="flex-1 overflow-hidden flex flex-col min-h-0">
      <ProcessManagement />
    </div>

    <!-- Clipboard Management Tab -->
    <div v-if="currentTab === 'clipboard'" class="flex-1 overflow-hidden flex flex-col min-h-0">
      <ClipboardManagement />
    </div>

    <!-- Office Workspace Tab -->
    <div v-if="currentTab === 'office'" class="flex-1 overflow-hidden flex flex-col min-h-0">
      <OfficeWorkspace />
    </div>

    <!-- Work Report Management Tab -->
    <div v-if="currentTab === 'work-report'" class="flex-1 overflow-hidden flex flex-col min-h-0">
      <WorkReportManagement v-if="authStore.isAuthenticated" />
      <div
        v-else
        data-test="work-report-login-prompt"
        class="flex-1 flex flex-col items-center justify-center gap-3 px-6 text-center"
      >
        <FileText :size="36" class="text-gray-300 dark:text-gray-600" />
        <h2 class="text-base font-semibold text-gray-800 dark:text-gray-100">{{ t('access.loginRequiredTitle') }}</h2>
        <p class="max-w-md text-sm text-gray-500 dark:text-gray-400">{{ t('access.workReportLoginDescription') }}</p>
        <button
          data-test="work-report-login-button"
          @click="showAuthDialog = true"
          class="px-4 py-2 rounded-md bg-primary text-white hover:bg-[#369b6e] transition-colors text-sm font-medium"
        >
          {{ t('access.loginAction') }}
        </button>
      </div>
    </div>

    <!-- 5. 状态栏 -->
    <div v-if="currentTab === 'files'" class="h-10 px-4 flex items-center justify-between text-xs text-gray-500 border-t border-gray-200 dark:border-dark-border bg-white dark:bg-dark-panel">
      <div>{{ t('file.totalItems', { count: fileStore.filteredFiles.length }) }}</div>
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
        v-if="currentTab === 'files' && contextMenu.show"
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

    <!-- Auth Dialog -->
    <AuthDialog
      :show="showAuthDialog"
      :base-url="commercialServerUrl"
      @close="showAuthDialog = false"
      @authenticated="handleAuthenticated"
    />

    <!-- Settings Dialog -->
    <SettingsDialog
      :show="showSettings"
      @close="showSettings = false"
      @login="handleSettingsLogin"
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
                :key="process.window_handle"
                class="flex items-center justify-between p-4 rounded-lg border border-gray-200 dark:border-dark-border bg-gray-50 dark:bg-dark-hover"
              >
                <div class="flex-1 min-w-0">
                  <div class="flex items-center space-x-2">
                    <span class="text-sm font-medium text-gray-800 dark:text-gray-200">{{ process.name }}</span>
                    <span class="text-xs text-gray-400">PID: {{ process.pid }}</span>
                  </div>
                  <p v-if="process.window_title" class="text-xs text-gray-500 mt-1 truncate">{{ process.window_title }}</p>
                </div>
                <button
                  @click="handleCloseProcess(process.window_handle, process.pid)"
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
      :saving="fileEditSaving"
      :shortcut-error="fileEditShortcutError"
      :path-invalid="invalidFavoriteIds.has(editingFile.id)"
      @close="editingFile = null"
      @saved="handleFileSaved"
      @relocate="handleRelocateFile"
    />

    <ClipboardQuickPanel />

    <!-- 全局点击处理 -->
    <div v-if="contextMenu.show" class="fixed inset-0 z-40" @click="closeContextMenu"></div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { onKeyStroke } from '@vueuse/core'
import { getCurrentWindow } from '@tauri-apps/api/window'
import { listen, type UnlistenFn } from '@tauri-apps/api/event'
import { registerGlobalShortcut, unregisterGlobalShortcut } from './api/shortcuts'
import {
  Search,
  Plus,
  FolderPlus,
  ArrowUpDown,
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
  FileSpreadsheet,
  Folder,
  Image,
  Code,
  FolderOpen,
  XCircle,
  FileX,
  Activity,
  Clipboard,
  Languages
} from 'lucide-vue-next'
import { useFileStore } from './stores/fileStore'
import { useGroupStore } from './stores/groupStore'
import { useSettingsStore } from './stores/settingsStore'
import { useSelectionStore } from './stores/selectionStore'
import { useRecentStore } from './stores/recentStore'
import { useClipboardStore } from './stores/clipboardStore'
import { useAuthStore } from './stores/authStore'
import { useI18n } from './composables/useI18n'
import {
  deleteManagedShortcut,
  importFavoritePath,
  openFile,
  pickFile,
  pickFolder,
  showInFolder,
  validateFavoritePath
} from './api/files'
import { findFileProcesses, closeProcess } from './api/processes'
import { captureScreenshotRegion } from './api/screenshot'
import { closeScreenshotOverlayWindow, openScreenshotOverlayWindow } from './api/screenshotOverlay'
import {
  exitApplication,
  restoreMainWindow,
  showFloatingBall
} from './api/floatingBall'
import GroupManager from './components/GroupManager.vue'
import AddFileButton from './components/AddFileButton.vue'
import EditFileDialog from './components/EditFileDialog.vue'
import SettingsDialog from './components/SettingsDialog.vue'
import AuthDialog from './components/AuthDialog.vue'
import RecentFiles from './components/RecentFiles.vue'
import ProcessManagement from './components/ProcessManagement.vue'
import ClipboardManagement from './components/ClipboardManagement.vue'
import ClipboardQuickPanel from './components/ClipboardQuickPanel.vue'
import WorkReportManagement from './components/work-report/WorkReportManagement.vue'
import OfficeWorkspace from './components/office/OfficeWorkspace.vue'
import { deriveIconFromExt, resolveGroupId } from './utils/file'
import { highlightText } from './utils/highlight'
import { applyCloseBehavior } from './utils/closeBehavior'
import type { CloseBehavior, FloatingBallPosition } from './types/settings'
import { useSortableFiles } from './composables/useSortableFiles'
import { useVirtualScroll } from './composables/useVirtualScroll'
import type { FileItem } from './types/file'
import type { ProcessInfo } from './types/process'
import type { ScreenshotRegion } from './types/screenshot'
import {
  createFavoriteShortcutCoordinator,
  replaceShortcutRegistration
} from './composables/useFavoriteShortcuts'
import { findShortcutConflict, normalizeShortcut } from './utils/shortcut'

const fileStore = useFileStore()
const groupStore = useGroupStore()
const settingsStore = useSettingsStore()
const selectionStore = useSelectionStore()
const recentStore = useRecentStore()
const clipboardStore = useClipboardStore()
const authStore = useAuthStore()
const { t, locale, toggleLocale } = useI18n()
const commercialServerUrl = import.meta.env.VITE_FILE_KEEPER_SERVER_URL || 'http://localhost:8088'
const invalidFavoriteIds = ref(new Set<string>())
const favoriteShortcutCoordinator = createFavoriteShortcutCoordinator({
  register: registerGlobalShortcut,
  unregister: unregisterGlobalShortcut
})

// Current tab state
type AppTab = 'files' | 'processes' | 'clipboard' | 'office' | 'work-report'
const currentTab = ref<AppTab>('files')
const showAuthDialog = ref(false)
const accountLabel = computed(() => authStore.user?.email || authStore.user?.phone || '账号')

function switchTab(tab: AppTab) {
  currentTab.value = tab
}

let clipboardMonitorRunning = false
async function startClipboardMonitor() {
  if (clipboardMonitorRunning) {
    return
  }
  clipboardMonitorRunning = true
  try {
    await clipboardStore.startMonitor()
  } catch (error) {
    clipboardMonitorRunning = false
    console.error('Failed to start clipboard monitor:', error)
  }
}

async function handleAuthenticated() {
  showAuthDialog.value = false
}

function handleSettingsLogin() {
  showSettings.value = false
  showAuthDialog.value = true
}

async function handleLogout() {
  try {
    await authStore.logout(commercialServerUrl)
  } catch (error) {
    console.error('Failed to logout:', error)
  }
}

// Hover state for checkboxes
const hoveredFileId = ref<string | null>(null)

// Drag-reorder for grid view (disabled while virtual scroll is active —
// custom order is now edited via the per-card numeric badge instead).
const gridContainer = ref<HTMLElement | null>(null)
useSortableFiles(gridContainer)

// Virtual scroll for grid view
const gridContainerRef = ref<HTMLElement | null>(null)
const gridVirtualScroll = useVirtualScroll(
  gridContainerRef,
  computed(() => fileStore.filteredFiles),
  {
    itemHeight: 220,  // 每个卡片高度（含间距）
    itemsPerRow: 5,   // 默认每行 5 个
    overscan: 10      // 缓冲区 10 个项目
  }
)

// Virtual scroll for list view
const listContainerRef = ref<HTMLElement | null>(null)
const listVirtualScroll = useVirtualScroll(
  listContainerRef,
  computed(() => fileStore.filteredFiles),
  {
    itemHeight: 60,   // 每行高度约 60px
    itemsPerRow: 1,   // 列表视图每行 1 个
    overscan: 5       // 缓冲区 5 个项目
  }
)

// Icon lazy loading setup - 使用单个全局 IntersectionObserver
const iconObserver = ref<IntersectionObserver | null>(null)
const observedElements = new Map<HTMLElement, FileItem>()

// 图标提取队列
const iconQueue: Array<{ file: FileItem; callback: (icon: string) => void }> = []
let isProcessingIcons = false
const MAX_CONCURRENT_ICONS = 3 // 降低并发数，避免卡顿
let activeIconCount = 0
let processQueueTimer: number | null = null
let getFileIconFn: ((path: string) => Promise<string | null>) | null = null

// 使用 requestIdleCallback 避免阻塞主线程
function scheduleIconProcessing() {
  if (processQueueTimer !== null) return

  const scheduleCallback = (window as any).requestIdleCallback ||
    ((cb: () => void) => setTimeout(cb, 16))

  processQueueTimer = scheduleCallback(() => {
    processQueueTimer = null
    processIconQueue()
  })
}

async function processIconQueue() {
  if (isProcessingIcons || iconQueue.length === 0) return

  isProcessingIcons = true

  // 只导入一次
  if (!getFileIconFn) {
    const { getFileIcon } = await import('./api/icons')
    getFileIconFn = getFileIcon
  }

  while (iconQueue.length > 0 && activeIconCount < MAX_CONCURRENT_ICONS) {
    const task = iconQueue.shift()
    if (!task) break

    activeIconCount++

    getFileIconFn(task.file.path)
      .then(icon => {
        task.callback(icon || '')
      })
      .catch(() => {
        task.callback('')
      })
      .finally(() => {
        activeIconCount--
        if (iconQueue.length > 0) {
        scheduleIconProcessing()
        }
      })
  }

  isProcessingIcons = false
}

function setupIconLazyLoad(el: HTMLElement | null, file: FileItem) {
  if (!el) return

  // 如果已经有图标且是正确的类型，不需要重新加载
  const isBase64Icon = file.icon && file.icon.startsWith('data:image')
  const needsRealIcon = settingsStore.settings.iconMode === 'real'
  const hasCorrectIcon = file.icon && (
    (needsRealIcon && isBase64Icon) ||
    (!needsRealIcon && !isBase64Icon)
  )

  if (hasCorrectIcon) return

  // 初始化全局 observer（只创建一次）
  if (!iconObserver.value) {
    iconObserver.value = new IntersectionObserver(
      (entries) => {
        entries.forEach(entry => {
          if (entry.isIntersecting) {
            const element = entry.target as HTMLElement
          const fileItem = observedElements.get(element)

            if (fileItem && !fileItem.icon) {
              // 添加到队列
        iconQueue.push({
            file: fileItem,
             callback: (icon: string) => {
               fileStore.updateFile(fileItem.id, { icon })
              }
          })
          scheduleIconProcessing()
            }

            // 停止观察此元素
            iconObserver.value?.unobserve(element)
            observedElements.delete(element)
          }
        })
      },
      {
        threshold: 0.1,
        rootMargin: '100px' // 提前 100px 开始加载
      }
    )
  }
  // 记录元素和文件的映射
  observedElements.set(el, file)
  iconObserver.value.observe(el)
}

// Order-badge inline editing (only meaningful when sortBy === 'custom')
const editingOrderId = ref<string | null>(null)
const editingOrderValue = ref('')

function startEditOrder(file: FileItem, currentPosition: number) {
  if (fileStore.sortBy !== 'custom') return
  editingOrderId.value = file.id
  editingOrderValue.value = String(currentPosition)
}

function commitEditOrder(file: FileItem) {
  if (editingOrderId.value !== file.id) return
  const target = parseInt(editingOrderValue.value, 10)
  if (Number.isFinite(target) && target > 0) {
    fileStore.moveToPosition(file.id, target)
  }
  editingOrderId.value = null
  editingOrderValue.value = ''
}

function cancelEditOrder() {
  editingOrderId.value = null
  editingOrderValue.value = ''
}

function autofocusInput(el: any) {
  if (el && typeof el.focus === 'function') {
    el.focus()
    if (typeof el.select === 'function') el.select()
  }
}

// Batch operations
const showBatchMoveMenu = ref(false)
const showSettings = ref(false)
let isScreenshotOverlayOpen = false

function handleBatchOpen() {
  const ids = Array.from(selectionStore.selectedIds)
  fileStore.batchOpen(ids)
  selectionStore.clearSelection()
}

async function handleBatchDelete() {
  const ids = Array.from(selectionStore.selectedIds)
  const confirmed = confirm(`确定删除选中的 ${ids.length} 个项目？`)
  if (confirmed) {
    const removableIds: string[] = []
    const removableFiles: FileItem[] = []
    const failures: string[] = []
    for (const id of ids) {
      const file = fileStore.files.find(item => item.id === id)
      if (!file) continue
      try {
        await favoriteShortcutCoordinator.unregister(file.id, file.shortcut)
        removableIds.push(file.id)
        removableFiles.push(file)
      } catch (error) {
        failures.push(`${file.name}: ${error instanceof Error ? error.message : String(error)}`)
      }
    }
    fileStore.batchDelete(removableIds)
    invalidFavoriteIds.value = new Set(
      [...invalidFavoriteIds.value].filter(id => !removableIds.includes(id))
    )
    const cleanupResults = await Promise.all(removableFiles.map(file => cleanupManagedArtifact(file)))
    selectionStore.clearSelection()
    if (failures.length > 0) {
      alert(t('file.shortcutRemoveFailed', { error: failures.join('\n') }))
    }
    if (cleanupResults.some(result => !result)) {
      alert(t('file.managedCleanupFailed'))
    }
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
const currentTheme = computed(() => settingsStore.effectiveTheme)

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

    const newItem = await fileStore.addFile({
      name,
      path: selectedPath,
      sourcePath: selectedPath,
      type: 'folder',
      icon: '', // Icon will be loaded lazily
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

  // Check if this is a file drag from outside (system) or internal reorder
  if (e.dataTransfer) {
    // If dragging files from system, types will include 'Files'
    // If dragging internally (Sortable), types will be different or empty
  const isExternalFileDrag = e.dataTransfer.types.includes('Files')

    if (isExternalFileDrag) {
      e.dataTransfer.dropEffect = 'copy'
    } else {
      // Internal drag (Sortable) - allow move
      e.dataTransfer.dropEffect = 'move'
    }
  }
}

function handleDragLeave(_e: DragEvent) {
  // Handled by Tauri onDragDropEvent
}

function handleDrop(e: DragEvent) {
  // Prevent default browser behavior; actual file drop is handled by Tauri
  e.preventDefault()
}

async function handleTauriDroppedPaths(paths: string[]) {
  const duplicates: string[] = []
  const invalids: string[] = []
  for (const filePath of paths) {
    const result = await processDroppedPath(filePath)
    if (result === 'duplicate') duplicates.push(filePath)
    else if (result === 'invalid') invalids.push(filePath)
  }

  const messages: string[] = []
  if (duplicates.length > 0) {
    const list = duplicates.map(p => p.split(/[/\\]/).pop() || p).join('\n  ')
    messages.push(`以下项目已存在，已忽略：\n  ${list}`)
  }
  if (invalids.length > 0) {
    const list = invalids.map(p => p.split(/[/\\]/).pop() || p).join('\n  ')
    messages.push(`以下路径无效，已忽略：\n  ${list}`)
  }
  if (messages.length > 0) {
    alert(messages.join('\n\n'))
  }
}

async function processDroppedPath(filePath: string): Promise<'added' | 'duplicate' | 'invalid' | 'error'> {
  try {
    if (fileStore.files.some(file => file.path === filePath || file.sourcePath === filePath)) {
      return 'duplicate'
    }
    const descriptor = await importFavoritePath(filePath)

    const newItem = await fileStore.addFile({
      name: descriptor.name,
      path: descriptor.path,
      sourcePath: descriptor.sourcePath,
      managedArtifact: descriptor.managedArtifact,
      shortcutTargetPath: descriptor.shortcutTargetPath,
      type: descriptor.itemType,
      icon: '',
      tags: [],
      groupId: resolveGroupId(
        groupStore.currentGroupId,
        groupStore.customGroups[0]?.id
      )
    })

    if (!newItem) {
      if (descriptor.managedArtifact) {
        await deleteManagedShortcut(descriptor.managedArtifact.cachePath).catch(() => undefined)
      }
      return 'duplicate'
    }
    await refreshFavoriteValidity(newItem)
    return 'added'
  } catch (error) {
    console.error('拖拽处理失败:', error instanceof Error ? error.message : String(error))
    return 'error'
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
  await appWindow.close()
}

async function handleSaveSettings(settings: { globalShortcut: string; clipboardShortcut: string; screenshotShortcut: string; closeBehavior: CloseBehavior; theme: 'light' | 'dark' | 'auto' }) {
  const proposed = {
    globalShortcut: normalizeShortcut(settings.globalShortcut),
    clipboardShortcut: normalizeShortcut(settings.clipboardShortcut),
    screenshotShortcut: normalizeShortcut(settings.screenshotShortcut)
  }
  const candidates = [
    ['main', proposed.globalShortcut],
    ['clipboard', proposed.clipboardShortcut],
    ['screenshot', proposed.screenshotShortcut]
  ] as const
  for (const [id, shortcut] of candidates) {
    const conflict = findShortcutConflict(shortcut, {
      settings: proposed,
      files: fileStore.files,
      excludeApplicationId: id
    })
    if (conflict) {
      alert(t('file.shortcutConflict', { label: conflict.label }))
      return
    }
  }

  settingsStore.updateSettings({
    closeBehavior: settings.closeBehavior,
    theme: settings.theme,
  })

  try {
    await updateGlobalShortcut(proposed.globalShortcut)
    await updateClipboardShortcut(proposed.clipboardShortcut)
    await updateScreenshotShortcut(proposed.screenshotShortcut)
  } catch (error) {
    alert(t('file.shortcutRegisterFailed', {
      error: error instanceof Error ? error.message : String(error)
    }))
    return
  }

  showSettings.value = false
}

// Global Shortcut Registration & Tauri DnD
let dndUnlisten: (() => void) | null = null
let closeRequestedUnlisten: (() => void) | null = null
let screenshotCaptureUnlisten: UnlistenFn | null = null
let screenshotCancelUnlisten: UnlistenFn | null = null
let floatingBallMovedUnlisten: UnlistenFn | null = null
let shortcutHandling = false // Prevent double-trigger
let clipboardShortcutHandling = false
let screenshotShortcutHandling = false
// Tracks the shortcut currently registered with the OS. Stays null when a
// register() call fails, so we never try to unregister a string the OS doesn't know about.
let registeredShortcut: string | null = null
let registeredClipboardShortcut: string | null = null
let registeredScreenshotShortcut: string | null = null

async function handleGlobalShortcut() {
  if (shortcutHandling) return
  shortcutHandling = true
  try {
    const isVisible = await appWindow.isVisible()
    if (isVisible) {
      await appWindow.hide()
    } else {
      await restoreMainWindow()
    }
  } finally {
    setTimeout(() => { shortcutHandling = false }, 300)
  }
}

async function handleClipboardShortcut() {
  if (clipboardShortcutHandling) return
  clipboardShortcutHandling = true
  try {
    clipboardStore.openQuickPanel()
  } finally {
    setTimeout(() => { clipboardShortcutHandling = false }, 300)
  }
}

async function handleScreenshotShortcut() {
  if (screenshotShortcutHandling || isScreenshotOverlayOpen) {
    console.log('[Screenshot] Shortcut blocked:', { screenshotShortcutHandling, isScreenshotOverlayOpen })
    return
  }
  screenshotShortcutHandling = true
  console.log('[Screenshot] Opening overlay window...')
  try {
    isScreenshotOverlayOpen = true
    await openScreenshotOverlayWindow()
    console.log('[Screenshot] Overlay window opened successfully')
    screenshotShortcutHandling = false  // 成功后立即释放锁
  } catch (error) {
    console.error('[Screenshot] Failed to open overlay:', error)
    isScreenshotOverlayOpen = false
    screenshotShortcutHandling = false
    alert(t('screenshot.captureFailed', { error: error instanceof Error ? error.message : String(error) }))
  }
}

async function handleScreenshotCancel() {
  isScreenshotOverlayOpen = false
  screenshotShortcutHandling = false
  try {
    await closeScreenshotOverlayWindow()
  } catch (error) {
    // 遮罩窗口可能已被 Host 销毁，忽略 "window not found" 错误
  }
}

async function handleScreenshotCapture(region: ScreenshotRegion) {
  isScreenshotOverlayOpen = false
  screenshotShortcutHandling = false
  try {
    await closeScreenshotOverlayWindow()
  } catch (error) {
    // 遮罩窗口可能已被 Host 销毁，忽略 "window not found" 错误
  }
  await nextTick()
  let itemId: string
  try {
    const result = await captureScreenshotRegion(region)
    itemId = result.itemId
  } catch (error) {
    alert(t('screenshot.captureFailed', { error: error instanceof Error ? error.message : String(error) }))
    return
  }

  switchTab('clipboard')
  clipboardStore.searchQuery = ''
  clipboardStore.kindFilter = 'all'
  clipboardStore.favoriteOnly = false
  clipboardStore.datePreset = 'all'

  try {
    await clipboardStore.loadItems()
    await clipboardStore.loadDetail(itemId)
  } catch (error) {
    console.warn('Failed to refresh clipboard history after screenshot capture:', error)
  }
  alert(t('screenshot.captureSuccess'))
}

async function updateGlobalShortcut(desired: string) {
  const replaced = await replaceShortcutRegistration(
    { register: registerGlobalShortcut, unregister: unregisterGlobalShortcut },
    registeredShortcut ?? '',
    desired,
    handleGlobalShortcut
  )
  registeredShortcut = replaced || null
  settingsStore.updateSettings({ globalShortcut: replaced })
}

async function updateClipboardShortcut(desired: string) {
  const replaced = await replaceShortcutRegistration(
    { register: registerGlobalShortcut, unregister: unregisterGlobalShortcut },
    registeredClipboardShortcut ?? '',
    desired,
    handleClipboardShortcut
  )
  registeredClipboardShortcut = replaced || null
  settingsStore.updateSettings({ clipboardShortcut: replaced })
}

async function updateScreenshotShortcut(desired: string) {
  const replaced = await replaceShortcutRegistration(
    { register: registerGlobalShortcut, unregister: unregisterGlobalShortcut },
    registeredScreenshotShortcut ?? '',
    desired,
    handleScreenshotShortcut
  )
  registeredScreenshotShortcut = replaced || null
  settingsStore.updateSettings({ screenshotShortcut: replaced })
}

let tokenRefreshInterval: number | null = null

onMounted(async () => {
  // Performance monitoring: record app startup time
  const startupTime = performance.now()

  // Intercept close so all three configured behaviors share one safe path.
  closeRequestedUnlisten = await appWindow.onCloseRequested(async (event) => {
    event.preventDefault()
    await applyCloseBehavior(settingsStore.settings.closeBehavior, {
      showFloatingBall: async () => {
        const position = await showFloatingBall(settingsStore.settings.floatingBallPosition)
        settingsStore.updateSettings({ floatingBallPosition: position })
      },
      hideMainWindow: () => appWindow.hide(),
      exitApplication,
      onFloatingBallFailure: (error) => {
        console.warn('[Window] Floating ball unavailable; using tray fallback:', error instanceof Error ? error.message : String(error))
        alert(t('settings.floatingBallFailed'))
      }
    })
  })

  floatingBallMovedUnlisten = await listen<FloatingBallPosition>('floating-ball://moved', (event) => {
    settingsStore.updateSettings({ floatingBallPosition: event.payload })
  })

  screenshotCaptureUnlisten = await listen<ScreenshotRegion>('screenshot://capture', async (event) => {
    await handleScreenshotCapture(event.payload)
  })
  screenshotCancelUnlisten = await listen('screenshot://cancel', async () => {
    await handleScreenshotCancel()
  })

  await (settingsStore as { $persistReady?: Promise<void> }).$persistReady

  try {
    await authStore.restoreSession(commercialServerUrl)
  } catch (error) {
    console.error('Failed to restore auth session:', error)
  }
  await startClipboardMonitor()

  const shortcut = settingsStore.settings.globalShortcut
  if (shortcut) {
    try {
      await registerGlobalShortcut(shortcut, handleGlobalShortcut)
      registeredShortcut = shortcut
      console.log(`Global shortcut registered: ${shortcut}`)
    } catch (error) {
      console.error('Failed to register global shortcut on startup:', error)
      // Don't alert on startup — the user didn't trigger this. Just log and
      // leave registeredShortcut = null so a later save can register cleanly.
    }
  }

  const clipboardShortcut = settingsStore.settings.clipboardShortcut
  if (clipboardShortcut) {
    try {
      await registerGlobalShortcut(clipboardShortcut, handleClipboardShortcut)
      registeredClipboardShortcut = clipboardShortcut
      console.log(`Clipboard shortcut registered: ${clipboardShortcut}`)
    } catch (error) {
      console.error('Failed to register clipboard shortcut on startup:', error)
    }
  }

  const screenshotShortcut = settingsStore.settings.screenshotShortcut
  if (screenshotShortcut) {
    try {
      await registerGlobalShortcut(screenshotShortcut, handleScreenshotShortcut)
      registeredScreenshotShortcut = screenshotShortcut
      console.log(`Screenshot shortcut registered: ${screenshotShortcut}`)
    } catch (error) {
      console.error('Failed to register screenshot shortcut on startup:', error)
    }
  }

  void (fileStore as { $persistReady?: Promise<void> }).$persistReady?.then(async () => {
    const failures = await favoriteShortcutCoordinator.restore(
      fileStore.files,
      id => () => { void openFavoriteById(id) },
      4
    )
    if (failures.length > 0) {
      const items = failures.map(failure => {
        const file = fileStore.files.find(item => item.id === failure.id)
        return `${file?.name ?? failure.id}: ${failure.error}`
      }).join('\n')
      alert(t('file.shortcutRestoreFailed', { items }))
    }
  }).catch(error => {
    console.error('Failed to restore favorite shortcuts:', error)
  })

  void (fileStore as { $persistReady?: Promise<void> }).$persistReady?.then(() =>
    Promise.all(fileStore.files.map(file => refreshFavoriteValidity(file)))
  ).catch(() => {
    console.warn('Failed to refresh favorite path status')
  })

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

  // 启动后每 5 分钟检查一次 access token 是否临近过期，提前刷新，防止长驻桌面端 401
  tokenRefreshInterval = window.setInterval(() => {
    if (authStore.isAuthenticated) {
      authStore.ensureValidToken(commercialServerUrl).catch((error) => {
        console.warn('[App] Token refresh check failed:', error)
      })
    }
  }, 5 * 60 * 1000)

  // Performance monitoring: log startup time
  const endTime = performance.now()
  const loadTime = endTime - startupTime
  console.log(`[Performance] App startup time: ${loadTime.toFixed(2)}ms`)
  console.log(`[Performance] Total files loaded: ${fileStore.files.length}`)
})

// Watch for icon mode changes and reload all icons
watch(
  () => settingsStore.settings.iconMode,
  async (newMode) => {
    console.log('Icon mode changed to:', newMode)

    if (newMode === 'generic') {
      // 通用图标模式：直接设置通用图标
   fileStore.files.forEach(file => {
        file.icon = file.type === 'folder' ? 'folder' : deriveIconFromExt(file.name)
      })
    } else {
      // 真实图标模式：清空图标，等待懒加载
      fileStore.files.forEach(file => {
      file.icon = ''
      })
      // 强制触发重新渲染，让懒加载重新工作
      await nextTick()
    }
  }
)
onUnmounted(async () => {
  // 清理图标 observer 和定时器
  if (processQueueTimer !== null) {
    const cancelCallback = (window as any).cancelIdleCallback ||
      ((id: number) => clearTimeout(id))
    cancelCallback(processQueueTimer)
    processQueueTimer = null
  }
  if (iconObserver.value) {
    iconObserver.value.disconnect()
    iconObserver.value = null
  }
  observedElements.clear()
  iconQueue.length = 0

  if (clipboardMonitorRunning) {
    try {
      await clipboardStore.stopMonitor()
    } catch (error) {
      console.warn('Failed to stop clipboard monitor on unmount:', error)
    } finally {
      clipboardMonitorRunning = false
    }
  }

  const favoriteReleaseFailures = await favoriteShortcutCoordinator.dispose()
  if (favoriteReleaseFailures.length > 0) {
    console.warn(`Failed to release ${favoriteReleaseFailures.length} favorite shortcuts on unmount`)
  }

  if (registeredShortcut) {
    try {
      await unregisterGlobalShortcut(registeredShortcut)
    } catch (error) {
      console.warn('Failed to unregister shortcut on unmount:', error)
    }
    registeredShortcut = null
  }
  if (registeredClipboardShortcut) {
    try {
      await unregisterGlobalShortcut(registeredClipboardShortcut)
    } catch (error) {
      console.warn('Failed to unregister clipboard shortcut on unmount:', error)
    }
    registeredClipboardShortcut = null
  }
  if (registeredScreenshotShortcut) {
    try {
      await unregisterGlobalShortcut(registeredScreenshotShortcut)
    } catch (error) {
      console.warn('Failed to unregister screenshot shortcut on unmount:', error)
    }
    registeredScreenshotShortcut = null
  }
  if (dndUnlisten) {
    dndUnlisten()
    dndUnlisten = null
  }
  if (closeRequestedUnlisten) {
    closeRequestedUnlisten()
    closeRequestedUnlisten = null
  }
  if (screenshotCaptureUnlisten) {
    screenshotCaptureUnlisten()
    screenshotCaptureUnlisten = null
  }
  if (screenshotCancelUnlisten) {
    screenshotCancelUnlisten()
    screenshotCancelUnlisten = null
  }
  if (floatingBallMovedUnlisten) {
    floatingBallMovedUnlisten()
    floatingBallMovedUnlisten = null
  }
  if (tokenRefreshInterval !== null) {
    clearInterval(tokenRefreshInterval)
    tokenRefreshInterval = null
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
const fileEditSaving = ref(false)
const fileEditShortcutError = ref('')

// Process Management
const showProcessManager = ref(false)
const processManagerFile = ref<FileItem | null>(null)
const fileProcesses = ref<ProcessInfo[]>([])
const loadingProcesses = ref(false)

async function handleFileClick(file: FileItem) {
  try {
    if (!await refreshFavoriteValidity(file)) {
      alert(t('file.pathInvalid'))
      return
    }
    await openFile(file.path)
    fileStore.recordOpen(file.id)
    recentStore.addRecent(file)
    console.log(`已打开收藏项: ${file.id}`)
  } catch (error) {
    console.error(`打开收藏项失败: ${file.id}`)
    alert(`打开失败: ${error}`)
  }
}

async function openFavoriteById(id: string) {
  const file = fileStore.files.find(item => item.id === id)
  if (!file) return
  await handleFileClick(file)
}

async function refreshFavoriteValidity(file: FileItem): Promise<boolean> {
  const valid = await validateFavoritePath(file.path, file.shortcutTargetPath)
  const next = new Set(invalidFavoriteIds.value)
  if (valid) next.delete(file.id)
  else next.add(file.id)
  invalidFavoriteIds.value = next
  return valid
}

async function cleanupManagedArtifact(file: FileItem): Promise<boolean> {
  if (!file.managedArtifact) return true
  try {
    await deleteManagedShortcut(file.managedArtifact.cachePath)
    return true
  } catch {
    console.warn(`Managed shortcut cleanup failed for favorite ${file.id}`)
    return false
  }
}

async function handleRelocateFile(file: FileItem) {
  try {
    const selectedPath = file.type === 'folder'
      ? await pickFolder(file.sourcePath || file.path)
      : await pickFile()
    if (!selectedPath) return
    if (fileStore.files.some(item =>
      item.id !== file.id && (item.path === selectedPath || item.sourcePath === selectedPath)
    )) {
      alert('该项目已存在')
      return
    }
    const descriptor = await importFavoritePath(selectedPath)
    const descriptorValid = await validateFavoritePath(
      descriptor.path,
      descriptor.shortcutTargetPath
    )
    if (!descriptorValid) {
      if (descriptor.managedArtifact) {
        await deleteManagedShortcut(descriptor.managedArtifact.cachePath).catch(() => undefined)
      }
      throw new Error(t('file.pathInvalid'))
    }
    const previous = { ...file }
    fileStore.updateFile(file.id, {
      path: descriptor.path,
      sourcePath: descriptor.sourcePath,
      managedArtifact: descriptor.managedArtifact,
      shortcutTargetPath: descriptor.shortcutTargetPath,
      type: descriptor.itemType
    })
    await refreshFavoriteValidity(file)
    if (!(await cleanupManagedArtifact(previous))) {
      alert(t('file.managedCleanupFailed'))
    }
  } catch (error) {
    alert(t('file.relocateFailed', {
      error: error instanceof Error ? error.message : String(error)
    }))
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

async function handleFileSaved(updates: Partial<FileItem>) {
  const file = editingFile.value
  if (!file || fileEditSaving.value) return
  fileEditSaving.value = true
  fileEditShortcutError.value = ''
  try {
    const desiredShortcut = normalizeShortcut(updates.shortcut ?? '')
    const conflict = findShortcutConflict(desiredShortcut, {
      settings: settingsStore.settings,
      files: fileStore.files,
      excludeFileId: file.id
    })
    if (conflict) {
      fileEditShortcutError.value = t('file.shortcutConflict', { label: conflict.label })
      return
    }
    const shortcut = await favoriteShortcutCoordinator.replace(
      file.id,
      file.shortcut,
      desiredShortcut,
      () => { void openFavoriteById(file.id) }
    )
    fileStore.updateFile(file.id, { ...updates, shortcut: shortcut || undefined })
    await refreshFavoriteValidity(file)
    editingFile.value = null
  } catch (error) {
    fileEditShortcutError.value = t('file.shortcutRegisterFailed', {
      error: error instanceof Error ? error.message : String(error)
    })
  } finally {
    fileEditSaving.value = false
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

async function handleCloseProcess(windowHandle: number, pid: number) {
  const confirmed = confirm(`确定关闭进程 PID ${pid}？`)
  if (!confirmed) return

  try {
    await closeProcess(windowHandle)
    // Reload processes after closing
    if (processManagerFile.value) {
      await loadFileProcesses(processManagerFile.value.path)
    }
  } catch (error) {
    console.error('Failed to close process:', error)
    alert(`关闭进程失败: ${error}`)
  }
}

async function handleRemoveFile(file: FileItem) {
  try {
    await favoriteShortcutCoordinator.unregister(file.id, file.shortcut)
    fileStore.removeFile(file.id)
    invalidFavoriteIds.value = new Set([...invalidFavoriteIds.value].filter(id => id !== file.id))
    if (!(await cleanupManagedArtifact(file))) {
      alert(t('file.managedCleanupFailed'))
    }
    console.log(`已移除收藏项: ${file.id}`)
  } catch (error) {
    alert(t('file.shortcutRemoveFailed', {
      error: error instanceof Error ? error.message : String(error)
    }))
  }
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

<style scoped>
/* Sortable.js drag and drop styles
   IMPORTANT: do NOT override `transform`, `position`, `top`, `left`, `width`,
   `height`, or `opacity` with !important — sortablejs sets these inline on the
   cloned ghost element to make it follow the mouse. Overriding them with
   !important breaks the mouse-following behavior. We only style the *look* of
   the ghost (shadow, slight scale of children, cursor). */

/* The cloned element shown under the cursor while dragging.
   Uses a soft shadow + cursor change for a Windows-like drag preview. */
.sortable-fallback {
  box-shadow:
    0 12px 28px rgba(0, 0, 0, 0.28),
    0 4px 10px rgba(0, 0, 0, 0.18) !important;
  border-radius: 0.5rem !important;
  cursor: grabbing !important;
  transition: none !important;
  /* Slight tint so the ghost reads as "lifted" against the underlying grid */
  outline: 1px solid rgba(59, 130, 246, 0.45) !important;
}

.sortable-fallback * {
  transition: none !important;
  pointer-events: none !important;
}

/* The placeholder left in the original spot — shows the drop target slot. */
.sortable-ghost {
  opacity: 0.35 !important;
  background: rgba(59, 130, 246, 0.08) !important;
  border: 2px dashed #3b82f6 !important;
}

.sortable-ghost > * {
  visibility: hidden;
}

/* The originally chosen item before drag starts — just a cursor hint. */
.sortable-chosen {
  cursor: grabbing;
}
</style>

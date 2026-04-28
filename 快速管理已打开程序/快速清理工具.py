"""
快速清理已打开的应用工具
支持一键关闭 Excel、PPT、浏览器、文件夹、终端、压缩包、文档
"""

import ctypes
import tkinter as tk
from tkinter import ttk
import threading

# 提前导入 psutil；若不可用，后续使用 Windows API 备选方案
try:
    import psutil
except Exception:
    psutil = None

# 提前导入 win32 模块，避免在回调内部首次导入导致异常
import win32gui
import win32process
import win32con


def _com_init():
    ctypes.windll.ole32.CoInitialize(None)


def _com_uninit():
    ctypes.windll.ole32.CoUninitialize()


# ============ 进程映射表 ============

# 浏览器进程 → (显示名称, 类别标识)
BROWSER_PROCS = {
    "chrome.exe": ("Google Chrome", "browser"),
    "msedge.exe": ("Microsoft Edge", "browser"),
    "firefox.exe": ("Mozilla Firefox", "browser"),
    "opera.exe": ("Opera", "browser"),
    "brave.exe": ("Brave", "browser"),
    "vivaldi.exe": ("Vivaldi", "browser"),
    "arc.exe": ("Arc", "browser"),
    "thorium.exe": ("Thorium", "browser"),
    "waterfox.exe": ("Waterfox", "browser"),
    "librewolf.exe": ("LibreWolf", "browser"),
    "palemoon.exe": ("Pale Moon", "browser"),
    "seamonkey.exe": ("SeaMonkey", "browser"),
    "avastbrowser.exe": ("Avast Secure Browser", "browser"),
    "ccleanerbrowser.exe": ("CCleaner Browser", "browser"),
    "yandex.exe": ("Yandex Browser", "browser"),
    "tor.exe": ("Tor Browser", "browser"),
    "iron.exe": ("SRWare Iron", "browser"),
    "slimjet.exe": ("Slimjet", "browser"),
    "360chrome.exe": ("360 极速浏览器", "browser"),
    "360se.exe": ("360 安全浏览器", "browser"),
    "sogouexplorer.exe": ("搜狗浏览器", "browser"),
    "liebao.exe": ("猎豹浏览器", "browser"),
    "qqbrowser.exe": ("QQ浏览器", "browser"),
    "ucbrowser.exe": ("UC浏览器", "browser"),
    "maxthon.exe": ("遨游浏览器", "browser"),
    "theworld.exe": ("世界之窗", "browser"),
    "centbrowser.exe": ("百分浏览器", "browser"),
    "coc_coc_browser.exe": ("Cốc Cốc", "browser"),
    "whale.exe": ("Naver Whale", "browser"),
    "naver.exe": ("Naver", "browser"),
    "duckduckgo.exe": ("DuckDuckGo", "browser"),
    "qutebrowser.exe": ("qutebrowser", "browser"),
}

# 终端进程
TERMINAL_PROCS = {
    "cmd.exe": ("命令提示符 CMD", "terminal"),
    "powershell.exe": ("Windows PowerShell", "terminal"),
    "pwsh.exe": ("PowerShell Core", "terminal"),
    "windowsterminal.exe": ("Windows Terminal", "terminal"),
    "wt.exe": ("Windows Terminal", "terminal"),
    "conhost.exe": ("控制台主机", "terminal"),
    "alacritty.exe": ("Alacritty", "terminal"),
    "wezterm-gui.exe": ("WezTerm", "terminal"),
    "tabby.exe": ("Tabby", "terminal"),
    "hyper.exe": ("Hyper", "terminal"),
    "fluentterminal.exe": ("Fluent Terminal", "terminal"),
    "mintty.exe": ("Mintty", "terminal"),
    "conemu64.exe": ("ConEmu", "terminal"),
    "cmder.exe": ("Cmder", "terminal"),
    "terminus.exe": ("Terminus", "terminal"),
    "electerm.exe": ("Electerm", "terminal"),
    "kitty.exe": ("KiTTY", "terminal"),
    "putty.exe": ("PuTTY", "terminal"),
}

# 压缩包工具进程
ARCHIVE_PROCS = {
    "winrar.exe": ("WinRAR", "archive"),
    "bandizip.exe": ("Bandizip", "archive"),
    "7zfm.exe": ("7-Zip", "archive"),
    "7zg.exe": ("7-Zip", "archive"),
    "peazip.exe": ("PeaZip", "archive"),
    "haozip.exe": ("好压", "archive"),
    "haozipc.exe": ("好压", "archive"),
    "360zip.exe": ("360压缩", "archive"),
    "winzip32.exe": ("WinZip", "archive"),
    "winzip64.exe": ("WinZip", "archive"),
    "izarc.exe": ("IZArc", "archive"),
    "izarc2go.exe": ("IZArc2Go", "archive"),
    "powerarchiver.exe": ("PowerArchiver", "archive"),
    "hamsterfreeziparchiver.exe": ("Hamster ZIP", "archive"),
    "zipware.exe": ("Zipware", "archive"),
    "extractnow.exe": ("ExtractNow", "archive"),
    "universal extractor.exe": ("Universal Extractor", "archive"),
    "breezip.exe": ("BreeZip", "archive"),
    "nanazip.exe": ("NanaZip", "archive"),
}

# 文档/文本编辑器进程
DOCUMENT_PROCS = {
    "notepad.exe": ("记事本", "document"),
    "notepad++.exe": ("Notepad++", "document"),
    "sublime_text.exe": ("Sublime Text", "document"),
    "code.exe": ("VS Code", "document"),
    "cursor.exe": ("Cursor", "document"),
    "zed.exe": ("Zed", "document"),
    "fleet.exe": ("Fleet", "document"),
    "typora.exe": ("Typora", "document"),
    "marktext.exe": ("Mark Text", "document"),
    "notable.exe": ("Notable", "document"),
    "obsidian.exe": ("Obsidian", "document"),
    "logseq.exe": ("Logseq", "document"),
    "joplin.exe": ("Joplin", "document"),
    "trilium.exe": ("Trilium", "document"),
    "standardnotes.exe": ("Standard Notes", "document"),
    "emacs.exe": ("Emacs", "document"),
    "xemacs.exe": ("XEmacs", "document"),
    "vim.exe": ("Vim", "document"),
    "gvim.exe": ("GVim", "document"),
    "nvim.exe": ("Neovim", "document"),
    "neovim.exe": ("Neovim", "document"),
    "neovide.exe": ("Neovide", "document"),
    "wordpad.exe": ("写字板", "document"),
    "write.exe": ("写字板", "document"),
    "word.exe": ("Microsoft Word", "document"),
    "winword.exe": ("Microsoft Word", "document"),
    "wps.exe": ("WPS Office", "document"),
    "et.exe": ("WPS 表格", "document"),
    "wpp.exe": ("WPS 演示", "document"),
    "acrobat.exe": ("Adobe Acrobat", "document"),
    "acrobatreader.exe": ("Adobe Reader", "document"),
    "sumatrapdf.exe": ("Sumatra PDF", "document"),
    "foxitreader.exe": ("Foxit Reader", "document"),
    "pdfxcview.exe": ("PDF-XChange", "document"),
    "nitropdf.exe": ("Nitro PDF", "document"),
    "drawboardpdf.exe": ("Drawboard PDF", "document"),
    "xodo.exe": ("Xodo PDF", "document"),
    "okular.exe": ("Okular", "document"),
    "calibre.exe": ("Calibre", "document"),
    "calibre-parallel.exe": ("Calibre", "document"),
    "sigil.exe": ("Sigil", "document"),
    "markdownpad.exe": ("MarkdownPad", "document"),
    "markdownedit.exe": ("Markdown Edit", "document"),
    "ghostwriter.exe": ("ghostwriter", "document"),
    "zettlr.exe": ("Zettlr", "document"),
    "textedit.exe": ("TextEdit", "document"),
    "gedit.exe": ("gedit", "document"),
    "mousepad.exe": ("Mousepad", "document"),
    "leafpad.exe": ("Leafpad", "document"),
    "featherpad.exe": ("FeatherPad", "document"),
    "xed.exe": ("XED", "document"),
    "pluma.exe": ("Pluma", "document"),
    "kwrite.exe": ("KWrite", "document"),
    "kate.exe": ("Kate", "document"),
    "jedit.exe": ("jEdit", "document"),
    "textpad.exe": ("TextPad", "document"),
    "editplus.exe": ("EditPlus", "document"),
    "ultraedit.exe": ("UltraEdit", "document"),
    "uedit32.exe": ("UltraEdit", "document"),
    "scite.exe": ("SciTE", "document"),
    "sc1.exe": ("SciTE", "document"),
    "codeblocks.exe": ("Code::Blocks", "document"),
    "devcpp.exe": ("Dev-C++", "document"),
    "geany.exe": ("Geany", "document"),
    "bluefish.exe": ("Bluefish", "document"),
    "kdevelop.exe": ("KDevelop", "document"),
    "anjuta.exe": ("Anjuta", "document"),
    "netbeans.exe": ("NetBeans", "document"),
    "eclipse.exe": ("Eclipse", "document"),
    "studio.exe": ("Android Studio", "document"),
    "idea.exe": ("IntelliJ IDEA", "document"),
    "idea64.exe": ("IntelliJ IDEA", "document"),
    "pycharm.exe": ("PyCharm", "document"),
    "pycharm64.exe": ("PyCharm", "document"),
    "webstorm.exe": ("WebStorm", "document"),
    "webstorm64.exe": ("WebStorm", "document"),
    "phpstorm.exe": ("PhpStorm", "document"),
    "phpstorm64.exe": ("PhpStorm", "document"),
    "datagrip.exe": ("DataGrip", "document"),
    "datagrip64.exe": ("DataGrip", "document"),
    "rubymine.exe": ("RubyMine", "document"),
    "rubymine64.exe": ("RubyMine", "document"),
    "goland.exe": ("GoLand", "document"),
    "goland64.exe": ("GoLand", "document"),
    "clion.exe": ("CLion", "document"),
    "clion64.exe": ("CLion", "document"),
    "rider.exe": ("Rider", "document"),
    "rider64.exe": ("Rider", "document"),
    "appcode.exe": ("AppCode", "document"),
    "appcode64.exe": ("AppCode", "document"),
    "androidstudio.exe": ("Android Studio", "document"),
    "androidstudio64.exe": ("Android Studio", "document"),
    "xcode.exe": ("Xcode", "document"),
    "brackets.exe": ("Brackets", "document"),
    "komodo.exe": ("Komodo Edit", "document"),
    "activestate-komodo.exe": ("Komodo Edit", "document"),
    "slickedit.exe": ("SlickEdit", "document"),
    "codeshare.exe": ("CodeShare", "document"),
}

# 媒体播放器进程
MEDIA_PROCS = {
    "vlc.exe": ("VLC 媒体播放器", "media"),
    "mpc-hc.exe": ("Media Player Classic", "media"),
    "mpc-hc64.exe": ("Media Player Classic", "media"),
    "potplayermini.exe": ("PotPlayer", "media"),
    "potplayermini64.exe": ("PotPlayer", "media"),
    "kmplayer.exe": ("KMPlayer", "media"),
    "gomplayer.exe": ("GOM Player", "media"),
    "5kplayer.exe": ("5KPlayer", "media"),
    "foobar2000.exe": ("foobar2000", "media"),
    "aimp.exe": ("AIMP", "media"),
    "aimp3.exe": ("AIMP3", "media"),
    "aimp4.exe": ("AIMP4", "media"),
    "winamp.exe": ("Winamp", "media"),
    "musicbee.exe": ("MusicBee", "media"),
    "mediamonkey.exe": ("MediaMonkey", "media"),
    "itunes.exe": ("iTunes", "media"),
    "spotify.exe": ("Spotify", "media"),
    "netease_cloud_music.exe": ("网易云音乐", "media"),
    "cloudmusic.exe": ("网易云音乐", "media"),
    "qqmusic.exe": ("QQ音乐", "media"),
    "kugou.exe": ("酷狗音乐", "media"),
    "kuwo.exe": ("酷我音乐", "media"),
    "baiduyinyue.exe": ("百度音乐", "media"),
    "xmly.exe": ("喜马拉雅", "media"),
    "mplayer.exe": ("MPlayer", "media"),
    "smplayer.exe": ("SMPlayer", "media"),
    "bomi.exe": ("Bomi", "media"),
    "mpv.exe": ("MPV", "media"),
    "wmplayer.exe": ("Windows Media Player", "media"),
    "microsoft.zunevideo.exe": ("Zune Video", "media"),
    "quicktimeplayer.exe": ("QuickTime Player", "media"),
    "realplay.exe": ("RealPlayer", "media"),
    "realplayer.exe": ("RealPlayer", "media"),
    "divxplayer.exe": ("DivX Player", "media"),
    "xvid.exe": ("Xvid", "media"),
    "aceplayer.exe": ("Ace Player", "media"),
    "acestream.exe": ("AceStream", "media"),
    "plex.exe": ("Plex", "media"),
    "plexmediaplayer.exe": ("Plex Media Player", "media"),
    "jellyfinmediaplayer.exe": ("Jellyfin", "media"),
    "kodi.exe": ("Kodi", "media"),
}

# 图像/设计工具进程
IMAGE_PROCS = {
    "photoshop.exe": ("Adobe Photoshop", "image"),
    "illustrator.exe": ("Adobe Illustrator", "image"),
    "gimp.exe": ("GIMP", "image"),
    "paint.net.exe": ("Paint.NET", "image"),
    "paintdotnet.exe": ("Paint.NET", "image"),
    "inkscape.exe": ("Inkscape", "image"),
    "krita.exe": ("Krita", "image"),
    "affinityphoto.exe": ("Affinity Photo", "image"),
    "affinitydesigner.exe": ("Affinity Designer", "image"),
    "coreldraw.exe": ("CorelDRAW", "image"),
    "corelpaintshoppro.exe": ("Corel PaintShop Pro", "image"),
    "lightroom.exe": ("Adobe Lightroom", "image"),
    "lightroomcc.exe": ("Adobe Lightroom CC", "image"),
    "captureone.exe": ("Capture One", "image"),
    "dxo_photolab.exe": ("DxO PhotoLab", "image"),
    "luminar.exe": ("Luminar", "image"),
    "on1photo.exe": ("ON1 Photo RAW", "image"),
    "skylumluminar.exe": ("Luminar AI", "image"),
    "acdsee.exe": ("ACDSee", "image"),
    "acdseepro.exe": ("ACDSee Pro", "image"),
    "xnview.exe": ("XnView", "image"),
    "xnviewmp.exe": ("XnView MP", "image"),
    "irfanview.exe": ("IrfanView", "image"),
    "faststoneimageviewer.exe": ("FastStone Image Viewer", "image"),
    "honeyview.exe": ("Honeyview", "image"),
    "imageglass.exe": ("ImageGlass", "image"),
    "nomacs.exe": ("nomacs", "image"),
    "jpegview.exe": ("JPEGView", "image"),
    "picasa.exe": ("Picasa", "image"),
    "photos.exe": ("Windows 照片", "image"),
    "mspaint.exe": ("画图", "image"),
    "paint3d.exe": ("画图 3D", "image"),
    "sketch.exe": ("Sketch", "image"),
    "figma.exe": ("Figma", "image"),
    "canva.exe": ("Canva", "image"),
    "blender.exe": ("Blender", "image"),
    "maya.exe": ("Maya", "image"),
    "3dsmax.exe": ("3ds Max", "image"),
    "cinema4d.exe": ("Cinema 4D", "image"),
    "zbrush.exe": ("ZBrush", "image"),
    "houdini.exe": ("Houdini", "image"),
    "substancepainter.exe": ("Substance Painter", "image"),
    "substanceDesigner.exe": ("Substance Designer", "image"),
    "unity.exe": ("Unity", "image"),
    "unrealeditor.exe": ("Unreal Editor", "image"),
    "godot.exe": ("Godot", "image"),
}

# 通讯/聊天工具进程
COMM_PROCS = {
    "wechat.exe": ("微信", "comm"),
    "wechatapp.exe": ("微信", "comm"),
    "qq.exe": ("QQ", "comm"),
    "tim.exe": ("TIM", "comm"),
    "dingtalk.exe": ("钉钉", "comm"),
    "dingtalklauncher.exe": ("钉钉", "comm"),
    "wework.exe": ("企业微信", "comm"),
    "wxwork.exe": ("企业微信", "comm"),
    "feishu.exe": ("飞书", "comm"),
    "lark.exe": ("飞书", "comm"),
    "slack.exe": ("Slack", "comm"),
    "discord.exe": ("Discord", "comm"),
    "teams.exe": ("Microsoft Teams", "comm"),
    "skype.exe": ("Skype", "comm"),
    "telegram.exe": ("Telegram", "comm"),
    "whatsapp.exe": ("WhatsApp", "comm"),
    "line.exe": ("LINE", "comm"),
    "viber.exe": ("Viber", "comm"),
    "signal.exe": ("Signal", "comm"),
    "element.exe": ("Element", "comm"),
    "zoom.exe": ("Zoom", "comm"),
    "webex.exe": ("WebEx", "comm"),
    "teamviewer.exe": ("TeamViewer", "comm"),
    "anydesk.exe": ("AnyDesk", "comm"),
    "rustdesk.exe": ("RustDesk", "comm"),
    "toDesk.exe": ("ToDesk", "comm"),
    "sunloginclient.exe": ("向日葵远程", "comm"),
    "goToMeeting.exe": ("GoToMeeting", "comm"),
    "meet.exe": ("Google Meet", "comm"),
    "outlook.exe": ("Microsoft Outlook", "comm"),
    "thunderbird.exe": ("Thunderbird", "comm"),
    "foxmail.exe": ("Foxmail", "comm"),
    "mailmaster.exe": ("网易邮箱大师", "comm"),
    "mailbird.exe": ("Mailbird", "comm"),
    "eMclient.exe": ("eM Client", "comm"),
    "postbox.exe": ("Postbox", "comm"),
    "sparrow.exe": ("Sparrow", "comm"),
    "airemail.exe": ("Airmail", "comm"),
    "spark.exe": ("Spark Mail", "comm"),
    "mailspring.exe": ("Mailspring", "comm"),
    "geary.exe": ("Geary", "comm"),
    "evolution.exe": ("Evolution", "comm"),
}

# 下载/传输工具进程
DOWNLOAD_PROCS = {
    "baidunetdisk.exe": ("百度网盘", "download"),
    "baidunetdiskhost.exe": ("百度网盘", "download"),
    "163yunpan.exe": ("网易网盘", "download"),
    "115yunpan.exe": ("115网盘", "download"),
    "115.exe": ("115网盘", "download"),
    "aliyunpan.exe": ("阿里云盘", "download"),
    "aliyundrive.exe": ("阿里云盘", "download"),
    "quark.exe": ("夸克网盘", "download"),
    "ucdrive.exe": ("UC网盘", "download"),
    "nutstore.exe": ("坚果云", "download"),
    "dropbox.exe": ("Dropbox", "download"),
    "onedrive.exe": ("OneDrive", "download"),
    "googledrive.exe": ("Google Drive", "download"),
    "megasync.exe": ("MEGA", "download"),
    "pcloud.exe": ("pCloud", "download"),
    "boxsync.exe": ("Box", "download"),
    "icloud.exe": ("iCloud", "download"),
    "synologydrive.exe": ("Synology Drive", "download"),
    "resilio-sync.exe": ("Resilio Sync", "download"),
    "bt-sync.exe": ("BitTorrent Sync", "download"),
    "bittorrent.exe": ("BitTorrent", "download"),
    "utorrent.exe": ("uTorrent", "download"),
    "qbittorrent.exe": ("qBittorrent", "download"),
    "transmission.exe": ("Transmission", "download"),
    "deluge.exe": ("Deluge", "download"),
    "vuze.exe": ("Vuze", "download"),
    "azureus.exe": ("Vuze", "download"),
    "frostwire.exe": ("Frostwire", "download"),
    "flashget.exe": ("快车 FlashGet", "download"),
    "thunder.exe": ("迅雷", "download"),
    "thunder5.exe": ("迅雷5", "download"),
    "xunlei.exe": ("迅雷", "download"),
    "xdm.exe": ("XDM", "download"),
    "fdm.exe": ("Free Download Manager", "download"),
    "jdownloader.exe": ("JDownloader", "download"),
    "jdownloader2.exe": ("JDownloader 2", "download"),
    "idman.exe": ("Internet Download Manager", "download"),
    "idm.exe": ("Internet Download Manager", "download"),
    "eagleget.exe": ("EagleGet", "download"),
    "orbitdownloader.exe": ("Orbit Downloader", "download"),
    "filezilla.exe": ("FileZilla", "download"),
    "winscp.exe": ("WinSCP", "download"),
    "cyberduck.exe": ("Cyberduck", "download"),
    "putty.exe": ("PuTTY", "download"),
    "flashfxp.exe": ("FlashFXP", "download"),
    "cuteftp.exe": ("CuteFTP", "download"),
    "coreftp.exe": ("Core FTP", "download"),
}

# 游戏/娱乐进程
GAME_PROCS = {
    "steam.exe": ("Steam", "game"),
    "steamwebhelper.exe": ("Steam", "game"),
    "epicgameslauncher.exe": ("Epic Games", "game"),
    "origin.exe": ("EA Origin", "game"),
    "eaapp.exe": ("EA App", "game"),
    "uplay.exe": ("Ubisoft Connect", "game"),
    "ubisoftconnect.exe": ("Ubisoft Connect", "game"),
    "battlenet.exe": ("Battle.net", "game"),
    "blizzardapp.exe": ("Battle.net", "game"),
    "goggalaxy.exe": ("GOG Galaxy", "game"),
    "gamesforwindowslive.exe": ("Games for Windows", "game"),
    "rockstarlauncher.exe": ("Rockstar Games", "game"),
    "socialclub.exe": ("Rockstar Social Club", "game"),
    "bethesdanetlauncher.exe": ("Bethesda", "game"),
    "minecraft.exe": ("Minecraft", "game"),
    "minecraftlauncher.exe": ("Minecraft Launcher", "game"),
    "leagueoflegends.exe": ("英雄联盟", "game"),
    "lol.exe": ("英雄联盟", "game"),
    "dota2.exe": ("Dota 2", "game"),
    "csgo.exe": ("CS:GO", "game"),
    "cs2.exe": ("CS2", "game"),
    "valorant.exe": ("Valorant", "game"),
    "overwatch.exe": ("守望先锋", "game"),
    "wow.exe": ("魔兽世界", "game"),
    "worldofwarcraft.exe": ("魔兽世界", "game"),
    "diabloiii.exe": ("暗黑破坏神3", "game"),
    "diabloiv.exe": ("暗黑破坏神4", "game"),
    "starcraftii.exe": ("星际争霸2", "game"),
    "hearthstone.exe": ("炉石传说", "game"),
    "heroesofthestorm.exe": ("风暴英雄", "game"),
    "apexlegend.exe": ("Apex Legends", "game"),
    "fortnite.exe": ("Fortnite", "game"),
    "pubg.exe": ("PUBG", "game"),
    "gtav.exe": ("GTA V", "game"),
    "cyberpunk2077.exe": ("赛博朋克2077", "game"),
    "eldenring.exe": ("艾尔登法环", "game"),
    "genshinimpact.exe": ("原神", "game"),
    "ys.exe": ("原神", "game"),
    "honkaiimpact3.exe": ("崩坏3", "game"),
    "honkaistarrail.exe": ("崩坏：星穹铁道", "game"),
    "starrail.exe": ("崩坏：星穹铁道", "game"),
    "wutheringwaves.exe": ("鸣潮", "game"),
    "game.exe": ("游戏", "game"),
}

# 系统工具进程
SYSTEM_PROCS = {
    "taskmgr.exe": ("任务管理器", "system"),
    "control.exe": ("控制面板", "system"),
    "ms-settings.exe": ("Windows 设置", "system"),
    "settings.exe": ("Windows 设置", "system"),
    "mmc.exe": ("Microsoft 管理控制台", "system"),
    "regedit.exe": ("注册表编辑器", "system"),
    "regedt32.exe": ("注册表编辑器", "system"),
    "gpedit.exe": ("组策略编辑器", "system"),
    "secpol.exe": ("本地安全策略", "system"),
    "lusrmgr.exe": ("本地用户和组", "system"),
    "eventvwr.exe": ("事件查看器", "system"),
    "perfmon.exe": ("性能监视器", "system"),
    "resmon.exe": ("资源监视器", "system"),
    "msinfo32.exe": ("系统信息", "system"),
    "dxdiag.exe": ("DirectX 诊断工具", "system"),
    "devmgmt.exe": ("设备管理器", "system"),
    "diskmgmt.exe": ("磁盘管理", "system"),
    "services.exe": ("服务", "system"),
    "compmgmt.exe": ("计算机管理", "system"),
    "calc.exe": ("计算器", "system"),
    "calculator.exe": ("计算器", "system"),
    "wincalc.exe": ("计算器", "system"),
    "notepad.exe": ("记事本", "system"),
    "mspaint.exe": ("画图", "system"),
    "wordpad.exe": ("写字板", "system"),
    "sndvol.exe": ("音量控制", "system"),
    "soundvol.exe": ("音量控制", "system"),
    "mmsys.cpl.exe": ("声音设置", "system"),
    "display.cpl.exe": ("显示设置", "system"),
    "desk.cpl.exe": ("显示设置", "system"),
    "main.cpl.exe": ("鼠标设置", "system"),
    "keyboard.cpl.exe": ("键盘设置", "system"),
    "intl.cpl.exe": ("区域设置", "system"),
    "timedate.cpl.exe": ("日期和时间", "system"),
    "appwiz.cpl.exe": ("程序和功能", "system"),
    "firewall.cpl.exe": ("Windows 防火墙", "system"),
    "netsetup.cpl.exe": ("网络设置", "system"),
    "powercfg.cpl.exe": ("电源选项", "system"),
    "systempropertiesadvanced.exe": ("系统属性", "system"),
    "systempropertiesperformance.exe": ("性能选项", "system"),
    "systempropertiesprotection.exe": ("系统保护", "system"),
    "systempropertieshardware.exe": ("硬件属性", "system"),
    "useraccountcontrolsettings.exe": ("UAC 设置", "system"),
    "credwiz.exe": ("凭据管理器", "system"),
    "certmgr.exe": ("证书管理器", "system"),
    "cluscfg.exe": ("群集配置", "system"),
    "dfrgui.exe": ("磁盘碎片整理", "system"),
    "dfrgui.exe": ("优化驱动器", "system"),
    "cleanmgr.exe": ("磁盘清理", "system"),
    "mstsc.exe": ("远程桌面连接", "system"),
    "rdpclip.exe": ("远程桌面剪贴板", "system"),
    "wsl.exe": ("WSL", "system"),
    "ubuntu.exe": ("Ubuntu WSL", "system"),
    "debian.exe": ("Debian WSL", "system"),
    "kali.exe": ("Kali WSL", "system"),
    "opensuse.exe": ("OpenSUSE WSL", "system"),
    "sles.exe": ("SLES WSL", "system"),
    "alpine.exe": ("Alpine WSL", "system"),
    "oraclelinux.exe": ("Oracle Linux WSL", "system"),
}


# ============ Windows API 备选：不依赖 psutil 获取进程名 ============

_kernel32 = ctypes.windll.kernel32
_PROCESS_QUERY_LIMITED_INFORMATION = 0x1000


def _get_process_name_from_pid(pid):
    """通过 Windows API 获取进程可执行文件名，不依赖 psutil。"""
    try:
        hProcess = _kernel32.OpenProcess(_PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
        if not hProcess:
            return None
        try:
            buf = ctypes.create_unicode_buffer(512)
            size = ctypes.wintypes.DWORD(512)
            if _kernel32.QueryFullProcessImageNameW(hProcess, 0, buf, ctypes.byref(size)):
                return buf.value.split('\\')[-1].lower()
        finally:
            _kernel32.CloseHandle(hProcess)
    except Exception:
        pass
    return None


# ============ 通用窗口枚举（基于进程名，不再依赖类名） ============

def _get_windows_by_process(process_map):
    """
    通过进程名枚举可见窗口。
    process_map: {进程名小写: (显示名称, 类别标识)}
    """
    results = []
    # 如果 psutil 可用，先建立 PID→进程名缓存，减少重复查询
    pid_cache = {}
    if psutil is not None:
        try:
            for proc in psutil.process_iter(['pid', 'name']):
                try:
                    pid_cache[proc.info['pid']] = proc.info['name'].lower()
                except Exception:
                    pass
        except Exception:
            pass

    def enum_callback(hwnd, _):
        if not win32gui.IsWindowVisible(hwnd):
            return
        title = win32gui.GetWindowText(hwnd)
        if not title:
            return
        try:
            _, pid = win32process.GetWindowThreadProcessId(hwnd)
            proc_name = pid_cache.get(pid)
            # 缓存未命中时，尝试 psutil 单次查询或 Windows API
            if proc_name is None:
                if psutil is not None:
                    try:
                        proc_name = psutil.Process(pid).name().lower()
                    except Exception:
                        pass
                if proc_name is None:
                    proc_name = _get_process_name_from_pid(pid)
                if proc_name is not None:
                    pid_cache[pid] = proc_name
            if proc_name is not None and proc_name in process_map:
                display_name, _ = process_map[proc_name]
                results.append({
                    "name": title,
                    "hwnd": hwnd,
                    "process": proc_name,
                    "source": display_name,
                    "id": str(hwnd),
                })
        except Exception:
            pass

    try:
        win32gui.EnumWindows(enum_callback, None)
    except Exception:
        pass

    return results


def _close_hwnd_windows(windows_to_close):
    """通过窗口句柄发送关闭消息"""
    for win in windows_to_close:
        hwnd = win.get("hwnd")
        if hwnd:
            try:
                win32gui.PostMessage(hwnd, win32con.WM_CLOSE, 0, 0)
            except Exception:
                pass


# ============ Excel 部分 ============

def _try_get_workbooks(prog_id, source_label):
    import comtypes.client
    try:
        app = comtypes.client.GetActiveObject(prog_id, dynamic=True)
    except Exception:
        return []

    try:
        wb_count = app.Workbooks.Count
        workbooks = []
        for i in range(1, wb_count + 1):
            wb = app.Workbooks.Item(i)
            workbooks.append({
                "name": wb.Name,
                "full_path": wb.FullName,
                "index": i,
                "prog_id": prog_id,
                "source": source_label,
            })
        return workbooks
    except Exception:
        return []


def _get_open_excel_files():
    results = []
    seen = set()
    for prog_id, label in [
        ("Excel.Application", "Office Excel"),
        ("Ket.Application", "WPS Excel"),
        ("kwps.Application", "WPS"),
    ]:
        for wb in _try_get_workbooks(prog_id, label):
            key = (wb["full_path"], wb["name"])
            if key not in seen:
                seen.add(key)
                results.append(wb)
    return results


def _close_excel_workbooks(wbs_to_close):
    import comtypes.client
    grouped = {}
    for wb in wbs_to_close:
        grouped.setdefault(wb["prog_id"], []).append(wb)

    for prog_id, wbs in grouped.items():
        try:
            app = comtypes.client.GetActiveObject(prog_id, dynamic=True)
            total = app.Workbooks.Count
            closing_all = len(wbs) >= total

            if closing_all:
                try:
                    app.Quit()
                except Exception:
                    # 如果 Quit 失败，尝试逐个关闭
                    for i in range(total, 0, -1):
                        try:
                            app.Workbooks.Item(i).Close()
                        except Exception:
                            pass
            else:
                # 按名称匹配关闭
                names_to_close = {wb["name"] for wb in wbs}
                for i in range(total, 0, -1):
                    try:
                        wb_obj = app.Workbooks.Item(i)
                        if wb_obj.Name in names_to_close:
                            wb_obj.Close()
                    except Exception:
                        pass
        except Exception:
            pass


# ============ PPT 部分 ============

def _try_get_presentations(prog_id, source_label):
    import comtypes.client
    try:
        app = comtypes.client.GetActiveObject(prog_id, dynamic=True)
    except Exception:
        return []

    try:
        count = app.Presentations.Count
        items = []
        for i in range(1, count + 1):
            pres = app.Presentations.Item(i)
            items.append({
                "name": pres.Name,
                "full_path": pres.FullName,
                "index": i,
                "prog_id": prog_id,
                "source": source_label,
            })
        return items
    except Exception:
        return []


def _get_open_ppt_files():
    results = []
    seen = set()
    for prog_id, label in [
        ("PowerPoint.Application", "Office PowerPoint"),
        ("WPP.Application", "WPS PPT"),
        ("ET.Application", "WPS 文档"),
    ]:
        for pres in _try_get_presentations(prog_id, label):
            key = (pres["full_path"], pres["name"])
            if key not in seen:
                seen.add(key)
                results.append(pres)
    return results


def _close_ppt_presentations(pres_to_close):
    import comtypes.client
    grouped = {}
    for pres in pres_to_close:
        grouped.setdefault(pres["prog_id"], []).append(pres)

    for prog_id, pres_list in grouped.items():
        try:
            app = comtypes.client.GetActiveObject(prog_id, dynamic=True)
            total = app.Presentations.Count
            closing_all = len(pres_list) >= total

            if closing_all:
                try:
                    app.Quit()
                except Exception:
                    for i in range(total, 0, -1):
                        try:
                            app.Presentations.Item(i).Close()
                        except Exception:
                            pass
            else:
                names_to_close = {pres["name"] for pres in pres_list}
                for i in range(total, 0, -1):
                    try:
                        pres_obj = app.Presentations.Item(i)
                        if pres_obj.Name in names_to_close:
                            pres_obj.Close()
                    except Exception:
                        pass
        except Exception:
            pass


# ============ 浏览器窗口部分（修复：以进程为主导） ============

def _get_browser_windows():
    """获取浏览器窗口列表——通过进程名匹配，兼容性更强"""
    return _get_windows_by_process(BROWSER_PROCS)


def _close_browser_windows(windows_to_close):
    """关闭浏览器窗口"""
    _close_hwnd_windows(windows_to_close)


# ============ 文件夹部分 ============

def _get_explorer_windows():
    import win32gui

    results = []

    def enum_callback(hwnd, windows):
        if not win32gui.IsWindowVisible(hwnd):
            return

        class_name = ""
        try:
            class_name = win32gui.GetClassName(hwnd)
        except Exception:
            pass

        if "CabinetWClass" not in class_name and "ExploreWClass" not in class_name:
            return

        title = win32gui.GetWindowText(hwnd)
        if not title:
            title = "文件夹"

        windows.append({
            "name": title,
            "hwnd": hwnd,
            "source": "文件资源管理器",
        })

    windows = []
    try:
        win32gui.EnumWindows(enum_callback, windows)
    except Exception:
        pass

    return windows


def _close_explorer_windows(windows_to_close):
    _close_hwnd_windows(windows_to_close)


# ============ 终端窗口部分 ============

def _get_terminal_windows():
    """获取终端/命令行窗口列表"""
    return _get_windows_by_process(TERMINAL_PROCS)


def _close_terminal_windows(windows_to_close):
    """关闭终端窗口"""
    _close_hwnd_windows(windows_to_close)


# ============ 压缩包窗口部分 ============

def _get_archive_windows():
    """获取压缩包工具窗口列表"""
    return _get_windows_by_process(ARCHIVE_PROCS)


def _close_archive_windows(windows_to_close):
    """关闭压缩包窗口"""
    _close_hwnd_windows(windows_to_close)


# ============ 文档窗口部分 ============

def _get_document_windows():
    """获取文档/文本编辑器窗口列表"""
    return _get_windows_by_process(DOCUMENT_PROCS)


def _close_document_windows(windows_to_close):
    """关闭文档窗口"""
    _close_hwnd_windows(windows_to_close)


# ============ 媒体播放器窗口部分 ============

def _get_media_windows():
    """获取媒体播放器窗口列表"""
    return _get_windows_by_process(MEDIA_PROCS)


def _close_media_windows(windows_to_close):
    """关闭媒体播放器窗口"""
    _close_hwnd_windows(windows_to_close)


# ============ 图像/设计工具窗口部分 ============

def _get_image_windows():
    """获取图像/设计工具窗口列表"""
    return _get_windows_by_process(IMAGE_PROCS)


def _close_image_windows(windows_to_close):
    """关闭图像/设计工具窗口"""
    _close_hwnd_windows(windows_to_close)


# ============ 通讯/聊天工具窗口部分 ============

def _get_comm_windows():
    """获取通讯/聊天工具窗口列表"""
    return _get_windows_by_process(COMM_PROCS)


def _close_comm_windows(windows_to_close):
    """关闭通讯/聊天工具窗口"""
    _close_hwnd_windows(windows_to_close)


# ============ 下载/传输工具窗口部分 ============

def _get_download_windows():
    """获取下载/传输工具窗口列表"""
    return _get_windows_by_process(DOWNLOAD_PROCS)


def _close_download_windows(windows_to_close):
    """关闭下载/传输工具窗口"""
    _close_hwnd_windows(windows_to_close)


# ============ 游戏/娱乐窗口部分 ============

def _get_game_windows():
    """获取游戏/娱乐窗口列表"""
    return _get_windows_by_process(GAME_PROCS)


def _close_game_windows(windows_to_close):
    """关闭游戏/娱乐窗口"""
    _close_hwnd_windows(windows_to_close)


# ============ 系统工具窗口部分 ============

def _get_system_windows():
    """获取系统工具窗口列表"""
    return _get_windows_by_process(SYSTEM_PROCS)


def _close_system_windows(windows_to_close):
    """关闭系统工具窗口"""
    _close_hwnd_windows(windows_to_close)


# ============ GUI 部分 ============

class CleanerApp:
    def __init__(self, root):
        self.root = root
        self.root.title("快速清理已打开的应用")
        self.root.geometry("950x600")
        self.root.resizable(True, True)

        self.tabs = {}
        self.tab_data = {}

        self._build_ui()
        self._refresh_all()

    def _build_ui(self):
        self.notebook = ttk.Notebook(self.root)
        self.notebook.pack(fill=tk.BOTH, expand=True, padx=8, pady=8)

        # tab_id, 显示名称, 获取函数, 关闭函数, (可选)COM ProgID列表
        tabs_config = [
            ("excel", "Excel", _get_open_excel_files, _close_excel_workbooks, [
                ("Excel.Application", "Office Excel"),
                ("Ket.Application", "WPS Excel"),
                ("kwps.Application", "WPS"),
            ]),
            ("ppt", "PPT", _get_open_ppt_files, _close_ppt_presentations, [
                ("PowerPoint.Application", "Office PowerPoint"),
                ("WPP.Application", "WPS PPT"),
            ]),
            ("browser", "浏览器", _get_browser_windows, _close_browser_windows, None),
            ("explorer", "文件夹", _get_explorer_windows, _close_explorer_windows, None),
            ("terminal", "终端", _get_terminal_windows, _close_terminal_windows, None),
            ("archive", "压缩包", _get_archive_windows, _close_archive_windows, None),
            ("document", "文档", _get_document_windows, _close_document_windows, None),
            ("media", "媒体", _get_media_windows, _close_media_windows, None),
            ("image", "图像", _get_image_windows, _close_image_windows, None),
            ("comm", "通讯", _get_comm_windows, _close_comm_windows, None),
            ("download", "下载", _get_download_windows, _close_download_windows, None),
            ("game", "游戏", _get_game_windows, _close_game_windows, None),
            ("system", "系统", _get_system_windows, _close_system_windows, None),
        ]

        for tab_id, tab_name, getter, closer, _ in tabs_config:
            frame = ttk.Frame(self.notebook)
            self.notebook.add(frame, text=tab_name)
            self.tabs[tab_id] = frame
            self.tab_data[tab_id] = {
                "getter": getter,
                "closer": closer,
                "items": [],
                "check_vars": [],
            }
            self._build_tab_ui(tab_id, frame)

    def _build_tab_ui(self, tab_id, parent):
        toolbar = ttk.Frame(parent, padding=8)
        toolbar.pack(fill=tk.X)

        ttk.Button(toolbar, text="刷新列表", command=lambda: self._refresh_tab(tab_id)).pack(
            side=tk.LEFT, padx=(0, 8))
        ttk.Button(toolbar, text="全选", command=lambda: self._select_all(tab_id)).pack(
            side=tk.LEFT, padx=(0, 4))
        ttk.Button(toolbar, text="取消全选", command=lambda: self._deselect_all(tab_id)).pack(
            side=tk.LEFT, padx=(0, 4))
        ttk.Button(toolbar, text="反选", command=lambda: self._invert_selection(tab_id)).pack(
            side=tk.LEFT)
        ttk.Label(toolbar, text="勾选 = 保留，未勾选 = 将被关闭",
                  foreground="gray").pack(side=tk.RIGHT)

        list_frame = ttk.Frame(parent, padding=(8, 0, 8, 0))
        list_frame.pack(fill=tk.BOTH, expand=True)

        # 窗口类应用统一使用 3 列；Office 类使用 4 列（含路径）
        if tab_id in ("browser", "explorer", "terminal", "archive", "document",
                      "media", "image", "comm", "download", "game", "system"):
            columns = ("select", "name", "source")
            self._setup_treeview(tab_id, list_frame, columns, {
                "select": (50, tk.CENTER, False),
                "name": (550, tk.W, True),
                "source": (150, tk.CENTER, False),
            })
        else:
            columns = ("select", "name", "path", "source")
            self._setup_treeview(tab_id, list_frame, columns, {
                "select": (50, tk.CENTER, False),
                "name": (200, tk.W, True),
                "path": (500, tk.W, True),
                "source": (120, tk.CENTER, False),
            })

        status_frame = ttk.Frame(parent)
        status_frame.pack(fill=tk.X)

        tab_names = {
            "excel": "Excel", "ppt": "PPT", "browser": "浏览器",
            "explorer": "文件夹", "terminal": "终端",
            "archive": "压缩包", "document": "文档",
            "media": "媒体", "image": "图像", "comm": "通讯",
            "download": "下载", "game": "游戏", "system": "系统",
        }
        self.tab_data[tab_id]["status_var"] = tk.StringVar(
            value=f"正在获取已打开的{tab_names.get(tab_id, '')}...")
        ttk.Label(status_frame, textvariable=self.tab_data[tab_id]["status_var"],
                  relief=tk.SUNKEN, anchor=tk.W, padding=(8, 4)).pack(fill=tk.X)

        bottom = ttk.Frame(parent, padding=8)
        bottom.pack(fill=tk.X)

        tab_close_text = {
            "excel": "关闭未勾选的Excel",
            "ppt": "关闭未勾选的PPT",
            "browser": "关闭未勾选的浏览器标签页",
            "explorer": "关闭未勾选的文件夹",
            "terminal": "关闭未勾选的终端窗口",
            "archive": "关闭未勾选的压缩包",
            "document": "关闭未勾选的文档",
            "media": "关闭未勾选的媒体播放器",
            "image": "关闭未勾选的图像工具",
            "comm": "关闭未勾选的通讯工具",
            "download": "关闭未勾选的下载工具",
            "game": "关闭未勾选的游戏",
            "system": "关闭未勾选的系统工具",
        }
        self.tab_data[tab_id]["close_btn"] = ttk.Button(
            bottom, text=tab_close_text.get(tab_id, "关闭"),
            command=lambda: self._close_unselected(tab_id))
        self.tab_data[tab_id]["close_btn"].pack(side=tk.RIGHT)

    def _setup_treeview(self, tab_id, parent, columns, col_config):
        tree = ttk.Treeview(parent, columns=columns, show="headings", selectmode="none")

        for col in columns:
            width, anchor, stretch = col_config.get(col, (100, tk.W, True))
            if col == "name":
                text = "名称"
            elif col == "path":
                text = "完整路径"
            elif col == "source":
                text = "来源"
            elif col == "select":
                text = "保留"
            else:
                text = col.capitalize()
            tree.heading(col, text=text)
            tree.column(col, width=width, anchor=anchor, stretch=stretch)
        tree.heading("source", text="来源")

        vsb = ttk.Scrollbar(parent, orient=tk.VERTICAL, command=tree.yview)
        tree.configure(yscrollcommand=vsb.set)
        tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        vsb.pack(side=tk.RIGHT, fill=tk.Y)
        tree.bind("<ButtonRelease-1>", lambda e, t=tab_id: self._on_tree_click(e, t))

        tree.tag_configure("checked", foreground="black")
        tree.tag_configure("unchecked", foreground="gray")

        self.tab_data[tab_id]["tree"] = tree

    def _run_com(self, func, on_done):
        def worker():
            _com_init()
            try:
                result = func()
            finally:
                _com_uninit()
            self.root.after(0, lambda: on_done(result))

        threading.Thread(target=worker, daemon=True).start()

    def _run_normal(self, func, on_done):
        def worker():
            try:
                result = func()
            except Exception:
                result = []
            self.root.after(0, lambda: on_done(result))

        threading.Thread(target=worker, daemon=True).start()

    def _refresh_all(self):
        for tab_id in self.tab_data:
            self._refresh_tab(tab_id)

    def _refresh_tab(self, tab_id):
        data = self.tab_data[tab_id]
        tree = data["tree"]

        for item in tree.get_children():
            tree.delete(item)

        data["items"] = []
        data["check_vars"] = []
        data["status_var"].set(f"正在获取已打开的...")
        data["close_btn"].config(state=tk.DISABLED)

        tab_names = {
            "excel": "Excel", "ppt": "PPT", "browser": "浏览器",
            "explorer": "文件夹", "terminal": "终端",
            "archive": "压缩包", "document": "文档",
            "media": "媒体", "image": "图像", "comm": "通讯",
            "download": "下载", "game": "游戏", "system": "系统",
        }
        status_prefix = f"正在获取已打开的{tab_names.get(tab_id, '')}..."

        def on_done(items):
            data["items"] = items
            for i, item in enumerate(items):
                var = tk.BooleanVar(value=True)
                data["check_vars"].append(var)

                if tab_id in ("browser", "explorer", "terminal", "archive", "document",
                              "media", "image", "comm", "download", "game", "system"):
                    display_name = item["name"]
                    tree.insert("", tk.END, iid=str(i),
                                values=("☑", display_name[:100], item["source"]),
                                tags=("checked",))
                else:
                    display_path = item.get("full_path", "")
                    tree.insert("", tk.END, iid=str(i),
                                values=("☑", item["name"], display_path, item["source"]),
                                tags=("checked",))

            n = len(items)
            tab_name = tab_names.get(tab_id, "")
            if n > 0:
                data["status_var"].set(f"共找到 {n} 个已打开的{tab_name}")
                data["close_btn"].config(state=tk.NORMAL)
            else:
                data["status_var"].set(f"没有找到已打开的{tab_name}")

        getter = data["getter"]
        if tab_id in ("browser", "explorer", "terminal", "archive", "document",
                      "media", "image", "comm", "download", "game", "system"):
            self._run_normal(getter, on_done)
        else:
            self._run_com(getter, on_done)

    def _update_row(self, tab_id, idx):
        data = self.tab_data[tab_id]
        item = data["items"][idx]
        checked = data["check_vars"][idx].get()
        tree = data["tree"]

        if tab_id in ("browser", "explorer", "terminal", "archive", "document",
                      "media", "image", "comm", "download", "game", "system"):
            display_name = item["name"]
            tree.item(str(idx),
                      values=("☑" if checked else "☐", display_name[:100], item["source"]),
                      tags=("checked" if checked else "unchecked",))
        else:
            display_path = item.get("full_path", "")
            tree.item(str(idx),
                      values=("☑" if checked else "☐", item["name"], display_path, item["source"]),
                      tags=("checked" if checked else "unchecked",))

    def _on_tree_click(self, event, tab_id):
        tree = self.tab_data[tab_id]["tree"]
        if tree.identify_region(event.x, event.y) != "cell":
            return
        if tree.identify_column(event.x) != "#1":
            return
        item_id = tree.identify_row(event.y)
        if not item_id:
            return
        idx = int(item_id)
        data = self.tab_data[tab_id]
        data["check_vars"][idx].set(not data["check_vars"][idx].get())
        self._update_row(tab_id, idx)

    def _for_each(self, tab_id, value_func):
        data = self.tab_data[tab_id]
        for i, var in enumerate(data["check_vars"]):
            var.set(value_func(var.get()))
            self._update_row(tab_id, i)

    def _select_all(self, tab_id):
        self._for_each(tab_id, lambda _: True)

    def _deselect_all(self, tab_id):
        self._for_each(tab_id, lambda _: False)

    def _invert_selection(self, tab_id):
        self._for_each(tab_id, lambda v: not v)

    def _close_unselected(self, tab_id):
        data = self.tab_data[tab_id]
        to_close = [item for i, item in enumerate(data["items"])
                    if not data["check_vars"][i].get()]

        if not to_close:
            tab_names = {
                "excel": "Excel", "ppt": "PPT", "browser": "浏览器",
                "explorer": "文件夹", "terminal": "终端",
                "archive": "压缩包", "document": "文档",
            }
            data["status_var"].set(f"没有需要关闭的{tab_names.get(tab_id, '')}")
            return

        n = len(to_close)
        tab_names = {
            "excel": "Excel", "ppt": "PPT", "browser": "浏览器",
            "explorer": "文件夹", "terminal": "终端",
            "archive": "压缩包", "document": "文档",
            "media": "媒体", "image": "图像", "comm": "通讯",
            "download": "下载", "game": "游戏", "system": "系统",
        }
        data["status_var"].set(f"正在关闭 {n} 个{tab_names.get(tab_id, '')}...")
        data["close_btn"].config(state=tk.DISABLED)

        def on_done(_):
            data["status_var"].set(f"已关闭 {n} 个{tab_names.get(tab_id, '')}")
            self._refresh_tab(tab_id)

        closer = data["closer"]
        if tab_id in ("browser", "explorer", "terminal", "archive", "document",
                      "media", "image", "comm", "download", "game", "system"):
            self._run_normal(lambda: closer(to_close), on_done)
        else:
            self._run_com(lambda: closer(to_close), on_done)


def main():
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except Exception:
        pass

    root = tk.Tk()
    CleanerApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()

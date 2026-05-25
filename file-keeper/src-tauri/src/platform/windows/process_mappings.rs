use crate::types::process::ProcessCategory;
use std::collections::HashMap;

/// Load all process mappings into a HashMap for fast lookup
pub fn load_process_mappings() -> HashMap<String, ProcessCategory> {
    let mut mappings = HashMap::new();

    add_browser_mappings(&mut mappings);
    add_office_mappings(&mut mappings);
    add_explorer_mappings(&mut mappings);
    add_terminal_mappings(&mut mappings);
    add_archive_mappings(&mut mappings);
    add_document_mappings(&mut mappings);
    add_media_mappings(&mut mappings);
    add_image_mappings(&mut mappings);
    add_communication_mappings(&mut mappings);
    add_download_mappings(&mut mappings);
    add_game_mappings(&mut mappings);
    add_system_mappings(&mut mappings);

    mappings
}

/// Add browser process mappings (30+ browsers)
fn add_browser_mappings(mappings: &mut HashMap<String, ProcessCategory>) {
    let browsers = vec![
        // Chromium-based browsers
        "chrome.exe",
        "msedge.exe",
        "brave.exe",
        "opera.exe",
        "vivaldi.exe",
        "chromium.exe",
        "yandex.exe",
        "360chrome.exe",
      "360se.exe",
        "sogouexplorer.exe",
        "liebao.exe",
     "maxthon.exe",
        "qqbrowser.exe",
        "ucbrowser.exe",
        "coccoc.exe",
        "cent.exe",
        "slimjet.exe",
     "torch.exe",
        "comodo_dragon.exe",
        "epic.exe",

        // Firefox-based browsers
        "firefox.exe",
        "waterfox.exe",
        "palemoon.exe",
        "seamonkey.exe",
        "icecat.exe",
        "librewolf.exe",

        // Safari and WebKit
     "safari.exe",

        // Other browsers
        "iexplore.exe",
        "microsoftedge.exe",
        "microsoftedgecp.exe",
      "tor.exe",
    "falkon.exe",
        "midori.exe",
        "qutebrowser.exe",
    ];

    for browser in browsers {
        mappings.insert(browser.to_lowercase(), ProcessCategory::Browser);
    }
}
/// Add office application mappings
fn add_office_mappings(mappings: &mut HashMap<String, ProcessCategory>) {
    let office_apps = vec![
      "winword.exe",      // Microsoft Word
        "excel.exe",        // Microsoft Excel
        "powerpnt.exe",     // Microsoft PowerPoint
        "onenote.exe",      // Microsoft OneNote
        "outlook.exe",      // Microsoft Outlook
        "msaccess.exe",     // Microsoft Access
    ];

    for app in office_apps {
      mappings.insert(app.to_lowercase(), ProcessCategory::Office);
    }
}

/// Add file explorer mappings
fn add_explorer_mappings(mappings: &mut HashMap<String, ProcessCategory>) {
    let explorers = vec![
        "explorer.exe",     // Windows Explorer
    ];

    for explorer in explorers {
        mappings.insert(explorer.to_lowercase(), ProcessCategory::Explorer);
    }
}

/// Add terminal/console application mappings
fn add_terminal_mappings(mappings: &mut HashMap<String, ProcessCategory>) {
    let terminals = vec![
        "cmd.exe",
        "powershell.exe",
        "pwsh.exe",
        "windowsterminal.exe",
        "wt.exe",
        "conhost.exe",
        "bash.exe",
        "wsl.exe",
        "ubuntu.exe",
        "debian.exe",
        "kali.exe",
      "opensuse.exe",
      "alacritty.exe",
        "hyper.exe",
        "terminus.exe",
        "cmder.exe",
        "consolez.exe",
        "mintty.exe",
    ];

    for terminal in terminals {
        mappings.insert(terminal.to_lowercase(), ProcessCategory::Terminal);
    }
}

/// Add archive/compression tool mappings
fn add_archive_mappings(mappings: &mut HashMap<String, ProcessCategory>) {
    let archive_tools = vec![
        "7zfm.exe",         // 7-Zip
        "7zg.exe",          // 7-Zip GUI
    "winrar.exe",       // WinRAR
        "winzip.exe",       // WinZip
        "peazip.exe",       // PeaZip
        "bandizip.exe",     // Bandizip
        "haozip.exe",       // HaoZip
        "360zip.exe",       // 360 Zip
      "2345zip.exe",      // 2345 Zip
     "kuaizip.exe",      // KuaiZip
        "winmount.exe",     // WinMount
        "poweriso.exe",     // PowerISO
        "ultraiso.exe",     // UltraISO
        "daemon.exe",       // DAEMON Tools
        "alcohol.exe",      // Alcohol 120%
        "imgburn.exe",      // ImgBurn
        "nero.exe",         // Nero
      "ashampoo.exe",     // Ashampoo
    ];

    for tool in archive_tools {
        mappings.insert(tool.to_lowercase(), ProcessCategory::Archive);
    }
}

/// Add document viewer/editor mappings (100+ apps)
fn add_document_mappings(mappings: &mut HashMap<String, ProcessCategory>) {
    let document_apps = vec![
        // PDF readers
        "acrord32.exe",     // Adobe Acrobat Reader
      "acrobat.exe",      // Adobe Acrobat Pro
        "foxitreader.exe",  // Foxit Reader
        "foxitpdfeditor.exe", // Foxit PDF Editor
        "sumatra.exe",      // Sumatra PDF
        "sumatrapdf.exe",
        "pdfxcview.exe",    // PDF-XChange Viewer
        "pdfxedit.exe",     // PDF-XChange Editor
      "nitro.exe",        // Nitro PDF
        "pdfelement.exe",   // Wondershare PDFelement
        "pdfill.exe",       // PDFill
        "gaaiho.exe",       // Gaaiho PDF
        "nuance.exe",       // Nuance Power PDF

        // Text editors
        "notepad.exe",      // Windows Notepad
        "notepad++.exe",    // Notepad++
        "sublime_text.exe", // Sublime Text
        "code.exe",         // VS Code
        "atom.exe",         // Atom
        "brackets.exe",     // Brackets
        "vim.exe",       // Vim
        "gvim.exe",         // GVim
        "emacs.exe",        // Emacs
        "notepad2.exe",     // Notepad2
        "notepad3.exe",     // Notepad3
        "editplus.exe",     // EditPlus
        "ultraedit.exe",    // UltraEdit
        "emeditor.exe",     // EmEditor
        "textpad.exe",      // TextPad
    "pspad.exe",        // PSPad
        "scite.exe",        // SciTE
        "geany.exe",        // Geany
        "kate.exe",      // Kate
        "kwrite.exe",       // KWrite
    "gedit.exe",        // gedit
        "mousepad.exe",     // Mousepad
        "leafpad.exe",      // Leafpad

        // Word processors
        "writer.exe",       // LibreOffice Writer
        "swriter.exe",      // OpenOffice Writer
        "abiword.exe",      // AbiWord
        "wordpad.exe",      // Windows WordPad
        "wps.exe",          // WPS Writer
      "et.exe",           // WPS Spreadsheets
        "wpp.exe",          // WPS Presentation

      // Note-taking apps
        "notion.exe",       // Notion
        "evernote.exe",     // Evernote
        "simplenote.exe",   // Simplenote
        "joplin.exe",       // Joplin
        "obsidian.exe",     // Obsidian
        "typora.exe",       // Typora
        "marktext.exe",     // MarkText
        "vnote.exe",        // VNote
        "cherrytree.exe",   // CherryTree
        "zim.exe",          // Zim
        "tiddlywiki.exe",   // TiddlyWiki
    "zettlr.exe",       // Zettlr
        "logseq.exe",       // Logseq
        "remnote.exe",      // RemNote
        "roam.exe",         // Roam Research

      // E-book readers
        "calibre.exe",      // Calibre
        "calibre-ebook-viewer.exe",
        "kindle.exe",       // Kindle
        "kobo.exe",      // Kobo
        "nook.exe",       // Nook
        "fbreader.exe",     // FBReader
        "coolreader.exe",   // Cool Reader
        "icecream.exe",     // Icecream Ebook Reader
        "bookviser.exe",    // Bookviser

        // Markdown editors
        "markdownpad.exe",  // MarkdownPad
        "haroopad.exe",     // Haroopad
        "ghostwriter.exe",  // ghostwriter
        "remarkable.exe",   // Remarkable
        "abricotine.exe",   // Abricotine

        // LaTeX editors
        "texstudio.exe",    // TeXstudio
        "texmaker.exe",     // Texmaker
        "texworks.exe",     // TeXworks
        "lyx.exe",          // LyX
        "kile.exe",         // Kile
        "winedt.exe",       // WinEdt
        "latexeditor.exe",  // LaTeX Editor

        // Other document tools
        "djview.exe",       // DjView (DjVu viewer)
        "windjview.exe",    // WinDjView
        "xps.exe",          // XPS Viewer
        "xpsviewer.exe",
        "write.exe",      // Windows Write
        "wordview.exe",     // Word Viewer
        "xlview.exe",       // Excel Viewer
        "ppview.exe",       // PowerPoint Viewer
    ];

    for app in document_apps {
        mappings.insert(app.to_lowercase(), ProcessCategory::Document);
    }
}

/// Add media player mappings (40+ apps)
fn add_media_mappings(mappings: &mut HashMap<String, ProcessCategory>) {
    let media_apps = vec![
        // Video players
        "vlc.exe",          // VLC Media Player
        "mpc-hc.exe",       // Media Player Classic
        "mpc-hc64.exe",
        "mpc-be.exe",    // MPC-BE
      "mpc-be64.exe",
        "potplayer.exe",    // PotPlayer
        "potplayermini.exe",
        "potplayermini64.exe",
        "kmplayer.exe",     // KMPlayer
        "kmplayer64.exe",
        "gomplayer.exe",    // GOM Player
        "smplayer.exe",     // SMPlayer
        "mpv.exe",          // mpv
        "wmplayer.exe",     // Windows Media Player
        "wmp.exe",
        "quicktime.exe",    // QuickTime
        "realplay.exe",     // RealPlayer
        "bsplayer.exe",     // BS.Player
        "divx.exe",         // DivX Player
        "splash.exe",       // Splash
        "zoom.exe",         // Zoom Player
        "daum.exe",      // Daum PotPlayer
     "5kplayer.exe",     // 5KPlayer
        "ace.exe",          // ACE Player

        // Audio players
        "foobar2000.exe",   // foobar2000
        "winamp.exe",       // Winamp
     "aimp.exe",         // AIMP
        "musicbee.exe",     // MusicBee
      "mediamonkey.exe",  // MediaMonkey
        "clementine.exe",   // Clementine
        "audacious.exe",    // Audacious
        "deadbeef.exe",     // DeaDBeeF
        "qmmp.exe",         // QMMP
     "xmplay.exe",       // XMPlay
        "itunes.exe",    // iTunes
        "spotify.exe",      // Spotify
        "qqmusic.exe",      // QQ Music
        "netease.exe",      // NetEase Cloud Music
        "kugou.exe",        // Kugou Music
        "kuwo.exe",      // Kuwo Music
        "xiami.exe",        // Xiami Music

        // Streaming
        "obs.exe",        // OBS Studio
        "obs64.exe",
        "streamlabs.exe",   // Streamlabs OBS
      "xsplit.exe",       // XSplit
    ];

    for app in media_apps {
        mappings.insert(app.to_lowercase(), ProcessCategory::Media);
    }
}

/// Add image viewer/editor mappings (40+ apps)
fn add_image_mappings(mappings: &mut HashMap<String, ProcessCategory>) {
    let image_apps = vec![
        // Image viewers
        "irfanview.exe",    // IrfanView
        "irfanview64.exe",
        "xnview.exe",       // XnView
        "xnviewmp.exe",     // XnView MP
        "faststone.exe",    // FastStone Image Viewer
        "honeyview.exe",    // Honeyview
      "imageglass.exe",   // ImageGlass
        "jpegview.exe",     // JPEGView
        "nomacs.exe",       // nomacs
        "picasa.exe",       // Picasa
        "acdsee.exe",       // ACDSee
        "photoviewer.exe",  // Windows Photo Viewer
      "photos.exe",       // Windows Photos

        // Image editors
        "photoshop.exe",    // Adobe Photoshop
        "gimp.exe",         // GIMP
        "gimp-2.10.exe",
        "krita.exe",        // Krita
        "paint.net.exe",    // Paint.NET
        "paintdotnet.exe",
        "mspaint.exe",      // MS Paint
      "paint.exe",
      "inkscape.exe",     // Inkscape
        "illustrator.exe",  // Adobe Illustrator
        "coreldraw.exe",    // CorelDRAW
        "affinity.exe",     // Affinity Photo
        "pixlr.exe",        // Pixlr
        "photoscape.exe",   // PhotoScape
        "lightroom.exe",    // Adobe Lightroom
        "darktable.exe",    // darktable
        "rawtherapee.exe",  // RawTherapee
        "capture.exe",      // Capture One

        // Screenshot tools
        "snagit.exe",    // Snagit
        "snagit32.exe",
        "greenshot.exe",    // Greenshot
        "sharex.exe",       // ShareX
        "lightshot.exe",    // Lightshot
        "picpick.exe",      // PicPick
        "screenpresso.exe", // Screenpresso
        "snippingtool.exe", // Snipping Tool
      "snip.exe",         // Snip & Sketch
    ];

    for app in image_apps {
        mappings.insert(app.to_lowercase(), ProcessCategory::Image);
    }
}

/// Add communication app mappings (40+ apps)
fn add_communication_mappings(mappings: &mut HashMap<String, ProcessCategory>) {
    let communication_apps = vec![
        // Instant messaging
        "wechat.exe",       // WeChat
        "weixin.exe",
        "qq.exe",         // QQ
        "tim.exe",          // TIM
        "skype.exe",        // Skype
        "telegram.exe",     // Telegram
      "discord.exe",   // Discord
        "slack.exe",        // Slack
        "teams.exe",        // Microsoft Teams
        "zoom.exe",      // Zoom
        "whatsapp.exe",     // WhatsApp
        "viber.exe",        // Viber
        "line.exe",         // LINE
        "kakaotalk.exe",    // KakaoTalk
        "signal.exe",       // Signal
        "element.exe",      // Element
        "riot.exe",         // Riot
        "matrix.exe",       // Matrix
        "icq.exe",          // ICQ
        "jabber.exe",       // Jabber
        "pidgin.exe",     // Pidgin
        "miranda.exe",      // Miranda
        "trillian.exe",     // Trillian

        // Video conferencing
      "zoomus.exe",       // Zoom
        "webex.exe",        // Webex
        "gotomeeting.exe",  // GoToMeeting
        "bluejeans.exe",    // BlueJeans
        "meet.exe",         // Google Meet
        "hangouts.exe",     // Google Hangouts

        // Email clients
        "thunderbird.exe",  // Thunderbird
        "mailbird.exe",     // Mailbird
        "emclient.exe",     // eM Client
        "postbox.exe",      // Postbox
        "thebat.exe",       // The Bat!
        "foxmail.exe",      // Foxmail
        "mailmaster.exe",   // NetEase Mail Master
        "outlook.exe",      // Outlook (already in Office, but also communication)

        // IRC clients
     "mirc.exe",         // mIRC
        "hexchat.exe",      // HexChat
        "irssi.exe",        // Irssi
    ];

    for app in communication_apps {
        mappings.insert(app.to_lowercase(), ProcessCategory::Communication);
    }
}

/// Add download manager mappings (40+ apps)
fn add_download_mappings(mappings: &mut HashMap<String, ProcessCategory>) {
    let download_apps = vec![
        // Download managers
        "idm.exe",          // Internet Download Manager
        "idman.exe",
     "fdm.exe",          // Free Download Manager
        "eagleget.exe",     // EagleGet
     "jdownloader.exe",  // JDownloader
        "jdownloader2.exe",
        "flashget.exe",     // FlashGet
        "getright.exe",     // GetRight
        "downloadmaster.exe", // Download Master
        "orbit.exe",        // Orbit Downloader
      "xunlei.exe",       // Xunlei (Thunder)
        "thunder.exe",
        "bittorrent.exe",   // BitTorrent
        "utorrent.exe",     // uTorrent
        "qbittorrent.exe",  // qBittorrent
        "transmission.exe", // Transmission
        "deluge.exe",       // Deluge
        "vuze.exe",         // Vuze
        "bitcomet.exe",     // BitComet
        "bitcometlite.exe",
        "aria2c.exe",       // aria2
        "wget.exe",         // wget
        "curl.exe",         // curl
        "axel.exe",         // Axel
        "persepolis.exe",   // Persepolis
        "motrix.exe",       // Motrix
        "xtreme.exe",       // Xtreme Download Manager
        "xdm.exe",
        "neat.exe",         // Neat Download Manager
        "ndm.exe",

        // FTP clients
        "filezilla.exe",    // FileZilla
        "winscp.exe",       // WinSCP
        "flashfxp.exe",     // FlashFXP
      "cuteftp.exe",      // CuteFTP
        "smartftp.exe",     // SmartFTP
     "coreftp.exe",      // Core FTP
        "cyberduck.exe",    // Cyberduck
        "transmit.exe",     // Transmit
        "leapftp.exe",      // LeapFTP
        "ftprush.exe",      // FTP Rush
    ];

    for app in download_apps {
        mappings.insert(app.to_lowercase(), ProcessCategory::Download);
    }
}

/// Add game platform mappings (40+ apps)
fn add_game_mappings(mappings: &mut HashMap<String, ProcessCategory>) {
    let game_apps = vec![
        // Game platforms
    "steam.exe",        // Steam
        "steamwebhelper.exe",
        "epicgameslauncher.exe", // Epic Games
        "epicgameslauncher-win64-shipping.exe",
        "origin.exe",       // Origin
        "originthinsetupinternal.exe",
        "uplay.exe",        // Uplay
     "upc.exe",          // Ubisoft Connect
        "battle.net.exe",   // Battle.net
        "battlenet.exe",
        "gog.exe",      // GOG Galaxy
        "galaxyclient.exe",
     "wegame.exe",       // WeGame
        "wegameplatform.exe",
        "tgp.exe",          // Tencent Game Platform
        "tgpassist.exe",

        // Game launchers
        "minecraft.exe",    // Minecraft
        "minecraftlauncher.exe",
        "roblox.exe",       // Roblox
        "robloxplayerlauncher.exe",
        "leagueoflegends.exe", // League of Legends
        "lol.exe",
        "dota2.exe",        // Dota 2
        "csgo.exe",         // CS:GO
        "valorant.exe",     // Valorant
        "overwatch.exe",    // Overwatch
        "fortnite.exe",     // Fortnite
        "fortniteclient-win64-shipping.exe",
        "pubg.exe",         // PUBG
        "tslgame.exe",
        "apex.exe",      // Apex Legends
        "r5apex.exe",
        "gta5.exe",         // GTA V
        "gtav.exe",
        "rdr2.exe",         // Red Dead Redemption 2
        "cyberpunk2077.exe", // Cyberpunk 2077
        "witcher3.exe",     // The Witcher 3
        "skyrim.exe",       // Skyrim
        "fallout4.exe",     // Fallout 4
        "minecraft.windows.exe", // Minecraft Windows 10
    ];

    for app in game_apps {
        mappings.insert(app.to_lowercase(), ProcessCategory::Game);
    }
}

/// Add system utility mappings (50+ apps)
fn add_system_mappings(mappings: &mut HashMap<String, ProcessCategory>) {
    let system_apps = vec![
        // System utilities
        "taskmgr.exe",      // Task Manager
        "perfmon.exe",      // Performance Monitor
        "resmon.exe",       // Resource Monitor
        "msconfig.exe",     // System Configuration
        "regedit.exe",      // Registry Editor
        "services.exe",     // Services
        "eventvwr.exe",     // Event Viewer
        "compmgmt.exe",     // Computer Management
        "devmgmt.exe",      // Device Manager
        "diskmgmt.exe",     // Disk Management
    "cleanmgr.exe",     // Disk Cleanup
        "defrag.exe",       // Disk Defragmenter
        "chkdsk.exe",       // Check Disk
        "sfc.exe",      // System File Checker
        "dism.exe",         // DISM

        // Antivirus/Security
        "msmpeng.exe",      // Windows Defender
        "msseces.exe",
        "securityhealthsystray.exe",
        "avast.exe",        // Avast
        "avgui.exe",        // AVG
        "avp.exe",          // Kaspersky
        "kavstart.exe",
        "360tray.exe",      // 360 Total Security
        "360sd.exe",
        "qqpcrtp.exe",      // Tencent PC Manager
        "qqpcmgr.exe",
        "bdagent.exe",      // Bitdefender
        "mcshield.exe",     // McAfee
        "nortonsecurity.exe", // Norton
     "mbam.exe",         // Malwarebytes

      // System tools
        "ccleaner.exe",     // CCleaner
        "ccleaner64.exe",
        "glary.exe",        // Glary Utilities
        "advancedsystemcare.exe", // Advanced SystemCare
        "wisecleaner.exe",  // Wise Care 365
        "driverbooster.exe", // Driver Booster
        "drivergenius.exe", // Driver Genius
        "drivereasy.exe",   // Driver Easy
        "crystaldiskinfo.exe", // CrystalDiskInfo
        "crystaldiskmark.exe", // CrystalDiskMark
        "hwinfo.exe",       // HWiNFO
      "hwinfo64.exe",
      "cpuz.exe",       // CPU-Z
        "gpuz.exe",       // GPU-Z
      "speccy.exe",       // Speccy
        "aida64.exe",       // AIDA64
        "everest.exe",      // EVEREST

        // Backup/Recovery
        "acronis.exe",      // Acronis True Image
        "easeus.exe",       // EaseUS Todo Backup
        "macrium.exe",      // Macrium Reflect
        "veeam.exe",        // Veeam
    ];

    for app in system_apps {
        mappings.insert(app.to_lowercase(), ProcessCategory::System);
    }
}


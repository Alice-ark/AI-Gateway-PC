# AI Gateway PC · 安装与使用（Windows）

Android-AI-gateway 的桌面移植版：把 Trae / Loomy / 小浣熊 / WorkBuddy(CodeBuddy) / Antigravity / ChatGPT
的自有账号聚合成一个本地 OpenAI 兼容端点，并附带本地网页管理台。

## 环境要求

- Windows 10/11
- JDK 17 及以上（如 `winget install Microsoft.OpenJDK.21`）
- 无需 Android SDK / 模拟器

## 构建

```bat
gradlew.bat :cli:installDist   :: 产出免安装运行目录
gradlew.bat :cli:distZip       :: 产出 cli/build/distributions/aigw.zip（分发用）
```

## 运行

```bat
cd cli\build\install\aigw\bin
aigw.bat serve --port 8790 --open
```

- 网关端点：`http://127.0.0.1:8790/v1`（OpenAI / Anthropic 兼容）
- 管理台：`http://127.0.0.1:8791/`（仅本机，`--admin-port` 可改端口）

常用命令：

| 命令 | 说明 |
|---|---|
| `aigw providers` | 列出供应商 |
| `aigw browser-login --provider codebuddy` | 浏览器设备码登录（WorkBuddy 等） |
| `aigw sms --provider loomy --phone 138xxx` | 短信验证码登录（Loomy） |
| `aigw transfer import --file 账号.json` | 导入手机 App 导出的账号信封 |
| `aigw accounts` / `credits` / `models` / `usage` | 状态查询 |

## 管理台功能（与手机 App 对齐）

- **首页**：服务启停、本机/局域网地址复制、实时调度拓扑、批量签到、一键刷新额度、今日用量
- **供应商**：卡片列表 + 四类登录向导（设备码 / 浏览器回调（含粘贴兜底）/ OAuth / 短信）
- **模型**：按供应商分组、开放名单开关
- **记录**：调用日志筛选（成功/失败/中断）
- **我的**：接入设置、账号信封导入导出（与手机 App 互通）、开机自启动开关

## 桌面快捷方式（推荐，零脚本）

桌面入口直接指向 `javaw.exe`（JDK 的 GUI 子系统进程），**不经过任何 .bat/.vbs/.ps1 脚本**：
无控制台窗口、无黑框闪烁，也不会被杀毒软件的脚本启发式误报。参数里的 `--open` 让网关
起来后自动打开管理台；重复双击会命中单实例保护（只打开管理台，不会起第二个进程）。

创建方式（PowerShell，路径按实际安装目录改）：

```powershell
$root = "D:\Workspace\AI\Code\Projects\AI-Gateway-PC\cli\build\install\aigw"
$javaw = (Get-Command javaw.exe).Source
$ws = New-Object -ComObject WScript.Shell
$lnk = $ws.CreateShortcut("$([Environment]::GetFolderPath('Desktop'))\AI Gateway PC.lnk")
$lnk.TargetPath = $javaw
$lnk.Arguments = '-cp lib\* dev.aigw.cli.MainKt serve --port 8790 --open'
$lnk.WorkingDirectory = $root
$lnk.IconLocation = "$root\aigw-icon.ico,0"
$lnk.Save()
```

`cli/src/main/dist/` 是 Gradle application 插件的发行内容目录：`installDist` / `distZip`
会自动把图标、`aigw-tray.bat`、`launcher.ps1`（手动排障备用）打进安装目录和 zip 分发包，
重建不会丢失。日常使用 = 双击「AI Gateway PC」→ 托盘出现绿色网关图标 + 管理台自动弹出。

## 开机自启动（可选，默认关闭）

管理台 → 我的 → 「保活与自启动」开关：把**桌面快捷方式的副本**（`.lnk`，纯快捷方式文件，
不含任何脚本）复制到启动文件夹；关闭即删除。不写注册表、不生成 VBS，随时可见可手删。

- 开启后每次登录**静默**启动网关：启动文件夹里的 `AI-Gateway-PC.lnk` 参数不带
  `--open`，只起后台进程 + 托盘图标，**不打开管理台页面**（只有桌面快捷方式带 `--open`）；
- 不想要自启动：保持开关关闭即可，开机不占用任何进程。

## 数据与安全

- 账号凭据明文存于 `~/.aigw`（PC 端无加密存储），注意目录权限；
- 账号信封导出内容含明文凭证，勿外传；
- `exposeLan`（暴露局域网）默认关闭；开启后建议同时设置 API Key；
- 本项目仅供学习交流，详见上游 LICENSE（MIT + 附加限制）。

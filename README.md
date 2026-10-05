# Comfy工作台（Comfy Workbench）

> 导入一条工作流，手机上点一下出图。不连线，不改节点。

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE) [![Android CI](https://github.com/Mimikkoro1/comfy-workbench-android/actions/workflows/android-ci.yml/badge.svg)](https://github.com/Mimikkoro1/comfy-workbench-android/actions/workflows/android-ci.yml)

**Comfy工作台** 是一个原生 Android（Kotlin + Jetpack Compose）应用。电脑上把 ComfyUI 工作流导出成 API 格式，手机里导入，参数变成几个输入框：提示词、种子、尺寸、参考图。点一下就提交到你自己的 ComfyUI。

它不是手机上的节点编辑器，也不替代 ComfyUI 网页端。节点和连线留在电脑上，手机只负责出图。

人不在电脑前，也能用同一套已经调好的工作流出图、图生图、图生视频、反推提示词。

## 它做什么

- **直连你的 ComfyUI**：HTTP + WebSocket，实时进度，没有云端中转。提示词、参数、图片只经过手机和你自己的机器
- **工作流变操作面板**：导入 API 格式 JSON，把提示词、尺寸、步数、种子、模型、参考图映射成手机控件。提示词识别 CLIPTextEncode 和 Qwen 等 TextEncode 节点；内置 11 个尺寸预设（含 9:16 竖屏）。示例在 `docs/example-workflows/`
- **提示词库**：分类取词、随机抽卡、保存和编辑
- **画廊**：图片 / 视频统一浏览，可放大、可播放。结果只进 App 图库，**不自动写入系统相册**，要留的再手动保存或分享
- **后台跑完**：生成和下载走前台服务，锁屏或切走不会中断，通知栏能看到进度
- **远程也能用**：一个地址框，内网或外网都行。外网可配 HTTP Basic Auth（适合 Caddy / Nginx），遇到 401 会展开账号密码
- **队列**：查看队列，中断当前任务
- **界面**：Material 3，单手操作。中英双语，默认跟系统语言，设置里可改
- **更新检查**：每天最多问一次 GitHub，也可在「关于」里手动查。不自动下载，不收集数据

## 它不做什么

- 不能在手机上连线、改节点、搭新图。要改结构，回 ComfyUI 网页端改完再导出
- 只吃 **API 格式** JSON。网页端默认的 `nodes` / `links` 工作流要先用「Export (API)」导出
- 视频文件输入本版不支持。图生视频可以，输入是参考图，不是一条视频
- 面板控件来自工作流里的字面量参数。已经连到别的节点的输入不会再暴露出来
- 自定义尺寸会按 16 的倍数对齐（最小 64）。不是 16 的倍数会被改小

## 截图

| 生成 | 画廊 | 工作流/库 | 设置 |
| --- | --- | --- | --- |
| <img src="docs/screenshots/generate_zh.png" width="216"/> | <img src="docs/screenshots/gallery_zh.png" width="216"/> | <img src="docs/screenshots/library_zh.png" width="216"/> | <img src="docs/screenshots/settings_zh.png" width="216"/> |

## 快速开始

> Android 8.0 及以上。Release APK 为 arm64-v8a / armeabi-v7a / x86 / x86_64 通用包。

1. 准备一台跑着 ComfyUI 的机器（PC / 服务器均可）
2. 手机和它在同一网络，或按下面的方式远程打通
3. 从 Releases 安装 APK，填 ComfyUI 地址，例如 `http://192.168.1.100:8188`
4. 在「工作流/库」导入一条 API 格式工作流，开始生成

## 远程访问

默认面向局域网，通信是 HTTP 明文。人不在家时，选一种方式把家里的 ComfyUI 露给手机：

- **蒲公英组网**（中国大陆最省事）：手机和家里电脑加入同一网络，填家里电脑的虚拟 IP，如 `http://<虚拟 IP>:8188`
- **公网 IPv6 + DDNS**：域名指向家里电脑的 IPv6，再用 Caddy / Nginx 反代并开 Basic Auth（建议 HTTPS）。防火墙只放行反代端口，不要直接开放 8188。密码加密保存在手机本地
- **Tailscale**：填 Tailscale IP。海外稳，中国大陆可能不稳
- **frp / 花生壳等端口转发**：建议套 HTTPS，或给 ComfyUI 加访问保护

公网不要裸奔 8188。不可信网络不要走明文，走 VPN 或隧道。

## 自定义工作流

在 ComfyUI 网页端导出 **API 格式** JSON，在应用里导入。`docs/example-workflows/` 里有四条能直接对一下格式的例子：

- `kr2turbo_t2i.workflow.json` — 文生图（Krea 2 Turbo）
- `kr2turbo_i2i.workflow.json` — 图生图
- `wan22_i2v_4step.workflow.json` — 图生视频（Wan2.2，4 步）
- `florence2_caption.workflow.json` — Florence-2 图片反推提示词

示例里的模型名要换成你机器上实际有的文件。工作流引用的模型、节点包，都得在 ComfyUI 那台机器上先装好。

## 构建

要求：JDK 17、Android SDK（`local.properties` 指向 SDK 路径）。

```bash
# Linux / macOS
./gradlew assembleRelease

# Windows
gradlew.bat assembleRelease
```

单元测试：`./gradlew testDebugUnitTest`（纯 JVM，不需要手机或模拟器）。

签名：复制 `keystore.properties.example` 为 `keystore.properties` 并填入 keystore。未配置时使用 debug 签名。

## FAQ

**连不上服务器？** ComfyUI 要用 `--listen` 听 `0.0.0.0`。只听 `127.0.0.1` 时手机进不去。防火墙放行 8188。先用手机浏览器打开 `http://<ip>:8188` 看通不通。

**支持哪些模型？** 应用不绑模型。你导入的工作流用什么，它就跑什么。

**会把图片传到云端吗？** 不会。流量只在手机和你的 ComfyUI 之间。

**和节点编辑器有什么区别？** 没有节点编辑器。结构在电脑上定死，手机只填参数、出图。

## License

[GPL-3.0](LICENSE)。

- UI 视觉思路参考自 [rikkahub](https://github.com/rikkahub/rikkahub)（AGPL-3.0），实现为独立编写
- 启动器图标使用了 [Comfy-Org/ComfyUI_frontend](https://github.com/Comfy-Org/ComfyUI_frontend) 的 ComfyUI logomark
- ComfyUI 及 ComfyUI 标志为 Comfy Org 的商标。本项目独立开发，与 Comfy Org 无关

---

## English

**Comfy Workbench** is a native Android app for a workflow you already finished on your PC. Export it as API-format JSON, import it on the phone, and the parameters become a few fields: prompt, seed, size, reference image. Tap once. It submits to your own ComfyUI over HTTP + WebSocket. No cloud in between.

It is not a node editor, and it does not replace the ComfyUI web UI. Graphs stay on the computer. The phone only runs them.

- Prompts are detected on CLIPTextEncode and TextEncode-style nodes such as Qwen; 11 built-in size presets including 9:16
- API-format workflows only. The default `nodes` / `links` file must be exported with Export (API) first
- No video-file input in this version. Image-to-video is supported; the input is a reference image
- Linked inputs are not exposed. Only literal parameters become controls
- Custom sizes are aligned down to a multiple of 16 (minimum 64)
- Android 8.0+. The release APK is a universal build (arm64-v8a / armeabi-v7a / x86 / x86_64)
- Generation and downloads run in a foreground service, so they finish with the screen locked
- Gallery stays inside the app and is **not** auto-saved to the system album
- One address field for LAN or remote. HTTP Basic Auth behind Caddy / Nginx; the login fields expand on 401
- Chinese and English UI, following the system language unless you switch it
- Update check asks GitHub at most once a day, or manually from About. No auto-download, no data collected

Examples: `docs/example-workflows/` (Krea 2 Turbo t2i / i2i, Wan2.2 i2v, Florence-2 caption). Model files named in those workflows must exist on your ComfyUI machine.

Remote access: on mainland China, Oray Pgyer (蒲公英) is the simplest mesh. Public IPv6 + DDNS with a Caddy / Nginx reverse proxy and Basic Auth also works; open only the proxy port, never 8188 itself. Tailscale is fine outside China and can be unstable on the mainland. Plain HTTP on an untrusted network should go through a tunnel.

### Screenshots

| Generate | Gallery | Workflows / Library | Settings |
| --- | --- | --- | --- |
| <img src="docs/screenshots/generate_en.png" width="216"/> | <img src="docs/screenshots/gallery_en.png" width="216"/> | <img src="docs/screenshots/library_en.png" width="216"/> | <img src="docs/screenshots/settings_en.png" width="216"/> |

Build: JDK 17 + Android SDK, then `./gradlew assembleRelease`. Unit tests (plain JVM, no device): `./gradlew testDebugUnitTest`.

Licensed under **GPL-3.0**. Unofficial project, not affiliated with Comfy Org. ComfyUI and the ComfyUI logo are trademarks of Comfy Org.

## 关于

个人项目，按自己的工作流打磨。欢迎 issue 和 PR。

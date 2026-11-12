# GMCH IDE

面向 Java 开发的轻量桌面 IDE：打开 Gradle 工程、编辑源码、编译、运行、查看输出。

> 当前状态：**v0.1.0 开发中**（骨架已就绪，等待 JDK 21 环境完成构建验证）。

## 技术栈

| 层 | 选型 |
| --- | --- |
| 桌面壳 | Electron 39 + electron-vite 5 |
| UI | Vue 3.5 + Pinia |
| 编辑器 | CodeMirror 6（Java 语法高亮） |
| 后端 | Spring Boot 3.5（Java sidecar） |
| 构建执行 | 工作区自带 Gradle Wrapper |
| 打包 | electron-builder（NSIS / DMG / AppImage / deb） |

## 目录

```
docs/        需求、技术方案、工程规范、发布验证、路线图
src/main     Electron 主进程（窗口、菜单、sidecar 管理）
src/preload  能力白名单（window.api）
src/renderer Vue 3 渲染进程（文件列表、编辑器、输出面板）
backend      Spring Boot sidecar（文件 API、Gradle 执行）
scripts      环境自检、后端构建、发布校验
```

详细说明见 [docs/01-技术方案.md](docs/01-技术方案.md)。

## 环境要求

- Node.js ≥ 22
- JDK 21（最低 17），并设置 `JAVA_HOME`
- 工作区需自带 `gradlew` / `gradlew.bat`

自检：

```bash
npm run doctor
```

## 快速开始

```bash
# 1. 安装前端依赖
npm install

# 2. 环境自检（Node / JDK 21 / Gradle Wrapper）
npm run doctor

# 3. 构建 Java sidecar（bootJar + jlink 运行时）
npm run backend:build

# 4. 开发模式
npm run dev
```

开发模式不打包后端时会提示「后端未构建」，此时只能编辑、不能编译/运行。

冒烟用工作区：`examples/hello-java`（已带 Gradle Wrapper），打开它即可验证编译与运行。

## 验证状态（2026-11-12）

| 项 | 状态 |
| --- | --- |
| 类型检查 + 三段构建（`out/`） | ✅ |
| sidecar 构建（20MB jar + jlink 运行时） | ✅ |
| 健康检查 / 鉴权 / 文件 API / SSE 编译 | ✅ `npm run smoke:backend` |
| 新建工程（Gradle + Hello World） | ✅ 后端 API 实测；UI 对话框已渲染 |
| Windows 解包产物（`dist/win-unpacked`） | ✅ |
| Windows NSIS 安装包 | ✅ `dist/gmch-ide-0.1.0-setup.exe`（166 MB） |
| Windows 免安装包 | ✅ `dist/GMCH IDE-0.1.0-win.zip`（201 MB） |
| GUI 冒烟 S1–S10 | ⏳ 需普通桌面环境/CI |

完整记录见 [docs/03-发布验证.md](docs/03-发布验证.md) 第 2.6 节。

## 常用命令

| 命令 | 作用 |
| --- | --- |
| `npm run dev` | 开发模式（主进程热更新 + Vite HMR） |
| `npm run build` | 类型检查 + 构建到 `out/` |
| `npm run backend:build` | 构建 sidecar（`build/backend`） |
| `npm run smoke:backend` | 后端自动化冒烟（健康、鉴权、SSE 编译） |
| `npm run build:win` / `build:mac` / `build:linux` | 构建对应平台安装包到 `dist/` |
| `npm run verify:release` | 校验产物与版本 |
| `npm run doctor` | 环境自检 |
| `npm run format` | Prettier 格式化 |

## 文档

| 文档 | 内容 |
| --- | --- |
| [00-产品需求](docs/00-产品需求.md) | v0.1.0 范围、FR/NFR、验收标准、里程碑 |
| [01-技术方案](docs/01-技术方案.md) | 架构、进程模型、ADR、安全基线、已知限制 |
| [02-工程规范](docs/02-工程规范.md) | 目录、命名、分支、质量门槛 |
| [03-发布验证](docs/03-发布验证.md) | 构建、冒烟 S1–S10、安装/卸载、签名状态 |
| [04-路线图](docs/04-路线图.md) | v0.1 → v1.0 规划与阻塞项 |

## 故障排查（Windows）

### 双击没反应 / 安装器提示「GMCH IDE 无法关闭」/ 安装后没有卸载项

先检查目录的**完整性级别**（本项目在 DSH 沙箱里开发时，工作区被标过 Low IL，导致 Chromium 拒绝启动、安装器无法写 HKCU）：

```powershell
icacls "G:\code\gmch_ide" | findstr Mandatory
```

如果输出包含 `Low Mandatory Level`，修复：

```powershell
icacls "G:\code\gmch_ide" /setintegritylevel (OI)(CI)M /T
```

对解压目录同样处理（把路径换成实际解压目录），或者直接把应用放到 `C:\Users\<你>\Programs\` 这类默认 Medium 的目录。修复后：

- `GMCH IDE.exe` 双击可正常启动；
- 安装器不会再误报「无法关闭」，卸载项与开始菜单快捷方式也会正常写入。

### 安装时提示「GMCH IDE 无法关闭」

这是 electron-builder 安装器的进程/文件占用检查，不是安装包损坏：

```powershell
tasklist | findstr /i gmch
taskkill /F /IM "GMCH IDE.exe" /T
```

先结束残留进程再点「重试」；仍失败就点「取消」，改用免安装包 `GMCH IDE-0.1.0-win.zip`。

### 双击 GMCH IDE.exe 没有反应

解压后先运行 `GMCH IDE 诊断启动.cmd`（会带 `--no-sandbox` 并输出退出码），然后查看：

- `%APPDATA%\gmch-ide\startup.log`：应用启动日志（单实例锁、窗口、渲染进程、后端状态）；
- `%APPDATA%\gmch-ide\chromium.log`：Chromium 日志。

常见结论：

- 日志停在 `singleInstanceLock=false`：已有隐藏实例，结束 `GMCH IDE.exe` 后重试；
- 日志停在 `boot` 之前、Chromium 日志为空：优先检查上面的完整性标签，其次是安全软件拦截；
- 日志中有 `uncaught`：按堆栈定位，属于应用代码问题。

## 许可

内部项目，未授权不得外部分发。

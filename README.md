# UidHide

**补上 HMA-OSS 管不到的那一层：UID → 包名。**

HMA-OSS（Hide My Applist 的开源分支）隐藏的是「某个应用能看到哪些包」；但系统里还有一条查询路径——给定一个 UID，反查它属于哪个包。检测器拿到目标应用的 UID 后，用这条路径就能确认「这个 UID 对应的是哪个包」，即使包列表里看不到它。

> 只适用于 **HMA-OSS**。原版 HMA 的隐藏机制与它不同，本模块读取的配置路径和判定逻辑都只针对 HMA-OSS，装在原版 HMA 上不会生效。

UidHide 是一个 LSPosed 模块，在 `system_server` 里按 HMA-OSS 的隐藏关系改写这条反查结果：被 HMA-OSS 对调用方隐藏的包，其 UID 反查返回 `null`。

## 它不做什么

刻意**不** hook `getPackageInfo`、`getApplicationInfo`、`shouldFilterApplication` 和已安装列表。那些属于 HMA-OSS 的职责；两个模块同时过滤会让结果互相矛盾，检测器反而能看出列表不一致。

隐藏集合完全来自 HMA-OSS 自己的配置，本模块不额外发明名单。

## 要求

- Android 13+（`minSdk 33`）
- LSPosed（Zygisk 或 Riru 均可）
- HMA-OSS，且已配置好隐藏关系（本模块读取它的 `config.json`）

## 安装

1. 安装 `uidhide-*.apk`
2. 在 LSPosed 里启用 **UidHide**
3. 作用域只勾 **系统框架**（`android`）
4. 重启

## 验证

```bash
# 目标 UID 由 HMA-OSS 对调用方隐藏时，反查结果应为 null
su <caller_uid> -c 'service call package 20 i32 <target_uid>'
```

模块日志标签为 `UidHide`：

```bash
su -c 'logcat -s UidHide'
```

## 构建

需要 Android SDK（build-tools + platform）和 JDK：

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
tools/build-apk.sh
```

脚本在 Windows（Git Bash / MSYS2）、Linux 和 macOS 上都能运行，会自动选择最新的 build-tools 和 platform。签名密钥从 `~/.android/uidhide-release.properties` 读取（可用 `RELEASE_PROPS` 指定别的路径），该文件不入库。

## 已知限制

- 只处理 UID → 包名这一条路径；包可见性、`/proc`、cgroup 等仍由 HMA-OSS 和别的模块负责
- 依赖 HMA-OSS 的配置文件路径与格式；HMA-OSS 改结构后需要同步修改
- 不同 Android 版本的 `system_server` 类名不同，候选类全部找不到时只在日志里提示，不会崩溃
- 只对「调用方是普通应用」的查询生效；`system_server` 内部调用不受影响

## 作者

Blazkness · https://github.com/SampleBlazkness

# FCMFix ColorOS

这是一个需要 **Root + LSPosed** 的系统级修复模块，主要用于解决一加 15 国行版
ColorOS 16 阻止 Google FCM 唤醒后台、无进程或已停止应用的问题。

它不是推送服务器、代理软件或常驻保活程序，也不能让本身不支持 FCM 的应用获得
FCM。它的作用是在 Google Play 服务已经收到消息后，修复消息从 GMS 传递到目标应用
时被 ColorOS 拦截的问题。

## 它解决的是什么问题

正常的 FCM 推送链路如下：

```text
应用服务器 → Google FCM → Google Play 服务（GMS）→ FCM 广播 → 目标应用 → 系统通知
```

在一加 15 国行 ColorOS 16 上，这条链路可能在多个位置被限制：

- ColorOS 电池组件可能把 GMS、Play 商店等 Google 核心包设置为禁止全部联网；
- GMS 发出 FCM 广播时，系统可能拒绝启动没有进程或处于 stopped 状态的应用；
- 国行版的应用分类、自启动、GCM bind、Hans 冻结和代理唤醒策略可能继续拦截；
- 即使 FCM 已经拉起目标应用，后台网络控制仍可能在数秒后重新冻结应用并关闭其
  socket，使应用不能及时拉取消息正文；
- 国行区域配置缺少国际版中的部分 GMS Doze 白名单条目。

典型表现包括：

- 应用打开时能收到通知，划掉后台或被系统清理后收不到；
- 日志出现 `Failed to broadcast to stopped app`；
- 必须手动打开应用，积压的消息才一起出现；
- GMS 已连接 FCM，但消息无法拉起目标应用。
- FCM Diagnostics 显示广播成功、进程也已启动，但通知延迟到亮屏或打开应用后才出现。

本模块就是针对这些系统限制进行修复。

## 实际做了什么

### 系统框架作用域

- 为目标应用的 FCM 广播补充 `FLAG_INCLUDE_STOPPED_PACKAGES`，允许广播到达已停止应用；
- 动态适配 Android 16 的广播方法参数，避免 ColorOS 复制 Intent 后丢失放行标记；
- 仅对模块允许列表中的目标应用绕过 ColorOS 应用分类、FCM 自启动、GCM bind 和
  service 启动限制；
- 在 FCM 到达时解除必要的 ColorOS/Hans 冻结和代理限制；
- 每次有效 FCM 到达后，仅为对应目标 UID 建立 20 秒投递窗口，暂时阻止 Hans 重新冻结
  以及 `OAppNetControlService` 关闭 socket；窗口结束后恢复系统原有省电策略；
- 为 GMS、GSF 和 Play 商店补充缺失的 Doze 条目，同时保留 ColorOS 原有白名单；
- 可选阻止系统在应用停止时自动删除它原有的通知。

### 电池作用域

ColorOS 的 `com.oplus.battery` 会在 Google 连通性探测失败时调用系统联网管理接口，
给 GMS、Play 商店或 ConfigUpdater 写入 `POLICY_REJECT_ALL`，即同时禁止 Wi-Fi 和
移动数据。

本模块只在“电池”进程中拦截这项针对 Google 核心包的自动限制，并改为
`POLICY_NONE`。从系统设置中由用户手动配置的应用联网规则不在这个进程中，因此不会
被模块强行覆盖。

## 它不能解决什么

- 目标应用本身不使用 FCM；
- 应用服务器没有发送消息，或账号、Token 已失效；
- Google Play 服务没有登录、被禁用或无法连接 Google 网络；
- 目标应用的系统通知权限或应用内部通知开关已关闭；
- VPN、代理、DNS 或网络环境本身无法连接 FCM；
- OTA 更新后 ColorOS 修改了关键类名或方法，导致现有 Hook 失配。

本模块不会安装 Google 服务，不会替代 GMS，也不会绕过应用自身的通知设置。

## 适用环境

已完成实机验证的环境：

- 设备：一加 15 国行版（PLK110）；
- 系统：ColorOS `16.0.10.500`，Android 16；
- LSPosed：Modern Xposed API 环境；
- 测试应用：Nekogram `tw.nekomimi.nekogram`。

其他 ColorOS 16 设备或后续 OTA 可能可以使用，但没有经过同等级验证。安装前应保留
可进入安全模式或禁用 LSPosed 模块的恢复手段。

## 安装和使用

1. 确认手机已经 Root，并安装可正常工作的 LSPosed。
2. 安装本仓库发布的 APK。
3. 在 LSPosed 中启用模块。
4. 一加 15 / ColorOS 16 只勾选以下两个作用域：
   - `系统框架`（`system`）；
   - `电池`（`com.oplus.battery`）。
5. 重启手机，使 system_server 和电池进程加载 Hook。
6. 打开 FCMFix，在应用列表中勾选需要由 FCM 唤醒的应用。
7. 确保目标应用本身的通知权限和通知频道已启用。

允许列表用于限制放行范围。不要无条件全选所有应用；只选择确实使用 FCM 且需要后台
推送的应用即可。修改允许列表通常会即时更新，异常时重启一次手机。

目标应用不需要为了本模块额外挂入系统“流量管理”白名单。模块只在一次 FCM 投递后的
短窗口内处理 ColorOS 自动触发的后台断网，不会永久放开应用后台联网；用户主动设置的
Wi-Fi、移动数据权限仍按系统设置执行。

当前版本使用独立包名 `com.fcmfix.coloros`。包名变更后不能直接覆盖
`com.kooritea.fcmfix.op15` 旧版；请先在 LSPosed 中停用并卸载旧版，安装新版后
重新勾选作用域、重启手机，并重新选择允许推送的应用。

新旧版可以同时安装，但 **不要在 LSPosed 中同时启用两个版本**，否则 Hook
可能重复执行。

## 是否还需要 GMS Magisk 模块

在已验证的 PLK110 `16.0.10.500` 上，不需要再启用
`coloros_gms_extreme_fix`：

- 该根模块保持禁用；
- ELSA 配置没有 bind mount；
- 仅启用本 APK 的 LSPosed 修复；
- GMS 的 5228 FCM 长连接保持正常；
- GMS、GSF、Play 商店和 ConfigUpdater 的联网策略均为 `POLICY_NONE`。

这只代表上述实测固件。后续 OTA 应重新检查 GMS 长连接、联网策略和强停推送。

## 实机验证结果

严格测试流程和结果如下：

1. 执行 `am force-stop tw.nekomimi.nekogram`；
2. 确认 Nekogram 为 `stopped=true` 且没有运行进程；
3. 从另一账号发送测试消息；
4. GMS 以 UID `10123` 发出 `com.google.android.c2dm.intent.RECEIVE`；
5. system_server 启动 Nekogram 的 `FirebaseInstanceIdReceiver`；
6. Nekogram 变为 `stopped=false`，并在约 0.57 秒后生成通知。

这证明当前版本不仅能处理普通划卡或后台进程被清理，也能在本机上恢复 Android
package stopped 状态下的 FCM 投递。

## 应用内选项

- **阻止应用停止时自动清除通知**：保留目标应用已有的通知；
- **允许唤醒被冰箱冻结的应用**：与 Ice Box 等冻结工具配合使用；
- **全选包含 FCM 的应用**：根据应用组件扫描结果批量加入，建议之后手动检查；
- **打开 FCM Diagnostics**：打开 GMS 自带诊断页面，检查 FCM 连接状态。

## 排查方法

如果仍然收不到推送，依次检查：

1. LSPosed 中是否只启用了一个 FCMFix；
2. 作用域是否同时包含“系统框架”和“电池”；
3. 修改作用域或更新 APK 后是否重启过手机；
4. 目标应用是否已经加入 FCMFix 允许列表；
5. GMS 的 FCM Diagnostics 是否显示连接正常；
6. 目标应用通知权限、通知频道和应用内部通知开关是否启用；
7. LSPosed 日志中是否存在 `hook error`；
8. 当前系统版本是否已经通过 OTA 更新。

如果日志已经显示 `Successful broadcast`，应用进程也被拉起，但通知仍延迟，检查新版
日志中是否出现 `Oplus FCM delivery window`、`Oplus FCM Hans-freeze bypass` 或
`Oplus FCM socket-close bypass`。这属于 ColorOS 在广播投递后的二次冻结/断网问题，
不是 GMS 长连接断开。

刚重启后 GMS 重新建立连接可能需要一点时间，测试时应先确认 FCM Diagnostics 已连接。

## 权限与隐私

- APK 自身不声明 `INTERNET` 权限，不通过开发者服务器中转任何消息；
- `QUERY_ALL_PACKAGES` 用于扫描本机哪些应用包含 FCM 接收组件，并显示允许列表；
- 配置通过 LSPosed 的远程配置接口提供给系统 Hook；
- 修复只针对允许列表中的目标应用，以及维持 FCM 所需的 Google 核心包。

## 下载与技术分析

- [下载最新版本](https://github.com/Artifical0/fcmfix-oneplus15-coloros16/releases/latest)
- [一加 15 ColorOS 16 国行/国际版差分与 Hook 分析](docs/oneplus15-coloros16-fcm-analysis.md)
- [酷安、公众号与技术论坛发布素材](docs/publishing-kit.md)
- 上游项目：[kooritea/fcmfix](https://github.com/kooritea/fcmfix)

每个版本的 APK SHA-256 见对应 GitHub Release 说明。

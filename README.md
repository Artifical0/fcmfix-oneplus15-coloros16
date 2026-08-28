# fcmfix (Android 10-16)

[![Android CI](https://github.com/kooritea/fcmfix/workflows/Android%20CI/badge.svg)](https://github.com/kooritea/fcmfix/actions)

让fcm/gcm唤醒未启动的应用进行发送通知  

### 一加 15 / ColorOS 16 适配

本仓库包含针对一加 15（Android 16 / ColorOS 16）的实机适配：

完整的国行/国际版固件差分、限制调用链和 Hook 映射见
[OnePlus 15 ColorOS 16 FCM 限制分析](docs/oneplus15-coloros16-fcm-analysis.md)。

- 在 `ActivityManagerService.broadcastIntentWithFeature` 前置入口补充
  `FLAG_INCLUDE_STOPPED_PACKAGES`，避免 ColorOS 16 在后续广播处理前复制或过滤 Intent，
  导致原有 `broadcastIntentLocked` 钩子无法唤醒已停止应用。
- 动态匹配 Android 16 广播方法参数，以及 ColorOS 的 OPlus 代理、自启动限制和解冻方法，
  减少 ROM 小版本变动造成的固定签名失效。
- 针对国行区域策略，按 FCMFix 允许列表绕过应用分类、GCM bind 和 FCM service 启动限制；
  仅为 GMS/GSF/Play 恢复 Doze 条目和 Hans 组件放行，不改写整份 ColorOS 配置。
- 阻止 ColorOS 电池组件在 Google 连通性检测失败时对 GMS 等核心包写入
  `POLICY_REJECT_ALL`，但不干预用户通过系统联网管理手动设置的规则。
- 测试包使用独立包名 `com.kooritea.fcmfix.op15`，可以和官方 FCMFix 并存；
  在 LSPosed 中只启用一个版本，作用域选择 `系统框架` 和 `电池`（包名
  `com.oplus.battery`）。

已在一加 15 ColorOS `16.0.10.500` 上完成实机回归：

- GMS、GSF、Play 商店和 ConfigUpdater 的联网策略均为 `POLICY_NONE`；
- Nekogram `tw.nekomimi.nekogram` 被强制停止后处于 `stopped=true` 且无进程；
- 收到 `com.google.android.c2dm.intent.RECEIVE` 后，系统成功启动 Nekogram、清除 stopped
  状态并生成通知。

### 附加功能

- 阻止Android系统在应用停止时自动移除通知栏的通知
- 在miui/hyperos(?)/OxygenOS15(?)/ColorOS15(?)上动态解除来自fcm的自启动限制
- 移除miui/hyperos对后台应用的通知限制
- 没有预期唤醒目标应用时发送提示通知

### lsposed作用域
- 在miui/hyperos上如果推送没有问题，就不需要勾选电量和性能

### 关于fcm

fcm是在Android中由google维护的一条介于google服务器与gms应用之间用于推送通知的长链接。  
一般的工作流程为应用服务器将消息发送到google服务器，google服务器将消息推送给gms应用，gms应用通过广播传递给应用，应用通过接收到的fcm消息决定是否发送通知和通知内容。  
其中gms通过fcm广播通知应用时，如果应用处于非运行状态，就会出现`Failed to broadcast to stopped app`，fcmfix主要就是解决这个问题。

### 已知问题

- 非miui/hyperos/OxygenOS15/ColorOS15系统可能需要给予目标应用类似允许自启动的权限，以及电池选项设置为不优化。

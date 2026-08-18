# fcmfix (Android 10-16)

[![Android CI](https://github.com/kooritea/fcmfix/workflows/Android%20CI/badge.svg)](https://github.com/kooritea/fcmfix/actions)

让fcm/gcm唤醒未启动的应用进行发送通知  

### 一加 15 / ColorOS 16 适配

本仓库包含针对一加 15（Android 16 / ColorOS 16）的实机适配：

- 在 `ActivityManagerService.broadcastIntentWithFeature` 前置入口补充
  `FLAG_INCLUDE_STOPPED_PACKAGES`，避免 ColorOS 16 在后续广播处理前复制或过滤 Intent，
  导致原有 `broadcastIntentLocked` 钩子无法唤醒已停止应用。
- 动态匹配 Android 16 广播方法参数，以及 ColorOS 的 OPlus 代理、自启动限制和解冻方法，
  减少 ROM 小版本变动造成的固定签名失效。
- 测试包使用独立包名 `com.kooritea.fcmfix.op15`，可以和官方 FCMFix 并存；
  在 LSPosed 中只启用一个版本，作用域选择 `system`。

已在一加 15 ColorOS `16.0.10.500` 上验证：Nekogram
`tw.nekomimi.nekogram` 处于 `stopped=true` 且无进程时，FCM 可以启动应用并生成通知。

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

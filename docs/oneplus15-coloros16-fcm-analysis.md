# OnePlus 15 ColorOS 16 FCM 限制分析

## 样本

- 国行：PLK110 16.0.10.500 CN01，Android 16，2026-08 安全补丁
  - 完整包 MD5：`2cbe1af4dece932fff62c81f5e6dbb68`
- 国际版：CPH2747 16.0.10.500 EX01，Android 16，2026-08 安全补丁
  - 完整包 MD5：`01cbbeb18a0b9e22c6bda698ce2f080a`

两套 payload 的 `system`、`system_ext`、`product`、`vendor`、`odm`、`my_product`、
`my_stock`、`my_region`、`my_manifest` 均已提取并进行文件与 dex 差分。

## 结论

ColorOS 国行的 FCM 限制不是单一开关。主要 framework 实现跨区域共用，区域包通过 feature 和 XML
启用不同策略。仅把 `google_restric_info` 改为 `0`，仍可能在联网策略、应用分类、自启动名单、Hans/ELSA
或 Doze 阶段被拦截。

| 层 | 国行与国际版差异 | 对 FCM 的影响 |
| --- | --- | --- |
| GMS 区域开关 | 国行独有 `oplus.software.disable_cn_gms` 和 `sys_gms_download_app_whitelist.xml` | 支持按系统设置禁用 GMS；未启用谷歌服务时可直接阻断 |
| GMS 联网策略 | Battery 的 `GoogleRestrictionController` 在 Google 探测失败时对 GMS、Play 和 ConfigUpdater 调用 `setUidPolicy(uid, 4)` | `4` 是 `POLICY_REJECT_ALL`，会同时拒绝移动数据和 Wi-Fi |
| Doze 白名单 | 国际版区域列表包含 GMS、GSF、Play；国行区域列表只有 GSF | 国行可把 GMS 移出永久省电白名单 |
| Hans GMS 状态 | 国行启用 `oplus.software.hans_restriction`，并监听 `google_restric_info` | 可向 Hans 各后台模块广播 GMS restricted 状态 |
| ELSA/Hans 掩码 | 国行 GMS/GSF/Play 为 `1111111110`，国际版为 `0111111110` | 国行额外包含 close-socket/net 位；两区配置都覆盖 service、broadcast、job 等位 |
| 应用分类限制 | `isAppClassifyRestricted` 在国际版直接返回 false，国行读取按用户、组件类型的限制表 | 可在广播、服务、Provider、重启服务、通知服务和 Job 入口提前拒绝目标应用 |
| GCM bind 自启动 | 国行策略在 `bsgcm` 路径先检查 `google_restric_info`，之后仍调用 `isAllowAutoStartByList` | 清除谷歌限制开关后，目标应用仍可能因自启动策略无法由 GMS 拉起 |
| LightOS 策略 | 国行 `sys_startup_policy_config.xml` 启用 Job、粘性广播、未使用应用、系统应用待机等限制 | 增加后台启动和任务调度限制；并非所有项都只针对 FCM |

## 关键调用链

ColorOS 16 的 GMS bind 路径使用：

- caller 标记：`system[gcm]`
- type 标记：`bsgcm`
- 入口：`OplusAppStartupManager.isAllowStartFromBindService(...)`
- 共用实现：`OplusAppStartupManager.isAllowStartFromService(...)`

在 `bsgcm` 分支中，`google_restric_info=0` 只会把初始 scene result 设为允许，随后仍会调用
`OplusStartupStrategy.isAllowAutoStartByList(...)`。因此只 Hook `isGoogleRestricInfoOn()` 不足以保证已被系统
结束的目标应用能被 FCM 拉起。

`OplusStartupStrategy.isAppClassifyRestricted(...)` 是另一条更早的国行专用拦截路径。它在国际版中因
`isExpVersionExt()` 直接跳过，在国行中则会查询 `All` 和具体组件类型限制表。

联网限制的直接写入者位于 Battery.apk 的
`com.oplus.battery.restrictdynamicfeature.google.GoogleRestrictionController`。该类先用
`google.com/generate_204` 等地址判断 Google 网络是否可用；失败后通过
`OplusNetworkingControlManager.setUidPolicy(uid, 4)` 写入 `POLICY_REJECT_ALL`，再广播限制状态并将
`google_restric_info` 设为 `1`。国行和国际版 Battery 都包含这套共用代码，实际行为由区域 feature、系统设置
和 RUS 配置决定。因此仅删除某个 iptables 链不是稳定的应用级方案，应阻止这次 Google 专属策略写入。

## 本适配版的处理

修复保持按 FCMFix 允许列表放行，不全局关闭 ColorOS 后台管控：

1. 对允许列表中的真实 FCM Intent，跳过 `isAppClassifyRestricted`。
2. 对 `bsgcm` / `system[gcm]` 且目标位于允许列表的 bind-service，返回允许。
3. 对旧式 FCM start-service 且目标位于允许列表的调用，返回允许。
4. 让 `OplusBgSceneManager` 不进入 GMS restricted 状态。
5. 对 GMS、GSF、Play 的 `OplusHansDBConfig.isSysRestrictionCpn` 返回 `NOT_PROXY`，不影响其他应用。
6. 在 `OplusDeviceIdleHelper.getNewWhiteList` 完成原有列表合并后，仅追加 GMS、GSF、Play，保留
   ColorOS 的本地、RUS、定制、NFC 和用户禁止规则。
7. 保留已有的 FCM 广播代理绕过、目标解冻和 GMS 重连修复。
8. 仅在 `com.oplus.battery` 进程中，把系统自动针对 Google 核心包提交的
   `POLICY_REJECT_ALL` 改为 `POLICY_NONE`；Settings/TrafficMonitor 进程中的用户手动联网控制不受影响。

## 关于上游 PR #262

PR #262 的方向包含 Doze 修复，但其实现会清空 ColorOS 已合并的白名单，再用 `mDefaultWhitelist` 重建。
在 PLK110 16.0.10.500 中，`mDefaultWhitelist` 本身不含 GMS，而且这种做法会丢失区域、RUS 和其他动态
条目。它针对 Battery OMS 的 Hook 在该固件 Battery.apk 中也没有对应的 `assets/oms`。因此本适配版没有
直接合并该实现，而是只追加缺失的 Google 包。

## 边界

- “划掉任务”“进程被系统杀死”“进入冻结状态”属于本修复的目标场景。
- 用户在应用详情中点击 Android 的“强行停止”后，系统会设置 package stopped 状态。FCMFix 会在系统广播
  入口补上 `FLAG_INCLUDE_STOPPED_PACKAGES`，本机旧版测试曾成功拉起 `stopped=true` 的 Nekogram；但这是
  对 Android 强停语义的主动绕过，仍需在每次 ROM 大改后单独回归，不能只用普通“进程被杀”测试替代。
- OTA 可能改变类名或方法签名。所有 ColorOS 专用 Hook 都独立捕获失败并写入 Xposed 日志，避免单个 Hook
  失配导致 system_server 启动失败。

## 实机验证清单

1. 确认所有新增 Hook 在 Xposed 日志中显示 active，且没有对应的 `hook error`。
2. 确认 `com.oplus.battery` 作用域已启用，并出现 `Oplus Battery network hook active`。
3. 确认 `dumpsys deviceidle whitelist` 包含 GMS、GSF、Play，且 GMS UID 没有 reject-all 联网策略。
4. 分别测试普通划卡/杀进程，以及 `am force-stop` 后 package 为 `stopped=true` 的强停场景。
5. 发送 FCM，检查 `Oplus classify restriction bypass` 或 GCM/service bypass 日志。
6. 确认测试应用进程被拉起并收到通知；随后测试息屏、Doze 和长时间待机。

## PLK110 16.0.10.500 实机结果

本适配版 `53-oneplus15-cos16-3` 已在一加 15 国行系统完成强停回归：

- 禁用引起 Zygote/SystemUI FreeType 崩溃的第三方 Font Loader 与配套字体模块后，
  system_server 和 LSPosed 注入保持稳定；该崩溃与 FCMFix 无关。
- `coloros_gms_extreme_fix` 根模块保持禁用，`sys_elsa_config_list.xml` 没有 bind mount，
  仅启用本适配 APK；GMS 的 5228 FCM 长连接保持 `ESTABLISHED`。
- `com.oplus.battery` 启动后，GMS、GSF、Play 商店及 ConfigUpdater 的
  `networking_control` UID policy 均为 `0`（`POLICY_NONE`）；修复前 GMS、
  Play 或 ConfigUpdater 曾被写入 `4`（`POLICY_REJECT_ALL`）。
- 对 Nekogram 执行 `am force-stop tw.nekomimi.nekogram` 后，包状态为
  `stopped=true` 且进程不存在。
- 测试消息到达时（11:49:12.047），system_server 收到由 GMS UID 发出的
  `com.google.android.c2dm.intent.RECEIVE`，FCMFix 的 BroadcastFix、
  OplusProxyFix 和 AutoStartFix Hook 均出现在调用栈中。
- Nekogram 随后以 PID 29890 被启动，包状态变为 `stopped=false`，并在约 0.57 秒后
  成功发布消息通知。

因此在该固件与当前 LSPosed 环境中，应用级修复已覆盖国行 ColorOS 的 Google
联网策略和已停止应用 FCM 唤醒链路。OTA 后仍应重复上述回归。

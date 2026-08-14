# 地区 Profile 系统架构

## 1. 核心观念：地区不是一个值

"设备属于哪个地区"在 Android 框架里有**至少 5 个互不相同的来源**，由不同代码写入，
可改性天差地别。把它们当成一个值，是这个问题一直显得混乱的根源。

| 信号 | 来源 | 机制 | 状态 |
| --- | --- | --- | --- |
| SIM 国家码 | 物理 SIM | CarrierConfig | ✅ 真机已验证 |
| SIM 运营商名 | 物理 SIM | CarrierConfig | ⚠️ 实验性 |
| SIM MCC/MNC | 物理 SIM | `setCarrierTestOverride` | ⚠️ 实验性 |
| SIM Carrier ID | 由 MCC/MNC 推导 | 无直接入口 | 只读 |
| 网络国家码 | **基站** | 无 | ❌ 免 root 不可改 |
| 网络 MCC/MNC | **基站** | 无 | ❌ 免 root 不可改 |
| 网络运营商名 | **基站** | 无 | ❌ 免 root 不可改 |
| 漫游 | SIM 与网络比较 | 无 | ❌ 免 root 不可改 |
| CarrierConfig | 配置体系 | CarrierService | ✅ 真机已验证 |
| APN / 数据 | 框架 | **刻意不动** | 只读（安全核验项） |
| 语言 / 时区 | 用户设置 | 系统设置 | 只读 |
| 出口 IP | 设备之外 | 外部网络方案 | 只读 |

代码中的权威定义：[`Signal`](../app/src/main/java/com/github/nrfr/region/Signal.kt)、
[`SignalCapabilities`](../app/src/main/java/com/github/nrfr/region/SignalCapability.kt)。

> `VERIFIED` 只能由**真机实测**赋予。读了 AOSP 源码就认为 OEM 行为一致，只能算
> `EXPERIMENTAL`。

## 2. 两条完全不同的机制

这是最容易搞错的一点：

### 2.1 CarrierConfig 路线（国家码、运营商名）

```
setCarrierTestOverride(carrierPrivilegeRules=<本应用证书 SHA-256>)   → 获得 carrier privileges
setCarrierServicePackageOverride(subId, <本应用>)                    → 被固定为 CarrierService
NrfrCarrierService.onLoadConfig()                                   → 返回配置 bundle
UiccProfile.handleSimCountryIsoOverride()                           → 写 gsm.sim.operator.iso-country
TelephonyManager.getSimCountryIso()                                 → 应用读到新值
```

### 2.2 setCarrierTestOverride 路线（MCC/MNC）

**CarrierConfig 里根本没有 MCC/MNC 的键。** 全部 `*_OVERRIDE_*` 键只有
`SIM_COUNTRY_ISO` / `CARRIER_NAME` / `SPDI` / `EHPLMN` / `PNN` / `OPL`。所以
CarrierService 这条路对 MCC/MNC **完全无效**，只能走：

```
IccRecords.setCarrierTestOverride(mccmnc, …)
    → TelephonyManager.setSimOperatorNumericForPhone(phoneId, mccmnc)
    → 写 gsm.sim.operator.numeric
    → TelephonyManager.getSimOperator() 返回新值
```

### 2.3 MCC/MNC 的连带影响（源码依据）

| 受影响项 | 依据 | 结论 |
| --- | --- | --- |
| Carrier ID | `CarrierResolver:335` 读 `getSimOperatorNumericForPhone()` 查 carrier-id 数据库 | **必然改变** |
| APN 匹配 | 框架按运营商 numeric 选择 APN | **可能影响移动数据** |
| 网络注册 | 调制解调器用真实 SIM 的 IMSI | **不受影响** |
| 物理 SIM | 全程只读 | **不受影响** |

因此 MCC/MNC 被标为实验性、默认关闭，并在 UI 上明确提示风险。

## 3. 事务模型

唯一的应用/清理编排点是
[`RegionTransaction`](../app/src/main/java/com/github/nrfr/region/RegionTransaction.kt)，
清理实现唯一地位于 [`Cleanup`](../app/src/main/java/com/github/nrfr/region/Cleanup.kt)。

```
① 快照           采集全部信号
② 校验基线       读不到将被改动信号的基线 → 拒绝执行（否则无法还原）
③ 写事务日志     落盘后才动任何特权状态
④ 注册并应用     privileges + CarrierService + 下发配置
⑤ 验证           逐信号区分 CONFIG_ACCEPTED / ACTUALLY_EFFECTIVE
⑥ 副作用检查     除预期信号外任何身份变化都算意外
⑦ 还原           【仍在注册状态下】把基线值推回
⑧ 释放           先撤 privileges、再清 override、轮询确认已解绑
⑨ 最终快照       清理**之后**才测量
⑩ 验证清理完整   六项条件全满足才关闭日志
```

### 3.1 不可调换的顺序（都是真机踩出来的）

1. **还原必须在解绑之前。** `handleSimCountryIsoOverride()` 只在覆盖值非空时才写属性，
   所以"移除键"对属性是 no-op；解绑后框架不再向我们索取配置，就再也推不回去了。
2. **先撤 privileges、再清 override。** `getCarrierService()` 里那道
   `mTestOverrideCarrierServicePackage != null` 过滤器一旦先被清除，而 privileges 还在，
   这次重算反而会把本应用选成"普通的"运营商服务。
3. **测量必须在清理之后。** 曾经 `finish()` 写在 `try` 的 return 里、清理在 `finally`，
   于是"事后"数据其实采集于清理之前。
4. **异步一律回读。** `setTestOverride*` 走 handler 线程，调用返回不等于生效。

### 3.2 清理完整的六项条件

[`CleanupReport.complete`](../app/src/main/java/com/github/nrfr/region/TransactionModel.kt)
要求**全部**满足：

1. 原始身份已还原（以对外可见的 getter 为准，不是看配置 bundle）
2. CarrierConfig 回到原始状态（原本不存在的键必须仍不存在）
3. CarrierService 已释放（轮询确认）
4. carrier privileges 已撤销
5. 无意外身份变化
6. APN / 数据未受损

**覆盖生效但没还原干净不算成功**（`TransactionResult.success` 同时要求两者）。

## 4. 崩溃 / 重启恢复

[`TransactionJournal`](../app/src/main/java/com/github/nrfr/region/TransactionJournal.kt)
在动任何特权状态**之前**用 `commit()`（同步写盘，不是 `apply()`）落盘基线，
只有清理被验证完整才删除。

[`RecoveryManager`](../app/src/main/java/com/github/nrfr/region/RecoveryManager.kt)
在 `MainActivity`（Shizuku 就绪时）与 `BootReceiver` 中运行：

- 先核对当前值是否已等于基线；重启通常已自然恢复，此时**不做任何特权调用**，直接关闭日志；
- 确实需要回滚时，才为还原重新注册；
- 幂等，重复运行安全；
- 回滚未完成则保留日志，下次启动继续尝试。

## 5. 免 root 绝对做不到的事

- 改变 `getNetworkCountryIso()` / `getNetworkOperator()` / `getNetworkOperatorName()`：
  它们来自 `ServiceState`，由调制解调器按实际注册的基站上报。要改就得动 RIL 或
  system_server，属于 root/系统改动。
- 直接写 Carrier ID：没有写入口，只能随 MCC/MNC 间接变化。
- 让覆盖跨重启保持：两个特权调用都是框架内存状态。
- 改变物理 SIM：**本项目从不写 SIM**。

### 对目标应用的现实意义

只要人在中国大陆用中国基站，`getNetworkCountryIso()` 就永远是 `cn`、
`getNetworkOperator()` 永远是 `460xx`。**若目标应用以网络侧为准，本方案无论如何都无效** ——
包括开启 MCC/MNC 伪装，因为那只动 SIM 侧。

## 6. 已证明 / 未证明

### 在 OnePlus 12R (CPH2609) / OxygenOS 16.0.5 / Android 16 上已证明

- CarrierService 注册可用，`onLoadConfig()` 被回调，返回的配置被合并
- `KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING` 确实使 `getSimCountryIso()` 由 `cn` → `us`
- 期间 SIM MCC/MNC 保持 `46000`、网络 MCC/MNC 保持 `46000`、网络国家码保持 `cn`、
  Carrier ID 保持 `1435`、APN 保持 `CMNET / cmnet`、5G NR 与移动数据均未受影响
- CarrierService 可被干净释放（修复后）
- 国家码可被还原（修复后）

### 尚未证明

- MCC/MNC 覆盖在真机上是否生效，以及它对 Carrier ID / APN / 数据的实际影响
- 运营商名覆盖对 `getSimOperatorName()` 的实际效果
- 目标应用在各组合下的实际反应

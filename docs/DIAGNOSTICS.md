# 诊断阶段（Step 6）：基线、来源归属与 TikTok 可见性分析

本阶段**只读**，不修改任何 MCC/MNC、国家码、APN 或 SIM 卡本身。

---

## 0. 真机验证结论（OnePlus 12R / OxygenOS 16.0.5 / Android 16）

**Android 16 的 CarrierService 方案在该真机上已验证可用。** 实测结果：

| 环节 | 结果 |
| --- | --- |
| ITelephony 隐藏方法可用性 | ✅ 三个方法均存在 |
| `setCarrierTestOverride` | ✅ 成功 |
| `setCarrierServicePackageOverride` | ✅ 成功 |
| `notifyConfigChangedForSubId` | ✅ 成功 |
| 框架回调 `CarrierService.onLoadConfig()` | ✅ 被调用 |
| 返回的 CarrierConfig 合并生效 | ✅ 哨兵键 `nrfr_probe_token` 可读回 |
| SIM 身份值 | ✅ 未变 |
| 网络身份值 | ✅ 未变 |
| 移动数据 | ✅ 保持连接 |
| APN | ✅ 保持 CMNET / cmnet |
| 网络制式 | ✅ 保持 5G NR |

**已验证的是"机制本身可用"**，即：本应用能被框架接受为 CarrierService，`onLoadConfig()`
会被回调，返回的配置会被真正合并进最终 CarrierConfig。

**尚未验证的是**：用它下发真实的国家码覆盖后，`getSimCountryIso()` 是否随之改变，
以及 TikTok 的实际反应。探测只下发了一个无意义的随机哨兵键。

### 已修复的探测逻辑缺陷

首次真机探测曾**误报失败**，原因是 `data_network_type` 在探测前后表现为：

```
探测前: 读取失败: SecurityException: getDataNetworkTypeForSubscriber
探测后: 5G NR
```

旧的比较逻辑只比较值本身，于是把"从读不到变成读得到"当成了"值发生了变化"。
这纯粹是**观测差异**：探测期间临时持有 carrier privileges 让这个字段变得可读，
与 SIM 或网络本身毫无关系。

修复方式见 [§4.3](#43-探测前后比较的语义)。

---

## 1. 为什么"国家"在 Android 里不是一个值

风控类应用之所以难绕，是因为"这台设备在哪个国家"在框架里有**至少四个互不相同的来源**，
它们由不同的东西决定，也因此有完全不同的可改性：

| 来源 | 决定于 | 本应用能否改 |
| --- | --- | --- |
| **SIM 卡** | SIM 内的 IMSI / EF 文件 | ✅ 经 CarrierConfig 间接改 |
| **蜂窝网络** | 当前注册的**基站** | ❌ 免 root 不可能 |
| **CarrierConfig** | 平台配置 + 运营商应用 | ✅ 这正是我们的作用点 |
| **设备设置** | 用户的语言/时区 | — 用户自己改 |

关键结论：**我们能改"SIM 说自己是哪国"，改不了"你正连着哪国的基站"。**

## 2. 完整基线清单（诊断界面采集的 20 项）

### 2.1 SIM 来源 —— 可影响

| 项 | API | 可改性 |
| --- | --- | --- |
| SIM 运营商代码 (MCC+MNC) | `getSimOperator()` | ⚠️ 需进阶选项 |
| SIM 运营商名称 | `getSimOperatorName()` | ✅ |
| SIM 国家码 (ISO) | `getSimCountryIso()` | ✅ **主要作用点** |
| SIM Carrier ID | `getSimCarrierId()` | ❌ |
| SIM Carrier ID 名称 | `getSimCarrierIdName()` | ❌ |
| SIM 精确 Carrier ID | `getSimSpecificCarrierId()` | ❌ |
| SIM 状态 | `getSimState()` | — |

> **Carrier ID 为什么改不了**：它由 `CarrierResolver` 依据 MCC/MNC + GID + SPN + IMSI 前缀，
> 对照系统内置的 carrier id 数据库解析得出，不读 CarrierConfig。只有改动那些底层输入
> （即进阶的 MCC/MNC 伪装）才会连带改变它。

### 2.2 网络来源 —— **无法影响**

| 项 | API | 可改性 |
| --- | --- | --- |
| 网络运营商代码 (MCC+MNC) | `getNetworkOperator()` | ❌ |
| 网络运营商名称 | `getNetworkOperatorName()` | ❌ |
| 网络国家码 (ISO) | `getNetworkCountryIso()` | ❌ |
| 是否漫游 | `isNetworkRoaming()` | ❌ |
| 当前网络制式 | `getDataNetworkType()` | ❌ |

> 这些值来自 `ServiceState`，由调制解调器根据实际注册的基站上报。
> 免 root 没有任何受支持的接口能改写它们；要改就得动 RIL 或 system_server，
> 那已经属于 root/系统改动，超出本项目范围。
>
> **只要人在中国大陆用中国基站，`getNetworkCountryIso()` 就永远是 `cn`。**

### 2.3 框架状态 —— 仅用于安全核验

数据连接状态、数据是否真正联通（`NET_CAPABILITY_VALIDATED`）、数据开关、
通话/短信能力、当前通话状态、默认数据/短信 subId、当前 APN。

这些不是身份标识，采集它们是为了在任何改动前后**证明电话功能没被弄坏**。

> APN 需要 carrier privileges 或 MODIFY_PHONE_STATE 才能读。普通应用会拿到
> `SecurityException`，诊断界面会如实显示"不可读"，**不会**为了读它去提权。

### 2.4 CarrierConfig —— 我们的作用点

国家码覆盖项、运营商名覆盖开关/值、配置键总数。经 Shizuku 以特权身份读取。

## 3. TikTok 可能用到哪些标识

按"风控实际可用性"排序。**关键在于：普通应用无需任何权限就能读到第一梯队。**

| 标识 | 普通应用可读 | 我们能改 | 备注 |
| --- | --- | --- | --- |
| `getSimCountryIso()` | ✅ 无需权限 | ✅ **能** | 本方案主要目标 |
| `getNetworkCountryIso()` | ✅ 无需权限 | ❌ **不能** | **最大的漏点** |
| `getSimOperator()` (MCC+MNC) | ✅ 无需权限 | ⚠️ 进阶选项 | 默认仍为 `460xx` |
| `getNetworkOperator()` (MCC+MNC) | ✅ 无需权限 | ❌ **不能** | 基站决定 |
| `getSimOperatorName()` | ✅ 无需权限 | ✅ 能 | |
| `getNetworkOperatorName()` | ✅ 无需权限 | ❌ 不能 | |
| `getSimCarrierId()` | ✅ 无需权限 | ❌ 不能 | 由 MCC/MNC 等推导 |
| CarrierConfig 具体值 | ❌ 需特权 | ✅ 能 | 应用一般读不到 |
| 系统语言 / 时区 | ✅ 无需权限 | — 用户自己改 | 常被用作辅助信号 |
| 出口 IP 地理位置 | ✅ | — 需你自己的网络方案 | 通常权重最高 |

### 现实评估

如果 TikTok 只看 `getSimCountryIso()`，本方案够用。

如果它同时看 `getNetworkCountryIso()` 或 `getNetworkOperator()`——在中国大陆基站下这两个
**必然**是 `cn` / `460xx`——那么**无论怎么改 SIM 侧都不可能通过**，即使启用进阶的 MCC/MNC 伪装
也不行，因为那只动 SIM 侧、动不了网络侧。

这不是实现上的欠缺，而是免 root 方案的**能力边界**。诊断报告的价值就在于：在你冒任何风险之前，
先把这条边界摆清楚。

## 4. CarrierService 机制探测（Phase B）

### 探测做什么

1. 反射检查三个隐藏方法是否存在（**只查找，不调用**）
2. 确认当前没有其它应用被绑定为 CarrierService
3. 记录 SIM/网络身份基线
4. 用 SIM 的**真实** MCC/MNC 与 SPN 调用 `setCarrierTestOverride`（值原样回填 → 不变）
5. `setCarrierServicePackageOverride` 把自己注册为 CarrierService
6. `notifyConfigChangedForSubId` 触发重新加载
7. 等待框架回调 `onLoadConfig()`
8. 回读合并后的 CarrierConfig，检查哨兵键是否存在
9. **`finally` 中无条件还原**
10. 重新采集身份值并与基线逐项对比

### 为什么这不改变网络身份

探测期间 `onLoadConfig()` 返回的 bundle **只含一个随机哨兵键** `nrfr_probe_token`。
这个键对框架毫无意义，不会被任何电话逻辑读取。于是我们既走通了完整链路，
又没有把任何真实电话参数放进配置里。

第 10 步是关键：报告**证明**身份值没变，而不是口头保证。

### 已识别的副作用（如实告知）

1. **`IccRecords` 的 test-mode 标志**在探测后仍为 true，直到重启或重新插拔 SIM。
   由于所有覆盖值都回填了真实值，各 getter 的返回值不变，因此无行为差异。
2. **`mTestOverrideRules` 非空时会屏蔽 UICC 规则**：探测的十几秒内，若有别的应用
   本来通过 SIM 证书持有 carrier privileges，它会**暂时**失去。中国大陆 SIM 上这类应用
   极为罕见，且还原后立即恢复。
3. **CarrierConfigLoader 会把运营商应用的配置缓存到 XML**，因此哨兵键可能在
   `/data/user_de/0/com.android.phone/files/` 下留下一个以本应用包名命名的缓存文件。
   还原后不再被使用，内容也只有那个随机 token。

### 4.3 探测前后比较的语义

每个值的比较结果被分成五类，**只有 `CHANGED` 且属于身份标识集合**才会导致"未还原"的结论：

| 分类 | 含义 | 算作变更？ |
| --- | --- | --- |
| `UNCHANGED` | 两次都读到，值相同 | 否 |
| `CHANGED` | 两次都读到，值不同 | **是**（仅限身份项） |
| `BECAME_READABLE` | 探测前不可读、探测后可读 | 否（观测差异） |
| `BECAME_UNREADABLE` | 探测前可读、探测后不可读 | 否（观测差异） |
| `NOT_COMPARABLE` | 两次都读不到 | 否 |

"读不到"由 `DiagnosticValue.error != null` 表示，**不是**用 `null` 值表示 ——
因为 `null` 和 `""` 都是合法的读数（例如把 mccmnc 传 null 导致属性被清空，那是真正的变更）。

参与判定的**身份标识集合**是显式列举的 8 项：

```
sim_operator, sim_operator_name, sim_country_iso, sim_carrier_id,
network_operator, network_operator_name, network_country_iso, network_roaming
```

`data_network_type` **刻意不在其中**：它是无线状态，会因小区重选、5G↔LTE 切换自行变化，
本来就不是身份声明。它仍会被采集与展示，但只作为"观测差异"呈现，例如：

```
⚠️ data_network_type: 探测前不可读、探测后可读（值 5G NR）；属观测差异，不算变更
```

### 4.4 两个彼此独立的结论

报告现在分别给出：

- **机制结论** = `onLoadConfig` 被回调 && 配置已合并 → Android 16 方案是否可用
- **还原结论** = 是否存在真正变更的身份值

二者**互不影响**。还原不干净是另一个问题，绝不会被表述成"机制不可用"。

### 4.5 让网络制式变得可比较

诊断界面现在会在进入时申请 `READ_PHONE_STATE`。授予后 `getDataNetworkType()`
在探测前后都可读，于是它成为一个**真正可比较**的字段而非权限伪影；
若用户拒绝授权，则退化为 `NOT_COMPARABLE`，同样不会被误判为变更。

### 4.6 「只改 SIM 国家码」实验（Step 7）

哨兵探测只证明了框架会**搬运**我们的 bundle，**没有**证明它会**响应**
`KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING` 这个具体的键。AOSP 里是
`UiccProfile.handleSimCountryIsoOverride()` 把它写进 `gsm.sim.operator.iso-country`，
但 OEM 完全可能合并了这个键却从不走那条路径。

因此这个实验把两件事严格分开：

| 判定 | 含义 |
| --- | --- |
| **A** | 该键出现在合并后的 CarrierConfig 中（配置被接受） |
| **B** | `getSimCountryIso()` 真的从 `cn` 变成了 `us`（配置真的生效） |
| **C** | `getNetworkCountryIso()` 保持 `cn` |
| **D** | SIM MCC/MNC 保持 `46000` |

结论分为四类：

| 结论 | 含义 |
| --- | --- |
| `CONFIG_REJECTED` | A 失败：键根本没进配置 |
| `CONFIG_ACCEPTED_NO_EFFECT` | A 成功、B 失败：**本 ROM 不响应这个键** |
| `EFFECTIVE` | A、B 均成功 |
| `NOT_RUN` | 未执行 |

`CONFIG_ACCEPTED_NO_EFFECT` 是需要如实报告的结果 —— 出现它就说明这条路在本机走不通，
**不会**再自动去试别的机制。

实验只下发**一个键**。`setCarrierTestOverride` 仍然回填 SIM 的真实 MCC/MNC 与 SPN，
因此 `gsm.sim.operator.numeric` / `.alpha` 保持不变。除 `sim_country_iso` 外，
任何身份键发生变化都会被记为**意外副作用**并导致实验判定为不干净。

还原同样在 `finally` 中无条件执行，并会再次逐项对比以确认国家码已回到 `cn`。

### 4.7 回滚缺陷的根因（run #8）与修复

run #8 在真机上覆盖成功（`cn` → `us`），但**清理后国家码卡在 `us`**。根因有两条，都在
AOSP 源码里可以直接看到。

#### 根因一：框架没有"取消覆盖"这条路径

`UiccProfile.handleSimCountryIsoOverride()`：

```java
String iso = config.getString(KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING);
if (!TextUtils.isEmpty(iso)
        && !iso.equals(TelephonyManager.getSimCountryIsoForPhone(mPhoneId))) {
    mTelephonyManager.setSimCountryIsoForPhone(mPhoneId, iso);
    SubscriptionManagerService.getInstance().setCountryIso(subId, iso);
}
```

键被移除后 `iso` 为空 → `!TextUtils.isEmpty(iso)` 为假 → **整个分支被跳过**。
`gsm.sim.operator.iso-country` 保持上一次写入的值。

全框架只有三处会写这个属性：

| 位置 | 触发时机 |
| --- | --- |
| `UiccProfile.handleSimCountryIsoOverride()` | 覆盖值非空时 |
| `SIMRecords.onAllRecordsLoaded()` | SIM 记录重新加载（重启 / 飞行模式 / 重新插卡）→ 由 IMSI 前 3 位查 `MccTable` |
| `SIMRecords.onRadioOffOrNotAvailable()` / `UiccProfile.resetProperties()` | 置为 `""` |

所以**移除键是单向的 no-op**，必须主动把原值再推一次。

#### 根因二：清理顺序与测量时机都错了

1. 旧代码先 `setCarrierServicePackageOverride(subId, null, …)` 解除注册 —— 框架从此不再向我们
   索取配置，也就再没有机会推回原值；
2. `finish()` 写在 `try` 的 `return` 表达式里，而清理在 `finally` —— 因此"事后"数据实际是在
   清理**之前**采集的，报告里的"还原"一栏测的其实是实验中状态。
   （这也解释了上一轮 `data_network_type` 为何在"事后"仍可读：当时仍持有 carrier privileges。）

#### 修复

- 新增 [`CountryIsoRestore`](../app/src/main/java/com/github/nrfr/diag/CountryIsoRestore.kt)：
  **在仍然注册为 CarrierService 的状态下**把基线值推回去，等 `getSimCountryIso()` 真的变回来，
  再移除该键；
- 清理改为在 `finally` 中完整执行，**测量移到 `try/finally` 之后**；
- 实验前记录 CarrierConfig 中该键的**原始状态**（可能是"不存在"），
  清理后逐项核对，绝不硬编码 `cn`；
- 读不到基线时**直接拒绝实验**，而不是事后无法还原；
- 判定改为三重条件：`getSimCountryIso()` 已还原 **且** 配置键回到原始状态 **且** 全部身份键一致。
  覆盖生效但没还原干净 **不算成功**；
- 同样的缺陷存在于主界面的「还原设置」，已一并修复：首次覆盖前会把真实国家码记入
  `OverrideStore.rememberOriginalCountry()`，还原时主动推回。

### 4.8 CarrierService 绑定未释放（run #9）

run #9 国家码已正确还原为 `cn`，但框架仍报告 `com.github.nrfr` 被绑定为 CarrierService，
导致下一次探测被安全前置检查拒绝。

#### 根因

**顺序**。`CarrierPrivilegesTracker.getCarrierService()`：

```java
if (mTestOverrideCarrierServicePackage != null
        && !mTestOverrideCarrierServicePackage.equals(packageName)) continue;
if (simPrivilegedPackages.contains(packageName)) { carrierServicePackageName = packageName; break; }
```

先清 `mTestOverrideCarrierServicePackage` 会让那道 `continue` 过滤器消失，而此刻
`mTestOverrideRules` 尚未清除 —— 我们**仍然持有 carrier privileges**，且本应用在 manifest 中
确实声明了 `CarrierService`。于是这一次重算把我们选成了"普通的"运营商服务。

**异步**。`setTestOverrideCarrierPrivilegeRules()` / `setTestOverrideCarrierServicePackage()`
都是 `mCurrentHandler.sendMessage(...)`，重算在 CPT 的 handler 线程上完成。
调用返回后立即读 `getCarrierServicePackageNameForLogicalSlot()` 得到的是**尚未更新的缓存**
（`mPrivilegedPackageInfo.mCarrierService.first`）。

#### 修复

新增 [`CarrierServiceRelease`](../app/src/main/java/com/github/nrfr/diag/CarrierServiceRelease.kt)：

1. **先**撤销 carrier privileges（`mTestOverrideRules = null`）；
2. **再**清除 CarrierService override —— 最后一次重算发生在我们已不具备资格之后；
3. `notifyConfigChangedForSubId`；
4. **轮询** `getCarrierServicePackageNameForLogicalSlot()` 直到不再是本应用（最多 8 秒）；
5. 未成功则整体重试，最多 3 次。

该操作幂等，已接入所有清理路径：哨兵探测、国家码实验、主界面「还原设置」、强制恢复。
实验判定新增第四个条件 `carrierServiceReleased` —— 绑定没释放同样算"没清理干净"。

诊断界面新增「**释放 CarrierService**」卡片，可随时单独执行。

#### 已经被卡住的设备怎么办

1. **切换飞行模式约 10 秒**（最简单，不需要本应用）——
   `SIMRecords.onAllRecordsLoaded()` 会从 IMSI 重新解析真实国家码；
2. 或重启手机；
3. 或用诊断界面的「恢复 SIM 国家码」卡片，填入原值（通常 `cn`）后执行。

### 会自动拒绝执行的情况

若 `getCarrierServicePackageNameForLogicalSlot()` 返回了**别的**包名，探测按钮直接禁用。
顶替一个真实的运营商应用会丢掉它提供的配置（可能含 VoLTE/IMS 关键项），这已经不是诊断行为。

## 5. 使用方法

1. 安装本分支构建的 APK，授权 Shizuku。
2. 主界面右上角 🔧 图标 → 进入「电话状态诊断」。
3. 基线会自动采集（**纯只读**）。
4. 点击「复制完整报告」，把文本发回。
5. 如需验证 Android 16 机制，点「运行探测」，确认弹窗后执行，再复制一次报告。
6. 探测后请核对：移动数据、通话、短信是否一切正常。

> 若报告中「探测后已完全还原」为 ❌，请立刻回主界面点「还原设置」，必要时重启。

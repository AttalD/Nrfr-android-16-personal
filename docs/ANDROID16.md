# Android 16 兼容性说明 / Android 16 compatibility

## 1. 原实现为什么在 Android 16 上失效

Nrfr 原本通过 Shizuku 以 **shell UID (2000)** 调用隐藏接口
`ICarrierConfigLoader.overrideConfig(subId, bundle, persistent)`。

AOSP 在 2025-09 提交了两个补丁（bug **441823943**，随 **2025-10 安全补丁**发布，
对应 **CVE-2025-48617**），都落在
`packages/services/Telephony/src/com/android/phone/CarrierConfigLoader.java`：

| 提交 | 作用 |
| --- | --- |
| `1ac1e79d1` — *Protect shell overriding the carrier config* | 新增 `secureOverrideConfig()`，直接拒绝 shell |
| `c8123b01b` — *Restricting UserBuild from presistent carrierConfig Override* | 正式版(user build)上禁止非系统应用写入持久化覆盖 |

```java
private void secureOverrideConfig(@Nullable PersistableBundle overrides, boolean persistent) {
    // Do not allow shell UID to override the carrier config. This will not impact
    // the CTS and telephony shell commands as they use different uids
    if (TelephonyPermissions.isShell(getCallingUid())) {
        throw new SecurityException("overrideConfig cannot be invoked by shell");
    }
    ...
    if (persistent && isUserBuild() && !isSystemApp()) {
        throw new SecurityException("overrideConfig with persistent=true only can be "
                + "invoked by system app");
    }
}
```

`overrideConfig()` 现在第一行就调用它。所以这**不是**可以绕过的 bug，而是有意的权限收紧：
shell 这条路被彻底关闭了。

`cmd phone cc set-value` 也不可用 —— `TelephonyShellCommand.handleCcCommand()` 要求
调用方是 **root** 且系统为**非 user build**，零售版 OxygenOS 两条都不满足。

## 2. 新方案：把本应用注册成 CarrierService

不再从外部“推”配置，而是成为系统主动“拉”配置的那个组件 —— 这是框架自带的正规扩展点。

```
①  ITelephony.setCarrierTestOverride(subId, …, carrierPrivilegeRules = <本应用证书 SHA-256>, …)
        ↳ enforceModifyPermission()          → shell 持有 MODIFY_PHONE_STATE，未被 CVE 补丁触及
        ↳ CarrierPrivilegesTracker.mTestOverrideRules → 本应用获得 carrier privileges

②  ITelephony.setCarrierServicePackageOverride(subId, "com.github.nrfr", …)
        ↳ TelephonyPermissions.enforceShellOnly()  → 恰好*要求* shell UID，正是 Shizuku 提供的

③  系统绑定 NrfrCarrierService → onLoadConfig() 返回
        KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING / KEY_CARRIER_NAME_*

④  UiccProfile.handleSimCountryIsoOverride() 把国家码写入
        gsm.sim.operator.iso-country → TelephonyManager.getSimCountryIso()
```

两个调用都**不是** root 操作，也没有修改系统分区。

### 为什么必须先拿 carrier privileges

`CarrierPrivilegesTracker.getCarrierService()` 里，`setCarrierServicePackageOverride` 只是把候选集**缩小**到
指定包名；该包名仍必须出现在 `simPrivilegedPackages` 中。而
`getPackagePrivilegedStatus()` 对非空 `mTestOverrideRules` 会返回 `PACKAGE_PRIVILEGED_FROM_SIM`，
这正是 ① 的作用。

### 安全注意事项（已在代码中处理）

* `carrierPrivilegeRules` 必须是**裸十六进制**，不能带 `":<包名>"` 后缀。
  同一个字符串会被两处解析，其中 `GsmCdmaPhone.setCarrierTestOverride` 直接丢给
  `IccUtils.hexStringToBytes`，遇到非十六进制字符会抛异常。见 `CertHashTest`。
* `IccRecords.setCarrierTestOverride()` 会把 `mccmnc` / `spn` 直接写进
  `gsm.sim.operator.numeric` / `gsm.sim.operator.alpha`。传 `null` 会**清空**它们，
  因此本实现始终回填 SIM 的真实值（或用户明确要求的伪装值）。
* 其余字段（imsi / iccid / gid1 / gid2 / pnn）传 `null` 是安全的：
  `IccRecords` 的各个 getter 在覆盖值为 null 时会回退到真实 SIM 值。

## 3. 局限

* `getNetworkCountryIso()` / `getNetworkOperator()` 来自**当前注册的网络**，不受本方案影响。
  在中国大陆基站上它们仍会返回 `cn` / `460xx`。
* `getSimOperator()` 默认仍是真实的 `460xx`；勾选进阶选项才会改变，但可能影响 APN 匹配。
* 覆盖不会跨重启保留 —— 两个特权调用都是框架内存状态。重启后需重新打开 Nrfr
  （Shizuku 恢复运行后会自动重新应用）。
* `IccRecords` 的 "test mode" 标志在还原后仍为 true，直到重启或重新插拔 SIM。
  由于所有覆盖值都已回填为真实值，这本身不产生行为差异。

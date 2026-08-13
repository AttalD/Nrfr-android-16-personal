# 测试与回滚指南 (OnePlus 12R / OxygenOS 16.0.5 / Android 16)

> ✅ **CarrierService 机制已在 OnePlus 12R / OxygenOS 16.0.5 真机上验证可用**
> （注册成功、`onLoadConfig()` 被回调、配置成功合并、SIM/网络身份与移动数据均未受影响）。
>
> ⚠️ **但「下发真实国家码后 `getSimCountryIso()` 是否随之改变」以及 TikTok 的实际反应仍未验证** ——
> 真机探测只下发了一个无意义的随机哨兵键。
> 以下步骤请按顺序执行，并在每一步确认电话/数据仍然正常。

全程 **不需要** root、解锁 Bootloader 或修改系统分区。任何一步出问题，直接执行
[§7 回滚](#7-回滚) 即可恢复。

---

## 0. 事前准备（建议）

记录当前状态，便于对比与核验：

```bash
adb shell getprop gsm.sim.operator.iso-country   # 预期: cn
adb shell getprop gsm.sim.operator.numeric       # 预期: 460xx
adb shell getprop gsm.sim.operator.alpha         # 预期: 运营商名
```

## 1. 安装 APK

从 GitHub Actions 下载构建产物：
**Actions → Build Android APK → 最近一次成功的运行 → Artifacts → `nrfr-debug-apk`**
解压得到 `app-debug.apk`。

```bash
adb install -r app-debug.apk
```

> 若此前装过官方版 Nrfr，请先卸载：签名不同会导致 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
> 本方案依赖**本 APK 自身的签名证书**，所以必须安装本分支构建的包。

## 2. 启动并授权 Shizuku

1. 安装 [Shizuku](https://shizuku.rikka.app/)。
2. 用**无线调试**方式启动（OxygenOS：开发者选项 → 无线调试）。
3. 打开 Nrfr，弹出 Shizuku 授权时选择「允许」。

> Shizuku 必须处于运行状态。免 Root 的 Shizuku **每次重启后都需要手动重新启动**。

## 3. 选择 SIM 卡

在下拉框中选择插着中国 SIM 的卡槽，卡片会显示当前生效的覆盖配置（初次应为「无覆盖配置」）。

## 4. 选择国家码

选择 **日本 (JP)** 或 **美国 (US)**。

运营商名可选，例如日本选 `NTT docomo`。

**先不要勾选**「同时伪装 SIM 运营商代码 (MCC/MNC)」——那是进阶项，见 §6。

## 5. 应用并核验

点击「保存生效」。界面会显示实际使用的生效方式：

- `CarrierService（Android 16 方案）` —— Android 16 上的预期结果
- `overrideConfig（传统方案）` —— 仅出现在未打 2025-10 补丁的系统上

核验：

```bash
# 关键指标：应变为 jp / us
adb shell getprop gsm.sim.operator.iso-country

# 确认本应用已被系统绑定为 CarrierService
adb shell dumpsys carrier_config | grep -i -A2 "carrier service"

# 确认覆盖键已进入 carrier config
adb shell dumpsys carrier_config | grep -i sim_country_iso_override
```

应用层核验：任意「设备信息」类 App 查看 SIM 国家码，或
`TelephonyManager.getSimCountryIso()` 的返回值。

**不需要重启。** 配置在系统重新加载 carrier config 后立即生效（通常 1–2 秒内）。

## 6. 核验电话功能未受影响

在继续之前**务必**确认：

- [ ] 移动数据可用（关闭 WiFi，打开任意网页）
- [ ] 可以拨出电话
- [ ] 可以收发短信
- [ ] 状态栏信号格正常，未掉网

只要有任何一项异常 → 立刻执行 [§7 回滚](#7-回滚)。

### 6b. 进阶（可选）：伪装 MCC/MNC

仅在 §5 的效果不足以让目标 App 放行时才尝试。

勾选「同时伪装 SIM 运营商代码 (MCC/MNC)」，填入 5–6 位数字，例如：

| 目标 | MCC+MNC |
| --- | --- |
| 日本 NTT docomo | `44010` |
| 美国 T-Mobile | `310260` |
| 美国 AT&T | `310410` |

**风险**：框架会用这个值做 APN 匹配，可能导致移动数据中断。改完后立刻重做 §6 的检查清单，
一旦数据不通就回滚。

## 7. TikTok 测试

1. 清除 TikTok 数据：设置 → 应用 → TikTok → 存储 → 清除数据。
   （否则会读到缓存中的旧地区。）
2. 断开 WiFi，仅用中国 SIM 的移动数据，或按需使用你自己的网络方案。
3. 启动 TikTok，观察是否仍因 SIM 地区被拒。

> 说明：TikTok 的地区判定通常同时参考 SIM 国家码、**网络**国家码、IP、系统语言与时区。
> 本方案只能改变 **SIM** 侧的判定依据，详见 [§9 已知限制](#9-已知限制)。

## 8. 回滚

**方式一（推荐）**：在 Nrfr 中点击「**还原设置**」。会依次：

1. 清除本地保存的覆盖配置；
2. `setCarrierServicePackageOverride(subId, null, …)` 解除 CarrierService 绑定；
3. `setCarrierTestOverride(…, carrierPrivilegeRules = null, …)` 撤销 carrier privileges，
   并把真实的 MCC/MNC 与 SPN 写回；
4. 通知系统重新加载 carrier config。

**方式二（彻底）**：重启手机。所有相关状态都只存在于框架内存中，重启即全部消失
（前提是已按方式一清除本地保存的配置，否则下次打开 Nrfr 会自动重新应用）。

**方式三（最彻底）**：卸载 Nrfr。失去 CarrierService 提供方后，系统会退回默认 carrier config。

回滚后核验：

```bash
adb shell getprop gsm.sim.operator.iso-country   # 应恢复为 cn
adb shell getprop gsm.sim.operator.numeric       # 应恢复为 460xx
```

> 物理 SIM 卡本身**从未被写入或修改**，全过程只改变系统上报给应用的值。

## 9. 重启行为

| 场景 | 是否需要重启 | 说明 |
| --- | --- | --- |
| 应用配置 | 否 | 立即生效 |
| 回滚 | 否 | 「还原设置」即时生效 |
| 重启后恢复配置 | — | 需先手动重启 Shizuku，再打开 Nrfr 即自动重新应用 |
| 彻底清除 test-mode 标志 | 是 | 仅重启或重新插拔 SIM 才会重建 `IccRecords` |

两个特权调用都是框架**内存状态**，重启必然丢失，这是机制本身的限制，不是缺陷。

## 10. 已知限制

- **网络侧国家码不受影响**：`getNetworkCountryIso()` / `getNetworkOperator()` 来自当前注册的基站。
  在中国大陆网络下仍会返回 `cn` / `460xx`。若目标 App 以此为准，本方案无效。
- **默认不改 `getSimOperator()`**：除非启用 §6b 的进阶项。
- **不跨重启保持**：见 §9。
- **依赖 Shizuku 运行中**：Shizuku 停止后，已生效的配置在下次系统重载 carrier config 前仍然保留，
  但无法再应用或还原。
- **`IccRecords` test-mode 标志**在还原后仍为 true，直到重启。由于所有覆盖值都已回填为真实值，
  这本身不产生行为差异。

## 11. 出问题时的排查信息

```bash
adb logcat -s Nrfr/Manager Nrfr/Privileged Nrfr/CarrierService Nrfr/Boot
```

失败时界面上的横幅会直接给出分类原因（shell 被拦截 / 缺少隐藏接口 / Shizuku 未授权等）。

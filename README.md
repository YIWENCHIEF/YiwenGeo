# YiwenGeo — Android 虚拟定位工具

基于 Android 官方 Test Provider（模拟位置）机制的开源虚拟定位应用。
在地图上选点，即可让手机上所有 App 读取到你指定的模拟 GPS 位置。

> ⚠️ **使用声明**：本程序仅供开发调试用途（如测试 App 的定位功能）。
> 严禁用于伪造打卡、欺骗奖励、绕过业务系统等非法活动；
> 因不当使用造成的一切后果由使用者自行承担。

## 功能特性

- **地图选点**：内置在线地图（境内高德瓦片 / 境外 OSM 自动切换），长按任意位置设为锚点
- **地址搜索**：接入 Photon 地理编码服务（免费无需 key），支持中文地名搜索
- **双 provider 注入**：同时模拟 `gps` 与 `network` 两个定位源，兼容优先读取
  network provider 或做交叉校验的 App
- **高频注入循环**：100ms 持续写入，运行于前台服务，熄屏/后台不中断
- **悬浮角标**：注入期间屏幕常驻"虚拟定位中"提示，防止无感知残留
- **历史记录**：注入点位自动存档（时间、坐标、反查地名、备注），点击可快速回填
- **定位自检**：显示 mock 授权状态与各 provider 最新读数，便于排查注入是否生效
- **坐标精度显示**：11 位小数经纬度标注

## 基本原理

Android 的 `LocationManager` 提供了一套公开的 **Test Provider API**：

```
用户: 开发者选项 → 选择模拟位置应用 → 选中本 App
        ▼  系统记录 AppOps: android:mock_location = allow (per-uid)
App:  addTestProvider("gps"/"network")     注册虚拟 provider
      setTestProviderEnabled(...)           启用
      循环 setTestProviderLocation(loc)     每 100ms 注入一个坐标
        ▼
system_server: 校验调用者 uid 的 mock_location AppOps，
               通过则把坐标当作对应 provider 的产出广播给所有监听者
        ▼
任意 App: requestLocationUpdates() 收到的即是模拟坐标
          （系统自动打标 isFromMockProvider() == true）
```

几个实现要点：

- **注入循环放在前台服务**（`foregroundServiceType="location"`），否则熄屏或后台冻结
  会导致注入中断、真实位置"穿透"；
- **双 provider**：不少 App 优先读 `network` provider 或做 GPS/network 交叉校验，
  只 mock `gps` 会被识破，因此两个 provider 同时注册、同时注入；
- **卫星特征补全**：部分定位 SDK 会校验 GPS 读数的卫星数与信噪比 extras
  （`satellites`、`maxCn0` 等），注入的 `Location` 携带典型良好值以通过校验；
- **坐标系转换**：高德瓦片使用 GCJ-02（国测局加密坐标），注入使用 WGS-84，
  应用内完成两套坐标系的互转（`CoordinateTransformer`）；
- **残留清理**：进程被强杀时 mock provider 注册可能残留并屏蔽真实定位，
  App 启动时主动 `removeTestProvider` 清理（`cleanupStale()`）。

## 使用方法

1. **安装**：下载最新 Release 的 APK 安装到手机。
2. **授权模拟位置**：打开手机 设置 → 开发者选项 → **选择模拟位置应用** → 选中 YiwenGeo。
   （若未开启开发者选项：设置 → 关于手机 → 连续点击"版本号"7 次）
3. **选点**：打开 App，等待地图定位到当前位置，长按地图任意位置放置锚点；
   也可用顶部搜索框直接搜索地名。
4. **注入**：点击"开始注入"。屏幕出现"虚拟定位中"角标、通知栏常驻通知，
   表示系统各 App 读到的定位已是锚点坐标。
5. **停止/回位**：点击"停止注入"恢复真实定位；"回位"按钮把地图视角飞回真实位置。
6. **排查**：若目标 App 仍显示旧位置，用 ⋮ 菜单中的"定位自检"查看各 provider
   是否带 [MOCK✓] 标记；部分 App 会主动检测 `isFromMockProvider` 标记拒用模拟位置，
   或走自建基站/WiFi 指纹定位，这类情况本工具无法覆盖。

## 构建

```bash
# 需要 JDK 17 与 Android SDK（compileSdk 35）
cp local.properties.example local.properties   # 修改 sdk.dir 为你本机 SDK 路径
./gradlew :app:assembleDebug
```

## 项目结构

```
app/src/main/java/com/yiwen/geo/
    MainActivity.kt          # Compose 地图主界面、锚点选点、注入控制
    MockLocationService.kt   # 前台服务：注入循环、悬浮角标、通知
    MockLocationEngine.kt    # Test Provider 注册/注入/清理封装
    CoordinateTransformer.kt # WGS-84 ↔ GCJ-02 坐标转换
    GlobalTileSource.kt      # 境内高德 / 境外 OSM 混合瓦片源
    GaodeTileSource.kt       # 高德瓦片源（免 key）
    PhotonSearch.kt          # Photon 地理编码搜索（免 key）
    GeoInterpolator.kt       # 球面坐标插值
    HistoryStore.kt          # 历史记录（SharedPreferences + JSON）
    MyLocationOverlay.kt     # 蓝点 + 误差圈覆盖层
gen_icons.py                 # 应用图标生成脚本（Pillow）
```

## License

本项目以 [MIT License](LICENSE) 开源，仅供开发调试用途。
使用者须自行确保不将其用于伪造打卡、欺骗奖励等违法或违约场景，
由此产生的一切后果由使用者承担。

## Releases

推送 `v*` 标签（如 `v3.7`）会触发 GitHub Actions 自动构建，
并在 Releases 中发布名为 `YiwenGeo-v3.7.apk` 的安装包。

# MYTT 选股器 · 安卓 App(纯原生 APK)

一个**在手机上本地跑选股**的原生安卓 App，不依赖任何服务器。输入一只股票的 6 位代码，即可算出该股最新的 **万手单 / 寻龙诀4号 / 综合**信号。

把通达信的 MYTT 选股公式用 **Kotlin 原生重写**，已用 9月23号 真实数据验证与 Python 版结果完全一致。



## 已为你打包好的 APK

```
mytt_android/MYTT选股器-debug.apk      ← 直接传到手机安装即可
```

把这 个 APK 文件发到手机上(微信/QQ/蓝牙), 点开安装。安装时如提示"未知来源/允许安装未知应用"，按提示允许即可(这是正常的安全提示, 因为这不是应用商店渠道下的 App)。安装后图标名【MYTT选股器】。



## 手机使用方法

1. 打开 App
2. 输入 6 位股票代码, 如 `600519` (贵州茅台`、`002635` (安洁科技`
3. 点【查询信号】
4. 等待几秒(需联网拉行情), 显示:
   - 数据日期 / 收盘价 / 当日涨幅
   - 万手单: ✓命中 / — 未命中
   - 寻龙诀4号: ✓命中 / — 未命中
   - 综合信号: ✓✓ 双信号命中(买入关注) / 单边命中 / 无信号

> 数据来源: 新浪免费行情(无需任何账号/密钥)。联网即可, 免费、无限次(相对)。



## 想自己重新打包(可选)

你有电脑装有 **Android Studio**时可以自行修改后打包。用命令行也能打:

```bash
cd mytt_android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```
打包产物在: `mytt_android/app/build/outputs/apk/debug/app-debug.apk`。

> 首次打包会联网下载依赖并需 Android SDK(你机器上已装), 稍慢属正常。





## 当前版本说明与后续可扩展点

- **当前**: 单只股票信号查询(快、稳、不依赖服务器)。
- **可扩展**: 下一步可加"全市场涨停池批量选股"(类似电脑版 select_date.py), 把选股结果列成清单; 或加定时通知。你有 Android Studio 经验或愿意再投入, 我可以继续做。



## 关键文件

```
mytt_android/
├── settings.gradle / build.gradle / gradle.properties / local.properties
├── gradlew  +  gradle/wrapper/(wrapper 打包用)
├── app/build.gradle              应用构建配置
└── app/src/main/
    ├── AndroidManifest.xml      清单(含联网权限)
    ├── java/com/mytt/selector/
    │   ├── Talc.kt            通达信函数库(Kotlin)
    │   ├── Mytt.kt           万手单+寻龙诀4号算法
    │   ├── SinaClient.kt     新浪行情拉取
    │   └── MainActivity.kt     界面
    └── res/                    布局/主题/文案
```

> 注: 使用**不复权**行情(与短线涨停/限价判断更贴合)。复权偏好如需改动, 告诉我即可调整起来
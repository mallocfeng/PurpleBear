# PurpleBear Android

这是 PurpleBear 的原生 Android 工程，可以直接用 Android Studio 打开。

## 环境要求

- Android Studio Hedgehog 或更新版本
- JDK 17
- Android SDK 35
- arm64-v8a Android 设备

## 构建

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Release 包默认输出到：

```text
app/build/outputs/apk/release/app-release.apk
```

Release 签名读取本地 `keystore.properties`，该文件不进入版本库。没有签名配置时，请使用 Debug 构建进行开发调试。

## 说明

应用通过 Android `VpnService` 接入 Xray 能力。仓库保留应用工程所需的预编译运行依赖和 Geo 数据，不包含 Xray-core 完整源码。Xray-core 源码请访问：

https://github.com/XTLS/Xray-core

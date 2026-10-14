# 动作唤醒闹钟 (ActionAlarm) — Android Studio 原生工程

本项目是一套完整的 **Android 原生动作视觉识别闹钟（Kotlin + Jetpack Compose + CameraX + Google ML Kit Pose Detection）** 源码工程。

## 一、核心功能特性

1. **标准闹钟管理与持久化**
   - 支持添加、编辑、删除、启停多个闹钟（采用 `AlarmManager.setAlarmClock` 高优先级精确唤醒 + `BootReceiver` 开机自动恢复）。
   - 闹钟响铃时自动通过 `AlarmService` 前台服务循环播放系统闹钟铃声与震动，并在锁屏状态下全屏弹出动作挑战界面（屏蔽返回键）。
2. **四大动作识别（离线骨骼关键点检测）**
   - **鼓掌 (`CLAPPING`)**：基于左右手腕距离与肩宽比值，识别“双手张开 → 合拢击掌”完整周期。
   - **深蹲 (`SQUAT`)**：基于髋-膝-踝三点夹角，识别“直立 (>150°) → 下蹲 (<118°) → 站直 (>150°)”完整周期。
   - **持续站立 (`STANDING`)**：实时检测全身关键点（肩、髋、膝、踝）在画面中保持直立站姿（双膝夹角 ≥ 152° 且躯干直立）。
   - **抬腿跳 (`HIGH_KNEES`)**：检测左右膝盖交替高抬至靠近髋部高度并落地的跳跃周期。
3. **四个时长档位与【连续计时 + 每5秒至少完成1次】机制**
   - 支持 **5秒 (`5s`)、15秒 (`15s`)、1分钟 (`1min`)、5分钟 (`5min`)** 四个档位。
   - **动态动作（鼓掌、深蹲、抬腿跳）**：用户完成第 1 次有效动作后开启连续计时，每完成 1 次新动作会将 5.0 秒保活倒计时刷新回 5.0 秒；若连续 5 秒内未完成至少 1 次有效动作（或离开镜头），已累计时间与次数立即**归零重置**。
   - **静态动作（持续站立）**：保持标准站姿期间连续计时；若离开镜头或蹲下/坐下超过 1.0 秒，计时立即**归零重置**。
   - 主界面每个闹钟卡片均提供 **“演练动作”** 按钮，无需等待闹钟到点即可随时测试相机骨骼识别与连续计时效果。

## 二、如何一键生成 APK 安装包

### 方式 1：使用 Android Studio 本地编译（推荐，约 2 分钟）
1. 解压 `ActionAlarm_AndroidStudio_Project.zip`。
2. 打开 **Android Studio**（建议使用 Hedgehog / Iguana / Jellyfish 或更新版本，内置 JDK 17）。
3. 点击 **Open**，选择解压后的 `ActionAlarm` 文件夹，等待 Gradle 自动同步依赖（首次同步会自动下载 CameraX 与 ML Kit 离线姿态模型依赖）。
4. 在顶部菜单栏依次点击：**Build → Build Bundle(s) / APK(s) → Build APK(s)**。
5. 编译完成后点击右下角弹窗中的 **locate**，即可在 `app/build/outputs/apk/debug/app-debug.apk` 获取安装包并安装到安卓手机。

### 方式 2：无需本地安装开发环境，使用 GitHub Actions 云端自动编译 APK
工程已内置 `.github/workflows/build-apk.yml` 云端自动打包脚本：
1. 在 GitHub 创建一个新仓库（公开或私有均可），将解压后的工程文件上传推送到仓库。
2. 进入仓库顶部的 **Actions** 标签页，选择 **Build ActionAlarm APK**，点击 **Run workflow**。
3. 等待约 2~3 分钟编译完成后，在任务底部的 **Artifacts** 区域即可直接下载编译好的 `ActionAlarm-debug-apk.zip`（内含 `app-debug.apk`）。

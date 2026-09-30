# Studying with friend（读书搭子）

安卓平板读书陪伴 App：像朋友一样陪你读书——该讲的地方用大白话讲，读完一章帮你总结、画导图、教你怎么把知识串起来记。

## 技术栈

Kotlin 2.0.21 + Jetpack Compose (Material3) + Room 2.6.1 + Navigation Compose，AGP 8.7.3，Gradle 8.9，minSdk 26 / targetSdk 35。

## 本机构建（Windows）

JDK 17 在 `C:\Program Files\Java\jdk-17.0.20.1+1`，Android SDK 在标准位置 `C:\Users\jingyue\AppData\Local\Android\Sdk`（含模拟器与系统镜像）。用户环境变量 `JAVA_HOME` / `ANDROID_HOME` 已配，`java` / `adb` 已在 PATH（新开终端生效）。工作区根目录 `AGENTS.md` 有完整环境说明。

```bash
cd android
gradlew.bat assembleDebug --no-daemon
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`

- 单元测试：`gradlew.bat testDebugUnitTest --no-daemon`
- release 包：`gradlew.bat assembleRelease --no-daemon`
- SDK 路径：`C:\Users\jingyue\AppData\Local\Android\Sdk`（local.properties 已写）；JDK 路径在 `gradle.properties` 的 `org.gradle.java.home`
- 注意：GRADLE_USER_HOME 必须指向英文路径（本机默认 `C:\Users\jingyue\.gradle` 即可；中文用户路径会让测试 worker 类加载失败）

## 低内存机器注意事项

`gradle.properties` 已按小内存机器调优（daemon 1.5G / Kotlin 1G / 单线程 / worker≤2）。构建建议加 `--no-daemon`（跑完即释放内存）。

## 安装与使用（平板）

1. 把 `app-debug.apk` 传到平板，直接点开安装（首次需允许"安装未知来源应用"）。
2. 首次进入先到 **设置** 页：填 API 地址（OpenAI 兼容，一般以 /v1 结尾）、模型名、API Key，点"测试连接"确认通了。
3. 回书架 → **导入**：选 txt/PDF 或直接粘贴文本 → 在目录确认页检查章节切分（可改名、可用自定义标题正则重切）。
4. 点开一本书进 **章目录**，选一章进阅读页：
   - 跑"粗读"：搭子先通读一章，标出 ★ 值得讲、合并讲、跳过的段落（不段段都讲）；
   - 标完点"讲解本节"：讲解卡以朋友口吻出现（讲什么/大白话/生活类比/关键点/记忆钩子/自检小问）；
   - 顶栏箭头可切上一章/下一章；正文 16sp 宽行距，重点段有主题色竖条。
5. 读完点 **章末总结 · 导图 · 自测**：总结（流式）/ 思维导图（可缩放、可导出 PNG）/ 记忆思路（可朗读）/ 知识串联 / 自测题（可作答对答案）；右上"导出"可把整包存成 Markdown。
6. 总结过 ≥2 章后，书详情页出现 **全书总览**：跨章演进 + 全书总导图 + 一条记忆主线。
7. 底栏 **复习中心**：章末自测答错的进错题本；总结过的章按 1/3/7/14/30 天排期，首页书卡片会显示"待复习"。

**讲解详略**：设置页可选 简略/标准/深入 三档，即选即存，影响之后生成的讲解长度与举例密度。

## 文档

- 总计划：`docs/plans/00-总计划.md`
- 里程碑计划案与评审记录：`docs/plans/`
- 模块总览：`docs/模块总览/`

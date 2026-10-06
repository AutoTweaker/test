# AGENTS.md

This file provides guidance to AutoTweaker when working with code in this repository.

## 这是什么

AutoTweaker/core 的测试仓库：只有单元测试与集成测试，没有生产代码。

被测实现全部来自 core，通过 core 导出的**运行时产物**（jar 集合）引入，不是 Gradle 项目依赖——所以测试跑的永远是 core main 的最新产物，而不是某个发布版本。

## 项目结构

`src/test/kotlin/` 下没有第二套组织方式：**测试放在被测类同名的包路径下，类名加 `Test` 后缀**。找某个类的测试、或者给某个类补测试，按包名定位即可。

```
io/github/autotweaker/
├── api/            api 模块的类型与序列化
├── core/           core 模块，主体
│   ├── domain/          agent 引擎（runner / think / tool / compact）、会话、chat、工具实现
│   ├── infrastructure/  持久化（h2 / json / migrate）、LLM provider、容器与路径、i18n
│   └── adapter/         i18n 翻译管线
├── adapter/cli/    cli-adapter 模块（ArgParser / CommandRouter / SyntaxValidator）
├── core/test/      测试基础设施：TestServices、PluginLoaderTest
└── pluginprobe/    仓库自带的探针插件，只给 PluginLoaderTest 当被加载对象
```

`core/test/` 和 `pluginprobe/` 是仅有的两处不跟着被测包走的地方。`src/test/resources/` 下只有 `logback-test.xml`。

## 跑测试

必须指定 core 的产物目录，否则配置阶段直接报错：

```bash
./gradlew test -Pautotweaker.runtime="<core>/core/build/runtime:<core>/cli-adapter/build/runtime"
```

也可以走环境变量 `AUTOTWEAKER_RUNTIME`；多个目录用 `:` 分隔。产物在 core 仓库导出：

```bash
./gradlew :autotweaker-core:exportRuntime :cli-adapter:exportRuntime
```

跑单个测试类或单个方法：

```bash
./gradlew test --tests 'io.github.autotweaker.core.domain.chat.ResilientChatTest'
./gradlew test --tests '*ResilientChatTest.FALLBACK*'
```

覆盖率（jacoco 已配好，`classDirectories` 指向产物目录里的 `autotweaker-*.jar`）：

```bash
./gradlew test jacocoTestReport -Pautotweaker.runtime="..."
```

报告在 `build/reports/jacoco/test/jacocoTestReport.xml`，`<package>` 节点自带包级 `LINE` counter（covered / missed），不用自己按类聚合。

## 测试基础设施

- **`TestServices.init()`** —— 统一的测试引导：起 Koin、装 `ServiceRegistry`。绝大多数测试在 `companion object { init { TestServices.init() } }` 里调一次。它用 mockk 顶掉持久化与追踪，`SecretStore` 走内存实现；需要 `MessageBuilder` 这类对象时从 `TestServices` 取
- **插件夹具** —— `prepareTestPlugins` 在构建期造一个假插件（`test.fixture`）并把 `AUTOTWEAKER_INSTALL_PATH` 指向它。core 的 `PluginLoader` 在没有有效插件时会直接退出进程，没有这个夹具整个测试 JVM 起不来
- 测试日志由 `src/test/resources/logback-test.xml` 控制

## 约定

- 框架：`kotlin.test` 断言 + JUnit 5（`useJUnitPlatform`）；协程测试用 `kotlinx-coroutines-test` 的 `runTest`；mock 用 mockk
- 测试函数名用反引号包住，**内容只能是 ASCII**——JVM class 文件编码限制，写中文会编译失败
- 每个文件带 GPL 版权头，缩进用 tab
- 断言盯住具体的值或可观察的行为，不要只调用不断言

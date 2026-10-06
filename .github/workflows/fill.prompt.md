# 测试补充

你在 AutoTweaker/test 仓库的 CI 中运行。你的任务是为覆盖率不足的地方补测试。无人可交互，不要征求确认，自主完成任务。

## 环境

- 工作目录就是 test 仓库根目录，你有它的完整 git 历史
- core 仓库源码在 `.core/`，含完整 git 历史，禁止修改
- core 的运行时产物已经导出好了（`.core/core/build/runtime` 与 `.core/cli-adapter/build/runtime`），跑测试直接引用，不要重新构建 core
- `.autotweaker/` 目录已建好，issue 文件写在那里
- git 身份已配置好（`user.name` / `user.email`），不要改动 git 配置
- 环境变量 `GH_TOKEN` 已就绪，`gh` 会自动读取它；不要运行认证、登录或鉴权检查类命令（`gh auth login`、`gh auth status` 之类），也不要给命令手动附加 token 参数
- 这个 token 是只读的：你没有写权限，不要尝试创建或修改任何 GitHub 资源

## 约束

- 不要访问 `.github/`，更不要写入或更新
- 你只新增测试，不要改动被测代码，也不要顺手重构别人的测试

## 跑覆盖率

```bash
./gradlew test jacocoTestReport -Pautotweaker.runtime="$PWD/.core/core/build/runtime:$PWD/.core/cli-adapter/build/runtime"
```

报告在 `build/reports/jacoco/test/jacocoTestReport.xml`。里面的 `<package>` 节点带包级的 `LINE` counter，`covered / (covered + missed)` 就是行覆盖率。

（`test` 任务没有输入变化时会跳过重跑、直接复用已有的执行数据，所以这条命令通常很快。）

## 挑一个包

在行覆盖率**低于 50%** 的包里挑**最值得测试的 1 个**，只做这一个。包很大就优先覆盖它最核心的类，不必求全。如果全部包都高于 50% 就找覆盖率最低的。

排序参考：

- 核心业务逻辑 > 工具函数 > 数据类 / 枚举 / 生成的代码
- 被别处引用得多的 > 孤立的
- 有分支、有边界、有错误路径的 > 纯转发、纯 getter
- 已经有测试的包可以接着补；完全没测试的，优先挑体量适中的

读 `.core/` 里对应的源码，看懂它到底在做什么，再决定测什么。

## 测试怎么写

**该这样写**

- 用 `kotlin.test`，跟同包下已有测试保持一致（`TestServices.init()`、构造方式照着现有的抄）
- 一条测试只验一件事，名字说清"什么条件下 → 什么结果"
- 断言盯住具体的值：`assertEquals(3, result.size)`，而不是 `assertTrue(result.isNotEmpty())`
- 异常用 `assertFailsWith<E> { }`
- 优先覆盖边界和失败路径：空集合、单个元素、越界、非法状态、并发冲突、失败后状态有没有回滚
- 时间、随机、UUID 这类不确定输入，要么注入固定值，要么断言"关系"而不是"具体值"
- 写完自己反推一遍：**把这段实现改坏，这条测试会红吗？**不会就说明它什么都没测到

**不要这样写**

- 只调用不断言——把方法挨个跑一遍，覆盖率很好看，一个断言都没有
- 恒真断言：`assertTrue(true)`、把 `assertNotNull(x)` 当唯一断言
- `@Disabled`、注释掉、用 `println` 代替断言
- `Thread.sleep` 等异步
- 依赖真实时钟、随机数、网络、外部服务
- mock 掉被测对象自己（那样测的是 mock，不是实现）
- 断言 private 字段这类内部实现细节
- 同一个行为写好几条几乎一样的测试
- 测试枚举类、没有成员方法的数据类（不是给 kotlin 写测试）

## 收尾（必须完成）

1. **重跑全量测试**，确认没有弄坏原有的测试。

2. **提交**：把新增的测试 commit（不要 push，不要动 `.core/`）。没有 commit 的改动会在 CI 结束时丢弃。

   commit message 用中文，写明补的是哪个包的什么行为。**不要用 `git commit -m "..."`**：消息里会带反引号、`$`、引号这类字符，塞进命令行会被 shell 先解析一遍。用带引号的 heredoc 从 stdin 读：

   ```bash
   git commit -F - <<'EOF'
   标题

   正文
   EOF
   ```

3. **新测试挂了，先怀疑自己**：读 `.core/` 里那段实现，判断到底是哪种情况。

   - **测试写错了**（更常见）：期望值拍错、误解了 API 语义、setup 缺东西、引入了不确定性。→ 改测试，让它对 core 的正确行为成立。
   - **core 的 bug**：你能具体指出实现错在哪里（逻辑错误、边界条件处理错、该抛的异常没抛、与同模块其它地方的约定矛盾、与它自己的文档矛盾）。→ 把这条测试提交上去，让它在仓库里红着；修复是 core 那边的事，你不改被测代码、也不删这条测试，然后按下面写 issue。

   说不出错在哪就不是 bug，按前一种处理。

4. **issue 操作**：只有发现 core 的 bug 时才做。先查重：

   ```bash
   gh issue list --repo AutoTweaker/core --state open
   ```

   自己在里面找有没有已经在说同一件事的，光看标题不够就翻正文。

   - 已有对应 issue → 有更多发现才追加 comment，否则什么都不做
   - 没有 → 新建

   写成两个文件（都已在 .gitignore 中），不要作多余的探测，直接通过 write 工具创建：

   - `.autotweaker/issue.md`：正文，纯 markdown 原样写，不要做任何转义。写清是哪条测试（文件路径 + 测试名）、期望行为与实际行为、判断依据（core 的哪段代码）。把那条测试的代码原样贴进去，方便 core 修完直接对照
   - `.autotweaker/issue.json`：元数据，二选一：

   ```json
   {"action":"create","title":"..."}
   ```

   ```json
   {"action":"comment","issue":123}
   ```

   没有发现 core 的 bug 时，不要写这两个文件。

最后输出一段话总结：挑了哪个包、为什么挑它、补了哪些行为、这个包的覆盖率从多少变成多少。

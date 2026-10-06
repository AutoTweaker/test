# 测试维护

你在 AutoTweaker/test 仓库的 CI 中运行。core 有了新提交，这里的测试失败了。你的任务是判断失败原因并作出处置。无人可交互，不要征求确认，自主决策到底。

## 环境

- 工作目录就是 test 仓库根目录，你有它的完整 git 历史
- core 仓库源码在 `.core/`，含完整 git 历史，禁止修改
- core 的运行时产物已经导出好了（`.core/core/build/runtime` 与 `.core/cli-adapter/build/runtime`），跑测试直接引用，不要重新构建 core
- `.autotweaker/` 目录已建好，issue 文件写在那里
- git 身份已配置好（`user.name` / `user.email`），直接 `git commit -m "..."` 即可，不要改动 git 配置
- 测试报告在 `build/test-results/test/*.xml` 与 `build/reports/tests/test/`
- 跑测试必须带产物参数：

  ```
  ./gradlew test -Pautotweaker.runtime="$PWD/.core/core/build/runtime:$PWD/.core/cli-adapter/build/runtime"
  ```

- 环境变量 `GH_TOKEN` 已就绪，`gh` 会自动读取它，直接用 `gh issue list --repo AutoTweaker/core --search "<关键词>"` 查询即可；不要运行认证、登录或鉴权检查类命令（`gh auth login`、`gh auth status` 之类），也不要给命令手动附加 token 参数
- 这个 token 是只读的：你没有写权限，不要尝试创建或修改任何 GitHub 资源

## 判定

测试失败只有两种可能：

1. **测试过期**（绝大多数情况）：core 改变了行为、接口或数据结构，测试的期望值没跟上，而 core 的新行为本身是合理自洽的。→ 修改测试代码，让它对新的正确行为成立。
2. **core 有 bug**：core 的实现本身就是错的。→ 不要改测试，让它保持失败，按下面"收尾"的方式报告。

判定必须建立在对 core 代码的分析上：从失败的测试出发，找到它对应的 core 实现，把那部分代码读懂，再下结论。**没有代码层面的证据，不要下 bug 结论。**

- 只有当你**能具体指出实现错在哪里**（逻辑错误、边界条件处理错、该抛的异常没抛、与同模块其它地方的约定矛盾、与它自己的文档矛盾），才算 bug
- 说不出实现错在哪，就按测试过期处理，并在最后的总结里说明依据与不确定之处
- git 历史只用来理解某段代码为什么长这样（`git -C .core log -p -- <文件>`），不能当作行为对错的依据——提交信息只说明作者想干什么，不说明他干对了；没被改过的老代码也可能一直是错的

## 修测试的约束

- 优先做最小适配：更新期望值、适配新的 API 形态、调整构造参数
- core 移除了对应的类、方法或功能时，删除测它的测试（测试方法或整个文件）是正确做法。删之前先在 `.core/` 里确认那东西确实不存在了，并在 commit message 里写明
- 禁止加 `@Disabled`、把断言弱化成恒真、用 try-catch 吞掉异常——这些让测试"变绿"，不是让它"变对"。功能没了就删测试，功能还在就不该禁用
- 改完必须重跑全量测试，确认全绿

## 收尾（必须完成）

1. **提交**：把改动 commit（`git add` + `git commit`，不要 push，不要动 `.core/`）。没有 commit 的改动会在 CI 结束时丢弃。commit message 用中文，说明适配了 core 的哪个改动。

2. **issue 操作**：只有判定为 core bug 时才做。先查重：

   ```
   gh issue list --repo AutoTweaker/core --state open --search "<关键词>"
   ```

   - 已有对应 issue → 追加 comment
   - 没有 → 新建

   写成两个文件（都已在 .gitignore 中）：

   - `.autotweaker/issue.md`：正文，纯 markdown 原样写，不要做任何转义。写清哪个测试失败、期望行为与实际行为、判断依据（core 的哪次提交、哪段代码）
   - `.autotweaker/issue.json`：元数据，二选一：

   ```json
   {"action":"create","title":"..."}
   ```

   ```json
   {"action":"comment","issue":123}
   ```

   判定为测试过期时两个文件都不要写。

最后输出一段话总结你的判断与处置。

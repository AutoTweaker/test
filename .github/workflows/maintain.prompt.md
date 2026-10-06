# 流程自测

本轮不是常规维护，而是验证自动化链路本身。本次的测试失败是人为制造的，不用理会、也不用修复。

1. 写 `.autotweaker/issue.md`，正文只写一行测试消息
2. 写 `.autotweaker/issue.json`：`{"action":"create","title":"测试：维护流程 issue 链路"}`
3. 全面测试当前环境里的 GitHub 访问能力。具体用什么命令、测哪些用例，由你自己设计和决定，至少覆盖这些方面：
   - 读取公开仓库（AutoTweaker/core）的内容：issue、PR、代码、release 等
   - 读取本仓库（AutoTweaker/test）的 issue
   - 写操作是否被允许：在本仓库创建 issue、在 core 创建 issue、给已有 issue 追加 comment、关闭 issue 等
   - 当前 gh 的认证来源：token 从哪来、带什么 scope

   把每条探测的原始输出和你的结论写进最后的总结，说明哪些能做、哪些被拒、依据是什么。

不要修改任何代码，不要 commit。最后输出一段总结。

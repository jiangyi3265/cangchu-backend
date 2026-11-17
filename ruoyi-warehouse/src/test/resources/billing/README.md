# 计费回归样本

`legacy-golden.json` 保存 122 组原版计费逻辑生成的合成输入与预期结果，不含客户数据或真实报价。
`BillingGoldenTest` 直接读取这个固定文件并调用 Java 计算器，Maven 测试不需要 Node.js。

旧 Node 系统移除时，一并移除了依赖旧 `services/billing.mjs` 的 JavaScript 样本生成器。
JSON 内的 `source` 路径和源码 SHA-256 是历史来源记录，不是当前依赖；原生成器和对应源码可在
[清理前的提交](https://github.com/jiangyi3265/cangchu-warehouse/tree/8d90a819f25a473709934fee61130e173ce94f3d) 中核对。

修改计费规则时应增加独立核算的 Java 测试，不应按新实现批量重写这些预期值。

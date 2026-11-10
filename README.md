# mycode

一个**从零手写**的命令行 AI 编程助手。除 JSON 序列化（Jackson）外不引入任何框架依赖，
目的是让人看清一个 agent 到底由哪些零件组成，而不是再学一套框架 API。

![JDK](https://img.shields.io/badge/JDK-17-blue) ![License](https://img.shields.io/badge/License-MIT-green) ![依赖](https://img.shields.io/badge/运行时依赖-仅_Jackson-orange)

```text
 你（终端）
   │ 提问 / 斜杠命令
   ▼
 Shell ──会话──► Agent（ReAct 主循环）
                   │  ① 组装上下文（历史 + 工具说明，超长自动压缩）
                   │  ② 调用模型（OpenAI 兼容 / Anthropic 双协议）
                   │  ③ 模型点名工具 → ToolRegistry 分发执行
                   │  ④ 结果回填，循环直到模型给出最终回答
                   ▼
      工具集：联网搜索 / 抓网页 / 改代码 / 跑命令 /
              待办清单 / 子代理 / MCP / Agent Skills …
```

## 功能特性

**对话与主循环**

- 交互式会话 + 单次提问两种主用法，支持打字机式流式输出
- 标准 ReAct 工具调用循环：模型决定用什么工具，内核负责安全分发与结果回填
- 上下文自动压缩：超过阈值把最老的对话压成摘要，长会话不爆上下文
- 启动自动恢复上次会话；一轮多个工具调用可并行执行

**内置工具**

| 工具 | 说明 |
| --- | --- |
| 联网搜索 | Tavily / 博查（Bocha），可切换供应商，附 Mock 供应商用于离线演示 |
| 抓取网页 | 把指定 URL 的正文抓进上下文 |
| 代码编辑 | 定位读取、精准替换、Java 语法校验，改完自动留备份，`/undo` 一键回滚 |
| 命令执行 | 带权限策略（白名单 / 每次询问）的 shell 命令执行 |
| 待办清单 | 长任务拆步骤、逐项勾选，进度模型自己维护 |
| 子代理（task） | 主代理把可拆的长任务派给子代理独立跑，只收回最终结论；定义与 Claude Code 的 agents 目录通用 |
| MCP 外部工具 | 配置 `.mycode/mcp.json` 即可接入任意 MCP 服务器，格式与 Claude Code 一致 |
| Agent Skills | 加载 Claude Code 格式的技能目录（SKILL.md），可驱动 docx / xlsx / pptx 等技能脚本 |
| 仓库地图 / 项目记忆 | 给模型一个「项目长什么样」的索引和跨会话的项目笔记 |

**可靠性与成本**

- 模型调用瞬时失败（429 / 5xx / 超时）自动重试，指数退避
- 编辑成功后可配置自动验证命令，报错直接喂回模型修
- `/cost` 统计 token 消耗：识别服务商前缀缓存命中，按填写的单价折算金额
- 会话存档可回看（`/history`），历史修改可回滚（`/undo`）

## 快速开始

环境要求：**JDK 17+**、**Maven 3.8+**。

```bash
# 1. 构建单文件 jar
mvn package -DskipTests

# 2. 配置模型（agent.properties 已被 gitignore，密钥不会被提交）
cp agent.properties.example agent.properties
#    编辑 agent.properties：填 provider / baseUrl / apiKey / model

# 3. 运行
./mycode                  # Linux / macOS / Git Bash，交互模式
mycode.bat                # Windows
./mycode "看看 Agent.java 的主循环"   # 单次提问
./mycode --demo           # 离线演示：假模型跑完整工具循环，不联网不花钱
./mycode --help           # 全部命令
```

### 演示与调试入口

| 命令 | 作用 |
| --- | --- |
| `--demo` | 离线演示，不联网不花钱 |
| `--code-demo` | 代码编辑演示：读文件 → 替换 → 语法校验 → 出差异 |
| `--search` | 联网搜索演示（加 `--real` 才真联网） |
| `--mock` | 早期门票演示：查景点 → 查天气 → 算门票，完整 ReAct 循环 |
| `--dump` | 打印 OpenAI 与 Anthropic 两种协议的真实报文 |
| `--config` | 查看当前生效的模型配置（不发请求） |

## 交互命令

```text
/help  /clear  /lang      帮助、清屏、切换回答语言（中/英）
/tools /files /skills     查看已加载的工具、可见文件、技能
/agents /memory /todo     查看子代理定义、项目记忆、待办清单
/model /permission /cost  当前模型、权限模式、token 与费用统计
/compress /undo /history  手动压缩上下文、回滚修改、回看会话存档
/exit                     退出（/quit /q 同义）
```

## 配置

配置文件为项目根目录的 `agent.properties`，优先级：**命令行参数 > 环境变量 > 配置文件 > 内置预设**。
支持的服务商预设：`dashscope / openai / deepseek / moonshot / zhipu / ollama / anthropic / custom`，
其余项（回答语言、界面宽度、压缩阈值、重试次数、子代理开关、单价等）见
[agent.properties.example](agent.properties.example) 的逐项中文注释。

## 项目结构

`src/main/java/mycode` 按「内核 → 客户端 → 工具 → 外围」分层，每个文件的类头注释都写清了它在整体中的位置：

| 分层 | 主要文件 | 说明 |
| --- | --- | --- |
| 内核部件 1：工具 | `Tool`、`SimpleTool`、`ToolRegistry`、`ToolCall` | 工具接口与注册表；`parameters()` 刻意返回普通 Map，不绑定 JSON 库 |
| 内核部件 2：上下文 | `AgentContext`、`ContextCompressor` | 会话状态与超长压缩 |
| 内核部件 3：主循环 | `Agent`、`ChatSession`、`ChatResult`、`Usage` | ReAct for 循环：整个 agent 的本质 |
| 内核部件 4：钩子 | `Hook`、`PermissionHook`、`ConsoleHook`、`LoggingHook` | 在工具调用前后做拦截：权限审批、界面回显、日志 |
| 模型客户端 | `LlmClient`、`OpenAiCompatibleClient`、`AnthropicClient`、`LlmFactory`、`SseAccumulator`、`Retries` | 双协议实现 + SSE 流式解析 + 自动重试 |
| 内置工具 | `CommandTool`、`CodeTools` / `CodeEditor`、`WebSearchTool`、`FetchPageTool`、`TodoTool`、`SubAgentTool`、`RepoMapTool`、`ProjectMemory`、`TaskOutputTool`、`Calculator` | 每个工具一个类 |
| 扩展接入 | `McpClient` / `McpTool`、`Skill` / `SkillRegistry` / `SkillTool`、`AgentRegistry` / `AgentDefinition` | MCP 服务器、Agent Skills、子代理定义的加载 |
| 外围 | `Shell`、`Main`、`Ui`、`SessionStore`、`BackupStore`、`AgentConfig`、`Costs`、`Language`、各 Demo | 交互界面、启动分发、存档、配置、演示入口 |

`samples/TicketService.java` 是代码编辑能力的演示靶子；`scripts/`、`tools/`、`.venv/` 是技能脚本及其运行环境。

## 设计原则

1. **零框架依赖**：除 Jackson（仅用于 JSON 报文）外不用任何框架，每个零件都能独立读懂
2. **内核不绑定协议**：内核层看不到 OpenAI / Anthropic 的类型，换模型供应商只动客户端层
3. **工具说明即提示词**：工具的 `description()` 直接决定模型调用准确率，代码里逐个写了写法要点
4. **安全默认**：命令执行有权限策略，代码修改自动备份可回滚，密钥文件永远不进 git

## 作者

本项目由 **my** 与 AI 助手（ZCode 上的 GLM）**共同完成**：
人负责选题、架构设计与每一步取舍，AI 负责把想法落成代码与中文注释。

## 许可证

[MIT](LICENSE) © 2026 my

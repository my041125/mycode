package mycode;

import java.nio.file.Path;
import java.util.List;

/**
 * 一个技能（Skill）。
 *
 * 沿用 Claude Code 的 Agent Skills 格式：一个目录 + SKILL.md，
 * SKILL.md 由「YAML frontmatter」和「markdown 正文」两部分组成：
 *
 *   ---
 *   name: docx
 *   description: 什么时候该用这个技能（决定能否被自动发现）
 *   allowed-tools: Read, Write, Edit
 *   ---
 *   正文：具体怎么做，可以引用同目录下的 scripts/、references/
 *
 * 设计上只保留真正需要的字段，其余（model / effort / maxTurns）解析但不使用 ——
 * 我们的客户端没有多模型路由，也没法按技能切换 effort。
 *
 * @param name        技能名，唯一标识，也是 use_skill 的参数
 * @param description 描述，启动时注入 system 供模型判断相关性（会被截断）
 * @param dir         技能目录，模型跑脚本时需要它来拼路径
 * @param body        SKILL.md 的正文（不含 frontmatter），激活时才加载
 * @param allowedTools 声明的工具白名单，用于提示可用范围；为空表示不限制
 * @param source      来源标签，例如「个人」「项目」，让用户知道技能从哪来
 * @author my
 */
public record Skill(String name, String description, Path dir, String body,
                    List<String> allowedTools, String source) {
}

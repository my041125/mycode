package mycode;

import java.util.Locale;

/**
 * 界面与模型回答的语言。
 *
 * 分成两层来理解：
 *   1. 界面文字（横幅、提示、错误）—— 本身就是中文硬编码，不随这个枚举变化；
 *   2. 模型回答的语言 —— 由这个枚举决定，附加到 system 提示词里。
 *
 * 为什么要单独做：模型有个通病，工具返回英文（git 输出、编译器报错、英文注释）时，
 * 它会顺着英文继续用英文回答。只在提示词里写「用中文」不够，得写清楚
 * 「即使上下文是英文，回答也必须是中文」，否则中文客户端会出现中英混杂。
 *
 * 代码标识符、命令输出本身保持原样 —— 那是事实数据，翻译反而会误导。
 */
public enum Language {

    /** 简体中文（默认） */
    ZH("zh", "简体中文", """
            语言要求（最高优先级）：
            - 你的所有回答一律使用简体中文；
            - 即使工具返回的是英文（git 输出、编译器报错、英文注释），也要用中文转述和解释；
            - 代码、命令、标识符、文件名保持原样，不要翻译；
            - 引用英文报错时可以先原文引用一句，紧接着用中文说明含义。
            """),

    /** 英文，给需要英文输出时用 */
    EN("en", "English", """
            Language requirement (highest priority):
            - Always reply in English, even if tool output is in Chinese;
            - Keep code, commands, identifiers and file names unchanged;
            - Briefly quote an error in its original language, then explain in English.
            """);

    /** 配置里用的值，例如 language=zh */
    public final String code;

    /** 人类可读的名字，横幅里展示 */
    public final String label;

    /** 追加到 system 提示词末尾的语言约束 */
    public final String hint;

    Language(String code, String label, String hint) {
        this.code = code;
        this.label = label;
        this.hint = hint;
    }

    /**
     * 解析语言，非法值回退到中文。
     * 客户端默认中文，所以任何解析失败都按中文处理，而不是报错中断启动。
     */
    public static Language parse(String value) {
        if (value == null || value.isBlank()) return ZH;
        String v = value.strip().toLowerCase(Locale.ROOT);
        for (Language l : values()) {
            if (l.code.equals(v)) return l;
        }
        return ZH;
    }

    /** 从配置文件读，缺省中文 */
    public static Language fromConfig() {
        return parse(AgentConfig.get("language"));
    }

    /** 从命令行 --lang=xx 读，其次配置文件，最后中文 */
    public static Language fromArgs(String[] args) {
        for (String a : args) {
            if (a.startsWith("--lang=")) {
                return parse(a.substring("--lang=".length()));
            }
        }
        return fromConfig();
    }
}

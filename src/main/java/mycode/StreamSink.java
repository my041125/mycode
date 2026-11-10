package mycode;

/**
 * 流式输出回调。
 *
 * 模型边生成边回调，界面就能边收到边打印 ——
 * 用户不用对着黑屏等十秒，也顺便解决了「不知道它是不是卡死了」的焦虑。
 *
 * 故意做得极简：只有 onText 一个必需方法。
 * 工具调用不走这里，因为工具调用是「攒完才能执行」的，
 * 边收边执行会拿半个参数去调工具，那是灾难。
 * @author my
 */
@FunctionalInterface
public interface StreamSink {

    /**
     * 收到一段文本增量。
     *
     * @param delta 新增的文字，可能只有一两个字，也可能是一整段
     */
    void onText(String delta);
}

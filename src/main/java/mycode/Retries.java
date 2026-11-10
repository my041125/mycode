package mycode;

/**
 * 模型调用的瞬时故障重试策略。
 *
 * 长任务跑十几步，中途一次 429 或网络抖动就把整轮废掉太亏 ——
 * 前面烧掉的上下文全在本地，重试一次通常就过去了。各家 agent 的标配能力。
 *
 * 分类原则：只有「等等就好」的错误才重试。401/403/400 是配置或参数错误，
 * 重试八次也是白等，必须立刻如实上报让用户去改配置。
 * @author my
 */
final class Retries {

    private Retries() {
    }

    /**
     * 判断一次模型调用失败是否值得重试。
     *
     * 两个客户端抛的都是 IllegalStateException，状态码在消息里
     * （"HTTP 429 -> ..."），超时和断连是 JDK 网络异常，消息也有固定特征，
     * 所以按消息分类就够了，不需要引入异常类型层级。
     */
    static boolean transientFailure(Exception e) {
        String m = String.valueOf(e.getMessage()).toLowerCase();

        // 永久错误：key 错、没权限、参数不对 —— 重试没有意义
        if (m.contains("401") || m.contains("403") || m.contains("400")
                || m.contains("unauthorized") || m.contains("forbidden")
                || m.contains("invalid api key") || m.contains("invalid_request")) {
            return false;
        }
        // 限流与服务端临时故障
        if (m.contains("429") || m.contains("rate limit")
                || m.contains("500") || m.contains("502") || m.contains("503") || m.contains("504")
                || m.contains("server error") || m.contains("overloaded")) {
            return true;
        }
        // 网络类：超时、断连、DNS —— 多半是瞬时的
        return m.contains("timeout") || m.contains("timed out")
                || m.contains("connection") || m.contains("unknownhost")
                || m.contains("reset") || m.contains("eof") || m.contains("broken pipe");
    }

    /** 第 n 次重试（从 0 计）前等多久：1s → 2s → 4s，指数退避 */
    static long backoffMillis(int retryIndex) {
        return 1000L << Math.min(retryIndex, 4);
    }
}

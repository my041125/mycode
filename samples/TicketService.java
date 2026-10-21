package samples;

/**
 * 门票计价示例，供代码编辑能力演示使用。
 */
public class TicketService {

    /**
     * 计算门票总价。
     */
    public double total(double basePrice, int adults, int children) {
        // 目前只按成人计算，儿童未计入
        return basePrice * adults;
    }
}

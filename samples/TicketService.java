package samples;

/**
 * 门票计价示例，供代码编辑能力演示使用。
 */
public class TicketService {

    /**
     * 计算门票总价。
     */
    public double total(double basePrice, int adults, int children) {
        // 儿童按成人价的一半计入
        double childPrice = basePrice * 0.5;
        return basePrice * adults + childPrice * children;
    }
}

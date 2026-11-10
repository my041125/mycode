package mycode;

import java.util.Scanner;

/**
 * 最简易计算器：支持加减乘除四个运算。
 *
 * 用法：运行后输入“数字 运算符 数字”，例如 3 + 5，回车得到结果；输入 q 退出。
 * @author my
 */
public class Calculator {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("简易计算器：输入 数字 运算符 数字（例如 3 + 5），输入 q 退出");

        while (true) {
            System.out.print("> ");
            String line = scanner.nextLine().trim();
            if (line.equalsIgnoreCase("q")) {
                break;
            }
            // 按空白拆成三部分：左操作数、运算符、右操作数
            String[] parts = line.split("\\s+");
            if (parts.length != 3) {
                System.out.println("格式不对，应该像这样：3 + 5");
                continue;
            }
            try {
                double a = Double.parseDouble(parts[0]);
                double b = Double.parseDouble(parts[2]);
                System.out.println("= " + calc(a, parts[1], b));
            } catch (NumberFormatException e) {
                System.out.println("请输入数字");
            } catch (IllegalArgumentException e) {
                System.out.println(e.getMessage());
            }
        }
    }

    /**
     * 按运算符计算结果。
     */
    static double calc(double a, String op, double b) {
        switch (op) {
            case "+":
                return a + b;
            case "-":
                return a - b;
            case "*":
                return a * b;
            case "/":
                if (b == 0) {
                    throw new IllegalArgumentException("除数不能为 0");
                }
                return a / b;
            default:
                throw new IllegalArgumentException("不支持的运算符：" + op + "（只支持 + - * /）");
        }
    }
}

// Put the expression evaluator through its paces.

// Sample usage:

// $ java expr.Example '3.14159 * x^2' 0 4 1
// 0
// 3.14159
// 12.5664
// 28.2743
// 50.2654
//
// $ java expr.Example 'sin (pi/4 * x)' 0 4 1
// 0
// 0.707107
// 1
// 0.707107
// 1.22461e-16
//
// $ java expr.Example 'sin (pi/4 x)' 0 4 1
// I don't understand your formula "sin (pi/4 x)".
// 
// I got as far as "sin (pi/4" and then saw "x".
// I expected ")" at that point, instead.
// An example of a formula I can parse is "sin (pi/4 + x)".

package expr;

/**
 * A simple example of parsing and evaluating an expression.
 */
public class Example {
	public static void main(String[] args) {
		Expr expr;
		try {
			expr = Parser.parse("3 * x^2");
		} catch (SyntaxException e) {
			System.err.println(e.explain());
			return;
		}


		Variable x = Variable.make("x");
		for (double xval = 0; xval <= 3; xval += 1) {
			x.setValue(xval);
			System.out.println(expr.value());
		}
	}
}

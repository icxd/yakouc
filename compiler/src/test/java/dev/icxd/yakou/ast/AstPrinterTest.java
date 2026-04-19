package dev.icxd.yakou.ast;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.icxd.yakou.parse.Parser;
import dev.icxd.yakou.syntax.Lexer;

import org.junit.jupiter.api.Test;

class AstPrinterTest {

  @Test
  void printsExpressionsFromMain() {
    String src = """
        fn main(): unit {
          let user = demo::User::parse("...");
          when user {
            is Ok(v) {
              v.fmt()
            }
          }
        }
        """;
    var toks = new Lexer(src, "m.yk").tokenizeAll();
    AstFile ast = new Parser("m.yk", toks).parseFile();
    String out = AstPrinter.print(ast);
    assertTrue(out.contains("demo::User::parse"));
    assertTrue(out.contains("when user"));
    assertTrue(out.contains("is Ok(let v)"));
    assertTrue(out.contains("v.fmt()"));
  }
}

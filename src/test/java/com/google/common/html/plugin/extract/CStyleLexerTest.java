package com.google.common.html.plugin.extract;

import org.junit.Test;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.html.plugin.extract.CStyleLexer.Token;

import junit.framework.TestCase;

@SuppressWarnings("javadoc")
public class CStyleLexerTest extends TestCase {

  @Test
  public static void testLex() {
    CStyleLexer lexer = new CStyleLexer(
        ""
        + "// Comment\n"
        + "\n"
        + "syntax=proto2 ;\t\n"
        + "package \"com\\.example\";\r\n"
        + "message FooBar_Baz$1 { string s = 1; /* zero obsolete*/ }\n"
        + "/**\n"
        + "TODO(foo)\n"
        + "* /\n"
        + "*/\n"
        + "service s"
        );

    ImmutableList.Builder<String> tokens = ImmutableList.builder();
    for (Token t : lexer) {
      tokens.add(t.type + ":" + t.toString());
    }

    ImmutableList<String> got = tokens.build();
    ImmutableList<String> want = ImmutableList.of(
        "WORD:syntax",
        "PUNCTUATION:=",
        "WORD:proto2",
        "PUNCTUATION:;",
        "WORD:package",
        "STRING:\"com\\.example\"",
        "PUNCTUATION:;",
        "WORD:message",
        "WORD:FooBar_Baz$1",
        "PUNCTUATION:{",
        "WORD:string",
        "WORD:s",
        "PUNCTUATION:=",
        "NUMBER:1",
        "PUNCTUATION:;",
        "PUNCTUATION:}",
        "WORD:service",
        "WORD:s");

    if (!got.equals(want)) {
      assertEquals(
          Joiner.on("\n\n").join(want),
          Joiner.on("\n\n").join(got));
      assertEquals(want, got);
    }
  }


  @Test
  public static void testProtoPackageFinder() {
    FindProtoPackageStmt finder = new FindProtoPackageStmt();
    assertEquals(
        "foo/bar/baz.proto",
        finder.chooseRelativePath(
            "proto/foo/bar/baz.proto",

            "// Copy\nsyntax = proto2;\npackage foo.bar;\n"
            .getBytes(Charsets.UTF_8))
        );
    assertEquals(
        "proto/foo/bar/baz.proto",
        finder.chooseRelativePath(
            "proto/foo/bar/baz.proto",

            "// Copy\nsyntax = proto2;\n"
            .getBytes(Charsets.UTF_8))
        );
  }
}

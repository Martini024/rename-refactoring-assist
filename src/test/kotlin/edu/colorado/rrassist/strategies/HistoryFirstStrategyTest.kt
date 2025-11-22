package edu.colorado.rrassist.strategies

import com.intellij.psi.codeStyle.NameUtil
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryFirstStrategyTest {
    @Test
    fun testNameUtilSplitNameIntoWordsDemo() {
        println("userName -> " + NameUtil.nameToWords("userName").toList())
        println("UserName -> " + NameUtil.nameToWords("UserName").toList())
        println("user_name -> " + NameUtil.nameToWords("user_name").toList())
        println("MY_CONSTANT_VALUE -> " + NameUtil.nameToWords("MY_CONSTANT_VALUE").toList())
    }

    @Test
    fun testNameUtilSplitNameIntoWords() {
        // empty
        assertEquals(
            listOf<String>(),
            NameUtil.nameToWords("").toList()
        )

        // simple camelCase
        assertEquals(
            listOf("user", "Name"),
            NameUtil.nameToWords("userName").toList()
        )

        // PascalCase
        assertEquals(
            listOf("User", "Name"),
            NameUtil.nameToWords("UserName").toList()
        )

        // snake_case
        assertEquals(
            listOf("user", "_", "name"),
            NameUtil.nameToWords("user_name").toList()
        )

        // SCREAMING_SNAKE_CASE
        assertEquals(
            listOf("MY", "_", "CONSTANT", "_", "VALUE"),
            NameUtil.nameToWords("MY_CONSTANT_VALUE").toList()
        )

        // single word
        assertEquals(
            listOf("Value"),
            NameUtil.nameToWords("Value").toList()
        )
    }

    @Test
    fun testConvert() {
        // empty
        assertEquals(
            "",
            NamingConvention.convert("", NamingConvention.CAMEL_CASE)
        )
        assertEquals(
            "",
            NamingConvention.convert("", NamingConvention.SNAKE_CASE)
        )
        assertEquals(
            "",
            NamingConvention.convert("", NamingConvention.CONSTANT_CASE)
        )
        assertEquals(
            "",
            NamingConvention.convert("", NamingConvention.PASCAL_CASE)
        )

        // CONSTANT_CASE source
        assertEquals(
            "my_var",
            NamingConvention.convert("MY_VAR", NamingConvention.SNAKE_CASE)
        )
        assertEquals(
            "MyVar",
            NamingConvention.convert("MY_VAR", NamingConvention.PASCAL_CASE)
        )
        assertEquals(
            "myVar",
            NamingConvention.convert("MY_VAR", NamingConvention.CAMEL_CASE)
        )
        // idempotent
        assertEquals(
            "MY_VAR",
            NamingConvention.convert("MY_VAR", NamingConvention.CONSTANT_CASE)
        )

        // SNAKE_CASE source
        assertEquals(
            "MY_VAR",
            NamingConvention.convert("my_var", NamingConvention.CONSTANT_CASE)
        )
        assertEquals(
            "MyVar",
            NamingConvention.convert("my_var", NamingConvention.PASCAL_CASE)
        )
        assertEquals(
            "myVar",
            NamingConvention.convert("my_var", NamingConvention.CAMEL_CASE)
        )
        // idempotent
        assertEquals(
            "my_var",
            NamingConvention.convert("my_var", NamingConvention.SNAKE_CASE)
        )

        // PASCAL_CASE source
        assertEquals(
            "MY_VAR",
            NamingConvention.convert("MyVar", NamingConvention.CONSTANT_CASE)
        )
        assertEquals(
            "my_var",
            NamingConvention.convert("MyVar", NamingConvention.SNAKE_CASE)
        )
        assertEquals(
            "myVar",
            NamingConvention.convert("MyVar", NamingConvention.CAMEL_CASE)
        )
        // idempotent
        assertEquals(
            "MyVar",
            NamingConvention.convert("MyVar", NamingConvention.PASCAL_CASE)
        )

        // CAMEL_CASE source
        assertEquals(
            "MY_VAR",
            NamingConvention.convert("myVar", NamingConvention.CONSTANT_CASE)
        )
        assertEquals(
            "my_var",
            NamingConvention.convert("myVar", NamingConvention.SNAKE_CASE)
        )
        assertEquals(
            "MyVar",
            NamingConvention.convert("myVar", NamingConvention.PASCAL_CASE)
        )
        // idempotent
        assertEquals(
            "myVar",
            NamingConvention.convert("myVar", NamingConvention.CAMEL_CASE)
        )

        // single-word lower
        assertEquals(
            "ID",
            NamingConvention.convert("id", NamingConvention.CONSTANT_CASE)
        )
        assertEquals(
            "id",
            NamingConvention.convert("id", NamingConvention.SNAKE_CASE)
        )
        assertEquals(
            "Id",
            NamingConvention.convert("id", NamingConvention.PASCAL_CASE)
        )
        assertEquals(
            "id",
            NamingConvention.convert("id", NamingConvention.CAMEL_CASE)
        )

        // single-word upper
        assertEquals(
            "MAX",
            NamingConvention.convert("MAX", NamingConvention.CONSTANT_CASE)
        )
        assertEquals(
            "max",
            NamingConvention.convert("MAX", NamingConvention.SNAKE_CASE)
        )
        assertEquals(
            "Max",
            NamingConvention.convert("MAX", NamingConvention.PASCAL_CASE)
        )
        assertEquals(
            "max",
            NamingConvention.convert("MAX", NamingConvention.CAMEL_CASE)
        )

        // digits and mixed
        assertEquals(
            "VALUE_1",
            NamingConvention.convert("value1", NamingConvention.CONSTANT_CASE)
        )
        assertEquals(
            "value_1",
            NamingConvention.convert("VALUE_1", NamingConvention.SNAKE_CASE)
        )
        assertEquals(
            "Value1",
            NamingConvention.convert("value_1", NamingConvention.PASCAL_CASE)
        )
        assertEquals(
            "value1",
            NamingConvention.convert("VALUE_1", NamingConvention.CAMEL_CASE)
        )

        // acronyms & IntelliJ-style splitting (NameUtil.splitNameIntoWords)
        assertEquals(
            "HTTP_SERVER",
            NamingConvention.convert("HTTPServer", NamingConvention.CONSTANT_CASE)
        )
        assertEquals(
            "http_server",
            NamingConvention.convert("HTTPServer", NamingConvention.SNAKE_CASE)
        )
        assertEquals(
            "HttpServer",
            NamingConvention.convert("HTTPServer", NamingConvention.PASCAL_CASE)
        )
        assertEquals(
            "httpServer",
            NamingConvention.convert("HTTPServer", NamingConvention.CAMEL_CASE)
        )

        assertEquals(
            "my_xml_parser_23",
            NamingConvention.convert("myXMLParser23", NamingConvention.SNAKE_CASE)
        )
        assertEquals(
            "MY_XML_PARSER_23",
            NamingConvention.convert("myXMLParser23", NamingConvention.CONSTANT_CASE)
        )
        assertEquals(
            "MyXmlParser23",
            NamingConvention.convert("myXMLParser23", NamingConvention.PASCAL_CASE)
        )
        assertEquals(
            "myXmlParser23",
            NamingConvention.convert("myXMLParser23", NamingConvention.CAMEL_CASE)
        )

        // ugly underscores – NameUtil should normalize
        assertEquals(
            "MY_VAR_NAME",
            NamingConvention.convert("__MY__VAR__NAME__", NamingConvention.CONSTANT_CASE)
        )
        assertEquals(
            "my_var_name",
            NamingConvention.convert("__MY__VAR__NAME__", NamingConvention.SNAKE_CASE)
        )
        assertEquals(
            "MyVarName",
            NamingConvention.convert("__MY__VAR__NAME__", NamingConvention.PASCAL_CASE)
        )
        assertEquals(
            "myVarName",
            NamingConvention.convert("__MY__VAR__NAME__", NamingConvention.CAMEL_CASE)
        )
    }
}
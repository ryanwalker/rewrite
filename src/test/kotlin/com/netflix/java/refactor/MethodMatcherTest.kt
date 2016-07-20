package com.netflix.java.refactor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MethodMatcherTest {
    @Test
    fun matchesMethodTargetType() {
        val typeRegex = { signature: String -> MethodMatcher(signature).targetTypePattern }

        assertTrue(typeRegex("*..MyClass foo()").matches("com.bar.MyClass"))
        assertTrue(typeRegex("MyClass foo()").matches("MyClass"))
        assertTrue(typeRegex("com.bar.MyClass foo()").matches("com.bar.MyClass"))
        assertTrue(typeRegex("com.*.MyClass foo()").matches("com.bar.MyClass"))
    }

    @Test
    fun matchesMethodName() {
        val nameRegex = { signature: String -> MethodMatcher(signature).methodNamePattern }

        assertTrue(nameRegex("A foo()").matches("foo"))
        assertTrue(nameRegex("A *()").matches("foo"))
        assertTrue(nameRegex("A fo*()").matches("foo"))

        // FIXME see section 5.4 in the Definitive ANTLR4 Reference for why ambiguity in the grammar places the star with the type expression
        assertFalse(nameRegex("A *oo()").matches("foo"))
    }

    val argRegex = { signature: String -> MethodMatcher(signature).argumentPattern }

    @Test
    fun matchesArguments() {
        assertTrue(argRegex("A foo()").matches(""))
        assertTrue(argRegex("A foo(int)").matches("int"))
        assertTrue(argRegex("A foo(java.util.Map)").matches("java.util.Map"))
    }

    @Test
    fun matchesUnqualifiedJavaLangArguments() {
        assertTrue(argRegex("A foo(String)").matches("java.lang.String"))
    }

    @Test
    fun matchesArgumentsWithWildcards() {
        assertTrue(argRegex("A foo(java.util.*)").matches("java.util.Map"))
        assertTrue(argRegex("A foo(java..*)").matches("java.util.Map"))
    }

    @Test
    fun matchesArgumentsWithDotDot() {
        assertTrue(argRegex("A foo(.., int)").matches("int"))
        assertTrue(argRegex("A foo(.., int)").matches("int,int"))

        assertTrue(argRegex("A foo(int, ..)").matches("int"))
        assertTrue(argRegex("A foo(int, ..)").matches("int,int"))

        assertTrue(argRegex("A foo(..)").matches(""))
        assertTrue(argRegex("A foo(..)").matches("int"))
        assertTrue(argRegex("A foo(..)").matches("int,int"))
    }

    @Test
    fun matchesMethodSymbolsWithVarargs() {
        // TODO implement me
    }

    @Test
    fun matchesArrayArguments() {
        assertTrue(argRegex("A foo(String[])").matches("java.lang.String[]"))
    }
}
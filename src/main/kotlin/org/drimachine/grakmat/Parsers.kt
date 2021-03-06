/*
 * Copyright (c) 2016 Drimachine.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:JvmName("Parsers")
package org.drimachine.grakmat

import java.io.File


/**
 * Data-class for parse result.
 *
 * @param value Parsing value.
 * @param remainder String with remaining content.
 */
data class Result<out A>(val value: A, val remainder: String)


/**
 * Data-class for source.
 *
 * @param text Text in the source.
 * @param fileName Source name. By default is `"<inline>"`.
 */
data class Source(val text: String, val fileName: String)


/**
 * Interface for parsers.
 *
 * @param A Type of result.
 */
interface Parser<out A> {
    /** Description for expected token. */
    val expectedDescription: String

    fun parse(input: String): A {
        val source = Source(input, "<inline>")
        return parseSource(source)
    }

    fun parseFile(file: File): A {
        val text = file.readText()
        val fileName: String = file.absolutePath
        val source = Source(text, fileName)
        return parseSource(source)
    }

    fun parseSource(source: Source): A {
        val text = source.text
        val (value: A, remainder: String) = eat(source, text)

        if (remainder.isEmpty()) {
            return value
        } else {
            val errorIndex = text.length - remainder.length
            throw UnexpectedTokenException(remainder.boundLengthTo(20), "<EOF>", text.errorPosition(errorIndex), source)
        }
    }

    /**
     * Cuts something from input and returns [Result] with value and remainder.
     *
     * @param source Parsing source.
     * @param input Input for processing.
     */
    fun eat(source: Source, input: String): Result<A>
}


/**
 * Function for creating parser using lambda.
 *
 * @param expectedDescription Description for expected token.
 * @param eatLambda Lambda which represents [Parser.eat] function.
 */
fun <A> createParser(expectedDescription: String, eatLambda: (Source, String) -> Result<A>): Parser<A> =
        InlineParser(expectedDescription, eatLambda)

/**
 * @see createParser
 */
class InlineParser<out A>(override val expectedDescription: String, val eatLambda: (Source, String) -> Result<A>)
: Parser<A> {
    override fun eat(source: Source, input: String): Result<A> = eatLambda.invoke(source, input)
}


/**
 * Creates parser that returns `null` and consumes nothing from the input.
 */
fun <A> empty(): Parser<A?> = EmptyParser<A>()

/**
 * @see empty
 */
class EmptyParser<out A> : Parser<A?> {
    override val expectedDescription: String
        get() = "empty string"

    override fun eat(source: Source, input: String): Result<A?> = Result(null, input)

    override fun toString(): String = expectedDescription
}


/**
 * Creates parser that returns empty parser and consumes nothing from the input.
 */
fun emptyString(): Parser<String> = empty<String>() map { "" }

/** @see string */
fun str(expected: String): Parser<String> = string(expected)

/**
 * Creates parser which expects, consumes and returns [expected].
 *
 * @param expected Expected string.
 */
fun string(expected: String): Parser<String> = when (expected.length) {
    0    -> emptyString()
    1    -> char(expected[0]) map { String(charArrayOf(it)) }
    else -> StringParser(expected)
}

/**
 * @see string
 */
class StringParser(val expected: String) : Parser<String> {
    override val expectedDescription: String
        get() = "\"$expected\""

    override fun eat(source: Source, input: String): Result<String> {
        if (expected.length > input.length) {
            val errorIndex = source.text.length
            throw UnexpectedEOFException(expectedDescription, source.text.errorPosition(errorIndex), source)
        }

        // Now, length of the input will be equal or greater than length of the expected sequence,
        // so I use indexes of expected to check tokens
        if (!input.regionMatches(0, expected, 0, expected.length)) {
            val errorIndex = source.text.length - input.length
            throw UnexpectedTokenException(input.boundLengthTo(20), expectedDescription, source.text.errorPosition(errorIndex), source)
        }

        val remainder = input.substring(expected.length, input.length)
        return Result(expected, remainder)
    }

    override fun toString(): String = expectedDescription
}


/**
 * Creates referenced parser.
 * Useful, when you use fields to hold rules:
 *
 * ```kotlin
 * object Math {
 *     private val exprRuleRef = ref { exprRule }
 *
 *     private val mainRule = exprRuleRef    // See? exprRule is declared later, but you can still use it through the reference!
 *     private val exprRule = ...
 * }
 * ```
 *
 * @param target Function that returns target parser.
 */
fun <A> ref(target: () -> Parser<A>) = ReferencedParser(target)

/**
 * @see ref
 */
class ReferencedParser<out A>(val target: () -> Parser<A>) : Parser<A> {
    override val expectedDescription: String
        get() = target().expectedDescription

    override fun eat(source: Source, input: String): Result<A> = target().eat(source, input)

    override fun toString(): String = target().toString()
}


/** @see char */
fun chr(expected: Char): Parser<Char> = char(expected)

/**
 * Creates parser that expects, consumes and returns single character.
 *
 * @param expected Expected character.
 */
fun char(expected: Char): Parser<Char> = CharParser(expected)

/**
 * @see char
 */
class CharParser(val expected: Char) : Parser<Char> {
    override val expectedDescription: String
        get() = "\'$expected\'"

    override fun eat(source: Source, input: String): Result<Char> {
        if (input.isEmpty()) {
            val errorIndex = source.text.length
            throw UnexpectedEOFException(expectedDescription, source.text.errorPosition(errorIndex), source)
        }

        if (input[0] != expected) {
            val errorIndex = source.text.length - input.length
            throw UnexpectedTokenException(input.boundLengthTo(20), expectedDescription, source.text.errorPosition(errorIndex), source)
        }

        val remainder = input.substring(1)
        return Result(expected, remainder)
    }

    override fun toString(): String = expectedDescription
}


/**
 * Parser that expects any character in a range, consumes one character,
 * and returns consumed one.
 *
 * @param included Characters that can be consumed.
 */
fun anyOf(vararg included: Char): Parser<Char> = IncludedCharParser(included.toList())

/**
 * @see anyOf
 */
fun anyOf(included: Iterable<Char>): Parser<Char> = IncludedCharParser(included.toList())

/**
 * @see anyOf
 */
class IncludedCharParser(val included: Iterable<Char>) : Parser<Char> {
    override val expectedDescription: String
        get() = "any char of ${included.joinToString(prefix = "{", separator = ", ", postfix = "}")}"

    override fun eat(source: Source, input: String): Result<Char> {
        if (input.isEmpty()) {
            val errorIndex = source.text.length
            throw UnexpectedEOFException(expectedDescription, source.text.errorPosition(errorIndex), source)
        }

        val value = input[0]
        if (value !in included) {
            val errorIndex = source.text.length - input.length
            throw UnexpectedTokenException(input.boundLengthTo(20), expectedDescription, source.text.errorPosition(errorIndex), source)
        }
        
        val remainder = input.substring(1)
        return Result(value, remainder)
    }

    override fun toString(): String = expectedDescription
}


/**
 * Parser that expects any character but not in a range,
 * consumes one character, and returns consumed one.
 *
 * @param excluded Characters that can't be consumed.
 */
fun except(vararg excluded: Char): Parser<Char> = ExcludedCharParser(excluded.toList())

/**
 * @see except
 */
fun except(excluded: Iterable<Char>): Parser<Char> = ExcludedCharParser(excluded.toList())

/**
 * @see except
 */
class ExcludedCharParser(val excluded: Iterable<Char>) : Parser<Char> {
    override val expectedDescription: String
        get() = "any char except ${excluded.joinToString(prefix = "{", separator = ", ", postfix = "}")}"

    override fun eat(source: Source, input: String): Result<Char> {
        if (input.isEmpty()) {
            val errorIndex = source.text.length
            throw UnexpectedEOFException(expectedDescription, source.text.errorPosition(errorIndex), source)
        }

        val value = input[0]
        if (value in excluded) {
            val errorIndex = source.text.length - input.length
            throw UnexpectedTokenException(input.boundLengthTo(20), expectedDescription, source.text.errorPosition(errorIndex), source)
        }
        
        val remainder = input.substring(1)
        return Result(value, remainder)
    }

    override fun toString(): String = expectedDescription
}


/**
 * Creates parser, that consumes any character and returns it.
 */
fun anyChar(): Parser<Char> = AnyCharParser()

/**
 * @see AnyCharParser
 */
class AnyCharParser : Parser<Char> {
    override val expectedDescription: String
        get() = "any char"

    override fun eat(source: Source, input: String): Result<Char> {
        if (input.isEmpty()) {
            val errorIndex = source.text.length
            throw UnexpectedEOFException(expectedDescription, source.text.errorPosition(errorIndex), source)
        }

        val value = input[0]
        val remainder = input.substring(1)
        return Result(value, remainder)
    }

    override fun toString(): String = expectedDescription
}

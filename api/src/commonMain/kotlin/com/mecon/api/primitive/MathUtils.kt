package com.mecon.api.primitive

import kotlin.math.absoluteValue

/**
 * Greatest common divisor
 */
fun gcd(a: Int, b: Int): Int = if (b == 0) a.absoluteValue else gcd(b, a % b)

/**
 * Least common multiple
 */
fun lcm(a: Int, b: Int): Int = (a / gcd(a, b) * b).absoluteValue

/**
 * Generate a random ID string
 */
fun generateId(): String = (1..9).map { "0123456789abcdefghijklmnopqrstuvwxyz".random() }.joinToString("")

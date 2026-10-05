package org.mozilla.javascript;

import java.math.BigInteger;

/**
 * Size checks for BigInt arithmetic. A single BigInteger operation runs in Java, out of sight of the instruction
 * budget, and its cost grows faster than its operands: squaring a value 30 times, or {@code 1n << 2n ** 30n}, would
 * freeze the game for minutes or exhaust its memory. The build rewrites Rhino's BigInt code ({@code ScriptRuntime},
 * {@code NativeBigInt}, {@code TokenStream}; see rhino/build.gradle) so that every multiplication, power, shift and
 * parse from text goes through here first. Results larger than {@link #maxBits} throw a RangeError.
 *
 * <p>This class is written in Rhino's package so it is relocated with it ({@code dev.vellum.shadow.rhino}).
 */
public final class VellumBigInts {
    /** The largest BigInt, in bits; process-wide, set by the engine from its limits. */
    private static volatile long maxBits = 1 << 16;

    private VellumBigInts() {}

    public static void setMaxBits(long bits) {
        maxBits = bits;
    }

    public static long maxBits() {
        return maxBits;
    }

    private static void check(long bits) {
        if (bits > maxBits) throw ScriptRuntime.rangeError("BigInt larger than " + maxBits + " bits");
    }

    public static BigInteger multiply(BigInteger a, BigInteger b) {
        check((long) a.bitLength() + b.bitLength());
        return a.multiply(b);
    }

    public static BigInteger pow(BigInteger a, int exponent) {
        if (a.abs().compareTo(BigInteger.ONE) > 0) check((long) (a.bitLength() - 1) * exponent + 1);
        return a.pow(exponent);
    }

    public static BigInteger shiftLeft(BigInteger a, int n) {
        if (n > 0 && a.signum() != 0) check((long) a.bitLength() + n);
        return a.shiftLeft(n);
    }

    public static BigInteger shiftRight(BigInteger a, int n) {
        if (n < 0 && a.signum() != 0) check((long) a.bitLength() - (long) n);
        return a.shiftRight(n);
    }

    /** Before {@code new BigInteger(text, radix)}: parsing is quadratic in the number of digits. */
    public static void checkDigits(String text, int radix) {
        check((long) Math.ceil(text.length() * (Math.log(Math.max(2, radix)) / Math.log(2))));
    }

    public static void checkDigits(String text) {
        checkDigits(text, 10);
    }
}

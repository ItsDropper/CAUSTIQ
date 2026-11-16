package dev.itsdropper.caustiq;

/** Pure compile-budget bounds and cycling behavior shared by settings and tests. */
public final class CompileBudget {
    public static final int MIN = 1;
    public static final int MAX = 8;

    private CompileBudget() { }

    /** Returns the next supported budget, wrapping from the maximum back to the minimum. */
    public static int next(int current) {
        return current >= MAX ? MIN : Math.max(MIN, current + 1);
    }
}

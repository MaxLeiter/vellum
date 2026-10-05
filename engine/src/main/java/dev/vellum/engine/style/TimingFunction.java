package dev.vellum.engine.style;

/** An easing function for transitions and animations: {@code cubic-bezier()}, {@code steps()} or the named keywords. */
public sealed interface TimingFunction {
    /** Maps input progress 0..1 to output progress (which may overshoot for bezier curves). */
    float apply(float t);

    TimingFunction LINEAR = new CubicBezier(0, 0, 1, 1);
    TimingFunction EASE = new CubicBezier(0.25f, 0.1f, 0.25f, 1f);
    TimingFunction EASE_IN = new CubicBezier(0.42f, 0, 1, 1);
    TimingFunction EASE_OUT = new CubicBezier(0, 0, 0.58f, 1);
    TimingFunction EASE_IN_OUT = new CubicBezier(0.42f, 0, 0.58f, 1);

    record CubicBezier(float x1, float y1, float x2, float y2) implements TimingFunction {
        @Override
        public float apply(float t) {
            if (t <= 0) return 0;
            if (t >= 1) return 1;
            if (x1 == y1 && x2 == y2) return t; // linear
            // Solve x(s) = t for s with Newton's method, falling back to bisection.
            float s = t;
            for (int i = 0; i < 8; i++) {
                float x = sample(x1, x2, s) - t;
                if (Math.abs(x) < 1e-5f) return sample(y1, y2, s);
                float d = slope(x1, x2, s);
                if (Math.abs(d) < 1e-6f) break;
                s -= x / d;
            }
            float lo = 0, hi = 1;
            s = t;
            for (int i = 0; i < 24; i++) {
                float x = sample(x1, x2, s);
                if (Math.abs(x - t) < 1e-5f) break;
                if (x < t) lo = s; else hi = s;
                s = (lo + hi) / 2;
            }
            return sample(y1, y2, s);
        }

        private static float sample(float a, float b, float s) {
            float inv = 1 - s;
            return 3 * inv * inv * s * a + 3 * inv * s * s * b + s * s * s;
        }

        private static float slope(float a, float b, float s) {
            float inv = 1 - s;
            return 3 * inv * inv * a + 6 * inv * s * (b - a) + 3 * s * s * (1 - b);
        }
    }

    /** {@code steps(n, jump-start | jump-end | jump-none | jump-both)}; {@code start}/{@code end} are aliases. */
    record Steps(int count, Jump jump) implements TimingFunction {
        public enum Jump { START, END, NONE, BOTH }

        @Override
        public float apply(float t) {
            if (t <= 0) return jump == Jump.START || jump == Jump.BOTH ? 1f / jumps() : 0;
            if (t >= 1) return 1;
            int step = (int) Math.floor(t * count);
            if (jump == Jump.START || jump == Jump.BOTH) step++;
            return Math.min(1f, (float) step / jumps());
        }

        private int jumps() {
            return switch (jump) {
                case START, END -> count;
                case NONE -> Math.max(1, count - 1);
                case BOTH -> count + 1;
            };
        }
    }
}

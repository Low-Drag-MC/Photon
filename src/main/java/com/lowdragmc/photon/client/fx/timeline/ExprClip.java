package com.lowdragmc.photon.client.fx.timeline;

import expr.Expr;
import expr.Parser;
import expr.SyntaxException;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * An <b>expression clip</b> on a single {@link AnimatedProperty} channel's curve lane: a time window
 * {@code [start, end)} over which the channel value is produced by a {@code y = f(t)} expression instead
 * of the underlying keyframe curve. The expression's {@code t} is <b>clip-local</b> (time since the
 * clip's start, in ticks), so moving a clip preserves its shape. A parse/eval failure lets the caller
 * fall back to the curve.
 * <p>
 * Like {@link Clip}, this class is intentionally free of any {@code net.minecraft} dependency so the
 * timeline evaluation core stays unit-testable with plain JUnit. Serialization lives in
 * {@link AnimatedProperty}.
 */
public class ExprClip implements SubClip {
    private double start;
    private double duration;
    private String expression;
    // transient parse cache (rebuilt lazily on expression change; never serialized/copied)
    @Nullable
    private transient Expr compiled;
    @Nullable
    private transient String error;
    @Nullable
    private transient String parsedInput;

    public ExprClip() {
        this(0.0, 1.0, "");
    }

    public ExprClip(double start, double duration, String expression) {
        this.start = start;
        this.duration = duration;
        this.expression = expression == null ? "" : expression;
    }

    public double start() {
        return start;
    }

    public ExprClip start(double start) {
        this.start = start;
        return this;
    }

    public double duration() {
        return duration;
    }

    public ExprClip duration(double duration) {
        this.duration = duration;
        return this;
    }

    /** Exclusive end time of this clip. */
    public double end() {
        return start + duration;
    }

    /** Half-open {@code [start, end)} so adjacent clips never both match a single time. */
    public boolean contains(double time) {
        return time >= start && time < end();
    }

    public String expression() {
        return expression;
    }

    public ExprClip expression(String expression) {
        this.expression = expression == null ? "" : expression;
        return this;
    }

    /** (Re)parse the expression when it changed, caching the {@link Expr} or the error message. Returns
     *  the compiled expression, or {@code null} when the source is empty or fails to parse. */
    @Nullable
    public Expr compiled() {
        if (!Objects.equals(parsedInput, expression)) {
            parsedInput = expression;
            if (expression.isBlank()) {
                compiled = null;
                error = null;
            } else {
                try {
                    compiled = Parser.parse(expression);
                    error = null;
                } catch (SyntaxException e) {
                    compiled = null;
                    error = e.getMessage();
                }
            }
        }
        return compiled;
    }

    /** The expression's syntax error (null if it is empty or parses). */
    @Nullable
    public String error() {
        compiled();
        return error;
    }

    /** An independent value copy of this clip (parse cache is not copied — it rebuilds lazily). */
    public ExprClip copy() {
        return new ExprClip(start, duration, expression);
    }
}

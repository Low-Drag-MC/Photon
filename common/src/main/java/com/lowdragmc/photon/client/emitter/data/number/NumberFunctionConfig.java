package com.lowdragmc.photon.client.emitter.data.number;

import com.lowdragmc.photon.client.emitter.data.number.curve.CurveConfig;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author KilaBash
 * @date 2023/5/27
 * @implNote NumberFunctionConfig
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
public @interface NumberFunctionConfig {
    Class<? extends NumberFunction>[] types() default { Constant.class };
    float min() default Integer.MIN_VALUE;
    float max() default Integer.MAX_VALUE;
    float wheelDur() default 0.1f;
    float defaultValue() default 0;
    boolean isDecimals() default true;
    CurveConfig curveConfig() default @CurveConfig();
}

package com.lowdragmc.photon.client.data.number;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author KilaBash
 * @date 2023/5/27
 * @implNote NumberFunction3Config
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
public @interface NumberFunction3Config {
    NumberFunctionConfig common() default @NumberFunctionConfig;
    NumberFunctionConfig[] xyz() default {};
}

package net.optionfactory.paddock;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * API documentation for controller methods, parameters and javabean fields.
 *
 * @author rferranti
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD})
public @interface ApiDoc {

    String value();

    String[] help() default "";
}

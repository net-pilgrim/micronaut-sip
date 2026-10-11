package net.pilgrim.sip.annotation;

import io.micronaut.core.bind.annotation.Bindable;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a method parameter to a variable extracted from a URI template or regex pattern
 * in a SIP controller route (e.g. {@code @OnInvite("users/{id:[0-9]+}")} or
 * {@code @OnInvite("regex:^sip:(?<ext>[0-9]{3,4})@.*")}).
 * <p>
 * Evaluated at compile time with zero reflection using Micronaut AOT argument injection.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.FIELD})
@Bindable
public @interface SipVar {

    /**
     * The name of the path/regex variable to bind to.
     * If left blank, the parameter name is used.
     *
     * @return variable name
     */
    String value() default "";

    /**
     * Default value if the variable is not matched or empty.
     *
     * @return default value
     */
    String defaultValue() default "";

    /**
     * Whether this variable is mandatory for successful dispatch.
     *
     * @return true if mandatory
     */
    boolean required() default true;
}

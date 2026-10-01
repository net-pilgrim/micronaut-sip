package net.pilgrim.sip.annotation;

import io.micronaut.core.bind.annotation.Bindable;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a URI parameter from the incoming SIP request-URI to a controller method parameter.
 * Example: for request-URI "sip:alice@example.com;transport=tcp;ttl=60",
 * {@code @SipParam("transport") String transport} or {@code @SipParam("ttl") int ttl}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@Bindable
public @interface SipParam {
    /**
     * The parameter name. If empty, the method parameter name is used.
     */
    String value() default "";

    /**
     * Whether the parameter is required.
     */
    boolean required() default false;

    /**
     * Default value if the parameter is not present.
     */
    String defaultValue() default "";
}

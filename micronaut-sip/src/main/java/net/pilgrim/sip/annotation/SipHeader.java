package net.pilgrim.sip.annotation;

import io.micronaut.core.bind.annotation.Bindable;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a SIP header value from incoming message to a controller method parameter.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@Bindable
public @interface SipHeader {
    /**
     * The SIP header name (e.g. "Call-ID", "From", "To", "Via").
     */
    String value() default "";

    /**
     * Whether the header is mandatory.
     */
    boolean required() default false;

    /**
     * Default value if header is not present.
     */
    String defaultValue() default "";
}

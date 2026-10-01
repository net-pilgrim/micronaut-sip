package net.pilgrim.sip.annotation;

import io.micronaut.core.bind.annotation.Bindable;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds the SIP message body (e.g. SDP or text payload) to a method parameter.
 * Can be bound to String or byte[].
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@Bindable
public @interface SipBody {
    /**
     * Whether the body is mandatory.
     */
    boolean required() default false;
}

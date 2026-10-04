package net.pilgrim.sip.annotation;

import io.micronaut.context.annotation.Executable;
import io.micronaut.core.bind.annotation.Bindable;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds the DTMF signal from a SIP message body (e.g. application/dtmf-relay or application/dtmf
 * per RFC 2976 / RFC 6086) to a controller method parameter, or marks a method for DTMF handling.
 *
 * Supported parameter types:
 * <ul>
 *     <li>{@link net.pilgrim.sip.dtmf.DtmfSignal}</li>
 *     <li>{@code char} or {@link Character}</li>
 *     <li>{@link String}</li>
 * </ul>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.METHOD})
@Inherited
@Bindable
@Executable
public @interface SipDtmf {
    /**
     * Whether the DTMF signal is required. If true and no valid DTMF signal is present
     * in the request, an {@link IllegalArgumentException} is thrown.
     */
    boolean required() default false;
}

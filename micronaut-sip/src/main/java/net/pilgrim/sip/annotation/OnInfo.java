package net.pilgrim.sip.annotation;

import io.micronaut.context.annotation.AliasFor;
import io.micronaut.context.annotation.Executable;
import net.pilgrim.sip.model.SipMethod;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for methods handling SIP INFO requests (RFC 2976 / RFC 6086),
 * commonly used for mid-dialog DTMF signaling (application/dtmf-relay).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Inherited
@Executable
@OnSipMethod(SipMethod.INFO)
public @interface OnInfo {
    @AliasFor(annotation = OnSipMethod.class, member = "path")
    String value() default "";
}

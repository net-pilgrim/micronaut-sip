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
 * Annotation for methods handling SIP CANCEL requests (RFC 3261).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Inherited
@Executable
@OnSipMethod(SipMethod.CANCEL)
public @interface OnCancel {
    @AliasFor(annotation = OnSipMethod.class, member = "path")
    String value() default "";
}

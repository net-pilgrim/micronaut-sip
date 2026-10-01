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
 * Annotation for methods handling SIP MESSAGE requests (RFC 3428 Instant Messaging).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Inherited
@Executable
@OnSipMethod(SipMethod.MESSAGE)
public @interface OnMessage {
    @AliasFor(annotation = OnSipMethod.class, member = "path")
    String value() default "";
}

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
 * Annotation for methods handling SIP PUBLISH requests (RFC 3903 Event State Publication Extension).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Inherited
@Executable
@OnSipMethod(SipMethod.PUBLISH)
public @interface OnPublish {
    /**
     * Optional path or user pattern filter.
     */
    @AliasFor(annotation = OnSipMethod.class, member = "path")
    String value() default "";
}

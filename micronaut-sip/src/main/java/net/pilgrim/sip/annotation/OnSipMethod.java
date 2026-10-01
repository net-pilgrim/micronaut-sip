package net.pilgrim.sip.annotation;

import io.micronaut.context.annotation.Executable;
import net.pilgrim.sip.model.SipMethod;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generic annotation for handling any SIP method.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Inherited
@Executable(processOnStartup = true)
public @interface OnSipMethod {
    /**
     * The SIP method name or enum value.
     */
    SipMethod value() default SipMethod.INVITE;

    /**
     * Custom method name if not in standard enum.
     */
    String custom() default "";

    /**
     * Optional path or user pattern filter.
     */
    String path() default "";
}

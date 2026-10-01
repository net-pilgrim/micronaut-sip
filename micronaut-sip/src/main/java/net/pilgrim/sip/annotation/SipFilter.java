package net.pilgrim.sip.annotation;

import jakarta.inject.Singleton;
import net.pilgrim.sip.model.SipMethod;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a class as a reactive SIP server filter bean.
 * Allows declarative configuration of URI patterns, method filtering, and execution order.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Singleton
public @interface SipFilter {

    /**
     * Request URI patterns to match against.
     * Empty array matches all request URIs.
     *
     * @return pattern array (e.g. {"/**", "/sip/**"})
     */
    String[] patterns() default {};

    /**
     * Specific SIP methods to filter (e.g. {@code SipMethod.INVITE}, {@code SipMethod.REGISTER}).
     * Empty array matches all standard SIP methods.
     *
     * @return array of standard SIP methods to match
     */
    SipMethod[] methods() default {};

    /**
     * Custom SIP method names to filter.
     *
     * @return array of custom method names
     */
    String[] customMethods() default {};

    /**
     * Filter execution order. Lower numbers have higher priority (run earlier).
     *
     * @return filter order (default 0)
     */
    int order() default 0;
}

package net.pilgrim.sip.visitor;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import net.pilgrim.sip.annotation.*;
import net.pilgrim.sip.router.SipPatternMatcher;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Compile-time AST visitor validating {@link SipController} classes, route patterns,
 * regular expressions, and {@link SipVar} parameter bindings Ahead-Of-Time (zero reflection).
 * <p>
 * Emits compile errors directly into {@code javac} during build when invalid patterns
 * or unmatched path variables are detected.
 */
public class SipControllerVisitor implements TypeElementVisitor<SipController, Object> {

    private static final Set<String> SIP_METHOD_ANNOTATIONS = Set.of(
            OnInvite.class.getName(),
            OnAck.class.getName(),
            OnBye.class.getName(),
            OnCancel.class.getName(),
            OnOptions.class.getName(),
            OnRegister.class.getName(),
            OnMessage.class.getName(),
            OnInfo.class.getName(),
            OnPrack.class.getName(),
            OnSubscribe.class.getName(),
            OnNotify.class.getName(),
            OnRefer.class.getName(),
            OnUpdate.class.getName(),
            OnPublish.class.getName(),
            OnSipMethod.class.getName()
    );

    @Override
    public Set<String> getSupportedAnnotationNames() {
        return Set.of(SipController.class.getName());
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        // Validate controller class prefix pattern if present
        AnnotationValue<SipController> controllerAnn = element.getAnnotation(SipController.class);
        if (controllerAnn != null) {
            String prefix = controllerAnn.stringValue().orElse("");
            if (!prefix.isEmpty()) {
                validatePattern(prefix, element, context);
            }
        }
    }

    @Override
    public void visitMethod(MethodElement methodElement, VisitorContext context) {
        String path = "";
        boolean isSipHandler = false;

        for (String annName : SIP_METHOD_ANNOTATIONS) {
            if (methodElement.hasAnnotation(annName)) {
                isSipHandler = true;
                AnnotationValue<?> ann = methodElement.getAnnotation(annName);
                if (ann != null) {
                    path = ann.stringValue().or(() -> ann.stringValue("path")).orElse("");
                }
                break;
            }
        }

        if (!isSipHandler) {
            return;
        }

        // Validate route pattern at compile time
        SipPatternMatcher matcher = null;
        if (!path.isEmpty()) {
            matcher = validatePattern(path, methodElement, context);
        }

        // Validate @SipVar parameter bindings at compile time
        for (ParameterElement param : methodElement.getParameters()) {
            if (param.hasAnnotation(SipVar.class)) {
                AnnotationValue<SipVar> varAnn = param.getAnnotation(SipVar.class);
                String varName = varAnn != null ? varAnn.stringValue().orElse("") : "";
                if (varName.isEmpty()) {
                    varName = param.getName();
                }

                if (matcher == null || matcher.getVariableNames().isEmpty()) {
                    context.fail("Parameter '" + param.getName() + "' is annotated with @SipVar('" + varName
                            + "') but route pattern '" + path + "' defines no template variables or named capture groups", param);
                } else if (!matcher.getVariableNames().contains(varName)) {
                    context.fail("Parameter '" + param.getName() + "' is annotated with @SipVar('" + varName
                            + "') which is not defined in route pattern variables: " + matcher.getVariableNames(), param);
                }
            }
        }
    }

    private SipPatternMatcher validatePattern(String pattern, io.micronaut.inject.ast.Element element, VisitorContext context) {
        try {
            return new SipPatternMatcher(pattern);
        } catch (PatternSyntaxException e) {
            context.fail("Malformed regular expression in SIP route pattern '" + pattern + "': " + e.getMessage(), element);
            return null;
        } catch (Exception e) {
            context.fail("Invalid SIP route pattern '" + pattern + "': " + e.getMessage(), element);
            return null;
        }
    }
}

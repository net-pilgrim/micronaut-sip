package net.pilgrim;


import io.micronaut.runtime.EmbeddedApplication;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

import jakarta.inject.Inject;

@MicronautTest
class SipAppTest {

    @Inject
    EmbeddedApplication<?> application;

    @Inject
    net.pilgrim.sip.router.SipDispatcher dispatcher;

    @Test
    void testItWorks() {
        Assertions.assertTrue(application.isRunning());
    }

    @Test
    void testExecutableMethodProcessorRegisteredRoutes() {
        Assertions.assertFalse(dispatcher.getRoutes().isEmpty());
        for (net.pilgrim.sip.router.SipRoute route : dispatcher.getRoutes()) {
            Assertions.assertNotNull(route.getExecutableMethod(), 
                    "Route for method " + route.getMethod() + " should have an ExecutableMethod");
        }
    }

    @Test
    void testDuplicateRouteDetectionThrowsException() {
        Assertions.assertThrows(IllegalStateException.class, () -> {
            dispatcher.registerController(new net.pilgrim.controller.CallController());
        });
    }
}

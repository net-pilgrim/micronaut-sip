package net.pilgrim.statemachine;

import net.pilgrim.statemachine.model.Event;
import net.pilgrim.statemachine.model.StateMachineDefinition;
import net.pilgrim.statemachine.parser.StateMachineParser;
import net.pilgrim.statemachine.runtime.StateMachineListener;
import net.pilgrim.statemachine.runtime.StateMachineSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end verification demonstrating the standalone state machine engine
 * orchestrating a domain completely outside telecom (E-Commerce Order & Payment Workflow).
 */
class OrderWorkflowTest {

    private final StateMachineParser parser = new StateMachineParser();

    @Test
    @DisplayName("Demonstrate standalone order lifecycle with payment, timeout cancellation, and inventory fulfillment")
    void testOrderWorkflowSuccess() {
        String xml = """
            <statemachine id="order_lifecycle" initial="created">
                <var name="orderId"/>
                <var name="customerTier" expr="'standard'"/>
                <var name="totalPaid" expr="0"/>

                <eventprocessor statevariable="order_status">
                    <!-- Step 1: Order placed -->
                    <transition event="order.placed" state="created">
                        <assign name="orderId" expr="event$.values.orderId"/>
                        <assign name="customerTier" expr="event$.values.tier"/>
                        <!-- Set 200ms expiration timer for payment -->
                        <send event="'order.payment_timeout'" delay="'200ms'" sendid="'paymentTimer'"/>
                        <assign name="order_status" expr="'awaiting_payment'"/>
                    </transition>

                    <!-- Step 2: Payment webhook received -->
                    <transition event="payment.received" state="awaiting_payment" cond="event$.values.amount >= 150">
                        <!-- Cancel payment expiration timer -->
                        <cancel sendid="'paymentTimer'"/>
                        <assign name="totalPaid" expr="event$.values.amount"/>
                        <!-- Dispatch internal event for fulfillment -->
                        <send event="'inventory.allocate'"/>
                        <assign name="order_status" expr="'payment_verified'"/>
                    </transition>

                    <!-- Step 3: Inventory allocated -->
                    <transition event="inventory.allocate" state="payment_verified">
                        <if cond="customerTier == 'vip'">
                            <assign name="order_status" expr="'priority_shipping'"/>
                        <else/>
                            <assign name="order_status" expr="'standard_shipping'"/>
                        </if>
                    </transition>

                    <!-- Step 4: Shipping dispatched -->
                    <transition event="shipment.dispatched">
                        <assign name="order_status" expr="'completed'"/>
                        <exit/>
                    </transition>

                    <!-- Timeout fallback -->
                    <transition event="order.payment_timeout" state="awaiting_payment">
                        <assign name="order_status" expr="'cancelled'"/>
                        <exit/>
                    </transition>
                </eventprocessor>
            </statemachine>
            """;

        StateMachineDefinition def = parser.parse(xml);
        StateMachineSession session = new StateMachineSession(def);

        List<String> stateHistory = Collections.synchronizedList(new ArrayList<>());
        session.addListener(new StateMachineListener() {
            @Override
            public void onStateChanged(String fromState, String toState, Event triggerEvent) {
                stateHistory.add(fromState + "->" + toState);
            }
        });

        assertEquals("created", session.getCurrentState());

        // 1. Place order
        Event orderPlaced = Event.builder("order.placed")
                .param("orderId", "ORD-8941")
                .param("tier", "vip")
                .build();
        session.processEvent(orderPlaced);

        assertEquals("awaiting_payment", session.getCurrentState());
        assertEquals("ORD-8941", session.getVariable("orderId"));
        assertEquals("vip", session.getVariable("customerTier"));

        // 2. Customer pays
        Event paymentReceived = Event.builder("payment.received")
                .param("amount", 250)
                .build();
        session.processEvent(paymentReceived);

        // Inventory allocation should have triggered immediately via internal send
        assertEquals("priority_shipping", session.getCurrentState());
        assertEquals(250L, ((Number) session.getVariable("totalPaid")).longValue());

        // 3. Shipment dispatched
        session.processEvent(new Event("shipment.dispatched"));
        assertEquals("completed", session.getCurrentState());
        assertTrue(session.isTerminated());

        // Verify state progression
        assertTrue(stateHistory.contains("created->awaiting_payment"));
        assertTrue(stateHistory.contains("awaiting_payment->payment_verified"));
        assertTrue(stateHistory.contains("payment_verified->priority_shipping"));
        assertTrue(stateHistory.contains("priority_shipping->completed"));
    }

    @Test
    @DisplayName("Demonstrate standalone payment timeout expiration")
    void testOrderWorkflowPaymentTimeout() throws Exception {
        String xml = """
            <statemachine id="order_lifecycle" initial="created">
                <eventprocessor statevariable="status">
                    <transition event="order.placed" state="created">
                        <send event="'order.timeout'" delay="'60ms'" sendid="'payTimer'"/>
                        <assign name="status" expr="'awaiting_payment'"/>
                    </transition>

                    <transition event="order.timeout" state="awaiting_payment">
                        <assign name="status" expr="'cancelled'"/>
                        <exit/>
                    </transition>
                </eventprocessor>
            </statemachine>
            """;

        StateMachineDefinition def = parser.parse(xml);
        StateMachineSession session = new StateMachineSession(def);

        session.processEvent(new Event("order.placed"));
        assertEquals("awaiting_payment", session.getCurrentState());

        // Wait for timeout to trigger
        Thread.sleep(120);

        assertEquals("cancelled", session.getCurrentState());
        assertTrue(session.isTerminated());
    }
}

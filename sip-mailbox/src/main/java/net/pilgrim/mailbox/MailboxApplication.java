package net.pilgrim.mailbox;

import io.micronaut.runtime.Micronaut;

/**
 * Answering machine application listening for calls to mailbox@<sip-ip>.
 */
public class MailboxApplication {

    public static void main(String[] args) {
        Micronaut.run(MailboxApplication.class, args);
    }
}

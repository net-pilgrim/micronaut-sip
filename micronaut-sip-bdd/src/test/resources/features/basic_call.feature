Feature: Basic Audio Call Setup and Teardown (RFC 3261)
  As a SIP user agent
  I want to establish a two-party call with SDP negotiation and tear it down cleanly
  So that conversational audio sessions work reliably

  Scenario: Successful two-party call between Alice and Bob
    Given a SIP endpoint "Alice"
    And a SIP endpoint "Bob"

    When "Alice" sends an "INVITE" to "Bob" with SDP offer:
      | media | proto   | port | codec |
      | audio | RTP/AVP | 4000 | PCMU  |

    Then "Bob" receives an "INVITE" request within 2 seconds
    And header "From" contains parameter "tag"
    And header "Contact" contains parameter "sip:alice@"

    When "Bob" responds with "180 Ringing"
    Then "Alice" receives "180 Ringing" within 2 seconds

    When "Bob" responds with "200 OK" with SDP answer:
      | media | proto   | port | codec |
      | audio | RTP/AVP | 5000 | PCMU  |

    Then "Alice" receives "200 OK" within 2 seconds
    And header "Contact" contains parameter "sip:bob@"
    And SDP negotiated codec is "PCMU"
    And the dialog between "Alice" and "Bob" is in state "CONFIRMED"

    When "Alice" sends an ACK to "Bob"
    Then "Bob" receives an "ACK" request within 1 second

    # Teardown
    When "Alice" sends a BYE to "Bob"
    Then "Bob" receives a "BYE" request within 2 seconds

    When "Bob" responds with "200 OK"
    Then "Alice" receives "200 OK" within 2 seconds
    And the dialog between "Alice" and "Bob" is in state "TERMINATED"

Feature: Early Media and Session Progress (RFC 3960 / RFC 3261)
  As a SIP network participant
  I want early media negotiated in provisional responses
  So that in-band ringback tones and announcements can be played before call answer

  Scenario: Early media negotiated via 183 Session Progress with SDP
    Given a SIP endpoint "Alice"
    And a SIP endpoint "Bob"

    When "Alice" sends an "INVITE" to "Bob" with SDP offer:
      | media | proto   | port | codec |
      | audio | RTP/AVP | 4000 | PCMU  |

    Then "Bob" receives an "INVITE" request within 2 seconds
    And header "From" contains parameter "tag"
    And the dialog between "Alice" and "Bob" is in state "EARLY"

    When "Bob" responds with "183 Session Progress" with SDP answer:
      | media | proto   | port | codec |
      | audio | RTP/AVP | 5000 | PCMU  |

    Then "Alice" receives "183 Session Progress" within 2 seconds
    And header "Content-Type" equals "application/sdp"
    And SDP negotiated codec is "PCMU"
    And the dialog between "Alice" and "Bob" is in state "EARLY"

    When "Bob" responds with "200 OK"
    Then "Alice" receives "200 OK" within 2 seconds
    And the dialog between "Alice" and "Bob" is in state "CONFIRMED"

    When "Alice" sends an ACK to "Bob"
    Then "Bob" receives an "ACK" request within 1 second

    # Teardown
    When "Alice" sends a BYE to "Bob"
    Then "Bob" receives a "BYE" request within 2 seconds
    When "Bob" responds with "200 OK"
    Then "Alice" receives "200 OK" within 2 seconds
    And the dialog between "Alice" and "Bob" is in state "TERMINATED"

Feature: CallController Integration Testing
  As a SIP client
  I want to test the real CallController in the sip-app server
  So that I can verify the actual production SIP stack behavior

  Scenario: INVITE to server triggers CallController and receives proper call flow
    Given the SIP server is running
    And a SIP endpoint "Alice"

    When "Alice" sends an "INVITE" to "bob" with SDP offer:
      | media | proto   | port | codec |
      | audio | RTP/AVP | 4000 | PCMU  |

    Then "Alice" receives "180 Ringing" within 2 seconds
    And "Alice" receives "200 OK" within 3 seconds
    And header "Contact" contains parameter "sip:"
    And SDP negotiated codec is "PCMU"

    When "Alice" sends an ACK to "bob"
    When "Alice" sends a BYE to "bob"
    Then "Alice" receives "200 OK" within 2 seconds

  Scenario: Server responds to OPTIONS with capabilities
    Given the SIP server is running
    And a SIP endpoint "Client"

    When "Client" sends an "OPTIONS" to "server"
    Then "Client" receives "200 OK" within 2 seconds
    And header "Allow" contains parameter "INVITE"

  Scenario: Server handles multiple concurrent calls
    Given the SIP server is running
    And a SIP endpoint "Alice"
    And a SIP endpoint "Bob"

    When "Alice" sends an "INVITE" to "user1" with SDP offer:
      | media | proto   | port | codec |
      | audio | RTP/AVP | 4000 | PCMU  |

    When "Bob" sends an "INVITE" to "user2" with SDP offer:
      | media | proto   | port | codec |
      | audio | RTP/AVP | 5000 | PCMU  |

    Then "Alice" receives "180 Ringing" within 2 seconds
    Then "Bob" receives "180 Ringing" within 2 seconds

    Then "Alice" receives "200 OK" within 3 seconds
    Then "Bob" receives "200 OK" within 3 seconds

  Scenario: CANCEL request aborts pending INVITE
    Given the SIP server is running
    And a SIP endpoint "Alice"

    When "Alice" sends an "INVITE" to "bob" with SDP offer:
      | media | proto   | port | codec |
      | audio | RTP/AVP | 4000 | PCMU  |

    Then "Alice" receives "180 Ringing" within 2 seconds

    When "Alice" sends a "CANCEL" to "bob"
    Then "Alice" receives "200 OK" within 1 second
    And "Alice" receives "487 Request Terminated" within 2 seconds

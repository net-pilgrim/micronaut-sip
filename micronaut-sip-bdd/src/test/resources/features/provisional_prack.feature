Feature: Reliable Provisional Responses and PRACK (RFC 3262)
  As a SIP network participant
  I want reliable provisional responses acknowledged via PRACK
  So that early media and session progress are guaranteed

  Scenario: 183 Session Progress acknowledged via PRACK
    Given a SIP endpoint "Alice"
    And a SIP endpoint "Bob"

    When "Alice" sends an "INVITE" to "Bob" with headers:
      | Header-Name | Value  |
      | Supported   | 100rel |

    Then "Bob" receives an "INVITE" request within 2 seconds

    When "Bob" responds with "183 Session Progress" with headers:
      | Header-Name | Value  |
      | Require     | 100rel |
      | RSeq        | 1      |

    Then "Alice" receives "183 Session Progress" within 2 seconds
    And header "RSeq" equals "1"

    When "Alice" sends PRACK acknowledging the provisional response to "Bob"
    Then "Bob" receives an "PRACK" request within 2 seconds
    And header "RAck" contains parameter "1"

    When "Bob" responds with "200 OK"
    Then "Alice" receives "200 OK" within 2 seconds

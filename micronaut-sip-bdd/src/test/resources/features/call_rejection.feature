Feature: SIP Call Rejection and Failure Handling
  As a callee unable to accept a call
  I want to reject incoming calls with proper status codes and headers
  So that callers are informed of busy state

  Scenario: Callee rejects call with 486 Busy Here
    Given a SIP endpoint "Alice"
    And a SIP endpoint "Bob"

    When "Alice" sends an "INVITE" to "Bob"
    Then "Bob" receives an "INVITE" request within 2 seconds

    When "Bob" responds with "486 Busy Here" with headers:
      | Header-Name | Value |
      | Retry-After | 60    |

    Then "Alice" receives "486 Busy Here" within 2 seconds
    And header "Retry-After" equals "60"

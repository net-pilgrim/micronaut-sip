Feature: SIP Digest Authentication Challenge and Retry (RFC 2617)
  As a secure SIP service
  I want to challenge unauthenticated requests with 401 Unauthorized
  So that valid digest credentials can be validated on retry

  Scenario: Registration challenged with 401 Unauthorized and retried with credentials
    Given a SIP endpoint "Alice" with username "alice" and password "secretpass"
    And a SIP endpoint "Registrar"

    # Step 1: Initial unauthenticated REGISTER
    When "Alice" sends an "REGISTER" to "Registrar" with headers:
      | Header-Name | Value |
      | Expires     | 3600  |

    Then "Registrar" receives an "REGISTER" request within 2 seconds

    # Step 2: Challenge issued by Registrar
    When "Registrar" responds with "401 Unauthorized" with headers:
      | Header-Name      | Value                                                              |
      | WWW-Authenticate | Digest realm="sip.domain", nonce="7d8f921e4a", qop="auth", algorithm=MD5 |

    Then "Alice" receives "401 Unauthorized" within 2 seconds
    And header "WWW-Authenticate" matches regex "Digest realm=.*, nonce=.*"

    # Step 3: Alice retries with calculated MD5 digest
    When "Alice" retries the last request with valid digest credentials
    Then "Registrar" receives an "REGISTER" request within 2 seconds
    And header "Authorization" matches regex "Digest username=\"alice\", realm=\"sip.domain\""

    # Step 4: Registrar accepts authenticated REGISTER
    When "Registrar" responds with "200 OK"
    Then "Alice" receives "200 OK" within 2 seconds

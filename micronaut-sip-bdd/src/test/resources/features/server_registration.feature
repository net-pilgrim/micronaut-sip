Feature: RegistrationController Integration Testing
  As a SIP user agent
  I want to register with the real SIP server
  So that I can verify registration handling works correctly

  Scenario: Successful user registration
    Given the SIP server is running
    And a SIP endpoint "Alice"

    When "Alice" sends a "REGISTER" to "registrar" with headers:
      | Header-Name | Value                           |
      | Expires     | 3600                            |
      | Contact     | <sip:alice@127.0.0.1:5070>      |

    Then "Alice" receives "200 OK" within 2 seconds
    And header "Contact" contains parameter "sip:alice@"

  Scenario: Registration with zero expiry (de-registration)
    Given the SIP server is running
    And a SIP endpoint "Bob"

    When "Bob" sends a "REGISTER" to "registrar" with headers:
      | Header-Name | Value                         |
      | Expires     | 0                             |
      | Contact     | <sip:bob@127.0.0.1:5071>      |

    Then "Bob" receives "200 OK" within 2 seconds

  Scenario: Multiple registrations from different endpoints
    Given the SIP server is running
    And a SIP endpoint "Alice"
    And a SIP endpoint "Bob"
    And a SIP endpoint "Carol"

    When "Alice" sends a "REGISTER" to "registrar" with headers:
      | Header-Name | Value                           |
      | Expires     | 3600                            |

    When "Bob" sends a "REGISTER" to "registrar" with headers:
      | Header-Name | Value                           |
      | Expires     | 3600                            |

    When "Carol" sends a "REGISTER" to "registrar" with headers:
      | Header-Name | Value                           |
      | Expires     | 3600                            |

    Then "Alice" receives "200 OK" within 2 seconds
    Then "Bob" receives "200 OK" within 2 seconds
    Then "Carol" receives "200 OK" within 2 seconds

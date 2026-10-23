# W3C CCXML 1.0 Specification & Integration Architecture for Micronaut Telecom Stack

**Author**: Pilgrim Telecom Platform Team  
**Status**: Formal Specification / Architecture RFC  
**Target Module**: `micronaut-ccxml`  
**Dependencies**: `micronaut-sip`, `micronaut-rtp`, `micronaut-vxml`, `micronaut-msml`

---

## 1. Executive Summary & Architectural Positioning

### 1.1 Context and Problem Statement
In telephony and Next-Generation Network (NGN) media servers, interactive voice applications require two distinct control planes:
1. **Dialog Management**: Prompting, collecting DTMF, running automated speech recognition (ASR), synthesizing text-to-speech (TTS), and handling form field loops (FIA). In our stack, this is executed by **`micronaut-vxml`** (VoiceXML 2.1 + SRGS + MRCPv2).
2. **Call Control & Session Orchestration**: Multi-party call placement, blind and attended transfers, conferencing, bridging audio between connections, supervisor whisper coaching, and fallback handling across asynchronous signaling events.

While VoiceXML 2.1 includes rudimentary call transfer (`<transfer>`), it is inherently single-dialog oriented and synchronously blocks during call progress. W3C CCXML 1.0 (*Voice Browser Call Control: CCXML Version 1.0*) provides a declarative, event-driven state machine specifically designed to sit above SIP signaling, dialog engines, and media mixers.

### 1.2 Architectural Placement in the Stack

```
                          +------------------------------------------------+
                          |              micronaut-ccxml                   |
                          |  - Reactive FIFO Event Queue                   |
                          |  - Event State Machine (<eventprocessor>)       |
                          |  - Connection Table / Dialog Table / Conf Table|
                          +------------------------------------------------+
                             /                     |                    \
        ConnectionBridge    /       DialogBridge   |                     \  MediaBridge
                           v                       v                      v
                  +-----------------+    +-------------------+    +-------------------+
                  |  micronaut-sip  |    |  micronaut-vxml   |    |  micronaut-msml   |
                  |  - SipSession   |    |  - VxmlSession    |    |  - MsmlConfMgr    |
                  |  - INVITE / BYE |    |  - SRGS / MRCP    |    |  - AudioMixer     |
                  |  - REFER/CANCEL |    |  - Prompts / ASR  |    |  - RTP Streams    |
                  +-----------------+    +-------------------+    +-------------------+
```

- **`micronaut-ccxml`** acts as the high-level orchestration layer.
- Telephony signaling is delegated to **`micronaut-sip`** via `ConnectionBridge`.
- Interactive voice dialogs are delegated to **`micronaut-vxml`** via `DialogBridge`.
- Audio mixing, bridging, and directional control (`duplex="full|half"`) are delegated to **`micronaut-msml`** and **`micronaut-rtp`** via `MediaBridge`.

---

## 2. CCXML Document Model & Language Syntax

A CCXML document has the root element `<ccxml>` and contains variable declarations, scripts, and an `<eventprocessor>`.

### 2.1 Core Elements

#### `<ccxml>`
The root container.
- Attributes: `version` (e.g., `"1.0"`).

#### `<var>`
Declares an ECMAScript/expression variable in document scope.
- Attributes: `name` (required), `expr` (optional initial expression).

#### `<assign>`
Assigns a value to a variable.
- Attributes: `name` (required), `expr` (required).

#### `<script>`
Executes inline script expressions or statements.

#### `<eventprocessor>`
The state machine container.
- Attributes: `statevariable` (optional, defaults to `"current_state"` or session variable tracking current state).

#### `<transition>`
Defines an event-handling block matched against incoming events and current states.
- Attributes:
  - `event`: Event name pattern. Supports wildcards (e.g. `connection.*`, `dialog.*`, `*`).
  - `state`: State or list of states in which this transition is valid (e.g. `init`, `active`, `dialog_active`).
  - `cond`: Boolean expression evaluated in the session context. Transition executes only if `cond` is true.

#### Executable Flow Controls
- `<if>`, `<elseif>`, `<else>`: Conditional branch execution.
- `<send>`: Sends an asynchronous or scheduled event to a target session, connection, or itself.
  - Attributes: `event`, `target`, `targettype`, `delay`, `sendid`, `namelist`.
- `<cancel>`: Cancels a previously scheduled event by `sendid`.
- `<exit>`: Terminates the CCXML session and cleans up all active connections, dialogs, and conferences.

---

### 2.2 Telephony Connection Elements

CCXML controls SIP dialogs and call legs through connection elements:

| Element | Attributes | Description |
| :--- | :--- | :--- |
| `<accept>` | `connectionid` | Accepts an incoming alerting call leg (SIP 180 Ringing / 200 OK). |
| `<reject>` | `connectionid`, `reason`, `hints` | Rejects an incoming call leg (SIP 4xx/5xx/6xx response, e.g. 486 Busy Here). |
| `<createcall>` | `dest`, `callerid`, `connectionid`, `timeout`, `aai` | Originates an outbound call leg (SIP INVITE). Emits `connection.progressing` and `connection.connected` (or `connection.failed`). |
| `<redirect>` | `connectionid`, `dest`, `reason` | Deflects the call (SIP 302 Moved Temporarily). |
| `<disconnect>` | `connectionid`, `reason` | Tears down the call leg (SIP BYE or CANCEL). Emits `connection.disconnected`. |

---

### 2.3 Dialog Management Elements

CCXML invokes and controls interactive VoiceXML dialogs via dialog elements:

| Element | Attributes | Description |
| :--- | :--- | :--- |
| `<dialogstart>` | `src`, `type`, `dialogid`, `connectionid`, `conferenceid`, `namelist`, `parameters` | Starts a VoiceXML 2.1 dialog bound to a connection or conference. Emits `dialog.started`. |
| `<dialogterminate>`| `dialogid`, `immediate` | Terminates an active VoiceXML dialog. Emits `dialog.exit`. |
| `<dialogprepare>` | `src`, `type`, `dialogid` | Pre-fetches and parses a VoiceXML document for low-latency startup. Emits `dialog.prepared`. |

---

### 2.4 Conference and Media Bridging Elements

CCXML controls media bridging and audio routing across call legs and conferences:

| Element | Attributes | Description |
| :--- | :--- | :--- |
| `<createconference>`| `conferenceid`, `confname`, `reservedtalkers` | Creates a multi-party audio mixing conference in `micronaut-msml`. Emits `conference.created`. |
| `<destroyconference>`| `conferenceid` | Destroys the conference and unjoins all participants. Emits `conference.destroyed`. |
| `<join>` | `id1`, `id2`, `duplex` | Connects media between two objects (call leg + call leg, or call leg + conference). `duplex="full"` or `duplex="half"`. Emits `conference.joined`. |
| `<unjoin>` | `id1`, `id2` | Breaks the media stream between two objects. Emits `conference.unjoined`. |

---

## 3. CCXML Event Architecture & State Machine

### 3.1 Event Processing Lifecycle
CCXML is fundamentally an **asynchronous event-driven reactive state machine**:
1. An incoming signal (SIP INVITE, VoiceXML dialog completion, timer expiration, or external request) arrives.
2. The event is mapped to a standard `CcxmlEvent` object and pushed onto the session's **FIFO Event Queue**.
3. The CCXML Interpreter pops the event from the queue:
   - Sets the special event variable: `event$` (contains `event$.name`, `event$.connectionid`, `event$.dialogid`, `event$.values`, etc.).
   - Searches `<eventprocessor>` for matching `<transition>` blocks where:
     a. `state` matches current session state (or transition omits `state`),
     b. `event` pattern matches `event$.name` (with prefix/wildcard matching),
     c. `cond` expression evaluates to `true`.
   - The first matching `<transition>` is executed sequentially.
   - Any state mutation (`current_state = '...'`) updates the session state for subsequent events.

### 3.2 Standard Event Catalog

```
+---------------------------------------------------------------------------------+
|                                 CcxmlEvent                                      |
+---------------------------------------------------------------------------------+
  |
  +-- Telephony (connection.*)
  |     |-- connection.alerting       (New inbound SIP INVITE)
  |     |-- connection.progressing    (Outbound SIP 180 Ringing)
  |     |-- connection.connected      (SIP 200 OK + ACK)
  |     |-- connection.disconnected   (SIP BYE received or sent)
  |     |-- connection.failed         (SIP 4xx/5xx/6xx failure, timeout)
  |     `-- connection.redirected     (SIP 302 Moved Temporarily)
  |
  +-- Dialog (dialog.*)
  |     |-- dialog.prepared           (VoiceXML parsed & ready)
  |     |-- dialog.started            (VoiceXML FIA executing)
  |     |-- dialog.exit               (VoiceXML <exit> with return values)
  |     `-- dialog.failed             (VoiceXML parse or execution error)
  |
  +-- Conference (conference.*)
  |     |-- conference.created        (Conference mixer ready)
  |     |-- conference.destroyed      (Conference terminated)
  |     |-- conference.joined         (Leg joined to leg or conf)
  |     `-- conference.unjoined       (Leg unjoined from leg or conf)
  |
  +-- Timers & Custom (send.*, user.*, error.*)
        |-- send.successful           (Delivered asynchronous event)
        |-- error.connection          (Connection failure detail)
        |-- error.dialog              (Dialog failure detail)
        `-- user.*                    (Application-defined events)
```

---

## 4. Bridge Specifications & Integration Contracts

To decouple CCXML execution from underlying protocols while remaining native to Micronaut, `micronaut-ccxml` defines three bridge interfaces:

### 4.1 ConnectionBridge (`net.pilgrim.ccxml.bridge.ConnectionBridge`)
Interacts with `micronaut-sip`'s `SipSession` and `ReactiveSipClient`:
- `Mono<String> createCall(String dest, String callerId, Duration timeout, Map<String, Object> hints)`: Dispatches outbound SIP INVITE and returns `connectionId`.
- `Mono<Void> acceptCall(String connectionId)`: Sends SIP 200 OK for an inbound 180 alerting session.
- `Mono<Void> rejectCall(String connectionId, int statusCode, String reason)`: Sends SIP error response (e.g. 486 Busy, 603 Decline).
- `Mono<Void> redirectCall(String connectionId, String targetUri)`: Sends SIP 302 redirect.
- `Mono<Void> disconnectCall(String connectionId, String reason)`: Sends SIP BYE or CANCEL.

### 4.2 DialogBridge (`net.pilgrim.ccxml.bridge.DialogBridge`)
Interacts with `micronaut-vxml`'s `VxmlSession` and `VxmlInterpreter`:
- `Mono<String> startDialog(String src, String connectionId, String conferenceId, Map<String, Object> params)`: Starts a VoiceXML session bound to the specified connection's RTP stream or conference mixer.
- `Mono<Void> terminateDialog(String dialogId, boolean immediate)`: Halts VoiceXML interpretation.
- `Mono<Void> prepareDialog(String dialogId, String src)`: Pre-parses and caches the VoiceXML AST.

### 4.3 MediaBridge (`net.pilgrim.ccxml.bridge.MediaBridge`)
Interacts with `micronaut-msml`'s `MsmlConferenceManager` and `micronaut-rtp`'s audio mixers:
- `Mono<String> createConference(String confName, Map<String, Object> attributes)`: Allocates an audio mixer room.
- `Mono<Void> destroyConference(String conferenceId)`: Closes mixer room.
- `Mono<Void> join(String id1, String id2, DuplexMode duplex)`: Establishes RTP forwarding.
  - If `duplex == FULL`: Full bidirectional audio between two legs or leg and conference.
  - If `duplex == HALF`: Unidirectional audio (e.g. `id1` audio sent to `id2`, but `id2` audio NOT sent to `id1`).
- `Mono<Void> unjoin(String id1, String id2)`: Detaches RTP forwarding.

---

## 5. Formal Call Control Scenarios

### Scenario 1: Attended Transfer with IVR Fallback
**Goal**: Inbound customer call is greeted by VoiceXML IVR. Customer selects "Talk to Specialist". CCXML places an outbound call to the specialist. While ringing, caller hears ringback/music. If specialist answers, the two legs are joined (`<join>`). If specialist is busy or no answer (SIP 486/408), CCXML drops the outbound leg and restarts VoiceXML dialog to notify the caller: *"Sorry, all specialists are currently busy. Please leave a voicemail."*

```mermaid
sequenceDiagram
    autonumber
    actor Caller
    participant CCXML as CCXML State Machine
    participant VXML as VoiceXML IVR
    actor Agent as Specialist Agent

    Caller->>CCXML: SIP INVITE (connection.alerting)
    CCXML->>Caller: <accept> (SIP 200 OK)
    CCXML->>VXML: <dialogstart src="menu.vxml">
    VXML->>Caller: Plays "Welcome. Press 1 for Specialist"
    Caller->>VXML: DTMF '1'
    VXML->>CCXML: <exit namelist="target"/> (dialog.exit, target="specialist")
    CCXML->>Agent: <createcall dest="sip:agent@pbx">
    Agent-->>CCXML: SIP 180 Ringing (connection.progressing)
    alt Agent Answers (200 OK)
        Agent->>CCXML: SIP 200 OK (connection.connected)
        CCXML->>CCXML: <join id1="caller" id2="agent" duplex="full"/>
        Caller<--Audio-->Agent: Conversing
    else Agent Busy or No-Answer
        Agent->>CCXML: SIP 486 Busy / 408 Timeout (connection.failed)
        CCXML->>VXML: <dialogstart src="busy_fallback.vxml">
        VXML->>Caller: "Specialists are busy. Please leave a message."
    end
```

### Scenario 2: Multi-Party Conferencing with PIN Verification
**Goal**: Multiple inbound callers dial a central conference DID. Each caller is routed to VoiceXML PIN verification dialog. If valid PIN, the leg is joined to an MSML conference mixer (`<join id1="leg" id2="conf">`). When caller hangs up, only that leg is unjoined without disturbing the ongoing conference.

```mermaid
sequenceDiagram
    autonumber
    actor Caller1
    actor Caller2
    participant CCXML as CCXML State Machine
    participant VXML as VoiceXML Auth
    participant MSML as MSML Conference Mixer

    CCXML->>MSML: <createconference confname="exec_board">
    MSML-->>CCXML: conference.created (confId="conf_101")
    
    Caller1->>CCXML: SIP INVITE (connection.alerting)
    CCXML->>Caller1: <accept>
    CCXML->>VXML: <dialogstart src="auth_pin.vxml">
    Caller1->>VXML: DTMF PIN "1234#"
    VXML-->>CCXML: dialog.exit (pin_valid=true)
    CCXML->>MSML: <join id1="caller1" id2="conf_101" duplex="full"/>
    
    Caller2->>CCXML: SIP INVITE (connection.alerting)
    CCXML->>Caller2: <accept>
    CCXML->>VXML: <dialogstart src="auth_pin.vxml">
    Caller2->>VXML: DTMF PIN "1234#"
    VXML-->>CCXML: dialog.exit (pin_valid=true)
    CCXML->>MSML: <join id1="caller2" id2="conf_101" duplex="full"/>
    Caller1<--Audio Mixer-->Caller2: Both in Conference Room 101
```

### Scenario 3: Whisper Coaching / Supervisor Monitoring (`duplex="half"`)
**Goal**: An active customer-agent call leg is running. A supervisor dials into the monitoring bridge. CCXML joins the supervisor to the agent with `duplex="half"` (supervisor audio is heard only by the agent, not customer). The supervisor can coach the agent in real time without the caller being aware.

```mermaid
sequenceDiagram
    autonumber
    actor Customer
    actor Agent
    actor Supervisor
    participant CCXML as CCXML Session
    participant Media as Audio Router / Mixer

    Customer<--Two-Way Call-->Agent: Active Call Leg
    Supervisor->>CCXML: Dial into Coaching Bridge
    CCXML->>Media: <join id1="sup" id2="agent" duplex="half"/>
    CCXML->>Media: <join id1="agent" id2="sup" duplex="half"/>
    CCXML->>Media: <join id1="customer" id2="sup" duplex="half"/>
    Note over Customer,Supervisor: Customer hears: Agent only.<br/>Agent hears: Customer + Supervisor.<br/>Supervisor hears: Customer + Agent.
```

### Scenario 4: Outbound Campaign Dialing
**Goal**: Telephony application initiates outbound automated notification call. When recipient answers (`connection.connected`), CCXML attaches a VoiceXML ASR dialog using Mistral MRCPv2. Once the notification or survey is acknowledged, CCXML terminates the call leg.

---

## 6. Implementation Plan for `micronaut-ccxml`

1. **Gradle Integration**: Add `:micronaut-ccxml` to `settings.gradle` and define `micronaut-ccxml/build.gradle`.
2. **AST Model (`net.pilgrim.ccxml.ast`)**:
   - `CcxmlDocument`, `CcxmlEventProcessor`, `CcxmlTransition`, `CcxmlNode`, `CcxmlExecutable`.
   - Control: `CcxmlVar`, `CcxmlAssign`, `CcxmlScript`, `CcxmlIf`, `CcxmlElseIf`, `CcxmlElse`, `CcxmlSend`, `CcxmlCancel`, `CcxmlExit`.
   - Telephony: `CcxmlCreateCall`, `CcxmlAccept`, `CcxmlReject`, `CcxmlRedirect`, `CcxmlDisconnect`.
   - Dialog: `CcxmlDialogStart`, `CcxmlDialogTerminate`, `CcxmlDialogPrepare`.
   - Conference: `CcxmlCreateConference`, `CcxmlDestroyConference`, `CcxmlJoin`, `CcxmlUnjoin`.
3. **XML Parser (`net.pilgrim.ccxml.parser.CcxmlParser`)**:
   - Hardened `DocumentBuilderFactory` preventing XXE/SSRF attacks.
   - Comprehensive parsing of elements, attributes, child transition trees, and embedded scripts.
4. **Expression Evaluator (`net.pilgrim.ccxml.eval.CcxmlExpressionEvaluator`)**:
   - Supports variable references, dotted navigation (`event$.connectionid`, `event$.values.target`), comparisons (`==`, `!=`, `<`, `>`, `&&`, `||`, `!`), and literal types (strings, booleans, numbers, nulls).
5. **State Machine Runtime (`net.pilgrim.ccxml.runtime`)**:
   - `CcxmlEvent`: Event encapsulating name, connectionId, dialogId, conferenceId, data/values.
   - `CcxmlSession`: Event queue, transition selector, scope variable storage, call/dialog/conf registries.
   - `CcxmlInterpreter`: Top-level orchestration interface.
6. **Bridges (`net.pilgrim.ccxml.bridge`)**:
   - `ConnectionBridge`, `DialogBridge`, `MediaBridge`, and default reactive in-memory implementations.
7. **Verification Test Suite**:
   - Complete unit and scenario test coverage verifying attended transfer, multi-party conference, whisper coaching, and outbound dialing.

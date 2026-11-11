import groovy.json.JsonOutput
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

@Field static final String VERSION = '1.0.0'

// The Comfort cloud pushes unit state over Socket.IO (on Engine.IO 4). Hubitat offers a plain
// websocket, and only to drivers, so this device holds the connection for the Mitsubishi
// Comfort app and handles the few packet types in use by hand.
@Field static final String STREAM_URL = 'wss://socket-prod.kumocloud.com/socket.io/?EIO=4&transport=websocket'
// The service pings every 25 seconds; this long without a word from it means the link is dead.
@Field static final Long QUIET_MS = 70000

// What is known about the connection right now, by device network ID. Kept in memory on
// purpose: after a hub restart or a code update the socket is gone, and so is this.
@Field static final Map LIVE = new ConcurrentHashMap()

metadata {
    definition(
        name: 'Mitsubishi Comfort Stream',
        namespace: 'thorvald',
        author: 'Thorvald Natvig',
        importUrl: 'https://raw.githubusercontent.com/thorvald/hubitat-apps/main/apps/mitsubishi-comfort/' +
            'mitsubishi-comfort-stream.groovy'
    ) {
        attribute 'connection', 'enum', ['connected', 'disconnected']
    }
}

void installed() {
    updateDataValue('driverVersion', VERSION)
    publish('disconnected')
}

// ---- Called by the Mitsubishi Comfort app ----

// Connects, replacing any connection already open, and follows the given units.
void connectStream(String token, List serials) {
    // ownClose: the hub will report the connection replaced here as closing; that is not a loss.
    LIVE[device.deviceNetworkId] = [
        token: token, serials: serials, joined: false, lost: false, seenAt: now(), ownClose: true
    ]
    if (getDataValue('driverVersion') != VERSION) {
        updateDataValue('driverVersion', VERSION)
    }
    interfaces.webSocket.close()
    // No websocket-level pings from this end: the service sends its own every 25 seconds, and
    // streamAlive() watches for those.
    interfaces.webSocket.connect(STREAM_URL, pingInterval: -1, headers: ['Authorization': 'Bearer ' + token])
}

// Closes the connection for good; the app calls connectStream() when it wants one again.
void disconnectStream() {
    LIVE.remove(device.deviceNetworkId)
    interfaces.webSocket.close()
    publish('disconnected')
}

// Follows these units too, on this connection and on the next: units found after it opened.
void followUnits(List serials) {
    Map live = LIVE[device.deviceNetworkId]
    List added = live == null ? [] : serials - live.serials
    if (!added) {
        return
    }
    live.serials = live.serials + added
    if (live.joined) {
        added.each { serial -> follow(serial) }
    }
}

// True while the session is joined and the service has been heard from recently.
boolean streamAlive() {
    Map live = LIVE[device.deviceNetworkId]
    return live != null && live.joined && now() - (live.seenAt as Long) < QUIET_MS
}

// Asks the service about units: 'device_status_v2' for whether each is connected, or
// 'force_adapter_request' with kind 'iuStatus' to have each report its state now.
void askUnits(String request, List serials, String kind = null) {
    if (!LIVE[device.deviceNetworkId]?.joined) {
        return
    }
    serials.each { serial -> emit(kind ? [request, serial, kind] : [request, serial]) }
}

// ---- Called by the hub ----

// One Engine.IO packet per message: 0 opens the transport, 1 closes it, 2 is a ping, 4x is Socket.IO.
void parse(String message) {
    Map live = LIVE[device.deviceNetworkId]
    if (live == null) {
        return
    }
    live.seenAt = now()
    if (message == '2') {
        interfaces.webSocket.sendMessage('3')
    } else if (message.startsWith('0')) {
        // The transport is open; join the default namespace. The token goes here as well as
        // in the connection's header, as other clients of this service send it.
        live.ownClose = false
        interfaces.webSocket.sendMessage('40' + JsonOutput.toJson([token: live.token]))
    } else if (message.startsWith('40')) {
        joined(live)
    } else if (message.startsWith('42')) {
        forward(message.substring(2))
    } else if (message == '1' || message.startsWith('41') || message.startsWith('44')) {
        lost(live, "the service ended the session (${message.take(120)})")
    }
}

void webSocketStatus(String status) {
    Map live = LIVE[device.deviceNetworkId]
    if (live == null || status.startsWith('status: open')) {
        // Not a connection the app still wants, or the transport just opened; in that case
        // the service speaks first and parse() takes it from there.
        return
    }
    if (live.ownClose && status.startsWith('status: closing')) {
        // This reports the end of the connection that connectStream() replaced.
        live.ownClose = false
        return
    }
    lost(live, status)
}

// ---- Internals ----

private void joined(Map live) {
    live.joined = true
    live.lost = false
    live.serials.each { serial -> follow(serial) }
    publish('connected')
    parent?.streamChanged(true, 'joined')
}

private void lost(Map live, String reason) {
    if (live.lost) {
        // Already reported; a refused session is often followed by the socket closing.
        return
    }
    live.lost = true
    live.joined = false
    publish('disconnected')
    parent?.streamChanged(false, reason)
}

// Subscribes to a unit's changes, and asks whether it is connected and for a report of its state now.
private void follow(String serial) {
    emit(['subscribe', serial])
    emit(['device_status_v2', serial])
    emit(['force_adapter_request', serial, 'iuStatus'])
}

// A Socket.IO event is a JSON list: its name, then its arguments.
private void emit(List event) {
    interfaces.webSocket.sendMessage('42' + JsonOutput.toJson(event))
}

private void forward(String json) {
    List event = parseJson(json)
    String name = event[0]
    // Acknowledgements, adapter details and capability profiles also arrive; the app has no use for them.
    if (name == 'device_update' || name == 'device_status_v2') {
        parent?.streamEvent(name, event[1])
    }
}

private void publish(String connection) {
    if (device.currentValue('connection') != connection) {
        sendEvent(name: 'connection', value: connection, descriptionText: "${device.displayName} is ${connection}")
    }
}

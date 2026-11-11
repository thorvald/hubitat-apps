import groovy.transform.Field
import groovyx.net.http.HttpResponseException
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Field static final String VERSION = '1.0.0'

// The Comfort cloud API is unofficial; AGENTS.md lists the references this follows.
@Field static final String API_URI = 'https://app-prod.kumocloud.com'
// The API requires an app version header; this is the one other clients are known to work with.
@Field static final String API_APP_VERSION = '3.2.4'
@Field static final String JSON_TYPE = 'application/json'
@Field static final String UNIT_DRIVER = 'Mitsubishi Comfort Unit'
@Field static final String DNI_PREFIX = 'comfort-'
// The device that holds the websocket for live updates; Hubitat allows those only in drivers.
@Field static final String STREAM_DRIVER = 'Mitsubishi Comfort Stream'
@Field static final String STREAM_DNI = 'comfortstream'

// The form in which the service stamps its rows and events. Written this way, times compare as text.
@Field static final DateTimeFormatter ROW_TIME =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

// Access tokens last about 20 minutes; renew them at 15.
@Field static final Long ACCESS_TOKEN_MS = 900000
// The service accepts a command at once and the unit applies it some seconds later; units
// have been seen to confirm within about six. For this long after a command is accepted,
// what the service reports of the unit does not replace what the command asked for. Then
// the unit is asked again.
@Field static final Long HOLD_MS = 8000
@Field static final Integer FOLLOW_UP_SECONDS = 10
// A command's answer arrives within the request's 20 second limit; after this long without
// one, give up on it.
@Field static final Integer COMMAND_TIMEOUT_SECONDS = 30
@Field static final Integer RETRY_DISCOVERY_SECONDS = 300
@Field static final Integer RETRY_STREAM_SECONDS = 10
@Field static final Integer OFFLINE_AFTER_FAILURES = 5

definition(
    name: 'Mitsubishi Comfort',
    namespace: 'thorvald',
    author: 'Thorvald Natvig',
    description: 'Adds the indoor units of a Mitsubishi Comfort (Kumo Cloud) account as thermostats.',
    category: 'Integrations',
    iconUrl: '',
    iconX2Url: '',
    iconX3Url: '',
    importUrl: 'https://raw.githubusercontent.com/thorvald/hubitat-apps/main/apps/mitsubishi-comfort/' +
        'mitsubishi-comfort.groovy',
    singleInstance: true,
    singleThreaded: true
)

preferences {
    page name: 'mainPage', title: '', install: true, uninstall: true
}

def mainPage() {
    dynamicPage(name: 'mainPage', title: "Mitsubishi Comfort ${VERSION}", install: true, uninstall: true) {
        section('Comfort account') {
            input 'username', 'text', title: 'Email', required: true
            input 'password', 'password', title: 'Password', required: true
        }
        section('Options') {
            input 'liveUpdates', 'bool',
                title: 'Live updates',
                description: 'Receive changes from the Comfort service as they happen, over a websocket.',
                defaultValue: true
            input 'pollMinutes', 'enum',
                title: 'Also read every unit\'s state every',
                options: [['1': '1 minute'], ['5': '5 minutes'], ['10': '10 minutes'], ['15': '15 minutes'],
                          ['30': '30 minutes']],
                defaultValue: '1',
                required: true
            input 'logEnable', 'bool', title: 'Enable debug logging for 30 minutes', defaultValue: false
        }
        section('Status') {
            paragraph statusText()
        }
    }
}

void installed() {
    initialize()
}

void updated() {
    initialize()
}

void uninstalled() {
    getChildDevice(STREAM_DNI)?.disconnectStream()
    getChildDevices().each { child -> deleteChildDevice(child.deviceNetworkId) }
}

void initialize() {
    unschedule()
    ['authFailed', 'authRetryAt', 'pauseUntil', 'streamRetryAt', 'streamAttempts'].each { key ->
        state.remove(key)
    }
    if (state.account != settings.username) {
        // Another account, so the old session is of no use. Otherwise keep it: the service
        // limits how often an account may sign in.
        ['accessToken', 'refreshToken', 'accessExpires'].each { key -> state.remove(key) }
        state.account = settings.username
    }
    state.holds = [:]
    state.failures = [:]
    state.inFlight = [:]
    state.waiting = [:]
    state.commanded = []
    if (settings.logEnable) {
        runIn(1800, 'logsOff')
    }
    if (!wantsStream()) {
        removeStream()
    }
    if (!settings.username || !settings.password) {
        showStatus('Enter the email and password of your Comfort account, then select Done.')
        return
    }
    switch (settings.pollMinutes as String) {
        case '5': runEvery5Minutes('poll'); break
        case '10': runEvery10Minutes('poll'); break
        case '15': runEvery15Minutes('poll'); break
        case '30': runEvery30Minutes('poll'); break
        default: runEvery1Minute('poll')
    }
    runEvery1Hour('discover')
    if (wantsStream()) {
        runEvery1Minute('checkStream')
    }
    discover()
}

void logsOff() {
    app.updateSetting('logEnable', [value: 'false', type: 'bool'])
}

// ---- Discovery: sites, their zones, and one child device per indoor unit ----

void discover() {
    if (!ensureToken()) {
        if (!state.authFailed) {
            runIn(RETRY_DISCOVERY_SECONDS, 'discover')
        }
        return
    }
    asynchttpGet('handleSites', apiRequest('/v3/sites'))
}

void handleSites(response, Map ignored) {
    Map answer = readAnswer(response)
    if (!answer.ok) {
        discoveryFailed('the list of sites', answer)
        return
    }
    answer.json.each { site ->
        if (site?.id) {
            asynchttpGet('handleZones', apiRequest("/v3/sites/${site.id}/zones"), [site: site.name ?: site.id])
        }
    }
}

void handleZones(response, Map data) {
    Map answer = readAnswer(response)
    if (!answer.ok) {
        discoveryFailed("the zones of ${data.site}", answer)
        return
    }
    List serials = []
    answer.json.each { zone ->
        Map adapter = zone?.adapter ?: [:]
        String serial = adapter.deviceSerial
        // Headless adapters are outdoor-unit stations, which have nothing to control.
        if (serial && !adapter.isHeadless && unitDevice(serial, zone.name as String)) {
            serials << serial
        }
    }
    logDebug "site ${data.site}: units ${serials}"
    showStatus("Connected. Found ${serials.size()} indoor unit(s) at ${data.site}.")
    serials.each { serial ->
        asynchttpGet('handleProfile', apiRequest("/v3/devices/${serial}/profile"), [serial: serial])
        requestState(serial)
    }
    checkStream()
}

void handleProfile(response, Map data) {
    Map answer = readAnswer(response)
    // Some accounts get the profile wrapped in a one-element list.
    Map profile = answer.ok ? [answer.json].flatten().find() : null
    if (!profile) {
        noteStatus(answer)
        logDebug "no profile for ${data.serial}: ${answer.message}"
        return
    }
    logDebug "profile of ${data.serial}: ${profile}"
    getChildDevice(DNI_PREFIX + data.serial)?.applyProfile(profile)
}

// ---- Reading state on a timer ----
// This runs with live updates on as well: other clients of this service have seen its stream
// stay connected yet stop delivering, and a regular read puts a bound on that.

void poll() {
    if (now() < stamp(state.pauseUntil)) {
        return
    }
    boolean signedIn = ensureToken()
    unitDevices().each { child ->
        String serial = serialOf(child.deviceNetworkId)
        if (signedIn) {
            requestState(serial)
        } else {
            unitFailed(serial, child, 'not signed in to the Comfort service')
        }
    }
}

// Runs shortly after a command: reads the units again and, with live updates connected, has
// the units that were commanded report their own state.
void pollAfterCommand() {
    List serials = state.commanded ?: []
    state.commanded = []
    if (state.streamUp && serials) {
        getChildDevice(STREAM_DNI)?.askUnits('force_adapter_request', serials, 'iuStatus')
    }
    poll()
}

// Called by a unit's refresh command.
void refreshUnit(String dni) {
    if (!ensureToken()) {
        return
    }
    String serial = serialOf(dni)
    requestState(serial)
    if (state.streamUp) {
        // Also have the unit itself report, as the Comfort app does when it refreshes.
        getChildDevice(STREAM_DNI)?.askUnits('force_adapter_request', [serial], 'iuStatus')
    }
}

void handleState(response, Map data) {
    String serial = data.serial
    def child = getChildDevice(DNI_PREFIX + serial)
    if (!child) {
        return
    }
    Map answer = readAnswer(response)
    if (!answer.ok || !answer.json) {
        noteStatus(answer)
        unitFailed(serial, child, answer.message as String)
        return
    }
    unitAnswered(serial, child)
    if (stamp(data.sent) != stamp((state.sent ?: [:])[serial])) {
        // The unit was sent a command after this was asked for, so it may describe the unit
        // as it was before.
        return
    }
    logDebug "state of ${serial}: ${answer.json}"
    // The service may answer with an old copy; the unit device goes by the time stamp on it.
    if (child.applyState(answer.json)) {
        state.lastUpdate = now()
    }
    if (!state.streamUp) {
        // With live updates connected, the service's own word on this is used instead; see streamEvent().
        child.applyHealth(answer.json.connected != false)
    }
}

// ---- Live updates ----
// The Stream device holds the websocket and calls streamChanged() and streamEvent().

// Runs every minute: keeps the connection up, and while it is up asks after each unit.
void checkStream() {
    List serials = unitSerials()
    if (!wantsStream() || !serials) {
        return
    }
    def stream = getChildDevice(STREAM_DNI)
    // Units found since the connection opened, such as those of a second site.
    stream?.followUnits(serials)
    if (stream?.streamAlive()) {
        state.streamUp = true
        // The service reports a unit dropping off, but not reliably its return, so ask.
        stream.askUnits('device_status_v2', serials)
        return
    }
    if (now() < stamp(state.streamRetryAt)) {
        // An attempt is under way, or the last one failed and it is too soon for the next.
        return
    }
    if (state.streamUp) {
        state.streamUp = false
        log.warn 'Live updates have gone quiet; reconnecting'
    }
    openStream(serials)
}

void retryStream() {
    checkStream()
}

// Called by the Stream device when its session is joined or lost.
void streamChanged(boolean up, String reason) {
    boolean wasUp = state.streamUp
    state.streamUp = up
    if (up) {
        state.streamAttempts = 0
        if (!wasUp) {
            log.info 'Live updates connected'
        }
        return
    }
    String text = reason.toLowerCase()
    if (['401', 'unauthor', 'jwt', 'expired', 'token'].any { hint -> text.contains(hint) }) {
        // The access token was not accepted; get a new one before trying again.
        state.accessExpires = 0
    }
    if (wasUp) {
        log.warn "Live updates lost (${reason}); reading state on the timer until they return"
    } else if (state.streamAttempts == 1) {
        log.warn "Live updates could not connect (${reason}); reading state on the timer and trying again"
    } else {
        logDebug "live updates still not connected: ${reason}"
    }
    runIn(RETRY_STREAM_SECONDS, 'retryStream')
}

// Called by the Stream device with what the service pushes: 'device_update' carries a unit's
// state, 'device_status_v2' whether the unit is connected to the cloud.
void streamEvent(String name, Map payload) {
    String serial = payload.deviceSerial
    def child = serial ? getChildDevice(DNI_PREFIX + serial) : null
    if (!child) {
        return
    }
    logDebug "live ${name} for ${serial}: ${payload}"
    if (name == 'device_status_v2') {
        child.applyHealth(payload.status != 'disconnected')
        return
    }
    state.lastUpdate = now()
    // A change that carries no time of its own counts as arriving now.
    Map changes = payload.updatedAt || payload.date ? payload : payload + [date: hubTime()]
    // While a command is out, and for a while after it is accepted, the unit may not have
    // applied it yet. What it says then is taken in, but does not replace what was asked for;
    // pollAfterCommand() has the unit report again later.
    child.applyUpdate(changes, commandOut(serial) || now() < holdOf(serial))
}

private void openStream(List serials) {
    // After a failed attempt wait 1, 2, 4, 8 and then 15 minutes before the next.
    Integer attempts = ((state.streamAttempts ?: 0) as Integer) + 1
    state.streamAttempts = attempts
    state.streamRetryAt = now() + Math.min(1 << Math.min(attempts - 1, 4), 15) * 60000L
    def stream = streamDevice()
    if (stream && ensureToken()) {
        logDebug "opening live updates for ${serials}"
        stream.connectStream(state.accessToken as String, serials)
    }
}

private void removeStream() {
    def stream = getChildDevice(STREAM_DNI)
    if (stream) {
        stream.disconnectStream()
        deleteChildDevice(STREAM_DNI)
    }
    state.streamUp = false
}

private boolean wantsStream() {
    return settings.liveUpdates != false
}

// ---- Commands ----
// One command per unit is out at a time, so that they take effect in the order they were
// given. What is asked of a unit meanwhile waits, merged, until the answer is in or overdue.

// Called by a unit with the fields to change, for example [spHeat: 21.5]. False when it could not be sent.
boolean sendUnitCommand(String dni, Map commands) {
    String serial = serialOf(dni)
    if (!ensureToken()) {
        log.warn "Cannot send ${commands} to ${unitName(serial)}: not signed in to the Comfort service"
        return false
    }
    Map waiting = state.waiting ?: [:]
    Map earlier = waiting.remove(serial) ?: [:]
    if (commandOut(serial)) {
        waiting[serial] = earlier + commands
        state.waiting = waiting
        return true
    }
    state.waiting = waiting
    Map lost = (state.inFlight ?: [:])[serial]
    if (lost) {
        // Its answer is overdue, and checkCommands() has yet to give up on it. What waited
        // for that answer goes out with this command, which wins where the two overlap.
        log.warn "The Comfort service gave no answer to ${lost.commands} for ${unitName(serial)}"
    }
    postCommand(serial, earlier + commands, false)
    return true
}

void handleCommand(response, Map data) {
    String serial = data.serial
    Map commands = data.commands
    Map answer = readAnswer(response)
    Map out = (state.inFlight ?: [:])[serial]
    if (!out || stamp(out.id) != stamp(data.id)) {
        // This command was given up on, and the unit may have been sent another since.
        logDebug "late answer to ${commands} for ${serial}: ${answer.message}"
        return
    }
    List accepted = answer.json?.devices
    if (answer.ok && (accepted == null || accepted.contains(serial))) {
        logDebug "command ${commands} accepted for ${serial}"
        // The unit applies it some seconds from now, and until then the service may report
        // the unit as it was before. A change on the live connection is judged by when it
        // arrives, and a read by the service's own time stamp on it. The service's date
        // header has no fraction of a second, so the command was accepted up to a second
        // after the time it gives.
        hold(serial, now() + HOLD_MS)
        Instant appliedBy = serviceTime(response).plusMillis(1000 + HOLD_MS)
        getChildDevice(DNI_PREFIX + serial)?.commandAccepted(ROW_TIME.format(appliedBy))
        runIn(FOLLOW_UP_SECONDS, 'pollAfterCommand')
        commandDone(serial)
        return
    }
    noteStatus(answer)
    if (answer.status == 401 && !data.retried) {
        // The token had expired. Send again, minus anything a newer command for this unit replaces.
        Map again = unreplaced(serial, commands)
        if (!again) {
            commandDone(serial)
            return
        }
        if (ensureToken()) {
            postCommand(serial, again, true)
            return
        }
    }
    giveUp(serial, commands, "The Comfort service did not accept ${commands} for ${unitName(serial)}: " +
        (answer.ok ? "unit missing from the answer ${answer.json}" : answer.message))
}

// Runs when the answer to a command is due at the latest, and gives up on any that never
// came, so that what has been asked of the unit since is not left waiting.
void checkCommands() {
    Map overdue = (state.inFlight ?: [:]).findAll { serial, out -> !commandOut(serial as String) }
    overdue.each { serial, out ->
        String problem = "The Comfort service gave no answer to ${out.commands} for ${unitName(serial as String)}"
        giveUp(serial as String, out.commands as Map, problem)
    }
}

// A unit's command was refused, or its answer never came: stop showing what it asked for,
// read what the unit is really doing, and go on to what has been asked of the unit since.
private void giveUp(String serial, Map commands, String problem) {
    log.warn problem
    getChildDevice(DNI_PREFIX + serial)?.commandFailed(unreplaced(serial, commands))
    runIn(1, 'pollAfterCommand')
    commandDone(serial)
}

// A unit's command is over, answered or not: send what has been asked of the unit since, if anything.
private void commandDone(String serial) {
    Map inFlight = state.inFlight ?: [:]
    inFlight.remove(serial)
    state.inFlight = inFlight
    Map waiting = state.waiting ?: [:]
    Map next = waiting.remove(serial)
    state.waiting = waiting
    if (!next) {
        return
    }
    if (ensureToken()) {
        postCommand(serial, next, false)
        return
    }
    log.warn "Cannot send ${next} to ${unitName(serial)}: not signed in to the Comfort service"
    getChildDevice(DNI_PREFIX + serial)?.commandFailed(next)
}

private void postCommand(String serial, Map commands, boolean retried) {
    logDebug "sending ${commands} to ${serial}"
    // Commands are numbered as they go out. The number tells the answer to this one from a
    // late answer to one given up on, and a read of the unit begun before this one from a
    // read begun after.
    Long id = stamp(state.commandId) + 1
    state.commandId = id
    Map inFlight = state.inFlight ?: [:]
    inFlight[serial] = [id: id, at: now(), commands: commands]
    state.inFlight = inFlight
    Map sent = state.sent ?: [:]
    sent[serial] = id
    state.sent = sent
    state.commanded = ((state.commanded ?: []) + serial).unique()
    Map params = apiRequest('/v3/devices/send-command')
    params.requestContentType = JSON_TYPE
    params.body = [deviceSerial: serial, commands: commands]
    asynchttpPost('handleCommand', params, [serial: serial, id: id, commands: commands, retried: retried])
    // Not in place of an earlier one: each command gets its own check.
    runIn(COMMAND_TIMEOUT_SECONDS + 1, 'checkCommands', [overwrite: false])
}

// Whether the unit has a command out whose answer may still come.
private boolean commandOut(String serial) {
    Map out = (state.inFlight ?: [:])[serial]
    return out != null && now() < stamp(out.at) + COMMAND_TIMEOUT_SECONDS * 1000L
}

// The part of a command that nothing asked of the unit since replaces.
private Map unreplaced(String serial, Map commands) {
    Map newer = (state.waiting ?: [:])[serial] ?: [:]
    return commands.findAll { field -> !newer.containsKey(field.key) }
}

// ---- Signing in ----

// True when there is a usable access token, signing in or renewing it first if needed.
private boolean ensureToken() {
    if (state.authFailed) {
        return false
    }
    if (state.accessToken && now() < stamp(state.accessExpires)) {
        return true
    }
    if (now() < stamp(state.authRetryAt)) {
        return false
    }
    if (state.refreshToken) {
        if (renewToken()) {
            return true
        }
        if (state.refreshToken) {
            // The service could not be reached; the refresh token is still good.
            return false
        }
    }
    return signIn()
}

private boolean signIn() {
    Map answer = blockingPost('/v3/login', [
        username: settings.username,
        password: settings.password,
        appVersion: API_APP_VERSION
    ], null)
    Map token = answer.json?.token ?: [:]
    if (answer.ok && token.access) {
        storeTokens(token.access as String, token.refresh as String)
        logDebug 'signed in'
        return true
    }
    String reason = answer.ok ? 'the answer held no token' : answer.message
    if (unreachable(answer)) {
        retryAuthLater(answer)
        showStatus("Could not sign in to the Comfort service (${reason}). Trying again shortly.")
        log.warn state.status
        return false
    }
    // Retrying with the same details cannot work, and repeated attempts get an account limited.
    state.authFailed = true
    showStatus("The Comfort service rejected the sign-in (${reason}). Check the email and password, " +
        'then select Done. It also answers like this after several sign-ins in a short time; if the ' +
        'details are right, wait half an hour before trying again.')
    log.error state.status
    return false
}

private boolean renewToken() {
    String refresh = state.refreshToken
    Map answer = blockingPost('/v3/refresh', [refresh: refresh], refresh)
    if (answer.ok && answer.json?.access) {
        storeTokens(answer.json.access as String, (answer.json.refresh ?: refresh) as String)
        logDebug 'renewed the access token'
        return true
    }
    if (unreachable(answer)) {
        retryAuthLater(answer)
        logDebug "could not renew the access token: ${answer.message}"
        return false
    }
    // The refresh token has expired or was replaced; sign in again.
    state.remove('refreshToken')
    return false
}

private void storeTokens(String access, String refresh) {
    state.accessToken = access
    state.refreshToken = refresh
    state.accessExpires = now() + ACCESS_TOKEN_MS
    state.remove('authRetryAt')
    if (state.streamUp) {
        // The live connection was opened with the old token; reopen it with this one, as other
        // clients do, so it is never left running on an expired token.
        openStream(unitSerials())
    }
}

// No answer, a timeout, a rate limit or a server error: worth trying again later.
private boolean unreachable(Map answer) {
    Integer status = answer.status as Integer
    return status == null || status == 408 || status == 429 || status >= 500
}

private void retryAuthLater(Map answer) {
    state.authRetryAt = now() + (answer.status == 429 ? 900000 : 120000)
}

// ---- HTTP ----

private Map apiRequest(String path) {
    // The last two ask for current data rather than a stored answer, as other clients of this service do.
    Map headers = [
        'Accept': JSON_TYPE, 'x-app-version': API_APP_VERSION, 'Cache-Control': 'no-cache, no-store',
        'x-allow-cache': 'false'
    ]
    if (state.accessToken) {
        headers['Authorization'] = 'Bearer ' + state.accessToken
    }
    return [uri: API_URI, path: path, headers: headers, contentType: JSON_TYPE, timeout: 20]
}

private void requestState(String serial) {
    if (commandOut(serial)) {
        // The answer could describe the unit as it was before the command, and would not be used.
        return
    }
    // The number of the unit's last command lets handleState() tell whether another went out meanwhile.
    Map data = [serial: serial, sent: stamp((state.sent ?: [:])[serial])]
    asynchttpGet('handleState', apiRequest("/v3/devices/${serial}"), data)
}

// A blocking POST, used only for signing in. Returns the same shape as readAnswer().
private Map blockingPost(String path, Map body, String bearer) {
    Map params = apiRequest(path)
    params.requestContentType = JSON_TYPE
    params.body = body
    params.timeout = 15
    if (bearer) {
        params.headers['Authorization'] = 'Bearer ' + bearer
    } else {
        params.headers.remove('Authorization')
    }
    Map answer = [ok: false, status: null, json: null, message: 'no answer']
    try {
        httpPost(params) { response ->
            answer.status = response.status
            answer.json = response.data
            answer.ok = response.status == 200
            answer.message = "HTTP ${response.status}"
        }
    } catch (HttpResponseException e) {
        Object detail = e.response?.data
        answer.status = e.statusCode
        answer.message = "HTTP ${e.statusCode}" + (detail ? ": ${detail}".take(300) : '')
    } catch (Exception e) { // groovylint-disable-line CatchException
        // Timeouts and other network failures; the sandbox offers no narrower type to catch.
        answer.message = e.message ?: e.toString()
    }
    return answer
}

// Turns the answer to an asynchronous request into [ok, status, json, message].
private Map readAnswer(response) {
    Integer status = response.status as Integer
    if (response.hasError() || status == null || status < 200 || status > 299) {
        String detail = null
        try {
            detail = response.errorMessage
            detail = response.errorData ?: detail
        } catch (Exception e) { // groovylint-disable-line CatchException
            // A failure without a body may have nothing to read; the sandbox offers no narrower type.
            logDebug "no error detail: ${e.message}"
        }
        String message = (status == null ? 'no answer' : "HTTP ${status}") + (detail ? ': ' + detail.take(300) : '')
        return [ok: false, status: status, json: null, message: message]
    }
    // Only parse what looks like JSON, so an HTML error page cannot raise an exception here.
    String opening = (response.data as String)?.trim()?.take(1)
    if (opening != '{' && opening != '[') {
        return [ok: false, status: status, json: null, message: "HTTP ${status} with an unreadable answer"]
    }
    return [ok: true, status: status, json: response.json, message: "HTTP ${status}"]
}

// The service's clock when it gave this answer, in whole seconds, which is all its date
// header holds. Going by that clock, the service's time stamps on rows can be compared
// without relying on the hub's. The hub's clock if the answer has no date.
private Instant serviceTime(response) {
    try {
        String header = response.headers?.find { name, value -> name?.equalsIgnoreCase('date') }?.value
        if (header) {
            return ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        }
    } catch (Exception e) { // groovylint-disable-line CatchException
        // An unreadable date; the sandbox offers no narrower type. The hub's clock will do.
        logDebug "no usable date on the answer: ${e.message}"
    }
    return Instant.ofEpochMilli(now())
}

private String hubTime() {
    return ROW_TIME.format(Instant.ofEpochMilli(now()))
}

// Reacts to what a failed answer says about the session: an expired token or a rate limit.
private void noteStatus(Map answer) {
    if (answer.status == 401) {
        state.accessExpires = 0
    } else if (answer.status == 429 && now() >= stamp(state.pauseUntil)) {
        state.pauseUntil = now() + 300000
        log.warn 'The Comfort service is limiting requests; pausing updates for 5 minutes'
    }
}

// ---- Bookkeeping ----

// The child device for an indoor unit, created on first sight.
private Object unitDevice(String serial, String zoneName) {
    return getChildDevice(DNI_PREFIX + serial) ?:
        addDevice(UNIT_DRIVER, DNI_PREFIX + serial, "Mitsubishi ${zoneName ?: serial}")
}

private Object streamDevice() {
    return getChildDevice(STREAM_DNI) ?: addDevice(STREAM_DRIVER, STREAM_DNI, 'Mitsubishi Comfort live updates')
}

// Null when the device cannot be created.
private Object addDevice(String driver, String dni, String label) {
    def child = null
    try {
        child = addChildDevice('thorvald', driver, dni, [name: driver, label: label])
        log.info "Added ${label}"
    } catch (Exception e) { // groovylint-disable-line CatchException
        // The driver is missing or the device ID is taken; the sandbox offers no narrower type.
        log.error "Could not add ${label}: ${e.message}. Is the '${driver}' driver installed?"
    }
    return child
}

private List unitDevices() {
    return getChildDevices().findAll { child -> child.deviceNetworkId.startsWith(DNI_PREFIX) }
}

private List unitSerials() {
    return unitDevices().collect { child -> serialOf(child.deviceNetworkId) }
}

private void discoveryFailed(String what, Map answer) {
    noteStatus(answer)
    log.warn "Could not read ${what} from the Comfort service: ${answer.message}"
    if (!unitDevices()) {
        showStatus("Signed in, but could not read ${what} (${answer.message}). Trying again shortly.")
    }
    runIn(RETRY_DISCOVERY_SECONDS, 'discover')
}

private void unitFailed(String serial, child, String message) {
    Map failures = state.failures ?: [:]
    Integer count = ((failures[serial] ?: 0) as Integer) + 1
    failures[serial] = count
    state.failures = failures
    if (count == 1) {
        log.warn "No update for ${child.displayName}: ${message}"
    } else {
        logDebug "no update for ${child.displayName} (${count} in a row): ${message}"
    }
    if (count == OFFLINE_AFTER_FAILURES) {
        child.applyHealth(false)
    }
}

private void unitAnswered(String serial, child) {
    Map failures = state.failures ?: [:]
    if (failures[serial]) {
        log.info "${child.displayName} is updating again"
        failures.remove(serial)
        state.failures = failures
    }
}

private void hold(String serial, Long until) {
    Map holds = state.holds ?: [:]
    holds[serial] = until
    state.holds = holds
}

private Long holdOf(String serial) {
    return stamp((state.holds ?: [:])[serial])
}

private Long stamp(value) {
    return value == null ? 0L : value as Long
}

private String serialOf(String dni) {
    return dni.startsWith(DNI_PREFIX) ? dni.substring(DNI_PREFIX.length()) : dni
}

private String unitName(String serial) {
    return getChildDevice(DNI_PREFIX + serial)?.displayName ?: serial
}

private void showStatus(String text) {
    state.status = text
}

private String statusText() {
    List lines = [state.status ?: 'Not connected yet. Enter your account details and select Done.']
    List names = unitDevices().collect { child -> child.displayName }.sort()
    if (names) {
        lines << 'Devices: ' + names.join(', ')
        lines << 'Live updates: ' + (!wantsStream() ? 'off' : state.streamUp ? 'connected' : 'not connected')
    }
    if (state.lastUpdate) {
        SimpleDateFormat format = new SimpleDateFormat('yyyy-MM-dd HH:mm:ss', Locale.US)
        if (location.timeZone) {
            format.timeZone = location.timeZone
        }
        lines << 'Last update: ' + format.format(new Date(state.lastUpdate as Long))
    }
    return lines.join('<br>')
}

private void logDebug(String message) {
    if (settings.logEnable) {
        log.debug message
    }
}

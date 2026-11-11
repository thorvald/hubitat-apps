import groovy.json.JsonOutput
import groovy.transform.Field

@Field static final String VERSION = '1.0.0'

// Comfort API names on the left, the names this driver shows on the right. The fan names
// follow the labels in the Comfort app, which differ from the API's own names.
@Field static final Map MODES = [off: 'off', heat: 'heat', cool: 'cool', auto: 'auto', dry: 'dry', vent: 'fan']
@Field static final Map FAN_SPEEDS = [
    auto: 'auto', superQuiet: 'quiet', quiet: 'low', low: 'medium', powerful: 'high', superPowerful: 'powerful'
]
@Field static final List AIR_DIRECTIONS = [
    'auto', 'swing', 'horizontal', 'midhorizontal', 'midpoint', 'midvertical', 'vertical'
]

// The parts of a unit's state that this driver shows.
@Field static final List STATE_FIELDS = [
    'power', 'operationMode', 'previousOperationMode', 'spHeat', 'spCool', 'roomTemp', 'humidity',
    'fanSpeed', 'airDirection', 'displayConfig'
]

// Mitsubishi pairs whole degrees Fahrenheit with half degrees Celsius by table rather than by
// arithmetic, and the two directions differ. These are the pairs its app and remotes use for
// setpoints; outside them the plain conversion applies.
// Celsius setpoint for 61 F through 80 F:
@Field static final List FAHRENHEIT_AS_CELSIUS = [
    16.0, 16.5, 17.0, 17.5, 18.0, 18.5, 19.5, 20.0, 21.0, 21.5,
    22.0, 22.5, 23.0, 23.5, 24.0, 24.5, 25.0, 25.5, 26.0, 26.5
]
// Fahrenheit shown for 16.0 C through 26.5 C, in half-degree steps:
@Field static final List CELSIUS_AS_FAHRENHEIT = [
    61, 62, 63, 64, 65, 66, 67, 67, 68, 69, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80
]

metadata {
    definition(
        name: 'Mitsubishi Comfort Unit',
        namespace: 'thorvald',
        author: 'Thorvald Natvig',
        importUrl: 'https://raw.githubusercontent.com/thorvald/hubitat-apps/main/apps/mitsubishi-comfort/' +
            'mitsubishi-comfort-unit.groovy'
    ) {
        capability 'Actuator'
        capability 'Sensor'
        capability 'Thermostat'
        capability 'TemperatureMeasurement'
        capability 'RelativeHumidityMeasurement'
        capability 'Refresh'

        // Redeclared so the device page offers this unit's values instead of the generic ones.
        command 'setThermostatMode', [
            [name: 'Mode*', type: 'ENUM', constraints: ['off', 'heat', 'cool', 'auto', 'dry', 'fan']]
        ]
        command 'setThermostatFanMode', [
            [name: 'Fan speed*', type: 'ENUM', constraints: ['auto', 'quiet', 'low', 'medium', 'high', 'powerful']]
        ]
        command 'setAirDirection', [
            [
                name: 'Direction*',
                type: 'ENUM',
                constraints: ['auto', 'swing', 'horizontal', 'midhorizontal', 'midpoint', 'midvertical', 'vertical']
            ]
        ]
        command 'dry'
        command 'fanOnly'

        attribute 'airDirection', 'string'
        attribute 'healthStatus', 'enum', ['online', 'offline']
    }

    preferences {
        input name: 'txtEnable', type: 'bool', title: 'Log state changes', defaultValue: true
    }
}

void installed() {
    updateDataValue('driverVersion', VERSION)
    publishSupported()
}

void updated() {
    updateDataValue('driverVersion', VERSION)
}

void refresh() {
    parent?.refreshUnit(device.deviceNetworkId)
}

// ---- Commands ----

void off() {
    setThermostatMode('off')
}

void heat() {
    setThermostatMode('heat')
}

void cool() {
    setThermostatMode('cool')
}

void auto() {
    setThermostatMode('auto')
}

void dry() {
    setThermostatMode('dry')
}

void fanOnly() {
    setThermostatMode('fan')
}

void emergencyHeat() {
    log.warn "${device.displayName} has no emergency heat"
}

void setThermostatMode(mode) {
    String wanted = mode
    String apiMode = MODES.find { entry -> entry.value == wanted }?.key
    if (!apiMode || !supportedModes().contains(wanted)) {
        log.warn "${device.displayName} does not support mode '${wanted}'"
        return
    }
    if (sendToUnit([operationMode: apiMode])) {
        assume([operationMode: apiMode, power: apiMode == 'off' ? 0 : 1])
    }
}

void setHeatingSetpoint(temperature) {
    changeSetpoint('heat', temperature)
}

void setCoolingSetpoint(temperature) {
    changeSetpoint('cool', temperature)
}

void fanAuto() {
    setThermostatFanMode('auto')
}

void fanOn() {
    log.warn "${device.displayName} has no continuous fan setting; set a fan speed instead"
}

void fanCirculate() {
    log.warn "${device.displayName} has no circulate setting; set a fan speed instead"
}

void setThermostatFanMode(fanMode) {
    String wanted = fanMode
    String apiSpeed = FAN_SPEEDS.find { entry -> entry.value == wanted }?.key
    if (!apiSpeed || !supportedFans().contains(wanted)) {
        log.warn "${device.displayName} does not support fan speed '${wanted}'"
        return
    }
    if (sendToUnit([fanSpeed: apiSpeed])) {
        assume([fanSpeed: apiSpeed])
    }
}

void setAirDirection(direction) {
    String wanted = direction
    if (!supportedDirections().contains(wanted)) {
        log.warn "${device.displayName} does not support air direction '${wanted}'"
        return
    }
    if (sendToUnit([airDirection: wanted])) {
        assume([airDirection: wanted])
    }
}

void setSchedule(schedule) {
    log.warn "${device.displayName} does not take schedules from Hubitat (ignored ${schedule})"
}

// ---- Called by the Mitsubishi Comfort app ----

// Four things are remembered between calls:
//   state.unit       what the service last said about the unit;
//   state.assumed    what this driver has asked for, until the unit is heard on it;
//   state.asOf       the service's time stamp on the newest thing it said;
//   state.appliedBy  the time, by the service's clock, by which the unit will have applied
//                    the last command the service accepted.
// What is shown is state.unit with state.assumed laid over it. The service accepts a command
// at once and the unit applies it seconds later, so for a while what the service says of the
// unit may still describe it as it was. That is taken in, but does not replace what was asked for.

// The unit's whole state as the cloud reports it (the answer to GET /v3/devices/{serial}).
// The service may answer with a copy it made a while ago, so the answer is used only when
// its own time stamp is newer than state.asOf, and it replaces what was asked for only when
// that stamp is past state.appliedBy. Returns whether it was used.
boolean applyState(Map reported) {
    String asOf = reported.updatedAt
    if (state.asOf && (!asOf || asOf <= state.asOf)) {
        return false
    }
    state.asOf = asOf
    state.unit = settled(reported).findAll { field -> field.key in STATE_FIELDS }
    if (!state.appliedBy || (asOf && asOf > state.appliedBy)) {
        state.assumed = [:]
    }
    show()
    return true
}

// A change pushed over the live connection, which comes from the unit and is always taken
// in. It may carry only some of the fields, so it is laid over the state last seen. The app
// says when it arrived too early for the unit to have applied the last command.
void applyUpdate(Map changes, boolean early) {
    // Pushed rows carry updatedAt and forced reports carry date, both by the service's clock;
    // the app dates anything that carries neither.
    state.asOf = changes.updatedAt ?: changes.date ?: state.asOf
    Map said = changes.findAll { field -> field.key in STATE_FIELDS && field.value != null }
    state.unit = (state.unit ?: [:]) + said
    if (!early) {
        state.assumed = (state.assumed ?: [:]).findAll { field -> !said.containsKey(field.key) }
    }
    show()
}

// The service accepted a command, and the unit will have applied it by this time on the
// service's clock.
void commandAccepted(String appliedBy) {
    state.appliedBy = appliedBy
}

// A command did not go through: stop showing what it asked for.
void commandFailed(Map commands) {
    List fields = (commands.keySet() as List) + (commands.containsKey('operationMode') ? ['power'] : [])
    state.assumed = (state.assumed ?: [:]).findAll { field -> !(field.key in fields) }
    show()
}

// The app calls this with what it has learned about the unit's connection to the cloud.
void applyHealth(boolean online) {
    publish('healthStatus', online ? 'online' : 'offline')
}

// What the unit can do (the answer to GET /v3/devices/{serial}/profile).
void applyProfile(Map profile) {
    Map lowest = profile.minimumSetPoints ?: [:]
    Map highest = profile.maximumSetPoints ?: [:]

    List modes = ['off', 'cool']
    if (profile.hasModeHeat || highest.heat != null) {
        modes << 'heat'
    }
    if (profile.hasModeAuto || highest.auto != null) {
        modes << 'auto'
    }
    if (profile.hasModeDry) {
        modes << 'dry'
    }
    if (profile.hasModeVent) {
        modes << 'fan'
    }

    Map speedsByCount = [3: ['low', 'medium', 'high'], 4: ['low', 'medium', 'high', 'powerful']]
    List fans = profile.hasFanSpeedAuto == false ? [] : ['auto']
    fans.addAll(speedsByCount[profile.numberOfFanSpeeds as Integer] ?: ['quiet', 'low', 'medium', 'high', 'powerful'])

    List directions = []
    if (profile.hasVaneDir) {
        directions = AIR_DIRECTIONS.findAll { direction -> direction != 'swing' || profile.hasVaneSwing }
    }

    state.profile = [
        modes: modes,
        fans: fans,
        directions: directions,
        heatMin: lowest.heat,
        heatMax: highest.heat,
        coolMin: lowest.cool,
        coolMax: highest.cool
    ]
    updateDataValue('driverVersion', VERSION)
    publishSupported()
}

// ---- Internals ----

// Publishes everything that follows from the unit's state and what has been asked of it.
private void show() {
    Map unit = (state.unit ?: [:]) + (state.assumed ?: [:])
    String degrees = "\u00b0${getTemperatureScale()}"
    Double room = unit.roomTemp
    Double heat = unit.spHeat
    Double cool = unit.spCool
    if (room != null) {
        publish('temperature', displayTemperature(room), degrees)
    }
    if (unit.humidity != null) {
        publish('humidity', unit.humidity, '%rh')
    }
    if (heat != null) {
        publish('heatingSetpoint', displaySetpoint(heat), degrees)
    }
    if (cool != null) {
        publish('coolingSetpoint', displaySetpoint(cool), degrees)
    }
    if (unit.fanSpeed) {
        publish('thermostatFanMode', FAN_SPEEDS[unit.fanSpeed] ?: unit.fanSpeed)
    }
    if (unit.airDirection) {
        publish('airDirection', unit.airDirection)
    }

    String apiMode = unit.power == 0 ? 'off' : unit.operationMode
    if (!apiMode) {
        return
    }
    publish('thermostatMode', hubMode(apiMode))
    Double target = usesCooling(apiMode, unit.previousOperationMode, room, heat) ? cool : heat
    if (target != null) {
        publish('thermostatSetpoint', displaySetpoint(target), degrees)
    }
    publish('thermostatOperatingState', operatingState(apiMode, unit.displayConfig, room, heat, cool))
}

// Shows a change this driver has just asked for, ahead of the unit confirming it. It stays
// until the unit speaks to the same fields, so an unrelated partial update does not undo it.
private void assume(Map change) {
    state.assumed = (state.assumed ?: [:]) + change
    show()
}

private boolean sendToUnit(Map commands) {
    if (parent == null) {
        log.warn "${device.displayName} is not attached to the Mitsubishi Comfort app"
        return false
    }
    return parent.sendUnitCommand(device.deviceNetworkId, commands)
}

private void changeSetpoint(String kind, temperature) {
    if (temperature == null) {
        return
    }
    boolean heating = kind == 'heat'
    Map profile = state.profile ?: [:]
    double wanted = toUnitSetpoint(temperature as double)
    Double lowest = heating ? profile.heatMin : profile.coolMin
    Double highest = heating ? profile.heatMax : profile.coolMax
    double celsius = wanted
    if (lowest != null && celsius < lowest) {
        celsius = lowest
    }
    if (highest != null && celsius > highest) {
        celsius = highest
    }
    if (celsius != wanted) {
        log.warn "${device.displayName} ${kind} setpoint ${temperature} is outside the unit's range; " +
            "using ${displaySetpoint(celsius)} instead"
    }
    Map change = [(heating ? 'spHeat' : 'spCool'): celsius]
    if (sendToUnit(change)) {
        assume(change)
    }
}

// While a change has not reached the unit yet, the cloud reports the unit's own values in
// realValues next to the requested ones. Show what the unit is actually doing.
private Map settled(Map reported) {
    Map unit = [:]
    unit.putAll(reported)
    Map real = reported.realValues ?: [:]
    ['power', 'operationMode', 'spHeat', 'spCool', 'fanSpeed', 'airDirection'].each { key ->
        if (real[key] != null) {
            unit[key] = real[key]
        }
    }
    return unit
}

private String hubMode(String apiMode) {
    if (apiMode == 'autoHeat' || apiMode == 'autoCool') {
        return 'auto'
    }
    return MODES[apiMode] ?: apiMode
}

// Whether the cooling setpoint is the one in effect; otherwise it is the heating setpoint.
private boolean usesCooling(String apiMode, lastMode, Double room, Double heat) {
    String mode = (apiMode == 'off' || apiMode == 'vent') ? lastMode : apiMode
    if (mode == 'auto') {
        return !(room != null && heat != null && room < heat)
    }
    return mode == 'cool' || mode == 'dry' || mode == 'autoCool'
}

private String operatingState(String apiMode, displayConfig, Double room, Double heat, Double cool) {
    // The unit raises its standby flag when it is on but its compressor is resting.
    if (apiMode == 'off' || displayConfig?.standby == true) {
        return 'idle'
    }
    switch (apiMode) {
        case 'heat':
        case 'autoHeat':
            return 'heating'
        case 'cool':
        case 'autoCool':
        case 'dry':
            return 'cooling'
        case 'vent':
            return 'fan only'
        case 'auto':
            // The unit has not said which way it went, so compare against its setpoints.
            if (room != null && cool != null && room > cool) {
                return 'cooling'
            }
            return room != null && heat != null && room < heat ? 'heating' : 'idle'
        default:
            return 'idle'
    }
}

private List supportedModes() {
    return state.profile?.modes ?: (MODES.values() as List)
}

private List supportedFans() {
    return state.profile?.fans ?: (FAN_SPEEDS.values() as List)
}

private List supportedDirections() {
    return state.profile ? (state.profile.directions ?: []) : AIR_DIRECTIONS
}

private void publishSupported() {
    publish('supportedThermostatModes', JsonOutput.toJson(supportedModes()))
    publish('supportedThermostatFanModes', JsonOutput.toJson(supportedFans()))
}

private boolean fahrenheit() {
    return getTemperatureScale() == 'F'
}

// A setpoint as the unit takes it: Celsius in half degrees.
private double toUnitSetpoint(double value) {
    if (fahrenheit()) {
        int index = Math.round(value) - 61
        if (index >= 0 && index < FAHRENHEIT_AS_CELSIUS.size()) {
            return FAHRENHEIT_AS_CELSIUS[index]
        }
    }
    double celsius = fahrenheit() ? (value - 32) * 5 / 9 : value
    return Math.round(celsius * 2) / 2
}

// A setpoint as shown in Hubitat, which in Fahrenheit is what the Comfort app shows for it.
private Number displaySetpoint(double celsius) {
    if (!fahrenheit()) {
        return celsius
    }
    // The table starts at 16.0 C, which is 32 half degrees.
    int index = Math.round(celsius * 2) - 32
    if (index >= 0 && index < CELSIUS_AS_FAHRENHEIT.size()) {
        return CELSIUS_AS_FAHRENHEIT[index]
    }
    return Math.round(celsius * 9 / 5 + 32) as Integer
}

// The room temperature as shown in Hubitat, to one decimal.
private double displayTemperature(double celsius) {
    double value = fahrenheit() ? celsius * 9 / 5 + 32 : celsius
    return Math.round(value * 10) / 10
}

// Sends an event only when the value differs from the current one, so the log shows changes.
private void publish(String name, value, String unit = null) {
    String before = device.currentValue(name)
    String after = value
    // 72 and 72.0 are the same reading.
    boolean sameNumber = before?.isNumber() && after?.isNumber() && before.toDouble() == after.toDouble()
    if (after == null || before == after || sameNumber) {
        return
    }
    String text = "${device.displayName} ${name} is ${value}${unit ?: ''}"
    Map event = [name: name, value: value, descriptionText: text]
    if (unit) {
        event.unit = unit
    }
    sendEvent(event)
    if (settings.txtEnable != false) {
        log.info text
    }
}

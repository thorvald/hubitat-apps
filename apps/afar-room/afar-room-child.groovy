import groovy.json.*
    import hubitat.helper.RMUtils
import groovy.time.TimeCategory
import java.text.SimpleDateFormat

def setVersion(){
    state.name = "Afar Room Child"
    state.version = "1.0.0"
}

definition(
    name: "Afar Room Child",
    namespace: "thorvald",
    author: "Thorvald Natvig",
    description: "Make your rooms smarter by directing them to do what you want, automatically.",
    category: "",
    parent: "thorvald:Afar Room",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    importUrl: "",
)

preferences {
    page name: "mainPage", title: "", install: true, uninstall: true
} 

def logsOff() {
    app.updateSetting("logEnable", [value: "false", type: "bool"])
}

def logDebug(String str) {
    if (logEnable) {
        log.debug("${parent.label}: ${app.label}: ${str}")
    }
}


def installed() {
    logDebug("Installed with settings: ${settings}")
    initialize()
}

def updated() {
    logDebug("Updated with settings: ${settings}")
    unsubscribe()
    unschedule()
    initialize()
    if(logEnable) runIn(3600, logsOff)
}

def initialize() {
    if(switches) {
        subscribe(switches, buttonHandler, ["filterEvents": false])
        logDebug( "Scribed switches")
    }
    if(motions) {
        subscribe(motions, motionHandler, ["filterEvents": false])
        logDebug( "Scribed motions")
    }
    if (lights) {
        subscribe(lights, lightHandler, ["filterEvents": false])
        logDebug("Scribed lights")
    }
}

def mainPage() {
    dynamicPage(name: "mainPage") {
        setVersion()
        theName = app.label
        state.appInstalled = app.getInstallationState() 
        if(theName == null || theName == "") theName = "New Child App"

        section (getFormat("title", "${state.name} - ${theName}")) {
            paragraph getFormat("line")
        }

        section(getFormat("header-green", "General")) {
            label title: "Enter a name for zone", required: true
            input "logEnable", "bool", defaultValue:false, title: "Enable Debug Logging", description: "Debugging", submitOnChange:true
        }

        section(getFormat("headre-greeen", "Devices")) {
            input "switches", "capability.pushableButton", title: "Select switch(es)", multiple: true, submitOnChange:true 
            input "lights", "capability.light", title: "Select light(s)", multiple: true, submitOnChange:true 
            input "motions", "capability.motionSensor", title: "Select sensor(s)", multiple: true, submitOnChange:true 
            input "override", "capability.switch", title: "Select motion override", submitOnChange:true 
            input "alwaysMotion", "bool", defaultValue:false, title: "Enable motion at all time", description: "Motion", submitOnChange:true
            input "minimumLight", "int", defaultValue:null, title: "Minimum light level", description: "Minimum Light", submitOnChange:true
        }
    }
}

def getFormat(type, myText="") {			// Modified from @Stephack Code   
    if(type == "header-green") return "<div style='color:#ffffff;font-weight: bold;background-color:#81BC00;border: 1px solid;box-shadow: 2px 3px #A9A9A9'>${myText}</div>"
    if(type == "line") return "<hr style='background-color:#1A77C9; height: 1px; border: 0;'>"
    if(type == "title") return "<h2 style='color:#1A77C9;font-weight: bold'>${myText}</h2>"
}

def indicator(hue, level) {
    def color = hue + level * 256 + 255*65536 + 1*16777216
    switches.each { sw ->
        sw.setIndicator(color)
    }

}

def lightOn(minLevel) {
    logDebug("lightOn " + minLevel)
    lights.each {light ->
        def ct = light.currentValue("colorTemperature")
        ct = ct == null ? null : ct.doubleValue()
        def level = light.currentValue("level")
        def sw = light.currentValue("switch")
        
        def targetCt = location.mode == "Day" ? 3000 : 2000
        def targetLevel = (sw == "on" && level > minLevel) ? level : minLevel;
        
        if (light.hasCapability("ColorTemperature")) {
            light.setColorTemperature(targetCt, targetLevel);
        } else {
            light.setLevel(targetLevel)
        }

        /*
        if (sw == "off" || level < minLevel) {
            light.setLevel(minLevel)
        } else {
            light.on()
        }

        if (ct != null && Math.abs(ct-targetCt)/targetCt > 0.1) {
            light.setColorTemperature(targetCt)
        }
*/
    }
    state.unwarn = false
}

def lightOff() {
    lights.each {light ->
        logDebug("Offing ${light}")
        light.off()
    }
    state.unwarn = false
}

def isOn() {
    def on = false
    lights.each { light ->
        on = on || (light.currentValue("switch") == "on")
    }
    return on
}

def buttonAction(evt) {
    if (evt.integerValue == 1) {
        if (evt.name == "pushed") {
            lightOn(30)
        }
        if (evt.name == "doubleTapped") {
            lightOn(100)
        }
    }

    if (evt.integerValue == 2) {
        if (evt.name == "pushed") {
            lightOff();
        }
    }

    if (evt.integerValue == 3) {
        if (evt.name == "pushed") {
            lights.each {light ->
                light.setLevel(100)
            }
        }
    }
}

def buttonHandler(evt) {
    if (evt.name != "pushed" && evt.name != "doubleTapped" && evt.name != "held" && evt.name != "released") {
        return
    }
    logDebug("child button ${evt.integerValue} ${evt.name}")

    if (evt.integerValue == 2 && evt.name == "pushed") {
        buttonAction(evt)
    } else if (evt.integerValue == 2 && evt.name == "doubleTapped") {
        logDebug("Double Off")
        parent.buttonHandler(evt)
    } else {
        parent.anyButtonPressed()
        buttonAction(evt)
    }
}

def motionHandler(evt) {
    if (evt.name == "motion") {
        if (override != null && override.currentValue("switch") == "on") {
            logDebug("Override motion ignore")
        } else if (evt.value == "inactive") {
            parent.inactive()
        } else if (evt.value == "active") {
            def warnwake = state.unwarn
            logDebug("Warnwake ${warnwake}")
            parent.active()
            if (!warnwake && (alwaysMotion || location.mode != "Day")) {
                lightOn((minimumLight != null) ? minimumLight.toInteger() : 1)
            }
        }
    }
}

def lightHandler(evt) {
    if (evt.name == "switch") {
        if (isOn()) {
            indicator(160, 255)
        } else {
            indicator(192, 255)
        }
        parent.updateSwitch()
    }
}

def motionTimerWarn() {
    logDebug("Warn")
    lights.each { light ->
        if (light.currentValue("switch") == "on") {
            def level = light.currentValue("level")
            if (level >= 5) {
                state.unwarn = true
                light.setLevel(level / 5)
            }
        }
    }
}

def motionTimerOff() {
    logDebug("Off")
    lightOff()
}

def motionTimerUnwarn() {
    logDebug("Unwarn")
    lights.each { light ->
        if (light.currentValue("switch") == "on") {
            def level = light.currentValue("level")
            if (state.unwarn) {
                light.setLevel(level * 5)
            }
        }
    }
    state.unwarn = false
}

def isActive() {
    def active = false
    motions.each { motion ->
        active = active || (motion.currentValue("motion") == "active")
    }
    return active
}

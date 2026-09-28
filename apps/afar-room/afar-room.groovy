def setVersion(){
    state.name = "Afar Room"
    state.version = "1.0.0"
}

definition(
    name:"Afar Room",
    namespace: "thorvald",
    author: "Thorvald Natvig",
    description: "Our smart rooms",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    importUrl: ""
)

preferences {
    page name: "mainPage", title: "", install: true, uninstall: true
} 

def logsOff() {
    app.updateSetting("logEnable", [value: "false", type: "bool"])
}

def logDebug(String str) {
    if (logEnable) {
        log.debug("${app.label}: ${str}")
    }
}

def installed() {
    log.debug "Installed with settings: ${settings}"
    initialize()
}

def updated() {
    logDebug("Updated with settings: ${settings}")
    logDebug("Master switch ${switches}")
    unsubscribe()
    initialize()
    masterSwitch.each { sw ->
        logDebug("Switch ${sw.properties}")
    }
    if(logEnable) runIn(3600, logsOff)
}

def initialize() {
    logDebug("There are ${childApps.size()} child apps")
    childApps.each {child ->
        logDebug("Child app: ${child.label}")
    }
    if(masterSwitch) {
        subscribe(masterSwitch, buttonHandler, ["filterEvents": false])
        logDebug("Scribed")
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

        if(state.appInstalled == 'COMPLETE'){
            section(getFormat("header-green", "Child Apps")) {
                app(name: "anyOpenApp", appName: "Afar Room Child", namespace: "thorvald", title: "<b>Add a new 'Afar Room' child</b>", multiple: true)
            }

            section(getFormat("header-green", "General")) {
                label title: "Enter a name for parent app (optional)", required: false
                input "logEnable", "bool", defaultValue:false, title: "Enable Debug Logging", description: "Debugging", submitOnChange:true
                input "warnTime", "int", defaultValue:null, title: "Default warn time for dimming", description: "Warn Time", submitOnChange:true
            }

            section(getFormat("headre-greeen", "Master switch")) {
                input "masterSwitch", "capability.pushableButton", title: "Select master switch(es)", multiple: true, submitOnChange:true
            }
        } else {
            section{paragraph "Please hit 'Done' to install '${app.label}' parent app "}
        }
    }
}

def getFormat(type, myText="") {
    if(type == "header-green") return "<div style='color:#ffffff;font-weight: bold;background-color:#81BC00;border: 1px solid;box-shadow: 2px 3px #A9A9A9'>${myText}</div>"
    if(type == "line") return "<hr style='background-color:#1A77C9; height: 1px; border: 0;'>"
    if(type == "title") return "<h2 style='color:#1A77C9;font-weight: bold'>${myText}</h2>"
}

def indicator(hue, level) {
    def color = hue + level * 256 + 255*65536 + 1*16777216
    masterSwitch.each { sw ->
        sw.setIndicator(color)
    }

}

def buttonHandler(evt) {
    if (evt.name != "pushed" && evt.name != "doubleTapped" && evt.name != "held" && evt.name != "released") {
        return
    }

    logDebug("buttonHandler ${evt.value} ${evt.name}")
    
    anyButtonPressed()

    if (evt.name == "doubleTapped" && evt.integerValue == 2) {
        evt.name = "pushed"
    }

    childApps.each {child ->
        child.buttonAction(evt)
    }
}

def active() {
    logDebug("Child reported active")
    unschedule(motionTimerWarn)
    unschedule(motionTimerOff)
    if (state.warned) {
        logDebug("Unwarning")
        childApps.each {child ->
            child.motionTimerUnwarn()
        }
        state.warned = false
    }
}

def anyButtonPressed() {
    logDebug("Someone reported a button press")
    state.button = true
    unschedule(motionTimerWarn)
    unschedule(motionTimerOff)
    if (! isActive()) {
        runIn(110 * 60, motionTimerWarn)
    }
}

def inactive() {
    logDebug("Child reported inactive")
    if (! isActive()) {
        runIn(state.button ? 110 * 60 : (warnTime != null ? (warnTime as Integer) : 60), motionTimerWarn)
    }
}

def motionTimerWarn() {
    logDebug("Warning")
    state.warned = true
    runIn(state.button ? 600 : 30, motionTimerOff)
    childApps.each {child ->
        child.motionTimerWarn()
    }
}

def motionTimerOff() {
    logDebug("Offing")
    state.warned = false
    state.button = false
    childApps.each {child ->
        child.motionTimerOff()
    }
}

def isActive() {
    def active = false
    childApps.each {child ->
        active = active || child.isActive()
    }
    logDebug("I am ${active}")
    return active
}

def isOn() {
    def on = false
    childApps.each {child ->
        on = on || child.isOn()
    }
    logDebug("Light is ${on}")
    return on
}

def updateSwitch() {
    def on = isOn()
    indicator(on ? 96 : 1, 255)
    if (! on) {
        logDebug("Light is off, resetting button state")
        state.button = false
    }
}

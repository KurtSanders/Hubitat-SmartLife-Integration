/**
 * Reference: https://raw.githubusercontent.com/ivarho/hubitatappndevice/master/Device/tuyaDevices/tuyaGenericBulbRGBW.groovy
 * Based from Tuya Integration by Ivar Holand
 *
 * Hubitat Dywarmic Towel Warmer Integration by Kurt Sanders 2024-2025
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import groovy.transform.Field
import java.text.SimpleDateFormat
import groovy.json.*

metadata {
definition(name: "Dywarmic Towel Warmer",
    	namespace: "kurtsanders",
       	author: "Kurt Sanders",
       	importUrl: "",
       	singleThreaded: true) {
    capability "Actuator"
    capability "Switch"
    capability 'TemperatureMeasurement'
    capability 'Refresh'

    attribute "countdown_left", "number"
    attribute "light", "string"
    attribute "child_lock", "string"
    attribute "mode", "string"
    attribute "level", "string"
    attribute "eco", "string"
    attribute "temp_unit_convert", "string"
    attribute "error", "string"
    attribute "state", "string"
    attribute "polling", "boolean"

        command "Disconnect"
        command "SendCustomJSONObject", [[name:"jsonPayload*", type: "STRING", description:"Format: {\"1\":true, \"12\":\"cancel\"}"]]
        command "SendCustomDataToDevice", [[name:"endpoint*", type:"NUMBER", description:"To which endpint(dps) do you want the data to be sent"], [name:"data*", type:"STRING", description:"the data to be sent, treated as string, but true and false is converted"]]
        command "setCountdownTimer", [[name: "Count Down Timer Units (mins)*", type:"ENUM", description:"Sets the Count Down Timer [mins]", constraints:SPA_COUNTDOWNTIMERLIST]]
        command "setFeature", [
            [name: "Feature*", type:"ENUM", description:"Feature", constraints:FEATURES],
            [name: "Value*", type:"ENUM", description:"Value", constraints:ONOFF],
        ]
    }
}

@Field static final Map SPA_COUNTDOWNTIMERLIST           = ["1h":'20',"2h":'40',"3h":'60',"4h":'80',"5h":'100',"6h":'120',"cancel":"cancel"]
@Field static final Integer DELTA_TEMPERATURE            = 5
@Field static final List ONOFF                           = ["on", "off"]
@Field static final Map FEATURES                         = ["8":"light","7":"child_lock","6":"eco"]

preferences {
	section("Dywarmic Towel Warmer Device Config") {
        input "ipaddress", "text", title: "Device IP:", required: true, description: "<small>tuya device local IP address. Found by using tools like tinytuya. Tip: configure a fixed IP address for your tuya device on your network to make sure the IP does not change over time.</small>"
        input "devId", "text", title: "Device ID:", required: true, description: "<small>Unique tuya device ID. Found by using tools like tinytuya.</small>"
        input "localKey", "text", title: "Device local key:", required: true, description: "<small>The local key used  for encrypted communication between HE and the tuya Deivce. Found by using tools like tinytuya.</small>"
        input name: "logEnable", type: "bool", title: "Enable <u>debug</u> logging", defaultValue: true, description: "<small>If issues are experienced it might help to turn on debug logging and see the debug logs, automatically turned off after 30 min. Check device IP, ID and local key make sure they are correct. Also a power off/on on the tuya device might help.</small>"
        input name: "logTrace", type: "bool", title: "Enable driver level <u>trace</u> logging", defaultValue: true, description: "<small>For trace level debugging, it could be helpful to follow the program flow to make sure the correct functions are called. (Auto disabled after 30 min)</small>"
        input "tuyaProtVersion", "enum", title: "Select tuya protocol version: ", required: true, defaultValue: 34, options: [31: "3.1", 33 : "3.3", 34: "3.4"], description: "<small>Select the correct protocol version corresponding to your device. If you run firmware update on the device you should expect the driver protocol version to update. Which protocol is used can be found using tools like tinytuya.</small>"
        input name: "poll_interval", type: "enum", title: "Configure poll interval:", defaultValue: 0, options: [0: "No polling", 1:"Every 1 second", 2:"Every 2 second", 3: "Every 3 second", 5: "Every 5 second", 10: "Every 10 second", 15: "Every 15 second", 20: "Every 20 second", 30: "Every 30 second", 60: "Every 1 min", 120: "Every 2 min", 180: "Every 3 min"], description: "<small>Old way of reading status of the deivce. Use \"No polling\" when auto reconnect or heart beat is enabled.</small>"
        input name: "autoReconnect", type: "bool", title: "Auto reconnect on socket close", defaultValue: true, description: "<small>A communication channel is kept open between HE and the tuya device. Every 30 s the socket is closed and re-opened. This is useful if the device is a switch, or is also being controlled from external apps like Smart Life etc. For <b>3.4</b> it is also smart to enable the Use heart beat method to reduce data traffic.</small>"
        input name: "heartBeatMethod", type: "bool", title: "Use heart beat method to keep connection alive", defaultValue: true, description: "<small>Use a heart beat to keep the connection alive, i.e. a message is sent every 20 seconds to the device, the causes less data traffic on <b>3.4</b> devices as sessions don't have to be negotiated all the time.</small>"
    }
}

void logsOff() {
	log.warn "debug and trace logging disabled..."
	device.updateSetting("logEnable", [value: "false", type: "bool"])
	device.updateSetting("logTrace", [value: "false", type: "bool"])
}

void installed() {
    device.updateSetting("logEnable", [value: "true", type: "bool"])
    device.updateSetting("logTrace", [value: "false", type: "bool"])
    state.units = location.temperatureScale
    updated()
}

void updated() {
	log.info "updated..."
    log.debug "debug logging is: ${logEnable == true}"
    if (logEnable) runIn(1800, logsOff)
    if (logTrace) runIn(1800, logsOff)
	if ([ipaddress,devId,localKey].contains(null) || [ipaddress,devId,localKey].contains("")) {
		log.error "One or more of the device preference required inputs, (eg. ipaddress, devId or localKey) are blank/empty, exiting..."
		return
	}

_updatedTuya()

    // Configure poll interval, only the parent pull for status
    if (poll_interval.toInteger() != null) {
        //Schedule run

        if (poll_interval.toInteger() == 0) {
            unschedule(refresh)
        } else if (poll_interval.toInteger() < 60) {
            schedule("*/${poll_interval} * * ? * *", refresh)
        } else if (poll_interval.toInteger() < 60*60) {
            minutes = poll_interval.toInteger()/60
            if(logEnable) log.debug "Setting schedule to Refresh every ${minutes} minutes"
            schedule("0 */${minutes} * ? * *", refresh)
        }
    }
    refresh()
}

void refresh() {
	if (logTrace) log.trace("refresh()")
    send("status", [:])
}

void on() {
	if (logTrace) log.trace("on()")
	state.statePayload[1] = true
	runInMillis(250, 'sendSetMessage')
}

void off() {
	if (logTrace) log.trace("off()")

	state.statePayload[1] = false
	runInMillis(250, 'sendSetMessage')
}

// Component command to control Towel Rack features
void setFeature(feature=null,value=null) {
    if (!feature || !value) {
        log.error "setFeature() requires a valid feature code ${FEATUES.values()} and value ${ONOFF.join(', ')}"
        return
    }
    feature = feature.toLowerCase()
    value = value.toLowerCase()
    if (logEnable) { log.debug "setCode(): feature: ${feature}, value ${value}" }
    def key = FEATURES.find { it.value == feature }?.key
    if (!key) {
        log.error "setFeature(): Invalid feature '${feature}'.  Feature must be ONE of these following features: ${FEATURES.values()}"
        return
    }
    if (!ONOFF.contains(value)) {
        log.error "setFeature(): Invalid value '${value}'.  Value must be either: ${ONOFF.join(', ')}."
        return
    }
    return
    state.statePayload[key] = (value == 'on')?true:false
    runInMillis(250, 'sendSetMessage')
}

void setCountdownTimer(String value) {
    value = value.toLowerCase()
    def key
    if (value.isNumber() || value == 'cancel') {
        key = SPA_COUNTDOWNTIMERLIST.find { it.value == value }?.key
    } else {
        key = (DPSMAP['12']['values']['range'].contains(value))?value:null
    }
    if (logEnable) { log.debug "setCountDown(): value ${value} = key: ${key}" }
    if (key) {
        if (logEnable) {log.debug "==> sendSetMessage() → state.statePayload[12] = ${key}"}
        state.statePayload[12] = key
        runInMillis(250, 'sendSetMessage')
    } else log.error "CountDownTimer argument value '${value}' is invalid.  The value must contain one of these values: ${SPA_COUNTDOWNTIMERLIST.values()}"
}

def SendCustomDataToDevice(endpoint, data) {
	if (logTrace) log.trace("SendCustomDataToDevice($endpoint, $data)")

	// A fix for a common use-case where true and false is sent
	// these values must be converted to boolean values to work
	if (data == "true") {
		data = true
	} else if (data == "false") {
		data = false
	}

	send("set", ["${endpoint}":data])
}

def SendCustomJSONObject(String _s_json_data)
{
	if (logTrace) log.trace("SendCustomJSONObject($_s_json_data)")

	status = [:]

	def jsonSlurper = new groovy.json.JsonSlurper()
	status = jsonSlurper.parseText(_s_json_data.substring(_s_json_data.indexOf('{')))

	send("set", status)
}

def sendSetMessage() {
	if (logTrace) log.trace("sendSetMessage() // current state.statePayload = $state.statePayload)")

	send("set", state.statePayload)
	state.statePayload = [:]
}

def parse(String message) {
    if (logTrace) log.trace("parse()")

    List results = _parseTuya(message)
    if (!results) return
    if (logTrace) log.trace "==> parse(): results= ${results}"

    results.each {status_object ->
        Boolean switchState = true
        status_object.dps.each { k, v ->
            if (logEnable) log.debug "${DPSMAP[k]['code']} = ${v}"
            // Map Tuya variables to Hubitat Thermostat attributes
            switch (DPSMAP[k]['code']) {
                case "switch":
	            	v = (v?'on':'off')
                case 'light':
                	sendEvent(name: DPSMAP[k]['code'], value : v)
                	if (logEnable) log.debug "** sendEvent ${DPSMAP[k]['code']} = ${v}"
                break

                case 'temp_unit_convert':
	                v= "°${v.toUpperCase()}"
	                state.units = v
                case 'eco':
                case 'child_lock':
                case 'mode':
                case 'countdown_left':
	                v =  formatSeconds(v)
                case 'state':
                case 'level':
	                if (logEnable) log.debug "** sendEvent ${DPSMAP[k]['code']} = ${v}"
    	            sendEvent(name: DPSMAP[k]['code'], value : v)
                break

                case "temp_current_f":
                // Send temperature value events when towel warmer switch is on
                if (device.currentValue('switch')) {
                    if (logEnable) log.debug "** sendEvent temperature = ${state.units}"
                    sendEvent(name: "temperature", value : v, unit: "${state.units}")
                } else {
                    // Only send temperature value events by units of 5 when towel warmer switch is off (Cooling Down or in Standby)
                    if (device.currentValue('temperature').toInteger()-DELTA_TEMPERATURE > v.toInteger()) {
                        if (logEnable) log.debug "** sendEvent temperature = ${v}°F"
                        sendEvent(name: "temperature", value : v, unit: "${state.units}")
                    }
                }
                break

                default:
                    if (logEnable) log.debug ">> Ignoring ${DPSMAP[k]['code']} = ${v}"
                    break
            }
        }
    }
}

public static String formatSeconds(int timeInSeconds)
{
    int hours = timeInSeconds / 3600;
    int secondsLeft = timeInSeconds - hours * 3600;
    int minutes = secondsLeft / 60;
    int seconds = secondsLeft - minutes * 60;

    String formattedTime = "";
    if (hours < 10)
        formattedTime += "0";
    formattedTime += hours + ":";

    if (minutes < 10)
        formattedTime += "0";
    formattedTime += minutes + ":";

    if (seconds < 10)
        formattedTime += "0";
    formattedTime += seconds ;

    return formattedTime;
}


// **************************************************************************************************
// **************************************************************************************************
// ************************************ TUYA PROTOCOL FUNCTIONS *************************************
// **************************************************************************************************
// **************************************************************************************************

import hubitat.device.HubAction
import hubitat.device.Protocol
import groovy.transform.Field

//@Field static Map state.statePayload = [:] // For the driver to use to queue up messages

// Session
//@Field static String staticSession_step // = state.session_step
//@Field static byte[] staticSessionKey // = state.sessionKey
//@Field static String state.LocalNonce // = state.localNonce

//@Field static byte[] staticLocalKey

// Program flow
//@Field static Integer staticRetry // = state.retry
//@Field static boolean state.HaveSession = false // = state.haveSession
//@Field static Short state.Msgseq = 1 // = state.msgseq

// Callback function used by HE to notify about socket changes
// This has been reported to be buggy
def socketStatus(String socketMessage) {
	if(logEnable) log.info "Socket status message received: " + socketMessage

	if (socketMessage == "send error: Broken pipe (Write failed)") {
		unschedule(heartbeat)
		socket_close()
	}

	if (socketMessage.contains('disconnect')) {
		unschedule(heartbeat)
		socket_close(settings.autoReconnect == true)

		if (settings.autoReconnect == true || settings.autoReconnect == null) {
			state.HaveSession = get_session(settings.tuyaProtVersion)

			if (state.HaveSession == false) {
				sendEvent(name: "presence", value: "not present")
			}
		}
	}
}

boolean socket_connect() {

	if (logTrace) log.trace "Socket connect: $settings.ipaddress at port: 6668"

	boolean returnStatus = true

	try {
		//port 6668
		interfaces.rawSocket.connect(settings.ipaddress, 6668, byteInterface: true, readDelay: 150)
		returnStatus = true
	} catch (java.net.NoRouteToHostException ex) {
		log.error "$ex - Can't connect to device, make sure correct IP address, try running 'python -m tinytuya scan' to verify, also try to power device on and off"
		returnStatus = false
	} catch (java.net.SocketTimeoutException ex) {
		log.error "$ex - Can't connect to device, make sure correct IP address, try running 'python -m tinytuya scan' to verify, also try to power device on and off"
		returnStatus = false
	} catch (e) {
		log.error "Error $e"
		returnStatus = false
	} finally {
		return returnStatus
	}
}

def socket_write(byte[] message) {
	String msg = hubitat.helper.HexUtils.byteArrayToHexString(message)

	if (logTrace) log.trace "Socket: write - " + settings.ipaddress + ":" + 6668 + " msg: " + msg

	try {
		interfaces.rawSocket.sendMessage(msg)
	} catch (e) {
		log.error "Error sending data to device: $e"
	}
}

def socket_close(boolean willTryToReconnect=false) {
	if (logTrace) log.trace "Socket: close"

	unschedule(sendTimeout)

	if (willTryToReconnect == false) {
		sendEvent(name: "presence", value: "not present")
	}

	state.session_step = "step1"
	state.HaveSession = false
	state.sessionKey = null

	try {
		interfaces.rawSocket.close()
	} catch (e) {
		log.error "Could not close socket: $e"
	}
}

@Field static String fCommand = ""
@Field static Map fMessage = [:]

def send(String command, Map message=null) {

	boolean sessionState = state.HaveSession

	if (sessionState == false) {
		if (logTrace) log.trace "No session, creating new session"
		sessionState = get_session(settings.tuyaProtVersion)
	}

	if (sessionState) {
		socket_write(generate_payload(command, message))
	}

	fCommand = command
	fMessage = message

	state.HaveSession = sessionState

	runInMillis(1000, sendTimeout)
}

def sendAll() {
	if (fCommand != "") {
		send(fCommand, fMessage)
	}
}

def sendTimeout() {
	if (state.retry > 0) {
		if (logEnable) log.warn "No response from device, retrying..."
		state.retry = state.retry - 1
		sendAll()
	} else {
		log.error "No answer from device after 5 retries"
		socket_close()
	}
}

Short getNewMessageSequence() {
	if (state.Msgseq == null) state.Msgseq = 0
	state.Msgseq = state.Msgseq + 1
	return state.Msgseq
}

byte[] getRealLocalKey() {
	byte[] staticLocalKey = localKey.replaceAll('&lt;', '<').getBytes("UTF-8")

	return staticLocalKey
}


def _updatedTuya() {
	state.statePayload = [:]
	state.HaveSession = false
	state.session_step = "step1"
	state.retry = 5
	state.Msgseq = 1
}

def DriverSelfTestReport(testName, byte[] generated, String expected) {
	boolean retValue = false

	sendEvent(name: "DriverSelfTest_$testName", value: "N/A")

	if (logTrace) log.trace "Generated " + hubitat.helper.HexUtils.byteArrayToHexString(generated)
	if (logTrace) log.trace "Expected " + expected

	if (hubitat.helper.HexUtils.byteArrayToHexString(generated) == expected) {
		log.info "$testName: Test passed"
		sendEvent(name: "DriverSelfTest_$testName", value: "OK")
		retValue = true
	} else {
		log.error "$testName: Test failed! The generated message does not match the expected output"
		sendEvent(name: "DriverSelfTest_$testName", value: "FAIL")
	}

	return retValue
}

def DriverSelfTestReport(testName, generated, expected) {
	boolean retValue = false

	sendEvent(name: "DriverSelfTest_$testName", value: "N/A")

	if (logTrace) log.trace "Generated " + generated
	if (logTrace) log.trace "Expected " + expected

	if (generated == expected) {
		if (logTrace) log.trace "$testName: Test passed"
		sendEvent(name: "DriverSelfTest_$testName", value: "OK")
		retValue = true
	} else {
		log.error "$testName: Test failed! The generated message does not match the expected output"
		sendEvent(name: "DriverSelfTest_$testName", value: "FAIL")
	}

	return retValue
}

def DriverSelfTest() {
	log.info "********** Starting driver self test *******************"

	state.clear()
	// Need to make sure to have this variable
	state.statePayload = [:]

	// Testing 3.1 set message
	expected = "000055AA0000000000000007000000B3332E313365666533353337353164353333323070306A6A4A75744C704839416F324B566F76424E55492B4A78527649334E5833305039794D594A6E33703842704B456A737767354C332B7849343638314B5277434F484C366B374B3543375A362F58766D6A7665714446736F714E31792B31584A53707542766D5A4337567371644944336A386A393354387944526154664A45486150516E784C394844625948754A63634A636E33773D3D1A3578640000AA55"
	generatedTestVector = generate_payload("set", ["20": true], "1702671803", "7ae83ffe1980sa3c".getBytes("UTF-8") as byte[], "bfd733c97d1bfc88b3sysa", "31", 0 as Short)
	DriverSelfTestReport("SetMessageV3_1", generatedTestVector, expected)

	// Testing 3.1 status message
	expected = "000055AA000000000000000A0000007A7B2267774964223A2262666437333363393764316266633838623373797361222C226465764964223A2262666437333363393764316266633838623373797361222C22756964223A2262666437333363393764316266633838623373797361222C2274223A2231373032363731383033227DCA1E0CC60000AA55"
	generatedTestVector = generate_payload("status", data=null, "1702671803", localkey="7ae83ffe1980sa3c".getBytes("UTF-8"), devid="bfd733c97d1bfc88b3sysa", tuyaVersion="31", 0 as Short)
	DriverSelfTestReport("StatusMessageV3_1", generatedTestVector, expected)

	// Testing 3.3 set message
	expected = "000055AA000000000000000700000087332E33000000000000000000000000A748E326EB4BA47F40A36295A2F04D508F89C51BC8DCD5F7D0FF72318267DE9F01A4A123B308392F7FB1238EBCD4A47008E1CBEA4ECAE42ED9EBF5EF9A3BDEA8316CA2A375CBED57252A6E06F9990BB56CA9D203DE3F23F774FCC8345A4DF2441DA3D09F12FD1C36D81EE25C709727DF2E5CF2B30000AA55"
	generatedTestVector = generate_payload("set", ["20": true], "1702671803", localkey="7ae83ffe1980sa3c".getBytes("UTF-8"), devid="bfd733c97d1bfc88b3sysa", tuyaVersion="33", 0 as Short)
	DriverSelfTestReport("SetMessageV3_3", generatedTestVector, expected)

	// Testing 3.3 status message
	expected = "000055AA000000000000000A00000088D0436FF6B453B07DC2CC8084484A8E3E08E1CBEA4ECAE42ED9EBF5EF9A3BDEA834A1D6E20760F13A0CF9DE1523730E598F89C51BC8DCD5F7D0FF72318267DE9F01A4A123B308392F7FB1238EBCD4A47008E1CBEA4ECAE42ED9EBF5EF9A3BDEA8316CA2A375CBED57252A6E06F9990BB543FF054E84050A495D427D28A8C0F29F0104C4D70000AA55"
	generatedTestVector = generate_payload("status", data=null, "1702671803", localkey="7ae83ffe1980sa3c".getBytes("UTF-8"), devid="bfd733c97d1bfc88b3sysa", tuyaVersion="33", 0 as Short)
	DriverSelfTestReport("StatusMessageV3_3", generatedTestVector, expected)


	// Testing 3.4 set message
	expected = "000055AA000000000000000D000000749AC0971A69B046C19DDFEAB6800CBB66A8FC70BDD2FF855511A3A2CBF2955BFC806C9FBFFA10ED709EC2BA4D8EC24609E50317C707468F02A110E429BA321FAA3862640A83699215E1313BA653C6DA0E5F01AADD72E172D7705B0AF82BFCD5E54A92562659A18235AEF0DDB1453BB7070000AA55"
	generatedTestVector = generate_payload("set", ["20": true], "1702671803", localkey="7ae83ffe1980sa3c".getBytes("UTF-8"), devid="bfd733c97d1bfc88b3sysa", tuyaVersion="34", 0 as Short)
	DriverSelfTestReport("SetMessageV3_4", generatedTestVector, expected)

	// Testing 3.4 status message
	expected = "000055AA000000000000001000000034A78158A05A786D32FEC14903A94445B47BEA54632DA130BAB31B719A8C21AB419104665404C82C85BDB55DCA068791F60000AA55"
	generatedTestVector = generate_payload("status", data=null, "1702671803", localkey="7ae83ffe1980sa3c".getBytes("UTF-8"), devid="bfd733c97d1bfc88b3sysa", tuyaVersion="34", 0 as Short)
	DriverSelfTestReport("StatusMessageV3_4", generatedTestVector, expected)

	// Testing Generating Session key request (1st)
	expected = "000055AA000000010000000300000044A3F090DD2637D2A406A883DDB748A528103D2D5B1508ABFA4BCDE07FC047EAFA47BF7E33438811CCB8FAA4D1FC848EB6AE0C6AA329B493CFAA44A42792AF6D230000AA55"
	generatedTestVector = hubitat.helper.HexUtils.byteArrayToHexString(generateKeyStartMessage('0123456789abcdef', "7ae83ffe1980sa3c".getBytes("UTF-8"), 1 as Short))
	DriverSelfTestReport("GenerateSessionKeyReqStep1", generatedTestVector, expected)

	// Testing Reception of Session key request answer (2nd)
	expectedRemoteNonce = "38a5c312169ac81b"
	(generatedTestVector, generatedRemoteNonce) = decodeIncomingKeyResponse("38a5c312169ac81b76y3hjbfiauhsndlkakjhbsadbuhyuywjhbcaj", "7ae83ffe1980sa3c".getBytes("UTF-8"), 7 as Short)
	DriverSelfTestReport("ReceptionOfNonceV3_4", new String(generatedRemoteNonce, "UTF-8"), expectedRemoteNonce)

	// Testing Generating Session key final answer (3rd)
	expected = "000055AA000000070000000500000054494C4CF320214B11CE224DBD40E5FC8608A08A33764CA039B2A09B39BFF6DFC0103D2D5B1508ABFA4BCDE07FC047EAFA33D53D2776CF99A4C2375C698985BC6F47EF698BCCE3BDFC56C73004297EB6330000AA55"
	DriverSelfTestReport("AnswerReceptionOfNonceV3_4", hubitat.helper.HexUtils.byteArrayToHexString(generatedTestVector), expected)

	// Testing Generating Sesson key
	expected = "34A80557C18868E1D090E3B210FBC253"
	generatedTestVector = calculateSessionKey('0123456789abcdef'.getBytes("UTF-8"), '2Y3iba43!2()4!!u', "7ae83ffe1980sa3c".getBytes("UTF-8"))
	DriverSelfTestReport("GenerateSessionKeyV3_4", generatedTestVector, expected)

	// Test decoding of incoming frame
	expected = ["dps":["20":true, "21":"white", "22":10, "23":0, "24":"000003e803e8", "25":"030e0d0000000000000001f403e8", "26":0, "41":true]]
	byte[] data = hubitat.helper.HexUtils.hexStringToByteArray("000055AA0000562C00000010000000A8000000004345E249505AE70FDC00278B03577AE8F61C1BBF33B0CB190B0A0DF085D39963CD4BA22EC93F613F7695C0CB64B8DE9FD375F2FF1F4A5CF5AEE45EB48595693A84D0EBA8EF376D5A9711D29EAF9E052A70A3950F3B647E4CE0FBA08BF9D0BC0FFD5D7E3C50DE257CDFBC1A172A242368D65C91C3F82FC1AD834398261F3F9F12FC30BC6EBFFFE76A40DB3D0765310DE2564AF7B59F6AF8CCB1A700513E7AB07E0000AA55")
	byte[] testKey = hubitat.helper.HexUtils.hexStringToByteArray("3BF9C84FA142D66FAE20825A4DF95ECF")

	decodeIncomingFrame(data, 0, testKey, {status ->
		DriverSelfTestReport("DecodingIncomingFrameV3_4", status.inspect(), expected.inspect())
	})

	// Clean-up after self-test
	tuyaDeviceUpdate()
}

def DriverSelfTestCallback(def status) {
	log.error "I was called with the following $status"
}

@Field static Map frameTypes = [
	3:  "KEY_START",
	4:  "KEY_RESP",
	5:  "KEY_FINAL",
	7:  "CONTROL",
	8:  "STATUS_RESP",
	9:  "HEART_BEAT",
	10: "DP_QUERY",
	13: "CONTROL_NEW",
	16: "DP_QUERY_NEW"]

def getFrameTypeId(String name) {
	return frameTypes.find{it.value == name}.key
}

@Field static Map frameChecksumSize = [
	"31": 4,
	"33": 4,
	"34": 32
]

List _parseTuya(String message) {
	if (logTrace) log.trace "Using new parser on message: " + message

	unschedule(sendTimeout)

	state.retry = 5

	String start = "000055AA"

	List startIndexes = []

	// Find number of incoming messages
	int index = 0
	int loopGuard = 100
	int location = 0
	while (index < message.size()) {
		index = message.indexOf(start, location)
		location = index + 1

		if (index != -1) {
			if (logTrace) log.trace "Found \"$start\" at: $index"
			// Later we handle incoming data as byte array, and incoming data is bytes represented as hex
			startIndexes.add(index/2)
		} else {
			// Not found
			break
		}

		if (loopGuard == 0) {
			break
		} else {
			loopGuard = loopGuard - 1
		}
	}

	if (logTrace) log.trace "Found starts on: $startIndexes"

	byte[] incomingData = hubitat.helper.HexUtils.hexStringToByteArray(message)

	List results = []

	startIndexes.each {
		Map result = decodeIncomingFrame(incomingData as byte[], it as Integer)
		if (result != null && result != [:]) {
			results.add(result)
		}
	}

	return results
}

Map decodeIncomingFrame(byte[] incomingData, Integer sofIndex=0, byte[] testKey=null, Closure callback=null) {
	long frameSequence = Byte.toUnsignedLong(incomingData[sofIndex + 7]) + (Byte.toUnsignedLong(incomingData[sofIndex + 8]) << 8)
	def frameType = Byte.toUnsignedInt(incomingData[sofIndex + 11])
	Integer frameLength = Byte.toUnsignedInt(incomingData[sofIndex + 15])

	if (logTrace) log.trace("Frame with SOFindex: $sofIndex, is sequence: $frameSequence, and message type: $frameType with length: $frameLength")

	if (frameTypes.containsKey(frameType)) {
		if (logTrace) log.trace "Frame types is known, key: $frameType name: ${frameTypes[frameType]}"
	} else {
		log.warn "Unknown frame type, key: $frameType"
		return
	}

	byte[] useKey = getRealLocalKey()

	if (testKey != null) {
		useKey = testKey
	} else if (state.sessionKey != null) {
		useKey = state.sessionKey
	}

	// Need to know checksum sizes
	Integer checksumSize = frameChecksumSize[settings.tuyaProtVersion]
	Integer payloadStart = 20
	Integer payloadLength = 16

	switch (frameTypes[frameType]) {
		case "KEY_RESP":
			if (logTrace) log.trace "This is a key negotation response"
			payloadStart = 20
			payloadLength = frameLength - checksumSize - 4 - 4
			useKey = getRealLocalKey()
			unschedule(get_session_timeout)
			break
		case "CONTROL":
		case "CONTROL_NEW":
			// Ignore, no useful information here
			return
			break
		case "STATUS_RESP":
			// Response to setting request
			fCommand = ""
			if (settings.tuyaProtVersion == "31") {
				payloadStart = 23 + 16 // 16 bytes to MD5 sum
				payloadLength = frameLength - checksumSize - 27
			} else if (settings.tuyaProtVersion == "33") {
				payloadStart = 35
				payloadLength = frameLength - checksumSize - 4 - 19
			} else if (settings.tuyaProtVersion == "34") {
				payloadStart = 20
				payloadLength = frameLength - checksumSize - 4 - 4
			}
			break
		case "HEART_BEAT":
			fCommand = ""
			payloadStart = 20
			payloadLength = frameLength - checksumSize - 4 - 4
			break
		case "DP_QUERY":
			fCommand = ""
			// Used by 3.3 protocol
			payloadStart = 20
			payloadLength = frameLength - checksumSize - 4 - 4
			break
		case "DP_QUERY_NEW":
			fCommand = ""
			// Response to status request
			payloadStart = 20
			payloadLength = frameLength - checksumSize - 4 - 4
			break
	}

	String plainTextMessage = ""

	if (incomingData[sofIndex + payloadStart] == '{') {
		// Incoming data is plain text
		plainTextMessage = new String(incomingData, "UTF-8")[(sofIndex + payloadStart)..(sofIndex + payloadStart + payloadLength - 1)]
		if (logTrace) log.trace "Unencrypted message: $plainTextMessage"
	} else {
		// Incoming data is encrypted
		plainTextMessage = decryptPayload(incomingData as byte[], useKey, sofIndex + payloadStart, payloadLength)
		if (logTrace) log.trace "Decrypted message: " + plainTextMessage
	}

	Object status = [:]

    // Check if incoming message is a JSON object
    if (plainTextMessage.indexOf('dps') != -1) {
        if (logTrace) log.trace "Found JSON object in string"
        def jsonSlurper = new groovy.json.JsonSlurper()
        status = jsonSlurper.parseText(plainTextMessage.substring(plainTextMessage.indexOf('{')))
    } else {
        if (logTrace) log.trace "Did not find a JSON object in string"
    }

	// Post process the incoming payload
	switch (frameTypes[frameType]) {
		case "KEY_RESP":
			payloadStart = 20

			byte[] responseOnKeyResponse
			byte[] remoteNonce
			(responseOnKeyResponse, remoteNonce) = decodeIncomingKeyResponse(plainTextMessage)
			state.session_step = "step3"
			socket_write(responseOnKeyResponse)

			state.sessionKey = calculateSessionKey(remoteNonce)
			state.session_step = "final"
			state.HaveSession = true

			sendEvent(name: "presence", value: "present")

			// Time to send actual message
			runInMillis(100, sendAll)

			if (heartBeatMethod) {
				runIn(20, heartbeat)
			} else {
				runIn(30, socketStatus, [data: "disconnect: pipe closed (driver forced - expected behaviour)"])
			}

			// No further actions needed on key response
			return
			break
		case "STATUS_RESP":
			// Response to setting request

			// Protocol 3.4 buries the dps info one level deeper
			if (settings.tuyaProtVersion == "34") {
				status = status["data"]
			}
			break
		case "HEART_BEAT":
			unschedule(socketStatus)
			runIn(18, heartbeat)
			break
	}

	if (logTrace) log.trace "JSON object: $status"
	if (logTrace) log.trace "DPS object: " + status

	if (callback != null) {
		callback(status)
	}

	// For debugging
	if (logTrace && status != null && status != [:]) {
		sendEvent(name: "rawMessage", value: status.dps)
	}

	return status
}

def decryptPayload(byte[] data, byte[] key, start, length) {
	ByteArrayOutputStream payloadStream = new ByteArrayOutputStream()

	for (i = 0; i < length; i++) {
		payloadStream.write(data[start + i])
	}

	byte[] payloadByteArray = payloadStream.toByteArray()

	if (logTrace) log.trace "Payload for decrypt [$start..$length]: " + hubitat.helper.HexUtils.byteArrayToHexString(payloadByteArray)

	// Protocol version 3.1 uses base64 conversion
	boolean useB64 = settings.tuyaProtVersion == "31" ? true : false

	return decrypt_bytes(payloadByteArray, key, useB64)
}

def decodeIncomingKeyResponse(String incomingData, byte[] useKey=getRealLocalKey(), Short useMsgSequence=null) {
	byte[] remoteNonce = incomingData[0..15].getBytes()

	Mac sha256HMAC = Mac.getInstance("HmacSHA256")
	SecretKeySpec key = new SecretKeySpec(useKey, "HmacSHA256")

	sha256HMAC.init(key)
	sha256HMAC.update(remoteNonce, 0, remoteNonce.size())
	byte[] digest = sha256HMAC.doFinal()

	if (logTrace) log.trace "Calculated key negotiation answer payload: " + hubitat.helper.HexUtils.byteArrayToHexString(digest)

	byte[] message = generateGeneralMessageV3_4(digest, getFrameTypeId("KEY_FINAL"), useKey, useMsgSequence)

	if (logTrace) log.trace "message to send: " + hubitat.helper.HexUtils.byteArrayToHexString(message)

	return [message, remoteNonce]
}

def calculateSessionKey(byte[] remoteNonce, String useLocalNonce=null, byte[] key=getRealLocalKey()) {

	byte[] localNonce = useLocalNonce==null? getLocalNonce().getBytes() : useLocalNonce.getBytes()

	byte[] calKey = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]

	// Do final session key calculation
	int i = 0
	for (byte b : localNonce) {
		calKey[i] = b ^ remoteNonce[i]
		i++
	}

	if (logTrace) log.trace "XOR'd keys: " + hubitat.helper.HexUtils.byteArrayToHexString(calKey)

	sessKeyHEXString = encrypt(calKey, key, false)

	byte[] sessKeyByteArray = hubitat.helper.HexUtils.hexStringToByteArray(sessKeyHEXString[0..31])

	if (logTrace) log.trace "Session key: " + hubitat.helper.HexUtils.byteArrayToHexString(sessKeyByteArray)

	if (logTrace) log.trace "********************** DONE  SESSION KEY NEGOTIATION **********************"

	return sessKeyByteArray
}

def Disconnect() {
	unschedule(heartbeat)
	socket_close()
}

def heartbeat() {
	send("hb")
	runIn(30, socketStatus, [data: "disconnect: pipe closed (driver forced - expected behaviour)"])
}

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec;

def generate_payload(String command, def data=null, String timestamp=null, byte[] localkey=getRealLocalKey(), String devid=settings.devId, String tuyaVersion=settings.tuyaProtVersion, Short useMsgSequence=null) {

	switch (tuyaVersion) {
		case "31":
		case "33":
			payloadFormat = "v3.1_v3.3"
			break
		case "34":
			payloadFormat = "v3.4"
			break
	}

	if (state.sessionKey != null) {
		localkey = state.sessionKey
	}

	if (logTrace) log.trace "Using key: " + new String(localkey as byte[], "UTF-8")
	if (logTrace) log.trace "Using key: " + hubitat.helper.HexUtils.byteArrayToHexString(localkey as byte[])

	json_data = payload()[payloadFormat][command]["command"]

	if (json_data.containsKey("gwId")) {
		json_data["gwId"] = devid
	}
	if (json_data.containsKey("devId")) {
		json_data["devId"] = devid
	}
	if (json_data.containsKey("uid")) {
		json_data["uid"] = devid
	}
	if (json_data.containsKey("t")) {

		if (timestamp == null) {
			Date now = new Date()
			json_data["t"] = (now.getTime()/1000).toInteger().toString()
		} else {
			json_data["t"] = timestamp
		}
	}

	if (data != null && data != [:]) {
		if (json_data.containsKey("data")) {
			json_data["data"] = ["dps" : data]
		} else {
			json_data["dps"] = data
		}
	}

	// Clean up json payload for tuya
	def json = new groovy.json.JsonBuilder(json_data)
	json_payload = groovy.json.JsonOutput.toJson(json.toString())
	json_payload = json_payload.replaceAll("\\\\", "")
	json_payload = json_payload.replaceFirst("\"", "")
	json_payload = json_payload[0..-2]

	if (logTrace) log.trace "payload before=" + json_payload

	// Contruct payload, sometimes encrypted, sometimes clear text, and a mix. Depending on the protocol version
	ByteArrayOutputStream contructed_payload = new ByteArrayOutputStream()

	if (tuyaVersion == "31") {
		if (command != "status") {
			encrypted_payload = encrypt(json_payload, localkey)

			if (logTrace) log.trace "Encrypted payload: " + hubitat.helper.HexUtils.byteArrayToHexString(encrypted_payload.getBytes())
			preMd5String = "data=" + encrypted_payload + "||lpv=" + "3.1" + "||" + new String(localkey, "UTF-8")
			if (logTrace) log.trace "preMd5String" + preMd5String
			hexdigest = generateMD5(preMd5String)
			hexdig = new String(hexdigest[8..-9].getBytes("UTF-8"), "ISO-8859-1")
			json_payload = "3.1" + hexdig + encrypted_payload
		}
		contructed_payload.write(json_payload.getBytes())

	} else if (tuyaVersion == "33") {
		encrypted_payload = encrypt(json_payload, localkey as byte[], false)

		if (logTrace) log.trace encrypted_payload

		if (command != "status" && command != "nb") {
			contructed_payload.write("3.3\0\0\0\0\0\0\0\0\0\0\0\0".getBytes())
			contructed_payload.write(hubitat.helper.HexUtils.hexStringToByteArray(encrypted_payload))
		} else {
			contructed_payload.write(hubitat.helper.HexUtils.hexStringToByteArray(encrypted_payload))
		}

	} else if (tuyaVersion == "34") {
		if (command != "status" && command != "hb") {
			json_payload = "3.4\0\0\0\0\0\0\0\0\0\0\0\0" + json_payload
		}
		encrypted_payload = encrypt(json_payload, localkey as byte[], false)
		contructed_payload.write(hubitat.helper.HexUtils.hexStringToByteArray(encrypted_payload))
	}

	if (logTrace) log.trace "payload after=" + json_payload

	byte[] final_payload = contructed_payload.toByteArray()

	payload_len = contructed_payload.size() + hubitat.helper.HexUtils.hexStringToByteArray(payload()[payloadFormat]["suffix"]).size()

	if (tuyaVersion == "31" || tuyaVersion == "33") {
		payload_len = payload_len + 4 // for CRC32 storage
	} else if (tuyaVersion == "34") {
		// SHA252 is used as data integrity check not CRC32, i.e. need to add 256 bits = 32 bytes to the length
		payload_len = payload_len + 32 // for HMAC (SHA-256) storage
	}

	if (logTrace) log.trace payload_len

	//log.info hubitat.helper.HexUtils.byteArrayToHexString(generateGeneralMessageV3_4(json_payload, 1, 3))

	Short msgSequence = useMsgSequence==null ? getNewMessageSequence() : useMsgSequence

	// Start constructing the final message
	output = new ByteArrayOutputStream()
	output.write(hubitat.helper.HexUtils.hexStringToByteArray(payload()[payloadFormat]["prefix_nr"]))
	output.write(msgSequence >> 8)
	output.write(msgSequence)
	output.write(hubitat.helper.HexUtils.hexStringToByteArray("000000"))
	output.write(hubitat.helper.HexUtils.hexStringToByteArray(payload()[payloadFormat][command]["hexByte"]))
	output.write(hubitat.helper.HexUtils.hexStringToByteArray("000000"))
	output.write(payload_len)
	output.write(final_payload)

	byte[] buf = output.toByteArray()

	if (tuyaVersion == "34") {
		if (logTrace) log.trace "Using HMAC (SHA-256) as checksum"

		Mac sha256_hmac = Mac.getInstance("HmacSHA256")
		SecretKeySpec key = new SecretKeySpec(localkey as byte[], "HmacSHA256")

		sha256_hmac.init(key)
		sha256_hmac.update(buf, 0, buf.size())
		byte[] digest = sha256_hmac.doFinal()

		if (logTrace) log.trace("message HMAC SHA256: " + hubitat.helper.HexUtils.byteArrayToHexString(digest))

		output.write(digest)
	} else {
		if (logEnable) log.info "Using CRC32 as checksum"

		crc32 = CRC32b(buf, buf.size()) & 0xffffffff
		if (logTrace) log.trace buf.size()

		hex_crc = Long.toHexString(crc32)

		if (logTrace) log.trace "HEX crc: $hex_crc : " + hex_crc.size()/2

		// Pad the CRC in case highest byte is 0
		if (hex_crc.size() < 7) {
			hex_crc = "00" + hex_crc
		}
		output.write(hubitat.helper.HexUtils.hexStringToByteArray(hex_crc))
	}

	output.write(hubitat.helper.HexUtils.hexStringToByteArray(payload()[payloadFormat]["suffix"]))

	return output.toByteArray()
}

def get_session(tuyaVersion) {

	if (tuyaVersion.toInteger() <= 33) {
		// Don't need to get session, just send message
		if (heartBeatMethod) {
			runIn(20, heartbeat)
		} else {
			runIn(30, socketStatus, [data: "disconnect: pipe closed (driver forced - expected behaviour)"])
		}

		boolean socket_connect_ret = socket_connect()

		if (socket_connect_ret == true) {
			sendEvent(name: "presence", value: "present")
		}

		return socket_connect_ret
	}

	current_session_state = state.session_step

	if (current_session_state == null) {
		current_session_state = "step1"
	}

	switch (current_session_state) {
		case "step1":
			socket_connect()
			state.session_step = "step2"
			socket_write(generateKeyStartMessage())
			runInMillis(750, get_session_timeout)
			break
		case "final":
			// We have the session, lets send the data
			return true
	}

	return false
}

def get_session_timeout() {
	log.error "Timout in getting session at $state.session_step, no answer from device"

	if (state.session_step == "step2") {
		state.session_step = "step1"
	}

	if (state.session_step == "step3") {
		state.session_step = "step1"
	}
}

def generateLocalNonce(Integer length=16) {
	String nonce = ""
	String alphabet = (('A'..'N')+('P'..'Z')+('a'..'k')+('m'..'z')+('2'..'9')).join()
	nonce = new Random().with {
		(1..length).collect { alphabet[ nextInt( alphabet.length() ) ] }.join()
	}
	return nonce
}

String getLocalNonce() {
	if (state.LocalNonce == null) {
		state.LocalNonce = generateLocalNonce()
	}
	return state.LocalNonce
}

byte[] generateKeyStartMessage(String useLocalNonce=null, byte[] useKey=getRealLocalKey(), Short useMsgSequence=null) {
	payloadFormat = "v3.4"

	if (logTrace) log.trace("********************** START SESSION KEY NEGOTIATION **********************")

	payload = useLocalNonce==null? getLocalNonce() : useLocalNonce

	if (logTrace) log.trace "Payload (local nonce): $payload"

	encrypted_payload = encrypt(payload, useKey, false)

	if (logTrace) log.trace("Payload (local nonce) encrypted: " + encrypted_payload)

	encrypted_payload = hubitat.helper.HexUtils.hexStringToByteArray(encrypted_payload)

	def packed_message = new ByteArrayOutputStream()

	Short msgSequence = useMsgSequence==null ? getNewMessageSequence() : useMsgSequence

	packed_message.write(hubitat.helper.HexUtils.hexStringToByteArray(payload()[payloadFormat]["prefix_nr"]))
	packed_message.write(msgSequence >> 8)
	packed_message.write(msgSequence)
	packed_message.write(hubitat.helper.HexUtils.hexStringToByteArray("000000"))
	packed_message.write(getFrameTypeId("KEY_START"))
	packed_message.write(hubitat.helper.HexUtils.hexStringToByteArray("000000"))
	packed_message.write(encrypted_payload.size() + 32 + hubitat.helper.HexUtils.hexStringToByteArray(payload()[payloadFormat]["suffix"]).size())
	packed_message.write(encrypted_payload)

	if (logTrace) log.trace hubitat.helper.HexUtils.byteArrayToHexString(packed_message.toByteArray())

	Mac sha256_hmac = Mac.getInstance("HmacSHA256")
	SecretKeySpec key = new SecretKeySpec(useKey, "HmacSHA256")

	sha256_hmac.init(key)
	sha256_hmac.update(packed_message.toByteArray(), 0, packed_message.size())
	byte[] digest = sha256_hmac.doFinal()

	packed_message.write(digest)
	packed_message.write(hubitat.helper.HexUtils.hexStringToByteArray(payload()[payloadFormat]["suffix"]))

	if (logTrace) log.trace hubitat.helper.HexUtils.byteArrayToHexString(packed_message.toByteArray())

	packed_message.toByteArray()
}

def generateGeneralMessageV3_4(byte[] data, Integer cmd, byte[] useKey=getRealLocalKey(), Short useMsgSequence=null){
	payloadFormat = "v3.4"

	encrypted_payload = encrypt(data, useKey, false)

	if (logTrace) log.trace("payload encrypted: " + encrypted_payload)

	encrypted_payload = hubitat.helper.HexUtils.hexStringToByteArray(encrypted_payload)

	def packed_message = new ByteArrayOutputStream()

	Short msgSequence = useMsgSequence==null ? getNewMessageSequence() : useMsgSequence

	packed_message.write(hubitat.helper.HexUtils.hexStringToByteArray(payload()[payloadFormat]["prefix_nr"]))
	packed_message.write(msgSequence >> 8)
	packed_message.write(msgSequence)
	packed_message.write(hubitat.helper.HexUtils.hexStringToByteArray("000000"))
	packed_message.write(cmd)
	packed_message.write(hubitat.helper.HexUtils.hexStringToByteArray("000000"))
	packed_message.write(encrypted_payload.size() + 32 + hubitat.helper.HexUtils.hexStringToByteArray(payload()[payloadFormat]["suffix"]).size())
	packed_message.write(encrypted_payload)

	if (logTrace) log.trace hubitat.helper.HexUtils.byteArrayToHexString(packed_message.toByteArray())

	Mac sha256_hmac = Mac.getInstance("HmacSHA256")
	SecretKeySpec keySpec = new SecretKeySpec(useKey, "HmacSHA256")

	sha256_hmac.init(keySpec)
	sha256_hmac.update(packed_message.toByteArray(), 0, packed_message.size())
	byte[] digest = sha256_hmac.doFinal()

	packed_message.write(digest)
	packed_message.write(hubitat.helper.HexUtils.hexStringToByteArray(payload()[payloadFormat]["suffix"]))

	if (logTrace) log.trace hubitat.helper.HexUtils.byteArrayToHexString(packed_message.toByteArray())

	packed_message.toByteArray()
}

// Helper functions
def payload()
{
	def payload_dict = [
		"v3.1_v3.3": [
			"status": [
				"hexByte": "0a",
				"command": ["gwId":"", "devId":"", "uid":"", "t":""]
			],
			"set": [
				"hexByte": "07",
				"command": ["devId":"", "uid": "", "t": ""]
			],
			"hb" : [
				"hexByte": "09",
				"command": ["gwId":"", "devId":""]
			],
			"prefix_nr": "000055aa0000",
			"prefix": "000055aa00000000000000",
			"suffix": "0000aa55"
		],
		"v3.4": [
			"status": [
				"hexByte": "10",
				"command": [:]
			],
			"set": [
				"hexByte": "0d",
				"command": ["protocol":5,"t":"","data":""]
			],
			"hb" : [
				"hexByte": "09",
				"command": ["gwId":"", "devId":""]
			],
			"neg1" : [
				"hexByte": "03"
			],
			"prefix_nr": "000055aa0000",
			"prefix"   : "000055aa00000000000000",
			"suffix"   : "0000aa55"
		]
	]

	return payload_dict
}

// Huge thank you to MrYutz for posting Groovy AES ecryption drivers for groovy
//https://community.hubitat.com/t/groovy-aes-encryption-driver/31556

import javax.crypto.spec.IvParameterSpec;
import javax.crypto.Cipher

// Encrypt plain text v. 3.1 uses base64 encoding, while 3.3 does not
def encrypt (def plainText, byte[] secret, encodeB64=true) {
	// Encryption is AES in ECB mode, pad using PKCS5Padding as needed
	def cipher = Cipher.getInstance("AES/ECB/PKCS5Padding ")
	SecretKeySpec key = new SecretKeySpec(secret, "AES")

	// Give the encryption engine the encryption key
	cipher.init(Cipher.ENCRYPT_MODE, key)

	def result = ""

	if (encodeB64) {
		result = cipher.doFinal(plainText.getBytes("UTF-8")).encodeBase64().toString()
	} else {
		if (plainText instanceof String) {
			result = cipher.doFinal(plainText.getBytes("UTF-8")).encodeHex().toString()
		} else {
			result = cipher.doFinal(plainText).encodeHex().toString()
		}
	}

	return result
}

// Decrypt ByteArray
def decrypt_bytes (byte[] cypherBytes, def secret, decodeB64=false) {
	if (logTrace) log.trace "*********** Decrypting **************"


	def cipher = Cipher.getInstance("AES/ECB/PKCS5Padding ")

	SecretKeySpec key

	if (secret instanceof String) {
		// Fix key to remove any escaped characters
		secret = secret.replaceAll('&lt;', '<')
		key = new SecretKeySpec(secret.getBytes(), "AES")
	} else {
		key = new SecretKeySpec(secret as byte[], "AES")
	}

	cipher.init(Cipher.DECRYPT_MODE, key)

	if (decodeB64) {
		cypherBytes = cypherBytes.decodeBase64()
	}

	def result = cipher.doFinal(cypherBytes)

	return new String(result, "UTF-8")
}

import java.security.MessageDigest

def generateMD5(String s){
	MessageDigest.getInstance("MD5").digest(s.bytes).encodeHex().toString()
}

def CRC32b(bytes, length) {
	crc = 0xFFFFFFFF

	for (i = 0; i < length; i++) {
		b = Byte.toUnsignedInt(bytes[i])

		crc = crc ^ b
		for (j = 7; j >= 0; j--) {
			mask = -(crc & 1)
			crc = (crc >> 1) ^(0xEDB88320 & mask)
		}
	}

	return ~crc
}

@Field static final Map DPSMAP = [
    "1": [
        "code": "switch",
        "type": "Boolean",
        "values": []
    ],
    "2": [
        "code": "temp_set",
        "type": "Integer",
        "values": [
            "unit": "\u00b0C",
            "min": 0,
            "max": 135,
            "scale": 0,
            "step": 1
        ]
    ],
    "3": [
        "code": "temp_current",
        "type": "Integer",
        "values": [
            "unit": "\u00b0C",
            "min": -20,
            "max": 135,
            "scale": 0,
            "step": 1
        ]
    ],
    "4": [
        "code": "mode",
        "type": "Enum",
        "values": [
            "range": [
                "smart",
                "manual"
            ]
        ]
    ],
    "5": [
        "code": "level",
        "type": "Enum",
        "values": [
            "range": [
                "level_1",
                "level_2",
                "level_3",
                "level_4"
            ]
        ]
    ],
    "6": [
        "code": "eco",
        "type": "Boolean",
        "values": []
    ],
    "7": [
        "code": "child_lock",
        "type": "Boolean",
        "values": []
    ],
    "8": [
        "code": "light",
        "type": "Boolean",
        "values": []
    ],
    "9": [
        "code": "temp_unit_convert",
        "type": "Enum",
        "values": [
            "range": [
                "c",
                "f"
            ]
        ]
    ],
    "10": [
        "code": "temp_set_f",
        "type": "Integer",
        "values": [
            "unit": "\u00b0F",
            "min": 32,
            "max": 270,
            "scale": 0,
            "step": 1
        ]
    ],
    "11": [
        "code": "temp_current_f",
        "type": "Integer",
        "values": [
            "unit": "\u00b0F",
            "min": -4,
            "max": 300,
            "scale": 0,
            "step": 1
        ]
    ],
    "12": [
        "code": "countdown_set",
        "type": "Enum",
        "values": [
            "range": [
                "cancel",
                "1h",
                "2h",
                "3h",
                "4h",
                "5h",
                "6h"
            ]
        ]
    ],
    "13": [
        "code": "countdown_left",
        "type": "Integer",
        "values": [
            "unit": "s",
            "min": 0,
            "max": 86400,
            "scale": 0,
            "step": 1
        ]
    ],
    "14": [
        "code": "state"
    ],
    "15": [
        "code": "fault",
        "type": "Bitmap",
        "values": [
            "label": [
                "ov_cr"
            ]
        ]
    ]
]


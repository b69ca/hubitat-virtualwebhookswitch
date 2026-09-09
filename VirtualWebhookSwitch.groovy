/*
 *  Virtual Webhook Switch
 *
 *  Hubitat virtual switch that calls a configured webhook when turned on and a
 *  separate webhook when turned off.
 *
 *  Author: Jon Wallace
 *  License: MIT
 *
 *  Notes:
 *  - Supports GET and POST webhooks.
 *  - POST requests can send JSON, plain text, or form-encoded bodies.
 *  - URLs and bodies support template tokens: {state}, {deviceName},
 *    {deviceId}, and {timestamp}.
 *  - Switch state can update optimistically or only after a successful webhook.
 *  - Optional retries and auto-off make it useful for flaky endpoints and
 *    button-like automations.
 */

metadata {
  definition(name: "Virtual Webhook Switch", namespace: "custom", author: "Jon Wallace") {
    capability "Switch"
    capability "Refresh"

    attribute "lastWebhookStatus", "string"
    attribute "lastWebhookStatusCode", "string"
    attribute "lastWebhookError", "string"
    attribute "lastWebhookAt", "string"
    attribute "lastWebhookAction", "string"
  }

  preferences {
    input name: "webhookUrlOn", type: "text", title: "On webhook URL", description: "URL to call when the switch turns on.", required: true
    input name: "webhookUrlOnMethod", type: "enum", title: "On webhook method", options: ["GET", "POST"], defaultValue: "POST", required: true
    input name: "webhookBodyOn", type: "text", title: "On webhook body", description: "Optional POST body. Supports {state}, {deviceName}, {deviceId}, and {timestamp}.", required: false

    input name: "webhookUrlOff", type: "text", title: "Off webhook URL", description: "URL to call when the switch turns off.", required: true
    input name: "webhookUrlOffMethod", type: "enum", title: "Off webhook method", options: ["GET", "POST"], defaultValue: "POST", required: true
    input name: "webhookBodyOff", type: "text", title: "Off webhook body", description: "Optional POST body. Supports {state}, {deviceName}, {deviceId}, and {timestamp}.", required: false

    input name: "customHeaders", type: "text", title: "Custom headers", description: "Optional headers, one per line or semicolon-separated, formatted as Header-Name: value.", required: false
    input name: "postContentType", type: "enum", title: "POST content type", description: "Content type used for POST webhook bodies.", options: ["application/json", "text/plain", "application/x-www-form-urlencoded"], defaultValue: "application/json", required: true
    input name: "requestTimeout", type: "number", title: "Request timeout (seconds)", description: "Maximum time to wait for the webhook response.", defaultValue: 15, range: "1..120", required: true
    input name: "updateStateMode", type: "enum", title: "Switch state update", description: "Choose when the switch state should change.", options: ["After successful webhook", "Immediately"], defaultValue: "After successful webhook", required: true
    input name: "duplicateCommandMode", type: "enum", title: "Duplicate commands", description: "Choose whether repeated on/off commands should call the webhook when the switch is already in that state.", options: ["Send webhook every time", "Ignore duplicate state commands"], defaultValue: "Send webhook every time", required: true
    input name: "retryCount", type: "number", title: "Retry attempts", description: "Number of retries after a failed webhook request.", defaultValue: 0, range: "0..5", required: true
    input name: "retryDelaySeconds", type: "number", title: "Retry delay (seconds)", description: "Seconds to wait between webhook retry attempts.", defaultValue: 10, range: "1..300", required: true
    input name: "autoOffSeconds", type: "number", title: "Auto-off delay (seconds)", description: "Set to 0 to disable. When greater than 0, a successful on command schedules an off command after this delay.", defaultValue: 0, range: "0..86400", required: true
    input name: "successStatusCodes", type: "text", title: "Successful HTTP status codes", description: "Comma-separated list or ranges. Example: 200..299,404", defaultValue: "200..299", required: true
    input name: "debugUrlMode", type: "enum", title: "Debug URL logging", description: "Choose how much of the webhook URL appears in debug logs.", options: ["Hide query string", "Log full URL"], defaultValue: "Hide query string", required: true
    input name: "logEnable", type: "bool", title: "Enable debug logging", description: "Log webhook request and response details.", defaultValue: false
  }
}

// ===== Lifecycle =====

def installed() {
  log.info "Installed Virtual Webhook Switch"
  sendEvent(name: "switch", value: "off")
  clearWebhookStatus()
}

def updated() {
  log.info "Updated settings"
  clearPendingWebhook()
  unschedule("runPendingWebhook")

  if (getAutoOffSeconds() == 0) {
    unschedule("autoOff")
  }
}

// ===== Commands =====

def on() {
  handleSwitchCommand("on")
}

def off() {
  handleSwitchCommand("off")
}

def autoOff() {
  if (device.currentValue("switch") == "on") {
    handleSwitchCommand("off")
  }
}

def refresh() {
  if (!device.currentValue("switch")) {
    sendEvent(name: "switch", value: "off")
  }
}

// ===== Webhook =====

def handleSwitchCommand(targetState) {
  if (shouldIgnoreDuplicateCommand(targetState)) {
    recordWebhookIgnored(targetState)
    return
  }

  state.pendingWebhookTargetState = targetState
  state.pendingWebhookAttempt = 0
  runPendingWebhook()
}

def runPendingWebhook() {
  def targetState = state.pendingWebhookTargetState

  if (!targetState) {
    return
  }

  def attempt = state.pendingWebhookAttempt != null ? state.pendingWebhookAttempt as Integer : 0
  def config = getWebhookConfig(targetState)

  if (!config.uri) {
    recordWebhookFailure(targetState, "Webhook URL is not configured.")
    clearPendingWebhook()
    return
  }

  if (attempt == 0 && shouldUpdateImmediately()) {
    setSwitchState(targetState)
  }

  def success = sendWebhook(targetState, config)

  if (success) {
    if (!shouldUpdateImmediately()) {
      setSwitchState(targetState)
    }

    scheduleAutoOffIfNeeded(targetState)
    clearPendingWebhook()
    return
  }

  if (attempt < getRetryCount()) {
    state.pendingWebhookAttempt = attempt + 1
    log.warn "Webhook ${targetState} failed; retrying attempt ${state.pendingWebhookAttempt} of ${getRetryCount()} in ${getRetryDelaySeconds()} seconds."
    runIn(getRetryDelaySeconds(), runPendingWebhook)
  } else {
    clearPendingWebhook()
  }
}

def sendWebhook(targetState, config) {
  def params = buildRequestParams(config)

  if (logEnable) {
    log.debug "Sending ${config.method} webhook for ${targetState}: ${getLoggableUri(config.uri)}"
  }

  try {
    def success = false

    if (config.method == "GET") {
      httpGet(params) { resp ->
        success = handleWebhookResponse(targetState, resp)
      }
    } else {
      httpPost(params) { resp ->
        success = handleWebhookResponse(targetState, resp)
      }
    }

    return success
  } catch (java.net.SocketTimeoutException e) {
    recordWebhookFailure(targetState, "Webhook request timed out.")
  } catch (Exception e) {
    recordWebhookFailure(targetState, e.message ?: e.toString())
  }

  return false
}

def handleWebhookResponse(targetState, resp) {
  def statusCode = resp?.status

  if (logEnable) {
    log.debug "Webhook response for ${targetState}: status=${statusCode}"
  }

  if (isSuccessfulStatusCode(statusCode)) {
    recordWebhookSuccess(targetState, statusCode)
    return true
  } else {
    recordWebhookFailure(targetState, "Unexpected HTTP status ${statusCode}", statusCode)
    return false
  }
}

def buildRequestParams(config) {
  def params = [
    uri: config.uri,
    contentType: "text/plain",
    timeout: getRequestTimeout()
  ]
  def headers = parseCustomHeaders()

  if (headers) {
    params.headers = headers
  }

  if (config.method == "POST") {
    def contentType = getPostContentType()
    params.requestContentType = contentType

    if (config.body) {
      params.body = config.body
    }
  }

  return params
}

// ===== State =====

def setSwitchState(newState) {
  sendEvent(name: "switch", value: newState, descriptionText: "${device.displayName} is ${newState}")
}

def recordWebhookSuccess(targetState, statusCode) {
  sendEvent(name: "lastWebhookStatus", value: "success")
  sendEvent(name: "lastWebhookStatusCode", value: statusCode?.toString() ?: "")
  sendEvent(name: "lastWebhookError", value: "")
  sendEvent(name: "lastWebhookAction", value: targetState)
  sendEvent(name: "lastWebhookAt", value: getNowString())
}

def recordWebhookFailure(targetState, message, statusCode = null) {
  log.warn "Webhook ${targetState} failed: ${message}"
  sendEvent(name: "lastWebhookStatus", value: "failed")

  if (statusCode != null) {
    sendEvent(name: "lastWebhookStatusCode", value: statusCode.toString())
  }

  sendEvent(name: "lastWebhookError", value: message)
  sendEvent(name: "lastWebhookAction", value: targetState)
  sendEvent(name: "lastWebhookAt", value: getNowString())
}

def recordWebhookIgnored(targetState) {
  if (logEnable) log.debug "Ignoring duplicate ${targetState} command; switch is already ${targetState}."
  sendEvent(name: "lastWebhookStatus", value: "ignored")
  sendEvent(name: "lastWebhookStatusCode", value: "")
  sendEvent(name: "lastWebhookError", value: "Duplicate ${targetState} command ignored.")
  sendEvent(name: "lastWebhookAction", value: targetState)
  sendEvent(name: "lastWebhookAt", value: getNowString())
}

def clearWebhookStatus() {
  sendEvent(name: "lastWebhookStatus", value: "never run")
  sendEvent(name: "lastWebhookStatusCode", value: "")
  sendEvent(name: "lastWebhookError", value: "")
  sendEvent(name: "lastWebhookAction", value: "")
  sendEvent(name: "lastWebhookAt", value: "")
}

// ===== Helpers =====

def getWebhookConfig(targetState) {
  if (targetState == "on") {
    return [
      uri: applyTemplate(settings?.webhookUrlOn, targetState),
      method: settings?.webhookUrlOnMethod ?: "POST",
      body: applyTemplate(settings?.webhookBodyOn, targetState)
    ]
  }

  return [
    uri: applyTemplate(settings?.webhookUrlOff, targetState),
    method: settings?.webhookUrlOffMethod ?: "POST",
    body: applyTemplate(settings?.webhookBodyOff, targetState)
  ]
}

def shouldUpdateImmediately() {
  return settings?.updateStateMode == "Immediately"
}

def shouldIgnoreDuplicateCommand(targetState) {
  return settings?.duplicateCommandMode == "Ignore duplicate state commands" && device.currentValue("switch") == targetState
}

def clearPendingWebhook() {
  state.remove("pendingWebhookTargetState")
  state.remove("pendingWebhookAttempt")
}

def getRequestTimeout() {
  def timeout = settings?.requestTimeout != null ? settings.requestTimeout as Integer : 15
  return Math.max(1, Math.min(120, timeout))
}

def getRetryCount() {
  def retries = settings?.retryCount != null ? settings.retryCount as Integer : 0
  return Math.max(0, Math.min(5, retries))
}

def getRetryDelaySeconds() {
  def seconds = settings?.retryDelaySeconds != null ? settings.retryDelaySeconds as Integer : 10
  return Math.max(1, Math.min(300, seconds))
}

def getAutoOffSeconds() {
  def seconds = settings?.autoOffSeconds != null ? settings.autoOffSeconds as Integer : 0
  return Math.max(0, Math.min(86400, seconds))
}

def getPostContentType() {
  return settings?.postContentType ?: "application/json"
}

def scheduleAutoOffIfNeeded(targetState) {
  def seconds = getAutoOffSeconds()

  if (targetState == "on" && seconds > 0) {
    unschedule("autoOff")
    runIn(seconds, autoOff)
    if (logEnable) log.debug "Scheduled auto-off in ${seconds} seconds."
  } else if (targetState == "off") {
    unschedule("autoOff")
  }
}

def parseCustomHeaders() {
  def headers = [:]

  if (!settings?.customHeaders) {
    return headers
  }

  settings.customHeaders.toString().split("\\r?\\n|;").each { line ->
    def trimmed = line.trim()
    if (trimmed) {
      def separatorIndex = trimmed.indexOf(":")

      if (separatorIndex > 0) {
        def name = trimmed.substring(0, separatorIndex).trim()
        def value = trimmed.substring(separatorIndex + 1).trim()

        if (name && value) {
          headers[name] = value
        }
      } else {
        log.warn "Ignoring invalid custom header '${trimmed}'. Expected format: Header-Name: value"
      }
    }
  }

  return headers
}

def isSuccessfulStatusCode(statusCode) {
  if (statusCode == null) {
    return false
  }

  def code = statusCode as Integer
  def configuredCodes = settings?.successStatusCodes ?: "200..299"

  try {
    return configuredCodes.toString().split(",").any { part ->
      def trimmed = part.trim()

      if (!trimmed) {
        return false
      }

      if (trimmed.contains("..")) {
        def rangeParts = trimmed.split("\\.\\.")
        if (rangeParts.size() == 2) {
          def start = rangeParts[0].trim() as Integer
          def end = rangeParts[1].trim() as Integer
          return code >= start && code <= end
        }
      }

      return code == (trimmed as Integer)
    }
  } catch (Exception e) {
    log.warn "Invalid successful status code setting '${configuredCodes}'. Falling back to 200..299."
    return code >= 200 && code <= 299
  }
}

def getNowString() {
  return new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
}

def getLoggableUri(uri) {
  if (settings?.debugUrlMode == "Log full URL") {
    return uri
  }

  def queryIndex = uri?.indexOf("?")
  return queryIndex != null && queryIndex >= 0 ? "${uri.substring(0, queryIndex)}?..." : uri
}

def applyTemplate(value, targetState) {
  if (value == null) {
    return null
  }

  return value.toString()
    .replace("{state}", targetState)
    .replace("{deviceName}", device.displayName ?: "")
    .replace("{deviceId}", device.id?.toString() ?: "")
    .replace("{timestamp}", getNowString())
}

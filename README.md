# Virtual Webhook Switch

Creates a Hubitat switch that calls one configured webhook when turned on and another when turned off. Use it to connect Hubitat switch automations to an HTTP service.

## What it does

- Supports GET or POST independently for On and Off.
- Sends JSON, plain-text, or form-encoded POST bodies and optional custom headers.
- Substitutes `{state}`, `{deviceName}`, `{deviceId}`, and `{timestamp}` in URLs and bodies.
- Supports configurable success codes, retries, duplicate-command handling, and delayed auto-off.
- Tracks the last webhook result, status code, error, action, and timestamp.

## Installation

1. Open **Drivers Code** in the Hubitat hub interface and create a new driver.
2. Paste [VirtualWebhookSwitch.groovy](VirtualWebhookSwitch.groovy) (or import the [raw source](https://raw.githubusercontent.com/b69ca/hubitat-virtualwebhookswitch/main/VirtualWebhookSwitch.groovy)) and save it.
3. Under **Devices**, add a virtual device and select **Virtual Webhook Switch** as its driver type.
4. Set the preferences below and save them.
5. Test **On** and **Off**, then inspect `lastWebhookStatus` and the receiving service.

The hub must be able to reach both configured webhook endpoints.

## Preferences

| Setting | Default | Purpose |
| --- | --- | --- |
| On webhook URL | Not set | URL to call when the switch turns on. |
| On webhook method | POST | Choices: GET, POST. |
| On webhook body | Not set | Optional POST body. Supports {state}, {deviceName}, {deviceId}, and {timestamp}. |
| Off webhook URL | Not set | URL to call when the switch turns off. |
| Off webhook method | POST | Choices: GET, POST. |
| Off webhook body | Not set | Optional POST body. Supports {state}, {deviceName}, {deviceId}, and {timestamp}. |
| Custom headers | Not set | Optional headers, one per line or semicolon-separated, formatted as Header-Name: value. |
| POST content type | application/json | Choices: application/json, text/plain, application/x-www-form-urlencoded. Content type used for POST webhook bodies. |
| Request timeout (seconds) | 15 | Maximum time to wait for the webhook response. Range: 1..120. |
| Switch state update | After successful webhook | Choices: After successful webhook, Immediately. Choose when the switch state should change. |
| Duplicate commands | Send webhook every time | Choices: Send webhook every time, Ignore duplicate state commands. Choose whether repeated on/off commands should call the webhook when the switch is already in that state. |
| Retry attempts | 0 | Number of retries after a failed webhook request. Range: 0..5. |
| Retry delay (seconds) | 10 | Seconds to wait between webhook retry attempts. Range: 1..300. |
| Auto-off delay (seconds) | 0 | Set to 0 to disable. When greater than 0, a successful on command schedules an off command after this delay. Range: 0..86400. |
| Successful HTTP status codes | 200..299 | Comma-separated list or ranges. Example: 200..299,404 |
| Debug URL logging | Hide query string | Choices: Hide query string, Log full URL. Choose how much of the webhook URL appears in debug logs. |
| Enable debug logging | false | Log webhook request and response details. |

## Usage and behavior

Configure both endpoint URLs and select the methods your service expects. For a JSON POST endpoint, an example body is:

```json
{"state":"{state}","device":"{deviceId}","timestamp":"{timestamp}"}
```

Enter custom headers one per line or separated by semicolons, using `Header-Name: value`. Template replacement is literal; provide the escaping/encoding your endpoint requires.

By default, the switch changes state only after a successful webhook. **Immediately** changes the local state before sending and does not roll it back on failure. Repeated commands send a webhook unless duplicate suppression is selected. Auto-off, when enabled, sends the configured Off webhook after a successful On; it is not just a local state reset.

**Refresh** only initializes a missing local switch state. It does not resend webhooks or query the remote service. There is no background status polling.

## Device interface

Capabilities: `Switch`, `Refresh`.

Standard readings/state: `switch`.

Additional attributes: `lastWebhookStatus`, `lastWebhookStatusCode`, `lastWebhookError`, `lastWebhookAt`, `lastWebhookAction`.

## Troubleshooting

- Check `lastWebhookStatus`, `lastWebhookStatusCode`, and `lastWebhookError` for request failures.
- Confirm the HTTP method, body, content type, headers, and success-code range match your endpoint.
- Debug logging hides query strings by default; full URL logging is optional.

## Updating

Replace the saved driver code in Hubitat with the latest source and save it. Keep existing devices; there is no need to recreate them. Save preferences and refresh as applicable.

## License

[MIT License](LICENSE). Author: Jon Wallace.

# SolaX Cloud developer API – full reference for this project

**Part B of this file is a complete copy of the original *SolaX Developer Portal* documentation** (the owner saved the page on 2026-10-16): every section, request/response field table, JSON example and appendix, in the portal's own wording (including its typos such as "Interger"). Only the portal's navigation menu and the account footer were left out, and the one diagram of the portal (the request state machine in Appendix 8) is an image that could not be copied. **Part A** holds the notes of this project (how the app uses the API, findings from the owner's real account, open points); short "Project note" blocks under some endpoints in part B repeat the findings that belong to them.

Code that uses the API: `src/main/kotlin/Solax.kt`; tests: `src/test/kotlin/SolaxTest.kt`, `ControlTest.kt`. Statements marked *verified* were confirmed against the owner's real account (2026-10-21); *unverified* ones were not. Check the portal before relying on a detail.

Quick facts (details in the copy below): all paths start with `/openapi/…`; the owner's region host is `https://openapi-eu.solaxcloud.com` (env `SOLAX_URL` overrides the default); calls except the token request succeed on `code` 10000 (the token request on 0); send `Authorization: bearer <access_token>`; `businessType` 1 = residential, 4 = commercial & industrial; power fields are W for residential and kW for C&I; control commands are asynchronous.

# Part A – Project notes

## A1. How the project uses the API

- **Flow** (`SolaxService.sync`): token (cached) → all plants of the account (`page_plant_info` without a `plantId` filter, `pageNo` 1…`pages`, at most five pages) → the selected plant's record (`pvCapacity`, `batteryCapacity`; read on first connect, then about weekly, or when the credentials or the selected plant change) → `get_stat_data` once per month involved → `pvGeneration` per day stored in `produced`.
- **Plant choice:** the settings page lists the account's plants (`solax.plants`); the first is the default; new credentials reset the choice; a selected plant that no longer exists gives a status asking to choose another.
- **Per place** (`solax` table): credentials, the selected plant id and plant list, `pv_kwp`, `battery_kwh`, token, status and `due_at`; daily values in `produced`. Deleting a place deletes both.
- **Battery charge:** hourly (every 30 minutes while the night zone lasts); `page_device_info` (`deviceType=1`) lists the inverters (serials cached in `inverter_sns`), then `realtime_data` (`snList` = inverter serials, `deviceType=2`, `requestSnType=1`) returns one entry per battery; the app averages `batterySOC`, sums `batteryRemainings` and `chargeDischargePower`.
- **Battery history of past days:** two requests per day (12-hour limit), one day per 10-minute loop pass; the day's highest charge counts, 99 % or more as "reached 100 %".
- **Capacity has up to three figures that can disagree:** the plant record's `batteryCapacity` (owner's: 5.8 kWh for a battery sold as 15 kWh), the battery devices' `ratedCapacity` (Wh for residential; the owner's device list returned none) and a usable capacity computed from `batteryRemainings` ÷ charge (owner's reading: 10 % and 1.2 kWh ⇒ about 12 kWh). The app keeps the raw answer when nothing usable comes back (`solax.diag_devices`, `solax.diag_history`).
- **Inverter control (night charging):** with a place's *Разреши управлене* on, the night planner creates TOU rules (TOU endpoints in part B) with `SolaxService.programCharge` / `removeChargeRules`; see A2 and `AGENTS.md`.
- **Retrying failures:** every failed call counts against the allowance, so the app waits by cause: **12 hours** for wrong credentials, a refused token or no access (`10401`, `10402` twice, `10403`, `10500`, `10505`, `10506`; changing the credentials ends the wait), **6 hours** when the allowance is used up (`10405`), **1 hour** for rate limits (`10406`), other codes and network errors. The same waits apply to the daily battery read and battery history. Changing credentials for the same plant keeps everything stored; a different account's plant list wipes the old plant's data.
- The Client Secret is stored as given (needed for the API) and must never be returned by the app's own API, logged, or put into a status text.

## A2. Findings from the owner's account and open points

### Verified on 2026-10-21 (residential, three-phase hybrid inverter, deviceModel family X3-Hybrid)

- The developer credentials **may send control commands** (`add_tou_set` returned `10000`; no `10403`).
- **A TOU rule is a full-day schedule:** the first segment must start at `00:00` (else `FIELD_ERROR: startTime setting must start from 00:00`), `startTime` must be before `endTime` (`FIELD_ERROR: startTime need before endTime`), the last segment ends at **`23:59`** (`24:00` → `PARAM_ERROR: endDate format must be HH:mm, with hours between 00-23…`), segments share their boundary time, and every self-use segment needs its own `minSoc` (`FIELD_IS_NULL: Tou Self-Consumption minSoc`). **A segment cannot cross midnight**, so a charge across midnight needs **one rule per date** (single-date `startDate`=`endDate`): the first day's charge runs to `23:59`, the next day's starts at `00:00`.
- `add_tou_set` answers `result.optDeviceList[0].ruleId` (a 19-digit number: read it as text) and `status` 1 (= added, per this call's documentation).
- `query_tou_set` needs `deviceSn` (one serial), `businessType` and **`date`** (`MM-dd`); `startDate`/`endDate` keys give `FIELD_IS_BLANK: startDate`, GET gives HTTP 500, a form body is unsupported. It returned the stored rule exactly as sent, and after `delete_tou_set` the date came back empty (`ruleId` null).
- `delete_tou_set` with `deviceSn`, `businessType`, `ruleId` returns `10000`.
- `apiRequestLog/listByCondition` returned no rows for TOU commands (the "Request Result Query" endpoint).
- A live attempt (rule for 10:37–10:57, `chargePower` 1000, target 21 %, battery at 17 %, daytime) showed **no sign of the rule acting**: grid power stayed 0, the battery kept charging at about 2 kW from the panels (more than the 1000 W cap, which by the documentation covers PV and grid charging together), and `deviceStatus` read **102 (Normal)** inside the window instead of **132 (TOU-Charging)**. The cloud stored the rule, but the inverter did not appear to run it. Possible causes: the inverter must be in a TOU work mode first (the portal documents no call for that), the command had not been delivered to the device, or this inverter model/firmware does not support TOU rules through the API.

- **Remote-control (VPP) modes, tried 2026-10-21 (daytime):** both were accepted (`status` 3) and became active after about 5 minutes (`deviceStatus` 130; the cloud values update only about every 5 minutes). **`pv_and_bat/individual_setting_target_soc_mode`** (`batteryPower` 1000, `pvPowerLimit` 9000, `targetSoc` 22, 900 s) left the battery **idle at 0 W with about 660 W imported from the grid** - not what was wanted; the meaning of its `batteryPower` is unclear, do not use it. **`push_power/positive_or_negative_mode`** (`batteryPower` **-1000** = charge, 900 s, `nextMotion` 160) held the battery at **about 960 W charging** while the panels could have given 2.4-2.6 kW, grid about 0 - so the power setting is respected. `exit_vpp_mode` answers `status` 3 and the inverter returns to `deviceStatus` 102. Not yet tried at night (real grid charging) and without a target SOC the caller must stop it (exit) when the charge reaches the target.
- The inverter's work mode in the SolaX app was **Self Use** (confirmed by the owner); `deviceStatus` 102 means only "Normal" and does not name the work mode. Adding a TOU rule through the API did not switch the inverter into TOU, so the rule never ran.

### Open points [unverified]

- How to make the inverter **act** on a TOU rule (see above). Next steps to try: read `deviceStatus` (131–135 / 150) during a window to see the real mode; check the SolaX app for the work mode; try the **PV & battery individual setting** remote modes ("Inverter Remote Control Mode"), which charge from PV and grid at a set battery power for a set duration without changing the stored work-mode configuration, or the self-use window call ("Inverter Work Mode Control" → Self Use Mode, no power cap).
- That `chargePower` caps charging power (the documentation says it caps PV plus grid charging) and that charging stops at `targetSoc`.
- Which mode the inverter returns to outside the rule's date or after the window.
- What happens when a rule is added for a date that already has one (the app deletes its own earlier rules first).
- Whether `get_stat_data` dates are in the plant's time zone (assumed) and how complete today's value is during the day (the chart treats it as "so far").
- Whether `pvGeneration` covers third-party PV on EMS/C&I plants as on a residential inverter.
- The exact call quota and rate for a developer account is not stated; the app makes about one statistics call a day per place plus one battery call per hour (every 30 minutes at night) and the weekly plant record.


# Part B – Original API documentation (full copy)

## Get Started

The Developer Portal provides developers with an efficient and convenient way to build and integrate a wide range of functions and services. All you need to do is choose the API endpoints that match your actual needs. This documentation will provide you with information about the API and how to integrate it.

### API Usage Guide

Welcome to the SolaX Developer Portal! To ensure the efficiency, security, and consistency of the API, please adhere to the platform's calling specifications. Reverse engineering or unauthorized data collection through the API is prohibited.

### API Call URL

The specific account's API URL can be found by clicking on "My Account" in the upper right corner.

## Authentication and Authorization

### Access Token Description

In addition to the API for obtaining and refreshing the access token as described in this section, all API interfaces require the access token for authentication.

When calling business interfaces, please include the access token in the HTTP request header, passing it through the Authorization request header.

Request header description

```text
 | Header Parameters  | Example
 | Authorization  | String  | Required  | bearer C8YLt10o-CdDlu7tyBKIXir5K8
 | bearer + space + (access_token)
Explanation:
bearer is the fixed authentication type; the first letter 'b' must be lowercase.
bearer and access_token are separated by a space.
(access_token) is the access token returned after calling the "Obtain Access Token" or "Obtain the access_token" interface.
If a valid access_token is not provided or the token has expired, the interface will return an authentication failure error.
```

### Obtain Access Token

**`POST /openapi/auth/oauth/token`**

Get Developer Portal access_token through Client ID and Client Secret in application.

All requests to access Developer Portal must be authenticated by the access_token. The default validity period of the access_token is 30 days. Calling other interfaces within the validity period will not automatically extend the token's validity.

To extend the validity period, you must call this interface again to obtain a new access_token.

The access token obtained via this interface grants device permissions covering all devices associated with this account on SolaXCloud.

#### Request Fields

```text
 | Body Parameters application/x-www-form-urlencoded  | Example
 | client_id  | String  | Required  | client_id=dse******23
    client_secret=d23******f2f
    grant_type=client_credentials
 | Client id, obtained from the specific application.
 | client_secret  | String  | Required
 | The Client Secret content, obtained from the specific application.
 | grant_type  | string  | Required
 | Authorization type; Fixed value "client_credentials"
```

#### Returned Fields

```text
 | Request Parameters  | Type  | Description  | Example
 | code  | integer  | 0 indicates success, non-0 indicates failure  | {
        "code": 0,
        "result": {
                "access_token": "0YU_CJKbSZjRVz1iZpgzu5token",
                "token_type": "bearer",
                "expires_in": 2591999,
                "scope": "all",
                "grant_type": "client_credentials",
                "auth_station": "all"
        }
  }
 | message  | String  | Error Message
 | result  | Data  | Specific data
 | Result description
 | access_token  | String  | The value of the pass token
 | token_type  | String  | Pass token type
 | expires_in  | Integer  | Expiration time
 | scope  | String  | Access domain (default is all)
 | grant_type  | String  | Authorization type
 | auth_station  | String  | The scope of authorized plants; under the `client_credentials` grant type, the default value is `all`.
```

> **Project note.** **Verified (client credentials):** works as described; the answer's `code` is `0` on success. The app caches the token and renews it an hour before expiry.

### Third-party OAuth 2.0 authorized login

OAuth 2.0 authorization allows SolaXCloud users to securely log in to integrated third-party applications or websites. After a user authorizes a third-party application or website that uses OAuth 2.0, the third party can obtain an access token, which can then be used to retrieve basic user information and other authorized related information.

SolaX OAuth 2.0 authorization currently supports the authorization code grant type.  In this mode, an authorization code is returned to the third-party application after user authorization. This authorization code can only be used once, and the third party can exchange it for an access token.

### Authorization Process

1. A SolaX user requests to use a third-party application.

2. The third-party application initiates a request to the SolaX developer platform for the user's authorization login interface.

3. The SolaX developer platform returns the rendered login interface.

4. The third-party application displays the login interface to the user.

5. The user enters their SolaXCloud account username and password to log in.

6. The account is verified, and authorization information is returned to the third-party application, requesting user confirmation.

7. The user agrees to the authorization request.

8. After receiving the user's authorization confirmation, the SolaX developer platform redirects to the third-party application interface, including a code.

9. The third-party application calls the access_token acquisition interface provided by the SolaX developer platform to obtain the access_token.

10. The SolaX developer platform returns the access_token to the third-party application through the response parameters.

### Authorized address

SolaX authorization code acquisition interface address::

{$URL Domain}/openapi/auth/oauth/authorize?response_type=code

&client_id={$Your Client ID}

&redirect_uri={$Your Redirect URL}

&scope={$Scope}

&state={$User definition state string}

&lang={$User definition language string}

Note:

{$URL Domain} is the API call address for this account;

{$Your Redirect URL} is the OAuth2.0 redirect address, which needs to be entered in the application on the SolaX developer platform in advance;

{$Your Client ID} and {$Scope} are information provided by SolaX;

{$User definition state string} is a user-defined string;

{$User definition language string}is the default language for website display, English is en_US.

Example:

https://openapi-eu.solaxcloud.com/openapi/auth/oauth/authorize?response_type=code&client_id=e7005d096366666b59ae865cf8ed&redirect_uri=https://developer.solaxcloud.com/callback&scope=all&state=ct0eps&lang=en_US

The format for redirecting to a new address is:

{$Your Redirect URL}?

code={authorization code}

&state={$User definition state string}

Note:

{$Your Redirect URL} is the OAuth 2.0 redirect URL;

{authorization code} is the authorization code;

{$User definition state string} is a user-defined string.

Example:

https://developer.solaxcloud.com/callback?code=sjG2Aj&state=ct0eps

### Obtain the access_token

**`POST /openapi/auth/oauth/token`**

Obtain the access token. Note that the authorization code is valid for a short period of 10 minutes and can only be used once; it becomes invalid immediately after obtaining the access token.

For the access_token obtained via this interface, the scope of device permissions corresponds to the range of devices selected by the authorizing account.

#### Request Fields

```text
 | Body Parameters application/x-www-form-urlencoded  | Example
 | client_id  | String  | Required  | client_id=dse******23
    client_secret=d23******f2f
    grant_type=authorization_code
    code=sjG2Aj
    redirect_uri=https://developer.solaxcloud.com/callback
 | Client id, obtained from the specific application.
 | client_secret  | String  | Required
 | The Client Secret content, obtained from the specific application.
 | grant_type  | string  | Required
 | Authorization type; for OAuth 2.0 authorization code flow, the value is fixed as "authorization_code".
 | code  | String  | Required
 | Authorization code, required when using the authorization code grant type.
 | redirect_uri  | String  | Required
 | Redirect URL, required for authorization code flow.
```

#### Returned Fields

```text
 | Request Parameters  | Type  | Description  | Example
 | code  | integer  | 0 indicates success, non-0 indicates failure  | {
      "code": 0,
      "result": {
          "access_token": "6teBnN8xTy51IDanUUcnL2uQg_I",
          "token_type": "bearer",
          "refresh_token": "QUzT_Fwb5YHTMQCNPGx8J0wV5yA",
          "expires_in": 86399,
          "scope": "all",
          "grant_type": "authorization_code",
          "auth_station": "193855248774402 197197434151617"
      }
  }
 | message  | String  | Error Message
 | result  | Data  | Specific data
 | Result description
 | access_token  | String  | The value of the pass token
 | refresh_token  | String  | Supports refreshing tokens, and when the access_token expires (or has not yet expired), the refresh_token can be used to refresh the access_token through the refresh_token interface.
 | token_type  | String  | Pass token type
 | expires_in  | Integer  | Expiration time, unit: s
 | scope  | String  | Access domain (default is all)
 | grant_type  | String  | Authorization type
 | auth_station  | String  | Authorized plants Scope (Content: A collection of plant ids)
```

> **Project note.** **Verified (client credentials):** works as described; the answer's `code` is `0` on success. The app caches the token and renews it an hour before expiry.

### Refresh access_token

**`POST /openapi/auth/oauth/token`**

Use refresh_token to refresh the access_token.

#### Request Fields

```text
 | Header Parameters  | Example
 | Authorization  | String  | Required  | basic YWRtaW46MTIzNDU2
 | Add a space after "basic", then concatenate it with the Base64 encoded result of `clientId + ":" + clientSecret`. (See http://www.esjson.com/base64Encrypt.html for an example.)
For example, the Base64 encoded result of `admin:123456` is `YWRtaW46MTIzNDU2`.
 | Body Parameters application/x-www-form-urlencoded  | Required
 | grant_type  | String  | Required  | grant_type=refresh_token
    refresh_token=QUzT_Fwb5YHTMQCNPGx8J0wV5yA
 | The authorization operation type; when refreshing the access token, the value is "refresh_token".
 | refresh_token  | String  | Required
 | The refresh token obtained during authorization.
```

#### Returned Fields

```text
 | Request Parameters  | Type  | Description  | Example
 | code  | integer  | 0 indicates success, non-0 indicates failure  | {
        "code": 0,
        "result": {
            "access_token": "Z3kFpW3NHroliqdfvNLgym4hyXU",
            "token_type": "bearer",
            "refresh_token": "QUzT_Fwb5YHTMQCNPGx8J0wV5yA",
            "expires_in": 86399,
            "scope": "all",
            "auth_station": "193855248774402 197197434151617"
        }
    }
 | message  | String  | Error Message
 | result  | Data  | Specific data
 | Result description
 | access_token  | String  | The value of the pass token
 | refresh_token  | String  | Supports refreshing tokens, and when the access_token expires (or has not yet expired), the refresh_token can be used to refresh the access_token through the refresh_token interface.
 | token_type  | String  | Pass token type
 | expires_in  | Integer  | Expiration time, unit: s
 | scope  | String  | Access domain (default is all)
 | auth_station  | String  | Authorized plants Scope (Content: A collection of plant ids)
```

> **Project note.** **Verified (client credentials):** works as described; the answer's `code` is `0` on success. The app caches the token and renews it an hour before expiry.

## Information Management

### Query Plant Information

**`GET /openapi/v2/plant/page_plant_info`**

Query the information of all plants covered by the current application, typically within the scope of plants that the account has access to, excluding shared plants.

#### Request Fields

```text
 | Params Parameters  | Example
 | plantId  | String  | Optional  | /openapi/v2/plant/page_plant_info
    ?plantId=339663******618
    &businessType=1
 | Plant id
 | plantName  | String  | Optional
 | Plant name
 | pageNo  | Interger  | Optional
 | Query page number, defaults to 1 if left blank
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial
```

#### Returned Fields

```text
 | Parameter Name  | Type  | Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
        "code": 10000,
        "result": {
                "total": 1,
                "pages": 1,
                "current": 1,
                "records": [{
                        "plantId": "339663******618",
                        "plantName": "pro",
                        "loginName": "test_uu",
                        "batteryCapacity": 0.0,
                        "pvCapacity": 0.0,
                        "createTime": "2025-03-26T10:05:30.022Z",
                        "plantTimeZone": "(UTC+01:00)Amsterdam, Berlin, Bern, Rome, Stockholm, Vienna",
                        "plantState": 0,
                        "plantAddress": "1 Street",
                        "longitude": 2.1491173,
                        "latitude": 41.3750289,
                        "electricityPriceUnit": "CNY"
                }],
                "size": 10
        }
}
 | message  | String  | Error Message
 | result  | Data  | Specific data
 | Result description
 | total  | Integer  | Total number
 | pages  | Integer  | Total pages
 | current  | Integer  | Current page (Query page number)
 | size  | Integer  | Items per page
 | records  | List  | Specific data
 | Records description
 | plantId  | String  | Plant id
 | plantName  | String  | Plant name
 | loginName  | String  | User Name
 | batteryCapacity  | Double  | Installed capacity of the plant’s energy storage, unit: kWh
 | pvCapacity  | Double  | Installed PV capacity, unit: kWp
 | createTime  | String  | Creation time, UTC time, compliant with ISO8601
 | plantTimeZone  | String  | Current time zone of the plant, IANA
 | plantState  | Integer  | Plant status, for detailed information on the specific values, please refer to "Appendix 2: Description and definition of the plantState field"
 | plantAddress  | String  | Plant address
 | longitude  | Double  | Longitude
 | latitude  | Double  | Latitude
 | electricityPriceUnit  | String  | Electricity price unit, IOS4027
```

> **Project note.** **Verified, used.** `plantTimeZone` in practice may be a Windows-style label despite the text saying IANA.

### Query Device Information

**`GET /openapi/v2/device/page_device_info`**

Query the device information associated with account.

#### Request Fields

```text
 | Params Parameters  | Example
 | plantId  | Long  | Optional  | /openapi/v2/device/page_device_info
    ?plantId=339663******618
    &deviceType=1
    &businessType=1
 | Plant id
 | deviceType  | Integer  | Required
 | Device type, for detailed information on the specific values, please refer to "Appendix 3: Description and definition of the deviceType field"
 | deviceSn  | String  | Optional
 | Device SN
 | pageNo  | Interger  | Optional
 | Query page number, defaults to 1 if left blank
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial
```

#### Returned Fields

```text
 | Parameter Name  | Type  | Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
        "code": 10000,
        "result": {
                "total": 1,
                "pages": 1,
                "current": 1,
                "records": [{
                        "registerNo": "SE******SP",
                        "deviceModel": 101,
                        "armVersion": "016.07",
                        "dspVersion": "016.07",
                        "ratedPower": "60",
                        "status": 1,
                        "runStatus": 3,
                        "flag": 0,
                        "deviceSn": "X3******01",
                        "plantId": "339663******618",
                        "plantName": "test_uu"
                }],
                "size": 10
        }
}
 | message  | String  | Error Message
 | result  | Data  | Specific data
 | Result description
 | total  | String  | Total number
 | pages  | Integer  | Total pages
 | current  | Integer  | Current page (Query page number)
 | size  | Integer  | Items per page
 | records  | List  | Specific data
 | records-Inverter
 | registerNo  | String  | Device registration number
 | deviceSn  | String  | Device SN
 | deviceModel  | Integer  | Device model, for detailed information on the specific values, please refer to "Appendix 4: Description and definition of the deviceModel field"
 | plantId  | String  | Plant id
 | armVersion  | String  | ARM version
 | dspVersion  | String  | DSP version
 | ratedPower  | String  | Rated power, unit: kw
 | onlineStatus  | Integer  | Online status,0:Offline, 1:Online
 | flag  | Integer  | Parallel status, for detailed information on the specific values, please refer to "Appendix 5: Description and definition of the flag field for the device parallel operation status"
 | records-Battery
 | registerNo  | String  | Device registration number
 | deviceSn  | String  | Device SN
 | deviceModel  | Integer  | Device model, for detailed information on the specific values, please refer to "Appendix 4: Description and definition of the deviceModel field"
 | plantId  | String  | Plant id
 | softwareVersion  | String  | software version
 | hardwareVersion  | String  | Hardware version
 | ratedCapacity  | String  | Battery capacity, unit: Wh(businessType=1), kWh(businessType=4)
 | onlineStatus  | Integer  | Online status,0:Offline, 1:Online
 | records-Meter
 | registerNo  | String  | Device registration number
 | deviceSn  | String  | Device SN
 | deviceModel  | Integer  | Device model, for detailed information on the specific values, please refer to "Appendix 4: Description and definition of the deviceModel field"
 | plantId  | String  | Plant id
 | onlineStatus  | Integer  | Online status,0:Offline, 1:Online
 | records-EV Charger
 | registerNo  | String  | Device registration number
 | deviceSn  | String  | Device SN
 | deviceModel  | Integer  | Device model, for detailed information on the specific values, please refer to "Appendix 4: Description and definition of the deviceModel field"
 | plantId  | String  | Plant id
 | armVersion  | String  | ARM version
 | singleThreePhase  | String  | Single-phase or three-phase, 0: Single-phase; 1: Three-phase
 | ratedPower  | String  | Rated power，unit: kw
 | onlineStatus  | Integer  | Online status, 0: Offline, 1: Online
```

> **Project note.** **Verified, used.** On the owner's account the battery device list returned no `ratedCapacity`. `onlineStatus` is the documented field (the example shows `status`/`runStatus`).

### Query Mster Device

**`POST /openapi/v2/device/get_master_control_device`**

Used in Commercial & Industrial systems, this function queries the top-level master control device in the system based on the requested device, typically the EMS. It is used for export control, import control, and querying the target devices for commands issued by the EMS system.

#### Request Fields

```text
 | Body Parameters application/json application/json  | Required
 | deviceSn  | String  | Required  | {
    "deviceSn": "10H1******020C",
    "deviceType": 1,
    "businessType": 4
}
 | Device SN
 | deviceType  | Interger  | Required
 | Device type, for detailed information on the specific values, please refer to "Appendix 3: Description and definition of the deviceType field"
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial
```

#### Returned Fields

```text
 | Parameter Name  | Type  | Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
            "code": 10000,
            "message": "success",
            "result":{
                "deviceSn":"10H1******020C",
                "controlDeviceType":100,
                "controlDeviceSn":"XM*****1M"
            }
}
 | message  | String  | Error Message
 | result  | List  | Specific data
 | Result description
 | deviceSn  | String  | Requesting device SN
 | controlDeviceSn  | String  | System main control device SN
 | controlDeviceType  | Interger  | System main control device type, for detailed information on the specific values, please refer to "Appendix 3: Description and definition of the deviceType field"
```

### Obtain System Information of the EMS system

**`POST /openapi/v2/device/ems_system/attribute_info`**

Used in the C&I system, to obtain the top EMS SN and basic information of the system.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | registerNo  | String  | Required  | {
    "plantId": 2345********5987,
    "deviceType": 100,
    "businessType": 4,
    "registerNo": "XMG11406EP"
}
 | Request EMS registration number, which is the same as the EMS SN.
 | plantId  | Interger  | Required
 | Plant id
 | deviceType  | Interger  | Optional
 | Device type, the value in this interface is fixed at 100. for detailed information on the specific values, please refer to "Appendix 3: Description and definition of the deviceType field"
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial
```

#### Returned Fields

```text
 | Parameter Name  | Type  | Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "result": {
        "total": 1,
        "pages": 1,
        "current": 1,
        "records": [
            {
                "stationId": 2345********5987,
                "registerNo": "XMG****EP",
                "deviceModel": 1,
                "deviceName": "hhhhhhh",
                "sysACRatedPower": 0,
                "sysBatteryCapacity": 0,
                "sysPVRatedPower": 0,
                "sysPVRatedPowerSolaXPart": 0,
                "sysPVRatedPowerThirdPart": 0
            }
        ],
        "size": 1
    },
    "traceId": "d987******234"
}
 | message  | String  | Error Message
 | traceId  | String  | The traceId recorded by the platform for this request, used for tracing and troubleshooting issues.
 | result  | List  | Specific data
 | Result description
 | total  | String  | Total number
 | pages  | Integer  | Total pages
 | current  | Integer  | Current page (Query page number)
 | size  | Integer  | Items per page
 | records  | Data  | EMS system list specific data
 | records description
 | registerNo  | String  | EMS registration number, which is the same as the EMS SN.
 | deviceModel  | Interger  | Device model, for detailed information on the specific values, please refer to "Appendix 4: Description and definition of the deviceModel field"
 | deviceName  | String  | EMS name
 | stationId  | Interger  | Plant id
 | sysACRatedPower  | BigDecimal  | EMS system AC side total rated power, unit: kW
 | sysBatteryCapacity  | BigDecimal  | EMS system total storage capacity, unit: kWh
 | sysPVRatedPower  | BigDecimal  | EMS system total PV installation power (SolaX PV + third-party PV), unit: kW
 | sysPVRatedPowerSolaXPart  | BigDecimal  | SolaX PV part of the total PV installed capacity of the system, unit: kW
 | sysPVRatedPowerThirdPart  | BigDecimal  | Third-party PV part of the total PV installed capacity of the system, unit: kW
```

### Query Heat Pump Information

**`POST /openapi/v2/device/heat_pump/info/basic`**

Query basic information about heat pump equipment.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | registerNo  | String  | Required  | "businessType": 1,
    "snList": [
        "B0********0B"
    ]
}
 | Device SN, minimum 1, maximum 10, multiple device SNs separated by commas.
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial. Currently only supports Residential.
```

#### Returned Fields

```text
 | Parameter Name  | Type  | Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "result": [
        {
            "registerNo": "RE****01",
            "deviceSn": "B0********0B",
            "deviceModel": "1",
            "plantId": "339663******618",
            "internalVersion": "v2.1.0",
            "externalVersion": "v1.3.2",
            "moduleVersion": "v3.0.1",
            "onlineStatus": 1
            "ratedOutputPower": "6800.0",
            "wiredCtrlVersion": "000.00",
            "wiredCtrl": 1
        }
    ],
    "message": "success",
    "traceId": "9029e422-b537-435b-8f9c-f888a05bc0f1.1779106008122"
}
 | message  | String  | Error Message
 | traceId  | String  | Error Message
 | result  | List  | Specific data
 | Result description
 | registerNo  | String  | Heat pump registration number
 | deviceSn  | String  | Device SN
 | deviceModel  | Interger  | Device model, for detailed information on the specific values, please refer to "Appendix 4: Description and definition of the deviceModel field"
 | plantId  | String  | Plant id
 | onlineStatus  | Interger  | Online status,0:Offline, 1:Online
 | ratedOutputPower  | BigDecimal  | Rated output power, unit: W
 | internalVersion  | String  | Internal version
 | externalVersion  | String  | External version
 | moduleVersion  | String  | Module version
 | wiredCtrlVersion  | String  | Wired controller version
 | wiredCtrl  | String  | Wired control method
0: single zone water temp
1: single zone room temp
2: dual zone water temp
3: dual zone water temp + room temp
4: triple zone water temp
```

## EMS System Functions

For the EMS system, a dedicated set of APIs is provided for data retrieval and control.

Note: The EMS systems supported within this functional domain include all systems except for the EMS1000 + X3-AELIO combination; specifically, the AELIO-B100 and AELIO-B200 models are not supported. For the EMS1000 + X3-AELIO system, please use the "Inverter work mode control" "Inverter remote control mode" and "Export Limit and Import Limit Setting" functions for control.

### Query EMS System Real-time Data

**`POST /openapi/v2/device/ems_system/summary_data`**

Query real-time data from the EMS system, using the top-level EMS as the query target.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | registerNoList  | List  | Required  | {
    "deviceType": 100,
    "businessType": 4,
    "registerNoList": [
        "XMG****EP"
    ]
}
 | List of EMS device registration numbers, minimum 1, maximum 10, multiple device SNs separated by commas.
 | deviceType  | Integer  | Required
 | Device Type, for detailed information on the specific values, please refer to "Appendix 3: Description and definition of the deviceType field"
 | pageNo  | Interger  | Optional
 | Query page number; defaults to 1 if left blank.
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial. The value in this interface should be 4.
```

#### Returned Fields

```text
 | Parameter Name  | Type  | Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "result": [
        {
            "registerNo": "XMG****EP",
            "utcTime": "2026-02-27T12:36:30Z",
            "plantLocalTime": "2026-02-27T20:36:30+08:00",
            "sysDailyPVYield": 0.0,
            "sysTotalPVYield": 0.0,
            "sysDailyBatteryCharge": 0.7,
            "sysTotalBatteryCharge": 0.7,
            "sysDailyBatteryDischarge": 0.1,
            "sysTotalBatteryDischarge": 0.1,
            "sysDailyLoadConsumption": -0.6,
            "sysTotalLoadConsumption": -0.6,
            "sysDailyImportEnergy": 0.0,
            "sysTotalImportEnergy": 0.0,
            "sysDailyExportEnergy": 0.0,
            "sysTotalExportEnergy": 0.0,
            "sysGridPower": 0.0,
            "sysPVPower": 0.0,
            "sysLoadPower": 0.0,
            "sysBatteryPower": 0.0,
            "sysBatterySOC": 74.0,
            "sysBatterySOH": 99.6,
            "sysBatteryRemainings": 633.85,
            "sysBatteryCapacity": 860.0
        }
    ],
    "traceId": "d987******234"
}
 | message  | String  | Error Message
 | traceId  | String  | The traceId recorded by the platform for this request, used for tracing and troubleshooting issues.
 | result  | Data  | Specific data
 | Result description
 | registerNo  | String  | EMS registration number, which is the same as the EMS SN.
 | utcTime  | String  | Data reporting time (UTC time), ISO8601
 | plantLocalTime  | String  | Data reporting time (Plant local time), ISO8601
 | sysDailyPVYield  | BigDecimal  | Daily system PV energy generation, unit: kWh
 | sysTotalPVYield  | BigDecimal  | Total system PV energy generation, unit: kWh
 | sysDailyBatteryCharge  | BigDecimal  | Daily battery charged energy, unit: kWh
 | sysTotalBatteryCharge  | BigDecimal  | Total battery charged energy, unit: kWh
 | sysDailyBatteryDischarge  | BigDecimal  | Daily battery discharged energy, unit: kWh
 | sysTotalBatteryDischarge  | BigDecimal  | Total battery discharged energy, unit: kWh
 | sysDailyLoadConsumption  | BigDecimal  | Daily loads energy consumption, unit: kWh
 | sysTotalLoadConsumption  | BigDecimal  | Total loads energy consumption, unit: kWh
 | sysDailyImportEnergy  | BigDecimal  | Daily energy import from the grid, unit: kWh
 | sysTotalImportEnergy  | BigDecimal  | Total energy import from the grid, unit: kWh
 | sysDailyExportEnergy  | BigDecimal  | Daily energy export to the grid, unit: kWh
 | sysTotalExportEnergy  | BigDecimal  | Total energy export to the grid, unit: kWh
 | sysGridPower  | BigDecimal  | System grid power, unit: kW
 | sysPVPower  | BigDecimal  | System PV power, unit: kW
 | sysLoadPower  | BigDecimal  | System loads power, unit: kW
 | sysBatteryPower  | BigDecimal  | System battery power, unit: kW
 | sysBatterySOC  | BigDecimal  | System battery SOC, unit: %
 | sysBatterySOH  | BigDecimal  | System battery SOH
 | sysBatteryRemainings  | BigDecimal  | System battery remain energy, unit: kWh
 | sysBatteryCapacity  | BigDecimal  | System rated battery capacity, unit: kWh
```

### EMS System Incremental Data Query

**`POST /openapi/v2/device/ems_system/increase_data`**

Query the incremental data of the EMS system, using the top-level EMS as the query target.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | registerNoList  | List  | Required  | {
    "deviceType": 100,
    "businessType": 4,
    "registerNoList": [
        "XMG****EP"
    ],
    "startTime": 1774994400,
    "endTime": 1774998000,
    "timeInterval": 30
}
 | List of EMS device registration numbers, minimum 1, maximum 10, multiple device SNs separated by commas.
 | deviceType  | Integer  | Required
 | Device type, the value in this interface should be 100, for detailed information on the specific values, please refer to "Appendix 3: Description and definition of the deviceType field"
 | startTime  | Integer  | Required
 | Query start timestamp, 11-digit long integer, precision: second
 | endTime  | Integer  | Required
 | Query end timestamp, 11-digit long integer, precision: second
 | timeInterval  | Integer  | Required
 | Time interval, unit: minute, optional 15, 30, 60, the returned results are cut by the [startTime,endTime] time period according to this parameter.
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial. The value in this interface should be 4.
```

#### Returned Fields

```text
 | Parameter Name  | Type  | Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "result": {
        "XMG****EP": [
            {
                "timeSliceStart": 1774994400,
                "pvYieldIncrease": 0.0,
                "batteryChargeYieldIncrease": 0.0,
                "batteryDischargeYieldIncrease": 0.0,
                "loadConsumptionYieldIncrease": 1.0,
                "gridImportYieldIncrease": 1.0,
                "gridExportYieldIncrease": 0.0,
                "gridPower": -1.91,
                "pvPower": 0.0,
                "loadPower": 1.91,
                "batteryPower": 0.0,
                "soc": 97,
                "soh": 99.73
            },
            {
                "timeSliceStart": 1774999800,
                "pvYieldIncrease": 8.3,
                "batteryChargeYieldIncrease": 0.0,
                "batteryDischargeYieldIncrease": 13.39,
                "loadConsumptionYieldIncrease": 0.69,
                "gridImportYieldIncrease": 0.0,
                "gridExportYieldIncrease": 21.0,
                "gridPower": 41.86,
                "pvPower": 16.52,
                "loadPower": 2.6,
                "batteryPower": -27.94,
                "soc": 95,
                "soh": 99.73
            }
        ]
    },
    "traceId": "d987******234"
}
 | message  | String  | Error message
 | traceId  | String  | The traceId recorded by the platform for this request, used to troubleshoot issues
 | result  | Data  | Specific data
 | result description
 | registerNo  | String  | EMS registration number, same as the EMS SN
 | Incremental data description for each registerNo
 | timeSliceStart  | BigDecimal  | The start time of the statistical interval, represented as a 10-digit timestamp in seconds. The end time of the interval can be calculated by adding the `timeInterval` value to this field.
 | pvYieldIncrease  | BigDecimal  | PV yield increment
 | batteryChargeYieldIncrease  | BigDecimal  | Battery charge increment
 | batteryDischargeYieldIncrease  | BigDecimal  | Battery discharge increment
 | loadConsumptionYieldIncrease  | BigDecimal  | Load consumption increment
 | gridImportYieldIncrease  | BigDecimal  | Grid import yield increment
 | gridExportYieldIncrease  | BigDecimal  | Grid export yield increment
 | gridPower  | BigDecimal  | Grid power, average value within the time period
 | pvPower  | BigDecimal  | PV power, average value within the time period
 | loadPower  | BigDecimal  | Load power, average value within the time period
 | batteryPower  | BigDecimal  | Battery power, average value within the time period
 | soc  | Integer  | ems system level SOC, latest one within the time period
 | soh  | BigDecimal  | ems system level SOH, latest one within the time period
```

### EMS System Self Use Mode Control

**`POST /openapi/v2/device/ems_system/control/work_mode/self_use`**

Set the EMS system's work mode to Self Use Mode.

Note: This feature currently requires a special firmware for EMS 1000 or EMS 1000 PRO.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | deviceType  | Interger  | Required  | {
    "deviceType": 100,
    "businessType": 4,
    "paramList": [
        {
            "registerNo": "XM******01",
            "dischargeCutOffSoc": 10,
            "chargeCutOffSoc": 100
        }
    ]
}
 | Device type, for detailed information on the specific values, please refer to "Appendix 3: Description and definition of the deviceType field". Fixed value 100 in this interface.
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial. Fixed value 4 in this interface.
 | paramList  | List  | Required
 | Controlled Objects and control content
 | ParamList description
 | registerNo  | String  | Required
 | Top EMS registration number in EMS system
 | dischargeCutOffSoc  | Interger  | Required
 | Discharge cutoff SOC, unit %, range [5,100].
 | chargeCutOffSoc  | Interger  | Required
 | Charge cutoff SOC, unit %, range [10,100].
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "result": {
        "XM******01": {
            "status": 5
        }
    },
    "traceId": "d987******234",
    "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | traceId  | String  | The traceId recorded by the platform for this request, used for tracing and troubleshooting issues.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### EMS System Manual Mode Control

**`POST /openapi/v2/device/ems_system/control/work_mode/manual`**

Set the EMS system's work mode to Manual Control Mode, to control the system to charge or discharge at a target power level to the target SOC.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | deviceType  | Interger  | Required  | {
    "deviceType": 100,
    "businessType": 4,
    "paramList": [
        {
            "registerNo": "XM******01",
            "manualMode": 0,
            "power": 100,
            "targetSoc": 80
        }
    ]
}
 | Device type, for detailed information on the specific values, please refer to "Appendix 3: Description and definition of the deviceType field". Fixed value 100 in this interface.
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial. Fixed value 4 in this interface.
 | paramList  | List  | Required
 | Controlled Objects and control content
 | paramList description
 | registerNo  | String  | Required
 | Top EMS registration number in EMS system
 | manualMode  | Interger  | Required
 | Manual mode setting parameters:
0: Stop charging and discharging
1: Force charging
2: Force discharging
 | power  | Interger  | Optional
 | Target power, unit:kW. Required when `manualMode` is set to 1 or 2.
 | targetSoc  | Interger  | Optional
 | Target SOC (%), range: [5, 100]. Required when `manualMode` is set to 1 or 2.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "result": {
        "XM******01": {
            "status": 5
        }
    },
    "traceId": "d987******234",
    "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | traceId  | String  | The traceId recorded by the platform for this request, used for tracing and troubleshooting issues.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### EMS System Feed-in Priority Mode Control

**`POST /openapi/v2/device/ems_system/control/work_mode/feed_in`**

Set the EMS system's work mode to Feed-in Priority Mode.

Note: This feature currently requires a special firmware for EMS 1000 or EMS 1000 PRO.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | deviceType  | Interger  | Required  | {
    "deviceType": 100,
    "businessType": 4,
    "paramList": [
        {
            "registerNo": "XM******01",
            "dischargeCutOffSoc": 10,
            "chargeCutOffSoc": 100
        }
    ]
}
 | Device type, for detailed information on the specific values, please refer to "Appendix 3: Description and definition of the deviceType field". Fixed value 100 in this interface.
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial. Fixed value 4 in this interface.
 | paramList  | List  | Required
 | Controlled Objects and control content
 | ParamList description
 | registerNo  | String  | Required
 | Top EMS registration number in EMS system
 | dischargeCutOffSoc  | Interger  | Required
 | Discharge cutoff SOC, unit %, range [10,100].
 | chargeCutOffSoc  | Interger  | Required
 | Charge cutoff SOC, unit %, range [10,100].
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "result": {
        "XM******01": {
            "status": 5
        }
    },
    "traceId": "d987******234",
    "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | traceId  | String  | The traceId recorded by the platform for this request, used for tracing and troubleshooting issues.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### EMS System Back Up Mode Control

**`POST /openapi/v2/device/ems_system/control/work_mode/back_up`**

Set the EMS system's work mode to Back Up Mode.

Note: This feature currently requires a special firmware for EMS 1000 or EMS 1000 PRO.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | deviceType  | Interger  | Required  | {
    "deviceType": 100,
    "businessType": 4,
    "paramList": [
        {
            "registerNo": "XM******01",
            "dischargeCutOffSoc": 10,
            "chargeCutOffSoc": 100,
            "backupSoc": 30
        }
    ]
}
 | Device type, for detailed information on the specific values, please refer to "Appendix 3: Description and definition of the deviceType field". Fixed value 100 in this interface.
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial. Fixed value 4 in this interface.
 | paramList  | List  | Required
 | Controlled Objects and control content
 | ParamList description
 | registerNo  | String  | Required
 | Top EMS registration number in EMS system
 | dischargeCutOffSoc  | Interger  | Required
 | Discharge cutoff SOC, unit %, range [10,100].
 | chargeCutOffSoc  | Interger  | Required
 | Charge cutoff SOC, unit %, range [10,100].
 | backupSoc  | Interger  | Required
 | Back Up SOC, unit %, range [5,100].
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "result": {
        "XM******01": {
            "status": 5
        }
    },
    "traceId": "d987******234",
    "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | traceId  | String  | The traceId recorded by the platform for this request, used for tracing and troubleshooting issues.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### EMS System-Level AC Port Power Control

**`POST /openapi/v2/device/ems_system/control/power_control/ac`**

Control EMS system-level ac port power.

Note: This feature currently requires a special firmware for EMS 1000 or EMS 1000 PRO.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | deviceType  | Interger  | Required  | {
    "deviceType": 100,
    "businessType": 4,
    "paramList": [
        {
            "registerNo": "XM******01",
            "acPower": 10
        }
    ]
}
 | Device type, for detailed information on the specific values, please refer to "Appendix 3: Description and definition of the deviceType field". Fixed value 100 in this interface.
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial. Fixed value 4 in this interface.
 | paramList  | List  | Required
 | Controlled Objects and control content
 | ParamList description
 | registerNo  | String  | Required
 | Top EMS registration number in EMS system
 | acPower  | Interger  | Required
 | System AC output power;  unit: kW; a positive value indicates discharge, a negative value indicates charge, a value of 0 indicates neither charge nor discharge, with no electricity being purchased or sold via the AC port.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "result": {
        "XM******01": {
            "status": 5
        }
    },
    "traceId": "d987******234",
    "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | traceId  | String  | The traceId recorded by the platform for this request, used for tracing and troubleshooting issues.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### EMS System-Level PV and Battery Port Power Control

**`POST /openapi/v2/device/ems_system/control/power_control/pv_battery`**

Control EMS system-level PV and battery port power.

Note: This feature currently requires a special firmware for EMS 1000 or EMS 1000 PRO.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | deviceType  | Interger  | Required  | {
    "deviceType": 100,
    "businessType": 4,
    "paramList": [
        {
            "registerNo": "XM******01",
            "pvPower": 10,
            "batteryPower": 5
        }
    ]
}
 | Device type, for detailed information on the specific values, please refer to "Appendix 3: Description and definition of the deviceType field". Fixed value 100 in this interface.
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial. Fixed value 4 in this interface.
 | paramList  | List  | Required
 | Controlled Objects and control content
 | ParamList description
 | registerNo  | String  | Required
 | Top EMS registration number in EMS system
 | pvPower  | Interger  | Required
 | System PV power; unit: kW; non-negative integer.
 | batteryPower  | Interger  | Optional
 | System battery power;  unit: kW; a positive value indicates discharge, a negative value indicates charge, a value of 0 indicates neither charge nor discharge.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "result": {
        "XM******01": {
            "status": 5
        }
    },
    "traceId": "d987******234",
    "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | traceId  | String  | The traceId recorded by the platform for this request, used for tracing and troubleshooting issues.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### EMS System-Level Export Limit

**`POST /openapi/v2/device/ems_system/control/limit_control/export`**

Set the export limit of the EMS system.

Note: This feature currently requires a special firmware for EMS 1000 or EMS 1000 PRO.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | deviceType  | Interger  | Required  | {
    "deviceType": 100,
    "businessType": 4,
    "paramList": [
        {
            "registerNo": "XM******01",
            "isEnable": 1,
            "limitValue": 1,
            "controlMethod": null,
            "exportOffsetEnable": 1,
            "exportOffsetValue": 1,
            "exportOffsetMode": 1
        }
    ]
}
 | Device type, for detailed information on the specific values, please refer to "Appendix 3: Description and definition of the deviceType field". Fixed value 100 in this interface.
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial. Fixed value 4 in this interface.
 | paramList  | List  | Required
 | Controlled Objects and control content
 | ParamList description
 | registerNo  | String  | Required
 | Top EMS registration number in EMS system
 | isEnable  | Interger  | Required
 | Export limit function enable status
0: Disable
1: Enable
 | limitValue  | Interger  | Optional
 | Limit value; unit: kW. This field is mandatory and takes effect when `isEnable` is 1.
Note: When `exportOffsetEnable` is 1, `limitValue` must be 0; that is, the offset setting must be used in a zero-export scenario.
 | controlMode  | Interger  | Optional
 | Control mode
0: Total power mode
1: Split-phase mode
Defaults to 0 if left blank.
 | exportOffsetEnable  | Interger  | Optional
 | Export offset setting enable status
0: Disabled
1: Enabled
Effective only when limitValue is 0
 | exportOffsetValue  | Interger  | Optional
 | Offset limit value, unit: kW. This field is mandatory and takes effect when `exportOffsetEnable` is 1.
 | exportOffsetMode  | Interger  | Optional
 | Offset mode
1: Range limit
2: Target limit
This field is mandatory and takes effect when `exportOffsetEnable` is 1.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "result": {
        "XM******01": {
            "status": 5
        }
    },
    "traceId": "d987******234",
    "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | traceId  | String  | The traceId recorded by the platform for this request, used for tracing and troubleshooting issues.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### EMS System-Level Import Limit

**`POST /openapi/v2/device/ems_system/control/limit_control/import`**

Set the import limit of the EMS system.

Note: This feature currently requires a special firmware for EMS 1000 or EMS 1000 PRO.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | deviceType  | Interger  | Required  | {
    "deviceType": 100,
    "businessType": 4,
    "paramList": [
        {
            "registerNo": "XM******01",
            "isEnable": 1,
            "limitValue": 1,
            "controlMethod": 0
        }
    ]
}
 | Device type, for detailed information on the specific values, please refer to "Appendix 3: Description and definition of the deviceType field". Fixed value 100 in this interface.
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial. Fixed value 4 in this interface.
 | paramList  | List  | Required
 | Controlled Objects and control content
 | ParamList description
 | registerNo  | String  | Required
 | Top EMS registration number in EMS system
 | isEnable  | Interger  | Required
 | Import limit function enable status
0: Disable
1: Enable
 | limitValue  | Interger  | Optional
 | Limit value; unit: kW. This field is mandatory and takes effect when `isEnable` is 1.
 | controlMode  | Interger  | Optional
 | Control mode
0: Reduce battery charge power until standby
1: Reduce battery charge power and discharge battery if necessary
Defaults to 0 if left blank.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "result": {
        "XM******01": {
            "status": 5
        }
    },
    "traceId": "d987******234",
    "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | traceId  | String  | The traceId recorded by the platform for this request, used for tracing and troubleshooting issues.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

## Monitoring Management

### Query Plant Real-Time Data

**`GET /openapi/v2/plant/realtime_data`**

Query real-time data from a specific plant.

#### Request Fields

```text
 | Params Parameters  | Example
 | plantId  | Long  | Required  | /openapi/v2/plant/realtime_data
    ?plantId=339663******618
    &businessType=1
 | Plant id
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial
```

#### Returned Fields

```text
 | Parameter Name  | Type  | Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
        "code": 10000,
        "result": {
                "plantLocalTime": "2025-04-01 14:52:51",
                "plantId": "339663******618",
                "dailyYield": 1.36,
                "totalYield": 1.26,
                "dailyCharged": 0.65,
                "totalCharged": 0.80,
                "dailyDischarged": 1.76,
                "totalDischarged": 1.69,
                "dailyImported": 1.54,
                "totalImported": 1.23,
                "dailyExported": 1.00,
                "totalExported": 1.06,
                "dailyEarnings": 11.17,
                "totalEarnings": 41.44
        }
}
 | message  | String  | Error Message
 | result  | Data  | Specific data
 | Result description
 | plantId  | String  | Plant id
 | plantLocalTime  | Integer  | Data reporting time (Plant local time)
 | dailyYield  | BigDecimal  | Daily plant yield, unit: kWh
 | totalYield  | BigDecimal  | Total plant yield, unit: kWh
 | dailyCharged  | BigDecimal  | Daily plant charge, unit: kWh
 | totalCharged  | BigDecimal  | Total plant charge, unit: kWh
 | dailyDischarged  | BigDecimal  | Daily plant discharge, unit: kWh
 | totalDischarged  | BigDecimal  | Total plant discharge, unit: kWh
 | dailyImported  | BigDecimal  | Daily plant imported energy, unit: kWh
 | totalImported  | BigDecimal  | Total plant imported energy, unit: kWh
 | dailyExported  | BigDecimal  | Daily plant exported energy, unit: kWh
 | totalExported  | BigDecimal  | Total plant exported energy, Unit: following the power station's currency unit
 | dailyEarnings  | BigDecimal  | Daily plant earnings
 | totalEarnings  | BigDecimal  | Total plant earnings, Unit: following the power station's currency unit
```

### Query Alarm Information

**`GET /openapi/v2/alarm/page_alarm_info`**

Query alarm information from a specific plant.

#### Request Fields

```text
 | Params Parameters  | Example
 | plantId  | Long  | Required  | /openapi/v2/alarm/page_alarm_info
    ?plantId=339663******618
    &businessType=1
    &alarmState=1
 | Plant id
 | deviceSn  | String  | Optional
 | Device SN
 | alarmState  | Interger  | Required
 | Alarm status, 0:Closed, 1:Ongoing
 | pageNo  | Interger  | Optional
 | Query page number: defaults to 1 if left blank
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial
```

#### Returned Fields

```text
 | Parameter Name  | Type  | Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "result": {
        "total": 1,
        "pages": 1,
        "current": 1,
        "records": [
            {
                "alarmStartTime": "2025-03-31 13:58:53",
                "alarmName": "AC undervoltage",
                "errorCode": "60",
                "alarmType": "ARM SOFTWARE FAULT",
                "alarmLevel": 2,
                "alarmState": 1,
                "alarmEndTime": null,
                "registerNo": "SE******SP",
                "deviceType": 1,
                "deviceModel": "2",
                "deviceSn": "X3******01"
            }
        ],
        "size": 10,
        "plantId": "339663******618"
    }
}
 | message  | String  | Error Message
 | result  | Data  | Specific data
 | Result description
 | plantId  | String  | Plant id
 | total  | Integer  | Alarm Total number
 | pages  | Integer  | Total pages
 | current  | Integer  | Current page (Query page number)
 | size  | Integer  | Items per page
 | records  | List  | Specific alarm list
 | Alarm list records
 | alarmStartTime  | String  | Alarm time
 | alarmName  | String  | Alarm name
 | errorCode  | String  | Alarm code
 | alarmType  | String  | Alarm Type
 | alarmLevel  | Integer  | Alarm level
 | alarmState  | Integer  | Alarm status, 0:Closed, 1:Ongoing
 | alarmEndTime  | String  | Alarm recovery time
 | deviceSn  | String  | Device SN
 | registerNo  | String  | Device registration number
 | deviceType  | Integer  | Device type, for detailed information on the specific values, please refer to "Appendix 3: Description and definition of the deviceType field"
 | deviceModel  | String  | Device model, for detailed information on the specific values, please refer to "Appendix 4: Description and definition of the deviceModel field"
```

### Query Plant Statistics

**`POST /openapi/v2/plant/energy/get_stat_data`**

This API retrieves statistical data for a specific plant, supporting both annual and monthly data. Annual data provides statistics for each month of the year, while monthly data provides statistics for each day of that month.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | plantId  | Long  | Required  | {
    "plantId": "339663******618",
    "dateType": "1",
    "date": "2025",
    "businessType": "4"
}
 | Plant id
 | dateType  | Interger  | Required
 | Statistical dimensions
1: Annual statistics;
2: Monthly statistics.
 | date  | String  | Required
 | Statistical query for dates
When dateType=1, the query returns the year, e.g., 2025.
When dateType=2, the query returns the month, e.g., 2025-09.
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial
```

#### Return Fields

```text
 | Parameter Name  | Type  | Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "result": {
        "plantId": "339663******618",
        "date": "2025",
        "currencyCode": "SEK",
        "plantEnergyStatDataList": [{
                "date": "2025-05",
                "pvGeneration": 190.60,
                "inverterACOutputEnergy": 1183.10,
                "exportEnergy": 9.80,
                "importEnergy": 22.09,
                "loadConsumption": 16861.63,
                "batteryCharged": -58.40,
                "batteryDischarged": 9.90,
                "earnings": 11.11
            },
            ……,
            {
                "date": "2025-09",
                "pvGeneration": 0.00,
                "inverterACOutputEnergy": 5.40,
                "exportEnergy": 1265.97,
                "importEnergy": 2559.30,
                "loadConsumption": 1328.31,
                "batteryCharged": 7.00,
                "batteryDischarged": 2.10,
                "earnings": 85748.04
            }
        ]
    }
}
 | message  | String  | Error Message
 | result  | Data  | Specific data
 | Result description
 | plantId  | String  | Plant id
 | date  | String  | Query time
 | currencyCode  | String  | Plant currency unit
 | plantEnergyStatDataList  | List  | Specific plant statistics
 | plantEnergyStatDataList description
 | date  | String  | Statistical time period
When dateType=1, it counts each month within the year, e.g., 2025-09;
When dateType=2, it counts each day within the month, e.g., 2025-09-03.
 | pvGeneration  | BigDecimal  | PV energy generation during the statistical period, unit: kWh
 | inverterACOutputEnergy  | BigDecimal  | Inverter AC port output energy during the statistical period, unit: kWh.
 | exportEnergy  | BigDecimal  | Energy output to the grid during the statistical period, unit: kWh.
 | importEnergy  | BigDecimal  | Energy input from the grid during the statistical period, unit: kWh.
 | loadConsumption  | BigDecimal  | Loads energy consumption during the statistical period, unit: kWh.
 | batteryCharged  | BigDecimal  | Battery charging amount during the statistical period, unit: kWh.
 | batteryDischarged  | BigDecimal  | Battery discharging amount during the statistical period, unit: kWh.
 | earnings  | BigDecimal  | Revenue during the statistical period
```

> **Project note.** **Verified, used.** The example passes all values as strings, which the app copies. `pvGeneration` is stored as "produced"; `inverterACOutputEnergy` (the SolaX app's *Inverter Output*) is lower on systems with a battery.

### Query Device Real-time Data

**`GET /openapi/v2/device/realtime_data`**

Query real-time data for a specific device or multiple devices of a certain type.

Meter Data Description: If the meter SN is available, query the corresponding data through the corresponding meter data interface. If the meter SN is unavailable or the CT is in use, query the corresponding data through the corresponding inverter data interface.

#### Request Fields

```text
 | Params parameters  | Example
 | snList  | String  | Required  | /openapi/v2/device/realtime_data
    ?snList=X3******01,X3******02
    &deviceType=1
    &businessType=4
 | Device SN, minimum 1, maximum 10, multiple device SNs separated by commas.
 | deviceType  | Interger  | Required
 | Device Type, for detailed information on the specific values, please refer to "Appendix 3: Description and definition of the deviceType field"
 | requestSnType  | Interger  | Optional
 | Device Type, for detailed information on the specific values, please refer to "Appendix 3: Description and definition of the deviceType field"
This field only applies when "deviceType": "2". Its primary use case is querying battery data without a serial number (SN).
When deviceType=2 (battery) and requestSnType=1 (inverter) in the request parameters, it indicates a query for all battery information under the requested inverter SN;
When deviceType=2 (battery) and requestSnType=2 (battery) in the request parameters, it indicates a query for battery information with the requested battery SN;
When deviceType=2 (battery) in the request parameters and requestSnType is not specified, it is equivalent to requestSnType=2 (battery), indicating a query for battery information with the requested battery SN.
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial
```

#### Returned Fields

```text
 | Parameter Name  | Type  | Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
        "code": 10000,
        "result": [{
                "deviceStatus": null,
                "dataTime": null,
                "plantLocalTime": "2025-05-22 15:13:10",
                "deviceSn": "X3******01",
                "registerNo": "SE******SP",
                "acCurrent1": 0.0,
                "acVoltage1": 224.1,
                "acCurrent2": 0.0,
                "acCurrent3": 0.0,
                "acVoltage2": 226.5,
                "acVoltage3": 227.9,
                "acPower1": 0.0,
                "acPower2": 0.0,
                "acPower3": 0.0,
                "acFrequency1": 50.0,
                "acFrequency2": 50.0,
                "acFrequency3": 50.0,
                "gridFrequency": 0.0,
                "totalPowerFactor": 1.0,
                "inverterTemperature": 40.4,
                "dailyACOutput": 160.9,
                "totalACOutput": 19907.5,
                "totalACInput": 6.0,
                "dailyACInput": 10.0,
                "dailyYield": 157.4,
                "totalYield": 20465.3,
                "mpptMap": {
                        "mppt2Voltage": 0.0,
                        "mppt3Voltage": 0.0,
                        "mppt1Voltage": 0.0,
                        "mppt4Voltage": 0.0,
                        "mppt5Voltage": 0.0,
                        "mppt10Power": 0.0,
                        "mppt6Voltage": 0.0,
                        "mppt7Voltage": 0.0,
                        "mppt3Power": 0.0,
                        "mppt6Power": 0.0,
                        "mppt11Current": 0.0,
                        "mppt10Current": 0.0,
                        "mppt12Current": 0.0,
                        "mppt9Power": 0.0,
                        "mppt2Power": 0.0,
                        "mppt8Power": 0.0,
                        "mppt11Voltage": 0.0,
                        "mppt12Voltage": 0.0,
                        "mppt12Power": 0.0,
                        "mppt10Voltage": 0.0,
                        "mppt8Voltage": 0.0,
                        "mppt9Voltage": 0.0,
                        "mppt5Power": 0.0,
                        "mppt5Current": 0.0,
                        "mppt6Current": 0.0,
                        "mppt7Current": 0.0,
                        "mppt8Current": 0.0,
                        "mppt7Power": 0.0,
                        "mppt3Current": 0.0,
                        "mppt2Current": 0.0,
                        "mppt4Current": 0.0,
                        "mppt11Power": 0.0,
                        "mppt4Power": 0.0,
                        "mppt9Current": 0.0,
                        "mppt1Current": 0.0,
                        "mppt1Power": 0.0
                },
                "pvMap": {
                        "pv3Current": 0.1,
                        "pv1Voltage": 782.8,
                        "pv2Voltage": 783.2,
                        "pv3Voltage": 783.0,
                        "pv4Voltage": 779.0,
                        "pv1Current": 0.0,
                        "pv4Current": 0.0,
                        "pv5Current": 0.0,
                        "pv1Power": 0.0,
                        "pv2Current": 0.0,
                        "pv2Power": 0.0,
                        "pv4Power": 0.0,
                        "pv6Power": 0.0,
                        "pv6Voltage": 0.0,
                        "pv5Power": 0.0,
                        "pv6Current": 0.0,
                        "pv5Voltage": 779.6,
                        "pv3Power": 0.101
                },
                "gridPower": 0,
                "todayImportEnergy": 0.07,
                "totalImportEnergy": 23.28,
                "todayExportEnergy": 0.00,
                "totalExportEnergy": 64.98,
                "gridPowerM2": 0,
                "todayImportEnergyM2": 0.08,
                "totalImportEnergyM2": 1.39,
                "todayExportEnergyM2": 0.00,
                "totalExportEnergyM2": 0.75,
                "EPSL1Voltage": 0.0,
                "EPSL1Current": 0.0,
                "EPSL1ActivePower": 0.0,
                "EPSL2Voltage": 0.0,
                "EPSL2Current": 0.0,
                "EPSL2ActivePower": 0.0,
                "EPSL3Voltage": 0.0,
                "EPSL3Current": 0.0,
                "EPSL3ActivePower": 0.0,
                "EPSL1ApparentPower": 0.0,
                "EPSL2ApparentPower": 0.0,
                "EPSL3ApparentPower": 0.0,
                "l2l3Voltage": null,
                "l1l2Voltage": null,
                "l1l3Voltage": null,
                "totalReactivePower": 0,
                "totalActivePower": 0,
                "MPPTTotalInputPower": null
        }]
}
 | message  | String  | Error Message
 | result  | Data  | Specific data
 | result-Inverter
 | deviceSn  | String  | Device SN
 | dataTime  | String  | Data reporting time (UTC time)
 | plantLocalTime  | String  | Data reporting time (Plant local time)
 | registerNo  | String  | Device registration number
 | deviceStatus  | Integer  | Device status, for detailed information on the specific values, please refer to "Appendix 6: Description and definition of the deviceStatus field"
 | acVoltage1~acVoltage3  | Double  | AC voltage 1~3, unit: V
 | acCurrent1~acCurrent3  | Double  | AC current 1~3, unit: A
 | acFrequency1~acFrequency3  | Double  | AC frequency 1~3, unit: Hz
 | acPower1~acPower3  | Double  | AC power 1~3, unit: W(businessType=1), kW(businessType=4)
 | gridFrequency  | Double  | Grid frequency, unit: Hz
 | totalActivePower  | Double  | Total active power, unit: W(businessType=1), kW(businessType=4), a positive value indicates discharge, a negative value indicates charge
 | totalReactivePower  | Double  | Total reactive power, unit: Var(businessType=1), kVar(businessType=4)
 | totalPowerFactor  | Double  | Total power factor
 | inverterTemperature  | Double  | Inverter temperature, unit: ℃
 | dailyYield  | Double  | Daily PV yield, unit: kWh
 | totalYield  | Double  | Total PV yield, unit: kWh
 | dailyACOutput  | Double  | Daily AC output energy, unit: kWh
 | totalACOutput  | Double  | Total AC output energy, unit: kWh
 | dailyACInput  | Double  | Daily AC input energy, unit: kWh
 | totalACInput  | Double  | Total AC input energy, unit: kWh
 | MPPTTotalInputPower  | Double  | MPPT input total power, unit: W(businessType=1), kW(businessType=4)
 | mpptMap  | Map  | MPPT data，Contains data content:
mppt1Voltage~mpptnVoltage: MPPT1~MPPTn voltage, unit: V
mppt1Current~mpptnCurrent: MPPT1~MPPTn current, unit: A
mppt1Power~mpptnPower: MPPT1~MPPTn power, unit: W(businessType=1), kW(businessType=4)
 | pvMap  | Double  | MPPT data，Contains data content:
pv1Voltage~pvnVoltage: PV1~PVn voltage, unit: V
pv1Current~pvnCurrent: PV1~PVn current, unit: A
pv1Power~pvnPower: PV1~PVn power, unit: W(businessType=1), kW(businessType=4)
 | gridPower  | Double  | Meter 1 grid port power, a positive value indicates export, a negative value indicates import, unit: W(businessType=1), kW(businessType=4)
 | todayImportEnergy  | Double  | Meter 1 daily imported active energy，unit kWh
 | totalImportEnergy  | Double  | Meter 1 total imported active energy，unit kWh
 | todayExportEnergy  | Double  | Meter 1 daily exported active energy，unit kWh
 | totalExportEnergy  | Double  | Meter 1 total exported active energy，unit kWh
 | gridPowerM2  | Double  | Meter 2 grid port power, a positive value indicates export, a negative value indicates import，unit: W(businessType=1), kW(businessType=4)
 | todayImportEnergyM2  | Double  | Meter 2 total imported active energy，unit kWh
 | totalImportEnergyM2  | Double  | Meter 2 total imported active energy，unit kWh
 | todayExportEnergyM2  | Double  | Meter 2 daily exported active energy，unit kWh
 | totalExportEnergyM2  | Double  | Meter 2 total exported active energy，unit kWh
 | epsl1ApparentPower~epsl3ApparentPower  | Double  | EPS L1~L3 apparent power，unit: W(businessType=1), kW(businessType=4)
 | epsl1ActivePower~epsl3ActivePower  | Double  | EPS L1~L3 active power，unit: W(businessType=1), kW(businessType=4)
 | epsl1Voltage~epsl3Voltage  | Double  | EPS L1~L3 voltage，unit: V
 | epsl1Current~epsl3Current  | Double  | EPS L1~L3 current，unit: A
 | l1l2Voltage/l2l3Voltage/l1l3Voltage  | Double  | L1-L2/L2-L3/L1-L3 voltage，unit: V
 | result-Battery data
 | deviceSn  | String  | Device SN
 | dataTime  | String  | Data reporting time (UTC time)
 | plantLocalTime  | String  | Data reporting time (Plant local time)
 | registerNo  | String  | Device registration number
 | deviceStatus  | Interger  | Device status, for detailed information on the specific values, please refer to "Appendix 6: Description and definition of the deviceStatus field"
 | batterySOC  | Double  | BatterySOC, unit: %
 | batteryRemainings  | Double  | Battery remain energy, unit: kWh
 | batterySOH  | Double  | BatterySOH
 | chargeDischargePower  | Double  | Battery charging or discharging power, unit: W, a positive value indicates charge, a negative value indicates discharge
 | batteryVoltage  | Double  | Battery voltage, unit: V
 | batteryCurrent  | Double  | Battery current, unit: A
 | batteryTemperature  | Double  | Battery temperature, unit: ℃
 | batteryCycleTimes  | Integer  | Battery cycle times
 | totalDevicCharge  | Double  | Total charge, unit: kWh
 | totalDeviceDischarge  | Double  | Total discharge, unit: kWh
 | result-Meter data
 | deviceSn  | String  | Device SN
 | dataTime  | String  | Data reporting time (UTC time)
 | plantLocalTime  | String  | Data reporting time (Plant local time)
 | registerNo  | String  | Device registration number
 | importEnergy  | Double  | Total forward active energy, unit: kWh
 | exportEnergy  | Double  | Total reverse active energy, unit: kWh
 | totalActivePower  | Double  | Total active power, unit:  W(businessType=1), kW(businessType=4), a positive value indicates export to grid, a negative value indicates import from grid.
 | totalReactivePower  | Double  | Total reactive power, unit:  Var(businessType=1), kVar(businessType=4)
 | totalApparentPower  | Double  | Total apparent power, unit:  W(businessType=1), kVA(businessType=4)
 | powerFactor  | Double  | Power factor
 | gridFrequency  | Double  | Grid frequency, unit: Hz
 | l1ActivePower~l3ActivePower  | Double  | L1~L3 active power, unit:  W(businessType=1), kW(businessType=4)
 | l1ReactivePower~l3ReactivePower  | Double  | L1~L3 Reactive power, unit:  Var(businessType=1), kVar(businessType=4)
 | l1Voltage~l3Voltage  | Double  | L1~L3 voltage, unit: V
 | l1Current~l3Current  | Double  | L1~L3 current, unit: A
 | result-EV Charger data
 | deviceSn  | String  | Device SN
 | dataTime  | String  | Data reporting time (UTC time)
 | plantLocalTime  | String  | Data reporting time (Plant local time)
 | registerNo  | String  | Device registration number
 | deviceStatus  | Interger  | Device status, for detailed information on the specific values, please refer to "Appendix 6: Description and definition of the deviceStatus field"
 | deviceWorkingMode  | String  | Work mode, for detailed information on the specific values, please refer to "Appendix 7: Description and definition of the deviceWorkingMode field"
 | singlePhaseCurrent  | Double  | Single phase current, unit: A
 | l1Current~l3Current  | Double  | L1~L3 current, unit: A
 | chargingPower  | Double  | Charging power, unit:  W(businessType=1), kW(businessType=4)
 | rfidnumber  | Double  | RFID No
 | chargingEnergyThisSession  | Double  | Charge energy this session, unit: kWh
 | totalChargeEnergy  | Double  | Total charge energy, unit: kWh
 | chargingTimeThisSession  | Double  | Charging time this session, unit: s
```

> **Project note.** **Verified, used** with `deviceType=2` + `requestSnType=1` for batteries and `deviceType=1` for the inverter. `deviceStatus` of the inverter is the only mode indicator (Appendix 6: 131-135 TOU modes, 150 Self Use, 102 Normal); there is no separate work-mode field.

### Query Inverter Real-time Data with Alarm Data

**`GET /openapi/v2/device/data/realtime`**

Query real-time data for a specific device or multiple devices of a certain type, while also returning information on the five most recent alarms.

#### Request Fields

```text
 | Params parameters  | Example
 | snList  | String  | Required  | {
    "snList": [
        "X3******01"
    ],
    "businessType": 1,
    "deviceType": 1
}
 | Device SN, minimum 1, maximum 10, multiple device SNs separated by commas.
 | deviceType  | Interger  | Required
 | Device Type, fixed value 1 in this interface.
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial, fixed value 1 in this interface.
```

#### Returned Fields

```text
 | Parameter Name  | Type  | Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
        "code": 10000,
        "result": [{
                "deviceStatus": null,
                "dataTime": null,
                "plantLocalTime": "2025-05-22 15:13:10",
                "deviceSn": "X3******01",
                "registerNo": "SE******SP",
                "acCurrent1": 0.0,
                "acVoltage1": 224.1,
                "acCurrent2": 0.0,
                "acCurrent3": 0.0,
                "acVoltage2": 226.5,
                "acVoltage3": 227.9,
                "acPower1": 0.0,
                "acPower2": 0.0,
                "acPower3": 0.0,
                "acFrequency1": 50.0,
                "acFrequency2": 50.0,
                "acFrequency3": 50.0,
                "gridFrequency": 0.0,
                "totalPowerFactor": 1.0,
                "inverterTemperature": 40.4,
                "dailyACOutput": 160.9,
                "totalACOutput": 19907.5,
                "dailyYield": 157.4,
                "totalYield": 20465.3,
                "mpptMap": {
                        "mppt2Voltage": 0.0,
                        "mppt3Voltage": 0.0,
                        "mppt1Voltage": 0.0,
                        "mppt4Voltage": 0.0,
                        "mppt5Voltage": 0.0,
                        "mppt10Power": 0.0,
                        "mppt6Voltage": 0.0,
                        "mppt7Voltage": 0.0,
                        "mppt3Power": 0.0,
                        "mppt6Power": 0.0,
                        "mppt11Current": 0.0,
                        "mppt10Current": 0.0,
                        "mppt12Current": 0.0,
                        "mppt9Power": 0.0,
                        "mppt2Power": 0.0,
                        "mppt8Power": 0.0,
                        "mppt11Voltage": 0.0,
                        "mppt12Voltage": 0.0,
                        "mppt12Power": 0.0,
                        "mppt10Voltage": 0.0,
                        "mppt8Voltage": 0.0,
                        "mppt9Voltage": 0.0,
                        "mppt5Power": 0.0,
                        "mppt5Current": 0.0,
                        "mppt6Current": 0.0,
                        "mppt7Current": 0.0,
                        "mppt8Current": 0.0,
                        "mppt7Power": 0.0,
                        "mppt3Current": 0.0,
                        "mppt2Current": 0.0,
                        "mppt4Current": 0.0,
                        "mppt11Power": 0.0,
                        "mppt4Power": 0.0,
                        "mppt9Current": 0.0,
                        "mppt1Current": 0.0,
                        "mppt1Power": 0.0
                },
                "pvMap": {
                        "pv3Current": 0.1,
                        "pv1Voltage": 782.8,
                        "pv2Voltage": 783.2,
                        "pv3Voltage": 783.0,
                        "pv4Voltage": 779.0,
                        "pv1Current": 0.0,
                        "pv4Current": 0.0,
                        "pv5Current": 0.0,
                        "pv1Power": 0.0,
                        "pv2Current": 0.0,
                        "pv2Power": 0.0,
                        "pv4Power": 0.0,
                        "pv6Power": 0.0,
                        "pv6Voltage": 0.0,
                        "pv5Power": 0.0,
                        "pv6Current": 0.0,
                        "pv5Voltage": 779.6,
                        "pv3Power": 0.101
                },
                "gridPower": 0,
                "todayImportEnergy": 0.07,
                "totalImportEnergy": 23.28,
                "todayExportEnergy": 0.00,
                "totalExportEnergy": 64.98,
                "gridPowerM2": 0,
                "todayImportEnergyM2": 0.08,
                "totalImportEnergyM2": 1.39,
                "todayExportEnergyM2": 0.00,
                "totalExportEnergyM2": 0.75,
                "EPSL1Voltage": 0.0,
                "EPSL1Current": 0.0,
                "EPSL1ActivePower": 0.0,
                "EPSL2Voltage": 0.0,
                "EPSL2Current": 0.0,
                "EPSL2ActivePower": 0.0,
                "EPSL3Voltage": 0.0,
                "EPSL3Current": 0.0,
                "EPSL3ActivePower": 0.0,
                "EPSL1ApparentPower": 0.0,
                "EPSL2ApparentPower": 0.0,
                "EPSL3ApparentPower": 0.0,
                "l2l3Voltage": null,
                "l1l2Voltage": null,
                "l1l3Voltage": null,
                "totalReactivePower": 0,
                "totalActivePower": 0,
                "MPPTTotalInputPower": null,
                "deviceModel": "104",
                "alarmList": [
                {
                    "alarmStartTime": "2026-07-22 14:30:18",
                    "errorCode": "147",
                    "alarmName": "START_GRID_OFP",
                    "alarmType": "Grid Fault",
                    "alarmLevel": 2,
                    "alarmState": 1,
                    "alarmCause": "/",
                    "handleSuggestion": "/"
                },
                {
                    "alarmStartTime": "2026-07-22 14:30:18",
                    "errorCode": "152",
                    "alarmName": "FAN05_FAULT",
                    "alarmType": "Inverter Fault",
                    "alarmLevel": 2,
                    "alarmState": 1,
                    "alarmCause": "/",
                    "handleSuggestion": "/"
                }
            ]
        }]
}
 | message  | String  | Error Message
 | traceId  | Data  | The traceId recorded by the platform for this request, used for tracing and troubleshooting issues.
 | result  | Data  | Specific data
 | result-Inverter
 | deviceSn  | String  | Device SN
 | dataTime  | String  | Data reporting time (UTC time)
 | plantLocalTime  | String  | Data reporting time (Plant local time)
 | registerNo  | String  | Device registration number
 | deviceStatus  | Integer  | Device status, for detailed information on the specific values, please refer to "Appendix 6: Description and definition of the deviceStatus field"
 | acVoltage1~acVoltage3  | Double  | AC voltage 1~3, unit: V
 | acCurrent1~acCurrent3  | Double  | AC current 1~3, unit: A
 | acFrequency1~acFrequency3  | Double  | AC frequency 1~3, unit: Hz
 | acPower1~acPower3  | Double  | AC power 1~3, unit: W(businessType=1), kW(businessType=4)
 | gridFrequency  | Double  | Grid frequency, unit: Hz
 | totalActivePower  | Double  | Total active power, unit: W(businessType=1), kW(businessType=4), a positive value indicates discharge, a negative value indicates charge
 | totalReactivePower  | Double  | Total reactive power, unit: Var(businessType=1), kVar(businessType=4)
 | totalPowerFactor  | Double  | Total power factor
 | inverterTemperature  | Double  | Inverter temperature, unit: ℃
 | dailyYield  | Double  | Daily PV yield, unit: kWh
 | totalYield  | Double  | Total PV yield, unit: kWh
 | dailyACOutput  | Double  | Daily AC output, unit: kWh
 | totalACOutput  | Double  | Total AC output, unit: kWh
 | MPPTTotalInputPower  | Double  | MPPT input total power, unit: W(businessType=1), kW(businessType=4)
 | mpptMap  | Map  | MPPT data，Contains data content:
mppt1Voltage~mpptnVoltage: MPPT1~MPPTn voltage, unit: V
mppt1Current~mpptnCurrent: MPPT1~MPPTn current, unit: A
mppt1Power~mpptnPower: MPPT1~MPPTn power, unit: W(businessType=1), kW(businessType=4)
 | pvMap  | Double  | MPPT data，Contains data content:
pv1Voltage~pvnVoltage: PV1~PVn voltage, unit: V
pv1Current~pvnCurrent: PV1~PVn current, unit: A
pv1Power~pvnPower: PV1~PVn power, unit: W(businessType=1), kW(businessType=4)
 | gridPower  | Double  | Meter 1 grid port power, a positive value indicates export, a negative value indicates import, unit: W(businessType=1), kW(businessType=4)
 | todayImportEnergy  | Double  | Meter 1 daily imported active energy，unit kWh
 | totalImportEnergy  | Double  | Meter 1 total imported active energy，unit kWh
 | todayExportEnergy  | Double  | Meter 1 daily exported active energy，unit kWh
 | totalExportEnergy  | Double  | Meter 1 total exported active energy，unit kWh
 | gridPowerM2  | Double  | Meter 2 grid port power, a positive value indicates export, a negative value indicates import，unit: W(businessType=1), kW(businessType=4)
 | todayImportEnergyM2  | Double  | Meter 2 total imported active energy，unit kWh
 | totalImportEnergyM2  | Double  | Meter 2 total imported active energy，unit kWh
 | todayExportEnergyM2  | Double  | Meter 2 daily exported active energy，unit kWh
 | totalExportEnergyM2  | Double  | Meter 2 total exported active energy，unit kWh
 | epsl1ApparentPower~epsl3ApparentPower  | Double  | EPS L1~L3 apparent power，unit: W(businessType=1), kW(businessType=4)
 | epsl1ActivePower~epsl3ActivePower  | Double  | EPS L1~L3 active power，unit: W(businessType=1), kW(businessType=4)
 | epsl1Voltage~epsl3Voltage  | Double  | EPS L1~L3 voltage，unit: V
 | epsl1Current~epsl3Current  | Double  | EPS L1~L3 current，unit: A
 | l1l2Voltage/l2l3Voltage/l1l3Voltage  | Double  | L1-L2/L2-L3/L1-L3 voltage，unit: V
 | deviceModel  | String  | Device model, for detailed information on the specific values, please refer to "Appendix 4: Description and definition of the deviceModel field"
 | alarmList  | Map  | The inverter alarm information displays the active alarms for the inverter, showing up to five items.
 | alarmList records
 | alarmStartTime  | String  | Alarm start time
 | errorCode  | String  | Alarm code
 | alarmName  | String  | Alarm name
 | alarmType  | String  | Alarm Type
 | alarmLevel  | Interger  | Alarm level
 | alarmState  | String  | Alarm status, 0:Closed, 1:Ongoing
 | alarmCause  | String  | Alarm reason
 | handleSuggestion  | String  | Recommendations for alarm handling
```

### Query Device History Data

**`GET /openapi/v2/device/history_data`**

Query historical data for a specific device or multiple devices of a certain type over a period of time. The maximum number of Device SNs is 10.

Meter Data Description: If the meter SN is available, query the corresponding data through the corresponding meter data interface. If the meter SN is unavailable or the CT is in use, query the corresponding data through the corresponding inverter data interface.

#### Request Fields

```text
 | Params Parameters  | Example
 | snList  | String  | Required  | /openapi/v2/device/history_data
    ?businessType=4
    &deviceType=1
    ?snList=X3******01,X3******02
    &startTime=1742918400000
    &endTime=1742972400000
    &timeInterval=60
 | Device SN, minimum 1, maximum 10, multiple device SNs separated by commas.
 | deviceType  | Integer  | Required
 | Device Type, for detailed information on the specific values, please refer to "Appendix 3: Description and definition of the deviceType field"
 | requestSnType  | Interger  | Optional
 | Device Type, for detailed information on the specific values, please refer to "Appendix 3: Description and definition of the deviceType field"
This field only applies when "deviceType": "2". Its primary use case is querying battery data without a serial number (SN).
When deviceType=2 (battery) and requestSnType=1 (inverter) in the request parameters, it indicates a query for all battery information under the requested inverter SN;
When deviceType=2 (battery) and requestSnType=2 (battery) in the request parameters, it indicates a query for battery information with the requested battery SN;
When deviceType=2 (battery) in the request parameters and requestSnType is not specified, it is equivalent to requestSnType=2 (battery), indicating a query for battery information with the requested battery SN.
 | startTime  | Long  | Required
 | The query start time, uses a 13-digit millisecond-level Unix timestamp (UTC time), and the query time period is within one year.
 | endTime  | Long  | Required
 | The query end time, uses a 13-digit millisecond-level Unix timestamp (UTC time), and the total query duration does not exceed 12 hours.
 | timeInterval  | Integer  | Required
 | Data time interval, 5,10,15,30,60, unit: mins
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial
```

#### Returned Fields

```text
 | Parameter Name  | Type  | Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
        "code": 10000,
        "result": [{
                "deviceStatus": null,
                "dataTime": null,
                "plantLocalTime": "2025-05-21 12:20:20",
                "deviceSn": "X3******01",
                "registerNo": "SE******SP",
                "acCurrent1": 6.4,
                "acVoltage1": 228.1,
                "acCurrent2": 6.4,
                "acCurrent3": 6.7,
                "acVoltage2": 230.5,
                "acVoltage3": 229.9,
                "acPower1": 1.25,
                "acPower2": 1.268,
                "acPower3": 1.321,
                "acFrequency1": 50.0,
                "acFrequency2": 50.0,
                "acFrequency3": 50.0,
                "gridFrequency": 0.0,
                "totalPowerFactor": null,
                "inverterTemperature": 38.1,
                "dailyACOutput": 57.8,
                "totalACOutput": 24773.7,
                "dailyYield": 57.0,
                "totalYield": 24493.5,
                "mpptMap": {
                        "mppt2Voltage": 0.0,
                        "mppt3Voltage": 0.0,
                        "mppt1Voltage": 0.0,
                        "mppt4Voltage": 0.0,
                        "mppt5Voltage": 0.0,
                        "mppt10Power": 0.0,
                        "mppt6Voltage": 0.0,
                        "mppt7Voltage": 0.0,
                        "mppt3Power": 0.0,
                        "mppt6Power": 0.0,
                        "mppt11Current": 0.0,
                        "mppt10Current": 0.0,
                        "mppt12Current": 0.0,
                        "mppt9Power": 0.0,
                        "mppt2Power": 0.0,
                        "mppt8Power": 0.0,
                        "mppt11Voltage": 0.0,
                        "mppt12Voltage": 0.0,
                        "mppt12Power": 0.0,
                        "mppt10Voltage": 0.0,
                        "mppt8Voltage": 0.0,
                        "mppt9Voltage": 0.0,
                        "mppt5Power": 0.0,
                        "mppt5Current": 0.0,
                        "mppt6Current": 0.0,
                        "mppt7Current": 0.0,
                        "mppt8Current": 0.0,
                        "mppt7Power": 0.0,
                        "mppt3Current": 0.0,
                        "mppt2Current": 0.0,
                        "mppt4Current": 0.0,
                        "mppt11Power": 0.0,
                        "mppt4Power": 0.0,
                        "mppt9Current": 0.0,
                        "mppt1Current": 0.0,
                        "mppt1Power": 0.0
                },
                "pvMap": {
                        "pv3Current": 1.4,
                        "pv1Voltage": 636.4,
                        "pv2Voltage": 617.4,
                        "pv3Voltage": 611.5,
                        "pv4Voltage": 610.8,
                        "pv1Current": 1.3,
                        "pv4Current": 1.3,
                        "pv5Current": 1.1,
                        "pv1Power": 0.838,
                        "pv2Current": 1.0,
                        "pv2Power": 0.673,
                        "pv4Power": 0.803,
                        "pv6Power": 0.0,
                        "pv6Voltage": 0.0,
                        "pv5Power": 0.761,
                        "pv6Current": 0.0,
                        "pv5Voltage": 684.8,
                        "pv3Power": 0.87
                },
                "gridPower": 0,
                "todayImportEnergy": 0.07,
                "totalImportEnergy": 23.28,
                "todayExportEnergy": 0.00,
                "totalExportEnergy": 64.98,
                "gridPowerM2": 0,
                "todayImportEnergyM2": 0.08,
                "totalImportEnergyM2": 1.39,
                "todayExportEnergyM2": 0.00,
                "totalExportEnergyM2": 0.75,
                "EPSL1Voltage": 0.0,
                "EPSL1Current": 0.0,
                "EPSL1ActivePower": 0.0,
                "EPSL2Voltage": 0.0,
                "EPSL2Current": 0.0,
                "EPSL2ActivePower": 0.0,
                "EPSL3Voltage": 0.0,
                "EPSL3Current": 0.0,
                "EPSL3ActivePower": 0.0,
                "EPSL1ApparentPower": 0.0,
                "EPSL2ApparentPower": 0.0,
                "EPSL3ApparentPower": 0.0,
                "l2l3Voltage": null,
                "l1l2Voltage": null,
                "l1l3Voltage": null,
                "totalReactivePower": null,
                "totalActivePower": 3,
                "MPPTTotalInputPower": null
        },
        ……
      ]
}
 | message  | String  | Error Message
 | result  | Data  | Specific data
 | result-Inverter
 | deviceSn  | String  | Device SN
 | dataTime  | String  | Data reporting time (UTC time)
 | plantLocalTime  | String  | Data reporting time (Plant local time)
 | registerNo  | String  | Device registration number
 | deviceStatus  | Integer  | Device status, for detailed information on the specific values, please refer to "Appendix 6: Description and definition of the deviceStatus field"
 | acVoltage1~acVoltage3  | Double  | AC voltage 1~3, unit: V
 | acCurrent1~acCurrent3  | Double  | AC current 1~3, unit: A
 | acFrequency1~acFrequency3  | Double  | AC frequency 1~3, unit: Hz
 | acPower1~acPower3  | Double  | AC power 1~3, unit: W(businessType=1), kW(businessType=4)
 | gridFrequency  | Double  | Grid frequency, unit: Hz
 | totalActivePower  | Double  | Total active power, unit: W(businessType=1), kW(businessType=4), a positive value indicates discharge, a negative value indicates charge
 | totalReactivePower  | Double  | Total reactive power, unit: Var(businessType=1), kVar(businessType=4)
 | totalPowerFactor  | Double  | Total power factor
 | inverterTemperature  | Double  | Inverter temperature, unit: ℃
 | dailyYield  | Double  | Daily PV yield, unit: kWh
 | totalYield  | Double  | Total PV yield, unit: kWh
 | dailyACOutput  | Double  | Daily AC output, unit: kWh
 | totalACOutput  | Double  | Total AC output, unit: kWh
 | MPPTTotalInputPower  | Double  | MPPT input total power, unit: W(businessType=1), kW(businessType=4)
 | mpptMap  | Map  | MPPT data，Contains data content:
mppt1Voltage~mpptnVoltage: MPPT1~MPPTn voltage, unit: V
mppt1Current~mpptnCurrent: MPPT1~MPPTn current, unit: A
mppt1Power~mpptnPower: MPPT1~MPPTn power, unit: W(businessType=1), kW(businessType=4)
 | pvMap  | Double  | MPPT data，Contains data content:
pv1Voltage~pvnVoltage: PV1~PVn voltage, unit: V
pv1Current~pvnCurrent: PV1~PVn current, unit: A
pv1Power~pvnPower: PV1~PVn power, unit: W(businessType=1), kW(businessType=4)
 | gridPower  | Double  | Meter 1 grid port power, a positive value indicates export, a negative value indicates import, unit: W(businessType=1), kW(businessType=4)
 | todayImportEnergy  | Double  | Meter 1 daily imported active energy，unit kWh
 | totalImportEnergy  | Double  | Meter 1 total imported active energy，unit kWh
 | todayExportEnergy  | Double  | Meter 1 daily exported active energy，unit kWh
 | totalExportEnergy  | Double  | Meter 1 total exported active energy，unit kWh
 | gridPowerM2  | Double  | Meter 2 grid port power, a positive value indicates export, a negative value indicates import, unit: W(businessType=1), kW(businessType=4)
 | todayImportEnergyM2  | Double  | Meter 2 total imported active energy，unit kWh
 | totalImportEnergyM2  | Double  | Meter 2 total imported active energy，unit kWh
 | todayExportEnergyM2  | Double  | Meter 2 daily exported active energy，unit kWh
 | totalExportEnergyM2  | Double  | Meter 2 total exported active energy，unit kWh
 | epsl1ApparentPower~epsl3ApparentPower  | Double  | EPS L1~L3 apparent power，unit: W(businessType=1), kW(businessType=4)
 | epsl1ActivePower~epsl3ActivePower  | Double  | EPS L1~L3 active power，unit: W(businessType=1), kW(businessType=4)
 | epsl1Voltage~epsl3Voltage  | Double  | EPS L1~L3 voltage，unit: V
 | epsl1Current~epsl3Current  | Double  | EPS L1~L3 current，unit: A
 | l1l2Voltage/l2l3Voltage/l1l3Voltage  | Double  | L1-L2/L2-L3/L1-L3 voltage，unit: V
 | result-Battery data
 | deviceSn  | String  | Device SN
 | dataTime  | String  | Data reporting time (UTC time)
 | plantLocalTime  | String  | Data reporting time (Plant local time)
 | registerNo  | String  | Device registration number
 | deviceStatus  | Interger  | Device status, for detailed information on the specific values, please refer to "Appendix 6: Description and definition of the deviceStatus field"
 | batterySOC  | Double  | BatterySOC, unit: %
 | batteryRemainings  | Double  | Battery remain energy, unit: kWh
 | batterySOH  | Double  | BatterySOH
 | chargeDischargePower  | Double  | Battery charging or discharging power, unit: W, a positive value indicates charge, a negative value indicates discharge
 | batteryVoltage  | Double  | Battery voltage, unit: V
 | batteryCurrent  | Double  | Battery current, unit: A
 | batteryTemperature  | Double  | Battery temperature, unit: ℃
 | batteryCycleTimes  | Integer  | Battery cycle times
 | totalDevicCharge  | Double  | Total charge, unit: kWh
 | totalDeviceDischarge  | Double  | Total discharge, unit: kWh
 | result-Meter data
 | deviceSn  | String  | Device SN
 | dataTime  | String  | Data reporting time (UTC time)
 | plantLocalTime  | String  | Data reporting time (Plant local time)
 | registerNo  | String  | Device registration number
 | importEnergy  | Double  | Total forward active energy, unit: kWh
 | exportEnergy  | Double  | Total reverse active energy, unit: kWh
 | totalActivePower  | Double  | Total active power, unit:  W(businessType=1), kW(businessType=4), a positive value indicates export to grid, a negative value indicates import from grid.
 | totalReactivePower  | Double  | Total reactive power, unit:  Var(businessType=1), kVar(businessType=4)
 | totalApparentPower  | Double  | Total apparent power, unit:  W(businessType=1), kVA(businessType=4)
 | powerFactor  | Double  | Power factor
 | gridFrequency  | Double  | Grid frequency, unit: Hz
 | l1ActivePower~l3ActivePower  | Double  | L1~L3 active power, unit:  W(businessType=1), kW(businessType=4)
 | l1ReactivePower~l3ReactivePower  | Double  | L1~L3 Reactive power, unit:  Var(businessType=1), kVar(businessType=4)
 | l1Voltage~l3Voltage  | Double  | L1~L3 voltage, unit: V
 | l1Current~l3Current  | Double  | L1~L3 current, unit: A
```

> **Project note.** **Verified, used.** A request may span at most 12 hours.

## Inverter Control Inquiry

### Request Result Query

**`POST /openapi/apiRequestLog/listByCondition`**

Used to query the execution result of the control instruction, and query through the requestId returned by the control instruction.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | requestId  | String  | Required  | {
    "requestId": "66******66"
}
 | The requestId returned by the request control instruction
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
  "code": 0,
  "message": "string",
  "success": true,
  "result": [
    {
      "sn": "string",
      "status": 3
    }
  ]
}
 | message  | String  | Error Message
 | success  | boolean  | Success flag
 | result  | Data  | Specific data
 | Result description
 | sn  | String  | Specific device SN
 | status  | Interger  | Specific device execution results description, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

> **Project note.** **Observed:** answered `10000` with an empty `result` for the `requestId`s of `add_tou_set` and `delete_tou_set` (also with `deviceSn` added and with a numeric `requestId`), so it could not be used to read their outcome.

## Export Limit and Import Limit Setting

Set the export and import limits for the inverter/system. For Commercial & Industrial EMS systems, the control objects need to be requested from the "Query Mster Device"

Note: Currently only NEO and EMS1000+AELIO are supported.

### Export Limit Setting

**`POST /openapi/v2/device/device_control/strategy/set_export_control`**

Batch set the export limit of inverter/system。

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
            "snList": [
                "X3******01"
                ],
            "deviceType": 100,
            "isEnable": 1,
            "controlMode": 1,
            "limitValue": 1.00,
            "businessType": 4
}
 | Device SN collection, minimum 1, maximum 10
 | deviceType  | Interger  | Required
 | Device Type, for detailed information on the specific values, please refer to "Appendix 3: Description and definition of the deviceType field"
 | isEnable  | Interger  | Required
 | Export limit function enable status
0: Disable
1: Enable
 | controlMode  | Interger  | Optional
 | Control mode, enable and required when businessType = 4
1: Overall control
 | limitValue  | BigDecimal  | Required
 | Limit value, two decimal places, unit:kW
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
  "code": 10000,
  "message": "success",
  "result": {
    "X3******01": {
      "status": 3
    }
  },
  "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### Import Limit Setting

**`POST /openapi/v2/device/device_control/strategy/set_import_control`**

Batch set the import limit of inverter/system. Currently only support for Commercial & Industrial business of EMS1000+AELIO system.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
            "snList": [
                "XM******M1"
                ],
            "deviceType": 100,
            "isEnable": 1,
            "limitValue": 11,
            "businessType": 4
}
 | Device SN collection, minimum 1, maximum 10
 | deviceType  | Interger  | Required
 | Device Type, for detailed information on the specific values, please refer to "Appendix 3: Description and definition of the deviceType field"
 | isEnable  | Interger  | Required
 | Import limit function enable status
0: Disable
1: Enable
 | limitValue  | Interger  | Required
 | Limit value, unit:kW
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
  "code": 10000,
  "message": "success",
  "result": {
    "XM******M1": {
      "status": 3
    }
  },
  "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

## Inverter Work Mode Control

Set the working mode of inverters except A1-Hybrid-G2.

### Self Use Mode

**`POST /openapi/v2/device/inverter_work_mode/batch_set_spontaneity_self_use`**

Batch set the inverter working mode to Self Use Mode.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
  "snList": [
    "X3******01"
  ],
  "businessType": 1,
  "chargeUpperSoc": 18,
  "minSoc": 10,
  "chargeFromGridEnable": 1,
  "chargeStartTimePeriod1": "05:57",
  "chargeEndTimePeriod1": "06:20",
  "dischargeStartTimePeriod1": "06:39",
  "dischargeEndTimePeriod1": "23:50",
  "enableTimePeriod2": 3,
  "chargeStartTimePeriod2": "",
  "chargeEndTimePeriod2": "05:23",
  "dischargeStartTimePeriod2": "00:52",
  "dischargeEndTimePeriod2": "23:56"
}
 | Device SN collection, minimum 1, maximum 10
 | minSoc  | Interger  | Required
 | Minimum SOC
 | chargeFromGridEnable  | Interger  | Required
 | Whether it is allowed to import from the grid
0: Not allowed
1: allowed
 | chargeUpperSoc  | Interger  | Required
 | Charging limit SOC
 | chargeStartTimePeriod1  | String  | Optional
 | Start time of charge time period 1, hh:mm (hour [0-23], minute [0-59])
 | chargeEndTimePeriod1  | String  | Optional
 | Start time of charge time period 1, hh:mm (hour [0-23], minute [0-59]), hh:mm (hour [0-23], minute [0-59])
 | dischargeStartTimePeriod1  | String  | Optional
 | Start time of discharge time period 1, hh:mm (hour [0-23], minute [0-59])
 | dischargeEndTimePeriod1  | String  | Optional
 | End time of discharge time period 1, hh:mm (hour [0-23], minute [0-59])
 | enableTimePeriod2  | Interger  | Optional
 | Charge and discharge time period 2 Enable switch
0: Not allowed
1: allowed
 | chargeStartTimePeriod2  | String  | Optional
 | Start time of charge time period 2, hh:mm (hour [0-23], minute [0-59])
Required when enableTimePeriod2 = 1
 | chargeEndTimePeriod2  | String  | Optional
 | End time of charge time period 2, hh:mm (hour [0-23], minute [0-59])
Required when enableTimePeriod2 = 1
 | dischargeStartTimePeriod2  | String  | Optional
 | Start time of discharge time period 2, hh:mm (hour [0-23], minute [0-59])
Required when enableTimePeriod2 = 1
 | dischargeEndTimePeriod2  | String  | Optional
 | End time of discharge time period 2, hh:mm (hour [0-23], minute [0-59])
Required when enableTimePeriod2 = 1
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
  "code": 10000,
  "message": "success",
  "result": {
    "X3******01": {
      "status": 3
    }
  },
  "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### Feed-in Priority Mode

**`POST /openapi/v2/device/inverter_work_mode/batch_set_on_grid_first`**

Batch set the inverter working mode to Feed-in Priority Mode.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
  "snList": [
    "X3******01"
  ],
  "businessType": 4,
  "minSoc": 55,
  "chargeUpperSoc": 99,
  "chargeStartTimePeriod1": "11:59",
  "chargeEndTimePeriod1": "13:00",
  "dischargeStartTimePeriod1": "14:20",
  "dischargeEndTimePeriod1": "15:21",
  "enableTimePeriod2": 1,
  "chargeStartTimePeriod2": "16:59",
  "chargeEndTimePeriod2": "17:00",
  "dischargeStartTimePeriod2": "19:21",
  "dischargeEndTimePeriod2": "19:21"
}
 | Device SN collection, minimum 1, maximum 10
 | minSoc  | Interger  | Required
 | Minimum SOC
 | chargeUpperSoc  | Interger  | Required
 | Charging limit SOC
 | chargeStartTimePeriod1  | String  | Optional
 | Start time of charge time period 1, hh:mm (hour [0-23], minute [0-59])
 | chargeEndTimePeriod1  | String  | Optional
 | Start time of charge time period 1, hh:mm (hour [0-23], minute [0-59]), hh:mm (hour [0-23], minute [0-59])
 | dischargeStartTimePeriod1  | String  | Optional
 | Start time of discharge time period 1, hh:mm (hour [0-23], minute [0-59])
 | dischargeEndTimePeriod1  | String  | Optional
 | End time of discharge time period 1, hh:mm (hour [0-23], minute [0-59])
 | enableTimePeriod2  | Interger  | Optional
 | Charge and discharge time period 2 Enable switch
0: Not allowed
1: allowed
 | chargeStartTimePeriod2  | String  | Optional
 | Start time of charge time period 2, hh:mm (hour [0-23], minute [0-59])
Required when enableTimePeriod2 = 1
 | chargeEndTimePeriod2  | String  | Optional
 | End time of charge time period 2, hh:mm (hour [0-23], minute [0-59])
Required when enableTimePeriod2 = 1
 | dischargeStartTimePeriod2  | String  | Optional
 | Start time of discharge time period 2, hh:mm (hour [0-23], minute [0-59])
Required when enableTimePeriod2 = 1
 | dischargeEndTimePeriod2  | String  | Optional
 | End time of discharge time period 2, hh:mm (hour [0-23], minute [0-59])
Required when enableTimePeriod2 = 1
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
  "code": 10000,
  "message": "success",
  "result": {
    "X3******01": {
      "status": 3
    }
  },
  "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### Back Up Mode

**`POST /openapi/v2/device/inverter_work_mode/batch_set_peace_mode`**

Batch set the inverter working mode to Back Up Mode.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
  "snList": [
    "X3******01"
  ],
  "businessType": 4,
  "minSoc": 98,
  "chargeUpperSoc": 99,
  "chargeFromGridEnable": 1,
  "enableTimePeriod2": 0,
  "chargeStartTimePeriod1": "19:00",
  "chargeEndTimePeriod1": "19:10",
  "dischargeStartTimePeriod1": "19:15",
  "dischargeEndTimePeriod1": "19:25"
}
 | Device SN collection, minimum 1, maximum 10
 | minSoc  | Interger  | Required
 | Minimum SOC
 | chargeFromGridEnable  | Interger  | Required
 | Whether it is allowed to import from the grid
0: Not allowed
1: allowed
 | chargeUpperSoc  | Interger  | Required
 | Charging limit SOC
 | chargeStartTimePeriod1  | String  | Optional
 | Start time of charge time period 1, hh:mm (hour [0-23], minute [0-59])
 | chargeEndTimePeriod1  | String  | Optional
 | Start time of charge time period 1, hh:mm (hour [0-23], minute [0-59]), hh:mm (hour [0-23], minute [0-59])
 | dischargeStartTimePeriod1  | String  | Optional
 | Start time of discharge time period 1, hh:mm (hour [0-23], minute [0-59])
 | dischargeEndTimePeriod1  | String  | Optional
 | End time of discharge time period 1, hh:mm (hour [0-23], minute [0-59])
 | enableTimePeriod2  | Interger  | Optional
 | Charge and discharge time period 2 Enable switch
0: Not allowed
1: allowed
 | chargeStartTimePeriod2  | String  | Optional
 | Start time of charge time period 2, hh:mm (hour [0-23], minute [0-59])
Required when enableTimePeriod2 = 1
 | chargeEndTimePeriod2  | String  | Optional
 | End time of charge time period 2, hh:mm (hour [0-23], minute [0-59])
Required when enableTimePeriod2 = 1
 | dischargeStartTimePeriod2  | String  | Optional
 | Start time of discharge time period 2, hh:mm (hour [0-23], minute [0-59])
Required when enableTimePeriod2 = 1
 | dischargeEndTimePeriod2  | String  | Optional
 | End time of discharge time period 2, hh:mm (hour [0-23], minute [0-59])
Required when enableTimePeriod2 = 1
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
  "code": 10000,
  "message": "success",
  "result": {
    "X3******01": {
      "status": 3
    }
  },
  "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### Manual Mode

**`POST /openapi/v2/device/inverter_work_mode/batch_set_manual_mode`**

Batch set the inverter working mode to Manual Mode, mainly used in debugging.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
  "snList": [
    "X3******01"
  ],
  "businessType": "1",
  "manualMode": 2
}
 | Device SN collection, minimum 1, maximum 10
 | manualMode  | Interger  | Required
 | Manual mode setting parameters:
0: Stop charging and discharging
1: Force charging
2: Force discharging
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
  "code": 10000,
  "message": "success",
  "result": {
    "X3******01": {
      "status": 3
    }
  },
  "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### TOU Mode – Add New TOU Setting

**`POST /openapi/v2/device/config/tou/add_tou_set`**

Added a TOU (Time-of-Use) mode setting for the inverter. Currently, only residential businessType is supported.

Specific modes can be scheduled for fixed time periods; the modes are explained below:

Self Use

PV energy is as self-sufficient as possible. The priority of PV energy is: load>battery>grid connection.

Min SOC: Functions similarly to Grid-Connected Min SOC, values do not interfere. In TOU scenarios, only this value is considered.

Charging

PV will charge the battery as much as possible, and the user can choose whether to import from the grid.

Allow Grid Power: Yes/No.

Charge To: Target SOC for grid charging

Max Charge Power: Max power for charging with both PV and grid

Discharging

The system will discharge at the "percentage" rated power until the "discharge threshold", and then switch to Self-use.

AC Power: Max AC output power, can be % of rated or specified limit.

Discharge To: Discharge Cutoff SOC.

Battery Standby

The battery does not charge or discharge. PV energy is only for self-use or output. The battery is charged only when the SOC is lower than the set minimum SOC. When the minimum SOC is reached, the charging stops.

Peak Shaving

After the load power reaches the threshold, the battery will begin to discharge.

Peak Limit: Sets peak shaving limit, allowing battery discharge when grid consumption exceeds this.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
    "snList": [
        "X3******01"
    ],
    "businessType": "1",
    "startDate": "10-20",
    "endDate": "10-30",
    "minSoc": 20,
    "runModeInfoList": [
        {
            "runMode": 5,
            "startTime": "00:00",
            "endTime": "23:59",
            "disChargePower": "80",
            "targetSoc": "100"
        }
    ]
}
 | Device SN collection, minimum 1, maximum 10
 | startDate  | String  | Required
 | Start date,format: MM-dd
 | endDate  | String  | Required
 | End date,format: MM-dd
 | minSoc  | String  | Required
 | Minimum SOC
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial.
 | runModeInfoList  | List  | Required
 | Settings Content
 | runModeInfoList description
 | startTime  | String  | Required
 | Start time, format: HH:mm
 | endTime  | String  | Required
 | End time, format: HH:mm
 | runMode  | Interger  | Required
 | Mode type. 1: Self Use. 2: Battery Standby. 3: Peak shaving. 4: Charging. 5: Discharging.
 | minSoc  | Interger  | Optional
 | Min SOC, required and effective when runMode=1, unit: %.
 | targetSoc  | Interger  | Optional
 | Target SOC, required and effective when runMode=4 or 5, unit: %.
 | peakLimitedPower  | Interger  | Optional
 | Power limit for peak shaving, required and effective when runMode=3, unit: W(businessType=1)，kW(businessType=4).
 | chargePower  | Interger  | Optional
 | Battery charging power, required and effective when runMode=4, unit: W(businessType=1)，kW(businessType=4)
 | disChargePower  | Interger  | Optional
 | Battery discharging power, required and effective when runMode=5, unit: (businessType=1)，kW(businessType=4)
 | chargeGridEnableFlag  | Interger  | Optional
 | Whether it is allowed to import from the grid:
0: Not allowed
1: allowed
required and effective when runMode=4
```

#### Returned Fields

```text
 | Request Parameters  | Type  | Description  | Example
 | code  | Integer  | 0 indicates success, non-0 indicates failure  | {
    "code": 10000,
    "result": {
        "optDeviceList": [
            {
                "deviceSn": "X3******01",
                "message": null,
                "ruleId": 21******30,
                "status": 1
            }
        ]
    },
    "traceId": "d987******234",
    "requestId": "66******66"
}
 | message  | String  | Error Message
 | traceId  | String  | The traceId recorded by the platform for this request, used for tracing and troubleshooting issues.
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | result  | Object  | Specific data
 | Result description
 | optDeviceList  | List  | Processing results for each device
 | optDeviceList description
 | deviceSn  | String  | Device SN
 | status  | Interger  | Device command result. 1: Add successful; 2: Add failed.
 | ruleId  | Long  | TOU rule Id
 | message  | String  | Error Message
```

> **Project note.** **Verified 2026-10-21.** A rule must be a full-day schedule: the first segment starts at `00:00`, `startTime` must be before `endTime` (a segment cannot cross midnight), the last segment ends at `23:59` (`24:00` is rejected), segments share their boundary time, every self-use segment needs its own `minSoc`. A charge across midnight needs one single-date rule per date. `result.optDeviceList[0].ruleId` is a 19-digit number (read it as text); `status` 1 = added. The documentation does not say how the inverter is switched into a TOU work mode. See A2.

### TOU Mode - Inquiry

**`POST /openapi/v2/device/config/tou/query_tou_set`**

Query the inverter's TOU settings for a specific time.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | deviceSn  | String  | Required  | {
    "deviceSn": "X3******01",
    "businessType": 1,
    "date": "09-29"
}
 | Device SN
 | date  | String  | Required
 | Inquiry Date, formate: MM-dd
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial.
```

#### Returned Fields

```text
 | Request Parameters  | Type  | Description  | Example
 | code  | Integer  | 0 indicates success, non-0 indicates failure  | {
    "code": 10000,
    "result": {
        "date": "09-29",
        "deviceSn": "X3******01",
        "startDate": "09-28",
        "endDate": "09-30",
        "minSoc": 20,
        "ruleId": 21******30,
        "runModeInfoList": [
            {
                "runMode": 1,
                "startTime": "00:00",
                "endTime": "09:30",
                "minSoc": 30
            },
            {
                "runMode": 5,
                "startTime": "09:30",
                "endTime": "11:30",
                "disChargePower": 1000,
                "targetSoc": 40
            },
            {
                "runMode": 2,
                "startTime": "11:30",
                "endTime": "13:30"
            },
            {
                "runMode": 4,
                "startTime": "13:30",
                "endTime": "15:30",
                "chargePower": 1000,
                "targetSoc": 80,
                "chargeGridEnableFlag": 1
            },
            {
                "runMode": 3,
                "startTime": "15:30",
                "endTime": "23:59",
                "peakLimitedPower": 1000
            }
        ]
    },
    "traceId": "d987******234"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | result  | Object  | Specific data
 | Result说明
 | deviceSn  | String  | Device SN
 | date  | String  | Inquiry Date, formate: MM-dd
 | ruleId  | Long  | TOU rule Id
 | minSoc  | Interger  | Min SOC
 | startDate  | String  | Start date,format: MM-dd
 | endDate  | String  | End date,format: MM-dd
 | runModeInfoList  | List  | Specific details of the configured TOU mode
 | runModeInfoList description
 | runMode  | Interger  | Configured TOU mode
1: Self Use
2: Battery Standby
3: Peak Shaving
4: Charging
5: Discharging
 | startlime  | String  | Start time, format: HH:mm
 | endTime  | String  | End time, format: HH:mm
 | minSoc  | Interger  | Min SOC, returned when runMode=1, unit: %.
 | targetSoc  | Interger  | Target SOC, returned when runMode=4 or 5, unit: %.
 | peakLimitedPower  | Interger  | Power limit for peak shaving, returned when runMode-3, unit: W(ousiness Type-1), kW(ousiness Type-4).
 | chargePower  | Interger  | Battery charging power, returned when runMode-4, unit: W(business Type-1), kW(business Type-4)
 | disChargePower  | Interger  | Battery discharging power, returned when runMode-5, unit: (ousiness Type-1), kW(business Type-4)
 | chargeGridEnableFlag  | Interger  | Whether it is allowed to import from the grid:
0: Not allowed
1: allowed
Returned when runMode=4
```

> **Project note.** **Verified 2026-10-21.** Needs `deviceSn` (one serial), `businessType` and **`date`** (`MM-dd`). With `startDate`/`endDate` instead: `FIELD_IS_BLANK: startDate`; GET gives HTTP 500; form encoding is unsupported. When no rule covers the date the object comes back with null fields.

### TOU mode - Delete

**`POST /openapi/v2/device/config/tou/delete_tou_set`**

Delete a specific TOU setting for a specific device.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | deviceSn  | String  | Required  | {
    "deviceSn": "X3******01",
    "businessType": 1,
    "ruleId": 21******30
}
 | Device SN
 | ruleId  | Long  | Required
 | TOU rule Id
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial. Currently only supports Residential.
```

#### Returned Fields

```text
 | Request Parameters  | Type  | Description  | Example
 | code  | Integer  | 0 indicates success, non-0 indicates failure  | {
    "code": 10000,
    "traceId": "d987******234",
    "requestId": "66******66"
}
 | message  | String  | Error Message
 | traceId  | String  | The traceId recorded by the platform for this request, used for tracing and troubleshooting issues.
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
```

> **Project note.** **Verified 2026-10-21.** `deviceSn` + `businessType` + `ruleId` returns `10000`; the date then queries empty.

## Inverter Function Settings

### Battery Heating Settings

**`POST /openapi/v2/device/config/battery/set_battery_heating`**

Batch set the battery heating function of inverters.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
    "snList": [
        "X3******01"
    ],
    "businessType": 1,
    "heatingEnable": 1,
    "heatingLevel": 1,
    "heatingPeriod1StartTime": "03:05",
    "heatingPeriod1EndTime": "05:05",
    "heatingPeriod2StartTime": "05:06",
    "heatingPeriod2EndTime": "07:08"
}
 | Device SN collection, minimum 1, maximum 10
 | heatingEnable  | Interger  | Required
 | Enable Battery Heating
0: Disabled
1: Enabled
 | heatingLevel  | Interger  | Optional
 | Battery Heating Level:
0: Activates below 0°C, heats to 10°C; 10A charge/discharge current.
1: Activates below 10°C, heats to 15°C; 15A charge/discharge current.
2: Activates below 20°C, heats to 25°C; 30A charge/discharge current.
This parameter is mandatory and effective only when `heatingEnable=1`.
 | heatingPeriod1StartTime  | Interger  | Optional
 | Battery heating period 1 start time, HH:mm. Required when heatingEnable=1.
 | heatingPeriod1EndTime  | Interger  | Optional
 | Battery heating period 1 end time, HH:mm. Required when heatingEnable=1.heatingPeriod1EndTime must be later than heatingPeriod1StartTime.
 | heatingPeriod2StartTime  | Interger  | Optional
 | Battery heating period 2 start time, HH:mm.
 | heatingPeriod2EndTime  | Interger  | Optional
 | Battery heating period 2 end time, HH:mm. Required when heatingPeriod2StartTime is specified..heatingPeriod2EndTime must be later than heatingPeriod2StartTime.
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "result": {
        "X3******01": {
            "status": 3
        }
    },
    "traceId": "d987******234",
    "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | traceId  | String  | The traceId recorded by the platform for this request, used for tracing and troubleshooting issues.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

## Inverter Remote Control Mode

Set the remote control mode of inverters except TRENE.

### Exit remote control

**`POST /openapi/v2/device/inverter_vpp_mode/exit_vpp_mode`**

Batch set inverter exit remote control.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
    "snList": [
        "X3******01"
    ],
    "businessType": 1
}
 | Device SN collection, minimum 1, maximum 10
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
  "code": 10000,
  "message": "success",
  "result": {
    "X3******01": {
      "status": 3
    }
  },
  "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### Power Control Mode

**`POST /openapi/v2/device/inverter_vpp_mode/power_control_mode`**

This mode controls the active and reactive power at the AC port of the inverter for a period of time. In this mode the PV runs at the highest possible power and the system can feed/take power to/from the grid. The key of the model is that the system will try to meet the target power as much as possible. If during operation the power from PV and the battery cannot meet the power requirements specified by the mode, the inverter will aim to get as close as possible to the target power requirement.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
  "snList": [
    "X3******01"
  ],
  "activePowerTarget": 1000,
  "wReactivePowerTarget": 100,
  "timeOfDuration": 60,
  "businessType": 1
}
 | Device SN collection, minimum 1, maximum 10
 | activePowerTarget  | Interger  | Required
 | Active power target (W)
 | wReactivePowerTarget  | Interger  | Required
 | Reactive power target (Var)
 | timeOfDuration  | Interger  | Required
 | Mode duration time (S)
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
  "code": 10000,
  "message": "success",
  "result": {
    "X3******01": {
      "status": 3
    }
  },
  "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### Electric Quantity Target Control Mode

**`POST /openapi/v2/device/inverter_vpp_mode/electric_quantity_target_control_mode`**

This mode controls the AC port of the inverter to input/output a certain amount of electric energy with a certain power. In this mode the PV runs at the highest possible power and the system can feed/take power to/from the grid. If during operation the power from PV and the battery cannot meet the power requirements specified by the mode, the inverter will aim to get as close as possible to the target power requirement.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
  "snList": [
    "X3******01"
  ],
  "businessType": 1,
  "chargeDischargPower": -1000,
  "targetEngergy": 1000
}
 | Device SN collection, minimum 1, maximum 10
 | targetEngergy  | Interger  | Required
 | AC input/output energy target, unit: Wh
 | chargeDischargPower  | Interger  | Required
 | AC active power target, a positive value indicates charge, a negative value indicates discharge, unit: W
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
  "code": 10000,
  "message": "success",
  "result": {
    "X3******01": {
      "status": 3
    }
  },
  "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### SOC Target Control Mode

**`POST /openapi/v2/device/inverter_vpp_mode/soc_target_control_mode`**

This mode controls the AC port of the inverter to input/output with a certain power and takes the battery SOC as the target value. In this mode the PV runs at the highest possible power and the system can feed/take power to/from the grid. If the current battery SOC is 50% and the command requires a certain power input from the AC port and the target SOC is 40%, then the system will directly exit the SOC target control mode.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
  "snList": [
    "X3******01"
  ],
  "targetSoc": 1,
  "chargeDischargPower": 1,
  "businessType": 1
}
 | Device SN collection, minimum 1, maximum 10
 | targetSoc  | Interger  | Required
 | Target SOC
 | chargeDischargPower  | Interger  | Required
 | AC active power target, a positive value indicates charge, a negative value indicates discharge, unit: W
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
  "code": 10000,
  "message": "success",
  "result": {
    "X3******01": {
      "status": 3
    }
  },
  "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### Push Power - Positive/Negative Mode

**`POST /openapi/v2/device/inverter_vpp_mode/push_power/positive_or_negative_mode`**

This mode directly controls the battery charging/discharging power, the PV power is as high as possible and the system can feed/take power to/from the grid.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
  "snList": [
    "X3******01"
  ],
  "batteryPower": 10,
  "timeOfDuration": 1,
  "nextMotion": 160,
  "businessType": 1
}
 | Device SN collection, minimum 1, maximum 10
 | batteryPower  | Interger  | Required
 | Battery charge/discharge power target, a positive value indicates discharge, a negative value indicates charge, unit: W
 | timeOfDuration  | Interger  | Required
 | Mode duration time, unit: S
 | nextMotion  | Interger  | Required
 | Action after execution mode ends
160: Exit Remote Control
161: Back to Self-Consume - Charge/Discharge Mode
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
  "code": 10000,
  "message": "success",
  "result": {
    "X3******01": {
      "status": 3
    }
  },
  "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### Push Power - Zero Mode

**`POST /openapi/v2/device/inverter_vpp_mode/push_power/zero_mode`**

In this mode the battery does not charge or discharge, the PV power is as high as possible, and the system can feed/take power to/from the grid. The batteries remain powered but do not work, and the hybrid inverter is working similar to an on-grid inverter (string inverter).

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
  "snList": [
    "X3******01"
  ],
  "timeOfDuration": 1,
  "nextMotion": 1,
  "businessType": 1
}
 | Device SN collection, minimum 1, maximum 10
 | timeOfDuration  | Interger  | Required
 | Mode duration time (S)
 | nextMotion  | Interger  | Required
 | Action after execution mode ends
160: Exit Remote Control
161: Back to Self-Consume - Charge/Discharge Mode
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
  "code": 10000,
  "message": "success",
  "result": {
    "X3******01": {
      "status": 3
    }
  },
  "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### Self-Consume Charge/Discharge Mode

**`POST /openapi/v2/device/inverter_vpp_mode/self_consume/charge_or_discharge_mode`**

Default remote control mode. In this mode the battery can be charged from PV only, no charge from grid. Battery discharge depending on the load and PV. If the PV input power cannot cover the consumption of the load, then the battery can be discharged; if the battery is fully charged, then the excess PV power can be fed to the grid. The priority of the PV input is: load > battery > grid. This is like Self-Use mode.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
  "snList": [
    "X3******01"
  ],
  "timeOfDuration": 1,
  "nextMotion": 160,
  "businessType": 1
}
 | Device SN collection, minimum 1, maximum 10
 | timeOfDuration  | Interger  | Required
 | Mode duration time (S)
 | nextMotion  | Interger  | Required
 | Action after execution mode ends
160: Exit Remote Control
161: Back to Self-Consume - Charge/Discharge Mode
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
  "code": 10000,
  "message": "success",
  "result": {
    "X3******01": {
      "status": 3
    }
  },
  "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### Self-Consume - Charge Only Mode

**`POST /openapi/v2/device/inverter_vpp_mode/self_consume/charge_only_mode`**

In this mode the battery can be charged from PV only, no charge from grid and discharge is not allowed. Import from grid if necessary, export to grid if battery full. The priority of the PV input is: load > battery > grid.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
    "snList": [
        "X3******01"
    ],
    "timeOfDuration": 300,
    "nextMotion": 160,
    "businessType": 1
}
 | Device SN collection, minimum 1, maximum 10
 | timeOfDuration  | Interger  | Required
 | Mode duration time (S)
 | nextMotion  | Interger  | Required
 | Action after execution mode ends
160: Exit Remote Control
161: Back to Self-Consume - Charge/Discharge Mode
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
  "code": 10000,
  "message": "success",
  "result": {
    "X3******01": {
      "status": 3
    }
  },
  "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### PV&BAT Individual Setting - Duration Mode

**`POST /openapi/v2/device/inverter_vpp_mode/pv_and_bat/individual_setting_duration_mode`**

In this mode the battery can be charged from PV and grid simultaneously, and PV power and BAT power can be set independently. In addition, the PV power limit can be set to zero, ensuring that the inverter can absorb power from the grid at its maximum capacity.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
  "snList": [
    "X3******01"
  ],
  "batteryPower": 1000,
  "pvPowerLimit": 0,
  "timeOfDuration": 300,
  "nextMotion": 160,
  "businessType": 1
}
 | Device SN collection, minimum 1, maximum 10
 | pvPowerLimit  | Interger  | Required
 | PV power limit (W)
 | batteryPower  | Interger  | Required
 | Battery power limit (W)
 | timeOfDuration  | Interger  | Required
 | Mode duration time (S)
 | nextMotion  | Interger  | Required
 | Action after execution mode ends
160: Exit Remote Control
161: Back to Self-Consume - Charge/Discharge Mode
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
  "code": 10000,
  "message": "success",
  "result": {
    "X3******01": {
      "status": 3
    }
  },
  "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### PV&BAT Individual Setting - Target SOC Mode

**`POST /openapi/v2/device/inverter_vpp_mode/pv_and_bat/individual_setting_target_soc_mode`**

In this mode the battery can be charged from PV and grid simultaneously, and PV power and BAT power can be set independently to reach the target SOC. In addition, the PV power limit can be set to zero, ensuring that the inverter can absorb power from the grid at its maximum capacity.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | deviceSnList  | List  | Required  | {
  "snList": [
    "X3******01"
  ],
  "batteryPower": 1000,
  "pvPowerLimit": 0,
  "targetSoc": 80,
  "timeOfDuration": 300,
  "nextMotion": 160,
  "businessType": 1
}
 | Device SN collection, minimum 1, maximum 10
 | pvPowerLimit  | Interger  | Required
 | PV power limit (W)
 | batteryPower  | Interger  | Required
 | Battery power limit (W)
 | targetSoc  | Interger  | Required
 | Target SOC（1-100）
 | timeOfDuration  | Interger  | Required
 | Mode duration time (S)
 | nextMotion  | Interger  | Required
 | Action after execution mode ends
160: Exit Remote Control
161: Back to Self-Consume - Charge/Discharge Mode
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
  "code": 10000,
  "message": "success",
  "result": {
    "X3******01": {
      "status": 3
    }
  },
  "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

## EV Charger Control

### EV Charger Charging Scene Settings

**`POST /openapi/v2/device/evc_control/set_charge_scene`**

Batch set EV Charger charging scene.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
    "snList": [
        "C1******01"
    ],
    "chargerScene": 1,
    "ocppUrl": "www.solaxcloud.com",
    "ocppChargerId": "66****66",
    "businessType": 1
}
 | Device SN collection, minimum 1, maximum 10
 | chargerScene  | Interger  | Required
 | Charging scene
0: HOME
1: OCPP
2: standard / solar
 | ocppUrl  | Interger  | Optional
 | OCPP url, up to 128 bytes, only works when the chargerScene parameter is 1
 | ocppChargerId  | Interger  | Optional
 | OCPP ChargerId, up to 25 bytes, only valid when the chargerScene parameter is 1
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
  "code": 10000,
  "message": "success",
  "result": {
    "C1******01": {
      "status": 3
    }
  },
  "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### EV Charger QR Code Setting

**`POST /openapi/v2/device/evc_control/set_evc_qr_code`**

Batch set the QR code displayed on the EV Charger.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
    "snList": [
        "C1******01"
    ],
    "qrCode": "www.solaxcloud.com",
    "businessType": 4
}
 | Device SN collection, minimum 1, maximum 10
 | qrCode  | String  | Required
 | EV Charger QR code string content (maximum 255 bytes)
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
  "code": 10000,
  "message": "success",
  "result": {
    "C1******01": {
      "status": 3
    }
  },
  "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### EV Charger Work Mode Setting

**`POST /openapi/v2/device/evc_control/set_evc_work_mode`**

Batch set the working mode of EV Charger.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
    "snList": [
        "C1******01"
    ],
    "workMode": 2,
    "currentGear": 16,
    "businessType": 4
}
 | Device SN collection, minimum 1, maximum 10
 | workMode  | Interger  | Required
 | Ev Charger work mode
0: STOP
1: FAST
2: ECO
3: GREEN
 | current  | Interger  | Optional
 | Current setting
No need to set when workMode is STOP
No need to set when workMode is FAST
Range when workMode is GREEN: 3, 6
Range when workMode is ECO: 6, 10, 16, 20, 25
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
  "code": 10000,
  "message": "success",
  "result": {
    "C1******01": {
      "status": 3
    }
  },
  "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### EV Charger Start Mode Setting

**`POST /openapi/v2/device/evc_control/set_evc_start_mode`**

Batch set the Start Mode of EV Charger.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
    "snList": [
        "C1******01"
    ],
    "startMode": 2,
    "businessType": 1
}
 | Device SN collection, minimum 1, maximum 10
 | startMode  | Interger  | Required
 | Start Mode
0: Plug and charge
1: Swipe card
2: APP
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
  "code": 10000,
  "message": "success",
  "result": {
    "C1******01": {
      "status": 3
    }
  },
  "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### EV Charger Charging and Discharging Settings

**`POST /openapi/v2/device/evc_control/set_evc_charge_command`**

Batch set EV Charger charging and discharging.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
    "snList": [
        "C1******01"
    ],
    "workMode": 2,
    "businessType": 1
}
 | Device SN collection, minimum 1, maximum 10
 | workCmd  | Interger  | Required
 | Control command
0: Lock
1: Available
2: Start charging
3: Stop charging
Locking post command only takes effect when the charging pile is unplugged.
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
  "code": 10000,
  "message": "success",
  "result": {
    "C1******01": {
      "status": 3
    }
  },
  "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### Schedule Charging

**`POST /openapi/v2/device/evc_control/set_evc_reserve_charge`**

Batch set EV Charger to schedule charging.

Only in the solar or standard scenario, when the startup method is [APP], [Schedule Charging] is supported. The other two startup methods, "Plug and charge" and "Swipe card," do not support "Schedule Charging."If the start time is set to be later than the end time, it will be processed as a cross-day task, and the execution mode will be "Only Once," meaning it will only trigger once. When the start time is set to be later than the end time, only X1/X3-HAC will recognize this setting, resulting in a 24-hour charging duration. The setting of 00:00 to 00:00 is not allowed, and the execution mode will be "Only Once," meaning it will only trigger once.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
    "snList": [
        "C1******01"
    ],
    "chargeStartTime": "09:00",
    "chargeEndTime": "10:00",
    "chargeCurrent": 16,
    "businessType": 4
}
 | Device SN collection, minimum 1, maximum 10
 | chargeStartTime  | String  | Required
 | Charging start time, hh:mm (hour [0-23], minute [0-59])
 | chargeEndTime  | String  | Required
 | Charging end time, hh:mm (hour [0-23], minute [0-59])
 | chargeCurrent  | Interger  | Required
 | Charging current
7kw and 22kw range: [6,32]
11kw range: [6,16]
6kw range: [6,30]
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
  "code": 10000,
  "message": "success",
  "result": {
    "C1******01": {
      "status": 3
    }
  },
  "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### EV Charger current limit setting

**`POST /openapi/v2/device/evc_control/set_evc_current_limit`**

Batch set the current limit of EV Charger.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
    "snList": [
        "C1******01"
    ],
    "currentLimit": 16,
    "businessType": 4
}
 | Device SN collection, minimum 1, maximum 10
 | currentLimit  | Interger  | Required
 | Current limit
4.6kW range: [6-20]
6kW : [6-30]
7kW、7.6kW、22kW range:[6,32]
9.6kW range: [6,40]
11kW range: [6,16]
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
  "code": 10000,
  "message": "success",
  "result": {
    "C1******01": {
      "status": 3
    }
  },
  "requestId": "66******66"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

## Heat Pump Functions

For the heat pump, a dedicated set of APIs is provided for data retrieval and control.

### Query Heat Pump Real-time Data

**`POST /openapi/v2/device/heat_pump/data/realtime`**

Query real-time data from the heat pump

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
    "businessType": 4,
    "snList": [
        "B0********0B"
    ]
}
 | Device SN collection, minimum 1, maximum 10
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial. Currently only supports Residential.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "result": [
        {
            "registerNo": "RE****01",
            "deviceSn": "B0********0B",
            "dataTime": "2026-06-23T07:29:09Z",
            "plantLocalTime": "2026-06-23T15:29:09+08:00",
            "backWaterTmp": null,
            "consumePower": 10.0,
            "currentCOP": null,
            "currentOutputPower": null,
            "dhwEnable": 1,
            "dhwZoneTemp": 33.0,
            "envirTmp": null,
            "hotWaterTankTmp": null,
            "outWaterTmp": 0.0,
            "todayColdConsumption": 0.0,
            "todayColdCOP": 0.0,
            "todayColdQty": 0.0,
            "todayElecConsumption": 0.0,
            "todayHeatConsumption": 0.0,
            "todayHeatCOP": 0.0,
            "todayHeatQty": 0.0,
            "todayHeatWaterCOP": 0.0,
            "todayHeatWaterQty": 0.0,
            "todayWaterConsumption": 0.0,
            "totalColdConsumption": 0.0,
            "totalColdCOP": 0.0,
            "totalColdQty": 0.0,
            "totalElecConsumption": 0.0,
            "totalHeatConsumption": 0.0,
            "totalHeatCOP": 0.0,
            "totalHeatQty": 0.0,
            "totalHeatWaterCOP": 0.0,
            "totalHeatWaterQty": 0.0,
            "totalWaterConsumption": 0.0,
            "zone": "A,B1",
            "zoneAAcMode": "2",
            "zoneAEnable": 0,
            "zoneASetTemp": 35.0,
            "zoneB1AcMode": "2",
            "zoneB1Enable": 0,
            "zoneB1SetTemp": 26.0,
            "zoneB2AcMode": null,
            "zoneB2Enable": null,
            "zoneB2SetTemp": null
        }
    ],
    "traceId": "d987******234"
}
 | message  | String  | Error Message
 | traceId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | result  | Data  | Specific data
 | Result description
 | registerNo  | String  | Device registration number
 | deviceSn  | String  | Device SN
 | dataTime  | String  | Data reporting time (UTC time)
 | plantLocalTime  | String  | Data reporting time (Plant local time)
 | envirTmp  | BigDecimal  | Real-time ambient temperature, unit: °C
 | hotWaterTankTmp  | BigDecimal  | Current domestic hot water temperature, unit: °C
 | outWaterTmp  | BigDecimal  | Current flow temperature, unit: ℃
 | backWaterTmp  | BigDecimal  | Current return water temperature, unit: ℃
 | consumePower  | BigDecimal  | Current consume power, unit: kW
 | currentOutputPower  | BigDecimal  | Current output power, unit: kW
 | todayElecConsumption  | BigDecimal  | Daily energy consumption, unit: kWh
 | todayHeatConsumption  | BigDecimal  | Daily heating energy consumption, unit: kWh
 | todayColdConsumption  | BigDecimal  | Daily coding energy consumption, unit: kWh
 | todayWaterConsumption  | BigDecimal  | Daily domestic hot water energy consumption, unit: kWh
 | todayHeatQty  | BigDecimal  | Daily heating output, unit: kWh
 | todayColdQty  | BigDecimal  | Daily coding output, unit: kWh
 | todayHeatWaterQty  | BigDecimal  | Daily domestic hot water output, unit: kWh
 | totalElecConsumption  | BigDecimal  | Total energy consumption, unit: kWh
 | totalHeatConsumption  | BigDecimal  | Total heating energy consumption, unit: kWh
 | totalColdConsumption  | BigDecimal  | Total coding energy consumption, unit: kWh
 | totalWaterConsumption  | BigDecimal  | Total domestic hot water energy consumption, unit: kWh
 | totalHeatQty  | BigDecimal  | Total heating output, unit: kWh
 | totalColdQty  | BigDecimal  | Total coding output, unit: kWh
 | totalHeatWaterQty  | BigDecimal  | Total domestic hot water output, unit: kWh
 | currentCOP  | BigDecimal  | Current COP (Coefficient Of Performance)
 | todayHeatCOP  | BigDecimal  | Daily heating COP
 | todayColdCOP  | BigDecimal  | Daily coding COP
 | todayHeatWaterCOP  | BigDecimal  | Daily domestic hot water COP
 | totalHeatCOP  | BigDecimal  | Total heating COP
 | totalColdCOP  | BigDecimal  | Total coding COP
 | totalHeatWaterCOP  | BigDecimal  | Total domestic hot water COP
 | dhwEnable  | BigDecimal  | Domestic hot water switch, 0=off，1=on
 | dhwZoneTemp  | BigDecimal  | Domestic hot water temperature setting, unit: ℃
 | zone  | BigDecimal  | Effective area marking; content includes A, B1, and B2.
 | zoneAEnable  | BigDecimal  | Zone A control switch, 0 = Off, 1 = On
 | zoneAAcMode  | BigDecimal  | Zone A cooling/heating setting: 1 = cooling, 2 = heating.
 | zoneASetTemp  | BigDecimal  | Temperature set for Zone A, unit: °C
 | zoneB1Enable  | BigDecimal  | Zone B1 control switch, 0 = Off, 1 = On
 | zoneB1AcMode  | BigDecimal  | Zone B1 cooling/heating setting: 1 = cooling, 2 = heating.
 | zoneB1SetTemp  | BigDecimal  | Temperature set for Zone B1, unit: °C
 | zoneB2Enable  | BigDecimal  | Zone B2 control switch, 0 = Off, 1 = On
 | zoneB2AcMode  | BigDecimal  | Zone B2 cooling/heating setting: 1 = cooling, 2 = heating.
 | zoneB2SetTemp  | BigDecimal  | Temperature set for Zone B2, unit: °C
```

### Room Temperature Setting

**`POST /openapi/v2/device/control/heat_pump/room_temp`**

Control the room temperature of the heat pump zone.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
    "snList": [
        "B0******0B"
    ],
    "businessType": 1,
    "zone": 1,
    "enable": 1,
    "mode": 1,
    "heatSetTemp": 27,
    "coolSetTemp": 23
}
 | Device SN collection, minimum 1, maximum 10
 | zone  | Interger  | Required
 | Target zone for control, 1=A, 2=B1, 3=B2
 | enable  | Interger  | Required
 | Function switche, 0=off，1=on
 | mode  | Interger  | Optional
 | Function mode; active when enabled=1. 1 = Cooling, 2 = Heating, 3 = Auto.
 | heatSetTemp  | Interger  | Optional
 | Heating water outlet temperature setting, active when enable=1; range: 25–80°C; unit: °C.
 | coolSetTemp  | Interger  | Optional
 | Coding water outlet temperature setting, active when enable=1; range: 25–80°C; unit: °C.
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial. Currently only supports Residential.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "result": {
        "B0******0B": {
            "status": 3
        }
    },
    "requestId": "641983923496546",
    "message": "success",
    "traceId": "9029e422-b537-435b-8f9c-f888a05bc0f1.1779106008122"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | traceId  | String  | The traceId recorded by the platform for this request, used for tracing and troubleshooting issues.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### Water Temperature Setting

**`POST /openapi/v2/device/control/heat_pump/water_temp`**

Control the water temperature of the heat pump zone.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
    "snList": [
        "B0******0B"
    ],
    "businessType": 1,
    "zone": 1,
    "enable": 1,
    "mode": 1,
    "heatSetTemp": 27,
    "coolSetTemp": 23
}
 | Device SN collection, minimum 1, maximum 10
 | zone  | Interger  | Required
 | Target zone for control, 1=A, 2=B1, 3=B2
 | enable  | Interger  | Required
 | Function switche, 0=off，1=on
 | mode  | Interger  | Optional
 | Function mode; active when enabled=1. 1 = Cooling, 2 = Heating, 3 = Auto.
 | heatSetTemp  | Interger  | Optional
 | Heating water outlet temperature setting, active when enable=1; range: 25–80°C; unit: °C.
 | coolSetTemp  | Interger  | Optional
 | Coding water outlet temperature setting, active when enable=1; range: 25–80°C; unit: °C.
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial. Currently only supports Residential.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "result": {
        "B0******0B": {
            "status": 3
        }
    },
    "requestId": "641983923496546",
    "message": "success",
    "traceId": "9029e422-b537-435b-8f9c-f888a05bc0f1.1779106008122"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | traceId  | String  | The traceId recorded by the platform for this request, used for tracing and troubleshooting issues.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### Domestic Hot Water Temperature Setting

**`POST /openapi/v2/device/control/heat_pump/hot_water`**

Control the Domestic hot water temperature of the heat pump.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
    "snList": [
        "B0******0B"
    ],
    "businessType": 1,
    "enable": 1,
    "setTemp": 27
}
 | Device SN collection, minimum 1, maximum 10
 | enable  | Interger  | Required
 | Function switche, 0=off，1=on
 | setTemp  | Interger  | Required
 | Domestic hot water temperature setting, required and active when enable=1; range: 25–80°C; unit: °C.
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial. Currently only supports Residential.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "result": {
        "B0******0B": {
            "status": 3
        }
    },
    "requestId": "641983923496546",
    "message": "success",
    "traceId": "9029e422-b537-435b-8f9c-f888a05bc0f1.1779106008122"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | traceId  | String  | The traceId recorded by the platform for this request, used for tracing and troubleshooting issues.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### Silent Mode Control

**`POST /openapi/v2/device/control/heat_pump/quiet_mode`**

Silent mode control.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
    "snList": [
        "B0******0B"
    ],
    "businessType": 1,
    "enable": 1,
    "quietLevel": 0,
    "startTime": "22:00",
    "endTime": "23:00"
}
 | Device SN collection, minimum 1, maximum 10
 | enable  | Interger  | Required
 | Function switche, 0=off，1=on
 | quietLevel  | Interger  | Optional
 | Mute level; required and effective when enable=1. 0: Level 1; 1: Level 2.
 | startTime  | String  | Optional
 | Mode start time, required and effective when enable=1; format: HH:mm.
 | endTime  | String  | Optional
 | Mode end time, required and effective when enable=1; format: HH:mm.
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial. Currently only supports Residential.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "result": {
        "B0******0B": {
            "status": 3
        }
    },
    "requestId": "641983923496546",
    "message": "success",
    "traceId": "9029e422-b537-435b-8f9c-f888a05bc0f1.1779106008122"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | traceId  | String  | The traceId recorded by the platform for this request, used for tracing and troubleshooting issues.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### Sterilization Mode Control

**`POST /openapi/v2/device/control/heat_pump/sterilization`**

Sterilization mode control.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
    "snList": [
        "B0******0B"
    ],
    "businessType": 1,
    "enable": 1,
    "startTime": "23:00",
    "weekCycle": "0101010"
}
 | Device SN collection, minimum 1, maximum 10
 | enable  | Interger  | Required
 | Function switche, 0=off，1=on
 | startTime  | String  | Optional
 | Mode start time, required and effective when enable=1; format: HH:mm.
 | weekCycle  | String  | Optional
 | Weekly recurrence indicator; required and effective when enable=1. It is a 7-character string representing the days from Sunday to Saturday (left to right), where '0' indicates no recurrence and '1' indicates recurrence. Example: `0101010`.
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial. Currently only supports Residential.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "result": {
        "B0******0B": {
            "status": 3
        }
    },
    "requestId": "641983923496546",
    "message": "success",
    "traceId": "9029e422-b537-435b-8f9c-f888a05bc0f1.1779106008122"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | traceId  | String  | The traceId recorded by the platform for this request, used for tracing and troubleshooting issues.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### Return Water Switch Control

**`POST /openapi/v2/device/control/heat_pump/back_water`**

Return water switch control.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | snList  | List  | Required  | {
    "snList": [
        "B0******0B"
    ],
    "businessType": 1,
    "enable": 1,
    "startTimeList": [
        "06:00",
        "18:00"
    ]
}
 | Device SN collection, minimum 1, maximum 10
 | enable  | Interger  | Required
 | Function switche, 0=off，1=on
 | startTimeList  | Map  | Optional
 | List of start times; required and effective when enable=1. Minimum of 1, maximum of 12 entries; format for each: HH:mm. Example: ["06:00", "18:00"]
 | businessType  | Interger  | Required
 | Business type, 1: Residential, 4: Commercial & Industrial. Currently only supports Residential.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "result": {
        "B0******0B": {
            "status": 3
        }
    },
    "requestId": "641983923496546",
    "message": "success",
    "traceId": "9029e422-b537-435b-8f9c-f888a05bc0f1.1779106008122"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | traceId  | String  | The traceId recorded by the platform for this request, used for tracing and troubleshooting issues.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

### Power Mode Settings

**`POST /openapi/v2/device/control/heat_pump/power_mode`**

Power Mode Settings.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | businessType  | Interger  | Required  | {
    "businessType": 1,
    "paramList": [
        {
            "snList": [
                "B0********0B"
            ],
            "powerMode": 1
        }
    ]
}
 | Business type, 1: Residential, 4: Commercial & Industrial. Currently only supports Residential.
 | paramList  | Interger  | Required
 | Set parameter list
 | paramList description
 | snList  | List  | Required
 | Device SN collection, minimum 1, maximum 10
 | powerMode  | Interger  | Required
 | Power Mode, 0=Standard, 1=Turbo, 2=Ecomonical, 3=Automatic
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "result": {
        "B0******0B": {
            "status": 3
        }
    },
    "requestId": "641983923496546",
    "message": "success",
    "traceId": "9029e422-b537-435b-8f9c-f888a05bc0f1.1779106008122"
}
 | message  | String  | Error Message
 | requestId  | String  | The ID recorded by the platform for this request, used to query the request result.
 | traceId  | String  | The traceId recorded by the platform for this request, used for tracing and troubleshooting issues.
 | result  | List  | Specific data
 | Result description
 | Device SN  | String  | Device SN
 | status  | Interger  | Device command delivery result, for detailed information on the specific values, please refer to "Appendix 8: Description and definition of the status field of the device command delivery result"
```

## Data Subscription Feature

This module allows users to subscribe to the device's real-time data.

### Query Subscription

**`POST /openapi/v2/device/subs/query`**

View my added subscriptions

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | businessType  | Interger  | Required  | {
    "subId": "",
    "businessType": 1,
    "dataType": 1,
    "deviceSn": ""
}
 | Business type, 1: Residential, 4: Commercial & Industrial.
 | subId  | String  | Optional
 | Data subscription Id
 | dataType  | Interger  | Optional
 | Data types: 1. Real-time data; 2. Alarm data; 3. 10 seconds level data.
 | deviceSn  | String  | Optional
 | Device SN to subscribe
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | Integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "message": "success",
    "result": [
        {
            "subId": "697158996499617",
            "dataPushName": "dataPushName",
            "businessType": 1,
            "dataType": 1,
            "dataTemplate": "Residential_Realtime",
            "pushType": "http",
            "pushAddress": "https://www.solaxpower.com/dataPush/port",
            "remainingCount": 0,
            "utcCreateTime": "2026-08-04T11:00:47Z"
        }
    ],
    "traceId": "71ba1b49-595e-441d-9327-e20c0b5519b9.1785841306633"
}
 | message  | String  | Error Message
 | traceId  | String  | The traceId recorded by the platform for this request, used for tracing and troubleshooting issues.
 | result  | List  | Specific data
 | Result description
 | subId  | String  | Subscription Id
 | dataPushName  | String  | Subscription name
 | businessType  | Interger  | Business type, 1: Residential, 4: Commercial & Industrial.
 | dataType  | Interger  | Data types: 1. Real-time data; 2. Alarm data; 3. 10 seconds level data.
 | dataTemplate  | String  | Push template code
 | pushType  | String  | Push Method (HTTP, Kafka)
 | pushAddress  | String  | Data push URL
 | remainingCount  | String  | Remaining push quota for this subscription
 | utcCreateTime  | String  | Push creation time, UTC time
```

### Add New HTTP Subscription

**`POST /openapi/v2/device/subs/http/create`**

Add new HTTP subscription.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | businessType  | Interger  | Required  | {
    "snList": [
        "X3******01"
    ],
    "dataPushName": "dataPushName",
    "businessType": 1,
    "dataType": 1,
    "deviceType": 1,
    "dataTemplate": "Residential_Realtime",
    "pushAddress": "https://www.solaxpower.com/dataPush/port"
}
 | Business type, 1: Residential, 4: Commercial & Industrial.
 | snList  | List  | Optional
 | List of subscribed device SN, maximum 500
 | datapushName  | String  | Optional
 | Subscription name. If left blank, the subscription ID will be used as the name upon creation.
 | dataType  | Interger  | Required
 | Data types: 1. Real-time data; 2. Alarm data; 3. 10 seconds level data.
 | deviceType  | Interger  | Required
 | Device Type, for detailed information on the specific values, please refer to "Appendix 3: Description and definition of the deviceType field". Currently supports only inverters and EV Chargers.
 | dataTemplate  | String  | Required
 | Push template code
Residential_Realtime: Inverter real-time data push - Residential
CI_Realtime: Inverter real-time data push - C&I
 | pushAddress  | String  | Required
 | Data push URL
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | Integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "message": "success",
    "result": {
        "subId": "679374650542658",
        "dataPushName": "679374650542658",
        "businessType": 1,
        "dataType": 1,
        "deviceType": 1,
        "pushType": "http",
        "dataTemplate": "Residential_Realtime",
        "remainingCount": 1000000000,
        "snList": [
            "X3******01"
        ],
        "utcCreateTime": "2026-07-10T07:58:25.897Z",
        "pushAddress": "https://www.solaxpower.com/dataPush/port"
    },
    "traceId": "81f03d22-6111-46aa-acc3-360ec14b0df2.1783670305765"
}
 | message  | String  | Error Message
 | traceId  | String  | The traceId recorded by the platform for this request, used for tracing and troubleshooting issues.
 | result  | List  | Specific data
 | Result description
 | subId  | String  | Subscription Id
 | dataPushName  | String  | Subscription name
 | businessType  | Interger  | Business type, 1: Residential, 4: Commercial & Industrial.
 | dataType  | Interger  | Data types: 1. Real-time data; 2. Alarm data; 3. 10 seconds level data.
 | dataTemplate  | String  | Push template code
 | pushType  | String  | Push Method (HTTP, Kafka)
 | pushAddress  | String  | Data push URL
 | remainingCount  | Interger  | Remaining push quota for this subscription
 | snList  | List  | List of subscribed device SN
 | utcCreateTime  | String  | Push creation time, UTC time
```

### Delete Subscription

**`POST /openapi/v2/device/subs/delete`**

Delete specific subscription.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | businessType  | Interger  | Required  | {
    "businessType": 1,
    "dataType": 1,
    "subId": "697158996499617"
}
 | Business type, 1: Residential, 4: Commercial & Industrial.
 | dataType  | Interger  | Required
 | Data types: 1. Real-time data; 2. Alarm data; 3. 10 seconds level data.
 | subId  | String  | Required
 | Subscription Id
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | Integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "message": "success",
    "result": null,
    "traceId": "a24b51e4-b4aa-497f-9498-eb0347c8a58c.1785841536813"
}
 | message  | String  | Error Message
 | traceId  | String  | The traceId recorded by the platform for this request, used for tracing and troubleshooting issues.
 | result  | Object  | Specific data, this endpoint returns null
```

### Query Devices within Subscription

**`POST /openapi/v2/device/subs/query_devices`**

Query specific devices within the subscription.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | businessType  | Interger  | Required  | {
    "pageNo": 1,
    "pageSize": 10,
    "subId": "697158996499617",
    "businessType": 1,
    "dataType":1
}
 | Business type, 1: Residential, 4: Commercial & Industrial.
 | dataType  | Interger  | Required
 | Data types: 1. Real-time data; 2. Alarm data; 3. 10 seconds level data.
 | subId  | String  | Required
 | Subscription Id
 | pageNo  | Interger  | Optional
 | Query page number; defaults to 1 if left blank.
 | pageSize  | Interger  | Optional
 | Items per page; defaults to 10 if left blank, maximum 500
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | Integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "message": "success",
    "result": {
        "total": 1,
        "pages": 1,
        "current": 1,
        "records": [
            "X3******01"
        ],
        "size": 10
    },
    "traceId": "d1e627c6-8ad1-477e-b9af-0ffcb7edb0ae.1785841504853"
}
 | message  | String  | Error Message
 | traceId  | String  | The traceId recorded by the platform for this request, used for tracing and troubleshooting issues.
 | result  | List  | Specific data
 | Result description
 | total  | Interger  | Total number of devices in the subscription
 | pages  | Interger  | Total pages
 | size  | Interger  | Items per page
 | current  | Interger  | Current page (Query page number)
 | records  | List  | Set of specific subscribed device SN
```

### Add Devices to Subscription

**`POST /openapi/v2/device/subs/add_devices`**

Add devices to a specific subscription.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | businessType  | Interger  | Required  | {
    "snList": [
        "X3******01"
    ],
    "subId": "697158996499617",
    "businessType": 1,
    "dataType": 1,
    "deviceType": 1
}
 | Business type, 1: Residential, 4: Commercial & Industrial.
 | dataType  | Interger  | Required
 | Data types: 1. Real-time data; 2. Alarm data; 3. 10 seconds level data.
 | deviceType  | Interger  | Required
 | Device Type, for detailed information on the specific values, please refer to "Appendix 3: Description and definition of the deviceType field". Currently supports only inverters and EV Chargers.
 | subId  | String  | Required
 | Subscription Id
 | snList  | List  | Required
 | Device SN, minimum 1, maximum 500, multiple device SNs separated by commas.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | Integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "message": "success",
    "result": null,
    "traceId": "0f859e01-aad7-4864-ab89-f765ab3c711e.1785841372511"
}
 | message  | String  | Error Message
 | traceId  | String  | The traceId recorded by the platform for this request, used for tracing and troubleshooting issues.
 | result  | Object  | Specific data, this endpoint returns null
```

### Remove Devices from Subscription

**`POST /openapi/v2/device/subs/del_devices`**

Remove devices from a subscription.

#### Request Fields

```text
 | Body Parameters application/json  | Example
 | businessType  | Interger  | Required  | {
    "businessType": 1,
    "dataType": 1,
    "snList": [
        "X3******01"
    ],
    "subId": "697158996499617"
}
 | Business type, 1: Residential, 4: Commercial & Industrial.
 | dataType  | Interger  | Required
 | Data types: 1. Real-time data; 2. Alarm data; 3. 10 seconds level data.
 | subId  | String  | Required
 | Subscription Id
 | snList  | List  | Required
 | Device SN, minimum 1, maximum 500, multiple device SNs separated by commas.
```

#### Response fields

```text
 | Parameter Name  | Parameter Type  | Parameter Description  | Example
 | code  | Integer  | 10000 indicates success, non-10000 indicates failure  | {
    "code": 10000,
    "message": "success",
    "result": "Successfully deleted 1 device",
    "traceId": "11e4d915-529d-4439-9fb2-4bffda7997a9.1785841456480"
}
 | message  | String  | Error Message
 | traceId  | String  | The traceId recorded by the platform for this request, used for tracing and troubleshooting issues.
 | result  | String  | Specific data
```

## Attachment

### Appendix 1: Return Content Definition

#### Description and definition of the code field in the returned content of the interface call

```text
 | code  | Description（CN）  | Description（EN）
 | 10000  | 操作成功  | Operation successful
 | 10001  | 操作失败  | Operation failed
 | 11500  | 系统异常，请稍后重试  | System busy, please try again later
 | 10200  | 操作异常，详见具体message内容  | Operation abnormality, please see the specific message content for details
 | 10400  | 请求非法  | Request not authenticated
 | 10401  | 用户名密码错误  | Username or password incorrect
 | 10402  | 请求access_token校验失败  | Request access_token authentication failed
 | 10403  | 接口无访问权限  | Interface has no access rights
 | 10404  | 回调地址未配置  | Callback function not configured
 | 10405  | API调用次数已使用完  | The number of API calls has been used up
 | 10406  | API调用频率已达上限，请稍后再试  | The API call rate has reached the upper limit, please try again later
 | 10500  | 设备无权限  | User has no device data permission
 | 10505  | 设备未授权  | Device unauthorized
 | 10506  | 电站未授权  | Plant unauthorized
```

#### Description and definition of the error_code field in the response returned to the redirect URL upon OAuth 2.0 authorization failure

```text
 | error_code  | Description（CN）  | Description（EN）
 | 11500  | 系统异常，请稍后重试  | System busy, please try again later
 | 11601  | 用户拒绝  | User authorization cancelled
 | 11610  | VPP设备检验失败  | VPP device verification failed
 | 11611  | VPP设备检验失败-dongle类型不支持  | VPP device verification failed: Unsupported dongle type
 | 11612  | VPP设备检验失败-dongle版本过低  | VPP device verification failed: Dongle version too low
 | 11613  | VPP设备检验失败-逆变器类型不支持  | VPP device verification failed: Unsupported inverter type
 | 11614  | VPP设备检验失败-ARM、DSP版本过低  | VPP device verification failed: Inverter ARM and DSP version too low
 | 11615  | VPP设备检验失败-dongle类型不支持，逆变器类型不支持  | VPP device verification failed: Unsupported dongle type and inverter type
 | 11616  | VPP设备检验失败-dongle类型不支持，ARM、DSP版本过低  | VPP device verification failed: Unsupported dongle type; Inverter ARM and DSP version too low
 | 11617  | VPP设备检验失败-dongle版本过低，逆变器类型不支持  | VPP device verification failed: Dongle version too low; unsupported inverter type
 | 11618  | VPP设备检验失败-dongle版本过低，ARM、DSP版本过低  | VPP device verification failed: Dongle version too low; Inverter ARM and DSP version too low
```

### Appendix 2: Description and definition of the plantState field

```text
 | Value of businessType  | Value of plantState  | Description (CN)  | Description (EN)
 | 1--Residential  | 0  | 接入中  | Connecting
 | 1  | 离线  | Offline
 | >1  | 在线  | Online
 | 4--Commercial & Industrial  | 0  | 离线  | Offline
 | 1  | 正常  | Normal
 | 2  | 紧急告警  | Failure
 | 3  | 一般告警  | Warning
 | 4  | 接入中  | Connecting
```

### Appendix 3: Description and definition of the deviceType field

```text
 | Value of deviceType  | Device Type Name (CN)  | Device Type Name (EN)
 | 1  | 逆变器  | Inverter
 | 2  | 电池  | Battery
 | 3  | 电表  | Meter
 | 4  | 充电桩  | EV Charger
 | 100  | EMS系统  | EMS System
```

### Appendix 4: Description and definition of the deviceModel field

#### Description of the deviceModel field for Residential business (businessType=1)

```text
 | Value of businessType  | Value of deviceType  | Value of deviceModel  | Device Model Description
 | 1--Residential  | 1--Inverter  | 1  | X1-LX
 | 2  | X-Hybrid
 | 3  | X1-Hybrid-G3
 | 4  | X1-Boost/Air/Mini
 | 5  | X3-Hybrid-G1/G2
 | 6  | X3-20K/30K
 | 7  | X3-MIC/PRO
 | 8  | X1-Smart
 | 9  | X1-AC
 | 10  | A1-Hybrid
 | 11  | A1-FIT
 | 12  | A1
 | 13  | J1-ESS
 | 14  | X3-Hybrid-G4
 | 15  | X1-Hybrid-G4
 | 16  | X3-MIC/PRO-G2
 | 17  | X1-SPT
 | 18  | X1-Boost-G4
 | 19  | A1-HYB-G2
 | 20  | A1-AC-G2
 | 21  | A1-SMT-G2
 | 22  | X1-Mini-G4
 | 23  | X1-IES
 | 24  | X3-IES
 | 25  | X3-ULT
 | 26  | X1-SMART-G2
 | 27  | A1-Micro 1 in 1
 | 28  | X1-Micro 2 in 1
 | 29  | X1-Micro 4 in 1
 | 31  | X3-AELIO
 | 32  | X3-HYB-G4 PRO
 | 33  | X3-NEO-LV
 | 34  | X1-VAST
 | 35  | X3-IES-P
 | 36  | J3-ULT-LV-16.5K
 | 37  | J3-ULT-30K
 | 38  | J1-ESS-HB-2
 | 39  | C3-IES
 | 40  | X3-IES-A
 | 41  | X1-IES-A
 | 43  | X3-ULT-GLV
 | 44  | X1-MINI-G4 PLUS
 | 46  | X1-Reno-LV
 | 47  | A1-HYB-G3
 | 48  | X1-Micro 4 in 1G2
 | 49  | X1-Micro 2 in 1G2
 | 50  | X-MS 2700
 | 64  | OG
 | 66  | X1-SPT-10K/12K
 | 67  | LVE
 | 69  | AEGIS
 | 70  | X3-AELIO(LA)
 | 100  | X3-FTH
 | 101  | X3-MGA-G2
 | 102  | X1-Hybrid-LV
 | 103  | X1-Lite-LV
 | 104  | X3-GRAND-HV
 | 105  | X3-FORTH-PLUS
 | 108  | X3-MIC-G3
 | 109  | X3-PRO-G3
 | 2--Battery  | 81  | BAK
 | 82  | REP
 | 83  | SIN
 | 84  | GOT
 | 840  | GOT-T30
 | 85  | TP001
 | 86  | TP002
 | 87  | TP003
 | 88  | TP004
 | 89  | TP005
 | 90  | TP006
 | 91  | TP007
 | 92  | TP008
 | 93  | TP009
 | 94  | TP010
 | 95  | TP011
 | 96  | TP012
 | 97  | TP013
 | 99  | TP015
 | 100  | TP016
 | 101  | TP017
 | 102  | TP018
 | 103  | TP019
 | 104  | TP020
 | 105  | TP021
 | 106  | TP022
 | 107  | TP023
 | 129  | T58-P1
 | 130  | TP201
 | 131  | TP202
 | 132  | TP203
 | 140  | TP211
 | 141  | TP212
 | 1411  | TP212-T30
 | 145  | TSYS-HS51
 | 146  | TP062
 | 161  | TR-HR140
 | 163  | TR-HR140
 | 167  | TPCU007
 | 3--Meter  | 50  | Meter X
 | 176  | M1-40
 | 178  | M3-40
 | 179  | M3-40-Dual
 | 181  | M3-40-Wide
 | 4--EV Charger  | 1  | X1/X3-EVC
 | 2  | X1/X3-EVC G1.1
 | 3  | X1/X3-HAC
 | 4  | J1-EVC
 | 5  | A1-HAC
 | 6  | C1/C3-HAC
 | 6--Heat Pump  | 1  | STU-R290
 | 10  | ALT-SF0
```

#### Description of the deviceModel field for Commercial & Industrial business (businessType=4)

```text
 | Value of businessType  | Value of deviceType  | Value of deviceModel  | Device Model Description
 | 4--Commercial & Industrial  | 1--Inverter  | 1  | X3-AELIO
 | 2  | X3-TRENE-100KI
 | 3  | X3-TRENE-100K
 | 4  | X3-TRENE
 | 16  | X3-PRO G2
 | 31  | X3-AELIO
 | 42  | X3-AELIO
 | 100  | X3-FORTH
 | 101  | X3-MEGA G2
 | 104  | X3-GRAND
 | 105  | X3-FORTH PLUS
 | 2--Battery  | 1  | TB-HR140
 | 2  | TB-HR522
 | 145  | TSYS-HS51
 | 163  | TR-HR140
 | 3--Meter  | 0~3  | DTSU666-CT
 | 4~5  | Wi-BR DTSU666-CT
 | 6  | CT
 | 7  | DTSU666-CT
 | 8  | UMG 103-CBM
 | 9  | M3-40-Dual
 | 10  | M3-40
 | 11  | PRISMA-310A
 | 4--EV Charger  | 1  | X1/X3-EVC
 | 2  | X1/X3-EVC G1.1
 | 3  | X1/X3-HAC
 | 4  | J1-EVC
 | 5  | A1-HAC
 | 6  | C1/C3-HAC
```

### Appendix 5: Description and definition of the flag field for the device parallel operation status

```text
 | Value of businessType  | Value of flag  | Description (CN)  | Description (EN)
 | 1--Residential  | 0  | 未并机  | Not in parallel
 | 1  | 主机  | Master
 | 2~4  | 从机1~3  | Slave 1~3
 | 4--Commercial & Industrial  | null  | 未并机  | Not in parallel
 | 0  | 主机  | Master
 | 1  | 从机  | Slave
```

### Appendix 6: Description and definition of the deviceStatus field

#### Description of the deviceStatus field for Inverter (deviceType=1)

```text
 | Value of deviceType  | Value of deviceStatus  | Description (CN)  | Description (EN)
 | 1--Inverter  | 100  | 等待运行  | Waiting
 | 101  | 自检  | Self-check
 | 102  | 正常运行  | Normal
 | 103  | 可恢复故障  | Fault
 | 104  | 永久性故障  | Permanent Fault Mode
 | 105  | 固件升级  | Update Mode
 | 106  | 离网检测  | EPS Check Mode
 | 107  | 离网  | EPS Mode
 | 108  | 自检测模式（意大利安规下）  | Self Test
 | 109  | 休眠模式  | Idle Mode
 | 110  | 待机模式  | Standby Mode
 | 111  | 光伏唤醒电池模式  | Pv Wake Up Bat Mode
 | 112  | 发电机检测模式  | Gen Check Mode
 | 113  | 发电机模式  | Gen run Mode
 | 114  | 快速关断待机模式  | RSD Standby
 | 130  | VPP 模式  | VPP mode
 | 131  | TOU-Self use  | TOU-Self use
 | 132  | TOU-Charging  | TOU-Charging
 | 133  | TOU-Discharging  | TOU-Discharging
 | 134  | TOU-Battery off  | TOU-Battery off
 | 135  | TOU-Peak Shaving  | TOU-Peak Shaving
 | 136  | 发电机正常运行模式  | Normal Mode(Gen)
 | 137  | 电池扩容模式  | Normal Mode(BAT-E)
 | 138  | 并网电池加热模式  | Normal Mode(BAT-H)
 | 139  | 离网电池加热模式  | EPS mode(BAT-H)
 | 140  | 启动模式  | Start Mode
 | 141  | Normal Mode(R-1)  | Normal Mode(R-1)
 | 142  | Normal Mode(R-2)  | Normal Mode(R-2)
 | 143  | Normal Mode(R-3)  | Normal Mode(R-3)
 | 144  | Normal Mode(R-4)  | Normal Mode(R-4)
 | 145  | Normal Mode(R-5)  | Normal Mode(R-5)
 | 146  | Normal Mode(R-6)  | Normal Mode(R-6)
 | 147  | Normal Mode(R-7)  | Normal Mode(R-7)
 | 150  | Self Use  | Self Use
 | 151  | Force Time Use  | Force Time Use
 | 152  | Back Up Mode  | Back Up Mode
 | 153  | Feedin Priority  | Feedin Priority
 | 154  | Demand Mode  | Demand Mode
 | 155  | ConstPowr Mode  | ConstPowr Mode
 | 160  | OpenAdr Mode  | OpenAdr Mode
 | 170  | STOP MODE  | STOP MODE
 | 171  | DEBUG MODE  | DEBUG MODE
 | 174  | Normal(Smart selfuse)  | Normal(Smart selfuse)
 | 175  | Normal(Smart feedin)  | Normal(Smart feedin)
 | 176  | Normal(Smart Bat not discharge)  | Normal(Smart Bat not discharge)
 | 177  | Normal(WLV 0%)  | Normal(WLV 0%)
 | 1301  | Power Control Mode  | Power Control Mode
 | 1302  | Electric Quantity Target Control Mode  | Electric Quantity Target Control Mode
 | 1303  | SOC Target Control Mode  | SOC Target Control Mode
 | 1304  | Push Power -Positive/Negative Mode  | Push Power -Positive/Negative Mode
 | 1305  | Push Power - Zero Mode  | Push Power - Zero Mode
 | 1306  | Self-Consume -Charge/Discharge Mode  | Self-Consume -Charge/Discharge Mode
 | 1307  | Self-Consume - Charge Only Mode  | Self-Consume - Charge Only Mode
 | 1308  | PV&BAT Individual Setting- Duration Mode  | PV&BAT Individual Setting- Duration Mode
 | 1309  | PV&BAT Individual Setting-Target SOC Mode  | PV&BAT Individual Setting-Target SOC Mode
```

#### Description of the deviceStatus field for Battery (deviceType=2)

```text
 | Value of deviceType  | Value of businessType  | Value of deviceStatus  | Description (CN)  | Description (EN)
 | 2--Battery  | 1--Residential  | 0  | Idle  | Idle
 | 1  | 工作  | Work
 | 4--Commercial & Industrial  | 0  | 放电状态自检  | Idle
 | 1  | 待机  | Standby
 | 2  | 放电预充  | Discharge Pre-Charge
 | 3  | 充电转放电预充  | Charge-to-discharge pre-charge
 | 4  | 放电工作状态  | Discharging
 | 5  | 放电工作模式故障  | Discharging Fault
 | 6  | 充电切换限流  | Charge switching current limit
 | 7  | 充电自检  | Charge Self-Test
 | 8  | 充电预充  | Charge Pre-Charge
 | 9  | 充电工作状态  | Charging
 | 10  | 充电工作模式故障  | Charging Fault
 | 11  | 下电状态  | Power Off Status
```

#### Description of the deviceStatus field for EV Charger (deviceType=4)

```text
 | Value of deviceType  | Value of deviceStatus  | Description (CN)  | Description (EN)
 | 4--EV Charger  | 0  | 可用  | Available
 | 1  | 准备  | Preparing
 | 2  | 充电  | Charging
 | 3  | 结束  | Finish
 | 4  | 故障  | Faulted
 | 5  | 不可用  | Unavailable
 | 6  | 预约  | Reserved
 | 7  | 车端暂停  | SuspendedEV
 | 8  | 桩端暂停  | SuspendedEVSE
 | 9  | 升级  | Update
 | 10  | 卡片激活  | CardActivation
 | 11  | 启动延时  | StartDelay
 | 12  | 充电暂停  | ChargPause
 | 13  | 停止中  | Stoping
```

### Appendix 7: Description and definition of the deviceWorkingMode field

Description of the deviceWorkingMode field for EV Charger (deviceType=4)

```text
 | Value of deviceType  | Value of deviceWorkingMode  | Description (CN)  | Description (EN)
 | 4--EV Charger  | 0  | STOP  | STOP
 | 1  | FAST  | FAST
 | 2  | ECO  | ECO
 | 3  | GREEN  | GREEN
```

### Appendix 8: Description and definition of the status field of the device command delivery result

### Overall Request Process State Machine Diagram

### Detailed explanation of the status field

```text
 | Value of status  | Description (CN)  | Description (EN)
 | 1  | 设备离线  | Device Offline
 | 2  | 指令下发失败  | Command issuance failed
 | 3  | 指令下发成功  | Command issuance succeeded
 | 4  | 设备返回收到并开始执行  | Device received and started execution
 | 5  | 设备执行失败  | Device execution failed
 | 6  | 执行超时  | Execution timed out
```

*Copyright 2025, SolaX Power. All Right Reserved.*

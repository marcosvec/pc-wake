# Set up your own

About 30-45 minutes. Everything you make here is yours: your own gist, token and secret. Nothing talks to anyone else's setup.

**You need**
- A Windows PC on Wi-Fi
- A Fire TV Stick on the same Wi-Fi, plugged into a wall adapter (not the TV's USB port)
- A GitHub account
- A computer to run ADB from (the PC itself is fine)

**Values you'll collect along the way** (keep them in a password manager, never in a repo)

| Name | Example | Where it comes from |
| --- | --- | --- |
| PC MAC | `AA:BB:CC:DD:EE:FF` | Step 1 |
| PC IP | `192.168.1.50` | Step 1 |
| Broadcast | `192.168.1.255` | Step 1 |
| Gist ID | `0123456789abcdef0123456789abcdef` | Step 2 |
| Token | `github_pat_...` | Step 3 |
| Secret | any long random string | Step 4 |

---

## 1. Check your PC can wake over Wi-Fi

Do this first. If it fails, nothing after it matters.

1. **BIOS:** turn on every "Resume by" / "Wake on LAN" option (onboard LAN, PCIe, networking device) and turn ErP off. On boards with built-in Wi-Fi, the onboard LAN option is usually the one that matters.
2. **Device Manager → Network adapters → your Wi-Fi adapter:**
   - Power Management: check all three boxes.
   - Advanced: set "Wake on Magic Packet" to Enabled.
3. **Settings → Network → Wi-Fi → your network:** set "Random hardware addresses" to Off. A sleeping card only listens for the address it was using.
4. **Get your values:** run `ipconfig /all` and find the Wi-Fi section.
   - "Physical Address" is your **PC MAC**.
   - "IPv4 Address" is your **PC IP**.
   - **Broadcast** is the PC IP with the last number swapped for 255 (on almost every home network).
5. **Let the relay see when the PC is awake.** Open PowerShell as admin and run:
   ```
   New-NetFirewallRule -DisplayName "PC Wake - allow ping from home network" -Direction Inbound -Protocol ICMPv4 -IcmpType 8 -Profile Private -RemoteAddress LocalSubnet -Action Allow
   ```
6. **Test it:** install any Wake-on-LAN app on your phone and add your PC MAC and broadcast. Sleep the PC, wait **30 seconds**, then send a wake. If the PC wakes, keep going.

Optional, in admin PowerShell: stop Windows from putting the PC back to sleep 2 minutes after a remote wake.
```
powercfg -attributes SUB_SLEEP 7bc4a2f9-d8fc-4469-b07b-33eb785aaca0 -ATTRIB_HIDE
powercfg /setacvalueindex SCHEME_CURRENT SUB_SLEEP 7bc4a2f9-d8fc-4469-b07b-33eb785aaca0 1800
powercfg /setactive SCHEME_CURRENT
```

## 2. Make the mailbox (a gist)

The gist is how your phone and your Fire Stick talk without opening anything on your network. The phone writes a command into it, and the Stick checks it every 5 seconds.

1. Go to **gist.github.com**.
2. Filename: `command.json`. Content: `{"cmd":null}`
3. Click **Add file**. Filename: `status.json`. Content: `{}`
4. Click the dropdown on the green button, pick **Create secret gist**.
5. Your **Gist ID** is the long string at the end of the new URL: `gist.github.com/your-name/THIS-PART`.

## 3. Make a token

This lets the page and the Stick read and write that gist.

1. Go to **GitHub → Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token**.
2. Name: anything, like `pc-wake`. Expiration: 90 days. You'll redo this step when it expires.
3. Repository access: **Public repositories**.
4. Permissions → Account → **Gists: Read and write**. Nothing else.
5. Generate it and copy the **token** (starts with `github_pat_`). Treat it like a password.

## 4. Make a signing secret

Every wake command gets signed with this, so only someone with the secret can wake your PC. Generate one:

- Windows PowerShell:
  ```
  $b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
  ```
- Mac/Linux: `openssl rand -base64 32`

## 5. Install the relay app on the Fire Stick

1. On the Fire Stick: **Settings → My Fire TV → About**, click the device name **7 times**. Then **Developer Options → ADB debugging: On**. Note the Stick's IP under **About → Network**.
2. On your computer, install [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) for ADB.
3. Run `adb connect STICK_IP:5555`. Accept the prompt on the TV, and tick "Always allow".
4. Build the app from this repo. It needs JDK 17 and the Android SDK:
   ```
   gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

## 6. Give the relay its config

Make a file called `config.json` with your values from the table:
```json
{
  "token": "github_pat_...",
  "secret": "your-secret",
  "gist_id": "your-gist-id",
  "pc_mac": "AA:BB:CC:DD:EE:FF",
  "pc_ip": "192.168.1.50",
  "broadcast": "192.168.1.255"
}
```
Push it to the app. Use **Command Prompt** on Windows, or a Mac/Linux terminal. PowerShell can't do the `<` redirect.
```
adb shell "run-as com.marcosvec.pcwake sh -c 'cat > files/config.json'" < config.json
```
Then delete your local `config.json`.

## 7. Keep the Stick awake

One time:
```
adb shell settings put secure sleep_timeout 2147483647
adb shell settings put system screen_off_timeout 2147483647
adb shell dumpsys deviceidle whitelist +com.marcosvec.pcwake
adb shell am set-standby-bucket com.marcosvec.pcwake active
adb shell cmd appops set com.marcosvec.pcwake RUN_ANY_IN_BACKGROUND allow
```
Open **PC Wake** on the TV once. From then on it restarts itself after reboots, updates and crashes.

The TV screen shows the relay's live status: whether it's running, when it last checked the mailbox, whether the PC is awake, and the last command. Press **Test relay**. Every line should say OK. It checks the whole chain without waking your PC.

## 8. Set up the control page

1. Fork this repo. Go to **Settings → Pages**, set Source to "Deploy from a branch", branch `main`, folder `/docs`. Your page goes live at `https://your-name.github.io/pc-wake/`.
2. Open it, expand **Setup on this device**, paste your token, secret and gist ID, and hit **Save**. "Relay" should turn green within a minute.
3. For your phone, hit **Phone setup QR** and scan it with your phone's camera. Don't screenshot or share the QR. It contains your token.

**Test:** sleep the PC, wait 30 seconds, tap **Wake PC**.

---

## When your token expires

Only the token expires. The gist and the secret last forever. When it happens, the page says "Token expired or revoked" and Test relay shows FAIL on Mailbox.

1. Make a new token, same settings as step 3.
2. Make `config.json` again with the new token, push it (step 6), then restart the app:
   ```
   adb shell am force-stop com.marcosvec.pcwake
   ```
   Then open **PC Wake** on the TV.
3. On the page: **Setup on this device**, paste the new token, **Save**, then **Phone setup QR** and scan it.

Press **Test relay** on the TV to confirm.

## If something's off

Start with **Test relay** on the TV. It names the broken link.

| You see | Fix |
| --- | --- |
| Relay: never seen | The Stick has no config. The TV app shows "Not configured". Redo step 6. |
| Token expired or revoked | See "When your token expires" above. |
| No response after 60s | Check step 1 on its own. Wrong MAC or random addresses still on are the usual causes. |
| PC wakes, then sleeps again | Run the optional powercfg commands in step 1. |
| Relay goes red overnight | Fire OS killed or slept the app. Redo step 7 and check the Stick is on wall power. |

Relay log: `adb shell run-as com.marcosvec.pcwake tail -n 20 files/heartbeat.log`
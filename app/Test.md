# Test Prompts

Run against `emulator-5554` (Android 17, 1080x2424) on 2026-09-05, model
`gemini-3.8-flash`. Every "verified" line below was checked against the device
itself — `content query`, `dumpsys`, or the app's own screen — not against what
the agent claimed it did.

## Results
前往松山工農資訊科的網站下載實習報告範例

| # | Prompt | Actions | Outcome | Verified against the device |
|---|--------|--------:|---------|------------------------------|
| 1 | What is the capital of Japan | 0 | pass | Answered in one call, no actions taken |
| 2 | What is the weather in Taipei tomorrow | 0 | pass | Answered from live search, no actions taken |
| 3 | Open the Clock app | 2 | pass | Clock in foreground |
| 4 | Set an alarm for 7:30 AM | 29 | pass | `7:30 AM Mon-Fri` present in the Alarms list |
| 5 | Set a timer for 5 minutes | ~6 | pass | Timer fired 5 minutes later on its own |
| 6 | Turn on Bluetooth in Settings | ~8 | pass | `settings get global bluetooth_on` → `1` |
| 7 | Search for the tallest building in the world in Chrome | 4 | pass | Chrome showing the Burj Khalifa result |
| 8 | use sms ask whether 0355688 want to have dinner with me | 9 | pass | Row in `content://sms/sent`, correct address and body |
| 9 | Add a contact named Test User with phone number 5551234 | 15 | pass *(after two fixes)* | `Test User` / `555-1234` in the contacts provider |

9 of 9 passed. The two question prompts never touched the phone, which is the
triage path working as intended.

## Prompt 9 failed twice first, and both causes were real

**First run — 50 steps, no contact created.** It typed the phone number one key
at a time on the on-screen keyboard:

```
click (Click Show symbols keyboard) -> Success
click (Tap 5 on symbols keyboard) -> Success
click (Backspace to delete 5) -> Success
```

Every step reported `Success`, so nothing looked wrong from inside the loop — it
just never converged. The cause was the `press_key` refusal: told it cannot
inject key events, the model fell back to pressing keys visually. Fixed by
instructing it never to enter text that way.

**Second run — still 50 steps.** The instruction alone did not save it, and the
log showed why:

```
type (Type First name Test) -> Field now reads "Test, First name".
type (Type Last name User) -> Field now reads "User, Last name".
type (Type phone number 5551234) -> No editable field has focus.
```

The name fields worked; the phone field never came back from
`findFocus(FOCUS_INPUT)` even though clicking it succeeded and the tree marked it
focused. `type` gave up and the model fell back to the keyboard again. Fixed by
falling back to a tree walk for the focused editable node when `findFocus`
returns nothing usable.

**Third run — 15 steps, contact created and verified.**

The general lesson: a failure string that is accurate ("no editable field has
focus") still sends the model down a bad path if the underlying capability is
recoverable. Worth checking the other refusal messages for the same shape.

## What is still untested

- **The confirmation overlay never fired.** No run produced a
  `safety_decision: require_confirmation`, including sending an SMS to a number
  that is not in contacts (prompt 8) — the model sent it without asking. The
  Allow/Deny overlay is therefore unexercised, and it is worth deciding whether
  sending a message should require confirmation regardless of what the model
  flags.
- Anything needing a signed-in account (Gmail, Calendar), since the emulator has none.
- Multi-app tasks ("find X in one app and put it in another").

## Prompts worth adding

- Re-run prompt 9 after the typing fix
- Reply to the most recent text message
- Turn on Do Not Disturb until 8am
- What time is my first alarm tomorrow *(reads device state — should not be answered by triage)*
- Take a photo
- Add milk to my shopping list in Keep

## Notes on running these

The agent presses Home before its first screenshot, so a run always starts from
the home screen rather than from inside Wristch. That was added during this
session: prompt 4 originally opened `alarm_app_3` (a third-party app on this
emulator whose name contains "alarm"), got confused, and started clicking
Wristch's own Test tab.

# Alpha 1.0.9 update

This update addresses the v1.0.8 test report.

## Changes

- `.test flight` now retries from an observed early touchdown. Flight steering
  remains receding-horizon and uses current position and velocity.
- Sorting and supply restocking verify accepted inventory actions and wait for
  low-TPS container contents to settle.
- Standalone trading verifies accepted villager interaction, a merchant screen,
  and accepted purchase actions before advancing.
- Each active test writes a bounded diagnostic session. Turning a test off, or
  reaching completion/failure, sends the session as a TXT attachment through
  the normal Discord notification channel.
- The default hall return is `-2012057 141 -1657332`.

## Testing workflow

Run one test at a time. On completion or cancellation, attach the generated
`metatrader-*.txt` file to the next test report along with the plain-English
gameplay result. The session includes throttled position snapshots, phase,
inventory-screen state through environment diagnostics, path/action events,
and explicit rejection or timeout events.

Build with `gradlew.bat clean build`.

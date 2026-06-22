# Chrome UA Bridge LSPosed

Companion LSPosed module for applying the current `vichanger` fake User-Agent profile to Android Chrome without a PC.

## What It Does

- Loads inside `com.android.chrome` through LSPosed.
- Reads the current fake profile from:

  ```text
  /data/misc/29314ea0-e7b4-477e-bf38-67f557ef6fc7/prefs/vn.vichanger.app/device.xml
  ```

- Connects directly to Chrome's local abstract DevTools socket:

  ```text
  chrome_devtools_remote
  ```

- Applies `Network.setUserAgentOverride` to every HTTP page target it sees.
- Applies `Emulation.setUserAgentOverride` with high-entropy UA-CH fields:
  `fullVersionList` and `formFactors`.
- Applies `Emulation.setTimezoneOverride` when `device.xml` contains `timezone`.
- Re-reads `device.xml` every loop, so it follows later `changeDevice` updates.
- Normalizes the Chrome version instead of trusting the stale version embedded in
  `vichanger`'s `user_agent`. The selected version is stable per fake profile.
  Android 9 and older are pinned to Chrome `138.0.7204.179`; Android 10+ profiles
  are mapped to a recent realistic version pool.

## Install

1. Build the APK from this project.
   - On GitHub: push to `main` or run the `Build APK` workflow. It uploads an artifact and publishes a GitHub Release named `Build <run_number>`.
   - Locally: run `gradle :app:assembleDebug`.
2. Install it on the phone.
3. Enable `Chrome UA Bridge` in LSPosed.
4. Scope it to:

   ```text
   com.android.chrome
   ```

5. Reboot, or at least force-stop and reopen Chrome after LSPosed has reloaded modules.

## Verify

Check logcat:

```text
ChromeUaBridge: starting in com.android.chrome
ChromeUaBridge: attached https://...
ChromeUaBridge: applied UA to ...
```

Check inside a page:

```js
navigator.userAgent
navigator.userAgentData
```

`/json/version` may still show Chrome's original process User-Agent. The page target is the source of truth for this module.

## Notes

- This is independent from the PC-side PowerShell script.
- It needs Chrome DevTools local socket to exist. On the tested device, `chrome_devtools_remote` exists.
- It does not modify the Chrome APK or global Android properties.

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

- Falls back to browser/WebView DevTools socket names for other Chromium-based
  browsers and WebView targets.
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
4. Scope it to the target browser. The APK ships a recommended scope list for
   Chrome, common Chromium-based browsers, and WebView packages. At minimum use:

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

## Cookie Export / Import (chuyển máy)

Khi chuyển từ máy cũ (đã root) sang máy mới, dùng tính năng này để backup và
restore tất cả cookie/session đã đăng nhập. Cookie được dump ở dạng plaintext
(đã giải mã), bypass mã hóa Android Keystore gắn với phần cứng máy cũ.

### Yêu cầu

- Máy cũ: đã root + LSPosed + module này đã cài
- Chrome (hoặc browser Chromium) đang mở ít nhất 1 tab HTTP
- ADB kết nối được với máy

### Export (máy cũ)

```bash
# Mở Chrome trước, rồi chạy:
adb shell am broadcast -a dev.local.chromeuabridge.EXPORT_COOKIES

# Kết quả lưu tại /sdcard/chromeuabridge_cookies.json
# Pull về PC:
adb pull /sdcard/chromeuabridge_cookies.json

# (Tùy chọn) Chỉ định path khác:
adb shell am broadcast -a dev.local.chromeuabridge.EXPORT_COOKIES \
    --es path /sdcard/Download/my_cookies.json
```

### Import (máy mới)

**Cách 1: Import thủ công**

```bash
# Push file cookie lên máy mới:
adb push chromeuabridge_cookies.json /sdcard/chromeuabridge_cookies.json

# Mở Chrome, rồi chạy:
adb shell am broadcast -a dev.local.chromeuabridge.IMPORT_COOKIES
```

**Cách 2: Auto-import khi khởi động**

Chỉ cần push file vào `/sdcard/chromeuabridge_cookies.json` rồi mở Chrome.
Module sẽ tự phát hiện file và import. Sau khi xong, file được rename thành
`.imported` để không import lại.

```bash
adb push chromeuabridge_cookies.json /sdcard/chromeuabridge_cookies.json
# Mở Chrome → module tự import
```

### Kiểm tra

```bash
adb logcat -s ChromeUaBridge
# ChromeUaBridge: exporting cookies from com.android.chrome ...
# ChromeUaBridge: exported 142 cookies to /sdcard/chromeuabridge_cookies.json
# ChromeUaBridge: ⚠ WARNING: file contains plaintext session tokens! Delete after import.
```

> **⚠ Bảo mật:** File export chứa plaintext session tokens (Google, Facebook, v.v.).
> Xóa file sau khi import xong!

## Notes

- This is independent from the PC-side PowerShell script.
- It needs Chrome DevTools local socket to exist. On the tested device, `chrome_devtools_remote` exists.
- It does not modify the Chrome APK or global Android properties.
- APKs are signed with a committed test PKCS12 key at
  `app/signing/test-signing.p12` so GitHub builds can be installed over earlier
  builds from this repo. This key is intentionally public and only suitable for
  test builds.

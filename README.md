# DualCam (Android, v0.1)

Ek recording -> 2 videos: **16:9 landscape (1080p)** aur **9:16 portrait (1080x1920)**, aesthetic filters ke saath.
Phone ko **landscape** me pakdo. Screen par 16:9 frame dikhta hai, uske andar 9:16 box (drag karke left/right kar sakte ho).

Stack: Kotlin, Jetpack Compose, CameraX (preview + 4K record), Media3 Transformer (crop + 3D LUT export).

## APK banana

**A) GitHub Actions (laptop pe kuch install nahi)**
1. Is folder ko ek naye GitHub repo me push karo.
2. Repo > Actions > "Build APK" run hone do (push pe apne aap chalta hai).
3. Run ke andar Artifacts > `DualCam-debug-apk` download karo, zip kholo, `app-debug.apk` phone me install karo.

**B) Android Studio**
Folder open karo > Build > Build APK(s). (Studio gradle wrapper khud bana lega.)

## Pehli baar test karte waqt dekhna
1. Preview me filter chip dabane pe colors badalte hain?
2. Record -> stop -> export ke baad Gallery/Movies/DualCam me 2 files aayi?
3. Portrait video me wahi hissa hai jo preview me bright box me tha?
4. Export me filter ka rang preview jaisa hai? Agar red/blue ulte hain: `Filters.kt` me `SWAP_RB_AXES = true` karo.
5. Portrait video side me ghuma hua (rotated) dikhe to `VideoExporter.kt` ka `uprightSize()` aur crop check karna padega — error log/screenshot bhejna.

## Known limits (v0.1)
- Sirf landscape. Zoom, tap-to-focus, 4K export abhi nahi.
- Preview filter ColorMatrix se hai (export ke LUT se thoda alag dikh sakta hai).
- Export real-time nahi hota: record ke baad 2 baar process hota hai (4K clip ke liye clip-length ke aas-paas time).

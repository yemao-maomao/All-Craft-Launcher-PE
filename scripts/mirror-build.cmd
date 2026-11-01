@echo off
REM ============================================================
REM ACL-PE mirror build (sandbox workaround)
REM   DSH sandbox on this host confines java-family child processes
REM   to C:\Users\vshuf\Documents\ai (old workspace); they cannot
REM   write D:\mc1. So: robocopy sources to the allowed mirror,
REM   run Gradle inside the mirror, copy the APK back.
REM   Git repo and sources ALWAYS stay at D:\mc1; the mirror is a
REM   disposable build site (safe to delete and recreate).
REM   Keep this file ASCII-only: cmd.exe parses it in the OEM
REM   codepage and UTF-8 Chinese comments corrupt SET lines.
REM ============================================================
set "MIRROR=C:\Users\vshuf\Documents\ai\aclpe-mirror"
set "JAVA_HOME=C:\Users\vshuf\Documents\ai\tools\jdk21\jdk-21.0.12.1+1"
set "GRADLE_USER_HOME=C:\Users\vshuf\Documents\ai\gradle-home"
set "TMP=C:\Users\vshuf\Documents\ai\tmp"
set "TEMP=C:\Users\vshuf\Documents\ai\tmp"
REM debug keystore lives in the allowed area (java cannot lock ~/.android/debug.keystore here)
set "ACLPE_DEBUG_KEYSTORE=C:\Users\vshuf\Documents\ai\aclpe-keys\debug.keystore"

echo [1/3] syncing sources to mirror...
robocopy "D:\mc1" "%MIRROR%" /MIR /XD .git build .gradle .kotlin acl-recovery dist /XF *.log /NFL /NDL /NJH /NJS /NP
if errorlevel 8 goto :fail

echo [2/3] building in mirror (clean, then unit tests + debug APK)...
cd /d "%MIRROR%"
REM :app:clean is mandatory: Gradle daemon VFS does not always notice robocopy's
REM external deletions, which produced stale APKs (assets removed but APK unchanged).
REM Clean must run in its OWN invocation: mixing it with tests in one call can
REM race (clean deletes app/build while test tasks write into it).
call gradlew.bat :app:clean --console=plain
if errorlevel 1 goto :fail
call gradlew.bat testDebugUnitTest :app:assembleDebug --console=plain
if errorlevel 1 goto :fail

echo [3/3] copying APK back...
if not exist "D:\mc1\dist" mkdir "D:\mc1\dist"
copy /y "app\build\outputs\apk\debug\app-debug.apk" "D:\mc1\dist\app-debug.apk"
if errorlevel 1 goto :fail

echo MIRROR BUILD OK
exit /b 0

:fail
echo MIRROR BUILD FAILED
exit /b 1

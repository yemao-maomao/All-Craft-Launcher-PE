@echo off
REM ============================================================
REM ACL-PE build verify: unit tests + debug APK (one command)
REM Host-verified env recipe - do not change blindly:
REM   JAVA_HOME        -> inner jdk21 dir (outer dir fails: JAVA_HOME invalid)
REM   GRADLE_USER_HOME -> avoid ~/.gradle (ghost handles break wrapper .lck)
REM   TMP/TEMP         -> avoid system TEMP (Kotlin compiler .alive marker issue)
REM NOTE: this script builds IN PLACE (D:\mc1). If the session sandbox
REM still confines java children to the old workspace, use
REM scripts\mirror-build.cmd instead (builds in the allowed mirror).
REM Keep this file ASCII-only: cmd.exe parses it in the OEM codepage
REM and UTF-8 Chinese comments corrupt SET lines.
REM ============================================================
set "JAVA_HOME=C:\Users\vshuf\Documents\ai\tools\jdk21\jdk-21.0.12.1+1"
set "GRADLE_USER_HOME=C:\Users\vshuf\Documents\ai\gradle-home"
set "TMP=C:\Users\vshuf\Documents\ai\tmp"
set "TEMP=C:\Users\vshuf\Documents\ai\tmp"
cd /d "%~dp0.."
call gradlew.bat :core-common:testDebugUnitTest :app:assembleDebug --console=plain

@echo off
REM Cross-compile gl4es (OpenGL -> GLES translation layer) to arm64 Android.
REM Output: libGL.so.1 copied to app/src/main/assets/natives/arm64-v8a/
REM gl4es source: https://github.com/ptitSeb/gl4es (MIT license, independent open-source lib)
REM Depends on: Android NDK + SDK cmake (local: NDK 27.0.12077973, cmake 3.22.1)

setlocal
set "NDK=%LOCALAPPDATA%\Android\Sdk\ndk\27.0.12077973"
set "CMAKE=%LOCALAPPDATA%\Android\Sdk\cmake\3.22.1\bin\cmake.exe"
set "NINJA=%LOCALAPPDATA%\Android\Sdk\cmake\3.22.1\bin\ninja.exe"
set "GL4ES=C:\Users\vshuf\Documents\ai\gl4es"

if not exist "%GL4ES%\CMakeLists.txt" (
    echo gl4es source not found at %GL4ES%. Clone with:
    echo   git clone --depth 1 https://github.com/ptitSeb/gl4es.git "%GL4ES%"
    exit /b 1
)

cd /d "%GL4ES%"
"%CMAKE%" -G Ninja -DCMAKE_MAKE_PROGRAM="%NINJA%" -DANDROID=ON -DNOX11=ON -DNO_GBM=ON -DCMAKE_TOOLCHAIN_FILE="%NDK%\build\cmake\android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DCMAKE_BUILD_TYPE=Release -S . -B build-android
if errorlevel 1 goto :fail
"%CMAKE%" --build build-android
if errorlevel 1 goto :fail

copy /Y "%GL4ES%\lib\libGL.so.1" "%~dp0..\app\src\main\assets\natives\arm64-v8a\libGL.so.1"
if errorlevel 1 goto :fail

echo.
echo Done. libGL.so.1 copied to assets/natives/arm64-v8a/ (embedded into APK).
exit /b 0

:fail
echo FAILED: gl4es build error.
exit /b 1

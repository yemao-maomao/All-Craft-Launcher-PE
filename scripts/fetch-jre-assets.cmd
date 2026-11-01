@echo off
REM 下载内置 JRE 资产到 APK（参考 FCL：运行时打进 APK，环境补齐不走网络）。
REM 用法：scripts\fetch-jre-assets.cmd（需联网；文件较大，约 92MB）
REM 资产缺失时构建仍成功，应用自动回退网络下载（双路径并存）。
REM 纯 ASCII 注释：本脚本在 Windows cmd 下运行，避免中文编码问题。

setlocal
set "ASSET_DIR=%~dp0..\app\src\main\assets\runtimes"
if not exist "%ASSET_DIR%" mkdir "%ASSET_DIR%"

echo [1/2] jre22-arm64.tar.xz (54974776 bytes, sha256 96C5A2A5E99E5800738FEB1627A01D5F30712B1F8EED02296F57D6F2B71B9DB7)
curl -L -o "%ASSET_DIR%\jre22-arm64.tar.xz" "http://oss.mcddos.top/fcl/jre/jre22-arm64.tar.xz"
if errorlevel 1 goto :fail

echo [2/2] jre25-arm64.tar.xz (37751348 bytes, sha256 94BCC9C654793F1B443BB6A0FC92A6DF0619C06CF7187236638BDAEF2EE9C4C2)
curl -L -o "%ASSET_DIR%\jre25-arm64.tar.xz" "http://oss.mcddos.top/fcl/jre/jre25-arm64.tar.xz"
if errorlevel 1 goto :fail

echo.
echo Verify sha256 (must match values in JreManifest.kt):
certutil -hashfile "%ASSET_DIR%\jre22-arm64.tar.xz" SHA256
certutil -hashfile "%ASSET_DIR%\jre25-arm64.tar.xz" SHA256
echo.
echo Done. Build with scripts\mirror-build.cmd to embed runtimes into the APK.
exit /b 0

:fail
echo FAILED: download error.
exit /b 1

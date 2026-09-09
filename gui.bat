@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo 编译中...
javac -encoding UTF-8 -d out src\xzpk\*.java
if errorlevel 1 (
    echo.
    echo 编译失败, 请确认已安装 JDK 并配置了 PATH
    pause
    exit /b 1
)
echo 启动图形界面...
start "" javaw -cp out xzpk.Gui

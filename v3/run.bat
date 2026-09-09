@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo [1/2] 编译中...
javac -encoding UTF-8 -d out src\xzpk\*.java
if errorlevel 1 (
    echo 编译失败, 请确认已安装 JDK 并配置了 PATH
    pause
    exit /b 1
)
echo [2/2] 运行中...
java -cp out xzpk.Main %*
echo.
pause

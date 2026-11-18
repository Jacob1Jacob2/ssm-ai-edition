@echo off
rem Local SSM AI test server. Join in Minecraft 26.3 at: localhost
rem Type "stop" in this window to shut it down cleanly.
cd /d "%~dp0"
for /d %%J in ("C:\Program Files\Eclipse Adoptium\jdk-25*") do set "JAVA=%%J\bin\java.exe"
"%JAVA%" -Xms1G -Xmx3G -XX:-UseBASE64Intrinsics -jar paper-26.3-245.jar --nogui
pause

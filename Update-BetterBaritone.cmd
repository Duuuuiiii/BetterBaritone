@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Update-BetterBaritone.ps1" -WaitForExit
if errorlevel 1 echo BetterBaritone update failed.
pause

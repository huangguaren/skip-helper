@echo off
rem Accessibility diagnostics wrapper: bypasses the local ExecutionPolicy for this process only.
rem   tools\diagnose-accessibility.cmd
rem   tools\diagnose-accessibility.cmd -Enable
rem   tools\diagnose-accessibility.cmd -Watch
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0diagnose-accessibility.ps1" %*

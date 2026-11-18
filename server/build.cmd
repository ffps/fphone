@echo off

set "PATH=C:\GO\bin;%PATH%"
set CGO_ENABLED=0

:: go mod tidy
:: set GOOS=linux
:: set GOARCH=arm64

go build -ldflags "-s -w -X main.version=0.3.0" -o fphone.exe .

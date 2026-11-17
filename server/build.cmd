@echo off

set "PATH=C:\GO\bin;%PATH%"
set CGO_ENABLED=0

:: go mod tidy
:: set GOOS=linux
:: set GOARCH=arm64

go build -ldflags "-s -w -X main.version=0.1.2" -o fphone.exe .

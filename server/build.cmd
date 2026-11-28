@echo off

set "PATH=C:\GO\bin;%PATH%"
set CGO_ENABLED=0

:: go mod tidy

set ver=1.1.3

go build -ldflags "-s -w -X main.version=%ver%" -o fphone.exe .

set GOOS=linux
set GOARCH=arm64

go build -ldflags "-s -w -X main.version=%ver%" -o fphone .

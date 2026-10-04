#!/usr/bin/env bash

# Create bin directory if it doesn't exist
mkdir -p bin

# Free port 8080 if occupied
if command -v lsof >/dev/null 2>&1; then
    PID=$(lsof -t -i:8080)
    if [ -n "$PID" ]; then
        echo "Freeing occupied port 8080 (PID: $PID)..."
        kill -9 $PID 2>/dev/null || true
        sleep 1
    fi
elif command -v fuser >/dev/null 2>&1; then
    fuser -k 8080/tcp 2>/dev/null || true
    sleep 1
fi

echo "Compiling CropAdvisoryApp.java into bin/..."
javac -d bin -cp "lib/sqlite-jdbc-3.45.3.0.jar" CropAdvisoryApp.java

if [ -f bin/CropAdvisoryApp.class ]; then
    echo "=========================================================="
    echo " Launching Smart Crop Advisory Server! Open browser at:"
    echo " 👉 http://localhost:8080"
    echo " 👉 http://127.0.0.1:8080"
    echo " Demo Farmer: Username: demo | PIN: 1234"
    echo " Demo Admin:  Username: admin | PIN: 9999"
    echo " Press Ctrl+C in this terminal to shut down."
    echo "=========================================================="
    java -cp "bin:lib/*" CropAdvisoryApp
else
    echo "Compilation failed. Please check Java errors."
    exit 1
fi

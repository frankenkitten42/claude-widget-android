#!/data/data/com.termux/files/usr/bin/bash
# claude-usage-boot.sh — Termux:Boot script
# Copy or symlink to ~/.termux/boot/claude-usage-boot.sh
#
# Runs the fetch script every 10 minutes in the background.
# Requires: Termux:Boot, Termux:API (for wake lock), proot-distro with Debian

termux-wake-lock

# Run once immediately at boot
proot-distro login debian -- /root/.local/bin/claude-usage-fetch.sh &

# Then every 10 minutes
while true; do
    sleep 600
    proot-distro login debian -- /root/.local/bin/claude-usage-fetch.sh
done &

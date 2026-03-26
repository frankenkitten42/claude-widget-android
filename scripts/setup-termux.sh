#!/data/data/com.termux/files/usr/bin/bash
# setup-termux.sh — One-time setup for Claude Widget data bridge
# Run this from bare Termux (NOT inside proot-distro)
set -euo pipefail

echo "=== Claude Widget Termux Setup ==="

# Check prerequisites
command -v proot-distro >/dev/null || { echo "ERROR: proot-distro not installed. Run: pkg install proot-distro"; exit 1; }
echo "[OK] proot-distro found"

# Check Debian rootfs
if ! proot-distro list 2>/dev/null | grep -q debian; then
    echo "ERROR: Debian rootfs not installed. Run: proot-distro install debian"
    exit 1
fi
echo "[OK] Debian rootfs found"

# Check the fetch script exists inside proot
if ! proot-distro login debian -- test -f /root/.local/bin/claude-usage-fetch.sh; then
    echo "ERROR: Fetch script not found at /root/.local/bin/claude-usage-fetch.sh inside Debian"
    exit 1
fi
echo "[OK] Fetch script found"

# Create Termux:Boot directory
mkdir -p ~/.termux/boot
echo "[OK] Boot directory ready"

# Install the boot script
BOOT_SCRIPT="$HOME/.termux/boot/claude-usage-boot.sh"
cat > "${BOOT_SCRIPT}" << 'BOOT_EOF'
#!/data/data/com.termux/files/usr/bin/bash
termux-wake-lock
proot-distro login debian -- /root/.local/bin/claude-usage-fetch.sh &
while true; do
    sleep 600
    proot-distro login debian -- /root/.local/bin/claude-usage-fetch.sh
done &
BOOT_EOF
chmod +x "${BOOT_SCRIPT}"
echo "[OK] Boot script installed at ${BOOT_SCRIPT}"

# Do a test fetch
echo ""
echo "Running test fetch..."
proot-distro login debian -- /root/.local/bin/claude-usage-fetch.sh && echo "[OK] Fetch completed" || echo "[WARN] Fetch had an error (check credentials)"

# Verify output
ANDROID_FILE="/storage/emulated/0/Documents/claude-usage/latest.json"
if [[ -f "${ANDROID_FILE}" ]]; then
    echo "[OK] Data file created at ${ANDROID_FILE}"
    echo ""
    echo "=== Setup complete! ==="
    echo "The widget will read data from: ${ANDROID_FILE}"
    echo "The fetch script will run every 10 minutes after reboot."
    echo ""
    echo "To start fetching now without rebooting, run:"
    echo "  nohup bash ~/.termux/boot/claude-usage-boot.sh &"
else
    echo "[WARN] Data file not created — check if the fetch script can write to shared storage"
fi

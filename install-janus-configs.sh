#!/bin/bash
# Script to install Janus configurations

echo "Copying Janus configuration files..."

# Backup existing configs
sudo cp /opt/janus/etc/janus/janus.jcfg /opt/janus/etc/janus/janus.jcfg.backup
sudo cp /opt/janus/etc/janus/janus.plugin.sip.jcfg /opt/janus/etc/janus/janus.plugin.sip.jcfg.backup
sudo cp /opt/janus/etc/janus/janus.transport.websockets.jcfg /opt/janus/etc/janus/janus.transport.websockets.jcfg.backup

# Copy new configs
sudo cp janus-configs/janus.jcfg /opt/janus/etc/janus/janus.jcfg
sudo cp janus-configs/janus.plugin.sip.jcfg /opt/janus/etc/janus/janus.plugin.sip.jcfg
sudo cp janus-configs/janus.transport.websockets.jcfg /opt/janus/etc/janus/janus.transport.websockets.jcfg

echo "Configuration files installed successfully"
echo "You can now start Janus with: /opt/janus/bin/janus"

QEMU data directory (-L). Keymap files are only needed when QEMU is started with -k <layout>;
the VNC server uses its built-in keysym tables otherwise. Firmware is not shipped: guests are
booted with -kernel/-initrd or a user-imported disk image.

# Pixel IMS

Enable carrier IMS features on Pixel phones **without root** — VoLTE, 5G calling (VoNR),
Wi-Fi calling (VoWiFi) and more, configured per SIM.

## What it does

- **Turns on IMS features** by overriding the carrier configuration: VoLTE, VoNR (5G calling),
  VoWiFi, Wi-Fi calling while roaming, and supplementary services over UT.
- **Per-SIM control** — each SIM slot has its own toggles, status and actions.
- **Live status** — shows the device's real IMS registration state and the *effective* carrier
  config for each feature, read straight from the system.
- **Apply / Restore** — write your settings to a SIM, or roll it back to the carrier defaults.
- **Apply on boot** — re-applies your settings automatically in the
  background once the phone is back online.

## How it works

There is no public API to change carrier config, so the app uses shell privileges supplied by a Shizuku-compatible service:

1. It requests authorization through the [Shizuku API](https://github.com/RikkaApps/Shizuku-API), compatible with standard Shizuku and Sui. ShizukuPlus works through its Drop-in build or the ShizukuPlus Compat Hub.
2. It launches a small `Instrumentation` that adopts the shell
   permission identity and calls `CarrierConfigManager.overrideConfig` for the selected SIM.
3. Status is read back with the same shell privileges via a lightweight `app_process` tool.

## Requirements

- A Tensor Pixel device on **Android 12+** (`minSdk 31`).

## Usage

1. Install and start Shizuku or Sui, open the app and grant Pixel IMS authorization when prompted. For ShizukuPlus, use its Drop-in build or install its Compat Hub. For standard Shizuku without root, start its service using its documented setup.
2. Pick a SIM, toggle the features you want, and tap **Apply**.
3. Optionally enable **Apply on boot** so the configuration survives restarts.
4. Use **Restore** to return a SIM to its carrier defaults.

## Disclaimer

This tool changes low-level telephony configuration on your own device. Whether a feature actually
works still depends on your carrier and network. Use at your own risk.

## Fork changes

This repository is a fork of Pixel IMS. This fork replaces the original Wireless Debugging pairing
and loopback ADB authorization flow with Shizuku UserService authorization. It supports standard
Shizuku and Sui through the Shizuku API, and ShizukuPlus through its Drop-in build or Compat Hub.
The existing IMS instrumentation, shell commands, status polling, permission grants, and apply-on-boot
behavior are retained; boot re-apply no longer depends on Wi-Fi or mDNS discovery.

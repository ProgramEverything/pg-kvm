# pg-kvm-esp32s3-dummy

This directory is a **placeholder** - it intentionally contains no firmware source code.

## What you need to do

Firmware is **not** bundled in this repository. Please visit the upstream project, download the
source, compile it, and flash it to your ESP32-S3 board yourself:

> https://github.com/KoStard/ESPRemoteControl/

Once the device is running the ESPRemoteControl firmware, the `pg-kvm-android` client can connect
to it and remote keyboard/mouse control should work out of the box, since both sides implement the
same protocol.

## Purpose

The `pg-kvm-android` client in this repository is designed to be **protocol-compatible with
[ESPRemoteControl](https://github.com/KoStard/ESPRemoteControl/)** by KoStard. It implements the
same Bluetooth Low Energy (BLE) control protocol that ESPRemoteControl's firmware exposes for
remote keyboard and mouse input, and it is meant to work alongside that project's firmware running
on an **ESP32-S3** board.

The ESP32-S3 firmware acts as a **BLE -> USB HID bridge**: it receives input events over BLE and
replays them to the target PC through the S3's native USB controller (TinyUSB), where it enumerates
as a wired USB keyboard and mouse. In other words, the Android side of this repo is the *client*,
and ESPRemoteControl is the *firmware* it expects to talk to.

## Notes

- An **ESP32-S3** (or another ESP32 variant with native USB, as supported by the upstream project)
  is required for the USB HID functionality; classic ESP32 chips without native USB cannot
  enumerate as a USB keyboard/mouse.

## Acknowledgements

Many thanks to **KoStard**, the author of
[ESPRemoteControl](https://github.com/KoStard/ESPRemoteControl/), for open-sourcing this project.
This repository would not be possible without their work.

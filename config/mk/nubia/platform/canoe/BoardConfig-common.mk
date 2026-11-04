# Qualcomm canoe.

TARGET_ARCH := arm64
TARGET_ARCH_VARIANT := armv9-2a
TARGET_CPU_ABI := arm64-v8a
TARGET_CPU_VARIANT := generic

TARGET_DYNAMIC_64_32_MEDIASERVER := true
TARGET_DYNAMIC_64_32_DRMSERVER := true

include vendor/mdevtool/config/mk/nubia/common/BoardConfig-common.mk

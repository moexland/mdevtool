# Qualcomm canoe.

TARGET_ARCH := arm64
TARGET_ARCH_VARIANT := armv9-2a
TARGET_CPU_ABI := arm64-v8a
TARGET_CPU_VARIANT := generic

TARGET_DYNAMIC_64_32_MEDIASERVER := true
TARGET_DYNAMIC_64_32_DRMSERVER := true

# Recovery loads the policy built here, not the stock vendor policy.
BOARD_VENDOR_SEPOLICY_DIRS += vendor/mdevtool/config/mk/nubia/platform/canoe/sepolicy/vendor

# The domain of the stock QTI ImsService app.
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += vendor/mdevtool/config/mk/nubia/platform/canoe/sepolicy/system_ext/private

include vendor/mdevtool/config/mk/nubia/common/BoardConfig-common.mk

# Board settings shared by every GrimoireOS nubia device.
#
# The kernel, vendor, odm and boot images stay stock; this tree builds system,
# system_ext, product, init_boot and recovery. Stock facts such as partition
# geometry come from the generated vendor/<vendor>/<device>/BoardConfig.mk, which
# includes this file first and then overrides it.

include build/make/target/board/BoardConfigMainlineCommon.mk

TARGET_NO_KERNEL := true
BOARD_USES_GENERIC_KERNEL_IMAGE := true
TARGET_USERIMAGES_USE_F2FS := true

BOARD_EROFS_COMPRESSOR := lz4hc,9
TARGET_USERIMAGES_SPARSE_EROFS_DISABLED := true

AB_OTA_UPDATER := true
# A generic full OTA would delete the stock dynamic partitions it doesn't carry.
# Updates are partial OTAs built outside the build system.
TARGET_SKIP_OTA_PACKAGE := true

# Development only: the top-level vbmeta is the stock one with verification
# disabled, so no AVB chain is generated here.
BOARD_AVB_ENABLE := false

BOARD_SEPOLICY_DIRS += build/make/target/board/generic_arm64/sepolicy

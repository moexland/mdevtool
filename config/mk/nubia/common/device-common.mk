# Product settings shared by every GrimoireOS nubia device.

$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/generic_system.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/handheld_system_ext.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/telephony_system_ext.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/aosp_product.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/window_extensions.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/generic_ramdisk.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/virtual_ab_ota/vabc_features.mk)
# No angle_default.mk: with system ANGLE as the GLES driver, WebView's in-process
# GPU thread hits a hardened_malloc write-after-free in eglCreateContext.

PRODUCT_ENFORCE_ARTIFACT_PATH_REQUIREMENTS := true
MODULE_BUILD_FROM_SOURCE := true

# Only system, system_ext, product, init_boot and recovery are built.
PRODUCT_USE_DYNAMIC_PARTITIONS := true
PRODUCT_USE_DYNAMIC_PARTITION_SIZE := true
PRODUCT_BUILD_SUPER_PARTITION := false
PRODUCT_BUILD_SUPER_EMPTY_IMAGE := false
PRODUCT_BUILD_USERDATA_IMAGE := false
PRODUCT_BUILD_CACHE_IMAGE := false
PRODUCT_BUILD_INIT_BOOT_IMAGE := true
PRODUCT_BUILD_RECOVERY_IMAGE := true
PRODUCT_BUILD_VENDOR_BOOT_IMAGE := false
PRODUCT_BUILD_DEBUG_BOOT_IMAGE := false
PRODUCT_BUILD_DEBUG_VENDOR_BOOT_IMAGE := false
PRODUCT_BUILD_VBMETA_IMAGE := false
PRODUCT_BUILD_PVMFW_IMAGE := false
PRODUCT_OTA_ENFORCE_VINTF_KERNEL_REQUIREMENTS := true

PRODUCT_VIRTUAL_AB_COMPRESSION_METHOD := lz4
PRODUCT_VIRTUAL_AB_COMPRESSION_FACTOR := 65536
PRODUCT_VIRTUAL_AB_COW_VERSION := 3

# The recovery half of base_vendor.mk, whose vendor half would clash with the
# stock vendor image. charger.recovery is left out: a separate recovery
# partition never boots in charger mode.
PRODUCT_PACKAGES += \
    adbd.recovery \
    cgroups.recovery.json \
    init_second_stage.recovery \
    ld.config.recovery.txt \
    linker.recovery \
    otacerts.recovery \
    recovery \
    servicemanager.recovery \
    shell_and_utilities_recovery \
    watchdogd.recovery
PRODUCT_VENDOR_PROPERTIES += \
    ro.recovery.usb.vid?=18D1 \
    ro.recovery.usb.adb.pid?=D001 \
    ro.recovery.usb.fastboot.pid?=4EE0

# Recovery modules that devices opt into, as cuttlefish does: fastbootd with its
# HAL, and the updater behind adb sideload.
PRODUCT_PACKAGES += \
    android.hardware.fastboot-service.example_recovery \
    fastbootd \
    update_engine_sideload

PRODUCT_COPY_FILES += \
    device/sample/etc/apns-full-conf.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/apns-conf.xml

# Qualcomm canoe, running a source-built system on the stock RedMagicOS
# 11.0.23 vendor (an Android 16 QSSI vendor) and kernel.

# The stock kernel uses 4 KiB pages and has CONFIG_USERFAULTFD.
PRODUCT_NO_BIONIC_PAGE_SIZE_MACRO := true
PRODUCT_MAX_PAGE_SIZE_SUPPORTED := 16384
PRODUCT_ENABLE_UFFD_GC := true

# Stock vendor clients such as wifilearner and psiclient still link the HIDL
# allocator and memory libraries. base_system_ext.mk only adds them for devices
# that shipped with API level 34 or lower; this one shipped with 36.
PRODUCT_PACKAGES += \
    hwservicemanager \
    android.hidl.allocator@1.0-service \
    android.hidl.memory@1.0-impl

# The stock Wi-Fi stack has a legacy keystore client of wificond, which cp2a
# no longer builds by default. Keep it until real client use and WPA-EAP have
# been audited.
PRODUCT_PACKAGES += wificond

PRODUCT_PACKAGES += init.canoe.grimoireos.rc

include vendor/mdevtool/config/mk/nubia/common/device-common.mk

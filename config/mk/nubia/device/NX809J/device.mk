# nubia RedMagic 11 Pro+ (NX809J).

PRODUCT_PACKAGES += \
    GrimoNX809JCooling \
    GrimoNX809JFrameworkOverlay \
    GrimoNX809JSettingsOverlay \
    init.nx809j.grimoireos.rc

# Without an idle timer SurfaceFlinger keeps the last mode on a static screen, which after a
# touch is the 144 Hz peak. The stock firmware drops to 60 Hz with its own controller and
# doesn't set this.
PRODUCT_PRODUCT_PROPERTIES += \
    ro.surface_flinger.set_idle_timer_ms=1000

include vendor/mdevtool/config/mk/nubia/platform/canoe/device.mk

# mdevtool

## 简览 | At a Glance

mdevtool是GrimoireOS的设备支持工具，地位相当于GrapheneOS的adevtool：它从原厂固件生成机型的vendor树，并存放手写的构建配方。GrimoireOS是在努比亚红魔11 Pro+（NX809J）上运行的GrapheneOS移植，沿用原厂的vendor、odm、内核与启动镜像，自编system、system_ext、product、init_boot与recovery。

mdevtool is the device support tool of GrimoireOS, playing the part of GrapheneOS's adevtool: it generates a device's vendor tree from the stock firmware and holds the hand-written build recipes. GrimoireOS is a port of GrapheneOS to the nubia RedMagic 11 Pro+ (NX809J) that keeps the stock vendor, odm, kernel and boot images and builds its own system, system_ext, product, init_boot and recovery.

## 工作方式 | How It Works

构建入口在生成的`vendor/nubia/NX809J`里。这个目录不进git，每次运行生成器都会整个重新生成，但只写入内容有变化的文件、删去不再生成的文件，没变的文件保留原来的时间戳，构建就不会重做依赖它们的步骤：原厂事实（产品身份、分区几何、启动镜像参数、VINTF矩阵、原厂镜像、从原厂recovery取出的HAL与fstab、IMS的原厂件，以及按原厂取值重建的资源覆盖层）都写在这里，手写的配方则留在mdevtool里，由生成的makefile先行引入、再被生成值覆盖。

The build entry points live in the generated `vendor/nubia/NX809J`. That directory is not kept in git; every run of the generator regenerates all of it, but writes only the files whose content changed and removes those no longer generated, so unchanged files keep their timestamps and the build doesn't redo the steps that depend on them. Stock facts — product identity, partition geometry, boot image parameters, the VINTF matrix, the stock images, the HALs and fstab taken from the stock recovery, the stock IMS files, and resource overlays rebuilt with stock values — are written there, while the hand-written recipes stay in mdevtool, included first by the generated makefiles and then overridden by the generated values.

- `generate.py`：生成器。先核对原厂输入的哈希，再写出vendor树，最后与`vendor-specs`比对。
- `config/device/<机型>.json`：原厂事实与生成方式，每组取值注明出处；`<机型>-inputs.json`列出原厂输入及其哈希。
- `config/mk/<厂商>/`：手写配方，分为品牌层`common`、平台层`platform/<平台>`与机型层`device/<机型>`三层，自下而上引入。
- `vendor-specs/`：生成结果逐文件的哈希。输出一旦变化，生成器就停下，等人审过后用`--update-spec`更新。
- `vendor-skels/`：生成的文本文件的副本，提交历史里能直接看出每次改动让生成结果变了什么。`.find-ignore`把它挡在构建系统的扫描之外。
- `scripts/finalize-init-boot.py`：发布时的init_boot后处理，把签名后重建的镜像恢复成原厂的头部与footer形态。

<!-- 分开中英两个列表 | keeps the two lists apart -->

- `generate.py`: the generator. It checks the hashes of the stock inputs, writes the vendor tree and compares the result with `vendor-specs`.
- `config/device/<device>.json`: stock facts and how to generate from them, with the source of every group of values; `<device>-inputs.json` lists the stock inputs and their hashes.
- `config/mk/<vendor>/`: hand-written recipes in three layers, the brand layer `common`, the platform layer `platform/<platform>` and the device layer `device/<device>`, each including the one below.
- `vendor-specs/`: per-file hashes of the generated tree. Whenever the output changes, the generator stops until someone has reviewed it and run `--update-spec`.
- `vendor-skels/`: copies of the generated text files, so that the history shows how each change affected the output. `.find-ignore` keeps them out of the build system's scans.
- `scripts/finalize-init-boot.py`: release post-processing for init_boot, restoring the stock header and footer to the image that signing rebuilds.

## 使用 | Usage

mdevtool位于GrapheneOS源码树的`vendor/mdevtool`，由GrimoireOS的local manifest引入。生成器只用Python标准库，需要Python 3.10或更新的版本。

mdevtool sits at `vendor/mdevtool` in a GrapheneOS source tree, added by GrimoireOS's local manifest. The generator uses only the Python standard library and needs Python 3.10 or newer.

### 原厂输入 | Stock Inputs

生成器从一个输入目录读取原厂RedMagicOS 11.0.23MR1的文件，按`config/device/NX809J-inputs.json`核对哈希：

- `boot.img`、`vendor.img`、`odm.img`、`vendor_dlkm.img`、`system_dlkm.img`：原厂分区镜像；
- `recovery.img`：原厂recovery整分区镜像。生成器自行解开它的ramdisk，取出QTI的启动控制HAL、健康HAL及其依赖库、init脚本、VINTF片段与fstab；
- `vendor/`、`odm/`：vendor与odm镜像解包后的目录，生成器从中读取`build.prop`和`etc/vintf`；
- `system/`、`system_ext/`、`product/`：这三个镜像解包后的目录，生成器从中复制IMS的原厂件、截取它的特权权限白名单，并解码原厂覆盖层里编译过的XML。

<!-- 分开中英两个列表 | keeps the two lists apart -->

The generator reads the stock RedMagicOS 11.0.23MR1 files from an input directory and checks their hashes against `config/device/NX809J-inputs.json`:

- `boot.img`, `vendor.img`, `odm.img`, `vendor_dlkm.img` and `system_dlkm.img`: the stock partition images;
- `recovery.img`: the whole stock recovery partition. The generator unpacks its ramdisk itself and takes out the QTI boot control and health HALs with their libraries, init scripts and VINTF fragments, and the fstab;
- `vendor/` and `odm/`: the unpacked vendor and odm images, from which the generator reads `build.prop` and `etc/vintf`;
- `system/`, `system_ext/` and `product/`: the unpacked images of these partitions, from which the generator copies the stock IMS files, takes the IMS block of the privileged permission allowlist and decodes the compiled XML of a stock overlay.

### 生成与构建 | Generate and Build

```bash
python3 vendor/mdevtool/generate.py NX809J --input-root <输入目录 | input directory>
source build/envsetup.sh
lunch NX809J cp2a userdebug
m target-files-package otatools-package initbootimage
```

生成的vendor树记录了生成时mdevtool的提交号。mdevtool之后若有新提交，构建会提醒重新生成；设置`MDEVTOOL_RELEASE=true`时直接报错，发布构建应当这样做。

The generated vendor tree records the mdevtool commit it came from. If mdevtool has moved on since, the build warns that it needs regenerating; with `MDEVTOOL_RELEASE=true` it fails instead, which is what release builds should use.

更改配方或配置使生成结果变化时，审过差异后运行：

When a change to the recipes or the configuration changes the output, review the difference and then run:

```bash
python3 vendor/mdevtool/generate.py NX809J --input-root <输入目录 | input directory> --update-spec
```

并把`vendor-specs`与`vendor-skels`的改动一同提交。

and commit the changes to `vendor-specs` and `vendor-skels` together with it.

## 范围 | Scope

mdevtool只为GrimoireOS服务，不打算成为通用的移植工具。欢迎借鉴，但配方与配置里的取值都是针对这台机器核实的。

mdevtool serves GrimoireOS only and is not meant to be a general porting tool. You are welcome to borrow from it, but the values in its recipes and configuration were verified for this one device.

> [!NOTE]
> 目前公开的部分止于构建：按上面的步骤能编出target-files，但签名、打包部分OTA与首次安装所用的工具暂不公开。其中只有init_boot的后处理`scripts/finalize-init-boot.py`随mdevtool提供。
>
> What is public currently stops at the build: the steps above produce target-files, but the tools that sign them and package partial OTAs or first installs are not published for now. Only the init_boot post-processing, `scripts/finalize-init-boot.py`, comes with mdevtool.

## 已知限制 | Known Limitations

recovery能启动、显示菜单、接受adb连接，也能读取电量。IMS沿用原厂的QTI实现，已在网络上注册成功，VoLTE去电能经IMS发起。散热由机型层的设置应用控制风扇和液冷泵，两者各有自动、手动两种模式，风扇的自动模式按机身温度调档。屏幕最高可到144 Hz，设置里有流畅画面开关；SIM热插拔、默认卡自动切换、5G SA与NSA，以及Wi-Fi通话所需的IWLAN服务，沿用原厂设置。自动亮度沿用原厂的亮度曲线、防抖时间与环境光阈值；光感在屏幕下方，会读到屏幕自身的光，机型层的一个系统应用像原厂一样把光感上方的屏幕颜色与背光交给传感器HAL，由它扣除。下面这些目前不可用或尚未验证：

Recovery boots, shows its menu, accepts adb connections and reads the battery. IMS uses the stock QTI implementation, registers with the network and places VoLTE calls through IMS. A settings app in the device layer runs the fan and the liquid cooling pump, each in an automatic or a manual mode; the automatic fan level follows the body temperature. The display reaches 144 Hz, with a Smooth Display switch in Settings; SIM hot swap, automatic default SIM fallback, 5G SA and NSA, and the IWLAN services that Wi-Fi calling needs follow the stock settings. Auto-brightness uses the stock brightness curve, debounce times and ambient light thresholds. The light sensor sits under the display and also reads the display's own light; as on the stock system, a system app in the device layer passes the colour and backlight over the sensor to the sensor HAL, which subtracts them. The following doesn't work or hasn't been verified yet:

- VoLTE语音通话还没有接通过，视频通话和Wi-Fi通话还没有试过；RCS服务没有安装。
- 启动控制HAL不随recovery自动启动。手动启动后，SELinux不允许它列出分区、读取分区节点的属性，所有槽位查询都会失败。
- 因此fastbootd报告的槽位信息不可信，不要用它刷写分区。
- 尚未验证用`adb sideload`安装更新包。
- recovery里SELinux拒绝了屏幕亮度的调节，不影响显示。
- 高通的移动数据优先级服务qmipriod能正常运行，但它向主、副SIM查询优先级状态都返回无效，对移动数据有没有影响尚未确认。
- 散热的自动模式没有原厂为游戏和跑分准备的场景；通话、锁屏和充电时自动启停的长时间表现尚未验证。
- 刷新率没有原厂在低亮度下的固定档、高温降档和按应用的名单。
- 亮度变化以平台的恒定速率渐变，没有原厂按亮度区间变速的渐变，变暗比原厂快得多。横屏时的补偿区域与长时间使用的耗电尚未验证。

<!-- 分开中英两个列表 | keeps the two lists apart -->

- No VoLTE voice call has been answered yet, and neither video calls nor Wi-Fi calling have been tried; the RCS service isn't installed.
- The boot control HAL doesn't start with recovery. Started by hand, it is denied by SELinux when it lists the partitions and reads the attributes of their device nodes, so every slot query fails.
- fastbootd therefore reports wrong slot information; don't flash partitions with it.
- Installing updates with `adb sideload` hasn't been verified.
- In recovery, SELinux denies the screen brightness controls; the display still works.
- Qualcomm's mobile data priority service, qmipriod, runs, but its priority state queries on both SIM subscriptions come back invalid; whether this affects mobile data hasn't been checked.
- The automatic cooling modes lack the stock scenes for games and benchmarks, and how they start and stop around calls, the lock screen and charging over long use hasn't been checked.
- The refresh rate lacks the stock fixed rate at low brightness, the thermal limits and the per-app lists.
- Brightness changes ramp at the platform's constant rate rather than with the stock ramp, which changes speed by brightness range, so dimming is much faster than on the stock system. The compensation in landscape and its power use over long periods haven't been checked.

## 授权 | License

mdevtool以MIT许可证发布，与adevtool相同，见[LICENSE](LICENSE)。

mdevtool is licensed under the MIT License, like adevtool; see [LICENSE](LICENSE).

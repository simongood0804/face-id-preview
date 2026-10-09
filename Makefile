# =============================================================================
# Face ID Preview — 开发快捷命令
#
# 用法:
#   make build       编译 APK
#   make install     安装到设备（adb install）
#   make push-system 推送 APK 到 /system/app/（需 root）
#   make uninstall   卸载应用
#   make run         启动应用
#   make pc-up       一键启动 PC 侧推流环境（MediaMTX + 控制中继 + 播放页，含自检）
#   make pc-check    自检 PC 侧推流环境
#   make pc-down     停止 PC 侧推流环境
#   make test        运行所有单元测试
#   make test-class  运行指定测试类（例: make test-class CLASS=PipelineConfigTest）
#   make test-suite  运行测试套件
#   make log         查看实时日志（过滤 FaceID 相关）
#   make log-crash   查看崩溃日志
#   make log-evs     查看 EvsSDK 相关日志
#   make gpu         实时监控 GPU 使用率
#   make top         查看进程资源占用
#   make dumpsys     查看应用状态
#   make clean       清理构建产物
# =============================================================================

# ------ 项目配置 ------
# JDK 路径：优先环境变量/命令行参数（make JAVA_HOME=/path）；
# macOS 自动探测 JDK 11，其他平台需显式指定（如 make JAVA_HOME=/usr/lib/jvm/java-11）
JAVA_HOME ?= $(shell /usr/libexec/java_home -v 11 2>/dev/null)
# 包名与安装位置：默认本项目的 DmsFace。
# 需要**顶替车机预装的其它系统应用**（例如标定软件 AVM_Calibrate）时，命令行覆盖这三项：
#   make push-system PACKAGE_NAME=com.mediapipe.avm \
#                    SYSTEM_APP_DIR=/system/app/AVM_Calibrate APK_NAME=AVM_Calibrate.apk
# 说明：
#   * PACKAGE_NAME 经 ORG_GRADLE_PROJECT_appId 传给 Gradle（Gradle 原生支持该前缀的环境变量），
#     保证 APK 内的 applicationId 与部署位置一致，不必给每条 gradlew 命令加参数；
#   * push-system 会先把目标目录下**文件名不同**的 APK 备份到 PC 的 /tmp 再删除，
#     避免新旧两个包并存（预装软件被顶掉后仍可用备份还原）；
#   * 目标包若已存在，**签名 / sharedUserId / 版本号**可能冲突，先跑 `make probe-replace` 体检。
PACKAGE_NAME    ?= com.skyworth.faceid
# 启动 Activity 用**完整类名**：包名可被顶替，但类名仍属 com.skyworth.faceid（namespace 未变），
# 若写成 `.ui.HomeActivity`，`am start -n <新包名>/.ui.HomeActivity` 会展开成新包名下的类而找不到。
ACTIVITY_NAME   := com.skyworth.faceid.ui.HomeActivity
APK_PATH        := app/build/outputs/apk/release/app-release.apk
SYSTEM_APP_DIR  ?= /system/app/DmsFace
APK_NAME        ?= DmsFace.apk
# 车机相机命名方案配置文件（App 启动时读一次；按车型推送，不用重编 APK）
CAMERA_PROFILE  ?= /vendor/etc/faceid/evs_camera_profile.json
export ORG_GRADLE_PROJECT_appId := $(PACKAGE_NAME)
# 模型文件（dlc + manifest）源目录与车机 vendor 目标目录
MODEL_ASSET_DIR   := app/src/main/assets/models
VENDOR_MODEL_DIR  := /vendor/etc/faceid

# ------ PC 侧推流环境（车机推流的对端：MediaMTX + 控制中继）------
# 说明：MediaMTX 手工启动读 ~/mediamtx.yml（若用 brew services 则读 /opt/homebrew/etc/mediamtx/）。
# 控制中继 server.py 与播放页 index.html 来自 media_record 库仓库，不在本仓库内。
MEDIAMTX_CONFIG   ?= $(HOME)/mediamtx.yml
MEDIAMTX_LOG      ?= /tmp/mediamtx.log
RELAY_DIR         ?= $(CURDIR)/../media_record/media_record/tools/camera-switch-demo
RELAY_LOG         ?= /tmp/relay.log
WHIP_PORT         ?= 8889
RELAY_PORT        ?= 8081
RELAY_STREAM      ?= test
# 打开播放页用的主机：**多网段时优先 192.***（演示通常跑在 192 网段的 WiFi 局域网；
# 这台 Mac 就是 en0=10.14.11.42、en1=192.168.6.233，取 en0 会指错网段）。
# 规则：本机所有非回环 IPv4 → 优先取 192.* → 否则第一个 → 都没有则 localhost。
# 可覆盖：make pc-up PAGE_HOST=192.168.6.233
PAGE_HOST         ?= $(shell A=$$(ifconfig 2>/dev/null | awk '/inet /{print $$2}' | grep -v '^127\.'); \
                          P=$$(echo "$$A" | grep '^192\.' | head -1); \
                          [ -n "$$P" ] || P=$$(echo "$$A" | head -1); echo "$${P:-localhost}")

# ------ 颜色输出 ------
RED    := \033[0;31m
GREEN  := \033[0;32m
YELLOW := \033[1;33m
NC     := \033[0m

.PHONY: build clean-build install push-system uninstall probe-replace push-profile run stop restart \
        pc-up pc-check pc-down \
        log log-crash log-evs log-last gpu top mem dumpsys pid \
        clean help test test-class test-suite test-report

# =============================================================================
# 构建
# =============================================================================

## 编译 Release APK（增量，不清缓存）
build:
	@echo "$(GREEN)[BUILD] compiling...$(NC)"
	JAVA_HOME="$(JAVA_HOME)" ./gradlew assembleRelease --no-daemon --no-build-cache
	@echo "$(GREEN)[BUILD] done: $(APK_PATH)$(NC)"

## 先 clean 再编译 Release APK（清除 Gradle 缓存，确保 native 库等是最新 AAR）
clean-build:
	@echo "$(GREEN)[CLEAN-BUILD] cleaning...$(NC)"
	JAVA_HOME="$(JAVA_HOME)" ./gradlew clean --no-daemon
	@echo "$(GREEN)[CLEAN-BUILD] compiling...$(NC)"
	JAVA_HOME="$(JAVA_HOME)" ./gradlew assembleRelease --no-daemon --no-build-cache
	@echo "$(GREEN)[CLEAN-BUILD] done: $(APK_PATH)$(NC)"

# =============================================================================
# 安装与卸载
# =============================================================================

## 安装到设备（adb install，仅用于验证 UI 逻辑；EvsSDK 系统库不可访问）
install: build
	@echo "$(GREEN)[INSTALL] installing...$(NC)"
	adb uninstall $(PACKAGE_NAME) 2>/dev/null || true
	adb install -r -d $(APK_PATH)
	@echo "$(GREEN)[INSTALL] done$(NC)"
	@echo "$(YELLOW)  注意: adb install 方式无法访问 libevsservicejni.so$(NC)"
	@echo "$(YELLOW)  如需完整功能，使用: make push-system$(NC)"

## 推送 APK 到 /system/app/（EvsSDK 完整功能需要此方式；先 clean 避免 Gradle 缓存旧 native 库）
push-system: clean-build
	@echo "$(GREEN)[PUSH-SYSTEM] waiting for device...$(NC)"
	adb wait-for-device
	@echo "$(GREEN)[PUSH-SYSTEM] remounting...$(NC)"
	adb root
	adb wait-for-device
	adb remount
	@echo "$(GREEN)[PUSH-SYSTEM] 顶替前清理：备份并移除目标目录下其它 APK...$(NC)"
	@adb shell "ls $(SYSTEM_APP_DIR)/*.apk 2>/dev/null" | tr -d '\r' | while read -r f; do \
		if [ -n "$$f" ] && [ "$$(basename $$f)" != "$(APK_NAME)" ]; then \
			echo "  备份到 /tmp/$$(basename $$f).bak 并移除 $$f"; \
			adb pull "$$f" "/tmp/$$(basename $$f).bak" >/dev/null 2>&1 || true; \
			adb shell rm -f "$$f"; \
		fi; \
	done; true
	@echo "$(GREEN)[PUSH-SYSTEM] creating directory...$(NC)"
	adb shell mkdir -p $(SYSTEM_APP_DIR)/lib/arm64
	@echo "$(GREEN)[PUSH-SYSTEM] pushing APK to device temp（先推临时文件：adb push 直接覆盖系统文件时会先把目标删掉，传输一旦中断就留成 whiteout）...$(NC)"
	adb push $(APK_PATH) /data/local/tmp/$(APK_NAME).new
	@echo "$(GREEN)[PUSH-SYSTEM] verifying transfer (md5)...$(NC)"
	@L=$$(md5 -q $(APK_PATH) 2>/dev/null || md5sum $(APK_PATH) | awk '{print $$1}'); \
	 R=$$(adb shell "md5sum /data/local/tmp/$(APK_NAME).new" | tr -d '\r' | awk '{print $$1}'); \
	 echo "  local =$$L"; echo "  device=$$R"; \
	 if [ "$$L" != "$$R" ]; then echo "$(RED)  MD5 不一致，已中止（系统目录未被改动）$(NC)"; exit 1; fi
	@echo "$(GREEN)[PUSH-SYSTEM] installing into $(SYSTEM_APP_DIR)...$(NC)"
	adb shell "cp /data/local/tmp/$(APK_NAME).new $(SYSTEM_APP_DIR)/$(APK_NAME) && chmod 644 $(SYSTEM_APP_DIR)/$(APK_NAME) && chown root:root $(SYSTEM_APP_DIR)/$(APK_NAME)"
	@echo "$(GREEN)[PUSH-SYSTEM] extracting and pushing native libs...$(NC)"
	cd /tmp && rm -rf apk_libs && mkdir apk_libs && cd apk_libs && \
	unzip -o $(CURDIR)/$(APK_PATH) "lib/arm64-v8a/*" && \
	adb push lib/arm64-v8a/*.so $(SYSTEM_APP_DIR)/lib/arm64/
	@echo "$(GREEN)[PUSH-SYSTEM] pushing models (dlc + manifest + calibration) to vendor...$(NC)"
	adb shell mkdir -p $(VENDOR_MODEL_DIR)
	adb push $(MODEL_ASSET_DIR)/*.dlc $(MODEL_ASSET_DIR)/manifest.json $(MODEL_ASSET_DIR)/dms_calibration.json $(VENDOR_MODEL_DIR)/
	adb shell chmod 644 $(VENDOR_MODEL_DIR)/*.dlc $(VENDOR_MODEL_DIR)/manifest.json $(VENDOR_MODEL_DIR)/dms_calibration.json
	adb shell chown root:root $(VENDOR_MODEL_DIR)/*.dlc $(VENDOR_MODEL_DIR)/manifest.json $(VENDOR_MODEL_DIR)/dms_calibration.json
	@echo "$(GREEN)[PUSH-SYSTEM] clearing app cache (force re-extract latest assets)...$(NC)"
	adb shell rm -rf /data/user/0/$(PACKAGE_NAME)/files/models
	@echo "$(GREEN)[PUSH-SYSTEM] setting permissions...$(NC)"
	adb shell chmod 644 $(SYSTEM_APP_DIR)/$(APK_NAME)
	adb shell chmod 644 $(SYSTEM_APP_DIR)/lib/arm64/*.so
	adb shell chown root:root $(SYSTEM_APP_DIR)/$(APK_NAME)
	adb shell chown root:root $(SYSTEM_APP_DIR)/lib/arm64/*.so
	@echo "$(GREEN)[PUSH-SYSTEM] clearing oat cache...$(NC)"
	adb shell rm -rf $(SYSTEM_APP_DIR)/oat
	@echo "$(GREEN)[PUSH-SYSTEM] rebooting...$(NC)"
	adb reboot
	@echo "$(GREEN)[PUSH-SYSTEM] waiting for device...$(NC)"
	adb wait-for-device
	@echo "$(GREEN)[PUSH-SYSTEM] done$(NC)"
	@echo "$(YELLOW)  应用已部署到 $(SYSTEM_APP_DIR)/$(APK_NAME)$(NC)"
	@echo "$(YELLOW)  native libs 已部署到 $(SYSTEM_APP_DIR)/lib/arm64/$(NC)"
	@echo "$(YELLOW)  模型(dlc+manifest)已部署到 $(VENDOR_MODEL_DIR)/$(NC)"
	@echo "$(YELLOW)  已清空应用模型缓存(files/models)$(NC)"
	@echo "$(YELLOW)  运行: make run$(NC)"

## 卸载应用
uninstall:
	@echo "$(GREEN)[UNINSTALL] uninstalling...$(NC)"
	adb uninstall $(PACKAGE_NAME) 2>/dev/null && \
		echo "$(GREEN)[UNINSTALL] done$(NC)" || \
		echo "$(YELLOW)[UNINSTALL] package not found$(NC)"

## 设置车机相机命名方案（按车型；**不用重编 APK**，改完需重启 App 生效）
## 用法:
##   make push-profile MODE=van233   # 老 van233：FVC / RBS / RVC / LBS + RVC + DMS
##   make push-profile MODE=avm      # minibus 等：AVMF / AVMR / AVMB / AVML + RVC + DMS
##   make push-profile MODE=auto     # 按固件 /vendor/etc/evs_hal_devices.xml 自动识别（默认）
##   make push-profile ORDER=AVMF,AVMR,AVMB,AVML,RVC,DMS    # 完全手动指定 6 路
push-profile:
	@echo "$(GREEN)[PROFILE] 写入相机命名方案 → $(CAMERA_PROFILE)$(NC)"
	@if [ -n "$(ORDER)" ]; then \
		echo "{\"mode\":\"order\",\"order\":[\"$$(echo $(ORDER) | sed 's/,/","/g')\"]}" > /tmp/evs_camera_profile.json; \
	else \
		echo "{\"mode\":\"$(if $(MODE),$(MODE),auto)\",\"order\":[]}" > /tmp/evs_camera_profile.json; \
	fi
	@cat /tmp/evs_camera_profile.json
	adb root >/dev/null 2>&1 || true
	adb wait-for-device
	adb remount >/dev/null 2>&1 || true
	adb shell mkdir -p $$(dirname $(CAMERA_PROFILE))
	adb push /tmp/evs_camera_profile.json $(CAMERA_PROFILE)
	adb shell chmod 644 $(CAMERA_PROFILE)
	@echo "$(YELLOW)  已写入；重启 App 生效：make stop && make run$(NC)"

## 顶替预装软件前的**只读**体检（不改任何东西）
## 用法: make probe-replace PACKAGE_NAME=<标定软件包名> SYSTEM_APP_DIR=<它的目录>
probe-replace:
	@echo "$(GREEN)[PROBE] 目标包名 = $(PACKAGE_NAME)$(NC)"
	@echo "$(YELLOW)  目标安装位置 = $(SYSTEM_APP_DIR)/$(APK_NAME)$(NC)"
	@echo "$(YELLOW)  --- 1) 包是否已安装（含签名/共享 UID/ABI）---$(NC)"
	@adb shell "dumpsys package $(PACKAGE_NAME) 2>/dev/null | grep -iE 'codePath|resourcePath|versionCode|versionName|sharedUser|userId=|primaryCpuAbi|flags=|signatures'" || echo "  (无输出：该包未安装)"
	@echo "$(YELLOW)  --- 2) 它在 packages.xml 里的记录 ---$(NC)"
	@adb shell "grep -nE '<(package|updated-package) name=\"$(PACKAGE_NAME)\"|<item name=\"$(PACKAGE_NAME)\"' /data/system/packages.xml" || echo "  (无记录)"
	@echo "$(YELLOW)  --- 3) 各 shared-user（判断它属于哪个共享 UID）---$(NC)"
	@adb shell "grep -n 'shared-user name' /data/system/packages.xml" || true
	@echo "$(YELLOW)  --- 4) packages.list 记录 ---$(NC)"
	@adb shell "grep -n '^$(PACKAGE_NAME) ' /data/system/packages.list" || echo "  (无记录)"
	@echo "$(YELLOW)  --- 5) 目标目录现有文件 ---$(NC)"
	@adb shell "ls -l $(SYSTEM_APP_DIR)/ 2>/dev/null" || true

# =============================================================================
# 运行
# =============================================================================

## 启动应用
run:
	@echo "$(GREEN)[RUN] starting $(PACKAGE_NAME)...$(NC)"
	adb shell am start -n $(PACKAGE_NAME)/$(ACTIVITY_NAME)
	@echo "$(GREEN)[RUN] done$(NC)"

## 强制停止应用
stop:
	@echo "$(GREEN)[STOP] force stopping...$(NC)"
	adb shell am force-stop $(PACKAGE_NAME)
	@echo "$(GREEN)[STOP] done$(NC)"

## 重启应用
restart: stop run

# =============================================================================
# PC 侧推流环境（MediaMTX + 控制中继 server.py）—— 一条命令启动
# =============================================================================

## 一键启动 PC 侧推流环境：准备配置 → 起 MediaMTX → 起中继 → 自检 → 打开播放页
pc-up:
	@echo "$(GREEN)[PC-UP] 配置: $(MEDIAMTX_CONFIG)$(NC)"
	@[ -f $(MEDIAMTX_CONFIG) ] || printf 'paths:\n  test:\n    source: publisher\n' > $(MEDIAMTX_CONFIG)
	@echo "$(GREEN)[PC-UP] 启动 MediaMTX（已在跑则跳过）...$(NC)"
	@if pgrep -q mediamtx; then echo "$(YELLOW)  已在运行，跳过$(NC)"; else (cd $(HOME) && nohup mediamtx > $(MEDIAMTX_LOG) 2>&1 &); fi
	@echo "$(GREEN)[PC-UP] 启动控制中继（已在跑则跳过）...$(NC)"
	@if lsof -nP -i :$(RELAY_PORT) >/dev/null 2>&1; then echo "$(YELLOW)  已在运行，跳过$(NC)"; else (cd $(RELAY_DIR) && nohup python3 $(RELAY_DIR)/server.py > $(RELAY_LOG) 2>&1 &); fi
	@sleep 2
	@$(MAKE) --no-print-directory pc-check
	@echo "$(GREEN)[PC-UP] 打开播放页 http://$(PAGE_HOST):$(RELAY_PORT)/$(NC)"
	@open "http://$(PAGE_HOST):$(RELAY_PORT)/"
	@echo "$(YELLOW)  下一步：车机 App → 推流测试校验 → 开始推流$(NC)"
	@echo "$(YELLOW)  推流地址: http://<PC-IP>:$(WHIP_PORT)/$(RELAY_STREAM)/whip$(NC)"

## 自检 PC 侧推流环境（配置是否放行、中继是否在跑）
pc-check:
	@code=$$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 http://127.0.0.1:$(WHIP_PORT)/$(RELAY_STREAM)/ 2>/dev/null || true); \
	case "$$code" in \
		200) echo "  播放页/配置 : 200   OK —— 已放行，可以推流";; \
		500) echo "  播放页/配置 : 500   path 未放行 → 检查 $(MEDIAMTX_CONFIG) 的 paths";; \
		000|"") echo "  播放页/配置 : ----  MediaMTX 未启动（make pc-up）";; \
		*) echo "  播放页/配置 : $$code";; \
	esac
	@relay=$$(curl -s --max-time 3 http://127.0.0.1:$(RELAY_PORT)/state 2>/dev/null || true); \
	if [ -n "$$relay" ]; then echo "  中继 /state : $$relay"; \
	else echo "  中继 /state : ----  中继未启动（可选，仅影响浏览器切摄像头）"; fi

## 停止 PC 侧推流环境（MediaMTX + 控制中继）
pc-down:
	@echo "$(GREEN)[PC-DOWN] 停止控制中继与 MediaMTX...$(NC)"
	@pkill -f 'camera-switch-demo.*server\.py' || true
	@pkill mediamtx || true
	@sleep 1
	@pgrep -q mediamtx && echo "$(RED)  MediaMTX 仍在运行$(NC)" || echo "$(YELLOW)  MediaMTX 已停止$(NC)"
	@lsof -nP -i :$(RELAY_PORT) >/dev/null 2>&1 && echo "$(RED)  中继端口 $(RELAY_PORT) 仍被占用$(NC)" || echo "$(YELLOW)  中继已停止$(NC)"

# =============================================================================
# 日志
# =============================================================================

## 查看实时日志（过滤 FaceIDPreview 和 FaceID 相关）
log:
	@echo "$(GREEN)[LOG] filtering $(PACKAGE_NAME)...$(NC)"
	adb logcat -v time | grep -E "$(PACKAGE_NAME)|FaceID|PreviewActivity|CameraManager|FramePipeline|BufferManager|PreviewRenderer"

## 查看崩溃日志
log-crash:
	@echo "$(GREEN)[LOG-CRASH] showing crash logs...$(NC)"
	adb logcat -d -v time | grep -E "FATAL|CRASH|AndroidRuntime|NativeCrash|$(PACKAGE_NAME)" | tail -50

## 查看 EvsSDK 相关日志
log-evs:
	@echo "$(GREEN)[LOG-EVS] showing EvsSDK logs...$(NC)"
	adb logcat -v time | grep -E "EvsHalWrapper|EvsCameraController|EvsCamera|libevsservicejni"

## 查看最近 200 行日志
log-last:
	@echo "$(GREEN)[LOG-LAST] last 200 lines...$(NC)"
	adb logcat -d -v time | grep -E "$(PACKAGE_NAME)" | tail -200

# =============================================================================
# 监控
# =============================================================================

## 实时监控 GPU 使用率（基于 /d/ion/ 或 dumpsys）
gpu:
	@echo "$(GREEN)[GPU] monitoring GPU usage (refresh every 2s)...$(NC)"
	@echo "$(YELLOW)  GPU Freq | GPU Load | Memory$(NC)"
	@echo "$(YELLOW)  ---------------------------------------$(NC)"
	@while true; do \
		echo "--- $$(date +%H:%M:%S) ---"; \
		adb shell dumpsys gfxinfo $(PACKAGE_NAME) 2>/dev/null | grep -E "Visible|Cached|Alloc|Total" | head -8; \
		adb shell cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq 2>/dev/null | awk '{printf "GPU Freq: %d MHz\n", $$1/1000000}'; \
		adb shell cat /sys/class/kgsl/kgsl-3d0/gpubusy 2>/dev/null | awk '{if ($$2 > 0) printf "GPU Load: %d%%\n", $$1*100/$$2}'; \
		sleep 2; \
	done

## 查看进程资源占用（CPU + 内存）
top:
	@echo "$(GREEN)[TOP] resource usage for $(PACKAGE_NAME)...$(NC)"
	@echo "  PID  CPU%  MEM   VSS     RSS     NAME"
	adb shell top -b -n 1 | grep "$(PACKAGE_NAME)" | awk '{printf "  %-5s %-5s %-5s %-7s %-7s %s\n", $$1, $$9, $$10, $$6, $$7, $$12}'

## 查看应用内存详情
mem:
	@echo "$(GREEN)[MEM] memory info...$(NC)"
	adb shell dumpsys meminfo $(PACKAGE_NAME)

# =============================================================================
# 诊断
# =============================================================================

## 查看应用状态（package info）
dumpsys:
	adb shell dumpsys package $(PACKAGE_NAME)

## 查看 PID
pid:
	@adb shell pidof $(PACKAGE_NAME) 2>/dev/null || echo "$(RED)not running$(NC)"

# =============================================================================
# 单元测试
# =============================================================================

## 运行所有单元测试
test:
	@echo "$(GREEN)[TEST] running all unit tests...$(NC)"
	JAVA_HOME="$(JAVA_HOME)" ./gradlew app:testDebugUnitTest --no-daemon
	@echo "$(GREEN)[TEST] done$(NC)"

## 运行指定测试类
# 用法: make test-class CLASS=PipelineConfigTest
#       make test-class CLASS=BufferManagerTest
#       make test-class CLASS=IFaceIDAlgorithmTest
test-class:
	@if [ -z "$(CLASS)" ]; then \
		echo "$(RED)请指定 CLASS 参数，例: make test-class CLASS=PipelineConfigTest$(NC)"; \
		exit 1; \
	fi
	@echo "$(GREEN)[TEST-CLASS] running $(CLASS)...$(NC)"
	JAVA_HOME="$(JAVA_HOME)" ./gradlew app:testDebugUnitTest --no-daemon --tests "com.skyworth.faceid.pipeline.$(CLASS)" \
		--tests "com.skyworth.faceid.algorithm.$(CLASS)"
	@echo "$(GREEN)[TEST-CLASS] done$(NC)"

## 运行测试套件
test-suite:
	@echo "$(GREEN)[TEST-SUITE] running test suite...$(NC)"
	JAVA_HOME="$(JAVA_HOME)" ./gradlew app:testDebugUnitTest --no-daemon --tests "com.skyworth.faceid.FaceIDPreviewTestSuite"
	@echo "$(GREEN)[TEST-SUITE] done$(NC)"

## 查看测试报告
test-report:
	@echo "$(GREEN)[TEST-REPORT] opening report...$(NC)"
	@open app/build/reports/tests/testDebugUnitTest/index.html 2>/dev/null || \
		echo "$(YELLOW)  报告文件: app/build/reports/tests/testDebugUnitTest/index.html$(NC)"

# =============================================================================
# 清理
# =============================================================================

## 清理构建产物
clean:
	@echo "$(GREEN)[CLEAN] cleaning...$(NC)"
	./gradlew clean --no-daemon
	@echo "$(GREEN)[CLEAN] done$(NC)"

# =============================================================================
# 帮助
# =============================================================================

## 显示帮助信息
help:
	@echo "Face ID Preview — 开发快捷命令"
	@echo ""
	@echo "用法: make <target>"
	@echo ""
	@echo "--- 构建 ---"
	@echo "  build          增量编译 Release APK（不清 Gradle 缓存）"
	@echo "  clean-build    先 clean 再编译 Release APK（确保 native 库等为最新 AAR）"
	@echo ""
	@echo "--- 部署 ---"
	@echo "  install        安装到设备（adb install）"
	@echo "  push-system    先 clean-build 再推送 APK 到 /system/app/（需 root，含重启）"
	@echo "  uninstall      卸载应用"
	@echo ""
	@echo "--- 运行 ---"
	@echo "  run            启动应用"
	@echo "  stop           强制停止应用"
	@echo "  restart        重启应用"
	@echo ""
	@echo "--- PC 侧推流环境（车机推流的对端）---"
	@echo "  pc-up          一键启动 MediaMTX + 控制中继 + 打开播放页，并自检"
	@echo "  pc-check       只自检（配置是否放行、中继 /state）"
	@echo "  pc-down        停止 MediaMTX 与控制中继"
	@echo ""
	@echo "--- 测试 ---"
	@echo "  test           运行所有单元测试"
	@echo "  test-class     运行指定测试类（CLASS=PipelineConfigTest）"
	@echo "  test-suite     运行测试套件"
	@echo "  test-report    打开测试报告"
	@echo ""
	@echo "--- 日志 ---"
	@echo "  log            实时日志（过滤 FaceID 相关）"
	@echo "  log-crash      查看崩溃日志"
	@echo "  log-evs        查看 EvsSDK 相关日志"
	@echo "  log-last       查看最近 200 行日志"
	@echo ""
	@echo "--- 监控 ---"
	@echo "  gpu            实时监控 GPU 使用率（每 2s 刷新）"
	@echo "  top            查看进程资源占用"
	@echo "  mem            查看应用内存详情"
	@echo ""
	@echo "--- 诊断 ---"
	@echo "  dumpsys        查看应用状态"
	@echo "  pid            查看进程 PID"
	@echo ""
	@echo "--- 清理 ---"
	@echo "  clean          清理构建产物"
	@echo ""
	@echo "--- 示例 ---"
	@echo "  make test                       # 运行所有测试"
	@echo "  make test-class CLASS=PipelineConfigTest  # 运行指定测试"
	@echo "  make test-suite                 # 运行测试套件"
	@echo "  make install && make run        # 安装并启动"

# =============================================================================
# Face ID Preview — 开发快捷命令
#
# 用法:
#   make build       编译 APK
#   make install     安装到设备（adb install）
#   make push-system 推送 APK 到 /system/app/（需 root）
#   make uninstall   卸载应用
#   make run         启动应用
#   make log         查看实时日志（过滤 FaceID 相关）
#   make log-crash   查看崩溃日志
#   make log-evs     查看 EvsSDK 相关日志
#   make gpu         实时监控 GPU 使用率
#   make top         查看进程资源占用
#   make dumpsys     查看应用状态
#   make clean       清理构建产物
# =============================================================================

# ------ 项目配置 ------
PACKAGE_NAME    := com.skyworth.faceid
ACTIVITY_NAME   := .ui.PreviewActivity
APK_PATH        := app/build/outputs/apk/debug/app-debug.apk
SYSTEM_APP_DIR  := /system/app/FaceIDPreview
APK_NAME        := FaceIDPreview.apk

# ------ 颜色输出 ------
RED    := \033[0;31m
GREEN  := \033[0;32m
YELLOW := \033[1;33m
NC     := \033[0m

.PHONY: build install push-system uninstall run log log-crash log-evs \
        gpu top dumpsys clean help

# =============================================================================
# 构建
# =============================================================================

## 编译调试 APK（平台签名）
build:
	@echo "$(GREEN)[BUILD] compiling...$(NC)"
	JAVA_HOME=/Users/simon/Library/Java/JavaVirtualMachines/corretto-11.0.25/Contents/Home \
	./gradlew assembleDebug --no-daemon
	@echo "$(GREEN)[BUILD] done: $(APK_PATH)$(NC)"

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

## 推送 APK 到 /system/app/（EvsSDK 完整功能需要此方式）
push-system: build
	@echo "$(GREEN)[PUSH-SYSTEM] remounting...$(NC)"
	adb root
	adb remount
	@echo "$(GREEN)[PUSH-SYSTEM] creating directory...$(NC)"
	adb shell mkdir -p $(SYSTEM_APP_DIR)
	@echo "$(GREEN)[PUSH-SYSTEM] pushing APK...$(NC)"
	adb push $(APK_PATH) $(SYSTEM_APP_DIR)/$(APK_NAME)
	@echo "$(GREEN)[PUSH-SYSTEM] setting permissions...$(NC)"
	adb shell chmod 644 $(SYSTEM_APP_DIR)/$(APK_NAME)
	adb shell chown root:root $(SYSTEM_APP_DIR)/$(APK_NAME)
	@echo "$(GREEN)[PUSH-SYSTEM] rebooting...$(NC)"
	adb reboot
	@echo "$(GREEN)[PUSH-SYSTEM] waiting for device...$(NC)"
	adb wait-for-device
	@echo "$(GREEN)[PUSH-SYSTEM] done$(NC)"
	@echo "$(YELLOW)  应用已部署到 $(SYSTEM_APP_DIR)/$(APK_NAME)$(NC)"
	@echo "$(YELLOW)  运行: make run$(NC)"

## 卸载应用
uninstall:
	@echo "$(GREEN)[UNINSTALL] uninstalling...$(NC)"
	adb uninstall $(PACKAGE_NAME) 2>/dev/null && \
		echo "$(GREEN)[UNINSTALL] done$(NC)" || \
		echo "$(YELLOW)[UNINSTALL] package not found$(NC)"

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
	@echo "  build          编译调试 APK（平台签名）"
	@echo ""
	@echo "--- 部署 ---"
	@echo "  install        安装到设备（adb install）"
	@echo "  push-system    推送 APK 到 /system/app/（需 root，含重启）"
	@echo "  uninstall      卸载应用"
	@echo ""
	@echo "--- 运行 ---"
	@echo "  run            启动应用"
	@echo "  stop           强制停止应用"
	@echo "  restart        重启应用"
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
	@echo "  make install && make run"
	@echo "  make log"
	@echo "  make gpu"

LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := thermal_logger
LOCAL_CFLAGS := -DNOISY
LOCAL_SRC_FILES := \
	thermal_logger.cc
LOCAL_LDLIBS :=
LOCAL_STATIC_LIBRARIES :=
LOCAL_SHARED_LIBRARIES :=
include $(BUILD_EXECUTABLE)


LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := imu_logger
LOCAL_SRC_FILES := \
	main.cpp
LOCAL_LDLIBS := -llog -landroid
LOCAL_STATIC_LIBRARIES :=
LOCAL_SHARED_LIBRARIES :=
include $(BUILD_EXECUTABLE)


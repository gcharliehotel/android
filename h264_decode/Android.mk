LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := h264_decode
LOCAL_SRC_FILES := \
	main.cpp \
	media_status_helper.cpp
LOCAL_LDLIBS := -llog -landroid -lmediandk
LOCAL_STATIC_LIBRARIES :=
LOCAL_SHARED_LIBRARIES :=
include $(BUILD_EXECUTABLE)


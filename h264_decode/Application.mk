APP_ABI := arm64-v8a
APP_PLATFORM := android-24
#APP_STL := stlport_static
APP_BUILD_SCRIPT := Android.mk

APP_CPPFLAGS += -std=c++11 -Wall
APP_STL := gnustl_static

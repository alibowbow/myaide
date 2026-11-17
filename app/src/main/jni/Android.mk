LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := gyeot_trash
LOCAL_SRC_FILES := trash_native.c
LOCAL_CFLAGS := -std=c11 -Wall -Wextra -Werror -fvisibility=hidden
LOCAL_LDFLAGS := -Wl,-z,relro,-z,now
include $(BUILD_SHARED_LIBRARY)

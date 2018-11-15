# gojni
JNI 的 Go 语言模板代码生成工具，配合 https://github.com/ClarkGuan/jni 使用，方便 Java 与 Go 之间通讯。

### 功能限制
- 目前仅支持 Java 单一源文件解析
- 不支持 import *

### 下载使用

* 下载代码

```
git clone https://github.com/ClarkGuan/gojni
```

* 安装

```
gradle install
```

* 使用

```
gojni xxx.java
```

### 举例

```java
package edu.buaa;

public class Main {
    static {
        System.loadLibrary("hello");
    }

    public static void main(String[] args) {
        nativeHello();
    }

    private static native void nativeHello();
}
```

假设该源文件路径是 $HOME/src/java/Main.java。运行命令

```
gojni -p main -o $HOME/src/go $HOME/src/java/Main.java
```

在目录 $HOME/src/go 生成两个文件：libs.c 和 libs.go。

libs.c:
```c
// 此文件为动态生成的，请不要修改！

#include <stdlib.h>
#include <stdio.h>
#include <stddef.h>
#include <stdint.h>
#include <jni.h>

#include "_cgo_export.h"

extern void jniOnLoad(GoUintptr vm);
extern void jniOnUnload(GoUintptr vm);

extern void jni_edu_buaa_nativeHello1(GoUintptr env, GoUintptr clazz);

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        fprintf(stderr, "[%s:%d] GetEnv() return error\n", __FILE__, __LINE__);
        abort();
    }

    jclass clazz;
    JNINativeMethod methods[255];
    jint size;
    char *name;

    name = "edu/buaa/Main";
    clazz = (*env)->FindClass(env, name);
    size = 0;

    methods[size].fnPtr = jni_edu_buaa_nativeHello1;
    methods[size].name = "nativeHello";
    methods[size].signature = "()V";
    size++;

    if ((*env)->RegisterNatives(env, clazz, methods, size) != 0) {
        fprintf(stderr, "[%s:%d] %s RegisterNatives() return error\n", __FILE__, __LINE__, name);
        abort();
    }

    jniOnLoad((GoUintptr) vm);
    return JNI_VERSION_1_6;
}

void JNI_OnUnload(JavaVM *vm, void *reserved) {
    jniOnUnload((GoUintptr) vm);
}
```

libs.go:
```go
package main

//
// #include <stdlib.h>
// #include <stddef.h>
// #include <stdint.h>
import "C"

//export jniOnLoad
func jniOnLoad(vm uintptr) {
    // TODO
}

//export jniOnUnload
func jniOnUnload(vm uintptr) {
    // TODO
}

//export jni_edu_buaa_nativeHello1
func jni_edu_buaa_nativeHello1(env uintptr, clazz uintptr) {
    // TODO
}
```

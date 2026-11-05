cmake_minimum_required(VERSION 3.22.1)
project("aclpe")

# ACL-PE JNI 进程内 JVM 启动桥（仿造 FCL/Pojav 思路，自研）
add_library(aclpe_jvm SHARED
    jvm_bridge.c
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(aclpe_jvm
    ${log-lib}
    ${android-lib}
    dl
)

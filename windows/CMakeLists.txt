cmake_minimum_required(VERSION 3.10)
project(InputBridgeClient C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

# 输入bridge客户端
add_executable(inputbridge_client inputbridge_client.c)

# 链接Windows库
target_link_libraries(inputbridge_client PRIVATE
    ws2_32    # Winsock2 (TCP网络)
    user32    # SendInput (输入注入)
)

# 编译优化
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    target_compile_options(inputbridge_client PRIVATE -O2)
endif()

# 安装规则
install(TARGETS inputbridge_client DESTINATION bin)

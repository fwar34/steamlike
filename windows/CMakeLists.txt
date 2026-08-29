cmake_minimum_required(VERSION 3.10) # 语法：cmake_minimum_required 指定最低需要的 CMake 版本为 3.10
project(InputBridgeClient C) # 语法：project 定义工程名称为 InputBridgeClient，语言为 C

set(CMAKE_C_STANDARD 99) # 语法：set 设置变量；CMAKE_C_STANDARD=99 指定使用 C99 标准
set(CMAKE_C_STANDARD_REQUIRED ON) # 语法：set 设置变量；CMAKE_C_STANDARD_REQUIRED=ON 强制要求编译器支持 C99

# 输入bridge客户端
add_executable(inputbridge_client inputbridge_client.c) # 语法：add_executable 定义可执行目标 inputbridge_client 及其源文件

# 链接Windows库
target_link_libraries(inputbridge_client PRIVATE # 语法：target_link_libraries 为目标链接外部库；PRIVATE 表示仅本目标可见
    ws2_32    # Winsock2 (TCP网络) # 语法：链接 ws2_32 库，提供 Winsock2 套接字网络接口
    user32    # SendInput (输入注入) # 语法：链接 user32 库，提供 SendInput 输入注入接口
) # 结束 target_link_libraries 命令

# 编译优化
if(CMAKE_BUILD_TYPE STREQUAL "Release") # 语法：if 条件判断；STREQUAL 比较字符串是否相等；判断是否为 Release 构建类型
    target_compile_options(inputbridge_client PRIVATE -O2) # 语法：target_compile_options 为目标添加编译选项；-O2 开启二级优化
endif() # 语法：endif 结束 if 条件块

# 安装规则
install(TARGETS inputbridge_client DESTINATION bin) # 语法：install 定义安装规则；将目标 inputbridge_client 安装到 bin 目录

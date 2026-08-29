/*
 * InputBridge Test Client - 调试用 Windows 测试程序
 *
 * 连接到 Android 端的 InputBridgeServer，仅打印收到的消息，不执行 SendInput。
 * 用于在开发期间验证手柄映射是否正确发送。
 *
 * ## 使用方法
 * 1. 启动 Android 端（模拟器或手机）的 SteamLike 服务
 * 2. 设置 ADB 端口转发（仅模拟器/USB 连接需要）:
 *      adb forward tcp:27015 tcp:27015
 * 3. 在 Windows 命令行运行:
 *      inputbridge_test.exe
 *    或指定 IP 和端口:
 *      inputbridge_test.exe 127.0.0.1 27015
 * 4. 在 Android 端按手柄按键或使用"测试手柄按键"按钮
 * 5. 此程序会打印所有收到的消息
 *
 * 编译: gcc inputbridge_test.c -o inputbridge_test.exe -lws2_32
 */

#include <stdio.h> // 语法：#include 预处理指令，引入标准输入输出库（printf 等）
#include <stdlib.h> // 语法：#include 预处理指令，引入标准库（atoi 等）
#include <string.h> // 语法：#include 预处理指令，引入字符串处理库
#include <winsock2.h> // 语法：#include 预处理指令，引入 Winsock2 套接字网络库
#include <ws2tcpip.h> // 语法：#include 预处理指令，引入 Winsock TCP/IP 扩展头文件

#pragma comment(lib, "ws2_32.lib") // 语法：#pragma comment 编译器指令，链接 ws2_32 网络库

/* ===== 协议定义（与 Android 端 InputBridgeServer.kt 保持一致）===== */

#define DEFAULT_PORT 27015 // 语法：#define 宏定义，默认连接端口号 27015
#define DEFAULT_IP   "127.0.0.1" // 语法：#define 宏定义，默认服务器 IP（本机回环地址）
#define PACKET_SIZE  8 // 语法：#define 宏定义，每个数据包固定为 8 字节

/* 消息类型 */
#define MSG_KEY_EVENT     0x01 // 语法：#define 宏定义，消息类型：键盘事件
#define MSG_MOUSE_MOVE    0x02 // 语法：#define 宏定义，消息类型：鼠标移动
#define MSG_MOUSE_BUTTON  0x03 // 语法：#define 宏定义，消息类型：鼠标按钮
#define MSG_MOUSE_WHEEL   0x04 // 语法：#define 宏定义，消息类型：鼠标滚轮
#define MSG_RELEASE_ALL   0x05 // 语法：#define 宏定义，消息类型：释放所有按键
#define MSG_PING          0x06 // 语法：#define 宏定义，消息类型：心跳包

/* Windows 虚拟键码名称（用于友好显示）*/
static const char* GetVkName(WORD vk) { // 语法：static 静态函数定义；const char* 返回只读字符串指针；WORD=16 位无符号整数
    switch (vk) { // 语法：switch 分支语句，按虚拟键码返回对应名称
        case 0x01: return "LBUTTON"; // 语法：case 分支：鼠标左键
        case 0x02: return "RBUTTON"; // 语法：case 分支：鼠标右键
        case 0x04: return "MBUTTON"; // 语法：case 分支：鼠标中键
        case 0x08: return "BACK"; // 语法：case 分支：退格键
        case 0x09: return "TAB"; // 语法：case 分支：制表键
        case 0x0D: return "ENTER"; // 语法：case 分支：回车键
        case 0x10: return "SHIFT"; // 语法：case 分支：Shift 键
        case 0x11: return "CTRL"; // 语法：case 分支：Ctrl 键
        case 0x12: return "ALT"; // 语法：case 分支：Alt 键
        case 0x14: return "CAPSLOCK"; // 语法：case 分支：大小写锁定键
        case 0x1B: return "ESC"; // 语法：case 分支：Esc 键
        case 0x20: return "SPACE"; // 语法：case 分支：空格键
        case 0x21: return "PAGEUP"; // 语法：case 分支：Page Up 键
        case 0x22: return "PAGEDOWN"; // 语法：case 分支：Page Down 键
        case 0x23: return "END"; // 语法：case 分支：End 键
        case 0x24: return "HOME"; // 语法：case 分支：Home 键
        case 0x25: return "LEFT"; // 语法：case 分支：左方向键
        case 0x26: return "UP"; // 语法：case 分支：上方向键
        case 0x27: return "RIGHT"; // 语法：case 分支：右方向键
        case 0x28: return "DOWN"; // 语法：case 分支：下方向键
        case 0x2D: return "INSERT"; // 语法：case 分支：Insert 键
        case 0x2E: return "DELETE"; // 语法：case 分支：Delete 键
        case 0x30: case 0x31: case 0x32: case 0x33: case 0x34: // 语法：多个 case 合并：数字键 0~4
        case 0x35: case 0x36: case 0x37: case 0x38: case 0x39: // 语法：多个 case 合并：数字键 5~9
            return "0-9"; // 数字键统一返回 "0-9"
        case 0x41: case 0x42: case 0x43: case 0x44: case 0x45: case 0x46: // 语法：多个 case 合并：字母键 A~F
        case 0x47: case 0x48: case 0x49: case 0x4A: case 0x4B: case 0x4C: // 语法：多个 case 合并：字母键 G~L
        case 0x4D: case 0x4E: case 0x4F: case 0x50: case 0x51: case 0x52: // 语法：多个 case 合并：字母键 M~R
        case 0x53: case 0x54: case 0x55: case 0x56: case 0x57: case 0x58: // 语法：多个 case 合并：字母键 S~X
        case 0x59: case 0x5A: // 语法：多个 case 合并：字母键 Y、Z
            return "A-Z"; // 字母键统一返回 "A-Z"
        case 0x70: case 0x71: case 0x72: case 0x73: case 0x74: case 0x75: // 语法：多个 case 合并：功能键 F1~F6
        case 0x76: case 0x77: case 0x78: case 0x79: case 0x7A: case 0x7B: // 语法：多个 case 合并：功能键 F7~F12
            return "F1-F12"; // 功能键统一返回 "F1-F12"
        case 0xA0: return "LSHIFT"; // 语法：case 分支：左 Shift 键
        case 0xA1: return "RSHIFT"; // 语法：case 分支：右 Shift 键
        case 0xA2: return "LCTRL"; // 语法：case 分支：左 Ctrl 键
        case 0xA3: return "RCTRL"; // 语法：case 分支：右 Ctrl 键
        case 0xA4: return "LALT"; // 语法：case 分支：左 Alt 键
        case 0xA5: return "RALT"; // 语法：case 分支：右 Alt 键
        default: return "VK_?"; // 语法：default 默认分支：未知键码返回占位名
    } // 结束 switch 语句
} // 结束 GetVkName 函数

/* 鼠标按钮名称 */
static const char* GetMouseName(unsigned char button) { // 语法：static 静态函数定义；unsigned char 无符号字节参数（按钮 ID）
    switch (button) { // 语法：switch 分支语句，按按钮 ID 返回名称
        case 0: return "LEFT"; // 语法：case 分支：左键
        case 1: return "RIGHT"; // 语法：case 分支：右键
        case 2: return "MIDDLE"; // 语法：case 分支：中键
        case 3: return "FORWARD"; // 语法：case 分支：前进键
        case 4: return "BACK"; // 语法：case 分支：后退键
        default: return "?"; // 语法：default 默认分支：未知按钮
    } // 结束 switch 语句
} // 结束 GetMouseName 函数

/* 处理收到的消息包 */
static void HandlePacket(const unsigned char* buf) { // 语法：static 静态函数定义；const unsigned char* 为只读字节指针（数据包）
    unsigned char msgType = buf[0]; // 语法：数组下标访问，取出第 0 字节作为消息类型

    /* 提取时间戳 */
    DWORD now = GetTickCount(); // 语法：GetTickCount 获取系统启动以来的毫秒数
    printf("[%u.%03us] ", now / 1000, now % 1000); // 打印秒.毫秒格式的时间戳

    switch (msgType) { // 语法：switch 分支语句，按消息类型打印对应内容
        case MSG_KEY_EVENT: { // 语法：case 分支：键盘事件
            /* Byte 1-2: VK code (uint16 LE)
             * Byte 3: isDown
             */
            WORD vk = (WORD)(buf[1] | (buf[2] << 8)); // 语法：位运算拼接小端 16 位虚拟键码；| 按位或；<< 左移；类型转换
            unsigned char isDown = buf[3]; // 取出第 3 字节作为按下标志
            printf("KEY %s (VK=0x%02X) %s\n", // 打印键盘事件：键名、虚拟键码、按下/释放
                   GetVkName(vk), vk, // 传入键名转换结果和虚拟键码
                   isDown ? "DOWN" : "UP"); // 语法：三目运算符，根据标志输出 DOWN/UP
            break; // 语法：break 跳出 switch 语句
        } // 结束 MSG_KEY_EVENT 分支
        case MSG_MOUSE_MOVE: { // 语法：case 分支：鼠标移动
            /* Byte 1-2: dx (int16 LE)
             * Byte 3-4: dy (int16 LE)
             */
            short dx = (short)(buf[1] | (buf[2] << 8)); // 语法：位运算解析小端 16 位 X 位移；类型转换
            short dy = (short)(buf[3] | (buf[4] << 8)); // 语法：位运算解析小端 16 位 Y 位移；类型转换
            printf("MOUSE_MOVE dx=%d dy=%d\n", dx, dy); // 打印鼠标位移信息
            break; // 语法：break 跳出 switch 语句
        } // 结束 MSG_MOUSE_MOVE 分支
        case MSG_MOUSE_BUTTON: { // 语法：case 分支：鼠标按钮
            /* Byte 1: button
             * Byte 2: isDown
             */
            unsigned char button = buf[1]; // 取出第 1 字节作为按钮 ID
            unsigned char isDown = buf[2]; // 取出第 2 字节作为按下标志
            printf("MOUSE_%s %s\n", // 打印鼠标按钮事件：按钮名、按下/释放
                   GetMouseName(button), // 传入按钮名称转换结果
                   isDown ? "DOWN" : "UP"); // 语法：三目运算符，根据标志输出 DOWN/UP
            break; // 语法：break 跳出 switch 语句
        } // 结束 MSG_MOUSE_BUTTON 分支
        case MSG_MOUSE_WHEEL: { // 语法：case 分支：鼠标滚轮
            /* Byte 1-2: delta (int16 LE) */
            short delta = (short)(buf[1] | (buf[2] << 8)); // 语法：位运算解析小端 16 位滚轮增量；类型转换
            printf("MOUSE_WHEEL delta=%d\n", delta); // 打印滚轮增量
            break; // 语法：break 跳出 switch 语句
        } // 结束 MSG_MOUSE_WHEEL 分支
        case MSG_RELEASE_ALL: // 语法：case 分支：释放所有按键
            printf("RELEASE_ALL\n"); // 打印释放所有按键消息
            break; // 语法：break 跳出 switch 语句
        case MSG_PING: // 语法：case 分支：心跳包
            printf("PING\n"); // 打印心跳包消息
            break; // 语法：break 跳出 switch 语句
        default: // 语法：default 默认分支：未知消息类型
            printf("UNKNOWN type=0x%02X data=[%02X %02X %02X %02X %02X %02X %02X]\n", // 打印未知消息类型及原始字节
                   msgType, buf[1], buf[2], buf[3], buf[4], buf[5], buf[6], buf[7]); // 逐字节打印数据包内容
            break; // 语法：break 跳出 switch 语句
    } // 结束 switch 语句
    fflush(stdout); // 语法：fflush 刷新标准输出缓冲区，确保立即显示
} // 结束 HandlePacket 函数

int main(int argc, char* argv[]) { // 语法：main 程序入口函数；argc=命令行参数个数；argv=参数数组（char* 指针数组）
    const char* ip = DEFAULT_IP; // 语法：const 只读指针，默认服务器 IP 地址
    int port = DEFAULT_PORT; // 默认端口号

    if (argc >= 2) ip = argv[1]; // 语法：if 判断参数个数；argv[1] 为第一个命令行参数（服务器 IP）
    if (argc >= 3) port = atoi(argv[2]); // 语法：if 判断参数个数；atoi 将字符串转为整数（端口号）

    /* 初始化 Winsock */
    WSADATA wsaData; // 语法：声明 WSADATA 结构体，存放 Winsock 初始化信息
    int result = WSAStartup(MAKEWORD(2, 2), &wsaData); // 语法：WSAStartup 初始化 Winsock；MAKEWORD(2,2)=版本 2.2；& 取地址
    if (result != 0) { // 语法：if 判断初始化是否失败
        printf("WSAStartup failed: %d\n", result); // 打印初始化失败错误码
        return 1; // 语法：return 返回 1 退出程序
    }

    /* 创建 socket */
    SOCKET sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP); // 语法：socket 创建套接字；AF_INET=IPv4；SOCK_STREAM=流式(TCP)
    if (sock == INVALID_SOCKET) { // 语法：if 判断套接字创建是否失败
        printf("socket failed: %d\n", WSAGetLastError()); // 打印套接字创建失败错误码
        WSACleanup(); // 语法：WSACleanup 清理 Winsock 资源
        return 1; // 语法：return 返回 1 退出程序
    }

    /* 连接到 Android 端服务器 */
    struct sockaddr_in addr; // 语法：struct 声明套接字地址结构体，存放 IPv4 地址信息
    addr.sin_family = AF_INET; // 设置地址族为 IPv4
    addr.sin_port = htons((u_short)port); // 语法：htons 将端口号转为网络字节序；类型转换
    addr.sin_addr.s_addr = inet_addr(ip); // 语法：inet_addr 将点分十进制 IP 字符串转为网络序数值

    printf("InputBridge Test Client\n"); // 打印程序标题
    printf("Connecting to %s:%d ...\n", ip, port); // 打印正在连接的信息
    fflush(stdout); // 语法：fflush 刷新标准输出，立即显示

    result = connect(sock, (struct sockaddr*)&addr, sizeof(addr)); // 语法：connect 发起 TCP 连接；& 取地址；类型转换；返回结果
    if (result == SOCKET_ERROR) { // 语法：if 判断连接是否失败
        printf("connect failed: %d\n", WSAGetLastError()); // 打印连接失败错误码
        printf("Hint: 检查:\n"); // 打印排查提示标题
        printf("  1. Android 端 SteamLike 服务已启动\n"); // 提示检查点 1
        printf("  2. ADB 端口转发已设置 (adb forward tcp:27015 tcp:27015)\n"); // 提示检查点 2
        printf("  3. 防火墙允许连接\n"); // 提示检查点 3
        closesocket(sock); // 语法：closesocket 关闭套接字
        WSACleanup(); // 语法：WSACleanup 清理 Winsock 资源
        return 1; // 语法：return 返回 1 退出程序
    }

    printf("Connected! Waiting for messages...\n"); // 打印连接成功提示
    printf("(Press Ctrl+C to exit)\n\n"); // 打印退出提示
    fflush(stdout); // 语法：fflush 刷新标准输出，立即显示

    /* 接收循环 */
    unsigned char buf[PACKET_SIZE]; // 语法：声明数据包缓冲区数组，大小 8 字节
    int totalRecv = 0; // 当前包内已接收的字节数
    while (1) { // 语法：while 无限循环，持续接收数据
        int n = recv(sock, (char*)buf + totalRecv, PACKET_SIZE - totalRecv, 0); // 语法：recv 接收剩余字节；指针偏移；类型转换
        if (n <= 0) { // 语法：if 判断连接关闭或出错
            if (n == 0) { // 语法：if 判断是对方关闭连接
                printf("\n[Connection closed by server]\n"); // 打印服务器关闭连接提示
            } else { // 语法：else 接收出错分支
                printf("\n[recv error: %d]\n", WSAGetLastError()); // 打印接收错误码
            }
            break; // 语法：break 跳出接收循环
        }
        totalRecv += n; // 累加已接收字节数
        if (totalRecv == PACKET_SIZE) { // 语法：if 判断已凑满 8 字节
            HandlePacket(buf); // 调用数据包处理函数
            totalRecv = 0; // 重置已接收计数，准备下一个包
        }
    }

    closesocket(sock); // 语法：closesocket 关闭套接字
    WSACleanup(); // 语法：WSACleanup 清理 Winsock 资源
    printf("Client exited\n"); // 打印客户端退出信息
    return 0; // 语法：return 返回 0 表示正常退出
} // 结束 main 函数

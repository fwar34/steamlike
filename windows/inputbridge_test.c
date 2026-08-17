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

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <winsock2.h>
#include <ws2tcpip.h>

#pragma comment(lib, "ws2_32.lib")

/* ===== 协议定义（与 Android 端 InputBridgeServer.kt 保持一致）===== */

#define DEFAULT_PORT 27015
#define DEFAULT_IP   "127.0.0.1"
#define PACKET_SIZE  8

/* 消息类型 */
#define MSG_KEY_EVENT     0x01
#define MSG_MOUSE_MOVE    0x02
#define MSG_MOUSE_BUTTON  0x03
#define MSG_MOUSE_WHEEL   0x04
#define MSG_RELEASE_ALL   0x05
#define MSG_PING          0x06

/* Windows 虚拟键码名称（用于友好显示）*/
static const char* GetVkName(WORD vk) {
    switch (vk) {
        case 0x01: return "LBUTTON";
        case 0x02: return "RBUTTON";
        case 0x04: return "MBUTTON";
        case 0x08: return "BACK";
        case 0x09: return "TAB";
        case 0x0D: return "ENTER";
        case 0x10: return "SHIFT";
        case 0x11: return "CTRL";
        case 0x12: return "ALT";
        case 0x14: return "CAPSLOCK";
        case 0x1B: return "ESC";
        case 0x20: return "SPACE";
        case 0x21: return "PAGEUP";
        case 0x22: return "PAGEDOWN";
        case 0x23: return "END";
        case 0x24: return "HOME";
        case 0x25: return "LEFT";
        case 0x26: return "UP";
        case 0x27: return "RIGHT";
        case 0x28: return "DOWN";
        case 0x2D: return "INSERT";
        case 0x2E: return "DELETE";
        case 0x30: case 0x31: case 0x32: case 0x33: case 0x34:
        case 0x35: case 0x36: case 0x37: case 0x38: case 0x39:
            return "0-9";
        case 0x41: case 0x42: case 0x43: case 0x44: case 0x45: case 0x46:
        case 0x47: case 0x48: case 0x49: case 0x4A: case 0x4B: case 0x4C:
        case 0x4D: case 0x4E: case 0x4F: case 0x50: case 0x51: case 0x52:
        case 0x53: case 0x54: case 0x55: case 0x56: case 0x57: case 0x58:
        case 0x59: case 0x5A:
            return "A-Z";
        case 0x70: case 0x71: case 0x72: case 0x73: case 0x74: case 0x75:
        case 0x76: case 0x77: case 0x78: case 0x79: case 0x7A: case 0x7B:
            return "F1-F12";
        case 0xA0: return "LSHIFT";
        case 0xA1: return "RSHIFT";
        case 0xA2: return "LCTRL";
        case 0xA3: return "RCTRL";
        case 0xA4: return "LALT";
        case 0xA5: return "RALT";
        default: return "VK_?";
    }
}

/* 鼠标按钮名称 */
static const char* GetMouseName(unsigned char button) {
    switch (button) {
        case 0: return "LEFT";
        case 1: return "RIGHT";
        case 2: return "MIDDLE";
        case 3: return "FORWARD";
        case 4: return "BACK";
        default: return "?";
    }
}

/* 处理收到的消息包 */
static void HandlePacket(const unsigned char* buf) {
    unsigned char msgType = buf[0];

    /* 提取时间戳 */
    DWORD now = GetTickCount();
    printf("[%u.%03us] ", now / 1000, now % 1000);

    switch (msgType) {
        case MSG_KEY_EVENT: {
            /* Byte 1-2: VK code (uint16 LE)
             * Byte 3: isDown
             */
            WORD vk = (WORD)(buf[1] | (buf[2] << 8));
            unsigned char isDown = buf[3];
            printf("KEY %s (VK=0x%02X) %s\n",
                   GetVkName(vk), vk,
                   isDown ? "DOWN" : "UP");
            break;
        }
        case MSG_MOUSE_MOVE: {
            /* Byte 1-2: dx (int16 LE)
             * Byte 3-4: dy (int16 LE)
             */
            short dx = (short)(buf[1] | (buf[2] << 8));
            short dy = (short)(buf[3] | (buf[4] << 8));
            printf("MOUSE_MOVE dx=%d dy=%d\n", dx, dy);
            break;
        }
        case MSG_MOUSE_BUTTON: {
            /* Byte 1: button
             * Byte 2: isDown
             */
            unsigned char button = buf[1];
            unsigned char isDown = buf[2];
            printf("MOUSE_%s %s\n",
                   GetMouseName(button),
                   isDown ? "DOWN" : "UP");
            break;
        }
        case MSG_MOUSE_WHEEL: {
            /* Byte 1-2: delta (int16 LE) */
            short delta = (short)(buf[1] | (buf[2] << 8));
            printf("MOUSE_WHEEL delta=%d\n", delta);
            break;
        }
        case MSG_RELEASE_ALL:
            printf("RELEASE_ALL\n");
            break;
        case MSG_PING:
            printf("PING\n");
            break;
        default:
            printf("UNKNOWN type=0x%02X data=[%02X %02X %02X %02X %02X %02X %02X]\n",
                   msgType, buf[1], buf[2], buf[3], buf[4], buf[5], buf[6], buf[7]);
            break;
    }
    fflush(stdout);
}

int main(int argc, char* argv[]) {
    const char* ip = DEFAULT_IP;
    int port = DEFAULT_PORT;

    if (argc >= 2) ip = argv[1];
    if (argc >= 3) port = atoi(argv[2]);

    /* 初始化 Winsock */
    WSADATA wsaData;
    int result = WSAStartup(MAKEWORD(2, 2), &wsaData);
    if (result != 0) {
        printf("WSAStartup failed: %d\n", result);
        return 1;
    }

    /* 创建 socket */
    SOCKET sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock == INVALID_SOCKET) {
        printf("socket failed: %d\n", WSAGetLastError());
        WSACleanup();
        return 1;
    }

    /* 连接到 Android 端服务器 */
    struct sockaddr_in addr;
    addr.sin_family = AF_INET;
    addr.sin_port = htons((u_short)port);
    addr.sin_addr.s_addr = inet_addr(ip);

    printf("InputBridge Test Client\n");
    printf("Connecting to %s:%d ...\n", ip, port);
    fflush(stdout);

    result = connect(sock, (struct sockaddr*)&addr, sizeof(addr));
    if (result == SOCKET_ERROR) {
        printf("connect failed: %d\n", WSAGetLastError());
        printf("Hint: 检查:\n");
        printf("  1. Android 端 SteamLike 服务已启动\n");
        printf("  2. ADB 端口转发已设置 (adb forward tcp:27015 tcp:27015)\n");
        printf("  3. 防火墙允许连接\n");
        closesocket(sock);
        WSACleanup();
        return 1;
    }

    printf("Connected! Waiting for messages...\n");
    printf("(Press Ctrl+C to exit)\n\n");
    fflush(stdout);

    /* 接收循环 */
    unsigned char buf[PACKET_SIZE];
    int totalRecv = 0;
    while (1) {
        int n = recv(sock, (char*)buf + totalRecv, PACKET_SIZE - totalRecv, 0);
        if (n <= 0) {
            if (n == 0) {
                printf("\n[Connection closed by server]\n");
            } else {
                printf("\n[recv error: %d]\n", WSAGetLastError());
            }
            break;
        }
        totalRecv += n;
        if (totalRecv == PACKET_SIZE) {
            HandlePacket(buf);
            totalRecv = 0;
        }
    }

    closesocket(sock);
    WSACleanup();
    printf("Client exited\n");
    return 0;
}

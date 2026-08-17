/*
 * InputBridge Client - Windows配套程序
 *
 * 在Winlator内运行，连接Android端的InputBridgeServer，
 * 接收手柄映射事件，通过SendInput()注入到Windows环境。
 *
 * 编译: gcc inputbridge_client.c -o inputbridge_client.exe -lws2_32 -luser32
 * 或使用: build.bat
 *
 * 用法: inputbridge_client.exe [android_ip] [port]
 *   默认: android_ip=127.0.0.1  port=27015
 *
 * 在Winlator中运行:
 *   1. 将inputbridge_client.exe复制到Winlator的C盘
 *   2. 在Winlator的命令行中运行: inputbridge_client.exe
 *   3. 保持窗口打开，切到WoW游戏即可
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <winsock2.h>
#include <windows.h>
#include <ws2tcpip.h>

#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "user32.lib")

/* ===== 协议定义 ===== */

#define DEFAULT_PORT 27015
#define DEFAULT_IP   "127.0.0.1"
#define PACKET_SIZE  8
#define RECV_BUF_SIZE 4096

/* 消息类型 */
#define MSG_KEY_EVENT    0x01
#define MSG_MOUSE_MOVE   0x02
#define MSG_MOUSE_BUTTON 0x03
#define MSG_MOUSE_WHEEL  0x04
#define MSG_RELEASE_ALL  0x05
#define MSG_PING         0x06

/* 鼠标按钮ID */
#define MOUSE_LEFT    0
#define MOUSE_RIGHT   1
#define MOUSE_MIDDLE  2
#define MOUSE_FORWARD 3
#define MOUSE_BACK    4

/* ===== 全局状态 ===== */

static SOCKET sock = INVALID_SOCKET;
static int running = 1;

/* 当前按下的键集合（用于ReleaseAll） */
static unsigned char pressedKeys[256] = {0};
static int pressedMouseButtons[5] = {0, 0, 0, 0, 0};

/* ===== 输入注入函数 ===== */

/*
 * 注入键盘事件
 * vkCode: Windows虚拟键码
 * isDown: 1=按下, 0=释放
 */
static void InjectKey(WORD vkCode, int isDown) {
    INPUT input;
    memset(&input, 0, sizeof(input));
    input.type = INPUT_KEYBOARD;
    input.ki.wVk = vkCode;
    input.ki.wScan = 0;
    input.ki.dwFlags = isDown ? 0 : KEYEVENTF_KEYUP;
    input.ki.time = 0;
    input.ki.dwExtraInfo = 0;

    SendInput(1, &input, sizeof(INPUT));

    /* 更新状态 */
    if (vkCode < 256) {
        pressedKeys[vkCode] = isDown ? 1 : 0;
    }
}

/*
 * 注入鼠标移动
 * dx, dy: 相对位移
 */
static void InjectMouseMove(int dx, int dy) {
    INPUT input;
    memset(&input, 0, sizeof(input));
    input.type = INPUT_MOUSE;
    input.mi.dx = dx;
    input.mi.dy = dy;
    input.mi.dwFlags = MOUSEEVENTF_MOVE;
    input.mi.time = 0;
    input.mi.dwExtraInfo = 0;

    SendInput(1, &input, sizeof(INPUT));
}

/*
 * 注入鼠标按钮事件
 * button: 0=左, 1=右, 2=中, 3=前进, 4=后退
 * isDown: 1=按下, 0=释放
 */
static void InjectMouseButton(int button, int isDown) {
    INPUT input;
    memset(&input, 0, sizeof(input));
    input.type = INPUT_MOUSE;

    DWORD downFlag, upFlag;
    DWORD mouseData = 0;
    switch (button) {
        case MOUSE_LEFT:
            downFlag = MOUSEEVENTF_LEFTDOWN;
            upFlag = MOUSEEVENTF_LEFTUP;
            break;
        case MOUSE_RIGHT:
            downFlag = MOUSEEVENTF_RIGHTDOWN;
            upFlag = MOUSEEVENTF_RIGHTUP;
            break;
        case MOUSE_MIDDLE:
            downFlag = MOUSEEVENTF_MIDDLEDOWN;
            upFlag = MOUSEEVENTF_MIDDLEUP;
            break;
        case MOUSE_FORWARD:
            downFlag = MOUSEEVENTF_XDOWN;
            upFlag = MOUSEEVENTF_XUP;
            mouseData = XBUTTON1;
            break;
        case MOUSE_BACK:
            downFlag = MOUSEEVENTF_XDOWN;
            upFlag = MOUSEEVENTF_XUP;
            mouseData = XBUTTON2;
            break;
        default:
            return;
    }

    input.mi.dwFlags = isDown ? downFlag : upFlag;
    input.mi.mouseData = mouseData;
    input.mi.time = 0;
    input.mi.dwExtraInfo = 0;

    SendInput(1, &input, sizeof(INPUT));

    /* 更新状态 */
    if (button >= 0 && button < 5) {
        pressedMouseButtons[button] = isDown ? 1 : 0;
    }
}

/*
 * 注入鼠标滚轮
 * delta: 滚轮增量
 */
static void InjectMouseWheel(int delta) {
    INPUT input;
    memset(&input, 0, sizeof(input));
    input.type = INPUT_MOUSE;
    input.mi.dwFlags = MOUSEEVENTF_WHEEL;
    input.mi.mouseData = (DWORD)delta;
    input.mi.time = 0;
    input.mi.dwExtraInfo = 0;

    SendInput(1, &input, sizeof(INPUT));
}

/*
 * 释放所有按下的键和按钮
 */
static void ReleaseAllInputs(void) {
    INPUT inputs[256];
    int count = 0;

    /* 释放所有按下的键盘按键 */
    for (int i = 0; i < 256; i++) {
        if (pressedKeys[i]) {
            memset(&inputs[count], 0, sizeof(INPUT));
            inputs[count].type = INPUT_KEYBOARD;
            inputs[count].ki.wVk = (WORD)i;
            inputs[count].ki.dwFlags = KEYEVENTF_KEYUP;
            count++;
            pressedKeys[i] = 0;
        }
    }

    /* 释放所有按下的鼠标按钮 */
    if (pressedMouseButtons[MOUSE_LEFT]) {
        memset(&inputs[count], 0, sizeof(INPUT));
        inputs[count].type = INPUT_MOUSE;
        inputs[count].mi.dwFlags = MOUSEEVENTF_LEFTUP;
        count++;
        pressedMouseButtons[MOUSE_LEFT] = 0;
    }
    if (pressedMouseButtons[MOUSE_RIGHT]) {
        memset(&inputs[count], 0, sizeof(INPUT));
        inputs[count].type = INPUT_MOUSE;
        inputs[count].mi.dwFlags = MOUSEEVENTF_RIGHTUP;
        count++;
        pressedMouseButtons[MOUSE_RIGHT] = 0;
    }
    if (pressedMouseButtons[MOUSE_MIDDLE]) {
        memset(&inputs[count], 0, sizeof(INPUT));
        inputs[count].type = INPUT_MOUSE;
        inputs[count].mi.dwFlags = MOUSEEVENTF_MIDDLEUP;
        count++;
        pressedMouseButtons[MOUSE_MIDDLE] = 0;
    }
    if (pressedMouseButtons[MOUSE_FORWARD]) {
        memset(&inputs[count], 0, sizeof(INPUT));
        inputs[count].type = INPUT_MOUSE;
        inputs[count].mi.dwFlags = MOUSEEVENTF_XUP;
        inputs[count].mi.mouseData = XBUTTON1;
        count++;
        pressedMouseButtons[MOUSE_FORWARD] = 0;
    }
    if (pressedMouseButtons[MOUSE_BACK]) {
        memset(&inputs[count], 0, sizeof(INPUT));
        inputs[count].type = INPUT_MOUSE;
        inputs[count].mi.dwFlags = MOUSEEVENTF_XUP;
        inputs[count].mi.mouseData = XBUTTON2;
        count++;
        pressedMouseButtons[MOUSE_BACK] = 0;
    }

    if (count > 0) {
        SendInput(count, inputs, sizeof(INPUT));
    }
}

/* ===== 数据包处理 ===== */

/*
 * 处理一个8字节数据包
 */
static void ProcessPacket(const unsigned char* data) {
    unsigned char msgType = data[0];

    switch (msgType) {
        case MSG_KEY_EVENT: {
            /* Byte 1-2: VK Code (uint16 LE) */
            WORD vkCode = (WORD)(data[1] | (data[2] << 8));
            /* Byte 3: isDown */
            int isDown = data[3] ? 1 : 0;
            InjectKey(vkCode, isDown);
            break;
        }

        case MSG_MOUSE_MOVE: {
            /* Byte 1-2: dx (int16 LE) */
            short dx = (short)(data[1] | (data[2] << 8));
            /* Byte 3-4: dy (int16 LE) */
            short dy = (short)(data[3] | (data[4] << 8));
            InjectMouseMove(dx, dy);
            break;
        }

        case MSG_MOUSE_BUTTON: {
            /* Byte 1: button */
            int button = data[1];
            /* Byte 2: isDown */
            int isDown = data[2] ? 1 : 0;
            InjectMouseButton(button, isDown);
            break;
        }

        case MSG_MOUSE_WHEEL: {
            /* Byte 1-2: delta (int16 LE) */
            short delta = (short)(data[1] | (data[2] << 8));
            InjectMouseWheel(delta);
            break;
        }

        case MSG_RELEASE_ALL: {
            ReleaseAllInputs();
            break;
        }

        case MSG_PING: {
            /* 心跳包，无需处理 */
            break;
        }

        default:
            /* 未知消息类型，忽略 */
            break;
    }
}

/* ===== 网络连接 ===== */

/*
 * 连接到Android服务器
 * 返回: 0=成功, -1=失败
 */
static int ConnectToServer(const char* ip, int port) {
    struct sockaddr_in serverAddr;

    sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock == INVALID_SOCKET) {
        printf("[ERROR] Socket creation failed: %d\n", WSAGetLastError());
        return -1;
    }

    serverAddr.sin_family = AF_INET;
    serverAddr.sin_port = htons((u_short)port);
    serverAddr.sin_addr.s_addr = inet_addr(ip);

    printf("[CONNECT] Connecting to %s:%d ...\n", ip, port);

    if (connect(sock, (struct sockaddr*)&serverAddr, sizeof(serverAddr)) == SOCKET_ERROR) {
        printf("[ERROR] Connection failed: %d\n", WSAGetLastError());
        closesocket(sock);
        sock = INVALID_SOCKET;
        return -1;
    }

    printf("[CONNECT] Connected to Android server!\n");
    return 0;
}

/*
 * 接收并处理数据
 * 返回: 0=正常, -1=连接断开
 */
static int ReceiveAndProcess(void) {
    unsigned char recvBuf[RECV_BUF_SIZE];
    static unsigned char packetBuf[PACKET_SIZE];
    static int packetOffset = 0;

    int bytesReceived = recv(sock, (char*)recvBuf, RECV_BUF_SIZE, 0);
    if (bytesReceived <= 0) {
        return -1;  /* 连接断开 */
    }

    /* 处理接收到的数据，按8字节分包 */
    for (int i = 0; i < bytesReceived; i++) {
        packetBuf[packetOffset++] = recvBuf[i];
        if (packetOffset >= PACKET_SIZE) {
            /* 完整包，处理 */
            ProcessPacket(packetBuf);
            packetOffset = 0;
        }
    }

    return 0;
}

/*
 * 控制台控制处理（Ctrl+C退出时清理）
 */
static BOOL WINAPI ConsoleHandler(DWORD signal) {
    if (signal == CTRL_C_EVENT || signal == CTRL_CLOSE_EVENT) {
        printf("\n[EXIT] Cleaning up...\n");
        running = 0;
        ReleaseAllInputs();
        if (sock != INVALID_SOCKET) {
            closesocket(sock);
        }
        ExitProcess(0);
    }
    return TRUE;
}

/* ===== 主函数 ===== */

int main(int argc, char* argv[]) {
    const char* ip = DEFAULT_IP;
    int port = DEFAULT_PORT;
    int reconnectDelay = 1000;  /* 重连延迟(ms) */

    /* 解析命令行参数 */
    if (argc >= 2) ip = argv[1];
    if (argc >= 3) port = atoi(argv[2]);

    /* 禁用stdout缓冲，确保管道/重定向环境下实时输出 */
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    /* 单进程限制: 使用命名互斥锁确保同时只有一个实例运行 */
    HANDLE hMutex = CreateMutex(NULL, TRUE, "Global\\SteamLikeInputBridgeClient");
    if (hMutex == NULL || GetLastError() == ERROR_ALREADY_EXISTS) {
        printf("[ERROR] Another instance is already running. Exiting.\n");
        if (hMutex) CloseHandle(hMutex);
        return 1;
    }
    printf("[INFO] Single instance lock acquired.\n");

    /* 初始化Winsock */
    WSADATA wsaData;
    if (WSAStartup(MAKEWORD(2, 2), &wsaData) != 0) {
        printf("[错误] WSAStartup失败: %d\n", WSAGetLastError());
        return 1;
    }

    /* 注册控制台退出处理 */
    SetConsoleCtrlHandler(ConsoleHandler, TRUE);

    printf("========================================\n");
    printf("  InputBridge Client for Winlator\n");
    printf("  SteamLike Controller - Windows Side\n");
    printf("========================================\n");
    printf("  Server: %s:%d\n", ip, port);
    printf("  Protocol: TCP 8-byte fixed-length packets\n");
    printf("  Injection: SendInput()\n");
    printf("========================================\n");
    printf("  Press Ctrl+C to quit\n\n");

    /* 主循环: 连接 → 接收 → 断开 → 重连 */
    while (running) {
        /* 连接到服务器 */
        if (ConnectToServer(ip, port) != 0) {
            printf("[RETRY] Reconnecting in %d seconds...\n", reconnectDelay / 1000);
            Sleep(reconnectDelay);
            continue;
        }

        /* 接收并处理数据，直到连接断开 */
        while (running) {
            if (ReceiveAndProcess() != 0) {
                printf("[DISCONNECT] Connection closed\n");
                ReleaseAllInputs();
                break;
            }
        }

        closesocket(sock);
        sock = INVALID_SOCKET;

        if (running) {
            printf("[RETRY] Reconnecting in %d seconds...\n", reconnectDelay / 1000);
            Sleep(reconnectDelay);
        }
    }

    /* 清理 */
    ReleaseAllInputs();
    WSACleanup();

    /* 释放单进程互斥锁 */
    if (hMutex) {
        ReleaseMutex(hMutex);
        CloseHandle(hMutex);
    }

    printf("[EXIT] Program exited\n");
    return 0;
}

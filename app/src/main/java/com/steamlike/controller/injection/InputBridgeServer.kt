package com.steamlike.controller.injection

import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * InputBridge TCP服务器
 *
 * 在Android端运行TCP服务器，接收手柄映射事件，转发给连接的Windows客户端。
 * Windows客户端运行在Winlator内部，通过localhost连接到本服务器。
 *
 * ## 架构
 * ```
 * Android APK                          Winlator (Windows)
 * ┌──────────────────┐                ┌──────────────────────┐
 * │ GamepadInputView  │                │ InputBridgeClient.exe │
 * │      ↓            │                │  (C程序)              │
 * │ SteamInput        │                │      ↓                │
 * │      ↓            │                │ recv() 接收事件       │
 * │ BridgeInputInjector│               │      ↓                │
 * │      ↓            │   TCP连接      │ SendInput() 注入      │
 * │ InputBridgeServer │←──────────────→│ (localhost:27015)     │
 * └──────────────────┘                └──────────────────────┘
 * ```
 *
 * ## 通信协议
 * 每个消息为8字节定长包:
 * ```
 * Byte 0: 消息类型
 *   0x01 = 键盘事件
 *   0x02 = 鼠标移动
 *   0x03 = 鼠标按钮
 *   0x04 = 鼠标滚轮
 *   0x05 = 释放所有按键
 *   0x06 = 心跳Ping
 *
 * Byte 1-7: 载荷 (按消息类型不同)
 *
 * 键盘事件 (0x01):
 *   Byte 1-2: Windows虚拟键码 (uint16 LE)
 *   Byte 3:   是否按下 (0=释放, 1=按下)
 *   Byte 4-7: 保留
 *
 * 鼠标移动 (0x02):
 *   Byte 1-2: dx (int16 LE)
 *   Byte 3-4: dy (int16 LE)
 *   Byte 5-7: 保留
 *
 * 鼠标按钮 (0x03):
 *   Byte 1: 按钮 (0=左, 1=右, 2=中)
 *   Byte 2: 是否按下 (0=释放, 1=按下)
 *   Byte 3-7: 保留
 *
 * 鼠标滚轮 (0x04):
 *   Byte 1-2: delta (int16 LE)
 *   Byte 3-7: 保留
 * ```
 *
 * @param port TCP监听端口，默认27015
 */
class InputBridgeServer(private val port: Int = DEFAULT_PORT) {

    companion object {
        private const val TAG = "InputBridgeServer"
        const val DEFAULT_PORT = 27015

        // 消息类型
        const val MSG_KEY_EVENT: Byte = 0x01
        const val MSG_MOUSE_MOVE: Byte = 0x02
        const val MSG_MOUSE_BUTTON: Byte = 0x03
        const val MSG_MOUSE_WHEEL: Byte = 0x04
        const val MSG_RELEASE_ALL: Byte = 0x05
        const val MSG_PING: Byte = 0x06

        const val PACKET_SIZE = 8
    }

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val clients = CopyOnWriteArrayList<ClientConnection>()
    private val messageQueue = ConcurrentLinkedQueue<ByteArray>()

    /** 服务器状态回调 */
    var onClientConnected: ((String) -> Unit)? = null
    var onClientDisconnected: ((String) -> Unit)? = null
    var onServerError: ((String) -> Unit)? = null

    /**
     * 启动TCP服务器
     * @return true=启动成功
     */
    fun start(): Boolean {
        if (isRunning.get()) return true
        return try {
            serverSocket = ServerSocket()
            // 绑定到所有接口，允许Winlator内的Windows程序通过localhost连接
            serverSocket!!.bind(InetSocketAddress(port))
            isRunning.set(true)

            // 启动接受连接的线程
            Thread(::acceptLoop, "BridgeServer-Accept").start()
            // 启动消息分发线程
            Thread(::dispatchLoop, "BridgeServer-Dispatch").start()

            Log.i(TAG, "服务器已启动, 端口=$port")
            true
        } catch (e: IOException) {
            Log.e(TAG, "启动服务器失败", e)
            onServerError?.invoke(e.message ?: "未知错误")
            false
        }
    }

    /**
     * 停止服务器
     */
    fun stop() {
        isRunning.set(false)
        clients.forEach { it.close() }
        clients.clear()
        messageQueue.clear()
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "关闭服务器失败", e)
        }
        serverSocket = null
        Log.i(TAG, "服务器已停止")
    }

    /**
     * 发送键盘事件
     * @param vkCode Windows虚拟键码 (如 VK_SPACE=0x20)
     * @param isDown true=按下, false=释放
     */
    fun sendKeyEvent(vkCode: Int, isDown: Boolean) {
        val packet = ByteArray(PACKET_SIZE)
        packet[0] = MSG_KEY_EVENT
        packet[1] = (vkCode and 0xFF).toByte()
        packet[2] = ((vkCode shr 8) and 0xFF).toByte()
        packet[3] = if (isDown) 1 else 0
        enqueue(packet)
    }

    /**
     * 发送鼠标移动事件
     * @param dx X轴相对位移
     * @param dy Y轴相对位移
     */
    fun sendMouseMove(dx: Float, dy: Float) {
        val packet = ByteArray(PACKET_SIZE)
        packet[0] = MSG_MOUSE_MOVE
        val dxInt = dx.toInt().toShort().toInt()
        val dyInt = dy.toInt().toShort().toInt()
        packet[1] = (dxInt and 0xFF).toByte()
        packet[2] = ((dxInt shr 8) and 0xFF).toByte()
        packet[3] = (dyInt and 0xFF).toByte()
        packet[4] = ((dyInt shr 8) and 0xFF).toByte()
        enqueue(packet)
    }

    /**
     * 发送鼠标按钮事件
     * @param button 0=左键, 1=右键, 2=中键
     * @param isDown true=按下, false=释放
     */
    fun sendMouseButton(button: Int, isDown: Boolean) {
        val packet = ByteArray(PACKET_SIZE)
        packet[0] = MSG_MOUSE_BUTTON
        packet[1] = button.toByte()
        packet[2] = if (isDown) 1 else 0
        enqueue(packet)
    }

    /**
     * 发送鼠标滚轮事件
     * @param delta 滚轮增量
     */
    fun sendMouseWheel(delta: Float) {
        val packet = ByteArray(PACKET_SIZE)
        packet[0] = MSG_MOUSE_WHEEL
        val deltaInt = delta.toInt().toShort().toInt()
        packet[1] = (deltaInt and 0xFF).toByte()
        packet[2] = ((deltaInt shr 8) and 0xFF).toByte()
        enqueue(packet)
    }

    /**
     * 发送释放所有按键事件
     */
    fun sendReleaseAll() {
        val packet = ByteArray(PACKET_SIZE)
        packet[0] = MSG_RELEASE_ALL
        enqueue(packet)
    }

    /**
     * 发送心跳包
     */
    fun sendPing() {
        val packet = ByteArray(PACKET_SIZE)
        packet[0] = MSG_PING
        enqueue(packet)
    }

    /** 是否有客户端连接 */
    fun hasClients(): Boolean = clients.isNotEmpty()

    /** 获取已连接客户端数量 */
    fun clientCount(): Int = clients.size

    // ===== 内部实现 =====

    private fun enqueue(packet: ByteArray) {
        messageQueue.add(packet)
    }

    /**
     * 接受客户端连接的循环
     */
    private fun acceptLoop() {
        while (isRunning.get()) {
            try {
                val socket = serverSocket?.accept() ?: break
                val client = ClientConnection(socket)
                clients.add(client)
                val addr = socket.remoteSocketAddress.toString()
                Log.i(TAG, "客户端已连接: $addr")
                onClientConnected?.invoke(addr)
                Thread({ handleClient(client) }, "BridgeServer-Client-${client.id}").start()
            } catch (e: IOException) {
                if (isRunning.get()) {
                    Log.e(TAG, "接受连接失败", e)
                }
            }
        }
    }

    /**
     * 处理单个客户端连接（读取客户端发来的数据，目前仅用于检测断开）
     */
    private fun handleClient(client: ClientConnection) {
        try {
            val input = client.socket.getInputStream()
            val buffer = ByteArray(64)
            while (isRunning.get() && !client.closed) {
                val read = input.read(buffer)
                if (read == -1) break  // 客户端断开
            }
        } catch (e: IOException) {
            // 客户端断开
        } finally {
            client.close()
            clients.remove(client)
            val addr = client.socket.remoteSocketAddress?.toString() ?: "unknown"
            Log.i(TAG, "客户端已断开: $addr")
            onClientDisconnected?.invoke(addr)
        }
    }

    /**
     * 消息分发循环：将队列中的消息发送给所有已连接的客户端
     */
    private fun dispatchLoop() {
        while (isRunning.get()) {
            try {
                val packet = messageQueue.poll()
                if (packet != null) {
                    // 发送给所有客户端
                    val deadClients = mutableListOf<ClientConnection>()
                    for (client in clients) {
                        try {
                            client.send(packet)
                        } catch (e: IOException) {
                            deadClients.add(client)
                        }
                    }
                    // 清理断开的客户端
                    if (deadClients.isNotEmpty()) {
                        deadClients.forEach { c ->
                            c.close()
                            clients.remove(c)
                        }
                    }
                } else {
                    // 队列为空，短暂休眠避免忙等待
                    Thread.sleep(1)
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "分发消息异常", e)
            }
        }
    }

    /**
     * 客户端连接封装
     */
    private class ClientConnection(val socket: Socket) {
        val id: Int = hashCode()
        private val outputStream: OutputStream? = try { socket.getOutputStream() } catch (e: IOException) { null }
        @Volatile var closed: Boolean = false

        fun send(data: ByteArray) {
            if (closed) throw IOException("连接已关闭")
            outputStream?.write(data)
            outputStream?.flush()
        }

        fun close() {
            closed = true
            try { socket.close() } catch (e: IOException) {}
        }
    }
}

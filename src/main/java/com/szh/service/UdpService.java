package com.szh.service;

import javax.swing.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * UDP 指令发送服务（使用虚拟线程，极低开销）
 */
public class UdpService {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 发送指令并回调响应
     * @param host    目标IP
     * @param port    目标端口
     * @param cmd     指令字符串
     * @param onResponse 响应回调 (EDT线程)
     */
    public void send(String host, int port, String cmd, Consumer<String> onResponse) {
        executor.submit(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(3000);
                byte[] data = cmd.getBytes(StandardCharsets.UTF_8);
                InetAddress addr = InetAddress.getByName(host);
                DatagramPacket packet = new DatagramPacket(data, data.length, addr, port);
                socket.send(packet);

                byte[] buf = new byte[4096];
                DatagramPacket recv = new DatagramPacket(buf, buf.length);
                socket.receive(recv);
                String resp = new String(recv.getData(), 0, recv.getLength(), StandardCharsets.UTF_8);

                SwingUtilities.invokeLater(() -> onResponse.accept(resp));
            } catch (SocketTimeoutException e) {
                SwingUtilities.invokeLater(() -> onResponse.accept("TIMEOUT"));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> onResponse.accept("ERR:" + e.getMessage()));
            }
        });
    }

    public void shutdown() {
        executor.shutdown();
    }
}

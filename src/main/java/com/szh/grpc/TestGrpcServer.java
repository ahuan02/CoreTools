package com.szh.grpc;

import com.szh.grpc.test.*;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 嵌入式测试 gRPC 服务端，用于 GrpcPanel 本地调试。
 * 提供 Greeter 服务 + Reflection 支持。
 */
public class TestGrpcServer {

    private Server server;
    private final int port;

    public TestGrpcServer(int port) {
        this.port = port;
    }

    /** 启动服务 */
    public void start() throws IOException {
        server = ServerBuilder.forPort(port)
                .addService(new GreeterImpl())
                .addService(ProtoReflectionServiceV1.newInstance()) // 开启 Reflection
                .build()
                .start();
        System.out.println("[TestGrpcServer] 已启动，端口: " + port + " (Reflection 已开启)");
    }

    /** 停止服务 */
    public void stop() throws InterruptedException {
        if (server != null) {
            server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            System.out.println("[TestGrpcServer] 已停止");
        }
    }

    public boolean isRunning() {
        return server != null && !server.isShutdown() && !server.isTerminated();
    }

    // ==================== Greeter 实现 ====================

    private static class GreeterImpl extends GreeterGrpc.GreeterImplBase {

        @Override
        public void sayHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
            String msg = "你好, " + request.getName()
                    + (request.getAge() > 0 ? " (" + request.getAge() + "岁)!" : "!");
            HelloReply reply = HelloReply.newBuilder()
                    .setMessage(msg)
                    .setTimestamp(System.currentTimeMillis())
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        }

        @Override
        public void echo(EchoRequest request, StreamObserver<EchoReply> responseObserver) {
            int repeat = request.getRepeat() > 0 ? request.getRepeat() : 1;
            for (int i = 0; i < repeat; i++) {
                EchoReply reply = EchoReply.newBuilder()
                        .setContent(request.getContent() + " #" + (i + 1))
                        .setIndex(i + 1)
                        .build();
                responseObserver.onNext(reply);
            }
            responseObserver.onCompleted();
        }

        @Override
        public void streamGreet(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
            for (int i = 0; i < 3; i++) {
                HelloReply reply = HelloReply.newBuilder()
                        .setMessage("Stream 你好 " + request.getName() + " [" + (i + 1) + "/3]")
                        .setTimestamp(System.currentTimeMillis())
                        .build();
                responseObserver.onNext(reply);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            responseObserver.onCompleted();
        }
    }
}

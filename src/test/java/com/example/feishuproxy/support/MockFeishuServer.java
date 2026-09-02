package com.example.feishuproxy.support;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;

/**
 * open.feishu.cn 的替身，基于 JDK 自带的 HTTP 服务器构建，因此测试无需额外依赖，也无需真实机器人。
 * 它记录收到的精确字节，并且可以被编排成以特定的状态码和报文应答，重试路径正是靠这种方式被覆盖到的。
 */
public class MockFeishuServer implements AutoCloseable {

    private static final String DEFAULT_BODY = "{\"code\":0,\"msg\":\"success\"}";

    private final HttpServer server;
    private final List<byte[]> requests = Collections.synchronizedList(new ArrayList<>());
    private final Queue<Reply> scripted = new ConcurrentLinkedQueue<>();
    private volatile Reply fallback = new Reply(200, DEFAULT_BODY);

    public MockFeishuServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/hook", exchange -> {
            requests.add(readAll(exchange.getRequestBody()));

            Reply reply = scripted.poll();
            if (reply == null) {
                reply = fallback;
            }
            byte[] out = reply.body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(reply.status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        this.server.setExecutor(Executors.newFixedThreadPool(4));
        this.server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    /** 每个答案按顺序各使用一次，之后回退到默认应答。 */
    public void enqueue(int status, String body) {
        scripted.add(new Reply(status, body));
    }

    public void setFallback(int status, String body) {
        this.fallback = new Reply(status, body);
    }

    public int requestCount() {
        return requests.size();
    }

    public byte[] lastRequestBytes() {
        synchronized (requests) {
            return requests.get(requests.size() - 1);
        }
    }

    public String lastRequestBody() {
        return new String(lastRequestBytes(), StandardCharsets.UTF_8);
    }

    public void reset() {
        requests.clear();
        scripted.clear();
        fallback = new Reply(200, DEFAULT_BODY);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static final class Reply {
        private final int status;
        private final String body;

        Reply(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}

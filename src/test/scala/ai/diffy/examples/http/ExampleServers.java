package ai.diffy.examples.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

public class ExampleServers {
    public static void main(String[] args) throws Exception {
        int primary = Integer.parseInt(args[0]);
        int secondary = Integer.parseInt(args[1]);
        int candidate = Integer.parseInt(args[2]);
        Random rng = new Random();
        Thread p = new Thread(() -> bind(primary, x -> { try{Thread.sleep((long) (50 * rng.nextGaussian()));} catch (Exception e) {} return x.toLowerCase();}));
        Thread s = new Thread(() -> bind(secondary, x -> { try{Thread.sleep((long) (50 * rng.nextGaussian()));} catch (Exception e) {} return x.toLowerCase();}));
        Thread c = new Thread(() -> bind(candidate, x -> { try{Thread.sleep((long) (60 * rng.nextGaussian()));} catch (Exception e) {} return x.toUpperCase();}));
        p.start();
        s.start();
        c.start();
        while(true){
            Thread.sleep(10);
        }
    }

    public static void bind(int port, Function<String, String> lambda) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext(
                    "/json",
                    new Handler(
                            "{\"name\":\"%s\", \"timestamp\":\"%s\"}",
                            "application/json",
                            lambda));
            server.createContext(
                    "/html",
                    new Handler(
                            "<body><name>%s</name><timestamp>%s</timestamp></body>",
                            "text/html",
                            lambda));
            server.setExecutor(null);
            server.start();
        } catch (Exception exception) {
            System.err.println("!!!failed to start!!!");
        }
    }
}
class Handler implements HttpHandler {
    private String template;
    private String contentType;
    private Function<String, String> lambda;
    public Handler(String template, String contentType, Function<String, String> lambda) {
        super();
        this.template = template;
        this.contentType = contentType;
        this.lambda = lambda;
    }

    @Override
    public void handle(HttpExchange t) throws IOException {
        System.out.println(reqToStr(t, getRequestBody(t)));
        String name  = lambda.apply(t.getRequestURI().getQuery());
        String response = String.format(template, name, System.currentTimeMillis());
        t.getResponseHeaders().add("Content-Type", contentType);
        t.sendResponseHeaders(200, response.length());
        OutputStream os = t.getResponseBody();
        os.write(response.getBytes());
        os.close();
    }

    public static String getRequestBody(HttpExchange t) throws IOException {
        InputStreamReader isr = new InputStreamReader(t.getRequestBody(), StandardCharsets.UTF_8);
        BufferedReader br = new BufferedReader(isr);
        int b;
        StringBuilder buf = new StringBuilder(512);
        while ((b = br.read()) != -1) {
            buf.append((char) b);
        }
        br.close();
        isr.close();

        return buf.toString();
    }

    public static String reqToStr(HttpExchange t, String body) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(t.getRequestMethod()).append(" ");
        sb.append(t.getRequestURI()).append(" ");
        sb.append(t.getProtocol()).append("\n");
        for (Map.Entry<String, List<String>> entry : t.getRequestHeaders().entrySet()) {
            sb.append(entry.getKey()).append(":");
            boolean isFirst = true;
            for (String v: entry.getValue()) {
                sb.append(isFirst ? "": ",").append(v);
                isFirst = false;
            }
            sb.append("\n");
        }
        sb.append("\n");

        sb.append(body);

        return sb.toString();
    }
}

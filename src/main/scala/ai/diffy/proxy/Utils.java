package ai.diffy.proxy;

import com.sun.net.httpserver.HttpExchange;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class Utils {
    /**
     * Note: you can only use the request body stream once.
     */
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

package ai.diffy.proxy;

import ai.diffy.Settings;
import ai.diffy.analysis.*;
import ai.diffy.lifter.HttpLifter;
import ai.diffy.lifter.Message;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class HttpDifferenceProxy {
    private static final Set<HttpMethod> methodsWithSideEffects =
        Stream.of(
            HttpMethod.POST,
            HttpMethod.PUT,
            HttpMethod.PATCH,
            HttpMethod.DELETE
        ).collect(Collectors.toSet());
    public final JoinedDifferences joinedDifferences;
    public final InMemoryDifferenceCollector collector;
    private final Logger log = LoggerFactory.getLogger(HttpDifferenceProxy.class);
    private final Settings settings;
    private final DifferenceAnalyzer analyzer;
    private final HttpLifter lifter;
    private final ThreadPoolExecutor threadPoolExecutor;
    private final HttpClient client;
    volatile public Date lastReset = new Date();
    private HttpServer server;

    private static class SessionInfo {
        public SessionInfo() {
            sessionId = null;
            cookies = new HashMap<String, String>();
        }
        public String sessionId;
        public HashMap<String, String> cookies;
    };

    private static final SessionInfo primarySession = new SessionInfo();
    private static final SessionInfo secondarySession = new SessionInfo();
    private static final SessionInfo candidateSession = new SessionInfo();

    public HttpDifferenceProxy(@Autowired Settings settings) {
        this.settings = settings;
        this.collector = new InMemoryDifferenceCollector();
        RawDifferenceCounter raw = RawDifferenceCounter.apply(new InMemoryDifferenceCounter());
        NoiseDifferenceCounter noise = NoiseDifferenceCounter.apply(new InMemoryDifferenceCounter());
        this.joinedDifferences = JoinedDifferences.apply(raw, noise);
        this.analyzer = new DifferenceAnalyzer(raw, noise, collector);
        this.lifter = new HttpLifter(settings);

        log.info("Starting Proxy server on port " + settings.servicePort());
        client = HttpClient.newHttpClient();


        threadPoolExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(10);
        try {
            server = HttpServer.create(new InetSocketAddress(settings.servicePort()), 0);
        } catch (IOException e) {
            log.error("Fail to create proxy server");
            System.exit(1);
        }
        server.createContext("/", this::handle);
        server.setExecutor(threadPoolExecutor);
        server.start();
    }

    private Map<String, String> splitQuery(URI url) {
        final Map<String, String> query_pairs = new LinkedHashMap<>();
        if (url.getQuery() == null) {
            return query_pairs;
        }
        final String[] pairs = url.getQuery().split("&");
        for (String pair : pairs) {
            final int idx = pair.indexOf("=");
            final String key = idx > 0 ? URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8) : pair;
            final String value = idx > 0 && pair.length() > idx + 1 ? URLDecoder.decode(pair.substring(idx + 1),
                StandardCharsets.UTF_8) : null;
            query_pairs.put(key, value);
        }
        return query_pairs;
    }


    private void handle(HttpExchange exchange) throws IOException {
        try {
            long currTime = System.currentTimeMillis();

            URI uri = exchange.getRequestURI();
            String method = exchange.getRequestMethod();
            String requestBody = Utils.getRequestBody(exchange);
            log.info("received a request\n{}", Utils.reqToStr(exchange, requestBody));

            if (!settings.allowHttpSideEffects() && methodsWithSideEffects.contains(method)) {
                log.info("Ignoring {} request for safety. Use --allowHttpSideEffects=true to turn safety off.", method);
                String response = String.format("%s is ignored", method);
                exchange.sendResponseHeaders(200, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
                return;
            }
            ai.diffy.proxy.HttpRequest request = new ai.diffy.proxy.HttpRequest(
                method,
                uri.toString(),
                uri.getPath(),
                splitQuery(uri),
                new HttpMessage(toHeaders(exchange.getRequestHeaders()), requestBody));

            // build request
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .method(method, HttpRequest.BodyPublishers.ofString(requestBody));
            copyRequestFields(builder, exchange.getRequestHeaders());

            log.info("time to prepare requests {} seconds", (System.currentTimeMillis() - currTime) / 1000.0);
            currTime = System.currentTimeMillis();

            HttpRequest primaryRequest = buildRequest(builder, uri, settings.primaryHost(), settings.primaryPort(), primarySession);
            HttpRequest secondaryRequest = buildRequest(builder, uri, settings.secondaryHost(),
                settings.secondaryPort(), secondarySession);
            HttpRequest candidateRequest = buildRequest(builder, uri, settings.candidateHost(),
                settings.candidatePort(), candidateSession);
            CompletableFuture<HttpResponse<byte[]>> primaryResponseFuture = client.sendAsync(primaryRequest,
                HttpResponse.BodyHandlers.ofByteArray());
            CompletableFuture<HttpResponse<byte[]>> secondaryResponseFuture = client.sendAsync(secondaryRequest,
                HttpResponse.BodyHandlers.ofByteArray());
            CompletableFuture<HttpResponse<byte[]>> candidateResponseFuture = client.sendAsync(candidateRequest,
                HttpResponse.BodyHandlers.ofByteArray());
            HttpResponse<byte[]> primaryResponse = primaryResponseFuture.join();
            HttpResponse<byte[]> secondaryResponse = secondaryResponseFuture.join();
            HttpResponse<byte[]> candidateResponse = candidateResponseFuture.join();

            updateSessionInfo(primarySession, method, primaryResponse);
            updateSessionInfo(secondarySession, method, secondaryResponse);
            updateSessionInfo(candidateSession, method, candidateResponse);

            HttpResponse<byte[]> resp;
            switch (settings.responseMode().name()) {
                case "candidate":
                    resp = candidateResponse;
                    break;
                case "secondary":
                    resp = secondaryResponse;
                    break;
                default:
                    resp = primaryResponse;
            }

            java.net.http.HttpHeaders pH = resp.headers();
            Optional<String> contentType = pH.firstValue("Content-type");

            List<String> setCookies = pH.allValues("Set-cookie");

            boolean isText = false;
            if (contentType.isPresent()) {
                if (contentType.get().contains("text")) {
                    isText = true;
                }
            }
            else {
                isText = true;
            }
            if (isText) {
                log.info("time to receive responses {} seconds", (System.currentTimeMillis() - currTime) / 1000.0);
                currTime = System.currentTimeMillis();

                Message r = lifter.liftRequest(request);
                Message c = lifter.liftResponse(toResponseB(candidateResponse));
                Message p = lifter.liftResponse(toResponseB(primaryResponse));
                Message s = lifter.liftResponse(toResponseB(secondaryResponse));
                analyzer.apply(r, c, p, s);

                log.info("time to analyze differences {} seconds", (System.currentTimeMillis() - currTime) / 1000.0);
            }

            copyResponseFields(exchange.getResponseHeaders(), resp.headers());
            exchange.getResponseHeaders().add("Via", "1.1 diffy");

            byte[] bytes = resp.body();
            exchange.sendResponseHeaders(resp.statusCode(), bytes.length);
            
            OutputStream os = exchange.getResponseBody();
            os.write(bytes);
            os.close();
        } catch (Exception e) {
            e.printStackTrace();
            String response = "Sever error.";
            exchange.sendResponseHeaders(500, response.length());
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }

    private HttpRequest buildRequest(HttpRequest.Builder builder, URI originalUri, String host, int port, SessionInfo info) throws URISyntaxException {
        String scheme = originalUri.getScheme() == null ? "http" : originalUri.getScheme();
        String path = originalUri.getPath();
        String query = originalUri.getQuery();
        if (info.sessionId != null) {
            if (query != null) {
                query = query.replaceAll("BV_SessionID=[a-zA-Z0-9\\.\\-]+", "BV_SessionID=" + info.sessionId);
            }
            path = path.replaceAll("BV_SessionID=[a-zA-Z0-9\\.\\-]+", "BV_SessionID=" + info.sessionId);
        }
        URI uri = new URI(
            scheme, "", host, port, path, query, originalUri.getFragment());

        setCookie(builder, info);
        return builder.uri(uri).build();
    }

    private HttpHeaders toHeaders(java.net.http.HttpHeaders headers) {
        HttpHeaders ret = new DefaultHttpHeaders();
        headers.map().forEach(ret::add);
        return ret;
    }

    private HttpHeaders toHeaders(com.sun.net.httpserver.Headers headers) {
        HttpHeaders ret = new DefaultHttpHeaders();
        headers.forEach(ret::add);
        return ret;
    }

    private ai.diffy.proxy.HttpResponse toResponse(HttpResponse<String> response) {
        String status = Objects.requireNonNull(HttpStatus.resolve(response.statusCode())).toString();
        HttpMessage msg = new HttpMessage(toHeaders(response.headers()), response.body());
        return new ai.diffy.proxy.HttpResponse(status, msg);
    }

    private ai.diffy.proxy.HttpResponse toResponseB(HttpResponse<byte[]> response) {
        String status = Objects.requireNonNull(HttpStatus.resolve(response.statusCode())).toString();
        HttpMessage msg = new HttpMessage(toHeaders(response.headers()), new String(response.body(), StandardCharsets.UTF_8));
        return new ai.diffy.proxy.HttpResponse(status, msg);
    }

    static HashSet<String> requestNotCopyFields = new HashSet<String>(Arrays.asList(
        "Accept-encoding"
    ));
    private void copyRequestFields(HttpRequest.Builder builder, com.sun.net.httpserver.Headers requestHeader) {
        requestHeader.forEach((fieldName, fieldValues) -> {
            if (!requestNotCopyFields.contains(fieldName)) {
                fieldValues.forEach((fieldValue) -> builder.header(fieldName, fieldValue));
            }
        });
    }

    static HashSet<String> notCopyFields = new HashSet<String>(Arrays.asList(
        "Date",
        "Content-length"
    ));
    private void copyResponseFields(com.sun.net.httpserver.Headers headers, java.net.http.HttpHeaders responseHeaders) {
        responseHeaders.map().forEach((fieldName, fieldValues) -> {
            if (!notCopyFields.contains(fieldName)) {
                fieldValues.forEach((fieldValue) -> headers.add(fieldName, fieldValue));
            }
        });
    }

    private void updateSessionInfo(SessionInfo info, String method, HttpResponse<byte[]> resp) {
        java.net.http.HttpHeaders headers = resp.headers();
        List<String> setCookies = headers.allValues("Set-cookie");
        setCookies.forEach((setCookie) -> {
            String[] cookie = setCookie.split(";");
            for (int i = 0; i < cookie.length; i++) {
                String elem = cookie[i];
                String[] s = elem.split("=");

                if (s.length == 2) {
                    info.cookies.put(s[0].trim(), s[1].trim());
                    break; // Ignore everything else
                }
            }
        });

        if (method.equalsIgnoreCase("post")) {
            String body = new String(resp.body(), StandardCharsets.UTF_8);

            Pattern p = Pattern.compile(".+location\\.href = \".+BV_SessionID=([a-zA-Z0-9\\.\\-]+).+", Pattern.MULTILINE | Pattern.DOTALL);
            Matcher m = p.matcher(body);
            boolean b = m.matches();
            if (m.matches()) {
                info.sessionId = m.group(1);
                log.info("Session updated: " + info.sessionId);
            }
        }
    }
    private void setCookie(HttpRequest.Builder builder, SessionInfo info) {
        String[] cookie = { "" };
        info.cookies.forEach((key, value) -> {
            cookie[0] += key + "=" + value + ";";
        });
        builder.setHeader("Cookie", cookie[0]);
    }

    public void clear() {
        lastReset = new Date();
        analyzer.clear();
    }

}

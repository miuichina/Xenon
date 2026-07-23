package zxc.iconic.xenon.proxy;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Bidirectional bridge between an Xray root-config JSON and a flat {@link FormModel} that the
 * Form-based editor reads/writes. Only the first outbound (tag "proxy") and its stream settings
 * are modelled — the rest (inbounds, direct/block outbounds, log/dns) is preserved verbatim.
 *
 * <p>The model covers the common subset of fields produced by {@link XrayUriConfigFactory} for
 * vless/vmess/trojan/shadowsocks/socks/http/hysteria2: protocol, server address/port, per-protocol
 * credentials, network transport selector, TLS/Reality settings and per-network sub-settings.
 */
public final class XrayConfigForm {

    public static final List<String> PROTOCOLS = Collections.unmodifiableList(Arrays.asList(
            "vless", "vmess", "trojan", "shadowsocks", "socks", "http", "hysteria"));

    public static final List<String> NETWORKS = Collections.unmodifiableList(Arrays.asList(
            "tcp", "ws", "h2", "grpc", "httpupgrade", "splithttp", "hysteria"));

    public static final List<String> SECURITIES = Collections.unmodifiableList(Arrays.asList(
            "none", "tls", "reality"));

    public static final List<String> TLS_FINGERPRINTS = Collections.unmodifiableList(Arrays.asList(
            "", "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized", "none"));

    public static final List<String> SS_METHODS = Collections.unmodifiableList(Arrays.asList(
            "", "aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
            "aes-128-cfb", "aes-256-cfb", "chacha20-ietf", "rc4-md5", "none",
            "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305"));

    public static final List<String> VMESS_SECURITY = Collections.unmodifiableList(Arrays.asList(
            "auto", "none", "zero", "aes-128-gcm", "chacha20-poly1305"));

    public static final List<String> GRPC_MODES = Collections.unmodifiableList(Arrays.asList(
            "gun", "multi"));

    private XrayConfigForm() {
    }

    /**
     * Flat, mutable model the Form editor binds to. Primitive fields are Strings to keep UI
     * binding trivial (EditText/BoldCursor) — int coercion happens at {@link #apply}.
     */
    public static final class FormModel {
        // — Outbound root —
        public String protocol = "";
        public String tag = "proxy";

        // — Server (single target; settings.vnext[0] / settings.servers[0] / settings.address) —
        public String address = "";
        public String port = "";

        // — Protocol credentials —
        public String userId = "";      // vless id, vmess id (uuid)
        public String flow = "";        // vless flow (xtls-rprx-vision)
        public String email = "";       // optional email (vmess/vless)
        public String password = "";    // trojan, shadowsocks, socks, http
        public String ssMethod = "";    // shadowsocks method
        public String socksUser = "";   // socks/http user
        public String socksPass = "";   // socks/http pass
        public String vmessAlterId = "";
        public String vmessSecurity = "auto";

        // — streamSettings —
        public String network = "tcp";
        public String security = "none";

        // — TLS / Reality —
        public String sni = "";
        public String alpn = "";
        public String fingerprint = "";
        public boolean allowInsecure = false;
        public String realityPublicKey = "";
        public String realityShortId = "";
        public String realitySpiderX = "";

        // — TCP http header —
        public String tcpHeaderType = "none"; // "none" | "http"
        public String tcpHost = "";
        public String tcpPath = "";

        // — ws —
        public String wsPath = "/";
        public String wsHost = "";

        // — h2 —
        public String h2Host = "";
        public String h2Path = "/";

        // — grpc —
        public String grpcServiceName = "";
        public String grpcMode = "gun";

        // — hysteria —
        public String hysteriaVersion = "2";
        public String hysteriaObfsType = "";
        public String hysteriaObfsPassword = "";

        /**
         * @return a short summary as "protocol • host:port" for display.
         */
        public String summary() {
            if (TextUtils.isEmpty(address) || TextUtils.isEmpty(port)) {
                return protocol.isEmpty() ? "proxy" : protocol;
            }
            return (protocol.isEmpty() ? "proxy" : protocol) + " • " + address + ":" + port;
        }
    }

    /**
     * Extract a {@link FormModel} from the given Xray JSON config string.
     * Returns {@code null} when the config is not a valid JSON object.
     */
    public static FormModel extract(String configJson) {
        if (TextUtils.isEmpty(configJson)) {
            return null;
        }
        JSONObject root;
        try {
            root = new JSONObject(configJson);
        } catch (Throwable ignore) {
            return null;
        }
        JSONArray outbounds = root.optJSONArray("outbounds");
        if (outbounds == null || outbounds.length() == 0) {
            return null;
        }
        JSONObject outbound = outbounds.optJSONObject(0);
        if (outbound == null) {
            return null;
        }
        FormModel m = new FormModel();
        m.protocol = outbound.optString("protocol", "");
        m.tag = outbound.optString("tag", "proxy");

        JSONObject settings = outbound.optJSONObject("settings");
        if (settings != null) {
            extractServerSettings(m, settings);
        }

        JSONObject stream = outbound.optJSONObject("streamSettings");
        if (stream != null) {
            extractStreamSettings(m, stream);
        }
        return m;
    }

    private static void extractServerSettings(FormModel m, JSONObject settings) {
        // vmess: vnext[0].{address,port,users[0].{id,security,alterId}}
        // vless: vnext[0].{address,port,users[0].{id,flow,email}}
        JSONArray vnext = settings.optJSONArray("vnext");
        if (vnext != null && vnext.length() > 0) {
            JSONObject server = vnext.optJSONObject(0);
            if (server != null) {
                m.address = server.optString("address", "");
                m.port = String.valueOf(server.optInt("port", 0));
                JSONArray users = server.optJSONArray("users");
                if (users != null && users.length() > 0) {
                    JSONObject u = users.optJSONObject(0);
                    if (u != null) {
                        m.userId = u.optString("id", "");
                        m.flow = u.optString("flow", "");
                        m.email = u.optString("email", "");
                        m.vmessSecurity = u.optString("security", "auto");
                        int alterId = u.optInt("alterId", 0);
                        if (alterId != 0) {
                            m.vmessAlterId = String.valueOf(alterId);
                        }
                    }
                }
            }
            return;
        }
        // trojan/ss/socks/http: servers[0].{address,port,password,method,user,pass}
        JSONArray servers = settings.optJSONArray("servers");
        if (servers != null && servers.length() > 0) {
            JSONObject server = servers.optJSONObject(0);
            if (server != null) {
                m.address = server.optString("address", "");
                m.port = String.valueOf(server.optInt("port", 0));
                m.password = server.optString("password", "");
                m.ssMethod = server.optString("method", "");
                m.socksUser = server.optString("user", "");
                m.socksPass = server.optString("pass", "");
            }
        }
        // hysteria: settings.{address,port,version}
        if ("hysteria".equals(m.protocol)) {
            m.address = settings.optString("address", m.address);
            int port = settings.optInt("port", 0);
            if (port != 0) {
                m.port = String.valueOf(port);
            }
            int version = settings.optInt("version", 2);
            m.hysteriaVersion = String.valueOf(version);
        }
    }

    private static void extractStreamSettings(FormModel m, JSONObject stream) {
        m.network = stream.optString("network", "tcp");
        m.security = stream.optString("security", "none");

        if ("tls".equals(m.security)) {
            JSONObject tls = stream.optJSONObject("tlsSettings");
            if (tls != null) {
                m.sni = tls.optString("serverName", "");
                m.fingerprint = tls.optString("fingerprint", "");
                m.allowInsecure = tls.optBoolean("allowInsecure", false);
                JSONArray alpn = tls.optJSONArray("alpn");
                if (alpn != null) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < alpn.length(); i++) {
                        if (sb.length() > 0) {
                            sb.append(",");
                        }
                        sb.append(alpn.optString(i, ""));
                    }
                    m.alpn = sb.toString();
                }
            }
        } else if ("reality".equals(m.security)) {
            JSONObject r = stream.optJSONObject("realitySettings");
            if (r != null) {
                m.sni = r.optString("serverName", "");
                m.fingerprint = r.optString("fingerprint", "");
                m.realityPublicKey = r.optString("publicKey", "");
                m.realityShortId = r.optString("shortId", "");
                m.realitySpiderX = r.optString("spiderX", "");
            }
        }

        if ("tcp".equals(m.network)) {
            JSONObject tcp = stream.optJSONObject("tcpSettings");
            if (tcp != null) {
                JSONObject header = tcp.optJSONObject("header");
                if (header != null && "http".equalsIgnoreCase(header.optString("type", ""))) {
                    m.tcpHeaderType = "http";
                    JSONObject request = header.optJSONObject("request");
                    if (request != null) {
                        JSONObject headers = request.optJSONObject("headers");
                        if (headers != null) {
                            JSONArray hostArr = headers.optJSONArray("Host");
                            if (hostArr != null && hostArr.length() > 0) {
                                m.tcpHost = hostArr.optString(0, "");
                            }
                        }
                        JSONArray pathArr = request.optJSONArray("path");
                        if (pathArr != null && pathArr.length() > 0) {
                            m.tcpPath = pathArr.optString(0, "");
                        }
                    }
                }
            }
        } else if ("ws".equals(m.network)) {
            JSONObject ws = stream.optJSONObject("wsSettings");
            if (ws != null) {
                m.wsPath = ws.optString("path", "/");
                JSONObject headers = ws.optJSONObject("headers");
                if (headers != null) {
                    m.wsHost = headers.optString("Host", "");
                }
            }
        } else if ("h2".equals(m.network)) {
            JSONObject h2 = stream.optJSONObject("httpSettings");
            if (h2 != null) {
                JSONArray hostArr = h2.optJSONArray("host");
                if (hostArr != null && hostArr.length() > 0) {
                    m.h2Host = hostArr.optString(0, "");
                }
                m.h2Path = h2.optString("path", "/");
            }
        } else if ("grpc".equals(m.network)) {
            JSONObject grpc = stream.optJSONObject("grpcSettings");
            if (grpc != null) {
                m.grpcServiceName = grpc.optString("serviceName", "");
                m.grpcMode = grpc.optBoolean("multiMode", false) ? "multi" : "gun";
            }
        } else if ("hysteria".equals(m.network)) {
            JSONObject h = stream.optJSONObject("hysteriaSettings");
            if (h != null) {
                m.password = h.optString("auth", m.password);
            }
            JSONObject finalmask = stream.optJSONObject("finalmask");
            if (finalmask != null) {
                JSONArray udp = finalmask.optJSONArray("udp");
                if (udp != null && udp.length() > 0) {
                    JSONObject mask = udp.optJSONObject(0);
                    if (mask != null) {
                        m.hysteriaObfsType = mask.optString("type", "");
                        JSONObject ms = mask.optJSONObject("settings");
                        if (ms != null) {
                            m.hysteriaObfsPassword = ms.optString("password", "");
                        }
                    }
                }
            }
        }
    }

    /**
     * Apply the model back into the given config JSON. Mutates only outbound[0] and its
     * streamSettings; reuses the rest of the config structure. Returns the mutated JSON string.
     */
    public static String apply(FormModel m, String configJson) throws Exception {
        JSONObject root;
        if (TextUtils.isEmpty(configJson)) {
            root = emptyConfig(10808);
        } else {
            root = new JSONObject(configJson);
        }
        JSONArray outbounds = root.optJSONArray("outbounds");
        if (outbounds == null || outbounds.length() == 0) {
            outbounds = new JSONArray();
            root.put("outbounds", outbounds);
        }
        JSONObject outbound;
        if (outbounds.length() == 0) {
            outbound = new JSONObject();
            outbounds.put(outbound);
        } else {
            outbound = outbounds.optJSONObject(0);
            if (outbound == null) {
                outbound = new JSONObject();
                outbounds.put(0, outbound);
            }
        }
        applyToOutbound(outbound, m);
        return root.toString(2);
    }

    private static void applyToOutbound(JSONObject outbound, FormModel m) throws Exception {
        outbound.put("tag", TextUtils.isEmpty(m.tag) ? "proxy" : m.tag);
        outbound.put("protocol", TextUtils.isEmpty(m.protocol) ? "vless" : m.protocol);
        outbound.put("settings", buildSettings(m));
        outbound.put("streamSettings", buildStream(m));
    }

    private static JSONObject buildSettings(FormModel m) throws Exception {
        JSONObject settings = new JSONObject();
        String protocol = m.protocol;
        int port = safePort(m.port);
        if ("vless".equals(protocol) || "vmess".equals(protocol)) {
            JSONObject user = new JSONObject();
            user.put("id", m.userId);
            if ("vless".equals(protocol)) {
                if (!TextUtils.isEmpty(m.flow)) {
                    user.put("flow", m.flow);
                }
                if (!TextUtils.isEmpty(m.email)) {
                    user.put("email", m.email);
                }
            } else {
                user.put("security", TextUtils.isEmpty(m.vmessSecurity) ? "auto" : m.vmessSecurity);
                user.put("alterId", safeInt(m.vmessAlterId, 0));
            }
            JSONObject server = new JSONObject();
            server.put("address", m.address);
            server.put("port", port);
            server.put("users", new JSONArray().put(user));
            settings.put("vnext", new JSONArray().put(server));
        } else if ("trojan".equals(protocol)) {
            JSONObject server = new JSONObject();
            server.put("address", m.address);
            server.put("port", port);
            server.put("password", m.password);
            settings.put("servers", new JSONArray().put(server));
        } else if ("shadowsocks".equals(protocol)) {
            JSONObject server = new JSONObject();
            server.put("address", m.address);
            server.put("port", port);
            server.put("method", TextUtils.isEmpty(m.ssMethod) ? "aes-256-gcm" : m.ssMethod);
            server.put("password", m.password);
            settings.put("servers", new JSONArray().put(server));
        } else if ("socks".equals(protocol) || "http".equals(protocol)) {
            JSONObject server = new JSONObject();
            server.put("address", m.address);
            server.put("port", port);
            if (!TextUtils.isEmpty(m.socksUser) || !TextUtils.isEmpty(m.socksPass)) {
                JSONObject user = new JSONObject();
                user.put("user", m.socksUser);
                user.put("pass", m.socksPass);
                server.put("users", new JSONArray().put(user));
            }
            settings.put("servers", new JSONArray().put(server));
        } else if ("hysteria".equals(protocol)) {
            settings.put("address", m.address);
            settings.put("port", port);
            settings.put("version", TextUtils.isEmpty(m.hysteriaVersion) ? 2 : Integer.parseInt(m.hysteriaVersion));
        }
        return settings;
    }

    private static JSONObject buildStream(FormModel m) throws Exception {
        JSONObject stream = new JSONObject();
        stream.put("network", TextUtils.isEmpty(m.network) ? "tcp" : m.network);
        stream.put("security", TextUtils.isEmpty(m.security) ? "none" : m.security);

        if ("tls".equals(m.security)) {
            JSONObject tls = new JSONObject();
            if (!TextUtils.isEmpty(m.sni)) {
                tls.put("serverName", m.sni);
            }
            if (!TextUtils.isEmpty(m.fingerprint)) {
                tls.put("fingerprint", m.fingerprint);
            }
            if (m.allowInsecure) {
                tls.put("allowInsecure", true);
            }
            if (!TextUtils.isEmpty(m.alpn)) {
                JSONArray alpn = new JSONArray();
                for (String item : m.alpn.split(",")) {
                    String t = item.trim();
                    if (!TextUtils.isEmpty(t)) {
                        alpn.put(t);
                    }
                }
                if (alpn.length() > 0) {
                    tls.put("alpn", alpn);
                }
            }
            stream.put("tlsSettings", tls);
        } else if ("reality".equals(m.security)) {
            JSONObject r = new JSONObject();
            if (!TextUtils.isEmpty(m.sni)) {
                r.put("serverName", m.sni);
            }
            if (!TextUtils.isEmpty(m.fingerprint)) {
                r.put("fingerprint", m.fingerprint);
            }
            if (!TextUtils.isEmpty(m.realityPublicKey)) {
                r.put("publicKey", m.realityPublicKey);
            }
            if (!TextUtils.isEmpty(m.realityShortId)) {
                r.put("shortId", m.realityShortId);
            }
            if (!TextUtils.isEmpty(m.realitySpiderX)) {
                r.put("spiderX", m.realitySpiderX);
            }
            stream.put("realitySettings", r);
        }

        if ("tcp".equals(m.network)) {
            if ("http".equalsIgnoreCase(m.tcpHeaderType)) {
                JSONObject tcp = new JSONObject();
                JSONObject header = new JSONObject();
                header.put("type", "http");
                JSONObject request = new JSONObject();
                if (!TextUtils.isEmpty(m.tcpHost)) {
                    JSONObject headers = new JSONObject();
                    JSONArray hostArr = new JSONArray();
                    hostArr.put(m.tcpHost.trim());
                    headers.put("Host", hostArr);
                    request.put("headers", headers);
                }
                if (!TextUtils.isEmpty(m.tcpPath)) {
                    JSONArray pathArr = new JSONArray();
                    pathArr.put(m.tcpPath.trim());
                    request.put("path", pathArr);
                }
                header.put("request", request);
                tcp.put("header", header);
                stream.put("tcpSettings", tcp);
            }
        } else if ("ws".equals(m.network)) {
            JSONObject ws = new JSONObject();
            if (!TextUtils.isEmpty(m.wsPath)) {
                ws.put("path", m.wsPath);
            }
            if (!TextUtils.isEmpty(m.wsHost)) {
                JSONObject headers = new JSONObject();
                headers.put("Host", m.wsHost);
                ws.put("headers", headers);
            }
            stream.put("wsSettings", ws);
        } else if ("h2".equals(m.network)) {
            JSONObject h2 = new JSONObject();
            if (!TextUtils.isEmpty(m.h2Host)) {
                JSONArray hostArr = new JSONArray();
                hostArr.put(m.h2Host.trim());
                h2.put("host", hostArr);
            }
            h2.put("path", TextUtils.isEmpty(m.h2Path) ? "/" : m.h2Path);
            stream.put("httpSettings", h2);
        } else if ("grpc".equals(m.network)) {
            JSONObject grpc = new JSONObject();
            if (!TextUtils.isEmpty(m.grpcServiceName)) {
                grpc.put("serviceName", m.grpcServiceName);
            }
            if ("multi".equalsIgnoreCase(m.grpcMode)) {
                grpc.put("multiMode", true);
            }
            stream.put("grpcSettings", grpc);
        } else if ("hysteria".equals(m.network)) {
            JSONObject h = new JSONObject();
            h.put("version", TextUtils.isEmpty(m.hysteriaVersion) ? 2 : Integer.parseInt(m.hysteriaVersion));
            h.put("auth", m.password);
            stream.put("hysteriaSettings", h);

            if (!TextUtils.isEmpty(m.hysteriaObfsPassword)) {
                JSONObject mask = new JSONObject();
                mask.put("type", TextUtils.isEmpty(m.hysteriaObfsType) ? "salamander" : m.hysteriaObfsType);
                JSONObject ms = new JSONObject();
                ms.put("password", m.hysteriaObfsPassword);
                mask.put("settings", ms);
                JSONObject finalmask = new JSONObject();
                finalmask.put("udp", new JSONArray().put(mask));
                stream.put("finalmask", finalmask);
            }
        }
        return stream;
    }

    // ---- VPN tun config builders ----------------------------------------------------

    /**
     * Build a config suitable for system-wide VPN mode by adding a tun inbound.
     * The fd is NOT embedded in the JSON — Xray core receives the fd as a separate
     * argument to {@code startLoop(config, tunFd)}, matching v2rayNG's pattern.
     *
     * @return the mutated config JSON string, or {@code null} on error.
     */
    public static String applyVpnMode(String configJson, int localPort) {
        if (TextUtils.isEmpty(configJson)) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(configJson);
            JSONArray inbounds = root.optJSONArray("inbounds");
            if (inbounds == null) {
                inbounds = new JSONArray();
            }
            // Avoid duplicating tun inbound on restart/reconfiguration.
            boolean hasTun = false;
            for (int i = 0; i < inbounds.length(); i++) {
                JSONObject ib = inbounds.optJSONObject(i);
                if (ib != null && "tun".equalsIgnoreCase(ib.optString("protocol", ""))) {
                    hasTun = true;
                    break;
                }
            }
            if (!hasTun) {
                inbounds.put(buildTunInbound());
            }
            root.put("inbounds", inbounds);
            return root.toString(2);
        } catch (Throwable ignore) {
            return null;
        }
    }

    static JSONObject buildTunInbound() throws JSONException {
        JSONObject tun = new JSONObject();
        tun.put("protocol", "tun");
        tun.put("tag", "tun-in");
        tun.put("sniffing", new JSONObject()
                .put("enabled", true)
                .put("destOverride", new JSONArray().put("http").put("tls").put("quic")));

        JSONObject settings = new JSONObject();
        settings.put("name", "xray0");
        settings.put("mtu", 1500);
        settings.put("strictRoute", false);
        settings.put("endpointIndependentNat", true);
        settings.put("stack", "gvisor");
        settings.put("userLevel", 8);
        tun.put("settings", settings);

        return tun;
    }

    // ---- helpers ----------------------------------------------------

    private static int safePort(String port) {
        try {
            int p = Integer.parseInt(port.trim());
            if (p > 0 && p <= 65535) {
                return p;
            }
        } catch (Throwable ignore) {
        }
        return 443;
    }

    private static int safeInt(String value, int fallback) {
        if (TextUtils.isEmpty(value)) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (Throwable ignore) {
            return fallback;
        }
    }

    private static JSONObject emptyConfig(int localPort) throws Exception {
        JSONObject inbound = new JSONObject();
        inbound.put("listen", "127.0.0.1");
        inbound.put("port", localPort);
        inbound.put("protocol", "socks");
        JSONObject inboundSettings = new JSONObject();
        inboundSettings.put("udp", true);
        inbound.put("settings", inboundSettings);

        JSONObject direct = new JSONObject();
        direct.put("tag", "direct");
        direct.put("protocol", "freedom");

        JSONObject block = new JSONObject();
        block.put("tag", "block");
        block.put("protocol", "blackhole");

        JSONObject log = new JSONObject();
        log.put("loglevel", "warning");

        JSONObject dnsObj = new JSONObject();
        dnsObj.put("servers", new JSONArray()
                .put("https+local://1.1.1.1/dns-query")
                .put("localhost"));

        JSONObject root = new JSONObject();
        root.put("log", log);
        root.put("dns", dnsObj);
        root.put("inbounds", new JSONArray().put(inbound));
        root.put("outbounds", new JSONArray().put(direct).put(block));
        return root;
    }
}

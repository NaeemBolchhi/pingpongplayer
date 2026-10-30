package nb.pingpong.pong;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.dnsoverhttps.DnsOverHttps;

/**
 * HTTP client for the places where the TV fetches streams itself. Names are looked up with the TV's normal DNS
 * first. If that fails, or returns a dead address (0.0.0.0 / loopback, which is how DNS filters block a site),
 * the lookup is repeated with DNS over HTTPS (Cloudflare, then Google), so a network-level block doesn't stop playback.
 */
final class Net {
    private static OkHttpClient client;

    private Net() {
    }

    static synchronized OkHttpClient client() {
        if (client == null) {
            final List<Dns> doh = new ArrayList<>();
            try {
                OkHttpClient bootstrap = new OkHttpClient.Builder()
                        .connectTimeout(6, TimeUnit.SECONDS)
                        .readTimeout(6, TimeUnit.SECONDS)
                        .build();
                doh.add(new DnsOverHttps.Builder()
                        .client(bootstrap)
                        .url(HttpUrl.get("https://cloudflare-dns.com/dns-query"))
                        .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
                        .build());
                doh.add(new DnsOverHttps.Builder()
                        .client(bootstrap)
                        .url(HttpUrl.get("https://dns.google/dns-query"))
                        .bootstrapDnsHosts(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("8.8.4.4"))
                        .build());
            } catch (Exception ignored) {
                // no DoH available: system DNS only
            }
            Dns dns = new Dns() {
                @Override
                public List<InetAddress> lookup(String host) throws UnknownHostException {
                    try {
                        List<InetAddress> sys = Dns.SYSTEM.lookup(host);
                        if (usable(sys)) return sys;
                    } catch (UnknownHostException ignored) {
                        // try DoH below
                    }
                    for (Dns d : doh) {
                        try {
                            List<InetAddress> r = d.lookup(host);
                            if (usable(r)) return r;
                        } catch (Exception ignored) {
                            // try the next resolver
                        }
                    }
                    throw new UnknownHostException("Can't resolve " + host);
                }
            };
            client = new OkHttpClient.Builder()
                    .dns(dns)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build();
        }
        return client;
    }

    private static boolean usable(List<InetAddress> l) {
        if (l == null || l.isEmpty()) return false;
        for (InetAddress a : l) {
            if (a.isAnyLocalAddress() || a.isLoopbackAddress()) return false;
        }
        return true;
    }

    static Request.Builder request(String url, String userAgent, String referer) throws IOException {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) throw new IOException("Bad address");
        Request.Builder b = new Request.Builder().url(parsed)
                .header("User-Agent", userAgent)
                .header("Accept", "*/*");
        if (referer != null && !referer.isEmpty()) b.header("Referer", referer);
        return b;
    }
}

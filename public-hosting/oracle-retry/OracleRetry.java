import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Keeps asking Oracle Cloud for the Always Free ARM server until a slot opens, then stops.
 *
 * It talks to Oracle's API with an API signing key, so it does not depend on a browser sign-in.
 * Everything it creates is in the Always Free allowance: one VM.Standard.A1.Flex instance of the
 * size set in oracle-retry.properties, and (once) the small network that instance needs.
 *
 *   java OracleRetry.java            (reads oracle-retry.properties next to this file)
 *
 * Stop it at any time by closing its window / ending the java process, or by creating a file
 * named STOP in this folder.
 */
public class OracleRetry {
    static Properties cfg = new Properties();
    static Path dir, log;
    static PrivateKey key;
    static String keyId, region, tenancy;
    static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    public static void main(String[] args) throws Exception {
        dir = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        log = dir.resolve("oracle-retry.log");
        try (var in = Files.newBufferedReader(dir.resolve("oracle-retry.properties"))) { cfg.load(in); }
        tenancy = need("tenancy"); region = need("region");
        keyId = tenancy + "/" + need("user") + "/" + need("fingerprint");
        String pem = Files.readString(Paths.get(need("key_file")));
        byte[] der = Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", ""));
        key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        String name = cfg.getProperty("display_name", "ssm-ai-edition");
        String shape = "VM.Standard.A1.Flex"; // the Always Free ARM shape; never anything else
        int ocpus = Integer.parseInt(cfg.getProperty("ocpus", "2"));
        int memory = Integer.parseInt(cfg.getProperty("memory_gb", "12"));
        if (ocpus > 4 || memory > 24) throw new IllegalStateException("Above the Always Free allowance (4 OCPU / 24 GB)");
        long wait = Math.max(45, Long.parseLong(cfg.getProperty("interval_seconds", "75")));
        String sshKey = Files.readString(Paths.get(need("ssh_public_key_file"))).trim();

        say("Starting. Shape " + shape + " " + ocpus + " OCPU / " + memory + " GB, name " + name);
        if (existing(name) != null) { say("An instance named " + name + " already exists. Nothing to do."); finish(existing(name)); return; }
        List<String> ads = new ArrayList<>();
        for (Object ad : list(get("identity", "/20160918/availabilityDomains?compartmentId=" + enc(tenancy)))) ads.add(str(ad, "name"));
        String image = image(shape);
        String subnet = subnet();
        say("Availability domains " + ads + "; image " + image + "; subnet " + subnet);

        String body = "{\"compartmentId\":" + q(tenancy) + ",\"displayName\":" + q(name) + ",\"shape\":" + q(shape)
            + ",\"shapeConfig\":{\"ocpus\":" + ocpus + ",\"memoryInGBs\":" + memory + "}"
            + ",\"sourceDetails\":{\"sourceType\":\"image\",\"imageId\":" + q(image) + "}"
            + ",\"createVnicDetails\":{\"subnetId\":" + q(subnet) + ",\"assignPublicIp\":true}"
            + ",\"metadata\":{\"ssh_authorized_keys\":" + q(sshKey) + "},\"availabilityDomain\":";
        long attempt = 0, pause = wait;
        while (!Files.exists(dir.resolve("STOP"))) {
            String ad = ads.get((int) (attempt++ % ads.size()));
            HttpResponse<String> r;
            try { r = call("POST", "iaas", "/20160918/instances/", body + q(ad) + "}"); }
            catch (Exception e) {
                say("#" + attempt + " " + ad + ": no answer (" + e + ")");
                // The request may still have gone through: look before asking again.
                Thread.sleep(60_000);
                try { String made = existing(name); if (made != null) { finish(made); return; } } catch (Exception ignored) { }
                continue;
            }
            String text = r.body() == null ? "" : r.body();
            if (r.statusCode() == 200) { say("#" + attempt + " " + ad + ": CREATED"); finish(str(parse(text), "id")); return; }
            String message = text.replaceAll("\\s+", " ");
            if (message.length() > 160) message = message.substring(0, 160);
            say("#" + attempt + " " + ad + ": " + r.statusCode() + " " + message);
            boolean full = r.statusCode() == 500 && text.contains("apacity");
            boolean busy = r.statusCode() == 429 || r.statusCode() >= 502;
            if (!full && !busy) { say("Oracle refused for another reason. Stopping so nothing unexpected happens."); return; }
            pause = busy ? Math.min(pause * 2, 900) : wait; // back off when Oracle says "too many requests"
            Thread.sleep(pause * 1000);
        }
        say("STOP file found. Stopped.");
    }

    /** Id of a live instance with this name, or null. */
    static String existing(String name) throws Exception {
        for (Object i : list(get("iaas", "/20160918/instances?compartmentId=" + enc(tenancy) + "&displayName=" + enc(name)))) {
            String state = str(i, "lifecycleState");
            if (!state.equals("TERMINATED") && !state.equals("TERMINATING")) return str(i, "id");
        }
        return null;
    }

    /** Newest full Canonical Ubuntu 24.04 image for the shape (or the one named in the settings). */
    static String image(String shape) throws Exception {
        if (!cfg.getProperty("image", "").isBlank()) return cfg.getProperty("image").trim();
        Object images = get("iaas", "/20160918/images?compartmentId=" + enc(tenancy) + "&operatingSystem=" + enc("Canonical Ubuntu")
            + "&operatingSystemVersion=24.04&shape=" + enc(shape) + "&sortBy=TIMECREATED&sortOrder=DESC&limit=50");
        for (Object i : list(images)) if (!str(i, "displayName").contains("Minimal")) { say("Image: " + str(i, "displayName")); return str(i, "id"); }
        throw new IllegalStateException("No Canonical Ubuntu 24.04 image found for " + shape);
    }

    /** The public subnet the server goes in. Made once (network, internet gateway, route) and reused. */
    static String subnet() throws Exception {
        if (!cfg.getProperty("subnet", "").isBlank()) return cfg.getProperty("subnet").trim();
        String c = enc(tenancy);
        Object vcn = named(get("iaas", "/20160918/vcns?compartmentId=" + c), "ssm-vcn");
        if (vcn == null) {
            vcn = parse(ok(call("POST", "iaas", "/20160918/vcns", "{\"compartmentId\":" + q(tenancy)
                + ",\"displayName\":\"ssm-vcn\",\"cidrBlocks\":[\"10.0.0.0/16\"],\"dnsLabel\":\"ssmvcn\"}")));
            say("Created network ssm-vcn");
            Thread.sleep(8000);
        }
        String vcnId = str(vcn, "id");
        Object gateway = named(get("iaas", "/20160918/internetGateways?compartmentId=" + c + "&vcnId=" + enc(vcnId)), "ssm-igw");
        if (gateway == null) {
            gateway = parse(ok(call("POST", "iaas", "/20160918/internetGateways", "{\"compartmentId\":" + q(tenancy)
                + ",\"vcnId\":" + q(vcnId) + ",\"isEnabled\":true,\"displayName\":\"ssm-igw\"}")));
            say("Created internet gateway ssm-igw");
            Thread.sleep(5000);
        }
        ok(call("PUT", "iaas", "/20160918/routeTables/" + str(vcn, "defaultRouteTableId"),
            "{\"routeRules\":[{\"destination\":\"0.0.0.0/0\",\"destinationType\":\"CIDR_BLOCK\",\"networkEntityId\":" + q(str(gateway, "id")) + "}]}"));
        Object subnet = named(get("iaas", "/20160918/subnets?compartmentId=" + c + "&vcnId=" + enc(vcnId)), "ssm-subnet");
        if (subnet == null) {
            subnet = parse(ok(call("POST", "iaas", "/20160918/subnets", "{\"compartmentId\":" + q(tenancy) + ",\"vcnId\":" + q(vcnId)
                + ",\"displayName\":\"ssm-subnet\",\"cidrBlock\":\"10.0.0.0/24\",\"dnsLabel\":\"ssmsub\",\"prohibitPublicIpOnVnic\":false}")));
            say("Created public subnet ssm-subnet");
            Thread.sleep(8000);
        }
        return str(subnet, "id");
    }

    /** Waits for the new server to start, then writes its address to SUCCESS.txt. */
    static void finish(String instance) throws Exception {
        String ip = null;
        for (int i = 0; i < 60 && ip == null; i++) {
            try {
                for (Object a : list(get("iaas", "/20160918/vnicAttachments?compartmentId=" + enc(tenancy) + "&instanceId=" + enc(instance)))) {
                    Object vnic = get("iaas", "/20160918/vnics/" + str(a, "vnicId"));
                    if (vnic instanceof Map<?, ?> m && m.get("publicIp") != null) ip = m.get("publicIp").toString();
                }
            } catch (Exception e) { say("Waiting for the address: " + e.getMessage()); }
            if (ip == null) Thread.sleep(10_000);
        }
        String text = "Oracle server created " + LocalDateTime.now() + "\ninstance=" + instance + "\npublic_ip=" + (ip == null ? "(not assigned yet; see the Oracle console)" : ip) + "\n";
        Files.writeString(dir.resolve("SUCCESS.txt"), text);
        say("DONE. " + text.replace("\n", " | "));
    }

    // ------------------------------------------------------------------ Oracle API plumbing

    static Object get(String service, String path) throws Exception { return parse(ok(call("GET", service, path, null))); }

    static String ok(HttpResponse<String> r) {
        if (r.statusCode() / 100 != 2) throw new IllegalStateException("Oracle answered " + r.statusCode() + ": " + r.body());
        return r.body();
    }

    /** One signed request (Oracle's "Signature version 1" scheme). */
    static HttpResponse<String> call(String method, String service, String path, String body) throws Exception {
        String host = service + "." + region + ".oraclecloud.com";
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC)).replace("+0000", "GMT");
        if (!date.endsWith("GMT")) date = date.substring(0, date.lastIndexOf(' ')) + " GMT";
        StringBuilder signing = new StringBuilder("(request-target): " + method.toLowerCase() + " " + path + "\ndate: " + date + "\nhost: " + host);
        String headers = "(request-target) date host";
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://" + host + path)).timeout(Duration.ofSeconds(90)).header("date", date);
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String sha = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
            signing.append("\nx-content-sha256: ").append(sha).append("\ncontent-type: application/json\ncontent-length: ").append(bytes.length);
            headers += " x-content-sha256 content-type content-length";
            request.header("x-content-sha256", sha).header("content-type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(bytes));
        } else request.method(method, HttpRequest.BodyPublishers.noBody());
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update(signing.toString().getBytes(StandardCharsets.UTF_8));
        request.header("authorization", "Signature version=\"1\",keyId=\"" + keyId + "\",algorithm=\"rsa-sha256\",headers=\"" + headers
            + "\",signature=\"" + Base64.getEncoder().encodeToString(signer.sign()) + "\"");
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String need(String name) {
        String v = cfg.getProperty(name, "").trim();
        if (v.isEmpty()) throw new IllegalStateException("oracle-retry.properties is missing " + name);
        return v;
    }

    static void say(String line) throws Exception {
        String out = LocalDateTime.now().withNano(0) + " " + line;
        System.out.println(out);
        Files.writeString(log, out + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }
    static String q(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""; }
    static List<?> list(Object o) { return o instanceof List<?> l ? l : List.of(); }
    static String str(Object o, String field) { Object v = ((Map<?, ?>) o).get(field); return v == null ? "" : v.toString(); }
    static Object named(Object items, String name) {
        for (Object i : list(items)) if (str(i, "displayName").equals(name) && !str(i, "lifecycleState").startsWith("TERMINAT")) return i;
        return null;
    }

    // ------------------------------------------------------------------ a small JSON reader

    static Object parse(String json) { return new Json(json).value(); }

    static class Json {
        final String s; int i = 0;
        Json(String s) { this.s = s; }
        void space() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object value() {
            space();
            char c = s.charAt(i);
            if (c == '{') {
                Map<String, Object> map = new LinkedHashMap<>(); i++; space();
                if (s.charAt(i) == '}') { i++; return map; }
                while (true) {
                    space(); String k = string(); space(); i++; // the colon
                    map.put(k, value()); space();
                    if (s.charAt(i++) == '}') return map;
                }
            }
            if (c == '[') {
                List<Object> out = new ArrayList<>(); i++; space();
                if (s.charAt(i) == ']') { i++; return out; }
                while (true) { out.add(value()); space(); if (s.charAt(i++) == ']') return out; }
            }
            if (c == '"') return string();
            int start = i;
            while (i < s.length() && ",}] \n\r\t".indexOf(s.charAt(i)) < 0) i++;
            String word = s.substring(start, i);
            return word.equals("null") ? null : word.equals("true") ? Boolean.TRUE : word.equals("false") ? Boolean.FALSE : word;
        }
        String string() {
            StringBuilder b = new StringBuilder(); i++;
            while (s.charAt(i) != '"') {
                char c = s.charAt(i++);
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n'); case 't' -> b.append('\t'); case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b'); case 'f' -> b.append('\f');
                    case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> b.append(e);
                }
            }
            i++;
            return b.toString();
        }
    }
}

package open.repocraft.server.infra.git.service;

import java.net.URI;
import java.net.URISyntaxException;

public record GitEndpoint(
        GitTransport transport,
        String user,
        String host,
        String port,
        String path
) {
    public static GitEndpoint parse(String raw) throws URISyntaxException {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Empty endpoint");
        }

        // Local path: no scheme/host hints, keep as-is.
        if (!raw.contains("://") && !raw.contains("@") && !raw.contains(":")) {
            return new GitEndpoint(GitTransport.LOCAL, null, null, null, raw);
        }

        // scp-like syntax: [user@]host:path
        if (!raw.contains("://") && raw.contains(":")) {
            int colon = raw.indexOf(':');
            String userHost = raw.substring(0, colon);
            String repoPath = raw.substring(colon + 1);
            String user = null;
            String host = userHost;
            int at = userHost.indexOf('@');
            if (at >= 0) {
                user = userHost.substring(0, at);
                host = userHost.substring(at + 1);
            }
            return new GitEndpoint(GitTransport.SSH, user, host, null, ensureLeadingSlash(repoPath));
        }

        URI uri = new URI(raw);
        GitTransport transport = switch (uri.getScheme().toLowerCase()) {
            case "ssh" -> GitTransport.SSH;
            case "git" -> GitTransport.GIT;
            case "http" -> GitTransport.HTTP;
            case "https" -> GitTransport.HTTPS;
            default -> throw new IllegalArgumentException("Unsupported transport: " + uri.getScheme());
        };

        String user = uri.getUserInfo();
        if (user != null && user.contains(":")) {
            user = user.substring(0, user.indexOf(':'));
        }

        return new GitEndpoint(
                transport,
                user,
                uri.getHost(),
                uri.getPort() == -1 ? null : Integer.toString(uri.getPort()),
                ensureLeadingSlash(uri.getPath())
        );
    }

    private static String ensureLeadingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "/";
        }
        return value.startsWith("/") ? value : "/" + value;
    }
}

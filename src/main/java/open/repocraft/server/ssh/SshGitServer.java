package open.repocraft.server.ssh;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;

import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.pubkey.AuthorizedKeysAuthenticator;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;

import open.repocraft.server.infra.git.service.GitCommandParser;
import open.repocraft.server.infra.git.service.GitRequest;
import open.repocraft.server.infra.git.service.ProcessGitCommandExecutor;

public final class SshGitServer {
    private SshGitServer() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.fromArgs(args);
        SshServer sshd = createServer(config);
        startAndAwait(sshd, config);
    }

    public static SshServer createServer(Config config) throws IOException {
        Objects.requireNonNull(config, "config");
        ProcessGitCommandExecutor executor = new ProcessGitCommandExecutor(
                config.uploadPackPath,
                config.receivePackPath,
                Map.of(),
                Path.of(config.repoRoot)
        );

        ensureParentDirectories(Path.of(config.hostKeyPath));
        ensureParentDirectories(Path.of(config.authorizedKeysPath));
        ensureParentDirectories(Path.of(config.repoRoot));

        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setHost(config.listenHost);
        sshd.setPort(config.listenPort);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(Path.of(config.hostKeyPath)));
        sshd.setPublickeyAuthenticator(new AuthorizedKeysAuthenticator(Path.of(config.authorizedKeysPath)));
        sshd.setPasswordAuthenticator(null);
        sshd.setKeyboardInteractiveAuthenticator(null);
        sshd.setShellFactory(null);
        sshd.setSubsystemFactories(Collections.emptyList());
        sshd.setCommandFactory(new GitCommandFactory(executor, Path.of(config.repoRoot)));
        return sshd;
    }

    public static void startAndAwait(SshServer sshd, Config config) throws IOException, InterruptedException {
        sshd.start();
        System.out.printf("Serving Git SSH on %s:%d (repos under %s)%n",
                config.listenHost.isBlank() ? "0.0.0.0" : config.listenHost,
                config.listenPort,
                config.repoRoot);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                sshd.stop();
            } catch (IOException ignored) {
                // Best effort stop.
            }
        }));

        Thread.currentThread().join();
    }

    public static record Config(String listenHost,
                                int listenPort,
                                String repoRoot,
                                String hostKeyPath,
                                String authorizedKeysPath,
                                String uploadPackPath,
                                String receivePackPath) {
        static Config fromArgs(String[] args) {
            String listen = argOr(args, "--listen", ":2222");
            String repoRoot = argOr(args, "--repo-root", "./repositories");
            String hostKey = argOr(args, "--host-key", "./ssh/hostkey");
            String authorized = argOr(args, "--authorized-keys", "./ssh/authorized_keys");
            String uploadPack = argOr(args, "--upload-pack", null);
            String receivePack = argOr(args, "--receive-pack", null);

            ListenAddress parsed = ListenAddress.parse(listen);
            return new Config(
                    parsed.host,
                    parsed.port,
                    repoRoot,
                    hostKey,
                    authorized,
                    uploadPack,
                    receivePack
            );
        }
    }

    private record ListenAddress(String host, int port) {
        static ListenAddress parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return new ListenAddress("", 2222);
            }
            String value = raw.trim();
            if (!value.contains(":")) {
                return new ListenAddress(value, 2222);
            }
            int idx = value.lastIndexOf(':');
            String host = value.substring(0, idx);
            String portPart = value.substring(idx + 1);
            int port = portPart.isBlank() ? 2222 : Integer.parseInt(portPart);
            return new ListenAddress(host, port);
        }
    }

    private static String argOr(String[] args, String key, String fallback) {
        if (args == null) {
            return fallback;
        }
        for (int i = 0; i < args.length; i++) {
            if (key.equals(args[i]) && i+1 < args.length) {
                return args[i+1];
            }
        }
        return fallback;
    }

    private static void ensureParentDirectories(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static final class GitCommandFactory implements CommandFactory {
        private final ProcessGitCommandExecutor executor;
        private final Path repoRoot;

        GitCommandFactory(ProcessGitCommandExecutor executor, Path repoRoot) {
            this.executor = Objects.requireNonNull(executor, "executor");
            this.repoRoot = repoRoot.toAbsolutePath().normalize();
        }

        @Override
        public Command createCommand(String command) {
            return new GitServiceCommand(command, executor, repoRoot);
        }
    }

    private static final class GitServiceCommand implements Command, Runnable {
        private final String rawCommand;
        private final ProcessGitCommandExecutor executor;
        private final Path repoRoot;

        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private org.apache.sshd.server.Environment environment;
        private org.apache.sshd.server.ExitCallback exitCallback;
        private Thread thread;

        GitServiceCommand(String rawCommand, ProcessGitCommandExecutor executor, Path repoRoot) {
            this.rawCommand = rawCommand;
            this.executor = executor;
            this.repoRoot = repoRoot;
        }

        @Override
        public void setInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public void setOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {
            this.err = err;
        }

        @Override
        public void setExitCallback(org.apache.sshd.server.ExitCallback callback) {
            this.exitCallback = callback;
        }

        @Override
        public void start(org.apache.sshd.server.Environment env) throws IOException {
            this.environment = env;
            this.thread = new Thread(this, "repocraft-git-command");
            this.thread.start();
        }

        @Override
        public void destroy() throws Exception {
            if (thread != null) {
                thread.interrupt();
            }
        }

        @Override
        public void run() {
            try {
                GitRequest parsed = GitCommandParser.parse(rawCommand);
                Path repoPath = resolveRepoPath(repoRoot, parsed.repoPath());
                if (!Files.exists(repoPath)) {
                    throw new IOException("repository not found");
                }

                String protocol = protocolFromEnv(environment);
                GitRequest execReq = new GitRequest(parsed.service(), repoPath.toString(), protocol);

                executor.execute(execReq, in, out, err);
                if (exitCallback != null) {
                    exitCallback.onExit(0);
                }
            } catch (Exception e) {
                writeError(err, e.getMessage());
                if (exitCallback != null) {
                    exitCallback.onExit(1, e.getMessage());
                }
            }
        }

        private static Path resolveRepoPath(Path root, String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("empty repo path");
            }
            String cleaned = raw.trim();
            if (cleaned.startsWith("'") || cleaned.startsWith("\"")) {
                cleaned = cleaned.substring(1, cleaned.length() - 1);
            }
            while (cleaned.startsWith("/")) {
                cleaned = cleaned.substring(1);
            }
            Path normalizedRoot = root.normalize();
            Path candidate = normalizedRoot.resolve(cleaned).normalize();
            if (!candidate.startsWith(normalizedRoot)) {
                throw new IllegalArgumentException("path traversal detected");
            }
            return candidate;
        }

        private static String protocolFromEnv(org.apache.sshd.server.Environment env) {
            if (env == null) {
                return null;
            }
            return env.getEnv().get("GIT_PROTOCOL");
        }

        private static void writeError(OutputStream err, String message) {
            if (err == null || message == null) {
                return;
            }
            try {
                err.write(("ERROR: " + message + "\n").getBytes(StandardCharsets.UTF_8));
                err.flush();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}

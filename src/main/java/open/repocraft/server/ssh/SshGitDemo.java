package open.repocraft.server.ssh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.sshd.server.SshServer;

public final class SshGitDemo {
    private SshGitDemo() {
    }

    public static void main(String[] args) throws Exception {
        Path base = Path.of("demo-java-data");
        Path repoRoot = base.resolve("repositories");
        Path sshDir = base.resolve("ssh");
        Path hostKey = sshDir.resolve("hostkey");
        Path userKey = sshDir.resolve("userkey");
        Path authorized = sshDir.resolve("authorized_keys");

        Files.createDirectories(repoRoot);
        Files.createDirectories(sshDir);

        requireExecutable("ssh-keygen");
        ensureKey(userKey);
        writeAuthorized(userKey.resolveSibling(userKey.getFileName() + ".pub"), authorized);
        maybeInitRepo(repoRoot.resolve("demo/hello.git"));

        SshGitServer.Config config = new SshGitServer.Config(
                "",
                2222,
                repoRoot.toString(),
                hostKey.toString(),
                authorized.toString(),
                null,
                null
        );

        SshServer sshd = SshGitServer.createServer(config);
        SshGitServer.startAndAwait(sshd, config);
    }

    private static void ensureKey(Path keyPath) throws IOException, InterruptedException {
        if (Files.exists(keyPath)) {
            return;
        }
        Process process = new ProcessBuilder(
                "ssh-keygen", "-t", "ed25519", "-f", keyPath.toString(), "-N", "", "-C", "repocraft-demo-user"
        ).inheritIO().start();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("ssh-keygen failed with code " + exit);
        }
    }

    private static void writeAuthorized(Path pub, Path authorized) throws IOException {
        byte[] data = Files.readAllBytes(pub);
        Files.writeString(authorized, new String(data));
    }

    private static void maybeInitRepo(Path repo) throws IOException, InterruptedException {
        if (Files.exists(repo.resolve("HEAD"))) {
            return;
        }
        Files.createDirectories(repo);
        Process process = new ProcessBuilder(
                "git", "init", "--bare", repo.toString()
        ).inheritIO().start();
        int exit = process.waitFor();
        if (exit != 0) {
            System.err.println("skip repo init (git missing?)");
        }
    }

    private static void requireExecutable(String name) {
        ProcessBuilder pb = new ProcessBuilder(name, "-h");
        try {
            Process p = pb.start();
            p.waitFor();
        } catch (IOException e) {
            throw new IllegalStateException(name + " is required for the demo", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(name + " check interrupted", e);
        }
    }
}
